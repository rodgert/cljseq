; SPDX-License-Identifier: EPL-2.0
;;
;; Waveshaper Synthesis + Spectral Bridge -- cljseq SCLang showcase
;; ================================================================
;;
;; Demonstrates:
;;   1. :wshape-saturate -- tanh soft saturation; drive as arc target
;;   2. :wshape-fold     -- wavefold; non-monotonic harmonic evolution
;;   3. Drive arc comparison -- same trajectory, different timbral result
;;   4. bind-spectral!   -- closing the loop: spectral analysis drives live
;;                          SC node parameters in real time
;;   5. Chaos -> waveshaper chain -- lorenz-fm feeding into drive control
;;
;; What waveshaping adds to the vocabulary:
;;
;;   cljseq already has tonal synthesis (FM, oscillators), physical models
;;   (KS strings, DynKlank resonators), and chaos oscillators. Waveshaping
;;   is the controlled non-linear distortion layer that bridges between them:
;;
;;   - Saturation (tanh): adds odd harmonics monotonically as drive rises.
;;     The timbre evolves predictably: sine -> triangle -> square.
;;     Feels like tube amplifier overdrive.
;;
;;   - Wavefold (fold2): adds both even and odd harmonics in a pattern that
;;     changes character non-monotonically with drive. Each fold depth is a
;;     distinct timbral state, not just "more." Feels like West Coast synthesis.
;;
;;   Both are natural apply-trajectory! targets. A 32-bar saturation arc is
;;   a compositional statement: the piece physically transforms over time.
;;
;; What bind-spectral! adds:
;;
;;   The spectral analysis loop (cljseq.spectral) watches a Temporal Buffer
;;   and publishes centroid/density/blur to the ctrl tree. bind-spectral!
;;   connects that analysis to a live SC node parameter -- the synthesis
;;   responds to what is being played, not just to explicit automation.
;;
;;   Dense harmonically complex playing -> high spectral density -> opens filter
;;   Sparse playing -> low density -> closes filter, cleans up
;;   This is audio-reactive synthesis without any signal routing to SC.
;;
;; Prerequisites:
;;   SuperCollider running, scsynth at localhost:57110.
;;   (For bind-spectral!: a Temporal Buffer and spectral SAM loop running.)
;;
;;   (session!)
;;   (connect-sc!)
;;   (send-all-synthdefs!)

(require '[cljseq.user   :refer :all])
(require '[cljseq.clock  :as clock])

;; ---------------------------------------------------------------------------
;; Section 1. Setup
;; ---------------------------------------------------------------------------

(session!)
(connect-sc!)
(send-all-synthdefs!)

;; ---------------------------------------------------------------------------
;; Section 2. Saturation -- hear the drive arc manually
;;
;;   Start a sustained node and update :drive by hand.
;;   Listen for the progression: clean sine -> warm saturation -> square-ish.
;;   The change is gradual and musical: no hard edges, just increasing density.
;; ---------------------------------------------------------------------------

(def sat-node (sc/sc-synth! :wshape-saturate {:freq 220 :drive 0.0 :amp 0.4}))

;; Step through drive values -- listen for the harmonic buildup:
;; (sc/set-param! sat-node :drive 0.1)
;; (sc/set-param! sat-node :drive 0.3)
;; (sc/set-param! sat-node :drive 0.5)
;; (sc/set-param! sat-node :drive 0.75)
;; (sc/set-param! sat-node :drive 1.0)
;; (sc/free-synth! sat-node)

;; ---------------------------------------------------------------------------
;; Section 3. Wavefold -- non-monotonic harmonic evolution
;;
;;   The same drive values produce qualitatively different timbres here.
;;   Around drive=0.2-0.3 the first fold creates a characteristically
;;   bright metallic quality. Around 0.5-0.6 the multiple folds produce
;;   a dense texture with both even and odd harmonics. At 0.8+ the
;;   folded spectrum becomes almost percussive.
;; ---------------------------------------------------------------------------

(def fold-node (sc/sc-synth! :wshape-fold {:freq 220 :drive 0.0 :amp 0.4}))

;; (sc/set-param! fold-node :drive 0.15)  ; <- first fold edge, bright
;; (sc/set-param! fold-node :drive 0.3)   ; <- second fold, even harmonics
;; (sc/set-param! fold-node :drive 0.55)  ; <- dense fold pattern
;; (sc/set-param! fold-node :drive 0.8)   ; <- heavily folded
;; (sc/free-synth! fold-node)

;; ---------------------------------------------------------------------------
;; Section 4. Drive arc comparison -- same trajectory, different voices
;;
;;   The identical s-curve trajectory applied to both saturation and fold.
;;   The trajectory is a first-class value -- both voices share it.
;;   The timbral result is completely different because the transfer functions
;;   are different, even though the automation is identical.
;; ---------------------------------------------------------------------------

(def sat-a  (sc/sc-synth! :wshape-saturate {:freq 165 :drive 0.02 :amp 0.35 :pan -0.4}))
(def fold-a (sc/sc-synth! :wshape-fold     {:freq 220 :drive 0.02 :amp 0.35 :pan  0.4}))

(def drive-arc
  (trajectory :from 0.02 :to 0.85 :beats 48 :curve :s-curve :start (now)))

(def cancel-drive!
  [(apply-trajectory! sat-a  :drive drive-arc)
   (apply-trajectory! fold-a :drive drive-arc)])

;; Both voices start clean and evolve over 48 beats -- to opposite timbral destinations.
;; (run! #(%) cancel-drive!)
;; (sc/free-synth! sat-a)
;; (sc/free-synth! fold-a)

;; ---------------------------------------------------------------------------
;; Section 5. bind-spectral! -- audio-reactive drive control
;;
;;   Requires: a Temporal Buffer capturing events + the spectral SAM loop.
;;
;;   (def buf (start-temporal-buffer! :main 4.0))
;;   (def sam (start-spectral! :main :cadence 1/4))
;;
;;   Once running, the spectral loop publishes :spectral/density [0..1]
;;   to the ctrl tree every 1/4 beat. Dense harmonically complex playing
;;   pushes density up; sparse playing pushes it down.
;;
;;   bind-spectral! connects this to the waveshaper's :drive parameter:
;;     density=0.0 (silent/sparse) -> drive=0.05 (clean)
;;     density=1.0 (dense playing) -> drive=0.75 (saturated)
;;
;;   The synthesis literally responds to what is being played.
;; ---------------------------------------------------------------------------

(def spectral-sat (sc/sc-synth! :wshape-saturate {:freq 220 :drive 0.05 :amp 0.4}))

;; Uncomment once the spectral SAM loop is running:
;; (def cancel-spectral-drive!
;;   (bind-spectral! spectral-sat :drive :spectral/density
;;                   (fn [density] (+ 0.05 (* 0.7 density)))))
;;
;; Now play into your Temporal Buffer -- the waveshaper responds live.
;;
;; You can also bind centroid to filter cutoff (if you add a filter to the chain):
;; (bind-spectral! spectral-sat :freq :spectral/centroid
;;                 (fn [centroid] (* centroid 2.0)))  ; transpose by centroid
;;
;; Stop:
;; (cancel-spectral-drive!)
;; (sc/free-synth! spectral-sat)

;; ---------------------------------------------------------------------------
;; Section 6. Chaos -> waveshaper chain
;;
;;   A Lorenz-FM node drives the melodic content.
;;   The chaos level is sampled from an arc and also applied to a waveshaper
;;   drive -- the more chaotic the pitch, the more saturated the timbre.
;;   Chaos and distortion rise together: a compound destabilization arc.
;; ---------------------------------------------------------------------------

(def chaos-node (sc/sc-synth! :lorenz-fm {:freq 110 :chaos 0.1 :fm-depth 0.2 :amp 0.3 :pan -0.3}))
(def drive-node (sc/sc-synth! :wshape-saturate {:freq 220 :drive 0.05 :amp 0.35 :pan 0.3}))

(def destab-arc
  (trajectory :from 0.1 :to 0.7 :beats 64 :curve :s-curve :start (now)))

(def cancel-destab!
  [(apply-trajectory! chaos-node :chaos drive-arc)           ; pitch chaos rises
   (apply-trajectory! drive-node :drive destab-arc)])        ; distortion tracks it

;; (run! #(%) cancel-destab!)
;; (sc/free-synth! chaos-node)
;; (sc/free-synth! drive-node)

;; ---------------------------------------------------------------------------
;; Section 7. Live loop with drive-arc per note
;;
;;   Each loop iteration samples the current point on the drive-arc.
;;   Early notes are clean, late notes are saturated -- timbre evolves
;;   through the melody rather than being set per-note.
;; ---------------------------------------------------------------------------

(def per-note-arc
  (trajectory :from 0.02 :to 0.9 :beats 64 :curve :s-curve :start (now)))

(def phrygian-pool
  (let [root 52]  ; E3 phrygian
    (into []
          (mapcat (fn [oct] (map #(+ root % (* oct 12)) [0 1 3 5 7 8 10]))
                  [0 1]))))

(deflive-loop :wshape-melody {}
  (let [beat  (now)
        drive (clock/sample per-note-arc beat)
        note  (rand-nth phrygian-pool)]
    (sc/sc-play! {:synth  :wshape-fold
                  :freq   (clock/midi->hz note)
                  :drive  drive
                  :amp    0.4
                  :decay  3.0}))
  (sleep! (rand-nth [1/2 1/2 1 1 3/2])))

;; (stop-loop! :wshape-melody)

;; ---------------------------------------------------------------------------
;; Section 8. Clean up
;; ---------------------------------------------------------------------------

;; (stop!)
;; (end-session!)
