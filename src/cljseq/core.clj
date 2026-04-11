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
  (:require [cljseq.clock           :as clock]
            [cljseq.conductor       :as conductor]
            [cljseq.config          :as config]
            [cljseq.ctrl            :as ctrl]
            [cljseq.flux            :as flux]
            [cljseq.link            :as link]
            [cljseq.loop            :as loop-ns]
            [cljseq.midi-in         :as midi-in]
            [cljseq.mod             :as mod]
            [cljseq.mpe             :as mpe]
            [cljseq.sidecar         :as sidecar]
            [cljseq.temporal-buffer :as tbuf])
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
  ;; Phase 2: propagate tempo change to the Link session when active.
  ;; This makes (core/set-bpm! x) equivalent to (link/set-bpm! x) when linked.
  (when (link/active?)
    (link/set-bpm! bpm))
  ;; Sync BPM to ctrl tree so HTTP GET /ctrl/config/bpm reflects current value.
  ;; Guarded by timeline presence (proxy for start! having been called).
  (when (:timeline @system-state)
    (ctrl/set! [:config :bpm] (double bpm)))
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
                 (assoc :config (assoc (config/default-config) :bpm bpm))
                 (assoc :timeline {:bpm            bpm
                                   :beat0-epoch-ms now-ms
                                   :beat0-beat     0.0})
                 (assoc :undo-stack [])
                 (dissoc :link-state :link-timeline))))
    (loop-ns/-register-system! system-state)
    (ctrl/-register-system! system-state)
    (flux/-register-system! system-state)
    (tbuf/-register-system! system-state)
    (link/-register-system! system-state)
    (mod/-register-system! system-state)
    (config/-register-system! system-state)
    ;; Register side effects for params with runtime consequences.
    ;; :bpm — full timeline reanchor + thread unpark + Link propagation.
    (config/-register-effect! :bpm set-bpm!)
    ;; Seed ctrl tree nodes for all registry params (HTTP/OSC read access).
    (config/seed-ctrl-tree!)
    (println (str "cljseq started at " bpm " BPM"))
    nil))

(defn stop!
  "Gracefully stop all live loops and shut down the system."
  []
  (midi-in/close-all-inputs!)
  (flux/stop-all!)
  (tbuf/stop-all!)
  (conductor/stop-all-conductors!)
  (mod/mod-unroute-all!)
  (loop-ns/stop-all-loops!)
  ;; Stop the m21 server if it was ever loaded (dynamic to avoid circular dep)
  (when-let [stop-m21 (resolve 'cljseq.m21/stop-server!)]
    (stop-m21))
  ;; Wait briefly for threads to notice the stop signal
  (Thread/sleep 50)
  (swap! system-state assoc :loops {})
  (println "cljseq stopped")
  nil)

;; ---------------------------------------------------------------------------
;; Microtonal pitch bend configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *bend-range-cents*
  "Pitch bend range in cents (one direction) used for the :pitch/bend-cents
  step map key. Default 200.0 (±2 semitones — the MIDI standard default).

  Match this to the pitch bend range configured on your synth. Override
  per-block with binding:
    (binding [core/*bend-range-cents* 1200.0]  ; ±1 octave bend range
      (play! (scala/degree->step ms root 7) 1/4))"
  200.0)

(defn- cents->bend14
  "Convert a cents offset to a 14-bit MIDI pitch bend value using
  *bend-range-cents* as the range calibration.

  Returns integer in [0, 16383]; center (no bend) = 8192."
  ^long [cents]
  (let [range (double *bend-range-cents*)
        frac  (/ (double cents) range)
        steps (if (>= frac 0.0) 8191.0 8192.0)]
    (max 0 (min 16383 (long (Math/round (+ 8192.0 (* frac steps))))))))

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
;; ---------------------------------------------------------------------------
;; SC dispatch — optional backend, registered by cljseq.sc at load time
;; ---------------------------------------------------------------------------

;; Backends register a (fn [note dur bpm]) handler here.
;; Avoids a compile-time dependency on cljseq.sc (which would create a cycle
;; through cljseq.osc → cljseq.core).
(defonce ^:private sc-dispatch (atom nil))

(defn ^:no-doc register-sc-dispatch!
  "Register a function to handle :synth play! events.
  Called automatically when cljseq.sc is loaded. Not part of the public API."
  [f]
  (reset! sc-dispatch f))

(defonce ^:private sample-dispatch (atom nil))

(defn ^:no-doc register-sample-dispatch!
  "Register a function to handle :sample play! events.
  Called automatically when cljseq.sample is loaded. Not part of the public API."
  [f]
  (reset! sample-dispatch f))

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
   ;; SC dispatch — routes :synth events to SuperCollider, bypassing MIDI
   ;; Sample dispatch — routes :sample events to SC buffer playback
   (cond
     (and (map? note) (contains? note :synth) @sc-dispatch)
     (@sc-dispatch note dur (get-bpm))

     (and (map? note) (contains? note :sample) @sample-dispatch)
     (@sample-dispatch note dur (get-bpm))

     :else
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
               timing   loop-ns/*timing-ctx*
               t-off-ns (if timing
                          (long (Math/round (* (clock/sample timing now-beat)
                                               (clock/beats->ms 1.0 (double (:bpm tl)))
                                               1000000.0)))
                          0)
               step     (if (map? note) note {:pitch/midi midi :dur/beats beats})]
           ;; Route through MPE allocator when enabled and step has per-note expression keys
           (if (and (mpe/mpe-enabled?) (mpe/mpe-step? step))
             (mpe/play-mpe! step now-beat tl)
             (let [channel   (or (and (map? note) (:midi/channel note)) 1)
                   velocity  (or (and (map? note) (:mod/velocity note)) 64)
                   wall-ns   (* (System/currentTimeMillis) 1000000)
                   on-ns     (max wall-ns (clock/beat->epoch-ns now-beat tl))
                   off-ns    (max (+ on-ns (* (long (clock/beats->ms beats (double (:bpm tl)))) 1000000))
                                  (clock/beat->epoch-ns (+ now-beat (double beats)) tl))
                   t-on-ns   (+ on-ns t-off-ns)]
               ;; Dispatch ctrl-path step-mods at note-on time (§24.6 Phase 3)
               (doseq [[k mod] (or loop-ns/*step-mod-ctx* [])
                       :when (vector? k)]
                 (let [val (if (satisfies? clock/ITemporalValue mod)
                             (clock/sample mod now-beat)
                             mod)]
                   (ctrl/send-at! t-on-ns k val)))
               ;; Microtonal pitch bend — send before note-on, reset after note-off
               (let [bend-cents (when (map? note) (:pitch/bend-cents note))
                     bend-14bit (when (and bend-cents (not (zero? (double bend-cents))))
                                  (cents->bend14 bend-cents))]
                 (when bend-14bit
                   (sidecar/send-pitch-bend! t-on-ns channel bend-14bit))
                 (sidecar/send-note-on!  t-on-ns channel midi velocity)
                 (sidecar/send-note-off! (+ off-ns t-off-ns) channel midi)
                 (when bend-14bit
                   (sidecar/send-pitch-bend! (+ off-ns t-off-ns) channel 8192))))))
         (let [ms (long (clock/beats->ms beats (get-bpm)))]
           (println (format "[play!] beat=%-8s midi=%-3d dur=%s beats (%dms)"
                            (str now-beat) midi (str beats) ms))))))))

;; ---------------------------------------------------------------------------
;; apply-trajectory! — continuous SC node parameter control
;; ---------------------------------------------------------------------------

(defn apply-trajectory!
  "Continuously apply an ITemporalValue trajectory by calling `setter-fn`
  with each sampled value at ~50ms intervals until the trajectory completes.

  `setter-fn` — a (fn [value]) called on each sample tick
  `traj`      — any ITemporalValue: Trajectory, OneShot, constant, etc.

  Returns a zero-argument cancel function. Call it to stop the trajectory
  before it completes naturally.

  Requires the system clock to be running (start!).

  This is the general form. For the SC-specific 3-arity convenience
  (apply-trajectory! node param traj), use cljseq.sc/apply-trajectory!

  Example:
    ;; General form — any setter
    (apply-trajectory! #(println \"val:\" %)
      (traj/trajectory :from 0.0 :to 1.0 :beats 8 :curve :s-curve :start (now)))

    ;; SC form (via cljseq.sc or cljseq.user)
    (let [node (sc/sc-synth! :saw-pad {:freq 220 :amp 0.5})]
      (apply-trajectory! node :cutoff
        (traj/trajectory :from 0.2 :to 0.9 :beats 16 :curve :s-curve
                         :start (now))))"
  [setter-fn traj]
  (let [running (atom true)
        thread  (Thread.
                  (fn []
                    (try
                      (loop []
                        (when @running
                          (let [tl    (get-timeline)
                                beat  (if tl
                                        (clock/epoch-ms->beat
                                          (System/currentTimeMillis) tl)
                                        0.0)
                                value (clock/sample traj beat)
                                nxt   (clock/next-edge traj beat)]
                            (setter-fn value)
                            (when (and @running (< nxt ##Inf))
                              (Thread/sleep 50)
                              (recur)))))
                      (catch InterruptedException _)
                      (catch Exception e
                        (when @running
                          (println "[apply-trajectory!] error:" (.getMessage e)))))))]
    (.setDaemon thread true)
    (.start thread)
    (fn cancel []
      (reset! running false)
      nil)))

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
