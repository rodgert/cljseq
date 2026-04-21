; SPDX-License-Identifier: EPL-2.0
(ns cljseq.arc
  "Arc type routing — fan-out from abstract arc nodes to device parameters.

  An arc node (e.g. [:arc/tension]) is an abstract ctrl tree value in [0.0, 1.0].
  `arc-bind!` connects it to one or more downstream ctrl paths, each with an
  optional per-path output range. Whenever the arc value changes (via ctrl/send!
  or ctrl/set! — including from a trajectory routed through mod-route!), all
  downstream paths are updated automatically.

  ## Workflow

    ;; 1. Bind the Hydrasynth filter cutoff + resonance to :arc/tension
    (arc-bind! [:arc/tension]
               {[:filter1/cutoff]    {:range [0.3 1.0]}
                [:filter1/resonance] {:range [0.0 0.6]}
                [:mod/velocity]      {:range [52 100]}})

    ;; 2. Route a trajectory — the arc fan-out fires on every sample
    (mod-route! [:arc/tension]
                (trajectory :from 0.0 :to 1.0 :beats 128
                            :curve :s-curve :start (now)))

    ;; 3. Or manually drive an arc value at the REPL
    (arc-send! [:arc/tension] 0.8)

    ;; 4. Remove routing
    (arc-unbind! [:arc/tension])

  ## Named arc paths (convention)

    [:arc/tension]    — harmonic/emotional energy; 0=calm, 1=peak
    [:arc/density]    — notes-per-beat; 0=sparse, 1=maximal
    [:arc/register]   — tessitura position; 0=low, 1=high
    [:arc/momentum]   — resistance to change; 0=restless, 1=locked groove
    [:arc/texture]    — voice depth; 0=mono, 1=full four-voice
    [:arc/gravity]    — tonal pull toward tonic; 0=away, 1=strong cadential

  Key design decisions: R&R §31 (trajectory/arc vocabulary)."
  (:require [cljseq.ctrl :as ctrl]))

;; ---------------------------------------------------------------------------
;; Internal state
;; {source-path {target-path {:range [lo hi]}}}
;; ---------------------------------------------------------------------------

(defonce ^:private routes (atom {}))

;; ---------------------------------------------------------------------------
;; Value scaling helpers
;; ---------------------------------------------------------------------------

(defn- scale-value
  "Map `value` ∈ [0.0, 1.0] linearly to [lo, hi]."
  ^double [value lo hi]
  (+ (double lo) (* (double value) (- (double hi) (double lo)))))

(defn- fan-out!
  "Send `value` to all target paths registered under `source-path`."
  [source-path value]
  (doseq [[target-path opts] (get @routes source-path)]
    (let [[lo hi] (or (:range opts) [0.0 1.0])]
      (ctrl/send! target-path (scale-value value lo hi)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn arc-bind!
  "Connect `source-path` to one or more downstream ctrl paths.

  `bindings` — map of {target-path opts} where opts may include:
                 :range [lo hi] — output range for this target (default [0.0 1.0])

  The source arc value (0.0–1.0 by convention) is linearly mapped to each
  target's :range before being dispatched via ctrl/send!.

  Re-calling arc-bind! with the same source replaces its downstream routes.
  Installs a ctrl/watch! callback on source-path the first time; subsequent
  calls update the routes atom (the watcher reads routes dynamically).

  Example:
    (arc-bind! [:arc/tension]
               {[:filter1/cutoff]    {:range [0.3 1.0]}
                [:filter1/resonance] {:range [0.0 0.6]}})"
  [source-path bindings]
  (let [first-bind? (not (contains? @routes source-path))]
    (swap! routes assoc source-path bindings)
    (when first-bind?
      (ctrl/watch! source-path ::arc-fan-out
                   (fn [tx _state]
                     (let [{:keys [path after]} (first (:tx/changes tx))]
                       (fan-out! path after))))))
  nil)

(defn arc-unbind!
  "Remove all arc routing from `source-path`.
  The ctrl watcher is removed; subsequent changes to source-path will not fan out."
  [source-path]
  (swap! routes dissoc source-path)
  (ctrl/unwatch-all! source-path)
  nil)

(defn arc-unbind-all!
  "Remove all arc routes and watchers."
  []
  (doseq [path (keys @routes)]
    (arc-unbind! path))
  nil)

(defn arc-routes
  "Return the current arc routing table: {source-path {target-path opts}}."
  []
  @routes)

(defn arc-send!
  "Set `source-path` to `value` and trigger its downstream fan-out.

  Equivalent to (ctrl/send! source-path value) but makes intent explicit.
  Use this to manually drive an arc at the REPL without a trajectory.

  Example:
    (arc-send! [:arc/tension] 0.75)"
  [source-path value]
  (ctrl/send! source-path value))
