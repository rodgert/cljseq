; SPDX-License-Identifier: EPL-2.0
(ns cljseq.chord
  "Chord as a rich value type (§4.4).

  A Chord is a root Pitch plus a quality keyword. It produces an ordered
  sequence of MIDI note numbers and Pitch records with optional inversion.

  ## Constructors
    (chord :C 4 :major)          ; C major triad, root position
    (chord :G 3 :dom7)           ; G dominant 7th
    (chord :D 4 :min7 1)         ; Dm7, first inversion

  ## Derived views
    (chord->pitches c)            ; ordered Pitch seq
    (chord->midis   c)            ; ordered MIDI int seq
    (chord-name     c)            ; \"Cmaj\" / \"G7\" etc.

  ## Scale-degree helpers
    (chord-at-degree scale degree quality)  ; chord built on scale degree
    (progression     scale degrees)         ; seq of Chords (Roman numeral API)

  ## Quality library
    Triads:   :major :minor :augmented :diminished :sus2 :sus4
    7ths:     :maj7 :min7 :dom7 :half-dim7 :dim7 :aug-maj7
    6ths:     :maj6 :min6
    9ths:     :maj9 :min9 :dom9 :dom-min9
    11ths:    :maj11 :min11 :dom11
    13ths:    :maj13 :min13 :dom13
    Added:    :add9 :add11 :madd9

  Key design decisions: R&R §4.4."
  (:require [cljseq.pitch  :as pitch]
            [cljseq.scale  :as scale]))

;; ---------------------------------------------------------------------------
;; Chord record
;; ---------------------------------------------------------------------------

(defrecord Chord [root quality inversion])
;; root       — Pitch record
;; quality    — keyword from quality library
;; inversion  — 0 (root pos) 1 (first) 2 (second) etc.

;; ---------------------------------------------------------------------------
;; Quality library: semitone intervals from root
;; ---------------------------------------------------------------------------

(def ^:private quality-intervals
  {:major        [0 4 7]
   :minor        [0 3 7]
   :augmented    [0 4 8]
   :diminished   [0 3 6]
   :sus2         [0 2 7]
   :sus4         [0 5 7]
   ;; 7ths
   :maj7         [0 4 7 11]
   :min7         [0 3 7 10]
   :dom7         [0 4 7 10]
   :half-dim7    [0 3 6 10]
   :dim7         [0 3 6 9]
   :aug-maj7     [0 4 8 11]
   :min-maj7     [0 3 7 11]
   ;; 6ths
   :maj6         [0 4 7 9]
   :min6         [0 3 7 9]
   ;; 9ths (include 7th)
   :maj9         [0 4 7 11 14]
   :min9         [0 3 7 10 14]
   :dom9         [0 4 7 10 14]
   :dom-min9     [0 4 7 10 13]
   ;; 11ths
   :maj11        [0 4 7 11 14 17]
   :min11        [0 3 7 10 14 17]
   :dom11        [0 4 7 10 14 17]
   ;; 13ths
   :maj13        [0 4 7 11 14 17 21]
   :min13        [0 3 7 10 14 17 21]
   :dom13        [0 4 7 10 14 17 21]
   ;; Added tones (no 7th)
   :add9         [0 4 7 14]
   :add11        [0 4 7 17]
   :madd9        [0 3 7 14]
   :power        [0 7]})

;; ---------------------------------------------------------------------------
;; Short name table for formatting
;; ---------------------------------------------------------------------------

(def ^:private quality-short
  {:major     "maj"  :minor    "m"    :augmented "aug"  :diminished "dim"
   :sus2      "sus2" :sus4     "sus4"
   :maj7      "maj7" :min7     "m7"   :dom7      "7"    :half-dim7  "ø7"
   :dim7      "°7"   :aug-maj7 "+maj7" :min-maj7 "mM7"
   :maj6      "6"    :min6     "m6"
   :maj9      "maj9" :min9     "m9"   :dom9      "9"    :dom-min9   "7b9"
   :maj11     "maj11" :min11   "m11"  :dom11     "11"
   :maj13     "maj13" :min13   "m13"  :dom13     "13"
   :add9      "add9" :add11    "add11" :madd9    "madd9"
   :power     "5"})

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn chord
  "Construct a Chord record.

  Examples:
    (chord :C 4 :major)         ; C major, root position
    (chord :G 3 :dom7 1)        ; G7, first inversion"
  ([root-step octave quality]
   (chord root-step octave quality 0))
  ([root-step octave quality inversion]
   (let [root (pitch/keyword->pitch (keyword (str (name root-step) octave)))]
     (->Chord root quality (int inversion)))))

;; ---------------------------------------------------------------------------
;; Derived views
;; ---------------------------------------------------------------------------

(defn chord->pitches
  "Return the ordered seq of Pitches for chord `c`, applying inversion.

  Inversion raises the bottom notes by an octave. Spelling follows:
  positive offset → sharp, negative (not applicable here) → flat."
  [^Chord c]
  (let [{:keys [root quality inversion]} c
        intervals (or (get quality-intervals quality)
                      (throw (ex-info "Unknown chord quality"
                                      {:quality quality
                                       :available (keys quality-intervals)})))
        pitches   (mapv #(pitch/transpose root % :sharp) intervals)
        inv       (int inversion)
        n         (count pitches)]
    (if (zero? inv)
      pitches
      ;; Raise each inverted bass note by one octave
      (let [to-raise (take inv pitches)
            rest-ps  (drop inv pitches)
            raised   (mapv #(pitch/octave-transpose % 1) to-raise)]
        (vec (concat rest-ps raised))))))

(defn chord->midis
  "Return the ordered seq of MIDI integers for chord `c`."
  [^Chord c]
  (mapv pitch/pitch->midi (chord->pitches c)))

(defn- pitch-class-str
  "Return step+accidental string for a Pitch, without octave number."
  [^cljseq.pitch.Pitch p]
  (let [{:keys [step accidental]} p
        acc-str (case accidental
                  :sharp        "#"
                  :double-sharp "##"
                  :flat         "b"
                  :double-flat  "bb"
                  :natural      "♮"
                  "")]
    (str (name step) acc-str)))

(defn chord-name
  "Return a human-readable name string for `c`, e.g. \"Cmaj\", \"G7\", \"Dm7\"."
  [^Chord c]
  (str (pitch-class-str (:root c))
       (get quality-short (:quality c) (name (:quality c)))))

;; ---------------------------------------------------------------------------
;; Scale-degree chord building
;; ---------------------------------------------------------------------------

(defn chord-at-degree
  "Build a chord on a given scale degree.

  `s`       — Scale (implements IScale)
  `degree`  — 0-based index into the scale
  `quality` — chord quality keyword

  Returns a Chord with root = the pitch at that scale degree."
  [s degree quality]
  (let [root (scale/pitch-at s degree)]
    (->Chord root quality 0)))

;; ---------------------------------------------------------------------------
;; Roman numeral progression
;; ---------------------------------------------------------------------------

(def ^:private roman-numerals
  "Map from Roman numeral keyword to [degree quality] for major key."
  {:I   [0 :major]   :II  [1 :minor]   :III [2 :minor]
   :IV  [3 :major]   :V   [4 :major]   :VI  [5 :minor]
   :VII [6 :diminished]
   :i   [0 :minor]   :ii  [1 :diminished] :III+ [2 :augmented]
   :iv  [3 :minor]   :v   [4 :minor]   :VI+  [5 :major]
   :VII+ [6 :major]
   ;; 7th chord shortcuts
   :Imaj7 [0 :maj7]  :IIm7 [1 :min7]  :IIIm7 [2 :min7]
   :IVmaj7 [3 :maj7] :V7  [4 :dom7]   :VIm7  [5 :min7]
   :VIIø7  [6 :half-dim7]})

(defn progression
  "Return a seq of Chords from a seq of Roman numeral keywords.

  `s`       — Scale (provides the root pitch for each degree)
  `degrees` — seq of Roman numeral keywords e.g. [:I :IV :V :I]

  Unknown keywords fall back to degree 0, major quality.

  Example:
    (progression (scale/scale :C 4 :major) [:I :IV :V7 :I])"
  [s degrees]
  (mapv (fn [rn]
          (let [[deg quality] (get roman-numerals rn [0 :major])
                root          (scale/pitch-at s deg)]
            (->Chord root quality 0)))
        degrees))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defmethod print-method Chord [c ^java.io.Writer w]
  (.write w (str "#chord[" (chord-name c)
                 (when (> (:inversion c) 0)
                   (str "/" (:inversion c)))
                 "]")))
