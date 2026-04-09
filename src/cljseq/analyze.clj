; SPDX-License-Identifier: EPL-2.0
(ns cljseq.analyze
  "Music analysis tools — key detection, chord identification, Roman numeral
  annotation, tension scoring, and mode-mixture detection, built on the cljseq
  theory layer (pitch/scale/chord).

  All functions are pure and work on plain collections of MIDI integers, Pitch
  records, or Chord records. No running system is required.

  ## Key detection (Krumhansl-Schmuckler)
    (detect-key [60 64 67 65 69 72])   ; C major triad + F major
    ;; => {:root :C :mode :major :score 0.94}

    (detect-key-candidates pitches)    ; all 24 keys sorted by score

  ## Chord identification
    (identify-chord [60 64 67])        ; => {:root :C :quality :major :inversion 0}
    (identify-chord [60 63 67 70])     ; => {:root :C :quality :min7 :inversion 0}
    (identify-chord-candidates pitches) ; all matching qualities sorted by score

  ## Roman numeral analysis
    (def key (scale/scale :C 4 :major))
    (chord->roman key [67 71 74])      ; G major in C context
    ;; => {:degree 4 :roman :V :quality :major :in-key? true}

    (analyze-progression key
      [[60 64 67] [65 69 72] [67 71 74] [60 64 67]])
    ;; => [{:roman :I ...} {:roman :IV ...} {:roman :V ...} {:roman :I ...}]

  ## Tension scoring
    (tension-score {:degree 4 :quality :dom7 :in-key? true})
    ;; => 0.9   (V7 — dominant function + quality boost)

    (progression-tension key [[60 64 67] [67 71 74 77] [65 69 72] [60 64 67]])
    ;; => [{:roman :I ... :tension 0.0}
    ;;     {:roman :V ... :tension 0.9}
    ;;     {:roman :IV ... :tension 0.4}
    ;;     {:roman :I ... :tension 0.0}]

  ## Mode mixture / borrowed chord detection
    (borrowed-chord? key {:root-pc 3 :quality :major :in-key? false ...})
    ;; => true   (Eb major in C major — borrowed from C minor)

    (annotate-progression key [[60 64 67] [63 67 70] [65 69 72] [60 64 67]])
    ;; => [{:roman :I ... :tension 0.0 :borrowed? false}
    ;;     {:roman nil ... :tension 0.85 :borrowed? true}  ; bIII
    ;;     {:roman :IV ... :tension 0.4 :borrowed? false}
    ;;     {:roman :I ... :tension 0.0 :borrowed? false}]

  ## Progression generation
    (suggest-progression key [0.0 0.4 0.8 0.0])
    ;; => [{:roman :I ...} {:roman :IV ...} {:roman :V ...} {:roman :I ...}]

    (suggest-progression key [0.0 0.85 0.9 0.0] :include-borrowed? true)
    ;; may return borrowed chords for high-tension targets

  ## Scale fitness
    (scale-fitness (scale/scale :C 4 :major) [60 64 67 69 71])
    ;; => 1.0

    (suggest-scales [60 64 67 69 71])  ; top matches across all 420 named scales
    ;; => [{:root :C :mode :major :fitness 1.0} ...]

  ## Pitch-class distribution
    (pitch-class-dist [60 64 67 64 60])  ; => {0 2, 4 2, 7 1}

  Key design decisions: Krumhansl & Kessler (1982) pitch-class profiles;
  coverage-precision scoring for chord matching; §4 theory layer integration."
  (:require [clojure.set   :as set]
            [cljseq.chord  :as chord-ns]
            [cljseq.pitch  :as pitch]
            [cljseq.scale  :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Input coercion — accept MIDI ints, Pitch records, Chord records, or colls
;; ---------------------------------------------------------------------------

(defn- ->midi-ints
  "Flatten any combination of MIDI ints, Pitch records, and Chord records to
  a seq of MIDI integers."
  [input]
  (cond
    (integer? input)                     [input]
    (instance? cljseq.pitch.Pitch input) [(pitch/pitch->midi input)]
    (instance? cljseq.chord.Chord input) (chord-ns/chord->midis input)
    (sequential? input)                  (mapcat ->midi-ints input)
    :else                                []))

;; ---------------------------------------------------------------------------
;; Pitch-class distribution
;; ---------------------------------------------------------------------------

(defn pitch-class-dist
  "Return a map of {pitch-class count} for the given collection of pitches.

  Accepts MIDI integers, Pitch records, Chord records, or nested collections.

  Example:
    (pitch-class-dist [60 64 67 64 60])  ; => {0 2, 4 2, 7 1}"
  [pitches]
  (frequencies (map #(mod % 12) (->midi-ints pitches))))

;; ---------------------------------------------------------------------------
;; Krumhansl-Schmuckler key detection
;; ---------------------------------------------------------------------------

;; Original K-S profiles (Krumhansl & Kessler 1982)
(def ^:private ks-major
  [6.35 2.23 3.48 2.33 4.38 4.09 2.52 5.19 2.39 3.66 2.29 2.88])

(def ^:private ks-minor
  [6.33 2.68 3.52 5.38 2.60 3.53 2.54 4.75 3.98 2.69 3.34 3.17])

(defn- pearson
  "Pearson correlation coefficient between two equal-length numeric sequences."
  [xs ys]
  (let [n  (count xs)
        mx (/ (reduce + xs) n)
        my (/ (reduce + ys) n)
        dx (mapv #(- % mx) xs)
        dy (mapv #(- % my) ys)
        num (reduce + (map * dx dy))
        sx  (Math/sqrt (reduce + (map #(* % %) dx)))
        sy  (Math/sqrt (reduce + (map #(* % %) dy)))]
    (if (or (zero? sx) (zero? sy)) 0.0 (double (/ num (* sx sy))))))

(defn- pc-dist-vec
  "Convert a pc→count map to a 12-element double vector."
  [dist-map]
  (mapv #(double (get dist-map % 0)) (range 12)))

(defn- rotate-vec
  "Rotate vector `v` left by `n` positions."
  [v n]
  (let [n (mod n (count v))]
    (into (subvec v n) (subvec v 0 n))))

;; Pitch class 0-11 → root keyword using conventional enharmonic spelling
;; (flats for Bb/Eb/Ab/Db/Gb — the flat-key names musicians expect)
(def ^:private pc->root-kw
  [0 :C   1 :Db  2 :D   3 :Eb  4 :E
   5 :F   6 :Gb  7 :G   8 :Ab  9 :A
   10 :Bb 11 :B])

(def ^:private pc->root-kw-map
  (apply hash-map pc->root-kw))

;; For chord identification we prefer sharps
(def ^:private pc->chord-root-kw
  [0 :C   1 :C#  2 :D   3 :D#  4 :E
   5 :F   6 :F#  7 :G   8 :G#  9 :A
   10 :A# 11 :B])

(def ^:private pc->chord-root-kw-map
  (apply hash-map pc->chord-root-kw))

(defn detect-key-candidates
  "Return all 24 possible keys sorted by Krumhansl-Schmuckler correlation score.

  `pitches` — any input accepted by pitch-class-dist.

  Each result map:
    :root   — root keyword (:C :Db :D ...)
    :mode   — :major or :minor (natural)
    :score  — Pearson correlation [-1.0 .. 1.0]; higher = better fit

  Example:
    (detect-key-candidates [60 64 67 65 69 72 71])
    ;; => [{:root :C :mode :major :score 0.94} ...]"
  [pitches]
  (let [dist (pc-dist-vec (pitch-class-dist pitches))]
    (sort-by (comp - :score)
             (for [root (range 12)
                   [mode profile] [[:major ks-major] [:minor ks-minor]]]
               {:root  (pc->root-kw-map root)
                :mode  mode
                :score (pearson dist (rotate-vec (vec profile) (mod (- 12 root) 12)))}))))

(defn detect-key
  "Detect the most likely key for a collection of pitches using the
  Krumhansl-Schmuckler algorithm.

  `pitches` — MIDI ints, Pitch records, Chord records, or nested collections.

  Returns a map with:
    :root   — root keyword (:C :Db :D ...)
    :mode   — :major or :minor
    :score  — Pearson correlation [−1.0 .. 1.0]

  Example:
    (detect-key [60 64 67 65 69 72])  ; C major chord + F major
    ;; => {:root :C :mode :major :score 0.94}"
  [pitches]
  (first (detect-key-candidates pitches)))

;; ---------------------------------------------------------------------------
;; Chord identification
;; ---------------------------------------------------------------------------

;; Quality interval sets used for identification (mod-12 pitch classes from root).
;; Ordered from simplest to most complex; prefer simpler chords on equal score.
(def ^:private id-qualities
  [[:power      #{0 7}]
   [:major      #{0 4 7}]
   [:minor      #{0 3 7}]
   [:augmented  #{0 4 8}]
   [:diminished #{0 3 6}]
   [:sus2       #{0 2 7}]
   [:sus4       #{0 5 7}]
   [:maj6       #{0 4 7 9}]
   [:min6       #{0 3 7 9}]
   [:dom7       #{0 4 7 10}]
   [:maj7       #{0 4 7 11}]
   [:min7       #{0 3 7 10}]
   [:half-dim7  #{0 3 6 10}]
   [:dim7       #{0 3 6 9}]
   [:aug-maj7   #{0 4 8 11}]
   [:min-maj7   #{0 3 7 11}]
   [:dom9       #{0 2 4 7 10}]
   [:maj9       #{0 2 4 7 11}]
   [:min9       #{0 2 3 7 10}]])

(defn- chord-inversion
  "Infer inversion from bass note PC and root+quality interval set.
  Returns 0 for root position, 1 for first inversion, etc."
  [bass-pc root-pc quality-ivs-sorted]
  (let [bass-interval (mod (- bass-pc root-pc) 12)]
    (max 0 (.indexOf quality-ivs-sorted bass-interval))))

(defn- score-chord-match
  "Score how well `root-pc` + `quality-pcs` explains `input-pcs`.
  Returns a value in [0.0 .. 1.0]. Coverage weighted more than precision."
  [input-pcs root-pc quality-pcs]
  (let [expected-pcs (set (map #(mod (+ root-pc %) 12) quality-pcs))
        matched      (set/intersection input-pcs expected-pcs)
        n-matched    (count matched)
        coverage     (/ n-matched (max 1 (count expected-pcs)))
        precision    (/ n-matched (max 1 (count input-pcs)))]
    (+ (* 0.6 coverage) (* 0.4 precision))))

(defn identify-chord-candidates
  "Return all chord matches for a collection of pitches, sorted by score.

  `pitches` — MIDI ints, Pitch records, Chord records, or nested collections.

  Each result map:
    :root       — root keyword (:C :C# ...)
    :root-pc    — root pitch class 0–11
    :quality    — chord quality keyword
    :inversion  — 0 (root pos) 1 (first) 2 (second) etc.
    :score      — match quality [0.0 .. 1.0]

  Example:
    (identify-chord-candidates [60 64 67])
    ;; => [{:root :C :quality :major :inversion 0 :score 1.0} ...]"
  [pitches]
  (let [midis      (->midi-ints pitches)
        input-pcs  (set (map #(mod % 12) midis))
        bass-pc    (when (seq midis) (mod (apply min midis) 12))]
    (when (seq input-pcs)
      (sort-by (comp - :score)
               (for [root-pc   (range 12)
                     [quality quality-pcs] id-qualities
                     :let [score (score-chord-match input-pcs root-pc quality-pcs)]
                     :when (pos? score)]
                 {:root      (pc->chord-root-kw-map root-pc)
                  :root-pc   root-pc
                  :quality   quality
                  :inversion (if bass-pc
                               (chord-inversion bass-pc root-pc (sort quality-pcs))
                               0)
                  :score     score})))))

(defn identify-chord
  "Identify the best-matching chord for a collection of pitches.

  Returns a map with :root, :root-pc, :quality, :inversion, :score;
  or nil if the input is empty.

  Example:
    (identify-chord [60 64 67])     ; => {:root :C :quality :major :inversion 0 ...}
    (identify-chord [60 63 67 70])  ; => {:root :C :quality :min7 :inversion 0 ...}"
  [pitches]
  (first (identify-chord-candidates pitches)))

;; ---------------------------------------------------------------------------
;; Roman numeral analysis
;; ---------------------------------------------------------------------------

(def ^:private degree->roman [:I :II :III :IV :V :VI :VII])

(defn- scale-pc->degree
  "Return the 0-based scale degree (0–6) for `root-pc` in `key-scale`,
  or nil if the root is not a diatonic degree of the scale."
  [key-scale root-pc]
  (let [pitches (scale-ns/scale-pitches key-scale)]
    (first (keep-indexed (fn [i p]
                           (when (= (mod (pitch/pitch->midi p) 12) root-pc)
                             i))
                         pitches))))

(defn chord->roman
  "Analyse a chord (pitches) relative to a key scale and return its Roman numeral.

  `key-scale`        — a Scale record, e.g. (scale/scale :C 4 :major)
  `chord-or-pitches` — Chord record, MIDI seq, Pitch seq, or nested collection

  Returns a map with:
    :degree   — 0-based scale degree (0 = tonic) or nil if chromatic
    :roman    — Roman numeral keyword (:I–:VII) or nil
    :quality  — chord quality keyword from identify-chord
    :root     — identified root keyword
    :in-key?  — true if the chord root is diatonic to the key

  Example:
    (chord->roman (scale/scale :C 4 :major) [67 71 74])
    ;; => {:degree 4 :roman :V :quality :major :root :G :in-key? true}"
  [key-scale chord-or-pitches]
  (let [match  (identify-chord chord-or-pitches)
        rpc    (:root-pc match)
        degree (when rpc (scale-pc->degree key-scale rpc))
        roman  (when (and degree (< degree (count degree->roman)))
                 (degree->roman degree))]
    (assoc match
           :degree  degree
           :roman   roman
           :in-key? (some? degree))))

(defn analyze-progression
  "Annotate a sequence of chords with Roman numeral analysis relative to `key-scale`.

  `key-scale` — Scale record
  `chords`    — seq of items each accepted by chord->roman (Chord, MIDI seq, etc.)

  Returns a vector of chord->roman result maps.

  Example:
    (def key (scale/scale :C 4 :major))
    (analyze-progression key [[60 64 67] [65 69 72] [67 71 74] [60 64 67]])
    ;; => [{:roman :I ...} {:roman :IV ...} {:roman :V ...} {:roman :I ...}]"
  [key-scale chords]
  (mapv #(chord->roman key-scale %) chords))

;; ---------------------------------------------------------------------------
;; Scale fitness and suggestion
;; ---------------------------------------------------------------------------

(defn scale-fitness
  "Return the fraction [0.0 .. 1.0] of distinct pitch classes in `pitches`
  that belong to `scale`.

  1.0 = all notes are diatonic; 0.0 = no notes are in the scale.

  Example:
    (scale-fitness (scale/scale :C 4 :major) [60 64 67 69 71])
    ;; => 1.0   (C E G A B — all in C major)"
  [scale pitches]
  (let [dist        (pitch-class-dist pitches)
        n-distinct  (count dist)]
    (if (zero? n-distinct)
      0.0
      (let [in-scale (count (filter (fn [[pc _]]
                                      (scale-ns/in-scale? scale (pitch/midi->pitch pc)))
                                    dist))]
        (double (/ in-scale n-distinct))))))

(defn suggest-scales
  "Suggest the best-fitting named scales for a collection of pitches.

  Tries all 35 named scales × 12 roots (420 combinations), scoring each by
  what fraction of distinct pitch classes are diatonic.

  `pitches`     — any input accepted by pitch-class-dist
  `opts`
    :top-n      — return only the top N results (default 10)
    :min-fitness — exclude results below this threshold (default 0.0)

  Each result map:
    :root     — root keyword (:C :Db ...)
    :mode     — scale name keyword (:major :dorian ...)
    :fitness  — [0.0 .. 1.0]

  Example:
    (suggest-scales [60 62 64 65 67 69 71])  ; C D E F G A B
    ;; => [{:root :C :mode :major :fitness 1.0}
    ;;     {:root :D :mode :dorian :fitness 1.0}
    ;;     {:root :E :mode :phrygian :fitness 1.0}
    ;;     ...]  (all C-major modes score 1.0 for this input)"
  [pitches & {:keys [top-n min-fitness] :or {top-n 10 min-fitness 0.0}}]
  (let [dist     (pitch-class-dist pitches)
        n-input  (count dist)
        modes    (scale-ns/scale-names)]
    (when (pos? n-input)
      (->> (for [root-pc (range 12)
                 mode    modes]
             (let [root-name (pc->root-kw-map root-pc)
                   s         (scale-ns/scale root-name 4 mode)
                   n-in      (count (filter (fn [[pc _]]
                                              (scale-ns/in-scale?
                                               s (pitch/midi->pitch pc)))
                                            dist))
                   fitness   (double (/ n-in n-input))]
               {:root root-name :mode mode :fitness fitness}))
           (filter #(>= (:fitness %) min-fitness))
           (sort-by (comp - :fitness))
           (take top-n)
           vec))))

;; ---------------------------------------------------------------------------
;; Tension scoring (Phase 2)
;;
;; Model: each scale degree carries a base tension reflecting its harmonic
;; function (tonic < subdominant < dominant). Quality adjustments modify the
;; base for chords whose color increases or decreases tension (e.g. dom7 raises
;; V to near-maximum; maj7 softens I slightly). Chromatic chords (nil degree)
;; default to a high base tension of 0.85 — they create ambiguity.
;; ---------------------------------------------------------------------------

(def ^:private degree-base-tension
  ;; Indexed by 0-based scale degree (0 = tonic I, 6 = leading-tone VII)
  [0.00   ; I   — tonic, fully resolved
   0.50   ; II  — subdominant function
   0.20   ; III — mediant, tonic function
   0.40   ; IV  — subdominant
   0.80   ; V   — dominant
   0.15   ; VI  — submediant, tonic function
   0.90]) ; VII — leading tone / subtonic

(def ^:private quality-tension-delta
  {:dom7       0.10   ; tritone drives tension up
   :maj7      -0.05   ; gentle colour, slightly softer
   :min7       0.05
   :half-dim7  0.10
   :dim7       0.15
   :diminished 0.10
   :augmented  0.10
   :aug-maj7   0.15})

(defn tension-score
  "Return the harmonic tension of an analyzed chord as a float in [0.0 .. 1.0].

  `analyzed` — a map with at minimum :degree (0-based, or nil) and :quality.
  Typically the result of chord->roman or identify-chord.

  Scoring:
    Tonic function (I, III, VI)   0.00 – 0.25
    Subdominant function (II, IV) 0.40 – 0.55
    Dominant function (V, VII)    0.80 – 0.90+
    Chromatic (nil degree)        0.85
    Quality delta applied on top; result clamped to [0.0 .. 1.0].

  Example:
    (tension-score {:degree 4 :quality :dom7})   ; => 0.9  (V7)
    (tension-score {:degree 0 :quality :major})  ; => 0.0  (I)
    (tension-score {:degree nil :quality :major}); => 0.85 (chromatic)"
  [{:keys [degree quality]}]
  (let [base (if (and (some? degree) (< (int degree) (count degree-base-tension)))
               (degree-base-tension (int degree))
               0.85)
        adj  (double (get quality-tension-delta quality 0.0))]
    (min 1.0 (max 0.0 (+ (double base) adj)))))

(defn progression-tension
  "Annotate a chord progression with harmonic tension scores.

  Each chord in `chords` is analyzed via chord->roman then extended with
  a :tension key (float [0.0 .. 1.0] from tension-score).

  `key-scale` — Scale record
  `chords`    — seq of items accepted by chord->roman

  Example:
    (progression-tension (scale/scale :C 4 :major)
                         [[60 64 67] [67 71 74 77] [65 69 72] [60 64 67]])
    ;; => [{:roman :I ... :tension 0.0}
    ;;     {:roman :V ... :tension 0.9}
    ;;     {:roman :IV ... :tension 0.4}
    ;;     {:roman :I ... :tension 0.0}]"
  [key-scale chords]
  (mapv (fn [c]
          (let [a (chord->roman key-scale c)]
            (assoc a :tension (tension-score a))))
        chords))

;; ---------------------------------------------------------------------------
;; Borrowed chord / mode-mixture detection (Phase 2)
;; ---------------------------------------------------------------------------

(def ^:private major-intervals [2 2 1 2 2 2 1])
(def ^:private natural-minor-intervals [2 1 2 2 1 2 2])

(defn- parallel-key
  "Return the parallel major/minor Scale for `key-scale`.
  Returns nil when the scale mode cannot be identified as major or natural minor."
  [key-scale]
  (let [ivs     (:intervals key-scale)
        root-pc (mod (pitch/pitch->midi (:root key-scale)) 12)
        root-kw (pc->root-kw-map root-pc)
        octave  (get-in key-scale [:root :octave] 4)]
    (cond
      (= ivs major-intervals)         (scale-ns/scale root-kw octave :natural-minor)
      (= ivs natural-minor-intervals) (scale-ns/scale root-kw octave :major)
      :else nil)))

(defn borrowed-chord?
  "Return true if `analyzed` is not diatonic to `key-scale` but is diatonic to
  its parallel major or natural-minor key — the classical mode-mixture condition.

  `key-scale` — Scale record (major or natural-minor; other modes return false)
  `analyzed`  — result map from chord->roman (must have :root-pc and :in-key?)

  Returns false for diatonic chords and for keys with non-major/minor interval
  patterns.

  Example:
    (def key (scale/scale :C 4 :major))
    ;; Eb major — bIII, not in C major but in C minor
    (borrowed-chord? key {:root-pc 3 :quality :major :in-key? false ...})
    ;; => true"
  [key-scale analyzed]
  (boolean
    (and (not (:in-key? analyzed))
         (when-let [par (parallel-key key-scale)]
           (scale-ns/in-scale? par (pitch/midi->pitch (:root-pc analyzed)))))))

;; ---------------------------------------------------------------------------
;; Full progression annotation (Phase 2)
;; ---------------------------------------------------------------------------

(defn annotate-progression
  "Fully annotate a chord progression: Roman numeral, tension, and mode-mixture.

  Each result map extends chord->roman with:
    :tension   — float [0.0 .. 1.0] from tension-score
    :borrowed? — true if the chord is borrowed from the parallel major/minor key

  `key-scale` — Scale record
  `chords`    — seq of items accepted by chord->roman

  Example:
    (annotate-progression (scale/scale :C 4 :major)
      [[60 64 67]   ; I
       [63 67 70]   ; bIII — borrowed from C minor
       [65 69 72]   ; IV
       [60 64 67]]) ; I
    ;; => [{:roman :I   :tension 0.0  :borrowed? false}
    ;;     {:roman nil  :tension 0.85 :borrowed? true}
    ;;     {:roman :IV  :tension 0.4  :borrowed? false}
    ;;     {:roman :I   :tension 0.0  :borrowed? false}]"
  [key-scale chords]
  (mapv (fn [c]
          (let [a (chord->roman key-scale c)]
            (assoc a
                   :tension   (tension-score a)
                   :borrowed? (borrowed-chord? key-scale a))))
        chords))

;; ---------------------------------------------------------------------------
;; Progression generation (Phase 2)
;; ---------------------------------------------------------------------------

(defn- diatonic-chords
  "Return analyzed chord maps for all 7 diatonic triads in `key-scale`.
  Each triad is built by stacking thirds on each scale degree."
  [key-scale]
  (mapv (fn [i]
          (let [root-p  (scale-ns/pitch-at key-scale i)
                third-p (scale-ns/pitch-at key-scale (+ i 2))
                fifth-p (scale-ns/pitch-at key-scale (+ i 4))
                triad   [(pitch/pitch->midi root-p)
                         (pitch/pitch->midi third-p)
                         (pitch/pitch->midi fifth-p)]]
            (chord->roman key-scale triad)))
        (range 7)))

(defn suggest-progression
  "Generate a chord progression matching a target tension contour.

  For each tension target value the diatonic chord (and optionally borrowed chords
  from the parallel key) whose tension-score is closest to the target is selected.

  `key-scale`       — Scale record (major or natural-minor recommended)
  `tension-targets` — seq of floats in [0.0 .. 1.0], one per chord position

  Options:
    :include-borrowed? — also consider chords borrowed from the parallel key
                         (default false)

  Returns a vector of annotated chord maps (chord->roman + :tension + :borrowed?).

  Example:
    (suggest-progression (scale/scale :C 4 :major) [0.0 0.4 0.8 0.0])
    ;; likely => I IV V I

    (suggest-progression (scale/scale :C 4 :major) [0.0 0.85 0.9 0.0]
                         :include-borrowed? true)
    ;; high-tension targets may pull in borrowed VII or bVII"
  [key-scale tension-targets & {:keys [include-borrowed?]
                                :or   {include-borrowed? false}}]
  (let [diatonic   (diatonic-chords key-scale)
        par-key    (when include-borrowed? (parallel-key key-scale))
        borrowed   (when par-key
                     (->> (range 7)
                          (mapv (fn [i]
                                  (let [rp (scale-ns/pitch-at par-key i)
                                        tp (scale-ns/pitch-at par-key (+ i 2))
                                        fp (scale-ns/pitch-at par-key (+ i 4))
                                        tr [(pitch/pitch->midi rp)
                                            (pitch/pitch->midi tp)
                                            (pitch/pitch->midi fp)]]
                                    (chord->roman key-scale tr))))
                          (filter #(not (:in-key? %)))))
        candidates (->> (into diatonic (or borrowed []))
                        (mapv #(assoc %
                                      :tension   (tension-score %)
                                      :borrowed? (borrowed-chord? key-scale %))))]
    (mapv (fn [target]
            (apply min-key
                   #(Math/abs (- (double (:tension %)) (double target)))
                   candidates))
          tension-targets)))

;; ---------------------------------------------------------------------------
;; Modal disambiguation — extend major/minor detection to all 7 church modes
;; ---------------------------------------------------------------------------

(def ^:private modal-signatures
  "Characteristic scale-degree intervals that distinguish modes sharing the same
  major/minor classification. Each entry is [test-mode check-fn], where check-fn
  takes [pc-distribution tonic-pc] and returns a score in [0.0 .. 1.0].

  Checks compare the raised/lowered degree characteristic of each mode against
  the frequency of that pitch class in the recording."
  {:dorian      (fn [dist tonic]
                  ;; Raised 6th vs natural minor: compare pc at +9 (♮6) vs +8 (♭6)
                  (let [n6 (get dist (mod (+ tonic 9) 12) 0)
                        b6 (get dist (mod (+ tonic 8) 12) 0)]
                    (if (> (+ n6 b6) 0) (/ n6 (+ n6 b6)) 0.5)))
   :phrygian    (fn [dist tonic]
                  ;; Lowered 2nd: compare pc at +1 (♭2) vs +2 (♮2)
                  (let [b2 (get dist (mod (+ tonic 1) 12) 0)
                        n2 (get dist (mod (+ tonic 2) 12) 0)]
                    (if (> (+ b2 n2) 0) (/ b2 (+ b2 n2)) 0.5)))
   :lydian      (fn [dist tonic]
                  ;; Raised 4th: compare pc at +6 (#4) vs +5 (♮4)
                  (let [s4 (get dist (mod (+ tonic 6) 12) 0)
                        n4 (get dist (mod (+ tonic 5) 12) 0)]
                    (if (> (+ s4 n4) 0) (/ s4 (+ s4 n4)) 0.5)))
   :mixolydian  (fn [dist tonic]
                  ;; Lowered 7th: compare pc at +10 (♭7) vs +11 (♮7)
                  (let [b7 (get dist (mod (+ tonic 10) 12) 0)
                        n7 (get dist (mod (+ tonic 11) 12) 0)]
                    (if (> (+ b7 n7) 0) (/ b7 (+ b7 n7)) 0.5)))})

(defn detect-mode
  "Disambiguate the church mode of a pitch collection given its detected tonic.

  Uses `detect-key` to establish tonic and major/minor class, then examines
  the frequency of characteristic scale degrees to narrow to all 7 church modes.

  `pitches` — MIDI ints, Pitch records, or nested collections.

  Returns a map with:
    :tonic      — root keyword (:C :Db :D ...)
    :mode       — mode keyword (:ionian :dorian :phrygian :lydian
                                :mixolydian :aeolian :locrian)
    :confidence — float [0.0 .. 1.0]; based on KS score + modal disambiguation
    :ks-score   — raw Krumhansl-Schmuckler correlation score
    :alternatives — up to 2 next-best mode candidates with confidence scores

  Example:
    (detect-mode [38 50 53 57 62 64 65 67 69])
    ;; A NDLR Dorian bass/pad pattern in D
    ;; => {:tonic :D :mode :dorian :confidence 0.84 :ks-score 0.91}"
  [pitches]
  (let [candidates (detect-key-candidates pitches)
        best       (first candidates)
        ;; Invert pc->root-kw-map to get root keyword → pitch class
        root-kw->pc (into {} (map (fn [[pc kw]] [kw pc]) pc->root-kw-map))
        tonic-pc    (get root-kw->pc (:root best) 0)
        dist        (pitch-class-dist pitches)
        ks-score    (:score best)
        ;; Candidate modes and their disambiguating score functions
        ;; — call each fn with (dist tonic-pc) to evaluate the signature
        mode-candidates
        (if (= :major (:mode best))
          {:ionian     (fn [d t]
                         (let [lyd  ((modal-signatures :lydian)  d t)
                               mix  ((modal-signatures :mixolydian) d t)]
                           (- 1.0 (max lyd mix))))
           :lydian     (:lydian     modal-signatures)
           :mixolydian (:mixolydian modal-signatures)}
          {:aeolian    (fn [d t]
                         (let [dor ((modal-signatures :dorian)   d t)
                               phr ((modal-signatures :phrygian) d t)]
                           (- 1.0 (max dor phr))))
           :dorian     (:dorian   modal-signatures)
           :phrygian   (:phrygian modal-signatures)})
        ;; Evaluate each mode's characteristic score
        scored-modes
        (->> mode-candidates
             (map (fn [[mode score-fn]]
                    {:mode        mode
                     :modal-score (score-fn dist tonic-pc)}))
             (sort-by (comp - :modal-score)))
        best-mode   (first scored-modes)
        modal-score (:modal-score best-mode)
        confidence  (* (max 0.0 ks-score) (max 0.1 modal-score))]
    {:tonic        (:root best)
     :mode         (:mode best-mode)
     :confidence   confidence
     :ks-score     ks-score
     :alternatives (mapv #(select-keys % [:mode :modal-score])
                         (rest scored-modes))}))
