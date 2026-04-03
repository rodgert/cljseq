; SPDX-License-Identifier: EPL-2.0
(ns cljseq.device
  "cljseq MIDI device model — defdevice and EDN device map loading.

  Loads device maps from EDN files in resources/devices/ and registers
  devices in the control tree. Two device roles are supported:

    :target     — cljseq drives the device (e.g. Korg Minilogue XD)
                  defdevice registers a ctrl node for every concrete CC entry
                  and binds each node to the corresponding MIDI CC number.

    :controller — device transmits to cljseq (e.g. Arturia KeyStep)
                  defdevice registers the device's bind-sources so that
                  device-bind! can translate human-readable source names
                  (e.g. :touch-strip) into ctrl tree bindings.

  ## Target device — quick start
    (defdevice :korg/minilogue-xd
      (load-device-map \"devices/korg-minilogue-xd.edn\"))

    ;; Numeric CC value (scaled 0–127)
    (device-send! :korg/minilogue-xd [:filter :cutoff] 80)

    ;; Semantic keyword (resolved via :values in device map)
    (device-send! :korg/minilogue-xd [:vco1 :wave] :saw)    ; => CC 23 = 43
    (device-send! :korg/minilogue-xd [:lfo :mode] :bpm-sync)

  ## Controller device — bind-source registration
    (defdevice :arturia/keystep
      (load-device-map \"devices/arturia-keystep.edn\"))

    (device-bind! [:filter/cutoff]
                  {:device :arturia/keystep :source :touch-strip})

    ;; Actual inbound MIDI routing requires a listener thread (future sprint).
    ;; device-bind! records the intent in the ctrl tree now.

  ## Direct definition (no EDN file)
    (defdevice :my-synth
      {:device/id    :my-synth
       :device/type  :synthesizer
       :device/role  :target
       :midi/channel 1
       :midi/cc      [{:cc 74 :path [:filter :cutoff] :range [0 127]}]})

  ## Inspection
    (list-devices)                                    ; => [:korg/minilogue-xd ...]
    (get-device :korg/minilogue-xd)                   ; => full registry entry

  Key design decisions: MIDI device research (doc/midi-device-research.md),
  Q55 (defdevice design — see inline notes in device.clj prior sprint)."
  (:require [clojure.java.io :as io]
            [clojure.edn     :as edn]
            [cljseq.ctrl     :as ctrl]))

;; ---------------------------------------------------------------------------
;; Device registry
;; Each entry: {:map device-map :channel n :role kw :resolvers {...} :cc-paths [...]}
;; ---------------------------------------------------------------------------

(defonce ^:private device-registry (atom {}))

;; ---------------------------------------------------------------------------
;; EDN loading
;; ---------------------------------------------------------------------------

(defn load-device-map
  "Load a device map from a classpath resource path.

  The path is relative to the classpath root (resources/ directory).
  Example:
    (load-device-map \"devices/korg-minilogue-xd.edn\")"
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))
    (throw (ex-info "cljseq.device/load-device-map: resource not found"
                    {:path resource-path}))))

;; ---------------------------------------------------------------------------
;; Resolver building — :values [{:value n :label kw}] → {:label n}
;; ---------------------------------------------------------------------------

(defn- label->value-map
  "Build a {:label cc-val} lookup from a :values vector."
  [values]
  (into {} (map (fn [{:keys [value label]}] [label value]) values)))

(defn- build-resolvers
  "Build {path-vec {:label cc-val}} for every CC entry that has :values."
  [cc-list]
  (reduce (fn [acc {:keys [path values]}]
            (if (seq values)
              (assoc acc path (label->value-map values))
              acc))
          {}
          (or cc-list [])))

;; ---------------------------------------------------------------------------
;; CC path extraction
;; ---------------------------------------------------------------------------

(defn- concrete-cc-entries
  "Return CC entries that have a concrete (integer) :cc number.
  Skips :configurable entries (used on controller devices like the KeyStep)."
  [cc-list]
  (filter #(integer? (:cc %)) (or cc-list [])))

(defn- cc-paths
  "Extract the :path from every concrete CC entry, for later unbinding."
  [device-map]
  (mapv :path (concrete-cc-entries (:midi/cc device-map))))

(defn- nrpn-paths
  "Extract the :path from every NRPN entry, for later unbinding."
  [device-map]
  (mapv :path (:midi/nrpn device-map)))

;; ---------------------------------------------------------------------------
;; Ctrl tree registration — target devices only
;; ---------------------------------------------------------------------------

(defn- register-cc-nodes!
  "Create a ctrl tree node and a MIDI CC binding for each concrete CC entry.
  Nodes are created under [device-id & path]."
  [device-id channel cc-list]
  (doseq [{:keys [cc path range values bipolar center]} (concrete-cc-entries cc-list)]
    (let [node-path (into [device-id] path)
          r         (or range [0 127])
          meta      (cond-> {:range r}
                      (seq values) (assoc :values (mapv :label values))
                      bipolar      (assoc :bipolar true :center (or center 64)))
          node-type (if (seq values) :enum :int)]
      (ctrl/defnode! node-path :type node-type :node-meta meta)
      (ctrl/bind! node-path
                  {:type :midi-cc :channel channel :cc-num cc :range r}
                  :priority 20))))

(defn- register-nrpn-nodes!
  "Create a ctrl tree node and a MIDI NRPN binding for each NRPN entry.
  Nodes are created under [device-id & path].

  NRPN entries default to 14-bit resolution (range [0 16383]).
  Add :bits 7 and :range [0 127] to an entry for 7-bit parameters."
  [device-id channel nrpn-list]
  (doseq [{:keys [nrpn path range bits raw]} (or nrpn-list [])]
    (let [node-path (into [device-id] path)
          b         (int (or bits 14))
          max-val   (if (= 14 b) 16383 127)
          r         (or range [0 max-val])]
      (ctrl/defnode! node-path :type :int :node-meta {:range r})
      (ctrl/bind! node-path
                  (cond-> {:type :midi-nrpn :channel channel :nrpn nrpn :bits b :range r}
                    raw (assoc :raw true))
                  :priority 20))))

(defn- register-target-device!
  "Register all CC and NRPN nodes for a :target device."
  [device-id channel device-map]
  (register-cc-nodes!   device-id channel (:midi/cc   device-map))
  (register-nrpn-nodes! device-id channel (:midi/nrpn device-map)))

;; ---------------------------------------------------------------------------
;; Unbind prior registration (for safe re-registration at the REPL)
;; ---------------------------------------------------------------------------

(defn- unregister-device!
  "Remove all ctrl bindings previously created for device-id."
  [device-id]
  (when-let [reg (clojure.core/get @device-registry device-id)]
    (doseq [path (concat (:cc-paths reg) (:nrpn-paths reg))]
      (ctrl/unbind! (into [device-id] path) :all))))

;; ---------------------------------------------------------------------------
;; defdevice
;; ---------------------------------------------------------------------------

(defn defdevice
  "Register a MIDI device from a device map.

  For :target devices: registers a ctrl node and binding for each concrete
  CC entry (:midi-cc) and each NRPN entry (:midi-nrpn). Existing registrations
  for the same device-id are cleanly replaced (REPL-safe).

  For :controller devices: registers bind-sources so device-bind! can
  translate source names into ctrl tree bindings.

  Returns device-id.

  Examples:
    (defdevice :korg/minilogue-xd
      (load-device-map \"devices/korg-minilogue-xd.edn\"))

    (defdevice :my-synth
      {:device/role :target :midi/channel 3
       :midi/cc   [{:cc 74 :path [:filter :cutoff] :range [0 127]}]
       :midi/nrpn [{:nrpn 1 :path [:portamento] :bits 14}]})"
  [id device-map]
  (unregister-device! id)
  (let [channel (int (clojure.core/get device-map :midi/channel 1))
        role    (:device/role device-map)
        cc-list (:midi/cc device-map)]
    (when (= :target role)
      (register-target-device! id channel device-map))
    (swap! device-registry assoc id
           {:map        device-map
            :channel    channel
            :role       role
            :resolvers  (build-resolvers cc-list)
            :cc-paths   (cc-paths device-map)
            :nrpn-paths (nrpn-paths device-map)})
    id))

;; ---------------------------------------------------------------------------
;; device-send! — semantic value resolution + ctrl/send! dispatch
;; ---------------------------------------------------------------------------

(defn device-send!
  "Send a value to a device control path, resolving semantic keyword values.

  `device-id` — keyword registered with defdevice (e.g. :korg/minilogue-xd)
  `path`      — control path vector (e.g. [:vco1 :wave])
  `value`     — numeric CC value 0–127, or a keyword label defined in the
                device map's :values list (e.g. :saw, :bpm-sync)

  Semantic resolution:
    The device map's :values list provides a label→CC-value mapping.
    (device-send! :korg/minilogue-xd [:vco1 :wave] :saw)   ; => CC 23 = 43
    (device-send! :korg/minilogue-xd [:filter :cutoff] 80) ; => CC 40 = 80

  Dispatches to the sidecar via ctrl/send! at the current wall-clock instant."
  [device-id path value]
  (let [reg (clojure.core/get @device-registry device-id)]
    (when-not reg
      (throw (ex-info "cljseq.device/device-send!: unknown device"
                      {:device device-id :registered (keys @device-registry)})))
    (let [resolved (if (keyword? value)
                     (let [lv (get-in reg [:resolvers path value])]
                       (if (nil? lv)
                         (throw (ex-info "cljseq.device/device-send!: no resolver for label"
                                         {:device device-id :path path :label value
                                          :known-labels (keys (clojure.core/get-in reg [:resolvers path]))}))
                         lv))
                     value)]
      (ctrl/send! (into [device-id] path) resolved))))

;; ---------------------------------------------------------------------------
;; device-bind! — controller device source → ctrl tree binding
;; ---------------------------------------------------------------------------

(defn device-bind!
  "Bind a ctrl tree path to a source on a controller device.

  `path`   — ctrl tree path to bind (e.g. [:filter/cutoff])
  `source` — map with :device (registered id) and :source (bind-source key)
             e.g. {:device :arturia/keystep :source :touch-strip}
  Options:
    :priority — binding priority (default 20)

  The source is looked up in the device's :cljseq/bind-sources map.
  The binding is stored on the ctrl node as type :midi-device-input.

  Actual inbound MIDI routing (KeyStep events → ctrl tree updates) requires
  a listener thread and is deferred to a future sprint. device-bind! records
  the intent so it can be activated when that infrastructure is in place."
  [path {:keys [device source]} & {:keys [priority] :or {priority 20}}]
  (let [reg (clojure.core/get @device-registry device)]
    (when-not reg
      (throw (ex-info "cljseq.device/device-bind!: unknown device"
                      {:device device :registered (keys @device-registry)})))
    (let [bind-sources (clojure.core/get-in reg [:map :cljseq/bind-sources])
          source-info  (clojure.core/get bind-sources source)]
      (when-not source-info
        (throw (ex-info "cljseq.device/device-bind!: unknown bind source"
                        {:device device :source source
                         :available (keys bind-sources)})))
      ;; :source is the bind-source name (e.g. :touch-strip); the underlying
      ;; MIDI message type from source-info uses :midi-source to avoid collision.
      (ctrl/bind! path
                  {:type        :midi-device-input
                   :device      device
                   :source      source
                   :channel     (:channel reg)
                   :midi-source (:source source-info)
                   :yields      (:yields source-info)}
                  :priority priority))))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn list-devices
  "Return a seq of registered device IDs."
  []
  (keys @device-registry))

(defn get-device
  "Return the full registry entry for device-id, or nil if not registered.
  Includes :map, :channel, :role, :resolvers, :cc-paths."
  [device-id]
  (clojure.core/get @device-registry device-id))
