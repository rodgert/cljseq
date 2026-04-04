; SPDX-License-Identifier: EPL-2.0
(ns cljseq.looper-test
  "Tests for the LooperDevice protocol, registry, collective ops, and
  level-scaling helpers. Uses a MockLooper to exercise the protocol
  without requiring any hardware or MIDI connection."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [cljseq.looper :as looper]))

;; ---------------------------------------------------------------------------
;; MockLooper — in-process LooperDevice for testing
;; ---------------------------------------------------------------------------

(defrecord MockLooper [calls]
  looper/LooperDevice
  (transport-start!  [_]         (swap! calls conj [:start]))
  (transport-stop!   [_]         (swap! calls conj [:stop]))
  (select-scene!     [_ n]       (swap! calls conj [:scene n]))
  (set-track-level!  [_ idx lvl] (swap! calls conj [:level idx lvl]))
  (mute-track!       [_ idx]     (swap! calls conj [:mute idx]))
  (unmute-track!     [_ idx]     (swap! calls conj [:unmute idx])))

(defn make-mock [] (->MockLooper (atom [])))

;; ---------------------------------------------------------------------------
;; Fixture — clear registry before each test
;; ---------------------------------------------------------------------------

(defn with-empty-registry [f]
  (doseq [k (keys (looper/registered-loopers))]
    (looper/unregister-looper! k))
  (f)
  (doseq [k (keys (looper/registered-loopers))]
    (looper/unregister-looper! k)))

(use-fixtures :each with-empty-registry)

;; ---------------------------------------------------------------------------
;; Protocol — direct dispatch on MockLooper
;; ---------------------------------------------------------------------------

(deftest transport-start-test
  (testing "transport-start! dispatches correctly"
    (let [m (make-mock)]
      (looper/transport-start! m)
      (is (= [[:start]] @(:calls m))))))

(deftest transport-stop-test
  (testing "transport-stop! dispatches correctly"
    (let [m (make-mock)]
      (looper/transport-stop! m)
      (is (= [[:stop]] @(:calls m))))))

(deftest select-scene-test
  (testing "select-scene! passes scene index"
    (let [m (make-mock)]
      (looper/select-scene! m 3)
      (is (= [[:scene 3]] @(:calls m))))))

(deftest set-track-level-test
  (testing "set-track-level! passes track index and level"
    (let [m (make-mock)]
      (looper/set-track-level! m 0 0.75)
      (is (= [[:level 0 0.75]] @(:calls m))))))

(deftest mute-track-test
  (testing "mute-track! passes track index"
    (let [m (make-mock)]
      (looper/mute-track! m 2)
      (is (= [[:mute 2]] @(:calls m))))))

(deftest unmute-track-test
  (testing "unmute-track! passes track index"
    (let [m (make-mock)]
      (looper/unmute-track! m 2)
      (is (= [[:unmute 2]] @(:calls m))))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest register-returns-nil-test
  (testing "register-looper! returns nil"
    (is (nil? (looper/register-looper! :test (make-mock))))))

(deftest register-stores-device-test
  (testing "registered device appears in registered-loopers"
    (let [m (make-mock)]
      (looper/register-looper! :dev-a m)
      (is (= m (get (looper/registered-loopers) :dev-a))))))

(deftest unregister-removes-device-test
  (testing "unregister-looper! removes the device"
    (looper/register-looper! :dev-b (make-mock))
    (looper/unregister-looper! :dev-b)
    (is (nil? (get (looper/registered-loopers) :dev-b)))))

(deftest unregister-noop-test
  (testing "unregister-looper! on unknown name is a no-op"
    (is (nil? (looper/unregister-looper! :not-registered-xyz)))))

(deftest re-register-replaces-test
  (testing "re-registering a name replaces the device"
    (let [m1 (make-mock) m2 (make-mock)]
      (looper/register-looper! :dev-c m1)
      (looper/register-looper! :dev-c m2)
      (is (= m2 (get (looper/registered-loopers) :dev-c))))))

(deftest register-requires-protocol-test
  (testing "register-looper! rejects non-LooperDevice values"
    (is (thrown? AssertionError
                 (looper/register-looper! :bad "not-a-device")))))

(deftest all-loopers-returns-vals-test
  (testing "all-loopers returns a seq of registered devices"
    (let [m1 (make-mock) m2 (make-mock)]
      (looper/register-looper! :x1 m1)
      (looper/register-looper! :x2 m2)
      (let [all (set (looper/all-loopers))]
        (is (contains? all m1))
        (is (contains? all m2))))))

;; ---------------------------------------------------------------------------
;; Collective operations
;; ---------------------------------------------------------------------------

(deftest start-all-loopers-test
  (testing "start-all-loopers! calls transport-start! on every device"
    (let [m1 (make-mock) m2 (make-mock)]
      (looper/register-looper! :a m1)
      (looper/register-looper! :b m2)
      (looper/start-all-loopers!)
      (is (= [[:start]] @(:calls m1)))
      (is (= [[:start]] @(:calls m2))))))

(deftest stop-all-loopers-test
  (testing "stop-all-loopers! calls transport-stop! on every device"
    (let [m1 (make-mock) m2 (make-mock)]
      (looper/register-looper! :a m1)
      (looper/register-looper! :b m2)
      (looper/stop-all-loopers!)
      (is (= [[:stop]] @(:calls m1)))
      (is (= [[:stop]] @(:calls m2))))))

(deftest set-all-levels-test
  (testing "set-all-levels! drives every device's track 0"
    (let [m1 (make-mock) m2 (make-mock)]
      (looper/register-looper! :a m1)
      (looper/register-looper! :b m2)
      (looper/set-all-levels! 0 0.5)
      (is (= [[:level 0 0.5]] @(:calls m1)))
      (is (= [[:level 0 0.5]] @(:calls m2))))))

(deftest mute-all-tracks-test
  (testing "mute-all-tracks! mutes track N on all devices"
    (let [m1 (make-mock) m2 (make-mock)]
      (looper/register-looper! :a m1)
      (looper/register-looper! :b m2)
      (looper/mute-all-tracks! 1)
      (is (= [[:mute 1]] @(:calls m1)))
      (is (= [[:mute 1]] @(:calls m2))))))

(deftest unmute-all-tracks-test
  (testing "unmute-all-tracks! unmutes track N on all devices"
    (let [m1 (make-mock) m2 (make-mock)]
      (looper/register-looper! :a m1)
      (looper/register-looper! :b m2)
      (looper/unmute-all-tracks! 3)
      (is (= [[:unmute 3]] @(:calls m1)))
      (is (= [[:unmute 3]] @(:calls m2))))))

(deftest collective-ops-empty-registry-test
  (testing "collective operations on empty registry are silent no-ops"
    (is (nil? (looper/start-all-loopers!)))
    (is (nil? (looper/stop-all-loopers!)))
    (is (nil? (looper/set-all-levels! 0 0.5)))
    (is (nil? (looper/mute-all-tracks! 0)))
    (is (nil? (looper/unmute-all-tracks! 0)))))

;; ---------------------------------------------------------------------------
;; Level scaling helpers
;; ---------------------------------------------------------------------------

(deftest normalized->midi-linear-test
  (testing "0.0 → 0, 0.5 → ~64, 1.0 → 127"
    (is (= 0   (looper/normalized->midi-linear 0.0)))
    (is (= 64  (looper/normalized->midi-linear 0.503937)))  ; nearest to 64/127
    (is (= 127 (looper/normalized->midi-linear 1.0))))
  (testing "clamps below 0 and above 127"
    (is (= 0   (looper/normalized->midi-linear -0.5)))
    (is (= 127 (looper/normalized->midi-linear 1.5)))))

(deftest normalized->aeros-fader-test
  (testing "0.0 → 0 (-inf)"
    (is (= 0 (looper/normalized->aeros-fader 0.0))))
  (testing "0.89 → 113 (unity / 0 dB)"
    (is (= 113 (looper/normalized->aeros-fader 0.89))))
  (testing "1.0 → 127 (+6 dB)"
    (is (= 127 (looper/normalized->aeros-fader 1.0))))
  (testing "midpoint 0.445 → somewhere between 0 and 113"
    (let [v (looper/normalized->aeros-fader 0.445)]
      (is (< 0 v 113))))
  (testing "above unity scales into 113–127 range"
    (let [v (looper/normalized->aeros-fader 0.945)]
      (is (< 113 v 127))))
  (testing "clamps at extremes"
    (is (= 0   (looper/normalized->aeros-fader -1.0)))
    (is (= 127 (looper/normalized->aeros-fader 2.0)))))
