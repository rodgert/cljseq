; SPDX-License-Identifier: EPL-2.0
;;
;; cljseq Rosetta Stone — Sonic-Pi and Overtone vocabulary in cljseq
;;
;; This file shows how common live-coding patterns from Sonic-Pi and Overtone
;; are expressed in cljseq. Read each section as a side-by-side translation.
;;
;; cljseq diverges from both systems in key ways:
;;   - Music theory is first-class data (scales, chords, intervals as values)
;;   - Device/synth targets are resolved at session time, not hardcoded
;;   - Synthesis graphs are Clojure data — inspectable, transformable, serializable
;;   - The score is a value — homoiconic, composable with standard Clojure
;;
;; Vocabulary mapping summary:
;;
;;   Sonic-Pi              Overtone                cljseq
;;   --------              --------                ------
;;   play N                (inst freq)             (play! {:pitch/midi N})
;;   play :C4              (note :C4)              (play! :C4)
;;   sleep N               (Thread/sleep ms)       (sleep! N)           ; beats
;;   live_loop :name       (def-loop name ...)     (deflive-loop :name {} ...)
;;   use_bpm N             (metro-bpm metro N)     (set-bpm! N)
;;   use_synth :blade      (definst blade ...)     (use-synth! :blade)  ; SC session
;;   chord :C, :major      (chord :C4 :major)      (make-chord :C 4 :major)
;;   scale :C4, :major     (scale :C4 :major)      (make-scale :C 4 :major)
;;   choose [...]          (choose [...])           (rand-nth [...])
;;   rrand lo hi           (ranged-rand lo hi)     (+ lo (rand (- hi lo)))
;;   sample :bd_haus       —                       (play! {:pitch/midi 36}) + device map
;;   with_fx :reverb       —                       trajectory + device-send! CC

(require '[cljseq.user :refer :all])

;; ============================================================================
;; 1. Playing a single note
;; ============================================================================

;; Sonic-Pi:
;;   play 60
;;   play :C4

;; Overtone:
;;   (demo 2 (sin-osc 261.63))
;;   (my-inst :freq (midi->hz 60))

;; cljseq:
(play! {:pitch/midi 60})
(play! :C4)                            ; note name syntax
(play! :C4 1/2)                        ; note keyword + explicit duration (beats)
(play! {:pitch/midi 60 :mod/velocity 80 :dur/beats 1})

;; With an SC synth (after connect-sc! and send-synthdef!):
;; (sc-play! {:synth :blade :freq 261.63 :amp 0.5 :dur-ms 500})

;; ============================================================================
;; 2. Tempo and timing
;; ============================================================================

;; Sonic-Pi:
;;   use_bpm 120
;;   sleep 1       ; 1 beat
;;   sleep 0.5     ; half beat

;; Overtone:
;;   (def metro (metronome 120))
;;   (Thread/sleep 500)  ; ms — no beat abstraction

;; cljseq:
(set-bpm! 120)

;; Inside a live loop, sleep! takes beats (quarter notes at current BPM):
(deflive-loop :timing-example {}
  (play! :C4)
  (sleep! 1)      ; 1 beat
  (play! :E4)
  (sleep! 1/2)    ; half beat (eighth note)
  (play! :G4)
  (sleep! 1/2))

;; ============================================================================
;; 3. Live loops
;; ============================================================================

;; Sonic-Pi:
;;   live_loop :beat do
;;     sample :bd_haus
;;     sleep 1
;;   end

;; Overtone:
;;   (def metro (metronome 120))
;;   (defn beat-loop [beat]
;;     (at (metro beat) (kick))
;;     (apply-at (metro (inc beat)) beat-loop (inc beat) []))
;;   (beat-loop (metro))

;; cljseq:
(deflive-loop :beat {}
  (play! {:pitch/midi 36 :dur/beats 1/4})   ; kick (MIDI note 36)
  (sleep! 1))

;; Hot-swap: redefine while running — new body takes effect next iteration
(deflive-loop :beat {}
  (play! {:pitch/midi 38 :dur/beats 1/4})   ; swap to snare
  (sleep! 1/2))

;; Stop a loop
(stop-loop! :beat)

;; ============================================================================
;; 4. Scales and melody
;; ============================================================================

;; Sonic-Pi:
;;   use_scale :minor
;;   play scale(:c4, :major).choose
;;   play_pattern_timed scale(:c4, :major), [0.25, 0.25, 0.25, 0.25]

;; Overtone:
;;   (def s (scale :C4 :major))
;;   (inst :freq (midi->hz (choose s)))

;; cljseq — scale is a first-class value:
(def my-scale (make-scale :C 4 :major))
(def my-minor (make-scale :A 3 :minor))
(def my-dorian (make-scale :D 4 :dorian))

;; Play random notes from a scale in a loop:
(deflive-loop :melody {}
  (with-harmony my-scale
    (play! (use-harmony! my-scale)))
  (sleep! 1/4))

;; Play ascending scale using dsl/phrase!:
(deflive-loop :ascending {}
  (phrase! [1/4 1/4 1/4 1/4 1/4 1/4 1/4 1/2]
           (range 8))    ; degrees 0–7
  (sleep! 4))

;; ============================================================================
;; 5. Chords
;; ============================================================================

;; Sonic-Pi:
;;   play chord(:C, :major)
;;   play chord(:C, :minor7)

;; Overtone:
;;   (chord :C4 :major)
;;   (doseq [note (chord :C4 :major)] (inst :freq (midi->hz note)))

;; cljseq:
(def cmaj  (make-chord :C 4 :major))
(def cmin7 (make-chord :C 4 :minor7))
(def g7    (make-chord :G 3 :dominant7))

(play-chord! cmaj)                             ; all notes simultaneously
(play-voicing! cmaj {:style :spread})          ; with voice leading
(arp! cmin7 {:pattern :up :dur/beats 1/4})    ; arpeggiated

;; ii-V-I progression as values:
(def progression (progression my-scale [:ii :V7 :I]))
;; progression is a seq of Chord records — standard Clojure, no special API

;; ============================================================================
;; 6. Randomization and generative patterns
;; ============================================================================

;; Sonic-Pi:
;;   play choose([60, 62, 64, 67, 69])
;;   play rrand_i(60, 72)
;;   play scale(:c4, :major).choose

;; Overtone:
;;   (def notes [60 62 64 67 69])
;;   (inst :freq (midi->hz (rand-nth notes)))

;; cljseq — same Clojure idioms, no special API:
(deflive-loop :random-melody {}
  (play! {:pitch/midi (rand-nth [60 62 64 67 69])})
  (sleep! 1/4))

;; Stochastic sequence (density + scale-weighted):
(deflive-loop :stochastic-melody {}
  (with-harmony my-dorian
    (let [note (use-harmony! my-dorian)]
      (when (> (rand) 0.3)               ; 70% chance of playing
        (play! note))))
  (sleep! 1/4))

;; Euclidean rhythm (requires cljseq.stochastic aliased as stoch):
;;   (require '[cljseq.stochastic :as stoch])
(deflive-loop :euclidean-kick {}
  (let [pattern (stoch/stochastic-rhythm {:pulses 5 :steps 16})]
    (doseq [beat pattern]
      (when (= 1 beat)
        (play! {:pitch/midi 36 :dur/beats 1/16}))
      (sleep! 1/16))))

;; ============================================================================
;; 7. Synth selection and parameters
;; ============================================================================

;; Sonic-Pi:
;;   use_synth :blade
;;   play 60, attack: 0.1, release: 2
;;   use_synth :prophet
;;   play 60, cutoff: 80, res: 0.3

;; Overtone:
;;   (definst blade [freq 440 amp 0.5 attack 0.1 release 2.0] ...)
;;   (blade :freq 440 :attack 0.1 :release 2.0)

;; cljseq — synth is session config; graph is data:
;;   (require '[cljseq.sc :as sc])
;;   (sc/connect-sc!)
;;   (sc/send-synthdef! :blade)
;;   (sc/send-synthdef! :prophet)
;;
;;   (sc/sc-play! {:synth :blade  :freq 440 :attack 0.1 :release 2.0 :dur-ms 2000})
;;   (sc/sc-play! {:synth :prophet :freq 440 :cutoff 80 :res 0.3 :dur-ms 1000})

;; Inspect a synth's graph — not possible in Sonic-Pi or Overtone:
(get-synth :blade)                         ; => full map with :args and :graph
(synth-ugens (get-synth :blade))           ; => #{:out :pan2 :lpf :env-gen :adsr :mix :var-saw :*}
(compile-synth :sc :blade)                 ; => sclang SynthDef string

;; Transform a synth — also not possible in Sonic-Pi or Overtone:
(-> (get-synth :prophet)
    (transpose-synth 12)                   ; one octave up
    (replace-arg :cutoff 90)               ; brighter filter
    (scale-amp 0.8))                       ; slightly quieter

;; ============================================================================
;; 8. Harmony context — cljseq-specific
;; ============================================================================

;; No direct equivalent in Sonic-Pi or Overtone.
;; cljseq propagates a harmony context through live loops dynamically.

(use-harmony! (make-scale :D 4 :dorian))

;; All loops that read *harmony-ctx* automatically use the new scale:
(deflive-loop :harmony-aware {}
  (with-harmony (make-scale :D 4 :dorian)
    (play! (use-harmony! (make-scale :D 4 :dorian))))
  (sleep! 1/4))

;; Chord constrains melody:
(use-chord! (make-chord :D 4 :minor7))

;; ============================================================================
;; 9. FX and device control
;; ============================================================================

;; Sonic-Pi:
;;   with_fx :reverb, mix: 0.5 do
;;     play 60
;;   end

;; Overtone:
;;   (def rev (freeverb :room 0.5))   ; audio graph node

;; cljseq — FX is device control via MIDI CC or OSC trajectory:
;;   (device-send! :strymon/nightsky [:mix] 64)     ; reverb mix CC
;;   (device-send! :boss/rv-500 [:engine-a :time] 80)

;; Trajectory automation — ramp reverb mix over 8 bars via MIDI CC:
;; (requires device map for :strymon/nightsky; see doc/user-manual.md §Device EDN)
;;   (deflive-loop :reverb-swell {}
;;     (let [t (/ (mod (now) 8) 8.0)]     ; 0.0→1.0 over 8 bars
;;       (send-cc! 1 91 (int (* 127 t)))) ; CC 91 = reverb send on channel 1
;;     (sleep! 1/4))

;; Or: apply-trajectory! drives an SC FX node continuously
;; (let [reverb-node (sc-synth! :free-verb {:mix 0.0 :room 0.5})]
;;   (apply-trajectory! reverb-node :mix
;;     (trajectory :from 0.0 :to 0.8 :beats 32 :curve :s-curve :start (now))))

;; ============================================================================
;; 10. Analysis — cljseq-specific
;; ============================================================================

;; No equivalent in Sonic-Pi. Overtone has some pitch analysis.
;; cljseq: music theory as computation on values.

(def prog [(make-chord :C 4 :major)
           (make-chord :A 3 :minor)
           (make-chord :F 3 :major)
           (make-chord :G 3 :dominant7)])

;; Analyze a progression:
(analyze-progression prog (make-scale :C 4 :major))
;; => [{:chord C-maj :roman "I"   :tension 0.1 :borrowed? false} ...]

;; Detect key from a MIDI note sequence:
(detect-key [60 62 64 65 67 69 71])
;; => {:key :C :mode :major :confidence 0.94}

;; Suggest what comes next:
(suggest-progression (make-scale :C 4 :major) {:target-tension 0.8})
;; => [(make-chord :G 3 :dominant7) (make-chord :B 3 :dim)]

;; ============================================================================
;; 11. Pattern sequencing
;; ============================================================================

;; Sonic-Pi:
;;   play_pattern_timed [60,62,64,65], [0.25]
;;   play_pattern_timed (ring 60,62,64,65), [0.25,0.5], release: 0.1

;; Overtone:
;;   (doseq [[note dur] (partition 2 [60 0.5 62 0.5 64 1.0])]
;;     (at (metro beat) (inst :freq (midi->hz note)))
;;     (swap! beat + dur))

;; cljseq:
(deflive-loop :pattern {}
  (phrase! [1/4 1/4 1/4 1/2 1/4]        ; durations
           [60  62  64  65  67])          ; MIDI pitches
  (sleep! 2))

;; Ring (looping sequence) — same as Sonic-Pi ring:
(def riff (ring [60 62 64 67 69 67 64 62]))
(deflive-loop :riff-loop {}
  (play! {:pitch/midi (tick! riff)})
  (sleep! 1/4))

;; ============================================================================
;; 12. Note transformers — cljseq-specific
;; ============================================================================

;; No direct equivalent in Sonic-Pi or Overtone.

;; Echo: each note generates diminishing repeats
(deflive-loop :echo-melody {}
  (play-transformed!
    {:pitch/midi 60 :dur/beats 1/4}
    (echo 3 0.7 1/4))                    ; 3 repeats, 70% decay, 1/4 beat apart
  (sleep! 2))

;; Strum a chord across time
(deflive-loop :strummed {}
  (play-transformed!
    {:pitch/midi 60 :dur/beats 1}
    (compose-xf
      (harmonize [4 7])                  ; add major third and fifth
      (strum :down 0.03)))               ; strum downward, 30ms spread
  (sleep! 2))

;; ============================================================================
;; 13. Multi-loop ensemble — cljseq-specific
;; ============================================================================

;; Sonic-Pi uses multiple live_loops independently.
;; cljseq adds ensemble awareness: loops share harmony context.

(deflive-loop :bass {}
  (play! {:pitch/midi 36 :dur/beats 1})
  (sleep! 1))

(deflive-loop :chords {}
  (play-chord! (make-chord :C 4 :major))
  (sleep! 2))

(deflive-loop :lead {}
  (with-harmony (make-scale :C 4 :pentatonic-minor)
    (play! (use-harmony! (make-scale :C 4 :pentatonic-minor))))
  (sleep! 1/4))

;; Update harmony for all loops at once:
(use-harmony! (make-scale :A 3 :minor))  ; all loops that read *harmony-ctx* respond
