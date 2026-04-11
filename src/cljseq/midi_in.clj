; SPDX-License-Identifier: EPL-2.0
(ns cljseq.midi-in
  "cljseq MIDI input listener — routes inbound MIDI to the ctrl tree and/or
  direct note handlers.

  Opens a JVM MIDI input port by partial device name and attaches a Receiver
  that dispatches on each inbound message:
    1. ctrl tree  — :midi-device-input bindings (CC, channel pressure, note-on)
    2. note handlers — registered per [device-id channel] via register-note-handler!

  ## Quick start — ctrl routing
    (defdevice :arturia/keystep (load-device-map \"devices/arturia-keystep.edn\"))
    (device-bind! [:filter/cutoff] {:device :arturia/keystep :source :touch-strip})
    (midi-in/open-input! :arturia/keystep)

  ## Quick start — direct note handler (T-1, any MIDI keyboard → play!)
    (defdevice :torso/t1 (load-device-map \"devices/torso-t1.edn\"))
    (midi-in/register-note-handler! :torso/t1 1
      (fn [note vel] (play! {:synth :klank-bell :pitch/midi note :mod/velocity vel})))
    (midi-in/open-input! :torso/t1)

  ## Cleanup
    (midi-in/close-input! :arturia/keystep)
    (midi-in/close-all-inputs!)

  ## Notes on :midi/channel in device maps
    :midi/channel may be an integer (e.g. 1) or a map
    (e.g. {:default :track-number :range [1 16]}).
    When it is a map the Receiver uses the actual message channel from the
    hardware — correct for multi-channel devices like the Torso T-1.

  ## Supported :midi-source types
    :note-on          — MIDI Note On (0x90); data1=note, data2=velocity
    :channel-pressure — MIDI Channel Pressure (0xD0); data1=pressure
    :cc-<n>           — MIDI Control Change (0xB0) CC number n
                        (Phase 1 supports :cc-64 only — sustain pedal)

  ## :yields extraction
    :pitch-midi      — data1 (note number, 0–127)
    :velocity-0-127  — data2 (velocity, 0–127)
    :pressure-0-127  — data1 (channel pressure, 0–127)
    :boolean         — (> data2 0)

  Key design decisions: R&R §28.8 (MIDI input routing)."
  (:require [clojure.string :as str]
            [cljseq.ctrl    :as ctrl]
            [cljseq.device  :as device])
  (:import  [javax.sound.midi MidiSystem ShortMessage Receiver]))

;; ---------------------------------------------------------------------------
;; MIDI command constants (ShortMessage)
;; ---------------------------------------------------------------------------

(def ^:private NOTE_ON          144) ; ShortMessage/NOTE_ON    0x90
(def ^:private CHANNEL_PRESSURE 208) ; ShortMessage/CHANNEL_PRESSURE 0xD0
(def ^:private CONTROL_CHANGE   176) ; ShortMessage/CONTROL_CHANGE  0xB0

;; ---------------------------------------------------------------------------
;; Active inputs: {device-id {:jvm-device MidiDevice :transmitter Transmitter}}
;; ---------------------------------------------------------------------------

(defonce ^:private inputs (atom {}))

;; ---------------------------------------------------------------------------
;; Note handlers: {[device-id channel] (fn [note vel] ...)}
;;
;; channel is 1-based. Use :all to match any channel from that device.
;; ---------------------------------------------------------------------------

(defonce ^:private note-handlers (atom {}))

(defn register-note-handler!
  "Register a function to be called on every Note On from `device-id` on `channel`.

  `device-id` — keyword registered with defdevice
  `channel`   — 1-based MIDI channel integer, or :all to match any channel
  `handler`   — (fn [note vel]) where note and vel are 0–127 integers

  Passing nil as handler removes the registration.

  Example:
    (midi-in/register-note-handler! :torso/t1 1
      (fn [note vel]
        (play! {:synth :klank-bell :pitch/midi note :mod/velocity vel})))"
  [device-id channel handler]
  (if handler
    (swap! note-handlers assoc [device-id channel] handler)
    (swap! note-handlers dissoc [device-id channel])))

(defn unregister-note-handler!
  "Remove the note handler for `device-id` on `channel`."
  [device-id channel]
  (swap! note-handlers dissoc [device-id channel]))

;; ---------------------------------------------------------------------------
;; Source matching and value extraction
;; ---------------------------------------------------------------------------

(defn- matches-source?
  "True if `midi-source` keyword (from :cljseq/bind-sources) describes
  this MIDI command + data1 pair."
  [midi-source command data1]
  (case midi-source
    :note-on          (= command NOTE_ON)
    :channel-pressure (= command CHANNEL_PRESSURE)
    :cc-64            (and (= command CONTROL_CHANGE) (= (int data1) 64))
    false))

(defn- extract-yield
  "Extract a Clojure value from MIDI data bytes based on the :yields key.

  Note-on:          data1=note, data2=velocity
  Channel pressure: data1=pressure, data2=0
  Control change:   data1=CC-number, data2=CC-value"
  [yields data1 data2]
  (case yields
    :pitch-midi      (int data1)
    :velocity-0-127  (int data2)
    :pressure-0-127  (int data1)
    :boolean         (> (int data2) 0)   ; CC value is in data2
    (int data1)))

;; ---------------------------------------------------------------------------
;; Message dispatch
;; ---------------------------------------------------------------------------

(defn- dispatch-message!
  "Route one inbound MIDI message to:
    1. All matching :midi-device-input ctrl bindings (CC, pressure, note-on)
    2. Registered note handlers for this [device-id channel] on Note On"
  [device-id channel command data1 data2]
  ;; 1. ctrl tree routing
  (doseq [[path binding] (ctrl/bindings-by-type :midi-device-input)]
    (when (and (= device-id (:device binding))
               (= (int channel) (int (:channel binding)))
               (matches-source? (:midi-source binding) command data1))
      (ctrl/set! path (extract-yield (:yields binding) data1 data2))))
  ;; 2. Note handler routing — Note On with velocity > 0
  (when (and (= command NOTE_ON) (> (int data2) 0))
    (let [handlers @note-handlers]
      (when-let [h (or (get handlers [device-id (int channel)])
                       (get handlers [device-id :all]))]
        (try (h (int data1) (int data2))
             (catch Exception e
               (binding [*out* *err*]
                 (println "[midi-in] note handler error:" e))))))))

;; ---------------------------------------------------------------------------
;; JVM MIDI device discovery
;; ---------------------------------------------------------------------------

(defn- find-jvm-device
  "Return the first JVM MIDI input device (maxTransmitters != 0) whose name
  contains `name-hint` (case-insensitive), or nil if none found."
  [name-hint]
  (let [hint-lc (str/lower-case name-hint)]
    (->> (MidiSystem/getMidiDeviceInfo)
         (filter #(str/includes? (str/lower-case (.getName ^javax.sound.midi.MidiDevice$Info %)) hint-lc))
         (map #(MidiSystem/getMidiDevice ^javax.sound.midi.MidiDevice$Info %))
         (filter #(not= 0 (.getMaxTransmitters ^javax.sound.midi.MidiDevice %)))
         first)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn open-input!
  "Open a JVM MIDI input port and route its messages to the ctrl tree.

  `device-id` — keyword registered with defdevice (e.g. :arturia/keystep)

  Options:
    :name-hint — partial JVM MIDI device name to match (default: :device/name
                 from the device map, e.g. \"Arturia KeyStep\")

  On each incoming MIDI message the receiver calls ctrl/set! for every
  :midi-device-input binding whose :device, :channel, and :midi-source match.

  Re-calling with the same device-id is a no-op if the port is already open.
  Throws ex-info if the device is not registered or no JVM MIDI device matches.

  Example:
    (open-input! :arturia/keystep)
    (open-input! :arturia/keystep :name-hint \"KeyStep MIDI\")"
  [device-id & {:keys [name-hint]}]
  (when-not (get @inputs device-id)
    (let [reg (device/get-device device-id)]
      (when-not reg
        (throw (ex-info "midi-in/open-input!: device not registered"
                        {:device device-id})))
      (let [hint    (or name-hint (get-in reg [:map :device/name] (name device-id)))
            ;; :midi/channel may be an integer OR a map (e.g. T-1's per-track map).
            ;; When it is a map, channel-from-map? is true and we use the actual
            ;; message channel from the hardware (set in the Receiver below).
            ch-cfg  (get-in reg [:map :midi/channel] 1)
            fixed-ch (when (integer? ch-cfg) (int ch-cfg))
            jvm-dev (find-jvm-device hint)]
        (when-not jvm-dev
          (throw (ex-info "midi-in/open-input!: no MIDI input device found"
                          {:device    device-id
                           :name-hint hint
                           :available (->> (MidiSystem/getMidiDeviceInfo)
                                           (mapv #(.getName ^javax.sound.midi.MidiDevice$Info %)))})))
        (.open ^javax.sound.midi.MidiDevice jvm-dev)
        (let [xmit (.getTransmitter ^javax.sound.midi.MidiDevice jvm-dev)
              recv (reify Receiver
                     (send [_ msg _ts]
                       (when (instance? ShortMessage msg)
                         ;; Use actual message channel when device map has a
                         ;; non-integer :midi/channel (multi-channel devices).
                         (let [msg-ch (inc (.getChannel ^ShortMessage msg))
                               ch     (or fixed-ch msg-ch)]
                           (dispatch-message! device-id ch
                                              (.getCommand ^ShortMessage msg)
                                              (.getData1   ^ShortMessage msg)
                                              (.getData2   ^ShortMessage msg)))))
                     (close [_] nil))]
          (.setReceiver ^javax.sound.midi.Transmitter xmit recv)
          (swap! inputs assoc device-id {:jvm-device jvm-dev :transmitter xmit})))))
  device-id)

(defn close-input!
  "Close the MIDI input port for `device-id`. No-op if not open."
  [device-id]
  (when-let [{:keys [jvm-device transmitter]} (get @inputs device-id)]
    (.close ^javax.sound.midi.Transmitter transmitter)
    (.close ^javax.sound.midi.MidiDevice  jvm-device)
    (swap! inputs dissoc device-id))
  nil)

(defn close-all-inputs!
  "Close all open MIDI input ports. Called by cljseq.core/stop!."
  []
  (doseq [id (keys @inputs)]
    (close-input! id))
  nil)

(defn open-inputs
  "Return a set of currently open device-ids. Useful for REPL inspection."
  []
  (set (keys @inputs)))

;; ---------------------------------------------------------------------------
;; Test hook
;; ---------------------------------------------------------------------------

(defn -dispatch-for-test!
  "Simulate a received MIDI message for testing purposes.

  Calls the same dispatch path as a real hardware message. Useful for unit
  testing routing logic without physical MIDI hardware.

  `device-id` — registered device keyword
  `channel`   — MIDI channel (1-based, matching the device map's :midi/channel)
  `command`   — MIDI status command byte (144=note-on, 176=CC, 208=ch-pressure)
  `data1`     — first data byte  (note number, CC number, or pressure)
  `data2`     — second data byte (velocity for note-on; 0 for single-data msgs)"
  [device-id channel command data1 data2]
  (dispatch-message! device-id channel command data1 data2))
