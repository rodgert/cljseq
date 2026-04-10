; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ensemble
  "Ensemble harmony groundwork — shared harmonic context derived from live
  Temporal Buffer snapshots.

  Provides `analyze-buffer` (pure snapshot → ImprovisationContext) and
  `start-harmony-ear!` (opt-in background loop that continuously updates
  *harmony-ctx* from a named Temporal Buffer).

  ## ImprovisationContext shape

    {:harmony/key       (scale/scale :D 3 :dorian)  ; detected or pinned Scale
     :harmony/chord     {:root :A :quality :min7     ; analyze/chord->roman result
                         :roman :V :tension 0.65}
     :harmony/tension   0.65   ; [0.0, 1.0] from analyze/tension-score
     :harmony/mode-conf 0.82   ; confidence from analyze/detect-mode
     :harmony/ks-score  0.91   ; raw Krumhansl-Schmuckler correlation
     :harmony/pcs       {2 4, 9 3, 0 2}  ; pitch-class distribution
     :ensemble/register :mid   ; :low :mid :high — tessiture of the window
     :ensemble/density  0.4}   ; note events per beat in the active zone window

  ## Quick start

    ;; Define a temporal buffer and a harmony ear that watches it:
    (deftemporal-buffer :main {:active-zone :z3})

    ;; Start the ear (opt-in — nothing runs automatically)
    (start-harmony-ear! :main)

    ;; In live loops, *harmony-ctx* is now an ImprovisationContext map.
    ;; dsl/root, dsl/scale-degree etc. still work — they extract :harmony/key.
    ;; Loops can also read :harmony/tension, :harmony/chord etc. directly.

    ;; With a key hint (skip auto-detection, derive chord/tension on top):
    (start-harmony-ear! :main :key-scale (scale/scale :D 3 :dorian))

    ;; With a transformer (ctx → ctx):
    (start-harmony-ear! :main
      :transform (fn [ctx]
        (if (< (:harmony/mode-conf ctx 0) 0.6)
          (assoc ctx :harmony/key my-fallback-scale)
          ctx)))

    ;; Stop the ear:
    (stop-harmony-ear!)

  ## Design notes

  The ear is implemented as a named deflive-loop (:harmony-ear) so it
  participates fully in the loop lifecycle — stop-loop!, hot-swap on
  re-eval, and master clock sync all apply. Requires cljseq.core/start!
  to have been called first (same requirement as any live loop).

  On empty buffer / insufficient pitch diversity, the prior context is
  retained (nil only on the very first call before any events arrive).

  Key detection requires at least 3 distinct pitch classes; below that
  threshold :harmony/key and :harmony/chord are omitted from the returned
  context."
  (:require [cljseq.analyze         :as analyze]
            [cljseq.ctrl            :as ctrl]
            [cljseq.dsl             :as dsl]
            [cljseq.loop            :as loop-ns]
            [cljseq.scale           :as scale-ns]
            [cljseq.temporal-buffer :as tb]))

;; ---------------------------------------------------------------------------
;; ImprovisationContext derivation
;; ---------------------------------------------------------------------------

(defn- midi->register
  "Classify a collection of MIDI values into a tessiture register keyword."
  [midi-vals]
  (if (empty? midi-vals)
    :mid
    (let [mid (/ (double (+ (apply min midi-vals) (apply max midi-vals))) 2.0)]
      (cond (< mid 48.0) :low
            (> mid 72.0) :high
            :else        :mid))))

(defn analyze-buffer
  "Analyze a Temporal Buffer snapshot and return an ImprovisationContext map.

  `buf-name`  — keyword name of a registered Temporal Buffer
  Options:
    :key-scale  — a Scale record; if provided, key detection is skipped and
                  this scale is used to derive chord and tension analysis

  Returns an ImprovisationContext map, or nil when the buffer has no events.

  At least 3 distinct pitch classes are required for key detection; below that
  threshold :harmony/key and :harmony/chord are omitted from the result.

  Example:
    (analyze-buffer :main)
    (analyze-buffer :main :key-scale (scale/scale :D 3 :dorian))"
  [buf-name & {:keys [key-scale]}]
  (when-let [events (tb/temporal-buffer-snapshot buf-name)]
    (when (seq events)
      (let [midi-vals  (keep :pitch/midi events)
            pc-dist    (analyze/pitch-class-dist midi-vals)
            n-pcs      (count pc-dist)
            info       (tb/temporal-buffer-info buf-name)
            zone-depth (double (get-in info [:zone :depth] 8.0))
            density    (if (pos? zone-depth)
                         (double (/ (count events) zone-depth))
                         0.0)
            register   (midi->register midi-vals)
            ;; Detect or pin the key
            detected   (when (and (nil? key-scale) (>= n-pcs 3))
                         (analyze/detect-mode midi-vals))
            key        (or key-scale
                           (when detected
                             (scale-ns/scale (:tonic detected) 4 (:mode detected))))
            ;; Chord and tension require a key
            chord-info (when key
                         (analyze/chord->roman key midi-vals))
            tension    (when chord-info
                         (analyze/tension-score chord-info))]
        (cond-> {:harmony/pcs       pc-dist
                 :ensemble/register register
                 :ensemble/density  density}
          detected   (assoc :harmony/mode-conf (:confidence detected)
                            :harmony/ks-score  (:ks-score detected))
          key        (assoc :harmony/key key)
          chord-info (assoc :harmony/chord (cond-> chord-info
                                             tension (assoc :tension tension)))
          tension    (assoc :harmony/tension tension))))))

;; ---------------------------------------------------------------------------
;; Harmony ear — opt-in background loop
;; ---------------------------------------------------------------------------

(defonce ^:private last-ctx (atom nil))

(defn- ctx->serial
  "Convert an ImprovisationContext to a JSON-safe serializable map for peer sharing.

  Replaces :harmony/key Scale record with primitive :harmony/root, :harmony/octave,
  :harmony/intervals fields. Drops :harmony/chord (contains Pitch records) and
  :harmony/pcs (pitch-class distribution — large, not needed by peers).

  The result is safe for ctrl/set! and round-trips through the HTTP server's
  ->json-safe encoder without falling back to pr-str."
  [ctx]
  (let [key  (:harmony/key ctx)
        base (dissoc ctx :harmony/key :harmony/chord :harmony/pcs)]
    (cond-> base
      key
      (assoc :harmony/root      (name (:step (:root key)))
             :harmony/octave    (int (:octave (:root key)))
             :harmony/intervals (vec (:intervals key))))))

(defn- run-ear-tick!
  "Perform one analysis cycle: snapshot → transform → publish.
  Returns the published context, or nil if nothing was published."
  [buf-name key-scale xform on-ctx]
  (let [raw  (analyze-buffer buf-name :key-scale key-scale)
        ctx  (when raw (xform raw))
        prev @last-ctx
        pub  (or ctx prev)]       ; retain prior context on empty/nil
    (when pub
      (reset! last-ctx pub)
      (dsl/use-harmony! pub)
      ;; Publish serializable form for peer sharing via HTTP server.
      ;; Wrapped in try so ctrl not being started does not break the ear.
      (try (ctrl/set! [:ensemble :harmony-ctx] (ctx->serial pub))
           (catch Exception _ nil))
      (when on-ctx (on-ctx pub)))
    pub))

(defmacro ^:private ear-loop
  "Internal: emit a deflive-loop body for the harmony ear.
  Pulled into a macro because deflive-loop is itself a macro and the body
  must close over runtime values from start-harmony-ear!."
  [buf-name key-scale xform on-ctx cadence]
  `(loop-ns/deflive-loop :harmony-ear {}
     (run-ear-tick! ~buf-name ~key-scale ~xform ~on-ctx)
     (loop-ns/sleep! ~cadence)))

(defn start-harmony-ear!
  "Start the harmony ear — a named live loop that continuously analyzes
  `buf-name` and publishes the result to *harmony-ctx*.

  The ear is opt-in: nothing runs automatically when a Temporal Buffer is
  defined. Requires cljseq.core/start! to have been called first.

  Options:
    :cadence    — analysis interval in beats (default 4); phrase-boundary
                  cadence recommended for key detection
    :key-scale  — pin a Scale record and skip auto-detection (chord and
                  tension are still derived from the live buffer)
    :transform  — a ctx→ctx function applied after analysis and before
                  publishing; compose multiple transforms with `comp`
    :on-ctx     — optional 1-arg side-effect fn called after each publish

  The ear registers as the live loop :harmony-ear. Stop it with:
    (stop-harmony-ear!)
  or via the standard loop API:
    (loop-ns/stop-loop! :harmony-ear)

  Example:
    (start-harmony-ear! :main)
    (start-harmony-ear! :main :cadence 8 :key-scale (scale/scale :D 3 :dorian))
    (start-harmony-ear! :main
      :transform (fn [ctx]
        (if (< (:harmony/mode-conf ctx 0.0) 0.6)
          (assoc ctx :harmony/key fallback-key)
          ctx)))"
  [buf-name & {:keys [cadence key-scale transform on-ctx]
               :or   {cadence 4}}]
  (let [xform (or transform identity)]
    (ear-loop buf-name key-scale xform on-ctx cadence))
  :harmony-ear)

(defn stop-harmony-ear!
  "Stop the harmony ear started by start-harmony-ear!.

  Equivalent to (loop-ns/stop-loop! :harmony-ear)."
  []
  (loop-ns/stop-loop! :harmony-ear))
