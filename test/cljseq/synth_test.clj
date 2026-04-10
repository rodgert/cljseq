; SPDX-License-Identifier: EPL-2.0
(ns cljseq.synth-test
  "Tests for cljseq.synth — synthesis graph vocabulary."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.synth  :as synth]
            [cljseq.sc     :as sc]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest builtin-synths-loaded-test
  (testing "built-in synths are registered at load time"
    (let [names (set (synth/synth-names))]
      (is (contains? names :beep))
      (is (contains? names :sine))
      (is (contains? names :saw-pad))
      (is (contains? names :blade))
      (is (contains? names :prophet))
      (is (contains? names :supersaw))
      (is (contains? names :dull-bell))
      (is (contains? names :fm))
      (is (contains? names :tb303))
      (is (contains? names :perc)))))

(deftest defsynth-registers-test
  (testing "defsynth! registers and get-synth retrieves"
    (synth/defsynth! ::test-sine
      {:args  {:freq 440 :amp 0.5}
       :graph [:out 0 [:sin-osc :freq]]})
    (let [s (synth/get-synth ::test-sine)]
      (is (map? s))
      (is (= {:freq 440 :amp 0.5} (:args s)))
      (is (= [:out 0 [:sin-osc :freq]] (:graph s))))))

(deftest defsynth-validates-args-test
  (testing "defsynth! rejects non-keyword name"
    (is (thrown? Exception (synth/defsynth! "bad-name" {:args {} :graph [:out 0]}))))
  (testing "defsynth! rejects non-map :args"
    (is (thrown? Exception (synth/defsynth! ::x {:args [] :graph [:out 0]}))))
  (testing "defsynth! rejects non-vector :graph"
    (is (thrown? Exception (synth/defsynth! ::x {:args {} :graph "bad"})))))

(deftest get-synth-returns-nil-for-unknown-test
  (testing "get-synth returns nil for unregistered synth"
    (is (nil? (synth/get-synth :does-not-exist-ever)))))

;; ---------------------------------------------------------------------------
;; Built-in synth structure
;; ---------------------------------------------------------------------------

(deftest sine-synth-structure-test
  (testing ":sine has expected args and graph shape"
    (let [s (synth/get-synth :sine)]
      (is (= 440   (get-in s [:args :freq])))
      (is (= 0.5   (get-in s [:args :amp])))
      (is (= 0.0   (get-in s [:args :pan])))
      (is (= :out  (first (:graph s)))))))

(deftest prophet-synth-structure-test
  (testing ":prophet has :cutoff and :res args"
    (let [s (synth/get-synth :prophet)]
      (is (contains? (:args s) :cutoff))
      (is (contains? (:args s) :res)))))

;; ---------------------------------------------------------------------------
;; Graph utilities
;; ---------------------------------------------------------------------------

(deftest synth-ugens-test
  (testing "synth-ugens collects all UGen names from a graph"
    (synth/defsynth! ::ugen-test
      {:args  {:freq 440 :amp 0.5}
       :graph [:out 0 [:pan2 [:* [:env-gen [:adsr 0.01 0.1 0.8 1.0] :gate :amp]
                                  [:sin-osc :freq]]
                            0.0]]})
    (let [ugens (synth/synth-ugens (synth/get-synth ::ugen-test))]
      (is (contains? ugens :out))
      (is (contains? ugens :pan2))
      (is (contains? ugens :env-gen))
      (is (contains? ugens :adsr))
      (is (contains? ugens :sin-osc))
      (is (contains? ugens :*)))))

(deftest map-graph-transforms-test
  (testing "map-graph applies f to every node"
    (synth/defsynth! ::map-test
      {:args  {:freq 440}
       :graph [:out 0 [:sin-osc :freq]]})
    (let [s        (synth/get-synth ::map-test)
          ;; Replace :freq with :pitch everywhere
          modified (synth/map-graph
                     (fn [n] (if (= n :freq) :pitch n))
                     (:graph s))]
      (is (= [:out 0 [:sin-osc :pitch]] modified)))))

(deftest transpose-synth-test
  (testing "transpose-synth shifts :freq by semitones"
    (synth/defsynth! ::transpose-test
      {:args  {:freq 440.0 :amp 0.5}
       :graph [:out 0 [:sin-osc :freq]]})
    (let [s  (synth/get-synth ::transpose-test)
          s' (synth/transpose-synth s 12)]
      ;; One octave up: 440 * 2 = 880
      (is (< (Math/abs (- 880.0 (get-in s' [:args :freq]))) 0.01)))))

(deftest scale-amp-test
  (testing "scale-amp multiplies :amp default"
    (synth/defsynth! ::amp-test
      {:args {:freq 440 :amp 0.5} :graph [:out 0 [:sin-osc :freq]]})
    (let [s  (synth/get-synth ::amp-test)
          s' (synth/scale-amp s 0.5)]
      (is (< (Math/abs (- 0.25 (get-in s' [:args :amp]))) 0.001)))))

(deftest replace-arg-test
  (testing "replace-arg substitutes an arg default"
    (synth/defsynth! ::replace-test
      {:args {:freq 440 :amp 0.5} :graph [:out 0 [:sin-osc :freq]]})
    (let [s  (synth/get-synth ::replace-test)
          s' (synth/replace-arg s :freq 880)]
      (is (= 880 (get-in s' [:args :freq])))
      (is (= 0.5 (get-in s' [:args :amp]))))))

;; ---------------------------------------------------------------------------
;; compile-synth dispatch
;; ---------------------------------------------------------------------------

(deftest compile-synth-unknown-backend-test
  (testing "compile-synth throws on unknown backend"
    (is (thrown? Exception (synth/compile-synth :no-such-backend :sine)))))

;; ---------------------------------------------------------------------------
;; Sonic-Pi / Overtone parity synths — registration and compile smoke tests
;; ---------------------------------------------------------------------------

(deftest parity-synths-registered-test
  (testing "all Sonic-Pi parity synths are registered"
    (let [names (set (synth/synth-names))]
      (doseq [id [:saw :tri :pulse :subpulse :dsaw :dpulse :dtri
                  :pretty-bell :pluck :hollow :zawa :dark-ambience
                  :growl :noise :bass]]
        (is (contains? names id) (str id " not registered"))))))

(deftest saw-compiles-test
  (testing ":saw compiles to Saw.ar"
    (let [sd (synth/compile-synth :sc :saw)]
      (is (string? sd))
      (is (.contains sd "Saw.ar")))))

(deftest tri-compiles-test
  (testing ":tri compiles to LFTri.ar"
    (let [sd (synth/compile-synth :sc :tri)]
      (is (.contains sd "LFTri.ar")))))

(deftest pulse-compiles-test
  (testing ":pulse compiles with width arg"
    (let [sd (synth/compile-synth :sc :pulse)]
      (is (.contains sd "Pulse.ar"))
      (is (.contains sd "width")))))

(deftest subpulse-compiles-test
  (testing ":subpulse contains sub-octave division"
    (let [sd (synth/compile-synth :sc :subpulse)]
      (is (.contains sd "Pulse.ar"))
      (is (.contains sd "sub_amp")))))

(deftest dsaw-compiles-test
  (testing ":dsaw has two Saw oscillators"
    (let [sd (synth/compile-synth :sc :dsaw)]
      (is (.contains sd "Mix.ar"))
      (is (.contains sd "Saw.ar"))
      (is (.contains sd "detune")))))

(deftest dpulse-compiles-test
  (testing ":dpulse has two Pulse oscillators"
    (let [sd (synth/compile-synth :sc :dpulse)]
      (is (.contains sd "Mix.ar"))
      (is (.contains sd "Pulse.ar")))))

(deftest dtri-compiles-test
  (testing ":dtri has two LFTri oscillators"
    (let [sd (synth/compile-synth :sc :dtri)]
      (is (.contains sd "Mix.ar"))
      (is (.contains sd "LFTri.ar")))))

(deftest pretty-bell-compiles-test
  (testing ":pretty-bell has four sine partials"
    (let [sd (synth/compile-synth :sc :pretty-bell)]
      (is (.contains sd "Mix.ar"))
      (is (.contains sd "SinOsc.ar"))
      ;; 4.16x partial gives the inharmonic top
      (is (.contains sd "4.16")))))

(deftest pluck-compiles-test
  (testing ":pluck uses Pluck UGen with period-derived delay"
    (let [sd (synth/compile-synth :sc :pluck)]
      (is (.contains sd "Pluck.ar"))
      (is (.contains sd "WhiteNoise.ar"))
      (is (.contains sd "coef")))))

(deftest hollow-compiles-test
  (testing ":hollow uses BPF"
    (let [sd (synth/compile-synth :sc :hollow)]
      (is (.contains sd "BPF.ar")))))

(deftest zawa-compiles-test
  (testing ":zawa uses VarSaw with SinOsc modulation"
    (let [sd (synth/compile-synth :sc :zawa)]
      (is (.contains sd "VarSaw.ar"))
      (is (.contains sd "SinOsc.ar"))
      (is (.contains sd "lfo_rate")))))

(deftest dark-ambience-compiles-test
  (testing ":dark-ambience uses BrownNoise and RLPF"
    (let [sd (synth/compile-synth :sc :dark-ambience)]
      (is (.contains sd "BrownNoise.ar"))
      (is (.contains sd "RLPF.ar")))))

(deftest growl-compiles-test
  (testing ":growl has sub-octave saw through RLPF"
    (let [sd (synth/compile-synth :sc :growl)]
      (is (.contains sd "RLPF.ar"))
      (is (.contains sd "Saw.ar")))))

(deftest noise-compiles-test
  (testing ":noise uses WhiteNoise through RLPF"
    (let [sd (synth/compile-synth :sc :noise)]
      (is (.contains sd "WhiteNoise.ar"))
      (is (.contains sd "RLPF.ar")))))

(deftest bass-compiles-test
  (testing ":bass has LPF and sub-octave voice"
    (let [sd (synth/compile-synth :sc :bass)]
      (is (.contains sd "LPF.ar"))
      (is (.contains sd "Mix.ar")))))

;; ---------------------------------------------------------------------------
;; Solar42 instrument model — synth registration and compile smoke tests
;; ---------------------------------------------------------------------------

(deftest solar42-synths-registered-test
  (testing "Solar42 voice synths are registered"
    (let [names (set (synth/synth-names))]
      (is (contains? names :solar-drone-voice))
      (is (contains? names :solar-vco-voice))
      (is (contains? names :solar-papa-voice))
      (is (contains? names :solar-filter)))))

(deftest solar-drone-voice-compiles-test
  (testing ":solar-drone-voice compiles with 6 saws and on/tune params"
    (let [sd (synth/compile-synth :sc :solar-drone-voice)]
      (is (.contains sd "Saw.ar"))
      (is (.contains sd "Mix.ar"))
      (is (.contains sd "on1"))
      (is (.contains sd "tune1"))
      (is (.contains sd "detune")))))

(deftest solar-vco-voice-compiles-test
  (testing ":solar-vco-voice compiles with VarSaw and Pulse sub"
    (let [sd (synth/compile-synth :sc :solar-vco-voice)]
      (is (.contains sd "VarSaw.ar"))
      (is (.contains sd "Pulse.ar"))
      (is (.contains sd "pwm_rate")))))

(deftest solar-papa-voice-compiles-test
  (testing ":solar-papa-voice compiles with FM, AM, Latch, Impulse"
    (let [sd (synth/compile-synth :sc :solar-papa-voice)]
      (is (.contains sd "SinOsc.ar"))
      (is (.contains sd "Latch.ar"))
      (is (.contains sd "Impulse.ar"))
      (is (.contains sd "fm_depth"))
      (is (.contains sd "noise_mix")))))

(deftest solar-filter-compiles-test
  (testing ":solar-filter has two cascaded RLPF stages"
    (let [sd (synth/compile-synth :sc :solar-filter)]
      (is (.contains sd "RLPF.ar"))
      ;; Two RLPF calls for dual-stage filter
      (is (< 1 (count (re-seq #"RLPF\.ar" sd)))))))
