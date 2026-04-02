; SPDX-License-Identifier: EPL-2.0
(ns cljseq.mod
  "cljseq modulation subsystem — LFOs, envelopes, and one-shot signals.

  All modulators are `ITemporalValue` instances produced by composing a
  `Phasor` with a shape function from `cljseq.phasor`.

  ## LFO
    (lfo (->Phasor 1/16 0) phasor/sine-uni)    ; slow sine, unipolar [0,1]
    (lfo (->Phasor 1/8  0) phasor/triangle)    ; triangle, same rate family
    (lfo (->Phasor 1/4  0) (phasor/square 0.3)) ; 30% duty-cycle square

  ## Envelope (looping)
    (def adsr (envelope [[0.0 0.0] [0.1 1.0] [0.3 0.7] [0.8 0.7] [1.0 0.0]]))
    (lfo (->Phasor 1/4 0) adsr)   ; 4-beat looping envelope

  ## One-shot envelope
    (one-shot (->Phasor 1/4 0) adsr start-beat)

  ## Routing to ctrl
    (mod-route! [:filter/cutoff] (lfo (->Phasor 1/4 0) phasor/sine-uni))
    (mod-unroute! [:filter/cutoff])

  The runner samples the modulator at its natural `next-edge` rate and
  dispatches via `ctrl/send!`. The ctrl binding's :range handles scaling
  to the physical MIDI range — so the modulator's raw output (typically
  [0.0, 1.0] for unipolar shapes) should match the binding's :range.

  When a one-shot's `next-edge` returns ##Inf the runner auto-stops.

  Key design decisions: R&R §28.7, Q30 (LFO rate — empirical during impl)."
  (:require [cljseq.clock  :as clock]
            [cljseq.ctrl   :as ctrl]
            [cljseq.loop   :as loop-ns]
            [cljseq.phasor :as phasor])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state reference (injected by cljseq.core/start!)
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

(defn -register-system!
  "Called by cljseq.core/start! to inject the system-state atom.
  Not part of the public API."
  [state-atom]
  (reset! system-ref state-atom))

(defn- current-beat
  "Return the current beat position from the system timeline, or 0.0."
  ^double []
  (if-let [s @system-ref]
    (if-let [tl (:timeline @s)]
      (clock/epoch-ms->beat (System/currentTimeMillis) tl)
      0.0)
    0.0))

;; ---------------------------------------------------------------------------
;; Active routes: {path {:mod mod :running? atom :thread Thread}}
;; ---------------------------------------------------------------------------

(defonce ^:private routes (atom {}))

;; ---------------------------------------------------------------------------
;; Lfo — Phasor + shape fn -> ITemporalValue
;; ---------------------------------------------------------------------------

(defrecord Lfo [ph shape-fn]
  clock/ITemporalValue
  (sample    [_ beat] (shape-fn (clock/sample ph beat)))
  (next-edge [_ beat] (clock/next-edge ph beat)))

(defn lfo
  "Construct an LFO from a Phasor and a shape function.

  `ph`       — a Phasor (from cljseq.clock) controlling rate and phase offset
  `shape-fn` — a function [0.0, 1.0) -> number (from cljseq.phasor or user-defined)

  The same Phasor instance can drive multiple LFOs; they will be phase-locked.

  Examples:
    (lfo (->Phasor 1/16 0) phasor/sine-uni)        ; unipolar sine
    (lfo (->Phasor 1/16 0) phasor/triangle)        ; triangle, phase-locked to above
    (lfo (->Phasor 1/8  0) (phasor/square 0.5))    ; 50% square at half the rate"
  [ph shape-fn]
  (->Lfo ph shape-fn))

;; ---------------------------------------------------------------------------
;; envelope — piecewise-linear shape function from breakpoints
;; ---------------------------------------------------------------------------

(defn envelope
  "Build a piecewise-linear shape function from breakpoints.

  `breakpoints` — sequence of [phase value] pairs, phases in [0.0, 1.0],
                  strictly ascending. First phase must be 0.0; last must be 1.0.

  Returns a function (fn [p] -> number) suitable for use as an `lfo` shape.

  Example (ADSR):
    (def adsr
      (envelope [[0.00 0.0]    ; silence at start
                 [0.10 1.0]    ; attack peak at 10% of cycle
                 [0.30 0.7]    ; decay to sustain level
                 [0.80 0.7]    ; sustain hold
                 [1.00 0.0]])) ; release to silence

  Use with lfo:
    (lfo (->Phasor 1/4 0) adsr)   ; 4-beat looping envelope"
  [breakpoints]
  (let [pts (vec (sort-by first breakpoints))]
    (fn [p]
      (let [p    (double p)
            pair (->> (partition 2 1 pts)
                      (filter (fn [[[p0] [p1]]]
                                (<= (double p0) p (double p1))))
                      first)]
        (if pair
          (let [[[p0 v0] [p1 v1]] pair
                p0 (double p0) v0 (double v0)
                p1 (double p1) v1 (double v1)]
            (+ v0 (* (/ (- p p0) (- p1 p0)) (- v1 v0))))
          (double (second (last pts))))))))

;; ---------------------------------------------------------------------------
;; one-shot — ITemporalValue that clamps after the first phasor cycle
;; ---------------------------------------------------------------------------

(defrecord OneShot [ph shape-fn start-beat]
  clock/ITemporalValue
  (sample [_ beat]
    (let [end-beat (clock/next-edge ph (double start-beat))]
      (if (>= (double beat) end-beat)
        (shape-fn 1.0)
        (shape-fn (clock/sample ph beat)))))
  (next-edge [_ beat]
    (let [end-beat (clock/next-edge ph (double start-beat))]
      (if (>= (double beat) end-beat)
        ##Inf
        (clock/next-edge ph beat)))))

(defn one-shot
  "Wrap an LFO so that it runs once and then clamps at its final value.

  `ph`         — a Phasor controlling the envelope duration and rate
  `shape-fn`   — shape function (e.g. from envelope or phasor/*)
  `start-beat` — the beat at which the one-shot starts (typically (now))

  After the first complete phasor cycle, `next-edge` returns ##Inf and
  `sample` returns the clamped end value (shape-fn applied to 1.0).
  When routed via mod-route!, the runner auto-stops on ##Inf.

  Example:
    (one-shot (->Phasor 1/4 0) adsr (now))   ; 4-beat one-shot attack"
  [ph shape-fn start-beat]
  (->OneShot ph shape-fn (double start-beat)))

;; ---------------------------------------------------------------------------
;; Runner thread
;; ---------------------------------------------------------------------------

(defn- runner-loop
  "Main body of a mod runner thread for `path`."
  [path running?]
  (loop []
    (when @running?
      (let [beat (current-beat)
            mod  (:mod (get @routes path))
            next (when mod (clock/next-edge mod beat))]
        (cond
          (nil? next)
          nil                       ; route removed — exit

          (= ##Inf next)
          (do
            (reset! running? false)
            (swap! routes dissoc path))   ; one-shot finished — auto-stop and clean up

          :else
          (do
            (loop-ns/-park-until-beat! next)
            (when @running?
              (let [beat' (current-beat)
                    mod'  (:mod (get @routes path))]
                (when mod'
                  (ctrl/send! path (clock/sample mod' beat')))))
            (recur)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn mod-route!
  "Connect a modulator to a ctrl path. Starts a runner thread that samples
  the modulator at its natural `next-edge` rate and dispatches via `ctrl/send!`.

  `path` — ctrl tree path vector, e.g. [:filter/cutoff]
  `mod`  — any ITemporalValue (lfo, one-shot, swing, etc.)

  The raw sampled value is passed directly to `ctrl/send!`. Set the ctrl
  binding's `:range` to match the modulator's output domain — typically
  [0.0 1.0] for unipolar shapes (sine-uni, triangle, saw-up).

  Re-evaluating with the same path hot-swaps the modulator without restarting
  the runner thread. When a one-shot's `next-edge` returns ##Inf the runner
  auto-stops."
  [path mod]
  (if (get @routes path)
    (swap! routes assoc-in [path :mod] mod)
    (let [running? (atom true)]
      (swap! routes assoc path {:mod mod :running? running? :thread nil})
      (let [t (Thread.
               (fn [] (runner-loop path running?)))]
        (.setDaemon t true)
        (.setName t (str "cljseq-mod-" (clojure.string/join "/" (map name path))))
        (swap! routes assoc-in [path :thread] t)
        (.start t))))
  path)

(defn mod-unroute!
  "Stop the mod runner for `path` and remove the route.

  The runner finishes its current sleep then exits. Calling mod-unroute! on
  an unknown path is a no-op."
  [path]
  (when-let [route (get @routes path)]
    (reset! (:running? route) false)
    (when-let [^Thread t (:thread route)]
      (LockSupport/unpark t)))
  (swap! routes dissoc path)
  nil)

(defn mod-unroute-all!
  "Stop all active mod runners. Called by cljseq.core/stop!."
  []
  (doseq [path (keys @routes)]
    (mod-unroute! path))
  nil)

(defn mod-routes
  "Return a map of currently active mod routes: {path mod}.
  Useful for inspection at the REPL."
  []
  (into {} (map (fn [[path r]] [path (:mod r)]) @routes)))
