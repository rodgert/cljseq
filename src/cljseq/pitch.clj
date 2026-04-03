; SPDX-License-Identifier: EPL-2.0
(ns cljseq.pitch
  "Pitch as a rich value type (§4.1).

  A Pitch captures the full spelling of a note — step, octave, accidental,
  and an optional microtone offset in cents. Enharmonic equivalents (C#4 vs
  Db4) are distinct values but report the same `:midi` and `:ps`.

  ## Constructors
    (pitch :C 4)               ; C4, no accidental
    (pitch :C 4 :sharp)        ; C#4
    (pitch :B 3 :flat)         ; Bb3
    (keyword->pitch :C#4)      ; → same as (pitch :C 4 :sharp)
    (midi->pitch 60)           ; → C4 (sharp-preferred spelling)
    (midi->pitch 61 :flat)     ; → Db4

  ## Derived views (pure functions)
    (pitch->midi  p)   ; 0–127 integer
    (pitch->ps    p)   ; float pitch-space (midi + microtone/100)
    (pitch-class  p)   ; 0–11
    (pitch->freq  p)   ; Hz (A4 = 440)

  ## Predicates / operations
    (enharmonic? p1 p2)          ; same pitch-space?
    (transpose   p  semitones)   ; chromatic shift, returns Pitch
    (above?      p1 p2)          ; p1 higher than p2?
    (pitch->str  p)              ; \"C#4\", \"Bb3\" etc.

  Key design decisions: R&R §4.1, Music21 pitch model."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Pitch record
;; ---------------------------------------------------------------------------

(defrecord Pitch [step octave accidental microtone])
;; step        — :C :D :E :F :G :A :B
;; octave      — integer, -1 to 9 (middle C = octave 4)
;; accidental  — nil :sharp :flat :natural :double-sharp :double-flat
;; microtone   — cents offset from nominal, -100 to 100 (default 0)

;; ---------------------------------------------------------------------------
;; Tables
;; ---------------------------------------------------------------------------

(def ^:private step->semitone
  {:C 0 :D 2 :E 4 :F 5 :G 7 :A 9 :B 11})

(def ^:private semitone->step
  {0 :C 2 :D 4 :E 5 :F 7 :G 9 :A 11 :B})

(def ^:private acc->semitone
  {nil 0 :natural 0 :sharp 1 :flat -1 :double-sharp 2 :double-flat -2})

;; Sharp-preferred and flat-preferred spellings for each pitch class
;; [step accidental] pairs indexed by pitch class 0-11
(def ^:private sharp-spellings
  [[:C nil] [:C :sharp] [:D nil] [:D :sharp] [:E nil]
   [:F nil] [:F :sharp] [:G nil] [:G :sharp] [:A nil] [:A :sharp] [:B nil]])

(def ^:private flat-spellings
  [[:C nil] [:D :flat] [:D nil] [:E :flat] [:E nil]
   [:F nil] [:G :flat] [:G nil] [:A :flat] [:A nil] [:B :flat] [:B nil]])

;; ---------------------------------------------------------------------------
;; Derived view functions
;; ---------------------------------------------------------------------------

(defn pitch->midi
  "Return the MIDI note number (0–127) for pitch `p`.
  Microtone is not reflected — use pitch->ps for that."
  ^long [^Pitch p]
  (let [base   (+ (* (+ (long (:octave p)) 1) 12)
                  (long (step->semitone (:step p))))
        offset (long (acc->semitone (:accidental p) 0))]
    (max 0 (min 127 (+ base offset)))))

(defn pitch->ps
  "Return the float pitch-space value for `p` (midi + microtone/100).
  Useful for microtonal comparisons and frequency calculation."
  ^double [^Pitch p]
  (+ (double (pitch->midi p)) (/ (double (:microtone p 0)) 100.0)))

(defn pitch-class
  "Return the pitch class (0–11) of `p`."
  ^long [^Pitch p]
  (mod (pitch->midi p) 12))

(defn pitch->freq
  "Return the frequency in Hz for `p` (A4 = 440 Hz standard tuning)."
  ^double [^Pitch p]
  (* 440.0 (Math/pow 2.0 (/ (- (pitch->ps p) 69.0) 12.0))))

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn pitch
  "Construct a Pitch record.

  Examples:
    (pitch :C 4)               ; C4
    (pitch :C 4 :sharp)        ; C#4
    (pitch :D 3 :flat 25)      ; Db3 + 25 cents"
  ([step octave]
   (->Pitch step octave nil 0))
  ([step octave accidental]
   (->Pitch step octave accidental 0))
  ([step octave accidental microtone]
   (->Pitch step octave accidental microtone)))

(defn keyword->pitch
  "Parse a note keyword to a Pitch record.

  Supports single and double accidentals:
    :C4   → C4    :C#4  → C#4   :Bb3 → Bb3
    :D##5 → D##5  :Ebb4 → Ebb4  :B-1 → B-1

  Returns nil for unrecognised keywords."
  [k]
  (when (keyword? k)
    (when-let [[_ step-s acc-s oct-s]
               (re-matches #"([A-G])(#{1,2}|b{1,2})?(-?\d+)" (name k))]
      (let [step       (keyword step-s)
            octave     (Integer/parseInt oct-s)
            accidental (case acc-s
                         "#"  :sharp
                         "##" :double-sharp
                         "b"  :flat
                         "bb" :double-flat
                         nil)]
        (->Pitch step octave accidental 0)))))

(defn midi->pitch
  "Convert a MIDI note number to a Pitch record.

  `spelling` — :sharp (default) or :flat controls enharmonic preference.

  Examples:
    (midi->pitch 60)        ; → C4
    (midi->pitch 61)        ; → C#4  (sharp preferred)
    (midi->pitch 61 :flat)  ; → Db4  (flat preferred)"
  ([midi]
   (midi->pitch midi :sharp))
  ([midi spelling]
   (let [midi    (int midi)
         pc      (mod midi 12)
         octave  (dec (quot midi 12))
         [step acc] (if (= :flat spelling)
                      (nth flat-spellings pc)
                      (nth sharp-spellings pc))]
     (->Pitch step octave acc 0))))

;; ---------------------------------------------------------------------------
;; Predicates and comparisons
;; ---------------------------------------------------------------------------

(defn enharmonic?
  "Return true if `p1` and `p2` have the same pitch-space value."
  [p1 p2]
  (== (pitch->ps p1) (pitch->ps p2)))

(defn above?
  "Return true if `p1` is higher than `p2` in pitch-space."
  [p1 p2]
  (> (pitch->ps p1) (pitch->ps p2)))

(defn below?
  "Return true if `p1` is lower than `p2` in pitch-space."
  [p1 p2]
  (< (pitch->ps p1) (pitch->ps p2)))

;; ---------------------------------------------------------------------------
;; Transformations
;; ---------------------------------------------------------------------------

(defn transpose
  "Shift `p` by `semitones` chromatically.

  Preserves the preferred spelling based on direction:
  positive semitones → sharp-preferred, negative → flat-preferred.
  Pass explicit `spelling` to override.

  Returns nil if the result would be outside the MIDI range [0, 127]."
  ([p semitones]
   (transpose p semitones (if (>= (int semitones) 0) :sharp :flat)))
  ([p semitones spelling]
   (let [new-midi (+ (pitch->midi p) (int semitones))]
     (when (<= 0 new-midi 127)
       (midi->pitch new-midi spelling)))))

(defn octave-transpose
  "Shift `p` by `n` octaves (n may be negative)."
  [p n]
  (transpose p (* 12 (int n))))

;; ---------------------------------------------------------------------------
;; Coercion helpers (accept Pitch, keyword, or MIDI int)
;; ---------------------------------------------------------------------------

(defn ->pitch
  "Coerce `x` to a Pitch record.

  Accepts:
    - Pitch record (returned as-is)
    - keyword  → keyword->pitch
    - integer  → midi->pitch"
  [x]
  (cond
    (instance? Pitch x) x
    (keyword? x)        (keyword->pitch x)
    (integer? x)        (midi->pitch (int x))
    :else (throw (ex-info "->pitch: cannot coerce" {:value x}))))

(defn ->midi
  "Coerce `x` to a MIDI integer.

  Accepts Pitch, keyword, or integer."
  ^long [x]
  (cond
    (instance? Pitch x) (pitch->midi x)
    (keyword? x)        (pitch->midi (keyword->pitch x))
    (integer? x)        (long x)
    :else (throw (ex-info "->midi: cannot coerce" {:value x}))))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn pitch->str
  "Return a human-readable string for `p`, e.g. \"C#4\", \"Bb3\", \"D4\"."
  [^Pitch p]
  (let [{:keys [step octave accidental]} p
        acc-str (case accidental
                  :sharp        "#"
                  :double-sharp "##"
                  :flat         "b"
                  :double-flat  "bb"
                  :natural      "♮"
                  "")]
    (str (name step) acc-str octave)))

(defmethod print-method Pitch [p ^java.io.Writer w]
  (.write w (str "#pitch[" (pitch->str p) "]")))
