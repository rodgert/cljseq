; SPDX-License-Identifier: EPL-2.0
(ns cljseq.trajectory-test
  "Unit tests for cljseq.trajectory — curve functions, Trajectory record,
  ITemporalValue contract, named gestures."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.clock      :as clock]
            [cljseq.trajectory :as traj]))

(def ^:private eps 1e-9)
(defn- ≈ [a b] (< (Math/abs (- (double a) (double b))) eps))

;; ---------------------------------------------------------------------------
;; apply-curve — direct curve fn access
;; ---------------------------------------------------------------------------

(deftest apply-curve-linear-test
  (testing ":linear is the identity on [0,1]"
    (is (≈ 0.0  (traj/apply-curve :linear 0.0)))
    (is (≈ 0.5  (traj/apply-curve :linear 0.5)))
    (is (≈ 1.0  (traj/apply-curve :linear 1.0)))))

(deftest apply-curve-exponential-test
  (testing ":exponential is t²"
    (is (≈ 0.0  (traj/apply-curve :exponential 0.0)))
    (is (≈ 0.25 (traj/apply-curve :exponential 0.5)))
    (is (≈ 1.0  (traj/apply-curve :exponential 1.0)))))

(deftest apply-curve-reverse-exp-test
  (testing ":reverse-exp is 1-(1-t)² — fast start, slow end"
    (is (≈ 0.0  (traj/apply-curve :reverse-exp 0.0)))
    (is (≈ 0.75 (traj/apply-curve :reverse-exp 0.5)))
    (is (≈ 1.0  (traj/apply-curve :reverse-exp 1.0)))))

(deftest apply-curve-s-curve-test
  (testing ":s-curve is 3t²-2t³ — symmetric S"
    (is (≈ 0.0  (traj/apply-curve :s-curve 0.0)))
    (is (≈ 0.5  (traj/apply-curve :s-curve 0.5)))  ; midpoint = exactly 0.5
    (is (≈ 1.0  (traj/apply-curve :s-curve 1.0)))))

(deftest apply-curve-cosine-test
  (testing ":cosine is (1-cos(π·t))/2"
    (is (≈ 0.0  (traj/apply-curve :cosine 0.0)))
    (is (≈ 0.5  (traj/apply-curve :cosine 0.5)))
    (is (≈ 1.0  (traj/apply-curve :cosine 1.0)))))

(deftest apply-curve-step-test
  (testing ":step holds at 0 then snaps to 1 at t=1"
    (is (≈ 0.0 (traj/apply-curve :step 0.0)))
    (is (≈ 0.0 (traj/apply-curve :step 0.5)))
    (is (≈ 0.0 (traj/apply-curve :step 0.999)))
    (is (≈ 1.0 (traj/apply-curve :step 1.0)))))

(deftest apply-curve-monotone-test
  (testing "all non-step curves are monotonically non-decreasing"
    (doseq [curve [:linear :exponential :reverse-exp :s-curve :cosine]]
      (let [ts    (map #(/ % 100.0) (range 101))
            vals  (map #(traj/apply-curve curve %) ts)
            pairs (partition 2 1 vals)]
        (is (every? (fn [[a b]] (<= (- b a) 1.0)) pairs)
            (str (name curve) " is monotone"))))))

;; ---------------------------------------------------------------------------
;; Trajectory record — ITemporalValue
;; ---------------------------------------------------------------------------

(deftest trajectory-sample-start-end-test
  (testing "sample at start returns :from; at end returns :to"
    (let [tr (traj/trajectory :from 0.2 :to 0.8 :beats 8 :curve :linear :start 0.0)]
      (is (≈ 0.2 (clock/sample tr 0.0)) "at start beat")
      (is (≈ 0.8 (clock/sample tr 8.0)) "at end beat"))))

(deftest trajectory-sample-midpoint-linear-test
  (testing ":linear midpoint is the mean of from and to"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 10 :curve :linear :start 0.0)]
      (is (≈ 0.5 (clock/sample tr 5.0))))))

(deftest trajectory-sample-midpoint-s-curve-test
  (testing ":s-curve midpoint is also 0.5 (symmetric)"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 10 :curve :s-curve :start 0.0)]
      (is (≈ 0.5 (clock/sample tr 5.0))))))

(deftest trajectory-clamps-before-start-test
  (testing "sample before start beat returns :from"
    (let [tr (traj/trajectory :from 0.3 :to 0.9 :beats 4 :curve :linear :start 10.0)]
      (is (≈ 0.3 (clock/sample tr 9.0)))
      (is (≈ 0.3 (clock/sample tr 0.0))))))

(deftest trajectory-clamps-after-end-test
  (testing "sample past end beat returns :to"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 4 :curve :linear :start 0.0)]
      (is (≈ 1.0 (clock/sample tr 4.0)))
      (is (≈ 1.0 (clock/sample tr 100.0))))))

(deftest trajectory-arbitrary-from-to-test
  (testing "from/to can be any range including reversed"
    (let [tr (traj/trajectory :from 1.0 :to 0.0 :beats 4 :curve :linear :start 0.0)]
      (is (≈ 1.0 (clock/sample tr 0.0)) "downward: start = 1.0")
      (is (≈ 0.5 (clock/sample tr 2.0)) "downward: midpoint = 0.5")
      (is (≈ 0.0 (clock/sample tr 4.0)) "downward: end = 0.0"))))

(deftest trajectory-non-zero-start-beat-test
  (testing "start-beat offsets the trajectory correctly"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 4 :curve :linear :start 8.0)]
      (is (≈ 0.0  (clock/sample tr 8.0)) "at start")
      (is (≈ 0.5  (clock/sample tr 10.0)) "midpoint")
      (is (≈ 1.0  (clock/sample tr 12.0)) "at end"))))

(deftest trajectory-step-curve-test
  (testing ":step holds at from until end then snaps to to"
    (let [tr (traj/trajectory :from 0.2 :to 0.9 :beats 4 :curve :step :start 0.0)]
      (is (≈ 0.2 (clock/sample tr 0.0)))
      (is (≈ 0.2 (clock/sample tr 2.0)))
      (is (≈ 0.2 (clock/sample tr 3.99)))
      (is (≈ 0.9 (clock/sample tr 4.0))))))

;; ---------------------------------------------------------------------------
;; Trajectory next-edge — ITemporalValue contract
;; ---------------------------------------------------------------------------

(deftest trajectory-next-edge-before-end-test
  (testing "next-edge returns a beat strictly greater than the queried beat"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 32 :curve :linear :start 0.0)]
      (is (> (clock/next-edge tr 0.0)  0.0))
      (is (> (clock/next-edge tr 10.0) 10.0))
      (is (> (clock/next-edge tr 31.0) 31.0)))))

(deftest trajectory-next-edge-after-end-returns-inf-test
  (testing "next-edge returns ##Inf once the trajectory is complete"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 4 :curve :linear :start 0.0)]
      (is (= ##Inf (clock/next-edge tr 4.0)))
      (is (= ##Inf (clock/next-edge tr 10.0))))))

(deftest trajectory-next-edge-exactly-at-end-test
  (testing "next-edge at exactly the end beat returns ##Inf"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 8 :curve :s-curve :start 2.0)]
      (is (= ##Inf (clock/next-edge tr 10.0))))))

;; ---------------------------------------------------------------------------
;; trajectory constructor validation
;; ---------------------------------------------------------------------------

(deftest trajectory-default-options-test
  (testing "defaults: from=0.0 to=1.0 curve=:linear start=0.0"
    (let [tr (traj/trajectory :beats 4)]
      (is (≈ 0.0 (clock/sample tr 0.0)))
      (is (≈ 1.0 (clock/sample tr 4.0))))))

(deftest trajectory-invalid-curve-throws-test
  (testing "unknown curve keyword throws assertion error"
    (is (thrown? AssertionError
                 (traj/trajectory :beats 4 :curve :no-such-curve)))))

(deftest trajectory-missing-beats-throws-test
  (testing "missing :beats throws assertion error"
    (is (thrown? AssertionError
                 (traj/trajectory :from 0.0 :to 1.0)))))

;; ---------------------------------------------------------------------------
;; Named gestures — structure and range checks
;; ---------------------------------------------------------------------------

(deftest buildup-returns-all-arc-paths-test
  (testing "buildup returns entries for tension/density/register/momentum"
    (let [m (traj/buildup :bars 8 :start 0.0)]
      (is (contains? m [:arc/tension]))
      (is (contains? m [:arc/density]))
      (is (contains? m [:arc/register]))
      (is (contains? m [:arc/momentum])))))

(deftest buildup-trajectories-are-itv-test
  (testing "all buildup values implement ITemporalValue"
    (doseq [[_ tr] (traj/buildup :bars 8 :start 0.0)]
      (is (satisfies? clock/ITemporalValue tr)))))

(deftest buildup-starts-at-from-ends-at-to-test
  (testing "buildup trajectories start at :from and end at :to"
    (let [m (traj/buildup :bars 4 :start 0.0 :from 0.1 :to 1.0)]
      (doseq [[path tr] m]
        (let [start-v (clock/sample tr 0.0)
              end-v   (clock/sample tr 16.0)]   ; 4 bars × 4 beats
          (is (<= (Math/abs (- start-v 0.1)) 0.01)
              (str (pr-str path) " starts near 0.1"))
          (is (>= end-v 0.9)
              (str (pr-str path) " ends near target")))))))

(deftest breakdown-returns-density-texture-momentum-test
  (testing "breakdown covers density, texture, momentum"
    (let [m (traj/breakdown :bars 4 :start 0.0)]
      (is (contains? m [:arc/density]))
      (is (contains? m [:arc/texture]))
      (is (contains? m [:arc/momentum])))))

(deftest breakdown-decreases-test
  (testing "breakdown samples decrease from start to end"
    (let [m (traj/breakdown :bars 4 :start 0.0)]
      (doseq [[path tr] m]
        (is (> (clock/sample tr 0.0) (clock/sample tr 16.0))
            (str (pr-str path) " decreases"))))))

(deftest groove-lock-returns-tension-momentum-density-test
  (testing "groove-lock covers tension, momentum, density"
    (let [m (traj/groove-lock :bars 8 :start 0.0)]
      (is (contains? m [:arc/tension]))
      (is (contains? m [:arc/momentum]))
      (is (contains? m [:arc/density])))))

(deftest wind-down-returns-all-arcs-test
  (testing "wind-down returns tension, density, momentum, texture, register"
    (let [m (traj/wind-down :bars 4 :start 0.0)]
      (is (contains? m [:arc/tension]))
      (is (contains? m [:arc/density]))
      (is (contains? m [:arc/momentum]))
      (is (contains? m [:arc/texture]))
      (is (contains? m [:arc/register])))))

(deftest wind-down-ends-near-zero-test
  (testing "wind-down trajectories end near 0 (wind-down to silence)"
    (let [m     (traj/wind-down :bars 4 :start 0.0)
          beats 16.0]
      (doseq [[path tr] m]
        (is (< (clock/sample tr beats) 0.25)
            (str (pr-str path) " ends near zero"))))))

;; ---------------------------------------------------------------------------
;; Monotone sample over full duration
;; ---------------------------------------------------------------------------

(deftest trajectory-monotone-sample-test
  (testing "trajectory samples are monotone over duration"
    (doseq [curve [:linear :exponential :reverse-exp :s-curve :cosine]]
      (let [tr    (traj/trajectory :from 0.0 :to 1.0 :beats 16
                                   :curve curve :start 0.0)
            beats (map #(* % 0.5) (range 33))   ; 0, 0.5, 1.0, ... 16.0
            vals  (map #(clock/sample tr %) beats)
            pairs (partition 2 1 vals)]
        (is (every? (fn [[a b]] (<= a (+ b 1e-9))) pairs)
            (str (name curve) " trajectory is monotone non-decreasing"))))))
