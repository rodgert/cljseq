; SPDX-License-Identifier: EPL-2.0
(ns cljseq.interval-test
  "Tests for cljseq.interval — Interval record, named intervals, arithmetic."
  (:require [clojure.test   :refer [deftest is testing]]
            [cljseq.pitch    :as pitch]
            [cljseq.interval :as iv]))

;; ---------------------------------------------------------------------------
;; named-interval constructors
;; ---------------------------------------------------------------------------

(deftest named-interval-lookup-test
  (testing "known named intervals resolve correctly"
    (let [m3 (iv/named-interval :M3)]
      (is (= :major (:quality m3)))
      (is (= 3      (:number m3)))
      (is (= :up    (:direction m3))))
    (let [p5 (iv/named-interval :P5)]
      (is (= :perfect (:quality p5)))
      (is (= 5        (:number p5))))
    (let [m7 (iv/named-interval :m7)]
      (is (= :minor (:quality m7)))
      (is (= 7      (:number m7))))))

(deftest named-interval-direction-test
  (testing "downward direction flag"
    (let [p5d (iv/named-interval :P5 :down)]
      (is (= :down (:direction p5d))))))

(deftest named-interval-nil-on-unknown-test
  (testing "unknown symbol returns nil"
    (is (nil? (iv/named-interval :X99)))))

;; ---------------------------------------------------------------------------
;; interval->semitones
;; ---------------------------------------------------------------------------

(deftest semitones-perfect-test
  (testing "perfect interval semitones"
    (is (= 0  (iv/interval->semitones (iv/named-interval :P1))))
    (is (= 5  (iv/interval->semitones (iv/named-interval :P4))))
    (is (= 7  (iv/interval->semitones (iv/named-interval :P5))))
    (is (= 12 (iv/interval->semitones (iv/named-interval :P8))))))

(deftest semitones-major-minor-test
  (testing "major/minor interval semitones"
    (is (= 2  (iv/interval->semitones (iv/named-interval :M2))))
    (is (= 1  (iv/interval->semitones (iv/named-interval :m2))))
    (is (= 4  (iv/interval->semitones (iv/named-interval :M3))))
    (is (= 3  (iv/interval->semitones (iv/named-interval :m3))))
    (is (= 9  (iv/interval->semitones (iv/named-interval :M6))))
    (is (= 8  (iv/interval->semitones (iv/named-interval :m6))))
    (is (= 11 (iv/interval->semitones (iv/named-interval :M7))))
    (is (= 10 (iv/interval->semitones (iv/named-interval :m7))))))

(deftest semitones-augmented-diminished-test
  (testing "augmented/diminished semitones"
    (is (= 6  (iv/interval->semitones (iv/named-interval :A4))))
    (is (= 6  (iv/interval->semitones (iv/named-interval :d5))))
    (is (= 8  (iv/interval->semitones (iv/named-interval :A5))))
    (is (= 1  (iv/interval->semitones (iv/named-interval :A1))))))

(deftest semitones-compound-test
  (testing "compound interval semitones"
    (is (= 14 (iv/interval->semitones (iv/named-interval :M9))))
    (is (= 13 (iv/interval->semitones (iv/named-interval :m9))))))

(deftest semitones-downward-test
  (testing "downward intervals are negative"
    (is (= -7  (iv/interval->semitones (iv/named-interval :P5 :down))))
    (is (= -12 (iv/interval->semitones (iv/named-interval :P8 :down))))))

;; ---------------------------------------------------------------------------
;; compound? / simple / invert
;; ---------------------------------------------------------------------------

(deftest compound-predicate-test
  (testing "compound? is true iff number > 8"
    (is (not (iv/compound? (iv/named-interval :P8))))
    (is (iv/compound? (iv/named-interval :M9)))
    (is (iv/compound? (iv/named-interval :P11)))))

(deftest simple-test
  (testing "simple reduces compound to within octave"
    (let [m9  (iv/named-interval :M9)
          sim (iv/simple m9)]
      (is (= 2 (:number sim)))
      (is (= :major (:quality sim))))
    (testing "simple is identity for non-compound"
      (let [p5 (iv/named-interval :P5)]
        (is (= p5 (iv/simple p5)))))))

(deftest invert-test
  (testing "interval inversions"
    ;; P5 inverts to P4
    (let [inv (iv/invert (iv/named-interval :P5))]
      (is (= :perfect (:quality inv)))
      (is (= 4        (:number inv))))
    ;; M3 inverts to m6
    (let [inv (iv/invert (iv/named-interval :M3))]
      (is (= :minor (:quality inv)))
      (is (= 6      (:number inv))))
    ;; m7 inverts to M2
    (let [inv (iv/invert (iv/named-interval :m7))]
      (is (= :major (:quality inv)))
      (is (= 2      (:number inv))))))

;; ---------------------------------------------------------------------------
;; add-interval
;; ---------------------------------------------------------------------------

(deftest add-interval-up-test
  (testing "add P5 up from C4 → G4"
    (let [c4     (pitch/pitch :C 4)
          result (iv/add-interval c4 (iv/named-interval :P5))]
      (is (= 67 (pitch/pitch->midi result)))))
  (testing "add M3 up from C4 → E4"
    (let [c4     (pitch/pitch :C 4)
          result (iv/add-interval c4 (iv/named-interval :M3))]
      (is (= 64 (pitch/pitch->midi result))))))

(deftest add-interval-down-test
  (testing "add P5 down from C4 → F3 (60 - 7 = 53)"
    (let [c4     (pitch/pitch :C 4)
          result (iv/add-interval c4 (iv/named-interval :P5 :down))]
      (is (= 53 (pitch/pitch->midi result))))))

(deftest add-interval-compound-test
  (testing "add M9 up from C4 → D5"
    (let [c4     (pitch/pitch :C 4)
          result (iv/add-interval c4 (iv/named-interval :M9))]
      (is (= 74 (pitch/pitch->midi result))))))

;; ---------------------------------------------------------------------------
;; interval-between
;; ---------------------------------------------------------------------------

(deftest interval-between-p5-test
  (testing "C4 to G4 = P5 up"
    (let [c4 (pitch/pitch :C 4)
          g4 (pitch/pitch :G 4)
          iv (iv/interval-between c4 g4)]
      (is (= :perfect (:quality iv)))
      (is (= 5        (:number iv)))
      (is (= :up      (:direction iv))))))

(deftest interval-between-m3-test
  (testing "A4 to C5 = m3 up"
    (let [a4 (pitch/pitch :A 4)
          c5 (pitch/pitch :C 5)
          iv (iv/interval-between a4 c5)]
      (is (= :minor (:quality iv)))
      (is (= 3      (:number iv))))))

(deftest interval-between-down-test
  (testing "G4 to C4 = P5 down"
    (let [g4 (pitch/pitch :G 4)
          c4 (pitch/pitch :C 4)
          iv (iv/interval-between g4 c4)]
      (is (= :down (:direction iv)))
      (is (= 5     (:number iv))))))

(deftest interval-between-unison-test
  (testing "C4 to C4 = P1"
    (let [c4 (pitch/pitch :C 4)
          iv (iv/interval-between c4 c4)]
      (is (= 1 (:number iv))))))

;; ---------------------------------------------------------------------------
;; interval-name
;; ---------------------------------------------------------------------------

(deftest interval-name-test
  (testing "interval-name returns canonical keyword"
    (is (= :P5 (iv/interval-name (iv/named-interval :P5))))
    (is (= :M3 (iv/interval-name (iv/named-interval :M3))))
    (is (= :m7 (iv/interval-name (iv/named-interval :m7))))))
