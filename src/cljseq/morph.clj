; SPDX-License-Identifier: EPL-2.0
(ns cljseq.morph
  "cljseq morph — named multi-input parameter mappings.

  A morph is a named control surface abstraction: one or more input values fan
  out through pure transfer functions to multiple ctrl-tree targets. Inspired by
  the Hydrasynth macro model.

  ## Quick start

    ;; Single-input: one fader drives multiple synth parameters
    (defmorph filter-brightness
      {:inputs  {:value [:float 0.0 1.0]}
       :targets [{:path [:filter/cutoff]    :fn #(* % 8000)}
                 {:path [:filter/resonance] :fn #(* % 0.7)}
                 {:path [:amp/brightness]   :fn identity}]})

    ;; Set input value — all targets update atomically
    (morph-set! filter-brightness :value 0.6)

    ;; Multi-input: XY pad drives correlated parameters
    (defmorph xy-filter
      {:inputs  {:x [:float 0.0 1.0]
                 :y [:float 0.0 1.0]}
       :targets [{:path [:filter/cutoff]    :fn (fn [{:keys [x]}]   (* x 8000))}
                 {:path [:filter/resonance] :fn (fn [{:keys [y]}]   (* y 0.9))}
                 {:path [:filter/drive]     :fn (fn [{:keys [x y]}] (* x y 4.0))}]})

    (morph-set! xy-filter :x 0.7 :y 0.3)

  Morphs auto-register at [:morphs <name>] in the ctrl tree and appear in
  morph-names. Target paths must be registered ctrl nodes.

  Key design decisions: Q49 (defmorph / cljseq.morph)."
  (:require [cljseq.ctrl :as ctrl]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register a morph context under the given name keyword. Used by defmorph macro."
  [k ctx]
  (swap! registry assoc k ctx))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-morph-context
  "Build a morph context map from the morph spec map.

  spec keys:
    :inputs  — map of input-key → [:type lo hi] descriptor
    :targets — vector of {:path ctrl-path :fn transfer-fn}"
  [spec]
  (let [{:keys [inputs targets]} spec
        init-vals (reduce-kv (fn [m k [_type lo _hi]] (assoc m k lo))
                              {}
                              inputs)]
    {:inputs  inputs
     :targets (vec targets)
     :values  (atom init-vals)}))

;; ---------------------------------------------------------------------------
;; defmorph macro (Q49)
;; ---------------------------------------------------------------------------

(defmacro defmorph
  "Define a named morph and register it in the ctrl tree.

  Creates a var bound to the morph context map and registers it at
  [:morphs <name>] in the ctrl tree.

  `spec` map keys:
    :inputs  — map of input-key → [:type lo hi]
               :type is :float or :int; lo/hi set the initial value and range
    :targets — vector of {:path ctrl-path :fn transfer-fn}
               single-input: fn receives the bare input value
               multi-input:  fn receives the full input-values map

  Example:
    (defmorph filter-brightness
      {:inputs  {:value [:float 0.0 1.0]}
       :targets [{:path [:filter/cutoff]    :fn #(* % 8000)}
                 {:path [:filter/resonance] :fn #(* % 0.7)}]})"
  [morph-name spec]
  `(do
     (def ~morph-name (make-morph-context ~spec))
     (register! ~(keyword (name morph-name)) ~morph-name)
     (ctrl/defnode! [:morphs ~(keyword (name morph-name))]
                    :type :data :value ~morph-name)
     ~morph-name))

;; ---------------------------------------------------------------------------
;; morph-set! — fan-out dispatch
;; ---------------------------------------------------------------------------

(defn morph-set!
  "Set one or more input values on a morph and fan out to all targets.

  For each target:
    - single-input morphs: transfer-fn receives the named input value directly
    - multi-input morphs:  transfer-fn receives the full input-values map

  Input values are clamped to the declared [lo hi] range.
  Target ctrl nodes that do not exist are silently skipped.

  Examples:
    (morph-set! my-morph :value 0.5)
    (morph-set! xy-morph :x 0.3 :y 0.8)"
  [ctx & kvs]
  (let [{:keys [inputs targets values]} ctx
        updates (apply hash-map kvs)
        ;; Clamp each updated value to its declared range
        clamped (reduce-kv
                  (fn [m k v]
                    (if-let [[_type lo hi] (get inputs k)]
                      (assoc m k (max (double lo) (min (double hi) (double v))))
                      m))
                  {}
                  updates)
        new-vals (swap! values merge clamped)
        single?  (= 1 (count inputs))
        solo-key (when single? (first (keys inputs)))]
    (doseq [{:keys [path fn]} targets]
      (when (ctrl/node-info path)
        (let [arg (if single? (get new-vals solo-key) new-vals)]
          (ctrl/set! path (fn arg)))))
    nil))

(defn morph-get
  "Return the current input values map for a morph."
  [ctx]
  @(:values ctx))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn morph-names
  "Return a seq of registered morph names."
  []
  (or (keys @registry) '()))
