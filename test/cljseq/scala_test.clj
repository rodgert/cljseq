; SPDX-License-Identifier: EPL-2.0
(ns cljseq.scala-test
  "Unit tests for cljseq.scala — Scala (.scl) file parsing and microtonal pitch math.

  All tests are pure (no REPL, no sidecar, no file I/O beyond inline strings).
  The :pitch/bend-cents integration with core/play! is tested in core_test."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [cljseq.scala   :as scala]))

;; ---------------------------------------------------------------------------
;; SCL string fixtures
;; ---------------------------------------------------------------------------

;; Standard 12-TET (all degrees are multiples of 100 cents)
(def ^:private scl-12tet
  "! 12tet.scl\n!\n12 tone equal temperament\n 12\n!\n 100.000\n 200.000\n 300.000\n 400.000\n 500.000\n 600.000\n 700.000\n 800.000\n 900.000\n 1000.000\n 1100.000\n 1200.000\n")

;; Pythagorean diatonic — ratios
(def ^:private scl-pythagorean
  "! pythagorean.scl\n!\nPythagorean diatonic scale\n 7\n!\n 9/8\n 81/64\n 4/3\n 3/2\n 27/16\n 243/128\n 2/1\n")

;; 5-note scale with mixed entry formats (cents + ratio + integer)
(def ^:private scl-mixed
  "! mixed.scl\n!\nMixed format test scale\n 5\n!\n 200.000\n 5/4\n 3/2\n 7/4\n 2\n")

;; Bohlen-Pierce — non-octave period (ratio 3/1 ≈ 1902 cents)
(def ^:private scl-bp-excerpt
  "! bp-excerpt.scl\n!\nBohlen-Pierce 3-step excerpt\n 3\n!\n 400.000\n 800.000\n 1902.000\n")

;; 31-EDO (31 equal divisions of the octave, each step ≈ 38.71 cents)
(def ^:private scl-31edo
  (str "! 31edo.scl\n!\n31 equal divisions of the octave\n 31\n!\n"
       (str/join "\n" (map #(format " %.6f" (* (/ 1200.0 31) %)) (range 1 32)))
       "\n"))

;; ---------------------------------------------------------------------------
;; Parsing — basic structure
;; ---------------------------------------------------------------------------

(deftest parse-12tet-test
  (testing "12-TET parses correctly"
    (let [ms (scala/parse-scl scl-12tet)]
      (is (= "12 tone equal temperament" (:description ms)))
      (is (= 12 (scala/degree-count ms)))
      (is (== 1200.0 (scala/period-cents ms))))))

(deftest parse-pythagorean-test
  (testing "Pythagorean scale parses correctly"
    (let [ms (scala/parse-scl scl-pythagorean)]
      (is (= "Pythagorean diatonic scale" (:description ms)))
      (is (= 7 (scala/degree-count ms)))
      (is (== 1200.0 (scala/period-cents ms)))))
  (testing "ratio entries convert to correct cents"
    (let [ms (scala/parse-scl scl-pythagorean)
          ;; 9/8 ≈ 203.910 cents
          d1 (second (scala/all-degrees ms))]
      (is (< (Math/abs (- d1 203.910)) 0.01)))))

(deftest parse-mixed-formats-test
  (testing "mixed cents/ratio/integer entries parse without error"
    (let [ms (scala/parse-scl scl-mixed)]
      (is (= 5 (scala/degree-count ms)))
      ;; 200.000 cents entry
      (is (== 200.0 (second (scala/all-degrees ms))))
      ;; 5/4 = 386.314 cents
      (is (< (Math/abs (- (nth (scala/all-degrees ms) 2) 386.314)) 0.01))
      ;; 3/2 = 701.955 cents
      (is (< (Math/abs (- (nth (scala/all-degrees ms) 3) 701.955)) 0.01))
      ;; 7/4 = 968.826 cents
      (is (< (Math/abs (- (nth (scala/all-degrees ms) 4) 968.826)) 0.01))
      ;; period = 2 (integer) = 1200.0 cents
      (is (< (Math/abs (- (scala/period-cents ms) 1200.0)) 0.01)))))

(deftest parse-nonoctave-period-test
  (testing "Bohlen-Pierce 3/1 period parses correctly"
    (let [ms (scala/parse-scl scl-bp-excerpt)]
      (is (= 3 (scala/degree-count ms)))
      (is (< (Math/abs (- (scala/period-cents ms) 1902.0)) 1.0)))))

(deftest parse-31edo-test
  (testing "31-EDO parses 31 degrees"
    (let [ms (scala/parse-scl scl-31edo)]
      (is (= 31 (scala/degree-count ms)))
      ;; Each step is 1200/31 ≈ 38.71 cents
      (is (< (Math/abs (- (second (scala/all-degrees ms)) (/ 1200.0 31))) 0.01)))))

(deftest parse-implicit-unison-test
  (testing "degree 0 is always 0.0 cents (implicit unison)"
    (let [ms (scala/parse-scl scl-pythagorean)]
      (is (== 0.0 (first (scala/all-degrees ms)))))))

(deftest parse-error-missing-count-test
  (testing "missing note count line throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (scala/parse-scl "! comment\ndescription only\n")))))

(deftest parse-error-non-integer-count-test
  (testing "non-integer note count throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (scala/parse-scl "! x\ndesc\nnot-a-number\n200.0\n")))))

;; ---------------------------------------------------------------------------
;; degree->cents
;; ---------------------------------------------------------------------------

(deftest degree->cents-root-test
  (testing "degree 0 is always 0.0 cents"
    (let [ms (scala/parse-scl scl-pythagorean)]
      (is (== 0.0 (scala/degree->cents ms 0))))))

(deftest degree->cents-within-period-test
  (testing "degrees 1-6 return correct values for Pythagorean scale"
    (let [ms      (scala/parse-scl scl-pythagorean)
          degrees (scala/all-degrees ms)]
      (doseq [i (range (scala/degree-count ms))]
        (is (== (nth degrees i) (scala/degree->cents ms i)))))))

(deftest degree->cents-octave-wrap-test
  (testing "degree = degree-count wraps to period-cents"
    (let [ms (scala/parse-scl scl-pythagorean)
          n  (scala/degree-count ms)]
      (is (== (scala/period-cents ms) (scala/degree->cents ms n)))))
  (testing "degree = 2*degree-count wraps to 2*period-cents"
    (let [ms (scala/parse-scl scl-12tet)
          n  (scala/degree-count ms)]
      (is (== (* 2.0 (scala/period-cents ms)) (scala/degree->cents ms (* 2 n)))))))

(deftest degree->cents-negative-test
  (testing "degree -1 is the last step minus period"
    (let [ms     (scala/parse-scl scl-pythagorean)
          n      (scala/degree-count ms)
          last-d (nth (scala/all-degrees ms) (dec n))]
      (is (< (Math/abs (- (scala/degree->cents ms -1)
                          (- last-d (scala/period-cents ms))))
             1e-9))))
  (testing "degree -n = -period (one period below root)"
    (let [ms (scala/parse-scl scl-pythagorean)
          n  (scala/degree-count ms)]
      (is (< (Math/abs (- (scala/degree->cents ms (- n))
                          (- (scala/period-cents ms))))
             1e-9)))))

;; ---------------------------------------------------------------------------
;; degree->note
;; ---------------------------------------------------------------------------

(deftest degree->note-root-test
  (testing "degree 0 from root 60 → midi 60 with 0 bend"
    (let [ms (scala/parse-scl scl-pythagorean)
          r  (scala/degree->note ms 60 0)]
      (is (= 60 (:midi r)))
      (is (== 0.0 (:bend-cents r))))))

(deftest degree->note-12tet-zero-bend-test
  (testing "all 12-TET degrees have zero bend-cents"
    (let [ms (scala/parse-scl scl-12tet)]
      (doseq [d (range 13)]
        (let [r (scala/degree->note ms 60 d)]
          (is (< (Math/abs (:bend-cents r)) 1e-9)
              (str "degree " d " should have zero bend, got " (:bend-cents r))))))))

(deftest degree->note-bend-range-test
  (testing "bend-cents is always in [-50, +50] (within one semitone)"
    (let [ms (scala/parse-scl scl-pythagorean)]
      (doseq [d (range -7 21)]
        (let [{:keys [bend-cents]} (scala/degree->note ms 60 d)]
          (is (<= -50.0 bend-cents 50.0)
              (str "degree " d " bend " bend-cents " out of ±50 range")))))))

(deftest degree->note-pythagorean-fifth-test
  (testing "Pythagorean 5th (degree 4) is slightly flat of 12-TET G"
    ;; 3/2 = 701.955 cents, 12-TET 5th = 700.0 cents
    ;; nearest MIDI from 60 = 67 (G4), bend = +1.955 cents
    (let [ms (scala/parse-scl scl-pythagorean)
          r  (scala/degree->note ms 60 4)]
      (is (= 67 (:midi r)))
      (is (< (Math/abs (- (:bend-cents r) 1.955)) 0.01)))))

(deftest degree->note-pythagorean-third-test
  (testing "Pythagorean major third (degree 2) is sharp of 12-TET"
    ;; 81/64 = 407.820 cents, nearest MIDI = 64 (E4, 400 cents), bend = +7.820 cents
    (let [ms (scala/parse-scl scl-pythagorean)
          r  (scala/degree->note ms 60 2)]
      (is (= 64 (:midi r)))
      (is (< (Math/abs (- (:bend-cents r) 7.820)) 0.01)))))

(deftest degree->note-multi-octave-test
  (testing "degree beyond one octave maps to correct MIDI + bend"
    (let [ms (scala/parse-scl scl-pythagorean)
          ;; degree 7 = one octave up from root
          r7  (scala/degree->note ms 60 7)
          ;; degree 11 = octave + degree 4 (5th)
          r11 (scala/degree->note ms 60 11)]
      (is (= 72 (:midi r7)))   ; C5
      (is (< (Math/abs (:bend-cents r7)) 1e-9))  ; pure octave, no bend
      (is (= 79 (:midi r11)))  ; G5
      (is (< (Math/abs (- (:bend-cents r11) 1.955)) 0.01)))))

(deftest degree->note-midi-clamp-test
  (testing "MIDI note is clamped to 0-127"
    (let [ms (scala/parse-scl scl-pythagorean)
          ;; root at 0 with a very low degree should not go below 0
          r  (scala/degree->note ms 0 -21)]
      (is (>= (:midi r) 0)))))

;; ---------------------------------------------------------------------------
;; degree->step
;; ---------------------------------------------------------------------------

(deftest degree->step-has-midi-test
  (testing "degree->step always has :pitch/midi"
    (let [ms (scala/parse-scl scl-pythagorean)]
      (is (contains? (scala/degree->step ms 60 0) :pitch/midi))
      (is (contains? (scala/degree->step ms 60 4) :pitch/midi)))))

(deftest degree->step-omits-zero-bend-test
  (testing "degree->step omits :pitch/bend-cents when bend is 0"
    (let [ms (scala/parse-scl scl-12tet)]
      (doseq [d (range 13)]
        (is (not (contains? (scala/degree->step ms 60 d) :pitch/bend-cents))
            (str "12-TET degree " d " should omit bend-cents"))))))

(deftest degree->step-includes-nonzero-bend-test
  (testing "degree->step includes :pitch/bend-cents for non-12TET scales"
    (let [ms (scala/parse-scl scl-pythagorean)
          ;; degree 4 (Pythagorean 5th) has non-zero bend
          step (scala/degree->step ms 60 4)]
      (is (contains? step :pitch/bend-cents))
      (is (< (Math/abs (- (:pitch/bend-cents step) 1.955)) 0.01)))))

;; ---------------------------------------------------------------------------
;; interval-cents
;; ---------------------------------------------------------------------------

(deftest interval-cents-test
  (testing "interval from root to 5th in Pythagorean scale"
    (let [ms (scala/parse-scl scl-pythagorean)]
      (is (< (Math/abs (- (scala/interval-cents ms 0 4) 701.955)) 0.01))))
  (testing "interval from 5th to octave (4th)"
    (let [ms (scala/parse-scl scl-pythagorean)]
      ;; 1200.0 - 701.955 = 498.045 (perfect 4th)
      (is (< (Math/abs (- (scala/interval-cents ms 4 7) 498.045)) 0.01)))))

;; ---------------------------------------------------------------------------
;; KBM fixtures
;; ---------------------------------------------------------------------------

;; Standard 12-key identity layout: middle=60, ref=69 (A4=440)
;; map-size=0 → identity mapping
(def ^:private kbm-identity
  "! identity.kbm\n!\n0\n0\n127\n60\n69\n440.0\n12\n")

;; 7-white-keys-only layout: maps MIDI 60-71 white keys to Pythagorean degrees.
;; Degree indices are into the Pythagorean scale's degrees vector (0-based):
;;   0=unison, 1=maj2(9/8), 2=maj3(81/64), 3=P4(4/3), 4=P5(3/2), 5=maj6, 6=maj7
;; Black keys (C# D# F# G# A#) are unmapped ('x').
(def ^:private kbm-white-keys
  (str "! white-keys.kbm\n!\n"
       "12\n"     ; map-size = 12
       "0\n"      ; first MIDI
       "127\n"    ; last MIDI
       "60\n"     ; middle note = C4
       "69\n"     ; reference note = A4
       "440.0\n"  ; reference freq
       "7\n"      ; formal octave = degree 7 (one period above root)
       "0\nx\n1\nx\n2\n3\nx\n4\nx\n5\nx\n6\n"))

;; 5-note equal layout: maps MIDI keys 0-4 within each period
(def ^:private kbm-pentatonic
  (str "! pentatonic.kbm\n!\n"
       "5\n"
       "0\n"
       "127\n"
       "60\n"
       "60\n"
       "261.626\n"
       "5\n"
       "0\n1\n2\n3\n4\n"))

;; ---------------------------------------------------------------------------
;; KBM parsing
;; ---------------------------------------------------------------------------

(deftest parse-kbm-identity-test
  (testing "identity kbm parses all 7 header fields"
    (let [kbm (scala/parse-kbm kbm-identity)]
      (is (= 0   (:map-size kbm)))
      (is (= 0   (:first-midi kbm)))
      (is (= 127 (:last-midi kbm)))
      (is (= 60  (:middle-note kbm)))
      (is (= 69  (:reference-note kbm)))
      (is (== 440.0 (:reference-freq kbm)))
      (is (= 12  (:formal-octave kbm)))
      (is (= [] (:keys kbm)) "identity map has no key entries"))))

(deftest parse-kbm-white-keys-test
  (testing "white-keys kbm has 12 key entries with :x for black keys"
    (let [kbm (scala/parse-kbm kbm-white-keys)]
      (is (= 12 (:map-size kbm)))
      (is (= 12 (count (:keys kbm))))
      (is (= 0  (nth (:keys kbm) 0)))   ; C → degree 0
      (is (= :x (nth (:keys kbm) 1)))   ; C# → unmapped
      (is (= 1  (nth (:keys kbm) 2)))   ; D → degree 1 (9/8)
      (is (= :x (nth (:keys kbm) 3)))   ; D# → unmapped
      (is (= 3  (nth (:keys kbm) 5)))   ; F → degree 3 (4/3)
      (is (= 6  (nth (:keys kbm) 11))))))  ; B → degree 6 (maj7)

(deftest parse-kbm-missing-headers-throws-test
  (testing "fewer than 7 header lines throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (scala/parse-kbm "! comment\n0\n0\n127\n")))))

(deftest parse-kbm-bad-integer-throws-test
  (testing "non-integer field throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (scala/parse-kbm "! x\nnot-an-int\n0\n127\n60\n69\n440.0\n12\n")))))

(deftest parse-kbm-bad-key-entry-throws-test
  (testing "non-integer non-x key entry throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (scala/parse-kbm (str "! x\n12\n0\n127\n60\n69\n440.0\n7\n"
                                       "0\nbad\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n"))))))

;; ---------------------------------------------------------------------------
;; midi->note — identity mapping (map-size=0)
;; ---------------------------------------------------------------------------

(deftest midi->note-identity-12tet-test
  (testing "identity kbm + 12-TET → no bend on any key"
    (let [kbm (scala/parse-kbm kbm-identity)
          ms  (scala/parse-scl scl-12tet)]
      (doseq [n (range 0 128)]
        (let [r (scala/midi->note kbm ms n)]
          (is (some? r) (str "note " n " should not be nil"))
          (is (< (Math/abs (:bend-cents r)) 1e-9)
              (str "note " n " should have zero bend in 12-TET")))))))

(deftest midi->note-identity-preserves-midi-test
  (testing "identity kbm maps MIDI notes to themselves in 12-TET"
    (let [kbm (scala/parse-kbm kbm-identity)
          ms  (scala/parse-scl scl-12tet)]
      (doseq [n (range 0 128)]
        (is (= n (:midi (scala/midi->note kbm ms n))))))))

;; ---------------------------------------------------------------------------
;; midi->note — range filtering
;; ---------------------------------------------------------------------------

(deftest midi->note-out-of-range-test
  (testing "MIDI note outside [first-midi, last-midi] returns nil"
    ;; Use a kbm with a restricted range
    (let [kbm (scala/parse-kbm (str "! range.kbm\n!\n0\n48\n72\n60\n69\n440.0\n12\n"))
          ms  (scala/parse-scl scl-12tet)]
      (is (nil? (scala/midi->note kbm ms 47)) "below first-midi → nil")
      (is (nil? (scala/midi->note kbm ms 73)) "above last-midi → nil")
      (is (some? (scala/midi->note kbm ms 60)) "in-range → non-nil"))))

;; ---------------------------------------------------------------------------
;; midi->note — white-key mapping + Pythagorean
;; ---------------------------------------------------------------------------

(deftest midi->note-white-key-mapped-test
  (testing "white keys (MIDI 60 C, 62 D, 65 F) are mapped to Pythagorean degrees"
    (let [kbm (scala/parse-kbm kbm-white-keys)
          ms  (scala/parse-scl scl-pythagorean)]
      ;; MIDI 60 → degree 0 (root, no bend)
      (let [r (scala/midi->note kbm ms 60)]
        (is (some? r))
        (is (= 60 (:midi r)))
        (is (< (Math/abs (:bend-cents r)) 1e-9) "root has no bend"))
      ;; MIDI 62 (D) → degree 1 (9/8 ≈ 203.91 cents → MIDI 62, bend ≈ +3.91 cents)
      (let [r (scala/midi->note kbm ms 62)]
        (is (some? r))
        (is (= 62 (:midi r)))
        (is (< (Math/abs (- (:bend-cents r) 3.91)) 0.05)
            (str "D key Pythagorean maj2 bend, got " (:bend-cents r)))))))

(deftest midi->note-black-key-unmapped-test
  (testing "black keys in white-key layout return nil"
    (let [kbm (scala/parse-kbm kbm-white-keys)
          ms  (scala/parse-scl scl-pythagorean)]
      (is (nil? (scala/midi->note kbm ms 61)) "C# → nil")
      (is (nil? (scala/midi->note kbm ms 63)) "D# → nil")
      (is (nil? (scala/midi->note kbm ms 66)) "F# → nil"))))

;; ---------------------------------------------------------------------------
;; midi->step
;; ---------------------------------------------------------------------------

(deftest midi->step-returns-step-map-test
  (testing "midi->step returns :pitch/midi for mapped keys"
    (let [kbm (scala/parse-kbm kbm-identity)
          ms  (scala/parse-scl scl-12tet)]
      (let [step (scala/midi->step kbm ms 60)]
        (is (contains? step :pitch/midi))
        (is (= 60 (:pitch/midi step))))))
  (testing "midi->step returns nil for unmapped keys"
    (let [kbm (scala/parse-kbm kbm-white-keys)
          ms  (scala/parse-scl scl-pythagorean)]
      (is (nil? (scala/midi->step kbm ms 61))))))

(deftest midi->step-omits-zero-bend-test
  (testing "midi->step omits :pitch/bend-cents when bend is 0"
    (let [kbm  (scala/parse-kbm kbm-identity)
          ms   (scala/parse-scl scl-12tet)]
      (doseq [n (range 0 128)]
        (is (not (contains? (scala/midi->step kbm ms n) :pitch/bend-cents))
            (str "12-TET note " n " should omit bend-cents"))))))

(deftest midi->step-includes-bend-for-microtonal-test
  (testing "midi->step includes :pitch/bend-cents for non-12TET mapping"
    (let [kbm  (scala/parse-kbm kbm-white-keys)
          ms   (scala/parse-scl scl-pythagorean)
          ;; MIDI 62 (D) → degree 1 (9/8), bend ≈ +3.91 cents
          step (scala/midi->step kbm ms 62)]
      (is (contains? step :pitch/bend-cents))
      (is (< (Math/abs (- (:pitch/bend-cents step) 3.91)) 0.05)))))

;; ---------------------------------------------------------------------------
;; scale->mts-bytes — MTS Bulk Dump SysEx
;; ---------------------------------------------------------------------------

(deftest mts-bytes-total-length-test
  (testing "scale->mts-bytes returns exactly 408 bytes"
    (let [ms  (scala/parse-scl scl-12tet)
          arr (scala/scale->mts-bytes ms)]
      (is (= 408 (alength arr))))))

(deftest mts-bytes-framing-test
  (testing "MTS message starts with F0 7E 7F 08 01 and ends with F7"
    (let [ms  (scala/parse-scl scl-12tet)
          arr (scala/scale->mts-bytes ms)]
      (is (= 0xF0 (bit-and (aget arr 0) 0xFF)))
      (is (= 0x7E (bit-and (aget arr 1) 0xFF)))
      (is (= 0x7F (bit-and (aget arr 2) 0xFF)))
      (is (= 0x08 (bit-and (aget arr 3) 0xFF)))
      (is (= 0x01 (bit-and (aget arr 4) 0xFF)))
      (is (= 0xF7 (bit-and (aget arr (dec (alength arr))) 0xFF))))))

(deftest mts-bytes-name-field-test
  (testing "16-byte name field is written starting at offset 6"
    (let [ms      (scala/parse-scl scl-12tet)   ; description = "12 tone equal temperament"
          arr     (scala/scale->mts-bytes ms)
          name-s  (apply str (map #(char (bit-and (aget arr (+ 6 %)) 0xFF)) (range 16)))]
      ;; First chars match the description, remainder padded with spaces
      (is (= "12 tone equal te" name-s)))))

(deftest mts-12tet-identity-test
  (testing "12-TET encodes all 128 keys with zero bend (yy=zz=0, xx=key)"
    (let [ms  (scala/parse-scl scl-12tet)
          arr (scala/scale->mts-bytes ms)]
      ;; Note entries start at byte 22: 1(F0) + 5(header) + 16(name) = 22
      (doseq [k (range 128)]
        (let [base (+ 22 (* k 3))
              xx   (bit-and (aget arr base)       0xFF)
              yy   (bit-and (aget arr (+ base 1)) 0xFF)
              zz   (bit-and (aget arr (+ base 2)) 0xFF)]
          (is (= k xx)  (str "12-TET key " k " xx should be " k))
          (is (= 0 yy)  (str "12-TET key " k " yy should be 0"))
          (is (= 0 zz)  (str "12-TET key " k " zz should be 0")))))))

(deftest mts-checksum-valid-test
  (testing "checksum is XOR of bytes [1..405] masked to 7 bits"
    (let [ms   (scala/parse-scl scl-12tet)
          arr  (scala/scale->mts-bytes ms)
          n    (alength arr)
          ;; bytes from index 1 (after F0) up to but not including checksum (index n-2)
          expected (bit-and
                     (reduce (fn [acc i]
                               (bit-xor acc (bit-and (aget arr i) 0xFF)))
                             0 (range 1 (- n 2)))
                     0x7F)
          actual (bit-and (aget arr (- n 2)) 0xFF)]
      (is (= expected actual)))))

(deftest mts-negative-bend-handling-test
  (testing "negative bend is folded into adjacent semitone — fine value is non-negative"
    ;; 31-EDO: steps ≈ 38.71 cents. MIDI key 61 (C#/Db) nearest semitone varies.
    ;; Whatever the result, yy and zz must always be in [0,127].
    (let [ms  (scala/parse-scl scl-31edo)
          arr (scala/scale->mts-bytes ms)]
      (doseq [k (range 128)]
        (let [base (+ 22 (* k 3))
              yy   (bit-and (aget arr (+ base 1)) 0xFF)
              zz   (bit-and (aget arr (+ base 2)) 0xFF)]
          (is (<= 0 yy 127) (str "key " k " yy out of range"))
          (is (<= 0 zz 127) (str "key " k " zz out of range")))))))
