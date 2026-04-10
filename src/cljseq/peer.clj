; SPDX-License-Identifier: EPL-2.0
(ns cljseq.peer
  "Topology Layer 2 — UDP multicast peer discovery + HTTP ctrl-tree mounting.

  Phase 1 scope:
    • Each node broadcasts a UDP beacon on multicast group
      239.255.43.99:7743 every 5 s so peers can discover it.
    • Listening for beacons populates the local peer registry.
    • mount-peer! starts a background poll thread that GETs two ctrl-tree
      paths from the peer's HTTP server and stores the results locally:
        GET /ctrl/ensemble/harmony-ctx → [:peers <id> :ensemble :ctx]
        GET /ctrl/spectral/state       → [:peers <id> :spectral :ctx]

  ## Quick start

    ;; Configure identity (call before start-discovery!)
    (peer/set-node-profile! {:node-id :mac-mini :role :main :http-port 7177})

    ;; Start broadcasting and listening
    (peer/start-discovery!)

    ;; Wait a few seconds, then check what is visible
    (peer/peers)   ; => {:ubuntu {...} :rpi {...}}

    ;; Mount a peer's context into the local ctrl tree
    (peer/mount-peer! :ubuntu)

    ;; Read the mounted harmony context
    (peer/peer-harmony-ctx :ubuntu)

    ;; Stop
    (peer/stop-discovery!)
    (peer/unmount-peer! :ubuntu)

  ## Serializable ImprovisationContext

  Scale and Pitch records cannot round-trip through JSON, so the harmony ear
  publishes a simplified map at [:ensemble :harmony-ctx]:

    {:harmony/root      \"D\"
     :harmony/octave    3
     :harmony/intervals [2 1 2 2 2 1 2]
     :harmony/tension   0.65
     :harmony/mode-conf 0.82
     :ensemble/density  0.4
     :ensemble/register \"mid\"}

  Use `serial->scale` to reconstruct a Scale record on the receiving side.

  ## Discovery protocol

  Beacon payload (EDN, UTF-8):
    {:node-id       :mac-mini
     :role          :main
     :http-port     7177
     :nrepl-port    7888
     :version       \"0.4.0\"
     :timestamp-ms  1712678400000}

  Sender: DatagramSocket → multicast group.
  Receiver: MulticastSocket joined to the same group.
  Peers expire 15 s after their last beacon."
  (:require [clojure.edn       :as edn]
            [clojure.data.json :as json]
            [cljseq.ctrl       :as ctrl]
            [cljseq.pitch      :as pitch-ns]
            [cljseq.scale      :as scale-ns])
  (:import [java.net DatagramSocket DatagramPacket InetAddress
                     MulticastSocket SocketTimeoutException]
           [java.net URL HttpURLConnection]
           [java.io BufferedReader InputStreamReader]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private multicast-group    "239.255.43.99")
(def ^:private multicast-port     7743)
(def ^:private beacon-interval-ms 5000)
(def ^:private peer-timeout-ms    15000)
(def ^:private poll-interval-ms   2000)
(def ^:private max-packet-bytes   4096)

;; ---------------------------------------------------------------------------
;; Node profile
;;
;; Defaults kept minimal; callers override :node-id and :role before starting.
;; ---------------------------------------------------------------------------

(defonce ^:private node-profile-atom
  (atom {:node-id    :local
         :role       :main
         :http-port  7177
         :nrepl-port 7888
         :version    "0.4.0"}))

(defn set-node-profile!
  "Merge `profile` into this node's identity.

  Call before `start-discovery!` to set a stable node-id and role.

  Keys:
    :node-id    — keyword identifier for this node (e.g. :mac-mini)
    :role       — keyword role (e.g. :main, :satellite)
    :http-port  — HTTP server port (default 7177)
    :nrepl-port — nREPL port for future remote-eval Phase 2 (default 7888)
    :version    — version string (default \"0.4.0\")

  Example:
    (peer/set-node-profile! {:node-id :mac-mini :role :main :http-port 7177})"
  [profile]
  (swap! node-profile-atom merge profile)
  nil)

(defn node-profile
  "Return this node's current identity profile map."
  []
  @node-profile-atom)

(defn node-id
  "Return this node's :node-id keyword."
  []
  (:node-id @node-profile-atom))

(declare stop-discovery!)

;; ---------------------------------------------------------------------------
;; Peer registry
;;
;; {node-id {:node-id kw :host str :http-port int :role kw :last-seen-ms long …}}
;; ---------------------------------------------------------------------------

(defonce ^:private peer-registry (atom {}))

;; ---------------------------------------------------------------------------
;; Discovery state
;;
;; {:beacon-thread Thread :listener-thread Thread}
;; ---------------------------------------------------------------------------

(defonce ^:private discovery-state (atom nil))

;; ---------------------------------------------------------------------------
;; Mount registry
;;
;; {node-id {:thread Thread}}
;; ---------------------------------------------------------------------------

(defonce ^:private mount-registry (atom {}))

;; ---------------------------------------------------------------------------
;; HTTP GET helper — injectable via dynamic binding for tests
;; ---------------------------------------------------------------------------

(defn- http-get-json*
  "HTTP GET `url-str`, returning the parsed JSON body or nil on any error."
  [url-str]
  (try
    (let [url  (URL. url-str)
          conn (cast HttpURLConnection (.openConnection url))]
      (doto conn
        (.setRequestMethod "GET")
        (.setConnectTimeout 3000)
        (.setReadTimeout    3000))
      (let [status (.getResponseCode conn)]
        (when (= 200 status)
          (with-open [r (BufferedReader.
                          (InputStreamReader.
                            (.getInputStream conn)
                            StandardCharsets/UTF_8))]
            (json/read r :key-fn identity)))))
    (catch Exception _ nil)))

(def ^:dynamic *http-get*
  "HTTP GET implementation used by poll threads.
  Rebind in tests to inject a mock: (binding [peer/*http-get* my-fn] ...)"
  http-get-json*)

;; ---------------------------------------------------------------------------
;; Serializable ImprovisationContext helpers
;; ---------------------------------------------------------------------------

(defn ctx->serial
  "Convert an ImprovisationContext map to a JSON-safe serializable form.

  Replaces the :harmony/key Scale record with primitive fields:
    :harmony/root      — string step name (\"D\")
    :harmony/octave    — integer
    :harmony/intervals — vector of semitone integers

  Drops :harmony/chord (contains Pitch records) and :harmony/pcs (pc-dist map).
  All remaining primitive fields (:harmony/tension etc.) pass through unchanged.

  Example:
    (ctx->serial {:harmony/key (scale :D 3 :dorian) :harmony/tension 0.65})
    ;; => {:harmony/root \"D\" :harmony/octave 3 :harmony/intervals [2 1 2 2 2 1 2]
    ;;     :harmony/tension 0.65}"
  [ctx]
  (let [key  (:harmony/key ctx)
        base (dissoc ctx :harmony/key :harmony/chord :harmony/pcs)]
    (cond-> base
      key
      (assoc :harmony/root      (name (:step (:root key)))
             :harmony/octave    (:octave (:root key))
             :harmony/intervals (vec (:intervals key))))))

(defn serial->scale
  "Reconstruct a Scale record from a serialized harmony context fragment.

  Expects:
    :harmony/root      — string step name (\"D\")
    :harmony/octave    — integer
    :harmony/intervals — vector of semitone integers

  Returns a Scale record, or nil if required keys are absent.

  Example:
    (serial->scale {:harmony/root \"D\" :harmony/octave 3
                    :harmony/intervals [2 1 2 2 2 1 2]})
    ;; => Scale{:root Pitch{:step :D :octave 3 ...} :intervals [2 1 2 2 2 1 2]}"
  [m]
  (let [root-str   (:harmony/root m)
        octave     (:harmony/octave m)
        intervals  (:harmony/intervals m)]
    (when (and root-str octave intervals)
      (scale-ns/make-scale
        (pitch-ns/pitch (keyword root-str) (int octave))
        (vec intervals)))))

;; ---------------------------------------------------------------------------
;; Beacon sender thread
;; ---------------------------------------------------------------------------

(defn- beacon-payload
  "Return this node's beacon as a pr-str EDN string."
  []
  (pr-str (assoc @node-profile-atom :timestamp-ms (System/currentTimeMillis))))

(defn- make-beacon-thread
  "Create (but do not start) the UDP multicast beacon sender thread."
  []
  (doto (Thread.
          (fn []
            (try
              (let [group  (InetAddress/getByName multicast-group)
                    socket (DatagramSocket.)]
                (try
                  (loop []
                    (when-not (.isInterrupted (Thread/currentThread))
                      (try
                        (let [payload (.getBytes (beacon-payload) StandardCharsets/UTF_8)
                              packet  (DatagramPacket. payload (count payload)
                                                       group multicast-port)]
                          (.send socket packet))
                        (catch Exception e
                          (binding [*out* *err*]
                            (println "[peer/beacon] send error:" (.getMessage e)))))
                      (Thread/sleep beacon-interval-ms)
                      (recur)))
                  (finally (.close socket))))
              (catch InterruptedException _ nil)
              (catch Exception e
                (binding [*out* *err*]
                  (println "[peer/beacon] thread error:" (.getMessage e))))))
          "cljseq-peer-beacon")
    (.setDaemon true)))

;; ---------------------------------------------------------------------------
;; Discovery listener thread
;; ---------------------------------------------------------------------------

(defn- expire-stale-peers!
  "Remove peers not seen within `peer-timeout-ms`."
  []
  (let [now (System/currentTimeMillis)]
    (swap! peer-registry
           (fn [r]
             (into {}
                   (filter (fn [[_ v]]
                              (< (- now (long (:last-seen-ms v 0)))
                                 peer-timeout-ms))
                           r))))))

(defn- handle-beacon!
  "Parse a received UDP beacon packet and update the peer registry."
  [^DatagramPacket packet]
  (try
    (let [data   (String. (.getData packet) 0 (.getLength packet) StandardCharsets/UTF_8)
          parsed (edn/read-string data)
          nid    (:node-id parsed)
          host   (.getHostAddress (.getAddress packet))]
      (when (and (keyword? nid) (not= nid (node-id)))
        (swap! peer-registry assoc nid
               (assoc parsed :host host :last-seen-ms (System/currentTimeMillis)))))
    (catch Exception e
      (binding [*out* *err*]
        (println "[peer/listener] bad beacon:" (.getMessage e))))))

(defn- make-listener-thread
  "Create (but do not start) the UDP multicast discovery listener thread."
  []
  (doto (Thread.
          (fn []
            (try
              (let [group  (InetAddress/getByName multicast-group)
                    socket (doto (MulticastSocket. (int multicast-port))
                             (.joinGroup group)
                             (.setSoTimeout 1000))
                    buf    (byte-array max-packet-bytes)]
                (try
                  (loop []
                    (when-not (.isInterrupted (Thread/currentThread))
                      (let [packet (DatagramPacket. buf (count buf))]
                        (try
                          (.receive socket packet)
                          (handle-beacon! packet)
                          (catch SocketTimeoutException _ nil)))
                      (expire-stale-peers!)
                      (recur)))
                  (finally
                    (try (.leaveGroup socket group) (catch Exception _ nil))
                    (.close socket))))
              (catch InterruptedException _ nil)
              (catch Exception e
                (binding [*out* *err*]
                  (println "[peer/listener] thread error:" (.getMessage e))))))
          "cljseq-peer-listener")
    (.setDaemon true)))

;; ---------------------------------------------------------------------------
;; Public discovery API
;; ---------------------------------------------------------------------------

(defn start-discovery!
  "Start the UDP multicast beacon sender and peer discovery listener.

  Any previous discovery session is stopped first (idempotent).

  Requires `set-node-profile!` to have been called with a stable :node-id
  before starting, otherwise defaults to :local which will collide on a
  multi-node network.

  Example:
    (peer/set-node-profile! {:node-id :mac-mini :http-port 7177})
    (peer/start-discovery!)"
  []
  (when @discovery-state
    (stop-discovery!))
  (let [beacon   (make-beacon-thread)
        listener (make-listener-thread)]
    (.start beacon)
    (.start listener)
    (reset! discovery-state {:beacon-thread beacon :listener-thread listener})
    (println (str "[peer] discovery started — node-id=" (node-id)
                  " group=" multicast-group ":" multicast-port)))
  nil)

(defn stop-discovery!
  "Stop the beacon and discovery listener threads."
  []
  (when-let [{:keys [^Thread beacon-thread ^Thread listener-thread]} @discovery-state]
    (.interrupt beacon-thread)
    (.interrupt listener-thread))
  (reset! discovery-state nil)
  (println "[peer] discovery stopped")
  nil)

(defn discovery-running?
  "Return true if the discovery beacon and listener are running."
  []
  (some? @discovery-state))

(defn peers
  "Return a snapshot of all currently discovered peers.

  Keys are node-id keywords; values contain :node-id, :host, :http-port,
  :role, :version, :last-seen-ms."
  []
  @peer-registry)

(defn peer-info
  "Return the info map for a specific `peer-node-id`, or nil if not discovered."
  [peer-node-id]
  (get @peer-registry peer-node-id))

;; ---------------------------------------------------------------------------
;; HTTP poll and ctrl-tree mount
;; ---------------------------------------------------------------------------

(defn- keywordize-keys
  "Recursively convert string map keys to keywords."
  [v]
  (cond
    (map? v)        (reduce-kv (fn [m k val]
                                 (assoc m (keyword k) (keywordize-keys val)))
                               {} v)
    (sequential? v) (mapv keywordize-keys v)
    :else           v))

(defn- poll-ctrl-node!
  "GET /ctrl/<path> from `base-url` and store result at `local-path` in ctrl tree.
  Returns the stored value on success, nil on miss or error."
  [base-url ctrl-path local-path]
  (when-let [resp (*http-get* (str base-url ctrl-path))]
    (when-let [v (get resp "value")]
      (let [kv (keywordize-keys v)]
        (try (ctrl/set! local-path kv) (catch Exception _ nil))
        kv))))

(defn- poll-peer!
  "Poll all known context paths from a discovered peer and mount into ctrl tree."
  [node-id host http-port]
  (let [base (str "http://" host ":" http-port)]
    (poll-ctrl-node! base "/ctrl/ensemble/harmony-ctx"
                     [:peers node-id :ensemble :ctx])
    (poll-ctrl-node! base "/ctrl/spectral/state"
                     [:peers node-id :spectral :ctx])))

(defn- make-poll-thread
  "Create (but do not start) the poll thread for `node-id`."
  [node-id host http-port]
  (doto (Thread.
          (fn []
            (try
              (loop []
                (when-not (.isInterrupted (Thread/currentThread))
                  (poll-peer! node-id host http-port)
                  (Thread/sleep poll-interval-ms)
                  (recur)))
              (catch InterruptedException _ nil)
              (catch Exception e
                (binding [*out* *err*]
                  (println "[peer/poll] error for" node-id ":" (.getMessage e))))))
          (str "cljseq-peer-poll-" (name node-id)))
    (.setDaemon true)))

;; ---------------------------------------------------------------------------
;; Public mount API
;; ---------------------------------------------------------------------------

(defn mount-peer!
  "Start polling a peer's harmony and spectral context into the local ctrl tree.

  `peer-node-id` must be a currently discovered peer (see `peers`).
  The peer must already be in the peer registry (i.e. its beacon was seen).

  Polled data is stored at:
    [:peers <peer-node-id> :ensemble :ctx] — serializable harmony context
    [:peers <peer-node-id> :spectral :ctx] — spectral state

  Calling mount-peer! on an already-mounted peer replaces the poll thread.

  Example:
    (peer/mount-peer! :ubuntu)
    (peer/peer-harmony-ctx :ubuntu) ; => {:harmony/root \"D\" ...}"
  [peer-node-id]
  (let [{:keys [host http-port]} (peer-info peer-node-id)]
    (when-not (and host http-port)
      (throw (ex-info (str "mount-peer!: peer " peer-node-id
                           " not in registry; call (peers) to check discovery")
                      {:peer peer-node-id :registry @peer-registry})))
    ;; Stop any existing poll thread for this peer
    (when-let [{:keys [^Thread thread]} (get @mount-registry peer-node-id)]
      (.interrupt thread))
    (let [t (make-poll-thread peer-node-id host http-port)]
      (.start t)
      (swap! mount-registry assoc peer-node-id {:thread t}))
    nil))

(defn unmount-peer!
  "Stop polling a peer and clear its data from the ctrl tree.

  No-op if the peer is not currently mounted.

  Example:
    (peer/unmount-peer! :ubuntu)"
  [peer-node-id]
  (when-let [{:keys [^Thread thread]} (get @mount-registry peer-node-id)]
    (.interrupt thread))
  (swap! mount-registry dissoc peer-node-id)
  (try (ctrl/set! [:peers peer-node-id] nil) (catch Exception _ nil))
  nil)

(defn mounted-peers
  "Return a set of peer node-id keywords currently being polled."
  []
  (set (keys @mount-registry)))

(defn peer-harmony-ctx
  "Return the most recently polled harmony context for `peer-node-id`, or nil.

  The returned map uses serialized form (string :harmony/root, integer
  :harmony/octave, etc.). Use `serial->scale` to reconstruct a Scale record.

  Example:
    (peer/peer-harmony-ctx :ubuntu)
    ;; => {:harmony/root \"D\" :harmony/octave 3 :harmony/tension 0.65 ...}

    (peer/serial->scale (peer/peer-harmony-ctx :ubuntu))
    ;; => Scale{:root D3 :intervals [2 1 2 2 2 1 2]}"
  [peer-node-id]
  (try (ctrl/get [:peers peer-node-id :ensemble :ctx]) (catch Exception _ nil)))

(defn peer-spectral-ctx
  "Return the most recently polled spectral state for `peer-node-id`, or nil.

  Example:
    (peer/peer-spectral-ctx :ubuntu)
    ;; => {:spectral/density 0.58 :spectral/centroid 0.52 :spectral/blur 0.0}"
  [peer-node-id]
  (try (ctrl/get [:peers peer-node-id :spectral :ctx]) (catch Exception _ nil)))
