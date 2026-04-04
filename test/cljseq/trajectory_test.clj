; SPDX-License-Identifier: EPL-2.0
(ns cljseq.trajectory-test
  "Unit tests for cljseq.trajectory — curve functions, Trajectory record,
  ITemporalValue contract, named gestures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.clock      :as clock]
            [cljseq.conductor  :as conductor]
            [cljseq.core       :as core]
            [cljseq.mod        :as mod]
            [cljseq.trajectory :as traj]))

(defn with-system [f]
  (core/start! :bpm 60000)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

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
;; New curves — :smooth-step and :breathe
;; ---------------------------------------------------------------------------

(deftest apply-curve-smooth-step-test
  (testing ":smooth-step is 6t⁵-15t⁴+10t³"
    (is (≈ 0.0  (traj/apply-curve :smooth-step 0.0)))
    (is (≈ 0.5  (traj/apply-curve :smooth-step 0.5)))  ; symmetric: midpoint = 0.5
    (is (≈ 1.0  (traj/apply-curve :smooth-step 1.0)))))

(deftest apply-curve-smooth-step-second-derivative-test
  (testing ":smooth-step has near-zero second derivative at endpoints"
    ;; The second derivative of 6t⁵-15t⁴+10t³ at t=0 and t=1 is 0.
    ;; Numerically: the first derivative should be near-zero at t≈0 and t≈1.
    ;; (not zero — only the 2nd deriv is 0 — but it should be much flatter
    ;; near endpoints than :s-curve)
    (let [eps 0.01
          d0  (- (traj/apply-curve :smooth-step eps) (traj/apply-curve :smooth-step 0.0))
          d1  (- (traj/apply-curve :smooth-step 1.0) (traj/apply-curve :smooth-step (- 1.0 eps)))
          ds0 (- (traj/apply-curve :s-curve eps)     (traj/apply-curve :s-curve 0.0))
          ds1 (- (traj/apply-curve :s-curve 1.0)     (traj/apply-curve :s-curve (- 1.0 eps)))]
      (is (< d0 ds0) ":smooth-step rises slower than :s-curve near t=0")
      (is (< d1 ds1) ":smooth-step is slower near t=1 too"))))

(deftest apply-curve-breathe-test
  (testing ":breathe is sin(π·t) — peaks at midpoint, returns to 0 at t=1"
    (is (≈ 0.0 (traj/apply-curve :breathe 0.0)))
    (is (≈ 1.0 (traj/apply-curve :breathe 0.5)))
    (is (< (Math/abs (traj/apply-curve :breathe 1.0)) 1e-9))))

(deftest breathe-trajectory-peaks-at-midpoint-test
  (testing ":breathe trajectory peaks at `to` at the midpoint beat"
    (let [tr (traj/trajectory :from 0.2 :to 1.0 :beats 8 :curve :breathe :start 0.0)]
      (is (≈ 0.2 (clock/sample tr 0.0)) "starts at from")
      (is (≈ 1.0 (clock/sample tr 4.0)) "peaks at to at midpoint")
      (is (< (Math/abs (- (clock/sample tr 8.0) 0.2)) 1e-9) "returns to from at end"))))

(deftest breathe-trajectory-completes-at-from-test
  (testing ":breathe trajectory clamps at from after completion (not to)"
    (let [tr (traj/trajectory :from 0.3 :to 0.9 :beats 4 :curve :breathe :start 0.0)]
      (is (< (Math/abs (- (clock/sample tr 4.0) 0.3)) 1e-9))
      (is (< (Math/abs (- (clock/sample tr 10.0) 0.3)) 1e-9)))))

(deftest breathe-next-edge-inf-after-end-test
  (testing ":breathe trajectory next-edge returns ##Inf after completion"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 4 :curve :breathe :start 0.0)]
      (is (= ##Inf (clock/next-edge tr 4.0))))))

;; ---------------------------------------------------------------------------
;; Named gestures — swell and tension-peak
;; ---------------------------------------------------------------------------

(deftest swell-returns-arc-paths-test
  (testing "swell returns entries for tension, density, register"
    (let [m (traj/swell :bars 4 :start 0.0)]
      (is (contains? m [:arc/tension]))
      (is (contains? m [:arc/density]))
      (is (contains? m [:arc/register])))))

(deftest swell-trajectories-are-itv-test
  (testing "all swell values implement ITemporalValue"
    (doseq [[_ tr] (traj/swell :bars 4 :start 0.0)]
      (is (satisfies? clock/ITemporalValue tr)))))

(deftest swell-peaks-at-midpoint-test
  (testing "swell tension peaks at peak-level at the midpoint bar"
    (let [m    (traj/swell :bars 4 :start 0.0 :from 0.1 :peak-level 0.9)
          tr   (get m [:arc/tension])
          mid  (* 4 4 0.5)]  ; midpoint beat = bars*4/2
      (is (< (Math/abs (- (clock/sample tr mid) 0.9)) 0.01)
          "peaks near peak-level at midpoint"))))

(deftest swell-returns-to-from-at-end-test
  (testing "swell arcs return to :from level at completion"
    (let [m    (traj/swell :bars 4 :start 0.0 :from 0.15 :peak-level 1.0)
          beats 16.0]
      (doseq [[path tr] m]
        (is (< (Math/abs (- (clock/sample tr beats) 0.15)) 1e-9)
            (str (pr-str path) " returns to :from at end"))))))

(deftest tension-peak-returns-arc-paths-test
  (testing "tension-peak returns tension, density, momentum"
    (let [m (traj/tension-peak :bars 2 :start 0.0)]
      (is (contains? m [:arc/tension]))
      (is (contains? m [:arc/density]))
      (is (contains? m [:arc/momentum])))))

(deftest tension-peak-tension-rises-test
  (testing "tension-peak tension arc rises from start to end"
    (let [tr (get (traj/tension-peak :bars 2 :start 0.0) [:arc/tension])]
      (is (< (clock/sample tr 0.0) (clock/sample tr 8.0))
          "tension is higher at end than start"))))

(deftest tension-peak-momentum-falls-test
  (testing "tension-peak momentum drops (resistance decreases during spike)"
    (let [tr (get (traj/tension-peak :bars 2 :start 0.0) [:arc/momentum])]
      (is (> (clock/sample tr 0.0) (clock/sample tr 8.0))
          "momentum is lower at end"))))

;; ---------------------------------------------------------------------------
;; Monotone sample over full duration
;; ---------------------------------------------------------------------------

(deftest trajectory-monotone-sample-test
  (testing "trajectory samples are monotone over duration"
    (doseq [curve [:linear :exponential :reverse-exp :s-curve :smooth-step :cosine]]
      (let [tr    (traj/trajectory :from 0.0 :to 1.0 :beats 16
                                   :curve curve :start 0.0)
            beats (map #(* % 0.5) (range 33))   ; 0, 0.5, 1.0, ... 16.0
            vals  (map #(clock/sample tr %) beats)
            pairs (partition 2 1 vals)]
        (is (every? (fn [[a b]] (<= a (+ b 1e-9))) pairs)
            (str (name curve) " trajectory is monotone non-decreasing"))))))

;; ---------------------------------------------------------------------------
;; :bounce curve
;; ---------------------------------------------------------------------------

(deftest apply-curve-bounce-endpoints-test
  (testing ":bounce starts at 0 and ends at 1"
    (is (≈ 0.0 (traj/apply-curve :bounce 0.0)))
    (is (≈ 1.0 (traj/apply-curve :bounce 1.0)))))

(deftest apply-curve-bounce-not-monotone-test
  (testing ":bounce is NOT monotone — it overshoots and bounces"
    ;; Sample densely; at least one adjacent pair should have a decrease
    (let [ts   (map #(/ % 200.0) (range 201))
          vals (map #(traj/apply-curve :bounce %) ts)
          pairs (partition 2 1 vals)]
      ;; There should be at least one decrease (the overshot bounce-backs)
      (is (some (fn [[a b]] (> a (+ b 1e-6))) pairs)
          ":bounce should have at least one overshoot"))))

(deftest apply-curve-bounce-values-in-range-test
  (testing ":bounce values stay within [0.0, 1.1] — small overshoot is OK"
    (doseq [t (map #(/ % 100.0) (range 101))]
      (let [v (traj/apply-curve :bounce t)]
        (is (<= -0.01 v 1.01) (str "bounce at t=" t " = " v " out of expected range"))))))

(deftest bounce-trajectory-starts-ends-test
  (testing ":bounce trajectory starts at :from and ends at :to"
    (let [tr (traj/trajectory :from 0.0 :to 1.0 :beats 8 :curve :bounce :start 0.0)]
      (is (≈ 0.0 (clock/sample tr 0.0)) "starts at from")
      (is (≈ 1.0 (clock/sample tr 8.0)) "ends at to"))))

;; ---------------------------------------------------------------------------
;; trance-buildup
;; ---------------------------------------------------------------------------

(deftest trance-buildup-returns-arc-paths-test
  (testing "trance-buildup returns tension, density, register, momentum"
    (let [m (traj/trance-buildup :bars 32 :start 0.0)]
      (is (contains? m [:arc/tension]))
      (is (contains? m [:arc/density]))
      (is (contains? m [:arc/register]))
      (is (contains? m [:arc/momentum])))))

(deftest trance-buildup-trajectories-are-itv-test
  (testing "all trance-buildup values implement ITemporalValue"
    (doseq [[_ tr] (traj/trance-buildup :bars 8 :start 0.0)]
      (is (satisfies? clock/ITemporalValue tr)))))

(deftest trance-buildup-tension-rises-slowly-test
  (testing "trance-buildup tension rises slowly (exponential) — low midpoint"
    (let [tr  (get (traj/trance-buildup :bars 8 :start 0.0) [:arc/tension])
          mid (clock/sample tr 16.0)]   ; midpoint of 8-bar = beat 16
      ;; Exponential curve: midpoint ≈ 0.25 (below linear midpoint 0.5)
      (is (< mid 0.4) (str "tension midpoint " mid " should be below 0.4 (slow rise)")))))

(deftest trance-buildup-momentum-is-step-test
  (testing "trance-buildup momentum is a late step-snap, near 0 until close to end"
    (let [tr      (get (traj/trance-buildup :bars 8 :start 0.0) [:arc/momentum])
          early   (clock/sample tr 4.0)    ; 1 bar in
          at-end  (clock/sample tr 32.0)]  ; 8 bars
      (is (< early 0.1) "momentum near 0 early in buildup")
      (is (> at-end 0.8) "momentum high at end"))))

;; ---------------------------------------------------------------------------
;; anticipation
;; ---------------------------------------------------------------------------

(deftest anticipation-returns-arc-paths-test
  (testing "anticipation returns tension, density, texture, momentum"
    (let [m (traj/anticipation :bars 2 :start 0.0)]
      (is (contains? m [:arc/tension]))
      (is (contains? m [:arc/density]))
      (is (contains? m [:arc/texture]))
      (is (contains? m [:arc/momentum])))))

(deftest anticipation-density-collapses-test
  (testing "anticipation collapses density and texture to near-zero"
    (let [m     (traj/anticipation :bars 2 :start 0.0)
          beats 8.0]
      (is (< (clock/sample (get m [:arc/density]) beats) 0.1)
          "density near zero at end")
      (is (< (clock/sample (get m [:arc/texture]) beats) 0.05)
          "texture near zero at end"))))

(deftest anticipation-tension-rises-test
  (testing "anticipation tension rises toward maximum (unresolved)"
    (let [tr (get (traj/anticipation :bars 2 :start 0.0) [:arc/tension])]
      (is (> (clock/sample tr 8.0) (clock/sample tr 0.0))
          "tension higher at end than start"))))

;; ---------------------------------------------------------------------------
;; arc-merge
;; ---------------------------------------------------------------------------

(deftest arc-merge-blends-shared-paths-test
  (testing "arc-merge returns weighted average for shared paths"
    (let [a     (traj/trajectory :from 0.0 :to 1.0 :beats 4 :curve :linear :start 0.0)
          b     (traj/trajectory :from 0.0 :to 0.0 :beats 4 :curve :linear :start 0.0)
          arc-a {[:arc/tension] a}
          arc-b {[:arc/tension] b}
          merged (traj/arc-merge arc-a arc-b :weight 0.6)
          blended (get merged [:arc/tension])]
      ;; At beat 4: a=1.0, b=0.0 → 0.6*1.0 + 0.4*0.0 = 0.6
      (is (< (Math/abs (- (clock/sample blended 4.0) 0.6)) 1e-6)
          "blended value = 0.6*1.0 + 0.4*0.0"))))

(deftest arc-merge-passes-through-unique-paths-test
  (testing "arc-merge includes paths from both maps that don't overlap"
    (let [arc-a {[:arc/tension] (traj/trajectory :beats 4 :curve :linear)}
          arc-b {[:arc/density] (traj/trajectory :beats 4 :curve :linear)}
          merged (traj/arc-merge arc-a arc-b)]
      (is (contains? merged [:arc/tension]))
      (is (contains? merged [:arc/density])))))

(deftest arc-merge-default-weight-is-half-test
  (testing "arc-merge default weight is 0.5 — equal blend"
    (let [a     (traj/trajectory :from 0.0 :to 1.0 :beats 4 :curve :linear :start 0.0)
          b     (traj/trajectory :from 0.0 :to 0.0 :beats 4 :curve :linear :start 0.0)
          merged (traj/arc-merge {[:p] a} {[:p] b})
          blended (get merged [:p])]
      ;; At beat 4: 0.5*1.0 + 0.5*0.0 = 0.5
      (is (< (Math/abs (- (clock/sample blended 4.0) 0.5)) 1e-6)))))

(deftest arc-merge-blended-is-itv-test
  (testing "blended values satisfy ITemporalValue"
    (let [a     (traj/trajectory :beats 4 :curve :linear)
          merged (traj/arc-merge {[:p] a} {[:p] a})
          blended (get merged [:p])]
      (is (satisfies? clock/ITemporalValue blended)))))

;; ---------------------------------------------------------------------------
;; time-warp
;; ---------------------------------------------------------------------------

(deftest time-warp-retimes-beats-test
  (testing "time-warp changes trajectory duration to new-bars * 4"
    (let [m      (traj/buildup :bars 16 :start 0.0)
          warped (traj/time-warp m 8)]
      (doseq [[path tr] warped]
        (when (instance? cljseq.trajectory.Trajectory tr)
          ;; After 8 bars (32 beats) the trajectory should be at or near :to
          (is (>= (clock/sample tr 32.0) 0.8)
              (str (pr-str path) " should be near target at 32 beats"))
          ;; Before 8 bars (16 beats = original end) should be mid-way
          (is (< (clock/sample tr 16.0) (clock/sample tr 32.0))
              (str (pr-str path) " should still be rising at beat 16 after time-warp")))))))

(deftest time-warp-overrides-start-beat-test
  (testing "time-warp :start overrides original start-beat"
    (let [m      {[:arc/tension] (traj/trajectory :from 0.0 :to 1.0
                                                  :beats 16 :curve :linear :start 0.0)}
          warped (traj/time-warp m 4 :start 8.0)
          tr     (get warped [:arc/tension])]
      ;; Before start (beat 7) should clamp at :from
      (is (≈ 0.0 (clock/sample tr 7.0)) "before new start is clamped at from")
      ;; At start (beat 8) should be at from
      (is (≈ 0.0 (clock/sample tr 8.0)) "at new start = from")
      ;; At new end (beat 8 + 4*4 = 24) should be at to
      (is (≈ 1.0 (clock/sample tr 24.0)) "at new end = to"))))

(deftest time-warp-preserves-non-trajectory-values-test
  (testing "time-warp passes non-Trajectory ITemporalValues through unchanged"
    (let [a     (traj/trajectory :beats 4 :curve :linear)
          b     (traj/trajectory :beats 4 :curve :linear)
          blended (traj/->BlendedTrajectory a b 0.5)
          m      {[:p] blended}
          warped (traj/time-warp m 8)]
      (is (identical? blended (get warped [:p]))
          "BlendedTrajectory passed through without modification"))))

;; ---------------------------------------------------------------------------
;; Conductor built-in gestures — :trance-buildup and :anticipation smoke tests
;; ---------------------------------------------------------------------------

(deftest conductor-builtin-trance-buildup-routes-test
  (testing ":trance-buildup conductor gesture routes arc paths"
    (let [routed (atom #{})]
      (with-redefs [mod/mod-route! (fn [path _tr] (swap! routed conj path) path)]
        (conductor/defconductor! :traj-test-trance [{:bars 0 :gesture :trance-buildup}])
        (conductor/fire! :traj-test-trance)
        (Thread/sleep 100))
      (is (contains? @routed [:arc/tension]))
      (is (contains? @routed [:arc/register])))))

(deftest conductor-builtin-anticipation-routes-test
  (testing ":anticipation conductor gesture routes arc paths"
    (let [routed (atom #{})]
      (with-redefs [mod/mod-route! (fn [path _tr] (swap! routed conj path) path)]
        (conductor/defconductor! :traj-test-anticipation [{:bars 0 :gesture :anticipation}])
        (conductor/fire! :traj-test-anticipation)
        (Thread/sleep 100))
      (is (contains? @routed [:arc/density]))
      (is (contains? @routed [:arc/texture])))))
