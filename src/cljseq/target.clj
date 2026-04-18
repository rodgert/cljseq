; SPDX-License-Identifier: EPL-2.0
(ns cljseq.target
  "Audio target protocols and registry — ITarget / ITriggerTarget / IParamTarget.

  Every audio target in cljseq (synthesizer, FX processor, sample player,
  external hardware) implements the protocols that match its capabilities:

    ITarget         — base: identity and connectivity (all targets)
    ITriggerTarget  — triggered note events with voice lifecycle (synths)
    IParamTarget    — continuous parameter control via the ctrl tree (all targets
                      with controllable parameters)

  ## Protocols

    (target-id    t)           → keyword — registry key for this target
    (target-live? t)           → boolean — is the transport reachable?

    (trigger-note!  t event)   → handle — fire a note; nil if not connected
    (release-note!  t handle)  → nil — explicit release for sustained notes

    (param-root   t)           → vector — ctrl-tree path root for this device
    (send-param!  t path v)    → nil — set param at relative path

  ## Registry

    (register! kw target)    — add a target under keyword kw
    (lookup    kw)           — retrieve by keyword; nil if not found
    (registered-targets)     — map of all registered targets

  ## Concrete implementations

    FnTarget      — wraps plain functions; used by SC, sample backends
    ParamTarget   — ITarget + IParamTarget for param-only devices (FX units,
                    OSC/MIDI-controlled hardware, Pd patches in param mode)

  ## Registration pattern for backend namespaces

  Backend namespaces (cljseq.sc, cljseq.sample) register themselves at load
  time. The protocols are defined here; implementations live in each backend
  to avoid compile-time circular dependencies.

    ;; In cljseq.sc, at namespace load time:
    (target/register! :sc
      (target/fn-target :sc
        :trigger-fn  sc-trigger-impl
        :release-fn  sc-release-impl
        :live?-fn    sc-connected?
        :param-root  [:sc]))

  ## Usage from core/play!

  The `:synth` key in an event map names the registered target:

    (play! {:synth :sc :pitch/midi 60 :dur/beats 1 :mod/velocity 90})
    ;; → looks up :sc in registry → calls trigger-note!

    (play! {:synth :my-pd-patch :pitch/midi 60 :dur/beats 2})
    ;; → looks up :my-pd-patch → calls trigger-note! → OSC to Pd"
  (:require [cljseq.ctrl :as ctrl]))

;; ---------------------------------------------------------------------------
;; Protocols
;; ---------------------------------------------------------------------------

(defprotocol ITarget
  "Base protocol: identity and connectivity. All targets implement this."
  (target-id    [t]
    "Return the keyword this target is registered under.")
  (target-live? [t]
    "Return true if the underlying transport is reachable / connected."))

(defprotocol ITriggerTarget
  "Capability: accepts triggered note events with voice lifecycle.
  Implemented by synthesizers and anything that responds to note-on/off."
  (trigger-note!  [t event]
    "Fire a note. `event` is a standard cljseq step map:
       {:pitch/midi N :dur/beats D :mod/velocity V :synth kw ...}
     Returns an opaque handle suitable for release-note!, or nil.")
  (release-note!  [t handle]
    "Explicitly release a sustained note identified by `handle`.
     No-op if the handle is nil or already released."))

(defprotocol IParamTarget
  "Capability: continuous parameter control via the ctrl tree.
  Implemented by synthesizers, FX processors, and any hardware with
  addressable parameters."
  (param-root   [t]
    "Return the ctrl-tree path root for this device's parameters.
     e.g. [:studio :nightsky] or [:sc]")
  (send-param!  [t path value]
    "Set the parameter at `path` (relative to param-root) to `value`.
     path may be a keyword (:mix) or a vector ([:reverb :mix]).
     Delegates to ctrl/set! at (conj (param-root t) path) by default."))

;; ---------------------------------------------------------------------------
;; Target registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn register!
  "Register `target` under keyword `kw`. Replaces any prior registration.
  `target` must implement at least ITarget.

  Example:
    (register! :sc my-sc-target)"
  [kw target]
  (swap! registry assoc kw target)
  kw)

(defn lookup
  "Return the target registered under `kw`, or nil."
  [kw]
  (get @registry kw))

(defn registered-targets
  "Return the full registry map {kw → target}."
  []
  @registry)

;; ---------------------------------------------------------------------------
;; FnTarget — function-wrapped target; used by SC, sample, and custom backends
;; ---------------------------------------------------------------------------

(defrecord FnTarget [id trigger-fn release-fn live?-fn param-root-path]
  ITarget
  (target-id    [_]   id)
  (target-live? [_]   (boolean (live?-fn)))

  ITriggerTarget
  (trigger-note!  [_ event]  (trigger-fn event))
  (release-note!  [_ handle] (when release-fn (release-fn handle)))

  IParamTarget
  (param-root  [_]           param-root-path)
  (send-param! [_ path value]
    (let [full-path (if (vector? path)
                      (into param-root-path path)
                      (conj param-root-path path))]
      (ctrl/set! full-path value))))

(defn fn-target
  "Build an FnTarget record from named options.

  Required:
    :id        — keyword name (used as registry key)
    :live?-fn  — zero-arg fn returning boolean

  For ITriggerTarget:
    :trigger-fn  — (fn [event]) → handle or nil
    :release-fn  — (fn [handle]) → nil (optional; default no-op)

  For IParamTarget:
    :param-root  — ctrl-tree path root vector (e.g. [:sc])

  Example:
    (fn-target :sc
      :trigger-fn  my-sc-trigger
      :live?-fn    sc-connected?
      :param-root  [:sc])"
  [id & {:keys [trigger-fn release-fn live?-fn param-root]
          :or   {trigger-fn  (fn [_] nil)
                 release-fn  nil
                 live?-fn    (constantly false)
                 param-root  []}}]
  (->FnTarget id trigger-fn release-fn live?-fn (vec param-root)))

;; ---------------------------------------------------------------------------
;; ParamTarget — ITarget + IParamTarget; for FX processors and devices without
;; triggered note events (e.g. reverb units, OSC FX processors, Pd patches
;; operating in continuous-param mode)
;; ---------------------------------------------------------------------------

(defrecord ParamTarget [id param-root-path live?-fn]
  ITarget
  (target-id    [_] id)
  (target-live? [_] (boolean (live?-fn)))

  IParamTarget
  (param-root  [_] param-root-path)
  (send-param! [_ path value]
    ;; Mirror to ctrl tree so watchers / UI see the value.
    ;; Actual transport (OSC, MIDI CC, etc.) is handled by ctrl/bind! bindings
    ;; on the param subtree — the ctrl tree delivers the bytes.
    (let [full-path (if (vector? path)
                      (into param-root-path path)
                      (conj param-root-path path))]
      (ctrl/set! full-path value))))

(defn param-target
  "Build a ParamTarget for a continuously-running device with addressable
  parameters (FX processors, OSC/MIDI-controlled hardware, Pd patches).

  The device's param subtree is wired to its transport (OSC address, MIDI CC,
  etc.) via ctrl/bind! — this record provides the device identity and the
  IParamTarget protocol face.

  Options:
    :param-root  — ctrl-tree path root (e.g. [:studio :nightsky])
    :live?-fn    — zero-arg fn returning boolean (default: always true)"
  [id & {:keys [param-root live?-fn]
          :or   {param-root []
                 live?-fn   (constantly true)}}]
  (->ParamTarget id (vec param-root) live?-fn))
