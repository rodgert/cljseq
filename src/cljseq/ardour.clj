; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ardour
  "Ardour DAW integration via OSC (Phase 1).

  Provides transport control, session management, and the 'ubiquitous capture'
  workflow. Communicates with a running Ardour instance over OSC/UDP.

  ## Prerequisites

  In Ardour: Edit → Preferences → Control Surfaces → OSC → Enable.
  Default OSC port: 3819. Set 'Port Mode' to Manual, 'Reply Manual Port' as
  needed. No feedback configuration is required for this namespace.

  ## Setup (call once per session)

    (ardour/connect!)                       ; localhost, port 3819
    (ardour/connect! \"192.168.1.42\")      ; remote host, default port
    (ardour/connect! \"192.168.1.42\" 3820) ; remote with custom port

  ## Transport

    (ardour/transport-play!)
    (ardour/transport-stop!)
    (ardour/transport-record! true)   ; arm global record enable
    (ardour/transport-record! false)  ; disarm
    (ardour/goto-start!)
    (ardour/add-marker!)              ; mark current position
    (ardour/save!)

  ## Ubiquitous Capture

  Designed for use with a pre-armed 'capture template' Ardour session
  (all relevant tracks already record-enabled). `capture!` arms global
  record and rolls transport; Ardour writes audio and MIDI continuously.

    (ardour/capture!)          ; arm + roll → prints session ID
    (ardour/capture-stop!)     ; stop transport + save session
    (ardour/capture-discard!)  ; stop without saving (false start)
    (ardour/capture-status)    ; {:recording? true :started-at <Instant> ...}

  ## Track control (optional)

    (ardour/track-recenable! 1 true)   ; record-enable track by ssid (1-based)
    (ardour/track-mute!      1 true)
    (ardour/track-gain!      1 1.0)    ; unity gain
    (ardour/track-fader!     1 0.8)    ; fader 0.0–1.0

  ## Mixbus compatibility

  Mixbus uses the same Ardour OSC protocol. All functions work identically
  with Mixbus — just point connect! at the Mixbus instance.

  Key design decision: §studio-vision 'just another device in the tree'.
  When :osc-path binding type is implemented, Ardour/Mixbus will become
  a defdevice with osc-path bindings. This namespace is the precursor."
  (:require [clojure.string :as str]
            [cljseq.osc     :as osc])
  (:import  [java.time Instant]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private ardour-state
  (atom {:host    "127.0.0.1"
         :port    3819
         :capture {:recording? false
                   :started-at nil
                   :session-id nil}}))

;; ---------------------------------------------------------------------------
;; Internal send helper
;; ---------------------------------------------------------------------------

(defn- send!
  "Send an OSC message to the configured Ardour instance.
  All public functions in this namespace call through here."
  [address & args]
  (let [{:keys [host port]} @ardour-state]
    (apply osc/osc-send! host port address args)))

;; ---------------------------------------------------------------------------
;; Connection configuration
;; ---------------------------------------------------------------------------

(defn connect!
  "Configure the Ardour OSC endpoint.

  Ardour OSC must be enabled in Preferences → Control Surfaces → OSC.

  Example:
    (ardour/connect!)                       ; localhost port 3819
    (ardour/connect! \"192.168.1.42\")      ; remote host, default port
    (ardour/connect! \"192.168.1.42\" 3820) ; remote with custom port"
  ([] (connect! "127.0.0.1" 3819))
  ([host] (connect! host 3819))
  ([host port]
   (swap! ardour-state assoc :host host :port port)
   (println (str "[ardour] configured → " host ":" port))
   nil))

(defn connected?
  "Return true if connect! has been called (endpoint is configured)."
  []
  (some? (:host @ardour-state)))

(defn ardour-host [] (:host @ardour-state))
(defn ardour-port [] (:port @ardour-state))

;; ---------------------------------------------------------------------------
;; Transport control
;; ---------------------------------------------------------------------------

(defn transport-play!
  "Start Ardour's transport (equivalent to pressing Play).
  If global record is armed, this begins recording."
  []
  (send! "/transport_play")
  nil)

(defn transport-stop!
  "Stop Ardour's transport."
  []
  (send! "/transport_stop")
  nil)

(defn transport-record!
  "Arm or disarm Ardour's global record enable.

  `arm?` — true to arm (the record button lights), false to disarm.

  Note: arming record does not start recording — call transport-play! to roll."
  [arm?]
  (send! "/transport_record" (int (if arm? 1 0)))
  nil)

(defn goto-start!
  "Locate Ardour's playhead to the session start."
  []
  (send! "/goto_start")
  nil)

(defn goto-end!
  "Locate Ardour's playhead to the session end."
  []
  (send! "/goto_end")
  nil)

(defn loop-toggle!
  "Toggle Ardour's loop playback mode."
  []
  (send! "/loop_toggle")
  nil)

(defn add-marker!
  "Add a location marker at Ardour's current playhead position."
  []
  (send! "/add_marker")
  nil)

;; ---------------------------------------------------------------------------
;; Session management
;; ---------------------------------------------------------------------------

(defn save!
  "Save the current Ardour session to disk."
  []
  (send! "/save_state")
  nil)

;; ---------------------------------------------------------------------------
;; Track / route control (ssid = surface strip ID, 1-based)
;; ---------------------------------------------------------------------------

(defn track-recenable!
  "Enable or disable recording on a specific track.

  `ssid` — track index, 1-based (matches Ardour's strip order in the mixer)
  `arm?` — true to arm, false to disarm

  Example:
    (ardour/track-recenable! 1 true)   ; arm track 1
    (ardour/track-recenable! 3 false)  ; disarm track 3"
  [ssid arm?]
  (send! "/route/recenable" (int ssid) (int (if arm? 1 0)))
  nil)

(defn track-mute!
  "Mute or unmute a track.

  `ssid` — track index, 1-based"
  [ssid mute?]
  (send! "/route/mute" (int ssid) (int (if mute? 1 0)))
  nil)

(defn track-gain!
  "Set the gain of a track.

  `ssid` — track index, 1-based
  `gain` — float, 0.0–2.0 (1.0 = unity, 2.0 = +6 dB)"
  [ssid ^double gain]
  (send! "/route/gain" (int ssid) (float gain))
  nil)

(defn track-fader!
  "Set the fader position of a track.

  `ssid`     — track index, 1-based
  `position` — float, 0.0–1.0 (0.0 = -∞ dB, 1.0 = 0 dB / unity)"
  [ssid ^double position]
  (send! "/route/fader" (int ssid) (float position))
  nil)

;; ---------------------------------------------------------------------------
;; Ubiquitous capture workflow
;; ---------------------------------------------------------------------------

(defn recording?
  "Return true if a capture is currently in progress."
  []
  (boolean (get-in @ardour-state [:capture :recording?])))

(defn capture-status
  "Return the current capture state map.

  Keys:
    :recording? — whether capture is active
    :started-at — java.time.Instant when capture! was called, or nil
    :session-id — opaque string ID for this capture, or nil"
  []
  (:capture @ardour-state))

(defn capture!
  "Start ubiquitous capture: arm global record and roll transport.

  Designed for use with a pre-armed 'capture template' Ardour session
  (all tracks already record-enabled in the template). This function
  arms global record and starts playback — Ardour records immediately.

  For the MIDI capture side: wire the sidecar's virtual MIDI output to
  an Ardour MIDI track input before calling capture! (one-time setup).

  Returns a session ID string for tracking.

  Example:
    (ardour/capture!)
    ;; => \"cap-1743734054123\"
    ;; [ardour] capture started — session cap-1743734054123"
  []
  (when (recording?)
    (throw (ex-info "Capture already in progress; call capture-stop! or capture-discard! first"
                    {:status (capture-status)})))
  (let [session-id (str "cap-" (System/currentTimeMillis))]
    (transport-record! true)
    (transport-play!)
    (swap! ardour-state assoc :capture
           {:recording? true
            :started-at (Instant/now)
            :session-id session-id})
    (println (str "[ardour] capture started — session " session-id))
    session-id))

(defn capture-stop!
  "Stop capture: stop transport, disarm record, and save the session.

  Call (ardour/capture-status) to get the session ID for your notes."
  []
  (when-not (recording?)
    (binding [*out* *err*]
      (println "[ardour] capture-stop! called but no capture in progress")))
  (transport-stop!)
  (transport-record! false)
  (save!)
  (swap! ardour-state assoc-in [:capture :recording?] false)
  (println (str "[ardour] capture stopped and saved — "
                (get-in @ardour-state [:capture :session-id])))
  nil)

(defn capture-discard!
  "Stop capture without saving — use this for false starts.

  Stops transport and disarms record. Does not save the session."
  []
  (transport-stop!)
  (transport-record! false)
  (let [sid (get-in @ardour-state [:capture :session-id])]
    (swap! ardour-state assoc :capture {:recording? false :started-at nil :session-id nil})
    (println (str "[ardour] capture discarded — " sid)))
  nil)
