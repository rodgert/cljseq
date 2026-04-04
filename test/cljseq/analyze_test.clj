; SPDX-License-Identifier: EPL-2.0
(ns cljseq.analyze-test
  "Unit tests for cljseq.analyze — key detection, chord identification,
  Roman numeral analysis, and scale fitness."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.analyze :as analyze]
            [cljseq.chord   :as chord-ns]
            [cljseq.pitch   :as pitch]
            [cljseq.scale   :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- c-major-scale [] (scale-ns/scale :C 4 :major))
(defn- a-minor-scale [] (scale-ns/scale :A 4 :natural-minor))

;; One octave of C major MIDI notes
(def c-major-notes [60 62 64 65 67 69 71 72])

;; C major triad, F major triad — clear C major context
(def c-major-context [60 64 67 65 69 72 67 71 74])

;; ---------------------------------------------------------------------------
;; pitch-class-dist
;; ---------------------------------------------------------------------------

(deftest pitch-class-dist-basic-test
  (testing "counts each pitch class correctly"
    (let [d (analyze/pitch-class-dist [60 64 67])]  ; C E G
      (is (= 1 (get d 0)))   ; C
      (is (= 1 (get d 4)))   ; E
      (is (= 1 (get d 7))))) ; G
  (testing "accumulates duplicate pitch classes"
    (let [d (analyze/pitch-class-dist [60 64 67 64 60])]
      (is (= 2 (get d 0)))
      (is (= 2 (get d 4)))
      (is (= 1 (get d 7)))))
  (testing "folds notes across octaves into pitch class"
    (let [d (analyze/pitch-class-dist [60 72 84])]  ; C4 C5 C6
      (is (= 3 (get d 0)))
      (is (= 1 (count d)))))
  (testing "accepts Pitch records"
    (let [d (analyze/pitch-class-dist [(pitch/pitch :C 4)
                                       (pitch/pitch :E 4)
                                       (pitch/pitch :G 4)])]
      (is (= 1 (get d 0)))
      (is (= 1 (get d 4)))
      (is (= 1 (get d 7)))))
  (testing "accepts Chord records"
    (let [c (chord-ns/chord :C 4 :major)
          d (analyze/pitch-class-dist [c])]
      (is (= 1 (get d 0)))
      (is (= 1 (get d 4)))
      (is (= 1 (get d 7)))))
  (testing "returns empty map for empty input"
    (is (= {} (analyze/pitch-class-dist [])))))

;; ---------------------------------------------------------------------------
;; detect-key
;; ---------------------------------------------------------------------------

(deftest detect-key-c-major-test
  (testing "detects C major from its own scale tones"
    (let [result (analyze/detect-key c-major-notes)]
      (is (= :C (:root result)))
      (is (= :major (:mode result)))
      (is (> (:score result) 0.9)))))

(deftest detect-key-a-minor-test
  (testing "A harmonic minor is clearly detected (raised 7th distinguishes from C major)"
    ;; A B C D E F G# — harmonic minor; G# (PC 8) is not in C major
    (let [notes  [57 59 60 62 64 65 68]
          result (analyze/detect-key notes)]
      (is (= :A (:root result)))
      (is (= :minor (:mode result)))))
  (testing "A natural minor appears in top-3 candidates for its own scale tones"
    ;; Natural minor shares all 7 PCs with C major — top result may be C major,
    ;; but A minor must be in the top 3 (relative key ambiguity is expected).
    (let [notes      [57 59 60 62 64 65 67]  ; A B C D E F G
          candidates (take 3 (analyze/detect-key-candidates notes))
          roots      (map :root candidates)]
      (is (some #{:A} roots) "A minor should appear in top-3 for its own notes"))))

(deftest detect-key-g-major-test
  (testing "detects G major"
    (let [notes [67 69 71 72 74 76 78]  ; G A B C D E F#
          result (analyze/detect-key notes)]
      (is (= :G (:root result)))
      (is (= :major (:mode result))))))

(deftest detect-key-f-major-test
  (testing "detects F major (one flat)"
    (let [notes [65 67 69 70 72 74 76]  ; F G A Bb C D E
          result (analyze/detect-key notes)]
      (is (= :F (:root result)))
      (is (= :major (:mode result))))))

(deftest detect-key-candidates-count-test
  (testing "returns 24 candidates (12 roots × 2 modes)"
    (is (= 24 (count (analyze/detect-key-candidates c-major-notes)))))
  (testing "candidates are sorted by score descending"
    (let [candidates (analyze/detect-key-candidates c-major-notes)
          scores     (map :score candidates)]
      (is (= scores (sort > scores))))))

(deftest detect-key-accepts-chord-records-test
  (testing "detect-key accepts Chord records"
    (let [chords [(chord-ns/chord :C 4 :major)
                  (chord-ns/chord :F 4 :major)
                  (chord-ns/chord :G 4 :major)]
          result (analyze/detect-key chords)]
      (is (= :C (:root result)))
      (is (= :major (:mode result))))))

;; ---------------------------------------------------------------------------
;; identify-chord
;; ---------------------------------------------------------------------------

(deftest identify-chord-major-triad-test
  (testing "identifies C major triad"
    (let [r (analyze/identify-chord [60 64 67])]
      (is (= :C (:root r)))
      (is (= :major (:quality r)))
      (is (= 0 (:inversion r)))
      (is (= 1.0 (:score r)))))
  (testing "identifies G major triad"
    (let [r (analyze/identify-chord [67 71 74])]
      (is (= :G (:root r)))
      (is (= :major (:quality r)))))
  (testing "identifies F major triad"
    (let [r (analyze/identify-chord [65 69 72])]
      (is (= :F (:root r)))
      (is (= :major (:quality r))))))

(deftest identify-chord-minor-triad-test
  (testing "identifies A minor triad"
    (let [r (analyze/identify-chord [57 60 64])]
      (is (= :A (:root r)))
      (is (= :minor (:quality r)))))
  (testing "identifies D minor triad"
    (let [r (analyze/identify-chord [62 65 69])]
      (is (= :D (:root r)))
      (is (= :minor (:quality r))))))

(deftest identify-chord-seventh-chords-test
  (testing "identifies G dominant 7th"
    (let [r (analyze/identify-chord [67 71 74 77])]
      (is (= :G (:root r)))
      (is (= :dom7 (:quality r)))))
  (testing "identifies C major 7th"
    (let [r (analyze/identify-chord [60 64 67 71])]
      (is (= :C (:root r)))
      (is (= :maj7 (:quality r)))))
  (testing "identifies D minor 7th"
    (let [r (analyze/identify-chord [62 65 69 72])]
      (is (= :D (:root r)))
      (is (= :min7 (:quality r))))))

(deftest identify-chord-diminished-test
  (testing "identifies B diminished"
    (let [r (analyze/identify-chord [71 74 77])]
      (is (= :B (:root r)))
      (is (= :diminished (:quality r))))))

(deftest identify-chord-inversion-test
  (testing "first inversion: E is bass note of C major"
    (let [r (analyze/identify-chord [64 67 72])]  ; E G C
      (is (= :C (:root r)))
      (is (= :major (:quality r)))
      (is (= 1 (:inversion r)))))
  (testing "second inversion: G is bass note of C major"
    (let [r (analyze/identify-chord [67 72 76])]  ; G C E
      (is (= :C (:root r)))
      (is (= :major (:quality r)))
      (is (= 2 (:inversion r))))))

(deftest identify-chord-accepts-pitch-records-test
  (testing "accepts Pitch records"
    (let [r (analyze/identify-chord [(pitch/pitch :C 4)
                                     (pitch/pitch :E 4)
                                     (pitch/pitch :G 4)])]
      (is (= :C (:root r)))
      (is (= :major (:quality r))))))

(deftest identify-chord-accepts-chord-record-test
  (testing "accepts Chord records"
    (let [c (chord-ns/chord :C 4 :major)
          r (analyze/identify-chord [c])]
      (is (= :C (:root r)))
      (is (= :major (:quality r))))))

(deftest identify-chord-nil-on-empty-test
  (testing "returns nil for empty input"
    (is (nil? (analyze/identify-chord [])))))

(deftest identify-chord-candidates-test
  (testing "returns candidates sorted by score"
    (let [candidates (analyze/identify-chord-candidates [60 64 67])]
      (is (seq candidates))
      (let [scores (map :score candidates)]
        (is (= scores (sort > scores))))
      ;; First candidate should be C major with score 1.0
      (let [best (first candidates)]
        (is (= :C (:root best)))
        (is (= :major (:quality best)))
        (is (= 1.0 (:score best)))))))

;; ---------------------------------------------------------------------------
;; chord->roman
;; ---------------------------------------------------------------------------

(deftest chord->roman-basic-test
  (testing "I chord (tonic)"
    (let [r (analyze/chord->roman (c-major-scale) [60 64 67])]
      (is (= :I (:roman r)))
      (is (= 0 (:degree r)))
      (is (true? (:in-key? r)))))
  (testing "IV chord"
    (let [r (analyze/chord->roman (c-major-scale) [65 69 72])]
      (is (= :IV (:roman r)))
      (is (= 3 (:degree r)))))
  (testing "V chord"
    (let [r (analyze/chord->roman (c-major-scale) [67 71 74])]
      (is (= :V (:roman r)))
      (is (= 4 (:degree r)))))
  (testing "ii chord"
    (let [r (analyze/chord->roman (c-major-scale) [62 65 69])]
      (is (= :II (:roman r)))
      (is (= 1 (:degree r)))))
  (testing "vi chord"
    (let [r (analyze/chord->roman (c-major-scale) [69 72 76])]
      (is (= :VI (:roman r)))
      (is (= 5 (:degree r)))))
  (testing "vii° chord"
    (let [r (analyze/chord->roman (c-major-scale) [71 74 77])]
      (is (= :VII (:roman r)))
      (is (= 6 (:degree r))))))

(deftest chord->roman-chromatic-test
  (testing "chromatic chord root returns :in-key? false"
    ;; Db major chord is not diatonic to C major
    (let [r (analyze/chord->roman (c-major-scale) [61 65 68])]
      (is (false? (:in-key? r)))
      (is (nil? (:roman r)))
      (is (nil? (:degree r))))))

(deftest chord->roman-dominant-seventh-test
  (testing "V7 chord identified correctly"
    (let [r (analyze/chord->roman (c-major-scale) [67 71 74 77])]
      (is (= :V (:roman r)))
      (is (= :dom7 (:quality r))))))

(deftest chord->roman-accepts-chord-record-test
  (testing "accepts Chord records"
    (let [c (chord-ns/chord :G 4 :major)
          r (analyze/chord->roman (c-major-scale) c)]
      (is (= :V (:roman r))))))

;; ---------------------------------------------------------------------------
;; analyze-progression
;; ---------------------------------------------------------------------------

(deftest analyze-progression-test
  (testing "I-IV-V-I progression in C major"
    (let [key   (c-major-scale)
          chords [[60 64 67]   ; C major
                  [65 69 72]   ; F major
                  [67 71 74]   ; G major
                  [60 64 67]]  ; C major
          result (analyze/analyze-progression key chords)]
      (is (= 4 (count result)))
      (is (= :I   (:roman (nth result 0))))
      (is (= :IV  (:roman (nth result 1))))
      (is (= :V   (:roman (nth result 2))))
      (is (= :I   (:roman (nth result 3))))))
  (testing "returns vector"
    (let [result (analyze/analyze-progression (c-major-scale) [[60 64 67]])]
      (is (vector? result)))))

(deftest analyze-progression-ii-V-I-test
  (testing "ii-V7-I jazz cadence in C major"
    (let [key    (c-major-scale)
          chords [[62 65 69 72]   ; Dm7
                  [67 71 74 77]   ; G7
                  [60 64 67 71]]  ; Cmaj7
          result (analyze/analyze-progression key chords)]
      (is (= :II (:roman (nth result 0))))
      (is (= :V  (:roman (nth result 1))))
      (is (= :I  (:roman (nth result 2)))))))

;; ---------------------------------------------------------------------------
;; scale-fitness
;; ---------------------------------------------------------------------------

(deftest scale-fitness-perfect-test
  (testing "all C major tones score 1.0"
    (is (= 1.0 (analyze/scale-fitness (c-major-scale) c-major-notes))))
  (testing "all A minor tones score 1.0 against A minor"
    (let [notes [57 59 60 62 64 65 67]]  ; A B C D E F G
      (is (= 1.0 (analyze/scale-fitness (a-minor-scale) notes))))))

(deftest scale-fitness-partial-test
  (testing "one chromatic note reduces fitness"
    (let [notes [60 64 67 61]  ; C major + C#
          fitness (analyze/scale-fitness (c-major-scale) notes)]
      (is (< fitness 1.0))
      (is (> fitness 0.0))))
  (testing "no matching notes returns 0.0"
    ;; All black keys vs C major (which has no sharps/flats except leading tones)
    ;; Actually C major has no black keys. Black-key pentatonic = C# D# F# G# A#
    (let [notes [61 63 66 68 70]  ; Db Eb Gb Ab Bb — all out of C major
          fitness (analyze/scale-fitness (c-major-scale) notes)]
      (is (< fitness 0.5)))))

(deftest scale-fitness-empty-test
  (testing "empty input returns 0.0"
    (is (= 0.0 (analyze/scale-fitness (c-major-scale) [])))))

;; ---------------------------------------------------------------------------
;; suggest-scales
;; ---------------------------------------------------------------------------

(deftest suggest-scales-c-major-test
  (testing "all 7 C major scale tones are in C major (and its modes)"
    (let [results (analyze/suggest-scales c-major-notes)]
      (is (seq results))
      ;; Top result should have fitness 1.0
      (is (= 1.0 (:fitness (first results))))
      ;; C major should appear in top results
      (let [c-major-entry (first (filter #(and (= :C (:root %))
                                               (= :major (:mode %)))
                                         results))]
        (is (some? c-major-entry))
        (is (= 1.0 (:fitness c-major-entry)))))))

(deftest suggest-scales-top-n-test
  (testing ":top-n limits results"
    (let [results (analyze/suggest-scales c-major-notes :top-n 3)]
      (is (= 3 (count results)))))
  (testing "default top-n is 10"
    (let [results (analyze/suggest-scales c-major-notes)]
      (is (<= (count results) 10)))))

(deftest suggest-scales-min-fitness-test
  (testing ":min-fitness filters out low-scoring scales"
    (let [results (analyze/suggest-scales c-major-notes :min-fitness 0.9 :top-n 100)]
      (is (every? #(>= (:fitness %) 0.9) results)))))

(deftest suggest-scales-sorted-test
  (testing "results are sorted by fitness descending"
    (let [results (analyze/suggest-scales c-major-notes :top-n 20)
          fits    (map :fitness results)]
      (is (= fits (sort > fits))))))

(deftest suggest-scales-pentatonic-test
  (testing "5-note input matches pentatonic scales"
    ;; C pentatonic major: C D E G A — PCs 0 2 4 7 9
    (let [notes   [60 62 64 67 69]
          results (analyze/suggest-scales notes :top-n 50 :min-fitness 1.0)
          modes   (set (map :mode results))]
      (is (contains? modes :pentatonic-major)))))

(deftest suggest-scales-nil-on-empty-test
  (testing "empty input returns nil"
    (is (nil? (analyze/suggest-scales [])))))

;; ---------------------------------------------------------------------------
;; tension-score
;; ---------------------------------------------------------------------------

(deftest tension-score-tonic-test
  (testing "I major scores 0.0 — fully resolved"
    (is (= 0.0 (analyze/tension-score {:degree 0 :quality :major}))))
  (testing "I maj7 scores slightly negative base but clamps to 0.0"
    (is (>= (analyze/tension-score {:degree 0 :quality :maj7}) 0.0))))

(deftest tension-score-dominant-test
  (testing "V major scores 0.8"
    (is (= 0.8 (analyze/tension-score {:degree 4 :quality :major}))))
  (testing "V dom7 scores 0.9 (V + dom7 delta)"
    (is (= 0.9 (analyze/tension-score {:degree 4 :quality :dom7})))))

(deftest tension-score-subdominant-test
  (testing "IV major scores 0.4"
    (is (= 0.4 (analyze/tension-score {:degree 3 :quality :major}))))
  (testing "II minor scores 0.5"
    (is (= 0.5 (analyze/tension-score {:degree 1 :quality :minor})))))

(deftest tension-score-leading-tone-test
  (testing "VII diminished scores 1.0 (0.9 base + 0.10 quality delta, clamped)"
    (is (= 1.0 (analyze/tension-score {:degree 6 :quality :diminished}))))
  (testing "VII dim7 scores 1.0 (clamped)"
    (is (= 1.0 (analyze/tension-score {:degree 6 :quality :dim7}))))
  (testing "VII major (uncommon) scores 0.9 base with no quality delta"
    (is (= 0.9 (analyze/tension-score {:degree 6 :quality :major})))))

(deftest tension-score-chromatic-test
  (testing "nil degree (chromatic chord) scores 0.85"
    (is (= 0.85 (analyze/tension-score {:degree nil :quality :major}))))
  (testing "result always in [0.0 .. 1.0]"
    (doseq [d (conj (vec (range 7)) nil)
            q [:major :minor :dom7 :maj7 :dim7 :diminished :augmented]]
      (let [t (analyze/tension-score {:degree d :quality q})]
        (is (<= 0.0 t 1.0) (str "out of range for degree=" d " quality=" q))))))

;; ---------------------------------------------------------------------------
;; progression-tension
;; ---------------------------------------------------------------------------

(deftest progression-tension-i-v-iv-i-test
  (testing "I V IV I: tension rises then falls"
    (let [key (c-major-scale)
          pts (analyze/progression-tension key
                [[60 64 67]   ; I
                 [67 71 74]   ; V
                 [65 69 72]   ; IV
                 [60 64 67]]) ; I
          ts  (mapv :tension pts)]
      (is (= 4 (count pts)) "one result per chord")
      (is (every? #(contains? % :tension) pts) ":tension key present on all")
      (is (< (first ts) (second ts)) "V is more tense than I")
      (is (= (first ts) (last ts))   "same I resolves back to same tension"))))

(deftest progression-tension-v7-test
  (testing "V7 has higher tension than V"
    (let [key    (c-major-scale)
          v-t    (:tension (first (analyze/progression-tension key [[67 71 74]])))
          v7-t   (:tension (first (analyze/progression-tension key [[67 71 74 77]])))]
      (is (> v7-t v-t) "dom7 quality raises V tension"))))

;; ---------------------------------------------------------------------------
;; borrowed-chord?
;; ---------------------------------------------------------------------------

(deftest borrowed-chord-biii-in-major-test
  (testing "bIII (Eb major) is borrowed in C major from C minor"
    (let [key      (c-major-scale)
          analyzed (analyze/chord->roman key [63 67 70])]  ; Eb G Bb
      (is (false? (:in-key? analyzed)) "Eb is not diatonic to C major")
      (is (true?  (analyze/borrowed-chord? key analyzed)) "recognised as borrowed"))))

(deftest borrowed-chord-bvii-in-major-test
  (testing "bVII (Bb major) is borrowed in C major from C minor"
    (let [key      (c-major-scale)
          analyzed (analyze/chord->roman key [70 74 77])]  ; Bb D F
      (is (true? (analyze/borrowed-chord? key analyzed))))))

(deftest borrowed-chord-diatonic-not-borrowed-test
  (testing "diatonic chords are not flagged as borrowed"
    (let [key (c-major-scale)]
      (doseq [triad [[60 64 67]   ; I
                     [62 65 69]   ; II
                     [65 69 72]   ; IV
                     [67 71 74]]] ; V
        (let [a (analyze/chord->roman key triad)]
          (is (false? (analyze/borrowed-chord? key a))
              (str "diatonic chord incorrectly flagged: " (:roman a))))))))

(deftest borrowed-chord-non-major-minor-returns-false-test
  (testing "borrowed-chord? returns false for non-major/minor scales"
    (let [dorian-key (scale-ns/scale :D 4 :dorian)
          analyzed   (analyze/chord->roman dorian-key [60 64 67])]
      ;; No parallel key defined for dorian — should return false, not throw
      (is (false? (analyze/borrowed-chord? dorian-key analyzed))))))

;; ---------------------------------------------------------------------------
;; annotate-progression
;; ---------------------------------------------------------------------------

(deftest annotate-progression-keys-test
  (testing "every result has :tension and :borrowed? keys"
    (let [key    (c-major-scale)
          result (analyze/annotate-progression key
                   [[60 64 67] [65 69 72] [67 71 74] [60 64 67]])]
      (doseq [m result]
        (is (contains? m :tension)   ":tension present")
        (is (contains? m :borrowed?) ":borrowed? present")))))

(deftest annotate-progression-borrowed-flag-test
  (testing "bIII gets :borrowed? true, diatonic chords get false"
    (let [key    (c-major-scale)
          result (analyze/annotate-progression key
                   [[60 64 67]   ; I  — diatonic
                    [63 67 70]   ; bIII — borrowed
                    [65 69 72]]) ; IV — diatonic
          flags  (mapv :borrowed? result)]
      (is (= [false true false] flags)))))

;; ---------------------------------------------------------------------------
;; suggest-progression
;; ---------------------------------------------------------------------------

(deftest suggest-progression-returns-count-test
  (testing "returns one chord per tension target"
    (let [key     (c-major-scale)
          targets [0.0 0.4 0.8 0.0]
          result  (analyze/suggest-progression key targets)]
      (is (= 4 (count result))))))

(deftest suggest-progression-i-v-i-shape-test
  (testing "tonic→dominant→tonic target shape returns appropriate degrees"
    (let [key    (c-major-scale)
          result (analyze/suggest-progression key [0.0 0.8 0.0])
          degrees (mapv :degree result)]
      ;; degree 0 = I (tension 0.0), degree 4 = V (tension 0.8)
      (is (= 0 (first degrees))  "first chord is tonic")
      (is (= 4 (second degrees)) "middle chord is dominant")
      (is (= 0 (last degrees))   "last chord is tonic"))))

(deftest suggest-progression-all-annotated-test
  (testing "results have :tension and :borrowed? keys"
    (let [key    (c-major-scale)
          result (analyze/suggest-progression key [0.0 0.5 0.8 0.0])]
      (doseq [m result]
        (is (contains? m :tension))
        (is (contains? m :borrowed?))))))

(deftest suggest-progression-include-borrowed-test
  (testing ":include-borrowed? true allows borrowed chords to be selected"
    ;; Request a very high tension target (0.95) with borrowed chords enabled.
    ;; The borrowed VII dim or bVII from C minor should be a candidate.
    (let [key    (c-major-scale)
          result (analyze/suggest-progression key [0.95]
                                              :include-borrowed? true)
          [c]    result]
      ;; Should select something with high tension (>= 0.8)
      (is (>= (:tension c) 0.8) "high-tension target selects a tense chord"))))
