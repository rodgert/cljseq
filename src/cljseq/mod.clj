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

  Key design decisions: R&R §28.7, Q30 (LFO rate — empirical during impl)."
  (:require [cljseq.clock  :as clock]
            [cljseq.phasor :as phasor]))

;; ---------------------------------------------------------------------------
;; Lfo — Phasor + shape fn → ITemporalValue
;; ---------------------------------------------------------------------------

(defrecord Lfo [ph shape-fn]
  clock/ITemporalValue
  (sample    [_ beat] (shape-fn (clock/sample ph beat)))
  (next-edge [_ beat] (clock/next-edge ph beat)))

(defn lfo
  "Construct an LFO from a Phasor and a shape function.

  `ph`       — a Phasor (from cljseq.clock) controlling rate and phase offset
  `shape-fn` — a function [0.0, 1.0) → number (from cljseq.phasor or user-defined)

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
      (let [p (double p)
            ;; Find the pair of breakpoints surrounding p
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
        ;; Clamped: return the shape value at phase 1.0 (end of envelope)
        (shape-fn 1.0)
        (shape-fn (clock/sample ph beat)))))
  (next-edge [_ beat]
    (let [end-beat (clock/next-edge ph (double start-beat))]
      (if (>= (double beat) end-beat)
        ##Inf       ; signal to scheduler: no further wakeup needed
        (clock/next-edge ph beat)))))

(defn one-shot
  "Wrap an LFO so that it runs once and then clamps at its final value.

  `ph`         — a Phasor controlling the envelope duration and rate
  `shape-fn`   — shape function (e.g. from envelope or phasor/*)
  `start-beat` — the beat at which the one-shot starts (typically (now))

  After the first complete phasor cycle, `next-edge` returns ##Inf and
  `sample` returns the clamped end value (shape-fn applied to 1.0).

  Example:
    (one-shot (->Phasor 1/4 0) adsr (now))   ; 4-beat one-shot attack"
  [ph shape-fn start-beat]
  (->OneShot ph shape-fn (double start-beat)))
