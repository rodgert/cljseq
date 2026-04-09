; SPDX-License-Identifier: EPL-2.0
(ns cljseq.texture
  "ITexture protocol — unified interface for software and hardware temporal/texture
  devices.

  All texture devices (TemporalBuffer, SpectralState, hardware reverbs/delays/loopers)
  share the same fundamental operations: freeze, thaw, inspect, set params, and fade.

  ## Protocol

    ;; Software device — backed by a TemporalBuffer named :echo
    (deftexture! :echo (buffer-texture :echo))

    ;; Freeze the buffer input gate
    (freeze! (get-texture :echo))

    ;; Unfreeze
    (thaw! (get-texture :echo))

    ;; Read state (live for software, shadow for hardware)
    (texture-state (get-texture :echo))

    ;; Set one or more params atomically
    (texture-set! (get-texture :echo) {:zone :z4 :rate 0.8})

    ;; Fade to a new state over 8 beats (linear interpolation for numeric params)
    (texture-fade! (get-texture :echo) {:rate 1.5 :feedback {:amount 0.6}} 8)

  ## Multi-device coordinated transition

    (texture-transition!
      {:echo   {:target {:active-zone :z4 :rate 0.8} :beats 8}
       :reverb {:target {:hold? true}}})

  ## Hardware shadow state

  Hardware devices (NightSky, Volante, etc.) are write-only — no readback.
  Shadow state tracks what was last sent so that `texture-state` returns a
  best-effort map. Shadow is initialized from `:texture/initial-state` in the
  device EDN and updated on every `texture-set!`, `freeze!`, and `thaw!` call.

  Shadow state shape:
    {:source     :texture-set!  ; last operation that touched the shadow
     :shadow     {...}          ; last-known param map sent to the device
     :confident? true}          ; false when the device may have been modified
                                ; externally (e.g. front-panel knob turns)

  Design notes: doc/design-texture-protocol.md (TBD)"
  (:require [cljseq.clock          :as clock]
            [cljseq.core           :as core]
            [cljseq.temporal-buffer :as tb]))

;; ---------------------------------------------------------------------------
;; ITexture protocol
;; ---------------------------------------------------------------------------

(defprotocol ITexture
  "Unified interface for all temporal/texture devices.

  Software implementations (TemporalBuffer, SpectralState) return live state
  from `texture-state`. Hardware implementations return shadow state — cljseq's
  best record of what was last sent to the device."

  (freeze!
    [this]
    "Freeze the device: arrest input accumulation while output continues.
    For TemporalBuffer: hold! true. For hardware: send freeze CC/NRPN.")

  (thaw!
    [this]
    "Unfreeze the device: resume normal input accumulation.
    For TemporalBuffer: hold! false. For hardware: send unfreeze CC/NRPN.")

  (frozen?
    [this]
    "Return true if the device is currently frozen.")

  (texture-state
    [this]
    "Return the current state map for the device.
    For software: live state (from temporal-buffer-info or equivalent).
    For hardware: shadow state — {:source kw :shadow {...} :confident? bool}.")

  (texture-set!
    [this params]
    "Apply a device-specific param map to the device atomically.
    For TemporalBuffer: delegates to temporal-buffer-set! directly.
    Recognized keys match the device's native state shape.
    Hardware: dispatches each key to the corresponding CC/NRPN and updates shadow.")

  (texture-fade!
    [this target beats]
    "Interpolate numeric params from their current values to `target` over `beats` beats.
    Non-numeric params (keywords, maps, booleans) are applied on the first interpolation
    step alongside the initial numeric values.
    Uses a background thread; returns immediately.
    `beats` must be positive; sub-beat resolution not guaranteed."))

;; ---------------------------------------------------------------------------
;; Shadow state registry
;;
;; Keyed by device-id (any value used as a registry key). Each entry:
;;   {:source     :texture-set!
;;    :shadow     {param-kw value ...}
;;    :confident? bool}
;;
;; Only hardware implementations use this; software devices have live state.
;; ---------------------------------------------------------------------------

(defonce ^:private shadow-registry (atom {}))

(defn shadow-init!
  "Initialize shadow state for a hardware device from `initial-params`.

  `device-id`     — keyword or value identifying the device in the registry
  `initial-params` — best-known initial parameter map (from EDN or explicit)

  Sets :confident? false until the first texture-set! confirms the shadow.
  Called by hardware device constructors."
  [device-id initial-params]
  (swap! shadow-registry assoc device-id
         {:source     :init
          :shadow     initial-params
          :confident? false})
  nil)

(defn shadow-update!
  "Update the shadow state for `device-id` after a write operation.

  `source`  — keyword naming the operation: :freeze! :thaw! :texture-set!
  `updates` — map of params that were just written (merged into existing shadow)"
  [device-id source updates]
  (swap! shadow-registry update device-id
         (fn [entry]
           (-> (or entry {:shadow {} :confident? false})
               (assoc  :source     source
                       :confident? true)
               (update :shadow merge updates))))
  nil)

(defn shadow-get
  "Return the shadow state map for `device-id`, or nil if not initialized."
  [device-id]
  (get @shadow-registry device-id))

;; ---------------------------------------------------------------------------
;; Texture registry
;; ---------------------------------------------------------------------------

(defonce ^:private texture-registry (atom {}))

(defn deftexture!
  "Register an ITexture implementation under `device-id`.

  `device-id` — any value (keyword recommended)
  `tx`        — an ITexture implementation

  Re-registering with the same id replaces the previous entry.

  Example:
    (deftexture! :echo (buffer-texture :echo))"
  [device-id tx]
  (swap! texture-registry assoc device-id tx)
  device-id)

(defn get-texture
  "Return the registered ITexture implementation for `device-id`, or nil."
  [device-id]
  (get @texture-registry device-id))

(defn texture-names
  "Return a seq of registered texture device ids."
  []
  (keys @texture-registry))

;; ---------------------------------------------------------------------------
;; Interpolation helpers
;; ---------------------------------------------------------------------------

(defn- lerp [a b t]
  (+ (double a) (* t (- (double b) (double a)))))

(defn- lerp-params
  "Linear interpolate numeric values in `target` from `current-state` at `t` ∈ [0,1].
  Non-numeric values (keywords, maps, booleans) are left unchanged in `target`."
  [current-state target t]
  (reduce-kv
    (fn [acc k v]
      (let [cur (get current-state k v)]
        (assoc acc k
               (if (and (number? v) (number? cur))
                 (lerp cur v t)
                 v))))
    {}
    target))

(defn- fade-thread!
  "Spawn a background thread that drives `tx` from its current state to `target`
  over `beats` beats using `n-steps` linear steps."
  [tx target beats n-steps]
  (let [bpm       (core/get-bpm)
        step-ms   (long (/ (clock/beats->ms beats bpm) n-steps))
        start     (texture-state tx)]
    (future
      (try
        (dotimes [i n-steps]
          (let [t  (/ (inc i) (double n-steps))
                p  (lerp-params start target t)]
            (texture-set! tx p)
            (when (< (inc i) n-steps)
              (Thread/sleep step-ms))))
        (catch Exception e
          (binding [*out* *err*]
            (println "[texture] texture-fade! error:" (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; TemporalBufferTexture — ITexture wrapper for temporal buffers
;; ---------------------------------------------------------------------------

(defrecord TemporalBufferTexture [buf-name]
  ITexture

  (freeze! [_]
    (tb/temporal-buffer-hold! buf-name true))

  (thaw! [_]
    (tb/temporal-buffer-hold! buf-name false))

  (frozen? [_]
    (boolean (:hold? (tb/temporal-buffer-info buf-name))))

  (texture-state [_]
    (tb/temporal-buffer-info buf-name))

  (texture-set! [_ params]
    (tb/temporal-buffer-set! buf-name params))

  (texture-fade! [this target beats]
    (let [n-steps (max 1 (int beats))]
      (fade-thread! this target beats n-steps)
      nil)))

(defn buffer-texture
  "Return an ITexture backed by the temporal buffer named `buf-name`.

  The buffer must already be registered via `deftemporal-buffer`.

  Example:
    (deftemporal-buffer :echo {:active-zone :z3})
    (deftexture! :echo (buffer-texture :echo))"
  [buf-name]
  (->TemporalBufferTexture buf-name))

;; ---------------------------------------------------------------------------
;; texture-transition! — coordinated multi-device transition
;; ---------------------------------------------------------------------------

(defn texture-transition!
  "Apply a coordinated transition across multiple ITexture devices simultaneously.

  `ops` — map of device-id → transition spec:
    {:target  {param-map}   ; required — param map passed to texture-set! or texture-fade!
     :beats   N}            ; optional — if present and positive, uses texture-fade!
                            ;            otherwise uses texture-set! (immediate)

  Devices are addressed by id in the texture registry. Unknown device ids are
  silently skipped. All transitions are initiated in the calling thread before
  any blocking — fade operations run in background futures.

  Example:
    (texture-transition!
      {:echo    {:target {:active-zone :z4 :rate 0.8} :beats 8}
       :reverb  {:target {:hold? true}}
       :looper  {:target {:active-zone :z5} :beats 16}})"
  [ops]
  (doseq [[device-id {:keys [target beats]}] ops]
    (when-let [tx (get @texture-registry device-id)]
      (if (and beats (pos? beats))
        (texture-fade! tx target beats)
        (texture-set! tx target))))
  nil)
