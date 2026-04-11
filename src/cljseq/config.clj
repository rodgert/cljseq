; SPDX-License-Identifier: EPL-2.0
(ns cljseq.config
  "Configuration registry — all tunable system parameters with defaults.

  Exposes all §25 parameters through:
    - get-config / set-config! / all-configs (Clojure API)
    - The ctrl tree at [:config <key>] paths (HTTP GET /ctrl/config/<key>)

  Parameters are seeded into the ctrl tree on core/start! so that external
  tools (DAWs, TouchOSC, custom GUIs) can read them via HTTP/OSC.

  ## Parameter keys
    :bpm                           — master tempo (beats per minute)
    :sched-ahead-ms/midi-hardware  — MIDI hardware schedule-ahead window (ms)
    :sched-ahead-ms/sc-osc         — SuperCollider OSC schedule-ahead window (ms)
    :sched-ahead-ms/vcv-rack       — VCV Rack schedule-ahead window (ms)
    :sched-ahead-ms/daw-midi       — DAW MIDI schedule-ahead window (ms)
    :sched-ahead-ms/link           — Ableton Link schedule-ahead window (ms)
    :lfo/update-rate-hz            — LFO / continuous-parameter update rate (Hz)
    :ctrl/undo-stack-depth         — ctrl tree undo stack depth
    :link/quantum                  — Ableton Link quantum (phrase length in beats)
    :osc/control-port              — OSC control-plane server port
    :osc/data-port                 — OSC data-plane / sidecar passthrough port

  ## Usage
    (config/get-config :bpm)             ;=> 120
    (config/set-config! :bpm 140)
    (config/all-configs)                 ;=> {:bpm 120 ...}

    ;; via HTTP (after start-server!):
    ;;   GET /ctrl/config/bpm             → {\"value\":120}
    ;;   GET /ctrl/config/link%2Fquantum  → {\"value\":4}
    ;;   GET /ctrl/config/sched-ahead-ms%2Fmidi-hardware → {\"value\":100}

  ## BPM write path
  For BPM changes with full side effects (timeline reanchor, thread unpark,
  Link propagation), use core/set-bpm! or config/set-config! :bpm.
  HTTP PUT /ctrl/config/bpm updates the registry value but does not rebuild
  the timeline — use HTTP PUT /bpm for live tempo changes from external tools.

  Key design decisions: §25 of R&R doc."
  (:require [cljseq.ctrl :as ctrl]))

;; ---------------------------------------------------------------------------
;; Parameter registry
;; ---------------------------------------------------------------------------

(def ^:private registry
  "Immutable registry of all tunable parameters.

  Each entry:
    :default   — value used on fresh start! when system-state has no override
    :type      — ctrl node type (:int :float :bool :keyword :enum :data)
    :doc       — one-line human description
    :validate  — (fn [v]) → true if valid; nil means always valid"
  {:bpm
   {:default  120
    :type     :float
    :doc      "Master tempo in beats per minute."
    :validate #(and (number? %) (pos? %))}

   :sched-ahead-ms/midi-hardware
   {:default  100
    :type     :int
    :doc      "MIDI hardware schedule-ahead window in milliseconds."
    :validate #(and (integer? %) (pos? %))}

   :sched-ahead-ms/sc-osc
   {:default  30
    :type     :int
    :doc      "SuperCollider OSC schedule-ahead window in milliseconds."
    :validate #(and (integer? %) (pos? %))}

   :sched-ahead-ms/vcv-rack
   {:default  50
    :type     :int
    :doc      "VCV Rack schedule-ahead window in milliseconds."
    :validate #(and (integer? %) (pos? %))}

   :sched-ahead-ms/daw-midi
   {:default  50
    :type     :int
    :doc      "DAW MIDI schedule-ahead window in milliseconds."
    :validate #(and (integer? %) (pos? %))}

   :sched-ahead-ms/link
   {:default  0
    :type     :int
    :doc      "Ableton Link schedule-ahead window in milliseconds (typically 0)."
    :validate #(and (integer? %) (>= % 0))}

   :lfo/update-rate-hz
   {:default  100
    :type     :int
    :doc      "LFO / continuous-parameter update rate in Hz."
    :validate #(and (integer? %) (pos? %) (<= % 1000))}

   :ctrl/undo-stack-depth
   {:default  50
    :type     :int
    :doc      "Maximum number of ctrl tree states kept in the undo stack."
    :validate #(and (integer? %) (pos? %))}

   :link/quantum
   {:default  4
    :type     :int
    :doc      "Ableton Link quantum — phrase length in beats. All peers with the same quantum stay in phase."
    :validate #(and (integer? %) (pos? %))}

   :osc/control-port
   {:default  57121
    :type     :int
    :doc      "UDP port for the cljseq OSC control-plane server."
    :validate #(and (integer? %) (> % 1023) (< % 65536))}

   :osc/data-port
   {:default  57110
    :type     :int
    :doc      "UDP port for the OSC data-plane / sidecar passthrough."
    :validate #(and (integer? %) (> % 1023) (< % 65536))}})

;; ---------------------------------------------------------------------------
;; System state reference (injected by cljseq.core/start!)
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

(defn -register-system!
  "Called by cljseq.core/start! to inject the system-state atom.
  Not part of the public API."
  [state-atom]
  (reset! system-ref state-atom))

;; ---------------------------------------------------------------------------
;; Effect registry
;;
;; Params with non-trivial runtime side effects (like :bpm, which must reanchor
;; the timeline) register a handler here so that set-config! can apply them.
;; Registered by cljseq.core/start! — not part of the public API.
;; ---------------------------------------------------------------------------

(defonce ^:private effect-registry (atom {}))

(defn -register-effect!
  "Register a (fn [value]) side-effect for a config key.
  Called by cljseq.core/start! — not part of the public API."
  [key f]
  (swap! effect-registry assoc key f))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn param-info
  "Return the registry entry for `key`, or nil if the key is unknown.

  Example:
    (config/param-info :bpm)
    ;=> {:default 120 :type :float :doc \"Master tempo...\" :validate #fn}"
  [key]
  (clojure.core/get registry key))

(defn all-param-keys
  "Return the set of all registered configuration parameter keys."
  []
  (set (keys registry)))

(defn default-config
  "Return a map of all parameters at their registry defaults.
  Used by cljseq.core/start! to reset :config on each fresh start.
  Not part of the public API."
  []
  (into {} (map (fn [[k e]] [k (:default e)])) registry))

(defn get-config
  "Read the current value of configuration parameter `key`.

  Returns the value from system-state [:config key], falling back to the
  registry default when no runtime value has been set.

  Returns nil if `key` is not a registered parameter.

  Examples:
    (config/get-config :bpm)            ;=> 120
    (config/get-config :link/quantum)   ;=> 4"
  [key]
  (when (contains? registry key)
    (let [default (:default (clojure.core/get registry key))]
      (if-let [s @system-ref]
        (let [v (clojure.core/get-in @s [:config key])]
          (if (some? v) v default))
        default))))

(defn all-configs
  "Return a map of all configuration parameters and their current values.

  Example:
    (config/all-configs)
    ;=> {:bpm 120 :link/quantum 4 :osc/control-port 57121 ...}"
  []
  (into {}
        (map (fn [k] [k (get-config k)]))
        (keys registry)))

(defn set-config!
  "Set configuration parameter `key` to `value`.

  Validates the value against the registry entry and throws ex-info if invalid
  or if `key` is not in the registry.

  Writes to system-state [:config key] and the ctrl tree [:config key] so the
  value is immediately visible via HTTP GET /ctrl/config/<key>.

  Fires any registered side-effect function for this key (e.g. :bpm triggers
  a full timeline reanchor and loop-thread unpark via core/set-bpm!).

  Returns nil.

  Examples:
    (config/set-config! :link/quantum 8)
    (config/set-config! :bpm 140)"
  [key value]
  (let [entry (clojure.core/get registry key)]
    (when-not entry
      (throw (ex-info (str "Unknown config key: " (pr-str key))
                      {:key key})))
    (let [validate (:validate entry)]
      (when (and validate (not (validate value)))
        (throw (ex-info (str "Invalid value for " (pr-str key) ": " (pr-str value))
                        {:key key :value value}))))
    ;; Write to system-state [:config key]
    (when-let [s @system-ref]
      (swap! s assoc-in [:config key] value))
    ;; Write to ctrl tree for HTTP/OSC visibility
    (ctrl/set! [:config key] value)
    ;; Apply registered side effect (e.g. :bpm → timeline reanchor)
    (when-let [f (clojure.core/get @effect-registry key)]
      (f value))
    nil))

(defn seed-ctrl-tree!
  "Populate the ctrl tree with typed nodes for all configuration parameters.

  Called by cljseq.core/start! after system-ref and ctrl system are both
  registered. Creates a CtrlNode for each registry entry using the current
  system-state value (falling back to the registry default).

  Not part of the public API."
  []
  (doseq [[key entry] registry]
    (ctrl/defnode! [:config key]
                   :type      (:type entry)
                   :node-meta {:doc (:doc entry)}
                   :value     (get-config key))))
