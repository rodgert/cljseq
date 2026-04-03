; SPDX-License-Identifier: EPL-2.0
;;
;; NDLR-like four-voice sequencer driven by the stochastic sequencer
;; ================================================================
;;
;; Architecture mirrors the fractal example (ndlr_fractal.clj) but replaces
;; the deterministic fractal harmonic sequencer with a probabilistic stochastic
;; engine. The contrast in character is the point:
;;
;;   Fractal    → structured, architectural, predetermined phrase grammar
;;   Stochastic → organic, improvised, probability-shaped harmony + rhythm
;;
;; Two stochastic contexts do distinct jobs:
;;
;;   harmonic-walk   — X-section selects a scale degree each harmonic step.
;;                     DEJA VU controls harmonic memory: near 1.0 the system
;;                     stays on a chord; near 0.0 it wanders freely.
;;
;;   motif-gen       — T-section gates note firings across two channels using
;;                     :complementary-bernoulli. When Motif 1 fires, Motif 2
;;                     is silent, and vice-versa — automatic call-and-response.
;;                     DEJA VU controls whether the call-response pattern locks
;;                     into a groove or stays unpredictable.
;;
;; Note content still comes from the Pattern library (chord/scale-relative
;; selectors), preserving the NDLR's harmony-tracking property. The stochastic
;; controls *when* notes fire and *which chord* is current — not raw pitches.
;;
;; Voice / channel assignments:
;;   ch 1 — Drone   (sustained root; anchors the harmony)
;;   ch 2 — Pad     (open-position chord voicing)
;;   ch 3 — Motif 1 (T-channel 0: fires when coin=heads)
;;   ch 4 — Motif 2 (T-channel 1: fires when coin=tails — complementary to M1)
;;
;; Key live controls (evaluate at the REPL while the loop is running):
;;
;;   ;; Make harmony drift freely (no memory)
;;   (reset! (:x-deja-vu harmonic-walk) 0.0)
;;
;;   ;; Lock harmony to a repeating 4-bar pattern
;;   (reset! (:x-deja-vu harmonic-walk) 0.95)
;;
;;   ;; Lock M1/M2 call-response into a repeating groove
;;   (reset! (:t-deja-vu motif-gen) 0.9)
;;
;;   ;; Release groove, return to random gating
;;   (reset! (:t-deja-vu motif-gen) 0.0)
;;
;;   ;; Bias harmony toward tonic (low degrees)
;;   (swap! harmonic-bias (constantly 0.2))
;;
;;   ;; Release tonic bias — wander the full modal space
;;   (swap! harmonic-bias (constantly 0.5))
;;
;;   ;; Stop
;;   (core/stop-loop! :ndlr-stochastic)

(ns examples.ndlr-stochastic
  (:require [cljseq.chord      :as chord-ns]
            [cljseq.core       :as core]
            [cljseq.dsl        :as dsl]
            [cljseq.loop       :as loop-ns]
            [cljseq.pattern    :as pat]
            [cljseq.random     :as random]
            [cljseq.scale      :as scale-ns]
            [cljseq.stochastic :as stoch]
            [cljseq.voice      :as voice]))

;; ---------------------------------------------------------------------------
;; 1. Key, scale, and harmonic quality table
;; ---------------------------------------------------------------------------

(def base-scale
  "C dorian — the modal colour the stochastic will wander through.
  Change live: (def base-scale (scale-ns/scale :D 4 :phrygian))"
  (scale-ns/scale :C 4 :dorian))

;; Maps diatonic scale degree (0-based) to a chord quality appropriate for
;; that mode.  Dorian: i ii♭III IV v vi♭ VII
(def degree->quality
  {0 :minor          ; i    (tonic minor)
   1 :minor          ; ii   (supertonic minor)
   2 :major          ; ♭III (mediant major)
   3 :major          ; IV   (subdominant major — the Dorian signature chord)
   4 :minor          ; v    (dominant minor)
   5 :half-dim7      ; vi°  (submediant)
   6 :major})        ; ♭VII (subtonic major)

;; ---------------------------------------------------------------------------
;; 2. Stochastic contexts
;; ---------------------------------------------------------------------------

;; Harmonic walk — X-section picks which scale degree is "active" this bar.
;;
;; x-range [0 6]: seven diatonic degrees.
;; x-steps 0:     raw mode — outputs integers 0–6, no scale quantization.
;; x-bias 0.35:   slight tonic/subdominant lean (IV = the Dorian colour chord).
;; x-loop-len 4:  4-step ring — DEJA VU near 1.0 ≈ 4-bar harmonic loop.
;; t-bias 0.0:    T-section unused for harmonic walk (we always draw X).

(def harmonic-bias (atom 0.35))   ; live-tweakable harmonic centre of gravity

(stoch/defstochastic harmonic-walk
  :channels   1
  :x-range    [0 6]
  :x-steps    0            ; raw integer output — no scale quantization
  :x-bias     0.35         ; note: x-bias is fixed at construction time
  :x-spread   0.55         ; moderate spread across the modal space
  :x-loop-len 4)           ; 4-step harmonic memory ring

;; Motif generators — T-section provides correlated two-channel gating.
;;
;; :complementary-bernoulli means: one coin flip per step.
;;   heads → T-channel 0 fires (Motif 1 plays), T-channel 1 silent
;;   tails → T-channel 1 fires (Motif 2 plays), T-channel 0 silent
;;
;; This enforces non-overlapping call-and-response between the two motifs.
;; t-bias 0.6: Motif 1 gets the "heads" so fires ~60% of steps.
;; t-loop-len 8: DEJA VU near 1.0 locks an 8-step call-response groove.

(stoch/defstochastic motif-gen
  :channels   2
  :t-mode     :complementary-bernoulli
  :t-bias     0.6
  :t-loop-len 8
  ;; X-section unused here — note content comes from Pattern records
  :x-range    [0 1])

;; ---------------------------------------------------------------------------
;; 3. Patterns (NDLR model — harmony-relative, no raw MIDI)
;; ---------------------------------------------------------------------------

;; Motif 1 — ascending scale run (resolves against *harmony-ctx*)
;; With stochastic gating, only 3–5 of the 8 degrees typically fire per bar —
;; the result sounds like a sparse, improvised scale fragment.
(def m1-pattern (pat/named-pattern :scale-up))        ; [:scale 1 2 3 4 5 6 7 8]

;; Motif 2 — chord arpeggio (resolves against *chord-ctx*)
;; When Motif 2 fires (complementary tails), it roots the listener back in
;; the harmony after Motif 1's scale excursion.
(def m2-pattern (pat/named-pattern :bounce))          ; [:chord 1 2 3 4 3 2]

;; ---------------------------------------------------------------------------
;; 4. Helpers
;; ---------------------------------------------------------------------------

(defn bars->beats [bars] (* bars 4))

(defn degree->chord
  "Derive a Chord from a scale degree index and the degree->quality table."
  [scale deg-idx]
  (let [quality (get degree->quality deg-idx :minor)]
    (chord-ns/chord-at-degree scale deg-idx quality)))

(defn stochastic-rhythms!
  "Draw n gate decisions for two complementary channels in a single interleaved
  pass, preserving the complementary-bernoulli correlation.

  Returns a vector of two Rhythm records: [rhy-ch0 rhy-ch1].

  IMPORTANT: channels must be drawn in order 0, 1 per step — calling ch-0
  advances the shared coin flip; ch-1 reads the cached complement.
  A velocity of `vel` is used for fired slots; nil for silent."
  [gen n vel]
  (let [pairs (mapv (fn [_]
                      [(when (stoch/next-t! gen 0) vel)
                       (when (stoch/next-t! gen 1) vel)])
                    (range n))]
    [(pat/rhythm (mapv first pairs))
     (pat/rhythm (mapv second pairs))]))

(defn play-stochastic-cycles!
  "Play `n-cycles` of `pat` × stochastic rhythm (drawn from `gen` channel `ch`)
  advancing virtual time by n-cycles × cycle-beats.

  Each call to stochastic-rhythms! draws a fresh Rhythm from the T-section —
  DEJA VU means high deja-vu values repeat the same gate pattern (groove lock)
  while low values produce a new pattern each cycle."
  [pat gen ch n-cycles opts]
  (let [pcnt     (pat/pat-len pat)
        n-steps  pcnt          ; one rhythm per pattern length for cleaner pairing
        vel      100]
    (dotimes [_ n-cycles]
      ;; Draw both channels together to maintain complementary correlation,
      ;; then pick the one we care about.
      (let [[rhy-0 rhy-1] (stochastic-rhythms! gen n-steps vel)
            rhy           (if (zero? ch) rhy-0 rhy-1)]
        (pat/motif! pat rhy opts)))))

(defn play-for-beats-stochastic!
  "Run stochastic motif cycles for exactly `harmonic-beats` of virtual time.
  Plays n full cycles then sleeps any remainder."
  [pat gen ch opts harmonic-beats]
  (let [clock-div   (get opts :clock-div 1/8)
        clen        (pat/pat-len pat)          ; one rhythm drawn per pattern length
        cycle-beats (* (double clock-div) clen)
        n-full      (long (/ (double harmonic-beats) cycle-beats))
        remainder   (- (double harmonic-beats) (* n-full cycle-beats))]
    (play-stochastic-cycles! pat gen ch n-full opts)
    (when (> remainder 0.001)
      (core/sleep! remainder))))

;; ---------------------------------------------------------------------------
;; 5. Single harmonic step performer
;;
;; Reads the next scale degree from harmonic-walk's X-section each iteration.
;; The drawn degree feeds into the chord derivation and becomes *chord-ctx*
;; for the duration of that harmonic step.
;;
;; The x-deja-vu atom controls harmonic memory:
;;   low  → wanders across the mode, different chord every bar
;;   high → revisits recent degrees, creating tonal centres and phrase shape
;; ---------------------------------------------------------------------------

(defn ndlr-stochastic-step!
  "Play one harmonic step: derive chord from stochastic degree, spawn 4 voices."
  [bars base-scale]
  (let [harmonic-beats  (bars->beats bars)
        ;; Draw next scale degree from X-section (0–6 integer)
        raw-degree      (stoch/next-x! harmonic-walk 0)
        degree-idx      (int (max 0 (min 6 raw-degree)))
        chord           (degree->chord base-scale degree-idx)
        root-midi       (first (chord-ns/chord->midis chord))
        pad-midis       (voice/open-position chord)
        start-beat      (loop-ns/-current-beat)]

    ;; ── Drone (ch 1) ───────────────────────────────────────────────────────
    ;; Root is always the drawn degree's root — traces the harmonic walk as a
    ;; sustained bass note. Velocity kept low to sit under the pad.
    (let [drone-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*synth-ctx*    {:midi/channel 1 :mod/velocity 50}]
              (dsl/play! {:pitch/midi root-midi
                          :dur/beats  (* harmonic-beats 0.95)})
              (core/sleep! harmonic-beats)))

          ;; ── Pad (ch 2) ─────────────────────────────────────────────────────
          ;; Open-position voicing of the stochastically-chosen chord.
          ;; Velocity 65 — present but below the motifs.
          pad-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*synth-ctx*    {:midi/channel 2 :mod/velocity 65}]
              (dsl/with-dur (* harmonic-beats 0.88)
                (dsl/play-voicing! pad-midis))
              (core/sleep! harmonic-beats)))

          ;; ── Motif 1 (ch 3) — scale-run, stochastic gate, T-ch 0 ───────────
          ;; *harmony-ctx* resolves :scale indices to actual pitches.
          ;; The T-section draws determine which of the 8 scale steps fires.
          ;; When t-deja-vu is high, the sparse scale fragment repeats as a motif.
          m1-future
          (future
            (binding [loop-ns/*virtual-time*  start-beat
                      loop-ns/*harmony-ctx*   base-scale
                      loop-ns/*chord-ctx*     chord]
              (play-for-beats-stochastic!
                m1-pattern motif-gen 0
                {:clock-div 1/8 :gate 0.75 :channel 3}
                harmonic-beats)))

          ;; ── Motif 2 (ch 4) — chord bounce, complementary gate, T-ch 1 ─────
          ;; *chord-ctx* resolves :chord indices to chord tones.
          ;; Fires when Motif 1 is silent — call-and-response woven by probability.
          ;; Pattern :bounce returns to root from peak: 1 2 3 4 3 2 (6 steps).
          m2-future
          (future
            (binding [loop-ns/*virtual-time*  start-beat
                      loop-ns/*harmony-ctx*   base-scale
                      loop-ns/*chord-ctx*     chord]
              (play-for-beats-stochastic!
                m2-pattern motif-gen 1
                {:clock-div 1/8 :gate 0.80 :channel 4}
                harmonic-beats)))]

      (run! deref [drone-future pad-future m1-future m2-future]))

    ;; Advance the outer loop's virtual time (deadline already consumed above).
    (core/sleep! harmonic-beats)))

;; ---------------------------------------------------------------------------
;; 6. Live loop
;;
;; Each iteration = 2 bars (8 beats) of one stochastically-chosen harmony.
;; The harmonic-walk deja-vu shapes whether the system settles or wanders.
;; ---------------------------------------------------------------------------

(core/deflive-loop :ndlr-stochastic {}
  (ndlr-stochastic-step! 2 base-scale))

;; ---------------------------------------------------------------------------
;; 7. Live controls — evaluate these at the REPL during playback
;; ---------------------------------------------------------------------------

;; ── Harmonic memory ──────────────────────────────────────────────────────

;; Free wandering — every bar is a fresh draw from the modal space
;; (reset! (:x-deja-vu harmonic-walk) 0.0)

;; Settled — system revisits recent degrees, creates harmonic centre of gravity
;; (reset! (:x-deja-vu harmonic-walk) 0.85)

;; Locked loop — 4-bar harmonic ostinato, same degrees each cycle
;; (reset! (:x-deja-vu harmonic-walk) 1.0)

;; ── Rhythmic groove ───────────────────────────────────────────────────────

;; Random gating — M1/M2 call-response is unpredictable
;; (reset! (:t-deja-vu motif-gen) 0.0)

;; Groove lock — M1/M2 complement settles into a repeating 8-step pattern
;; (reset! (:t-deja-vu motif-gen) 0.9)

;; ── Texture and density ──────────────────────────────────────────────────

;; Sparser texture: t-bias 0.3 means Motif 1 fires only 30% of steps
;; Re-create motif-gen with new bias (takes effect on next defstochastic eval)
;; (stoch/defstochastic motif-gen
;;   :channels 2 :t-mode :complementary-bernoulli :t-bias 0.3 :t-loop-len 8
;;   :x-range [0 1])

;; ── Pattern swaps ────────────────────────────────────────────────────────

;; Motif 1 → inverted scale (descending)
;; (def m1-pattern (pat/invert-pattern (pat/named-pattern :scale-up)))

;; Motif 1 → alberti bass figure against chord
;; (def m1-pattern (pat/named-pattern :alberti))

;; Motif 2 → root-fifth alternation (more open bass feel)
;; (def m2-pattern (pat/named-pattern :root-fifth))

;; Motif 2 → chromatic colour from root
;; (def m2-pattern (pat/named-pattern :chromatic-bl))   ; blues hexatonic

;; ── Harmonic space ───────────────────────────────────────────────────────

;; Shift to D phrygian — all voice contexts update on next harmonic step
;; (def base-scale (scale-ns/scale :D 4 :phrygian))

;; Shift to F lydian
;; (def base-scale (scale-ns/scale :F 4 :lydian))

;; Pentatonic — fewer degree choices, more stable/folk feel
;; (def base-scale (scale-ns/scale :A 3 :pentatonic-minor))

;; ── Stop ─────────────────────────────────────────────────────────────────
;; (core/stop-loop! :ndlr-stochastic)
