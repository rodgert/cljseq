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
  (testing "master-clock sample"
    (is (= 0.0 (clock/sample clock/master-clock 0)))
    (is (= 0.5 (clock/sample clock/master-clock 0.5)))
    (is (= 0.0 (clock/sample clock/master-clock 1.0))))
  (testing "master-clock next-edge"
    (is (= 1.0 (clock/next-edge clock/master-clock 0)))
    (is (= 1.0 (clock/next-edge clock/master-clock 0.3)))
    (is (= 2.0 (clock/next-edge clock/master-clock 1.0)))))

(deftest clock-div-test
  (testing "clock-div/4 sample"
    (is (= 0.0   (clock/sample (clock/clock-div 4) 0)))
    (is (= 0.25  (clock/sample (clock/clock-div 4) 1)))
    (is (= 0.0   (clock/sample (clock/clock-div 4) 4))))
  (testing "clock-div/4 next-edge"
    (is (= 4.0 (clock/next-edge (clock/clock-div 4) 0)))
    (is (= 4.0 (clock/next-edge (clock/clock-div 4) 1)))
    (is (= 8.0 (clock/next-edge (clock/clock-div 4) 4)))))

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
    (binding [loop-ns/*virtual-time* 4.0]
      (is (= 4.0 (loop-ns/now)))))
  (testing "sleep! advances *virtual-time*"
    ;; Use a very fast BPM (6000 = 10ms/beat) to keep test fast.
    (core/start! :bpm 6000)
    (try
      (binding [loop-ns/*virtual-time* 0.0]
        (loop-ns/sleep! 1)
        (is (= 1.0 (loop-ns/now))))
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
