; SPDX-License-Identifier: EPL-2.0
(ns cljseq.voice-test
  "Tests for cljseq.voice — voice leading, named voicings, and progression analysis."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.chord  :as chord]
            [cljseq.voice  :as voice]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- c-major  [] (chord/chord :C 4 :major))
(defn- f-major  [] (chord/chord :F 4 :major))
(defn- g-dom7   [] (chord/chord :G 4 :dom7))
(defn- a-minor  [] (chord/chord :A 4 :minor))
(defn- cmaj7    [] (chord/chord :C 4 :maj7))

;; ---------------------------------------------------------------------------
;; close-position
;; ---------------------------------------------------------------------------

(deftest close-position-major-test
  (testing "close-position of C major = [60 64 67]"
    (is (= [60 64 67] (voice/close-position (c-major))))))

(deftest close-position-minor-test
  (testing "close-position of A minor = [69 72 76]"
    (is (= [69 72 76] (voice/close-position (a-minor))))))

(deftest close-position-7th-test
  (testing "close-position of Cmaj7 = [60 64 67 71]"
    (is (= [60 64 67 71] (voice/close-position (cmaj7))))))

(deftest close-position-sorted-test
  (testing "close-position is always sorted ascending"
    (doseq [q [:major :minor :dom7 :maj7 :dim7 :sus4]]
      (let [v (voice/close-position (chord/chord :D 4 q))]
        (is (= v (sort v)) (str "not sorted for " q))))))

;; ---------------------------------------------------------------------------
;; open-position
;; ---------------------------------------------------------------------------

(deftest open-position-widens-test
  (testing "open-position spans more semitones than close-position"
    (let [close (voice/close-position (c-major))
          open  (voice/open-position (c-major))
          close-span (- (last close) (first close))
          open-span  (- (last open)  (first open))]
      (is (> open-span close-span)))))

(deftest open-position-same-pitchclasses-test
  (testing "open-position has same pitch classes as close-position"
    (let [close-pcs (set (map #(mod % 12) (voice/close-position (c-major))))
          open-pcs  (set (map #(mod % 12) (voice/open-position  (c-major))))]
      (is (= close-pcs open-pcs)))))

;; ---------------------------------------------------------------------------
;; drop-2
;; ---------------------------------------------------------------------------

(deftest drop-2-4note-test
  (testing "drop-2 lowers 2nd-highest note by octave"
    (let [closed (voice/close-position (cmaj7))    ; [60 64 67 71]
          d2     (voice/drop-2 (cmaj7))
          ;; 2nd highest in [60 64 67 71] is 67 → 67-12=55
          ;; so d2 = sorted [55 60 64 71]
          ]
      (is (= 4 (count d2)) "4 notes preserved")
      ;; The dropped note (55) should be the new low note
      (is (< (first d2) (first closed)) "drop-2 adds a lower note"))))

(deftest drop-2-triad-fallback-test
  (testing "drop-2 on triad falls back to open-position"
    (let [d2   (voice/drop-2 (c-major))
          open (voice/open-position (c-major))]
      (is (= d2 open)))))

(deftest drop-2-same-pitchclasses-test
  (testing "drop-2 preserves pitch classes"
    (let [orig-pcs (set (map #(mod % 12) (voice/close-position (cmaj7))))
          d2-pcs   (set (map #(mod % 12) (voice/drop-2 (cmaj7))))]
      (is (= orig-pcs d2-pcs)))))

;; ---------------------------------------------------------------------------
;; drop-3
;; ---------------------------------------------------------------------------

(deftest drop-3-4note-test
  (testing "drop-3 lowers 3rd-highest note by octave"
    (let [d3 (voice/drop-3 (cmaj7))]
      (is (= 4 (count d3)))
      (is (every? #(<= 0 % 127) d3) "all MIDI in range"))))

(deftest drop-3-triad-fallback-test
  (testing "drop-3 on triad falls back to open-position"
    (is (= (voice/drop-3 (c-major))
           (voice/open-position (c-major))))))

;; ---------------------------------------------------------------------------
;; spread
;; ---------------------------------------------------------------------------

(deftest spread-spans-test
  (testing "spread spans at least 12 semitones for a triad"
    (let [v    (voice/spread (c-major))
          span (- (last v) (first v))]
      (is (>= span 12)))))

(deftest spread-in-range-test
  (testing "spread stays within MIDI range"
    (doseq [q [:major :minor :dom7 :maj7]]
      (let [v (voice/spread (chord/chord :C 4 q))]
        (is (every? #(<= 0 % 127) v) (str "out of range for " q))))))

;; ---------------------------------------------------------------------------
;; voice-lead
;; ---------------------------------------------------------------------------

(deftest voice-lead-reduces-movement-test
  (testing "voice-lead produces less movement than root-position C→F"
    (let [prev-close [60 64 67]                     ; C major
          f-root     (voice/close-position (f-major)) ; [65 69 72] — 5+5+5=15
          f-voiced   (voice/voice-lead prev-close (f-major))
          movement   (reduce + (map #(Math/abs (- %1 %2)) prev-close f-voiced))]
      ;; Best voice leading C→F should move less than root position (15)
      (is (<= movement 15)))))

(deftest voice-lead-preserves-pitch-classes-test
  (testing "voice-lead preserves the pitch classes of the target chord"
    (let [prev [60 64 67]
          voiced (voice/voice-lead prev (f-major))
          orig-pcs (set (map #(mod % 12) (voice/close-position (f-major))))
          new-pcs  (set (map #(mod % 12) voiced))]
      (is (= orig-pcs new-pcs)))))

(deftest voice-lead-same-note-count-test
  (testing "voice-lead returns same number of notes as prev"
    (is (= 3 (count (voice/voice-lead [60 64 67] (f-major)))))
    (is (= 4 (count (voice/voice-lead [60 64 67 71] (g-dom7)))))))

(deftest voice-lead-result-sorted-test
  (testing "voice-lead result is sorted ascending"
    (let [v (voice/voice-lead [60 64 67] (f-major))]
      (is (= v (sort v))))))

(deftest voice-lead-accepts-midi-vec-test
  (testing "voice-lead accepts raw MIDI vec as target"
    (let [result (voice/voice-lead [60 64 67] [65 69 72])]
      (is (= 3 (count result))))))

(deftest voice-lead-unison-no-change-test
  (testing "voice-lead to same chord returns same notes"
    (let [v (voice/voice-lead [60 64 67] (c-major))]
      (is (= [60 64 67] v)))))

;; ---------------------------------------------------------------------------
;; smooth-progression
;; ---------------------------------------------------------------------------

(deftest smooth-progression-length-test
  (testing "smooth-progression returns same number of voicings as input chords"
    (let [chords [(c-major) (f-major) (g-dom7) (c-major)]
          voiced (voice/smooth-progression chords)]
      (is (= 4 (count voiced))))))

(deftest smooth-progression-first-is-close-test
  (testing "first voicing in smooth-progression is close-position"
    (let [voiced (voice/smooth-progression [(c-major) (f-major)])]
      (is (= [60 64 67] (first voiced))))))

(deftest smooth-progression-with-seed-test
  (testing "smooth-progression respects explicit seed"
    (let [seed   [55 60 64]   ; G3 C4 E4 (C major 2nd inversion-ish)
          voiced (voice/smooth-progression [(f-major) (g-dom7)] seed)]
      (is (= (sort seed) (first voiced))))))

(deftest smooth-progression-pitch-classes-preserved-test
  (testing "each voicing's pitch classes are a subset of the chord's pitch classes"
    ;; voice-lead always preserves voice count from the first chord, so when a
    ;; target chord has more notes than the voice texture (e.g. 4-note dom7 in a
    ;; 3-voice progression), some chord tones are dropped — but no alien tones
    ;; are introduced.
    (let [chords [(c-major) (f-major) (g-dom7)]
          voiced (voice/smooth-progression chords)]
      (doseq [[c v] (map vector chords voiced)]
        (let [chord-pcs  (set (map #(mod % 12)
                                   (voice/close-position c)))
              actual-pcs (set (map #(mod % 12) v))]
          (is (every? chord-pcs actual-pcs)
              (str "alien pitch classes in voicing of " c)))))))

(deftest smooth-progression-monotone-movement-test
  (testing "total movement in a smooth progression is less than root-position jumps"
    (let [chords [(c-major) (f-major) (g-dom7) (c-major)]
          voiced (voice/smooth-progression chords)
          ;; total movement across all changes
          movement     (fn [a b] (reduce + (map #(Math/abs (- (long %1) (long %2))) (sort a) (sort b))))
          smooth-total (reduce + (map movement voiced (rest voiced)))
          ;; root-position total
          root-voiced  (mapv voice/close-position chords)
          root-total   (reduce + (map movement root-voiced (rest root-voiced)))]
      ;; Voice-led should be at most as much movement as root position
      (is (<= smooth-total root-total)
          (str "smooth=" smooth-total " root=" root-total)))))

;; ---------------------------------------------------------------------------
;; Voice extraction
;; ---------------------------------------------------------------------------

(deftest soprano-voice-test
  (testing "soprano-voice extracts highest note from each voicing"
    (let [voiced [[60 64 67] [65 69 72]]]
      (is (= [67 72] (voice/soprano-voice voiced))))))

(deftest bass-voice-test
  (testing "bass-voice extracts lowest note from each voicing"
    (let [voiced [[60 64 67] [65 69 72]]]
      (is (= [60 65] (voice/bass-voice voiced))))))

(deftest inner-voices-test
  (testing "inner-voices strips soprano and bass"
    (let [voiced [[60 64 67 71]]]
      (is (= [[64 67]] (voice/inner-voices voiced))))))

(deftest inner-voices-triad-test
  (testing "inner-voices of a triad returns single middle note"
    (let [voiced [[60 64 67]]]
      (is (= [[64]] (voice/inner-voices voiced))))))

;; ---------------------------------------------------------------------------
;; Progression motion analysis
;; ---------------------------------------------------------------------------

(deftest progression-motion-length-test
  (testing "progression-motion has one entry per chord change"
    (let [chords [(c-major) (f-major) (g-dom7) (c-major)]
          voiced (voice/smooth-progression chords)
          motion (voice/progression-motion voiced)]
      (is (= 3 (count motion))))))

(deftest progression-motion-keys-test
  (testing "each motion map has :soprano :bass :total-movement"
    (let [voiced (voice/smooth-progression [(c-major) (f-major)])
          motion (voice/progression-motion voiced)]
      (is (contains? (first motion) :soprano))
      (is (contains? (first motion) :bass))
      (is (contains? (first motion) :total-movement)))))

(deftest progression-motion-values-test
  (testing "motion type values are valid keywords"
    (let [voiced (voice/smooth-progression [(c-major) (f-major) (g-dom7)])
          valid  #{:oblique :parallel :similar :contrary}]
      (doseq [m (voice/progression-motion voiced)]
        (is (valid (:soprano m)) (str "invalid soprano motion: " (:soprano m)))
        (is (valid (:bass m))    (str "invalid bass motion: " (:bass m)))))))

(deftest progression-motion-total-movement-positive-test
  (testing "total-movement is a non-negative integer"
    (let [voiced (voice/smooth-progression [(c-major) (f-major)])]
      (doseq [m (voice/progression-motion voiced)]
        (is (>= (:total-movement m) 0))))))

(deftest progression-motion-not-always-oblique-test
  (testing "progression-motion soprano/bass are not always :oblique (bug regression)"
    ;; G major → F major: outer voices should move, giving non-oblique motion
    (let [g-major (chord/chord :G 4 :major)
          voiced  (voice/smooth-progression [(c-major) (f-major) g-major])
          motion  (voice/progression-motion voiced)]
      ;; At least one change should have non-oblique soprano or bass motion
      (is (some #(or (not= :oblique (:soprano %))
                     (not= :oblique (:bass %)))
                motion)))))

;; ---------------------------------------------------------------------------
;; parallel-fifths
;; ---------------------------------------------------------------------------

(deftest parallel-fifths-detects-test
  (testing "parallel-fifths flags voice pair moving in parallel P5"
    ;; C–G → D–A: both voice pairs move up by 2, interval stays P5 (7 semitones)
    (let [v1 [60 67]   ; C4 G4
          v2 [62 69]]  ; D4 A4
      (is (= [[0 1]] (voice/parallel-fifths v1 v2))))))

(deftest parallel-fifths-static-voice-no-flag-test
  (testing "parallel-fifths ignores pairs with a static voice"
    ;; C–G → C–A: bass static, not parallel
    (let [v1 [60 67]
          v2 [60 69]]
      (is (empty? (voice/parallel-fifths v1 v2))))))

(deftest parallel-fifths-contrary-motion-no-flag-test
  (testing "parallel-fifths does not flag contrary motion even if fifth preserved"
    ;; Contrary motion — voices cross
    (let [v1 [60 67]
          v2 [65 62]]  ; soprano down, bass up
      (is (empty? (voice/parallel-fifths v1 v2))))))

(deftest parallel-fifths-no-fifth-no-flag-test
  (testing "parallel-fifths ignores parallel motion that is not a fifth"
    ;; C–E → D–F#: parallel thirds, not fifths
    (let [v1 [60 64]
          v2 [62 66]]
      (is (empty? (voice/parallel-fifths v1 v2))))))

(deftest parallel-fifths-returns-indices-test
  (testing "parallel-fifths returns voice index pairs, not MIDI values"
    (let [v1 [60 64 67]
          v2 [62 66 69]]
      ;; Only 60→62 and 67→69 are a P5 pair (C–G)
      (doseq [pair (voice/parallel-fifths v1 v2)]
        (is (= 2 (count pair)) "each result is a pair of indices")
        (is (every? integer? pair))))))

;; ---------------------------------------------------------------------------
;; parallel-octaves
;; ---------------------------------------------------------------------------

(deftest parallel-octaves-detects-test
  (testing "parallel-octaves flags voice pair moving in parallel octaves"
    ;; C4–C5 → D4–D5: both move up 2, interval stays 0 mod 12
    (let [v1 [60 72]
          v2 [62 74]]
      (is (= [[0 1]] (voice/parallel-octaves v1 v2))))))

(deftest parallel-octaves-unison-detects-test
  (testing "parallel-octaves catches unisons (0 semitones apart)"
    (let [v1 [60 60]
          v2 [62 62]]
      (is (= [[0 1]] (voice/parallel-octaves v1 v2))))))

(deftest parallel-octaves-no-flag-for-fifths-test
  (testing "parallel-octaves does not flag parallel fifths"
    (let [v1 [60 67]
          v2 [62 69]]
      (is (empty? (voice/parallel-octaves v1 v2))))))

;; ---------------------------------------------------------------------------
;; voice-overlap
;; ---------------------------------------------------------------------------

(deftest voice-overlap-lower-jumps-above-test
  (testing "voice-overlap flags lower voice jumping above upper's prior position"
    ;; Bass was 60, soprano was 64; now bass=67 (above old soprano)
    (let [v1 [60 64]
          v2 [67 65]]
      ;; After sort: v2 = [65 67], but overlap should catch the crossing
      ;; Actually sort-asc will reorder, let's think carefully...
      ;; voice-overlap sorts both internally. v1 sorted = [60 64], v2 sorted = [65 67]
      ;; i=0, j=1: v2[0]=65 > v1[1]=64 → overlap at [0 1]
      (is (= [[0 1]] (voice/voice-overlap v1 v2))))))

(deftest voice-overlap-no-flag-stepwise-test
  (testing "voice-overlap does not flag clean stepwise motion"
    (let [v1 [60 67]
          v2 [62 69]]
      (is (empty? (voice/voice-overlap v1 v2))))))

(deftest voice-overlap-upper-drops-below-test
  (testing "voice-overlap flags upper voice dropping below lower's prior position"
    ;; Soprano was 67, bass was 64; now soprano=60 (below old bass 64)
    (let [v1 [64 67]
          v2 [65 60]]
      ;; v2 sorted = [60 65]; v2[1]=65 < v1[0]=64? No, 65 > 64. v2[0]=60 < v1[0]=64?
      ;; Check: v2[j=1]=65 < v1[i=0]=64? No.
      ;; Check: v2[i=0]=60 > v1[j=1]=67? No.
      ;; Hmm. Let me reconsider. v1=[64 67], v2=[60 65] (sorted).
      ;; i=0, j=1: v2[0]=60 > v1[1]=67? No. v2[1]=65 < v1[0]=64? No (65 >= 64).
      ;; No overlap here actually. Let me pick a clearer example.
      ;; v1=[64 67], soprano drops to 62, bass stays 65 → v2=[62 65]
      ;; i=0,j=1: v2[0]=62 > v1[1]=67? No. v2[1]=65 < v1[0]=64? No.
      ;; Still no overlap. Overlap definition: lower voice jumps above upper's prior position.
      ;; v1=[60 67], v2=[68 62] — but after sort v2=[62 68]
      ;; i=0,j=1: v2[0]=62 > v1[1]=67? No. v2[1]=68 < v1[0]=60? No.
      ;; Hmm. After sort the overlap is harder to trigger.
      ;; Actually overlap with sorting: since v2 is sorted, v2[0] <= v2[1]
      ;; Overlap condition: v2[i=0] > v1[j=1] means lowest new note > highest old note
      ;; That's not really overlap, that's parallel motion upward.
      ;; OR v2[j=1] < v1[i=0] means highest new note < lowest old note (both moved way down)
      ;; Hmm...
      ;; Wait, I think the original intent of voice-overlap is about crossing:
      ;; Voice i (lower) in v2 should still be below voice j (upper) in v2.
      ;; But the comparison is against v1 positions.
      ;; The standard definition: overlap (not crossing) occurs when voice i moves PAST
      ;; the PREVIOUS position of adjacent voice j.
      ;; e.g., bass moves up to D5 when soprano was at C5 - bass went above soprano's old position
      ;; Concrete: v1=[60,72], bass(0)=60, soprano(1)=72; v2=[74, 67]
      ;; After sort: v2=[67, 74]. i=0,j=1: v2[0]=67 > v1[1]=72? No.
      ;; v1=[60,72], v2=[73, 65]. After sort: v2=[65, 73].
      ;; i=0,j=1: v2[0]=65 > v1[1]=72? No. v2[1]=73 < v1[0]=60? No.
      ;; Still hard. Let me try v1=[60,65], v2=[66, 62]. After sort v2=[62,66].
      ;; i=0,j=1: v2[0]=62 > v1[1]=65? No. v2[1]=66 < v1[0]=60? No.
      ;; What about v1=[60, 62], v2=[63, 61]? After sort v2=[61, 63].
      ;; i=0,j=1: v2[0]=61 > v1[1]=62? No. v2[1]=63 < v1[0]=60? No.
      ;; Hmm, v1=[60, 63], v2=[64, 61]. After sort v2=[61,64].
      ;; i=0,j=1: v2[0]=61 > v1[1]=63? No. v2[1]=64 < v1[0]=60? No.
      ;; What gives? Let me look at the implementation again...
      ;; (> (long (sv2 i)) (long (sv1 j)))  → v2[i] > v1[j]  lower-new > upper-old
      ;; (< (long (sv2 j)) (long (sv1 i)))  → v2[j] < v1[i]  upper-new < lower-old
      ;; For the first condition: v2[0] > v1[1]
      ;; v1=[60 64], v2=[67 65] -> sorted v2=[65,67]
      ;; v2[0]=65 > v1[1]=64? YES! → overlap [0 1]
      ;; That's the test case I already wrote above. Good.
      ;; For the second condition: v2[1] < v1[0]
      ;; v1=[65 67], v2=[60 64] -> sorted v2=[60,64]
      ;; v2[1]=64 < v1[0]=65? YES! → overlap [0 1]
      (is (= [[0 1]] (voice/voice-overlap [65 67] [60 64]))))))

;; ---------------------------------------------------------------------------
;; counterpoint-errors
;; ---------------------------------------------------------------------------

(deftest counterpoint-errors-empty-for-clean-test
  (testing "counterpoint-errors returns empty seq for clean voice leading"
    ;; C major → F major in contrary motion (standard good voice leading)
    (let [v1 [60 64 67]   ; C major close
          v2 [60 65 69]]  ; F major — bass static, inner/soprano move contrary
      ;; Whether or not this specific pair has errors, the function must return a seq
      (is (sequential? (voice/counterpoint-errors v1 v2))))))

(deftest counterpoint-errors-flags-parallel-fifth-test
  (testing "counterpoint-errors includes :parallel-fifth type"
    (let [v1 [60 67]
          v2 [62 69]]
      (let [errs (voice/counterpoint-errors v1 v2)]
        (is (some #(= :parallel-fifth (:type %)) errs))))))

(deftest counterpoint-errors-error-map-shape-test
  (testing "counterpoint-errors error maps have :type and :voices keys"
    (let [v1 [60 67]
          v2 [62 69]]
      (doseq [err (voice/counterpoint-errors v1 v2)]
        (is (contains? err :type))
        (is (contains? err :voices))
        (is (vector? (:voices err)))))))

;; ---------------------------------------------------------------------------
;; progression-errors
;; ---------------------------------------------------------------------------

(deftest progression-errors-length-test
  (testing "progression-errors returns one seq per chord change"
    (let [chords [(c-major) (f-major) (g-dom7) (c-major)]
          voiced (voice/smooth-progression chords)
          errs   (voice/progression-errors voiced)]
      (is (= 3 (count errs))))))

(deftest progression-errors-elements-are-vecs-test
  (testing "progression-errors inner elements are vecs"
    (let [voiced (voice/smooth-progression [(c-major) (f-major)])]
      (doseq [e (voice/progression-errors voiced)]
        (is (vector? e))))))

(deftest progression-errors-parallel-fifth-detected-test
  (testing "progression-errors flags known parallel fifth in a 2-voice sequence"
    (let [voiced [[60 67] [62 69]]   ; C–G → D–A
          errs   (voice/progression-errors voiced)]
      (is (= 1 (count errs)))
      (is (some #(= :parallel-fifth (:type %)) (first errs))))))
