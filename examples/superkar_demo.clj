; SPDX-License-Identifier: EPL-2.0
;;
;; SuperKarplus-Inspired KS Ensemble — cljseq SCLang showcase
;; ===========================================================
;;
;; Demonstrates:
;;   1. The :superkar-voice synth — warpable Karplus-Strong with body resonance
;;   2. The :superkar patch — four independent voices on a shared bus + reverb
;;   3. Per-voice warp trajectories with staggered start times
;;   4. Body resonance sweeps for timbral evolution
;;   5. The inharmonic texture that emerges when warp ≠ 1.0
;;
;; What makes the warp parameter interesting:
;;
;;   In standard Karplus-Strong, delaytime = 1/freq. The comb filter reinforces
;;   partials at exact integer multiples of the fundamental — a harmonic series.
;;
;;   When delaytime = warp/freq (warp ≠ 1.0), the comb reinforces partials at
;;   warp × fundamental, 2×warp × fundamental, etc. — an inharmonic series.
;;   The string no longer "rings true"; instead it develops bell-like sidebands,
;;   metallic ghost tones, and a shimmering instability.
;;
;;   On hardware this is usually one global knob. In cljseq, each of four voices
;;   can travel its own warp trajectory at its own pace. The ensemble evolves from
;;   a clean harmonic chord into a cloud of inharmonic partials and back — a
;;   sound that hardware cannot produce with per-voice independence.
;;
;; Prerequisites:
;;   SuperCollider running, scsynth reachable at localhost:57110.
;;
;;   (session!)
;;   (connect-sc!)
;;   (send-all-synthdefs!)
;;
;; Evaluate each section in order at the REPL.

(require '[cljseq.user   :refer :all])
(require '[cljseq.clock  :as clock])

;; ---------------------------------------------------------------------------
;; § 1. Setup
;; ---------------------------------------------------------------------------

(session!)
(connect-sc!)
(send-all-synthdefs!)

;; ---------------------------------------------------------------------------
;; § 2. Single voice — hear the warp parameter
;;
;;   warp = 1.0   → standard KS, harmonic A3
;;   warp = 0.98  → slightly sharp partials, "stretched" quality
;;   warp = 1.05  → partials above fundamental, bell shimmer begins
;;   warp = 1.2   → strongly inharmonic, gong-like
;;   warp = 0.5   → partials at half frequency — the comb rings an octave lower
;;                   while the pitch envelope still shows A3
;; ---------------------------------------------------------------------------

;; Standard KS
(sc/sc-play! {:synth :superkar-voice :freq 220 :amp 0.6 :decay 20 :coef 0.5 :warp 1.0
              :body-freq 600 :body-res 0.5})

;; Slight warp — bell shimmer beginning
(sc/sc-play! {:synth :superkar-voice :freq 220 :amp 0.6 :decay 20 :coef 0.3 :warp 1.07
              :body-freq 800 :body-res 0.3})

;; Strong warp — gong territory
(sc/sc-play! {:synth :superkar-voice :freq 220 :amp 0.6 :decay 25 :coef -0.2 :warp 1.35
              :body-freq 400 :body-res 0.2})

;; ---------------------------------------------------------------------------
;; § 3. The :superkar patch — four voices, one instantiation
;;
;;   Instantiate the patch: four voices on A2/E3/A3/E4, shared bus + reverb.
;;   All voices start at warp 1.0 (clean harmonic chord).
;; ---------------------------------------------------------------------------

(def skar (sc/instantiate-patch! :superkar))

;; Re-tune the voices to any chord you like:
;; (sc/set-patch-param! skar :voice1-freq 110)   ; A2 (default)
;; (sc/set-patch-param! skar :voice2-freq 130.8) ; C3 — minor chord

;; ---------------------------------------------------------------------------
;; § 4. Per-voice warp trajectories — the technique the hardware can't do
;;
;;   Each voice gets its own trajectory with a different curve and staggered
;;   start time. Voice1 begins warping immediately; voice4 doesn't start its
;;   drift until 16 beats in. The ensemble passes through many timbral states
;;   as the warps diverge and (optionally) converge.
;;
;;   cancel-warps! is a vector of cancel fns — call (run! #(%) cancel-warps!)
;;   to stop all four trajectories simultaneously.
;; ---------------------------------------------------------------------------

(def cancel-warps!
  (let [t (now)]
    ;; Voice 1: root — slow drift toward gentle inharmonicity, then back
    [(apply-trajectory! (get-in skar [:nodes :voice1]) :warp
       (trajectory :from 1.0 :to 1.12 :beats 48 :curve :s-curve :start t))
     ;; Voice 2: fifth — slightly faster, overshoots into bell territory
     (apply-trajectory! (get-in skar [:nodes :voice2]) :warp
       (trajectory :from 1.0 :to 1.22 :beats 40 :curve :exp :start (+ t 8)))
     ;; Voice 3: octave — starts after 16 beats, dips below 1.0 (sub-tuning)
     (apply-trajectory! (get-in skar [:nodes :voice3]) :warp
       (trajectory :from 1.0 :to 0.88 :beats 36 :curve :lin :start (+ t 16)))
     ;; Voice 4: high fifth — last to move, most extreme warp
     (apply-trajectory! (get-in skar [:nodes :voice4]) :warp
       (trajectory :from 1.0 :to 1.4 :beats 32 :curve :s-curve :start (+ t 24)))]))

;; Stop trajectories:
;; (run! #(%) cancel-warps!)

;; ---------------------------------------------------------------------------
;; § 5. Body resonance sweep — changing what the "instrument" is made of
;;
;;   While warp changes the string physics, body-freq changes the resonant
;;   cavity. Sweeping from a low body-freq to high transforms the instrument
;;   from a hollow gourd to a bright mandolin body in real time.
;; ---------------------------------------------------------------------------

(def cancel-bodies!
  (let [t (now)]
    [(apply-trajectory! (get-in skar [:nodes :voice1]) :body-freq
       (trajectory :from 200 :to 1800 :beats 64 :curve :exp :start t))
     (apply-trajectory! (get-in skar [:nodes :voice2]) :body-freq
       (trajectory :from 300 :to 1200 :beats 56 :curve :s-curve :start t))
     (apply-trajectory! (get-in skar [:nodes :voice3]) :body-freq
       (trajectory :from 600 :to 2400 :beats 48 :curve :exp :start (+ t 8)))
     (apply-trajectory! (get-in skar [:nodes :voice4]) :body-freq
       (trajectory :from 900 :to 300  :beats 40 :curve :lin :start (+ t 16)))]))

;; Stop body sweeps:
;; (run! #(%) cancel-bodies!)

;; ---------------------------------------------------------------------------
;; § 6. Live loop over the warp-evolving ensemble
;;
;;   A melody loop plucks notes from a Lydian scale while the persistent nodes
;;   drift through inharmonic space underneath. The loop samples a coef arc so
;;   each new pluck has a different brightness — strings brightening as the
;;   ensemble warps away from center.
;; ---------------------------------------------------------------------------

(def lydian-pool
  (let [root 57]  ; A3 = MIDI 69... use A2 = 45 for lower register
    (into []
          (mapcat (fn [oct] (map #(+ root % (* oct 12)) [0 2 4 6 7 9 11]))
                  [0 1]))))

;; Coef arc — strings grow slightly brighter as time advances
(def pluck-coef-arc
  (trajectory :from 0.5 :to 0.1 :beats 48 :curve :lin :start (now)))

(deflive-loop :skar-melody {}
  (let [beat (now)
        coef (clock/sample pluck-coef-arc beat)
        note (rand-nth lydian-pool)]
    (sc/sc-play! {:synth :superkar-voice
                  :freq  (clock/midi->hz note)
                  :amp   0.45 :decay 12 :coef coef
                  :warp  1.0             ; melody stays harmonic while drones warp
                  :body-freq (+ 400 (* (clock/sample pluck-coef-arc beat) 800))
                  :body-res 0.45}))
  (sleep! (rand-nth [1/2 1/2 1 1 1 3/2 2])))

;; (stop-loop! :skar-melody)

;; ---------------------------------------------------------------------------
;; § 7. Clean up
;; ---------------------------------------------------------------------------

;; (run! #(%) cancel-warps!)
;; (run! #(%) cancel-bodies!)
;; (stop-loop! :skar-melody)
;; (sc/free-patch! skar)
;; (stop!)
;; (end-session!)
