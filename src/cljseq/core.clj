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
            [cljseq.ctrl     :as ctrl]
            [cljseq.link     :as link]
            [cljseq.loop     :as loop-ns]
            [cljseq.sidecar  :as sidecar])
  (:import  [java.util.concurrent.locks LockSupport])
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
         :config      {:bpm 120}
         :timeline    nil}))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(defn get-bpm
  "Return current BPM."
  []
  (get-in @system-state [:config :bpm]))

(defn get-timeline
  "Return the current timeline map for beat↔epoch-ms conversion.
  Returns nil before start! is called."
  []
  (:timeline @system-state))

(defn set-bpm!
  "Set the master BPM.

  Rolls the timeline anchor to the current wall-clock instant so that
  beat->epoch-ms is always valid relative to the new tempo (Q59).

  After rolling the anchor, unparks all sleeping loop threads (Q60) so that
  each recomputes its wall-clock deadline via park-until-beat! against the
  new timeline. The loops' virtual-time beat positions are unaffected —
  they continue from the same beat, arriving at the correct wall-clock time."
  [bpm]
  (swap! system-state
         (fn [s]
           (let [tl       (:timeline s)
                 now-ms   (System/currentTimeMillis)
                 cur-beat (if tl
                            (clock/epoch-ms->beat now-ms tl)
                            0.0)]
             (-> s
                 (assoc-in [:config :bpm] bpm)
                 (assoc :timeline {:bpm            bpm
                                   :beat0-epoch-ms now-ms
                                   :beat0-beat     cur-beat})))))
  ;; Unpark all sleeping loop threads so they recompute their deadline (Q60)
  (doseq [[_ entry] (:loops @system-state)]
    (when-let [^Thread t (:thread entry)]
      (LockSupport/unpark t)))
  nil)

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Boot the cljseq system.

  Options:
    :bpm  — initial BPM (default 120)

  Registers the system-state atom with cljseq.loop and initialises the
  master timeline anchored at the current wall-clock instant / beat 0."
  [& {:keys [bpm] :or {bpm 120}}]
  (let [now-ms (System/currentTimeMillis)]
    (swap! system-state
           (fn [s]
             (-> s
                 (assoc-in [:config :bpm] bpm)
                 (assoc :timeline {:bpm            bpm
                                   :beat0-epoch-ms now-ms
                                   :beat0-beat     0.0}))))
    (loop-ns/-register-system! system-state)
    (ctrl/-register-system! system-state)
    (link/-register-system! system-state)
    (println (str "cljseq started at " bpm " BPM"))
    nil))

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
  "Play a note. Routes to the sidecar for MIDI output when connected;
  falls back to stdout when running without a sidecar (Phase 0 mode).

  Accepts:
    (play! {:pitch/midi 60 :dur/beats 1/2})   ; step map (canonical form)
    (play! {:pitch/midi 60 :dur/beats 1/4
            :midi/channel 2 :mod/velocity 80}) ; with channel and velocity
    (play! :C4)                                ; note keyword, *default-dur*
    (play! :C4 1/2)                            ; note keyword + explicit dur
    (play! 60)                                 ; MIDI integer, *default-dur*
    (play! 60 1/4)                             ; MIDI integer + explicit dur

  Step map keys honoured:
    :pitch/midi     — MIDI note number (required)
    :dur/beats      — note duration in beats (default 1/4)
    :midi/channel   — MIDI channel 1–16 (default 1)
    :mod/velocity   — MIDI velocity 0–127 (default 64)"
  ([note]
   (play! note nil))
  ([note dur]
   (let [midi     (cond
                    (map? note)     (:pitch/midi note)
                    (keyword? note) (keyword->midi note)
                    (integer? note) note
                    :else           (throw (ex-info "play!: unrecognised note form"
                                                    {:note note})))
         beats    (or (and (map? note) (:dur/beats note))
                      dur
                      1/4)
         now-beat (double loop-ns/*virtual-time*)]
     (if (sidecar/connected?)
       (let [tl       (:timeline @system-state)
             channel  (or (and (map? note) (:midi/channel note)) 1)
             velocity (or (and (map? note) (:mod/velocity note)) 64)
             on-ns    (clock/beat->epoch-ns now-beat tl)
             off-ns   (clock/beat->epoch-ns (+ now-beat (double beats)) tl)]
         (sidecar/send-note-on!  on-ns channel midi velocity)
         (sidecar/send-note-off! off-ns channel midi))
       (let [ms (long (clock/beats->ms beats (get-bpm)))]
         (println (format "[play!] beat=%-8s midi=%-3d dur=%s beats (%dms)"
                          (str now-beat) midi (str beats) ms)))))))

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
  "Align to the next beat-grid boundary and park until it.
  See cljseq.loop/sync! for full docs.

  (sync!)    — next whole beat
  (sync! 4)  — next bar boundary (4 beats)
  (sync! 1/2) — next half-beat"
  ([] (loop-ns/sync!))
  ([divisor] (loop-ns/sync! divisor)))

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
