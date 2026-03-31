; SPDX-License-Identifier: EPL-2.0
(ns cljseq.loop
  "cljseq live-loop subsystem.

  `deflive-loop` defines a named, repeating loop registered in the system
  state at [:loops <name>]. `live-loop` is a macro alias with auto-generated
  name (Q11).

  Re-evaluating a `deflive-loop` form hot-swaps the body: the running thread
  picks up the new fn at its next iteration without restarting.

  Virtual time (`*virtual-time*`) is a thread-local rational beat counter.
  `sleep!` advances it and parks the thread for the equivalent wall-clock
  duration. `now` returns the current virtual beat position.

  Key design decisions: Q11 (live-loop alias), Q1 (virtual time),
  Q2–Q3 (sync! semantics — basic stub in Phase 0)."
  (:require [cljseq.clock :as clock]))

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

;; ---------------------------------------------------------------------------
;; Virtual time
;; ---------------------------------------------------------------------------

(def ^:dynamic *virtual-time*
  "Current beat position within a live-loop iteration.
  Rational; advanced by sleep!. Bound per-thread by deflive-loop."
  0N)

(defn now
  "Return the current virtual beat position."
  []
  *virtual-time*)

(defn sleep!
  "Advance virtual time by `beats` and sleep for the equivalent wall-clock
  duration at the current BPM.

  `beats` may be any number (integer, ratio, float). Internally stored as
  a rational to preserve exact beat arithmetic (Q1)."
  [beats]
  (let [ms (clock/beats->ms beats (get-bpm))]
    (set! *virtual-time* (+ *virtual-time* (rationalize beats)))
    (Thread/sleep (long ms))))

(defn sync!
  "Align to the next beat boundary within the sync window.
  Phase 0 stub — returns current virtual time unchanged."
  []
  *virtual-time*)

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
                     (binding [cljseq.loop/*virtual-time* 0N]
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
