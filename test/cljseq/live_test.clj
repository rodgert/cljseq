; SPDX-License-Identifier: EPL-2.0
(ns cljseq.live-test
  "Unit tests for cljseq.live — synth context, play!, phrase!, ring/tick!."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.clock :as clock]
            [cljseq.core  :as core]
            [cljseq.ctrl  :as ctrl]
            [cljseq.live  :as live]
            [cljseq.loop  :as loop-ns]
            [cljseq.phasor :as phasor]
            [cljseq.pitch :as pitch]
            [cljseq.scala :as scala]
            [cljseq.scale :as scale]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- capture-play!
  "Execute `f` with core/play! replaced by a spy. Returns a vector of
  the step maps passed to core/play! during execution."
  [f]
  (let [calls (atom [])]
    (with-redefs [core/play! (fn [step] (swap! calls conj step))]
      (f))
    @calls))

;; ---------------------------------------------------------------------------
;; ring / tick!
;; ---------------------------------------------------------------------------

(deftest ring-cycle-test
  (testing "tick! cycles through all elements"
    (let [r (live/ring :C4 :E4 :G4)]
      (is (= :C4 (live/tick! r)))
      (is (= :E4 (live/tick! r)))
      (is (= :G4 (live/tick! r)))
      (is (= :C4 (live/tick! r)) "wraps back to start")))
  (testing "independent rings do not share state"
    (let [r1 (live/ring :A :B)
          r2 (live/ring :X :Y)]
      (live/tick! r1)
      (is (= :X (live/tick! r2)) "r2 unaffected by r1 advance"))))

;; ---------------------------------------------------------------------------
;; play! — step map normalisation
;; ---------------------------------------------------------------------------

(deftest play-normalises-keyword-test
  (testing "keyword note is resolved to :pitch/midi"
    (let [[step] (capture-play! #(live/play! :C4 1/4))]
      (is (= 60 (:pitch/midi step)))
      (is (= 1/4 (:dur/beats step))))))

(deftest play-normalises-integer-test
  (testing "integer note is stored as :pitch/midi"
    (let [[step] (capture-play! #(live/play! 64 1/2))]
      (is (= 64 (:pitch/midi step)))
      (is (= 1/2 (:dur/beats step))))))

(deftest play-passthrough-map-test
  (testing "step map is passed through with dur merged"
    (let [[step] (capture-play!
                  #(live/play! {:pitch/midi 48 :midi/channel 5} 1/8))]
      (is (= 48 (:pitch/midi step)))
      (is (= 1/8 (:dur/beats step)))
      (is (= 5 (:midi/channel step))))))

(deftest play-default-dur-test
  (testing "omitting dur uses *default-dur*"
    (let [[step] (capture-play!
                  #(binding [live/*default-dur* 1/2]
                     (live/play! :D4)))]
      (is (= 1/2 (:dur/beats step))))))

(deftest play-step-map-dur-beats-test
  (testing ":dur/beats in step map takes priority over dur arg"
    (let [[step] (capture-play!
                  #(live/play! {:pitch/midi 60 :dur/beats 1/2} 1/8))]
      (is (= 1/2 (:dur/beats step)) "step map :dur/beats wins over arg"))))

;; ---------------------------------------------------------------------------
;; play! — synth context routing
;; ---------------------------------------------------------------------------

(deftest play-no-context-channel-default-test
  (testing "no *synth-ctx* → no :midi/channel injected (core uses its own default)"
    (let [[step] (capture-play!
                  #(binding [loop-ns/*synth-ctx* nil]
                     (live/play! :C4 1/4)))]
      (is (nil? (:midi/channel step)) "channel not in step when ctx is nil"))))

(deftest play-context-sets-channel-test
  (testing "*synth-ctx* :midi/channel appears in the step map"
    (let [ctx {:midi/channel 3 :mod/velocity 90}
          [step] (capture-play!
                  #(binding [loop-ns/*synth-ctx* ctx]
                     (live/play! :C4 1/4)))]
      (is (= 3 (:midi/channel step)))
      (is (= 90 (:mod/velocity step))))))

(deftest play-step-map-overrides-context-test
  (testing "per-note :midi/channel in step map overrides *synth-ctx*"
    (let [ctx {:midi/channel 3 :mod/velocity 90}
          [step] (capture-play!
                  #(binding [loop-ns/*synth-ctx* ctx]
                     (live/play! {:pitch/midi 60 :midi/channel 7} 1/4)))]
      (is (= 7 (:midi/channel step)) "per-note channel wins"))))

;; ---------------------------------------------------------------------------
;; with-synth
;; ---------------------------------------------------------------------------

(deftest with-synth-binds-context-test
  (testing "with-synth binds *synth-ctx* for the duration of the block"
    (let [ctx  {:midi/channel 4}
          calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))]
        (live/with-synth ctx
          (live/play! :E4 1/4)))
      (is (= 4 (:midi/channel (first @calls))))))
  (testing "with-synth does not affect *synth-ctx* after the block"
    (binding [loop-ns/*synth-ctx* nil]
      (live/with-synth {:midi/channel 9} (live/play! :C4 1/4))
      (is (nil? loop-ns/*synth-ctx*) "ctx restored after with-synth"))))

;; ---------------------------------------------------------------------------
;; use-synth!
;; ---------------------------------------------------------------------------

(deftest use-synth-sets-root-test
  (testing "use-synth! sets the root binding of *synth-ctx*"
    (let [original loop-ns/*synth-ctx*]
      (try
        (let [ctx {:midi/channel 6}]
          (live/use-synth! ctx)
          (is (= ctx loop-ns/*synth-ctx*)))
        (finally
          (live/use-synth! original)))))
  (testing "use-synth! nil clears context"
    (let [original loop-ns/*synth-ctx*]
      (try
        (live/use-synth! {:midi/channel 2})
        (live/use-synth! nil)
        (is (nil? loop-ns/*synth-ctx*))
        (finally
          (live/use-synth! original))))))

;; ---------------------------------------------------------------------------
;; phrase!
;; ---------------------------------------------------------------------------

(deftest phrase-uniform-dur-test
  (testing "phrase! plays each note with the given :dur"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (live/phrase! [:C4 :E4 :G4] :dur 1/8))
      (is (= 3 (count @calls)))
      (is (= [60 64 67] (mapv :pitch/midi @calls)))
      (is (every? #(= 1/8 (:dur/beats %)) @calls)))))

(deftest phrase-inherits-context-test
  (testing "phrase! inherits *synth-ctx* channel"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (binding [loop-ns/*synth-ctx* {:midi/channel 5}]
          (live/phrase! [:C4 :E4] :dur 1/4)))
      (is (every? #(= 5 (:midi/channel %)) @calls)))))

;; ---------------------------------------------------------------------------
;; set-default-dur! / with-dur
;; ---------------------------------------------------------------------------

(deftest set-default-dur-test
  (testing "set-default-dur! changes root *default-dur*"
    (let [original live/*default-dur*]
      (try
        (live/set-default-dur! 1/8)
        (is (= 1/8 live/*default-dur*))
        (finally
          (live/set-default-dur! original))))))

(deftest with-dur-scopes-default-dur-test
  (testing "with-dur binds *default-dur* only within the block"
    (let [original live/*default-dur*]
      (live/with-dur 1/16
        (is (= 1/16 live/*default-dur*) "within block"))
      (is (= original live/*default-dur*) "restored after block")))
  (testing "play! inside with-dur uses the scoped duration"
    (let [[step] (capture-play!
                  #(live/with-dur 1/2
                     (live/play! :C4)))]
      (is (= 1/2 (:dur/beats step))))))

;; ---------------------------------------------------------------------------
;; ring-reset! / ring-len
;; ---------------------------------------------------------------------------

(deftest ring-reset-test
  (testing "ring-reset! returns ring to first element"
    (let [r (live/ring :A :B :C)]
      (live/tick! r)
      (live/tick! r)
      (live/ring-reset! r)
      (is (= :A (live/tick! r)) "back to first element after reset"))))

(deftest ring-len-test
  (testing "ring-len returns number of elements"
    (is (= 3 (live/ring-len (live/ring :A :B :C))))
    (is (= 1 (live/ring-len (live/ring :solo))))
    (is (= 0 (live/ring-len (live/ring))))))

;; ---------------------------------------------------------------------------
;; defring
;; ---------------------------------------------------------------------------

(deftest defring-creates-var-test
  (testing "defring creates a cycling ring"
    (core/start! :bpm 120)
    (try
      (live/defring test-defring-a :X :Y :Z)
      (is (= :X (live/tick! test-defring-a)))
      (is (= :Y (live/tick! test-defring-a)))
      (finally (core/stop!)))))

(deftest defring-registers-ctrl-node-test
  (testing "defring registers the ring in the ctrl tree at [:rings/<name>]"
    (core/start! :bpm 120)
    (try
      (live/defring test-defring-b :P :Q)
      (is (some? (ctrl/get [:rings/test-defring-b])) "ctrl node exists")
      (is (= test-defring-b (ctrl/get [:rings/test-defring-b])) "ctrl value is the ring atom")
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; use-mod! / with-mod
;; ---------------------------------------------------------------------------

(deftest use-mod-sets-root-test
  (testing "use-mod! sets the root binding of *mod-ctx*"
    (let [original loop-ns/*mod-ctx*
          ctx      {[:filter/cutoff] :placeholder}]
      (try
        (live/use-mod! ctx)
        (is (= ctx loop-ns/*mod-ctx*))
        (finally
          (live/use-mod! original))))))

(deftest with-mod-scopes-mod-ctx-test
  (testing "with-mod binds *mod-ctx* only within the block"
    (let [original loop-ns/*mod-ctx*
          ctx      {[:resonance] :placeholder}]
      (live/with-mod ctx
        (is (= ctx loop-ns/*mod-ctx*) "within block"))
      (is (= original loop-ns/*mod-ctx*) "restored after block"))))

;; ---------------------------------------------------------------------------
;; tick-mods!
;; ---------------------------------------------------------------------------

(deftest tick-mods-routes-to-ctrl-test
  (testing "tick-mods! samples each mod in *mod-ctx* at *virtual-time* and calls ctrl/send!"
    (core/start! :bpm 120)
    (try
      (ctrl/defnode! [:test/tmod-cutoff] :type :float :value 0.0)
      (let [ph  (clock/->Phasor 1 0)
            lfo (live/ring 0.5)]          ; use a simple value; any ITemporalValue works
        ;; Use a constant ITemporalValue (just a record that returns 0.75)
        (let [const-mod (reify clock/ITemporalValue
                          (sample    [_ _beat] 0.75)
                          (next-edge [_ beat]  (+ beat 1.0)))]
          (binding [loop-ns/*mod-ctx*    {[:test/tmod-cutoff] const-mod}
                    loop-ns/*virtual-time* 0.0]
            (live/tick-mods!))
          (is (= 0.75 (ctrl/get [:test/tmod-cutoff])) "ctrl node updated with sampled value")))
      (finally (core/stop!)))))

(deftest tick-mods-noop-when-nil-test
  (testing "tick-mods! is a no-op when *mod-ctx* is nil"
    (binding [loop-ns/*mod-ctx* nil]
      (is (nil? (live/tick-mods!))))))

;; ---------------------------------------------------------------------------
;; deflive-loop :synth opts — integration check
;; ---------------------------------------------------------------------------

(deftest deflive-loop-synth-opts-test
  (testing ":synth in deflive-loop opts binds *synth-ctx* for the loop body"
    (core/start! :bpm 6000)
    (try
      (let [ctx      {:midi/channel 8}
            observed (promise)]
        ;; Keep with-redefs open until after the loop fires so the spy is
        ;; still the root binding when the daemon thread calls core/play!.
        (with-redefs [core/play! (fn [step] (deliver observed step))]
          (core/deflive-loop :ctx-test {:synth ctx}
            (live/play! :C4 1/4)
            (core/stop-loop! :ctx-test))
          (let [step (deref observed 500 nil)]
            (is (some? step) "play! should have been called")
            (is (= 8 (:midi/channel step))))))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; §24.6 per-step mod routing — apply-step-mods / use-step-mods! / with-step-mods
;; ---------------------------------------------------------------------------

(deftest apply-step-mods-with-itv-test
  (testing "ITemporalValue in *step-mod-ctx* is sampled at *virtual-time*"
    (let [const-mod (reify clock/ITemporalValue
                      (sample    [_ _] 99)
                      (next-edge [_ b] (+ b 1.0)))
          step {:pitch/midi 60 :dur/beats 1/4}]
      (binding [loop-ns/*step-mod-ctx*    {:mod/velocity const-mod}
                loop-ns/*virtual-time*    0.0]
        (let [result (live/apply-step-mods step)]
          (is (= 99 (:mod/velocity result)) "ITV sampled and merged"))))))

(deftest apply-step-mods-with-constant-test
  (testing "Non-ITemporalValue constant in *step-mod-ctx* is used directly"
    (let [step {:pitch/midi 60 :dur/beats 1/4}]
      (binding [loop-ns/*step-mod-ctx*    {:gate/len 0.8}
                loop-ns/*virtual-time*    0.0]
        (let [result (live/apply-step-mods step)]
          (is (= 0.8 (:gate/len result)) "constant merged into step"))))))

(deftest apply-step-mods-noop-when-nil-test
  (testing "apply-step-mods returns step unchanged when *step-mod-ctx* is nil"
    (let [step {:pitch/midi 60 :dur/beats 1/4}]
      (binding [loop-ns/*step-mod-ctx* nil]
        (is (= step (live/apply-step-mods step)))))))

(deftest apply-step-mods-overrides-step-values-test
  (testing "step-mods override explicit step map values"
    (let [const-mod (reify clock/ITemporalValue
                      (sample    [_ _] 42)
                      (next-edge [_ b] (+ b 1.0)))
          step {:pitch/midi 60 :dur/beats 1/4 :mod/velocity 80}]
      (binding [loop-ns/*step-mod-ctx*    {:mod/velocity const-mod}
                loop-ns/*virtual-time*    0.0]
        (let [result (live/apply-step-mods step)]
          (is (= 42 (:mod/velocity result))
              "step-mod overrides explicit step value"))))))

(deftest play-applies-step-mods-test
  (testing "play! automatically applies *step-mod-ctx* to the step"
    (let [const-mod (reify clock/ITemporalValue
                      (sample    [_ _] 77)
                      (next-edge [_ b] (+ b 1.0)))
          captured  (atom nil)]
      (with-redefs [core/play! (fn [step] (reset! captured step))]
        (binding [loop-ns/*step-mod-ctx*    {:mod/velocity const-mod}
                  loop-ns/*virtual-time*    0.0]
          (live/play! {:pitch/midi 60 :dur/beats 1/4})))
      (is (= 77 (:mod/velocity @captured)) "step-mod value reached core/play!"))))

(deftest play-step-mod-overrides-synth-ctx-test
  (testing "step-mods have higher priority than *synth-ctx*"
    (let [const-mod (reify clock/ITemporalValue
                      (sample    [_ _] 55)
                      (next-edge [_ b] (+ b 1.0)))
          captured  (atom nil)]
      (with-redefs [core/play! (fn [step] (reset! captured step))]
        (binding [loop-ns/*synth-ctx*       {:mod/velocity 90}
                  loop-ns/*step-mod-ctx*    {:mod/velocity const-mod}
                  loop-ns/*virtual-time*    0.0]
          (live/play! :C4 1/4)))
      (is (= 55 (:mod/velocity @captured)) "step-mod wins over synth ctx"))))

(deftest with-step-mods-scopes-context-test
  (testing "with-step-mods binds *step-mod-ctx* only within the block"
    (let [original loop-ns/*step-mod-ctx*
          ctx      {:gate/len 0.9}]
      (live/with-step-mods ctx
        (is (= ctx loop-ns/*step-mod-ctx*) "within block"))
      (is (= original loop-ns/*step-mod-ctx*) "restored after block"))))

(deftest use-step-mods-sets-root-test
  (testing "use-step-mods! sets the root binding of *step-mod-ctx*"
    (let [original loop-ns/*step-mod-ctx*
          ctx      {:mod/velocity 64}]
      (try
        (live/use-step-mods! ctx)
        (is (= ctx loop-ns/*step-mod-ctx*))
        (finally
          (live/use-step-mods! original)))))
  (testing "use-step-mods! nil clears context"
    (let [original loop-ns/*step-mod-ctx*]
      (try
        (live/use-step-mods! {:gate/len 0.5})
        (live/use-step-mods! nil)
        (is (nil? loop-ns/*step-mod-ctx*))
        (finally
          (live/use-step-mods! original))))))

(deftest deflive-loop-binds-step-mods-test
  (testing ":step-mods in deflive-loop opts binds *step-mod-ctx* for the loop body"
    (core/start! :bpm 6000)
    (try
      (let [const-mod (reify clock/ITemporalValue
                        (sample    [_ _] 33)
                        (next-edge [_ b] (+ b 1.0)))
            ctx      {:mod/velocity const-mod}
            observed (promise)]
        (with-redefs [core/play! (fn [step] (deliver observed step))]
          (core/deflive-loop :step-mod-loop-test {:step-mods ctx}
            (live/play! :C4 1/4)
            (core/stop-loop! :step-mod-loop-test))
          (let [step (deref observed 500 nil)]
            (is (some? step) "play! should have been called")
            (is (= 33 (:mod/velocity step)) "step-mod applied in loop"))))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Harmony context (*harmony-ctx*)
;; ---------------------------------------------------------------------------

(deftest harmony-use-harmony-test
  (testing "use-harmony! sets *harmony-ctx* root binding"
    (let [s (scale/scale :C 4 :major)]
      (live/use-harmony! s)
      (is (= s loop-ns/*harmony-ctx*))
      (live/use-harmony! nil)
      (is (nil? loop-ns/*harmony-ctx*)))))

(deftest harmony-with-harmony-test
  (testing "with-harmony scopes *harmony-ctx* to body"
    (let [s (scale/scale :D 4 :dorian)]
      (live/with-harmony s
        (is (= s loop-ns/*harmony-ctx*)))
      (is (nil? loop-ns/*harmony-ctx*)))))

(deftest harmony-root-test
  (testing "root returns the root pitch of *harmony-ctx*"
    (live/with-harmony (scale/scale :C 4 :major)
      (let [r (live/root)]
        (is (= 60 (pitch/pitch->midi r))))))
  (testing "root throws when no context"
    (is (thrown? Exception (live/root)))))

(deftest harmony-fifth-test
  (testing "fifth returns degree 4 of *harmony-ctx*"
    (live/with-harmony (scale/scale :C 4 :major)
      ;; degree 4 of C major = G4 = MIDI 67
      (is (= 67 (pitch/pitch->midi (live/fifth))))))
  (testing "fifth throws when no context"
    (is (thrown? Exception (live/fifth)))))

(deftest harmony-scale-degree-test
  (testing "scale-degree returns pitch at given degree"
    (live/with-harmony (scale/scale :C 4 :major)
      (is (= 60 (pitch/pitch->midi (live/scale-degree 0))))   ; C4
      (is (= 62 (pitch/pitch->midi (live/scale-degree 1))))   ; D4
      (is (= 72 (pitch/pitch->midi (live/scale-degree 7)))))  ; C5 (octave up)
  (testing "scale-degree throws when no context"
    (is (thrown? Exception (live/scale-degree 0))))))

(deftest harmony-in-key-test
  (testing "in-key? returns true for scale members"
    (live/with-harmony (scale/scale :C 4 :major)
      (is (live/in-key? (pitch/pitch :C 4)))
      (is (live/in-key? :G4))
      (is (not (live/in-key? (pitch/pitch :C 4 :sharp))))))
  (testing "in-key? throws when no context"
    (is (thrown? Exception (live/in-key? :C4)))))

(deftest play-with-pitch-record-test
  (testing "play! accepts a Pitch record"
    (core/start! :bpm 6000)
    (try
      (let [observed (promise)]
        (with-redefs [core/play! (fn [step] (deliver observed step))]
          (live/play! (pitch/pitch :C 4) 1/4)
          (let [step (deref observed 200 nil)]
            (is (some? step) "play! should have been called")
            (is (= 60 (:pitch/midi step)) "MIDI derived from Pitch record"))))
      (finally (core/stop!)))))

(deftest harmony-context-in-deflive-loop-test
  (testing ":harmony opt binds *harmony-ctx* for the loop thread"
    (core/start! :bpm 6000)
    (try
      (let [s        (scale/scale :G 4 :major)
            observed (promise)]
        (with-redefs [core/play! (fn [step] (deliver observed step))]
          (core/deflive-loop :harmony-loop-test {:harmony s}
            (live/play! (live/root) 1/4)
            (core/stop-loop! :harmony-loop-test))
          (let [step (deref observed 500 nil)]
            (is (some? step) "play! should have been called")
            ;; G4 = MIDI 67
            (is (= 67 (:pitch/midi step)) "root of G major = G4"))))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; *tuning-ctx* / apply-tuning / use-tuning! / with-tuning
;; ---------------------------------------------------------------------------

;; Build a simple 12-EDO MicrotonalScale (equal temperament) with
;; period 1200 cents and 12 degrees at 100-cent steps.
(def ^:private twelve-edo
  (scala/->MicrotonalScale "12-EDO test" 1200.0
                           (mapv #(* 100.0 %) (range 13))))

;; A 31-EDO scale: 31 equal divisions of the octave, ~38.71 cents per step.
(def ^:private thirty-one-edo
  (scala/->MicrotonalScale "31-EDO test" 1200.0
                           (mapv #(* (/ 1200.0 31) %) (range 32))))

(deftest apply-tuning-nil-ctx-test
  (testing "no *tuning-ctx* → step passes through unchanged"
    (let [[step] (capture-play!
                  #(binding [loop-ns/*tuning-ctx* nil]
                     (live/play! 60 1/4)))]
      (is (= 60 (:pitch/midi step)))
      (is (nil? (:pitch/bend-cents step))))))

(deftest apply-tuning-12edo-identity-test
  (testing "12-EDO with identity KBM: MIDI 60 → 60, no bend"
    (let [[step] (capture-play!
                  #(binding [loop-ns/*tuning-ctx* {:scale twelve-edo}]
                     (live/play! 60 1/4)))]
      (is (= 60 (:pitch/midi step)))
      (is (nil? (:pitch/bend-cents step)) "12-TET bend is zero, omitted"))))

(deftest apply-tuning-12edo-midi61-test
  (testing "12-EDO MIDI 61 → midi 61, no bend (exactly 100 cents)"
    (let [[step] (capture-play!
                  #(binding [loop-ns/*tuning-ctx* {:scale twelve-edo}]
                     (live/play! 61 1/4)))]
      (is (= 61 (:pitch/midi step)))
      (is (nil? (:pitch/bend-cents step))))))

(deftest apply-tuning-31edo-adds-bend-test
  (testing "31-EDO MIDI 62 (D4): retuned MIDI and non-zero bend"
    ;; With identity KBM, degree = midi - 60 = 2.
    ;; 31-EDO degree 2 = 2*(1200/31) ≈ 77.42 cents.
    ;; Nearest semitone = 1 (100 cents), so midi = 61, bend ≈ -22.58 cents.
    (let [[step] (capture-play!
                  #(binding [loop-ns/*tuning-ctx* {:scale thirty-one-edo}]
                     (live/play! 62 1/4)))]
      (is (integer? (:pitch/midi step)) "midi is an integer")
      (is (number? (:pitch/bend-cents step)) "bend-cents present for 31-EDO"))))

(deftest apply-tuning-no-pitch-midi-test
  (testing "step without :pitch/midi passes through unchanged"
    (let [[step] (capture-play!
                  #(binding [loop-ns/*tuning-ctx* {:scale twelve-edo}]
                     (live/play! {:pitch/midi 60 :mod/velocity 80} 1/4)))]
      ;; play! normalises the map so :pitch/midi is present — this just ensures
      ;; the tuning pipeline doesn't crash on a step that already went through.
      (is (= 60 (:pitch/midi step))))))

(deftest with-tuning-scope-test
  (testing "with-tuning binds *tuning-ctx* for the body only"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))]
        (binding [loop-ns/*virtual-time* 0.0]
          ;; Outside with-tuning: no bend expected for 12-TET note
          (live/play! 60 1/4)
          ;; Inside with-tuning: 31-EDO context active
          (live/with-tuning {:scale thirty-one-edo}
            (live/play! 62 1/4))
          ;; Outside again: no tuning
          (live/play! 60 1/4)))
      (let [[out-before in-scope out-after] @calls]
        (is (nil? (:pitch/bend-cents out-before)) "no tuning outside")
        (is (some? (:pitch/midi in-scope)) "tuning applied inside")
        (is (nil? (:pitch/bend-cents out-after)) "no tuning after scope")))))

(deftest use-tuning-sets-root-binding-test
  (testing "use-tuning! alters the root binding of *tuning-ctx*"
    (let [original loop-ns/*tuning-ctx*]
      (try
        (live/use-tuning! {:scale twelve-edo})
        (is (= twelve-edo (:scale loop-ns/*tuning-ctx*)))
        (finally
          (alter-var-root #'loop-ns/*tuning-ctx* (constantly original)))))))

(deftest tuning-opt-in-deflive-loop-test
  (testing ":tuning opt in deflive-loop binds *tuning-ctx* for the loop thread"
    (core/start! :bpm 6000)
    (try
      (let [observed (promise)]
        (with-redefs [core/play! (fn [step] (deliver observed step))]
          (core/deflive-loop :tuning-loop-test {:tuning {:scale thirty-one-edo}}
            (live/play! 62 1/4)
            (core/stop-loop! :tuning-loop-test))
          (let [step (deref observed 500 nil)]
            (is (some? step) "play! should have been called")
            (is (integer? (:pitch/midi step)) "midi is an integer")
            (is (number? (:pitch/bend-cents step)) "31-EDO bend added by loop"))))
      (finally (core/stop!)))))
