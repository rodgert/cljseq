; SPDX-License-Identifier: EPL-2.0
(ns cljseq.link-test
  "Tests for cljseq.link Phase 2 — transport hooks, BPM propagation, beacon
  integration. No live Link session or sidecar is required; the tests drive
  the system via the private push handler and atom manipulation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core  :as core]
            [cljseq.link  :as link]))

;; ---------------------------------------------------------------------------
;; Helpers — private atom access
;; ---------------------------------------------------------------------------

(defn- system-ref  [] @#'link/system-ref)
(defn- hooks-atom  [] @#'link/transport-hook-registry)
;; Simulate a sidecar 0x80 push by calling the private handler directly.
;; Builds the 37-byte payload matching the wire format in parse-link-state.
(defn- make-link-payload
  [& {:keys [bpm anchor-beat anchor-us peers playing]
      :or   {bpm 120.0 anchor-beat 0.0 anchor-us 0
             peers 0 playing false}}]
  (let [buf (java.nio.ByteBuffer/allocate 37)]
    (.order buf java.nio.ByteOrder/LITTLE_ENDIAN)
    (.putDouble buf bpm)
    (.putDouble buf anchor-beat)
    (.putLong   buf anchor-us)
    (.putInt    buf (int peers))
    (.put       buf (byte (if playing 1 0)))
    (.put       buf (byte 0))
    (.put       buf (byte 0))
    (.put       buf (byte 0))
    (.array buf)))

(defn- simulate-push! [& opts]
  (#'link/on-link-state-push (apply make-link-payload opts)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (core/start! :bpm 120)
    ;; Clear any stale link state from previous tests
    (when-let [s @(system-ref)]
      (swap! s dissoc :link-state :link-timeline))
    (reset! (hooks-atom) {})
    (t)
    (reset! (hooks-atom) {})
    (core/stop!)))

;; ---------------------------------------------------------------------------
;; Link state parsing
;; ---------------------------------------------------------------------------

(deftest parse-link-state-populates-system
  (testing "0x80 push sets :link-state and :link-timeline in system state"
    (simulate-push! :bpm 140.0 :anchor-beat 4.0 :anchor-us 1000000
                    :peers 2 :playing true)
    (is (= 140.0 (link/bpm)))
    (is (= 2     (link/peers)))
    (is (true?   (link/playing?)))
    (is (link/active?))))

(deftest link-active-false-before-push
  (testing "active? returns false when no push has been received"
    ;; fresh start — no link-timeline yet
    (is (false? (link/active?)))))

;; ---------------------------------------------------------------------------
;; Transport-change hooks
;; ---------------------------------------------------------------------------

(deftest transport-hook-fires-on-playing-transition
  (testing "on-transport-change! hook fires when playing changes false→true"
    (let [received (atom nil)]
      (link/on-transport-change! ::test-hook (fn [p] (reset! received p)))
      ;; First push: not playing
      (simulate-push! :playing false)
      ;; Second push: playing changes to true
      (simulate-push! :playing true)
      (is (true? @received)))))

(deftest transport-hook-fires-on-stopped-transition
  (testing "hook fires when playing changes true→false"
    (let [received (atom :not-fired)]
      (simulate-push! :playing true)  ; set initial state
      (link/on-transport-change! ::test-hook (fn [p] (reset! received p)))
      (simulate-push! :playing false)
      (is (false? @received)))))

(deftest transport-hook-not-fired-when-state-unchanged
  (testing "hook does NOT fire when playing state stays the same"
    (let [call-count (atom 0)]
      (simulate-push! :playing false)
      (link/on-transport-change! ::test-hook (fn [_] (swap! call-count inc)))
      ;; Same state again
      (simulate-push! :playing false)
      (is (zero? @call-count)))))

(deftest multiple-hooks-all-fire
  (testing "all registered hooks fire on a transport transition"
    (let [r1 (atom nil) r2 (atom nil)]
      (link/on-transport-change! ::hook-1 (fn [p] (reset! r1 p)))
      (link/on-transport-change! ::hook-2 (fn [p] (reset! r2 p)))
      (simulate-push! :playing false)
      (simulate-push! :playing true)
      (is (true? @r1))
      (is (true? @r2)))))

(deftest remove-transport-hook-stops-firing
  (testing "remove-transport-hook! prevents the hook from firing"
    (let [r (atom :not-fired)]
      (link/on-transport-change! ::removable (fn [p] (reset! r p)))
      (link/remove-transport-hook! ::removable)
      (simulate-push! :playing false)
      (simulate-push! :playing true)
      (is (= :not-fired @r)))))

(deftest hook-exception-does-not-crash-push
  (testing "a hook that throws does not prevent other hooks from firing"
    (let [r (atom :not-fired)]
      (link/on-transport-change! ::bad-hook  (fn [_] (throw (ex-info "boom" {}))))
      (link/on-transport-change! ::good-hook (fn [p] (reset! r p)))
      (simulate-push! :playing false)
      (simulate-push! :playing true)
      (is (true? @r)))))

;; ---------------------------------------------------------------------------
;; on-transport-change! / remove-transport-hook! / transport-hooks
;; ---------------------------------------------------------------------------

(deftest register-hook-adds-to-registry
  (link/on-transport-change! ::my-hook (fn [_] nil))
  (is (contains? (link/transport-hooks) ::my-hook)))

(deftest remove-hook-removes-from-registry
  (link/on-transport-change! ::rm-hook (fn [_] nil))
  (link/remove-transport-hook! ::rm-hook)
  (is (not (contains? (link/transport-hooks) ::rm-hook))))

(deftest reregister-hook-replaces-previous
  (let [v (atom 0)]
    (link/on-transport-change! ::shared (fn [_] (swap! v + 1)))
    (link/on-transport-change! ::shared (fn [_] (swap! v + 10)))
    (simulate-push! :playing false)
    (simulate-push! :playing true)
    ;; only the second registration should fire
    (is (= 10 @v))))

;; ---------------------------------------------------------------------------
;; BPM push — core/set-bpm! propagation (tested without live sidecar)
;; ---------------------------------------------------------------------------

(deftest set-bpm-does-not-crash-when-link-inactive
  (testing "core/set-bpm! is safe when Link is not active"
    ;; No link-timeline set — link/active? returns false — no IPC call attempted
    (is (nil? (core/set-bpm! 130.0)))
    (is (= 130.0 (core/get-bpm)))))
