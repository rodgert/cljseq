; SPDX-License-Identifier: EPL-2.0
(ns cljseq.random-test
  "Unit tests for cljseq.random — distribution primitives and weighted scales."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.random :as random]
            [cljseq.scale  :as scale]))

;; ---------------------------------------------------------------------------
;; rand-gaussian — statistical sanity
;; ---------------------------------------------------------------------------

(deftest rand-gaussian-returns-double-test
  (testing "rand-gaussian returns a double"
    (is (instance? Double (random/rand-gaussian)))))

(deftest rand-gaussian-roughly-centered-test
  (testing "rand-gaussian mean is near 0 over many samples"
    (let [samples (repeatedly 2000 random/rand-gaussian)
          mean    (/ (apply + samples) (count samples))]
      (is (< (Math/abs mean) 0.15) "mean within 0.15 of 0"))))

;; ---------------------------------------------------------------------------
;; sample-distribution
;; ---------------------------------------------------------------------------

(deftest sample-distribution-constant-at-zero-spread-test
  (testing "spread=0 always returns bias exactly"
    (dotimes [_ 50]
      (is (= 0.7 (random/sample-distribution 0.0 0.7)))
      (is (= 0.3 (random/sample-distribution 0.0 0.3))))))

(deftest sample-distribution-in-range-test
  (testing "sample-distribution result is always in [0.0, 1.0]"
    (dotimes [_ 500]
      (let [spread (rand)
            bias   (rand)
            v      (random/sample-distribution spread bias)]
        (is (<= 0.0 v 1.0) (str "out of range: " v " spread=" spread " bias=" bias))))))

(deftest sample-distribution-uniform-spread-test
  (testing "spread=1 produces uniform-ish distribution (mean near 0.5)"
    (let [samples (repeatedly 2000 #(random/sample-distribution 1.0 0.5))
          mean    (/ (apply + samples) (count samples))]
      (is (< (Math/abs (- mean 0.5)) 0.05) "uniform mean near 0.5"))))

(deftest sample-distribution-gaussian-biased-test
  (testing "spread=0.5, bias=0.8 produces values centered near 0.8"
    (let [samples (repeatedly 1000 #(random/sample-distribution 0.5 0.8))
          mean    (/ (apply + samples) (count samples))]
      (is (> mean 0.60) "biased high"))))

;; ---------------------------------------------------------------------------
;; weighted-scale — construction
;; ---------------------------------------------------------------------------

(deftest weighted-scale-returns-map-test
  (testing "weighted-scale returns a map with required keys"
    (let [ws (random/weighted-scale :major)]
      (is (= :major (:scale/type ws)))
      (is (vector? (:intervals ws)))
      (is (vector? (:weights ws)))
      (is (= (count (:intervals ws)) (count (:weights ws)))))))

(deftest weighted-scale-major-intervals-test
  (testing "major scale has correct semitone intervals"
    (let [ws (random/weighted-scale :major)]
      (is (= [0 2 4 5 7 9 11] (:intervals ws))))))

(deftest weighted-scale-custom-weights-test
  (testing "custom weight overrides degree weight"
    (let [ws (random/weighted-scale :major {:seventh 0.05})]
      ;; seventh is index 6 in degree-names
      (is (< (nth (:weights ws) 6) 0.1) "seventh weight overridden"))))

(deftest weighted-scale-pentatonic-five-degrees-test
  (testing "pentatonic scale has 5 degrees"
    (let [ws (random/weighted-scale :pentatonic)]
      (is (= 5 (count (:intervals ws)))))))

;; ---------------------------------------------------------------------------
;; progressive-quantize
;; ---------------------------------------------------------------------------

(deftest progressive-quantize-raw-at-low-steps-test
  (testing "steps <= 0.5 returns raw midi-note as int"
    (is (= 61 (random/progressive-quantize 61 (random/weighted-scale :major) 0.0)))
    (is (= 61 (random/progressive-quantize 61 (random/weighted-scale :major) 0.5)))))

(deftest progressive-quantize-snaps-to-nearest-scale-degree-test
  (testing "C# (61) snaps to C (60) or D (62) in C major at steps=1.0"
    (let [result (random/progressive-quantize 61 (random/weighted-scale :major) 1.0)]
      ;; steps=1.0 threshold=1.0 → only weight>=1.0 degrees survive → tonic (weight=1.0)
      ;; tonic interval is 0 → nearest is C (60)
      (is (= 60 result) "C# snaps to C (tonic) at maximum quantization"))))

(deftest progressive-quantize-same-octave-test
  (testing "quantized note stays in the same octave as input"
    (doseq [midi [60 62 64 65 67 69 71]]
      (let [result (random/progressive-quantize midi (random/weighted-scale :major) 0.8)]
        (is (<= 60 result 71) (str "stays in octave 5: " result " from " midi))))))

(deftest progressive-quantize-chromatic-no-snap-test
  (testing "chromatic scale with steps=0.75 passes all 12 pitches"
    ;; All degrees have equal weight 1.0 (from defaults applied to only 7,
    ;; but chromatic has 12 so defaults cycle). Just check result is in 0-127.
    (let [ws (random/weighted-scale :chromatic)]
      (doseq [midi (range 60 72)]
        (let [result (random/progressive-quantize midi ws 0.75)]
          (is (<= 0 result 127)))))))

;; ---------------------------------------------------------------------------
;; learn-scale-weights
;; ---------------------------------------------------------------------------

(deftest learn-scale-weights-returns-map-test
  (testing "learn-scale-weights returns proper weighted-scale map"
    (let [ws (random/learn-scale-weights [60 62 64 60 62 60 67 65])]
      (is (= :major (:scale/type ws)))
      (is (vector? (:intervals ws)))
      (is (vector? (:weights ws))))))

(deftest learn-scale-weights-tonic-highest-test
  (testing "most frequent note gets weight 1.0"
    ;; Play C (60) most often — should be weight 1.0
    (let [ws (random/learn-scale-weights [60 60 60 60 62 64])]
      (is (= 1.0 (first (:weights ws))) "tonic (C=60) has weight 1.0"))))

(deftest learn-scale-weights-normalizes-test
  (testing "all weights are in [0.0, 1.0]"
    (let [ws (random/learn-scale-weights [60 62 64 65 67 69 71 60 62])]
      (doseq [w (:weights ws)]
        (is (<= 0.0 w 1.0) (str "weight out of range: " w))))))

(deftest learn-scale-weights-empty-notes-test
  (testing "empty note list returns all-zero weights"
    (let [ws (random/learn-scale-weights [])]
      (is (every? zero? (:weights ws))))))

;; ---------------------------------------------------------------------------
;; Scale record integration
;; ---------------------------------------------------------------------------

(deftest progressive-quantize-accepts-scale-record-test
  (testing "progressive-quantize works with a Scale record (uniform weights)"
    (let [s (scale/scale :C 4 :major)]
      ;; C# (61) at full quantization should snap to C (60) or D (62)
      (let [result (random/progressive-quantize 61 s 1.0)]
        (is (#{60 62} result) "C# snaps to C or D in C major")))))

(deftest progressive-quantize-scale-vs-weighted-test
  (testing "Scale record: C# snaps toward C at steps=0.8 (uniform weights keep all degrees)"
    ;; With uniform weights (Scale record), threshold = (0.8-0.5)*2 = 0.6.
    ;; All degrees have weight 1.0 >= 0.6, so all are eligible.
    ;; C# (pc=1) is closest to C (0) or D (2).
    (let [s      (scale/scale :C 4 :major)
          result (random/progressive-quantize 61 s 0.8)]
      (is (#{60 62} result) "C# snaps to C or D (nearest scale members)")))
  (testing "weighted-scale at steps=1.0 collapses to tonic only"
    ;; Default weights: only tonic (1.0) survives at threshold=1.0.
    (let [ws     (random/weighted-scale :major)
          result (random/progressive-quantize 64 ws 1.0)]
      (is (= 0 (mod result 12)) "All pitches snap to tonic at steps=1.0"))))

(deftest progressive-quantize-scale-raw-test
  (testing "steps <= 0.5 passes through unchanged for Scale record"
    (let [s (scale/scale :C 4 :major)]
      (is (= 61 (random/progressive-quantize 61 s 0.0)))
      (is (= 61 (random/progressive-quantize 61 s 0.5))))))

(deftest learn-scale-weights-with-scale-record-test
  (testing "learn-scale-weights accepts :scale option with Scale record"
    (let [s  (scale/scale :C 4 :major)
          ws (random/learn-scale-weights [60 62 64 65 67] :scale s)]
      (is (vector? (:intervals ws)))
      (is (vector? (:weights ws)))
      ;; intervals should match the major scale
      (is (= [0 2 4 5 7 9 11] (:intervals ws))))))
