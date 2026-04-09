; SPDX-License-Identifier: EPL-2.0
(ns cljseq.topology
  "cljseq studio MIDI topology -- Layer 1.

  Provides stable logical device aliases that survive MIDI port index changes
  across reboots, USB reconnects, and MioXM re-patching. Device aliases are
  declared in an EDN topology file (default: resources/studio-topology.edn)
  and resolved to OS MIDI port indices at sidecar startup time.

  ## Quick start

    (topology/load-topology!)               ; load default topology file
    (topology/start-sidecar! :hydrasynth)   ; output to Hydrasynth
    (topology/start-sidecar! :hydrasynth    ; output + MIDI input
                             :input :linnstrument)

  ## Topology EDN format

  The topology file is a map with :devices, :interfaces, and :hosts keys.
  Each :devices entry maps an alias keyword to:

    :port-pattern  -- substring matched against OS MIDI output port name
    :in-pattern    -- substring for MIDI input port (defaults to :port-pattern)
    :device-id     -- keyword reference to a device map in resources/devices/
    :midi/channel  -- default MIDI channel (1-16)
    :role          -- :synth :controller :fx :midi-to-cv :looper :virtual

  See resources/studio-topology.edn for the full reference topology.

  ## Layer roadmap

  Layer 1 (Sprint 38): logical device aliases + port-pattern resolution.
  Layer 2 (future):    MioXM multi-host routing, per-interface port assignment.
  Layer 3 (future):    Spatial/group device targets, trajectory routing."
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [cljseq.dirs     :as dirs]
            [cljseq.sidecar  :as sidecar]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(def ^:private topology (atom nil))

(defn default-topology-path
  "Return the default path for the studio topology file.

  Resolution order:
    1. CLJSEQ_TOPOLOGY environment variable
    2. (dirs/user-config-dir)/topology.edn
       -- XDG default: ~/.config/cljseq/topology.edn
       -- macOS native: ~/Library/Application Support/cljseq/topology.edn

  See doc/topology-example.edn for the full schema and annotations."
  []
  (dirs/topology-path))

;; ---------------------------------------------------------------------------
;; Load / reload
;; ---------------------------------------------------------------------------

(defn load-topology!
  "Load a studio topology from `path` (default: resources/studio-topology.edn).

  The file must be a valid EDN map with a :devices key. Replaces any
  previously loaded topology.

  Returns the topology map."
  ([]
   (load-topology! (default-topology-path)))
  ([path]
   (let [f   (io/file path)
         topo (if (.exists f)
                (edn/read-string (slurp f))
                (throw (ex-info
                        (str "Topology file not found: " path "\n"
                             "  Create it from the schema reference: doc/topology-example.edn\n"
                             "  Or set CLJSEQ_TOPOLOGY to an explicit path.")
                        {:path path})))]
     (when-not (map? (:devices topo))
       (throw (ex-info "Invalid topology: :devices must be a map"
                       {:path path :topology topo})))
     (reset! topology topo)
     (let [n (count (:devices topo))]
       (println (format "[topology] loaded %s (%d device%s)"
                        path n (if (= 1 n) "" "s"))))
     topo)))

(defn loaded?
  "Return true if a topology has been loaded."
  []
  (some? @topology))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn device-ids
  "Return a sorted vector of all device alias keywords in the loaded topology."
  []
  (when-let [t @topology]
    (into [] (sort (keys (:devices t))))))

(defn device-info
  "Return the descriptor map for device alias `alias`, or nil if not found.

  The descriptor includes :port-pattern, :in-pattern, :device-id,
  :midi/channel, :role, and any other keys defined in the topology file."
  [alias]
  (when-let [t @topology]
    (get (:devices t) alias)))

(defn interface-info
  "Return the descriptor map for interface alias `alias`, or nil if not found."
  [alias]
  (when-let [t @topology]
    (get (:interfaces t) alias)))

(defn topology-meta
  "Return the topology metadata map (id, version, description), or nil."
  []
  (when-let [t @topology]
    (select-keys t [:topology/id :topology/version :topology/description])))

;; ---------------------------------------------------------------------------
;; Port resolution
;; ---------------------------------------------------------------------------

(defn- pattern-for
  "Return the port-pattern string for `alias` and `direction` (:output or :input)."
  [alias direction]
  (let [entry (device-info alias)]
    (when entry
      (if (= direction :input)
        (or (:in-pattern entry) (:port-pattern entry))
        (:port-pattern entry)))))

(defn resolve-output-port
  "Resolve device alias `alias` to a MIDI output port index.

  Calls sidecar/find-midi-port with the device's :port-pattern.
  Returns the integer port index, or throws ex-info if the alias is unknown
  or no matching port is found."
  [alias]
  (let [pat (pattern-for alias :output)]
    (when-not pat
      (throw (ex-info (str "Unknown topology device: " alias)
                      {:alias alias :known (device-ids)})))
    (sidecar/find-midi-port pat :direction :output)))

(defn resolve-input-port
  "Resolve device alias `alias` to a MIDI input port index.

  Uses :in-pattern if defined, otherwise falls back to :port-pattern.
  Returns the integer port index, or throws ex-info if no match is found."
  [alias]
  (let [pat (pattern-for alias :input)]
    (when-not pat
      (throw (ex-info (str "Unknown topology device: " alias)
                      {:alias alias :known (device-ids)})))
    (sidecar/find-midi-port pat :direction :input)))

;; ---------------------------------------------------------------------------
;; Sidecar convenience
;; ---------------------------------------------------------------------------

(defn start-sidecar!
  "Start the cljseq sidecar with MIDI output routed to `output-alias`.

  Resolves the device alias to a port index via :port-pattern matching,
  then calls sidecar/start-sidecar!.

  Options:
    :input    -- alias keyword for MIDI input monitoring (optional)
    :binary   -- path to sidecar binary (passed through to sidecar/start-sidecar!)
    :port     -- TCP port for IPC (passed through)

  Requires a topology to be loaded first (see load-topology!).

  Examples:
    (topology/start-sidecar! :hydrasynth)
    (topology/start-sidecar! :hydrasynth :input :linnstrument)
    (topology/start-sidecar! :iac)"
  [output-alias & {:keys [input binary port]}]
  (when-not (loaded?)
    (throw (ex-info "No topology loaded -- call (topology/load-topology!) first" {})))
  (let [out-port (resolve-output-port output-alias)
        in-port  (when input (resolve-input-port input))]
    (apply sidecar/start-sidecar!
           (cond-> [:midi-port out-port]
             in-port (conj :midi-in-port in-port)
             binary  (conj :binary binary)
             port    (conj :port port)))))

;; ---------------------------------------------------------------------------
;; Topology summary
;; ---------------------------------------------------------------------------

(defn print-topology
  "Print a human-readable summary of the loaded topology."
  []
  (if-let [t @topology]
    (do
      (println (format "Topology: %s (v%s)"
                       (name (get t :topology/id :unknown))
                       (get t :topology/version "?")))
      (println "Devices:")
      (doseq [[alias entry] (sort-by key (:devices t))]
        (println (format "  %-16s  pattern=%-20s  ch=%-2s  role=%s"
                         (name alias)
                         (str \" (:port-pattern entry) \")
                         (get entry :midi/channel "-")
                         (name (get entry :role :unknown))))))
    (println "[topology] no topology loaded")))
