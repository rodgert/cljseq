; SPDX-License-Identifier: EPL-2.0
;;
;; rubycon-core.clj — Kosmische session: Rubycon polyrhythmic core
;;
;; Aesthetic: Phaedra/Rubycon sequencer grammar inflected with Sabbath's
;; Phrygian weight and Sunn O)))'s tectonic patience. D Dorian is the stable
;; territory; Phrygian dominant provides the dark inflection.
;;
;; How to run:
;;   Load this file into a REPL in the explore/kosmische worktree.
;;   Evaluate sections top-to-bottom. Comments mark what to listen for.
;;
;;   lein repl  (from ~/Documents/org/areas/cljseq/worktrees/cljseq/explore-kosmische)
;;
;; AUDIO PATHS:
;;
;;   Hardware path (studio):
;;     Ch 1 — bass/sub voice (Sub37, Minimoog, or similar — long filter, portamento)
;;     Ch 2 — mid voice (second synth or Surge XT OB-Xa style, IAC Bus 2 port 1)
;;     Ch 3 — counter-register (lighter synth or Surge XT high-register patch)
;;     sidecar + IAC Driver Bus 2
;;
;;   Standalone SC path (travel rig — no IAC/hardware dependency):
;;     Voice A → SC :bass synth  (D2 register, heavy filtered saw)
;;     Voice B → SC :dark-ambience synth (mid register, resonant pad)
;;     Voice C → SC :pretty-bell synth   (D4+ register, ToB counter-register)
;;     (sc/connect-sc!) in section 0B below
;;
;; MIDI note reference:
;;   D2=38  E2=40  F2=41  G2=43  A2=45  C3=48
;;   D3=50  F3=53  G3=55  A3=57  C4=60
;;   D4=62  E4=64  G4=67  A4=69  C5=72  D5=74

;; cljseq.core is the default REPL ns and already owns play!, sleep!,
;; deflive-loop, stop-loop!, set-bpm!, now, etc.
;; Only pull in what core doesn't have:
(require '[cljseq.journey         :as journey]
         '[cljseq.berlin          :as berlin]
         '[cljseq.sc              :as sc]
         '[cljseq.temporal-buffer :as tbuf]
         '[cljseq.user            :refer [session! start-sidecar! stop-sidecar!
                                          make-scale trajectory]]
         '[cljseq.berlin          :refer [frippertronics! sos-send!]]
         '[cljseq.temporal-buffer :refer [temporal-buffer-hold! temporal-buffer-color!]])

;; ============================================================
;; 0A. SESSION BOOT — clock
;; ============================================================

;; Start clock at 100 BPM — TD canonical Phaedra/Rubycon tempo.
;; Drop to 72 for Zeit/Irrlicht territory (heavy, pre-sequencer doom).
(session! :bpm 100)

;; ============================================================
;; 0B. AUDIO PATH — pick one of: hardware MIDI, or standalone SC
;;
;; HARDWARE PATH (studio with IAC + sidecar):
;; ============================================================

;; Note: worktrees share the main checkout's build/ directory.
;; Locate the sidecar binary via git's common dir pointer.
(def ^:private sidecar-bin
  (let [main-root (-> (clojure.java.shell/sh "git" "rev-parse" "--git-common-dir")
                      :out clojure.string/trim
                      (clojure.java.io/file "..")
                      .getCanonicalPath)]
    (str main-root "/build/cpp/cljseq-sidecar/cljseq-sidecar")))

;; IAC Driver Bus 2 = port index 1 (use (list-midi-ports) to verify).
;; Voice A on MIDI Ch 1, Voice B on Ch 2, Voice C on Ch 3.
(comment
  (start-sidecar! :midi-port 1 :binary sidecar-bin))

;; ============================================================
;; 0C. STANDALONE SC PATH (travel rig — no IAC/hardware needed)
;;
;; Voices route through SC synthdefs instead of MIDI.
;; Requires SuperCollider running locally with sclang on port 57120.
;;
;; SynthDefs used:
;;   :bass         — heavy detuned saw + sub-octave, ADSR, LPF
;;   :dark-ambience — resonant RLPF over brown noise + sine; long attack
;;   :pretty-bell  — 4 sine partials, harmonic + slight inharmonicity; long release
;;
;; Voice steps gain :synth key; play! dispatches to SC automatically
;; when (sc/sc-connected?) is true, bypassing MIDI/sidecar entirely.
;; ============================================================

(comment
  (sc/connect-sc!)
  ;; Pre-load all three synthdefs — safe to call multiple times:
  (sc/ensure-synthdef! :bass)
  (sc/ensure-synthdef! :dark-ambience)
  (sc/ensure-synthdef! :pretty-bell))

;; ============================================================
;; 1. VERIFY LOGIC (safe to run without audio)
;; ============================================================

;; Phase relationship between the two voices.
;; At 100 BPM: the patterns drift apart and re-align every 2.04 minutes.
;; You should hear a gradual phase shift — the bass and mid voice will
;; drift relative to each other, then snap back into alignment.
(journey/phase-pair 12 17)
;; => {:lcm 204
;;     :alignment-beats [204 408 612 816 1020]
;;     :drift-rate 0.02941...
;;     :at-bpm {:bpm 100 :minutes 2.04}}

;; The Phaedra arc scaffold — skim the bar numbers and movement structure.
;; Replace keyword placeholders with real fns before calling start-journey!.
(journey/phaedra-arc)

;; ============================================================
;; 2. SCALE — D Dorian (stable territory)
;;
;; D Dorian: D E F G A B C
;; Mutation will keep all pitches within this set.
;; Phrygian dominant inflection introduced in Movement 3 (see below).
;; ============================================================

(def rubycon-scale (make-scale :D 2 :dorian))

;; ============================================================
;; 3. OSTINATI — three voices, incommensurate lengths
;;
;; Voice A: 4 steps × 3 beats each  →  12-beat cycle
;; Voice B: 3 steps at 5/6/6 beats  →  17-beat cycle
;; Voice C: 4 steps at 4/3/3/3 beats → 13-beat cycle  (ToB counter-register)
;;
;; Phase relationships (all at 100 BPM):
;;   A/B LCM 204 beats  =  2.04 min  (original pair)
;;   A/C LCM 156 beats  =  1.56 min
;;   B/C LCM 221 beats  =  2.21 min
;;   A/B/C triple LCM 2652 beats = 26.52 min (the grand cycle — rarely if ever lands)
;;
;; Each loop plays ONE step then sleeps. Notes are long and sustained.
;; The three-way polyrhythm creates a much denser web of phase relationships.
;; ============================================================

;; Voice A — bass register, root motion, 12-beat cycle
;; D2 tonic anchor with minor-third and fifth movement.
;; Sleeps 3 beats between steps; total pass = 4 × 3 = 12 beats.
;;
;; Hardware path: Ch 1 (bass synth)
;; SC standalone: add :synth :bass to each step
(def ost-a
  (berlin/ostinato
    [{:pitch/midi 38 :dur/beats 2.5 :mod/velocity 80}   ; D2 — root
     {:pitch/midi 43 :dur/beats 2.5 :mod/velocity 72}   ; G2 — fourth
     {:pitch/midi 41 :dur/beats 2.5 :mod/velocity 68}   ; F2 — minor third
     {:pitch/midi 40 :dur/beats 2.5 :mod/velocity 75}]  ; E2 — second (Dorian colour)
    {:scale rubycon-scale :mutation-rate 0.08 :drift :random}))

;; SC synth overlay for voice A — merge :synth into each step for standalone play.
;; Set this before the voice-a loop if using SC path:
(defn- add-sc-voice-a [step]
  (merge {:synth :bass :amp 0.5 :cutoff 45 :attack 0.2 :release 1.8} step))

;; Voice B — mid register, prime-length cycle, 17-beat
;; Three steps at unequal sleeps: 5, 6, 6 beats → 17-beat cycle.
;; Unequal step spacing gives an irregular, breathing feel.
;;
;; Hardware path: Ch 2 (Surge XT OB-Xa style on IAC Bus 2)
;; SC standalone: add :synth :dark-ambience to each step
(def ost-b
  (berlin/ostinato
    [{:pitch/midi 50 :dur/beats 4.0 :mod/velocity 65}   ; D3 — octave root
     {:pitch/midi 53 :dur/beats 5.0 :mod/velocity 58}   ; F3 — minor third
     {:pitch/midi 55 :dur/beats 5.0 :mod/velocity 62}]  ; G3 — fifth
    {:scale rubycon-scale :mutation-rate 0.06 :drift :random}))

(defn- add-sc-voice-b [step]
  (merge {:synth :dark-ambience :amp 0.28 :cutoff 48 :res 0.82 :attack 1.5 :release 5.0} step))

;; Voice C — counter-register, 13-beat cycle (ToB: Tyranny of Beauty)
;; Lighter, higher register — D4/E4/G4/A4. Lydian flavour: emphasise the
;; raised fourth (G#4) for the "light over dark" ToB character, but keep
;; scale-constrained in D Dorian most of the time. Introduce this voice
;; after the two-voice arc is established.
;;
;; 4 steps at 4/3/3/3 beats = 13 beats total.
;; Phase against A: LCM 156 beats (1.56 min). Phase against B: LCM 221 (2.21 min).
;;
;; Hardware path: Ch 3
;; SC standalone: :pretty-bell — warm harmonic bell, long release
(def ost-c
  (berlin/ostinato
    [{:pitch/midi 62 :dur/beats 3.0 :mod/velocity 55}   ; D4 — tonic (high)
     {:pitch/midi 67 :dur/beats 2.5 :mod/velocity 48}   ; G4 — fifth
     {:pitch/midi 69 :dur/beats 2.5 :mod/velocity 52}   ; A4 — sixth
     {:pitch/midi 64 :dur/beats 2.5 :mod/velocity 45}]  ; E4 — second
    {:scale rubycon-scale :mutation-rate 0.04 :drift :up}))

;; SC synth overlay for voice C — :pretty-bell, low amplitude, long release
(defn- add-sc-voice-c [step]
  (merge {:synth :pretty-bell :amp 0.22 :attack 0.04 :release 4.0} step))

;; ============================================================
;; 4. S42 DRONE — Solar42-inspired SC voice (4th voice, below the mix)
;;
;; Requires sclang running: (sc/connect-sc!) then evaluate this block.
;; verb-node is the master volume control for the entire drone chain.
;; drone-ramp! (defined in section 7) fades it in/out over the arc.
;;
;; Setup procedure:
;;   1. Pre-send synthdefs + sync
;;   2. instantiate-patch! :s42
;;   3. Kill mono verb, spawn stereo reverb-bus-stereo
;;   4. bind verb-node — this is the master amp for the drone
;; ============================================================

(comment
  ;; Run this block once after (sc/connect-sc!):
  (doseq [s [:s42-drone-voice :s42-vco-voice :s42-papa-voice :s42-filter :reverb-bus-stereo]]
    (sc/ensure-synthdef! s))
  (Thread/sleep 3000)  ; wait for scsynth to load defs
  (def s42 (sc/instantiate-patch! :s42))
  (sc/kill-synth! (get-in s42 [:nodes :verb]))
  (def verb-node (sc/sc-synth! :reverb-bus-stereo
                   {:in-bus (get-in s42 [:buses :filtered-mix])
                    :out 0 :room 0.5 :mix 0.33 :damp 0.5 :amp 0.0}))
  (def s42 (assoc-in s42 [:nodes :verb] verb-node)))

;; ============================================================
;; 5. BAR COUNTER — the shared timeline
;; ============================================================

(journey/start-bar-counter!)

;; Verify it's counting:
;; (Thread/sleep 2000) (journey/current-bar)  ; should be > 0

;; ============================================================
;; 6. VOICE LOOPS — fire A and B, start phase-locked
;;
;; :restart-on-bar true snaps both loops to the next bar boundary
;; before entering, so voice-a and voice-b start from beat 0 together.
;; The drift begins immediately after that aligned start.
;;
;; Voice C is held back — introduced later via introduce-voice-c! (section 8).
;; ============================================================

;; Choose playback path per voice:
;;   Hardware (IAC): use (berlin/next-step! ost-x) directly — MIDI ch routing
;;   SC standalone: wrap with add-sc-voice-x to inject :synth key
;;
;; Hardware path shown below. For SC path, replace (berlin/next-step! ost-a)
;; with (add-sc-voice-a (berlin/next-step! ost-a)) and remove :channel opts.

;; Voice A: step every 3 beats, full 12-beat cycle
(deflive-loop :voice-a {:channel 1 :restart-on-bar true}
  (berlin/with-portamento 1 80
    (play! (berlin/next-step! ost-a)))
  (sleep! 3))

;; SC path (uncomment to use instead of hardware):
(comment
  (deflive-loop :voice-a {:restart-on-bar true}
    (play! (add-sc-voice-a (berlin/next-step! ost-a)))
    (sleep! 3)))

;; Voice B: step at 5 / 6 / 6 beat spacing, full 17-beat cycle
;; Unequal spacing gives an irregular, breathing feel.
(let [step-sleeps (atom (cycle [5 6 6]))]
  (deflive-loop :voice-b {:channel 2 :restart-on-bar true}
    (play! (journey/humanise (berlin/next-step! ost-b)
                             {:timing-variance-ms 8 :velocity-variance 6}))
    (sleep! (first (swap! step-sleeps rest)))))

;; SC path (uncomment to use instead of hardware):
(comment
  (let [step-sleeps (atom (cycle [5 6 6]))]
    (deflive-loop :voice-b {:restart-on-bar true}
      (play! (journey/humanise (add-sc-voice-b (berlin/next-step! ost-b))
                               {:timing-variance-ms 8 :velocity-variance 6}))
      (sleep! (first (swap! step-sleeps rest))))))

;; ============================================================
;; WHAT TO LISTEN FOR — first five minutes
;;
;; 0:00 — Both voices start together on D2/D3. Filter closed.
;; 0:30 — First separation: voice-b has shifted by ~5 beats relative to A.
;; 1:00 — Mid-drift: the two voices are maximally out of phase.
;;         This is the "Rubycon middle" — spacious, unsettling.
;; 2:04 — Re-alignment. Both voices land on a downbeat together.
;;         May feel like a structural event even though nothing changed.
;; 2:04+ — Second phase cycle begins. Mutation has now altered some pitches
;;          (rate 0.08/0.06 means roughly 1 note changes per 12-15 passes).
;; ============================================================

;; ============================================================
;; 7. FILTER JOURNEY — open the filter over 64 bars
;;
;; Start this immediately after the loops are running.
;; The filter opening IS Movement 2 (Crystallisation).
;; CC 74 = filter cutoff on most synths; adjust for yours.
;; ============================================================

;; (berlin/filter-journey! [:filter-a :cutoff] 74 1 5 90 64 :breathe)
;; (berlin/filter-journey! [:filter-b :cutoff] 74 2 8 75 64 :smooth-step)

;; ============================================================
;; 8. JOURNEY CONDUCTOR — the four-movement arc
;;
;; Wire real functions to the phaedra-arc scaffold.
;; Each transition fires exactly once at the bar position shown.
;; The conductor is additive — fire it after the loops are running.
;; ============================================================

;; Ramp the s42 reverb master amp from `from` to `to` over `beats` beats.
;; Uses `verb-node` (bound after instantiate-patch! + stereo reverb setup).
(defn drone-ramp! [from to beats]
  (let [bpm      (get-bpm)
        ms-total (* beats (/ 60000.0 bpm))
        steps    40
        step-ms  (/ ms-total steps)
        delta    (/ (- to from) steps)]
    (future
      (loop [i 0 v from]
        (when (<= i steps)
          (cljseq.sc/set-param! verb-node :amp v)
          (Thread/sleep (long step-ms))
          (recur (inc i) (+ v delta)))))
    {:from from :to to :beats beats}))

(defn begin-emergence! []
  ;; Loops already running. Start filter journeys here.
  (berlin/filter-journey! [:filter-a :cutoff] 74 1 5 90 64 :breathe)
  (berlin/filter-journey! [:filter-b :cutoff] 74 2 8 75 64 :smooth-step)
  ;; Drone builds from silence to full presence over 320 beats (~5 min)
  (when (bound? #'verb-node)
    (cljseq.sc/set-param! verb-node :amp 0.0)
    (drone-ramp! 0.0 0.18 320))
  (println "[journey] Movement 1: Emergence — filter opening + drone rising over 64 bars"))

(defn enter-crystallise! []
  ;; Increase mutation: pattern starts finding itself
  (berlin/set-mutation-trajectory!
    ost-a
    (trajectory :from 0.08 :to 0.22 :beats 64 :curve :smooth-step)
    32)
  (berlin/set-mutation-trajectory!
    ost-b
    (trajectory :from 0.06 :to 0.18 :beats 64 :curve :smooth-step)
    32)
  (println "[journey] Movement 2: Crystallisation — mutation rising"))

(defn enter-development! []
  ;; Patterns now alive and drifting. Add Phrygian dominant inflection:
  ;; swap the scale to Phrygian dominant for darker colour.
  ;; Comment out if you want to stay in pure Dorian.
  (let [phrygian-dom (make-scale :D 2 :phrygian-dominant)]
    (swap! ost-a assoc :scale phrygian-dom)
    (swap! ost-b assoc :scale phrygian-dom))
  (println "[journey] Movement 3: Development — Phrygian inflection"))

(defn begin-dissolution! []
  ;; Let both patterns fragment.
  (berlin/dissolve! ost-a {:rate 0.25})
  (berlin/dissolve! ost-b {:rate 0.20})
  ;; Close the filter over 80 bars.
  (berlin/filter-journey! [:filter-a :cutoff] 74 1 90 5 80 :smooth-step)
  (berlin/filter-journey! [:filter-b :cutoff] 74 2 75 8 80 :smooth-step)
  ;; Fade drone back to silence over 80 bars
  (when (bound? #'verb-node)
    (drone-ramp! 0.18 0.0 320))
  (println "[journey] Movement 4: Dissolution — patterns fragmenting, drone fading"))

(defn end-silence! []
  (stop-loop! :voice-a)
  (stop-loop! :voice-b)
  (stop-loop! :voice-c)
  (journey/stop-bar-counter!)
  (println "[journey] Silence"))

;; Wire the arc and fire the conductor.
;; Call this after the loops are running and you are ready to begin the full arc.
(defn fire-arc! []
  (journey/start-journey!
    [[0   begin-emergence!]
     [48  enter-crystallise!]
     [80  enter-development!]
     [240 begin-dissolution!]
     [320 end-silence!]]
    {:on-end #(println "[journey] Arc complete")}))

;; ============================================================
;; 9. THIRD VOICE — ToB counter-register introduction
;;
;; Voice C is the "Tyranny of Beauty" counter-register: lighter, higher,
;; melodic, lyrical. It runs a 13-beat cycle (prime against both 12 and 17),
;; creating its own set of phase relationships with both existing voices.
;;
;; TRANSITION PATTERN:
;;   a) Start voice-c at near-silent velocity (vel 30), frozen mutation
;;   b) Gradual fade-in via set-mutation-trajectory! as mutation wakes up
;;   c) Development: voice-c contributes to the harmonic texture
;;   d) Dissolve-out: voice-c fragments then falls silent
;;
;; This two→three→two arc can be played by the conductor or triggered live.
;; ============================================================

;; Voice C loop — 13-beat cycle (4 + 3 + 3 + 3 beats)
;; Start AFTER introduce-voice-c! is called.
(let [step-sleeps (atom (cycle [4 3 3 3]))]
  (deflive-loop :voice-c {:channel 3}
    (play! (journey/humanise (berlin/next-step! ost-c)
                             {:timing-variance-ms 5 :velocity-variance 4}))
    (sleep! (first (swap! step-sleeps rest)))))

;; SC path (uncomment to use instead of hardware):
(comment
  (let [step-sleeps (atom (cycle [4 3 3 3]))]
    (deflive-loop :voice-c {}
      (play! (journey/humanise (add-sc-voice-c (berlin/next-step! ost-c))
                               {:timing-variance-ms 5 :velocity-variance 4}))
      (sleep! (first (swap! step-sleeps rest))))))

(defn introduce-voice-c!
  "Begin voice C crystallization — fades in over ~48 bars.

  Voice C starts at low mutation, then mutation rises with a trajectory
  so the pattern gradually 'finds itself' over time. The low starting velocity
  in ost-c (45-55) means it enters quietly; you can bump velocity manually.

  Call this during Development (Movement 3) when the two-voice arc is established."
  []
  ;; Reset to origin, low velocity context — voice-c starts quiet
  (berlin/reset-ostinato! ost-c)
  (berlin/freeze-ostinato! ost-c)               ; start frozen — no mutation yet
  ;; Wake up mutation gradually: 0.0 → 0.12 over 48 passes
  (berlin/set-mutation-trajectory! ost-c
    (trajectory :from 0.0 :to 0.12 :beats 48 :curve :smooth-step)
    48)
  ;; Start the loop (if not already running — deflive-loop is idempotent)
  (println "[voice-c] Crystallizing in — 13-beat counter-register entering"))

(defn dissolve-voice-c!
  "Begin voice C dissolution — fragments over ~32 bars then stops.

  The pattern loses coherence, mutation rate spikes, then the loop stops.
  Call this when returning to the two-voice arc."
  []
  (berlin/dissolve! ost-c {:rate 0.35})
  ;; Schedule loop stop after 32 bars (128 beats at 4 beats/bar)
  (future
    (Thread/sleep (long (* 128 (/ 60000.0 (get-bpm) 4))))
    (stop-loop! :voice-c)
    (println "[voice-c] Dissolved — returning to two-voice arc")))

;; Phase analysis for the three-voice system:
(comment
  ;; A/B (established pair):
  (journey/phase-pair 12 17)    ; LCM 204, 2.04 min
  ;; A/C:
  (journey/phase-pair 12 13)    ; LCM 156, 1.56 min
  ;; B/C:
  (journey/phase-pair 17 13))   ; LCM 221, 2.21 min

;; ============================================================
;; 10. FRIPPERTRONICS — Sound on Sound accumulation (optional arc)
;;
;; Route voice C through a temporal buffer with high feedback and
;; tape-style Color. Earlier passes fade while new material accumulates.
;;
;; This can replace or augment the three-voice transition:
;;   - Run voice-c through sos-send! instead of plain play!
;;   - The temporal buffer plays back voice-c delayed by 64 beats (~38 sec at 100 BPM)
;;   - Material accumulates and fades per generation (:tape Color)
;;   - Freeze the buffer at a peak moment; sculpt with :dark Color
;;
;; Architecture:
;;   live loop → sos-send! → play! (immediate) + temporal buffer (delayed)
;;                                                ↑ feedback ──────────┘
;; ============================================================

;; Create the Frippertronics SOS buffer — 64-beat loop depth
(def sos (frippertronics! :sos {:zone :z6 :feedback 0.75 :color :tape}))

;; Tape-drift wrapper — adds analog wobble to voice C before it goes in
(def tape-c (berlin/tape-drift ost-c 6))

;; Frippertronics loop — replaces or runs alongside :voice-c
;; Each step plays immediately AND feeds into the 64-beat buffer.
;; After 64 beats, the buffer starts playing back the accumulated material.
(comment
  (let [step-sleeps (atom (cycle [4 3 3 3]))]
    (deflive-loop :voice-c-sos {}
      (play! (sos-send! :sos
               (journey/humanise (berlin/tick-tape! tape-c)
                                 {:timing-variance-ms 5 :velocity-variance 4})))
      (sleep! (first (swap! step-sleeps rest)))))

  ;; What to listen for:
  ;;   0:00–0:38  — voice C plays live; nothing from the buffer yet
  ;;   0:38       — first buffer echo arrives (64 beats at 100 BPM ≈ 38 sec)
  ;;   0:38–1:16  — two layers: live voice + first echo; both in the buffer
  ;;   1:16+      — three or more generations accumulating; :tape Color
  ;;                fades each pass (vel × 0.95) and adds ±10¢ pitch flutter

  ;; Mid-arc — darken the SOS tail while keeping live voice bright:
  (temporal-buffer-color! :sos :dark)    ; each generation gets darker/longer

  ;; Freeze: stop new input; frozen content keeps cycling and fading
  (temporal-buffer-hold! :sos true)

  ;; Open it back up for a new layer:
  (temporal-buffer-hold! :sos false)

  ;; At peak density — stop the live feed and let the buffer decay to silence:
  (stop-loop! :voice-c-sos)
  ;; Buffer tail fades over max-generation × vel-decay ≈ 12 passes × 0.95^12 ≈ 54% velocity
  )

;; ============================================================
;; 11. VARIATIONS — use at the REPL during Movement 3
;; ============================================================

;; Crystallize voice-a toward a specific target pattern:
(comment
  (berlin/crystallize! ost-a
    [{:pitch/midi 38 :dur/beats 2.5 :mod/velocity 80}
     {:pitch/midi 45 :dur/beats 2.5 :mod/velocity 72}   ; A2 — sixth
     {:pitch/midi 43 :dur/beats 2.5 :mod/velocity 68}
     {:pitch/midi 38 :dur/beats 2.5 :mod/velocity 75}]  ; back to root
    {:rate 0.3}))

;; Deflect voice-b toward tritone territory (Bb — the Sabbath interval):
(comment
  (berlin/deflect-ostinato! ost-b
    [{:pitch/midi 49 :dur/beats 4.0 :mod/velocity 60}   ; Db3 — flat 7 (Locrian)
     {:pitch/midi 44 :dur/beats 5.0 :mod/velocity 55}   ; Ab2 — tritone from D
     {:pitch/midi 50 :dur/beats 5.0 :mod/velocity 62}]  ; D3 — resolve
    {:rate 0.2}))

;; Slow the tempo down toward doom:
(comment
  (set-bpm! 72))

;; Freeze voice-a for a static drone moment, then release:
(comment
  (berlin/freeze-ostinato! ost-a)
  ;; ... wait ...
  (berlin/thaw-ostinato! ost-a 0.15))

;; Scale shift — move voice-c to Lydian for a brighter counter-melody:
(comment
  (let [d-lydian (make-scale :D 4 :lydian)]
    (swap! ost-c assoc :scale d-lydian)))

;; ============================================================
;; 12. TEARDOWN
;; ============================================================

(comment
  (stop-loop! :voice-a)
  (stop-loop! :voice-b)
  (stop-loop! :voice-c)
  (stop-loop! :voice-c-sos)
  (journey/stop-bar-counter!)
  (end-session!))
