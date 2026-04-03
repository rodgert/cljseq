; SPDX-License-Identifier: EPL-2.0
;;
;; Getting started with cljseq
;; ===========================
;;
;; Load and evaluate forms in your REPL in order.
;; Each section builds on the previous one.
;;
;; Before running: start a Clojure REPL in this project directory.
;;   lein repl
;;   ; or connect your editor's nREPL to a running lein repl server.

;; ---------------------------------------------------------------------------
;; 1. Boot the system
;; ---------------------------------------------------------------------------

(require '[cljseq.user :refer :all])

(session!)              ; start clock at 120 BPM

;; Connect your MIDI interface (find the right index with sidecar/list-midi-ports)
;; (start-sidecar! :midi-port 0)

;; ---------------------------------------------------------------------------
;; 2. Play a single note (Phase 0 stdout mode — no sidecar required)
;; ---------------------------------------------------------------------------

(play! {:pitch/midi 60 :dur/beats 1/2})   ; middle C, half beat

;; Shorthand forms also work:
(play! :C4)             ; keyword → MIDI, default 1/4 beat duration
(play! :G4 1/2)         ; with explicit duration

;; ---------------------------------------------------------------------------
;; 3. A minimal live loop
;; ---------------------------------------------------------------------------

(deflive-loop :pulse {}
  (play! {:pitch/midi 60 :dur/beats 1/4})
  (sleep! 1))

;; Hot-swap: re-evaluate any time to change what plays.
;; The running thread picks up the new body on its next iteration.
(deflive-loop :pulse {}
  (play! {:pitch/midi 64 :dur/beats 1/4})
  (sleep! 1/2))

(stop-loop! :pulse)

;; ---------------------------------------------------------------------------
;; 4. Scale and chord context
;; ---------------------------------------------------------------------------

(require '[cljseq.scale  :as scale]
         '[cljseq.chord  :as chord]
         '[cljseq.dsl    :as dsl]
         '[cljseq.core   :as core])

(def my-scale (make-scale :C 4 :dorian))
(def my-chord (make-chord :C 4 :minor))

;; A loop that walks scale degrees using *harmony-ctx*
(deflive-loop :melody {:harmony my-scale}
  (dsl/scale-degree (rand-nth [1 2 3 4 5]) 1/4)
  (sleep! 1/2))

(stop-loop! :melody)

;; ---------------------------------------------------------------------------
;; 5. Chord voicing + drone — two phase-locked voices
;; ---------------------------------------------------------------------------

(require '[cljseq.voice :as voice])

(let [scale (make-scale :C 4 :major)
      chords (progression scale [:I :IV :V7 :I])]
  (def session-scale  scale)
  (def session-chords chords))

(defn play-bar! [chord]
  (let [root-midi  (first (chord/chord->midis chord))
        pad-midis  (voice/open-position chord)
        start-beat (cljseq.loop/-current-beat)]
    ;; Drone
    (future
      (binding [cljseq.loop/*virtual-time* start-beat
                cljseq.loop/*synth-ctx*    {:midi/channel 1 :mod/velocity 52}]
        (play! {:pitch/midi root-midi :dur/beats 3.8})
        (sleep! 4)))
    ;; Pad
    (future
      (binding [cljseq.loop/*virtual-time* start-beat
                cljseq.loop/*synth-ctx*    {:midi/channel 2 :mod/velocity 65}]
        (dsl/with-dur 3.5
          (play-voicing! pad-midis))
        (sleep! 4)))))

(def chord-ring (atom (cycle session-chords)))

(deflive-loop :progression {}
  (let [chord (first @chord-ring)]
    (swap! chord-ring rest)
    (play-bar! chord)
    (sleep! 4)))

(stop-loop! :progression)

;; ---------------------------------------------------------------------------
;; 6. Pattern + Rhythm — the NDLR motif model
;; ---------------------------------------------------------------------------

(require '[cljseq.pattern :as pat])

;; An 8-note scale run against a tresillo (3-in-8 Euclidean) rhythm.
;; The pattern and rhythm cycle independently — 8 × 8 = 64 steps before
;; repeating.  Different scale degrees land on the pulse each bar.
(def motif-pat (pat/named-pattern :scale-up))
(def motif-rhy (pat/named-rhythm  :tresillo))

(deflive-loop :motif {:harmony (make-scale :C 4 :dorian)
                      :chord   (make-chord :C 4 :minor)}
  (pat/motif! motif-pat motif-rhy {:clock-div 1/8 :gate 0.75 :channel 3})
  (sleep! 0))          ; motif! advances vt internally; sleep! 0 is a no-op

(stop-loop! :motif)

;; Swap the pattern live without stopping:
(def motif-pat (pat/named-pattern :bounce))
(def motif-rhy (pat/named-rhythm  :son-clave))

;; ---------------------------------------------------------------------------
;; 7. Stochastic context — probabilistic melody
;; ---------------------------------------------------------------------------

(require '[cljseq.stochastic :as stoch])

(stoch/defstochastic melody-gen
  :channels   1
  :x-range    [0 6]      ; degree indices 0–6
  :x-steps    0          ; raw integer output
  :x-bias     0.4        ; slight tonic lean
  :x-spread   0.5
  :x-loop-len 8)

(deflive-loop :stoch-melody {:harmony (make-scale :C 4 :dorian)}
  (let [deg   (int (stoch/next-x! melody-gen 0))
        midi  (-> (make-scale :C 4 :dorian)
                  (scale/pitch-at deg)
                  cljseq.pitch/pitch->midi)]
    (play! {:pitch/midi midi :dur/beats 1/4 :midi/channel 3}))
  (sleep! 1/2))

;; Tune DEJA VU live: 0.0 = free wander, 1.0 = locked 8-bar loop
;; (reset! (:x-deja-vu melody-gen) 0.8)
;; (reset! (:x-deja-vu melody-gen) 0.0)

(stop-loop! :stoch-melody)

;; ---------------------------------------------------------------------------
;; 8. End session
;; ---------------------------------------------------------------------------

(end-session!)
