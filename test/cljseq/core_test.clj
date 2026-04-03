; SPDX-License-Identifier: EPL-2.0
(ns cljseq.core-test
  "Phase 0 unit tests — clock, virtual time, live loop, play!."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.clock :as clock]
            [cljseq.core  :as core]
            [cljseq.ctrl  :as ctrl]
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
;; sync!
;; ---------------------------------------------------------------------------

(deftest sync-test
  (testing "sync! advances to next whole-beat boundary"
    (core/start! :bpm 6000) ; 10ms per beat
    (try
      (binding [loop-ns/*virtual-time* 3.2]
        (loop-ns/sync!)
        (is (= 4.0 (loop-ns/now))))
      (finally (core/stop!))))
  (testing "sync! with divisor 4 advances to next 4-beat boundary"
    (core/start! :bpm 6000)
    (try
      (binding [loop-ns/*virtual-time* 1.5]
        (loop-ns/sync! 4)
        (is (= 4.0 (loop-ns/now))))
      (finally (core/stop!))))
  (testing "sync! from exact boundary advances to NEXT boundary"
    (core/start! :bpm 6000)
    (try
      (binding [loop-ns/*virtual-time* 4.0]
        (loop-ns/sync!)
        (is (= 5.0 (loop-ns/now))))
      (finally (core/stop!)))))

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

;; ---------------------------------------------------------------------------
;; set-bpm! Q60 — unpark sleeping loops on tempo change
;; ---------------------------------------------------------------------------

(deftest set-bpm-unparks-test
  (testing "set-bpm! wakes sleeping loop threads so they recompute deadline"
    ;; Strategy: start at a slow BPM so sleep! parks for a long time,
    ;; then call set-bpm! at a much faster tempo and verify the loop
    ;; completes its sleep quickly rather than waiting the full old deadline.
    (core/start! :bpm 10)   ; 10 BPM → 1 beat = 6000ms
    (try
      (let [done? (promise)]
        ;; Loop sleeps for 1 beat at 10 BPM (= 6000ms).
        ;; If Q60 works, set-bpm! will unpark it and it will recompute
        ;; the deadline at 6000 BPM (= 10ms/beat), finishing almost instantly.
        (core/deflive-loop :q60-test {}
          (loop-ns/sleep! 1)
          (deliver done? true)
          ;; Stop after one iteration
          (core/stop-loop! :q60-test))
        ;; Give the loop 50ms to start and park in sleep!
        (Thread/sleep 50)
        ;; Raise BPM to 6000 — the sleeping loop should wake and recompute
        (core/set-bpm! 6000)
        ;; The loop should complete well within 500ms (generous headroom)
        (is (deref done? 500 false)
            "Loop should wake and finish within 500ms after set-bpm!"))
      (finally (core/stop!)))))

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

;; ---------------------------------------------------------------------------
;; §24.6 Phase 3 — ctrl-path step-mod dispatch at note-on time
;; ---------------------------------------------------------------------------

(deftest ctrl-path-step-mod-dispatched-at-note-on-test
  (testing "ctrl-path entry in *step-mod-ctx* dispatched via send-at! at note-on time"
    ;; Set up a ctrl node with a MIDI CC binding
    (core/start! :bpm 120)
    (try
      (ctrl/defnode! [:step-mod/cutoff] :type :int :node-meta {:range [0 127]})
      (ctrl/bind! [:step-mod/cutoff]
                  {:type :midi-cc :channel 1 :cc-num 74 :range [0 127]})
      (let [send-at-calls (atom [])
            note-on-calls (atom [])]
        (with-redefs [cljseq.sidecar/connected?   (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [t ch n v]
                                                      (swap! note-on-calls conj {:t t :ch ch :n n :v v}))
                      cljseq.sidecar/send-note-off! (fn [& _])
                      cljseq.sidecar/send-cc!        (fn [t ch cc val]
                                                       (swap! send-at-calls conj {:t t :ch ch :cc cc :val val}))]
          (binding [loop-ns/*virtual-time*   0.0
                    loop-ns/*step-mod-ctx*   {[:step-mod/cutoff] 80}]
            (core/play! {:pitch/midi 60 :dur/beats 1/4})))
        ;; The CC for cutoff should have been dispatched
        (let [cc-calls (filter #(= 74 (:cc %)) @send-at-calls)]
          (is (= 1 (count cc-calls)) "one CC74 dispatched for step-mod")
          ;; CC and note-on should share the same timestamp
          (when (and (seq cc-calls) (seq @note-on-calls))
            (is (= (:t (first cc-calls))
                   (:t (first @note-on-calls)))
                "step-mod CC and note-on share the same scheduled timestamp"))))
      (finally (core/stop!)))))

(deftest ctrl-path-step-mod-keyword-not-dispatched-test
  (testing "keyword keys in *step-mod-ctx* do NOT trigger send-at! (handled by apply-step-mods)"
    (core/start! :bpm 120)
    (try
      (ctrl/defnode! [:step-mod/vel] :type :int :node-meta {:range [0 127]})
      (ctrl/bind! [:step-mod/vel]
                  {:type :midi-cc :channel 1 :cc-num 11 :range [0 127]})
      (let [cc11-calls (atom [])]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [& _])
                      cljseq.sidecar/send-note-off! (fn [& _])
                      cljseq.sidecar/send-cc!        (fn [_t _ch cc _val]
                                                       (when (= 11 cc)
                                                         (swap! cc11-calls conj cc)))]
          (binding [loop-ns/*virtual-time*   0.0
                    ;; :mod/velocity is a keyword key — goes into step map, not ctrl dispatch
                    loop-ns/*step-mod-ctx*   {:mod/velocity 80}]
            (core/play! {:pitch/midi 60 :dur/beats 1/4})))
        ;; CC11 (bound to [:step-mod/vel]) should NOT have been sent
        (is (empty? @cc11-calls) "keyword step-mod does not trigger CC on [:step-mod/vel]"))
      (finally (core/stop!)))))
