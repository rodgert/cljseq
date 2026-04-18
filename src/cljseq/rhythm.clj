; SPDX-License-Identifier: EPL-2.0
(ns cljseq.rhythm
  "Euclidean rhythm generation (§5.2) and rhythm utilities.

  Euclidean rhythms distribute k pulses among n steps as evenly as possible.
  They reproduce a surprising number of world music rhythms:

    e(3,8) = [1 0 0 1 0 0 1 0]  — tresillo (Cuban)
    e(5,8) = [1 0 1 1 0 1 1 0]  — son clave
    e(7,12)= [1 0 1 1 0 1 0 1 1 0 1 0]  — west African

  ## Constructors
    (euclidean k n)           ; → [1 0 1 ...] pulse vector, length n
    (euclidean k n offset)    ; rotated by `offset` steps

  ## Utilities
    (rotate-rhythm  r n)    ; rotate vector r by n positions
    (rhythm-density r)      ; pulse density as float 0..1
    (invert-rhythm  r)      ; swap 0s and 1s
    (complement-rhythm r)   ; same as invert (alias)
    (interleave-rhythms r1 r2)  ; interleave two equal-length rhythms

  ## Polymorphic step generation
    (rhythm->steps r dur-fn)  ; map pulses to step maps via dur-fn

  Key design decisions: R&R §5.2."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Bjorklund's algorithm
;; ---------------------------------------------------------------------------

(defn- bjorklund
  "Compute Euclidean rhythm using Bjorklund's recursive algorithm.
  Returns a vector of 1s and 0s of length `n` with `k` pulses."
  [k n]
  (cond
    (= k 0) (vec (repeat n 0))
    (= k n) (vec (repeat n 1))
    :else
    (let [pulses    (vec (repeat k [1]))
          remainders (vec (repeat (- n k) [0]))]
      (loop [p pulses r remainders]
        (cond
          (<= (count r) 1)
          (into [] (apply concat (concat p r)))

          (>= (count r) (count p))
          (recur (mapv #(into % %2) p (take (count p) r))
                 (subvec r (count p)))

          :else
          (recur (mapv #(into % %2) (take (count r) p) r)
                 (subvec p (count r))))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn euclidean
  "Return a Euclidean rhythm vector: k pulses distributed across n steps.
  Optionally rotated by `offset` positions (positive = right, negative = left).

  Examples:
    (euclidean 3 8)        ; [1 0 0 1 0 0 1 0]
    (euclidean 5 8)        ; [1 0 1 1 0 1 1 0]  son clave
    (euclidean 5 8 1)      ; rotate by 1"
  ([k n]
   (bjorklund k n))
  ([k n offset]
   (let [r (bjorklund k n)
         o (mod (- offset) n)]     ; negative mod for left-rotation effect
     (into (subvec r o) (subvec r 0 o)))))

(defn rotate-rhythm
  "Rotate rhythm vector `r` by `n` positions.
  Positive `n` rotates right (delays pulses), negative rotates left."
  [r n]
  (let [len (count r)
        n   (mod n len)]
    (into (subvec (vec r) n) (subvec (vec r) 0 n))))

(defn rhythm-density
  "Return the pulse density of rhythm `r` as a float [0.0, 1.0]."
  ^double [r]
  (/ (double (count (filter pos? r))) (double (count r))))

(defn invert-rhythm
  "Return the complement of rhythm `r` — 1s become 0s and vice-versa."
  [r]
  (mapv #(if (pos? %) 0 1) r))

(def complement-rhythm
  "Alias for invert-rhythm."
  invert-rhythm)

(defn interleave-rhythms
  "Interleave two rhythms of equal length, taking turns element by element.
  Where either rhythm has a pulse, the result has a pulse."
  [r1 r2]
  {:pre [(= (count r1) (count r2))]}
  (mapv #(if (or (pos? %1) (pos? %2)) 1 0) r1 r2))

;; ---------------------------------------------------------------------------
;; Step map generation
;; ---------------------------------------------------------------------------

(defn rhythm->steps
  "Convert a rhythm vector to a seq of step maps.

  `r`      — rhythm vector of 1s and 0s
  `dur-fn` — called with the current step index; returns the :dur for that step.
             Defaults to a constant 1.

  Each pulse (1) becomes {:beat? true :dur (dur-fn i)},
  each rest (0) becomes {:beat? false :dur (dur-fn i)}.

  Useful for integrating with cljseq.live/play! pipelines."
  ([r]
   (rhythm->steps r (constantly 1)))
  ([r dur-fn]
   (vec (map-indexed
          (fn [i pulse]
            {:beat? (pos? pulse)
             :dur   (dur-fn i)})
          r))))

(defn rhythm->onsets
  "Return the 0-based indices of pulse positions in rhythm `r`."
  [r]
  (keep-indexed (fn [i v] (when (pos? v) i)) r))

;; ---------------------------------------------------------------------------
;; Well-known Euclidean rhythm constants
;; ---------------------------------------------------------------------------

(def tresillo     (euclidean 3 8))    ; Cuban tresillo
(def cinquillo    (euclidean 5 8))    ; son clave / cinquillo
(def bossa-nova   (euclidean 3 16))
(def son-clave    (euclidean 5 8))
(def rumba-clave  (euclidean 5 8 1))
(def standard-4   (euclidean 4 4))    ; four-on-the-floor
(def standard-8   (euclidean 8 8))    ; eight-note straight
