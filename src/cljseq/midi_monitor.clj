; SPDX-License-Identifier: EPL-2.0
(ns cljseq.midi-monitor
  "MIDI message capture via javax.sound.midi.

  Opens a named MIDI input port and accumulates received ShortMessages into
  an atom. Useful for integration testing (verify notes arrive) and REPL-based
  debugging (watch what the sidecar is actually sending).

  ## Quick REPL usage
    (def m (open-monitor \"MainStage\"))
    ;; ... play some notes ...
    (recent-messages m 10)
    (close-monitor m)

  ## In tests
    (def m (open-monitor \"MainStage\"))
    (play! :C4 1/4)
    (is (await-note-on m 60 2000))   ; wait up to 2s for middle C
    (close-monitor m)

  macOS: IAC Driver ports appear as \"MainStage\", \"Bus 1\", etc.
         The input side (tx != 0) is what we listen on.
  Linux: ALSA virtual ports appear as \"Midi Through\", etc."
  (:require [clojure.string :as str])
  (:import [javax.sound.midi MidiSystem ShortMessage Receiver]))

;; ---------------------------------------------------------------------------
;; Device discovery
;; ---------------------------------------------------------------------------

(defn list-inputs
  "Return the names of all available MIDI input devices (those that can
  transmit messages to the JVM)."
  []
  (->> (MidiSystem/getMidiDeviceInfo)
       (keep (fn [info]
               (let [d (MidiSystem/getMidiDevice info)]
                 (when (not= 0 (.getMaxTransmitters d))
                   (.getName info)))))
       (remove #{"Real Time Sequencer"})))

(defn- find-input-device
  "Return the first MIDI input MidiDevice whose name contains `name-fragment`."
  [name-fragment]
  (->> (MidiSystem/getMidiDeviceInfo)
       (keep (fn [info]
               (let [d (MidiSystem/getMidiDevice info)]
                 (when (and (not= 0 (.getMaxTransmitters d))
                            (str/includes? (.getName info) name-fragment))
                   d))))
       first))

;; ---------------------------------------------------------------------------
;; Monitor lifecycle
;; ---------------------------------------------------------------------------

(defn open-monitor
  "Open a MIDI input monitor on the device whose name contains `name-fragment`.

  Returns a monitor map:
    :device      — the open MidiDevice
    :transmitter — the active Transmitter
    :messages    — atom of captured message maps

  Each captured message has keys:
    :command  — status byte & 0xF0 (e.g. 0x90 = NoteOn, 0x80 = NoteOff)
    :channel  — MIDI channel 1–16
    :data1    — note number or CC number
    :data2    — velocity or CC value
    :ts       — system timestamp from javax.sound.midi (-1 if unavailable)

  Throws ex-info if no matching device is found."
  [name-fragment]
  (let [device (find-input-device name-fragment)]
    (when-not device
      (throw (ex-info (str "No MIDI input found matching: " (pr-str name-fragment))
                      {:available (list-inputs)})))
    (.open device)
    (let [messages (atom [])
          recv     (reify Receiver
                     (send [_ msg ts]
                       (when (instance? ShortMessage msg)
                         (swap! messages conj
                                {:command (.getCommand msg)
                                 :channel (inc (.getChannel msg))
                                 :data1   (.getData1 msg)
                                 :data2   (.getData2 msg)
                                 :ts      ts})))
                     (close [_] nil))
          tx       (.getTransmitter device)]
      (.setReceiver tx recv)
      {:device      device
       :transmitter tx
       :messages    messages})))

(defn close-monitor
  "Release the MIDI monitor's transmitter and device."
  [{:keys [device transmitter]}]
  (try (.close transmitter) (catch Exception _))
  (try (.close device)      (catch Exception _))
  nil)

;; ---------------------------------------------------------------------------
;; Inspection helpers
;; ---------------------------------------------------------------------------

(defn all-messages
  "Return all captured messages."
  [{:keys [messages]}]
  @messages)

(defn recent-messages
  "Return the last `n` captured messages."
  [{:keys [messages]} n]
  (take-last n @messages))

(defn clear-messages!
  "Clear the captured message history."
  [{:keys [messages]}]
  (reset! messages [])
  nil)

;; ---------------------------------------------------------------------------
;; Assertion helpers
;; ---------------------------------------------------------------------------

(defn await-message
  "Poll `messages` atom until a message matching `pred` arrives or
  `timeout-ms` elapses. Returns the matching message or nil on timeout."
  [messages pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [msg (first (filter pred @messages))]
        msg
        (when (< (System/currentTimeMillis) deadline)
          (Thread/sleep 10)
          (recur))))))

(defn await-note-on
  "Wait up to `timeout-ms` for a NoteOn with `note` (MIDI number) on any channel.
  Returns the message map or nil on timeout."
  ([monitor note timeout-ms]
   (await-message (:messages monitor)
                  #(and (= 0x90 (:command %))
                        (= note  (:data1 %))
                        (pos?    (:data2 %)))
                  timeout-ms)))

(defn await-note-off
  "Wait up to `timeout-ms` for a NoteOff (or NoteOn vel=0) for `note`."
  ([monitor note timeout-ms]
   (await-message (:messages monitor)
                  #(and (= note (:data1 %))
                        (or (= 0x80 (:command %))
                            (and (= 0x90 (:command %))
                                 (= 0 (:data2 %)))))
                  timeout-ms)))
