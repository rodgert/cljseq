; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ensemble-improv
  "Ensemble improvisation agent — unified generative loop that responds to
  ImprovisationContext, driving both note output and ITexture devices.

  Without ITexture, a generative loop drives MIDI output only. The ensemble
  improv agent reads the extended ImprovisationContext (harmony + spectral) and
  drives both note generation AND texture device transitions from the same
  musical reality. The same tension/density/register snapshot that shapes what
  notes are chosen also shapes what the effects layer does.

  ## Quick start

    ;; Start the harmony ear and (optionally) the spectral SAM loop
    (start-harmony-ear! :main)
    (def sam (start-spectral! :main))

    ;; Start the improv agent
    (start-improv! :main :sam sam)

    ;; Stop
    (stop-improv!)

  ## Texture routing

  The :routing map wires ImprovisationContext fields to ITexture devices.
  Each entry maps a device-id to a route spec with a :params-fn:

    (start-improv! :main
      :sam     sam
      :routing {:reverb {:params-fn (fn [ctx]
                                      {:hold? (> (:harmony/tension ctx 0) 0.8)})
                          :beats 4}
                :delay  {:params-fn (fn [ctx]
                                      {:active-zone (if (< (:ensemble/density ctx 0) 0.5)
                                                      :z3 :z4)})}})

  ## Conductor integration

  Use `improv-gesture-fn` to build :on-start callbacks for conductor sections.
  The fn merges profile overrides, updates routing, and optionally fires an
  immediate texture transition at the section boundary:

    (defconductor! :my-arc
      [{:name :buildup :bars 32 :gesture :buildup
        :on-start (improv-gesture-fn
                    {:profile {:gate 0.95 :vel-variance 30}
                     :texture {:reverb {:target {:hold? false} :beats 8}}})}
       {:name :wind-down :bars 8 :gesture :wind-down
        :on-start (improv-gesture-fn
                    {:profile {:gate 0.4 :vel-variance 10}
                     :texture {:reverb {:target {:hold? true}}}})}])

    (fire! :my-arc)

  ## Note generation strategy

  Pitches are chosen from the scale in :harmony/key, biased by context:
    - :harmony/tension → degree selection width
        < 0.35: root triad only [0 2 4]
        < 0.65: diatonic pentatonic range [0 1 2 3 4]
        ≥ 0.65: full scale [0 … n-1]
    - :ensemble/register → octave range (:low :mid :high)
    - :ensemble/density + profile :gate → note probability per step

  When :harmony/key is absent (insufficient pitch data), the agent rests."
  (:require [cljseq.core           :as core]
            [cljseq.loop           :as loop-ns]
            [cljseq.pitch          :as pitch]
            [cljseq.scale          :as scale-ns]
            [cljseq.spectral       :as spectral]
            [cljseq.texture        :as tx]))

;; ---------------------------------------------------------------------------
;; Agent config atom
;;
;; Holds the current improv agent configuration so the running loop can read
;; it on every tick. update-improv! merges into this atom without restarting
;; the loop.
;;
;; Shape:
;;   {:buf-name  :main          ; Temporal Buffer name (used for context lookup)
;;    :profile   {...}          ; note-generation parameters (see below)
;;    :routing   {dev-id spec}  ; texture routing (see below)
;;    :sam       SpectralState  ; optional SpectralState for spectral/* fields
;;    :status    :running}
;; ---------------------------------------------------------------------------

(defonce ^:private agent-config (atom nil))

;; ---------------------------------------------------------------------------
;; Default profile
;;
;; :step-beats   — cadence between note-attempt steps
;; :gate         — base probability of generating a note at each step
;; :dur-beats    — note duration
;; :velocity     — base MIDI velocity
;; :vel-variance — ± random velocity spread
;; :channel      — MIDI output channel
;; ---------------------------------------------------------------------------

(def default-profile
  {:step-beats   1/4
   :gate         0.65
   :dur-beats    1/4
   :velocity     80
   :vel-variance 16
   :channel      1})

;; ---------------------------------------------------------------------------
;; Note generation helpers
;; ---------------------------------------------------------------------------

(defn- degree-pool
  "Return a vector of MIDI pitches drawn from `scale`, filtered by `tension`
  and `register`.

  tension → degree set:
    < 0.35  → [0 2 4]     (root triad — stable)
    < 0.65  → [0 1 2 3 4] (diatonic pentatonic)
    ≥ 0.65  → [0 … n-1]   (full scale)

  register → octave spread:
    :low  → one octave below root
    :mid  → root octave (default)
    :high → one octave above root"
  [scale tension register]
  (let [n      (count (:intervals scale))
        degs   (cond
                 (< (double tension) 0.35) [0 2 4]
                 (< (double tension) 0.65) [0 1 2 3 4]
                 :else                     (vec (range n)))
        octave (case register
                 :low  -1
                 :high  1
                 0)
        lo-degs (map #(- % n) degs)
        hi-degs (map #(+ % n) degs)
        all-degs (concat (when (= octave -1) lo-degs)
                         degs
                         (when (= octave  1) hi-degs))
        midis  (keep (fn [d]
                       (try
                         (let [m (pitch/pitch->midi (scale-ns/pitch-at scale d))]
                           (when (<= 0 m 127) m))
                         (catch Exception _ nil)))
                     all-degs)]
    (vec (distinct midis))))

(defn- pick-step
  "Decide whether to generate a note this step and return a note event map
  (or nil to rest). Probability is gated by profile :gate scaled by density."
  [ctx profile]
  (when-let [scale (:harmony/key ctx)]
    (let [tension  (double (:harmony/tension ctx 0.3))
          register (:ensemble/register ctx :mid)
          density  (double (:ensemble/density ctx 0.3))
          gate     (double (:gate profile 0.65))
          ;; More events in window → higher probability of filling in
          gate-adj (min 0.95 (* gate (max 0.2 (* density 1.8))))
          pool     (degree-pool scale tension register)]
      (when (and (seq pool) (< (Math/random) gate-adj))
        (let [midi   (rand-nth pool)
              spread (long (:vel-variance profile 16))
              vel    (-> (+ (long (:velocity profile 80))
                            (- (rand-int (* 2 spread)) spread))
                         (max 1)
                         (min 127))]
          {:pitch/midi   midi
           :dur/beats    (:dur-beats profile 1/4)
           :mod/velocity vel
           :midi/channel (:channel profile 1)})))))

;; ---------------------------------------------------------------------------
;; Texture routing
;; ---------------------------------------------------------------------------

(defn- apply-texture-routing!
  "Translate ImprovisationContext fields into ITexture transitions via routing map.

  routing: {device-id {:params-fn (fn [ctx] → params-map) :beats N}}"
  [ctx routing]
  (when (and (seq routing) (seq ctx))
    (let [ops (->> routing
                   (keep (fn [[device-id {:keys [params-fn beats]}]]
                           (when-let [params (try (params-fn ctx)
                                                   (catch Exception e
                                                     (binding [*out* *err*]
                                                       (println "[improv] routing error:" (.getMessage e)))
                                                     nil))]
                             [device-id (cond-> {:target params}
                                          beats (assoc :beats beats))])))
                   (into {}))]
      (when (seq ops)
        (tx/texture-transition! ops)))))

;; ---------------------------------------------------------------------------
;; Improv tick and loop
;; ---------------------------------------------------------------------------

(defn- run-improv-tick!
  "Perform one improv step: read context, pick a note, apply texture routing."
  []
  (when-let [{:keys [profile routing sam]} @agent-config]
    (let [harm-ctx  (or loop-ns/*harmony-ctx* {})
          spec-ctx  (when sam (spectral/spectral-ctx sam))
          ctx       (if spec-ctx (merge harm-ctx spec-ctx) harm-ctx)]
      (when-let [step (pick-step ctx profile)]
        (core/play! step))
      (apply-texture-routing! ctx routing))))

(defmacro ^:private improv-loop
  "Internal: emit a deflive-loop for the improv agent.
  Loop name is fixed as :improv-agent. All state flows through agent-config
  so the loop body is always the same form; no restart is needed to pick up
  profile or routing changes."
  []
  `(loop-ns/deflive-loop :improv-agent {}
     (run-improv-tick!)
     (loop-ns/sleep! (double (:step-beats (:profile @agent-config) 0.25)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start-improv!
  "Start the ensemble improv agent watching `buf-name` for harmonic context.

  The agent generates one note per `:step-beats` (scaled by context density)
  in the key and register of the live ImprovisationContext, and applies the
  `:routing` map to registered ITexture devices on the same cadence.

  Requires cljseq.core/start! and typically start-harmony-ear! first.

  Options:
    :profile  — note-generation parameters map (merged with default-profile):
                  :step-beats   beat interval between steps (default 1/4)
                  :gate         base play probability [0,1] (default 0.65)
                  :dur-beats    note duration in beats (default 1/4)
                  :velocity     base MIDI velocity (default 80)
                  :vel-variance velocity randomization ± (default 16)
                  :channel      MIDI output channel (default 1)
    :routing  — texture routing map {device-id route-spec}:
                  route-spec: {:params-fn (fn [ctx] → params-map) :beats N}
                  :params-fn is called with the current ImprovisationContext
                  each step and returns a params map for texture-transition!
    :sam      — optional SpectralState; when provided, :spectral/* fields are
                merged into the ImprovisationContext before each step

  Returns :improv-agent.

  Example:
    (start-improv! :main)
    (start-improv! :main :sam sam-instance :profile {:channel 2 :gate 0.8})"
  [buf-name & {:keys [profile routing sam]}]
  (reset! agent-config {:buf-name buf-name
                        :profile  (merge default-profile profile)
                        :routing  (or routing {})
                        :sam      sam
                        :status   :running})
  (improv-loop)
  :improv-agent)

(defn stop-improv!
  "Stop the improv agent. The agent-config is cleared; the loop stops.

  Equivalent to (loop-ns/stop-loop! :improv-agent)."
  []
  (reset! agent-config nil)
  (loop-ns/stop-loop! :improv-agent))

(defn update-improv!
  "Update the running improv agent's configuration without restarting the loop.

  `overrides` map:
    :profile  — merged into the current profile (partial override)
    :routing  — replaces the routing map entirely
    :sam      — replace the SpectralState reference

  Changes take effect on the next tick. Throws if no improv agent is running.

  Example:
    (update-improv! {:profile {:gate 0.9 :vel-variance 30}})
    (update-improv! {:routing {:reverb {:params-fn f :beats 4}}})"
  [overrides]
  (when (nil? @agent-config)
    (throw (ex-info "update-improv!: no improv agent running; call start-improv! first" {})))
  (swap! agent-config
         (fn [cfg]
           (cond-> cfg
             (:profile overrides)
             (update :profile merge (:profile overrides))
             (:routing overrides)
             (assoc :routing (:routing overrides))
             (contains? overrides :sam)
             (assoc :sam (:sam overrides)))))
  nil)

(defn improv-state
  "Return a snapshot of the current improv agent configuration, or nil."
  []
  @agent-config)

(defn improv-gesture-fn
  "Return an :on-start compatible fn (no args) for use in conductor sections.

  The returned fn, when called at a section boundary, applies the given
  overrides to the improv agent and optionally fires an immediate texture
  transition. Does nothing if no improv agent is running.

  `overrides` map:
    :profile  — merged into current profile
    :routing  — replaces routing map
    :sam      — new SpectralState
    :texture  — immediate texture-transition! ops map (applied once at boundary)

  Example:
    (defconductor! :my-arc
      [{:name :buildup :bars 32 :gesture :buildup
        :on-start (improv-gesture-fn {:profile {:gate 0.9}
                                      :texture {:reverb {:target {:hold? false} :beats 8}}})}
       {:name :wind-down :bars 8 :gesture :wind-down
        :on-start (improv-gesture-fn {:profile {:gate 0.3}
                                      :texture {:reverb {:target {:hold? true}}}})}])"
  [overrides]
  (fn []
    (when @agent-config
      (swap! agent-config
             (fn [cfg]
               (cond-> cfg
                 (:profile overrides)
                 (update :profile merge (:profile overrides))
                 (:routing overrides)
                 (assoc :routing (:routing overrides))
                 (contains? overrides :sam)
                 (assoc :sam (:sam overrides)))))
      (when-let [tex (:texture overrides)]
        (tx/texture-transition! tex)))))
