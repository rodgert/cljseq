; SPDX-License-Identifier: EPL-2.0
(ns cljseq.random
  "cljseq probability and distribution primitives.

  Provides the stochastic building blocks used by `cljseq.stochastic`:
  spread/bias distributions, weighted scales, progressive quantization,
  and scale-weight learning from note sequences.

  ## Distribution sampling
    (sample-distribution 0.5 0.5)     ; Gaussian, center of range -> value in [0,1]
    (sample-distribution 0.0 0.7)     ; constant at 0.7
    (sample-distribution 1.0 0.5)     ; uniform random

  ## Weighted scales
    (weighted-scale :major)                              ; uniform weights
    (weighted-scale :major {:tonic 1.0 :seventh 0.1})   ; custom weights
    (learn-scale-weights [60 62 64 60 62] :scale-kw :major)

  ## Progressive quantization
    (progressive-quantize 61 (weighted-scale :major) 0.7) ; C# -> C or D

  Key design decisions: R&R §23.4-23.5, Q36 (weighted scale as map extension)."
  (:require [cljseq.pitch :as pitch]))

;; ---------------------------------------------------------------------------
;; Scale definitions
;; ---------------------------------------------------------------------------

(def ^:private scale-intervals
  "Semitone offsets for each scale degree, root = 0."
  {:major       [0 2 4 5 7 9 11]
   :minor        [0 2 3 5 7 8 10]
   :dorian       [0 2 3 5 7 9 10]
   :phrygian     [0 1 3 5 7 8 10]
   :lydian       [0 2 4 6 7 9 11]
   :mixolydian   [0 2 4 5 7 9 10]
   :locrian      [0 1 3 5 6 8 10]
   :pentatonic   [0 2 4 7 9]
   :minor-pent   [0 3 5 7 10]
   :chromatic    [0 1 2 3 4 5 6 7 8 9 10 11]})

(def ^:private degree-names
  "Degree name keyword -> index in scale-intervals vector."
  {:tonic 0 :second 1 :third 2 :fourth 3 :fifth 4 :sixth 5 :seventh 6})

(def ^:private default-degree-weights
  "Musically-motivated default weights: root/fifth most stable, seventh least."
  [1.0 0.6 0.8 0.5 0.9 0.4 0.2])

;; ---------------------------------------------------------------------------
;; Gaussian sampling — Box-Muller transform
;; ---------------------------------------------------------------------------

(defn rand-gaussian
  "Return a sample from the standard normal distribution N(0,1).
  Uses the Box-Muller transform."
  ^double []
  (* (Math/sqrt (* -2.0 (Math/log (max 1e-10 (rand)))))
     (Math/cos (* 2.0 Math/PI (rand)))))

;; ---------------------------------------------------------------------------
;; Distribution model (§23.4)
;; ---------------------------------------------------------------------------

(defn sample-distribution
  "Draw one sample from a spread/bias distribution, returning a value in [0.0, 1.0].

  `spread` — distribution width [0.0=constant, 0.5=Gaussian, 1.0=uniform]
  `bias`   — center of distribution [0.0-1.0]

  Interpolates between a Gaussian centered at `bias` and a uniform draw
  as spread approaches 1.0. At spread=0.0 returns `bias` exactly."
  ^double [spread bias]
  (let [s (double spread)
        b (double bias)]
    (cond
      (<= s 0.0) b
      (>= s 1.0) (rand)
      :else
      (let [gauss (max 0.0 (min 1.0 (+ b (* s 0.3 (rand-gaussian)))))
            unif  (rand)]
        (+ (* (- 1.0 s) gauss) (* s unif))))))

;; ---------------------------------------------------------------------------
;; Weighted scale (Q36)
;; ---------------------------------------------------------------------------

(defn weighted-scale
  "Create a weighted scale map for use with progressive-quantize.

  `scale-kw`   — scale type keyword (:major, :minor, :dorian, etc.)
  `weight-map` — optional map of degree-name keyword -> weight [0.0-1.0].
                 Degree names: :tonic :second :third :fourth :fifth :sixth :seventh
                 Missing degrees use musically-motivated defaults.

  Returns {:scale/type kw :intervals [semitones] :weights [floats]}

  Examples:
    (weighted-scale :major)
    (weighted-scale :major {:tonic 1.0 :seventh 0.05})
    (weighted-scale :dorian {:fifth 1.0 :tonic 0.9 :second 0.3})"
  ([scale-kw]
   (weighted-scale scale-kw {}))
  ([scale-kw weight-map]
   (let [intervals (get scale-intervals scale-kw [0 2 4 5 7 9 11])
         n         (count intervals)
         defaults  (vec (take n default-degree-weights))
         weights   (reduce (fn [ws [degree-name w]]
                             (if-let [idx (get degree-names degree-name)]
                               (if (< idx n) (assoc ws idx (double w)) ws)
                               ws))
                           defaults
                           weight-map)]
     {:scale/type scale-kw
      :intervals  intervals
      :weights    weights})))

;; ---------------------------------------------------------------------------
;; Progressive quantization (§23.4 — steps parameter)
;; ---------------------------------------------------------------------------

(defn- step-intervals->offsets
  "Convert step intervals (e.g. [2 2 1 2 2 2 1] for major) to cumulative
  pitch-class offsets from root (e.g. [0 2 4 5 7 9 11])."
  [step-intervals]
  (vec (butlast
         (reduce (fn [acc x] (conj acc (+ (peek acc) x)))
                 [0]
                 step-intervals))))

(defn- scale->intervals-weights
  "Extract [intervals weights] from either a weighted-scale map or a
  cljseq.scale/Scale record. Scale records get uniform weights (1.0 each).

  weighted-scale maps already store cumulative offsets in :intervals.
  Scale records store step intervals in :intervals — these are converted."
  [s]
  (if (:weights s)
    ;; weighted-scale map — :intervals already cumulative offsets
    [(:intervals s) (:weights s)]
    ;; Scale record — :intervals are step sizes, convert to cumulative offsets
    (let [offsets (step-intervals->offsets (:intervals s))]
      [offsets (vec (repeat (count offsets) 1.0))])))

(defn progressive-quantize
  "Quantize `midi-note` to a scale controlled by `steps`.

  `midi-note` — integer MIDI note [0-127]
  `s`         — weighted-scale map (from weighted-scale) OR a cljseq.scale/Scale
                record. Scale records use uniform weights (every degree equally
                likely). Weighted-scale maps honour per-degree weights.
  `steps`     — quantization depth [0.0-1.0]
                <= 0.5: raw (no quantization)
                >  0.5: quantize with threshold = (steps-0.5)*2
                =  1.0: only highest-weight degree (tonic) survives

  Returns the quantized MIDI note in the same octave as the input."
  [midi-note s steps]
  (let [[intervals weights] (scale->intervals-weights s)]
    (if (<= (double steps) 0.5)
      (int midi-note)
      (let [threshold (* (- (double steps) 0.5) 2.0)
            eligible  (for [[iv w] (map vector intervals weights)
                            :when (>= (double w) threshold)]
                        (int iv))
            eligible  (if (empty? eligible) [(first intervals)] eligible)
            pc        (mod (int midi-note) 12)
            octave    (* 12 (quot (int midi-note) 12))
            best      (apply min-key
                             (fn [iv]
                               (let [d (Math/abs ^long (- (long pc) (long iv)))]
                                 (min d (- 12 d))))
                             eligible)]
        (+ octave (int best))))))

;; ---------------------------------------------------------------------------
;; Scale weight learning from a note corpus
;; ---------------------------------------------------------------------------

(defn learn-scale-weights
  "Build a weighted-scale from a sequence of MIDI notes.

  Computes a frequency histogram over scale degrees (notes not in the scale
  are snapped to the nearest degree). Normalises counts so the most frequent
  degree has weight 1.0.

  Options:
    :scale-kw — scale type keyword (default :major)

  Example:
    (learn-scale-weights [60 62 64 60 62 60 67 65])
    ;=> {:scale/type :major :intervals [0 2 4 5 7 9 11] :weights [1.0 0.5 ...]}"
  [midi-notes & {:keys [scale-kw scale] :or {scale-kw :major}}]
  (let [intervals (or (and scale (step-intervals->offsets (:intervals scale)))
                      (get scale-intervals scale-kw [0 2 4 5 7 9 11]))
        snap      (fn [pc]
                    (apply min-key
                           (fn [iv] (let [d (Math/abs ^long (- (long pc) (long iv)))]
                                      (min d (- 12 d))))
                           intervals))
        snapped   (map #(snap (mod (int %) 12)) midi-notes)
        counts    (frequencies snapped)
        max-count (apply max 1 (vals counts))
        weights   (mapv #(/ (double (get counts % 0)) max-count) intervals)]
    {:scale/type scale-kw
     :intervals  intervals
     :weights    weights}))
