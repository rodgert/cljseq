; SPDX-License-Identifier: EPL-2.0
;;
;; Karplus-Strong String — cljseq SCLang showcase
;; ================================================
;;
;; Demonstrates:
;;   1. The :pluck synth — Karplus-Strong via SC's Pluck UGen
;;   2. apply-trajectory! driving a live SC node parameter in real time
;;   3. Trajectories as compositional arcs: each note samples a curve as it plays
;;   4. The "score as value" principle applied to timbre, not just pitch
;;   5. The :s42 patch as a second apply-trajectory! target (filter sweep)
;;
;; What makes this interesting:
;;
;;   The :coef parameter of a Pluck UGen controls the string's internal
;;   reflection coefficient — in physical terms, the "material" the string
;;   is made of. At coef = -1.0 the string barely damps (bright, bell-like).
;;   At coef = 0.99 the string damps extremely fast (barely a click).
;;   Between those extremes lies the entire vocabulary of plucked string timbre:
;;   steel, nylon, gut, silk, air.
;;
;;   In cljseq, a trajectory is a first-class value — it describes a curve over
;;   time that can be sampled at any beat. That same trajectory can be:
;;     • Sampled once per note to evolve each note's starting timbre
;;     • Fed continuously to apply-trajectory! to evolve a *live* running node
;;
;;   Both are the same trajectory value. The score is data; the time axis is
;;   just another dimension over which you compose.
;;
;; Prerequisites:
;;   SuperCollider running, with scsynth reachable at localhost:57110.
;;
;;   (session!)        ; start clock
;;   (connect-sc!)     ; open OSC connection to scsynth
;;   (send-all-synthdefs!)  ; upload all SynthDefs (first run only)
;;
;; Evaluate each section in order at the REPL.

(require '[cljseq.user   :refer :all])
(require '[cljseq.clock  :as clock])

;; ---------------------------------------------------------------------------
;; § 1. Setup
;; ---------------------------------------------------------------------------

(session!)
(connect-sc!)
(send-all-synthdefs!)   ; upload :pluck, :s42 voices, etc. to scsynth

;; ---------------------------------------------------------------------------
;; § 2. A single pluck — verify the UGen works
;;
;;   :coef    -1.0 → 0.99  string reflection coefficient
;;   :decay   seconds before string energy decays to near-silence
;;            (also controls the ASR envelope release, so they match)
;;   :freq    pitch in Hz
;; ---------------------------------------------------------------------------

;; Warm nylon-string pluck at A3
(sc/sc-play! {:synth :pluck :freq 220 :amp 0.7 :decay 15 :coef 0.5})

;; Metallic steel string — same pitch, much brighter
(sc/sc-play! {:synth :pluck :freq 220 :amp 0.7 :decay 20 :coef -0.5})

;; Barely-there gut string, almost a thump
(sc/sc-play! {:synth :pluck :freq 220 :amp 0.7 :decay 4 :coef 0.95})

;; ---------------------------------------------------------------------------
;; § 3. Coef as a compositional arc — per-note trajectory sampling
;;
;;   A trajectory is a Clojure value. On each loop iteration we sample it at
;;   the current beat: (clock/sample arc (now)). As beats advance the arc
;;   returns different values — the melody's timbre evolves without any
;;   explicit state management.
;;
;;   Over 32 bars the string "material" shifts from metallic (-0.7) to warm
;;   gut (0.85): a slow, inevitable darkening of the same pitches.
;; ---------------------------------------------------------------------------

;; Double harmonic (Dune) scale from A2, two octaves
;; A  Bb  C#  D  E  F  G# | A  Bb  C#  D  E  F  G#
(def dune-pool
  (let [root 45]  ; A2 = MIDI 45; double harmonic offsets: [0 1 4 5 7 8 11]
    (into []
          (mapcat (fn [oct] (map #(+ root % (* oct 12)) [0 1 4 5 7 8 11]))
                  [0 1]))))

;; The timbre arc: steel → gut over 32 beats
(def string-arc
  (trajectory :from -0.7 :to 0.85 :beats 32 :curve :s-curve :start (now)))

;; The decay arc: long shimmer → short thump
(def decay-arc
  (trajectory :from 25.0 :to 4.0 :beats 32 :curve :exp :start (now)))

(deflive-loop :ks-arc {}
  (let [beat (now)
        coef (clock/sample string-arc beat)
        dec  (clock/sample decay-arc  beat)
        note (rand-nth dune-pool)]
    (sc/sc-play! {:synth :pluck :freq (clock/midi->hz note)
                  :amp 0.65 :decay dec :coef coef}))
  (sleep! (rand-nth [1/2 1/2 1 1 2])))

;; Stop when you want
;; (stop-loop! :ks-arc)

;; ---------------------------------------------------------------------------
;; § 4. apply-trajectory! — animating a live, persistent SC node
;;
;;   A single long-sustaining pluck acts as a drone. apply-trajectory!
;;   continuously sends /n_set messages to update its :coef while it rings.
;;   The string's timbre evolves in real time — the UGen's reflection
;;   coefficient changes mid-decay, creating a sound impossible on real hardware.
;;
;;   This is the direct "SCLang By Other Means" demonstration:
;;     Clojure trajectory → continuous OSC → live UGen parameter
;;   The synthesis intention is described in data; cljseq executes it.
;; ---------------------------------------------------------------------------

;; A very long-sustaining drone on E2 (bass register)
(def drone-node
  (sc/sc-synth! :pluck {:freq 82.4 :amp 0.5 :decay 120 :coef -0.95 :pan -0.2}))

;; Sweep coef from bright to dark over 16 bars, then stop
(def cancel-drone-sweep!
  (apply-trajectory! drone-node :coef
    (trajectory :from -0.95 :to 0.92 :beats 64 :curve :exp :start (now))))

;; Second drone a fifth above, sweeping the opposite direction (dark → bright)
(def drone2-node
  (sc/sc-synth! :pluck {:freq 123.5 :amp 0.4 :decay 120 :coef 0.88 :pan 0.2}))

(def cancel-drone2-sweep!
  (apply-trajectory! drone2-node :coef
    (trajectory :from 0.88 :to -0.7 :beats 64 :curve :s-curve :start (now))))

;; Melodic line over the drones
(deflive-loop :ks-melody {}
  (let [note (rand-nth (subvec dune-pool 7 14))]  ; upper octave only
    (sc/sc-play! {:synth :pluck :freq (clock/midi->hz note)
                  :amp 0.55 :decay 8 :coef 0.3}))
  (sleep! (rand-nth [1/2 1/2 1 1 1 3/2])))

;; Wind it down
;; (stop-loop! :ks-melody)
;; (cancel-drone-sweep!)
;; (cancel-drone2-sweep!)
;; (sc/free-synth! drone-node)
;; (sc/free-synth! drone2-node)

;; ---------------------------------------------------------------------------
;; § 5. KS chord — three strings, three trajectories, one rhythm loop
;;
;;   Three persistent nodes on A2 / E3 / A3 (root, fifth, octave).
;;   Each gets its own trajectory with a different curve shape and phase offset.
;;   The rhythm loop plucks each string in a repeating pattern; the live
;;   apply-trajectory! animations evolve their base timbre underneath.
;; ---------------------------------------------------------------------------

(def chord-nodes
  {:root   (sc/sc-synth! :pluck {:freq 110   :amp 0.45 :decay 90 :coef 0.2 :pan -0.3})
   :fifth  (sc/sc-synth! :pluck {:freq 164.8 :amp 0.38 :decay 90 :coef 0.5 :pan  0.0})
   :octave (sc/sc-synth! :pluck {:freq 220   :amp 0.32 :decay 90 :coef 0.8 :pan  0.3})})

(def cancel-chord-sweeps!
  (let [t (now)]
    [(apply-trajectory! (:root   chord-nodes) :coef
       (trajectory :from 0.2 :to -0.6 :beats 48 :curve :s-curve  :start t))
     (apply-trajectory! (:fifth  chord-nodes) :coef
       (trajectory :from 0.5 :to  0.9 :beats 48 :curve :lin      :start t))
     (apply-trajectory! (:octave chord-nodes) :coef
       (trajectory :from 0.8 :to -0.3 :beats 48 :curve :exp      :start t))]))

;; The pluck rhythm re-excites the same string nodes (Pluck responds to gate=1 again)
;; This doesn't yet re-trigger via set-param! — re-excitation would require
;; a dedicated trigger approach. This section shows the continuous coef drift.
;; Uncomment and adapt for your own re-excitation strategy.

;; Wind down:
;; (run! #(%) cancel-chord-sweeps!)
;; (run! sc/free-synth! (vals chord-nodes))

;; ---------------------------------------------------------------------------
;; § 6. s42 (Solar42-inspired) filter sweep — the same mechanism, different target
;;
;;   apply-trajectory! doesn't know or care whether the parameter is a
;;   KS coefficient or a filter cutoff. The trajectory-to-node pipeline
;;   is identical. This is why trajectories are first-class values.
;; ---------------------------------------------------------------------------

;; Requires the :s42 patch to be instantiated:
;; (def s42 (instantiate-patch! :s42))

;; Sweep the Polivoks-inspired filter from closed to open over 16 bars
;; (def cancel-filter!
;;   (apply-trajectory! (get-in s42 [:nodes :filter]) :cutoff
;;     (trajectory :from 20 :to 100 :beats 64 :curve :s-curve :start (now))))

;; Simultaneously sweep the drone detune — widening the chord voicing
;; (doseq [id [:drone1 :drone2 :drone3 :drone4]]
;;   (apply-trajectory! (get-in s42 [:nodes id]) :detune
;;     (trajectory :from 0.005 :to 0.06 :beats 32 :curve :lin :start (now))))

;; ---------------------------------------------------------------------------
;; § 7. Clean up everything
;; ---------------------------------------------------------------------------

;; (stop!)   ; stops all loops, leaves SC nodes running
;; (end-session!)  ; full shutdown
