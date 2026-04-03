; SPDX-License-Identifier: EPL-2.0
(ns cljseq.pitch-test
  "Tests for cljseq.pitch — Pitch record, constructors, and derived views."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.pitch :as p]))

;; ---------------------------------------------------------------------------
;; pitch->midi
;; ---------------------------------------------------------------------------

(deftest pitch->midi-natural-test
  (testing "natural pitches map to correct MIDI numbers"
    (is (= 60 (p/pitch->midi (p/pitch :C 4))))
    (is (= 62 (p/pitch->midi (p/pitch :D 4))))
    (is (= 64 (p/pitch->midi (p/pitch :E 4))))
    (is (= 65 (p/pitch->midi (p/pitch :F 4))))
    (is (= 67 (p/pitch->midi (p/pitch :G 4))))
    (is (= 69 (p/pitch->midi (p/pitch :A 4))))
    (is (= 71 (p/pitch->midi (p/pitch :B 4))))))

(deftest pitch->midi-accidentals-test
  (testing "accidentals shift MIDI number"
    (is (= 61 (p/pitch->midi (p/pitch :C 4 :sharp))))
    (is (= 59 (p/pitch->midi (p/pitch :C 4 :flat))))
    (is (= 62 (p/pitch->midi (p/pitch :C 4 :double-sharp))))
    (is (= 58 (p/pitch->midi (p/pitch :C 4 :double-flat))))
    (is (= 60 (p/pitch->midi (p/pitch :C 4 :natural))))))

(deftest pitch->midi-octave-boundary-test
  (testing "octave boundaries"
    (is (= 0  (p/pitch->midi (p/pitch :C -1))))
    (is (= 12 (p/pitch->midi (p/pitch :C 0))))
    (is (= 127 (p/pitch->midi (p/pitch :G 9))))))

(deftest pitch->midi-clamped-test
  (testing "values clamped to 0-127"
    (is (= 0 (p/pitch->midi (p/pitch :C -1 :double-flat))))
    (is (<= 0 (p/pitch->midi (p/pitch :G 9)) 127))))

;; ---------------------------------------------------------------------------
;; pitch->ps
;; ---------------------------------------------------------------------------

(deftest pitch->ps-no-microtone-test
  (testing "pitch->ps equals pitch->midi when microtone is 0"
    (is (== 60.0 (p/pitch->ps (p/pitch :C 4))))
    (is (== 69.0 (p/pitch->ps (p/pitch :A 4))))))

(deftest pitch->ps-microtone-test
  (testing "pitch->ps includes microtone offset"
    (is (== 60.25 (p/pitch->ps (p/pitch :C 4 nil 25))))
    (is (== 59.5  (p/pitch->ps (p/pitch :C 4 nil -50))))))

;; ---------------------------------------------------------------------------
;; pitch-class
;; ---------------------------------------------------------------------------

(deftest pitch-class-test
  (testing "pitch class is MIDI mod 12"
    (is (= 0  (p/pitch-class (p/pitch :C 4))))
    (is (= 1  (p/pitch-class (p/pitch :C 4 :sharp))))
    (is (= 11 (p/pitch-class (p/pitch :B 3))))
    (is (= 0  (p/pitch-class (p/pitch :C 5))))))

;; ---------------------------------------------------------------------------
;; pitch->freq
;; ---------------------------------------------------------------------------

(deftest pitch->freq-a4-test
  (testing "A4 = 440 Hz"
    (is (< (Math/abs (- 440.0 (p/pitch->freq (p/pitch :A 4)))) 0.001))))

(deftest pitch->freq-c4-test
  (testing "C4 ≈ 261.63 Hz"
    (is (< (Math/abs (- 261.626 (p/pitch->freq (p/pitch :C 4)))) 0.01))))

(deftest pitch->freq-octave-doubling-test
  (testing "each octave up doubles frequency"
    (let [c4 (p/pitch->freq (p/pitch :C 4))
          c5 (p/pitch->freq (p/pitch :C 5))]
      (is (< (Math/abs (- (* 2 c4) c5)) 0.001)))))

;; ---------------------------------------------------------------------------
;; keyword->pitch
;; ---------------------------------------------------------------------------

(deftest keyword->pitch-natural-test
  (testing "natural note keyword"
    (let [p (p/keyword->pitch :C4)]
      (is (= :C (:step p)))
      (is (= 4  (:octave p)))
      (is (nil? (:accidental p))))))

(deftest keyword->pitch-sharp-test
  (testing "single sharp"
    (let [p (p/keyword->pitch :C#4)]
      (is (= :sharp (:accidental p))))))

(deftest keyword->pitch-flat-test
  (testing "single flat"
    (let [p (p/keyword->pitch :Bb3)]
      (is (= :B (:step p)))
      (is (= 3  (:octave p)))
      (is (= :flat (:accidental p))))))

(deftest keyword->pitch-double-sharp-test
  (testing "double sharp"
    (is (= :double-sharp (:accidental (p/keyword->pitch :D##5))))))

(deftest keyword->pitch-double-flat-test
  (testing "double flat"
    (is (= :double-flat (:accidental (p/keyword->pitch :Ebb4))))))

(deftest keyword->pitch-negative-octave-test
  (testing "negative octave"
    (let [p (p/keyword->pitch :B-1)]
      (is (= :B (:step p)))
      (is (= -1 (:octave p))))))

(deftest keyword->pitch-nil-on-invalid-test
  (testing "returns nil for invalid keyword"
    (is (nil? (p/keyword->pitch :XY4)))
    (is (nil? (p/keyword->pitch :not-a-pitch)))))

;; ---------------------------------------------------------------------------
;; midi->pitch
;; ---------------------------------------------------------------------------

(deftest midi->pitch-c4-test
  (testing "MIDI 60 = C4"
    (let [p (p/midi->pitch 60)]
      (is (= :C (:step p)))
      (is (= 4  (:octave p)))
      (is (nil? (:accidental p))))))

(deftest midi->pitch-sharp-preferred-test
  (testing "MIDI 61 → C#4 (sharp default)"
    (let [p (p/midi->pitch 61)]
      (is (= :C (:step p)))
      (is (= :sharp (:accidental p))))))

(deftest midi->pitch-flat-preferred-test
  (testing "MIDI 61 → Db4 (flat spelling)"
    (let [p (p/midi->pitch 61 :flat)]
      (is (= :D (:step p)))
      (is (= :flat (:accidental p))))))

(deftest midi->pitch-roundtrip-test
  (testing "midi->pitch->midi identity for all MIDI values"
    (doseq [m (range 0 128)]
      (is (= m (p/pitch->midi (p/midi->pitch m)))
          (str "failed for MIDI " m)))))

;; ---------------------------------------------------------------------------
;; enharmonic? / above? / below?
;; ---------------------------------------------------------------------------

(deftest enharmonic-test
  (testing "C#4 and Db4 are enharmonic"
    (is (p/enharmonic? (p/pitch :C 4 :sharp) (p/pitch :D 4 :flat))))
  (testing "C4 and C4 are enharmonic"
    (is (p/enharmonic? (p/pitch :C 4) (p/pitch :C 4))))
  (testing "C4 and D4 are not enharmonic"
    (is (not (p/enharmonic? (p/pitch :C 4) (p/pitch :D 4))))))

(deftest above-below-test
  (testing "above? and below?"
    (is (p/above? (p/pitch :G 4) (p/pitch :C 4)))
    (is (p/below? (p/pitch :C 4) (p/pitch :G 4)))
    (is (not (p/above? (p/pitch :C 4) (p/pitch :C 4))))))

;; ---------------------------------------------------------------------------
;; transpose
;; ---------------------------------------------------------------------------

(deftest transpose-up-test
  (testing "transpose up 7 semitones: C4 → G4"
    (let [result (p/transpose (p/pitch :C 4) 7)]
      (is (= 67 (p/pitch->midi result))))))

(deftest transpose-down-test
  (testing "transpose down 5 semitones: C4 → G3"
    (let [result (p/transpose (p/pitch :C 4) -5)]
      (is (= 55 (p/pitch->midi result))))))

(deftest transpose-out-of-range-test
  (testing "transpose beyond range returns nil"
    (is (nil? (p/transpose (p/pitch :C -1) -5)))
    (is (nil? (p/transpose (p/pitch :G 9) 5)))))

;; ---------------------------------------------------------------------------
;; octave-transpose
;; ---------------------------------------------------------------------------

(deftest octave-transpose-test
  (testing "octave-transpose shifts by 12 semitones"
    (is (= 72 (p/pitch->midi (p/octave-transpose (p/pitch :C 4) 1))))
    (is (= 48 (p/pitch->midi (p/octave-transpose (p/pitch :C 4) -1))))))

;; ---------------------------------------------------------------------------
;; ->pitch and ->midi coercions
;; ---------------------------------------------------------------------------

(deftest ->pitch-from-pitch-test
  (testing "->pitch passes Pitch through"
    (let [p (p/pitch :C 4)]
      (is (= p (p/->pitch p))))))

(deftest ->pitch-from-keyword-test
  (testing "->pitch coerces keyword"
    (is (= 60 (p/pitch->midi (p/->pitch :C4))))))

(deftest ->pitch-from-int-test
  (testing "->pitch coerces integer"
    (is (= 60 (p/pitch->midi (p/->pitch 60))))))

(deftest ->midi-coercions-test
  (testing "->midi accepts Pitch, keyword, and integer"
    (is (= 60 (p/->midi (p/pitch :C 4))))
    (is (= 60 (p/->midi :C4)))
    (is (= 60 (p/->midi 60)))))

;; ---------------------------------------------------------------------------
;; pitch->str
;; ---------------------------------------------------------------------------

(deftest pitch->str-test
  (testing "pitch->str produces correct strings"
    (is (= "C4"  (p/pitch->str (p/pitch :C 4))))
    (is (= "C#4" (p/pitch->str (p/pitch :C 4 :sharp))))
    (is (= "Bb3" (p/pitch->str (p/pitch :B 3 :flat))))
    (is (= "D##5" (p/pitch->str (p/pitch :D 5 :double-sharp))))
    (is (= "Ebb4" (p/pitch->str (p/pitch :E 4 :double-flat))))))
