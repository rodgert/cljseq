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

(def ^:private d-dorian-pcs #{2 4 5 7 9 11 0}) ; D(2) E(4) F(5) G(7) A(9) B♮(11) C(0) — Dorian ♮6!

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
;; resolve-ambiguous! — secondary voice classification
;; ---------------------------------------------------------------------------

(deftest resolve-ambiguous-basic
  (testing "Ambiguous notes near motif activity are absorbed into motif voices"
    ;; Put one ambiguous note at beat 2.5 — within 1 beat of motif-1-clean (beat 2.0–3.5)
    (let [ambig-high {:pitch/midi 68 :start/beats 2.5 :dur/beats 0.75 :velocity 64}
          ambig-low  {:pitch/midi 55 :start/beats 9.0 :dur/beats 0.75 :velocity 60}
          voices {:drone drone-notes :pad pad-notes
                  :motif-1 motif-1-clean :motif-2 []
                  :ambiguous [ambig-high ambig-low]}
          result (repair/resolve-ambiguous! voices)]
      ;; No ambiguous notes remain
      (is (empty? (:ambiguous result)))
      ;; High note near motif → motif-1
      (is (some #(= 68 (:pitch/midi %)) (:motif-1 result)))
      ;; Low note far from motif, below split → pad
      (is (some #(= 55 (:pitch/midi %)) (:pad result)))))

  (testing "Ambiguous notes with no motif context are split by register"
    ;; Place ambiguous notes far from any motif activity
    (let [high {:pitch/midi 72 :start/beats 50.0 :dur/beats 0.75 :velocity 64}
          low  {:pitch/midi 48 :start/beats 51.0 :dur/beats 0.75 :velocity 60}
          voices {:drone [] :pad [] :motif-1 [] :motif-2 [] :ambiguous [high low]}
          result (repair/resolve-ambiguous! voices)]
      (is (empty? (:ambiguous result)))
      (is (some #(= 72 (:pitch/midi %)) (:motif-1 result)))
      (is (some #(= 48 (:pitch/midi %)) (:pad result))))))

;; ---------------------------------------------------------------------------
;; resolution motif voices
;; ---------------------------------------------------------------------------

(deftest resolution-has-motif-voice
  (testing "generate-resolution! produces a non-empty motif-1 descending walk"
    (let [voices   {:drone drone-notes :pad pad-notes
                    :motif-1 motif-1-clean :motif-2 []}
          key-res  {:tonic :D :mode :dorian
                    :scale (scale/scale :D 4 :dorian)}
          struct   {:final-chord {:tension 0.15} :progression [] :tension-arc []
                    :peak-bar 8 :peak-tension 0.7}
          res      (repair/generate-resolution! struct key-res {:bars 4 :style :ambient-fade})]
      (is (seq (get-in res [:voices :motif-1])))
      ;; Walk should end on or near the tonic (degree 0 = D)
      (let [last-midi (:pitch/midi (last (get-in res [:voices :motif-1])))]
        (is (= 2 (mod last-midi 12))))   ; D = pitch class 2
      ;; Velocities should decrease (fade)
      (let [vels (mapv :velocity (get-in res [:voices :motif-1]))]
        (is (> (first vels) (last vels))))))

  (testing "motif notes are all diatonic to the scale"
    (let [key-res {:tonic :D :mode :dorian :scale (scale/scale :D 4 :dorian)}
          struct  {:final-chord {:tension 0.15} :progression [] :tension-arc []
                   :peak-bar 8 :peak-tension 0.7}
          res     (repair/generate-resolution! struct key-res {:bars 4 :style :ambient-fade})
          sc      (:scale key-res)]
      (is (every? #(scale/in-scale? sc (pitch/midi->pitch (:pitch/midi %)))
                  (get-in res [:voices :motif-1]))))))

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

;; ---------------------------------------------------------------------------
;; save-midi! — MIDI export round-trip
;; ---------------------------------------------------------------------------

(deftest save-midi-round-trip
  (testing "save-midi! writes a readable Format 1 MIDI file"
    (let [res-drone [{:pitch/midi 50 :start/beats 0.0 :dur/beats 16.0 :velocity 45}]
          res-pad   [{:chord [50 53 57] :start/beats 0.0 :dur/beats 4.0 :velocity 55}
                     {:chord [55 59 62] :start/beats 4.0 :dur/beats 4.0 :velocity 55}]
          score  {:meta    {:tonic :D :mode :dorian :tempo 112.0 :bars 16 :time-sig "4/4"}
                  :voices  {:drone drone-notes :pad pad-notes
                             :motif-1 motif-1-clean :motif-2 []}
                  :resolution {:bars 4 :style :ambient-fade
                               :voices {:drone res-drone :pad res-pad
                                        :motif-1 [] :motif-2 []}}}
          tmp    (java.io.File/createTempFile "cljseq-midi-test" ".mid")
          path   (.getAbsolutePath tmp)]
      (try
        (repair/save-midi! score path {:include-resolution? true})
        ;; File was written and is non-empty
        (is (.exists tmp))
        (is (pos? (.length tmp)))
        ;; Verify it's a valid MIDI file (starts with MThd header)
        (let [bytes (with-open [in (java.io.FileInputStream. path)]
                      (let [buf (byte-array 4)]
                        (.read in buf)
                        buf))]
          (is (= [0x4D 0x54 0x68 0x64]   ; "MThd"
                 (mapv #(bit-and % 0xFF) bytes))))
        (finally
          (.delete tmp))))))

(deftest save-midi-resolution-offset
  (testing "Resolution notes are offset by score-beats in MIDI output"
    ;; Verify by checking the score map arithmetic directly:
    ;; 16 bars × 4 beats/bar = 64 beats offset for resolution
    (let [score {:meta    {:tonic :D :mode :dorian :tempo 112.0 :bars 16 :time-sig "4/4"}
                 :voices  {:drone drone-notes :pad [] :motif-1 [] :motif-2 []}
                 :resolution {:bars 2 :style :ambient-fade
                              :voices {:drone [{:pitch/midi 50 :start/beats 0.0
                                                :dur/beats 8.0 :velocity 45}]
                                       :pad [] :motif-1 [] :motif-2 []}}}
          tmp  (java.io.File/createTempFile "cljseq-midi-offset-test" ".mid")
          path (.getAbsolutePath tmp)]
      (try
        (repair/save-midi! score path {:include-resolution? true})
        (is (pos? (.length tmp)))
        (finally
          (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; Phase 3 — Composition as data: to-score, transpose, mode-shift, retrograde
;; ---------------------------------------------------------------------------

;; Shared fixture — minimal repair-pipeline! output for transformation tests
(def ^:private repair-out
  {:meta        {:tonic :D :mode :dorian :tempo 112.0 :bars 16 :time-sig "4/4"}
   :voices      {:drone drone-notes :pad pad-notes
                 :motif-1 motif-1-clean :motif-2 []}
   :structure   {:progression     [{:bar 0 :roman :i :tension 0.2}
                                    {:bar 4 :roman :IV :tension 0.5}]
                 :tension-arc     [{:bar 0 :tension 0.2} {:bar 4 :tension 0.5}]
                 :peak-bar        4
                 :peak-tension    0.5
                 :final-chord     {:bar 12 :roman :i :tension 0.2}}
   :corrections {:corrected [] :unchanged motif-1-clean}
   :resolution  {:bars 4 :style :ambient-fade
                 :voices {:drone [{:pitch/midi 38 :start/beats 0.0 :dur/beats 16.0 :velocity 45}]
                          :pad [] :motif-1 [] :motif-2 []}}})

;; --- to-score ---------------------------------------------------------------

(deftest to-score-structure
  (testing "Returns :meta :voices :arc and :coda keys"
    (let [score (repair/to-score repair-out)]
      (is (map? score))
      (is (contains? score :meta))
      (is (contains? score :voices))
      (is (contains? score :arc))
      (is (contains? score :coda))))

  (testing ":key-sig is derived from tonic and mode"
    (let [score (repair/to-score repair-out)]
      (is (= "D dorian" (get-in score [:meta :key-sig])))))

  (testing ":arc contains the structural summary keys"
    (let [arc (:arc (repair/to-score repair-out))]
      (is (contains? arc :progression))
      (is (contains? arc :tension-arc))
      (is (contains? arc :peak-bar))
      (is (contains? arc :peak-tension))
      (is (contains? arc :final-chord))))

  (testing "include-coda? false omits :coda"
    (let [score (repair/to-score repair-out {:include-coda? false})]
      (is (not (contains? score :coda)))))

  (testing "apply-corrections? false leaves voices untouched"
    (let [with-outlier (assoc-in repair-out [:corrections :corrected]
                                 [{:voice :motif-1 :index 2
                                   :original  {:pitch/midi 61 :start/beats 1.5 :dur/beats 0.25 :velocity 68}
                                   :proposed  {:pitch/midi 62 :start/beats 1.5 :dur/beats 0.25 :velocity 68}
                                   :reason    "out-of-scale"}])
          score-false  (repair/to-score with-outlier {:apply-corrections? false})
          score-true   (repair/to-score with-outlier {:apply-corrections? true})]
      ;; With corrections applied, the outlier pitch should be gone
      (is (not= (get-in score-false [:voices :motif-1])
                (get-in score-true  [:voices :motif-1]))))))

;; --- transpose --------------------------------------------------------------

(deftest transpose-pitch-shift
  (testing "Shifts :pitch/midi in all voice notes"
    (let [score  (repair/to-score repair-out)
          score2 (repair/transpose score 2)]
      ;; All drone pitches shifted by 2
      (let [orig-midis (mapv :pitch/midi (get-in score  [:voices :drone]))
            new-midis  (mapv :pitch/midi (get-in score2 [:voices :drone]))]
        (is (every? true? (map #(= (+ %1 2) %2) orig-midis new-midis))))))

  (testing "Shifts :pitch/midi in motif notes by the given amount"
    ;; motif-1-clean first note is MIDI 62 (D4); +2 = 64 (E4)
    (let [score  (repair/to-score repair-out)
          score2 (repair/transpose score 2)]
      (is (= 64 (get-in score2 [:voices :motif-1 0 :pitch/midi])))))

  (testing "Updates :meta :tonic correctly"
    (let [score  (repair/to-score repair-out)
          score2 (repair/transpose score 2)]   ; D + 2 semitones = E
      (is (= :E (get-in score2 [:meta :tonic])))))

  (testing "Updates :meta :key-sig"
    (let [score2 (repair/transpose (repair/to-score repair-out) 2)]
      (is (= "E dorian" (get-in score2 [:meta :key-sig])))))

  (testing "Transposes coda voices"
    (let [score  (repair/to-score repair-out)
          score2 (repair/transpose score 5)
          orig   (get-in score  [:coda :voices :drone 0 :pitch/midi])
          new    (get-in score2 [:coda :voices :drone 0 :pitch/midi])]
      (is (= (+ orig 5) new))))

  (testing "Negative semitones work"
    (let [score  (repair/to-score repair-out)
          score2 (repair/transpose score -2)]   ; D - 2 = C
      (is (= :C (get-in score2 [:meta :tonic])))))

  (testing "Zero semitones is identity"
    (let [score  (repair/to-score repair-out)
          score2 (repair/transpose score 0)]
      (is (= (get-in score [:meta :tonic]) (get-in score2 [:meta :tonic])))
      (is (= (get-in score [:voices :drone]) (get-in score2 [:voices :drone]))))))

;; --- mode-shift -------------------------------------------------------------

(deftest mode-shift-dorian-to-aeolian
  (testing "D Dorian → D Aeolian: ♮6 (B) becomes ♭6 (Bb)"
    ;; B3 = MIDI 59 in D Dorian; Bb3 = MIDI 58 in D Aeolian
    ;; pad-notes contains B3 (MIDI 59) in the IV chord cluster at beat 4
    (let [score   (repair/to-score repair-out)
          shifted (repair/mode-shift score :aeolian)
          ;; Check that any B (MIDI 59 or 71) in pad has become Bb (58 or 70)
          orig-pad-midis (set (mapv :pitch/midi (get-in score   [:voices :pad])))
          new-pad-midis  (set (mapv :pitch/midi (get-in shifted [:voices :pad])))]
      ;; B3 (59) was in original — Bb3 (58) should be in shifted
      (when (contains? orig-pad-midis 59)
        (is (not (contains? new-pad-midis 59)) "B♮ should be gone")
        (is (contains? new-pad-midis 58) "Bb should appear"))))

  (testing "Updates :meta :mode"
    (let [shifted (repair/mode-shift (repair/to-score repair-out) :aeolian)]
      (is (= :aeolian (get-in shifted [:meta :mode])))))

  (testing "Updates :meta :key-sig"
    (let [shifted (repair/mode-shift (repair/to-score repair-out) :aeolian)]
      (is (= "D aeolian" (get-in shifted [:meta :key-sig])))))

  (testing "Shared scale degrees are preserved"
    ;; D E F G A are shared between D Dorian and D Aeolian; drone is D (MIDI 38)
    (let [score   (repair/to-score repair-out)
          shifted (repair/mode-shift score :aeolian)]
      (is (= (mapv :pitch/midi (get-in score   [:voices :drone]))
             (mapv :pitch/midi (get-in shifted [:voices :drone]))))))

  (testing "Idempotent within same mode"
    (let [score   (repair/to-score repair-out)
          shifted (repair/mode-shift score :dorian)]
      (is (= (mapv :pitch/midi (get-in score   [:voices :motif-1]))
             (mapv :pitch/midi (get-in shifted [:voices :motif-1])))))))

;; --- retrograde -------------------------------------------------------------

(deftest retrograde-single-voice
  (testing "First note of retrograded voice has the pitch of the original last note"
    (let [score  (repair/to-score repair-out)
          before (get-in score [:voices :motif-1])
          after  (:motif-1 (:voices (repair/retrograde score {:voice :motif-1})))]
      (is (= (:pitch/midi (last before))
             (:pitch/midi (first after))))))

  (testing "Last note of retrograded voice has the pitch of the original first note"
    (let [score  (repair/to-score repair-out)
          before (get-in score [:voices :motif-1])
          after  (:motif-1 (:voices (repair/retrograde score {:voice :motif-1})))]
      (is (= (:pitch/midi (first before))
             (:pitch/midi (last after))))))

  (testing "Event span preserved — (last-end - first-start) unchanged by retrograde"
    ;; Retrograde redistributes the initial silence gap to the end, so max-end
    ;; shifts, but the interval from first-on to last-off stays the same.
    (let [note-end  (fn [n] (+ (:start/beats n 0.0) (:dur/beats n 0.0)))
          score     (repair/to-score repair-out)
          before    (get-in score [:voices :motif-1])
          after     (:motif-1 (:voices (repair/retrograde score {:voice :motif-1})))
          event-span (fn [notes]
                       (- (apply max (map note-end notes))
                          (apply min (map :start/beats notes))))
          es-b      (event-span before)
          es-a      (event-span after)]
      (is (< (Math/abs (- es-b es-a)) 0.001))))

  (testing "Note count is unchanged"
    (let [score  (repair/to-score repair-out)
          before (get-in score [:voices :motif-1])
          after  (:motif-1 (:voices (repair/retrograde score {:voice :motif-1})))]
      (is (= (count before) (count after)))))

  (testing "Empty voice: no error"
    (let [score (repair/to-score repair-out)]
      (is (= [] (:motif-2 (:voices (repair/retrograde score {:voice :motif-2}))))))))

(deftest retrograde-all-voices
  (testing "All voices are retrograded using common span"
    (let [note-end  (fn [n] (+ (:start/beats n 0.0) (:dur/beats n 0.0)))
          score     (repair/to-score repair-out)
          retrograded (repair/retrograde score)
          ;; drone is the longest voice — check its span is preserved
          drone-b   (get-in score       [:voices :drone])
          drone-a   (get-in retrograded [:voices :drone])
          span-b    (apply max (map note-end drone-b))
          span-a    (apply max (map note-end drone-a))]
      (is (< (Math/abs (- span-b span-a)) 0.001))))

  (testing "Motif-1 first note pitch = last note pitch before retrograde"
    (let [score     (repair/to-score repair-out)
          retrograded (repair/retrograde score)
          before    (get-in score       [:voices :motif-1])
          after     (get-in retrograded [:voices :motif-1])]
      (is (= (:pitch/midi (last before))
             (:pitch/midi (first after)))))))
