; SPDX-License-Identifier: EPL-2.0
(ns cljseq.rhythm-test
  "Tests for cljseq.rhythm — Euclidean rhythms and utilities."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.rhythm :as r]))

;; ---------------------------------------------------------------------------
;; euclidean — canonical patterns
;; ---------------------------------------------------------------------------

(deftest euclidean-zero-pulses-test
  (testing "0 pulses = all rests"
    (is (= [0 0 0 0] (r/euclidean 0 4)))))

(deftest euclidean-all-pulses-test
  (testing "k = n means all pulses"
    (is (= [1 1 1 1] (r/euclidean 4 4)))))

(deftest euclidean-tresillo-test
  (testing "e(3,8) = tresillo [1 0 0 1 0 0 1 0]"
    (is (= [1 0 0 1 0 0 1 0] (r/euclidean 3 8)))))

(deftest euclidean-son-clave-test
  (testing "e(5,8) = son clave [1 0 1 1 0 1 1 0]"
    (is (= [1 0 1 1 0 1 1 0] (r/euclidean 5 8)))))

(deftest euclidean-length-invariant-test
  (testing "result always has length n"
    (doseq [k (range 0 9)
            n (range 1 17)
            :when (<= k n)]
      (is (= n (count (r/euclidean k n)))))))

(deftest euclidean-pulse-count-invariant-test
  (testing "result always has exactly k pulses"
    (doseq [k (range 0 9)
            n (range 1 17)
            :when (<= k n)]
      (is (= k (reduce + (r/euclidean k n)))))))

(deftest euclidean-with-offset-test
  (testing "euclidean with offset rotates pattern"
    (let [base    (r/euclidean 3 8)
          rotated (r/euclidean 3 8 1)]
      ;; rotation by 1 shifts pulses right by 1
      (is (not= base rotated))
      (is (= 8 (count rotated)))
      (is (= 3 (reduce + rotated))))))

;; ---------------------------------------------------------------------------
;; rotate-rhythm
;; ---------------------------------------------------------------------------

(deftest rotate-rhythm-by-zero-test
  (testing "rotation by 0 is identity"
    (let [r [1 0 1 0 1]]
      (is (= r (r/rotate-rhythm r 0))))))

(deftest rotate-rhythm-test
  (testing "rotate left by 1 (first element moves to end)"
    ;; [1 0 1 0] rotated by 1 → [0 1 0 1]
    (is (= [0 1 0 1] (r/rotate-rhythm [1 0 1 0] 1)))
    (is (= [1 0 1 0] (r/rotate-rhythm [1 0 1 0] 2)))))

(deftest rotate-rhythm-wrap-test
  (testing "rotation wraps around"
    (let [rv [1 0 0 1 0 0 1 0]]
      (is (= rv (r/rotate-rhythm rv 8))))))

;; ---------------------------------------------------------------------------
;; rhythm-density
;; ---------------------------------------------------------------------------

(deftest rhythm-density-test
  (testing "rhythm density is pulse count / step count"
    (is (== 0.5  (r/rhythm-density [1 0 1 0])))
    (is (== 0.0  (r/rhythm-density [0 0 0 0])))
    (is (== 1.0  (r/rhythm-density [1 1 1 1])))
    (is (< (Math/abs (- (/ 3.0 8) (r/rhythm-density (r/euclidean 3 8)))) 0.001))))

;; ---------------------------------------------------------------------------
;; invert-rhythm / complement-rhythm
;; ---------------------------------------------------------------------------

(deftest invert-rhythm-test
  (testing "invert swaps 0s and 1s"
    (is (= [0 1 0 1] (r/invert-rhythm [1 0 1 0])))
    (is (= [1 1 1 1] (r/invert-rhythm [0 0 0 0])))))

(deftest double-invert-identity-test
  (testing "double inversion is identity"
    (let [base [1 0 1 1 0 1 1 0]]
      (is (= base (r/invert-rhythm (r/invert-rhythm base)))))))

(deftest complement-alias-test
  (testing "complement-rhythm is alias for invert-rhythm"
    (is (= (r/invert-rhythm [1 0 1 0])
           (r/complement-rhythm [1 0 1 0])))))

;; ---------------------------------------------------------------------------
;; interleave-rhythms
;; ---------------------------------------------------------------------------

(deftest interleave-rhythms-test
  (testing "interleave uses OR logic"
    (is (= [1 1 1 0] (r/interleave-rhythms [1 0 1 0] [0 1 0 0])))))

(deftest interleave-complementary-test
  (testing "interleave with complement fills all gaps"
    (let [base [1 0 1 0 1 0 1 0]
          inv  (r/invert-rhythm base)
          combined (r/interleave-rhythms base inv)]
      (is (= [1 1 1 1 1 1 1 1] combined)))))

;; ---------------------------------------------------------------------------
;; rhythm->steps
;; ---------------------------------------------------------------------------

(deftest rhythm->steps-default-test
  (testing "rhythm->steps with default dur=1"
    (let [steps (r/rhythm->steps [1 0 1])]
      (is (= 3 (count steps)))
      (is (:beat? (first steps)))
      (is (not (:beat? (second steps))))
      (is (= 1 (:dur (first steps)))))))

(deftest rhythm->steps-custom-dur-test
  (testing "rhythm->steps with custom dur-fn"
    (let [steps (r/rhythm->steps [1 0 1 0] (constantly 0.5))]
      (is (every? #(= 0.5 (:dur %)) steps)))))

;; ---------------------------------------------------------------------------
;; rhythm->onsets
;; ---------------------------------------------------------------------------

(deftest rhythm->onsets-test
  (testing "rhythm->onsets returns pulse indices"
    (is (= [0 3 6] (seq (r/rhythm->onsets [1 0 0 1 0 0 1 0]))))))

;; ---------------------------------------------------------------------------
;; Predefined rhythms
;; ---------------------------------------------------------------------------

(deftest predefined-rhythms-test
  (testing "predefined rhythm constants have correct properties"
    (is (= 8  (count r/tresillo)))
    (is (= 3  (reduce + r/tresillo)))
    (is (= 8  (count r/son-clave)))
    (is (= 5  (reduce + r/son-clave)))
    (is (= 4  (count r/standard-4)))
    (is (= 4  (reduce + r/standard-4)))))
