; SPDX-License-Identifier: EPL-2.0
(ns cljseq.clock-test
  "Unit tests for cljseq.clock — ITemporalValue, named constructors,
  BPM utilities, and timeline beat↔epoch-ms conversions."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.clock :as clock]))

(def eps 1e-6)
(defn ≈ [a b] (< (Math/abs (- (double a) (double b))) eps))

;; ---------------------------------------------------------------------------
;; master-clock
;; ---------------------------------------------------------------------------

(deftest master-clock-is-unit-phasor-test
  (testing "master-clock samples as rate-1 phasor"
    (is (≈ 0.0 (clock/sample clock/master-clock 0)))
    (is (≈ 0.5 (clock/sample clock/master-clock 0.5)))
    (is (≈ 0.0 (clock/sample clock/master-clock 1.0))))
  (testing "master-clock next-edge fires every beat"
    (is (≈ 1.0 (clock/next-edge clock/master-clock 0)))
    (is (≈ 2.0 (clock/next-edge clock/master-clock 1.0)))
    (is (≈ 3.0 (clock/next-edge clock/master-clock 2.1)))))

;; ---------------------------------------------------------------------------
;; clock-mul
;; ---------------------------------------------------------------------------

(deftest clock-mul-double-speed-test
  (testing "clock-mul 2 fires twice per beat"
    (let [cm (clock/clock-mul 2)]
      (is (≈ 0.0  (clock/sample cm 0.0)))
      (is (≈ 0.0  (clock/sample cm 0.5)))   ; wraps: 2×0.5 = 1.0 → 0.0
      (is (≈ 0.5  (clock/sample cm 0.25)))  ; 2×0.25 = 0.5
      (is (≈ 0.5  (clock/next-edge cm 0)))  ; next edge at 0.5 beats
      (is (≈ 1.0  (clock/next-edge cm 0.5))))))

(deftest clock-mul-fractional-rate-test
  (testing "clock-mul 3/2 produces 3:2 polyrhythm"
    (let [cm (clock/clock-mul 3/2)]
      ;; next edge at beat 2/3 (first cycle of 1.5-per-beat clock)
      (is (≈ (/ 2.0 3.0) (clock/next-edge cm 0))))))

(deftest clock-mul-next-edge-contract-test
  (testing "clock-mul next-edge always returns > current beat"
    (let [cm (clock/clock-mul 4)]
      (doseq [b [0 0.1 0.25 0.5 0.75 1.0 1.5 2.0]]
        (is (> (clock/next-edge cm b) (double b))
            (str "next-edge not > current at beat " b))))))

;; ---------------------------------------------------------------------------
;; clock-div
;; ---------------------------------------------------------------------------

(deftest clock-div-sample-test
  (testing "clock-div n: one cycle per n beats"
    (let [cd (clock/clock-div 2)]
      (is (≈ 0.0  (clock/sample cd 0)))
      (is (≈ 0.25 (clock/sample cd 0.5)))  ; 0.5 * (1/2) = 0.25
      (is (≈ 0.5  (clock/sample cd 1.0)))  ; 1.0 * (1/2) = 0.5
      (is (≈ 0.0  (clock/sample cd 2.0))))))

(deftest clock-div-next-edge-contract-test
  (testing "clock-div next-edge always returns > current beat"
    (let [cd (clock/clock-div 8)]
      (doseq [b [0 1 3 7 8 9 16]]
        (is (> (clock/next-edge cd b) (double b))
            (str "next-edge not > current at beat " b))))))

;; ---------------------------------------------------------------------------
;; clock-shift
;; ---------------------------------------------------------------------------

(deftest clock-shift-offset-test
  (testing "clock-shift offsets phase by the given fraction"
    (let [cs (clock/clock-shift 1 0.25)]
      ;; sample at beat 0: phase = wrap(0 * 1 + 0.25) = 0.25
      (is (≈ 0.25 (clock/sample cs 0)))
      ;; sample at beat 0.75: phase = wrap(0.75 + 0.25) = 0.0
      (is (≈ 0.0  (clock/sample cs 0.75))))))

;; ---------------------------------------------------------------------------
;; BPM utilities
;; ---------------------------------------------------------------------------

(deftest bpm->ms-per-beat-test
  (testing "bpm->ms-per-beat at standard tempos"
    (is (≈ 500.0   (clock/bpm->ms-per-beat 120)))   ; 120 BPM → 500ms/beat
    (is (≈ 600.0   (clock/bpm->ms-per-beat 100)))   ; 100 BPM → 600ms/beat
    (is (≈ 1000.0  (clock/bpm->ms-per-beat 60)))    ; 60 BPM  → 1000ms/beat
    (is (≈ 250.0   (clock/bpm->ms-per-beat 240))))) ; 240 BPM → 250ms/beat

(deftest beats->ms-test
  (testing "beats->ms converts beat duration to wall-clock ms"
    ;; At 120 BPM: 1 beat = 500ms
    (is (≈ 500.0  (clock/beats->ms 1   120)))
    (is (≈ 250.0  (clock/beats->ms 1/2 120)))
    (is (≈ 125.0  (clock/beats->ms 1/4 120)))
    (is (≈ 2000.0 (clock/beats->ms 4   120)))))

(deftest now-ns-positive-and-growing-test
  (testing "now-ns returns a positive long"
    (let [t (clock/now-ns)]
      (is (pos? t) "epoch-ns is positive")
      (is (instance? Long t))))
  (testing "now-ns increases monotonically"
    (let [t0 (clock/now-ns)
          _  (Thread/sleep 1)
          t1 (clock/now-ns)]
      (is (> t1 t0) "time moves forward"))))

;; ---------------------------------------------------------------------------
;; Timeline beat↔epoch-ms conversions
;; ---------------------------------------------------------------------------

(def ^:private tl-120
  "Reference timeline at 120 BPM anchored at epoch 1000000ms, beat 0."
  {:bpm 120 :beat0-epoch-ms 1000000 :beat0-beat 0.0})

(deftest beat->epoch-ms-test
  (testing "beat->epoch-ms at 120 BPM (500ms/beat)"
    ;; beat 0 → epoch 1000000
    (is (= 1000000 (clock/beat->epoch-ms 0.0 tl-120)))
    ;; beat 1 → epoch 1000000 + 500
    (is (= 1000500 (clock/beat->epoch-ms 1.0 tl-120)))
    ;; beat 4 → epoch 1000000 + 2000
    (is (= 1002000 (clock/beat->epoch-ms 4.0 tl-120))))
  (testing "beat->epoch-ms with non-zero anchor beat"
    (let [tl {:bpm 120 :beat0-epoch-ms 2000000 :beat0-beat 8.0}]
      ;; beat 9 = beat0 + 1 → epoch 2000000 + 500
      (is (= 2000500 (clock/beat->epoch-ms 9.0 tl))))))

(deftest epoch-ms->beat-test
  (testing "epoch-ms->beat at 120 BPM"
    (is (≈ 0.0 (clock/epoch-ms->beat 1000000 tl-120)))
    (is (≈ 1.0 (clock/epoch-ms->beat 1000500 tl-120)))
    (is (≈ 4.0 (clock/epoch-ms->beat 1002000 tl-120)))))

(deftest beat-epoch-round-trip-test
  (testing "beat→epoch→beat round-trip is identity"
    (doseq [b [0.0 0.5 1.0 2.5 8.0 16.0 100.0]]
      (let [epoch (clock/beat->epoch-ms b tl-120)
            back  (clock/epoch-ms->beat epoch tl-120)]
        (is (≈ b back) (str "round-trip failed at beat " b)))))
  (testing "epoch→beat→epoch round-trip is identity"
    (doseq [ms [1000000 1000250 1000500 1001000 1010000]]
      (let [beat  (clock/epoch-ms->beat ms tl-120)
            back  (clock/beat->epoch-ms beat tl-120)]
        (is (= ms back) (str "round-trip failed at epoch-ms " ms))))))

(deftest beat->epoch-ns-test
  (testing "beat->epoch-ns = beat->epoch-ms × 1000000"
    (is (= (* 1000500 1000000) (clock/beat->epoch-ns 1.0 tl-120)))
    (is (= (* 1000000 1000000) (clock/beat->epoch-ns 0.0 tl-120)))))

;; ---------------------------------------------------------------------------
;; next-edge contract: must always be strictly > current beat
;; ---------------------------------------------------------------------------

(deftest next-edge-always-advances-test
  (testing "next-edge is strictly greater than current beat for all constructors"
    (let [clocks [(clock/->Phasor 1 0)
                  (clock/->Phasor 1/3 0)
                  (clock/clock-div 4)
                  (clock/clock-mul 3)
                  (clock/clock-shift 2 0.5)]]
      (doseq [c clocks
              b [0.0 0.1 0.5 0.999 1.0 2.0 7.99 8.0 100.0]]
        (is (> (clock/next-edge c b) b)
            (str "next-edge not > " b " for " c))))))
