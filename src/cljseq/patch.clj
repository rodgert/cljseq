; SPDX-License-Identifier: EPL-2.0
(ns cljseq.patch
  "Signal graph patches — backend-agnostic multi-node synthesis chains.

  A patch is a Clojure map describing a directed graph of synthesis nodes
  connected by named audio/control buses. It is pure data: inspectable,
  serializable to EDN, and compilable to multiple backends via the
  `compile-patch` multimethod.

  ## Patch structure

    {:buses  {bus-key {:channels int :rate :audio|:control}}
     :nodes  [{:id    node-key
               :synth synth-registry-key
               :args  {arg-key value}   ; overrides synth defaults
               :in    {arg-key bus-key} ; bus-key resolved to SC bus index at instantiation
               :out   {arg-key bus-key|integer}}]
     :params {param-key [node-id arg-key]}} ; externally addressable params

  Bus keys appearing in :in/:out values are merged into :args, remaining as
  keyword placeholders until resolved to integer indices at instantiation time.
  Integer values in :out (e.g. {:out 0}) are hardware bus indices, passed through as-is.

  ## Defining a patch

    (defpatch! :reverb-chain
      {:buses  {:dry {:channels 1 :rate :audio}}
       :nodes  [{:id :osc  :synth :sine-bus  :args {:freq 440}
                 :out {:out-bus :dry}}
                {:id :verb :synth :reverb-bus :args {:room 0.6 :mix 0.4}
                 :in  {:in-bus :dry}
                 :out {:out 0}}]
       :params {:freq [:osc :freq]
                :room [:verb :room]}})

  ## Compiling a patch

    (compile-patch :sc :reverb-chain)
    ;=> {:patch-name :reverb-chain :buses {...} :nodes [...] :params {...}}

    (compile-patch :pd :reverb-chain)
    ;=> {:pd-text \"#N canvas...\" :patch {...}}

  ## Running a patch (requires cljseq.sc)

    (require '[cljseq.sc :as sc])
    (sc/connect-sc!)
    (def inst (sc/instantiate-patch! :reverb-chain))
    (sc/set-patch-param! inst :freq 550)
    (sc/set-patch-param! inst :room 0.8)
    (sc/free-patch! inst)

  ## Patches as values

  Because a patch is a Clojure map, it composes with standard operations:

    ;; Inspect nodes
    (map :id (:nodes (get-patch :reverb-chain)))

    ;; Find all synths used
    (->> (get-patch :reverb-chain) :nodes (map :synth) set)

    ;; Serialize to EDN — round-trips cleanly
    (pr-str (get-patch :reverb-chain))"
  (:require [clojure.string  :as str]
            [clojure.edn     :as edn]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private patch-registry (atom {}))

(defn defpatch!
  "Register a named patch definition.

  `patch-map` must contain:
    :nodes — ordered vector of node maps (instantiation order matters for SC)

  Optional:
    :buses  — {bus-key {:channels int :rate :audio|:control}}
    :params — {param-key [node-id arg-key]} for external param control

  Node map keys:
    :id     — unique keyword within this patch
    :synth  — synth registry keyword
    :args   — arg overrides merged with synth defaults
    :in     — {arg-key bus-key} bus inputs (bus-key resolved at instantiation)
    :out    — {arg-key bus-key|integer} bus outputs

  Idempotent: re-registering replaces the previous definition."
  [patch-name patch-map]
  (when-not (keyword? patch-name)
    (throw (ex-info "defpatch!: name must be a keyword" {:name patch-name})))
  (when-not (vector? (:nodes patch-map))
    (throw (ex-info "defpatch!: :nodes must be a vector" {:patch patch-name})))
  (swap! patch-registry assoc patch-name
         (assoc patch-map :name patch-name))
  patch-name)

(defn get-patch
  "Return the patch definition for `patch-name`, or nil."
  [patch-name]
  (get @patch-registry patch-name))

(defn patch-names
  "Return all registered patch names."
  []
  (keys @patch-registry))

(defn patch-params
  "Return the :params map for a patch — {param-key [node-id arg-key]}.
  Returns nil if the patch is not found or has no :params."
  [patch-name]
  (:params (get-patch patch-name)))

;; ---------------------------------------------------------------------------
;; Backend name maps
;; ---------------------------------------------------------------------------

(defn- load-backend-map [backend-kw]
  (when-let [r (io/resource (str "backends/" (name backend-kw) ".edn"))]
    (edn/read-string (slurp r))))

(def ^:private backend-maps
  (delay {:sc (load-backend-map :sc)
          :pd (load-backend-map :pd)}))

(defn canonical->backend
  "Resolve a canonical cljseq UGen name to a backend-specific name.
  Returns the canonical name unchanged if no mapping is found."
  [backend canonical-name]
  (or (get-in @backend-maps [backend canonical-name])
      canonical-name))

;; ---------------------------------------------------------------------------
;; compile-patch multimethod
;; ---------------------------------------------------------------------------

(defmulti compile-patch
  "Compile a patch to a backend-specific representation.

  Dispatch value is the backend keyword:
    :sc  — returns normalized SC instantiation map (buses, nodes, params)
    :pd  — returns {:pd-text string :patch map}

  `patch-name-or-map` is either a registered patch keyword or a raw patch map.

  Example:
    (compile-patch :sc :reverb-chain)
    (compile-patch :pd :reverb-chain)"
  (fn [backend _patch] backend))

(defn- resolve-patch [patch-name-or-map]
  (if (keyword? patch-name-or-map)
    (or (get-patch patch-name-or-map)
        (throw (ex-info "compile-patch: unknown patch" {:name patch-name-or-map})))
    patch-name-or-map))

(defn- merge-node-args
  "Merge a node's :args, :in, and :out maps into one flat args map.
  Bus-key values in :in/:out remain as keywords — resolved at instantiation."
  [{:keys [args in out] :or {args {} in {} out {}}}]
  (merge args in out))

;; ---------------------------------------------------------------------------
;; :sc backend
;; ---------------------------------------------------------------------------

(defmethod compile-patch :sc [_ patch-name-or-map]
  (let [patch  (resolve-patch patch-name-or-map)
        buses  (or (:buses patch) {})
        nodes  (:nodes patch)
        params (or (:params patch) {})]
    {:patch-name (:name patch)
     :buses      buses
     :nodes      (mapv (fn [node]
                         {:id    (:id node)
                          :synth (:synth node)
                          :args  (merge-node-args node)})
                       nodes)
     :params     params}))

;; ---------------------------------------------------------------------------
;; :pd backend — emit PureData .pd patch text
;; ---------------------------------------------------------------------------

(defn- pd-obj-name [synth-key]
  (let [bmap (get @backend-maps :pd {})]
    (if-let [entry (get bmap synth-key)]
      ;; :obj is a string (~ is invalid in EDN keywords)
      (:obj entry)
      ;; Fallback: append ~ for signal objects
      (str (name synth-key) "~"))))

(defn- pd-arg-str [args]
  (->> args
       (remove (fn [[_ v]] (keyword? v)))   ; strip unresolved bus refs
       (map (fn [[_ v]] (if (float? v) (format "%.4f" v) (str v))))
       (str/join " ")))

(defmethod compile-patch :pd [_ patch-name-or-map]
  (let [patch    (resolve-patch patch-name-or-map)
        nodes    (:nodes patch)
        n        (count nodes)
        obj-lines (map-indexed
                    (fn [i node]
                      (let [obj  (pd-obj-name (:synth node))
                            astr (pd-arg-str (merge-node-args node))]
                        (format "#X obj 50 %d %s%s;"
                                (* (inc i) 80)
                                obj
                                (if (str/blank? astr) "" (str " " astr)))))
                    nodes)
        ;; Simple linear chain: outlet 0 of node i → inlet 0 of node i+1
        conn-lines (map (fn [i] (format "#X connect %d 0 %d 0;" i (inc i)))
                        (range (dec n)))
        patch-name (or (:name patch) "patch")
        header     (format "#N canvas 0 0 600 %d %s 10;" (* (+ n 2) 80) (name patch-name))]
    {:pd-text (str header "\n"
                   (str/join "\n" obj-lines)
                   (when (seq conn-lines)
                     (str "\n" (str/join "\n" conn-lines)))
                   "\n")
     :patch   patch}))

(defmethod compile-patch :default [backend _]
  (throw (ex-info "compile-patch: unknown backend" {:backend backend})))

;; ---------------------------------------------------------------------------
;; Built-in patches
;; ---------------------------------------------------------------------------

;; Registered at load time. Synths :sine-bus and :reverb-bus must be loaded
;; in cljseq.synth (added to resources/synths/).

(defonce ^:private _patches-loaded
  (do
    (defpatch! :reverb-chain
      {:buses  {:dry {:channels 1 :rate :audio}}
       :nodes  [{:id   :osc
                 :synth :sine-bus
                 :args  {:freq 440 :amp 0.5 :pan 0.0}
                 :out   {:out-bus :dry}}
                {:id   :verb
                 :synth :reverb-bus
                 :args  {:room 0.6 :mix 0.4 :damp 0.5 :amp 1.0}
                 :in    {:in-bus :dry}
                 :out   {:out 0}}]
       :params {:freq [:osc :freq]
                :amp  [:osc :amp]
                :room [:verb :room]
                :mix  [:verb :mix]
                :damp [:verb :damp]}})
    (defpatch! :granular-cloud
      {:buses  {:grain-bus {:channels 2 :rate :audio}}
       :nodes  [{:id    :grains
                 :synth :granular-voice
                 :args  {:density 8 :dur 0.12 :pos 0.5 :rate 1.0 :amp 0.6}
                 :out   {:out-bus :grain-bus}}
                {:id    :verb
                 :synth :reverb-bus
                 :args  {:room 0.7 :mix 0.4 :damp 0.3 :amp 1.0}
                 :in    {:in-bus :grain-bus}
                 :out   {:out 0}}]
       :params {:buf     [:grains :buf]
                :density [:grains :density]
                :pos     [:grains :pos]
                :rate    [:grains :rate]
                :dur     [:grains :dur]
                :room    [:verb :room]
                :mix     [:verb :mix]}})

    ;; ---------------------------------------------------------------------------
    ;; :s42 — Solar42-inspired multi-voice drone synthesizer patch.
    ;;
    ;; Structurally inspired by the voice topology of multi-oscillator analog drone
    ;; synthesizers (4 drone voices, 2 richer VCO voices, 2 experimental FM/AM/noise voices,
    ;; dual resonant filter, effects). Not a licensed reproduction of any specific instrument.
    ;; See doc/attribution.md for the hardware source that shaped this design.
    ;;
    ;; Signal flow:
    ;;   4 drone voices  ─┐
    ;;   2 VCO voices    ─┼─► voice-mix bus ─► s42-filter ─► effects ─► out
    ;;   2 papa voices   ─┘                    (dual RLPF)   (FreeVerb)
    ;;
    ;; Each voice writes stereo to :voice-mix via Out.ar — they sum on the bus.
    ;; The filter reads the summed mix, applies dual RLPF (Polivoks-inspired topology).
    ;;
    ;; Key params via set-patch-param! or apply-trajectory!:
    ;;   :filter-cutoff / :filter-res — filter character (the main sound-shaping control)
    ;;   :effects-mix   — dry/wet reverb blend
    ;;   :droneN-freq   — tune each drone voice independently
    ;;   :droneN-detune — spread the 6 saws within each drone voice
    ;;   :vcoN-freq / :vcoN-pwm-rate — VCO pitch and PWM modulation speed
    ;;   :papaN-freq / :papaN-fm-depth / :papaN-noise-mix — Papa voice timbre
    ;;
    ;; Scale vocabulary (see cljseq.scale) matching common drone synthesizer keyboard modes:
    ;;   "Gypsy"    → :double-harmonic
    ;;   "Gamelan"  → :pelog / :slendro
    ;;   "Japanese" → :in-scale / :in-sen / :hirajoshi
    ;;   "Arabian"  → :hijaz
    ;;   "Flamenco" → :phrygian-dominant
    ;;   "Whole Tone" → :whole-tone
    ;; ---------------------------------------------------------------------------
    (defpatch! :s42
      {:buses  {:voice-mix    {:channels 2 :rate :audio}
                :filtered-mix {:channels 2 :rate :audio}}
       :nodes  [;; ── Drone voices (6 detuned saws each, gate+tune per oscillator) ──
                {:id :drone1 :synth :s42-drone-voice
                 :args {:freq 110 :amp 0.22 :detune 0.02} :out {:out-bus :voice-mix}}
                {:id :drone2 :synth :s42-drone-voice
                 :args {:freq 146 :amp 0.22 :detune 0.02} :out {:out-bus :voice-mix}}
                {:id :drone3 :synth :s42-drone-voice
                 :args {:freq 165 :amp 0.22 :detune 0.02} :out {:out-bus :voice-mix}}
                {:id :drone4 :synth :s42-drone-voice
                 :args {:freq 220 :amp 0.22 :detune 0.02} :out {:out-bus :voice-mix}}
                ;; ── VCO voices (VarSaw + PWM + sub) ──
                {:id :vco1 :synth :s42-vco-voice
                 :args {:freq 220 :amp 0.3 :pwm-rate 0.7 :sub-amp 0.4} :out {:out-bus :voice-mix}}
                {:id :vco2 :synth :s42-vco-voice
                 :args {:freq 330 :amp 0.3 :pwm-rate 1.1 :sub-amp 0.3} :out {:out-bus :voice-mix}}
                ;; ── FM/AM/noise voices ──
                {:id :papa1 :synth :s42-papa-voice
                 :args {:freq 110 :amp 0.25 :fm-depth 0.6 :fm-ratio 2.0 :noise-mix 0.1}
                 :out {:out-bus :voice-mix}}
                {:id :papa2 :synth :s42-papa-voice
                 :args {:freq 55 :amp 0.25 :fm-depth 0.4 :fm-ratio 1.5 :noise-mix 0.2 :sh-rate 3.0}
                 :out {:out-bus :voice-mix}}
                ;; ── Filter stage: dual RLPF → Polivoks approximation ──
                {:id :filter :synth :s42-filter
                 :args {:cutoff 55 :res 0.3 :amp 0.8}
                 :in {:in-bus :voice-mix} :out {:out-bus :filtered-mix}}
                ;; ── Effects block ──
                {:id :verb :synth :reverb-bus
                 :args {:room 0.5 :mix 0.2 :damp 0.5 :amp 1.0}
                 :in {:in-bus :filtered-mix} :out {:out 0}}]
       :params {;; Filter (the Polivoks pair — biggest sound shaper)
                :filter-cutoff [:filter :cutoff]
                :filter-res    [:filter :res]
                ;; Effects
                :effects-mix   [:verb :mix]
                :effects-room  [:verb :room]
                ;; Drone voice pitches + spread
                :drone1-freq   [:drone1 :freq]
                :drone2-freq   [:drone2 :freq]
                :drone3-freq   [:drone3 :freq]
                :drone4-freq   [:drone4 :freq]
                :drone1-detune [:drone1 :detune]
                :drone2-detune [:drone2 :detune]
                :drone3-detune [:drone3 :detune]
                :drone4-detune [:drone4 :detune]
                ;; VCO pitches and PWM modulation rate
                :vco1-freq     [:vco1 :freq]
                :vco2-freq     [:vco2 :freq]
                :vco1-pwm      [:vco1 :pwm-rate]
                :vco2-pwm      [:vco2 :pwm-rate]
                ;; Papa Srapa voice timbre controls
                :papa1-freq    [:papa1 :freq]
                :papa2-freq    [:papa2 :freq]
                :papa1-fm      [:papa1 :fm-depth]
                :papa2-fm      [:papa2 :fm-depth]
                :papa1-noise   [:papa1 :noise-mix]
                :papa2-noise   [:papa2 :noise-mix]
                :papa2-sh-rate [:papa2 :sh-rate]}})

    ;; ---------------------------------------------------------------------------
    ;; :superkar — SuperKarplus-inspired warpable multi-voice KS ensemble.
    ;;
    ;; Four independent KS voices with per-voice warp, body, and coef controls.
    ;; Each voice routes to a shared bus; a reverb bus adds spatial depth.
    ;;
    ;; The key insight: warp ≠ 1.0 produces inharmonic partials — the comb filter
    ;; reinforces frequencies at warp/period rather than 1/period. Per-voice warp
    ;; trajectories with staggered start times create an evolving inharmonic ensemble
    ;; texture impossible on hardware with a single global warp control.
    ;;
    ;; Voice layout (default tuning — A2, E3, A3, E4):
    ;;   voice1 — root (A2 = 110 Hz)
    ;;   voice2 — fifth above (E3 = 164.8 Hz)
    ;;   voice3 — octave above (A3 = 220 Hz)
    ;;   voice4 — fifth + octave (E4 = 329.6 Hz)
    ;;
    ;; Key params via set-patch-param! or apply-trajectory!:
    ;;   :voiceN-freq      — retune each voice independently
    ;;   :voiceN-warp      — per-voice warp factor (1.0 = harmonic)
    ;;   :voiceN-coef      — per-voice string brightness
    ;;   :voiceN-body-freq — per-voice body resonance frequency
    ;;   :voiceN-body-res  — per-voice body resonance width
    ;;   :effects-mix      — dry/wet reverb blend
    ;; ---------------------------------------------------------------------------
    (defpatch! :superkar
      {:buses  {:string-bus {:channels 2 :rate :audio}}
       :nodes  [{:id :voice1 :synth :superkar-voice
                 :args {:freq 110.0 :amp 0.5 :decay 25.0 :coef 0.5
                        :warp 1.0 :body-freq 400.0 :body-res 0.4 :pan -0.3}
                 :out {:out-bus :string-bus}}
                {:id :voice2 :synth :superkar-voice
                 :args {:freq 164.8 :amp 0.45 :decay 22.0 :coef 0.5
                        :warp 1.0 :body-freq 600.0 :body-res 0.5 :pan 0.1}
                 :out {:out-bus :string-bus}}
                {:id :voice3 :synth :superkar-voice
                 :args {:freq 220.0 :amp 0.4 :decay 18.0 :coef 0.5
                        :warp 1.0 :body-freq 800.0 :body-res 0.5 :pan -0.1}
                 :out {:out-bus :string-bus}}
                {:id :voice4 :synth :superkar-voice
                 :args {:freq 329.6 :amp 0.35 :decay 15.0 :coef 0.5
                        :warp 1.0 :body-freq 1200.0 :body-res 0.6 :pan 0.3}
                 :out {:out-bus :string-bus}}
                {:id :verb :synth :reverb-bus
                 :args {:room 0.7 :mix 0.3 :damp 0.4 :amp 1.0}
                 :in {:in-bus :string-bus} :out {:out 0}}]
       :params {:voice1-freq      [:voice1 :freq]
                :voice2-freq      [:voice2 :freq]
                :voice3-freq      [:voice3 :freq]
                :voice4-freq      [:voice4 :freq]
                :voice1-warp      [:voice1 :warp]
                :voice2-warp      [:voice2 :warp]
                :voice3-warp      [:voice3 :warp]
                :voice4-warp      [:voice4 :warp]
                :voice1-coef      [:voice1 :coef]
                :voice2-coef      [:voice2 :coef]
                :voice3-coef      [:voice3 :coef]
                :voice4-coef      [:voice4 :coef]
                :voice1-body-freq [:voice1 :body-freq]
                :voice2-body-freq [:voice2 :body-freq]
                :voice3-body-freq [:voice3 :body-freq]
                :voice4-body-freq [:voice4 :body-freq]
                :voice1-body-res  [:voice1 :body-res]
                :voice2-body-res  [:voice2 :body-res]
                :voice3-body-res  [:voice3 :body-res]
                :voice4-body-res  [:voice4 :body-res]
                :effects-mix      [:verb :mix]
                :effects-room     [:verb :room]}})

    ;; ---------------------------------------------------------------------------
    ;; :chaos-ensemble — three independent chaos voices on a shared bus.
    ;;
    ;; Two Lorenz voices + one Hénon voice, each with independent :chaos control.
    ;; The Lorenz pair are panned left/right with slightly different :s (Prandtl)
    ;; values — small differences in s cause the attractors to diverge on different
    ;; trajectories over time, creating an organic widening effect even at identical
    ;; :chaos values.
    ;;
    ;; Key params via set-patch-param! or apply-trajectory!:
    ;; :lorenz1-chaos / :lorenz2-chaos / :henon-chaos  — per-voice bifurcation
    ;; :lorenz1-cut   / :lorenz2-cut   / :henon-cut    — per-voice filter color
    ;; :effects-mix                                    — dry/wet reverb blend
    ;; ---------------------------------------------------------------------------
    (defpatch! :chaos-ensemble
      {:buses  {:chaos-bus {:channels 2 :rate :audio}}
       :nodes  [{:id :lorenz1 :synth :chaos-lorenz
                 :args {:chaos 0.5 :s 10.0 :freq-cut 1800 :freq-res 0.6 :amp 0.35 :pan -0.5}
                 :out {:out-bus 0}}
                {:id :lorenz2 :synth :chaos-lorenz
                 :args {:chaos 0.5 :s 12.0 :freq-cut 2400 :freq-res 0.55 :amp 0.3 :pan 0.5}
                 :out {:out-bus 0}}
                {:id :henon :synth :chaos-henon
                 :args {:chaos 0.4 :freq-cut 3200 :freq-res 0.5 :amp 0.3 :pan 0.0}
                 :out {:out-bus 0}}
                {:id :verb :synth :reverb-bus
                 :args {:room 0.6 :mix 0.25 :damp 0.5 :amp 1.0}
                 :in {:in-bus 0} :out {:out 0}}]
       :params {:lorenz1-chaos [:lorenz1 :chaos]
                :lorenz2-chaos [:lorenz2 :chaos]
                :henon-chaos   [:henon :chaos]
                :lorenz1-cut   [:lorenz1 :freq-cut]
                :lorenz2-cut   [:lorenz2 :freq-cut]
                :henon-cut     [:henon :freq-cut]
                :lorenz1-amp   [:lorenz1 :amp]
                :lorenz2-amp   [:lorenz2 :amp]
                :henon-amp     [:henon :amp]
                :effects-mix   [:verb :mix]
                :effects-room  [:verb :room]}})

    ;; ---------------------------------------------------------------------------
    ;; :klank-ensemble — three DynKlank resonator voices + reverb.
    ;;
    ;; Two bell voices (different freq-scale + partial ratios) and one marimba-bar
    ;; voice. Each has independent decay and tuning controls. The reverb tail
    ;; blends the three physical-model voices into a shared acoustic space.
    ;;
    ;; Key params via set-patch-param! or apply-trajectory!:
    ;;   :bell1-freq / :bell2-freq / :bars-freq  -- retune voices
    ;;   :bell1-d1   / :bell2-d1   / :bars-d1   -- decay of fundamental partial
    ;;   :effects-mix                             -- reverb wet/dry
    ;; ---------------------------------------------------------------------------
    (defpatch! :klank-ensemble
      {:buses  {:klank-bus {:channels 2 :rate :audio}}
       :nodes  [{:id :bell1 :synth :klank-bell
                 :args {:freq-scale 440 :amp 0.5
                        :f2 2.756 :d1 1.2 :d2 0.9 :pan -0.4}
                 :out {:out-bus 0}}
                {:id :bell2 :synth :klank-bell
                 :args {:freq-scale 660 :amp 0.4
                        :f2 2.95 :d1 1.8 :d2 1.2 :pan 0.4}
                 :out {:out-bus 0}}
                {:id :bars :synth :klank-bars
                 :args {:freq-scale 220 :amp 0.55 :d1 0.8 :pan 0.0}
                 :out {:out-bus 0}}
                {:id :verb :synth :reverb-bus
                 :args {:room 0.7 :mix 0.35 :damp 0.4 :amp 1.0}
                 :in {:in-bus 0} :out {:out 0}}]
       :params {:bell1-freq [:bell1 :freq-scale]
                :bell2-freq [:bell2 :freq-scale]
                :bars-freq  [:bars  :freq-scale]
                :bell1-d1   [:bell1 :d1]
                :bell2-d1   [:bell2 :d1]
                :bars-d1    [:bars  :d1]
                :bell1-amp  [:bell1 :amp]
                :bell2-amp  [:bell2 :amp]
                :bars-amp   [:bars  :amp]
                :effects-mix  [:verb :mix]
                :effects-room [:verb :room]}})
    true))
