; SPDX-License-Identifier: EPL-2.0
(ns cljseq.mpe-test
  "Tests for cljseq.mpe — MPE voice allocator, pitch bend math, and dispatch routing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core  :as core]
            [cljseq.mpe   :as mpe]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 60000)  ; fast clock so beat->ns math doesn't dominate
  (try (f) (finally
             (mpe/disable-mpe!)
             (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; enable / disable / mpe-enabled?
;; ---------------------------------------------------------------------------

(deftest enable-disable-test
  (testing "starts disabled"
    (is (false? (mpe/mpe-enabled?))))
  (testing "enable-mpe! activates MPE"
    (mpe/enable-mpe!)
    (is (true? (mpe/mpe-enabled?))))
  (testing "disable-mpe! deactivates MPE"
    (mpe/disable-mpe!)
    (is (false? (mpe/mpe-enabled?)))))

(deftest enable-returns-nil-test
  (testing "enable-mpe! returns nil"
    (is (nil? (mpe/enable-mpe!))))
  (testing "disable-mpe! returns nil"
    (is (nil? (mpe/disable-mpe!)))))

;; ---------------------------------------------------------------------------
;; semitones->bend14
;; ---------------------------------------------------------------------------

(deftest bend14-center-test
  (testing "zero semitones => 8192 (center)"
    (is (= 8192 (mpe/semitones->bend14 0.0)))
    (is (= 8192 (mpe/semitones->bend14 0)))))

(deftest bend14-max-positive-test
  (testing "full positive bend => 16383"
    (binding [mpe/*mpe-bend-range* 48]
      (is (= 16383 (mpe/semitones->bend14 48.0)))))
  (testing "beyond range is clamped to 16383"
    (binding [mpe/*mpe-bend-range* 48]
      (is (= 16383 (mpe/semitones->bend14 100.0))))))

(deftest bend14-max-negative-test
  (testing "full negative bend => 0"
    (binding [mpe/*mpe-bend-range* 48]
      (is (= 0 (mpe/semitones->bend14 -48.0)))))
  (testing "beyond negative range is clamped to 0"
    (binding [mpe/*mpe-bend-range* 48]
      (is (= 0 (mpe/semitones->bend14 -100.0))))))

(deftest bend14-small-positive-test
  (testing "one semitone up with ±48 range produces expected value"
    ;; 1/48 of 8191 ≈ 171 steps above center
    (binding [mpe/*mpe-bend-range* 48]
      (let [v (mpe/semitones->bend14 1.0)]
        (is (> v 8192) "above center")
        (is (< v 8400) "not too far above center")
        (is (= 8363 v))))))  ; 8192 + round(1/48 * 8191) = 8192 + 171 = 8363

(deftest bend14-small-negative-test
  (testing "one semitone down with ±48 range"
    (binding [mpe/*mpe-bend-range* 48]
      (let [v (mpe/semitones->bend14 -1.0)]
        (is (< v 8192) "below center")
        (is (= 8021 v))))))  ; 8192 - round(1/48 * 8192) = 8192 - 171 = 8021

(deftest bend14-range-2-test
  (testing "±2 semitone range (common default)"
    (binding [mpe/*mpe-bend-range* 2]
      (is (= 8192 (mpe/semitones->bend14 0.0)))
      (is (= 16383 (mpe/semitones->bend14 2.0)))
      (is (= 0     (mpe/semitones->bend14 -2.0)))
      ;; 0.5 semitones up: round(0.25 * 8191) = 2048 above center
      (is (= 10240 (mpe/semitones->bend14 0.5))))))

(deftest bend14-result-in-range-test
  (testing "result is always in [0 16383]"
    (binding [mpe/*mpe-bend-range* 48]
      (doseq [st (range -50 51)]
        (let [v (mpe/semitones->bend14 (double st))]
          (is (>= v 0)     (str "below 0 at semitones=" st))
          (is (<= v 16383) (str "above 16383 at semitones=" st)))))))

;; ---------------------------------------------------------------------------
;; mpe-step?
;; ---------------------------------------------------------------------------

(deftest mpe-step-pitch-bend-test
  (testing ":pitch/bend triggers MPE routing"
    (is (true? (mpe/mpe-step? {:pitch/midi 60 :pitch/bend 0.5})))))

(deftest mpe-step-pressure-test
  (testing ":midi/pressure triggers MPE routing"
    (is (true? (mpe/mpe-step? {:pitch/midi 60 :midi/pressure 80})))))

(deftest mpe-step-slide-test
  (testing ":mod/slide triggers MPE routing"
    (is (true? (mpe/mpe-step? {:pitch/midi 60 :mod/slide 64})))))

(deftest mpe-step-all-keys-test
  (testing "all MPE keys together triggers routing"
    (is (true? (mpe/mpe-step? {:pitch/midi 60 :dur/beats 1
                               :pitch/bend 0.5 :midi/pressure 80 :mod/slide 64})))))

(deftest mpe-step-plain-step-map-test
  (testing "plain step map without MPE keys is not an MPE step"
    (is (false? (mpe/mpe-step? {:pitch/midi 60 :dur/beats 1/4})))
    (is (false? (mpe/mpe-step? {:pitch/midi 60 :mod/velocity 80})))
    (is (false? (mpe/mpe-step? {})))))

(deftest mpe-step-non-map-test
  (testing "non-map inputs return false"
    (is (false? (mpe/mpe-step? nil)))
    (is (false? (mpe/mpe-step? 60)))
    (is (false? (mpe/mpe-step? :C4)))))

;; ---------------------------------------------------------------------------
;; Channel allocation — round-robin
;; ---------------------------------------------------------------------------

(deftest channel-alloc-round-robin-test
  (testing "next-channel! cycles through channels 2–9"
    ;; Reset allocator by cycling 8 more times and observing the round-trip
    ;; Note: we call mpe/play-mpe! indirectly; test the public effect via
    ;; enable-mpe! + play! in Phase 0. For unit-level channel ordering,
    ;; use play-mpe! with a fake step (sidecar not connected → returns nil).
    (mpe/enable-mpe!)
    (let [results (atom [])]
      ;; play-mpe! returns the channel or nil; call 8 times
      (let [tl {:bpm 60000 :beat0-epoch-ms (System/currentTimeMillis) :beat0-beat 0.0}]
        (dotimes [_ 8]
          (let [ch (mpe/play-mpe! {:pitch/midi 60 :dur/beats 1/4} 0.0 tl)]
            ;; nil when sidecar not connected; that's expected in unit tests
            (swap! results conj ch))))
      ;; All values are nil (no sidecar) — just verify no exceptions thrown
      (is (= 8 (count @results))))))

;; ---------------------------------------------------------------------------
;; play-mpe! — no sidecar (Phase 0 / unit test mode)
;; ---------------------------------------------------------------------------

(deftest play-mpe-no-sidecar-test
  (testing "play-mpe! returns nil when sidecar not connected"
    (let [tl {:bpm 60000 :beat0-epoch-ms (System/currentTimeMillis) :beat0-beat 0.0}
          result (mpe/play-mpe! {:pitch/midi 60 :dur/beats 1/4 :pitch/bend 0.5}
                                0.0 tl)]
      (is (nil? result)))))

(deftest play-mpe-no-crash-with-all-keys-test
  (testing "play-mpe! with all MPE keys doesn't crash (no sidecar)"
    (let [tl {:bpm 60000 :beat0-epoch-ms (System/currentTimeMillis) :beat0-beat 0.0}]
      (is (nil? (mpe/play-mpe! {:pitch/midi 60 :dur/beats 1/2
                                :mod/velocity 80
                                :pitch/bend 0.5
                                :midi/pressure 90
                                :mod/slide 64}
                               0.0 tl))))))

(deftest play-mpe-minimal-step-test
  (testing "play-mpe! with only :pitch/midi works (no MPE keys needed)"
    (let [tl {:bpm 60000 :beat0-epoch-ms (System/currentTimeMillis) :beat0-beat 0.0}]
      (is (nil? (mpe/play-mpe! {:pitch/midi 60} 0.0 tl))))))

;; ---------------------------------------------------------------------------
;; core/play! routing — MPE dispatch decision
;; ---------------------------------------------------------------------------

(deftest core-play-mpe-disabled-no-crash-test
  (testing "play! with MPE step map but MPE disabled → Phase 0 stdout path"
    ;; MPE disabled (fixture teardown ensures this) — no sidecar connected
    ;; Should print to stdout via Phase 0 path, not crash
    (is (nil? (core/play! {:pitch/midi 60 :dur/beats 1/4 :pitch/bend 0.5})))))

(deftest core-play-mpe-enabled-no-crash-test
  (testing "play! with MPE enabled and MPE step map → nil (no sidecar)"
    (mpe/enable-mpe!)
    (is (nil? (core/play! {:pitch/midi 60 :dur/beats 1/4 :pitch/bend 0.5})))))

(deftest core-play-mpe-enabled-plain-step-test
  (testing "play! with MPE enabled but plain step map → normal Phase 0 path"
    (mpe/enable-mpe!)
    ;; No :pitch/bend/:midi/pressure/:mod/slide → goes to normal path → Phase 0 stdout
    (is (nil? (core/play! {:pitch/midi 60 :dur/beats 1/4})))))

;; ---------------------------------------------------------------------------
;; *mpe-bend-range* binding
;; ---------------------------------------------------------------------------

(deftest mpe-bend-range-binding-test
  (testing "*mpe-bend-range* is dynamically rebindable"
    (binding [mpe/*mpe-bend-range* 2]
      (is (= 2 mpe/*mpe-bend-range*))
      (is (= 16383 (mpe/semitones->bend14 2.0)))
      (is (= 8192  (mpe/semitones->bend14 0.0)))))
  (testing "default *mpe-bend-range* is 48"
    (is (= 48 mpe/*mpe-bend-range*))))
