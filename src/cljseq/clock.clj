; SPDX-License-Identifier: EPL-2.0
(ns cljseq.clock
  "cljseq clock subsystem — ITemporalValue protocol, Phasor record, BPM utilities.

  `ITemporalValue` is the single protocol for all time-varying values in cljseq:
  phasors, LFOs, envelopes, swing modulators, and any user-defined signal.

  `Phasor` is the canonical implementation: a unipolar ramp in [0.0, 1.0) at a
  given rate in cycles per beat. All clock and modulation signals are Phasors or
  transformations of Phasors.

  MasterClock and ClockDiv from the ITemporalValue spike (Q44) are superseded by
  `master-clock`, `clock-div`, `clock-mul`, and `clock-shift` — all of which
  return Phasor instances.

  Key design decisions: R&R §28 (Phasor Signal Architecture), Q44 (ITemporalValue),
  Q1 (virtual time), Q35 (timing returns Long nanoseconds)."
  (:require [cljseq.phasor :as phasor]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol ITemporalValue
  "A value that varies over musical time.

  `beat` is a rational number representing the current beat position.
  Contract:
    - `sample` must be pure and allocation-free on the hot path
    - `next-edge` must return a beat strictly greater than the input beat"
  (sample    [v beat] "Sample the value at `beat`. Returns a number.")
  (next-edge [v beat] "Return the beat of the next 0→1 rising edge after `beat`."))

;; ---------------------------------------------------------------------------
;; Phasor record
;; ---------------------------------------------------------------------------

(defrecord Phasor [rate phase-offset]
  ITemporalValue
  (sample [_ beat]
    (phasor/wrap (+ (* (double rate) (double beat))
                    (double phase-offset))))
  (next-edge [_ beat]
    ;; Edge occurs when beat×rate + offset crosses an integer.
    ;; Next integer above current accumulated phase:
    (let [accumulated (+ (* (double rate) (double beat))
                         (double phase-offset))
          next-n      (+ (Math/floor accumulated) 1.0)]
      (/ (- next-n (double phase-offset)) (double rate)))))

;; ---------------------------------------------------------------------------
;; Named clock constructors
;; ---------------------------------------------------------------------------

(def master-clock
  "The reference phasor: one cycle per beat.
  Equivalent to the former MasterClock record."
  (->Phasor 1 0))

(defn clock-div
  "Return a Phasor that completes one cycle every `n` beats.
  Equivalent to the former ClockDiv record.

  Examples:
    (clock-div 4)   ; one cycle per 4 beats (bar-rate at 4/4)
    (clock-div 16)  ; one cycle per 16 beats (phrase-rate)"
  [n]
  (->Phasor (/ 1.0 (double n)) 0))

(defn clock-mul
  "Return a Phasor that completes `n` cycles per beat.

  Examples:
    (clock-mul 4)   ; four triggers per beat (sixteenth notes)
    (clock-mul 3/2) ; 3:2 polyrhythm against master-clock"
  [n]
  (->Phasor (double n) 0))

(defn clock-shift
  "Return a Phasor at the same rate as (clock-div n) but shifted by phase-offset.
  phase-offset is in [0.0, 1.0): 0.5 shifts the clock by half a cycle.

  Useful for offsetting two voices that share a clock rate."
  [n phase-offset]
  (->Phasor (/ 1.0 (double n)) (double phase-offset)))

;; ---------------------------------------------------------------------------
;; BPM / time conversion utilities
;; ---------------------------------------------------------------------------

(defn bpm->ms-per-beat
  "Return wall-clock milliseconds per beat at `bpm`."
  ^double [bpm]
  (/ 60000.0 (double bpm)))

(defn beats->ms
  "Convert `beats` (rational or double) to milliseconds at `bpm`."
  ^double [beats bpm]
  (* (double beats) (bpm->ms-per-beat bpm)))

(defn now-ns
  "Current wall-clock time in nanoseconds."
  ^long []
  (System/nanoTime))

;; ---------------------------------------------------------------------------
;; Beat ↔ epoch-ms conversion (requires a timeline map)
;; ---------------------------------------------------------------------------

;; A timeline map has the shape:
;;   {:bpm            <number>     ; current BPM
;;    :beat0-epoch-ms <long>       ; epoch-ms at which beat0-beat occurred
;;    :beat0-beat     <double>}    ; beat position corresponding to beat0-epoch-ms
;;
;; The anchor rolls on every BPM change (see cljseq.core/set-bpm!), so the
;; linear mapping is always valid within the current tempo segment.

(defn beat->epoch-ms
  "Convert an absolute beat position to an epoch-millisecond wall-clock deadline.

  `timeline` is the map held at [:timeline] in the system-state atom.
  The result is suitable for `LockSupport/parkUntil` (Q59)."
  ^long [^double target-beat timeline]
  (let [bpm             (double (:bpm timeline))
        beat0-epoch-ms  (double (:beat0-epoch-ms timeline))
        beat0-beat      (double (:beat0-beat timeline))]
    (long (Math/round (+ beat0-epoch-ms
                         (* (- target-beat beat0-beat)
                            (bpm->ms-per-beat bpm)))))))

(defn epoch-ms->beat
  "Convert an epoch-millisecond timestamp to a beat position using `timeline`."
  ^double [^long epoch-ms timeline]
  (let [bpm             (double (:bpm timeline))
        beat0-epoch-ms  (double (:beat0-epoch-ms timeline))
        beat0-beat      (double (:beat0-beat timeline))]
    (+ beat0-beat
       (/ (- (double epoch-ms) beat0-epoch-ms)
          (bpm->ms-per-beat bpm)))))

(defn beat->epoch-ns
  "Convert an absolute beat position to epoch-nanoseconds.
  Suitable for the sidecar `time_ns` wire field (C++ system_clock epoch)."
  ^long [^double target-beat timeline]
  (* (beat->epoch-ms target-beat timeline) 1000000))
