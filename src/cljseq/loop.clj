; SPDX-License-Identifier: EPL-2.0
(ns cljseq.loop
  "cljseq live-loop subsystem.

  `deflive-loop` defines a named, repeating loop registered in the system
  state at [:loops <name>]. `live-loop` is a macro alias with auto-generated
  name (Q11).

  Re-evaluating a `deflive-loop` form hot-swaps the body: the running thread
  picks up the new fn at its next iteration without restarting.

  Virtual time (`*virtual-time*`) is a thread-local beat counter anchored to
  the master timeline. `sleep!` advances it and parks the thread until the
  corresponding wall-clock deadline (absolute, drift-free). `now` returns the
  current virtual beat position.

  Key design decisions: Q11 (live-loop alias), Q1 (virtual time), Q59
  (LockSupport/parkUntil for drift-free sleep), Q2–Q3 (sync! stub Phase 0)."
  (:require [cljseq.clock :as clock])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state reference (injected by cljseq.core/start!)
;; ---------------------------------------------------------------------------

;; Holds a reference to the cljseq.core/system-state atom.
;; Set by -register-system!; nil until cljseq.core/start! is called.
(def ^:private system-ref (atom nil))

(defn -system-ref
  "Return the system-ref atom. Used by deflive-loop macro expansions in other
  namespaces. Not part of the public API."
  []
  system-ref)

(defn -register-system!
  "Called by cljseq.core/start! to inject the system-state atom.
  Not part of the public API."
  [state-atom]
  (reset! system-ref state-atom))

(defn- get-bpm
  "Return current BPM from system state, or 120 if system not started."
  []
  (if-let [s @system-ref]
    (get-in @s [:config :bpm])
    120))

(defn- get-timeline
  "Return the current system timeline, or nil if not started."
  []
  (when-let [s @system-ref]
    (:timeline @s)))

;; ---------------------------------------------------------------------------
;; Virtual time
;; ---------------------------------------------------------------------------

(def ^:dynamic *virtual-time*
  "Current beat position, anchored to the master timeline.
  Advanced by sleep!. Bound per-thread by deflive-loop.
  Initialised to the current master-clock beat when the loop starts
  (not 0), so that beat->epoch-ms conversion is always valid (Q59)."
  0.0)

(defn now
  "Return the current virtual beat position."
  []
  *virtual-time*)

(defn -current-beat
  "Return the current beat position from the master timeline.
  Used by deflive-loop to initialise *virtual-time* on thread start.
  Returns 0.0 if the system is not yet started."
  ^double []
  (if-let [tl (get-timeline)]
    (clock/epoch-ms->beat (System/currentTimeMillis) tl)
    0.0))

(defn- park-until!
  "Park the current thread until the epoch-ms wall-clock deadline.
  Loops to handle spurious wakeups (LockSupport/parkUntil contract)."
  [^long epoch-ms]
  (loop []
    (when (< (System/currentTimeMillis) epoch-ms)
      (LockSupport/parkUntil epoch-ms)
      (recur))))

(defn sleep!
  "Advance virtual time by `beats` and park until the corresponding
  wall-clock deadline (absolute, drift-free).

  Uses LockSupport/parkUntil against the master timeline for correct
  scheduling across BPM changes (Q59). Falls back to Thread/sleep if
  the system is not started.

  `beats` may be any number (integer, ratio, float). *virtual-time*
  is stored as a double to match the timeline epoch-ms arithmetic;
  beat arithmetic on the Clojure side uses rationals where it matters."
  [beats]
  (let [target-beat (+ (double *virtual-time*) (double beats))]
    (set! *virtual-time* target-beat)
    (if-let [tl (get-timeline)]
      (park-until! (clock/beat->epoch-ms target-beat tl))
      (Thread/sleep (long (clock/beats->ms beats (get-bpm)))))))

(defn sync!
  "Align the current loop to the next beat-grid boundary and park until it.

  `divisor` — beat grid to align to (default 1). Examples:
    (sync!)    — next whole beat
    (sync! 4)  — next bar boundary (4 beats)
    (sync! 1/2) — next half-beat

  Sets *virtual-time* to the aligned boundary, parks until the corresponding
  wall-clock deadline, and returns the new *virtual-time*."
  ([] (sync! 1))
  ([divisor]
   (let [d    (double divisor)
         vt   (double *virtual-time*)
         next (+ (* (Math/floor (/ vt d)) d) d)]
     (set! *virtual-time* next)
     (if-let [tl (get-timeline)]
       (park-until! (clock/beat->epoch-ms next tl))
       (Thread/sleep (long (clock/beats->ms (- next vt) (get-bpm)))))
     *virtual-time*)))

;; ---------------------------------------------------------------------------
;; deflive-loop
;; ---------------------------------------------------------------------------

(defmacro deflive-loop
  "Define a named live loop that runs continuously.

  `loop-name` — keyword identifying the loop (e.g. :hello).
  `opts`      — options map (reserved; pass {} for now).
  `body`      — forms to execute each iteration.

  The loop is registered in the system state at [:loops loop-name].
  Re-evaluating the form hot-swaps the body; the thread picks up the new fn
  on its next iteration.

  The loop thread binds *virtual-time* to 0N at startup. Use sleep! to advance
  it. Loop tick-count increments each iteration.

  Examples:
    (deflive-loop :kick {}
      (play! {:pitch/midi 36 :dur/beats 1/4})
      (sleep! 1))

    ;; Hot-swap: re-evaluate to change what plays
    (deflive-loop :kick {}
      (play! {:pitch/midi 37 :dur/beats 1/8})
      (sleep! 1/2))"
  [loop-name _opts & body]
  `(let [loop-fn#  (fn [] ~@body)
         sref#     (cljseq.loop/-system-ref)]
     (if (get-in @@sref# [:loops ~loop-name])
       ;; Hot-swap: update fn; thread picks it up next iteration
       (do
         (swap! @sref# assoc-in [:loops ~loop-name :fn] loop-fn#)
         ~loop-name)
       ;; First evaluation: create entry and start thread
       (let [running?# (atom true)]
         (swap! @sref# assoc-in [:loops ~loop-name]
                {:fn         loop-fn#
                 :tick-count 0
                 :running?   running?#
                 :thread     nil})
         (let [t# (Thread.
                   (fn []
                     (binding [cljseq.loop/*virtual-time* (cljseq.loop/-current-beat)]
                       (loop []
                         (when @running?#
                           (let [f# (get-in @@sref#
                                            [:loops ~loop-name :fn])]
                             (f#)
                             (swap! @sref#
                                    update-in [:loops ~loop-name :tick-count]
                                    inc))
                           (recur))))))]
           (.setDaemon t# true)
           (.setName t# (str "cljseq-loop-" (name ~loop-name)))
           (swap! @sref# assoc-in [:loops ~loop-name :thread] t#)
           (.start t#)
           ~loop-name)))))

(defmacro live-loop
  "Alias for `deflive-loop` (Q11). Name is auto-generated when omitted.
  Usage identical to deflive-loop."
  [loop-name opts & body]
  `(deflive-loop ~loop-name ~opts ~@body))

;; ---------------------------------------------------------------------------
;; Loop management utilities
;; ---------------------------------------------------------------------------

(defn stop-loop!
  "Stop a named loop. The thread finishes its current iteration then exits."
  [loop-name]
  (when-let [s @system-ref]
    (when-let [running? (get-in @s [:loops loop-name :running?])]
      (reset! running? false)))
  nil)

(defn stop-all-loops!
  "Stop all running loops."
  []
  (when-let [s @system-ref]
    (doseq [[lname _] (:loops @s)]
      (stop-loop! lname)))
  nil)
