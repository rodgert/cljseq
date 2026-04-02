; SPDX-License-Identifier: EPL-2.0
(ns cljseq.dsl-test
  "Unit tests for cljseq.dsl — synth context, play!, phrase!, arp!, ring/tick!."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.clock :as clock]
            [cljseq.core  :as core]
            [cljseq.ctrl  :as ctrl]
            [cljseq.dsl   :as dsl]
            [cljseq.loop  :as loop-ns]
            [cljseq.phasor :as phasor]))

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
    (let [r (dsl/ring :C4 :E4 :G4)]
      (is (= :C4 (dsl/tick! r)))
      (is (= :E4 (dsl/tick! r)))
      (is (= :G4 (dsl/tick! r)))
      (is (= :C4 (dsl/tick! r)) "wraps back to start")))
  (testing "independent rings do not share state"
    (let [r1 (dsl/ring :A :B)
          r2 (dsl/ring :X :Y)]
      (dsl/tick! r1)
      (is (= :X (dsl/tick! r2)) "r2 unaffected by r1 advance"))))

;; ---------------------------------------------------------------------------
;; play! — step map normalisation
;; ---------------------------------------------------------------------------

(deftest play-normalises-keyword-test
  (testing "keyword note is resolved to :pitch/midi"
    (let [[step] (capture-play! #(dsl/play! :C4 1/4))]
      (is (= 60 (:pitch/midi step)))
      (is (= 1/4 (:dur/beats step))))))

(deftest play-normalises-integer-test
  (testing "integer note is stored as :pitch/midi"
    (let [[step] (capture-play! #(dsl/play! 64 1/2))]
      (is (= 64 (:pitch/midi step)))
      (is (= 1/2 (:dur/beats step))))))

(deftest play-passthrough-map-test
  (testing "step map is passed through with dur merged"
    (let [[step] (capture-play!
                  #(dsl/play! {:pitch/midi 48 :midi/channel 5} 1/8))]
      (is (= 48 (:pitch/midi step)))
      (is (= 1/8 (:dur/beats step)))
      (is (= 5 (:midi/channel step))))))

(deftest play-default-dur-test
  (testing "omitting dur uses *default-dur*"
    (let [[step] (capture-play!
                  #(binding [dsl/*default-dur* 1/2]
                     (dsl/play! :D4)))]
      (is (= 1/2 (:dur/beats step))))))

(deftest play-step-map-dur-beats-test
  (testing ":dur/beats in step map takes priority over dur arg"
    (let [[step] (capture-play!
                  #(dsl/play! {:pitch/midi 60 :dur/beats 1/2} 1/8))]
      (is (= 1/2 (:dur/beats step)) "step map :dur/beats wins over arg"))))

;; ---------------------------------------------------------------------------
;; play! — synth context routing
;; ---------------------------------------------------------------------------

(deftest play-no-context-channel-default-test
  (testing "no *synth-ctx* → no :midi/channel injected (core uses its own default)"
    (let [[step] (capture-play!
                  #(binding [loop-ns/*synth-ctx* nil]
                     (dsl/play! :C4 1/4)))]
      (is (nil? (:midi/channel step)) "channel not in step when ctx is nil"))))

(deftest play-context-sets-channel-test
  (testing "*synth-ctx* :midi/channel appears in the step map"
    (let [ctx {:midi/channel 3 :mod/velocity 90}
          [step] (capture-play!
                  #(binding [loop-ns/*synth-ctx* ctx]
                     (dsl/play! :C4 1/4)))]
      (is (= 3 (:midi/channel step)))
      (is (= 90 (:mod/velocity step))))))

(deftest play-step-map-overrides-context-test
  (testing "per-note :midi/channel in step map overrides *synth-ctx*"
    (let [ctx {:midi/channel 3 :mod/velocity 90}
          [step] (capture-play!
                  #(binding [loop-ns/*synth-ctx* ctx]
                     (dsl/play! {:pitch/midi 60 :midi/channel 7} 1/4)))]
      (is (= 7 (:midi/channel step)) "per-note channel wins"))))

;; ---------------------------------------------------------------------------
;; with-synth
;; ---------------------------------------------------------------------------

(deftest with-synth-binds-context-test
  (testing "with-synth binds *synth-ctx* for the duration of the block"
    (let [ctx  {:midi/channel 4}
          calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))]
        (dsl/with-synth ctx
          (dsl/play! :E4 1/4)))
      (is (= 4 (:midi/channel (first @calls))))))
  (testing "with-synth does not affect *synth-ctx* after the block"
    (binding [loop-ns/*synth-ctx* nil]
      (dsl/with-synth {:midi/channel 9} (dsl/play! :C4 1/4))
      (is (nil? loop-ns/*synth-ctx*) "ctx restored after with-synth"))))

;; ---------------------------------------------------------------------------
;; use-synth!
;; ---------------------------------------------------------------------------

(deftest use-synth-sets-root-test
  (testing "use-synth! sets the root binding of *synth-ctx*"
    (let [original loop-ns/*synth-ctx*]
      (try
        (let [ctx {:midi/channel 6}]
          (dsl/use-synth! ctx)
          (is (= ctx loop-ns/*synth-ctx*)))
        (finally
          (dsl/use-synth! original)))))
  (testing "use-synth! nil clears context"
    (let [original loop-ns/*synth-ctx*]
      (try
        (dsl/use-synth! {:midi/channel 2})
        (dsl/use-synth! nil)
        (is (nil? loop-ns/*synth-ctx*))
        (finally
          (dsl/use-synth! original))))))

;; ---------------------------------------------------------------------------
;; phrase!
;; ---------------------------------------------------------------------------

(deftest phrase-uniform-dur-test
  (testing "phrase! plays each note with the given :dur"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (dsl/phrase! [:C4 :E4 :G4] :dur 1/8))
      (is (= 3 (count @calls)))
      (is (= [60 64 67] (mapv :pitch/midi @calls)))
      (is (every? #(= 1/8 (:dur/beats %)) @calls)))))

(deftest phrase-inherits-context-test
  (testing "phrase! inherits *synth-ctx* channel"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (binding [loop-ns/*synth-ctx* {:midi/channel 5}]
          (dsl/phrase! [:C4 :E4] :dur 1/4)))
      (is (every? #(= 5 (:midi/channel %)) @calls)))))

;; ---------------------------------------------------------------------------
;; arp!
;; ---------------------------------------------------------------------------

(deftest arp-up-test
  (testing "arp! :up plays root, third, fifth in order"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (dsl/arp! :C4 :major :up 1/16))
      (is (= [60 64 67] (mapv :pitch/midi @calls))))))

(deftest arp-down-test
  (testing "arp! :down plays fifth, third, root"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (dsl/arp! :C4 :major :down 1/16))
      (is (= [67 64 60] (mapv :pitch/midi @calls))))))

(deftest arp-up-down-test
  (testing "arp! :up-down bounces: root-third-fifth-third-root"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (dsl/arp! :C4 :major :up-down 1/16))
      (is (= [60 64 67 64 60] (mapv :pitch/midi @calls))))))

(deftest arp-inherits-context-test
  (testing "arp! inherits *synth-ctx* channel"
    (let [calls (atom [])]
      (with-redefs [core/play! (fn [step] (swap! calls conj step))
                    core/sleep! (fn [_] nil)]
        (binding [loop-ns/*synth-ctx* {:midi/channel 10}]
          (dsl/arp! :C4 :major :up 1/16)))
      (is (every? #(= 10 (:midi/channel %)) @calls)))))

;; ---------------------------------------------------------------------------
;; set-default-dur! / with-dur
;; ---------------------------------------------------------------------------

(deftest set-default-dur-test
  (testing "set-default-dur! changes root *default-dur*"
    (let [original dsl/*default-dur*]
      (try
        (dsl/set-default-dur! 1/8)
        (is (= 1/8 dsl/*default-dur*))
        (finally
          (dsl/set-default-dur! original))))))

(deftest with-dur-scopes-default-dur-test
  (testing "with-dur binds *default-dur* only within the block"
    (let [original dsl/*default-dur*]
      (dsl/with-dur 1/16
        (is (= 1/16 dsl/*default-dur*) "within block"))
      (is (= original dsl/*default-dur*) "restored after block")))
  (testing "play! inside with-dur uses the scoped duration"
    (let [[step] (capture-play!
                  #(dsl/with-dur 1/2
                     (dsl/play! :C4)))]
      (is (= 1/2 (:dur/beats step))))))

;; ---------------------------------------------------------------------------
;; ring-reset! / ring-len
;; ---------------------------------------------------------------------------

(deftest ring-reset-test
  (testing "ring-reset! returns ring to first element"
    (let [r (dsl/ring :A :B :C)]
      (dsl/tick! r)
      (dsl/tick! r)
      (dsl/ring-reset! r)
      (is (= :A (dsl/tick! r)) "back to first element after reset"))))

(deftest ring-len-test
  (testing "ring-len returns number of elements"
    (is (= 3 (dsl/ring-len (dsl/ring :A :B :C))))
    (is (= 1 (dsl/ring-len (dsl/ring :solo))))
    (is (= 0 (dsl/ring-len (dsl/ring))))))

;; ---------------------------------------------------------------------------
;; defring
;; ---------------------------------------------------------------------------

(deftest defring-creates-var-test
  (testing "defring creates a cycling ring"
    (core/start! :bpm 120)
    (try
      (dsl/defring test-defring-a :X :Y :Z)
      (is (= :X (dsl/tick! test-defring-a)))
      (is (= :Y (dsl/tick! test-defring-a)))
      (finally (core/stop!)))))

(deftest defring-registers-ctrl-node-test
  (testing "defring registers the ring in the ctrl tree at [:rings/<name>]"
    (core/start! :bpm 120)
    (try
      (dsl/defring test-defring-b :P :Q)
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
        (dsl/use-mod! ctx)
        (is (= ctx loop-ns/*mod-ctx*))
        (finally
          (dsl/use-mod! original))))))

(deftest with-mod-scopes-mod-ctx-test
  (testing "with-mod binds *mod-ctx* only within the block"
    (let [original loop-ns/*mod-ctx*
          ctx      {[:resonance] :placeholder}]
      (dsl/with-mod ctx
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
            lfo (dsl/ring 0.5)]          ; use a simple value; any ITemporalValue works
        ;; Use a constant ITemporalValue (just a record that returns 0.75)
        (let [const-mod (reify clock/ITemporalValue
                          (sample    [_ _beat] 0.75)
                          (next-edge [_ beat]  (+ beat 1.0)))]
          (binding [loop-ns/*mod-ctx*    {[:test/tmod-cutoff] const-mod}
                    loop-ns/*virtual-time* 0.0]
            (dsl/tick-mods!))
          (is (= 0.75 (ctrl/get [:test/tmod-cutoff])) "ctrl node updated with sampled value")))
      (finally (core/stop!)))))

(deftest tick-mods-noop-when-nil-test
  (testing "tick-mods! is a no-op when *mod-ctx* is nil"
    (binding [loop-ns/*mod-ctx* nil]
      (is (nil? (dsl/tick-mods!))))))

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
            (dsl/play! :C4 1/4)
            (core/stop-loop! :ctx-test))
          (let [step (deref observed 500 nil)]
            (is (some? step) "play! should have been called")
            (is (= 8 (:midi/channel step))))))
      (finally (core/stop!)))))
