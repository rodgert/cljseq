; SPDX-License-Identifier: EPL-2.0
(ns cljseq.chord-test
  "Tests for cljseq.chord — Chord record, quality library, and progressions."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.pitch  :as pitch]
            [cljseq.scale  :as scale]
            [cljseq.chord  :as chord]))

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(deftest chord-constructor-test
  (testing "chord builds from step/octave/quality"
    (let [c (chord/chord :C 4 :major)]
      (is (instance? cljseq.chord.Chord c))
      (is (= :major (:quality c)))
      (is (= 0      (:inversion c))))))

(deftest chord-with-inversion-test
  (testing "inversion stored correctly"
    (is (= 1 (:inversion (chord/chord :C 4 :major 1))))
    (is (= 2 (:inversion (chord/chord :C 4 :major 2))))))

;; ---------------------------------------------------------------------------
;; chord->midis — triad qualities
;; ---------------------------------------------------------------------------

(deftest c-major-midis-test
  (testing "C major triad = C4 E4 G4"
    (is (= [60 64 67] (chord/chord->midis (chord/chord :C 4 :major))))))

(deftest c-minor-midis-test
  (testing "C minor triad = C4 Eb4 G4"
    (is (= [60 63 67] (chord/chord->midis (chord/chord :C 4 :minor))))))

(deftest c-augmented-midis-test
  (testing "C augmented triad = C4 E4 G#4"
    (is (= [60 64 68] (chord/chord->midis (chord/chord :C 4 :augmented))))))

(deftest c-diminished-midis-test
  (testing "C diminished triad = C4 Eb4 Gb4"
    (is (= [60 63 66] (chord/chord->midis (chord/chord :C 4 :diminished))))))

(deftest c-sus2-test
  (testing "C sus2 = C4 D4 G4"
    (is (= [60 62 67] (chord/chord->midis (chord/chord :C 4 :sus2))))))

(deftest c-sus4-test
  (testing "C sus4 = C4 F4 G4"
    (is (= [60 65 67] (chord/chord->midis (chord/chord :C 4 :sus4))))))

;; ---------------------------------------------------------------------------
;; 7th chords
;; ---------------------------------------------------------------------------

(deftest cmaj7-test
  (testing "Cmaj7 = C E G B"
    (is (= [60 64 67 71] (chord/chord->midis (chord/chord :C 4 :maj7))))))

(deftest cmin7-test
  (testing "Cm7 = C Eb G Bb"
    (is (= [60 63 67 70] (chord/chord->midis (chord/chord :C 4 :min7))))))

(deftest cdom7-test
  (testing "C7 = C E G Bb"
    (is (= [60 64 67 70] (chord/chord->midis (chord/chord :C 4 :dom7))))))

(deftest cdim7-test
  (testing "Cdim7 = C Eb Gb Bbb"
    (is (= [60 63 66 69] (chord/chord->midis (chord/chord :C 4 :dim7))))))

(deftest chalf-dim7-test
  (testing "Cø7 = C Eb Gb Bb"
    (is (= [60 63 66 70] (chord/chord->midis (chord/chord :C 4 :half-dim7))))))

;; ---------------------------------------------------------------------------
;; Inversions
;; ---------------------------------------------------------------------------

(deftest first-inversion-test
  (testing "C major first inversion: E4 G4 C5"
    (let [c (chord/chord :C 4 :major 1)
          m (chord/chord->midis c)]
      (is (= [64 67 72] m)))))

(deftest second-inversion-test
  (testing "C major second inversion: G4 C5 E5"
    (let [c (chord/chord :C 4 :major 2)
          m (chord/chord->midis c)]
      (is (= [67 72 76] m)))))

;; ---------------------------------------------------------------------------
;; chord-name
;; ---------------------------------------------------------------------------

(deftest chord-name-test
  (testing "chord-name returns correct strings"
    (is (= "Cmaj"  (chord/chord-name (chord/chord :C 4 :major))))
    (is (= "Cm"    (chord/chord-name (chord/chord :C 4 :minor))))
    (is (= "G7"    (chord/chord-name (chord/chord :G 4 :dom7))))
    (is (= "Fmaj7" (chord/chord-name (chord/chord :F 4 :maj7))))))

;; ---------------------------------------------------------------------------
;; chord-at-degree
;; ---------------------------------------------------------------------------

(deftest chord-at-degree-test
  (testing "chord-at-degree builds on correct scale pitch"
    (let [s  (scale/scale :C 4 :major)
          ;; degree 4 = G4, major → G major triad
          c  (chord/chord-at-degree s 4 :major)]
      (is (= [67 71 74] (chord/chord->midis c))))))

;; ---------------------------------------------------------------------------
;; progression
;; ---------------------------------------------------------------------------

(deftest progression-i-iv-v-test
  (testing "I-IV-V in C major"
    (let [s   (scale/scale :C 4 :major)
          prg (chord/progression s [:I :IV :V])]
      (is (= 3 (count prg)))
      ;; I = C major
      (is (= [60 64 67] (chord/chord->midis (first prg))))
      ;; IV = F major
      (is (= [65 69 72] (chord/chord->midis (second prg))))
      ;; V = G major
      (is (= [67 71 74] (chord/chord->midis (nth prg 2)))))))

(deftest progression-7th-chords-test
  (testing "V7 in C major"
    (let [s   (scale/scale :C 4 :major)
          prg (chord/progression s [:V7])]
      ;; G dom7 = G B D F
      (is (= [67 71 74 77] (chord/chord->midis (first prg)))))))

;; ---------------------------------------------------------------------------
;; Chord->pitches
;; ---------------------------------------------------------------------------

(deftest chord->pitches-test
  (testing "chord->pitches returns Pitch records"
    (let [c  (chord/chord :C 4 :major)
          ps (chord/chord->pitches c)]
      (is (= 3 (count ps)))
      (is (every? #(instance? cljseq.pitch.Pitch %) ps)))))

;; ---------------------------------------------------------------------------
;; Unknown quality throws
;; ---------------------------------------------------------------------------

(deftest unknown-quality-throws-test
  (testing "unknown quality throws on chord->midis"
    (let [bad (assoc (chord/chord :C 4 :major) :quality :xyzzy)]
      (is (thrown? Exception (chord/chord->midis bad))))))
