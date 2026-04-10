; SPDX-License-Identifier: EPL-2.0
(ns cljseq.synth
  "Synthesis graph vocabulary — engine-agnostic synth definitions.

  A synth definition is a Clojure map with two required keys:

    :args   — map of argument names to default values
    :graph  — hiccup-style UGen graph; top-level node must be [:out ...]

  Graphs are Clojure data: inspectable, transformable, serializable to EDN,
  and compilable to multiple backends via the `compile-synth` multimethod.

  ## Defining a synth

    (defsynth! :pad
      {:args  {:freq 440 :amp 0.5 :attack 0.01 :release 1.0 :pan 0.0}
       :graph [:out 0
                [:pan2
                 [:* [:env-gen [:adsr :attack 0.1 0.8 :release] :gate :amp]
                     [:sin-osc :freq]]
                 :pan]]})

  ## Graph node types

    keyword   — argument reference (:freq → the `freq` arg)
    number    — numeric literal
    vector    — UGen call: [:ugen-name & args]

  ## Standard UGen names

    Oscillators: :sin-osc :saw :pulse :var-saw :lf-tri :white-noise :pink-noise
    Envelopes:   :env-gen :adsr :perc :linen
    Filters:     :lpf :hpf :rlpf :moog-ff
    Effects:     :free-verb :comb-n :allpass-n
    Mix/Pan:     :mix :pan2 :balance2
    Math:        :* :+ :- :/ :clip :lag :abs
    Output:      :out

  ## Compilation backends

    (compile-synth :sc   synth-name)   — sclang SynthDef string
    (compile-synth :sc-node synth-map) — OSC /s_new args map

  ## Graph transforms

    (map-graph f synth)    — apply f to every node in the graph
    (synth-ugens synth)    — collect all UGen names used
    (transpose-synth synth semitones) — shift :freq arg default

  ## Built-in synths

  Five synths are pre-loaded from resources/synths/:
    :sine     — ADSR sine wave
    :saw-pad  — detuned saw pair
    :blade    — triple VarSaw + LPF (Sonic-Pi :blade equivalent)
    :perc     — sine percussion with pitch decay
    :prophet  — saw+pulse + RLPF (Sonic-Pi :prophet equivalent)"
  (:require [clojure.java.io :as io]
            [clojure.edn     :as edn]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private synth-registry (atom {}))

(defn defsynth!
  "Register a named synth definition.

  `synth-map` must contain:
    :args   — map of {arg-kw default-value}
    :graph  — hiccup UGen graph; top-level node must be [:out ...]

  Optional:
    :synth/tags — seq of keyword tags
    :synth/doc  — docstring

  Idempotent: re-registering replaces the previous definition.

  Example:
    (defsynth! :pad
      {:args  {:freq 440 :amp 0.5}
       :graph [:out 0 [:sin-osc :freq]]})"
  [synth-name synth-map]
  (when-not (keyword? synth-name)
    (throw (ex-info "defsynth!: synth name must be a keyword" {:name synth-name})))
  (when-not (map? (:args synth-map))
    (throw (ex-info "defsynth!: :args must be a map" {:synth synth-name})))
  (when-not (vector? (:graph synth-map))
    (throw (ex-info "defsynth!: :graph must be a vector" {:synth synth-name})))
  (swap! synth-registry assoc synth-name synth-map)
  synth-name)

(defn get-synth
  "Return the synth definition for `synth-name`, or nil if not found."
  [synth-name]
  (get @synth-registry synth-name))

(defn synth-names
  "Return a sorted seq of all registered synth names."
  []
  (sort (keys @synth-registry)))

;; ---------------------------------------------------------------------------
;; Graph utilities
;; ---------------------------------------------------------------------------

(defn map-graph
  "Apply `f` to every node in `graph`, returning a transformed graph.
  `f` receives each node (keyword, number, or vector) and returns the
  replacement. Called depth-first — children are transformed before parents."
  [f graph]
  (cond
    (vector? graph)
    (f (into [(first graph)] (map (partial map-graph f) (rest graph))))
    :else
    (f graph)))

(defn synth-ugens
  "Return the set of all UGen names (keywords) referenced in `synth`'s graph."
  [synth]
  (let [ugens (atom #{})]
    (map-graph (fn [node]
                 (when (and (vector? node) (keyword? (first node)))
                   (swap! ugens conj (first node)))
                 node)
               (:graph synth))
    @ugens))

(defn transpose-synth
  "Return a new synth with the :freq default shifted by `semitones`."
  [synth semitones]
  (update-in synth [:args :freq]
             (fn [f] (when f (* f (Math/pow 2.0 (/ semitones 12.0)))))))

(defn scale-amp
  "Return a new synth with the :amp default multiplied by `factor`."
  [synth factor]
  (update-in synth [:args :amp] (fn [a] (when a (* a factor)))))

(defn replace-arg
  "Return a new synth with arg `k` default replaced by `v`."
  [synth k v]
  (assoc-in synth [:args k] v))

;; ---------------------------------------------------------------------------
;; Compilation multimethod
;; ---------------------------------------------------------------------------

(defmulti compile-synth
  "Compile a registered synth to a backend-specific representation.

  Dispatch on `backend` keyword. Built-in backends:
    :sc  — returns a sclang SynthDef string (from cljseq.sc)

  Example:
    (compile-synth :sc :blade)
    ;=> \"SynthDef(\\\\blade, { |freq=440.0, ..., gate=1| ... }).add;\""
  (fn [backend _synth-name] backend))

(defmethod compile-synth :default [backend synth-name]
  (throw (ex-info "compile-synth: unknown backend"
                  {:backend backend :synth synth-name})))

;; ---------------------------------------------------------------------------
;; EDN loader — resources/synths/
;; ---------------------------------------------------------------------------

(defn load-synth-map
  "Load a synth definition from an EDN file on the classpath.

  `path` is resolved relative to the classpath root:
    (load-synth-map \"synths/sine.edn\")

  Returns the parsed synth map."
  [path]
  (if-let [url (io/resource path)]
    (edn/read-string (slurp url))
    (throw (ex-info "cljseq.synth/load-synth-map: file not found"
                    {:path path}))))

(defn- load-builtin! [filename]
  (let [m (load-synth-map (str "synths/" filename))
        id (:synth/id m)]
    (defsynth! id m)))

;; ---------------------------------------------------------------------------
;; Boot — load built-in synths
;; ---------------------------------------------------------------------------

(defonce ^:private _builtins-loaded
  (do
    (load-builtin! "beep.edn")
    (load-builtin! "sine.edn")
    (load-builtin! "saw-pad.edn")
    (load-builtin! "blade.edn")
    (load-builtin! "prophet.edn")
    (load-builtin! "supersaw.edn")
    (load-builtin! "dull-bell.edn")
    (load-builtin! "fm.edn")
    (load-builtin! "tb303.edn")
    (load-builtin! "perc.edn")
    (load-builtin! "sine-bus.edn")
    (load-builtin! "reverb-bus.edn")
    (load-builtin! "buf-player.edn")
    (load-builtin! "buf-looper.edn")
    (load-builtin! "granular-voice.edn")
    true))
