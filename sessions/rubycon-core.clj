; SPDX-License-Identifier: EPL-2.0
;;
;; rubycon-core.clj — Kosmische session: Rubycon two-voice polyrhythmic core
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
;; Gear assumed:
;;   Ch 1 — bass/sub voice (Sub37, Minimoog, or similar — long filter, portamento)
;;   Ch 2 — mid voice (second synth or second patch — shorter decay)
;;   Ch 3 — drone/pad (sustained tone, full resonance, filter nearly closed)
;;   A MIDI-to-audio path (sidecar + IAC or direct hardware)
;;
;; MIDI note reference:
;;   D2=38  E2=40  F2=41  G2=43  A2=45  C3=48  D3=50
;;   D3=50  F3=53  G3=55  A3=57  C4=60  D4=62

;; cljseq.core is the default REPL ns and already owns play!, sleep!,
;; deflive-loop, stop-loop!, set-bpm!, now, etc.
;; Only pull in what core doesn't have:
(require '[cljseq.journey :as journey]
         '[cljseq.berlin  :as berlin]
         '[cljseq.user    :refer [session! start-sidecar! stop-sidecar!
                                  make-scale trajectory]])

;; ============================================================
;; 0. SESSION BOOT
;; ============================================================

;; Start clock at 100 BPM — TD canonical Phaedra/Rubycon tempo.
;; Drop to 72 for Zeit/Irrlicht territory (heavy, pre-sequencer doom).
(session! :bpm 100)

;; Connect MIDI output. Adjust port index for your setup.
;; (list-midi-ports) to see available ports.
;;
;; Note: worktrees don't have their own build/ dir. Locate the binary from
;; the main checkout by walking up from this file's git worktree root.
(def ^:private sidecar-bin
  (let [main-root (-> (clojure.java.shell/sh "git" "rev-parse" "--git-common-dir")
                      :out clojure.string/trim
                      (clojure.java.io/file "..")
                      .getCanonicalPath)]
    (str main-root "/build/cpp/cljseq-sidecar/cljseq-sidecar")))

(start-sidecar! :midi-port 0 :binary sidecar-bin)

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
;; 3. OSTINATI — two voices, incommensurate lengths
;;
;; Voice A: 4-step pattern, sleeps 3 beats/step → 12-beat cycle
;; Voice B: 3-step pattern, sleeps 5+6+6 beats/step → 17-beat cycle
;;
;; Each loop iteration plays ONE step then sleeps.
;; The notes are long and sustained — Zeit territory.
;; The polyrhythmic drift is the composition.
;; ============================================================

;; Voice A — bass register, root motion, 12-beat total cycle
;; D2 tonic anchor with minor-third and fifth movement.
;; Sleeps 3 beats between steps; total pass = 4 × 3 = 12 beats.
(def ost-a
  (berlin/ostinato
    [{:pitch/midi 38 :dur/beats 2.5 :velocity 80}   ; D2 — root
     {:pitch/midi 43 :dur/beats 2.5 :velocity 72}   ; G2 — fourth
     {:pitch/midi 41 :dur/beats 2.5 :velocity 68}   ; F2 — minor third
     {:pitch/midi 40 :dur/beats 2.5 :velocity 75}]  ; E2 — second (Dorian colour)
    {:scale rubycon-scale :mutation-rate 0.08 :drift :random}))

;; Voice B — mid register, prime-length cycle, creates phase drift with A
;; Three steps at unequal sleeps: 5, 6, 6 beats → 17-beat cycle.
;; Unequal step spacing gives an irregular, breathing feel.
(def ost-b
  (berlin/ostinato
    [{:pitch/midi 50 :dur/beats 4.0 :velocity 65}   ; D3 — octave root
     {:pitch/midi 53 :dur/beats 5.0 :velocity 58}   ; F3 — minor third
     {:pitch/midi 55 :dur/beats 5.0 :velocity 62}]  ; G3 — fifth
    {:scale rubycon-scale :mutation-rate 0.06 :drift :random}))

;; ============================================================
;; 4. BAR COUNTER — the shared timeline
;; ============================================================

(journey/start-bar-counter!)

;; Verify it's counting:
;; (Thread/sleep 2000) (journey/current-bar)  ; should be > 0

;; ============================================================
;; 5. VOICE LOOPS — fire both, they start phase-locked
;;
;; :restart-on-bar true snaps both loops to the next bar boundary
;; before entering, so voice-a and voice-b start from beat 0 together.
;; The drift begins immediately after that aligned start.
;; ============================================================

;; Voice A: step every 3 beats, full 12-beat cycle
(deflive-loop :voice-a {:channel 1 :restart-on-bar true}
  (berlin/with-portamento 1 80
    (play! (berlin/next-step! ost-a)))
  (sleep! 3))

;; Voice B: step at 5 / 6 / 6 beat spacing, full 17-beat cycle
;; Uses a simple phase-index to alternate the sleep durations.
(let [step-sleeps (atom (cycle [5 6 6]))]
  (deflive-loop :voice-b {:channel 2 :restart-on-bar true}
    (play! (journey/humanise (berlin/next-step! ost-b)
                     {:timing-variance-ms 8 :velocity-variance 6}))
    (sleep! (first (swap! step-sleeps rest)))))

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
;; 6. FILTER JOURNEY — open the filter over 64 bars
;;
;; Start this immediately after the loops are running.
;; The filter opening IS Movement 2 (Crystallisation).
;; CC 74 = filter cutoff on most synths; adjust for yours.
;; ============================================================

;; (berlin/filter-journey! [:filter-a :cutoff] 74 1 5 90 64 :breathe)
;; (berlin/filter-journey! [:filter-b :cutoff] 74 2 8 75 64 :smooth-step)

;; ============================================================
;; 7. JOURNEY CONDUCTOR — the four-movement arc
;;
;; Wire real functions to the phaedra-arc scaffold.
;; Each transition fires exactly once at the bar position shown.
;; The conductor is additive — fire it after the loops are running.
;; ============================================================

(defn begin-emergence! []
  ;; Loops already running. Start filter journeys here.
  (berlin/filter-journey! [:filter-a :cutoff] 74 1 5 90 64 :breathe)
  (berlin/filter-journey! [:filter-b :cutoff] 74 2 8 75 64 :smooth-step)
  (println "[journey] Movement 1: Emergence — filter opening over 64 bars"))

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
  (println "[journey] Movement 4: Dissolution — patterns fragmenting"))

(defn end-silence! []
  (stop-loop! :voice-a)
  (stop-loop! :voice-b)
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
;; 8. VARIATIONS — use at the REPL during Movement 3
;; ============================================================

;; Crystallize voice-a toward a specific target pattern:
(comment
  (berlin/crystallize! ost-a
    [{:pitch/midi 38 :dur/beats 2.5 :velocity 80}
     {:pitch/midi 45 :dur/beats 2.5 :velocity 72}   ; A2 — sixth
     {:pitch/midi 43 :dur/beats 2.5 :velocity 68}
     {:pitch/midi 38 :dur/beats 2.5 :velocity 75}]  ; back to root
    {:rate 0.3}))

;; Deflect voice-b toward tritone territory (Bb — the Sabbath interval):
(comment
  (berlin/deflect-ostinato! ost-b
    [{:pitch/midi 49 :dur/beats 4.0 :velocity 60}   ; Db3 — flat 7 (Locrian)
     {:pitch/midi 44 :dur/beats 5.0 :velocity 55}   ; Ab2 — tritone from D
     {:pitch/midi 50 :dur/beats 5.0 :velocity 62}]  ; D3 — resolve
    {:rate 0.2}))

;; Slow the tempo down toward doom:
(comment
  (set-bpm! 72))

;; Freeze voice-a for a static drone moment, then release:
(comment
  (berlin/freeze-ostinato! ost-a)
  ;; ... wait ...
  (berlin/thaw-ostinato! ost-a 0.15))

;; Phase-pair check for a potential third voice (prime against both):
(comment
  (journey/phase-pair 12 19)   ; LCM 228 ≈ 2.3 min
  (journey/phase-pair 17 19))  ; LCM 323 ≈ 3.2 min

;; ============================================================
;; 9. TEARDOWN
;; ============================================================

(comment
  (stop-loop! :voice-a)
  (stop-loop! :voice-b)
  (journey/stop-bar-counter!)
  (end-session!))
