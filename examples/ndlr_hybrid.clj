; SPDX-License-Identifier: EPL-2.0
;;
;; Hybrid NDLR: fractal harmonic structure + stochastic meta-controller
;; =====================================================================
;;
;; Three-layer temporal hierarchy:
;;
;;   FAST   — note-level events (1/8 beat clock-div inside motif!)
;;   MID    — harmonic steps (2 bars each, driven by fractal harmony-seq)
;;   SLOW   — structural evolution (every `*evolve-interval*` steps, the
;;             stochastic meta-controller decides what to mutate)
;;
;; The fractal provides the harmonic grammar — a deterministic I-IV-V7-I
;; progression with :reverse and :inverse branches — just as in ndlr_fractal.
;;
;; Two stochastic contexts operate as a modulation matrix *above* the fractal:
;;
;;   evolution-ctl  — three independent gates (low probability each) that
;;                    decide per-evolution-window whether to:
;;                      ch 0: flip the fractal path (structural pivot)
;;                      ch 1: mutate the fractal trunk (harmonic reseed)
;;                      ch 2: swap one motif pattern from the curated pool
;;
;;   tension-arc    — slow X-section [0.0–1.0] with high deja-vu and a long
;;                    loop.  Its value is read each evolution window and:
;;                      • drives the t-deja-vu of motif-gen (high tension =
;;                        groove lock; low tension = organic randomness)
;;                      • scales Motif 1/2 velocities (loud at peak tension)
;;
;;   motif-gen      — complementary-bernoulli T-section for call-and-response
;;                    gating of Motif 1 / Motif 2 (identical to stochastic
;;                    example except its deja-vu is modulated externally)
;;
;; The result: the fractal gives the piece a spine — repeating, invertible
;; phrase grammar — while the stochastic layer introduces drift, surprise,
;; and long-arc dynamics that no single deterministic sequence can provide.
;;
;; Voice / channel assignments (same as other NDLR examples):
;;   ch 1 — Drone   (sustained root; anchors harmony)
;;   ch 2 — Pad     (open-position chord; sustained)
;;   ch 3 — Motif 1 (T-ch 0: scale-relative, fires on coin=heads)
;;   ch 4 — Motif 2 (T-ch 1: chord-relative, fires on coin=tails)
;;
;; Key live controls:
;;
;;   ;; Speed up structural evolution (mutate every 2 steps instead of 4)
;;   (alter-var-root #'*evolve-interval* (constantly 2))
;;
;;   ;; Freeze the fractal (stop path/mutate decisions from taking effect)
;;   (frac/freeze! harmony-seq)
;;   (frac/thaw! harmony-seq)
;;
;;   ;; Force a tension peak immediately
;;   (reset! (:x-deja-vu tension-arc) 0.0)
;;   (swap! tension-override (constantly 0.9))
;;
;;   ;; Lock groove hard regardless of tension
;;   (reset! (:t-deja-vu motif-gen) 0.95)
;;
;;   ;; Release groove back to tension-arc control
;;   (reset! groove-override nil)
;;
;;   ;; Stop
;;   (core/stop-loop! :ndlr-hybrid)

(ns examples.ndlr-hybrid
  (:require [cljseq.chord      :as chord-ns]
            [cljseq.core       :as core]
            [cljseq.dsl        :as dsl]
            [cljseq.fractal    :as frac]
            [cljseq.loop       :as loop-ns]
            [cljseq.pattern    :as pat]
            [cljseq.scale      :as scale-ns]
            [cljseq.stochastic :as stoch]
            [cljseq.voice      :as voice]))

;; ---------------------------------------------------------------------------
;; 1. Key and base scale
;; ---------------------------------------------------------------------------

(def base-scale
  "C dorian — a mode with both minor gravity (i, ii, v) and a bright IV
  that the Dorian colour chord exploits.
  Live: (def base-scale (scale-ns/scale :D 4 :phrygian))"
  (scale-ns/scale :C 4 :dorian))

;; Dorian degree → chord quality (same table as ndlr_stochastic)
(def degree->quality
  {0 :minor 1 :minor 2 :major 3 :major 4 :minor 5 :half-dim7 6 :major})

;; ---------------------------------------------------------------------------
;; 2. Fractal harmonic sequencer (structural grammar layer)
;;
;; The trunk encodes the Dorian I-III-IV-v progression.
;; :reverse gives a retrograde branch; :inverse a mirror branch.
;; Three branches (0=trunk, 1=retrograde, 2=inverse) give the
;; evolution-ctl path-switch something meaningful to choose between.
;; ---------------------------------------------------------------------------

(frac/deffractal harmony-seq
  :trunk      [{:degree :I   :dur/bars 2}
               {:degree :III :dur/bars 1}
               {:degree :IV  :dur/bars 2}
               {:degree :I   :dur/bars 1}]
  :transforms [:reverse :inverse]    ; branch 1 = retrograde, branch 2 = mirror
  :path       :leftmost              ; start: trunk then retrograde
  :order      :forward)

;; ---------------------------------------------------------------------------
;; 3. Stochastic meta-controllers (evolution / tension layers)
;; ---------------------------------------------------------------------------

;; evolution-ctl — three independent low-probability gates.
;; Each step in the evolution window is one draw per channel:
;;   ch 0 → path-flip gate   (~20% chance)
;;   ch 1 → mutate gate      (~15% chance)
;;   ch 2 → motif-swap gate  (~25% chance)
;;
;; t-bias per-channel achieved via independent-bernoulli mode with
;; channel-specific bias atoms rather than a shared single bias.
;; We use three separate single-channel contexts for clarity.

(stoch/defstochastic path-gate
  :channels   1
  :t-mode     :independent-bernoulli
  :t-bias     0.20
  :t-loop-len 4
  :x-range    [0 2])   ; which path index to switch to (0=trunk, 1=retro, 2=inverse)

(stoch/defstochastic mutate-gate
  :channels   1
  :t-mode     :independent-bernoulli
  :t-bias     0.15
  :t-loop-len 4
  :x-range    [0 1])

(stoch/defstochastic swap-gate
  :channels   1
  :t-mode     :independent-bernoulli
  :t-bias     0.25
  :t-loop-len 4
  :x-range    [0 1])   ; 0 = swap Motif 1, 1 = swap Motif 2

;; tension-arc — slow-moving value [0.0–1.0].
;; High deja-vu (0.75 default) means tension evolves slowly in long arcs.
;; x-loop-len 16 = 16-step memory ring (many evolution windows before repeat).
;; x-bias 0.5, x-spread 0.45: centered, with moderate swing toward extremes.

(stoch/defstochastic tension-arc
  :channels    1
  :x-range     [0 100]      ; integer 0–100; divide by 100.0 to get [0.0–1.0]
  :x-steps     0            ; raw integer output
  :x-bias      0.5
  :x-spread    0.45
  :x-loop-len  16)

(def ^:dynamic *tension-arc-deja-vu* 0.75)

;; Seed the deja-vu so the arc starts in a settled state.
;; Must be set after defstochastic expands, so the atom exists.
(reset! (:x-deja-vu tension-arc) *tension-arc-deja-vu*)

;; motif-gen — complementary-bernoulli gating for the two motifs.
;; Its t-deja-vu is driven externally by tension-arc each evolution step.

(stoch/defstochastic motif-gen
  :channels   2
  :t-mode     :complementary-bernoulli
  :t-bias     0.55            ; Motif 1 fires ~55% of steps
  :t-loop-len 8
  :x-range    [0 1])

;; ---------------------------------------------------------------------------
;; 4. Pattern pools (curated; motif-swap draws from these)
;; ---------------------------------------------------------------------------

(def m1-pool
  "Scale-relative patterns for Motif 1. Drawn during motif-swap events."
  [(pat/named-pattern :scale-up)       ; ascending run
   (pat/named-pattern :scale-down)     ; descending
   (pat/named-pattern :scale-bounce)   ; up-down wave
   (pat/named-pattern :pentatonic)     ; pentatonic subset
   (pat/named-pattern :zigzag)         ; alternating skip
   (pat/named-pattern :thirds-up)])    ; parallel thirds

(def m2-pool
  "Chord-relative patterns for Motif 2. Drawn during motif-swap events."
  [(pat/named-pattern :bounce)         ; root-up-peak-down
   (pat/named-pattern :alberti)        ; root-fifth-third-fifth
   (pat/named-pattern :root-fifth)     ; root-fifth alternation
   (pat/named-pattern :shell-up)       ; shell voicing ascent
   (pat/named-pattern :broken-triad)   ; broken-chord figure
   (pat/named-pattern :peak)])         ; rises to peak and stops

;; Active patterns — mutated by evolve!
(def m1-pattern (atom (pat/named-pattern :scale-up)))
(def m2-pattern (atom (pat/named-pattern :bounce)))

;; ---------------------------------------------------------------------------
;; 5. Evolution state
;; ---------------------------------------------------------------------------

;; How many harmonic steps between evolution-ctl draws.
;; Defvar so it can be adjusted live without stopping the loop.
(def ^:dynamic *evolve-interval* 4)

;; Step counter — incremented by ndlr-hybrid-step!, evolution fires when
;; (zero? (mod step-count *evolve-interval*)).
(def step-count (atom 0))

;; Optional manual override for groove deja-vu (nil = use tension-arc).
(def groove-override (atom nil))

;; ---------------------------------------------------------------------------
;; 6. Helpers
;; ---------------------------------------------------------------------------

(defn bars->beats [bars] (* bars 4))

(defn degree->chord
  "Resolve a Roman numeral keyword to a Chord record against `scale`."
  [scale degree-kw]
  (first (chord-ns/progression scale [degree-kw])))

(defn stochastic-rhythms!
  "Draw n complementary gate pairs from motif-gen.
  Must interleave ch-0 and ch-1 draws to preserve correlation.
  Returns [rhy-ch0 rhy-ch1]."
  [gen n vel]
  (let [pairs (mapv (fn [_]
                      [(when (stoch/next-t! gen 0) vel)
                       (when (stoch/next-t! gen 1) vel)])
                    (range n))]
    [(pat/rhythm (mapv first pairs))
     (pat/rhythm (mapv second pairs))]))

(defn play-stochastic-cycles!
  "Play `n-cycles` of `pat` × fresh stochastic rhythm from `gen` channel `ch`."
  [pat gen ch n-cycles opts]
  (let [pcnt    (pat/pat-len pat)
        n-steps pcnt
        vel     100]
    (dotimes [_ n-cycles]
      (let [[rhy-0 rhy-1] (stochastic-rhythms! gen n-steps vel)
            rhy           (if (zero? ch) rhy-0 rhy-1)]
        (pat/motif! pat rhy opts)))))

(defn play-for-beats-stochastic!
  "Run stochastic motif cycles for exactly `harmonic-beats` of virtual time."
  [pat gen ch opts harmonic-beats]
  (let [clock-div   (get opts :clock-div 1/8)
        clen        (pat/pat-len pat)
        cycle-beats (* (double clock-div) clen)
        n-full      (long (/ (double harmonic-beats) cycle-beats))
        remainder   (- (double harmonic-beats) (* n-full cycle-beats))]
    (play-stochastic-cycles! pat gen ch n-full opts)
    (when (> remainder 0.001)
      (core/sleep! remainder))))

;; ---------------------------------------------------------------------------
;; 7. Evolution function (slow structural layer)
;;
;; Called every *evolve-interval* harmonic steps.
;; Reads tension-arc once, then conditionally applies:
;;   path-gate  → fractal path flip (chooses a different branch order)
;;   mutate-gate → fractal trunk mutation (harmonic material shifts)
;;   swap-gate  → motif pattern swap from curated pools
;;
;; The tension value (0.0–1.0) controls motif-gen's t-deja-vu:
;;   low tension  → organic, unpredictable call-and-response
;;   high tension → groove locks into a repeating 8-step pattern
;; ---------------------------------------------------------------------------

(defn evolve!
  "Apply stochastic meta-decisions to the fractal and motif state."
  []
  ;; ── Tension arc ───────────────────────────────────────────────────────────
  (let [raw-tension  (stoch/next-x! tension-arc 0)
        tension      (/ (double (max 0 (min 100 raw-tension))) 100.0)

        ;; Groove deja-vu: respect manual override if set
        groove-dv    (or @groove-override tension)]

    ;; Push tension into motif-gen's rhythm memory
    (reset! (:t-deja-vu motif-gen) groove-dv)

    ;; ── Structural evolution decisions ────────────────────────────────────
    ;; path flip — switch which fractal branch order plays
    (when (stoch/next-t! path-gate 0)
      (let [new-path (int (max 0 (min 2 (stoch/next-x! path-gate 0))))]
        (frac/fractal-path! harmony-seq [new-path (mod (inc new-path) 3)])))

    ;; mutate — reseed the fractal's mutable branches (not the trunk)
    (when (stoch/next-t! mutate-gate 0)
      (frac/mutate! harmony-seq))

    ;; motif swap — replace M1 or M2 pattern from its pool
    (when (stoch/next-t! swap-gate 0)
      (let [which (int (max 0 (min 1 (stoch/next-x! swap-gate 0))))
            pool  (if (zero? which) m1-pool m2-pool)
            pat   (pool (rand-int (count pool)))]
        (if (zero? which)
          (reset! m1-pattern pat)
          (reset! m2-pattern pat))))))

;; ---------------------------------------------------------------------------
;; 8. Single harmonic step performer (mid layer)
;;
;; Mirrors ndlr_fractal.clj's ndlr-step! but:
;;   • resolves degree-kw against degree->quality for Dorian chord types
;;   • uses stochastic-rhythm gating (complementary-bernoulli) for M1/M2
;;   • reads live m1-pattern / m2-pattern atoms so pattern swaps take effect
;;   • increments step-count and fires evolve! at the *evolve-interval*
;; ---------------------------------------------------------------------------

(defn ndlr-hybrid-step!
  "Play one harmonic step of the hybrid NDLR sequencer.

  `step`  — {:degree :IV :dur/bars 2} from (frac/next-step! harmony-seq)
  `scale` — base Scale record"
  [step scale]
  (let [{:keys [degree dur/bars]} step
        harmonic-beats  (bars->beats (or bars 2))
        ;; Resolve Roman numeral to a Dorian-quality chord
        degree-kw       (or degree :I)
        degree-idx      ({:I 0 :II 1 :III 2 :IV 3 :V 4 :VI 5 :VII 6
                          :V7 4} degree-kw 0)
        quality         (get degree->quality degree-idx :minor)
        chord           (chord-ns/chord-at-degree scale degree-idx quality)
        root-midi       (first (chord-ns/chord->midis chord))
        pad-midis       (voice/open-position chord)
        start-beat      (loop-ns/-current-beat)
        ;; Read live pattern atoms
        cur-m1          @m1-pattern
        cur-m2          @m2-pattern]

    ;; ── Drone (ch 1) ──────────────────────────────────────────────────────
    (let [drone-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*synth-ctx*    {:midi/channel 1 :mod/velocity 52}]
              (dsl/play! {:pitch/midi root-midi
                          :dur/beats  (* harmonic-beats 0.95)})
              (core/sleep! harmonic-beats)))

          ;; ── Pad (ch 2) ────────────────────────────────────────────────────
          pad-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*synth-ctx*    {:midi/channel 2 :mod/velocity 68}]
              (dsl/with-dur (* harmonic-beats 0.88)
                (dsl/play-voicing! pad-midis))
              (core/sleep! harmonic-beats)))

          ;; ── Motif 1 (ch 3) — scale-relative, stochastic gate T-ch 0 ──────
          ;; Velocity scales gently with tension: stays audible but recedes
          ;; when groove is locked (tension-arc drives deja-vu, not velocity
          ;; directly — but motif density already creates perceived tension).
          m1-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*harmony-ctx*  scale
                      loop-ns/*chord-ctx*    chord]
              (play-for-beats-stochastic!
                cur-m1 motif-gen 0
                {:clock-div 1/8 :gate 0.75 :channel 3}
                harmonic-beats)))

          ;; ── Motif 2 (ch 4) — chord-relative, complementary gate T-ch 1 ───
          m2-future
          (future
            (binding [loop-ns/*virtual-time* start-beat
                      loop-ns/*harmony-ctx*  scale
                      loop-ns/*chord-ctx*    chord]
              (play-for-beats-stochastic!
                cur-m2 motif-gen 1
                {:clock-div 1/8 :gate 0.80 :channel 4}
                harmonic-beats)))]

      (run! deref [drone-future pad-future m1-future m2-future]))

    ;; ── Slow evolution tick ───────────────────────────────────────────────
    (let [n (swap! step-count inc)]
      (when (zero? (mod n *evolve-interval*))
        (evolve!)))

    (core/sleep! harmonic-beats)))

;; ---------------------------------------------------------------------------
;; 9. Live loop
;;
;; The fractal provides the harmonic step; the stochastic meta-layer reshapes
;; the fractal every *evolve-interval* steps from the outside.
;;
;; Conceptually: the fractal is a composer writing a score, and the stochastic
;; layer is an improvisor who occasionally scribbles on that score — crossing
;; out passages, adding new ones, adjusting how tightly the rhythm section plays.
;; ---------------------------------------------------------------------------

(core/deflive-loop :ndlr-hybrid {}
  (ndlr-hybrid-step! (frac/next-step! harmony-seq) base-scale))

;; ---------------------------------------------------------------------------
;; 10. Live controls
;; ---------------------------------------------------------------------------

;; ── Evolution rate ────────────────────────────────────────────────────────

;; Evolve every 2 steps — restless, texture changes rapidly
;; (alter-var-root #'*evolve-interval* (constantly 2))

;; Evolve every 8 steps — patient, long arcs before mutation
;; (alter-var-root #'*evolve-interval* (constantly 8))

;; ── Tension arc controls ─────────────────────────────────────────────────

;; Free the arc — slow drift between loose and locked textures
;; (reset! (:x-deja-vu tension-arc) 0.0)

;; Pin the arc to a locked groove permanently
;; (reset! groove-override 0.92)

;; Release the override — return to tension-arc control
;; (reset! groove-override nil)

;; ── Fractal controls ─────────────────────────────────────────────────────

;; Freeze the fractal — evolution-ctl path/mutate gates have no effect
;; (frac/freeze! harmony-seq)
;; (frac/thaw! harmony-seq)

;; Force a specific branch order manually (bypasses path-gate)
;; (frac/fractal-path! harmony-seq [2 0 1])   ; inverse → trunk → retrograde

;; Randomize the path — chaos pivot
;; (frac/fractal-path! harmony-seq :random)

;; ── Motif pattern hot-swap ────────────────────────────────────────────────

;; Manually override M1 mid-performance (takes effect next harmonic step)
;; (reset! m1-pattern (pat/named-pattern :pentatonic))
;; (reset! m1-pattern (pat/invert-pattern (pat/named-pattern :scale-up)))

;; Manually override M2
;; (reset! m2-pattern (pat/named-pattern :root-fifth))
;; (reset! m2-pattern (pat/named-pattern :alberti))

;; ── Key and modal colour ─────────────────────────────────────────────────

;; Shift modal centre — the fractal's progression qualities stay Dorian-mapped
;; but sound completely different transposed to D Phrygian
;; (def base-scale (scale-ns/scale :D 4 :phrygian))

;; Lydian — brightens the IV to a #IV, gives an expansive quality
;; (def base-scale (scale-ns/scale :F 4 :lydian))

;; ── Stop ─────────────────────────────────────────────────────────────────
;; (core/stop-loop! :ndlr-hybrid)
