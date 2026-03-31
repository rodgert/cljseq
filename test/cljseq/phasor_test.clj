; SPDX-License-Identifier: EPL-2.0
(ns cljseq.phasor-test
  "Unit tests for cljseq.phasor pure operations and shape functions."
  (:require [clojure.test :refer [deftest is testing are]]
            [cljseq.clock  :as clock]
            [cljseq.phasor :as phasor]))

(def eps 1e-9)
(defn ≈ [a b] (< (Math/abs (- (double a) (double b))) eps))

;; ---------------------------------------------------------------------------
;; wrap
;; ---------------------------------------------------------------------------

(deftest wrap-test
  (testing "wrap: values already in [0,1) are unchanged"
    (is (≈ 0.0  (phasor/wrap 0.0)))
    (is (≈ 0.5  (phasor/wrap 0.5)))
    (is (≈ 0.99 (phasor/wrap 0.99))))
  (testing "wrap: integers wrap to 0"
    (is (≈ 0.0 (phasor/wrap 1.0)))
    (is (≈ 0.0 (phasor/wrap 4.0)))
    (is (≈ 0.0 (phasor/wrap -1.0))))
  (testing "wrap: fractional values above 1 are reduced"
    (is (≈ 0.3 (phasor/wrap 1.3)))
    (is (≈ 0.7 (phasor/wrap 2.7))))
  (testing "wrap: negative fractional values"
    (is (≈ 0.7 (phasor/wrap -0.3)))))

;; ---------------------------------------------------------------------------
;; fold
;; ---------------------------------------------------------------------------

(deftest fold-test
  (testing "fold: 0 and 1 map to 0"
    (is (≈ 0.0 (phasor/fold 0.0)))
    (is (≈ 0.0 (phasor/fold 1.0))))
  (testing "fold: midpoint maps to 1"
    (is (≈ 1.0 (phasor/fold 0.5))))
  (testing "fold: quarter points map to 0.5"
    (is (≈ 0.5 (phasor/fold 0.25)))
    (is (≈ 0.5 (phasor/fold 0.75)))))

;; ---------------------------------------------------------------------------
;; scale
;; ---------------------------------------------------------------------------

(deftest scale-test
  (testing "scale: 0 maps to lo"
    (is (≈ 20.0 (phasor/scale 0.0 20.0 80.0))))
  (testing "scale: 1 maps to hi"
    (is (≈ 80.0 (phasor/scale 1.0 20.0 80.0))))
  (testing "scale: midpoint maps to midpoint"
    (is (≈ 50.0 (phasor/scale 0.5 20.0 80.0)))))

;; ---------------------------------------------------------------------------
;; index
;; ---------------------------------------------------------------------------

(deftest index-test
  (testing "index: 16-step sequence"
    (is (= 0  (phasor/index 0.0   16)))
    (is (= 0  (phasor/index 0.06  16)))
    (is (= 1  (phasor/index 0.0625 16)))
    (is (= 8  (phasor/index 0.5   16)))
    (is (= 15 (phasor/index 0.99  16)))))

;; ---------------------------------------------------------------------------
;; step
;; ---------------------------------------------------------------------------

(deftest step-test
  (testing "step: quantises to n equal levels"
    (is (≈ 0.0    (phasor/step 0.0   4)))
    (is (≈ 0.0    (phasor/step 0.1   4)))
    (is (≈ 0.25   (phasor/step 0.25  4)))
    (is (≈ 0.25   (phasor/step 0.4   4)))
    (is (≈ 0.5    (phasor/step 0.5   4)))
    (is (≈ 0.75   (phasor/step 0.75  4)))))

;; ---------------------------------------------------------------------------
;; delta
;; ---------------------------------------------------------------------------

(deftest delta-test
  (testing "delta: 0 when no wrap"
    (is (= 0 (phasor/delta 0.5 0.4)))
    (is (= 0 (phasor/delta 0.9 0.8))))
  (testing "delta: 1 when wrap occurred"
    (is (= 1 (phasor/delta 0.05 0.95)))
    (is (= 1 (phasor/delta 0.0  0.99)))))

;; ---------------------------------------------------------------------------
;; sync
;; ---------------------------------------------------------------------------

(deftest phase-reset-test
  (testing "phase-reset: resets phase to 0 on positive trigger"
    (is (≈ 0.0 (phasor/phase-reset 0.7 1)))
    (is (≈ 0.0 (phasor/phase-reset 0.3 5))))
  (testing "phase-reset: preserves phase when trigger is zero or negative"
    (is (≈ 0.7 (phasor/phase-reset 0.7 0)))
    (is (≈ 0.3 (phasor/phase-reset 0.3 -1)))))

;; ---------------------------------------------------------------------------
;; Shape functions
;; ---------------------------------------------------------------------------

(deftest sine-uni-test
  (testing "sine-uni: [0,1] range"
    (is (≈ 0.5 (phasor/sine-uni 0.0)))   ; sin(0) = 0, scaled to 0.5
    (is (≈ 1.0 (phasor/sine-uni 0.25)))  ; sin(π/2) = 1
    (is (≈ 0.5 (phasor/sine-uni 0.5)))   ; sin(π) = 0
    (is (≈ 0.0 (phasor/sine-uni 0.75))))) ; sin(3π/2) = -1

(deftest sine-bi-test
  (testing "sine-bi: [-1,1] range"
    (is (≈  0.0 (phasor/sine-bi 0.0)))
    (is (≈  1.0 (phasor/sine-bi 0.25)))
    (is (≈  0.0 (phasor/sine-bi 0.5)))
    (is (≈ -1.0 (phasor/sine-bi 0.75)))))

(deftest triangle-test
  (testing "triangle: [0,1] range, peaks at 0.5"
    (is (≈ 0.0 (phasor/triangle 0.0)))
    (is (≈ 0.5 (phasor/triangle 0.25)))
    (is (≈ 1.0 (phasor/triangle 0.5)))
    (is (≈ 0.5 (phasor/triangle 0.75)))))

(deftest saw-test
  (testing "saw-up: identity"
    (is (≈ 0.0 (phasor/saw-up 0.0)))
    (is (≈ 0.5 (phasor/saw-up 0.5)))
    (is (≈ 0.9 (phasor/saw-up 0.9))))
  (testing "saw-down: inverted"
    (is (≈ 1.0 (phasor/saw-down 0.0)))
    (is (≈ 0.5 (phasor/saw-down 0.5)))
    (is (≈ 0.1 (phasor/saw-down 0.9)))))

(deftest square-test
  (testing "square 50% duty cycle"
    (let [sq (phasor/square 0.5)]
      (is (≈ 1.0 (sq 0.0)))
      (is (≈ 1.0 (sq 0.49)))
      (is (≈ 0.0 (sq 0.5)))
      (is (≈ 0.0 (sq 0.99)))))
  (testing "square 25% duty cycle"
    (let [sq (phasor/square 0.25)]
      (is (≈ 1.0 (sq 0.1)))
      (is (≈ 0.0 (sq 0.3))))))

;; ---------------------------------------------------------------------------
;; Phasor record (cljseq.clock integration)
;; ---------------------------------------------------------------------------

(deftest phasor-record-test
  (testing "Phasor sample wraps correctly"
    (let [ph (clock/->Phasor 1 0)]
      (is (≈ 0.0 (clock/sample ph 0)))
      (is (≈ 0.5 (clock/sample ph 0.5)))
      (is (≈ 0.0 (clock/sample ph 1.0)))))
  (testing "Phasor next-edge"
    (let [ph (clock/->Phasor 1 0)]
      (is (≈ 1.0 (clock/next-edge ph 0)))
      (is (≈ 1.0 (clock/next-edge ph 0.3)))
      (is (≈ 2.0 (clock/next-edge ph 1.0)))))
  (testing "clock-div/4 next-edge"
    (let [cd (clock/clock-div 4)]
      (is (≈ 4.0 (clock/next-edge cd 0)))
      (is (≈ 4.0 (clock/next-edge cd 1)))
      (is (≈ 8.0 (clock/next-edge cd 4)))))
  (testing "clock-shift phase offset"
    (let [ph (clock/clock-shift 4 0.5)]
      ;; At beat 0, phase = wrap(0 * 1/4 + 0.5) = 0.5
      (is (≈ 0.5 (clock/sample ph 0))))))
