; SPDX-License-Identifier: EPL-2.0
(ns cljseq.timing
  "cljseq timing modulator namespace.

  Timing modulators are `ITemporalValue` instances that return Long nanosecond
  offsets. They are applied additively to the scheduled event time (`time_ns`)
  after BPM-based beat-to-wall-clock conversion.

  All timing modulators use phasor arithmetic internally for beat-phase detection.

  ## Swing
    (swing :amount 0.55)    ; 55% swing — delays off-beats by 55% of a half-beat

  ## Humanize (Phase 1+)
    (humanize :amount 0.02) ; random ±2% timing scatter

  Key design decisions: Q35 (timing returns Long ns), Q46 (timing as ITemporalValue),
  R&R §28 (phasor model for beat-phase detection)."
  (:require [cljseq.clock  :as clock]
            [cljseq.phasor :as phasor]))

;; ---------------------------------------------------------------------------
;; Swing
;; ---------------------------------------------------------------------------

(defrecord Swing [amount]
  clock/ITemporalValue
  (sample [_ beat]
    ;; Off-beat detection: phase of the beat phasor > 0.4
    ;; (phasor/wrap beat) gives position within the current beat [0.0, 1.0)
    (let [phase (phasor/wrap (double beat))]
      (if (> phase 0.4)
        (long (* (double amount) 20000000))   ; up to ~20ms at amount=1.0
        0)))
  (next-edge [_ beat]
    ;; Next beat boundary
    (+ (Math/floor (double beat)) 1.0)))

(defn swing
  "Construct a swing timing modulator.

  :amount  [0.0, 1.0] — proportion of the off-beat interval to delay late.
  Returns Long nanosecond offsets suitable for the time_ns pipeline (Q35).

  At amount=0.5 the off-beat is delayed by 50% of the half-beat interval,
  giving a classic shuffle feel. At amount=0.55–0.67 the feel is closer to
  a jazz triplet swing."
  [& {:keys [amount] :or {amount 0.5}}]
  (->Swing amount))

;; ---------------------------------------------------------------------------
;; Humanize (stub — Phase 1)
;; ---------------------------------------------------------------------------

;; (defn humanize [& {:keys [amount] :or {amount 0.01}}] ...)
;;
;; Returns random nanosecond scatter ± (amount × beat-duration-ns).
;; Implemented in Phase 1 when the timing pipeline is wired.
