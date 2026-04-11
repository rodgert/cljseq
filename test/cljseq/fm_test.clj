; SPDX-License-Identifier: EPL-2.0
(ns cljseq.fm-test
  (:require [clojure.test  :refer [deftest is testing are]]
            [clojure.string :as str]
            [cljseq.fm     :as fm]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- contains-str? [s substr]
  (str/includes? s substr))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest registry-def-get-test
  (testing "def-fm! returns the name keyword"
    (is (= :test-op-a (fm/def-fm! :test-op-a
                        {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}]
                         :fm/carriers  #{1}
                         :fm/routing   {}}))))

  (testing "get-fm returns the definition with :fm/name injected"
    (let [d (fm/get-fm :test-op-a)]
      (is (= :test-op-a (:fm/name d)))
      (is (= #{1} (:fm/carriers d)))))

  (testing "get-fm returns nil for unknown name"
    (is (nil? (fm/get-fm :no-such-fm-xyz))))

  (testing "fm-names includes registered synth"
    (is (some #{:test-op-a} (fm/fm-names))))

  (testing "built-in named synths are present"
    (let [names (set (fm/fm-names))]
      (is (contains? names :dx-ep))
      (is (contains? names :dx-bass))
      (is (contains? names :dx-bell))
      (is (contains? names :dt-metal))
      (is (contains? names :dx-brass)))))

;; ---------------------------------------------------------------------------
;; Algorithm templates
;; ---------------------------------------------------------------------------

(deftest fm-algorithm-templates-test
  (testing "all five templates are accessible"
    (are [k] (map? (fm/fm-algorithm k))
      :2op
      :4op-stack
      :2pairs
      :3-to-1
      :additive-4))

  (testing ":2op has one carrier and one modulator"
    (let [alg (fm/fm-algorithm :2op)]
      (is (= #{1} (:fm/carriers alg)))
      (is (= {2 1} (:fm/routing alg)))
      (is (= 2 (count (:fm/operators alg))))))

  (testing ":4op-stack is a series chain"
    (let [alg (fm/fm-algorithm :4op-stack)]
      (is (= #{1} (:fm/carriers alg)))
      (is (= {2 1, 3 2, 4 3} (:fm/routing alg)))
      (is (= 4 (count (:fm/operators alg))))))

  (testing ":2pairs has two carriers and two independent modulators"
    (let [alg (fm/fm-algorithm :2pairs)]
      (is (= #{1 3} (:fm/carriers alg)))
      (is (= {2 1, 4 3} (:fm/routing alg)))))

  (testing ":3-to-1 has one carrier with three parallel modulators"
    (let [alg (fm/fm-algorithm :3-to-1)]
      (is (= #{1} (:fm/carriers alg)))
      (is (= {2 1, 3 1, 4 1} (:fm/routing alg)))))

  (testing ":additive-4 has four carriers and empty routing"
    (let [alg (fm/fm-algorithm :additive-4)]
      (is (= #{1 2 3 4} (:fm/carriers alg)))
      (is (empty? (:fm/routing alg)))))

  (testing "unknown template throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fm/fm-algorithm :no-such-template)))))

;; ---------------------------------------------------------------------------
;; Topological sort (via SC compilation output order)
;; ---------------------------------------------------------------------------

(deftest topo-sort-via-sc-test
  (testing "2op: modulator var declared before carrier"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      ;; op_2 (modulator) must appear before op_1 (carrier)
      (is (< (.indexOf code "var op_2") (.indexOf code "var op_1")))))

  (testing "4op-stack: ops declared in modulator-first order"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :4op-stack))]
      ;; op_4 → op_3 → op_2 → op_1; deepest first
      (is (< (.indexOf code "var op_4") (.indexOf code "var op_3")))
      (is (< (.indexOf code "var op_3") (.indexOf code "var op_2")))
      (is (< (.indexOf code "var op_2") (.indexOf code "var op_1")))))

  (testing "additive-4: all vars present"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :additive-4))]
      (is (contains-str? code "op_1"))
      (is (contains-str? code "op_2"))
      (is (contains-str? code "op_3"))
      (is (contains-str? code "op_4")))))

;; ---------------------------------------------------------------------------
;; SC compiler
;; ---------------------------------------------------------------------------

(deftest compile-fm-sc-structure-test
  (testing "output is a SynthDef string"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (string? code))
      (is (contains-str? code "SynthDef(\\"))
      (is (contains-str? code "}).add;"))))

  (testing "arg list includes freq and amp"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (contains-str? code "freq="))
      (is (contains-str? code "amp="))))

  (testing "gate=1 is always present in arg list"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (contains-str? code "gate=1"))))

  (testing "output bus uses Pan2.ar"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (contains-str? code "Pan2.ar"))))

  (testing "Out.ar present"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (contains-str? code "Out.ar"))))

  (testing "EnvGen.ar present for each operator"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (contains-str? code "EnvGen.ar"))))

  (testing "carrier envelope gets doneAction: 2"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (contains-str? code "doneAction: 2"))))

  (testing "SinOsc.ar used for each operator"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 2.5 :level 0.8 :env :perc}]
                                   :fm/carriers  #{1}
                                   :fm/routing   {}})]
      (is (contains-str? code "SinOsc.ar"))))

  (testing "ratio appears in the SinOsc frequency expression"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 2.5 :level 0.8 :env :adsr}]
                                   :fm/carriers  #{1}
                                   :fm/routing   {}})]
      (is (contains-str? code "2.5"))))

  (testing "2pairs produces Mix.ar for multiple carriers"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2pairs))]
      (is (contains-str? code "Mix.ar"))))

  (testing "single carrier does NOT use Mix.ar"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (not (contains-str? code "Mix.ar")))))

  (testing "modulation sum appears in carrier op expression"
    ;; op_1 is carrier, modulated by op_2 → SinOsc.ar(... + op_2)
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (contains-str? code "op_2")))))

(deftest compile-fm-sc-env-types-test
  (testing ":perc env uses Env.perc"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :perc}]
                                   :fm/carriers  #{1}
                                   :fm/routing   {}})]
      (is (contains-str? code "Env.perc"))))

  (testing ":adsr env uses Env.adsr"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}]
                                   :fm/carriers  #{1}
                                   :fm/routing   {}})]
      (is (contains-str? code "Env.adsr"))))

  (testing ":short-perc env uses Env.perc"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :short-perc}]
                                   :fm/carriers  #{1}
                                   :fm/routing   {}})]
      (is (contains-str? code "Env.perc"))))

  (testing "map env with adsr keys uses Env.adsr"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0
                                                   :env {:attack 0.01 :decay 0.2
                                                         :sustain 0.5 :release 1.0}}]
                                   :fm/carriers  #{1}
                                   :fm/routing   {}})]
      (is (contains-str? code "Env.adsr"))))

  (testing "map env with only attack/decay uses Env.perc"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0
                                                   :env {:attack 0.001 :decay 0.3}}]
                                   :fm/carriers  #{1}
                                   :fm/routing   {}})]
      (is (contains-str? code "Env.perc")))))

(deftest compile-fm-sc-named-synths-test
  (testing "all named synths compile to non-empty strings without error"
    (doseq [nm [:dx-ep :dx-bass :dx-bell :dt-metal :dx-brass]]
      (let [code (fm/compile-fm :sc nm)]
        (is (string? code) (str nm " should compile to string"))
        (is (pos? (count code)) (str nm " should not be empty"))
        (is (contains-str? code "SynthDef") (str nm " should contain SynthDef"))
        (is (contains-str? code "}).add;") (str nm " should end with }).add;")))))

  (testing ":dx-ep synth name appears in SynthDef"
    (let [code (fm/compile-fm :sc :dx-ep)]
      (is (contains-str? code "dx_ep"))))

  (testing ":dx-bass has 4 op vars"
    (let [code (fm/compile-fm :sc :dx-bass)]
      (is (contains-str? code "op_1"))
      (is (contains-str? code "op_2"))
      (is (contains-str? code "op_3"))
      (is (contains-str? code "op_4"))))

  (testing ":dx-bell has two carriers (Mix.ar)"
    (let [code (fm/compile-fm :sc :dx-bell)]
      (is (contains-str? code "Mix.ar"))))

  (testing ":dx-brass has 3 ops"
    (let [code (fm/compile-fm :sc :dx-brass)]
      (is (contains-str? code "op_1"))
      (is (contains-str? code "op_2"))
      (is (contains-str? code "op_3"))))

  (testing "unknown name throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fm/compile-fm :sc :no-such-fm-xxx)))))

;; ---------------------------------------------------------------------------
;; Digitone compiler
;; ---------------------------------------------------------------------------

(deftest compile-fm-digitone-structure-test
  (testing "returns map with :algorithm :ccs :note"
    (let [r (fm/compile-fm :digitone (fm/fm-algorithm :2op))]
      (is (map? r))
      (is (integer? (:algorithm r)))
      (is (vector? (:ccs r)))
      (is (string? (:note r)))))

  (testing "algorithm index is in range 0–7"
    (doseq [tpl [:2op :4op-stack :2pairs :3-to-1 :additive-4]]
      (let [r (fm/compile-fm :digitone (fm/fm-algorithm tpl))]
        (is (<= 0 (:algorithm r) 7) (str tpl " algorithm out of range")))))

  (testing "CC values are in 0–127"
    (doseq [tpl [:2op :4op-stack :2pairs :3-to-1 :additive-4]]
      (let [r    (fm/compile-fm :digitone (fm/fm-algorithm tpl))
            vals (map :value (:ccs r))]
        (doseq [v vals]
          (is (<= 0 v 127) (str tpl " has out-of-range CC value " v))))))

  (testing "additive-4 maps to algorithm 7"
    (let [r (fm/compile-fm :digitone (fm/fm-algorithm :additive-4))]
      (is (= 7 (:algorithm r)))))

  (testing "2pairs maps to algorithm 1 (two pairs)"
    (let [r (fm/compile-fm :digitone (fm/fm-algorithm :2pairs))]
      (is (= 1 (:algorithm r)))))

  (testing "4op-stack maps to algorithm 0 (full series stack)"
    (let [r (fm/compile-fm :digitone (fm/fm-algorithm :4op-stack))]
      (is (= 0 (:algorithm r)))))

  (testing "CC 90 (algorithm) is always present"
    (let [r    (fm/compile-fm :digitone (fm/fm-algorithm :2op))
          ccs  (map :cc (:ccs r))]
      (is (some #{90} ccs))))

  (testing "CC 91 (ratio-c carrier) is always present when carrier exists"
    (let [r    (fm/compile-fm :digitone (fm/fm-algorithm :2op))
          ccs  (map :cc (:ccs r))]
      (is (some #{91} ccs))))

  (testing "all named synths compile without error"
    (doseq [nm [:dx-ep :dx-bass :dx-bell :dt-metal :dx-brass]]
      (let [r (fm/compile-fm :digitone nm)]
        (is (map? r) (str nm " should return map"))
        (is (contains? r :algorithm))
        (is (contains? r :ccs))))))

(deftest digitone-ratio-cc-test
  (testing "ratio 0.25 maps to CC 0 (minimum)"
    ;; Test via a single-op synth with ratio 0.25
    (let [r (fm/compile-fm :digitone
                           {:fm/operators [{:id 1 :ratio 0.25 :level 1.0 :env :adsr}]
                            :fm/carriers  #{1}
                            :fm/routing   {}})
          cc91 (first (filter #(= 91 (:cc %)) (:ccs r)))]
      (is (= 0 (:value cc91)))))

  (testing "ratio 1.0 maps to a mid-range CC value"
    (let [r (fm/compile-fm :digitone
                           {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}]
                            :fm/carriers  #{1}
                            :fm/routing   {}})
          cc91 (first (filter #(= 91 (:cc %)) (:ccs r)))]
      ;; ratio 1.0 should be between 0 and 127
      (is (< 0 (:value cc91) 127))))

  (testing "ratio 16.0 maps to CC 127 (maximum)"
    (let [r (fm/compile-fm :digitone
                           {:fm/operators [{:id 1 :ratio 16.0 :level 1.0 :env :adsr}]
                            :fm/carriers  #{1}
                            :fm/routing   {}})
          cc91 (first (filter #(= 91 (:cc %)) (:ccs r)))]
      (is (= 127 (:value cc91))))))

;; ---------------------------------------------------------------------------
;; Leviasynth compiler
;; ---------------------------------------------------------------------------

(deftest compile-fm-leviasynth-structure-test
  (testing "returns map with :ccs and :note"
    (let [r (fm/compile-fm :leviasynth (fm/fm-algorithm :2op))]
      (is (map? r))
      (is (vector? (:ccs r)))
      (is (string? (:note r)))))

  (testing "each CC entry has :osc :path :cc :value"
    (let [r    (fm/compile-fm :leviasynth (fm/fm-algorithm :2op))
          ccs  (:ccs r)]
      (doseq [c ccs]
        (is (contains? c :osc))
        (is (contains? c :path))
        (is (contains? c :cc))
        (is (contains? c :value)))))

  (testing "CC values are in 0–127"
    (let [r    (fm/compile-fm :leviasynth (fm/fm-algorithm :2pairs))
          vals (map :value (:ccs r))]
      (doseq [v vals]
        (is (<= 0 v 127)))))

  (testing "level CCs are in range 24–31"
    (let [r    (fm/compile-fm :leviasynth (fm/fm-algorithm :2op))
          lccs (filter #(= :level (last (:path %))) (:ccs r))]
      (doseq [c lccs]
        (is (<= 24 (:cc c) 31) (str "Level CC out of range: " (:cc c))))))

  (testing "all named synths compile without error"
    (doseq [nm [:dx-ep :dx-bass :dx-bell :dt-metal :dx-brass]]
      (let [r (fm/compile-fm :leviasynth nm)]
        (is (map? r) (str nm " should return map"))
        (is (vector? (:ccs r)))))))

(deftest leviasynth-cc-38-gap-test
  (testing "OSC 1-5 pitch CCs are 33–37"
    (let [r    (fm/compile-fm :leviasynth
                              {:fm/operators (mapv (fn [i]
                                                     {:id (inc i) :ratio 1.0 :level 1.0 :env :adsr})
                                                   (range 8))
                               :fm/carriers  #{1}
                               :fm/routing   {}})
          pitch-ccs (filter #(= :pitch (last (:path %))) (:ccs r))]
      ;; OSC 1→CC 33, OSC 2→34, OSC 3→35, OSC 4→36, OSC 5→37
      (let [by-osc (into {} (map (fn [c] [(:osc c) (:cc c)]) pitch-ccs))]
        (is (= 33 (get by-osc 1)))
        (is (= 34 (get by-osc 2)))
        (is (= 35 (get by-osc 3)))
        (is (= 36 (get by-osc 4)))
        (is (= 37 (get by-osc 5))))))

  (testing "OSC 6 pitch CC is 39 (skips 38)"
    (let [r    (fm/compile-fm :leviasynth
                              {:fm/operators (mapv (fn [i]
                                                     {:id (inc i) :ratio 1.0 :level 1.0 :env :adsr})
                                                   (range 8))
                               :fm/carriers  #{1}
                               :fm/routing   {}})
          pitch-ccs (filter #(= :pitch (last (:path %))) (:ccs r))
          by-osc    (into {} (map (fn [c] [(:osc c) (:cc c)]) pitch-ccs))]
      (is (= 39 (get by-osc 6)))
      (is (= 40 (get by-osc 7)))
      (is (= 41 (get by-osc 8)))))

  (testing "CC 38 never appears in pitch CCs"
    (let [r    (fm/compile-fm :leviasynth
                              {:fm/operators (mapv (fn [i]
                                                     {:id (inc i) :ratio 1.0 :level 1.0 :env :adsr})
                                                   (range 8))
                               :fm/carriers  #{1}
                               :fm/routing   {}})
          pitch-ccs (filter #(= :pitch (last (:path %))) (:ccs r))
          cc-nums   (set (map :cc pitch-ccs))]
      (is (not (contains? cc-nums 38))))))

(deftest leviasynth-pitch-ratio-test
  (testing "ratio 1.0 maps to CC 64 (center)"
    ;; 2-op, carrier has ratio 1.0
    (let [r    (fm/compile-fm :leviasynth
                              {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                             {:id 2 :ratio 1.0 :level 0.5 :env :perc}]
                               :fm/carriers  #{1}
                               :fm/routing   {2 1}})
          pitch-ccs (filter #(= :pitch (last (:path %))) (:ccs r))
          osc1 (first (filter #(= 1 (:osc %)) pitch-ccs))]
      (is (= 64 (:value osc1)))))

  (testing "ratio 2.0 (one octave up) maps above 64"
    (let [r    (fm/compile-fm :leviasynth
                              {:fm/operators [{:id 1 :ratio 2.0 :level 1.0 :env :adsr}]
                               :fm/carriers  #{1}
                               :fm/routing   {}})
          pitch-ccs (filter #(= :pitch (last (:path %))) (:ccs r))
          osc1 (first (filter #(= 1 (:osc %)) pitch-ccs))]
      (is (> (:value osc1) 64))))

  (testing "ratio 0.5 (one octave down) maps below 64"
    (let [r    (fm/compile-fm :leviasynth
                              {:fm/operators [{:id 1 :ratio 0.5 :level 1.0 :env :adsr}]
                               :fm/carriers  #{1}
                               :fm/routing   {}})
          pitch-ccs (filter #(= :pitch (last (:path %))) (:ccs r))
          osc1 (first (filter #(= 1 (:osc %)) pitch-ccs))]
      (is (< (:value osc1) 64)))))

;; ---------------------------------------------------------------------------
;; Waveform selection
;; ---------------------------------------------------------------------------

(deftest waveform-selection-test
  (testing ":waveform :sin (default) emits SinOsc.ar"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :waveform :sin}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "SinOsc.ar"))))

  (testing ":waveform :saw emits Saw.ar"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :waveform :saw}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "Saw.ar"))
      (is (not (contains-str? code "SinOsc.ar")))))

  (testing ":waveform :tri emits LFTri.ar"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :waveform :tri}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "LFTri.ar"))))

  (testing ":waveform :pulse emits Pulse.ar"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :waveform :pulse}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "Pulse.ar"))))

  (testing ":waveform :sin-fb emits SinOscFB.ar"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :waveform :sin-fb :feedback 0.4}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "SinOscFB.ar"))))

  (testing "mixed waveforms in one synth"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :waveform :sin}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :perc
                                                   :waveform :saw}]
                                   :fm/carriers #{1} :fm/routing {2 1}})]
      (is (contains-str? code "SinOsc.ar"))
      (is (contains-str? code "Saw.ar")))))

;; ---------------------------------------------------------------------------
;; Self-feedback
;; ---------------------------------------------------------------------------

(deftest self-feedback-test
  (testing ":feedback > 0 auto-selects SinOscFB when no :waveform specified"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :feedback 0.3}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "SinOscFB.ar"))
      (is (contains-str? code "0.3"))))

  (testing ":feedback 0 uses SinOsc (no feedback)"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :feedback 0.0}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "SinOsc.ar"))
      (is (not (contains-str? code "SinOscFB.ar")))))

  (testing "feedback value appears in SinOscFB call"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr
                                                   :feedback 0.75}]
                                   :fm/carriers #{1} :fm/routing {}})]
      (is (contains-str? code "0.75")))))

;; ---------------------------------------------------------------------------
;; TZFM — through-zero FM
;; ---------------------------------------------------------------------------

(deftest tzfm-test
  (testing "TZFM routing scales modulation by freq * ratio"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :perc}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 {:to 1 :depth 1.0 :tzfm? true}}})]
      ;; TZFM: op_2 * freq * ratio * depth should appear
      (is (contains-str? code "op_2 * freq"))
      (is (contains-str? code "SinOsc.ar"))))

  (testing "non-TZFM routing does not scale by freq"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :perc}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 {:to 1 :depth 1.0 :tzfm? false}}})]
      ;; Non-TZFM: plain op_2 * depth, no freq factor
      (is (not (contains-str? code "op_2 * freq")))))

  (testing "shorthand routing {2 1} behaves as non-TZFM depth-1.0"
    (let [code-short    (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                           {:id 2 :ratio 2.0 :level 0.5 :env :perc}]
                                            :fm/carriers #{1}
                                            :fm/routing {2 1}})
          code-explicit (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                           {:id 2 :ratio 2.0 :level 0.5 :env :perc}]
                                            :fm/carriers #{1}
                                            :fm/routing {2 {:to 1 :depth 1.0 :tzfm? false}}})]
      (is (= code-short code-explicit))))

  (testing "routing depth appears in modulation expression"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :perc}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 {:to 1 :depth 0.6 :tzfm? false}}})]
      (is (contains-str? code "0.6")))))

;; ---------------------------------------------------------------------------
;; Cross-feedback (LocalIn / LocalOut)
;; ---------------------------------------------------------------------------

(deftest cross-feedback-test
  (testing "cross-feedback emits LocalIn.ar"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :adsr}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 1}
                                   :fm/cross-feedback [{:from 1 :to 2 :depth 0.1}]})]
      (is (contains-str? code "LocalIn.ar"))))

  (testing "cross-feedback emits LocalOut.ar"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :adsr}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 1}
                                   :fm/cross-feedback [{:from 1 :to 2 :depth 0.1}]})]
      (is (contains-str? code "LocalOut.ar"))))

  (testing "LocalIn channel count matches number of cross-feedback entries"
    ;; 2 entries → LocalIn.ar(2)
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :adsr}
                                                  {:id 3 :ratio 3.0 :level 0.4 :env :adsr}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 1, 3 2}
                                   :fm/cross-feedback [{:from 1 :to 3 :depth 0.08}
                                                       {:from 2 :to 3 :depth 0.05}]})]
      (is (contains-str? code "LocalIn.ar(2)"))))

  (testing "single cross-feedback entry uses LocalIn.ar(1)"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :adsr}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 1}
                                   :fm/cross-feedback [{:from 1 :to 2 :depth 0.1}]})]
      (is (contains-str? code "LocalIn.ar(1)"))))

  (testing "no cross-feedback → no LocalIn/LocalOut"
    (let [code (fm/compile-fm :sc (fm/fm-algorithm :2op))]
      (is (not (contains-str? code "LocalIn")))
      (is (not (contains-str? code "LocalOut")))))

  (testing "LocalOut references the from-op var"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :adsr}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 1}
                                   :fm/cross-feedback [{:from 1 :to 2 :depth 0.1}]})]
      ;; LocalOut should reference op_1
      (is (contains-str? code "op_1"))))

  (testing "LocalIn declaration appears before op var declarations"
    (let [code (fm/compile-fm :sc {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                                                  {:id 2 :ratio 2.0 :level 0.5 :env :adsr}]
                                   :fm/carriers #{1}
                                   :fm/routing {2 1}
                                   :fm/cross-feedback [{:from 1 :to 2 :depth 0.1}]})]
      (is (< (.indexOf code "LocalIn") (.indexOf code "var op_"))))))

;; ---------------------------------------------------------------------------
;; 8-op algorithm templates
;; ---------------------------------------------------------------------------

(deftest fm-8op-templates-test
  (testing "all 8-op templates are accessible"
    (are [k] (map? (fm/fm-algorithm k))
      :8op-stack
      :4-pairs
      :2x4-stacks
      :8-carriers
      :4mod-1))

  (testing ":8op-stack has 8 operators and 1 carrier"
    (let [alg (fm/fm-algorithm :8op-stack)]
      (is (= 8 (count (:fm/operators alg))))
      (is (= #{1} (:fm/carriers alg)))))

  (testing ":8op-stack is a series chain 8→7→…→1"
    (let [r (:fm/routing (fm/fm-algorithm :8op-stack))]
      (is (= 1 (get r 2)))
      (is (= 2 (get r 3)))
      (is (= 7 (get r 8)))))

  (testing ":4-pairs has 4 carriers and 4 modulators"
    (let [alg (fm/fm-algorithm :4-pairs)]
      (is (= #{1 3 5 7} (:fm/carriers alg)))
      (is (= 8 (count (:fm/operators alg))))))

  (testing ":2x4-stacks has 2 carriers"
    (let [alg (fm/fm-algorithm :2x4-stacks)]
      (is (= #{1 5} (:fm/carriers alg)))
      (is (= 8 (count (:fm/operators alg))))))

  (testing ":8-carriers has all 8 as carriers and no routing"
    (let [alg (fm/fm-algorithm :8-carriers)]
      (is (= #{1 2 3 4 5 6 7 8} (:fm/carriers alg)))
      (is (empty? (:fm/routing alg)))))

  (testing ":4mod-1 has 4 carriers including the main carrier"
    (let [alg (fm/fm-algorithm :4mod-1)]
      (is (contains? (:fm/carriers alg) 1))
      (is (= 4 (count (:fm/carriers alg))))))

  (testing "all 8-op templates compile to valid SC strings"
    (doseq [k [:8op-stack :4-pairs :2x4-stacks :8-carriers :4mod-1]]
      (let [code (fm/compile-fm :sc (fm/fm-algorithm k))]
        (is (string? code) (str k " should compile"))
        (is (contains-str? code "SynthDef") (str k " should contain SynthDef"))
        (is (contains-str? code "}).add;") (str k " should end with }).add;"))
        (is (= 8 (count (re-seq #"var op_" code)))
            (str k " should have 8 op vars"))))))

;; ---------------------------------------------------------------------------
;; Named 8-op presets
;; ---------------------------------------------------------------------------

(deftest named-8op-presets-test
  (testing "all named 8-op presets are registered"
    (let [names (set (fm/fm-names))]
      (is (contains? names :8op-brass))
      (is (contains? names :8op-bells))
      (is (contains? names :8op-evolving-pad))
      (is (contains? names :8op-dx7-pno))))

  (testing "all 8-op presets compile to valid SC strings"
    (doseq [nm [:8op-brass :8op-bells :8op-evolving-pad :8op-dx7-pno]]
      (let [code (fm/compile-fm :sc nm)]
        (is (string? code) (str nm " should compile"))
        (is (contains-str? code "SynthDef") (str nm " should contain SynthDef"))
        (is (contains-str? code "}).add;") (str nm " should end with }).add;"))
        (is (= 8 (count (re-seq #"var op_" code)))
            (str nm " should have 8 op vars")))))

  (testing ":8op-brass uses TZFM (freq appears in modulation)"
    (let [code (fm/compile-fm :sc :8op-brass)]
      (is (contains-str? code "op_2 * freq"))))

  (testing ":8op-bells has cross-feedback (LocalIn/LocalOut)"
    (let [code (fm/compile-fm :sc :8op-bells)]
      (is (contains-str? code "LocalIn.ar"))
      (is (contains-str? code "LocalOut.ar"))))

  (testing ":8op-evolving-pad has cross-feedback and self-feedback (SinOscFB)"
    (let [code (fm/compile-fm :sc :8op-evolving-pad)]
      (is (contains-str? code "LocalIn.ar"))
      (is (contains-str? code "SinOscFB.ar"))))

  (testing ":8op-dx7-pno has 4 carriers (Mix.ar)"
    (let [code (fm/compile-fm :sc :8op-dx7-pno)]
      (is (contains-str? code "Mix.ar"))))

  (testing "all 8-op presets compile for leviasynth backend"
    (doseq [nm [:8op-brass :8op-bells :8op-evolving-pad :8op-dx7-pno]]
      (let [r (fm/compile-fm :leviasynth nm)]
        (is (map? r))
        (is (= 8 (count (filter #(= :level (last (:path %))) (:ccs r))))
            (str nm " should have 8 level CCs"))))))

;; ---------------------------------------------------------------------------
;; Unknown backend
;; ---------------------------------------------------------------------------

(deftest compile-fm-unknown-backend-test
  (testing "unknown backend throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fm/compile-fm :no-such-backend (fm/fm-algorithm :2op))))))

;; ---------------------------------------------------------------------------
;; Inline fm-def (no registry lookup required)
;; ---------------------------------------------------------------------------

(deftest compile-fm-inline-def-test
  (testing "compile-fm :sc accepts an inline def map"
    (let [alg {:fm/operators [{:id 1 :ratio 1.5 :level 0.9 :env :perc}]
               :fm/carriers  #{1}
               :fm/routing   {}}
          code (fm/compile-fm :sc alg)]
      (is (string? code))
      (is (contains-str? code "1.5"))))

  (testing "compile-fm :digitone accepts an inline def map"
    (let [alg {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                              {:id 2 :ratio 3.0 :level 0.5 :env :perc}]
               :fm/carriers  #{1}
               :fm/routing   {2 1}}
          r   (fm/compile-fm :digitone alg)]
      (is (map? r))
      (is (contains? r :algorithm))))

  (testing "compile-fm :leviasynth accepts an inline def map"
    (let [alg {:fm/operators [{:id 1 :ratio 1.0 :level 0.8 :env :adsr}]
               :fm/carriers  #{1}
               :fm/routing   {}}
          r   (fm/compile-fm :leviasynth alg)]
      (is (map? r))
      (is (vector? (:ccs r))))))
