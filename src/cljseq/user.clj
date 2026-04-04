; SPDX-License-Identifier: EPL-2.0
(ns cljseq.user
  "Convenience namespace for REPL sessions.

  Load this at the start of a session to get everything you need in scope:

    (require '[cljseq.user :refer :all])
    (session!)           ; start clock at 120 BPM, prints status
    (session! :bpm 140)  ; with custom BPM

  Everything in cljseq.core, cljseq.dsl, and the most-used theory namespaces
  is re-exported. You can also require just the pieces you need directly.

  Typical live session:

    (session!)
    (start-sidecar! :midi-port 0)   ; connect MIDI output

    ;; Simple loop
    (deflive-loop :kick {}
      (play! {:pitch/midi 36 :dur/beats 1/4})
      (sleep! 1))

    ;; Hot-swap the body any time
    (deflive-loop :kick {}
      (play! {:pitch/midi 38 :dur/beats 1/4})
      (sleep! 1/2))

    ;; Stop individual or all loops
    (stop-loop! :kick)
    (end-session!)

  See examples/ for full demonstrations."
  (:require [cljseq.analyze    :as analyze]
            [cljseq.arc        :as arc]
            [cljseq.ardour     :as ardour]
            [cljseq.chord      :as chord]
            [cljseq.conductor  :as conductor]
            [cljseq.core       :as core]
            [cljseq.dsl        :as dsl]
            [cljseq.fractal    :as frac]
            [cljseq.learn      :as learn]
            [cljseq.loop       :as loop-ns]
            [cljseq.mod        :as mod]
            [cljseq.pattern    :as pat]
            [cljseq.scala      :as scala]
            [cljseq.scale      :as scale]
            [cljseq.sidecar    :as sidecar]
            [cljseq.stochastic :as stoch]
            [cljseq.trajectory :as traj]
            [cljseq.voice      :as voice]))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn session!
  "Start a cljseq session: boot the system clock and print a status summary.

  Options:
    :bpm — initial BPM (default 120)

  Does nothing if the system is already running (idempotent).

  Example:
    (session!)
    (session! :bpm 140)"
  [& {:keys [bpm] :or {bpm 120}}]
  (core/start! :bpm bpm)
  (println (str "Session ready — BPM " bpm
                "\n  (start-sidecar! :midi-port 0) to connect MIDI output"
                "\n  (end-session!) to stop"))
  nil)

(defn end-session!
  "Stop all loops and shut down the system."
  []
  (core/stop!)
  nil)

;; ---------------------------------------------------------------------------
;; Re-export the most-used core API
;; ---------------------------------------------------------------------------

(def set-bpm!      core/set-bpm!)
(def get-bpm       core/get-bpm)
(def play!         core/play!)
(def sleep!        core/sleep!)
(def sync!         core/sync!)
(def stop-loop!    core/stop-loop!)
(def now           core/now)

(defmacro deflive-loop [loop-name opts & body]
  `(core/deflive-loop ~loop-name ~opts ~@body))

(defmacro live-loop [loop-name opts & body]
  `(core/deflive-loop ~loop-name ~opts ~@body))

;; ---------------------------------------------------------------------------
;; Re-export DSL
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Trajectory / arc types
;; ---------------------------------------------------------------------------

(def trajectory       traj/trajectory)
(def apply-curve      traj/apply-curve)
(def buildup          traj/buildup)
(def trance-buildup   traj/trance-buildup)
(def breakdown        traj/breakdown)
(def anticipation     traj/anticipation)
(def groove-lock      traj/groove-lock)
(def wind-down        traj/wind-down)
(def swell            traj/swell)
(def tension-peak     traj/tension-peak)
(def arc-merge        traj/arc-merge)
(def time-warp        traj/time-warp)
(def mod-route!    mod/mod-route!)
(def mod-unroute!  mod/mod-unroute!)
(def arc-bind!        arc/arc-bind!)
(def arc-unbind!      arc/arc-unbind!)
(def arc-send!        arc/arc-send!)
(def arc-routes       arc/arc-routes)
(def defconductor!    conductor/defconductor!)
(def fire!            conductor/fire!)
(def abort!           conductor/abort!)
(def cue!             conductor/cue!)
(def conductor-state  conductor/conductor-state)
(def conductor-names  conductor/conductor-names)

;; ---------------------------------------------------------------------------
;; Analysis
;; ---------------------------------------------------------------------------

(def pitch-class-dist          analyze/pitch-class-dist)
(def detect-key               analyze/detect-key)
(def detect-key-candidates    analyze/detect-key-candidates)
(def identify-chord           analyze/identify-chord)
(def identify-chord-candidates analyze/identify-chord-candidates)
(def chord->roman             analyze/chord->roman)
(def analyze-progression      analyze/analyze-progression)
(def scale-fitness            analyze/scale-fitness)
(def suggest-scales           analyze/suggest-scales)
(def tension-score            analyze/tension-score)
(def progression-tension      analyze/progression-tension)
(def borrowed-chord?          analyze/borrowed-chord?)
(def annotate-progression     analyze/annotate-progression)
(def suggest-progression      analyze/suggest-progression)

;; ---------------------------------------------------------------------------
;; MIDI Learn — device map authoring
;; ---------------------------------------------------------------------------

(def start-learn-session!  learn/start-learn-session!)
(def learn-notes!          learn/learn-notes!)
(def learn-cc!             learn/learn-cc!)
(def learn-sequence!       learn/learn-sequence!)
(def cancel-learn!         learn/cancel-learn!)
(def learning?             learn/learning?)
(def learn-session-state   learn/learn-session-state)
(def clear-session!        learn/clear-session!)
(def export-device-map!    learn/export-device-map!)

;; ---------------------------------------------------------------------------
;; Re-export DSL
;; ---------------------------------------------------------------------------

(def with-dur       dsl/with-dur)
(def use-harmony!   dsl/use-harmony!)
(def use-chord!     dsl/use-chord!)
(def use-synth!     dsl/use-synth!)
(def use-tuning!    dsl/use-tuning!)
(def play-chord!    dsl/play-chord!)
(def play-voicing!  dsl/play-voicing!)
(def arp!           dsl/arp!)
(def phrase!        dsl/phrase!)
(def ring           dsl/ring)
(def tick!          dsl/tick!)

(defmacro with-tuning [ctx & body]
  `(dsl/with-tuning ~ctx ~@body))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; Scala microtonal scales
;; ---------------------------------------------------------------------------

(def parse-scl         scala/parse-scl)
(def load-scl          scala/load-scl)
(def degree-count      scala/degree-count)
(def degree->cents     scala/degree->cents)
(def degree->note      scala/degree->note)
(def degree->step      scala/degree->step)
(def interval-cents    scala/interval-cents)
(def parse-kbm         scala/parse-kbm)
(def load-kbm          scala/load-kbm)
(def midi->note        scala/midi->note)
(def midi->step        scala/midi->step)
(def scale->mts-bytes  scala/scale->mts-bytes)

;; ---------------------------------------------------------------------------
;; Ardour DAW integration
;; ---------------------------------------------------------------------------

(def connect-ardour!       ardour/connect!)
(def transport-play!       ardour/transport-play!)
(def transport-stop!       ardour/transport-stop!)
(def transport-record!     ardour/transport-record!)
(def goto-start!           ardour/goto-start!)
(def add-marker!           ardour/add-marker!)
(def ardour-save!          ardour/save!)
(def capture!              ardour/capture!)
(def capture-stop!         ardour/capture-stop!)
(def capture-discard!      ardour/capture-discard!)
(def capture-status        ardour/capture-status)
(def recording?            ardour/recording?)

;; ---------------------------------------------------------------------------
;; Sidecar shorthand
;; ---------------------------------------------------------------------------

(def list-midi-ports  sidecar/list-midi-ports)
(def find-midi-port   sidecar/find-midi-port)

(defn start-sidecar!
  "Connect the MIDI sidecar.

  Options:
    :midi-port    — MIDI output port: integer index or name substring (default 0)
    :midi-in-port — MIDI input port: integer index or name substring (default nil)

  Use (list-midi-ports) to discover available ports.

  Example:
    (start-sidecar!)
    (start-sidecar! :midi-port 1)
    (start-sidecar! :midi-port \"IAC\")
    (start-sidecar! :midi-port \"Hydra\" :midi-in-port \"Hydra\")"
  [& {:keys [midi-port midi-in-port] :or {midi-port 0}}]
  (sidecar/start-sidecar! :midi-port midi-port :midi-in-port midi-in-port)
  (println (str "Sidecar started on MIDI port " midi-port))
  nil)

(defn stop-sidecar!
  "Disconnect the MIDI sidecar."
  []
  (sidecar/stop-sidecar!)
  nil)

(def send-sysex! sidecar/send-sysex!)
(def send-mts!   sidecar/send-mts!)

;; ---------------------------------------------------------------------------
;; Handy theory shortcuts at the top level
;; ---------------------------------------------------------------------------

(defn make-scale
  "Build a Scale record. Common modal shorthand.

  Examples:
    (make-scale :C 4 :major)
    (make-scale :D 4 :dorian)
    (make-scale :A 3 :pentatonic-minor)"
  [root octave mode]
  (scale/scale root octave mode))

(defn make-chord
  "Build a Chord record.

  Examples:
    (make-chord :C 4 :maj7)
    (make-chord :G 3 :dominant7)"
  [root octave quality]
  (chord/chord root octave quality))

(defn progression
  "Build a sequence of chords from Roman numeral keywords.

  Example:
    (progression (make-scale :C 4 :major) [:I :IV :V7 :I])"
  [scale degrees]
  (chord/progression scale degrees))
