; SPDX-License-Identifier: EPL-2.0
(ns cljseq.synth-test
  "Tests for cljseq.synth — synthesis graph vocabulary."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.synth  :as synth]))

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
