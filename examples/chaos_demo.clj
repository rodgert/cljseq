; SPDX-License-Identifier: EPL-2.0
;;
;; Chaos Synthesis — cljseq SCLang showcase
;; =========================================
;;
;; Demonstrates:
;;   1. The :chaos-lorenz synth — Lorenz attractor as audio oscillator
;;   2. The :chaos-henon synth — Hénon map as audio oscillator
;;   3. The :lorenz-fm synth — FM synthesis with Lorenz chaos modulation
;;   4. The :chaos-ensemble patch — three voices with independent bifurcation
;;   5. Bifurcation arcs — apply-trajectory! driving :chaos from stable → chaotic
;;   6. The period-doubling cascade — audible structure in the route to chaos
;;
;; What makes chaos synthesis interesting:
;;
;;   Standard synthesis vocabularies generate signals with predictable spectral
;;   relationships. A sine wave has one partial. FM produces sidebands at integer
;;   multiples of the modulating frequency. Even noise is spectrally flat.
;;
;;   Chaotic oscillators are different: they are deterministic (same initial
;;   conditions → same trajectory) but infinitely sensitive to parameters. A small
;;   increase in the Lorenz r parameter takes the system from a clean periodic orbit
;;   to an inharmonic strange attractor. The route passes through period-doubling:
;;   a tone becomes two tones, then four, then eight, then chaos. This transition
;;   is a spectrum of timbral states impossible to produce with standard methods.
;;
;;   In cljseq, :chaos [0..1] is a first-class trajectory target. A bifurcation
;;   arc drives the Lorenz r from 10 (stable) to 50 (deep chaos) — the composition
;;   is the trajectory across this parameter space.
;;
;; Prerequisites:
;;   SuperCollider running, scsynth at localhost:57110.
;;
;;   (session!)
;;   (connect-sc!)
;;   (send-all-synthdefs!)

(require '[cljseq.user   :refer :all])
(require '[cljseq.clock  :as clock])

;; ---------------------------------------------------------------------------
;; § 1. Setup
;; ---------------------------------------------------------------------------

(session!)
(connect-sc!)
(send-all-synthdefs!)

;; ---------------------------------------------------------------------------
;; § 2. Lorenz attractor — hear the chaos parameter sweep manually
;;
;;   Start a persistent Lorenz node and update :chaos by hand.
;;   Listen for the transition from stable approach → butterfly → deep chaos.
;; ---------------------------------------------------------------------------

(def lz (sc/sc-synth! :chaos-lorenz {:chaos 0.3 :freq-cut 2000 :amp 0.4 :pan 0.0}))

;; Raise chaos incrementally — listen for the transition region around 0.4–0.5:
;; (sc/set-param! lz :chaos 0.35)
;; (sc/set-param! lz :chaos 0.45)   ; ← approaching chaos onset
;; (sc/set-param! lz :chaos 0.5)    ; ← classic butterfly
;; (sc/set-param! lz :chaos 0.7)
;; (sc/set-param! lz :chaos 1.0)    ; ← deep chaos
;; (sc/free-synth! lz)

;; ---------------------------------------------------------------------------
;; § 3. Hénon map — discrete, metallic chaos
;;
;;   The Hénon map period-doubles more sharply than Lorenz.
;;   chaos=0.0 → a=1.0: clean period-2 (two pitches alternating)
;;   chaos=0.45 → a=1.27: visible period-doubling transition
;;   chaos=0.7 → a=1.4: classic Hénon attractor (metallic fizz)
;; ---------------------------------------------------------------------------

(def hn (sc/sc-synth! :chaos-henon {:chaos 0.1 :freq-cut 3500 :amp 0.45 :pan 0.0}))

;; Hear the period-doubling cascade:
;; (sc/set-param! hn :chaos 0.3)  ; period-4
;; (sc/set-param! hn :chaos 0.44) ; near-onset
;; (sc/set-param! hn :chaos 0.7)  ; classic Hénon
;; (sc/free-synth! hn)

;; ---------------------------------------------------------------------------
;; § 4. Lorenz FM — chaos as modulation source
;;
;;   A clean SinOsc carrier becomes increasingly unstable as the Lorenz
;;   modulator enters its chaotic regime. The carrier frequency deviates
;;   by ±(fm-depth × freq) per Lorenz sample — at high chaos, hundreds of Hz
;;   per sample. A dense inharmonic cloud emerges from what started as a pure tone.
;; ---------------------------------------------------------------------------

(def lfm (sc/sc-synth! :lorenz-fm {:freq 220 :chaos 0.1 :fm-depth 0.3 :amp 0.45}))

;; (sc/set-param! lfm :chaos 0.3)
;; (sc/set-param! lfm :chaos 0.5)  ; FM instability rapidly increasing
;; (sc/set-param! lfm :chaos 0.8)  ; dense cloud
;; (sc/set-param! lfm :fm-depth 0.6) ; wider deviation at any chaos level
;; (sc/free-synth! lfm)

;; ---------------------------------------------------------------------------
;; § 5. Bifurcation arc — apply-trajectory! driving chaos over time
;;
;;   The canonical chaos-as-tension-arc: a composition that begins in tonal
;;   territory and gradually destabilizes into noise over a defined number of beats.
;;   This is the direct analogue to a classical crescendo — but in spectral space
;;   rather than dynamic space.
;; ---------------------------------------------------------------------------

;; Simple bifurcation arc: stable → classic butterfly over 48 beats
(def lorenz-node
  (sc/sc-synth! :chaos-lorenz {:chaos 0.2 :freq-cut 2000 :amp 0.4 :pan -0.3}))

(def cancel-bifurcation!
  (apply-trajectory! lorenz-node :chaos
    (trajectory :from 0.2 :to 0.75 :beats 48 :curve :s-curve :start (now))))

;; Filter sweep rises with chaos — the spectrum opens as it destabilizes
(def cancel-filter-rise!
  (apply-trajectory! lorenz-node :freq-cut
    (trajectory :from 800 :to 4000 :beats 48 :curve :exp :start (now))))

;; (cancel-bifurcation!)
;; (cancel-filter-rise!)
;; (sc/free-synth! lorenz-node)

;; ---------------------------------------------------------------------------
;; § 6. Chaos ensemble — three voices, three independent bifurcation paths
;;
;;   The :chaos-ensemble patch: two Lorenz voices (s=10 / s=12) + Hénon voice.
;;   The Lorenz pair use different Prandtl numbers — their attractors diverge
;;   over time even if :chaos is identical, creating organic stereo widening.
;;
;;   Three independent trajectories, staggered start times:
;;     Lorenz1 (left)  — slow drift, reaches deep chaos last
;;     Lorenz2 (right) — faster drift, reaches butterfly early, then pulls back
;;     Hénon   (center) — medium speed, stays in period-doubling territory
;; ---------------------------------------------------------------------------

(def chaos-ens (sc/instantiate-patch! :chaos-ensemble))

(def cancel-ens!
  (let [t (now)]
    [(apply-trajectory! (get-in chaos-ens [:nodes :lorenz1]) :chaos
       (trajectory :from 0.2 :to 0.8 :beats 64 :curve :s-curve :start t))
     (apply-trajectory! (get-in chaos-ens [:nodes :lorenz2]) :chaos
       (trajectory :from 0.25 :to 0.6 :beats 48 :curve :exp :start (+ t 8)))
     (apply-trajectory! (get-in chaos-ens [:nodes :henon]) :chaos
       (trajectory :from 0.1 :to 0.55 :beats 56 :curve :lin :start (+ t 16)))]))

;; (run! #(%) cancel-ens!)
;; (sc/free-patch! chaos-ens)

;; ---------------------------------------------------------------------------
;; § 7. Chaos + melody — live loop over a bifurcating drone
;;
;;   A melody loop in the Dorian scale plays over a Lorenz drone that slowly
;;   enters chaos. Each melodic note samples the current chaos level and adjusts
;;   its own filter color accordingly — brighter as chaos deepens.
;; ---------------------------------------------------------------------------

(def chaos-drone
  (sc/sc-synth! :chaos-lorenz {:chaos 0.15 :freq-cut 1200 :amp 0.35 :pan -0.2}))

(def chaos-arc
  (trajectory :from 0.15 :to 0.72 :beats 64 :curve :s-curve :start (now)))

(def cancel-drone-chaos!
  (apply-trajectory! chaos-drone :chaos chaos-arc))

;; Dorian scale for melody (D3 = MIDI 50, Dorian offsets: 0 2 3 5 7 9 10)
(def dorian-pool
  (let [root 50]
    (into []
          (mapcat (fn [oct] (map #(+ root % (* oct 12)) [0 2 3 5 7 9 10]))
                  [0 1]))))

(deflive-loop :chaos-melody {}
  (let [beat    (now)
        chaos   (clock/sample chaos-arc beat)
        cut     (+ 800 (* chaos 2400))   ; filter opens with chaos
        note    (rand-nth dorian-pool)]
    (sc/sc-play! {:synth  :lorenz-fm
                  :freq   (clock/midi->hz note)
                  :chaos  (* 0.4 chaos)  ; melodic notes partly chaotic
                  :fm-depth 0.2
                  :freq-cut cut
                  :amp    0.35
                  :decay  4.0}))
  (sleep! (rand-nth [1/2 1/2 1 1 1 3/2])))

;; (stop-loop! :chaos-melody)
;; (cancel-drone-chaos!)
;; (sc/free-synth! chaos-drone)

;; ---------------------------------------------------------------------------
;; § 8. Clean up
;; ---------------------------------------------------------------------------

;; (stop!)
;; (end-session!)
