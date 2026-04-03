; SPDX-License-Identifier: EPL-2.0
;;
;; NDLR-like four-voice sequencer driven by the fractal harmonic sequencer
;; =========================================================================
;;
;; Architecture mirrors the Conductive Labs NDLR's four simultaneous voices:
;;
;;   Drone   (ch 1)  — sustained root note; anchors the harmonic centre
;;   Pad     (ch 2)  — open-position chord voicing; sustained for the bar
;;   Motif 1 (ch 3)  — fast melodic arpeggio cycling against chord tones
;;   Motif 2 (ch 4)  — slower bass figure (alberti / root-fifth)
;;
;; The fractal sequencer drives the *harmonic progression* — not individual
;; pitches. Each step in the fractal trunk is a harmonic state:
;;   {:degree :IV :dur/bars 2}
;;
;; The fractal's transforms act on that sequence structurally.  `:reverse`
;; gives us a retrograde of the same progression as branch 1; `:leftmost`
;; path plays trunk then retrograde, giving a palindrome effect.  The motif
;; patterns and Euclidean rhythms are completely unaware of which branch is
;; playing — they just resolve against *harmony-ctx* / *chord-ctx* at the
;; moment of playback.
;;
;; To run (after (core/start! :bpm 120)):
;;   (load-file "examples/ndlr_fractal.clj")
;;   ; -- or evaluate the ns form and forms below in your REPL --
;;   (core/stop-loop! :ndlr)   ; to stop

(ns examples.ndlr-fractal
  (:require [cljseq.chord   :as chord-ns]
            [cljseq.core    :as core]
            [cljseq.dsl     :as dsl]
            [cljseq.fractal :as frac]
            [cljseq.loop    :as loop-ns]
            [cljseq.pattern :as pat]
            [cljseq.scale   :as scale-ns]
            [cljseq.voice   :as voice]))

;; ---------------------------------------------------------------------------
;; 1. Key and base scale
;; ---------------------------------------------------------------------------

(def base-scale
  "C major, rooted at C4.  Change to (scale-ns/scale :D 4 :dorian) etc. live."
  (scale-ns/scale :C 4 :major))

;; ---------------------------------------------------------------------------
;; 2. Fractal harmonic sequencer
;;
;; Trunk encodes the progression as a sequence of harmonic step maps.
;; Each map carries:
;;   :degree   — Roman numeral keyword (:I :II :III :IV :V :V7 :VI :VII etc.)
;;   :dur/bars — how many 4/4 bars to sustain this harmony
;;
;; The :reverse transform produces a second branch that plays the progression
;; backwards (I IV V7 I → I V7 IV I), giving a palindrome when using
;; :leftmost path (trunk then reversed).
;; ---------------------------------------------------------------------------

(frac/deffractal harmony-seq
  :trunk      [{:degree :I  :dur/bars 2}
               {:degree :IV :dur/bars 2}
               {:degree :V7 :dur/bars 1}
               {:degree :I  :dur/bars 1}]
  :transforms [:reverse]    ; branch 1 = retrograde of trunk
  :path       :leftmost     ; [0 1] → trunk, then retrograde, then repeat
  :order      :forward)

;; ---------------------------------------------------------------------------
;; 3. Pattern × Rhythm pairs (NDLR model — each voice has its own pair)
;;
;; Lengths are chosen so cycle-len divides cleanly into 4 beats (1 bar):
;;   Motif 1: 8 selectors × 8 rhythm steps → cycle = 8 × (1/8) = 1 beat
;;   Motif 2: 4 selectors × 4 rhythm steps → cycle = 4 × (1/4) = 1 beat
;; No partial-cycle remainder for any integer number of bars.
;; ---------------------------------------------------------------------------

;; Motif 1 — soprano arpeggio
;; :scale-up cycles through 8 scale degrees; tresillo (3-in-8 Euclidean) gives
;; the rhythmic tension.  The 5-vs-8 independence between an 8-note scale run
;; and a tresillo that only fires on 3 of 8 slots creates a moving melody
;; where different scale degrees land on the pulse each bar.
(def m1-pattern (pat/named-pattern :scale-up))          ; [:scale 1 2 3 4 5 6 7 8]
(def m1-rhythm  (pat/named-rhythm  :tresillo))          ; Euclidean(3,8)

;; Motif 2 — bass figure
;; Alberti-bass shape (root-fifth-third-fifth) at 1/4 clock; the drone already
;; covers the root so the alberti adds the 3rd and 5th as rhythmic movement.
(def m2-pattern (pat/named-pattern :alberti))           ; [:chord 1 3 2 3]
(def m2-rhythm  (pat/rhythm [100 nil 80 nil]))          ; straight quarter-note pulse

;; ---------------------------------------------------------------------------
;; 4. Helpers
;; ---------------------------------------------------------------------------

(defn bars->beats
  "Convert bars to beats (assumes 4/4)."
  [bars]
  (* bars 4))

(defn degree->chord
  "Resolve a Roman numeral keyword to a Chord record against `scale`."
  [scale degree-kw]
  (first (chord-ns/progression scale [degree-kw])))

(defn play-for-beats!
  "Run motif! cycles for exactly `harmonic-beats` of virtual time.
  Plays as many complete lcm-cycles as fit; sleeps the remainder.
  Must be called from a thread with *virtual-time* bound."
  [p r opts harmonic-beats]
  (let [clock-div   (get opts :clock-div 1/8)
        clen        (pat/cycle-len p r)
        cycle-beats (* (double clock-div) clen)
        n-full      (long (/ (double harmonic-beats) cycle-beats))
        remainder   (- (double harmonic-beats) (* n-full cycle-beats))]
    (dotimes [_ n-full]
      (pat/motif! p r opts))
    (when (> remainder 0.001)
      (core/sleep! remainder))))

;; ---------------------------------------------------------------------------
;; 5. Single-step performer
;;
;; Given one harmonic step map from the fractal, this function:
;;   a. Derives the current chord and root MIDI note
;;   b. Captures the current beat as a shared start point for all four voices
;;   c. Spawns four futures — one per NDLR voice — all starting at that beat
;;   d. Blocks until all futures finish (the harmonic step duration)
;;   e. Advances the outer loop's virtual time by harmonic-beats
;;
;; Each future binds *harmony-ctx*, *chord-ctx*, and *virtual-time* freshly
;; from the captured start-beat.  They run concurrently in wall-clock time
;; but are all beat-locked to the same anchor.
;; ---------------------------------------------------------------------------

(defn ndlr-step!
  "Play one harmonic step of the four-voice NDLR sequencer.

  `step`  — {:degree :IV :dur/bars 2}  from (frac/next-step! harmony-seq)
  `scale` — base Scale record"
  [step scale]
  (let [{:keys [degree dur/bars]} step
        harmonic-beats (bars->beats (or bars 2))
        chord          (degree->chord scale (or degree :I))
        root-midi      (first (chord-ns/chord->midis chord))
        pad-midis      (voice/open-position chord)
        start-beat     (loop-ns/-current-beat)]

    ;; ── Drone (ch 1) ───────────────────────────────────────────────────────
    ;; Root note only; velocity kept low to sit underneath the pad.
    ;; Sustained for 95% of the bar so releases just before the chord change.
    (let [drone-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*synth-ctx*    {:midi/channel 1 :mod/velocity 55}]
              (dsl/play! {:pitch/midi root-midi
                          :dur/beats  (* harmonic-beats 0.95)})
              (core/sleep! harmonic-beats)))

          ;; ── Pad (ch 2) ─────────────────────────────────────────────────────
          ;; Open-position voicing (every other tone raised an octave) played
          ;; simultaneously.  with-dur sets the gate for all notes in the block.
          pad-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*synth-ctx*    {:midi/channel 2 :mod/velocity 70}]
              (dsl/with-dur (* harmonic-beats 0.90)
                (dsl/play-voicing! pad-midis))
              (core/sleep! harmonic-beats)))

          ;; ── Motif 1 (ch 3) — scale-up × tresillo ──────────────────────────
          ;; *harmony-ctx* lets motif! resolve :scale indices to actual pitches.
          ;; The tresillo rhythm fires 3 of every 8 slots, leaving gaps that
          ;; let the pad breathe.
          m1-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*harmony-ctx*  scale
                      loop-ns/*chord-ctx*    chord]
              (play-for-beats! m1-pattern m1-rhythm
                               {:clock-div 1/8 :gate 0.80 :channel 3}
                               harmonic-beats)))

          ;; ── Motif 2 (ch 4) — alberti × quarter-note pulse ─────────────────
          ;; *chord-ctx* lets motif! resolve :chord indices to chord tones.
          ;; Root (1) + 3rd (3) + 5th (2 = second chord tone in a triad,
          ;; which is the 5th) cycle against a simple on/off quarter pattern.
          m2-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*harmony-ctx*  scale
                      loop-ns/*chord-ctx*    chord]
              (play-for-beats! m2-pattern m2-rhythm
                               {:clock-div 1/4 :gate 0.85 :channel 4}
                               harmonic-beats)))]

      ;; Block until all four voices finish their harmonic window.
      ;; The wall-clock deadline has already been consumed by the futures.
      (run! deref [drone-future pad-future m1-future m2-future]))

    ;; Advance the outer loop's virtual time.
    ;; The sleep deadline (start-beat + harmonic-beats) is already in the past,
    ;; so park-until-beat! returns immediately — this is pure bookkeeping.
    (core/sleep! harmonic-beats)))

;; ---------------------------------------------------------------------------
;; 6. Live loop
;;
;; The outer loop advances the fractal one step per iteration.
;; Each call to ndlr-step! consumes exactly that step's duration in virtual
;; time, so the loop body naturally self-times without extra sleeps.
;;
;; Hot-swap: re-evaluate any def above (scale, patterns, harmony-seq) and it
;; takes effect on the next harmonic step.  Swap the fractal path live with:
;;   (frac/fractal-path! harmony-seq :random)
;;   (frac/mutate! harmony-seq)           ; reseed transform mutations
;;   (frac/freeze! harmony-seq)           ; lock to current branch
;; ---------------------------------------------------------------------------

(core/deflive-loop :ndlr {}
  (ndlr-step! (frac/next-step! harmony-seq) base-scale))

;; ---------------------------------------------------------------------------
;; 7. Transformation recipes (evaluate these live at the REPL)
;; ---------------------------------------------------------------------------

;; Play the retrograde progression only
;; (frac/fractal-path! harmony-seq [1])

;; Shuffle branch order every time the path repeats
;; (frac/fractal-path! harmony-seq :random)

;; Swap Motif 1 to a bounce arpeggio (takes effect next harmonic step)
;; (def m1-pattern (pat/named-pattern :bounce))
;; (def m1-rhythm  (pat/named-rhythm  :son-clave))

;; Transpose Motif 2 up two chord tones (starts a 3rd higher than root)
;; (def m2-pattern (pat/transpose-pattern (pat/named-pattern :alberti) 1))

;; Invert the scale-up melody (becomes scale-down)
;; (def m1-pattern (pat/invert-pattern (pat/named-pattern :scale-up)))

;; Change key mid-performance
;; (def base-scale (scale-ns/scale :D 4 :dorian))

;; Stop
;; (core/stop-loop! :ndlr)
