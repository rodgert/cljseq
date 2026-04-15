; SPDX-License-Identifier: EPL-2.0
(ns cljseq.supervisor-test
  "Tests for cljseq.supervisor — event bus, service registry, watchdog,
  loop pause/resume integration."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [cljseq.core       :as core]
            [cljseq.loop       :as loop-ns]
            [cljseq.supervisor :as supervisor]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 60000)
  (try (f) (finally (core/stop!))))

(defn with-clean-supervisor [f]
  ;; Reset supervisor state between tests
  (reset! @#'supervisor/services {})
  (reset! @#'supervisor/service-state {})
  (reset! @#'supervisor/event-handlers {})
  (when (supervisor/running?) (supervisor/stop-watchdog!))
  (try (f)
       (finally
         (when (supervisor/running?) (supervisor/stop-watchdog!))
         (reset! @#'supervisor/services {})
         (reset! @#'supervisor/service-state {})
         (reset! @#'supervisor/event-handlers {}))))

(use-fixtures :each with-system with-clean-supervisor)

;; ---------------------------------------------------------------------------
;; Service registry
;; ---------------------------------------------------------------------------

(deftest register-returns-service-name-test
  (testing "register! returns the service name"
    (is (= :my-svc (supervisor/register! :my-svc :check-fn (constantly true))))))

(deftest initial-status-is-unknown-test
  (testing "newly registered service has :unknown status"
    (supervisor/register! :svc-a :check-fn (constantly true))
    (is (= :unknown (supervisor/service-status :svc-a)))))

(deftest deregister-removes-service-test
  (testing "deregister! removes the service"
    (supervisor/register! :svc-b :check-fn (constantly true))
    (supervisor/deregister! :svc-b)
    (is (= :unknown (supervisor/service-status :svc-b)))))

(deftest all-statuses-returns-all-test
  (testing "all-statuses returns a map with all registered services"
    (supervisor/register! :svc-c :check-fn (constantly true))
    (supervisor/register! :svc-d :check-fn (constantly false))
    (let [s (supervisor/all-statuses)]
      (is (contains? s :svc-c))
      (is (contains? s :svc-d)))))

;; ---------------------------------------------------------------------------
;; any-down?
;; ---------------------------------------------------------------------------

(deftest any-down-unknown-is-not-down-test
  (testing "any-down? returns nil when services are :unknown (not yet checked)"
    (supervisor/register! :svc-e :check-fn (constantly true))
    (is (nil? (supervisor/any-down? [:svc-e])))))

(deftest any-down-detects-down-service-test
  (testing "any-down? returns truthy when a service is :down"
    (supervisor/register! :svc-f :check-fn (constantly false))
    (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
    (Thread/sleep 150)  ; allow at least one check
    (supervisor/stop-watchdog!)
    (is (supervisor/any-down? [:svc-f]))))

(deftest any-down-false-when-all-up-test
  (testing "any-down? returns nil when all services are :up"
    (supervisor/register! :svc-g :check-fn (constantly true))
    (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
    (Thread/sleep 150)
    (supervisor/stop-watchdog!)
    (is (nil? (supervisor/any-down? [:svc-g])))))

;; ---------------------------------------------------------------------------
;; Event bus
;; ---------------------------------------------------------------------------

(deftest on-event-registers-and-fires-test
  (testing "on-event! registers a handler that fires when emit! is called"
    (let [received (atom nil)]
      (supervisor/on-event! :test-svc :down ::h1
                            (fn [payload] (reset! received payload)))
      (@#'supervisor/emit! :test-svc :down {:foo :bar})
      (is (= {:foo :bar} @received)))))

(deftest off-event-removes-handler-test
  (testing "off-event! prevents the handler from firing"
    (let [called (atom false)]
      (supervisor/on-event! :test-svc :up ::h2
                            (fn [_] (reset! called true)))
      (supervisor/off-event! :test-svc :up ::h2)
      (@#'supervisor/emit! :test-svc :up {})
      (is (false? @called)))))

(deftest handler-exception-does-not-propagate-test
  (testing "a handler that throws does not prevent other handlers from firing"
    (let [second-called (atom false)]
      (supervisor/on-event! :test-svc :down ::throw-h
                            (fn [_] (throw (ex-info "handler boom" {}))))
      (supervisor/on-event! :test-svc :down ::ok-h
                            (fn [_] (reset! second-called true)))
      (@#'supervisor/emit! :test-svc :down {})
      (is (true? @second-called)
          "second handler fired despite first one throwing"))))

;; ---------------------------------------------------------------------------
;; Watchdog: service status transitions
;; ---------------------------------------------------------------------------

(deftest watchdog-transitions-up-to-down-test
  (testing "watchdog emits :down when a healthy service becomes unhealthy"
    (let [healthy?  (atom true)
          events    (atom [])]
      (supervisor/register! :flaky :check-fn #(deref healthy?))
      (supervisor/on-event! :flaky :down ::track
                            (fn [p] (swap! events conj p)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (Thread/sleep 100)     ; let the service come up
      (reset! healthy? false)
      (Thread/sleep 200)     ; wait for :down transition
      (supervisor/stop-watchdog!)
      (is (seq @events) "at least one :down event emitted")
      (is (every? #(= :flaky (:service %)) @events)))))

(deftest watchdog-emits-up-on-recovery-test
  (testing "watchdog emits :up when a down service recovers"
    (let [healthy? (atom false)
          up-events (atom [])]
      (supervisor/register! :recoverable :check-fn #(deref healthy?))
      (supervisor/on-event! :recoverable :up ::track
                            (fn [p] (swap! up-events conj p)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (Thread/sleep 100)      ; service starts :down
      (reset! healthy? true)  ; service recovers
      (Thread/sleep 200)      ; wait for :up transition
      (supervisor/stop-watchdog!)
      (is (seq @up-events) ":up event emitted on recovery")
      (is (= :up (supervisor/service-status :recoverable))))))

(deftest watchdog-calls-restart-fn-on-down-test
  (testing "watchdog calls restart-fn when service goes down"
    (let [healthy?    (atom true)
          restarted?  (atom false)]
      (supervisor/register! :restartable
                            :check-fn    #(deref healthy?)
                            :restart-fn  #(reset! restarted? true))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (Thread/sleep 100)
      (reset! healthy? false)
      (Thread/sleep 200)
      (supervisor/stop-watchdog!)
      (is (true? @restarted?) "restart-fn was called"))))

(deftest watchdog-calls-restore-fn-on-recovery-test
  (testing "watchdog calls restore-fn after successful recovery"
    (let [healthy?   (atom false)
          restored?  (atom false)]
      (supervisor/register! :restorable
                            :check-fn    #(deref healthy?)
                            :restore-fn  #(reset! restored? true))
      (supervisor/on-event! :restorable :up ::flip
                            (fn [_] (reset! healthy? true)))  ; stay up once up
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (Thread/sleep 50)
      (reset! healthy? true)   ; service recovers
      (Thread/sleep 200)
      (supervisor/stop-watchdog!)
      (is (true? @restored?) "restore-fn was called after recovery"))))

(deftest watchdog-does-not-re-emit-down-test
  (testing "watchdog emits :down only once per outage, not every tick"
    (let [down-count (atom 0)]
      (supervisor/register! :once-down :check-fn (constantly false))
      (supervisor/on-event! :once-down :down ::count
                            (fn [_] (swap! down-count inc)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (Thread/sleep 300)   ; multiple ticks while :down
      (supervisor/stop-watchdog!)
      (is (= 1 @down-count) ":down emitted exactly once"))))

;; ---------------------------------------------------------------------------
;; Loop pause / resume integration
;; ---------------------------------------------------------------------------

(deftest pause-on-down-pauses-loop-body-test
  (testing ":pause-on-down pauses loop body while service is :down"
    (let [body-ran  (atom false)]
      (supervisor/register! :test-gate :check-fn (constantly false))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (Thread/sleep 150)  ; force service into :down state
      ;; Now start a loop that should NOT execute its body
      (loop-ns/deflive-loop :test-paused {:pause-on-down [:test-gate]}
        (reset! body-ran true)
        (loop-ns/sleep! 1000))
      (Thread/sleep 100)
      (is (false? @body-ran) "loop body did not run while service was :down")
      (loop-ns/stop-loop! :test-paused)
      (supervisor/stop-watchdog!))))

(deftest pause-on-down-resumes-when-service-recovers-test
  (testing ":pause-on-down loop resumes when service comes back :up"
    (let [healthy?  (atom false)
          body-ran  (promise)]
      (supervisor/register! :test-gate2 :check-fn #(deref healthy?))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (Thread/sleep 150)  ; service starts :down
      (loop-ns/deflive-loop :test-resume {:pause-on-down [:test-gate2]}
        (deliver body-ran :ran)
        (loop-ns/sleep! 1000))
      (Thread/sleep 50)   ; confirm body hasn't run
      (reset! healthy? true)   ; service recovers
      (let [result (deref body-ran 1000 :timeout)]
        (loop-ns/stop-loop! :test-resume)
        (supervisor/stop-watchdog!)
        (is (= :ran result) "loop body ran after service recovered")))))

;; ---------------------------------------------------------------------------
;; restart-loop!
;; ---------------------------------------------------------------------------

(deftest restart-loop-restarts-dead-thread-test
  (testing "restart-loop! starts a fresh thread for a dead loop"
    (let [v        (atom :initial)
          dead-t   (doto (Thread. (fn [])) (.start))
          running? (atom true)
          sref     @(loop-ns/-system-ref)]
      (.join dead-t 200)
      ;; Inject a stale entry (dead thread, running?=true)
      (swap! sref assoc-in [:loops :test-rl]
             {:fn         (fn [] (reset! v :restarted) (loop-ns/sleep! 1000))
              :tick-count 0
              :running?   running?
              :sleep-interrupted? (atom false)
              :thread     dead-t})
      (loop-ns/restart-loop! :test-rl)
      (Thread/sleep 100)
      (is (= :restarted @v) "restarted loop body ran")
      (loop-ns/stop-loop! :test-rl))))

(deftest restart-loop-returns-nil-for-live-loop-test
  (testing "restart-loop! returns nil and leaves a live loop untouched"
    (let [v (atom :original)]
      (loop-ns/deflive-loop :test-rl-live {}
        (reset! v :live)
        (loop-ns/sleep! 1000))
      (Thread/sleep 30)
      (is (nil? (loop-ns/restart-loop! :test-rl-live))
          "restart-loop! returns nil for a live loop")
      (loop-ns/stop-loop! :test-rl-live))))

;; ---------------------------------------------------------------------------
;; Watchdog: loop monitoring
;; ---------------------------------------------------------------------------

(deftest watchdog-emits-down-for-dead-loop-test
  (testing "watchdog emits :loops/:down when a loop thread dies"
    (let [dead-events (atom [])
          dead-t      (doto (Thread. (fn [])) (.start))
          running?    (atom true)
          sref        @(loop-ns/-system-ref)]
      (.join dead-t 200)
      (swap! sref assoc-in [:loops :test-dead-loop]
             {:fn (fn []) :tick-count 0
              :running? running?
              :sleep-interrupted? (atom false)
              :thread dead-t})
      (supervisor/on-event! :loops :down ::track-dead
                            (fn [p] (swap! dead-events conj p)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? true)
      (Thread/sleep 200)
      (supervisor/stop-watchdog!)
      (swap! sref update :loops dissoc :test-dead-loop)
      (is (some #(= :test-dead-loop (:loop %)) @dead-events)
          "dead loop event emitted by watchdog"))))

;; ---------------------------------------------------------------------------
;; Watchdog lifecycle
;; ---------------------------------------------------------------------------

(deftest start-watchdog-running-test
  (testing "start-watchdog! sets running? to true"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (is (supervisor/running?))
    (supervisor/stop-watchdog!)))

(deftest stop-watchdog-not-running-test
  (testing "stop-watchdog! sets running? to false"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (supervisor/stop-watchdog!)
    (is (not (supervisor/running?)))))

(deftest double-start-throws-test
  (testing "starting the watchdog twice throws"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)))
      (finally
        (supervisor/stop-watchdog!)))))

(deftest stop-wires-out-pause-check-test
  (testing "stop-watchdog! removes the pause-check hook from deflive-loop"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (is (some? @loop-ns/-pause-check-fn) "pause-check wired in after start")
    (supervisor/stop-watchdog!)
    (is (nil? @loop-ns/-pause-check-fn) "pause-check cleared after stop")))

;; ---------------------------------------------------------------------------
;; BPM-derived watchdog interval
;; ---------------------------------------------------------------------------

(deftest watchdog-sleep-ms-explicit-interval-test
  (testing "watchdog-sleep-ms returns interval-ms unchanged when explicitly provided"
    ;; fixture starts at bpm 60000, so BPM-derived default would be tiny;
    ;; explicit 5000 must be returned verbatim
    (is (= 5000 (@#'supervisor/watchdog-sleep-ms 5000 1 4)))))

(deftest watchdog-sleep-ms-derives-from-bpm-test
  (testing "watchdog-sleep-ms computes ms from BPM when interval-ms is nil"
    ;; fixture sets BPM=60000 → 1 beat = 1 ms
    ;; 1 bar × 4 beats/bar × (60000 / 60000) = 4 ms
    (is (= 100 (@#'supervisor/watchdog-sleep-ms nil 1 4))
        "result clamped to 100 ms minimum")
    ;; At bpm=60: 1 bar × 4 beats × (60000/60) = 4000 ms
    (core/set-bpm! 60)
    (is (= 4000 (@#'supervisor/watchdog-sleep-ms nil 1 4)))))

(deftest watchdog-sleep-ms-bars-parameter-test
  (testing "watchdog-sleep-ms scales by :bars"
    ;; At bpm=60: 2 bars × 4 beats × 1000 ms/beat = 8000 ms
    (core/set-bpm! 60)
    (is (= 8000 (@#'supervisor/watchdog-sleep-ms nil 2 4)))))

(deftest watchdog-sleep-ms-beats-per-bar-test
  (testing "watchdog-sleep-ms scales by :beats-per-bar for odd meters"
    ;; At bpm=60: 1 bar × 3 beats × 1000 ms/beat = 3000 ms
    (core/set-bpm! 60)
    (is (= 3000 (@#'supervisor/watchdog-sleep-ms nil 1 3)))))

(deftest watchdog-bpm-derived-interval-fires-test
  (testing "watchdog with no :interval-ms fires at approximately one bar's worth of ms"
    ;; Set a very high BPM so one bar is just a few ms — let the watchdog tick fast
    (core/set-bpm! 60000)  ; 1 bar = 4 ms → clamped to 100 ms
    (let [tick-count (atom 0)]
      (supervisor/register! :bpm-svc :check-fn (fn [] (swap! tick-count inc) true))
      ;; no :interval-ms — uses BPM-derived sleep (clamped to 100 ms)
      (supervisor/start-watchdog! :monitor-loops? false)
      (Thread/sleep 350)
      (supervisor/stop-watchdog!)
      (is (>= @tick-count 2) "watchdog ticked at least twice in 350 ms"))))
