; SPDX-License-Identifier: EPL-2.0
(ns cljseq.timing
  "cljseq timing modulator namespace.

  Timing modulators are `ITemporalValue` instances that return Double
  fractional-beat offsets. `cljseq.core/play!` samples the active modulator
  at the current virtual-beat position and converts to nanoseconds via the
  running BPM timeline before dispatching to the sidecar.

  The two built-in modulators:

    swing     — delays notes in the 8th-note upbeat region (second half of
                each beat) by `amount - 0.5` beats. This shifts off-beat
                notes later in wall-clock time without changing virtual time,
                producing the characteristic groove feel. Range: 0.5 (straight)
                to ~0.667 (triplet swing).

    humanize  — adds uniform random scatter of ±amount beats to every note.
                Reduces machine-like precision.

  Both can be composed with `combine`:
    (combine (swing :amount 0.6) (humanize :amount 0.005))

  ## Usage in a live loop
    (deflive-loop :bass {:synth bass :timing (timing/swing :amount 0.6)}
      (play! :C3 1/4)
      (sleep! 1/4)
      (play! :E3 1/4)   ; upbeat — shifted by 0.1 beats wall-clock
      (sleep! 1/4))

  ## Ad-hoc at the REPL
    (dsl/with-timing (timing/swing :amount 0.55)
      (phrase! [:C4 :E4 :G4] :dur 1/4))

  Key design decisions: Q35 (timing offset applied before sidecar dispatch),
  Q46 (timing as ITemporalValue), R&R §28.7."
  (:require [cljseq.clock :as clock]))

;; ---------------------------------------------------------------------------
;; Swing
;;
;; 8th-note grid via floor arithmetic:
;;
;;   beat:       0    0.25   0.5   0.75   1.0   1.5
;;   8th index:  0     0      1     1      2     3
;;   role:      down  down   UP    UP    down    UP
;;   offset:     0     0    amt    amt    0     amt
;;
;; amt = amount - 0.5  (e.g. 0.55 - 0.5 = 0.05 beats)
;;
;; Any note with virtual-time in the second half of a beat is considered
;; an upbeat and receives the delay. Quarter-note patterns are unaffected
;; (they land on even 8th indices). Eighth-note patterns produce classic
;; swing feel.
;; ---------------------------------------------------------------------------

(defrecord Swing [amount]
  clock/ITemporalValue
  (sample [_ beat]
    (let [eighth-idx (long (Math/floor (* 2.0 (double beat))))]
      (if (even? eighth-idx)
        0.0
        (- (double amount) 0.5))))
  (next-edge [_ beat]
    ;; Next 8th-note boundary: (floor(beat×2) + 1) / 2
    (/ (+ (Math/floor (* 2.0 (double beat))) 1.0) 2.0)))

(defn swing
  "Return a swing timing modulator.

  `:amount` — swing ratio in [0.5, 1.0).
               0.5   = straight (no effect)
               0.55  = light swing
               0.667 ≈ triplet swing (classic jazz)
  Default: 0.55.

  Samples to a Double fractional-beat offset; `core/play!` converts to ns
  using the running BPM timeline."
  [& {:keys [amount] :or {amount 0.55}}]
  (->Swing (double amount)))

;; ---------------------------------------------------------------------------
;; Humanize
;;
;; Uniform random scatter ±amount beats on every sample call.
;; next-edge is not meaningful for a stochastic signal; returns beat+1.
;; ---------------------------------------------------------------------------

(defrecord Humanize [amount]
  clock/ITemporalValue
  (sample [_ _beat]
    (* (- (* 2.0 (Math/random)) 1.0) (double amount)))
  (next-edge [_ beat]
    (+ (double beat) 1.0)))

(defn humanize
  "Return a humanize timing modulator.

  `:amount` — maximum scatter in beats; result is uniformly random in
               [-amount, +amount]. Default: 0.01 (1% of a beat).

  At 120 BPM, 0.01 beats ≈ 5ms of scatter — subtle but perceptible."
  [& {:keys [amount] :or {amount 0.01}}]
  (->Humanize (double amount)))

;; ---------------------------------------------------------------------------
;; Combine — additive composition
;; ---------------------------------------------------------------------------

(defn combine
  "Combine timing modulators by summing their fractional-beat offsets.

  Usage:
    (combine (swing :amount 0.6) (humanize :amount 0.005))"
  [& mods]
  (reify clock/ITemporalValue
    (sample [_ beat]
      (reduce + 0.0 (map #(clock/sample % beat) mods)))
    (next-edge [_ beat]
      (reduce min Double/MAX_VALUE (map #(clock/next-edge % beat) mods)))))
