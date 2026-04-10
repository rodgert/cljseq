; SPDX-License-Identifier: EPL-2.0
(ns cljseq.spatial-field-test
  "Tests for cljseq.spatial-field — physics, geometry, registry, and modes."
  (:require [clojure.test         :refer [deftest is testing]]
            [cljseq.spatial-field :as sf]
            [cljseq.core          :as core]))

;; ---------------------------------------------------------------------------
;; N-gon geometry
;; ---------------------------------------------------------------------------

(deftest ngon-walls-count-test
  (testing "ngon-walls returns N walls for integer N"
    (doseq [n [3 4 5 6 7 8]]
      (is (= n (count (sf/ngon-walls n)))
          (str "N=" n " should produce " n " walls")))))

(deftest ngon-walls-decimal-test
  (testing "ngon-walls with decimal N rounds up to ceil(N) walls"
    (is (= 5 (count (sf/ngon-walls 4.7))))
    (is (= 6 (count (sf/ngon-walls 5.1))))
    (is (= 4 (count (sf/ngon-walls 3.2))))))

(deftest ngon-walls-structure-test
  (testing "each wall has :a :b :normal :type"
    (let [walls (sf/ngon-walls 4)]
      (doseq [w walls]
        (is (contains? w :a))
        (is (contains? w :b))
        (is (contains? w :normal))
        (is (contains? w :type))
        (is (= 2 (count (:a w))))
        (is (= 2 (count (:b w))))
        (is (= 2 (count (:normal w))))))))

(deftest ngon-walls-normal-length-test
  (testing "wall normals are unit vectors"
    (let [walls (sf/ngon-walls 6)]
      (doseq [w walls]
        (let [[nx ny] (:normal w)
              len     (Math/sqrt (+ (* nx nx) (* ny ny)))]
          (is (< (Math/abs (- len 1.0)) 1e-6)
              "normal should be a unit vector"))))))

(deftest ngon-walls-type-test
  (testing "wall-type option sets boundary type"
    (let [walls (sf/ngon-walls 4 :type :generate)]
      (is (every? #(= :generate (:type %)) walls))))
  (testing "wall-types per-wall override"
    (let [walls (sf/ngon-walls 4 :wall-types [:reflect :generate :absorb :portal])]
      (is (= :reflect  (:type (nth walls 0))))
      (is (= :generate (:type (nth walls 1))))
      (is (= :absorb   (:type (nth walls 2))))
      (is (= :portal   (:type (nth walls 3)))))))

;; ---------------------------------------------------------------------------
;; Ray-wall intersection / find-next-collision
;; ---------------------------------------------------------------------------

(deftest find-next-collision-hits-wall-test
  (testing "particle heading toward a wall finds a collision"
    (let [walls  (sf/ngon-walls 4)   ; square room
          ;; Particle at centre heading right
          particle {:position [0.5 0.5] :velocity [1.0 0.0] :mass 1.0 :age 0}
          result   (sf/find-next-collision particle walls)]
      (is (< (:t result) ##Inf) "should find a collision")
      (is (pos? (:t result))    "collision time should be positive")
      (is (contains? result :wall-idx)))))

(deftest find-next-collision-no-velocity-test
  (testing "stationary particle finds no collision"
    (let [walls    (sf/ngon-walls 4)
          particle {:position [0.5 0.5] :velocity [0.0 0.0] :mass 1.0 :age 0}
          result   (sf/find-next-collision particle walls)]
      (is (= ##Inf (:t result))))))

;; ---------------------------------------------------------------------------
;; step-particle
;; ---------------------------------------------------------------------------

(deftest step-particle-advances-position-test
  (testing "step-particle moves particle forward"
    (let [walls    (sf/ngon-walls 4)
          particle {:position [0.5 0.5] :velocity [0.2 0.0] :mass 1.0 :age 0}
          result   (sf/step-particle particle walls {} 0.01)]
      (is (contains? result :particle))
      (is (contains? result :events))
      (let [p' (:particle result)
            [px' _] (:position p')]
        (is (> px' 0.5) "x should have advanced right")))))

(deftest step-particle-reflects-off-wall-test
  (testing "particle reflecting off a wall reverses velocity component"
    ;; The N=4 room is a diamond (rotated square), not axis-aligned.
    ;; Wall 0 runs top→right; its midpoint is [0.725 0.725]; inward normal ≈ [-0.707 -0.707].
    ;; Aim particle from centre directly at wall-0 midpoint: vel = [1/√2, 1/√2].
    ;; After elastic reflection, velocity should become [-1/√2, -1/√2] (both negative).
    (let [walls    (sf/ngon-walls 4)
          inv-sq2  (/ 1.0 (Math/sqrt 2.0))
          particle {:position [0.5 0.5] :velocity [inv-sq2 inv-sq2] :mass 1.0 :age 0}
          ;; dt large enough to reach wall but small enough for one collision
          result   (sf/step-particle particle walls {} 0.5)]
      (let [[vx' vy'] (:velocity (:particle result))]
        (is (< vx' 0.0) "x velocity should be negative after top-right wall reflection")
        (is (< vy' 0.0) "y velocity should be negative after top-right wall reflection")))))

(deftest step-particle-generate-wall-fires-event-test
  (testing "collision with :generate wall produces an event"
    (let [walls    (sf/ngon-walls 4 :type :generate)
          particle {:position [0.89 0.5] :velocity [2.0 0.0] :mass 1.0 :age 0}
          result   (sf/step-particle particle walls {} 0.5)]
      (is (seq (:events result)) "should have at least one collision event"))))

(deftest step-particle-absorb-wall-stops-test
  (testing "particle absorbed at :absorb wall has near-zero velocity"
    (let [walls    (sf/ngon-walls 4 :type :absorb)
          particle {:position [0.89 0.5] :velocity [2.0 0.0] :mass 1.0 :age 0}
          result   (sf/step-particle particle walls {} 0.5)
          [vx' vy'] (:velocity (:particle result))]
      (is (and (< (Math/abs vx') 1e-4)
               (< (Math/abs vy') 1e-4))
          "absorbed particle should have near-zero velocity"))))

;; ---------------------------------------------------------------------------
;; Forces
;; ---------------------------------------------------------------------------

(deftest gravity-force-test
  (testing "gravity pulls particle downward"
    (let [walls    (sf/ngon-walls 4)
          particle {:position [0.5 0.5] :velocity [0.0 0.0] :mass 1.0 :age 0}
          forces   {:gravity {:direction :down :strength 1.0}}
          result   (sf/step-particle particle walls forces 0.1)
          [_ vy']  (:velocity (:particle result))]
      (is (< vy' 0.0) "gravity should pull vy negative"))))

(deftest damping-force-test
  (testing "damping reduces particle speed"
    (let [walls    (sf/ngon-walls 4)
          particle {:position [0.5 0.5] :velocity [1.0 0.0] :mass 1.0 :age 0}
          forces   {:damping 0.5}
          result   (sf/step-particle particle walls forces 0.01)
          [vx' _]  (:velocity (:particle result))]
      (is (< vx' 1.0) "damping should reduce speed"))))

;; ---------------------------------------------------------------------------
;; Axis mappings
;; ---------------------------------------------------------------------------

(deftest particle-axes-test
  (testing "particle-axes returns all expected keys"
    (let [p    {:position [0.7 0.3] :velocity [0.5 -0.2] :mass 1.0 :age 10}
          axes (#'sf/particle-axes p)]
      (doseq [k [:x :y :r :θ :vx :vy :speed :age]]
        (is (contains? axes k) (str k " should be in axes"))))))

(deftest map-particle-test
  (testing "map-particle applies mappings and returns target values"
    (let [p    {:position [1.0 0.5] :velocity [0.0 0.0] :mass 1.0 :age 0}
          mapped (sf/map-particle p {:x :pan})]
      (is (contains? mapped :pan))
      (is (number? (:pan mapped))))))

;; ---------------------------------------------------------------------------
;; VBAP quad-gains
;; ---------------------------------------------------------------------------

(deftest quad-gains-center-test
  (testing "centre position gives equal gains to all four channels"
    (let [[fl fr rl rr] (sf/quad-gains [0.5 0.5])]
      (is (< (Math/abs (- fl fr)) 1e-6) "FL=FR at centre")
      (is (< (Math/abs (- rl rr)) 1e-6) "RL=RR at centre")
      (is (< (Math/abs (- fl rl)) 1e-6) "FL=RL at centre"))))

(deftest quad-gains-left-test
  (testing "full-left position emphasises FL and RL"
    (let [[fl fr rl rr] (sf/quad-gains [0.0 0.5])]
      (is (> fl fr) "FL > FR when at left")
      (is (> rl rr) "RL > RR when at left"))))

(deftest quad-gains-front-test
  (testing "full-front position emphasises FL and FR"
    (let [[fl fr rl rr] (sf/quad-gains [0.5 1.0])]
      (is (> fl rl) "FL > RL when at front")
      (is (> fr rr) "FR > RR when at front"))))

(deftest quad-gains-power-test
  (testing "quad-gains sum of squares is approximately 1 (constant power)"
    (doseq [[x y] [[0.5 0.5] [0.0 0.5] [1.0 0.5] [0.5 0.0] [0.5 1.0]
                   [0.25 0.75] [0.8 0.2]]]
      (let [[fl fr rl rr] (sf/quad-gains [x y])
            power (+ (* fl fl) (* fr fr) (* rl rl) (* rr rr))]
        (is (< (Math/abs (- power 1.0)) 0.02)
            (str "constant power should hold at [" x " " y "] — got " power))))))

(deftest quad-gains-all-non-negative-test
  (testing "all gains are non-negative"
    (doseq [[x y] (for [x (range 0 11) y (range 0 11)]
                    [(/ x 10.0) (/ y 10.0)])]
      (let [gains (sf/quad-gains [x y])]
        (is (every? #(>= % 0.0) gains)
            (str "negative gain at [" x " " y "] — " gains))))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest defspatial-field-registers-test
  (testing "defspatial-field! registers a field retrievable by name"
    (sf/defspatial-field! ::test-field
      {:mode :generation :sides 4.0 :ball-speed 1.0})
    (is (some? (sf/get-field ::test-field)))
    (is (contains? (set (sf/field-names)) ::test-field))))

(deftest defspatial-field-walls-built-test
  (testing "registered field has walls built from sides"
    (sf/defspatial-field! ::walls-test
      {:mode :generation :sides 5.0})
    (let [field (sf/get-field ::walls-test)]
      (is (= 5 (count (:walls field)))))))

(deftest field-state-stopped-by-default-test
  (testing "newly registered field is stopped"
    (sf/defspatial-field! ::state-test
      {:mode :generation :sides 4.0})
    (is (= :stopped (sf/field-state ::state-test)))))

;; ---------------------------------------------------------------------------
;; Lifecycle: start!/stop!
;; ---------------------------------------------------------------------------

(deftest start-stop-modulation-field-test
  (testing "start-field! and stop-field! work for modulation mode"
    (core/start! :bpm 120)
    (try
      (sf/defspatial-field! ::mod-lifecycle
        {:mode :modulation :sides 4.0 :ball-speed 0.5 :particles 1})
      (sf/start-field! ::mod-lifecycle)
      (is (= :running (sf/field-state ::mod-lifecycle)))
      (Thread/sleep 80)  ; let it tick once
      (sf/stop-field! ::mod-lifecycle)
      (is (= :stopped (sf/field-state ::mod-lifecycle)))
      (finally (core/stop!)))))

(deftest start-stop-generation-field-test
  (testing "start-field! and stop-field! work for generation mode"
    (sf/defspatial-field! ::gen-lifecycle
      {:mode :generation :sides 4.0 :ball-speed 1.0})
    (sf/start-field! ::gen-lifecycle)
    (is (= :running (sf/field-state ::gen-lifecycle)))
    (sf/stop-field! ::gen-lifecycle)
    (is (= :stopped (sf/field-state ::gen-lifecycle)))))

;; ---------------------------------------------------------------------------
;; Named presets
;; ---------------------------------------------------------------------------

(deftest presets-registered-test
  (testing "all named presets are registered at load time"
    (doseq [p [:ricochet :dribble :drift :orbit :pinball
               :gravity-well :portal-room :instrument-quad]]
      (is (some? (sf/get-field p)) (str p " preset should be registered")))))

(deftest preset-modes-test
  (testing "preset modes are correct"
    (is (= :generation (:mode (sf/get-field :ricochet))))
    (is (= :generation (:mode (sf/get-field :dribble))))
    (is (= :modulation (:mode (sf/get-field :drift))))
    (is (= :modulation (:mode (sf/get-field :orbit))))
    (is (= :generation (:mode (sf/get-field :pinball))))))

;; ---------------------------------------------------------------------------
;; ITransformer
;; ---------------------------------------------------------------------------

(deftest field-transformer-returns-itransformer-test
  (testing "field-transformer returns an ITransformer"
    (sf/defspatial-field! ::xf-test
      {:mode :generation :sides 4.0 :ball-speed 1.0})
    (sf/start-field! ::xf-test)
    (try
      (let [xformer (sf/field-transformer ::xf-test)]
        (is (satisfies? cljseq.transform/ITransformer xformer)))
      (finally (sf/stop-field! ::xf-test)))))

(deftest field-transformer-passes-through-event-test
  (testing "transform returns the triggering event with delay-beats 0"
    (sf/defspatial-field! ::xf-passthrough
      {:mode :generation :sides 4.0 :ball-speed 0.01 :min-velocity 0.001})
    (sf/start-field! ::xf-passthrough)
    (try
      (let [xformer (sf/field-transformer ::xf-passthrough)
            event   {:pitch/midi 60 :dur/beats 1/4}
            results (cljseq.transform/transform xformer event)]
        (is (seq results) "transform should return at least one result")
        (is (= 0 (:delay-beats (first results)))
            "triggering event has delay-beats 0")
        (is (= event (:event (first results)))
            "triggering event is passed through unchanged"))
      (finally (sf/stop-field! ::xf-passthrough)))))
