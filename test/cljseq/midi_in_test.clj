; SPDX-License-Identifier: EPL-2.0
(ns cljseq.midi-in-test
  "Unit tests for cljseq.midi-in — MIDI input routing to the ctrl tree.

  All tests use -dispatch-for-test! to simulate inbound MIDI messages,
  avoiding the need for physical MIDI hardware."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [cljseq.core   :as core]
            [cljseq.ctrl   :as ctrl]
            [cljseq.device :as device]
            [cljseq.midi-in :as midi-in]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Helpers — inline device maps for test devices
;; ---------------------------------------------------------------------------

(def ^:private keystep-map
  {:device/id   :test/keystep
   :device/role :controller
   :midi/channel 1
   :cljseq/bind-sources
   {:notes       {:source :note-on            :yields :pitch-midi}
    :velocity    {:source :note-on            :yields :velocity-0-127}
    :touch-strip {:source :channel-pressure   :yields :pressure-0-127}
    :sustain     {:source :cc-64              :yields :boolean}}})

(def ^:private NOTE_ON          144)
(def ^:private CHANNEL_PRESSURE 208)
(def ^:private CONTROL_CHANGE   176)

;; ---------------------------------------------------------------------------
;; note-on → :pitch-midi
;; ---------------------------------------------------------------------------

(deftest note-on-routes-pitch-test
  (testing "note-on dispatches pitch-midi to bound ctrl node"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/pitch] :type :int :value 0)
    (device/device-bind! [:test/pitch] {:device :test/keystep :source :notes})
    (midi-in/-dispatch-for-test! :test/keystep 1 NOTE_ON 60 80)
    (is (= 60 (ctrl/get [:test/pitch])) "note number routed to node")))

;; ---------------------------------------------------------------------------
;; note-on → :velocity-0-127
;; ---------------------------------------------------------------------------

(deftest note-on-routes-velocity-test
  (testing "note-on dispatches velocity to bound ctrl node"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/velocity] :type :int :value 0)
    (device/device-bind! [:test/velocity] {:device :test/keystep :source :velocity})
    (midi-in/-dispatch-for-test! :test/keystep 1 NOTE_ON 60 100)
    (is (= 100 (ctrl/get [:test/velocity])) "velocity routed to node")))

;; ---------------------------------------------------------------------------
;; channel-pressure → :pressure-0-127
;; ---------------------------------------------------------------------------

(deftest channel-pressure-routes-pressure-test
  (testing "channel-pressure dispatches pressure value to bound ctrl node"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/pressure] :type :int :value 0)
    (device/device-bind! [:test/pressure] {:device :test/keystep :source :touch-strip})
    (midi-in/-dispatch-for-test! :test/keystep 1 CHANNEL_PRESSURE 90 0)
    (is (= 90 (ctrl/get [:test/pressure])) "pressure routed to node")))

;; ---------------------------------------------------------------------------
;; cc-64 → :boolean
;; ---------------------------------------------------------------------------

(deftest cc64-routes-boolean-on-test
  (testing "CC 64 > 0 dispatches true to bound ctrl node"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/sustain-on] :type :bool :value false)
    (device/device-bind! [:test/sustain-on] {:device :test/keystep :source :sustain})
    (midi-in/-dispatch-for-test! :test/keystep 1 CONTROL_CHANGE 64 127)
    (is (= true (ctrl/get [:test/sustain-on])) "sustain on")))

(deftest cc64-routes-boolean-off-test
  (testing "CC 64 = 0 dispatches false to bound ctrl node"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/sustain-off] :type :bool :value true)
    (device/device-bind! [:test/sustain-off] {:device :test/keystep :source :sustain})
    (midi-in/-dispatch-for-test! :test/keystep 1 CONTROL_CHANGE 64 0)
    (is (= false (ctrl/get [:test/sustain-off])) "sustain off")))

;; ---------------------------------------------------------------------------
;; Channel mismatch — should not dispatch
;; ---------------------------------------------------------------------------

(deftest wrong-channel-no-dispatch-test
  (testing "message on wrong channel does not update ctrl node"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/ch-pitch] :type :int :value 0)
    (device/device-bind! [:test/ch-pitch] {:device :test/keystep :source :notes})
    ;; device is on channel 1; send on channel 2
    (midi-in/-dispatch-for-test! :test/keystep 2 NOTE_ON 60 80)
    (is (= 0 (ctrl/get [:test/ch-pitch])) "value unchanged for wrong channel")))

;; ---------------------------------------------------------------------------
;; Device mismatch — should not dispatch
;; ---------------------------------------------------------------------------

(deftest wrong-device-no-dispatch-test
  (testing "message for unknown device does not update ctrl node"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/dev-pitch] :type :int :value 0)
    (device/device-bind! [:test/dev-pitch] {:device :test/keystep :source :notes})
    (midi-in/-dispatch-for-test! :test/other-device 1 NOTE_ON 60 80)
    (is (= 0 (ctrl/get [:test/dev-pitch])) "value unchanged for wrong device")))

;; ---------------------------------------------------------------------------
;; Multiple bindings — note-on updates both pitch and velocity nodes
;; ---------------------------------------------------------------------------

(deftest note-on-updates-multiple-nodes-test
  (testing "single note-on updates all matching bindings simultaneously"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/multi-pitch] :type :int :value 0)
    (ctrl/defnode! [:test/multi-vel]   :type :int :value 0)
    (device/device-bind! [:test/multi-pitch] {:device :test/keystep :source :notes}    :priority 20)
    (device/device-bind! [:test/multi-vel]   {:device :test/keystep :source :velocity} :priority 20)
    (midi-in/-dispatch-for-test! :test/keystep 1 NOTE_ON 48 110)
    (is (= 48  (ctrl/get [:test/multi-pitch])) "pitch updated")
    (is (= 110 (ctrl/get [:test/multi-vel]))   "velocity updated")))

;; ---------------------------------------------------------------------------
;; bindings-by-type — ctrl helper
;; ---------------------------------------------------------------------------

(deftest bindings-by-type-returns-input-bindings-test
  (testing "ctrl/bindings-by-type finds :midi-device-input bindings in the tree"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/bbt-pitch] :type :int :value 0)
    (device/device-bind! [:test/bbt-pitch] {:device :test/keystep :source :notes})
    (let [found (ctrl/bindings-by-type :midi-device-input)]
      (is (seq found) "at least one binding found")
      (is (some #(= [:test/bbt-pitch] (first %)) found)
          "bound path present in results"))))

(deftest bindings-by-type-empty-for-unknown-type-test
  (testing "ctrl/bindings-by-type returns empty for a type with no bindings"
    (is (empty? (ctrl/bindings-by-type :no-such-type)))))

;; ---------------------------------------------------------------------------
;; open-inputs inspection
;; ---------------------------------------------------------------------------

(deftest open-inputs-empty-initially-test
  (testing "open-inputs returns empty set when no ports are open"
    (is (empty? (midi-in/open-inputs)))))

;; ---------------------------------------------------------------------------
;; close-all-inputs! clears state
;; ---------------------------------------------------------------------------

(deftest close-all-inputs-no-op-when-empty-test
  (testing "close-all-inputs! is a no-op with no open inputs"
    (is (nil? (midi-in/close-all-inputs!)))))
