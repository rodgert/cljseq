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
  (:require [cljseq.clock :as clock]
            [cljseq.link  :as link])
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

(defn- effective-timeline
  "Return the timeline to use for beat→epoch-ms conversion.
  When Link is active, returns the Link-sourced anchor; otherwise
  the local BPM timeline."
  []
  (or (link/link-timeline) (get-timeline)))

;; ---------------------------------------------------------------------------
;; Virtual time and synth context
;; ---------------------------------------------------------------------------

(def ^:dynamic *synth-ctx*
  "Active synth-routing context for the current thread.
  A map with at minimum :midi/channel (1–16) and optionally :mod/velocity (0–127).
  nil means no context — channel 1, velocity 64 are the fallback defaults.

  Bound per-thread by deflive-loop (from the :synth key in opts) and by
  cljseq.dsl/with-synth. Set at the REPL root with cljseq.dsl/use-synth!.

  Example context map:
    {:midi/channel 3 :mod/velocity 100}"
  nil)

(def ^:dynamic *timing-ctx*
  "Active timing modulator for the current thread. An ITemporalValue
  (from cljseq.timing) that returns Double fractional-beat offsets,
  or nil for no timing modification.

  Bound per-thread by deflive-loop (from the :timing key in opts) and by
  cljseq.dsl/with-timing. Set at the REPL root with cljseq.dsl/use-timing!."
  nil)

(def ^:dynamic *mod-ctx*
  "Active mod routing context for the current thread.
  A map from ctrl-path (vector) to ITemporalValue (LFO/envelope), or nil.

  Each entry maps a ctrl node path to a modulator. dsl/tick-mods! iterates
  this map, sampling each modulator at *virtual-time* and routing the result
  to ctrl/send!.

  Bound per-thread by deflive-loop (from the :mod key in opts) and by
  cljseq.dsl/with-mod. Set at the REPL root with cljseq.dsl/use-mod!.

  Example context map:
    {[:filter/cutoff] (mod/lfo (->Phasor 1/4 0) phasor/sine-uni)
     [:resonance]     (mod/lfo (->Phasor 1/8 0) phasor/triangle)}"
  nil)

(def ^:dynamic *step-mod-ctx*
  "Active per-step mod routing context for the current thread (§24.6).
  A map from step-key keyword to ITemporalValue (or a constant number), or nil.

  At each dsl/play! call, each entry is sampled at *virtual-time* and merged
  into the step map AFTER the synth context — step-mods override everything.
  Use this to apply LFOs or envelopes to note properties such as velocity,
  gate length, or pitch.

  Bound per-thread by deflive-loop (from the :step-mods key in opts) and by
  cljseq.dsl/with-step-mods. Set at the REPL root with cljseq.dsl/use-step-mods!.

  Example:
    {[:mod/velocity] (mod/lfo (->Phasor 1/4 0) phasor/triangle)
     :gate/len       (mod/lfo (->Phasor 1/8 0) phasor/sine-uni)}"
  nil)

(def ^:dynamic *harmony-ctx*
  "Active harmonic context for the current thread (§4.3).
  A cljseq.scale/Scale record (root + interval pattern), or nil.

  Used by dsl/root, dsl/fifth, dsl/scale-degree to derive pitches from the
  current key/scale without hard-coding MIDI numbers.

  Bound per-thread by deflive-loop (from the :harmony key in opts) and by
  cljseq.dsl/with-harmony. Set at the REPL root with cljseq.dsl/use-harmony!.

  Example:
    (scale/scale :C 4 :major)      ; C major, root C4
    (scale/scale :D 3 :dorian)     ; D dorian, root D3"
  nil)

(def ^:dynamic *chord-ctx*
  "Active chord context for the current thread (§5.1).
  A cljseq.chord/Chord record (root + quality + inversion), or nil.

  Used by cljseq.pattern/motif! to resolve chord-relative pattern indices.
  Index 1 = chord root, 2 = next chord tone, etc.; indices beyond chord size
  cycle upward by octave.

  Bound per-thread by deflive-loop (from the :chord key in opts) and by
  cljseq.dsl/with-chord. Set at the REPL root with cljseq.dsl/use-chord!.

  Example:
    (chord/chord :C 4 :maj7)   ; Cmaj7 as chord context"
  nil)

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
  "Return the current beat position from the effective timeline (Link or local).
  Used by deflive-loop to initialise *virtual-time* on thread start.
  Returns 0.0 if the system is not yet started."
  ^double []
  (if-let [tl (effective-timeline)]
    (clock/epoch-ms->beat (System/currentTimeMillis) tl)
    0.0))

(defn -start-beat
  "Return the beat at which a new live-loop should begin.
  When Link is active, snaps to the next quantum boundary (default 4 beats)
  so the loop joins the session in phase (§15.4).
  When Link is inactive, returns the current beat immediately."
  ^double []
  (let [current (-current-beat)]
    (if (link/active?)
      (link/next-quantum-beat current 4)
      current)))

(defn- park-until-beat!
  "Park the current thread until `target-beat` on the effective timeline (Q60).

  Re-evaluates `beat->epoch-ms` on every wakeup — whether spurious or triggered
  by `LockSupport/unpark` from `set-bpm!` or a Link state push. This means any
  tempo change (local or from a Link peer) immediately causes the thread to
  recompute its wall-clock deadline, rather than sleeping to the old deadline.

  When Link is active, `effective-timeline` returns the Link-sourced anchor, so
  `sleep!` is automatically phase-locked to the Link session."
  [^double target-beat]
  (loop []
    (when-let [tl (effective-timeline)]
      (let [epoch-ms (clock/beat->epoch-ms target-beat tl)]
        (when (< (System/currentTimeMillis) epoch-ms)
          (LockSupport/parkUntil epoch-ms)
          (recur))))))

(defn -park-until-beat!
  "Public facade for macro expansions in other namespaces (e.g. deflive-loop).
  Not part of the user-facing API."
  [target-beat]
  (park-until-beat! target-beat))

(defn sleep!
  "Advance virtual time by `beats` and park until the corresponding
  wall-clock deadline (absolute, drift-free).

  Uses LockSupport/parkUntil against the master timeline for correct
  scheduling across BPM changes (Q59, Q60). Falls back to Thread/sleep if
  the system is not started.

  `beats` may be any number (integer, ratio, float). *virtual-time*
  is stored as a double to match the timeline epoch-ms arithmetic;
  beat arithmetic on the Clojure side uses rationals where it matters."
  [beats]
  (let [target-beat (+ (double *virtual-time*) (double beats))]
    (set! *virtual-time* target-beat)
    (if (effective-timeline)
      (park-until-beat! target-beat)
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
     (if (effective-timeline)
       (park-until-beat! next)
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
  [loop-name opts & body]
  `(let [synth-ctx#     (:synth ~opts)
         timing-ctx#    (:timing ~opts)
         mod-ctx#       (:mod ~opts)
         step-mod-ctx#  (:step-mods ~opts)
         harmony-ctx#   (:harmony ~opts)
         chord-ctx#     (:chord ~opts)
         loop-fn#       (fn []
                          (binding [cljseq.loop/*synth-ctx*     synth-ctx#
                                    cljseq.loop/*timing-ctx*    timing-ctx#
                                    cljseq.loop/*mod-ctx*       mod-ctx#
                                    cljseq.loop/*step-mod-ctx*  step-mod-ctx#
                                    cljseq.loop/*harmony-ctx*   harmony-ctx#
                                    cljseq.loop/*chord-ctx*     chord-ctx#]
                            ~@body))
         sref#       (cljseq.loop/-system-ref)]
     ;; Clear a stale entry (loop was stopped but entry was not removed).
     ;; If we don't do this, re-using the name hot-swaps the fn into a dead
     ;; thread and the new body never executes.
     (when-let [entry# (get-in @@sref# [:loops ~loop-name])]
       (when-not @(:running? entry#)
         (swap! @sref# update :loops dissoc ~loop-name)))
     (if (get-in @@sref# [:loops ~loop-name])
       ;; Hot-swap: loop is running — update fn; thread picks it up next iteration
       (do
         (swap! @sref# assoc-in [:loops ~loop-name :fn] loop-fn#)
         ~loop-name)
       ;; First evaluation (or restart after stop): create entry and start thread
       (let [running?# (atom true)]
         (swap! @sref# assoc-in [:loops ~loop-name]
                {:fn         loop-fn#
                 :tick-count 0
                 :running?   running?#
                 :thread     nil})
         (let [t# (Thread.
                   (fn []
                     (let [start-beat# (cljseq.loop/-start-beat)]
                       (binding [cljseq.loop/*virtual-time* start-beat#]
                         ;; When Link is active, park until the quantum boundary
                         ;; so the loop enters the session in phase (§15.4).
                         (when (cljseq.link/active?)
                           (cljseq.loop/-park-until-beat! start-beat#))
                         (loop []
                           (when @running?#
                             (let [f# (get-in @@sref#
                                              [:loops ~loop-name :fn])]
                               (f#)
                               (swap! @sref#
                                      update-in [:loops ~loop-name :tick-count]
                                      inc))
                             (recur)))
                         ;; Self-cleanup: remove our state entry when exiting.
                         ;; Only remove if we're still the current entry — a new
                         ;; deflive-loop with the same name may have already
                         ;; started a fresh thread with a different running? atom.
                         (swap! @sref# update :loops
                                (fn [loops#]
                                  (if (identical? running?#
                                                  (get-in loops# [~loop-name :running?]))
                                    (dissoc loops# ~loop-name)
                                    loops#)))))))]
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
  "Stop a named loop. The thread finishes its current sleep then exits.

  Unparks the loop thread immediately so it wakes from sleep! without waiting
  for its current deadline — useful for tight test timing and REPL responsiveness."
  [loop-name]
  (when-let [s @system-ref]
    (when-let [{:keys [running? thread]} (get-in @s [:loops loop-name])]
      (reset! running? false)
      (when thread
        (LockSupport/unpark ^Thread thread))))
  nil)

(defn stop-all-loops!
  "Stop all running loops."
  []
  (when-let [s @system-ref]
    (doseq [[lname _] (:loops @s)]
      (stop-loop! lname)))
  nil)
