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
    true))
