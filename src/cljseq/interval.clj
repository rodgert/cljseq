; SPDX-License-Identifier: EPL-2.0
(ns cljseq.interval
  "Interval as a rich value type (§4.2).

  An Interval captures a diatonic distance between two pitches: quality,
  number (1-based degree), direction, and an optional microtone offset.

  ## Constructors
    (interval :major 3)          ; major third, upward
    (interval :perfect 5 :down)  ; perfect fifth, downward
    (named-interval :M3)         ; → (interval :major 3)
    (interval-between p1 p2)     ; compute from two Pitch values

  ## Derived views
    (interval->semitones iv)     ; signed semitone count
    (interval->cents     iv)     ; signed cents (×100 + microtone)
    (interval-name       iv)     ; :P1 :m2 :M2 ... :P8 :m9 etc.

  ## Predicates / operations
    (compound?    iv)   ; number > 8?
    (simple       iv)   ; reduce compound to within an octave
    (invert       iv)   ; complement within the octave
    (add-interval p iv) ; apply interval to a Pitch → Pitch

  Key design decisions: R&R §4.2."
  (:require [cljseq.pitch :as pitch]))

;; ---------------------------------------------------------------------------
;; Interval record
;; ---------------------------------------------------------------------------

(defrecord Interval [quality number direction microtone])
;; quality    — :perfect :major :minor :augmented :diminished
;;              :doubly-augmented :doubly-diminished
;; number     — 1-based integer (1 = unison, 8 = octave, 9+ = compound)
;; direction  — :up (default) or :down
;; microtone  — cents offset (default 0)

;; ---------------------------------------------------------------------------
;; Tables
;; ---------------------------------------------------------------------------

;; Semitone sizes for diatonic intervals by number (1-based, within octave)
;; The "natural" semitone count for each scale degree using major-scale spelling
(def ^:private natural-semitones
  {1 0, 2 2, 3 4, 4 5, 5 7, 6 9, 7 11, 8 12})

;; Perfect intervals are 1, 4, 5, 8 — they have :perfect quality
;; Imperfect intervals are 2, 3, 6, 7 — they have :major/:minor quality
(def ^:private perfect-intervals #{1 4 5 8})

(defn- natural-size
  "Semitone count for degree `n` reduced to 1-8 range."
  [n]
  (let [n8 (if (> n 8) (- n (* 8 (dec (quot n 8)))) n)]
    (natural-semitones n8 0)))

(def ^:private quality->offset
  "Semitone offset from the natural (major/perfect) size."
  {:doubly-augmented  2
   :augmented         1
   :major             0
   :perfect           0
   :minor            -1
   :diminished       -1   ; for imperfect; for perfect it's -1
   :doubly-diminished -2})

;; For perfect intervals, diminished = -1 (one less than perfect)
;; For imperfect intervals, diminished = -2 (one less than minor = major - 2)
;; We handle this in interval->semitones

;; ---------------------------------------------------------------------------
;; Named interval table
;; ---------------------------------------------------------------------------

(def named-intervals
  "Map from short symbol to [quality number] pair."
  {:P1  [:perfect    1]
   :d2  [:diminished 2]
   :m2  [:minor      2]
   :M2  [:major      2]
   :A1  [:augmented  1]
   :d3  [:diminished 3]
   :m3  [:minor      3]
   :M3  [:major      3]
   :A2  [:augmented  2]
   :d4  [:diminished 4]
   :P4  [:perfect    4]
   :A3  [:augmented  3]
   :d5  [:diminished 5]
   :TT  [:diminished 5]   ; tritone alias
   :A4  [:augmented  4]
   :P5  [:perfect    5]
   :d6  [:diminished 6]
   :m6  [:minor      6]
   :M6  [:major      6]
   :A5  [:augmented  5]
   :d7  [:diminished 7]
   :m7  [:minor      7]
   :M7  [:major      7]
   :A6  [:augmented  6]
   :d8  [:diminished 8]
   :P8  [:perfect    8]
   :A7  [:augmented  7]
   ;; Compound (9th and beyond)
   :m9  [:minor      9]
   :M9  [:major      9]
   :A9  [:augmented  9]
   :m10 [:minor      10]
   :M10 [:major      10]
   :P11 [:perfect    11]
   :A11 [:augmented  11]
   :P12 [:perfect    12]
   :m13 [:minor      13]
   :M13 [:major      13]})

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn interval
  "Construct an Interval record.

  Examples:
    (interval :major 3)           ; M3 upward
    (interval :perfect 5 :down)   ; P5 downward
    (interval :minor 7 :up 25)    ; m7 upward + 25 cents"
  ([quality number]
   (->Interval quality number :up 0))
  ([quality number direction]
   (->Interval quality number direction 0))
  ([quality number direction microtone]
   (->Interval quality number direction microtone)))

(defn named-interval
  "Return an Interval for a named symbol such as :M3, :P5, :m7.

  Optionally pass :down as second arg for a downward interval.
  Returns nil for unknown names."
  ([sym]
   (named-interval sym :up))
  ([sym direction]
   (when-let [[q n] (get named-intervals sym)]
     (->Interval q n direction 0))))

;; ---------------------------------------------------------------------------
;; Derived views
;; ---------------------------------------------------------------------------

(defn interval->semitones
  "Return the signed semitone count for interval `iv`.
  Positive = up, negative = down.
  Microtone is NOT included; use interval->cents for that."
  ^long [^Interval iv]
  (let [{:keys [quality number direction]} iv
        ;; Interval numbers advance by 7 per octave (not 8): M9 = M2 + P8
        simple-n   (if (> number 8)
                     (- number (* 7 (quot (- number 2) 7)))
                     number)
        octaves    (if (> number 8) (quot (- number 2) 7) 0)
        nat        (long (natural-semitones simple-n 0))
        offset     (cond
                     (= quality :diminished)
                     (if (perfect-intervals simple-n) -1 -2)

                     (= quality :doubly-diminished)
                     (if (perfect-intervals simple-n) -2 -3)

                     :else
                     (long (quality->offset quality 0)))
        semitones  (+ nat offset (* 12 octaves))]
    (if (= direction :down) (- semitones) semitones)))

(defn interval->cents
  "Return the signed cents value for interval `iv` (semitones × 100 + microtone)."
  ^double [^Interval iv]
  (+ (* 100.0 (double (interval->semitones iv)))
     (double (:microtone iv 0))
     (if (= :down (:direction iv))
       (- (double (:microtone iv 0)))
       (double (:microtone iv 0)))))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(defn compound?
  "Return true if `iv` spans more than an octave (number > 8)."
  [^Interval iv]
  (> (:number iv) 8))

(defn simple
  "Reduce a compound interval to its simple equivalent (within an octave).
  Unison and octave are preserved. Direction is preserved."
  [^Interval iv]
  (if (compound? iv)
    (let [n (:number iv)
          simple-n (- n (* 7 (quot (- n 2) 7)))]
      (->Interval (:quality iv) simple-n (:direction iv) (:microtone iv)))
    iv))

(defn invert
  "Return the inversion of simple interval `iv` within the octave.
  P ↔ P, M ↔ m, A ↔ d. Number complement = 9 - n."
  [^Interval iv]
  (let [iv    (simple iv)
        n     (:number iv)
        inv-n (- 9 n)
        inv-q (case (:quality iv)
                :perfect          :perfect
                :major            :minor
                :minor            :major
                :augmented        :diminished
                :diminished       :augmented
                :doubly-augmented :doubly-diminished
                :doubly-diminished :doubly-augmented)]
    (->Interval inv-q inv-n (:direction iv) (:microtone iv))))

;; ---------------------------------------------------------------------------
;; Interval arithmetic
;; ---------------------------------------------------------------------------

(defn add-interval
  "Apply interval `iv` to pitch `p`, returning a new Pitch.

  Spelling preference: :up → sharp, :down → flat (unless `spelling` supplied).
  Returns nil if result falls outside MIDI range [0, 127]."
  ([p iv]
   (add-interval p iv (if (= :down (:direction iv)) :flat :sharp)))
  ([p iv spelling]
   (let [semitones (interval->semitones iv)]
     (pitch/transpose p semitones spelling))))

;; ---------------------------------------------------------------------------
;; Interval between two pitches
;; ---------------------------------------------------------------------------

(defn- abs-diff->interval
  "Convert an absolute semitone difference `diff` (0-12) to [quality number].
  Uses sharp-preferred spelling to determine diatonic number."
  [diff]
  ;; Map semitone distance to canonical quality + number
  (case (int (mod diff 12))
    0  [:perfect    1]
    1  [:minor      2]
    2  [:major      2]
    3  [:minor      3]
    4  [:major      3]
    5  [:perfect    4]
    6  [:diminished 5]
    7  [:perfect    5]
    8  [:minor      6]
    9  [:major      6]
    10 [:minor      7]
    11 [:major      7]
    [:perfect 1]))

(defn interval-between
  "Compute the interval from pitch `p1` to pitch `p2`.

  Direction is :up when p2 >= p1, :down otherwise.
  Octave compounds are accounted for (number 9+).

  Example:
    (interval-between (pitch/pitch :C 4) (pitch/pitch :G 4))
    ; → Interval{:perfect 5 :up 0}"
  [p1 p2]
  (let [s1        (pitch/pitch->ps p1)
        s2        (pitch/pitch->ps p2)
        direction (if (>= s2 s1) :up :down)
        diff      (Math/abs (- s2 s1))
        octaves   (int (/ diff 12))
        remainder (mod diff 12)
        [q n]     (abs-diff->interval remainder)
        number    (if (> octaves 0)
                    (+ n (* 8 octaves))
                    n)]
    (->Interval q number direction 0)))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn interval-name
  "Return the short name keyword for interval `iv`, e.g. :M3, :P5, :m7.
  Falls back to a generated symbol for unusual intervals."
  [^Interval iv]
  (let [iv  (if (compound? iv) iv iv)
        sem (Math/abs (interval->semitones iv))]
    (or (some (fn [[k [q n]]]
                (when (and (= q (:quality iv))
                           (= n (:number iv)))
                  k))
              named-intervals)
        (keyword (str (name (:quality iv)) (str (:number iv)))))))

(defmethod print-method Interval [iv ^java.io.Writer w]
  (.write w (str "#interval[" (name (interval-name iv))
                 (when (= :down (:direction iv)) "↓")
                 "]")))
