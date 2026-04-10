; SPDX-License-Identifier: EPL-2.0
;;
;; DynKlank Physical Modeling -- cljseq SCLang showcase
;; =====================================================
;;
;; Demonstrates:
;;   1. :klank-bell  -- inharmonic bell via DynKlank resonator bank
;;   2. :klank-bars  -- marimba/vibraphone bar modes
;;   3. :klank-ensemble patch -- three voices in shared acoustic space
;;   4. Partial ratio detuning -- moving between instrument families
;;   5. Decay arc -- DynKlank resonator decay times as trajectory targets
;;   6. bind-spectral! + DynKlank -- audio-reactive decay sculpting
;;
;; What DynKlank adds to the vocabulary:
;;
;;   Karplus-Strong models plucked strings via a recirculating comb filter.
;;   DynKlank models struck resonators (bells, bars, plates) via a bank of
;;   parallel second-order band-pass filters -- each filter represents one
;;   resonant mode of the physical object.
;;
;;   The character of the instrument is encoded entirely in the partial ratios
;;   (:f1-:f4) and decay times (:d1-:d4):
;;
;;   Bell (inharmonic, long decay):
;;     f2=2.756 f3=5.404 f4=8.933 -- non-integer multiples, ambiguous pitch
;;     d1=1.2s d2=0.9s d3=0.55s   -- lower partials sustain longer
;;
;;   Marimba bar (near-harmonic, moderate decay):
;;     f2=2.756 f3=5.404           -- slightly stretched harmonic series
;;     d1=0.8s d2=0.4s             -- short decay, percussive
;;
;;   Vibraphone (tuned bar, long decay + tremolo):
;;     f2=4.0 (two octaves, typical undercut tuning)
;;     d1=2.5s d2=1.5s             -- long singing decay
;;
;;   The :freq-scale parameter retunes all modes simultaneously, making it
;;   the primary pitch control. Individual :fN values are ratios relative to
;;   freq-scale -- changing them morphs the instrument family.
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
;; Section 1. Setup
;; ---------------------------------------------------------------------------

(session!)
(connect-sc!)
(send-all-synthdefs!)

;; ---------------------------------------------------------------------------
;; Section 2. Bell -- single voice, partial ratio exploration
;;
;;   Listen to how the f2 ratio changes the perceived pitch centre.
;;   At f2=2.756 (default) the pitch feels ambiguous.
;;   At f2=2.0 (octave) the pitch becomes clearer but loses bell character.
;;   At f2=3.1 (sharp) the bell sounds harder, more metallic.
;; ---------------------------------------------------------------------------

;; Default bell partials -- classic inharmonic character
(sc/sc-play! {:synth :klank-bell :freq-scale 440 :amp 0.6})

;; Larger bell -- lower freq-scale, longer decays
(sc/sc-play! {:synth :klank-bell :freq-scale 220 :d1 3.0 :d2 2.2 :d3 1.4 :d4 0.8 :amp 0.7})

;; Harder, more metallic -- f2 ratio slightly sharp of default
(sc/sc-play! {:synth :klank-bell :freq-scale 440 :f2 3.1 :f3 5.8 :amp 0.55})

;; ---------------------------------------------------------------------------
;; Section 3. Bars -- marimba and vibraphone variants
;; ---------------------------------------------------------------------------

;; Marimba character -- short percussive decay
(sc/sc-play! {:synth :klank-bars :freq-scale 440 :amp 0.65 :d1 0.4 :d2 0.2})

;; Vibraphone character -- long singing decay, octave-tuned f2
(sc/sc-play! {:synth :klank-bars :freq-scale 440 :f2 4.0 :a3 0.05 :d1 2.5 :d2 1.5 :amp 0.6})

;; Glockenspiel -- high freq-scale, bright and long sustain
(sc/sc-play! {:synth :klank-bars :freq-scale 1760 :d1 3.0 :d2 2.5 :d3 2.0 :amp 0.5})

;; ---------------------------------------------------------------------------
;; Section 4. :klank-ensemble patch -- bell + bars in shared reverb space
;; ---------------------------------------------------------------------------

(def klank (sc/instantiate-patch! :klank-ensemble))

;; Tune the ensemble to an open fifth (A2 / E3 / A3)
(sc/set-patch-param! klank :bars-freq  220)
(sc/set-patch-param! klank :bell1-freq 330)
(sc/set-patch-param! klank :bell2-freq 440)

;; Add more reverb for a larger space
(sc/set-patch-param! klank :effects-room 0.85)
(sc/set-patch-param! klank :effects-mix  0.5)

;; (sc/free-patch! klank)

;; ---------------------------------------------------------------------------
;; Section 5. Decay arc -- resonator decay as compositional arc target
;;
;;   DynKlank decay times (:d1, :d2, :d3) are natural arc targets.
;;   Sweeping from long to short: the sound shrinks from a cathedral bell
;;   to a dry woodblock. Sweeping from short to long: a xylophone becomes
;;   a vibraphone in real time.
;; ---------------------------------------------------------------------------

(def bell-node (sc/sc-synth! :klank-bell {:freq-scale 330 :d1 0.3 :d2 0.2 :d3 0.1 :amp 0.6}))

;; Grow the decay -- xylophone -> vibraphone -> bell
(def cancel-decay-grow!
  [(apply-trajectory! bell-node :d1
     (trajectory :from 0.3 :to 3.5 :beats 32 :curve :exp :start (now)))
   (apply-trajectory! bell-node :d2
     (trajectory :from 0.2 :to 2.5 :beats 32 :curve :exp :start (now)))
   (apply-trajectory! bell-node :d3
     (trajectory :from 0.1 :to 1.5 :beats 32 :curve :exp :start (now)))])

;; (run! #(%) cancel-decay-grow!)
;; (sc/free-synth! bell-node)

;; ---------------------------------------------------------------------------
;; Section 6. Live bell melody over sustained bar drone
;; ---------------------------------------------------------------------------

;; Sustained bar drone -- vibraphone-like
(def bar-drone (sc/sc-synth! :klank-bars {:freq-scale 110 :f2 4.0 :d1 4.0 :d2 3.0 :amp 0.4}))

;; Pentatonic minor pool for melody
(def penta-pool
  (let [root 57]  ; A3 = MIDI 69, use A4
    (into []
          (mapcat (fn [oct] (map #(+ root % (* oct 12)) [0 3 5 7 10]))
                  [0 1]))))

(deflive-loop :klank-melody {}
  (let [note  (rand-nth penta-pool)
        freq  (clock/midi->hz note)
        ;; Alternate between bell and bar character
        synth (rand-nth [:klank-bell :klank-bars])]
    (sc/sc-play! {:synth synth
                  :freq-scale freq
                  :amp (rand-nth [0.5 0.55 0.6])
                  :d1  (rand-nth [0.6 0.9 1.4])
                  :pan (- (* (rand) 0.8) 0.4)}))
  (sleep! (rand-nth [1/2 1 1 2 2 3/2])))

;; (stop-loop! :klank-melody)
;; (sc/free-synth! bar-drone)

;; ---------------------------------------------------------------------------
;; Section 7. bind-spectral! + DynKlank -- audio-reactive decay sculpting
;;
;;   Dense playing -> short, dry decays (chaotic, percussive)
;;   Sparse playing -> long, singing decays (open, resonant)
;;   The physical model literally grows or shrinks in response to performance.
;;
;;   Requires: Temporal Buffer + spectral SAM loop running.
;; ---------------------------------------------------------------------------

(def spectral-bell (sc/sc-synth! :klank-bell {:freq-scale 440 :d1 2.0 :d2 1.5 :amp 0.5}))

;; Uncomment once spectral SAM is running:
;; (def cancel-spectral-decay!
;;   (bind-spectral! spectral-bell :d1 :spectral/density
;;                   (fn [density]
;;                     ;; Dense playing -> short decay; sparse -> long
;;                     (+ 0.3 (* 2.5 (- 1.0 density))))))

;; (cancel-spectral-decay!)
;; (sc/free-synth! spectral-bell)

;; ---------------------------------------------------------------------------
;; Section 8. Clean up
;; ---------------------------------------------------------------------------

;; (stop-loop! :klank-melody)
;; (sc/free-patch! klank)
;; (stop!)
;; (end-session!)
