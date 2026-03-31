; SPDX-License-Identifier: EPL-2.0
(ns cljseq.clock
  "cljseq clock subsystem — ITemporalValue protocol and BPM utilities.

  Promoted from cljseq.spike.temporal (Q44 spike verified).

  `ITemporalValue` is the single protocol for all time-varying values:
  clocks, LFOs, envelopes, swing modulators. See doc/spike-itemporalvalue.md.

  Key design decisions: Q44 (ITemporalValue protocol), Q1 (virtual time),
  Q35 (timing returns Long nanoseconds).")

;; ---------------------------------------------------------------------------
;; Protocol (promoted from spike)
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
;; Master clock
;; ---------------------------------------------------------------------------

(defrecord MasterClock [bpm]
  ITemporalValue
  (sample    [_ _beat] 1.0)
  (next-edge [_ beat]  (inc (long beat))))

(defn master-clock
  "Construct a master clock at `bpm` beats per minute.
  Ticks on every integer beat boundary."
  [bpm]
  (->MasterClock bpm))

;; ---------------------------------------------------------------------------
;; Clock divider
;; ---------------------------------------------------------------------------

(defrecord ClockDiv [divisor source]
  ITemporalValue
  (sample    [_ beat]
    (if (zero? (mod (long beat) divisor)) 1.0 0.0))
  (next-edge [_ beat]
    (* divisor (inc (long (/ beat divisor))))))

(defn clock-div
  "Divide `source` clock by `divisor`.
  Returns high (1.0) only on beats that are multiples of divisor."
  [divisor source]
  (->ClockDiv divisor source))

;; ---------------------------------------------------------------------------
;; LFO
;; ---------------------------------------------------------------------------

(defrecord Lfo [rate shape]
  ITemporalValue
  (sample    [_ beat]
    (Math/sin (* 2.0 Math/PI (double rate) (double beat))))
  (next-edge [_ beat]
    (let [cycle   (/ 1.0 (double rate))
          current (mod (double beat) cycle)]
      (+ (double beat) (- cycle current)))))

(defn lfo
  "Construct a sine LFO. :rate = cycles per beat (default 1.0)."
  [& {:keys [rate shape] :or {rate 1.0 shape :sine}}]
  (->Lfo rate shape))

;; ---------------------------------------------------------------------------
;; Swing timing modulator
;; ---------------------------------------------------------------------------

(defrecord Swing [amount]
  ITemporalValue
  (sample    [_ beat]
    (let [phase (mod (double beat) 1.0)]
      (if (> phase 0.4)
        (long (* (double amount) 20000000))
        0)))
  (next-edge [_ beat]
    (inc (long beat))))

(defn swing
  "Construct a swing timing modulator.
  :amount [0.0 1.0] — proportion of off-beat interval to delay.
  Returns Long nanosecond offsets (Q35)."
  [& {:keys [amount] :or {amount 0.5}}]
  (->Swing amount))

;; ---------------------------------------------------------------------------
;; BPM / time conversion utilities
;; ---------------------------------------------------------------------------

(defn bpm->ms-per-beat
  "Return wall-clock milliseconds per beat at `bpm`."
  [bpm]
  (/ 60000.0 (double bpm)))

(defn beats->ms
  "Convert `beats` (rational) to milliseconds at `bpm`."
  [beats bpm]
  (* (double beats) (bpm->ms-per-beat bpm)))

(defn now-ns
  "Current wall-clock time in nanoseconds."
  ^long []
  (System/nanoTime))
