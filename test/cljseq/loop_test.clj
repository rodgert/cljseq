; SPDX-License-Identifier: EPL-2.0
(ns cljseq.loop-test
  "Unit tests for cljseq.loop — virtual time, sleep!, sync!, deflive-loop,
  stop-loop!, dynamic context vars."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core :as core]
            [cljseq.loop :as loop]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  ;; 60000 BPM = 1 beat per millisecond — makes timing tests fast
  (core/start! :bpm 60000)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Dynamic vars: defaults and binding
;; ---------------------------------------------------------------------------

(deftest dynamic-var-defaults-test
  (testing "*synth-ctx* default is nil"
    (is (nil? loop/*synth-ctx*)))
  (testing "*timing-ctx* default is nil"
    (is (nil? loop/*timing-ctx*)))
  (testing "*mod-ctx* default is nil"
    (is (nil? loop/*mod-ctx*)))
  (testing "*virtual-time* default is 0.0"
    (is (= 0.0 loop/*virtual-time*))))

(deftest now-returns-virtual-time-test
  (testing "now returns *virtual-time*"
    (binding [loop/*virtual-time* 42.5]
      (is (= 42.5 (loop/now)))))
  (testing "now returns 0.0 from default binding"
    (is (= 0.0 (loop/now)))))

(deftest dynamic-binding-isolates-threads-test
  (testing "dynamic bindings are per-thread"
    (let [results (atom [])
          done    (promise)]
      (binding [loop/*virtual-time* 100.0]
        (.start
          (Thread.
            (fn []
              (binding [loop/*virtual-time* 200.0]
                (swap! results conj (loop/now))
                (deliver done true)))))
        (swap! results conj (loop/now)))
      (deref done 1000 nil)
      (is (= #{100.0 200.0} (set @results))
          "each thread sees its own virtual time"))))

;; ---------------------------------------------------------------------------
;; sleep! — advances *virtual-time*
;; ---------------------------------------------------------------------------

(deftest sleep-advances-virtual-time-test
  (testing "sleep! advances *virtual-time* by the requested beats"
    (let [result (promise)]
      (.start
        (Thread.
          (fn []
            (binding [loop/*virtual-time* 0.0]
              (loop/sleep! 5)
              (deliver result (loop/now))))))
      (is (= 5.0 (deref result 1000 :timeout))
          "*virtual-time* advanced to 5.0"))))

(deftest sleep-rational-beats-test
  (testing "sleep! accepts rational beat durations"
    (let [result (promise)]
      (.start
        (Thread.
          (fn []
            (binding [loop/*virtual-time* 0.0]
              (loop/sleep! 1/4)
              (deliver result (loop/now))))))
      (let [vt (deref result 1000 :timeout)]
        (is (not= :timeout vt))
        (is (< (Math/abs (- (double vt) 0.25)) 1e-9))))))

(deftest sleep-cumulative-test
  (testing "multiple sleep! calls accumulate *virtual-time*"
    (let [result (promise)]
      (.start
        (Thread.
          (fn []
            (binding [loop/*virtual-time* 0.0]
              (loop/sleep! 2)
              (loop/sleep! 3)
              (deliver result (loop/now))))))
      (is (= 5.0 (deref result 1000 :timeout))))))

;; ---------------------------------------------------------------------------
;; sync! — aligns to beat-grid boundary
;; ---------------------------------------------------------------------------

(deftest sync-aligns-to-whole-beat-test
  (testing "sync! with divisor 1 snaps to next whole beat"
    (let [result (promise)]
      (.start
        (Thread.
          (fn []
            (binding [loop/*virtual-time* 0.7]
              (deliver result (loop/sync!))))))
      (is (= 1.0 (deref result 1000 :timeout))))))

(deftest sync-aligns-to-bar-boundary-test
  (testing "sync! with divisor 4 snaps to next 4-beat boundary"
    (let [result (promise)]
      (.start
        (Thread.
          (fn []
            (binding [loop/*virtual-time* 0.7]
              (deliver result (loop/sync! 4))))))
      (is (= 4.0 (deref result 1000 :timeout)))))
  (testing "sync! past first bar snaps to next"
    (let [result (promise)]
      (.start
        (Thread.
          (fn []
            (binding [loop/*virtual-time* 4.1]
              (deliver result (loop/sync! 4))))))
      (is (= 8.0 (deref result 1000 :timeout))))))

(deftest sync-half-beat-test
  (testing "sync! with divisor 1/2 snaps to next half-beat"
    (let [result (promise)]
      (.start
        (Thread.
          (fn []
            (binding [loop/*virtual-time* 0.3]
              (deliver result (loop/sync! 1/2))))))
      (is (= 0.5 (deref result 1000 :timeout))))))

;; ---------------------------------------------------------------------------
;; deflive-loop — registration and execution
;; ---------------------------------------------------------------------------

(deftest deflive-loop-registers-in-system-test
  (testing "deflive-loop registers the loop in system state"
    (loop/deflive-loop :test-reg-loop {}
      (loop/sleep! 1000))
    (Thread/sleep 20)
    (let [s @(deref (loop/-system-ref))]
      (is (some? (get-in s [:loops :test-reg-loop])) "loop entry exists"))
    (loop/stop-loop! :test-reg-loop)))

(deftest deflive-loop-increments-tick-count-test
  (testing "deflive-loop increments tick-count each iteration"
    (let [counter (atom 0)]
      (loop/deflive-loop :test-tick-loop {}
        (swap! counter inc)
        (loop/sleep! 1))
      (Thread/sleep 50)   ; allow several ticks at 60000 BPM
      (is (pos? @counter) "tick-count incremented")
      (loop/stop-loop! :test-tick-loop))))

(deftest deflive-loop-hot-swap-test
  (testing "re-evaluating deflive-loop hot-swaps the body"
    (let [v (atom :original)]
      ;; First definition
      (loop/deflive-loop :test-swap-loop {}
        (reset! v :original)
        (loop/sleep! 1))
      (Thread/sleep 20)
      ;; Hot-swap: new definition
      (loop/deflive-loop :test-swap-loop {}
        (reset! v :updated)
        (loop/sleep! 1))
      (Thread/sleep 50)   ; wait for new iteration to fire
      (is (= :updated @v) "body was hot-swapped")
      (loop/stop-loop! :test-swap-loop))))

(deftest deflive-loop-hot-swap-interrupts-long-sleep-test
  (testing "hot-swapping a loop parked in a very long sleep! picks up the new fn immediately"
    ;; The loop sleeps for 1000 beats (= 1000ms at 60000 BPM).
    ;; Without sleep interruption the new body would not run for ~1000ms.
    ;; With it, the thread should pick up the new fn within ~50ms.
    (let [v       (atom :original)
          started (promise)]
      (loop/deflive-loop :test-hotswap-interrupt {}
        (deliver started :ok)
        (reset! v :original)
        (loop/sleep! 1000))
      (deref started 500 :timeout)   ; wait for first iteration to begin
      (Thread/sleep 5)               ; let it enter sleep!
      (loop/deflive-loop :test-hotswap-interrupt {}
        (reset! v :updated)
        (loop/sleep! 1000))
      (Thread/sleep 100)  ; well within the 1000-beat sleep deadline
      (is (= :updated @v) "new body ran while old sleep was still pending")
      (loop/stop-loop! :test-hotswap-interrupt))))

(deftest deflive-loop-restarts-stopped-loop-test
  (testing "deflive-loop with a stopped loop name starts a fresh loop"
    ;; Regression: previously the stale state-map entry caused deflive-loop to
    ;; hot-swap the fn into the dead thread, so the new body never executed.
    (let [v (atom :initial)]
      (loop/deflive-loop :test-restart-loop {}
        (reset! v :first)
        (loop/sleep! 1))
      (Thread/sleep 30)
      (loop/stop-loop! :test-restart-loop)
      (Thread/sleep 20)  ; let old thread exit
      (reset! v :waiting)
      (loop/deflive-loop :test-restart-loop {}
        (reset! v :second)
        (loop/sleep! 1000))
      (Thread/sleep 30)  ; give new thread time to fire once
      (is (= :second @v) "new body executed after restart with same name")
      (loop/stop-loop! :test-restart-loop))))

(deftest live-loop-alias-test
  (testing "live-loop is an alias for deflive-loop"
    (loop/live-loop :test-alias-loop {}
      (loop/sleep! 1000))
    (Thread/sleep 20)
    (let [s @(deref (loop/-system-ref))]
      (is (some? (get-in s [:loops :test-alias-loop])) "alias creates loop"))
    (loop/stop-loop! :test-alias-loop)))

;; ---------------------------------------------------------------------------
;; stop-loop! / stop-all-loops!
;; ---------------------------------------------------------------------------

(deftest stop-loop-removes-state-entry-test
  (testing "stop-loop! causes the state entry to be removed after thread exits"
    ;; Use sleep! 1 (= 1ms at 60000 BPM) so the thread exits promptly after
    ;; stop-loop! unparks it — park-until-beat! re-parks if the deadline is
    ;; far away, so a short sleep is needed to make unpark effective quickly.
    (loop/deflive-loop :test-cleanup-loop {}
      (loop/sleep! 1))
    (Thread/sleep 20)
    ;; Entry exists while running
    (is (some? (get-in @(deref (loop/-system-ref)) [:loops :test-cleanup-loop])))
    (loop/stop-loop! :test-cleanup-loop)
    ;; Thread wakes from its 1ms sleep, checks running? → false, self-cleanup runs
    (Thread/sleep 30)
    (is (nil? (get-in @(deref (loop/-system-ref)) [:loops :test-cleanup-loop]))
        "state entry removed after stop")))

(deftest stop-loop-halts-execution-test
  (testing "stop-loop! prevents further iterations"
    (let [counter (atom 0)]
      (loop/deflive-loop :test-stop-loop {}
        (swap! counter inc)
        (loop/sleep! 1))
      (Thread/sleep 30)
      (let [count-before @counter]
        (loop/stop-loop! :test-stop-loop)
        (Thread/sleep 20)  ; settle
        (is (>= @counter count-before) "counter didn't go backwards")
        ;; After stopping, count should not grow further
        (let [count-after @counter]
          (Thread/sleep 20)
          (is (= count-after @counter) "counter stable after stop"))))))

(deftest stop-all-loops-test
  (testing "stop-all-loops! stops all running loops"
    (let [a (atom 0) b (atom 0)]
      (loop/deflive-loop :test-all-a {}
        (swap! a inc)
        (loop/sleep! 1))
      (loop/deflive-loop :test-all-b {}
        (swap! b inc)
        (loop/sleep! 1))
      (Thread/sleep 20)
      (loop/stop-all-loops!)
      (Thread/sleep 10)
      (let [a0 @a b0 @b]
        (Thread/sleep 20)
        (is (= a0 @a) "loop a stopped")
        (is (= b0 @b) "loop b stopped")))))

;; ---------------------------------------------------------------------------
;; Context vars propagate through deflive-loop opts
;; ---------------------------------------------------------------------------

(deftest deflive-loop-binds-synth-ctx-test
  (testing "deflive-loop binds *synth-ctx* from :synth opt"
    (let [captured (promise)]
      (loop/deflive-loop :test-ctx-loop {:synth {:midi/channel 5}}
        (when-not (realized? captured)
          (deliver captured loop/*synth-ctx*))
        (loop/sleep! 1000))
      (let [ctx (deref captured 1000 :timeout)]
        (is (not= :timeout ctx))
        (is (= {:midi/channel 5} ctx) "synth ctx bound correctly"))
      (loop/stop-loop! :test-ctx-loop))))

;; ---------------------------------------------------------------------------
;; Resilient loop — errors do not kill the thread
;; ---------------------------------------------------------------------------

(deftest loop-survives-body-exception-test
  (testing "a body exception is caught and the loop continues"
    (let [counter  (atom 0)
          throw?   (atom true)]
      (loop/deflive-loop :test-error-loop {}
        (swap! counter inc)
        (when @throw?
          (throw (ex-info "deliberate test error" {})))
        (loop/sleep! 1))
      ;; Let it throw for a few iterations, then disable the throw
      (Thread/sleep 30)
      (reset! throw? false)
      (let [count-at-disable @counter]
        (Thread/sleep 30)
        (is (> @counter count-at-disable)
            "loop kept running after exception"))
      (loop/stop-loop! :test-error-loop))))

(deftest loop-restarts-after-crash-test
  (testing "deflive-loop detects a dead thread and starts a fresh one"
    ;; Simulate a crashed loop by creating an entry with a dead thread
    ;; and running?=true (exactly what happens when the body throws an Error
    ;; that bypasses Exception).  The stale-check now runs inside a single
    ;; swap! so no concurrent deflive-loop can observe torn state.
    (let [v        (atom :initial)
          dead-t   (doto (Thread. (fn [])) (.start))
          running? (atom true)
          sref     @(loop/-system-ref)]
      ;; Wait for the thread to die naturally
      (.join dead-t 200)
      ;; Inject the stale entry directly
      (swap! sref assoc-in [:loops :test-crash-loop]
             {:fn         (fn [] (reset! v :stale))
              :tick-count 0
              :running?   running?
              :thread     dead-t})
      ;; Re-evaluate — should detect dead thread and start fresh
      (loop/deflive-loop :test-crash-loop {}
        (reset! v :fresh)
        (loop/sleep! 1000))
      (Thread/sleep 30)
      (is (= :fresh @v) "fresh loop body ran after dead thread detected")
      (loop/stop-loop! :test-crash-loop))))

(deftest loop-survives-throwable-error-test
  (testing "a body Throwable (Error subclass) is caught and the loop continues"
    ;; Prior to the catch-Throwable fix, an AssertionError would escape the
    ;; try-catch, kill the thread silently, and leave running?=true —
    ;; the loop would go dark with no feedback.
    (let [counter (atom 0)
          throw?  (atom true)]
      (loop/deflive-loop :test-throwable-loop {}
        (swap! counter inc)
        (when @throw?
          (throw (AssertionError. "deliberate throwable")))
        (loop/sleep! 1))
      ;; Let it throw for a few iterations, then disable the throw
      (Thread/sleep 30)
      (reset! throw? false)
      (let [count-at-disable @counter]
        (Thread/sleep 30)
        (is (> @counter count-at-disable)
            "loop kept running after AssertionError"))
      (loop/stop-loop! :test-throwable-loop))))

(deftest loop-stale-check-atomic-with-stopped-loop-test
  (testing "stale-check inside swap! is consistent: stopped loop starts fresh, not hot-swap"
    ;; The stale check (running?=false) and entry removal now happen in a single
    ;; swap!, so a concurrent deflive-loop cannot see a partially-cleared state.
    ;; Behaviorally: stopping a loop and re-evaluating with the same name must
    ;; always produce a fresh thread, never a hot-swap into the stopped entry.
    (let [which (atom :none)]
      (loop/deflive-loop :test-atomic-stale {}
        (reset! which :first)
        (loop/sleep! 1))
      (Thread/sleep 20)
      (loop/stop-loop! :test-atomic-stale)
      (Thread/sleep 20)  ; let thread exit and self-clean
      ;; At this point running?=false. Re-evaluating must start fresh.
      (loop/deflive-loop :test-atomic-stale {}
        (reset! which :second)
        (loop/sleep! 1000))
      (Thread/sleep 30)
      (is (= :second @which) "re-evaluation after stop started a fresh loop")
      (loop/stop-loop! :test-atomic-stale))))

;; ---------------------------------------------------------------------------
;; -start-beat-for-period (Q32)
;; ---------------------------------------------------------------------------

(deftest start-beat-for-period-nil-no-link-test
  (testing "nil period with no Link active returns current beat (no future snap)"
    ;; At 60000 BPM, -current-beat changes fast; just verify the result is ≥ 0
    ;; and that the function returns without blocking.
    (let [b (loop/-start-beat-for-period nil)]
      (is (>= b 0.0) "returned a beat ≥ 0"))))

(deftest start-beat-for-period-true-snaps-to-quantum-test
  (testing "true snaps to next system-quantum boundary (default 4)"
    (let [b (loop/-start-beat-for-period true)]
      ;; Result must be a positive multiple of 4.0 (within floating point tolerance)
      (is (>= b 0.0))
      (let [rem (mod b 4.0)]
        (is (or (< rem 1e-6) (> rem 3.999999))
            (str "beat " b " is not near a multiple of 4"))))))

(deftest start-beat-for-period-number-snaps-to-period-test
  (testing "numeric period snaps to next multiple of N beats"
    (let [b2 (loop/-start-beat-for-period 2)]
      (let [rem (mod b2 2.0)]
        (is (or (< rem 1e-6) (> rem 1.999999))
            (str "beat " b2 " is not near a multiple of 2"))))
    (let [b8 (loop/-start-beat-for-period 8)]
      (let [rem (mod b8 8.0)]
        (is (or (< rem 1e-6) (> rem 7.999999))
            (str "beat " b8 " is not near a multiple of 8"))))))

;; ---------------------------------------------------------------------------
;; deflive-loop :restart-on-bar — parks without Link active (Q32)
;; ---------------------------------------------------------------------------

(deftest restart-on-bar-true-parks-until-boundary-test
  (testing ":restart-on-bar true parks until quantum boundary even without Link"
    ;; At 60000 BPM (1 beat/ms), 4 beats = 4ms. The loop should not start
    ;; immediately — it should wait until the next 4-beat boundary.
    ;; We give it up to 50ms (several quantum periods) to fire at least once.
    (let [started (promise)]
      (loop/deflive-loop :test-rob-true {:restart-on-bar true}
        (deliver started :ok)
        (loop/sleep! 1000))
      (let [result (deref started 100 :timeout)]
        (is (= :ok result) "loop started after quantum boundary"))
      (loop/stop-loop! :test-rob-true))))

(deftest restart-on-bar-period-snaps-to-boundary-test
  (testing ":restart-on-bar 2 parks until 2-beat boundary"
    (let [started (promise)]
      (loop/deflive-loop :test-rob-period {:restart-on-bar 2}
        (deliver started :ok)
        (loop/sleep! 1000))
      (let [result (deref started 50 :timeout)]
        (is (= :ok result) "loop started after 2-beat boundary"))
      (loop/stop-loop! :test-rob-period))))

(deftest restart-on-bar-false-starts-immediately-test
  (testing "no :restart-on-bar — loop starts immediately (no extra park)"
    ;; Control test: without :restart-on-bar a loop with no Link should
    ;; fire immediately (within a few ms).
    (let [started (promise)]
      (loop/deflive-loop :test-rob-none {}
        (deliver started :ok)
        (loop/sleep! 1000))
      (let [result (deref started 50 :timeout)]
        (is (= :ok result) "loop started without parking"))
      (loop/stop-loop! :test-rob-none))))

;; ---------------------------------------------------------------------------
;; loop-status
;; ---------------------------------------------------------------------------

(deftest loop-status-empty-test
  (testing "loop-status returns an empty vector when no loops are running"
    (is (= [] (loop/loop-status)))))

(deftest loop-status-running-test
  (testing "loop-status returns an entry for a running loop"
    (loop/deflive-loop :status-test {}
      (loop/sleep! 10000))
    (Thread/sleep 30)  ; allow loop thread to start
    (let [status (loop/loop-status)
          entry  (first (filter #(= :status-test (:name %)) status))]
      (is (some? entry) "running loop appears in status")
      (is (true? (:running? entry)) "running? is true")
      (is (number? (:ticks entry)) "ticks is numeric"))
    (loop/stop-loop! :status-test)))

(deftest loop-status-stopped-loop-removed-test
  (testing "stopped loop is removed from loop-status after thread exits"
    ;; Use a very short sleep (1 beat = 1ms at 60000 BPM) so stop-loop!
    ;; only has to wait for one iteration to finish, not a long sleep.
    (loop/deflive-loop :status-stop {}
      (loop/sleep! 1))
    (Thread/sleep 30)
    (loop/stop-loop! :status-stop)
    (Thread/sleep 50)  ; allow thread to finish current 1ms sleep and deregister
    (let [names (map :name (loop/loop-status))]
      (is (not (some #(= :status-stop %) names))
          "stopped loop not present in status"))))
