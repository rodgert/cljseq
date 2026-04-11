; SPDX-License-Identifier: EPL-2.0
(ns cljseq.osc
  "OSC (Open Sound Control) UDP receiver + push-subscribe — §17 Phase 2/3.

  Listens on a UDP port for OSC messages and routes them to the ctrl tree
  using the same addressing scheme as the HTTP control-plane server.

  ## Inbound routes (Phase 2)

    /ping                       — log receipt (no-op)
    /bpm  <f-or-i>              — set master BPM
    /ctrl/<p0>/<p1>/...  <val>  — write value to ctrl path [p0 p1 ...]

  ## Push-subscribe routes (Phase 3)

    /sub   <ctrl-addr> <callback-host> <callback-port>
      — subscribe to changes at ctrl-addr; server pushes to callback on every write

    /unsub <ctrl-addr> <callback-host> <callback-port>
      — cancel a specific subscription

    /tree  <ctrl-addr>
      — pull: server pushes the current value at ctrl-addr back to the sender

  Push messages use the same address as the subscribed ctrl-addr, e.g.:
    /ctrl/filter%2Fcutoff 0.75     (float32)
    /ctrl/loops/bass/vel 88        (int32)
    /ctrl/some/key \"value\"         (string)
    /ctrl/opaque/data \"(pr-str v)\" (all other types serialized via pr-str)

  ## Path encoding

  Each URL path segment is decoded to a keyword, matching the HTTP API.
  Namespaced keywords use URL-encoded slashes in the segment:
    /ctrl/filter%2Fcutoff  →  [:filter/cutoff]
    /ctrl/loops/bass       →  [:loops :bass]

  ## Supported OSC argument types (inbound)

    f — float32 (standard OSC single-precision float)
    i — int32
    s — OSC string (null-terminated, 4-byte aligned)
    d — float64 (double)

  Blob (b) arguments are silently skipped.

  ## Start / stop

    (osc/start-osc-server! :port 57120)
    (osc/stop-osc-server!)

  ## Push-subscribe quick start

    ;; Subscribe a peer at 192.168.1.42:9000 to filter cutoff changes
    (osc/subscribe! [:filter/cutoff] \"192.168.1.42\" 9000)

    ;; Peer receives OSC push: /ctrl/filter%2Fcutoff 0.75 whenever cutoff changes

    ;; Pull current value (responds to sender's address via /tree roundtrip)
    ;; — wire: client sends /tree /ctrl/filter%2Fcutoff to this server's port

    ;; Cancel subscription
    (osc/unsubscribe! [:filter/cutoff] \"192.168.1.42\" 9000)"
  (:require [clojure.string :as str]
            [cljseq.ctrl    :as ctrl]
            [cljseq.core    :as core])
  (:import  [java.net DatagramSocket DatagramPacket InetSocketAddress URLDecoder URLEncoder]
            [java.nio ByteBuffer ByteOrder]
            [java.nio.charset StandardCharsets]
            [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Private state
;; ---------------------------------------------------------------------------

(defonce ^:private server-atom (atom nil))

;; Subscriber registry — {ctrl-path #{:host :port}}
(defonce ^:private subscriber-registry (atom {}))

(declare stop-osc-server! osc-send!)

;; ---------------------------------------------------------------------------
;; Push-subscribe: injectable send function for testing
;; ---------------------------------------------------------------------------

(def ^:dynamic *push-fn*
  "Function used to deliver push messages: `(fn [host port address & args])`.
  Defaults to `osc-send!`. Rebind in tests to capture outbound push calls."
  nil)   ; forward-declared; set after osc-send! is defined

;; ---------------------------------------------------------------------------
;; Path ↔ OSC address helpers
;; ---------------------------------------------------------------------------

(defn ctrl-path->osc-address
  "Convert a ctrl-tree path vector to an OSC address string.

  Inverse of `parse-ctrl-path`. Namespaced keywords are URL-encoded:
    [:filter/cutoff]  →  \"/ctrl/filter%2Fcutoff\"
    [:loops :bass]    →  \"/ctrl/loops/bass\"

  Example:
    (ctrl-path->osc-address [:filter/cutoff])   ;=> \"/ctrl/filter%2Fcutoff\"
    (ctrl-path->osc-address [:loops :bass :vel]) ;=> \"/ctrl/loops/bass/vel\""
  [path]
  (str "/ctrl/"
       (str/join "/"
                 (map (fn [k]
                        (URLEncoder/encode
                          (str (when (namespace k) (str (namespace k) "/")) (name k))
                          "UTF-8"))
                      path))))

;; ---------------------------------------------------------------------------
;; Push watcher
;; ---------------------------------------------------------------------------

(defn- coerce-for-osc
  "Coerce `value` to a type that `osc-send!` accepts (Long, Double, or String)."
  [value]
  (cond
    (nil? value)      "nil"
    (integer? value)  (long value)
    (number? value)   (double value)
    (string? value)   value
    :else             (pr-str value)))

(defn- sub-watcher-key
  "The ctrl watch-key used for a subscribed path."
  [path]
  [::sub path])

(defn- make-push-watcher
  "Return a ctrl watcher fn that pushes the value to all registered subscribers
  for `path` using `*push-fn*` (or `osc-send!` when not rebound)."
  [path]
  (fn [_ value]
    (when-let [subs (seq (get @subscriber-registry path))]
      (let [address (ctrl-path->osc-address path)
            coerced (coerce-for-osc value)
            send-fn (or *push-fn* osc-send!)]
        (doseq [{:keys [host port]} subs]
          (try
            (send-fn host port address coerced)
            (catch Exception e
              (binding [*out* *err*]
                (println "[osc/pub] push error →" host ":" port ":" (.getMessage e))))))))))

(defn- ensure-watcher!
  "Install a ctrl push watcher on `path` if not already present."
  [path]
  (ctrl/watch! path (sub-watcher-key path) (make-push-watcher path)))

(defn- remove-watcher-if-empty!
  "Remove the ctrl push watcher for `path` if there are no more subscribers."
  [path]
  (when (empty? (get @subscriber-registry path))
    (ctrl/unwatch! path (sub-watcher-key path))))

;; ---------------------------------------------------------------------------
;; Public push-subscribe API
;; ---------------------------------------------------------------------------

(defn subscribe!
  "Subscribe `host`:`port` to receive OSC push messages whenever the ctrl
  tree value at `path` changes.

  The push message is sent as an OSC datagram to `host`:`port` with the
  OSC address `(ctrl-path->osc-address path)`. The value is coerced:
    numbers → float64 (f)
    integers → int32 (i) [see `coerce-for-osc`]
    strings → string (s)
    other   → pr-str string (s)

  Idempotent: re-subscribing the same {path host port} is a no-op.

  Example:
    (osc/subscribe! [:filter/cutoff] \"192.168.1.42\" 9000)"
  [path host port]
  (swap! subscriber-registry update path (fnil conj #{}) {:host host :port port})
  (ensure-watcher! path)
  nil)

(defn unsubscribe!
  "Remove `host`:`port` from push subscribers for `path`.
  Removes the ctrl watcher when the last subscriber leaves.

  Example:
    (osc/unsubscribe! [:filter/cutoff] \"192.168.1.42\" 9000)"
  [path host port]
  (swap! subscriber-registry update path disj {:host host :port port})
  (remove-watcher-if-empty! path)
  nil)

(defn unsubscribe-all!
  "Remove all subscribers for `path` and the associated ctrl watcher.

  Example:
    (osc/unsubscribe-all! [:filter/cutoff])"
  [path]
  (swap! subscriber-registry dissoc path)
  (ctrl/unwatch! path (sub-watcher-key path))
  nil)

(defn subscribers
  "Return a snapshot of the subscriber registry.

  Returns a map of {ctrl-path #{:host :port}}.

  Example:
    (osc/subscribers)
    ;=> {[:filter/cutoff] #{{:host \"192.168.1.42\" :port 9000}}}"
  []
  @subscriber-registry)

;; ---------------------------------------------------------------------------
;; OSC wire format helpers
;; ---------------------------------------------------------------------------

(defn- osc-align
  "Round `n` up to the next 4-byte boundary."
  ^long [^long n]
  (bit-and (+ n 3) (bit-not 3)))

(defn- decode-string
  "Read a null-terminated, 4-byte-aligned OSC string from `buf` at its current
  position. Advances the buffer position past the padded string."
  [^ByteBuffer buf]
  (let [start (.position buf)]
    (loop []
      (when (not= 0 (.get buf))
        (recur)))
    (let [consumed (- (.position buf) start)   ; includes the null byte
          s        (String. (.array buf) start (dec consumed) "UTF-8")
          _        (.position buf (int (+ start (osc-align consumed))))]
      s)))

(defn- decode-message
  "Parse an OSC message from a `byte[]` array.
  Returns {:address <string> :args [...]} or throws on malformed input."
  [^bytes data]
  (let [buf     (doto (ByteBuffer/wrap data)
                  (.order ByteOrder/BIG_ENDIAN))
        address (decode-string buf)
        _       (when-not (str/starts-with? address "/")
                  (throw (ex-info "not an OSC address" {:address address})))
        ;; Type tag string is optional; default to no args
        type-tag (if (.hasRemaining buf)
                   (let [s (decode-string buf)]
                     (if (str/starts-with? s ",") (subs s 1) ""))
                   "")
        args     (reduce (fn [acc t]
                           (case t
                             \i (conj acc (.getInt buf))
                             \f (conj acc (.getFloat buf))
                             \d (conj acc (.getDouble buf))
                             \s (conj acc (decode-string buf))
                             ;; blob: skip length + data + padding
                             \b (let [len    (.getInt buf)
                                      padded (osc-align len)
                                      _      (.position buf
                                                        (+ (.position buf) (int padded)))]
                                  acc)
                             acc))
                         []
                         type-tag)]
    {:address address :args args}))

;; ---------------------------------------------------------------------------
;; Path parsing
;; ---------------------------------------------------------------------------

(defn- parse-ctrl-path
  "Convert an OSC address like \"/ctrl/loops/bass\" to [:loops :bass].
  Returns nil for a bare \"/ctrl\" with no segments."
  [address]
  (let [without-prefix (str/replace-first address #"^/ctrl/?" "")
        segments       (remove empty? (str/split without-prefix #"/"))]
    (when (seq segments)
      (mapv (fn [s] (keyword (URLDecoder/decode s "UTF-8")))
            segments))))

;; ---------------------------------------------------------------------------
;; Dispatcher
;; ---------------------------------------------------------------------------

(defn- dispatch
  "Route a decoded OSC message to the appropriate handler.
  `sender-host` and `sender-port` are used by /tree to push the response."
  [{:keys [address args]} sender-host sender-port]
  (cond
    (= "/ping" address)
    (println "[osc] ping")

    (= "/bpm" address)
    (when-let [bpm (first args)]
      (core/set-bpm! (double bpm)))

    (= "/sub" address)
    (let [[path-addr callback-host callback-port] args]
      (when (and path-addr callback-host callback-port)
        (when-let [path (parse-ctrl-path path-addr)]
          (subscribe! path callback-host (int callback-port)))))

    (= "/unsub" address)
    (let [[path-addr callback-host callback-port] args]
      (when (and path-addr callback-host callback-port)
        (when-let [path (parse-ctrl-path path-addr)]
          (unsubscribe! path callback-host (int callback-port)))))

    (= "/tree" address)
    (let [[path-addr] args]
      (when (and path-addr sender-host sender-port)
        (when-let [path (parse-ctrl-path path-addr)]
          (let [value   (ctrl/get path)
                coerced (coerce-for-osc value)
                send-fn (or *push-fn* osc-send!)]
            (try
              (send-fn sender-host sender-port (ctrl-path->osc-address path) coerced)
              (catch Exception e
                (binding [*out* *err*]
                  (println "[osc/tree] error:" (.getMessage e)))))))))

    (str/starts-with? address "/ctrl/")
    (let [ctrl-path (parse-ctrl-path address)
          value     (first args)]
      (when ctrl-path
        (ctrl/set! ctrl-path value)))

    :else
    (binding [*out* *err*]
      (println "[osc] unhandled address:" address))))

;; ---------------------------------------------------------------------------
;; Listener thread
;; ---------------------------------------------------------------------------

(defn- listener-loop
  "UDP receive loop — runs on a daemon thread until `running?` is false or
  the socket is closed."
  [^DatagramSocket sock running?]
  (let [buf (byte-array 65536)
        pkt (DatagramPacket. buf (alength buf))]
    (loop []
      (when @running?
        (try
          (.receive sock pkt)
          (when @running?
            (let [data        (Arrays/copyOfRange buf 0 (.getLength pkt))
                  sender-host (.getHostAddress (.getAddress pkt))
                  sender-port (.getPort pkt)]
              (try (dispatch (decode-message data) sender-host sender-port)
                   (catch Exception e
                     (binding [*out* *err*]
                       (println "[osc] decode error:" (.getMessage e)))))))
          (catch java.net.SocketException _
            ;; socket closed by stop-osc-server! — exit cleanly
            (reset! running? false)))
        (when @running? (recur))))))

;; ---------------------------------------------------------------------------
;; OSC message encoding (for osc-send!)
;; ---------------------------------------------------------------------------

(defn- osc-encode-str
  "Encode a String as a null-terminated, 4-byte-aligned OSC string."
  ^bytes [^String s]
  (let [raw    (.getBytes s "UTF-8")
        padlen (int (osc-align (inc (alength raw))))
        buf    (byte-array padlen)]
    (System/arraycopy raw 0 buf 0 (alength raw))
    buf))

(defn- arg-type-tag
  "Return the single-char OSC type tag for a Clojure value."
  [arg]
  (cond
    (or (instance? Long arg) (instance? Integer arg)) "i"
    (or (instance? Double arg) (instance? Float arg)) "f"
    (instance? String arg)                            "s"
    :else (throw (ex-info "unsupported OSC arg type"
                          {:arg arg :type (type arg)}))))

(defn- encode-osc-arg
  "Encode a single OSC argument to a byte array."
  ^bytes [arg]
  (cond
    (or (instance? Long arg) (instance? Integer arg))
    (.array (doto (ByteBuffer/allocate 4)
              (.order ByteOrder/BIG_ENDIAN)
              (.putInt (.intValue ^Number arg))))

    (or (instance? Double arg) (instance? Float arg))
    (.array (doto (ByteBuffer/allocate 4)
              (.order ByteOrder/BIG_ENDIAN)
              (.putFloat (.floatValue ^Number arg))))

    (instance? String arg)
    (osc-encode-str arg)))

(defn encode-message
  "Build a complete OSC message byte array from an address string and a seq
  of Clojure arguments.

  Supported argument types: Long/Integer → OSC int32 (i),
  Double/Float → OSC float32 (f), String → OSC string (s).

  A message with no arguments is valid (e.g. /transport_play)."
  ^bytes [^String address args]
  (let [addr-b  (osc-encode-str address)
        tags    (apply str "," (map arg-type-tag args))
        tag-b   (osc-encode-str tags)
        arg-bs  (map encode-osc-arg args)
        total   (transduce (map alength) + 0 (into [addr-b tag-b] arg-bs))
        result  (byte-array total)
        pos     (atom 0)]
    (doseq [^bytes chunk (into [addr-b tag-b] arg-bs)]
      (System/arraycopy chunk 0 result (int @pos) (alength chunk))
      (swap! pos + (alength chunk)))
    result))

;; ---------------------------------------------------------------------------
;; OSC client — send to an external target
;; ---------------------------------------------------------------------------

(defn osc-send!
  "Send an OSC message via UDP to host:port.

  Supported argument types: Long/Integer (OSC int32), Double/Float (OSC float32),
  String (OSC string). No-arg messages are valid.

  Creates a new DatagramSocket per call — suitable for low-frequency control
  messages (transport, parameter changes). Not intended for audio-rate use.

  Example:
    (osc/osc-send! \"192.168.1.42\" 3819 \"/transport_play\")
    (osc/osc-send! \"127.0.0.1\"    3819 \"/transport_record\" (int 1))
    (osc/osc-send! \"127.0.0.1\"    9000 \"/n_set\" (int 1000) \"freq\" 440.0)"
  [^String host port ^String address & args]
  (let [msg  (encode-message address (vec args))
        addr (InetSocketAddress. host (int (long port)))
        pkt  (DatagramPacket. msg (alength msg) addr)]
    (with-open [sock (DatagramSocket.)]
      (.send sock pkt))))

;; Set *push-fn* default now that osc-send! is defined.
;; The forward-declared nil is replaced with the real function.
(alter-var-root #'*push-fn* (constantly osc-send!))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start-osc-server!
  "Start the OSC UDP receiver.

  Options:
    :port — UDP port to listen on (default 57120, the standard SuperCollider port)

  Idempotent: any previously running receiver is stopped first.

  Example:
    (osc/start-osc-server! :port 57120)"
  [& {:keys [port] :or {port 57120}}]
  (when @server-atom
    (stop-osc-server!))
  (let [sock     (DatagramSocket. (int port))
        running? (atom true)
        t        (doto (Thread. #(listener-loop sock running?))
                   (.setDaemon true)
                   (.setName (str "cljseq-osc-" port))
                   (.start))]
    (reset! server-atom {:socket sock :running? running? :thread t :port port})
    (println (str "[osc] started on UDP port " port))
    nil))

(defn stop-osc-server!
  "Stop the OSC UDP receiver if running."
  []
  (when-let [{:keys [^DatagramSocket socket running?]} @server-atom]
    (reset! running? false)
    (.close socket)
    (reset! server-atom nil)
    (println "[osc] stopped"))
  nil)

(defn osc-port
  "Return the UDP port the OSC server is listening on, or nil if not running."
  []
  (:port @server-atom))

(defn osc-running?
  "Return true if the OSC server is running."
  []
  (some? @server-atom))
