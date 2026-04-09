; SPDX-License-Identifier: EPL-2.0
(ns cljseq.spectral
  "Spectral analysis model (SAM) — derives spectral characteristics from
  live Temporal Buffer snapshots and exposes them as an ITexture device.

  The spectral layer augments the harmonic ImprovisationContext with three
  fields that characterize the *textural quality* of the current musical
  content rather than its pitch identity:

    :spectral/density  — harmonic richness: distinct pitch classes / 12 [0,1]
    :spectral/centroid — pitch center of mass: mean MIDI pitch / 127 [0,1]
    :spectral/blur     — smear level: 0 = fully live, 1 = fully frozen

  These complement the harmony ear's `:harmony/*` and `:ensemble/*` fields;
  the full extended ImprovisationContext is the merge of both:

    {:harmony/key       (scale :D 3 :dorian)
     :harmony/tension   0.65
     :ensemble/density  0.4
     :spectral/density  0.58   ; 7 distinct pitch classes
     :spectral/centroid 0.52   ; mean MIDI around 66 (F#4)
     :spectral/blur     0.0}   ; fully live

  ## Quick start

    ;; Start the SAM loop watching a named Temporal Buffer
    (def sam (start-spectral! :main))

    ;; Read the current spectral context
    (spectral-ctx sam)   ; => {:spectral/density 0.58 ...}

    ;; Freeze: holds spectral state, blur → 1.0
    (tx/freeze! sam)

    ;; Thaw: resumes analysis, blur → 0.0 on next tick
    (tx/thaw! sam)

    ;; Register as a named texture device for transition routing
    (tx/deftexture! :spectral sam)
    (tx/texture-transition! {:spectral {:target {:spectral/blur 0.5} :beats 8}})

    ;; Merge spectral context with harmony context in a live loop:
    (deflive-loop :generator {}
      (let [ctx (merge (ensemble/analyze-buffer :main) (spectral-ctx sam))]
        (when (:harmony/key ctx)
          ...)))

    ;; Stop the SAM loop
    (stop-spectral! sam)

  ## SAM loop mechanics

  The SAM loop is a named live loop (:spectral-sam). It runs at the configured
  cadence (default 4 beats). When frozen, the analysis is skipped but the loop
  continues running so that :on-ctx callbacks still fire with the held state.
  This allows downstream consumers to remain synchronized even during freeze.

  Requires cljseq.core/start! to have been called before `start-spectral!`."
  (:require [cljseq.loop            :as loop-ns]
            [cljseq.temporal-buffer :as tb]
            [cljseq.texture         :as tx]))

;; ---------------------------------------------------------------------------
;; Spectral computation
;; ---------------------------------------------------------------------------

(defn- compute-spectral
  "Derive spectral characteristics from a sequence of MIDI event maps.

  Returns a map with :spectral/density and :spectral/centroid.
  Returns centroid 0.5 (mid-range) when events is empty."
  [events]
  (let [midis (keep :pitch/midi events)]
    (if (seq midis)
      (let [pcs    (count (set (map #(mod (int %) 12) midis)))
            mean-m (/ (double (reduce + 0.0 midis)) (count midis))]
        {:spectral/density  (/ pcs 12.0)
         :spectral/centroid (/ mean-m 127.0)})
      {:spectral/density  0.0
       :spectral/centroid 0.5})))

;; ---------------------------------------------------------------------------
;; SAM analysis loop
;; ---------------------------------------------------------------------------

(defn- run-sam-tick!
  "Perform one SAM analysis cycle.

  When frozen: skips recomputation but still fires on-ctx with the held state.
  When live: snapshots the buffer, computes spectral fields, updates state-atom.
  Sets :spectral/blur to 0.0 whenever an update is applied."
  [buf-name on-ctx state-atom]
  (let [frozen? (:frozen? @state-atom)]
    (when-not frozen?
      (when-let [events (tb/temporal-buffer-snapshot buf-name)]
        (when (seq events)
          (let [spec (compute-spectral events)]
            (swap! state-atom merge spec {:spectral/blur 0.0})))))
    (let [pub (dissoc @state-atom :frozen?)]
      (when on-ctx (on-ctx pub))
      pub)))

(defmacro ^:private sam-loop
  "Internal: emit a deflive-loop for the SAM analysis loop.
  Pulled into a macro because deflive-loop is itself a macro and the body
  must close over runtime values from start-spectral!."
  [buf-name cadence on-ctx state-atom]
  `(loop-ns/deflive-loop :spectral-sam {}
     (run-sam-tick! ~buf-name ~on-ctx ~state-atom)
     (loop-ns/sleep! ~cadence)))

;; ---------------------------------------------------------------------------
;; SpectralState — ITexture implementation
;; ---------------------------------------------------------------------------

(defrecord SpectralState [state-atom buf-name]
  tx/ITexture

  (freeze! [_]
    (swap! state-atom assoc :frozen? true :spectral/blur 1.0)
    nil)

  (thaw! [_]
    (swap! state-atom assoc :frozen? false :spectral/blur 0.0)
    nil)

  (frozen? [_]
    (boolean (:frozen? @state-atom)))

  (texture-state [_]
    ;; Expose only the public spectral fields (not internal :frozen? flag).
    (select-keys @state-atom [:spectral/density :spectral/centroid :spectral/blur]))

  (texture-set! [_ params]
    ;; Merge any spectral params (or any keys) into the state.
    ;; :frozen? can be set directly via texture-set! for advanced use.
    (swap! state-atom merge params)
    nil)

  (texture-fade! [this target beats]
    (tx/start-texture-fade! this target beats)
    nil))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start-spectral!
  "Start the Spectral Analysis Model loop watching `buf-name`.

  The SAM loop runs at `cadence` beats, derives :spectral/density,
  :spectral/centroid, and :spectral/blur from the buffer's active zone snapshot,
  and optionally calls `:on-ctx` with the updated spectral state.

  Returns a SpectralState record that implements ITexture. Use it to freeze,
  thaw, inspect, or modulate the spectral state.

  Options:
    :cadence  — analysis interval in beats (default 4)
    :on-ctx   — optional 1-arg fn called after each analysis tick with the
                current spectral state map (excluding :frozen?)

  The returned SpectralState starts in the live state:
    {:spectral/density 0.0 :spectral/centroid 0.5 :spectral/blur 0.0}

  Example:
    (def sam (start-spectral! :main))
    (def sam (start-spectral! :main :cadence 8))
    (def sam (start-spectral! :main :on-ctx (fn [s] (println s))))"
  [buf-name & {:keys [cadence on-ctx] :or {cadence 4}}]
  (let [sa (atom {:spectral/density  0.0
                  :spectral/centroid 0.5
                  :spectral/blur     0.0
                  :frozen?           false})]
    (sam-loop buf-name cadence on-ctx sa)
    (->SpectralState sa buf-name)))

(defn stop-spectral!
  "Stop the SAM loop started by `start-spectral!`.

  The SpectralState record remains valid; `texture-state` still returns the
  last computed values. Equivalent to (loop-ns/stop-loop! :spectral-sam)."
  [_sam]
  (loop-ns/stop-loop! :spectral-sam))

(defn spectral-ctx
  "Return the current spectral state map from `sam` as an ImprovisationContext
  fragment suitable for merging with harmony context.

  Returns:
    {:spectral/density  0.58
     :spectral/centroid 0.52
     :spectral/blur     0.0}

  Example:
    (let [ctx (merge (ensemble/analyze-buffer :main) (spectral-ctx sam))]
      (dsl/use-harmony! ctx))"
  [sam]
  (tx/texture-state sam))
