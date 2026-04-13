; SPDX-License-Identifier: EPL-2.0
(ns cljseq.midi-in-test
  "Unit tests for cljseq.midi-in — MIDI input routing to the ctrl tree
  and direct note handler dispatch.

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

;; Device with a map-valued :midi/channel (like T-1)
(def ^:private multichan-map
  {:device/id   :test/multichan
   :device/role :sequencer
   :midi/channel {:default :track-number :range [1 16] :per-track true}
   :cljseq/bind-sources {}})

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

;; ---------------------------------------------------------------------------
;; Note handler registration and dispatch
;; ---------------------------------------------------------------------------

(deftest register-note-handler-receives-note-on
  (testing "registered handler called on Note On"
    (device/defdevice :test/t1 multichan-map)
    (let [received (atom nil)]
      (midi-in/register-note-handler! :test/t1 1
        (fn [note vel] (reset! received {:note note :vel vel})))
      (midi-in/-dispatch-for-test! :test/t1 1 NOTE_ON 60 100)
      (is (= {:note 60 :vel 100} @received) "handler received note and velocity")
      (midi-in/unregister-note-handler! :test/t1 1))))

(deftest note-handler-not-called-on-note-off
  (testing "handler NOT called for Note On with velocity 0 (note-off convention)"
    (device/defdevice :test/t1 multichan-map)
    (let [called (atom false)]
      (midi-in/register-note-handler! :test/t1 1
        (fn [_note _vel] (reset! called true)))
      (midi-in/-dispatch-for-test! :test/t1 1 NOTE_ON 60 0)  ; vel=0 = note off
      (is (false? @called) "handler not called for vel=0")
      (midi-in/unregister-note-handler! :test/t1 1))))

(deftest note-handler-wrong-channel-not-called
  (testing "handler not called when channel does not match"
    (device/defdevice :test/t1 multichan-map)
    (let [called (atom false)]
      (midi-in/register-note-handler! :test/t1 2  ; registered on ch 2
        (fn [_note _vel] (reset! called true)))
      (midi-in/-dispatch-for-test! :test/t1 1 NOTE_ON 60 80)  ; arrives on ch 1
      (is (false? @called) "ch 2 handler not triggered by ch 1 message")
      (midi-in/unregister-note-handler! :test/t1 2))))

(deftest note-handler-all-channel-wildcard
  (testing ":all wildcard handler matches any channel"
    (device/defdevice :test/t1 multichan-map)
    (let [notes (atom [])]
      (midi-in/register-note-handler! :test/t1 :all
        (fn [note _vel] (swap! notes conj note)))
      (midi-in/-dispatch-for-test! :test/t1 1  NOTE_ON 60 80)
      (midi-in/-dispatch-for-test! :test/t1 3  NOTE_ON 64 90)
      (midi-in/-dispatch-for-test! :test/t1 10 NOTE_ON 67 70)
      (is (= [60 64 67] @notes) "all three channels hit the :all handler")
      (midi-in/unregister-note-handler! :test/t1 :all))))

(deftest unregister-note-handler-stops-dispatch
  (testing "unregister-note-handler! stops further calls"
    (device/defdevice :test/t1 multichan-map)
    (let [count (atom 0)]
      (midi-in/register-note-handler! :test/t1 1
        (fn [_note _vel] (swap! count inc)))
      (midi-in/-dispatch-for-test! :test/t1 1 NOTE_ON 60 80)
      (midi-in/unregister-note-handler! :test/t1 1)
      (midi-in/-dispatch-for-test! :test/t1 1 NOTE_ON 62 80)
      (is (= 1 @count) "only first dispatch counted"))))

(deftest register-note-handler-nil-removes
  (testing "passing nil as handler removes the registration"
    (device/defdevice :test/t1 multichan-map)
    (let [count (atom 0)]
      (midi-in/register-note-handler! :test/t1 1
        (fn [_note _vel] (swap! count inc)))
      (midi-in/register-note-handler! :test/t1 1 nil)  ; remove via nil
      (midi-in/-dispatch-for-test! :test/t1 1 NOTE_ON 60 80)
      (is (= 0 @count) "nil-registered handler never called"))))

;; ---------------------------------------------------------------------------
;; map-valued :midi/channel — dispatch uses actual message channel
;; ---------------------------------------------------------------------------

(deftest multichannel-device-dispatches-on-actual-channel
  (testing "device with map :midi/channel routes note handlers by message channel"
    (device/defdevice :test/t1 multichan-map)
    (let [ch1-notes (atom [])
          ch3-notes (atom [])]
      (midi-in/register-note-handler! :test/t1 1
        (fn [note _vel] (swap! ch1-notes conj note)))
      (midi-in/register-note-handler! :test/t1 3
        (fn [note _vel] (swap! ch3-notes conj note)))
      (midi-in/-dispatch-for-test! :test/t1 1 NOTE_ON 60 80)
      (midi-in/-dispatch-for-test! :test/t1 3 NOTE_ON 64 80)
      (is (= [60] @ch1-notes) "ch 1 handler got ch 1 note")
      (is (= [64] @ch3-notes) "ch 3 handler got ch 3 note")
      (midi-in/unregister-note-handler! :test/t1 1)
      (midi-in/unregister-note-handler! :test/t1 3))))

;; ---------------------------------------------------------------------------
;; ctrl routing coexists with note handlers
;; ---------------------------------------------------------------------------

(deftest ctrl-and-note-handler-both-fire
  (testing "ctrl binding and note handler both receive the same Note On"
    (device/defdevice :test/keystep keystep-map)
    (ctrl/defnode! [:test/coexist-pitch] :type :int :value 0)
    (device/device-bind! [:test/coexist-pitch] {:device :test/keystep :source :notes})
    (let [handler-note (atom nil)]
      (midi-in/register-note-handler! :test/keystep 1
        (fn [note _vel] (reset! handler-note note)))
      (midi-in/-dispatch-for-test! :test/keystep 1 NOTE_ON 55 90)
      (is (= 55 (ctrl/get [:test/coexist-pitch])) "ctrl tree updated")
      (is (= 55 @handler-note) "note handler also called")
      (midi-in/unregister-note-handler! :test/keystep 1))))
