; SPDX-License-Identifier: EPL-2.0
(ns cljseq.core
  "cljseq — music-theory-aware sequencer for Clojure.

  System lifecycle and primary user-facing API. Start with `(start!)`,
  stop with `(stop!)`. All interactive forms are available via:
    (require '[cljseq.core :refer :all])

  ## Hello clock session
    (start!)
    (deflive-loop :hello {}
      (play! {:pitch/midi 60 :dur/beats 1/2})
      (sleep! 1))
    (stop!)

  Key design decisions: Q47 (single system-state atom), Q48 (patch system),
  Q11 (live-loop alias), Q1 (virtual time), phase0-readiness.md."
  (:require [cljseq.clock    :as clock]
            [cljseq.loop     :as loop-ns])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; System state (Q47)
;; ---------------------------------------------------------------------------

;;; Single system-state atom. Shape per Q47:
;;;   :tree        — control tree (persistent map)
;;;   :serial      — structural change counter
;;;   :undo-stack  — bounded ring; default depth 50
;;;   :checkpoints — named snapshots for panic/revert
;;;   :loops       — live-loop registry
;;;   :config      — runtime configuration (BPM etc.)
(defonce system-state
  (atom {:tree        {}
         :serial      0
         :undo-stack  []
         :checkpoints {}
         :loops       {}
         :config      {:bpm 120}}))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(defn get-bpm
  "Return current BPM."
  []
  (get-in @system-state [:config :bpm]))

(defn set-bpm!
  "Set the master BPM."
  [bpm]
  (swap! system-state assoc-in [:config :bpm] bpm)
  nil)

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Boot the cljseq system.

  Options:
    :bpm  — initial BPM (default 120)

  Registers the system-state atom with cljseq.loop."
  [& {:keys [bpm] :or {bpm 120}}]
  (swap! system-state assoc-in [:config :bpm] bpm)
  (loop-ns/-register-system! system-state)
  (println (str "cljseq started at " bpm " BPM"))
  nil)

(defn stop!
  "Gracefully stop all live loops and shut down the system."
  []
  (loop-ns/stop-all-loops!)
  ;; Wait briefly for threads to notice the stop signal
  (Thread/sleep 50)
  (swap! system-state assoc :loops {})
  (println "cljseq stopped")
  nil)

;; ---------------------------------------------------------------------------
;; Note keyword → MIDI conversion
;; ---------------------------------------------------------------------------

(def ^:private note-class
  {"C" 0 "D" 2 "E" 4 "F" 5 "G" 7 "A" 9 "B" 11})

(defn keyword->midi
  "Convert a note keyword to a MIDI note number.

  Examples:
    (keyword->midi :C4)  => 60
    (keyword->midi :A4)  => 69
    (keyword->midi :G#3) => 56
    (keyword->midi :Bb2) => 46

  Returns nil if the keyword is not a valid note name."
  [k]
  (when (keyword? k)
    (when-let [[_ note acc octave]
               (re-matches #"([A-G])([#b]?)(-?\d+)" (name k))]
      (let [base  (note-class note)
            shift (case acc "#" 1 "b" -1 0)
            oct   (Integer/parseInt octave)]
        (+ (* (+ oct 1) 12) base shift)))))

;; ---------------------------------------------------------------------------
;; play! — Phase 0 stdout stub
;; ---------------------------------------------------------------------------

(defn play!
  "Play a note. Phase 0: prints to stdout. Phase 1: emits MIDI via sidecar.

  Accepts:
    (play! {:pitch/midi 60 :dur/beats 1/2})   ; step map (canonical form)
    (play! :C4)                                ; note keyword, *default-dur*
    (play! :C4 1/2)                            ; note keyword + explicit dur
    (play! 60)                                 ; MIDI integer, *default-dur*
    (play! 60 1/4)                             ; MIDI integer + explicit dur

  The *default-dur* dynamic var (from cljseq.dsl, default 1/4) controls the
  fallback duration when none is provided."
  ([note]
   (play! note nil))
  ([note dur]
   (let [midi  (cond
                 (map? note)     (:pitch/midi note)
                 (keyword? note) (keyword->midi note)
                 (integer? note) note
                 :else           (throw (ex-info "play!: unrecognised note form"
                                                 {:note note})))
         beats (or (and (map? note) (:dur/beats note))
                   dur
                   1/4)
         bpm   (get-bpm)
         ms    (long (clock/beats->ms beats bpm))
         beat  loop-ns/*virtual-time*]
     (println (format "[play!] beat=%-8s midi=%-3d dur=%s beats (%dms)"
                      (str beat) midi (str beats) ms)))))

;; ---------------------------------------------------------------------------
;; Re-export live-loop API from cljseq.loop
;; ---------------------------------------------------------------------------

(defn now
  "Return the current virtual beat position. Delegates to cljseq.loop/now."
  []
  (loop-ns/now))

(defn sleep!
  "Advance virtual time and sleep. Delegates to cljseq.loop/sleep!"
  [beats]
  (loop-ns/sleep! beats))

(defn sync!
  "Align to beat boundary. Delegates to cljseq.loop/sync!"
  []
  (loop-ns/sync!))

(defmacro deflive-loop
  "Define a named live loop. See cljseq.loop/deflive-loop for full docs."
  [loop-name opts & body]
  `(loop-ns/deflive-loop ~loop-name ~opts ~@body))

(defmacro live-loop
  "Alias for deflive-loop (Q11). See cljseq.loop/deflive-loop."
  [loop-name opts & body]
  `(loop-ns/deflive-loop ~loop-name ~opts ~@body))

(defn stop-loop!
  "Stop a named live loop."
  [loop-name]
  (loop-ns/stop-loop! loop-name))

;; ---------------------------------------------------------------------------
;; ctrl namespace stubs (Phase 0: read-only access to system state)
;; ---------------------------------------------------------------------------

(defn ctrl-get
  "Read a value from the system state at `path`.
  Phase 0 stub for the full ctrl/get from cljseq.ctrl."
  [path]
  (get-in @system-state path))

;; ---------------------------------------------------------------------------
;; -main
;; ---------------------------------------------------------------------------

(defn -main
  [& _args]
  (println "cljseq — use at a Clojure nREPL"))
