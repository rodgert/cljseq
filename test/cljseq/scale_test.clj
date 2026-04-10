; SPDX-License-Identifier: EPL-2.0
(ns cljseq.scale-test
  "Tests for cljseq.scale — Scale record, named scales, and IScale protocol."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.pitch  :as pitch]
            [cljseq.scale  :as scale]))

;; ---------------------------------------------------------------------------
;; Constructor / named scale library
;; ---------------------------------------------------------------------------

(deftest scale-constructor-test
  (testing "scale constructor builds from name"
    (let [s (scale/scale :C 4 :major)]
      (is (instance? cljseq.scale.Scale s))
      (is (= :C (:step (:root s))))
      (is (= 4  (:octave (:root s)))))))

(deftest named-scale-test
  (testing "named-scale returns Scale record with correct intervals"
    (let [s (scale/named-scale :major)]
      (is (= [2 2 1 2 2 2 1] (:intervals s))))))

(deftest scale-names-test
  (testing "scale-names returns a sorted non-empty list"
    (let [names (scale/scale-names)]
      (is (seq names))
      (is (contains? (set names) :major))
      (is (contains? (set names) :dorian))
      (is (contains? (set names) :pentatonic-minor)))))

(deftest scale-unknown-name-throws-test
  (testing "unknown scale name throws"
    (is (thrown? Exception (scale/scale :C 4 :nonexistent-mode)))))

;; ---------------------------------------------------------------------------
;; scale-pitches
;; ---------------------------------------------------------------------------

(deftest c-major-pitches-test
  (testing "C major scale pitches"
    (let [s      (scale/scale :C 4 :major)
          pits   (scale/scale-pitches s)
          midis  (mapv pitch/pitch->midi pits)]
      (is (= 7 (count pits)))
      (is (= [60 62 64 65 67 69 71] midis)))))

(deftest a-minor-pitches-test
  (testing "A natural minor scale pitches"
    (let [s     (scale/scale :A 4 :natural-minor)
          pits  (scale/scale-pitches s)
          midis (mapv pitch/pitch->midi pits)]
      (is (= [69 71 72 74 76 77 79] midis)))))

(deftest pentatonic-count-test
  (testing "pentatonic scale has 5 pitches"
    (is (= 5 (count (scale/scale-pitches (scale/scale :C 4 :pentatonic-major)))))))

(deftest chromatic-count-test
  (testing "chromatic scale has 12 pitches"
    (is (= 12 (count (scale/scale-pitches (scale/scale :C 4 :chromatic)))))))

;; ---------------------------------------------------------------------------
;; pitch-at
;; ---------------------------------------------------------------------------

(deftest pitch-at-within-octave-test
  (testing "pitch-at for C major degrees 0-6"
    (let [s (scale/scale :C 4 :major)]
      (is (= 60 (pitch/pitch->midi (scale/pitch-at s 0))))
      (is (= 62 (pitch/pitch->midi (scale/pitch-at s 1))))
      (is (= 64 (pitch/pitch->midi (scale/pitch-at s 2))))
      (is (= 67 (pitch/pitch->midi (scale/pitch-at s 4)))))))

(deftest pitch-at-compound-degree-test
  (testing "pitch-at wraps into next octave for degree >= 7"
    (let [s (scale/scale :C 4 :major)]
      ;; degree 7 = C5 (octave above root)
      (is (= 72 (pitch/pitch->midi (scale/pitch-at s 7))))
      ;; degree 8 = D5
      (is (= 74 (pitch/pitch->midi (scale/pitch-at s 8)))))))

;; ---------------------------------------------------------------------------
;; degree-of
;; ---------------------------------------------------------------------------

(deftest degree-of-in-scale-test
  (testing "degree-of returns correct index for scale members"
    (let [s (scale/scale :C 4 :major)]
      (is (= 0 (scale/degree-of s (pitch/pitch :C 4))))
      (is (= 1 (scale/degree-of s (pitch/pitch :D 4))))
      (is (= 6 (scale/degree-of s (pitch/pitch :B 4)))))))

(deftest degree-of-out-of-scale-test
  (testing "degree-of returns nil for pitches not in scale"
    (let [s (scale/scale :C 4 :major)]
      (is (nil? (scale/degree-of s (pitch/pitch :C 4 :sharp)))))))

;; ---------------------------------------------------------------------------
;; next-pitch
;; ---------------------------------------------------------------------------

(deftest next-pitch-up-test
  (testing "next-pitch steps up through C major"
    (let [s  (scale/scale :C 4 :major)
          c4 (pitch/pitch :C 4)]
      (is (= 62 (pitch/pitch->midi (scale/next-pitch s c4 1))))  ; D4
      (is (= 64 (pitch/pitch->midi (scale/next-pitch s c4 2))))  ; E4
      (is (= 72 (pitch/pitch->midi (scale/next-pitch s c4 7))))  ; C5
    )))

(deftest next-pitch-down-test
  (testing "next-pitch steps down through C major"
    (let [s  (scale/scale :C 4 :major)
          c4 (pitch/pitch :C 4)]
      (is (= 59 (pitch/pitch->midi (scale/next-pitch s c4 -1))))  ; B3
      (is (= 57 (pitch/pitch->midi (scale/next-pitch s c4 -2)))))))  ; A3

;; ---------------------------------------------------------------------------
;; transpose-scale
;; ---------------------------------------------------------------------------

(deftest transpose-scale-test
  (testing "transpose-scale shifts root by semitones"
    (let [s  (scale/scale :C 4 :major)
          g  (scale/transpose-scale s 7)]
      (is (= 67 (pitch/pitch->midi (:root g))))
      (is (= (:intervals s) (:intervals g))))))

;; ---------------------------------------------------------------------------
;; in-scale?
;; ---------------------------------------------------------------------------

(deftest in-scale-test
  (testing "in-scale? for C major"
    (let [s (scale/scale :C 4 :major)]
      (is (scale/in-scale? s (pitch/pitch :C 4)))
      (is (scale/in-scale? s (pitch/pitch :G 4)))
      (is (not (scale/in-scale? s (pitch/pitch :C 4 :sharp))))))
  (testing "in-scale? works across octaves"
    (let [s (scale/scale :C 4 :major)]
      (is (scale/in-scale? s (pitch/pitch :C 5)))
      (is (scale/in-scale? s (pitch/pitch :D 3))))))

;; ---------------------------------------------------------------------------
;; Backward compatibility with weighted-scale maps
;; ---------------------------------------------------------------------------

(deftest backward-compat-test
  (testing "Scale record responds to :intervals like a map"
    (let [s (scale/named-scale :major)]
      (is (= [2 2 1 2 2 2 1] (:intervals s))))))

;; ---------------------------------------------------------------------------
;; Solar42 keyboard mode scales
;; ---------------------------------------------------------------------------

(deftest solar42-scales-registered-test
  (testing "Solar42 keyboard scales are registered"
    (let [names (set (scale/scale-names))]
      (is (contains? names :pelog)    "Gamelan pelog")
      (is (contains? names :slendro)  "Gamelan slendro")
      (is (contains? names :hijaz)    "Arabian hijaz maqam")
      (is (contains? names :folk)     "Folk/klezmer scale")
      (is (contains? names :in-sen)   "Japanese in-sen"))))

(deftest pelog-scale-test
  (testing ":pelog has 6 steps (7-note scale)"
    (let [s (scale/named-scale :pelog)]
      (is (= 6 (count (:intervals s))))
      (is (= [1 2 3 1 4 1] (:intervals s))))))

(deftest slendro-scale-test
  (testing ":slendro has 5 steps"
    (let [s (scale/named-scale :slendro)]
      (is (= 5 (count (:intervals s)))))))

(deftest hijaz-same-as-phrygian-dominant-test
  (testing ":hijaz and :phrygian-dominant share intervals"
    (is (= (:intervals (scale/named-scale :hijaz))
           (:intervals (scale/named-scale :phrygian-dominant))))))

(deftest folk-same-as-romanian-minor-test
  (testing ":folk and :romanian-minor share intervals"
    (is (= (:intervals (scale/named-scale :folk))
           (:intervals (scale/named-scale :romanian-minor))))))

(deftest persian-scale-test
  (testing ":persian has correct interval pattern (1 b2 3 4 b5 b6 7)"
    (let [s (scale/named-scale :persian)]
      (is (= [1 3 1 1 2 3 1] (:intervals s))))))

(deftest dune-scale-test
  (testing ":dune matches :double-harmonic intervals"
    (is (= (:intervals (scale/named-scale :dune))
           (:intervals (scale/named-scale :double-harmonic))))))
