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
  (:require [cljseq.chord    :as chord]
            [cljseq.core     :as core]
            [cljseq.dsl      :as dsl]
            [cljseq.fractal  :as frac]
            [cljseq.loop     :as loop-ns]
            [cljseq.pattern  :as pat]
            [cljseq.scale    :as scale]
            [cljseq.sidecar  :as sidecar]
            [cljseq.stochastic :as stoch]
            [cljseq.voice    :as voice]))

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

(def with-dur       dsl/with-dur)
(def use-harmony!   dsl/use-harmony!)
(def use-chord!     dsl/use-chord!)
(def use-synth!     dsl/use-synth!)
(def play-chord!    dsl/play-chord!)
(def play-voicing!  dsl/play-voicing!)
(def arp!           dsl/arp!)
(def phrase!        dsl/phrase!)
(def ring           dsl/ring)
(def tick!          dsl/tick!)

;; ---------------------------------------------------------------------------
;; Sidecar shorthand
;; ---------------------------------------------------------------------------

(defn start-sidecar!
  "Connect the MIDI sidecar on the given port index (default 0).

  Use (list-midi-ports) from cljseq.sidecar to discover available ports.

  Example:
    (start-sidecar! :midi-port 1)"
  [& {:keys [midi-port] :or {midi-port 0}}]
  (sidecar/start-sidecar! :midi-port midi-port)
  (println (str "Sidecar started on MIDI port " midi-port))
  nil)

(defn stop-sidecar!
  "Disconnect the MIDI sidecar."
  []
  (sidecar/stop-sidecar!)
  nil)

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
