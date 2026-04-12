; SPDX-License-Identifier: EPL-2.0
(ns cljseq.berlin-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cljseq.berlin     :as berlin]
            [cljseq.trajectory :as traj]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- simple-pattern []
  [{:pitch/midi 60 :dur/beats 1/4}
   {:pitch/midi 64 :dur/beats 1/4}
   {:pitch/midi 67 :dur/beats 1/4}
   {:pitch/midi 71 :dur/beats 1/4}])

(defn- make-ost
  ([] (make-ost {}))
  ([opts] (berlin/ostinato (simple-pattern) opts)))

;; ---------------------------------------------------------------------------
;; ostinato — construction
;; ---------------------------------------------------------------------------

(deftest ostinato-construction
  (testing "returns an atom"
    (is (instance? clojure.lang.Atom (make-ost))))

  (testing "initial state fields"
    (let [ost (make-ost {:mutation-rate 0.2 :drift :up})
          st  @ost]
      (is (= 4 (count (:pattern st))) "pattern has 4 steps")
      (is (= (:pattern st) (:origin st)) "origin matches initial pattern")
      (is (= 0 (:pos st)) "initial position is 0")
      (is (= 0 (:pass st)) "initial pass is 0")
      (is (= :random-walk (:mode st)) "default mode is :random-walk")
      (is (= 0.2 (:mutation-rate st)) "mutation-rate passed through")
      (is (= :up (:drift st)) "drift direction passed through")))

  (testing "pattern stored as vectors of step maps"
    (let [ost (make-ost)
          st  @ost]
      (is (vector? (:pattern st)))
      (doseq [step (:pattern st)]
        (is (contains? step :pitch/midi))
        (is (contains? step :dur/beats))))))

;; ---------------------------------------------------------------------------
;; next-step! — advancement and state transitions
;; ---------------------------------------------------------------------------

(deftest next-step-advance
  (testing "returns a step map"
    (let [ost  (make-ost)
          step (berlin/next-step! ost)]
      (is (map? step))
      (is (contains? step :pitch/midi))
      (is (contains? step :dur/beats))))

  (testing "cycles through pattern positions"
    (let [ost (make-ost {:mutation-rate 0.0})]  ; frozen — no mutation
      (let [steps (repeatedly 4 #(berlin/next-step! ost))]
        (is (= (map :pitch/midi (simple-pattern))
               (map :pitch/midi steps))
            "steps match initial pattern in order"))))

  (testing "pos wraps to 0 after full pass"
    (let [ost (make-ost {:mutation-rate 0.0})]
      (dotimes [_ 4] (berlin/next-step! ost))
      (is (zero? (:pos @ost)) "pos resets to 0 after 4 steps")))

  (testing "pass count increments after each full cycle"
    (let [ost (make-ost {:mutation-rate 0.0})]
      (dotimes [_ 8] (berlin/next-step! ost))
      (is (= 2 (:pass @ost)) "2 passes after 8 steps in 4-step pattern")))

  (testing "frozen ostinato never mutates"
    (let [ost    (make-ost {:mutation-rate 0.0})
          before (map :pitch/midi (:pattern @ost))]
      (dotimes [_ 40] (berlin/next-step! ost))
      (is (= before (map :pitch/midi (:pattern @ost)))
          "pattern unchanged after 10 passes with rate 0"))))

;; ---------------------------------------------------------------------------
;; Mutation modes
;; ---------------------------------------------------------------------------

(deftest crystallize-mode
  (testing "crystallize! sets mode and target"
    (let [ost    (make-ost)
          target [{:pitch/midi 62 :dur/beats 1/4}
                  {:pitch/midi 65 :dur/beats 1/4}
                  {:pitch/midi 69 :dur/beats 1/4}
                  {:pitch/midi 72 :dur/beats 1/4}]]
      (berlin/crystallize! ost target)
      (is (= :crystallize (:mode @ost)) "mode set to :crystallize")
      (is (= target (:target @ost)) "target stored")))

  (testing "crystallize! converges toward target over passes"
    ;; Use high rate and many passes to verify direction of movement
    (let [origin [{:pitch/midi 60 :dur/beats 1/4}
                  {:pitch/midi 60 :dur/beats 1/4}
                  {:pitch/midi 60 :dur/beats 1/4}
                  {:pitch/midi 60 :dur/beats 1/4}]
          target [{:pitch/midi 72 :dur/beats 1/4}
                  {:pitch/midi 72 :dur/beats 1/4}
                  {:pitch/midi 72 :dur/beats 1/4}
                  {:pitch/midi 72 :dur/beats 1/4}]
          ost    (berlin/ostinato origin {:mutation-rate 0.0})]
      (berlin/crystallize! ost target {:rate 1.0})
      ;; Run enough steps to complete several passes
      (dotimes [_ 40] (berlin/next-step! ost))
      (let [final-pitches (map :pitch/midi (:pattern @ost))]
        (is (every? #(= 72 %) final-pitches)
            "all notes converge to target at rate 1.0")))))

(deftest dissolve-mode
  (testing "dissolve! sets mode"
    (let [ost (make-ost)]
      (berlin/dissolve! ost {:rate 0.5})
      (is (= :dissolve (:mode @ost)) "mode set to :dissolve")
      (is (= 0.5 (:mutation-rate @ost)) "rate set"))))

(deftest deflect-ostinato-mode
  (testing "deflect-ostinato! sets teleological mode and target"
    (let [ost    (make-ost)
          target (simple-pattern)]
      (berlin/deflect-ostinato! ost target {:rate 0.3})
      (is (= :teleological (:mode @ost)) "mode set to :teleological")
      (is (= (vec target) (:target @ost)) "target stored")
      (is (= 0.3 (:mutation-rate @ost)) "custom rate applied"))))

;; ---------------------------------------------------------------------------
;; Freeze / thaw
;; ---------------------------------------------------------------------------

(deftest freeze-thaw
  (testing "freeze-ostinato! sets rate to 0"
    (let [ost (make-ost {:mutation-rate 0.3})]
      (berlin/freeze-ostinato! ost)
      (is (zero? (:mutation-rate @ost)) "rate is 0 after freeze")
      (is (nil? (:mutation-traj @ost)) "trajectory cleared")))

  (testing "thaw-ostinato! restores rate"
    (let [ost (make-ost {:mutation-rate 0.3})]
      (berlin/freeze-ostinato! ost)
      (berlin/thaw-ostinato! ost 0.25)
      (is (= 0.25 (:mutation-rate @ost)) "rate restored to 0.25")))

  (testing "thaw-ostinato! defaults to 0.1"
    (let [ost (make-ost {:mutation-rate 0.3})]
      (berlin/freeze-ostinato! ost)
      (berlin/thaw-ostinato! ost)
      (is (= 0.1 (:mutation-rate @ost)) "default rate is 0.1"))))

;; ---------------------------------------------------------------------------
;; reset-ostinato!
;; ---------------------------------------------------------------------------

(deftest reset-ostinato
  (testing "reset-ostinato! brings pos back to 0"
    (let [ost (make-ost {:mutation-rate 0.0})]
      (dotimes [_ 3] (berlin/next-step! ost))
      (is (= 3 (:pos @ost)) "pos advanced to 3")
      (berlin/reset-ostinato! ost)
      (is (zero? (:pos @ost)) "pos reset to 0")))

  (testing "reset-ostinato! does not change the pattern"
    (let [ost    (make-ost {:mutation-rate 0.0})
          before (map :pitch/midi (:pattern @ost))]
      (berlin/reset-ostinato! ost)
      (is (= before (map :pitch/midi (:pattern @ost))) "pattern unchanged"))))

;; ---------------------------------------------------------------------------
;; set-mutation-trajectory!
;; ---------------------------------------------------------------------------

(deftest mutation-trajectory
  (testing "set-mutation-trajectory! wires trajectory and total-passes"
    (let [ost  (make-ost)
          traj (traj/trajectory :from 0.05 :to 0.30 :beats 64 :curve :linear)]
      (berlin/set-mutation-trajectory! ost traj 64)
      (is (some? (:mutation-traj @ost)) "trajectory stored")
      (is (= 64 (:traj-passes @ost)) "total-passes stored"))))

;; ---------------------------------------------------------------------------
;; tape-drift
;; ---------------------------------------------------------------------------

(deftest tape-drift-wrapper
  (testing "tape-drift returns an atom"
    (let [ost  (make-ost)
          tape (berlin/tape-drift ost 12)]
      (is (instance? clojure.lang.Atom tape))))

  (testing "tick-tape! returns a step map"
    (let [ost  (make-ost {:mutation-rate 0.0})
          tape (berlin/tape-drift ost 0)]  ; max-drift 0 = no drift
      (let [step (berlin/tick-tape! tape)]
        (is (map? step))
        (is (contains? step :pitch/midi)))))

  (testing "tick-tape! with zero max-drift produces no bend-cents"
    ;; With max-drift 0, drift-cents stays at 0; no :pitch/bend-cents added
    (let [ost  (make-ost {:mutation-rate 0.0})
          tape (berlin/tape-drift ost 0)]
      (dotimes [_ 8]
        (let [step (berlin/tick-tape! tape)]
          (is (nil? (:pitch/bend-cents step)) "no bend cents when max-drift is 0")))))

  (testing "tick-tape! with non-zero drift eventually produces bend-cents"
    (let [ost  (make-ost {:mutation-rate 0.0})
          tape (berlin/tape-drift ost 50)]
      ;; First step: drift-cents starts at 0 so no bend on first step
      (berlin/tick-tape! tape)
      ;; After first step, drift-cents should be non-zero
      (is (not (zero? (:drift-cents @tape))) "drift accumulates after first step")
      ;; Second step should attach :pitch/bend-cents
      (let [step (berlin/tick-tape! tape)]
        (is (some? (:pitch/bend-cents step)) "bend-cents attached on second step")))))

;; ---------------------------------------------------------------------------
;; Microtonal drift
;; ---------------------------------------------------------------------------

(deftest microtonal-drift
  (testing "microtonal option attaches :pitch/bend-cents after first step"
    (let [ost (make-ost {:microtonal true})]
      ;; First call initializes drift-state for pos 0
      (let [step (berlin/next-step! ost)]
        ;; The first step may be 0.0 cents; subsequent steps for same pos should drift
        (is (contains? step :pitch/bend-cents) "bend-cents key present"))
      ;; After one full pass, drift should have accumulated
      (dotimes [_ 3] (berlin/next-step! ost))
      ;; drift-state should now have entries
      (is (seq (:drift-state @ost)) "drift-state populated after steps")))

  (testing "bend-cents stays within ±8 cents"
    (let [ost (make-ost {:microtonal true :gravity 0.5})]
      (dotimes [_ 100]
        (let [step (berlin/next-step! ost)]
          (when-let [cents (:pitch/bend-cents step)]
            (is (<= -8.0 cents 8.0) "bend cents within max-drift bounds")))))))
