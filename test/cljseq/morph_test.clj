; SPDX-License-Identifier: EPL-2.0
(ns cljseq.morph-test
  "Unit tests for cljseq.morph — defmorph, morph-set!, fan-out, inspection."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core  :as core]
            [cljseq.ctrl  :as ctrl]
            [cljseq.morph :as morph]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; defmorph — construction
;; ---------------------------------------------------------------------------

(deftest defmorph-creates-context-map-test
  (testing "defmorph creates a var with context map"
    (morph/defmorph test-morph-a
      {:inputs  {:value [:float 0.0 1.0]}
       :targets [{:path [:test/cut-a] :fn #(* % 8000)}]})
    (is (map? test-morph-a))
    (is (map? (:inputs test-morph-a)))
    (is (vector? (:targets test-morph-a)))
    (is (instance? clojure.lang.Atom (:values test-morph-a)))))

(deftest defmorph-initialises-values-to-lo-test
  (testing "initial input values set to lo of declared range"
    (morph/defmorph test-morph-b
      {:inputs  {:value [:float 0.2 0.9]}
       :targets []})
    (is (= 0.2 (get (morph/morph-get test-morph-b) :value)))))

(deftest defmorph-registers-ctrl-node-test
  (testing "defmorph registers [:morphs name] in ctrl tree"
    (morph/defmorph test-morph-c
      {:inputs  {:value [:float 0.0 1.0]}
       :targets []})
    (is (some? (ctrl/node-info [:morphs :test-morph-c])))))

(deftest defmorph-registers-in-morph-names-test
  (testing "defmorph adds name to morph-names"
    (morph/defmorph test-morph-d
      {:inputs  {:value [:float 0.0 1.0]}
       :targets []})
    (is (some #{:test-morph-d} (morph/morph-names)))))

;; ---------------------------------------------------------------------------
;; morph-set! — single input fan-out
;; ---------------------------------------------------------------------------

(deftest morph-set-single-input-fan-out-test
  (testing "morph-set! fans value through transfer fn to target ctrl node"
    (ctrl/defnode! [:test/morph-cutoff] :type :int :value 0)
    (morph/defmorph test-ms-a
      {:inputs  {:value [:float 0.0 1.0]}
       :targets [{:path [:test/morph-cutoff] :fn #(int (* % 1000))}]})
    (morph/morph-set! test-ms-a :value 0.5)
    (is (= 500 (ctrl/get [:test/morph-cutoff])))))

(deftest morph-set-multiple-targets-test
  (testing "morph-set! fans out to all targets"
    (ctrl/defnode! [:test/morph-cut] :type :int :value 0)
    (ctrl/defnode! [:test/morph-res] :type :int :value 0)
    (morph/defmorph test-ms-b
      {:inputs  {:value [:float 0.0 1.0]}
       :targets [{:path [:test/morph-cut] :fn #(int (* % 100))}
                 {:path [:test/morph-res] :fn #(int (* % 50))}]})
    (morph/morph-set! test-ms-b :value 0.8)
    (is (= 80 (ctrl/get [:test/morph-cut])))
    (is (= 40 (ctrl/get [:test/morph-res])))))

(deftest morph-set-clamps-to-range-test
  (testing "morph-set! clamps input to declared [lo hi]"
    (ctrl/defnode! [:test/morph-clamp] :type :int :value 0)
    (morph/defmorph test-ms-c
      {:inputs  {:value [:float 0.0 1.0]}
       :targets [{:path [:test/morph-clamp] :fn #(int (* % 100))}]})
    ;; Over-range: 2.0 clamped to 1.0
    (morph/morph-set! test-ms-c :value 2.0)
    (is (= 100 (ctrl/get [:test/morph-clamp])))
    ;; Under-range: -1.0 clamped to 0.0
    (morph/morph-set! test-ms-c :value -1.0)
    (is (= 0 (ctrl/get [:test/morph-clamp])))))

(deftest morph-set-updates-stored-values-test
  (testing "morph-set! updates the stored input values"
    (morph/defmorph test-ms-d
      {:inputs  {:value [:float 0.0 1.0]}
       :targets []})
    (morph/morph-set! test-ms-d :value 0.7)
    (is (= 0.7 (get (morph/morph-get test-ms-d) :value)))))

;; ---------------------------------------------------------------------------
;; morph-set! — multi-input fan-out
;; ---------------------------------------------------------------------------

(deftest morph-set-multi-input-test
  (testing "morph-set! passes full input map to multi-input target fns"
    (ctrl/defnode! [:test/morph-xy-out] :type :int :value 0)
    (morph/defmorph test-ms-xy
      {:inputs  {:x [:float 0.0 1.0]
                 :y [:float 0.0 1.0]}
       :targets [{:path [:test/morph-xy-out]
                  :fn   (fn [{:keys [x y]}] (int (* x y 100)))}]})
    (morph/morph-set! test-ms-xy :x 0.5 :y 0.8)
    (is (= 40 (ctrl/get [:test/morph-xy-out])))))

(deftest morph-set-partial-multi-input-update-test
  (testing "morph-set! with only one key of a multi-input morph preserves others"
    (ctrl/defnode! [:test/morph-partial] :type :int :value 0)
    (morph/defmorph test-ms-part
      {:inputs  {:x [:float 0.0 1.0]
                 :y [:float 0.0 1.0]}
       :targets [{:path [:test/morph-partial]
                  :fn   (fn [{:keys [x y]}] (int (+ (* x 50) (* y 50))))}]})
    ;; Set both, then update only x
    (morph/morph-set! test-ms-part :x 1.0 :y 1.0)
    (morph/morph-set! test-ms-part :x 0.0)
    ;; y still 1.0, x now 0.0 → result = 0 + 50 = 50
    (is (= 50 (ctrl/get [:test/morph-partial])))))

;; ---------------------------------------------------------------------------
;; morph-set! — missing target nodes skipped silently
;; ---------------------------------------------------------------------------

(deftest morph-set-skips-missing-ctrl-node-test
  (testing "morph-set! skips targets whose ctrl node does not exist"
    (morph/defmorph test-ms-skip
      {:inputs  {:value [:float 0.0 1.0]}
       :targets [{:path [:test/does-not-exist-xyz] :fn identity}]})
    ;; Should not throw
    (is (nil? (morph/morph-set! test-ms-skip :value 0.5)))))

;; ---------------------------------------------------------------------------
;; morph-get
;; ---------------------------------------------------------------------------

(deftest morph-get-returns-current-values-test
  (testing "morph-get returns the current input values map"
    (morph/defmorph test-mg-a
      {:inputs  {:x [:float 0.0 1.0]
                 :y [:float 0.0 1.0]}
       :targets []})
    (morph/morph-set! test-mg-a :x 0.3 :y 0.6)
    (let [v (morph/morph-get test-mg-a)]
      (is (= 0.3 (:x v)))
      (is (= 0.6 (:y v))))))

;; ---------------------------------------------------------------------------
;; morph-names
;; ---------------------------------------------------------------------------

(deftest morph-names-returns-seq-test
  (testing "morph-names returns a sequential collection"
    (is (sequential? (morph/morph-names)))))

(deftest morph-names-includes-registered-test
  (testing "morph-names includes morphs defined with defmorph"
    (morph/defmorph test-mn-a {:inputs {:value [:float 0.0 1.0]} :targets []})
    (is (some #{:test-mn-a} (morph/morph-names)))))
