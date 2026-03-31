; SPDX-License-Identifier: EPL-2.0
(ns cljseq.phasor
  "Pure phasor math — the foundational signal vocabulary for cljseq.

  A unipolar phasor is a linear ramp in [0.0, 1.0) cycling at a given rate.
  All periodic signals in cljseq (LFOs, clocks, envelopes, step sequences)
  are derived by applying operations from this namespace to a phasor value.

  No dependencies. All functions are pure transformations over doubles.

  Key design decisions: R&R §28 (Phasor Signal Architecture).")

;; ---------------------------------------------------------------------------
;; Core operations
;; ---------------------------------------------------------------------------

(defn wrap
  "Wrap x into [0.0, 1.0). The fundamental phasor normalisation operation."
  ^double [x]
  (let [x (double x)]
    (- x (Math/floor x))))

(defn fold
  "Fold p into [0.0, 1.0] with triangle/ping-pong reflection.
  Input p is assumed to already be in [0.0, 1.0) (i.e. already wrapped).
  Result: 0→1→0 triangle shape over one phasor cycle."
  ^double [p]
  (let [p (double p)]
    (- 1.0 (Math/abs (- (* 2.0 p) 1.0)))))

(defn scale
  "Map p ∈ [0.0, 1.0) to the range [lo, hi)."
  ^double [p lo hi]
  (+ (double lo) (* (double p) (- (double hi) (double lo)))))

(defn index
  "Return the integer step index for phase p in an n-step sequence.
  Result is in [0, n)."
  ^long [p n]
  (long (Math/floor (* (double p) (double n)))))

(defn step
  "Quantise p to n equal steps. Returns a staircase value in [0.0, 1.0)."
  ^double [p n]
  (/ (Math/floor (* (double p) (double n))) (double n)))

(defn delta
  "Return 1 if a wrap occurred between p-prev and p (i.e. p < p-prev),
  0 otherwise. Fires once per phasor cycle at the 0-crossing."
  ^long [p p-prev]
  (if (< (double p) (double p-prev)) 1 0))

(defn phase-reset
  "Return 0.0 (reset phase) if trigger is positive, otherwise return p unchanged.
  Models a hard sync input: a positive trigger forces the phasor back to 0."
  ^double [p trigger]
  (if (pos? (double trigger)) 0.0 (double p)))

;; ---------------------------------------------------------------------------
;; Shape functions
;;
;; All accept a phasor value p ∈ [0.0, 1.0) and return a number.
;; Unipolar shapes return [0.0, 1.0]; bipolar shapes return [-1.0, 1.0].
;; ---------------------------------------------------------------------------

(defn sine-uni
  "Unipolar sine: maps phasor to [0.0, 1.0]."
  ^double [p]
  (* 0.5 (+ 1.0 (Math/sin (* 2.0 Math/PI (double p))))))

(defn sine-bi
  "Bipolar sine: maps phasor to [-1.0, 1.0]."
  ^double [p]
  (Math/sin (* 2.0 Math/PI (double p))))

(defn triangle
  "Unipolar triangle: maps phasor to [0.0, 1.0] with linear rise and fall.
  Equivalent to fold(p)."
  ^double [p]
  (fold p))

(defn saw-up
  "Upward sawtooth: the phasor itself. Maps [0.0, 1.0) → [0.0, 1.0)."
  ^double [p]
  (double p))

(defn saw-down
  "Downward sawtooth: inverse phasor. Maps [0.0, 1.0) → (0.0, 1.0]."
  ^double [p]
  (- 1.0 (double p)))

(defn square
  "Return a square wave shape function with pulse width pw ∈ [0.0, 1.0).
  The returned fn maps a phasor value to 1.0 if p < pw, else 0.0.

  Usage: ((square 0.5) p)   ; 50% duty cycle"
  [pw]
  (let [pw (double pw)]
    (fn ^double [p] (if (< (double p) pw) 1.0 0.0))))
