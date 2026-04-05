; SPDX-License-Identifier: EPL-2.0
(ns cljseq.midi-repair-test
  (:require [clojure.test       :refer [deftest testing is are]]
            [cljseq.midi-repair :as repair]
            [cljseq.analyze     :as analyze]
            [cljseq.scale       :as scale]
            [cljseq.pitch       :as pitch]))

;; ---------------------------------------------------------------------------
;; Fixtures — synthetic NDLR note streams for testing without a real MIDI file
;; ---------------------------------------------------------------------------

(def ^:private d-dorian-pcs #{2 4 5 7 9 10 0}) ; D(2) E(4) F(5) G(7) A(9) Bb(10) C(0)

(defn- d-dorian-midi? [midi]
  (contains? d-dorian-pcs (mod midi 12)))

(def ^:private drone-notes
  [{:pitch/midi 38 :start/beats 0.0 :dur/beats 16.0 :velocity 55 :channel 1}
   {:pitch/midi 38 :start/beats 16.0 :dur/beats 16.0 :velocity 55 :channel 1}])

(def ^:private pad-notes
  ;; D Dorian harmony: i=Dm, IV=G major (G-B-D, note B♮ = Dorian ♮6), v=Am, i=Dm
  ;; In Dorian the iv chord is MAJOR because the 6th degree (B) is raised.
  ;; Using G major (not G minor) here is the key Dorian signature.
  [{:pitch/midi 50 :start/beats 0.0  :dur/beats 2.0 :velocity 60 :channel 1}   ; D3 (i)
   {:pitch/midi 53 :start/beats 0.01 :dur/beats 2.0 :velocity 60 :channel 1}   ; F3
   {:pitch/midi 57 :start/beats 0.02 :dur/beats 2.0 :velocity 60 :channel 1}   ; A3
   {:pitch/midi 55 :start/beats 4.0  :dur/beats 2.0 :velocity 58 :channel 1}   ; G3 (IV — G major)
   {:pitch/midi 59 :start/beats 4.01 :dur/beats 2.0 :velocity 58 :channel 1}   ; B3 ← ♮6, Dorian signature!
   {:pitch/midi 62 :start/beats 4.02 :dur/beats 2.0 :velocity 58 :channel 1}   ; D4
   {:pitch/midi 57 :start/beats 8.0  :dur/beats 2.0 :velocity 65 :channel 1}   ; A3 (v — Am)
   {:pitch/midi 60 :start/beats 8.01 :dur/beats 2.0 :velocity 65 :channel 1}   ; C4
   {:pitch/midi 64 :start/beats 8.02 :dur/beats 2.0 :velocity 65 :channel 1}   ; E4
   {:pitch/midi 50 :start/beats 12.0 :dur/beats 2.0 :velocity 55 :channel 1}   ; D3 (i)
   {:pitch/midi 53 :start/beats 12.01 :dur/beats 2.0 :velocity 55 :channel 1}  ; F3
   {:pitch/midi 57 :start/beats 12.02 :dur/beats 2.0 :velocity 55 :channel 1}]); A3

(def ^:private motif-1-clean
  [{:pitch/midi 62 :start/beats 0.5 :dur/beats 0.25 :velocity 72 :channel 1}  ; D4
   {:pitch/midi 64 :start/beats 1.0 :dur/beats 0.25 :velocity 70 :channel 1}  ; E4
   {:pitch/midi 65 :start/beats 1.5 :dur/beats 0.25 :velocity 68 :channel 1}  ; F4
   {:pitch/midi 67 :start/beats 2.0 :dur/beats 0.25 :velocity 70 :channel 1}  ; G4
   {:pitch/midi 69 :start/beats 2.5 :dur/beats 0.25 :velocity 72 :channel 1}  ; A4 (♮6 — Dorian!)
   {:pitch/midi 67 :start/beats 3.0 :dur/beats 0.25 :velocity 68 :channel 1}  ; G4
   {:pitch/midi 65 :start/beats 3.5 :dur/beats 0.25 :velocity 65 :channel 1}])

(def ^:private motif-1-with-outlier
  ;; Same as clean but bar 2 has C# (MIDI 61) — not in D Dorian, not chromatic passing
  (assoc-in motif-1-clean [2 :pitch/midi] 61))

(def ^:private all-notes-clean
  (into [] (concat drone-notes pad-notes motif-1-clean)))

(def ^:private all-notes-with-outlier
  (into [] (concat drone-notes pad-notes motif-1-with-outlier)))

;; ---------------------------------------------------------------------------
;; detect-mode — modal disambiguation
;; ---------------------------------------------------------------------------

(deftest detect-mode-dorian
  (testing "D Dorian detected from characteristic ♮6 (A)"
    (let [pitches (mapv :pitch/midi (concat pad-notes motif-1-clean))
          result  (analyze/detect-mode pitches)]
      (is (= :D (:tonic result)))
      (is (= :dorian (:mode result)))
      (is (> (:confidence result) 0.5)))))

(deftest detect-mode-aeolian-vs-dorian
  (testing "Aeolian detected when ♭6 (Bb/Ab) dominates"
    ;; A natural minor (Aeolian): A B C D E F G
    (let [aeolian-pitches [69 71 72 74 76 77 79   ; A B C D E F G
                           69 74 76 72 69]]         ; A D E C A
      (let [result (analyze/detect-mode aeolian-pitches)]
        (is (= :A (:tonic result)))
        (is (= :aeolian (:mode result)))))))

;; ---------------------------------------------------------------------------
;; separate-voices! — NDLR structure heuristic
;; ---------------------------------------------------------------------------

(deftest separate-voices-basic
  (testing "Drone identified by duration and low register"
    (let [voices (repair/separate-voices! {:notes all-notes-clean})]
      (is (seq (:drone voices)))
      (is (every? #(<= (:pitch/midi %) 50) (:drone voices)))
      (is (every? #(>= (:dur/beats %) 4.0) (:drone voices)))))

  (testing "Pad identified by chord clusters"
    (let [voices (repair/separate-voices! {:notes all-notes-clean})]
      (is (seq (:pad voices)))))

  (testing "Motif notes are short and in upper register"
    (let [voices (repair/separate-voices! {:notes all-notes-clean})]
      (is (seq (:motif-1 voices)))
      (is (every? #(< (:dur/beats %) 0.6) (:motif-1 voices)))))

  (testing "Ambiguous set is small for clean NDLR data"
    (let [voices (repair/separate-voices! {:notes all-notes-clean})]
      (is (<= (count (:ambiguous voices)) 3)))))

;; ---------------------------------------------------------------------------
;; correct-outliers! — out-of-scale note detection
;; ---------------------------------------------------------------------------

(deftest outlier-detection
  (testing "Clean motif produces no corrections"
    (let [voices   {:drone drone-notes :pad pad-notes
                    :motif-1 motif-1-clean :motif-2 []}
          key-res  {:tonic :D :mode :dorian
                    :scale (scale/scale :D 3 :dorian)}
          result   (repair/correct-outliers! voices key-res)]
      (is (empty? (:corrected result)))
      (is (= (count motif-1-clean) (count (:unchanged result))))))

  (testing "Outlier C# detected and corrected"
    (let [voices   {:drone drone-notes :pad pad-notes
                    :motif-1 motif-1-with-outlier :motif-2 []}
          key-res  {:tonic :D :mode :dorian
                    :scale (scale/scale :D 3 :dorian)}
          result   (repair/correct-outliers! voices key-res)]
      (is (= 1 (count (:corrected result))))
      (let [corr (first (:corrected result))]
        (is (= 61 (get-in corr [:original :pitch/midi])))  ; C# caught
        (is (some? (:proposed corr)))
        ;; Proposed correction should be in D Dorian
        (when-let [prop (:proposed corr)]
          (is (d-dorian-midi? (:pitch/midi prop)))))))

  (testing "Chromatic passing tone is not flagged"
    ;; C# between C and D is a chromatic passing tone — should not be corrected
    (let [passing-note {:pitch/midi 61 :start/beats 1.5 :dur/beats 0.25 :velocity 68}
          context [{:pitch/midi 60 :start/beats 1.0 :dur/beats 0.25}   ; C  (Dorian ♭7)
                   passing-note
                   {:pitch/midi 62 :start/beats 2.0 :dur/beats 0.25}]  ; D  (tonic)
          voices  {:drone [] :pad [] :motif-1 context :motif-2 []}
          key-res {:tonic :D :mode :dorian :scale (scale/scale :D 3 :dorian)}
          result  (repair/correct-outliers! voices key-res)]
      (is (empty? (:corrected result))))))

;; ---------------------------------------------------------------------------
;; accept-corrections
;; ---------------------------------------------------------------------------

(deftest accept-corrections-test
  (testing "Accepted corrections update voice pitches in score"
    (let [voices  {:drone drone-notes :pad pad-notes
                   :motif-1 motif-1-with-outlier :motif-2 []}
          key-res {:tonic :D :mode :dorian :scale (scale/scale :D 3 :dorian)}
          corr    (repair/correct-outliers! voices key-res)
          score   {:voices voices :corrections corr}
          updated (repair/accept-corrections score)]
      ;; After correction, no note in motif-1 should be the original outlier
      (is (not (some #(= 61 (:pitch/midi %))
                     (get-in updated [:voices :motif-1])))))))

;; ---------------------------------------------------------------------------
;; score serialisation round-trip
;; ---------------------------------------------------------------------------

(deftest score-round-trip
  (testing "save-score / load-score round-trip preserves structure"
    (let [score  {:meta    {:tonic :D :mode :dorian :tempo 112.0 :bars 16}
                  :voices  {:drone drone-notes :pad pad-notes
                             :motif-1 motif-1-clean :motif-2 []}
                  :structure {:peak-bar 8 :peak-tension 0.7}
                  :corrections {:corrected [] :unchanged motif-1-clean}
                  :resolution {:bars 4 :style :ambient-fade}}
          edn    (repair/save-score score)
          loaded (repair/load-score edn)]
      (is (= (get-in score [:meta :tonic]) (get-in loaded [:meta :tonic])))
      (is (= (get-in score [:meta :mode])  (get-in loaded [:meta :mode])))
      (is (= (count (get-in score [:voices :motif-1]))
             (count (get-in loaded [:voices :motif-1])))))))
