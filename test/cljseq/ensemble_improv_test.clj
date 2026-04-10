; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ensemble-improv-test
  "Tests for cljseq.ensemble-improv — note generation, texture routing,
  agent lifecycle, and conductor gesture integration."
  (:require [clojure.test            :refer [deftest is testing use-fixtures]]
            [cljseq.ensemble-improv  :as improv]
            [cljseq.scale            :as scale-ns]
            [cljseq.texture          :as tx]))

;; ---------------------------------------------------------------------------
;; Helpers — direct atom access via Var
;;
;; agent-config is ^:private; we access it via #' to get the Var and deref
;; to get the atom. All lifecycle tests manipulate it directly to avoid
;; starting a live system or mocking a macro (deflive-loop is a macro and
;; cannot be intercepted by with-redefs).
;; ---------------------------------------------------------------------------

(defn- agent-atom
  "Return the agent-config atom (bypasses private via Var ref)."
  []
  @#'improv/agent-config)

(defn- set-agent! [v]
  (reset! (agent-atom) v))

(use-fixtures :each
  (fn [t]
    (set-agent! nil)
    (t)
    (set-agent! nil)))

;; ---------------------------------------------------------------------------
;; Helpers — scale and context builders
;; ---------------------------------------------------------------------------

(defn- c-major [] (scale-ns/scale :C 4 :major))
(defn- d-dorian [] (scale-ns/scale :D 3 :dorian))

(defn- ctx-with-key
  ([scale]    {:harmony/key scale :harmony/tension 0.3 :ensemble/density 0.5 :ensemble/register :mid})
  ([scale t]  {:harmony/key scale :harmony/tension t   :ensemble/density 0.5 :ensemble/register :mid}))

;; ---------------------------------------------------------------------------
;; degree-pool — note set selection by tension and register
;; ---------------------------------------------------------------------------

(deftest degree-pool-low-tension-returns-triad
  (testing "tension < 0.35 → root-triad pitches only (degrees 0 2 4)"
    (let [pool (#'improv/degree-pool (c-major) 0.2 :mid)]
      (is (seq pool))
      ;; C major triad from C4: C4=60 E4=64 G4=67
      (is (= #{60 64 67} (set pool))))))

(deftest degree-pool-mid-tension-expands
  (testing "0.35 ≤ tension < 0.65 → 5 diatonic degrees"
    (let [pool (#'improv/degree-pool (c-major) 0.5 :mid)]
      (is (= 5 (count pool))))))

(deftest degree-pool-high-tension-full-scale
  (testing "tension ≥ 0.65 → all scale degrees"
    (let [scale (c-major)
          n     (count (:intervals scale))
          pool  (#'improv/degree-pool scale 0.8 :mid)]
      (is (= n (count pool))))))

(deftest degree-pool-low-register-shifts-down
  (testing ":low register includes pitches one octave below root"
    (let [pool-mid (#'improv/degree-pool (c-major) 0.2 :mid)
          pool-low (#'improv/degree-pool (c-major) 0.2 :low)]
      (is (some #(< % (apply min pool-mid)) pool-low)))))

(deftest degree-pool-high-register-shifts-up
  (testing ":high register includes pitches one octave above root"
    (let [pool-mid  (#'improv/degree-pool (c-major) 0.2 :mid)
          pool-high (#'improv/degree-pool (c-major) 0.2 :high)]
      (is (some #(> % (apply max pool-mid)) pool-high)))))

(deftest degree-pool-all-valid-midi
  (testing "all returned MIDI values are in [0, 127]"
    (doseq [register [:low :mid :high]
            tension  [0.1 0.5 0.9]]
      (let [pool (#'improv/degree-pool (d-dorian) tension register)]
        (is (every? #(<= 0 % 127) pool)
            (str "out-of-range for register=" register " tension=" tension))))))

;; ---------------------------------------------------------------------------
;; pick-step — note event construction
;; ---------------------------------------------------------------------------

(deftest pick-step-nil-when-no-key
  (testing "pick-step returns nil when :harmony/key is absent"
    (is (nil? (#'improv/pick-step {} improv/default-profile)))))

(defn- pick-steps-n
  "Run pick-step n times with ctx and profile; return generated (non-nil) steps."
  [ctx profile n]
  (filter some? (repeatedly n #(#'improv/pick-step ctx profile))))

(deftest pick-step-valid-event-when-gated-on
  (testing "pick-step returns valid event maps when gate = 1.0"
    (let [ctx   (ctx-with-key (c-major))
          p     (assoc improv/default-profile :gate 1.0)
          steps (pick-steps-n ctx p 100)]
      (is (seq steps) "expected at least one note in 100 attempts")
      (doseq [step steps]
        (is (<= 0 (:pitch/midi step) 127))
        (is (<= 1 (:mod/velocity step) 127))
        (is (number? (:dur/beats step)))
        (is (integer? (:midi/channel step)))))))

(deftest pick-step-velocity-clamps
  (testing "velocity stays in [1, 127] even with extreme variance"
    (let [ctx   (ctx-with-key (c-major))
          p     (assoc improv/default-profile :gate 1.0 :velocity 120 :vel-variance 50)
          steps (pick-steps-n ctx p 100)]
      (is (seq steps))
      (doseq [step steps]
        (is (<= 1 (:mod/velocity step) 127))))))

(deftest pick-step-uses-channel
  (testing "pick-step propagates :channel from profile"
    (let [ctx   (ctx-with-key (c-major))
          p     (assoc improv/default-profile :gate 1.0 :channel 5)
          steps (pick-steps-n ctx p 100)]
      (is (seq steps))
      (is (every? #(= 5 (:midi/channel %)) steps)))))

(deftest pick-step-uses-dur-beats
  (testing "pick-step propagates :dur-beats from profile"
    (let [ctx   (ctx-with-key (c-major))
          p     (assoc improv/default-profile :gate 1.0 :dur-beats 1/2)
          steps (pick-steps-n ctx p 100)]
      (is (seq steps))
      (is (every? #(= 1/2 (:dur/beats %)) steps)))))

;; ---------------------------------------------------------------------------
;; apply-texture-routing!
;; ---------------------------------------------------------------------------

(deftest texture-routing-dispatches-transition
  (testing "apply-texture-routing! calls texture-transition! with built ops"
    (let [received (atom nil)
          ctx      (ctx-with-key (c-major) 0.9)
          routing  {:reverb {:params-fn (fn [c]
                                          {:hold? (> (:harmony/tension c 0) 0.8)})
                              :beats 4}}]
      (with-redefs [tx/texture-transition! (fn [ops] (reset! received ops) nil)]
        (#'improv/apply-texture-routing! ctx routing))
      (is (some? @received))
      (is (true?  (get-in @received [:reverb :target :hold?])))
      (is (= 4    (get-in @received [:reverb :beats]))))))

(deftest texture-routing-skips-nil-params
  (testing "apply-texture-routing! skips entries where params-fn returns nil"
    (let [received (atom ::not-called)
          ctx      {}
          routing  {:reverb {:params-fn (constantly nil)}}]
      (with-redefs [tx/texture-transition! (fn [ops] (reset! received ops) nil)]
        (#'improv/apply-texture-routing! ctx routing))
      ;; Either not called or called with empty ops — nil received means empty ops
      (is (or (= ::not-called @received) (empty? @received))))))

(deftest texture-routing-swallows-params-exception
  (testing "apply-texture-routing! does not propagate params-fn exceptions"
    (is (nil?
          (with-redefs [tx/texture-transition! (constantly nil)]
            (#'improv/apply-texture-routing!
              (ctx-with-key (c-major))
              {:bad {:params-fn (fn [_] (throw (ex-info "boom" {})))}}))))))

(deftest texture-routing-no-beats-omits-key
  (testing "routing spec without :beats produces no :beats in ops"
    (let [received (atom nil)
          ctx      (ctx-with-key (c-major))
          routing  {:delay {:params-fn (constantly {:active-zone :z4})}}]
      (with-redefs [tx/texture-transition! (fn [ops] (reset! received ops) nil)]
        (#'improv/apply-texture-routing! ctx routing))
      (is (= {:active-zone :z4} (get-in @received [:delay :target])))
      (is (not (contains? (get @received :delay) :beats))))))

;; ---------------------------------------------------------------------------
;; Agent config lifecycle — direct atom manipulation (no live system needed)
;; ---------------------------------------------------------------------------

(deftest improv-state-nil-when-not-running
  (testing "improv-state returns nil when no agent is configured"
    (is (nil? (improv/improv-state)))))

(deftest set-agent-state-round-trips
  (testing "directly setting the atom is reflected by improv-state"
    (set-agent! {:buf-name :test :profile {:gate 0.7} :status :running})
    (let [s (improv/improv-state)]
      (is (= :test (:buf-name s)))
      (is (= :running (:status s))))))

(deftest update-improv-merges-profile
  (testing "update-improv! merges profile overrides into existing config"
    (set-agent! {:buf-name :main
                 :profile  (merge improv/default-profile {:gate 0.6})
                 :routing  {}
                 :status   :running})
    (improv/update-improv! {:profile {:gate 0.95 :channel 2}})
    (let [p (:profile (improv/improv-state))]
      (is (= 0.95 (:gate p)))
      (is (= 2    (:channel p)))
      (is (= 1/4  (:step-beats p))))))  ; default preserved

(deftest update-improv-replaces-routing
  (testing "update-improv! replaces routing map entirely"
    (let [old {:reverb {:params-fn identity}}
          new {:delay  {:params-fn constantly}}]
      (set-agent! {:buf-name :main :profile improv/default-profile
                   :routing old :status :running})
      (improv/update-improv! {:routing new})
      (is (= new (:routing (improv/improv-state)))))))

(deftest update-improv-throws-when-not-running
  (testing "update-improv! throws when no agent is configured"
    (is (thrown? clojure.lang.ExceptionInfo
                 (improv/update-improv! {:profile {:gate 0.5}})))))

;; ---------------------------------------------------------------------------
;; improv-gesture-fn — conductor integration
;; ---------------------------------------------------------------------------

(deftest improv-gesture-fn-returns-fn
  (testing "improv-gesture-fn returns a callable"
    (is (fn? (improv/improv-gesture-fn {})))))

(deftest improv-gesture-fn-updates-profile
  (testing "calling gesture-fn merges :profile into agent config"
    (set-agent! {:buf-name :main
                 :profile  (merge improv/default-profile {:gate 0.5})
                 :routing  {} :status :running})
    (let [f (improv/improv-gesture-fn {:profile {:gate 0.95}})]
      (f)
      (is (= 0.95 (:gate (:profile (improv/improv-state))))))))

(deftest improv-gesture-fn-fires-texture
  (testing "calling gesture-fn fires :texture ops via texture-transition!"
    (let [received (atom nil)]
      (set-agent! {:buf-name :main :profile improv/default-profile
                   :routing {} :status :running})
      (with-redefs [tx/texture-transition! (fn [ops] (reset! received ops) nil)]
        (let [tex {:reverb {:target {:hold? true}}}
              f   (improv/improv-gesture-fn {:texture tex})]
          (f)
          (is (= tex @received)))))))

(deftest improv-gesture-fn-noop-when-not-running
  (testing "calling gesture-fn when agent is not running is a silent no-op"
    (let [f (improv/improv-gesture-fn {:profile {:gate 0.9}})]
      (is (nil? (f))))))

(deftest default-profile-has-required-keys
  (testing "default-profile contains all required note-generation keys"
    (doseq [k [:step-beats :gate :dur-beats :velocity :vel-variance :channel]]
      (is (contains? improv/default-profile k)
          (str "missing key: " k)))))
