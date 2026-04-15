; SPDX-License-Identifier: EPL-2.0
(ns cljseq.journey-test
  (:require [clojure.test :refer [deftest is are testing]]
            [cljseq.journey :as journey]))

;; ---------------------------------------------------------------------------
;; phase-pair — pure function, easy to property-test
;; ---------------------------------------------------------------------------

(deftest phase-pair-basic
  (testing "12/17 — classic Rubycon pair"
    (let [pp (journey/phase-pair 12 17)]
      (is (= 204 (:lcm pp)) "LCM of 12 and 17 is 204")
      (is (= [204 408 612 816 1020] (:alignment-beats pp)) "first 5 alignments")
      (is (pos? (:drift-rate pp)) "drift-rate is positive")
      (is (= 100 (:bpm (:at-bpm pp))) "default bpm is 100")))

  (testing "16/23 — spacious pair"
    (let [pp (journey/phase-pair 16 23)]
      (is (= 368 (:lcm pp)) "LCM of 16 and 23 is 368")))

  (testing "same lengths — no drift"
    (let [pp (journey/phase-pair 8 8)]
      (is (= 8 (:lcm pp)) "LCM of 8 and 8 is 8")
      (is (zero? (:drift-rate pp)) "identical lengths produce zero drift")))

  (testing "custom bpm option"
    (let [pp (journey/phase-pair 12 17 {:bpm 80})]
      (is (= 80 (:bpm (:at-bpm pp))))))

  (testing "custom n-alignments"
    (let [pp (journey/phase-pair 12 17 {:n-alignments 3})]
      (is (= 3 (count (:alignment-beats pp))))))

  (testing "at-bpm minutes matches lcm / bpm"
    (let [pp (journey/phase-pair 12 17 {:bpm 100})]
      (is (< (Math/abs (- (:minutes (:at-bpm pp)) 2.04)) 0.001)
          "12/17 at 100 BPM ≈ 2.04 minutes"))))

;; ---------------------------------------------------------------------------
;; humanise — pure function
;; ---------------------------------------------------------------------------

(deftest humanise-basic
  (testing "velocity stays in [1, 127]"
    (dotimes [_ 100]
      (let [step  {:pitch/midi 60 :dur/beats 1/4 :velocity 64}
            hum   (journey/humanise step)]
        (is (<= 1 (:velocity hum) 127) "velocity clamped to valid MIDI range"))))

  (testing "velocity clamps at extremes"
    (dotimes [_ 50]
      (let [hum (journey/humanise {:pitch/midi 60 :dur/beats 1/4 :velocity 127}
                                  {:velocity-variance 20})]
        (is (<= (:velocity hum) 127) "never exceeds 127")))
    (dotimes [_ 50]
      (let [hum (journey/humanise {:pitch/midi 60 :dur/beats 1/4 :velocity 1}
                                  {:velocity-variance 20})]
        (is (>= (:velocity hum) 1) "never below 1"))))

  (testing "timing offset is attached when variance > 0"
    ;; With large variance, offset will almost always be non-zero.
    ;; We test 20 samples and expect at least one offset present.
    (let [offsets (for [_ (range 20)]
                    (:timing/offset-ms
                     (journey/humanise {:pitch/midi 60 :dur/beats 1/4 :velocity 64}
                                       {:timing-variance-ms 50})))]
      (is (some some? offsets) "at least one timing offset should be present")))

  (testing ":late bias produces non-negative offsets"
    (dotimes [_ 50]
      (let [hum (journey/humanise {:pitch/midi 60 :dur/beats 1/4 :velocity 64}
                                  {:timing-variance-ms 20 :timing-bias :late})]
        (when-let [off (:timing/offset-ms hum)]
          (is (>= off 0) "late bias gives non-negative offset")))))

  (testing ":early bias produces non-positive offsets"
    (dotimes [_ 50]
      (let [hum (journey/humanise {:pitch/midi 60 :dur/beats 1/4 :velocity 64}
                                  {:timing-variance-ms 20 :timing-bias :early})]
        (when-let [off (:timing/offset-ms hum)]
          (is (<= off 0) "early bias gives non-positive offset")))))

  (testing "step keys are preserved"
    (let [step {:pitch/midi 62 :dur/beats 1/2 :velocity 80 :channel 3}
          hum  (journey/humanise step)]
      (is (= 62 (:pitch/midi hum)) "pitch preserved")
      (is (= 1/2 (:dur/beats hum)) "duration preserved")
      (is (= 3 (:channel hum)) "extra keys preserved")))

  (testing "default velocity when absent"
    (let [hum (journey/humanise {:pitch/midi 60 :dur/beats 1/4})]
      (is (<= 1 (:velocity hum) 127) "default velocity 64 is applied before variance"))))

;; ---------------------------------------------------------------------------
;; phaedra-arc — structural test (no side effects)
;; ---------------------------------------------------------------------------

(deftest phaedra-arc-structure
  (let [arc (journey/phaedra-arc)]
    (testing "returns expected top-level keys"
      (is (contains? arc :timeline))
      (is (contains? arc :movements))
      (is (contains? arc :phase-pairs)))

    (testing "timeline is a vector of [bar keyword] pairs"
      (let [tl (:timeline arc)]
        (is (vector? tl) "timeline is a vector")
        (is (seq tl) "timeline is non-empty")
        (doseq [[bar kw] tl]
          (is (integer? bar) "bar is an integer")
          (is (keyword? kw) "transition placeholder is a keyword"))))

    (testing "timeline is sorted by bar number"
      (let [bars (map first (:timeline arc))]
        (is (= bars (sort bars)) "bars are in ascending order")))

    (testing "movements map contains expected names"
      (let [mvts (:movements arc)]
        (is (contains? mvts :emergence))
        (is (contains? mvts :development))
        (is (contains? mvts :dissolution))))

    (testing "each movement is a [start end] pair"
      (doseq [[_ [s e]] (:movements arc)]
        (is (integer? s) "start is integer")
        (is (integer? e) "end is integer")
        (is (< s e) "start < end")))

    (testing "phase-pairs list is non-empty with :a, :b keys"
      (let [pairs (:phase-pairs arc)]
        (is (seq pairs))
        (doseq [p pairs]
          (is (contains? p :a))
          (is (contains? p :b)))))))

;; ---------------------------------------------------------------------------
;; bar counter — tests using private state access
;; ---------------------------------------------------------------------------

(deftest bar-counter-state
  (testing "current-bar returns integer"
    (is (integer? (journey/current-bar))))

  (testing "reset-bar-counter! sets bar to 0"
    (journey/reset-bar-counter!)
    (is (zero? (journey/current-bar)))))
