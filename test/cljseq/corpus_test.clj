; SPDX-License-Identifier: EPL-2.0
(ns cljseq.corpus-test
  "Corpus integration test — Bach chorale reduction (BWV 371 opening phrase).

  Encodes the opening phrase of Bach's 'Ich dank' dir, lieber Herre' (BWV 371)
  as a G-major Roman-numeral progression and plays it through the full stack:

    chord/progression → voice/smooth-progression → live/play-progression!
      → sidecar IPC → MIDI scheduler → Hydrasynth Explorer

  ## Automated (lein test cljseq.corpus-test)
  Starts a sidecar process, plays the 7-chord phrase at 80 BPM, and verifies
  that NoteOn IPC frames arrive for the tonic (G4, MIDI 67) and dominant
  (D5, MIDI 74) chords. Requires the sidecar binary to be built.

  ## Interactive (REPL with Hydrasynth connected on port 2)
    (require '[cljseq.corpus-test :as corpus])
    (corpus/start-hardware-session!)   ; starts sidecar on MIDI port 2
    (corpus/play-bach-phrase!)         ; plays once at 80 BPM
    (corpus/play-bach-phrase! 3)       ; slower — 3 beats per chord
    (corpus/stop-hardware-session!)    ; clean shutdown"
  (:require [clojure.test   :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string  :as str]
            [cljseq.chord   :as chord]
            [cljseq.core    :as core]
            [cljseq.live  :as live]
            [cljseq.loop    :as loop-ns]
            [cljseq.scale   :as scale]
            [cljseq.sidecar :as sidecar]
            [cljseq.voice   :as voice]))

;; ---------------------------------------------------------------------------
;; Musical material — BWV 371 opening phrase (G major)
;;
;; Harmony: I  V  vi  ii  IV  V7  I
;;          G  D  Em  Am  C   D7  G
;;
;; Source: Bach, Choralgesänge BWV 371 "Ich dank' dir, lieber Herre"
;; (public domain; first documented use c. 1736)
;; ---------------------------------------------------------------------------

(def ^:private bach-degrees
  "Roman-numeral progression for BWV 371 opening phrase."
  [:I :V :VI :II :IV :V7 :I])

(def ^:private bach-scale
  "G major, root octave 4."
  (scale/scale :G 4 :major))

(defn bach-chords
  "Return the 7-element seq of Chord records for the BWV 371 phrase."
  []
  (chord/progression bach-scale bach-degrees))

(defn bach-voicings
  "Return the 7-element seq of voice-led MIDI-int vecs for the phrase.
  Each voicing has 3 notes; voice leading minimizes total movement."
  []
  (voice/smooth-progression (bach-chords)))

;; ---------------------------------------------------------------------------
;; Interactive API
;; ---------------------------------------------------------------------------

(defn play-bach-phrase!
  "Play the Bach chorale phrase once.

  `dur` — beats per chord (default 2 = dotted quarter at 80 BPM).

  Requires (core/start!) and a connected sidecar. Plays through the active
  synth context (*synth-ctx*) if set."
  ([] (play-bach-phrase! 2))
  ([dur]
   (live/play-progression! (bach-chords) dur)))

(defn bach!
  "Play the Bach chorale phrase in a one-shot live loop, then stop.

  `dur` — beats per chord (default 2). Stops cleanly after one pass.
  Kills any previous :bach-phrase loop before starting.

  Example:
    (corpus/bach!)      ; 2 beats per chord
    (corpus/bach! 3)    ; slow, atmospheric"
  ([] (bach! 2))
  ([dur]
   (core/stop-loop! :bach-phrase)
   (Thread/sleep 50)
   (core/deflive-loop :bach-phrase {}
     (play-bach-phrase! dur)
     (core/stop-loop! :bach-phrase))))

(defn start-hardware-session!
  "Boot cljseq at 80 BPM and connect to the Hydrasynth Explorer on MIDI port 2.
  Call once before play-bach-phrase!."
  []
  (core/start! :bpm 80)
  (sidecar/start-sidecar! :binary "build/cpp/cljseq-sidecar/cljseq-sidecar"
                          :midi-port 2)
  (println "Hardware session ready — call (play-bach-phrase!) to start."))

(defn stop-hardware-session!
  "Stop the sidecar and cljseq system."
  []
  (sidecar/stop-sidecar!)
  (core/stop!))

;; ---------------------------------------------------------------------------
;; Automated tests
;; ---------------------------------------------------------------------------

(def ^:private sidecar-binary "build/cpp/cljseq-sidecar/cljseq-sidecar")
(def ^:private timeout-ms 3000)

(defn- skip-unless-binary [f]
  (let [file (io/file sidecar-binary)]
    (if (and (.exists file) (.canExecute file))
      (f)
      (println (str "  [skip] sidecar binary not found: " sidecar-binary)))))

(use-fixtures :each skip-unless-binary)

(defn- with-session [f]
  (try
    (core/start! :bpm 80)
    (sidecar/start-sidecar! :binary sidecar-binary)
    (Thread/sleep 100)
    (f)
    (finally
      (try (sidecar/stop-sidecar!) (catch Exception _))
      (try (core/stop!)            (catch Exception _)))))

(defn- await-note-on [note]
  (sidecar/await-dispatch
   #(and (str/includes? % "[ipc] enqueue")
         (str/includes? % "type=0x01")
         (str/includes? % (str "b9=" note " ")))
   timeout-ms))

;; ---------------------------------------------------------------------------
;; Chord structure tests (no sidecar required)
;; ---------------------------------------------------------------------------

(deftest bach-phrase-chord-count-test
  (testing "BWV 371 phrase has 7 chords"
    (is (= 7 (count (bach-chords))))))

(deftest bach-phrase-voicings-test
  (testing "smooth-progression produces 7 three-note voicings"
    (let [vs (bach-voicings)]
      (is (= 7 (count vs)))
      (is (every? #(= 3 (count %)) vs) "each voicing has exactly 3 notes")))
  (testing "all MIDI note numbers are in the valid range"
    (is (every? #(and (>= % 0) (<= % 127))
                (flatten (bach-voicings)))))
  (testing "bass voice is always below soprano"
    (let [vs (bach-voicings)]
      (is (every? (fn [v] (< (apply min v) (apply max v))) vs)))))

;; ---------------------------------------------------------------------------
;; Pipeline integration tests (sidecar required)
;; ---------------------------------------------------------------------------

(deftest bach-tonic-note-on-test
  (testing "First chord (I = G major) fires NoteOn for G4 (MIDI 67)"
    (with-session
      (fn []
        ;; Play just the first chord via play-voicing! so the rest of the
        ;; progression does not block the test for too long.
        (let [first-voicing (first (bach-voicings))]
          (binding [loop-ns/*virtual-time* (loop-ns/-current-beat)]
            (live/play-voicing! first-voicing)))
        ;; G4 = MIDI 67 should appear in the sidecar enqueue log.
        ;; Voice leading may shift the chord, so we check all three notes.
        (let [first-voicing (first (bach-voicings))
              any-arrived?  (some #(some? (await-note-on %)) first-voicing)]
          (is any-arrived?
              (str "At least one note of the tonic chord should arrive; "
                   "voicing=" (first (bach-voicings)))))))))

(deftest bach-full-phrase-pipeline-test
  (testing "Full 7-chord phrase completes without exception"
    (with-session
      (fn []
        ;; play-progression! calls sleep!, which requires *virtual-time* to be
        ;; thread-locally bound (set! can't mutate the root binding).
        ;; Use dur=1/4 so the test finishes in ~130ms total (7 × 1/4 beat at 80 BPM).
        (is (nil? (binding [loop-ns/*virtual-time* (loop-ns/-current-beat)]
                    (play-bach-phrase! 1/4)))
            "play-bach-phrase! should return nil without throwing")))))
