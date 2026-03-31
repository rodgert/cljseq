; SPDX-License-Identifier: EPL-2.0
(ns cljseq.random
  "cljseq probability and distribution primitives.

  Provides the stochastic building blocks used by `cljseq.stochastic`:
  probability distributions, weighted scales, corpus-learned weights,
  and progressive quantization.

  ## Core distribution model
    (distribution :spread 0.5 :bias 0.6)  ;=> sampler fn (fn [] -> [0,1])

  ## Weighted scales
    (weighted-scale :C :major {:C 0.3 :E 0.2 :G 0.2 ...})
    (learn-scale-weights notes :key :C)

  ## Sampling
    (draw dist)                   ; draw one sample from distribution
    (progressive-quantize v scale steps)

  Key design decisions: R&R §23, Phase 6i implementation prerequisites.")

;; ---------------------------------------------------------------------------
;; Distribution model
;;
;; A distribution is a sampler function (fn [] -> Float [0.0 1.0]).
;; The :spread parameter controls the shape:
;;   0.0 = point mass (always returns :bias)
;;   0.25 = gaussian (tight cluster around :bias)
;;   0.5–0.75 = uniform (spread across range)
;;   1.0 = quantized (returns only the 5 canonical scale degree values)
;; The :bias parameter shifts the center of the distribution.
;; ---------------------------------------------------------------------------

;; (defn distribution [& {:keys [spread bias] :or {spread 0.5 bias 0.5}}] ...)

;; ---------------------------------------------------------------------------
;; Weighted scale
;;
;; Extends the standard scale map (R&R §4) with :scale/weights — a map from
;; pitch class keyword to salience weight (0.0–1.0).
;; Higher weight = more likely to be selected by stochastic-sequence.
;;
;; Weights need not sum to 1.0; they are normalised at draw time.
;; ---------------------------------------------------------------------------

;; (defn weighted-scale [scale-type weights] ...)

;; ---------------------------------------------------------------------------
;; Scale weight learning
;;
;; Builds a weighted scale from a sequence of MIDI notes by computing a
;; pitch-class histogram and normalising to weights.
;; ---------------------------------------------------------------------------

;; (defn learn-scale-weights [notes & {:keys [key]}] ...)

;; ---------------------------------------------------------------------------
;; Progressive quantization
;;
;; Maps a continuous value in [0.0, 1.0] to a scale degree, where the
;; :steps parameter controls how many degrees survive:
;;   steps=0.0 -> only the tonic survives (maximum quantization)
;;   steps=0.5 -> pentatonic subset
;;   steps=1.0 -> all scale degrees (chromatic, no quantization)
;; ---------------------------------------------------------------------------

;; (defn progressive-quantize [value weighted-scale steps] ...)

;; ---------------------------------------------------------------------------
;; Convenience: draw a single sample
;; ---------------------------------------------------------------------------

;; (defn draw [distribution] ((distribution)))
