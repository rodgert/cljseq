; SPDX-License-Identifier: EPL-2.0
(ns cljseq.conductor-test
  "Unit tests for cljseq.conductor — section sequencing, gesture resolution,
  fire!/abort!/state lifecycle."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [cljseq.conductor :as conductor]
            [cljseq.core      :as core]
            [cljseq.ctrl      :as ctrl]
            [cljseq.mod       :as mod]))

;; ---------------------------------------------------------------------------
;; Fixture — run at 60000 BPM so sleep! durations are in microseconds
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 60000)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; defconductor! — registration
;; ---------------------------------------------------------------------------

(deftest defconductor-registers-test
  (testing "defconductor! returns the name keyword"
    (is (= :test-reg
           (conductor/defconductor! :test-reg
             [{:name :a :bars 0 :gesture nil}]))))
  (testing "conductor-names includes registered conductor"
    (conductor/defconductor! :test-names [{:bars 0}])
    (is (some #{:test-names} (conductor/conductor-names)))))

(deftest defconductor-initial-state-test
  (testing "freshly registered conductor has :idle status"
    (conductor/defconductor! :test-idle [{:bars 0}])
    (is (= :idle (:status (conductor/conductor-state :test-idle))))))

(deftest defconductor-replaces-sections-test
  (testing "re-registering replaces sections; running instance is unaffected"
    (conductor/defconductor! :test-replace [{:name :old :bars 0}])
    (conductor/defconductor! :test-replace [{:name :new :bars 0}])
    ;; Sections are replaced; status is preserved
    (is (= :idle (:status (conductor/conductor-state :test-replace))))))

;; ---------------------------------------------------------------------------
;; fire! — lifecycle
;; ---------------------------------------------------------------------------

(deftest fire-unknown-conductor-throws-test
  (testing "fire! on unknown conductor throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (conductor/fire! :no-such-conductor-xyz)))))

(deftest fire-transitions-to-running-test
  (testing "fire! transitions status to :running"
    (conductor/defconductor! :test-running [{:bars 1000}])
    (conductor/fire! :test-running)
    (Thread/sleep 20)
    (is (= :running (:status (conductor/conductor-state :test-running))))
    (conductor/abort! :test-running)
    (Thread/sleep 20)))

(deftest fire-already-running-throws-test
  (testing "fire! on a running conductor throws"
    (conductor/defconductor! :test-double-fire [{:bars 1000}])
    (conductor/fire! :test-double-fire)
    (Thread/sleep 20)
    (is (thrown? clojure.lang.ExceptionInfo
                 (conductor/fire! :test-double-fire)))
    (conductor/abort! :test-double-fire)
    (Thread/sleep 20)))

(deftest fire-done-after-completion-test
  (testing "status is :done after all sections complete"
    (conductor/defconductor! :test-done
      [{:name :a :bars 0}
       {:name :b :bars 0}])
    (conductor/fire! :test-done)
    (Thread/sleep 100)
    (is (= :done (:status (conductor/conductor-state :test-done))))))

;; ---------------------------------------------------------------------------
;; abort! — lifecycle
;; ---------------------------------------------------------------------------

(deftest abort-transitions-to-aborted-test
  (testing "abort! sets status to :aborted"
    (conductor/defconductor! :test-abort [{:bars 1000}])
    (conductor/fire! :test-abort)
    (Thread/sleep 20)
    (conductor/abort! :test-abort)
    (Thread/sleep 30)
    (is (= :aborted (:status (conductor/conductor-state :test-abort))))))

(deftest abort-stops-section-execution-test
  (testing "abort! prevents remaining sections from running"
    (let [counter (atom 0)]
      (conductor/defconductor! :test-abort-stop
        [{:bars 0 :on-start (fn [] (swap! counter inc))}
         {:bars 1000}
         {:bars 0 :on-start (fn [] (swap! counter inc))}
         {:bars 0 :on-start (fn [] (swap! counter inc))}])
      (conductor/fire! :test-abort-stop)
      (Thread/sleep 30)
      (conductor/abort! :test-abort-stop)
      (Thread/sleep 30)
      ;; Only the first section ran before the long sleep was aborted
      (is (= 1 @counter) "only first section's on-start ran"))))

(deftest abort-noop-on-idle-test
  (testing "abort! on an idle conductor is a no-op"
    (conductor/defconductor! :test-abort-idle [{:bars 0}])
    (is (nil? (conductor/abort! :test-abort-idle)))))

;; ---------------------------------------------------------------------------
;; Section execution — on-start / on-end callbacks
;; ---------------------------------------------------------------------------

(deftest on-start-callback-fires-test
  (testing ":on-start fires before sleep"
    (let [p (promise)]
      (conductor/defconductor! :test-on-start
        [{:bars 0 :on-start (fn [] (deliver p :fired))}])
      (conductor/fire! :test-on-start)
      (is (= :fired (deref p 500 :timeout))))))

(deftest on-end-callback-fires-test
  (testing ":on-end fires after section sleep"
    (let [p (promise)]
      (conductor/defconductor! :test-on-end
        [{:bars 0 :on-end (fn [] (deliver p :ended))}])
      (conductor/fire! :test-on-end)
      (is (= :ended (deref p 500 :timeout))))))

(deftest sections-execute-in-order-test
  (testing "sections execute in order"
    (let [order (atom [])]
      (conductor/defconductor! :test-order
        [{:name :a :bars 0 :on-start (fn [] (swap! order conj :a))}
         {:name :b :bars 0 :on-start (fn [] (swap! order conj :b))}
         {:name :c :bars 0 :on-start (fn [] (swap! order conj :c))}])
      (conductor/fire! :test-order)
      (Thread/sleep 100)
      (is (= [:a :b :c] @order)))))

(deftest on-start-exception-does-not-stop-conductor-test
  (testing "exception in :on-start is swallowed; conductor continues"
    (let [p (promise)]
      (conductor/defconductor! :test-cb-error
        [{:bars 0 :on-start (fn [] (throw (ex-info "deliberate" {})))}
         {:bars 0 :on-start (fn [] (deliver p :continued))}])
      (conductor/fire! :test-cb-error)
      (is (= :continued (deref p 500 :timeout))
          "second section ran despite first on-start throwing"))))

;; ---------------------------------------------------------------------------
;; conductor-state — section name tracking
;; ---------------------------------------------------------------------------

(deftest conductor-state-tracks-section-test
  (testing "conductor-state :section reflects the running section"
    (let [p (promise)]
      (conductor/defconductor! :test-section-track
        [{:name :long-section :bars 1000
          :on-start (fn [] (deliver p :started))}])
      (conductor/fire! :test-section-track)
      (deref p 500 :timeout)
      (is (= :long-section (:section (conductor/conductor-state :test-section-track))))
      (conductor/abort! :test-section-track)
      (Thread/sleep 20))))

(deftest conductor-state-nil-when-not-registered-test
  (testing "conductor-state returns nil for unknown conductor"
    (is (nil? (conductor/conductor-state :definitely-not-registered-xyz)))))

(deftest conductor-state-section-nil-after-done-test
  (testing ":section is nil after conductor completes"
    (conductor/defconductor! :test-section-done [{:bars 0}])
    (conductor/fire! :test-section-done)
    (Thread/sleep 100)
    (is (nil? (:section (conductor/conductor-state :test-section-done))))))

;; ---------------------------------------------------------------------------
;; Gesture resolution — custom fn and map
;; ---------------------------------------------------------------------------

(deftest custom-fn-gesture-test
  (testing "fn gesture is called with section map and start beat"
    (let [received (atom nil)]
      (conductor/defconductor! :test-fn-gesture
        [{:bars 0
          :gesture (fn [section start]
                     (reset! received {:section section :start start})
                     {})}])
      (conductor/fire! :test-fn-gesture)
      (Thread/sleep 100)
      (is (some? @received) "fn was called")
      (is (some? (:start @received)) "start beat was provided"))))

(deftest map-gesture-routes-trajectories-test
  (testing "map gesture routes each entry via mod-route!"
    (let [routed (atom #{})]
      (with-redefs [cljseq.mod/mod-route! (fn [path _tr] (swap! routed conj path) path)]
        (conductor/defconductor! :test-map-gesture
          [{:bars 0
            :gesture {[:arc/tension] :fake-traj
                      [:arc/density] :fake-traj}}])
        (conductor/fire! :test-map-gesture)
        (Thread/sleep 100))
      (is (contains? @routed [:arc/tension]))
      (is (contains? @routed [:arc/density])))))

(deftest nil-gesture-routes-nothing-test
  (testing "nil gesture routes no trajectories"
    (let [route-count (atom 0)]
      (with-redefs [cljseq.mod/mod-route! (fn [_ _] (swap! route-count inc))]
        (conductor/defconductor! :test-nil-gesture
          [{:bars 0 :gesture nil}])
        (conductor/fire! :test-nil-gesture)
        (Thread/sleep 100))
      (is (= 0 @route-count)))))

(deftest unknown-gesture-keyword-throws-test
  (testing "unknown gesture keyword causes :error status"
    (conductor/defconductor! :test-bad-gesture
      [{:bars 0 :gesture :not-a-real-gesture}])
    (conductor/fire! :test-bad-gesture)
    (Thread/sleep 100)
    (is (= :error (:status (conductor/conductor-state :test-bad-gesture))))))

;; ---------------------------------------------------------------------------
;; Built-in gesture keywords — smoke test (routes to arc paths)
;; ---------------------------------------------------------------------------

(deftest builtin-buildup-routes-arc-paths-test
  (testing ":buildup gesture routes to arc paths"
    (let [routed (atom #{})]
      (with-redefs [cljseq.mod/mod-route! (fn [path _tr] (swap! routed conj path) path)]
        (conductor/defconductor! :test-buildup-smoke
          [{:bars 0 :gesture :buildup}])
        (conductor/fire! :test-buildup-smoke)
        (Thread/sleep 100))
      (is (contains? @routed [:arc/tension]))
      (is (contains? @routed [:arc/density])))))

(deftest builtin-drop-routes-arc-paths-test
  (testing ":drop gesture routes tension/density/momentum"
    (let [routed (atom #{})]
      (with-redefs [cljseq.mod/mod-route! (fn [path _tr] (swap! routed conj path) path)]
        (conductor/defconductor! :test-drop-smoke
          [{:bars 0 :gesture :drop}])
        (conductor/fire! :test-drop-smoke)
        (Thread/sleep 100))
      (is (contains? @routed [:arc/tension]))
      (is (contains? @routed [:arc/density]))
      (is (contains? @routed [:arc/momentum])))))

;; ---------------------------------------------------------------------------
;; :repeat — section repetition
;; ---------------------------------------------------------------------------

(deftest repeat-section-runs-n-times-test
  (testing ":repeat N causes the section to execute N times"
    (let [counter (atom 0)]
      (conductor/defconductor! :test-repeat-3
        [{:bars 0 :repeat 3 :on-start (fn [] (swap! counter inc))}])
      (conductor/fire! :test-repeat-3)
      (Thread/sleep 150)
      (is (= 3 @counter) "on-start fires exactly 3 times"))))

(deftest repeat-1-is-same-as-no-repeat-test
  (testing ":repeat 1 is equivalent to omitting :repeat"
    (let [c1 (atom 0) c2 (atom 0)]
      (conductor/defconductor! :test-repeat-1-explicit
        [{:bars 0 :repeat 1 :on-start (fn [] (swap! c1 inc))}])
      (conductor/defconductor! :test-repeat-1-default
        [{:bars 0 :on-start (fn [] (swap! c2 inc))}])
      (conductor/fire! :test-repeat-1-explicit)
      (conductor/fire! :test-repeat-1-default)
      (Thread/sleep 150)
      (is (= 1 @c1))
      (is (= 1 @c2)))))

(deftest repeat-abort-stops-mid-repetition-test
  (testing "abort! during a :repeat section halts after the current iteration"
    (let [counter (atom 0)
          started (promise)]
      (conductor/defconductor! :test-repeat-abort
        [{:bars 1000 :repeat 10
          :on-start (fn []
                      (swap! counter inc)
                      (deliver started :ok))}])
      (conductor/fire! :test-repeat-abort)
      (deref started 500 :timeout)
      (conductor/abort! :test-repeat-abort)
      (Thread/sleep 50)
      ;; Only 1 repetition started before abort
      (is (= 1 @counter)
          "abort halts before second repetition begins"))))

(deftest repeat-multiple-sections-test
  (testing ":repeat applies independently per section"
    (let [order (atom [])]
      (conductor/defconductor! :test-repeat-multi
        [{:name :a :bars 0 :repeat 2 :on-start (fn [] (swap! order conj :a))}
         {:name :b :bars 0 :repeat 3 :on-start (fn [] (swap! order conj :b))}])
      (conductor/fire! :test-repeat-multi)
      (Thread/sleep 200)
      (is (= [:a :a :b :b :b] @order)))))

;; ---------------------------------------------------------------------------
;; Built-in gesture keywords — swell and tension-peak
;; ---------------------------------------------------------------------------

(deftest builtin-swell-routes-arc-paths-test
  (testing ":swell gesture routes tension, density, register"
    (let [routed (atom #{})]
      (with-redefs [cljseq.mod/mod-route! (fn [path _tr] (swap! routed conj path) path)]
        (conductor/defconductor! :test-swell-smoke
          [{:bars 0 :gesture :swell}])
        (conductor/fire! :test-swell-smoke)
        (Thread/sleep 100))
      (is (contains? @routed [:arc/tension]))
      (is (contains? @routed [:arc/density]))
      (is (contains? @routed [:arc/register])))))

(deftest builtin-tension-peak-routes-arc-paths-test
  (testing ":tension-peak gesture routes tension, density, momentum"
    (let [routed (atom #{})]
      (with-redefs [cljseq.mod/mod-route! (fn [path _tr] (swap! routed conj path) path)]
        (conductor/defconductor! :test-tension-peak-smoke
          [{:bars 0 :gesture :tension-peak}])
        (conductor/fire! :test-tension-peak-smoke)
        (Thread/sleep 100))
      (is (contains? @routed [:arc/tension]))
      (is (contains? @routed [:arc/density]))
      (is (contains? @routed [:arc/momentum])))))

;; ---------------------------------------------------------------------------
;; stop-all-conductors!
;; ---------------------------------------------------------------------------

(deftest stop-all-conductors-test
  (testing "stop-all-conductors! aborts all running conductors"
    (conductor/defconductor! :test-stop-all-a [{:bars 1000}])
    (conductor/defconductor! :test-stop-all-b [{:bars 1000}])
    (conductor/fire! :test-stop-all-a)
    (conductor/fire! :test-stop-all-b)
    (Thread/sleep 20)
    (conductor/stop-all-conductors!)
    (Thread/sleep 30)
    (is (= :aborted (:status (conductor/conductor-state :test-stop-all-a))))
    (is (= :aborted (:status (conductor/conductor-state :test-stop-all-b))))))

;; ---------------------------------------------------------------------------
;; :when predicate — conditional section skip
;; ---------------------------------------------------------------------------

(deftest when-predicate-skips-section-test
  (testing ":when false skips entire section (no on-start, no sleep)"
    (let [counter (atom 0)]
      (conductor/defconductor! :test-when-skip
        [{:bars 0 :when (constantly false) :on-start (fn [] (swap! counter inc))}
         {:bars 0 :on-start (fn [] (swap! counter inc))}])
      (conductor/fire! :test-when-skip)
      (Thread/sleep 100)
      (is (= 1 @counter) "only the second section ran"))))

(deftest when-predicate-runs-section-test
  (testing ":when true runs the section normally"
    (let [counter (atom 0)]
      (conductor/defconductor! :test-when-run
        [{:bars 0 :when (constantly true) :on-start (fn [] (swap! counter inc))}])
      (conductor/fire! :test-when-run)
      (Thread/sleep 100)
      (is (= 1 @counter)))))

(deftest when-predicate-evaluated-per-repetition-test
  (testing ":when is evaluated on each repetition — can flip mid-repeat"
    (let [counter  (atom 0)
          allow?   (atom false)]
      ;; Start disallowed; flip to allowed after first check
      (conductor/defconductor! :test-when-flip
        [{:bars 0 :repeat 3
          :when  (fn [] (let [v @allow?] (reset! allow? true) v))
          :on-start (fn [] (swap! counter inc))}])
      (conductor/fire! :test-when-flip)
      (Thread/sleep 150)
      ;; First rep: false (skipped). Second: true. Third: true. Counter = 2.
      (is (= 2 @counter) "skipped first rep, ran second and third"))))

(deftest when-exception-runs-section-test
  (testing ":when exception is treated as true (run section, log error)"
    (let [counter (atom 0)]
      (conductor/defconductor! :test-when-exception
        [{:bars 0
          :when (fn [] (throw (ex-info "deliberate" {})))
          :on-start (fn [] (swap! counter inc))}])
      (conductor/fire! :test-when-exception)
      (Thread/sleep 100)
      (is (= 1 @counter) "section ran despite :when throwing"))))

;; ---------------------------------------------------------------------------
;; :on-transition — conductor-level transition callback
;; ---------------------------------------------------------------------------

(deftest on-transition-fires-between-sections-test
  (testing ":on-transition is called before each section with from/to names"
    (let [transitions (atom [])]
      (conductor/defconductor! :test-transition
        [{:name :a :bars 0}
         {:name :b :bars 0}
         {:name :c :bars 0}]
        {:on-transition (fn [from to] (swap! transitions conj [from to]))})
      (conductor/fire! :test-transition)
      (Thread/sleep 150)
      ;; Three sections → three on-transition calls
      ;; First: [nil :a], then [:a :b], then [:b :c]
      (is (= 3 (count @transitions)) "one call per section")
      (is (= [nil :a] (first @transitions)) "first transition has nil from")
      (is (= [:a :b] (second @transitions)))
      (is (= [:b :c] (nth @transitions 2))))))

(deftest on-transition-fires-with-nil-names-test
  (testing ":on-transition handles sections without :name (nil to)"
    (let [transitions (atom [])]
      (conductor/defconductor! :test-transition-nil-names
        [{:bars 0}
         {:bars 0}]
        {:on-transition (fn [from to] (swap! transitions conj [from to]))})
      (conductor/fire! :test-transition-nil-names)
      (Thread/sleep 100)
      (is (= 2 (count @transitions)))
      (is (= [[nil nil] [nil nil]] @transitions)))))

(deftest on-transition-exception-does-not-stop-conductor-test
  (testing ":on-transition exception is swallowed; conductor continues"
    (let [counter (atom 0)]
      (conductor/defconductor! :test-transition-error
        [{:bars 0 :on-start (fn [] (swap! counter inc))}
         {:bars 0 :on-start (fn [] (swap! counter inc))}]
        {:on-transition (fn [_ _] (throw (ex-info "deliberate" {})))})
      (conductor/fire! :test-transition-error)
      (Thread/sleep 100)
      (is (= 2 @counter) "both sections ran despite on-transition throwing"))))

(deftest defconductor-without-opts-has-no-on-transition-test
  (testing "defconductor! with no opts runs normally without on-transition"
    (let [counter (atom 0)]
      (conductor/defconductor! :test-no-opts
        [{:bars 0 :on-start (fn [] (swap! counter inc))}
         {:bars 0 :on-start (fn [] (swap! counter inc))}])
      (conductor/fire! :test-no-opts)
      (Thread/sleep 100)
      (is (= 2 @counter)))))

;; ---------------------------------------------------------------------------
;; cue! — jump to named section at next boundary
;; ---------------------------------------------------------------------------

(deftest cue-jumps-to-named-section-test
  (testing "cue! causes the runner to jump to the named section at the next boundary"
    ;; :a has a 5-bar sleep (20ms at 60000 BPM). We set the cue after :a enters
    ;; its sleep but before it finishes. When :a's sleep expires, the runner
    ;; checks the cue, finds :c, and jumps there — skipping :b.
    (let [a-started (promise)
          order     (atom [])
          reached   (promise)]
      (conductor/defconductor! :test-cue
        [{:name :a :bars 5
          :on-start (fn [] (swap! order conj :a) (deliver a-started :ok))}
         {:name :b :bars 0
          :on-start (fn [] (swap! order conj :b))}
         {:name :c :bars 0
          :on-start (fn [] (swap! order conj :c) (deliver reached :ok))}])
      (conductor/fire! :test-cue)
      (deref a-started 500 :timeout)   ; wait until :a has entered its sleep
      (conductor/cue! :test-cue :c)    ; set cue while :a is sleeping
      (is (= :ok (deref reached 500 :timeout)) "section :c should run")
      (Thread/sleep 30)
      (is (= :a (first @order))         "section :a ran first")
      (is (some #{:c} @order)           "section :c ran after cue")
      (is (not (some #{:b} @order))     "section :b was skipped by cue"))))

(deftest cue-noop-when-not-running-test
  (testing "cue! on an idle conductor is a no-op"
    (conductor/defconductor! :test-cue-idle [{:name :a :bars 0}])
    (is (nil? (conductor/cue! :test-cue-idle :a)))))

(deftest cue-unknown-target-continues-in-order-test
  (testing "cue! with unknown target logs warning and continues in order"
    ;; :a has a 5-bar sleep so we can set the cue while it's sleeping,
    ;; then both :a and :b run in order (invalid cue is ignored).
    (let [a-started (promise)
          b-done    (promise)
          order     (atom [])]
      (conductor/defconductor! :test-cue-unknown
        [{:name :a :bars 5
          :on-start (fn [] (swap! order conj :a) (deliver a-started :ok))}
         {:name :b :bars 0
          :on-start (fn [] (swap! order conj :b) (deliver b-done :ok))}])
      (conductor/fire! :test-cue-unknown)
      (deref a-started 500 :timeout)
      (conductor/cue! :test-cue-unknown :no-such-section)
      (deref b-done 500 :timeout)
      (Thread/sleep 20)
      (is (= [:a :b] @order) "conductor ran in order after invalid cue"))))
