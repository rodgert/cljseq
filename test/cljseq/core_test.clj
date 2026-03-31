; SPDX-License-Identifier: EPL-2.0
(ns cljseq.core-test
  "Phase 0 unit tests — clock, virtual time, live loop, play!."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.clock :as clock]
            [cljseq.core  :as core]
            [cljseq.loop  :as loop-ns]))

;; ---------------------------------------------------------------------------
;; cljseq.clock
;; ---------------------------------------------------------------------------

(deftest master-clock-test
  (testing "MasterClock always returns 1.0"
    (let [mc (clock/master-clock 120)]
      (is (= 1.0 (clock/sample mc 0)))
      (is (= 1.0 (clock/sample mc 1/2)))
      (is (= 1.0 (clock/sample mc 7)))))
  (testing "MasterClock next-edge on integer beats"
    (let [mc (clock/master-clock 120)]
      (is (= 1 (clock/next-edge mc 0)))
      (is (= 2 (clock/next-edge mc 1)))
      (is (= 5 (clock/next-edge mc 4)))))
  (testing "MasterClock next-edge mid-beat advances to next integer"
    (let [mc (clock/master-clock 120)]
      (is (= 3 (clock/next-edge mc 2.3))))))

(deftest clock-div-test
  (testing "ClockDiv/4 fires on multiples of 4"
    (let [cd (clock/clock-div 4 (clock/master-clock 120))]
      (is (= 1.0 (clock/sample cd 0)))
      (is (= 0.0 (clock/sample cd 1)))
      (is (= 0.0 (clock/sample cd 2)))
      (is (= 1.0 (clock/sample cd 4)))
      (is (= 1.0 (clock/sample cd 8)))))
  (testing "ClockDiv next-edge"
    (let [cd (clock/clock-div 4 (clock/master-clock 120))]
      (is (= 4 (clock/next-edge cd 1)))
      (is (= 8 (clock/next-edge cd 4))))))

(deftest beats->ms-test
  (testing "120 BPM: 1 beat = 500ms"
    (is (= 500.0 (clock/beats->ms 1 120))))
  (testing "120 BPM: 1/2 beat = 250ms"
    (is (= 250.0 (clock/beats->ms 1/2 120))))
  (testing "60 BPM: 1 beat = 1000ms"
    (is (= 1000.0 (clock/beats->ms 1 60)))))

;; ---------------------------------------------------------------------------
;; keyword->midi
;; ---------------------------------------------------------------------------

(deftest keyword->midi-test
  (testing "Standard notes"
    (is (= 60 (core/keyword->midi :C4)))
    (is (= 69 (core/keyword->midi :A4)))
    (is (= 72 (core/keyword->midi :C5)))
    (is (= 48 (core/keyword->midi :C3))))
  (testing "Sharps and flats"
    (is (= 61 (core/keyword->midi :C#4)))
    (is (= 61 (core/keyword->midi :Db4)))
    (is (= 56 (core/keyword->midi :G#3))))
  (testing "Invalid keywords return nil"
    (is (nil? (core/keyword->midi :not-a-note)))
    (is (nil? (core/keyword->midi :foo)))))

;; ---------------------------------------------------------------------------
;; Virtual time
;; ---------------------------------------------------------------------------

(deftest virtual-time-test
  (testing "now returns *virtual-time* in loop context"
    (binding [loop-ns/*virtual-time* 4N]
      (is (= 4N (loop-ns/now)))))
  (testing "sleep! advances *virtual-time*"
    ;; We need system started to get BPM; use a very fast BPM to minimise sleep
    (core/start! :bpm 6000)
    (try
      (binding [loop-ns/*virtual-time* 0N]
        (loop-ns/sleep! 1)
        (is (= 1N (loop-ns/now))))
      (finally
        (core/stop!)))))

;; ---------------------------------------------------------------------------
;; deflive-loop — smoke test
;; ---------------------------------------------------------------------------

(deftest deflive-loop-smoke-test
  (testing "deflive-loop starts, ticks, and stops"
    (core/start! :bpm 6000) ; fast BPM: 1 beat = 10ms
    (try
      (core/deflive-loop :test-smoke {}
        (loop-ns/sleep! 1))
      ;; Give the loop ~5 iterations worth of time (50ms)
      (Thread/sleep 100)
      (let [ticks (core/ctrl-get [:loops :test-smoke :tick-count])]
        (is (pos? ticks) "Loop should have ticked at least once"))
      (core/stop-loop! :test-smoke)
      (finally
        (core/stop!)))))

(deftest deflive-loop-hotswap-test
  (testing "re-evaluating deflive-loop replaces fn on next iteration"
    (core/start! :bpm 6000)
    (try
      (let [seen (atom [])]
        (core/deflive-loop :test-swap {}
          (swap! seen conj :v1)
          (loop-ns/sleep! 1))
        (Thread/sleep 50)
        ;; Hot-swap to v2
        (core/deflive-loop :test-swap {}
          (swap! seen conj :v2)
          (loop-ns/sleep! 1))
        (Thread/sleep 50)
        (is (some #{:v1} @seen) "v1 should have run before swap")
        (is (some #{:v2} @seen) "v2 should have run after swap"))
      (finally
        (core/stop!)))))
