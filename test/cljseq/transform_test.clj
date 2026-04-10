; SPDX-License-Identifier: EPL-2.0
(ns cljseq.transform-test
  "Tests for cljseq.transform — ITransformer protocol, composition,
  play-transformed!, and all eight built-in transformers."
  (:require [clojure.test     :refer [deftest is testing use-fixtures]]
            [cljseq.transform :as xf]
            [cljseq.scale     :as scale-ns]
            [cljseq.loop      :as loop-ns]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- note
  "Build a minimal note event map."
  ([]        (note 60 80))
  ([midi]    (note midi 80))
  ([midi vel] {:pitch/midi midi :mod/velocity vel :dur/beats 1/4 :midi/channel 1}))

(defn- transform-result
  "Apply `xf` to `event` and return the result seq."
  [xf event]
  (xf/transform xf event))

(defn- events-of
  "Extract just the :event maps from transform results."
  [results]
  (mapv :event results))

(defn- delays-of
  "Extract just the :delay-beats values from transform results."
  [results]
  (mapv :delay-beats results))

;; ---------------------------------------------------------------------------
;; ITransformer contract — every transformer must return a seq
;; ---------------------------------------------------------------------------

(deftest transformers-return-seqs
  (testing "all transformers return sequential results (not nil)"
    (let [event     (note)
          scale     (scale-ns/scale :C 4 :major)
          all-xfs   [(xf/velocity-curve)
                     (xf/quantize {:scale scale})
                     (xf/harmonize {:intervals [7]})
                     (xf/echo {:repeats 2})
                     (xf/note-repeat {:n-copies 2})
                     (xf/strum)
                     (xf/dribble {:bounces 2})
                     (xf/latch)]]
      (doseq [x all-xfs]
        (is (sequential? (xf/transform x event))
            (str "non-sequential result from " (type x)))))))

;; ---------------------------------------------------------------------------
;; VelocityCurve
;; ---------------------------------------------------------------------------

(deftest velocity-curve-identity
  (testing "identity breakpoints pass velocity unchanged"
    (let [x   (xf/velocity-curve {:breakpoints [[0 0] [127 127]]})
          res (transform-result x (note 60 80))]
      (is (= 1 (count res)))
      (is (= 80 (:mod/velocity (:event (first res))))))))

(deftest velocity-curve-maps-value
  (testing "velocity-curve interpolates correctly at mid-range"
    ;; Breakpoints: [0 0] [64 90] [127 127]
    ;; At x=64: y=90; at x=32: lerp between (0,0)→(64,90) = 45
    (let [x   (xf/velocity-curve {:breakpoints [[0 0] [64 90] [127 127]]})
          res (transform-result x (note 60 64))]
      (is (= 90 (:mod/velocity (:event (first res))))))))

(deftest velocity-curve-clamps-at-ends
  (testing "velocity-curve clamps values outside the breakpoint range"
    (let [x       (xf/velocity-curve {:breakpoints [[20 30] [100 120]]})
          res-lo  (transform-result x (note 60 5))   ; below range → clamp to 30
          res-hi  (transform-result x (note 60 127))]  ; above range → clamp to 120
      (is (= 30  (:mod/velocity (:event (first res-lo)))))
      (is (= 120 (:mod/velocity (:event (first res-hi))))))))

(deftest velocity-curve-preserves-other-keys
  (testing "velocity-curve does not modify non-velocity keys"
    (let [x   (xf/velocity-curve {:breakpoints [[0 0] [127 127]]})
          ev  (note 72 64)
          res (transform-result x ev)]
      (is (= 72 (:pitch/midi (:event (first res))))))))

(deftest velocity-curve-no-velocity-passthrough
  (testing "velocity-curve passes events without :mod/velocity unchanged"
    (let [x   (xf/velocity-curve {:breakpoints [[0 0] [127 127]]})
          ev  {:pitch/midi 60 :dur/beats 1/4}
          res (transform-result x ev)]
      (is (= 1 (count res)))
      (is (= ev (:event (first res)))))))

;; ---------------------------------------------------------------------------
;; Quantize
;; ---------------------------------------------------------------------------

(deftest quantize-snaps-to-scale
  (testing "quantize snaps pitch to nearest scale degree"
    ;; C major scale: C D E F G A B (MIDI 60 62 64 65 67 69 71)
    ;; MIDI 63 (Eb) → nearest is D(62) or E(64); test that it snaps to one of them
    (let [scale (scale-ns/scale :C 4 :major)
          x     (xf/quantize {:scale scale})
          res   (transform-result x (note 63))]
      (is (= 1 (count res)))
      (is (#{62 64} (:pitch/midi (:event (first res))))))))

(deftest quantize-passes-in-scale-notes
  (testing "quantize preserves pitches already in scale"
    (let [scale (scale-ns/scale :C 4 :major)
          x     (xf/quantize {:scale scale})
          res   (transform-result x (note 60))]
      (is (= 60 (:pitch/midi (:event (first res))))))))

(deftest quantize-drops-when-out-of-forgiveness
  (testing "quantize drops event when pitch is beyond forgiveness threshold"
    (let [scale (scale-ns/scale :C 4 :major)
          x     (xf/quantize {:scale scale :forgiveness 1})
          ;; MIDI 63 (Eb4) is 1 semitone from D(62) and 1 from E(64) — exactly at boundary
          ;; MIDI 66 (F#4) is 1 semitone from F(65) and 1 from G(67) — at boundary too
          ;; Use something clearly outside: Eb in dorian context vs C major
          ;; B♭(70) is 1 semitone from B(71) — within 1 → snaps
          ;; Use MIDI 66 which is equidistant (1 each from F=65 and G=67)
          ;; With forgiveness=1, it snaps (diff=1 is ≤ 1)
          ;; Use forgiveness=0 to force a drop on any out-of-scale note
          x0    (xf/quantize {:scale scale :forgiveness 0})
          res   (transform-result x0 (note 63))]  ; Eb not in C major
      ;; Eb is 1 semitone away; with forgiveness=0, should drop
      (is (empty? res)))))

(deftest quantize-nil-scale-passthrough
  (testing "quantize with nil scale passes event unchanged"
    (let [x   (xf/quantize {:scale nil})
          res (transform-result x (note 63))]
      (is (= 1 (count res)))
      (is (= 63 (:pitch/midi (:event (first res))))))))

;; ---------------------------------------------------------------------------
;; Harmonize
;; ---------------------------------------------------------------------------

(deftest harmonize-adds-intervals
  (testing "harmonize adds notes at specified intervals"
    (let [x   (xf/harmonize {:intervals [7] :snap? false})
          res (transform-result x (note 60))]
      (is (= 2 (count res)))
      (let [midis (set (map (comp :pitch/midi :event) res))]
        (is (contains? midis 60))
        (is (contains? midis 67))))))

(deftest harmonize-multiple-intervals
  (testing "harmonize adds all specified interval voices"
    (let [x   (xf/harmonize {:intervals [4 7 12] :snap? false})
          res (transform-result x (note 60))]
      (is (= 4 (count res)))   ; original + 3 intervals
      (let [midis (set (map (comp :pitch/midi :event) res))]
        (is (contains? midis 60))
        (is (contains? midis 64))
        (is (contains? midis 67))
        (is (contains? midis 72))))))

(deftest harmonize-excludes-original
  (testing "harmonize with include-original? false only returns harmony notes"
    (let [x   (xf/harmonize {:intervals [7] :snap? false :include-original? false})
          res (transform-result x (note 60))]
      (is (= 1 (count res)))
      (is (= 67 (:pitch/midi (:event (first res))))))))

(deftest harmonize-clamps-to-midi-range
  (testing "harmonize drops harmony notes that fall outside [0, 127]"
    ;; G9 = 127; adding 7 = 134 — out of range, should be dropped
    (let [x   (xf/harmonize {:intervals [7] :snap? false})
          res (transform-result x (note 127))]
      ;; Only the original 127 should remain; the harmony (134) is dropped
      (is (= 1 (count res)))
      (is (= 127 (:pitch/midi (:event (first res))))))))

(deftest harmonize-reads-harmony-ctx
  (testing "harmonize snaps to *harmony-ctx* when snap? = true and no key override"
    (let [scale (scale-ns/scale :C 4 :major)
          x     (xf/harmonize {:intervals [4] :snap? true})
          res   (binding [loop-ns/*harmony-ctx* {:harmony/key scale}]
                  (transform-result x (note 60)))]
      ;; Original C4 + harmony at +4 semitones (E4=64, which is in C major → stays 64)
      (let [midis (set (map (comp :pitch/midi :event) res))]
        (is (contains? midis 60))
        (is (contains? midis 64))))))

(deftest harmonize-no-snap-ignores-harmony-ctx
  (testing "harmonize with snap? false uses raw chromatic intervals regardless of *harmony-ctx*"
    (let [scale (scale-ns/scale :C 4 :major)
          x     (xf/harmonize {:intervals [1] :snap? false})  ; +1 = C# (not in C major)
          res   (binding [loop-ns/*harmony-ctx* {:harmony/key scale}]
                  (transform-result x (note 60)))]
      (let [midis (set (map (comp :pitch/midi :event) res))]
        (is (contains? midis 61)  "C# should appear with snap? false")))))

;; ---------------------------------------------------------------------------
;; Echo
;; ---------------------------------------------------------------------------

(deftest echo-produces-n-plus-one-events
  (testing "echo with :repeats n produces n+1 events (original + n copies)"
    (let [x   (xf/echo {:repeats 3 :decay 0.7 :delay-beats 1/4})
          res (transform-result x (note))]
      (is (= 4 (count res))))))

(deftest echo-delay-beats-accumulate
  (testing "echo delay-beats are multiples of :delay-beats"
    (let [x      (xf/echo {:repeats 3 :decay 0.7 :delay-beats 1/4})
          delays (delays-of (transform-result x (note)))]
      (is (= [0.0 0.25 0.5 0.75] (mapv double delays))))))

(deftest echo-velocity-decays
  (testing "echo velocity decreases by decay factor each repeat"
    (let [x   (xf/echo {:repeats 3 :decay 0.5 :delay-beats 1/4})
          res (transform-result x (note 60 80))
          vels (mapv (comp :mod/velocity :event) res)]
      (is (= 80 (first vels)))
      (is (< (second vels) 80))
      (is (apply > vels) "velocities should be strictly decreasing"))))

(deftest echo-velocity-clamps-at-1
  (testing "echo velocity never drops below 1"
    (let [x   (xf/echo {:repeats 10 :decay 0.3 :delay-beats 1/8})
          res (transform-result x (note 60 10))
          vels (mapv (comp :mod/velocity :event) res)]
      (is (every? #(>= % 1) vels)))))

(deftest echo-pitch-step
  (testing "echo with :pitch-step shifts pitch each repeat"
    (let [x   (xf/echo {:repeats 3 :decay 0.8 :delay-beats 1/4 :pitch-step 2})
          res (transform-result x (note 60))
          midis (mapv (comp :pitch/midi :event) res)]
      (is (= 60 (first midis)))
      (is (= 62 (second midis)))
      (is (= 64 (nth midis 2)))
      (is (= 66 (nth midis 3))))))

(deftest echo-zero-repeats
  (testing "echo with :repeats 0 returns only the original event"
    (let [x   (xf/echo {:repeats 0 :decay 0.7 :delay-beats 1/4})
          res (transform-result x (note))]
      (is (= 1 (count res)))
      (is (= 0.0 (double (:delay-beats (first res))))))))

;; ---------------------------------------------------------------------------
;; NoteRepeat
;; ---------------------------------------------------------------------------

(deftest note-repeat-produces-n-copies
  (testing "note-repeat produces exactly :n-copies results"
    (let [x   (xf/note-repeat {:n-copies 4 :window-beats 1})
          res (transform-result x (note))]
      (is (= 4 (count res))))))

(deftest note-repeat-uniform-spacing
  (testing "note-repeat distributes copies evenly across the window"
    (let [x      (xf/note-repeat {:n-copies 4 :window-beats 2})
          delays (mapv (comp double :delay-beats) (transform-result x (note)))]
      (is (= [0.0 0.5 1.0 1.5] delays)))))

(deftest note-repeat-euclidean-distributes
  (testing "euclidean note-repeat distributes N pulses in a larger grid"
    (let [x   (xf/note-repeat {:n-copies 3 :window-beats 2 :euclidean? true})
          res (transform-result x (note))]
      (is (= 3 (count res)))
      ;; All positions should be within the window
      (is (every? #(< (:delay-beats %) 2.0) res)))))

(deftest note-repeat-all-same-event
  (testing "note-repeat produces identical events at each position"
    (let [ev  (note 72 100)
          x   (xf/note-repeat {:n-copies 3 :window-beats 1})
          evs (events-of (transform-result x ev))]
      (is (every? #(= ev %) evs)))))

;; ---------------------------------------------------------------------------
;; Strum
;; ---------------------------------------------------------------------------

(deftest strum-spreads-chord-notes
  (testing "strum produces one result per note in :chord/notes"
    (let [chord {:chord/notes [60 64 67 72] :dur/beats 1 :mod/velocity 80}
          x     (xf/strum {:direction :up :rate-beats 1/32})
          res   (transform-result x chord)]
      (is (= 4 (count res))))))

(deftest strum-up-direction-ascending
  (testing "strum :up plays notes in ascending MIDI order"
    (let [chord {:chord/notes [60 64 67] :dur/beats 1 :mod/velocity 80}
          x     (xf/strum {:direction :up :rate-beats 1/32})
          midis (mapv (comp :pitch/midi :event) (transform-result x chord))]
      (is (= [60 64 67] midis)))))

(deftest strum-down-direction-descending
  (testing "strum :down plays notes in reverse order"
    (let [chord {:chord/notes [60 64 67] :dur/beats 1 :mod/velocity 80}
          x     (xf/strum {:direction :down :rate-beats 1/32})
          midis (mapv (comp :pitch/midi :event) (transform-result x chord))]
      (is (= [67 64 60] midis)))))

(deftest strum-delay-beats-accumulate
  (testing "strum delays are multiples of :rate-beats"
    (let [chord {:chord/notes [60 64 67] :dur/beats 1 :mod/velocity 80}
          x     (xf/strum {:direction :up :rate-beats 1/32})
          delays (mapv (comp double :delay-beats) (transform-result x chord))]
      (is (= [0.0 (double 1/32) (double 2/32)] delays)))))

(deftest strum-removes-chord-notes-key
  (testing "strum removes :chord/notes from individual note events"
    (let [chord {:chord/notes [60 64 67] :dur/beats 1 :mod/velocity 80}
          x     (xf/strum)
          evs   (events-of (transform-result x chord))]
      (is (every? #(not (contains? % :chord/notes)) evs)))))

(deftest strum-single-note-fallback
  (testing "strum falls back to :pitch/midi when :chord/notes is absent"
    (let [x   (xf/strum)
          res (transform-result x (note 60))]
      (is (= 1 (count res)))
      (is (= 60 (:pitch/midi (:event (first res))))))))

;; ---------------------------------------------------------------------------
;; Dribble
;; ---------------------------------------------------------------------------

(deftest dribble-produces-n-plus-one-events
  (testing "dribble with :bounces n produces n+1 events"
    (let [x   (xf/dribble {:bounces 4 :initial-delay 1/4 :restitution 0.6})
          res (transform-result x (note))]
      (is (= 5 (count res))))))

(deftest dribble-first-event-immediate
  (testing "dribble first event has delay-beats = 0"
    (let [x   (xf/dribble {:bounces 3 :initial-delay 1/4 :restitution 0.6})
          res (transform-result x (note))]
      (is (= 0.0 (double (:delay-beats (first res))))))))

(deftest dribble-delays-increasing
  (testing "dribble delay-beats are strictly increasing"
    (let [x      (xf/dribble {:bounces 4 :initial-delay 1/4 :restitution 0.65})
          delays (mapv (comp double :delay-beats) (transform-result x (note)))]
      (is (apply < delays)))))

(deftest dribble-gaps-shrinking
  (testing "dribble inter-onset gaps decrease with each bounce"
    (let [x      (xf/dribble {:bounces 4 :initial-delay 1/2 :restitution 0.6})
          delays (mapv (comp double :delay-beats) (transform-result x (note)))
          gaps   (map - (rest delays) delays)]
      (is (apply > gaps) "gaps should be strictly shrinking"))))

(deftest dribble-zero-bounces
  (testing "dribble with :bounces 0 returns only the original event"
    (let [x   (xf/dribble {:bounces 0 :initial-delay 1/4 :restitution 0.6})
          res (transform-result x (note))]
      (is (= 1 (count res))))))

;; ---------------------------------------------------------------------------
;; Latch
;; ---------------------------------------------------------------------------

(deftest latch-toggle-alternates
  (testing "latch :toggle alternates open/closed on successive calls"
    (let [x    (xf/latch {:mode :toggle :initial true})
          ev   (note)
          r1   (transform-result x ev)   ; open → passes
          r2   (transform-result x ev)   ; closed → drops
          r3   (transform-result x ev)]  ; open again → passes
      (is (= 1 (count r1)) "first call should pass")
      (is (= 0 (count r2)) "second call should drop")
      (is (= 1 (count r3)) "third call should pass"))))

(deftest latch-toggle-initial-closed
  (testing "latch :toggle with :initial false starts closed"
    (let [x  (xf/latch {:mode :toggle :initial false})
          ev (note)]
      (is (empty? (transform-result x ev)))
      (is (= 1 (count (transform-result x ev)))))))

(deftest latch-velocity-passes-above-threshold
  (testing "latch :velocity passes events at or above threshold"
    (let [x   (xf/latch {:mode :velocity :threshold 80})
          r1  (transform-result x (note 60 80))   ; = threshold
          r2  (transform-result x (note 60 100))] ; > threshold
      (is (= 1 (count r1)))
      (is (= 1 (count r2))))))

(deftest latch-velocity-drops-below-threshold
  (testing "latch :velocity drops events below threshold"
    (let [x  (xf/latch {:mode :velocity :threshold 80})
          r1 (transform-result x (note 60 79))]
      (is (empty? r1)))))

;; ---------------------------------------------------------------------------
;; compose-xf — chaining
;; ---------------------------------------------------------------------------

(deftest compose-xf-two-transformers
  (testing "compose-xf applies transformers left-to-right"
    ;; velocity-curve maps 80 → 80 (identity); echo produces 3 copies
    (let [chain (xf/compose-xf
                  (xf/velocity-curve {:breakpoints [[0 0] [127 127]]})
                  (xf/echo {:repeats 2 :decay 0.7 :delay-beats 1/4}))
          res   (transform-result chain (note 60 80))]
      (is (= 3 (count res))))))

(deftest compose-xf-delays-accumulate
  (testing "compose-xf accumulates delay-beats across chain"
    ;; note-repeat [0.0, 0.25, 0.5, 0.75] × echo 2 repeats at 1/8 each
    ;; Each note-repeat event gets echoed: [0, 1/8, 2/8] added to its position
    (let [chain  (xf/compose-xf
                   (xf/note-repeat {:n-copies 2 :window-beats 1})  ; [0.0, 0.5]
                   (xf/echo {:repeats 1 :decay 0.7 :delay-beats 1/4}))   ; +[0, 0.25] each
          delays (sort (mapv (comp double :delay-beats) (transform-result chain (note))))]
      ;; 2 note-repeat × 2 echo = 4 events at [0.0, 0.25, 0.5, 0.75]
      (is (= 4 (count delays)))
      (is (= [0.0 0.25 0.5 0.75] delays)))))

(deftest compose-xf-latch-in-chain
  (testing "latch in a chain drops events before they reach downstream transformers"
    (let [chain (xf/compose-xf
                  (xf/latch {:mode :velocity :threshold 80})
                  (xf/echo {:repeats 3 :decay 0.7 :delay-beats 1/4}))
          r-pass (transform-result chain (note 60 90))   ; passes latch → 4 echo results
          r-drop (transform-result chain (note 60 70))]  ; dropped by latch → 0 results
      (is (= 4 (count r-pass)))
      (is (= 0 (count r-drop))))))

(deftest compose-xf-empty-returns-single-pass
  (testing "compose-xf with no transformers passes the event unchanged"
    (let [chain (apply xf/compose-xf [])
          res   (transform-result chain (note 60 80))]
      (is (= 1 (count res)))
      (is (= (note 60 80) (:event (first res)))))))

;; ---------------------------------------------------------------------------
;; play-transformed! — dispatch behaviour
;; ---------------------------------------------------------------------------

(deftest play-transformed-calls-play-for-each-result
  (testing "play-transformed! calls core/play! for each immediate result"
    (let [played (atom [])
          x      (xf/note-repeat {:n-copies 3 :window-beats 0})  ; all at delay 0
          ev     (note)]
      (with-redefs [cljseq.core/play! (fn [e] (swap! played conj e) nil)]
        (xf/play-transformed! x ev)
        ;; Wait briefly for any threads (there should be none since delays = 0)
        (Thread/sleep 5))
      (is (= 3 (count @played))))))

(deftest play-transformed-nil-event-noop
  (testing "play-transformed! with nil event does not throw"
    (let [played (atom 0)
          x      (xf/echo {:repeats 2})]
      (with-redefs [cljseq.core/play! (fn [_] (swap! played inc) nil)]
        (xf/play-transformed! x nil))
      (is (= 0 @played)))))

(deftest play-transformed-schedules-delayed-events
  (testing "play-transformed! schedules delayed events on background threads"
    (let [played  (atom [])
          ;; Use a very short delay so the test completes quickly
          x       (xf/echo {:repeats 1 :decay 0.9 :delay-beats 0.001})]  ; ~0.5ms at 120 BPM
      (with-redefs [cljseq.core/play! (fn [e] (swap! played conj e) nil)
                    cljseq.core/get-bpm (constantly 120)]
        (xf/play-transformed! x (note))
        ;; Immediate event plays synchronously; delayed one may not have fired yet
        (Thread/sleep 50))  ; generous window for the 0.5ms delay thread
      (is (= 2 (count @played)) "both original and delayed echo should have fired"))))

;; ---------------------------------------------------------------------------
;; Private helpers — breakpoint-apply
;; ---------------------------------------------------------------------------

(deftest breakpoint-apply-interpolates
  (testing "breakpoint-apply linearly interpolates between breakpoints"
    (let [bp  [[0 0] [100 200]]
          result (#'xf/breakpoint-apply bp 50)]
      (is (< 99.0 result 101.0)))))

(deftest breakpoint-apply-at-breakpoint
  (testing "breakpoint-apply returns exact y at breakpoint x values"
    (let [bp [[0 10] [127 90]]]
      (is (= 10.0  (#'xf/breakpoint-apply bp 0)))
      (is (= 90.0  (#'xf/breakpoint-apply bp 127))))))

;; ---------------------------------------------------------------------------
;; Private helpers — bjorklund / euclidean-positions
;; ---------------------------------------------------------------------------

(deftest bjorklund-correct-pulse-count
  (testing "bjorklund returns exactly `steps` values with exactly `pulses` true"
    (doseq [[pulses steps] [[3 8] [5 8] [4 16] [2 7]]]
      (let [result (#'xf/bjorklund pulses steps)]
        (is (= steps (count result))
            (str "wrong length for " pulses "/" steps))
        (is (= pulses (count (filter true? result)))
            (str "wrong pulse count for " pulses "/" steps))))))

(deftest euclidean-positions-correct-count
  (testing "euclidean-positions returns exactly n-copies positions"
    (let [positions (#'xf/euclidean-positions 3 2.0)]
      (is (= 3 (count positions))))))

(deftest euclidean-positions-within-window
  (testing "all euclidean positions fall within the window"
    (doseq [n [2 3 5 7]]
      (let [window 4.0
            positions (#'xf/euclidean-positions n window)]
        (is (every? #(< % window) positions)
            (str "out-of-window position for n=" n))))))
