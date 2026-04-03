; SPDX-License-Identifier: EPL-2.0
(ns cljseq.scale
  "Scale as a rich value type (§4.3).

  A Scale pairs a root Pitch with an interval sequence that defines its
  step-pattern. The IScale protocol provides polymorphic dispatch for
  pitch-set enumeration, degree-aware navigation, and transposition.

  ## Constructors
    (scale :C 4 :major)           ; C major, root C4
    (scale :D 3 :dorian)          ; D dorian, root D3
    (make-scale root intervals)   ; from explicit interval vector
    (named-scale name)            ; {:intervals [...] :weights [...]} map

  ## IScale protocol
    (scale-pitches   s)            ; ordered Pitch seq from root
    (pitch-at        s degree)     ; Pitch at 0-indexed scale degree
    (degree-of       s pitch)      ; degree index or nil
    (next-pitch      s pitch n)    ; n steps up/down from pitch
    (transpose-scale s semitones)  ; shift root by semitones

  ## Utility
    (scale-names)     ; list all named scales
    (in-scale? s p)   ; is Pitch p a member of scale s?

  Key design decisions: R&R §4.3."
  (:require [clojure.string  :as str]
            [cljseq.pitch    :as pitch]
            [cljseq.interval :as iv]))

;; ---------------------------------------------------------------------------
;; IScale protocol
;; ---------------------------------------------------------------------------

(defprotocol IScale
  (scale-pitches   [s]         "Return ordered seq of Pitches from root through one octave.")
  (pitch-at        [s degree]  "Return the Pitch at 0-based scale degree `degree`.")
  (degree-of       [s pitch]   "Return 0-based degree of `pitch` in scale, or nil.")
  (next-pitch      [s p n]     "Return pitch `n` diatonic steps above/below `p` in scale.")
  (transpose-scale [s semis]   "Return new scale with root shifted by `semis` semitones."))

;; ---------------------------------------------------------------------------
;; Scale record
;; ---------------------------------------------------------------------------

(defrecord Scale [root intervals])
;; root      — Pitch record
;; intervals — vector of semitone integers (step pattern, not cumulative)
;;             e.g. [2 2 1 2 2 2 1] for major

;; ---------------------------------------------------------------------------
;; Named scale library (35 scales, R&R §4.3)
;; ---------------------------------------------------------------------------

(def ^:private scale-library
  {:major              [2 2 1 2 2 2 1]
   :natural-minor      [2 1 2 2 1 2 2]
   :harmonic-minor     [2 1 2 2 1 3 1]
   :melodic-minor      [2 1 2 2 2 2 1]   ; ascending form
   :dorian             [2 1 2 2 2 1 2]
   :phrygian           [1 2 2 2 1 2 2]
   :lydian             [2 2 2 1 2 2 1]
   :mixolydian         [2 2 1 2 2 1 2]
   :locrian            [1 2 2 1 2 2 2]
   :whole-tone         [2 2 2 2 2 2]
   :diminished         [2 1 2 1 2 1 2 1] ; whole-half
   :diminished-wh      [1 2 1 2 1 2 1 2] ; half-whole (dominant dim)
   :augmented          [3 1 3 1 3 1]
   :pentatonic-major   [2 2 3 2 3]
   :pentatonic-minor   [3 2 2 3 2]
   :pentatonic-blues   [3 2 1 1 3 2]
   :pentatonic-neutral [2 3 2 2 3]
   :blues              [3 2 1 1 3 2]
   :bebop-dominant     [2 2 1 2 2 1 1 1]
   :bebop-major        [2 2 1 2 1 1 2 1]
   :bebop-minor        [2 1 2 2 1 1 2 1]
   :bebop-dorian       [2 1 1 1 2 2 1 2]
   :lydian-dominant    [2 2 2 1 2 1 2]
   :lydian-augmented   [2 2 2 2 1 2 1]
   :altered            [1 2 1 2 2 2 2]   ; super-locrian
   :phrygian-dominant  [1 3 1 2 1 2 2]
   :double-harmonic    [1 3 1 2 1 3 1]
   :hungarian-minor    [2 1 3 1 1 3 1]
   :hungarian-major    [3 1 2 1 2 1 2]
   :romanian-minor     [2 1 3 1 2 1 2]
   :neapolitan-major   [1 2 2 2 2 2 1]
   :neapolitan-minor   [1 2 2 2 1 3 1]
   :enigmatic          [1 3 2 2 2 1 1]
   :prometheus         [2 2 2 3 1 2]
   :in-scale           [1 4 2 1 4]       ; Japanese in
   :hirajoshi          [2 1 4 1 4]
   :iwato              [1 4 1 4 2]
   :yo                 [2 3 2 2 3]
   :chromatic          [1 1 1 1 1 1 1 1 1 1 1 1]})

;; ---------------------------------------------------------------------------
;; Cumulative offset helper
;; ---------------------------------------------------------------------------

(defn- cumulative
  "Convert step intervals to cumulative semitone offsets from root.
  Returns vector of length (count intervals) + 1, starting with 0."
  [intervals]
  (reduce (fn [acc x] (conj acc (+ (peek acc) x)))
          [0]
          intervals))

;; ---------------------------------------------------------------------------
;; IScale implementation for Scale record
;; ---------------------------------------------------------------------------

(extend-type Scale
  IScale

  (scale-pitches [s]
    (let [offsets (butlast (cumulative (:intervals s)))]
      (mapv (fn [semis]
              (pitch/transpose (:root s) semis
                               (if (>= semis 0) :sharp :flat)))
            offsets)))

  (pitch-at [s degree]
    (let [intervals (:intervals s)
          n-steps   (count intervals)
          degree    (int degree)
          ;; Use floor division so negative degrees go one octave below
          octaves   (Math/floorDiv degree n-steps)
          idx       (Math/floorMod degree n-steps)
          offsets   (cumulative intervals)
          semis     (+ (nth offsets idx) (* 12 octaves))]
      (pitch/transpose (:root s) semis
                       (if (>= semis 0) :sharp :flat))))

  (degree-of [s p]
    (let [offsets (butlast (cumulative (:intervals s)))
          root-ps (pitch/pitch->ps (:root s))
          p-ps    (pitch/pitch->ps p)
          diff    (- p-ps root-ps)
          octave  (int (Math/floor (/ diff 12)))
          within  (mod diff 12)]
      (when-let [idx (first (keep-indexed
                              (fn [i o] (when (== (double within) (double o)) i))
                              offsets))]
        (+ idx (* octave (count (:intervals s)))))))

  (next-pitch [s p n]
    (let [deg (degree-of s p)]
      (if deg
        (pitch-at s (+ deg n))
        ;; p not in scale — snap to nearest degree then step
        (let [ps       (pitch/pitch->ps p)
              root-ps  (pitch/pitch->ps (:root s))
              diff     (- ps root-ps)
              offsets  (butlast (cumulative (:intervals s)))
              n-steps  (count (:intervals s))
              octave   (int (/ diff 12))
              within   (mod diff 12)
              nearest  (apply min-key #(Math/abs (- (double within) (double %)))
                              offsets)
              idx      (first (keep-indexed #(when (== (double nearest) (double %2)) %1) offsets))
              base-deg (+ idx (* octave n-steps))]
          (pitch-at s (+ base-deg n))))))

  (transpose-scale [s semis]
    (let [new-root (pitch/transpose (:root s) semis
                                    (if (>= semis 0) :sharp :flat))]
      (->Scale new-root (:intervals s)))))

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn make-scale
  "Construct a Scale from a Pitch root and a vector of step intervals (semitones).

  Example:
    (make-scale (pitch/pitch :C 4) [2 2 1 2 2 2 1])  ; C major"
  [root intervals]
  (->Scale root intervals))

(defn scale
  "Construct a named Scale.

  `root-step`  — keyword like :C, :D, :Bb
  `octave`     — integer octave (root pitch octave)
  `scale-name` — keyword from the scale library (e.g. :major, :dorian)
  `spelling`   — optional :sharp or :flat for root spelling

  Example:
    (scale :C 4 :major)
    (scale :Bb 3 :minor)"
  ([root-step octave scale-name]
   (scale root-step octave scale-name :sharp))
  ([root-step octave scale-name spelling]
   (let [root      (or (pitch/keyword->pitch (keyword (str (name root-step) octave)))
                       (pitch/pitch (keyword (str/replace (name root-step) #"[0-9-]" ""))
                                    octave))
         intervals (get scale-library scale-name)]
     (when-not intervals
       (throw (ex-info "Unknown scale name" {:scale-name scale-name
                                             :available (keys scale-library)})))
     (->Scale root intervals))))

;; ---------------------------------------------------------------------------
;; Named scale map accessor (backward-compatible with random.clj weighted maps)
;; ---------------------------------------------------------------------------

(defn named-scale
  "Return a Scale record for `scale-name` at root C4.

  The record's :intervals field matches the :intervals key in the legacy
  weighted-scale maps used by cljseq.random, so existing (:intervals s) calls
  continue to work without modification."
  [scale-name]
  (when-let [intervals (get scale-library scale-name)]
    (->Scale (pitch/pitch :C 4) intervals)))

(defn scale-names
  "Return a sorted list of all named scale keywords."
  []
  (sort (keys scale-library)))

;; ---------------------------------------------------------------------------
;; Utility predicates
;; ---------------------------------------------------------------------------

(defn in-scale?
  "Return true if pitch `p` is a member of scale `s` (ignoring octave)."
  [s p]
  (some? (degree-of s (pitch/->pitch p))))
