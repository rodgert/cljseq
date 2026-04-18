; SPDX-License-Identifier: EPL-2.0
(ns cljseq.voice
  "Voice leading — smooth connections between chords (§6).

  Voice leading minimizes the total semitone movement across chord changes.
  Given a previous voicing (seq of MIDI integers) and a target chord, the
  algorithm searches all rotations and octave shifts of the target chord tones
  to find the assignment that minimizes the sum of absolute voice distances.

  ## Core algorithm
    (voice-lead prev-midis target-chord)  ; → MIDI int vec, best voicing
    (voice-lead prev-midis target-midis)  ; → same, from raw MIDI vec

  ## Named voicings
    (close-position  chord)  ; notes packed within one octave
    (open-position   chord)  ; notes alternated between lower/upper octave
    (drop-2          chord)  ; standard jazz drop-2 (2nd-highest dropped an octave)
    (drop-3          chord)  ; standard jazz drop-3 (3rd-highest dropped an octave)
    (spread          chord)  ; widest possible spacing within ~2 octaves

  ## Progression helpers
    (smooth-progression chords)       ; seq of Chord → seq of MIDI-int vecs
    (smooth-progression chords seed)  ; provide first voicing explicitly

  ## Voice extraction
    (soprano-voice voiced-seq)  ; highest note from each voicing
    (bass-voice    voiced-seq)  ; lowest note from each voicing
    (inner-voices  voiced-seq)  ; middle voices (all except soprano and bass)

  ## Motion analysis
    (progression-motion voiced-seq)  ; outer-voice motion per change (:oblique etc.)

  ## Counterpoint rules (Phase 2)
    (parallel-fifths  v1 v2)     ; → [[i j] ...] voice pairs moving in parallel P5
    (parallel-octaves v1 v2)     ; → [[i j] ...] parallel octaves / unisons
    (voice-overlap    v1 v2)     ; → [[i j] ...] adjacent pairs where voices overlap
    (counterpoint-errors v1 v2)  ; → seq of {:type kw :voices [i j]} maps
    (progression-errors voiced-seq) ; → vec of error seqs, one per change

  Key design decisions: R&R §6, nearest-neighbour voice-leading algorithm."
  (:require [cljseq.chord :as chord]))

;; ---------------------------------------------------------------------------
;; Private utilities
;; ---------------------------------------------------------------------------

(defn- sort-asc
  "Return a sorted (ascending) vector of MIDI integers."
  [midis]
  (vec (sort (map long midis))))

(defn- total-movement
  "Sum of absolute semitone distances between corresponding notes."
  [a b]
  (reduce + 0 (map #(Math/abs (- (long %1) (long %2))) a b)))

(defn- cartesian-product
  "Lazy cartesian product of colls — returns seq of vecs."
  [& colls]
  (reduce (fn [acc coll]
            (for [a acc b coll]
              (conj (vec a) b)))
          [[]]
          colls))

(defn- candidate-placements
  "For a MIDI note `m`, return candidate octave placements near `anchor`."
  [m anchor]
  (let [pc   (mod (long m) 12)
        base (* 12 (quot (long anchor) 12))]
    (filterv #(<= 0 % 127)
             [(+ base pc -12)
              (+ base pc)
              (+ base pc 12)
              (+ base pc 24)])))

(defn- rotations
  "Return all cyclic rotations of vector `v`."
  [v]
  (let [n (count v)]
    (mapv (fn [i] (into (subvec v i) (subvec v 0 i)))
          (range n))))

;; ---------------------------------------------------------------------------
;; Core voice-leading algorithm
;; ---------------------------------------------------------------------------

(defn voice-lead
  "Return the voicing of `target` that minimizes total movement from `prev`.

  `prev`   — seq of MIDI integers (previous chord voicing).
  `target` — cljseq.chord/Chord record OR seq of MIDI integers.

  Algorithm:
  1. Extract target MIDI notes and enumerate all rotations.
  2. For each rotation, generate all ±octave placements near the prev anchor.
  3. Pick the sorted candidate that minimizes total semitone movement.

  Both `prev` and the result are sorted ascending (lowest → highest pitch)."
  [prev target]
  (let [prev-sorted (sort-asc prev)
        n           (count prev-sorted)
        anchor      (long (/ (reduce + prev-sorted) (max 1 n)))
        target-midis (if (instance? cljseq.chord.Chord target)
                       (chord/chord->midis target)
                       (sort-asc target))
        candidates  (for [rot  (rotations (sort-asc target-midis))
                          cand (apply cartesian-product
                                      (map #(candidate-placements % anchor) rot))
                          :let  [sv (sort-asc cand)]
                          :when (= (count sv) n)]
                      sv)]
    (if (seq candidates)
      (apply min-key #(total-movement prev-sorted %) candidates)
      (vec (take n (sort-asc target-midis))))))

;; ---------------------------------------------------------------------------
;; Named voicings
;; ---------------------------------------------------------------------------

(defn close-position
  "Return a MIDI-int vec with all chord tones packed within one octave.
  Notes are stacked ascending from root position.

  Example: C major close = [60 64 67]"
  [c]
  (sort-asc (chord/chord->midis c)))

(defn open-position
  "Return a MIDI-int vec with chord tones spread over roughly two octaves.
  Every other note (0-indexed) is raised by an octave.

  Example: C major open = [60 67 76] (C4 G4 E5)"
  [c]
  (let [closed (sort-asc (chord/chord->midis c))]
    (sort-asc (map-indexed (fn [i m] (if (odd? i) (+ m 12) m)) closed))))

(defn drop-2
  "Return a drop-2 voicing: the second-highest note dropped one octave.
  Standard jazz piano/guitar voicing for 4-note chords; falls back to
  open-position for triads.

  Example: Cmaj7 drop-2 = [52 60 64 71] (E3 C4 E4 B4)"
  [c]
  (let [sorted (sort-asc (chord/chord->midis c))
        n      (count sorted)]
    (if (< n 4)
      (open-position c)
      (sort-asc (update sorted (- n 2) - 12)))))

(defn drop-3
  "Return a drop-3 voicing: the third-highest note dropped one octave.
  Common jazz voicing for 4-note chords; falls back to open-position for triads."
  [c]
  (let [sorted (sort-asc (chord/chord->midis c))
        n      (count sorted)]
    (if (< n 4)
      (open-position c)
      (sort-asc (update sorted (- n 3) - 12)))))

(defn spread
  "Return the widest possible voicing, distributing notes across ~24 semitones.
  Useful for lush pad textures."
  [c]
  (let [sorted (sort-asc (chord/chord->midis c))
        root   (first sorted)
        n      (count sorted)
        step   (if (> n 1) (/ 24.0 (dec n)) 12.0)]
    (sort-asc
      (map-indexed
        (fn [i m]
          (let [target (long (+ root (* i step)))
                pc     (mod (long m) 12)
                base   (* 12 (quot target 12))
                candidates (filterv #(<= 0 % 127)
                                    [(+ base pc -12) (+ base pc) (+ base pc 12)])
                best (apply min-key #(Math/abs ^long (- target (long %))) candidates)]
            best))
        sorted))))

;; ---------------------------------------------------------------------------
;; Smooth progression
;; ---------------------------------------------------------------------------

(defn smooth-progression
  "Apply voice leading to a sequence of chords, returning a vec of MIDI-int vecs.

  Each chord is voiced to minimize total semitone movement from the previous.

  `chords` — seq of Chord records or MIDI-int vecs.
  `seed`   — optional first voicing (MIDI-int vec). When omitted the first
             chord is voiced in close position.

  Example:
    (smooth-progression
      [(chord/chord :C 4 :major)
       (chord/chord :F 4 :major)
       (chord/chord :G 4 :dom7)
       (chord/chord :C 4 :major)])"
  ([chords]
   (when (seq chords)
     (let [first-voiced (close-position (first chords))]
       (smooth-progression (rest chords) first-voiced [first-voiced]))))
  ([chords seed]
   ;; Public 2-arity: seed is an explicit first voicing
   (smooth-progression (seq chords) (sort-asc seed) [(sort-asc seed)]))
  ([chords prev acc]
   ;; Private recursive accumulator — called internally only
   (if (empty? chords)
     acc
     (let [next-voiced (voice-lead prev (first chords))]
       (recur (rest chords) next-voiced (conj acc next-voiced))))))

;; ---------------------------------------------------------------------------
;; Voice extraction
;; ---------------------------------------------------------------------------

(defn soprano-voice
  "Return a vec of the highest MIDI note from each voicing."
  [voiced-seq]
  (mapv #(apply max %) voiced-seq))

(defn bass-voice
  "Return a vec of the lowest MIDI note from each voicing."
  [voiced-seq]
  (mapv #(apply min %) voiced-seq))

(defn inner-voices
  "Return a vec of inner-voice vecs (all notes except soprano and bass)."
  [voiced-seq]
  (mapv (fn [v]
          (let [sv (sort-asc v)]
            (subvec sv 1 (max 1 (dec (count sv))))))
        voiced-seq))

;; ---------------------------------------------------------------------------
;; Voice motion analysis
;; ---------------------------------------------------------------------------

(defn- motion-type
  "Classify motion between voice `a` (from a1 → a2) and voice `b` (b1 → b2)."
  [[a1 a2] [b1 b2]]
  (let [da (- (long a2) (long a1))
        db (- (long b2) (long b1))]
    (cond
      (or (zero? da) (zero? db)) :oblique
      (= da db)                  :parallel
      (and (pos? da) (pos? db))  :similar
      (and (neg? da) (neg? db))  :similar
      :else                      :contrary)))

(defn progression-motion
  "Analyse voice motion across a voiced progression.

  Returns a vec of motion maps, one per chord change:
    {:soprano kw :bass kw :total-movement n}

  where kw is :oblique, :parallel, :similar, or :contrary."
  [voiced-seq]
  (mapv (fn [[v1 v2]]
          (let [sv1 (sort-asc v1) sv2 (sort-asc v2)
                s1  (last sv1)    s2  (last sv2)
                b1  (first sv1)   b2  (first sv2)]
            {:soprano        (motion-type [s1 s2] [b1 b2])
             :bass           (motion-type [b1 b2] [s1 s2])
             :total-movement (total-movement sv1 sv2)}))
        (partition 2 1 voiced-seq)))

;; ---------------------------------------------------------------------------
;; Counterpoint rules (Phase 2)
;; ---------------------------------------------------------------------------

(defn- interval-mod12
  "Interval between two MIDI notes, mod 12 (pitch-class interval)."
  [lo hi]
  (mod (Math/abs (- (long hi) (long lo))) 12))

(defn parallel-fifths
  "Return a vec of `[i j]` voice-index pairs that move in parallel perfect fifths.

  Both voices must actually move (neither static) and move in the same direction.
  The interval between them must be a perfect fifth (7 semitones mod 12) in
  both `v1` and `v2`.

  `v1` and `v2` are sorted MIDI-int vecs of the same length."
  [v1 v2]
  (let [sv1 (sort-asc v1)
        sv2 (sort-asc v2)
        n   (count sv1)]
    (into []
          (for [i (range n)
                j (range (inc i) n)
                :let [da (- (long (sv2 i)) (long (sv1 i)))
                      db (- (long (sv2 j)) (long (sv1 j)))]
                :when (and (not (zero? da))
                           (not (zero? db))
                           (= (pos? da) (pos? db))
                           (= 7 (interval-mod12 (sv1 i) (sv1 j)))
                           (= 7 (interval-mod12 (sv2 i) (sv2 j))))]
            [i j]))))

(defn parallel-octaves
  "Return a vec of `[i j]` voice-index pairs that move in parallel octaves or
  unisons.

  Both voices must actually move (neither static) and move in the same direction.
  The interval between them must be 0 mod 12 (octave or unison) in both
  `v1` and `v2`.

  `v1` and `v2` are sorted MIDI-int vecs of the same length."
  [v1 v2]
  (let [sv1 (sort-asc v1)
        sv2 (sort-asc v2)
        n   (count sv1)]
    (into []
          (for [i (range n)
                j (range (inc i) n)
                :let [da (- (long (sv2 i)) (long (sv1 i)))
                      db (- (long (sv2 j)) (long (sv1 j)))]
                :when (and (not (zero? da))
                           (not (zero? db))
                           (= (pos? da) (pos? db))
                           (= 0 (interval-mod12 (sv1 i) (sv1 j)))
                           (= 0 (interval-mod12 (sv2 i) (sv2 j))))]
            [i j]))))

(defn voice-overlap
  "Return a vec of `[i j]` adjacent voice-index pairs that overlap across a
  chord change.

  Overlap occurs when a lower voice in `v2` lands above the upper voice's
  prior position, or vice versa:
    v2[i] > v1[j]  (lower voice jumps over where upper voice was)
    v2[j] < v1[i]  (upper voice drops below where lower voice was)

  `v1` and `v2` are sorted MIDI-int vecs of the same length."
  [v1 v2]
  (let [sv1 (sort-asc v1)
        sv2 (sort-asc v2)
        n   (count sv1)]
    (into []
          (for [i (range (dec n))
                :let [j (inc i)]
                :when (or (> (long (sv2 i)) (long (sv1 j)))
                          (< (long (sv2 j)) (long (sv1 i))))]
            [i j]))))

(defn counterpoint-errors
  "Return a seq of error maps for a single chord change from `v1` to `v2`.

  Each map has:
    {:type   kw         ; :parallel-fifth, :parallel-octave, or :voice-overlap
     :voices [i j]}     ; voice indices involved

  `v1` and `v2` are sorted MIDI-int vecs of the same length."
  [v1 v2]
  (concat
    (map (fn [pair] {:type :parallel-fifth  :voices pair}) (parallel-fifths  v1 v2))
    (map (fn [pair] {:type :parallel-octave :voices pair}) (parallel-octaves v1 v2))
    (map (fn [pair] {:type :voice-overlap   :voices pair}) (voice-overlap    v1 v2))))

(defn progression-errors
  "Return a vec of error seqs, one per chord change in `voiced-seq`.

  Each element is the result of `counterpoint-errors` for that change.
  An empty inner seq means no violations at that step.

  `voiced-seq` is a seq of sorted MIDI-int vecs."
  [voiced-seq]
  (mapv (fn [[v1 v2]] (vec (counterpoint-errors v1 v2)))
        (partition 2 1 voiced-seq)))
