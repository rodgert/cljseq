; SPDX-License-Identifier: EPL-2.0
(ns cljseq.pattern-test
  "Unit tests for cljseq.pattern — Pattern, Rhythm, motif!, transformations,
  named library, Euclidean bridge."
  (:require [clojure.test    :refer [deftest is testing]]
            [cljseq.chord    :as chord-ns]
            [cljseq.core     :as core]
            [cljseq.live  :as live]
            [cljseq.loop     :as loop-ns]
            [cljseq.pattern  :as pat]
            [cljseq.scale    :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Constructor tests
;; ---------------------------------------------------------------------------

(deftest pattern-constructor-test
  (testing "pattern creates Pattern record with correct ptype and data"
    (let [p (pat/pattern [:chord 1 3 5])]
      (is (= :chord (:ptype p)))
      (is (= [1 3 5] (:data p))))
    (let [p (pat/pattern [:scale 1 3 5 8 5 3])]
      (is (= :scale (:ptype p)))
      (is (= [1 3 5 8 5 3] (:data p))))
    (let [p (pat/pattern [:pitch :C4 :E4 :G4])]
      (is (= :pitch (:ptype p)))
      (is (= [:C4 :E4 :G4] (:data p))))
    (let [p (pat/pattern [:midi 60 64 67])]
      (is (= :midi (:ptype p)))
      (is (= [60 64 67] (:data p)))))

  (testing "pattern rejects unknown type tags"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pat/pattern [:arp 1 2 3]))))

  (testing "pattern rejects empty data"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pat/pattern [:chord])))))

(deftest rhythm-constructor-test
  (testing "rhythm creates Rhythm record with correct data"
    (let [r (pat/rhythm [100 nil 80 nil])]
      (is (= [100 nil 80 nil] (:data r)))))

  (testing "rhythm accepts :tie entries"
    (let [r (pat/rhythm [127 :tie :tie nil 90])]
      (is (= [127 :tie :tie nil 90] (:data r)))))

  (testing "rhythm rejects empty data"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pat/rhythm [])))))

;; ---------------------------------------------------------------------------
;; pat-len / rhy-len / cycle-len
;; ---------------------------------------------------------------------------

(deftest utility-fns-test
  (testing "pat-len returns count of pattern data"
    (is (= 5 (pat/pat-len (pat/pattern [:chord 1 3 5 2 4])))))

  (testing "rhy-len returns count of rhythm data"
    (is (= 4 (pat/rhy-len (pat/rhythm [100 nil 80 nil])))))

  (testing "cycle-len returns lcm of pattern and rhythm lengths"
    (is (= 15 (pat/cycle-len (pat/pattern [:chord 1 3 5 2 4])
                              (pat/rhythm  [100 nil 80]))))
    (is (= 8 (pat/cycle-len (pat/pattern [:midi 60 64])
                             (pat/rhythm  [100 nil 80 nil 90 nil 70 nil]))))
    (is (= 4 (pat/cycle-len (pat/pattern [:midi 60 64 67 60])
                             (pat/rhythm  [100 nil 80 nil]))))))

;; ---------------------------------------------------------------------------
;; MIDI pattern — direct passthrough
;; ---------------------------------------------------------------------------

(deftest motif-midi-pattern-test
  (testing "motif! with :midi pattern sends correct MIDI note numbers"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0]
            (pat/motif! (pat/pattern [:midi 60 64 67])
                        (pat/rhythm  [100 80 70])
                        {:clock-div 1/16})))
        (is (= [60 64 67] @played) "all three MIDI notes played"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Pitch pattern — keyword resolution
;; ---------------------------------------------------------------------------

(deftest motif-pitch-pattern-test
  (testing "motif! with :pitch pattern resolves keywords to MIDI"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0]
            (pat/motif! (pat/pattern [:pitch :C4 :E4 :G4])
                        (pat/rhythm  [100 90 80])
                        {:clock-div 1/16})))
        (is (= [60 64 67] @played)))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Scale-relative pattern
;; ---------------------------------------------------------------------------

(deftest motif-scale-pattern-test
  (testing "motif! with :scale pattern resolves degrees against *harmony-ctx*"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])
            ;; C major, root C4 (MIDI 60): degrees 1,2,3 = C4,D4,E4 = 60,62,64
            c-major (scale-ns/scale :C 4 :major)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*harmony-ctx*  c-major]
            (pat/motif! (pat/pattern [:scale 1 2 3])
                        (pat/rhythm  [100 90 80])
                        {:clock-div 1/16})))
        (is (= [60 62 64] @played) "C major degrees 1 2 3 = C4 D4 E4"))
      (finally (core/stop!)))))

(deftest motif-scale-wrapping-test
  (testing ":scale indices beyond scale size wrap with octave shift"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])
            ;; C major 7 degrees; index 8 = degree 7 (idx 6 in 0-based = 7th degree B4 = 71) + octave? No.
            ;; pitch-at uses floorDiv: degree 7 (0-based) in 7-note scale → octave 1, idx 0 → C5 = 72
            c-major (scale-ns/scale :C 4 :major)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*harmony-ctx*  c-major]
            ;; index 8 (1-based) → 0-based degree 7 → pitch-at wraps: C5 = 72
            (pat/motif! (pat/pattern [:scale 8])
                        (pat/rhythm  [100])
                        {:clock-div 1/16})))
        (is (= [72] @played) "scale index 8 in 7-note C major = C5 (MIDI 72)"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Chord-relative pattern
;; ---------------------------------------------------------------------------

(deftest motif-chord-pattern-test
  (testing "motif! with :chord pattern resolves against *chord-ctx*"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])
            ;; C major triad: C4=60, E4=64, G4=67
            c-major-chord (chord-ns/chord :C 4 :major)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*chord-ctx*    c-major-chord]
            (pat/motif! (pat/pattern [:chord 1 2 3])
                        (pat/rhythm  [100 90 80])
                        {:clock-div 1/16})))
        (is (= [60 64 67] @played) "chord indices 1 2 3 = C4 E4 G4"))
      (finally (core/stop!)))))

(deftest motif-chord-octave-cycling-test
  (testing "chord index beyond chord size cycles upward by octave"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])
            ;; C major triad: [60 64 67]. Index 4 → 0-based 3 → floorDiv(3,3)=1 octave, idx=0 → 60+12=72
            c-major-chord (chord-ns/chord :C 4 :major)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*chord-ctx*    c-major-chord]
            (pat/motif! (pat/pattern [:chord 4])
                        (pat/rhythm  [100])
                        {:clock-div 1/16})))
        (is (= [72] @played) "chord index 4 in triad = root + octave (C5=72)"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Rests and ties
;; ---------------------------------------------------------------------------

(deftest motif-rest-test
  (testing "nil velocity in rhythm causes rest — no note played"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0]
            ;; Pattern 3 notes, rhythm: play, rest, play
            (pat/motif! (pat/pattern [:midi 60 64 67])
                        (pat/rhythm  [100 nil 80])
                        {:clock-div 1/16})))
        (is (= [60 67] @played) "only non-rest steps play notes"))
      (finally (core/stop!)))))

(deftest motif-tie-test
  (testing ":tie velocity causes hold — no retrigger"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0]
            (pat/motif! (pat/pattern [:midi 60 64])
                        (pat/rhythm  [100 :tie])
                        {:clock-div 1/16})))
        (is (= [60] @played) ":tie step does not trigger a new note"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Independent cycling (the NDLR emergent complexity property)
;; ---------------------------------------------------------------------------

(deftest motif-lcm-cycling-test
  (testing "3-note pattern × 2-step rhythm plays lcm(3,2)=6 steps"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0]
            (pat/motif! (pat/pattern [:midi 60 64 67])
                        (pat/rhythm  [100 80])
                        {:clock-div 1/16})))
        ;; 6 steps, all notes (no rests): pattern cycles 60,64,67,60,64,67
        (is (= 6 (count @played)) "lcm(3,2)=6 steps total")
        (is (= [60 64 67 60 64 67] @played) "pattern cycles twice over rhythm's 3"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Probability option
;; ---------------------------------------------------------------------------

(deftest motif-probability-zero-test
  (testing "probability 0.0 → no notes fire, only sleeps"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0]
            (pat/motif! (pat/pattern [:midi 60 64 67])
                        (pat/rhythm  [100 90 80])
                        {:clock-div 1/16 :probability 0.0})))
        (is (empty? @played) "no notes at probability 0"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Context management — use-chord! / with-chord
;; ---------------------------------------------------------------------------

(deftest with-chord-test
  (testing "with-chord binds *chord-ctx* for the scope"
    (let [c (chord-ns/chord :G 3 :dom7)]
      (live/with-chord c
        (is (= c loop-ns/*chord-ctx*) "*chord-ctx* bound inside with-chord")))
    (is (nil? loop-ns/*chord-ctx*) "restored to nil after with-chord")))

(deftest deflive-loop-chord-opt-test
  (testing "deflive-loop :chord opt binds *chord-ctx* inside loop body"
    (core/start! :bpm 60000)
    (try
      (let [c        (chord-ns/chord :F 4 :min7)
            observed (promise)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [& _] nil)
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (core/deflive-loop :chord-opt-test {:chord c}
            (deliver observed loop-ns/*chord-ctx*)
            (core/stop-loop! :chord-opt-test))
          (let [result (deref observed 500 ::timeout)]
            (is (= c result) "*chord-ctx* bound inside loop"))))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; :chromatic ptype
;; ---------------------------------------------------------------------------

(deftest motif-chromatic-pattern-test
  (testing ":chromatic pattern offsets from *harmony-ctx* root"
    (core/start! :bpm 60000)
    (try
      (let [played  (atom [])
            ;; C major root = C4 = MIDI 60
            c-major (scale-ns/scale :C 4 :major)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*harmony-ctx*  c-major]
            ;; offsets 0,4,7 from C4(60) = C4,E4,G4 = 60,64,67
            (pat/motif! (pat/pattern [:chromatic 0 4 7])
                        (pat/rhythm  [100 90 80])
                        {:clock-div 1/16})))
        (is (= [60 64 67] @played) "chromatic 0,4,7 from C4 = C4,E4,G4"))
      (finally (core/stop!)))))

(deftest motif-chromatic-negative-test
  (testing ":chromatic accepts negative offsets (below root)"
    (core/start! :bpm 60000)
    (try
      (let [played  (atom [])
            c-major (scale-ns/scale :C 4 :major)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*harmony-ctx*  c-major]
            ;; offset -2 from C4(60) = Bb3 = 58
            (pat/motif! (pat/pattern [:chromatic -2])
                        (pat/rhythm  [100])
                        {:clock-div 1/16})))
        (is (= [58] @played) "chromatic -2 from C4 = Bb3 (MIDI 58)"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Pattern transformations
;; ---------------------------------------------------------------------------

(deftest reverse-pattern-test
  (testing "reverse-pattern reverses the selector data"
    (let [p  (pat/pattern [:chord 1 2 3 4])
          rp (pat/reverse-pattern p)]
      (is (= :chord (:ptype rp)))
      (is (= [4 3 2 1] (:data rp)))))

  (testing "reverse-pattern on scale pattern"
    (let [p  (pat/pattern [:scale 1 2 3 4 5])
          rp (pat/reverse-pattern p)]
      (is (= [5 4 3 2 1] (:data rp)))))

  (testing "reverse of reverse = original"
    (let [p (pat/pattern [:chord 1 3 5 2 4])]
      (is (= (:data p) (:data (pat/reverse-pattern (pat/reverse-pattern p))))))))

(deftest rotate-pattern-test
  (testing "rotate-pattern right by 1"
    (let [p  (pat/pattern [:chord 1 2 3 4])
          rp (pat/rotate-pattern p 1)]
      (is (= [4 1 2 3] (:data rp)))))

  (testing "rotate-pattern right by 2"
    (let [p  (pat/pattern [:chord 1 2 3 4])
          rp (pat/rotate-pattern p 2)]
      (is (= [3 4 1 2] (:data rp)))))

  (testing "rotate-pattern left by 1 (negative n)"
    (let [p  (pat/pattern [:chord 1 2 3 4])
          rp (pat/rotate-pattern p -1)]
      (is (= [2 3 4 1] (:data rp)))))

  (testing "rotate by full length = identity"
    (let [p (pat/pattern [:chord 1 2 3 4])]
      (is (= (:data p) (:data (pat/rotate-pattern p 4)))))))

(deftest transpose-pattern-test
  (testing "transpose-pattern adds n to all chord indices"
    (let [p  (pat/pattern [:chord 1 2 3])
          tp (pat/transpose-pattern p 2)]
      (is (= :chord (:ptype tp)))
      (is (= [3 4 5] (:data tp)))))

  (testing "transpose-pattern works for scale indices"
    (let [p  (pat/pattern [:scale 1 2 3 4 5])
          tp (pat/transpose-pattern p 3)]
      (is (= [4 5 6 7 8] (:data tp)))))

  (testing "transpose-pattern with negative offset"
    (let [p  (pat/pattern [:chromatic 0 2 4])
          tp (pat/transpose-pattern p -2)]
      (is (= [-2 0 2] (:data tp))))))

(deftest invert-pattern-test
  (testing "invert-pattern reflects linear ascending run"
    ;; [1 2 3 4 5]: lo=1, hi=5, pivot=6 → [5 4 3 2 1]
    (let [p  (pat/pattern [:scale 1 2 3 4 5])
          ip (pat/invert-pattern p)]
      (is (= [5 4 3 2 1] (:data ip)))))

  (testing "invert-pattern on asymmetric pattern"
    ;; [1 3 5]: lo=1, hi=5, pivot=6 → [5 3 1]
    (let [p  (pat/pattern [:chord 1 3 5])
          ip (pat/invert-pattern p)]
      (is (= [5 3 1] (:data ip)))))

  (testing "invert of invert = original"
    (let [p (pat/pattern [:scale 1 2 3 5 7])]
      (is (= (:data p) (:data (pat/invert-pattern (pat/invert-pattern p))))))))

;; ---------------------------------------------------------------------------
;; rhythm-from-euclid
;; ---------------------------------------------------------------------------

(deftest rhythm-from-euclid-test
  (testing "rhythm-from-euclid 3 8 gives tresillo shape"
    ;; euclidean(3,8) = [1 0 0 1 0 0 1 0]
    (let [r (pat/rhythm-from-euclid 3 8)]
      (is (instance? cljseq.pattern.Rhythm r))
      (is (= 8 (count (:data r))))
      ;; 3 onsets (100), 5 rests (nil)
      (is (= 3 (count (filter some? (:data r)))))
      (is (= 5 (count (filter nil? (:data r)))))))

  (testing "rhythm-from-euclid uses custom velocity"
    (let [r (pat/rhythm-from-euclid 3 8 90)]
      (is (every? #(or (nil? %) (= 90 %)) (:data r)))))

  (testing "rhythm-from-euclid with rotation"
    ;; Two different offsets should give different results
    (let [r0 (pat/rhythm-from-euclid 5 8 100 0)
          r1 (pat/rhythm-from-euclid 5 8 100 1)]
      (is (not= (:data r0) (:data r1)) "rotation produces different pattern")))

  (testing "rhythm-from-euclid onset count matches k"
    (doseq [[k n] [[3 8] [5 8] [5 16] [7 16] [4 4]]]
      (let [r (pat/rhythm-from-euclid k n)]
        (is (= k (count (filter some? (:data r))))
            (str "euclid(" k "," n ") has " k " onsets"))))))

;; ---------------------------------------------------------------------------
;; Named pattern library
;; ---------------------------------------------------------------------------

(deftest named-pattern-test
  (testing "named-pattern returns Pattern record for known names"
    (let [p (pat/named-pattern :bounce)]
      (is (instance? cljseq.pattern.Pattern p))
      (is (= :chord (:ptype p))))
    (let [p (pat/named-pattern :scale-up)]
      (is (= :scale (:ptype p)))
      (is (= [1 2 3 4 5 6 7 8] (:data p))))
    (let [p (pat/named-pattern :alberti)]
      (is (= [1 3 2 3] (:data p)) "alberti = root-fifth-third-fifth")))

  (testing "named-pattern throws for unknown names"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pat/named-pattern :no-such-pattern))))

  (testing "pattern-names returns non-empty sorted list"
    (let [names (pat/pattern-names)]
      (is (seq names))
      (is (= names (sort names)) "names are sorted")
      (is (every? keyword? names)))))

;; ---------------------------------------------------------------------------
;; Named rhythm library
;; ---------------------------------------------------------------------------

(deftest named-rhythm-test
  (testing "named-rhythm returns Rhythm record for known names"
    (let [r (pat/named-rhythm :tresillo)]
      (is (instance? cljseq.pattern.Rhythm r))
      (is (= 8 (count (:data r)))))
    (let [r (pat/named-rhythm :waltz)]
      (is (= 3 (count (:data r)))))
    (let [r (pat/named-rhythm :four-on-floor)]
      (is (= 4 (count (:data r))))))

  (testing "named-rhythm :offbeat has onsets only on even (0-indexed) positions"
    (let [r    (pat/named-rhythm :offbeat)
          data (:data r)]
      (is (every? nil?  (take-nth 2 data))       "odd-indexed steps are rests")
      (is (every? some? (take-nth 2 (rest data))) "even-indexed steps have notes")))

  (testing "named-rhythm throws for unknown names"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pat/named-rhythm :no-such-rhythm))))

  (testing "rhythm-names returns non-empty sorted list"
    (let [names (pat/rhythm-names)]
      (is (seq names))
      (is (= names (sort names)))
      (is (every? keyword? names)))))

;; ---------------------------------------------------------------------------
;; Integration: named library + transforms in motif!
;; ---------------------------------------------------------------------------

(deftest named-library-motif-integration-test
  (testing "named-pattern :up played against chord gives ascending tones"
    (core/start! :bpm 60000)
    (try
      (let [played        (atom [])
            c-major-chord (chord-ns/chord :C 4 :major)]   ; [60 64 67]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*chord-ctx*    c-major-chord]
            (pat/motif! (pat/named-pattern :up)
                        (pat/rhythm [100 90 80 70])
                        {:clock-div 1/16})))
        (is (= [60 64 67 72] @played)
            ":up = [1 2 3 4]; index 4 in triad wraps → C5=72"))
      (finally (core/stop!)))))

(deftest reversed-named-pattern-integration-test
  (testing "reverse of :up played against chord gives descending tones"
    (core/start! :bpm 60000)
    (try
      (let [played        (atom [])
            c-major-chord (chord-ns/chord :C 4 :major)]
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*chord-ctx*    c-major-chord]
            (pat/motif! (pat/reverse-pattern (pat/named-pattern :up))
                        (pat/rhythm [100 90 80 70])
                        {:clock-div 1/16})))
        ;; reversed :up = [4 3 2 1] → 72, 67, 64, 60
        (is (= [72 67 64 60] @played)))
      (finally (core/stop!)))))

(deftest euclid-rhythm-in-motif-test
  (testing "rhythm-from-euclid as rhythm driver: onset count matches notes played"
    (core/start! :bpm 60000)
    (try
      (let [played (atom [])
            rhy    (pat/rhythm-from-euclid 5 8)]  ; 5 onsets in 8 steps
        (with-redefs [cljseq.sidecar/connected?    (constantly true)
                      cljseq.sidecar/send-note-on!  (fn [_ _ p _] (swap! played conj p))
                      cljseq.sidecar/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.0]
            ;; 1-note pattern × 8-step rhythm = lcm(1,8)=8 steps, 5 onsets
            (pat/motif! (pat/pattern [:midi 60])
                        rhy
                        {:clock-div 1/16})))
        (is (= 5 (count @played)) "5 notes from 5-in-8 Euclidean rhythm"))
      (finally (core/stop!)))))
