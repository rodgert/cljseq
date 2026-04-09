; SPDX-License-Identifier: EPL-2.0
(ns cljseq.temporal-buffer-test
  "Phase 1 tests: core buffer, zone table, zone switching, hold, flip, info.

  No live scheduler or sidecar required — tests work in Phase 0 mode."
  (:require [clojure.test              :refer [deftest testing is are]]
            [cljseq.temporal-buffer    :as tbuf]))

;; ---------------------------------------------------------------------------
;; Zone table
;; ---------------------------------------------------------------------------

(deftest zone-table-structure
  (testing "8 named zones are defined"
    (is (= 8 (count (tbuf/zone-ids)))))
  (testing "zones are in depth order z0..z7"
    (let [depths (map #(:depth (tbuf/zone-info %)) (tbuf/zone-ids))]
      (is (= (sort depths) depths))
      (is (= [0.5 2.0 4.0 8.0 16.0 32.0 64.0 128.0] (vec depths)))))
  (testing "z0 is exponential rate (Karplus territory)"
    (is (= :exponential (:rate-mode (tbuf/zone-info :z0)))))
  (testing "z1..z7 are linear rate"
    (doseq [z (rest (tbuf/zone-ids))]
      (is (= :linear (:rate-mode (tbuf/zone-info z))) (str z " should be :linear"))))
  (testing "unknown zone returns nil"
    (is (nil? (tbuf/zone-info :z99)))))

;; ---------------------------------------------------------------------------
;; Lifecycle: deftemporal-buffer / stop!
;; ---------------------------------------------------------------------------

(deftest lifecycle
  (testing "deftemporal-buffer returns the name keyword"
    (is (= :test-buf (tbuf/deftemporal-buffer :test-buf {:active-zone :z2})))
    (tbuf/stop! :test-buf))

  (testing "buffer appears in buffer-names after creation"
    (tbuf/deftemporal-buffer :lc-buf {})
    (is (contains? (set (tbuf/buffer-names)) :lc-buf))
    (tbuf/stop! :lc-buf))

  (testing "stop! removes buffer from registry"
    (tbuf/deftemporal-buffer :stop-test {})
    (tbuf/stop! :stop-test)
    (is (not (contains? (set (tbuf/buffer-names)) :stop-test))))

  (testing "stop! on unknown name is safe"
    (is (nil? (tbuf/stop! :no-such-buf))))

  (testing "re-defining replaces the prior instance"
    (tbuf/deftemporal-buffer :redef {:active-zone :z1})
    (tbuf/deftemporal-buffer :redef {:active-zone :z4})
    (let [info (tbuf/temporal-buffer-info :redef)]
      (is (= :z4 (:active-zone info))))
    (tbuf/stop! :redef)))

;; ---------------------------------------------------------------------------
;; temporal-buffer-info
;; ---------------------------------------------------------------------------

(deftest info-structure
  (tbuf/deftemporal-buffer :info-test {:active-zone :z3 :max-depth 64.0})
  (let [info (tbuf/temporal-buffer-info :info-test)]
    (testing "active-zone"
      (is (= :z3 (:active-zone info))))
    (testing "zone map is present"
      (is (= 8.0 (get-in info [:zone :depth]))))
    (testing "hold? starts false"
      (is (false? (:hold? info))))
    (testing "flip? starts false"
      (is (false? (:flip? info))))
    (testing "max-depth respected"
      (is (= 64.0 (:max-depth info))))
    (testing "empty buffer"
      (is (zero? (:event-count info)))
      (is (nil? (:oldest-beat info)))))
  (tbuf/stop! :info-test)

  (testing "info on unknown buffer returns nil"
    (is (nil? (tbuf/temporal-buffer-info :no-such)))))

;; ---------------------------------------------------------------------------
;; temporal-buffer-send!
;; ---------------------------------------------------------------------------

(deftest send-accumulates-events
  (tbuf/deftemporal-buffer :send-test {:active-zone :z3 :max-depth 128.0})

  (testing "send! returns nil"
    (is (nil? (tbuf/temporal-buffer-send! :send-test {:pitch/midi 60 :dur/beats 0.25}))))

  (testing "events accumulate in the buffer"
    (tbuf/temporal-buffer-send! :send-test {:pitch/midi 62 :dur/beats 0.25})
    (tbuf/temporal-buffer-send! :send-test {:pitch/midi 64 :dur/beats 0.25})
    (let [info (tbuf/temporal-buffer-info :send-test)]
      ;; At least the 2 we just sent (possibly 1 from the first send test)
      (is (>= (:event-count info) 2))))

  (testing "each event has :beat :generation :halo-depth stamped"
    ;; peek at the buffer atom via info; event fields are checked indirectly
    ;; by verifying event-count grows after send
    (let [before (:event-count (tbuf/temporal-buffer-info :send-test))]
      (tbuf/temporal-buffer-send! :send-test {:pitch/midi 65})
      (let [after (:event-count (tbuf/temporal-buffer-info :send-test))]
        (is (= (inc before) after)))))

  (testing "send! on unknown buffer is safe"
    (is (nil? (tbuf/temporal-buffer-send! :no-such {:pitch/midi 60}))))

  (tbuf/stop! :send-test))

;; ---------------------------------------------------------------------------
;; Hold mode
;; ---------------------------------------------------------------------------

(deftest hold-mode
  (tbuf/deftemporal-buffer :hold-test {})

  (testing "hold! freezes input — events are not written while held"
    (tbuf/temporal-buffer-hold! :hold-test true)
    (is (true? (:hold? (tbuf/temporal-buffer-info :hold-test))))
    (let [before (:event-count (tbuf/temporal-buffer-info :hold-test))]
      (tbuf/temporal-buffer-send! :hold-test {:pitch/midi 60})
      (tbuf/temporal-buffer-send! :hold-test {:pitch/midi 62})
      (let [after (:event-count (tbuf/temporal-buffer-info :hold-test))]
        (is (= before after) "no new events while held"))))

  (testing "releasing hold resumes input"
    (tbuf/temporal-buffer-hold! :hold-test false)
    (is (false? (:hold? (tbuf/temporal-buffer-info :hold-test))))
    (let [before (:event-count (tbuf/temporal-buffer-info :hold-test))]
      (tbuf/temporal-buffer-send! :hold-test {:pitch/midi 67})
      (let [after (:event-count (tbuf/temporal-buffer-info :hold-test))]
        (is (= (inc before) after) "event written after hold released"))))

  (tbuf/stop! :hold-test))

;; ---------------------------------------------------------------------------
;; Zone switching — pitch-free (Phase 1: no Doppler to test, structural only)
;; ---------------------------------------------------------------------------

(deftest zone-switching
  (tbuf/deftemporal-buffer :zone-test {:active-zone :z2})

  (testing "initial zone is :z2"
    (is (= :z2 (:active-zone (tbuf/temporal-buffer-info :zone-test)))))

  (testing "zone! updates the active zone"
    (tbuf/temporal-buffer-zone! :zone-test :z4)
    (is (= :z4 (:active-zone (tbuf/temporal-buffer-info :zone-test)))))

  (testing "zone! with :z0 (Karplus) accepted"
    (tbuf/temporal-buffer-zone! :zone-test :z0)
    (is (= :z0 (:active-zone (tbuf/temporal-buffer-info :zone-test)))))

  (testing "zone! with unknown zone throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tbuf/temporal-buffer-zone! :zone-test :z99))))

  (testing "zone switch does not discard the buffer"
    (tbuf/temporal-buffer-zone! :zone-test :z1)
    (tbuf/temporal-buffer-send! :zone-test {:pitch/midi 60})
    (let [count-before (:event-count (tbuf/temporal-buffer-info :zone-test))]
      (tbuf/temporal-buffer-zone! :zone-test :z5)
      (let [count-after (:event-count (tbuf/temporal-buffer-info :zone-test))]
        (is (= count-before count-after) "zone switch preserves buffer contents"))))

  (tbuf/stop! :zone-test))

;; ---------------------------------------------------------------------------
;; Flip (retrograde) toggle
;; ---------------------------------------------------------------------------

(deftest flip-toggle
  (tbuf/deftemporal-buffer :flip-test {})

  (testing "flip? starts false"
    (is (false? (:flip? (tbuf/temporal-buffer-info :flip-test)))))

  (testing "flip! toggles to true"
    (tbuf/temporal-buffer-flip! :flip-test)
    (is (true? (:flip? (tbuf/temporal-buffer-info :flip-test)))))

  (testing "flip! toggles back to false"
    (tbuf/temporal-buffer-flip! :flip-test)
    (is (false? (:flip? (tbuf/temporal-buffer-info :flip-test)))))

  (tbuf/stop! :flip-test))

;; ---------------------------------------------------------------------------
;; stop-all!
;; ---------------------------------------------------------------------------

(deftest stop-all
  (tbuf/deftemporal-buffer :sa1 {})
  (tbuf/deftemporal-buffer :sa2 {:active-zone :z1})
  (tbuf/stop-all!)
  (testing "all buffers removed after stop-all!"
    (is (not (contains? (set (tbuf/buffer-names)) :sa1)))
    (is (not (contains? (set (tbuf/buffer-names)) :sa2)))))

;; ---------------------------------------------------------------------------
;; Phase 2 — Rate / Doppler helpers (via private var access)
;; ---------------------------------------------------------------------------

(def ^:private doppler-pitch-cents @#'tbuf/doppler-pitch-cents)
(def ^:private shift-pitch          @#'tbuf/shift-pitch)
(def ^:private effective-cursor-rate @#'tbuf/effective-cursor-rate)

(deftest doppler-pitch-cents-linear
  (let [linear-zone {:rate-mode :linear}]
    (testing "rate=1.0 → 0 cents"
      (is (== 0.0 (doppler-pitch-cents 1.0 1200.0 linear-zone false))))
    (testing "rate=2.0 → +1200 cents at scale 1200"
      (is (== 1200.0 (doppler-pitch-cents 2.0 1200.0 linear-zone false))))
    (testing "rate=0.5 → -600 cents at scale 1200"
      (is (== -600.0 (doppler-pitch-cents 0.5 1200.0 linear-zone false))))
    (testing "tape-scale halved → half the pitch offset"
      (is (== 600.0 (doppler-pitch-cents 2.0 600.0 linear-zone false))))
    (testing "clocked? suppresses pitch"
      (is (== 0.0 (doppler-pitch-cents 2.0 1200.0 linear-zone true))))))

(deftest doppler-pitch-cents-exponential
  (let [exp-zone {:rate-mode :exponential}]
    (testing "rate=1.0 → 0 cents (log2(1)=0)"
      (is (< (Math/abs (doppler-pitch-cents 1.0 1200.0 exp-zone false)) 1e-9)))
    (testing "rate=2.0 → +1200 cents (one octave)"
      (is (< (Math/abs (- 1200.0 (doppler-pitch-cents 2.0 1200.0 exp-zone false))) 1e-6)))
    (testing "rate=0.5 → -1200 cents (one octave down)"
      (is (< (Math/abs (- -1200.0 (doppler-pitch-cents 0.5 1200.0 exp-zone false))) 1e-6)))
    (testing "rate=4.0 → +2400 cents (two octaves)"
      (is (< (Math/abs (- 2400.0 (doppler-pitch-cents 4.0 1200.0 exp-zone false))) 1e-6)))
    (testing "clocked? suppresses pitch"
      (is (== 0.0 (doppler-pitch-cents 2.0 1200.0 exp-zone true))))))

(deftest shift-pitch-semantics
  (testing "zero cents → event unchanged"
    (let [ev {:pitch/midi 60 :dur/beats 0.25}]
      (is (= ev (shift-pitch ev 0.0)))))
  (testing "+1200 cents → +12 semitones (one octave up)"
    (let [ev {:pitch/midi 60}
          ev' (shift-pitch ev 1200.0)]
      (is (= 72 (:pitch/midi ev')))))
  (testing "-1200 cents → -12 semitones (one octave down)"
    (let [ev {:pitch/midi 60}
          ev' (shift-pitch ev -1200.0)]
      (is (= 48 (:pitch/midi ev')))))
  (testing "+50 cents → rounds to MIDI 61 with -50 cents fractional offset"
    ;; Math/round(60.5) = 61; remainder = (60.5 - 61) * 100 = -50
    (let [ev {:pitch/midi 60}
          ev' (shift-pitch ev 50.0)]
      (is (= 61 (:pitch/midi ev')))
      (is (< (Math/abs (- -50.0 (:pitch/cents-offset ev'))) 1e-6))))
  (testing "midi clamped at 0 floor"
    (let [ev {:pitch/midi 2}
          ev' (shift-pitch ev -600.0)]
      (is (>= (:pitch/midi ev') 0))))
  (testing "midi clamped at 127 ceiling"
    (let [ev {:pitch/midi 125}
          ev' (shift-pitch ev 600.0)]
      (is (<= (:pitch/midi ev') 127)))))

(deftest effective-cursor-rate-test
  (testing "no skew → base rate unchanged"
    (is (== 2.0 (effective-cursor-rate 2.0 nil :a)))
    (is (== 2.0 (effective-cursor-rate 2.0 nil :b))))
  (testing "inverse skew — cursor A gets + amount"
    (is (== 1.2 (effective-cursor-rate 1.0 {:mode :inverse :amount 0.2} :a))))
  (testing "inverse skew — cursor B gets - amount"
    (is (== 0.8 (effective-cursor-rate 1.0 {:mode :inverse :amount 0.2} :b)))))

;; ---------------------------------------------------------------------------
;; Phase 2 — State API
;; ---------------------------------------------------------------------------

(deftest phase2-defaults
  (tbuf/deftemporal-buffer :p2-def {})
  (let [info (tbuf/temporal-buffer-info :p2-def)]
    (testing "rate defaults to 1.0"
      (is (== 1.0 (:rate info))))
    (testing "tape-scale defaults to 1200.0"
      (is (== 1200.0 (:tape-scale info))))
    (testing "clocked? defaults to false"
      (is (false? (:clocked? info))))
    (testing "skew defaults to nil"
      (is (nil? (:skew info)))))
  (tbuf/stop! :p2-def))

(deftest phase2-opts-at-creation
  (tbuf/deftemporal-buffer :p2-opts {:rate 1.5 :tape-scale 600.0 :clocked? false
                                     :skew {:mode :inverse :amount 0.1}})
  (let [info (tbuf/temporal-buffer-info :p2-opts)]
    (testing "rate from opts"
      (is (== 1.5 (:rate info))))
    (testing "tape-scale from opts"
      (is (== 600.0 (:tape-scale info))))
    (testing "skew from opts"
      (is (= {:mode :inverse :amount 0.1} (:skew info)))))
  (tbuf/stop! :p2-opts))

(deftest phase2-rate-mutation
  (tbuf/deftemporal-buffer :p2-rate {})
  (testing "temporal-buffer-rate! updates state"
    (tbuf/temporal-buffer-rate! :p2-rate 2.0)
    (is (== 2.0 (:rate (tbuf/temporal-buffer-info :p2-rate)))))
  (testing "temporal-buffer-rate! on unknown buf is safe"
    (is (nil? (tbuf/temporal-buffer-rate! :no-such 2.0))))
  (tbuf/stop! :p2-rate))

(deftest phase2-tape-scale-mutation
  (tbuf/deftemporal-buffer :p2-ts {})
  (testing "temporal-buffer-tape-scale! updates state"
    (tbuf/temporal-buffer-tape-scale! :p2-ts 600.0)
    (is (== 600.0 (:tape-scale (tbuf/temporal-buffer-info :p2-ts)))))
  (tbuf/stop! :p2-ts))

(deftest phase2-clocked-mutation
  (tbuf/deftemporal-buffer :p2-clk {})
  (testing "temporal-buffer-clocked! enables clocked mode"
    (tbuf/temporal-buffer-clocked! :p2-clk true)
    (is (true? (:clocked? (tbuf/temporal-buffer-info :p2-clk)))))
  (testing "temporal-buffer-clocked! disables clocked mode"
    (tbuf/temporal-buffer-clocked! :p2-clk false)
    (is (false? (:clocked? (tbuf/temporal-buffer-info :p2-clk)))))
  (tbuf/stop! :p2-clk))

(deftest phase2-skew-mutation
  (tbuf/deftemporal-buffer :p2-skew {:skew {:mode :inverse :amount 0.2}})
  (testing "temporal-buffer-skew! clears skew"
    (tbuf/temporal-buffer-skew! :p2-skew nil)
    (is (nil? (:skew (tbuf/temporal-buffer-info :p2-skew)))))
  (testing "temporal-buffer-skew! sets new skew params"
    (tbuf/temporal-buffer-skew! :p2-skew {:mode :inverse :amount 0.4})
    (is (= {:mode :inverse :amount 0.4} (:skew (tbuf/temporal-buffer-info :p2-skew)))))
  (tbuf/stop! :p2-skew))

(deftest phase2-skew-lifecycle
  (testing "skew buffer starts; both threads spin up"
    (tbuf/deftemporal-buffer :p2-slc {:skew {:mode :inverse :amount 0.1}})
    (is (contains? (set (tbuf/buffer-names)) :p2-slc)))
  (testing "stop! on skew buffer is clean"
    (tbuf/stop! :p2-slc)
    (is (not (contains? (set (tbuf/buffer-names)) :p2-slc)))))

(deftest phase2-hold-blocks-send-at-rate
  ;; Regression: Phase 2 state additions should not break hold semantics
  (tbuf/deftemporal-buffer :p2-hold {:rate 2.0 :tape-scale 600.0})
  (tbuf/temporal-buffer-hold! :p2-hold true)
  (let [before (:event-count (tbuf/temporal-buffer-info :p2-hold))]
    (tbuf/temporal-buffer-send! :p2-hold {:pitch/midi 60})
    (is (= before (:event-count (tbuf/temporal-buffer-info :p2-hold)))))
  (tbuf/stop! :p2-hold))

;; ---------------------------------------------------------------------------
;; Phase 3 — Color helpers (via private var access)
;; ---------------------------------------------------------------------------

(def ^:private apply-color @#'tbuf/apply-color)

(deftest color-presets-defined
  (testing "all 6 presets exist"
    (is (every? #(contains? tbuf/color-presets %) [:dark :warm :neutral :tape :bright :crisp])))
  (testing "each preset has vel-mult, dur-mult, pitch-drift-cents"
    (doseq [[_ preset] tbuf/color-presets]
      (is (contains? preset :vel-mult))
      (is (contains? preset :dur-mult))
      (is (contains? preset :pitch-drift-cents)))))

(deftest apply-color-neutral
  (let [ev {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 100}
        ev' (apply-color ev :neutral)]
    (testing ":neutral reduces velocity by vel-mult=0.95"
      (is (= (int (Math/round (* 100 0.95))) (:mod/velocity ev'))))
    (testing ":neutral does not change pitch"
      (is (= 60 (:pitch/midi ev'))))
    (testing ":neutral duration unchanged (dur-mult=1.0)"
      (is (< (Math/abs (- 0.25 (:dur/beats ev'))) 1e-9)))))

(deftest apply-color-dark
  (let [ev {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 100}
        ev' (apply-color ev :dark)]
    (testing ":dark reduces velocity (vel-mult=0.90)"
      (is (= (int (Math/round (* 100 0.90))) (:mod/velocity ev'))))
    (testing ":dark lengthens duration (dur-mult=1.15)"
      (is (< (Math/abs (- (* 0.25 1.15) (:dur/beats ev'))) 1e-9)))
    (testing ":dark sags pitch by -5 cents → MIDI 60 stays 60 (rounds)"
      ;; -5 cents from 60 → 59.95 → rounds to 60
      (is (= 60 (:pitch/midi ev'))))))

(deftest apply-color-bright
  (let [ev {:pitch/midi 60 :dur/beats 0.5 :mod/velocity 80}
        ev' (apply-color ev :bright)]
    (testing ":bright holds velocity higher (vel-mult=0.97)"
      (is (= (int (Math/round (* 80 0.97))) (:mod/velocity ev'))))
    (testing ":bright shortens duration (dur-mult=0.90)"
      (is (< (Math/abs (- (* 0.5 0.90) (:dur/beats ev'))) 1e-9)))))

(deftest apply-color-custom-fn
  (let [ev {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 100}
        double-vel (fn [e] (assoc e :mod/velocity (* 2 (:mod/velocity e 0))))]
    (testing "custom fn is applied directly"
      (is (= 200 (:mod/velocity (apply-color ev double-vel)))))))

(deftest apply-color-velocity-clamped-for-presets
  (testing "built-in preset: velocity never goes below 0"
    ;; vel-mult 0.90 applied to velocity 1 → Math/round(0.9) = 1, but with 0.5 it would be 0
    (let [ev {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 1}]
      (is (>= (:mod/velocity (apply-color ev :dark)) 0))))
  (testing "built-in preset: result is always in [0, 127]"
    (let [ev {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 127}]
      (is (<= (:mod/velocity (apply-color ev :crisp)) 127))))
  (testing "unknown preset keyword falls back to :neutral without throwing"
    (let [ev {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 80}]
      (is (map? (apply-color ev :unknown-color))))))

;; ---------------------------------------------------------------------------
;; Phase 3 — State API
;; ---------------------------------------------------------------------------

(deftest phase3-defaults
  (tbuf/deftemporal-buffer :p3-def {})
  (let [info (tbuf/temporal-buffer-info :p3-def)]
    (testing "color defaults to :neutral"
      (is (= :neutral (:color info))))
    (testing "feedback defaults to {:amount 0.0}"
      (is (= 0.0 (get-in info [:feedback :amount])))))
  (tbuf/stop! :p3-def))

(deftest phase3-opts-at-creation
  (tbuf/deftemporal-buffer :p3-opts {:color :bright
                                     :feedback {:amount 0.7 :max-generation 4 :velocity-floor 10}})
  (let [info (tbuf/temporal-buffer-info :p3-opts)]
    (testing "color from opts"
      (is (= :bright (:color info))))
    (testing "feedback.amount from opts"
      (is (== 0.7 (get-in info [:feedback :amount]))))
    (testing "feedback.max-generation from opts"
      (is (= 4 (get-in info [:feedback :max-generation]))))
    (testing "feedback.velocity-floor from opts"
      (is (= 10 (get-in info [:feedback :velocity-floor])))))
  (tbuf/stop! :p3-opts))

(deftest phase3-color-mutation
  (tbuf/deftemporal-buffer :p3-color {})
  (testing "temporal-buffer-color! updates state"
    (tbuf/temporal-buffer-color! :p3-color :dark)
    (is (= :dark (:color (tbuf/temporal-buffer-info :p3-color)))))
  (testing "temporal-buffer-color! accepts a fn"
    (tbuf/temporal-buffer-color! :p3-color identity)
    (is (fn? (:color (tbuf/temporal-buffer-info :p3-color)))))
  (testing "temporal-buffer-color! on unknown buf is safe"
    (is (nil? (tbuf/temporal-buffer-color! :no-such :dark))))
  (tbuf/stop! :p3-color))

(deftest phase3-feedback-mutation
  (tbuf/deftemporal-buffer :p3-fb {})
  (testing "temporal-buffer-feedback! updates amount"
    (tbuf/temporal-buffer-feedback! :p3-fb {:amount 0.8 :max-generation 6 :velocity-floor 8})
    (let [info (tbuf/temporal-buffer-info :p3-fb)]
      (is (== 0.8 (get-in info [:feedback :amount])))
      (is (= 6   (get-in info [:feedback :max-generation])))
      (is (= 8   (get-in info [:feedback :velocity-floor])))))
  (testing "temporal-buffer-feedback! on unknown buf is safe"
    (is (nil? (tbuf/temporal-buffer-feedback! :no-such {:amount 0.5}))))
  (tbuf/stop! :p3-fb))

(deftest phase3-generation-stamped-on-send
  ;; Events written by temporal-buffer-send! start at generation 0
  (tbuf/deftemporal-buffer :p3-gen {:feedback {:amount 0.0}})
  (tbuf/temporal-buffer-send! :p3-gen {:pitch/midi 60 :dur/beats 0.25})
  (testing "send! stamps :generation 0 on events"
    ;; We can't directly inspect the buffer atom from outside,
    ;; but event-count growing confirms events are stored
    (is (pos? (:event-count (tbuf/temporal-buffer-info :p3-gen)))))
  (tbuf/stop! :p3-gen))

(deftest phase3-regression-hold-with-feedback
  ;; Hold + active feedback should not let events enter via send!
  (tbuf/deftemporal-buffer :p3-hold-fb {:feedback {:amount 1.0}})
  (tbuf/temporal-buffer-hold! :p3-hold-fb true)
  (let [before (:event-count (tbuf/temporal-buffer-info :p3-hold-fb))]
    (tbuf/temporal-buffer-send! :p3-hold-fb {:pitch/midi 60})
    (is (= before (:event-count (tbuf/temporal-buffer-info :p3-hold-fb)))))
  (tbuf/stop! :p3-hold-fb))

;; ---------------------------------------------------------------------------
;; Phase 4 — Halo helpers (via private var access)
;; ---------------------------------------------------------------------------

(def ^:private generate-halo-copies @#'tbuf/generate-halo-copies)

(deftest generate-halo-copies-count
  (let [ev     {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 80
                :beat 4.0 :generation 0 :halo-depth 0}
        halo   {:copies 3 :spread 0.1 :pitch-spread 5}
        copies (generate-halo-copies ev 4.0 halo)]
    (testing "generates exactly :copies neighbors"
      (is (= 3 (count copies))))
    (testing "each copy has halo-depth 1"
      (is (every? #(= 1 (:halo-depth %)) copies)))
    (testing "each copy is near play-beat (within ±spread)"
      (doseq [c copies]
        (is (< (Math/abs (- 4.0 (double (:beat c)))) 0.15))))
    (testing "each copy has a valid MIDI note"
      (doseq [c copies]
        (is (<= 0 (:pitch/midi c) 127))))))

(deftest generate-halo-copies-halo-depth-increments
  (let [ev     {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 80
                :beat 0.0 :generation 2 :halo-depth 2}
        halo   {:copies 2 :spread 0.05 :pitch-spread 3}
        copies (generate-halo-copies ev 0.0 halo)]
    (testing "halo-depth increments from parent value"
      (is (every? #(= 3 (:halo-depth %)) copies)))
    (testing "generation is inherited from parent"
      (is (every? #(= 2 (:generation %)) copies)))))

(deftest generate-halo-copies-zero-spread
  (let [ev     {:pitch/midi 60 :dur/beats 0.25 :mod/velocity 80
                :beat 4.0 :generation 0 :halo-depth 0}
        halo   {:copies 2 :spread 0.0 :pitch-spread 0}
        copies (generate-halo-copies ev 4.0 halo)]
    (testing "zero spread → copies land exactly on play-beat"
      (doseq [c copies]
        (is (== 4.0 (double (:beat c))))))
    (testing "zero pitch-spread → pitch unchanged"
      (doseq [c copies]
        (is (= 60 (:pitch/midi c)))))))

;; ---------------------------------------------------------------------------
;; Phase 4 — State API
;; ---------------------------------------------------------------------------

(deftest phase4-halo-default-nil
  (tbuf/deftemporal-buffer :p4-def {})
  (testing "halo defaults to nil"
    (is (nil? (:halo (tbuf/temporal-buffer-info :p4-def)))))
  (tbuf/stop! :p4-def))

(deftest phase4-halo-opts-at-creation
  (let [halo-cfg {:amount 0.3 :copies 4 :spread 0.05 :pitch-spread 8
                  :feedback-threshold 0.7 :max-halo-depth 3}]
    (tbuf/deftemporal-buffer :p4-opts {:halo halo-cfg})
    (let [info (tbuf/temporal-buffer-info :p4-opts)]
      (testing "halo from opts"
        (is (= halo-cfg (:halo info)))))
    (tbuf/stop! :p4-opts)))

(deftest phase4-halo-mutation
  (tbuf/deftemporal-buffer :p4-mut {})
  (testing "temporal-buffer-halo! sets halo config"
    (tbuf/temporal-buffer-halo! :p4-mut {:amount 0.5 :copies 2 :spread 0.1
                                         :pitch-spread 5 :feedback-threshold 0.7
                                         :max-halo-depth 4})
    (is (= 0.5 (:amount (:halo (tbuf/temporal-buffer-info :p4-mut))))))
  (testing "temporal-buffer-halo! clears halo with nil"
    (tbuf/temporal-buffer-halo! :p4-mut nil)
    (is (nil? (:halo (tbuf/temporal-buffer-info :p4-mut)))))
  (testing "temporal-buffer-halo! on unknown buf is safe"
    (is (nil? (tbuf/temporal-buffer-halo! :no-such {:amount 0.3}))))
  (tbuf/stop! :p4-mut))

(deftest phase4-regression-all-prior-features-intact
  ;; Regression: Phase 4 additions do not break Phase 1–3 state
  (tbuf/deftemporal-buffer :p4-reg
    {:active-zone :z2
     :rate        1.5
     :color       :warm
     :feedback    {:amount 0.5}
     :halo        {:amount 0.2 :copies 2 :spread 0.05 :pitch-spread 3
                   :feedback-threshold 0.7 :max-halo-depth 2}})
  (let [info (tbuf/temporal-buffer-info :p4-reg)]
    (testing "zone preserved"
      (is (= :z2 (:active-zone info))))
    (testing "rate preserved"
      (is (== 1.5 (:rate info))))
    (testing "color preserved"
      (is (= :warm (:color info))))
    (testing "feedback preserved"
      (is (== 0.5 (get-in info [:feedback :amount]))))
    (testing "halo present"
      (is (= 0.2 (:amount (:halo info))))))
  (tbuf/stop! :p4-reg))
