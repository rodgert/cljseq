; SPDX-License-Identifier: EPL-2.0
(ns cljseq.osc
  "OSC (Open Sound Control) UDP receiver — §17 Phase 2.

  Listens on a UDP port for OSC messages and routes them to the ctrl tree
  using the same addressing scheme as the HTTP control-plane server.

  ## Routes

    /ping                       — log receipt (no-op)
    /bpm  <f-or-i>              — set master BPM
    /ctrl/<p0>/<p1>/...  <val>  — write value to ctrl path [p0 p1 ...]

  ## Path encoding

  Each URL path segment is decoded to a keyword, matching the HTTP API.
  Namespaced keywords use URL-encoded slashes in the segment:
    /ctrl/filter%2Fcutoff  →  [:filter/cutoff]
    /ctrl/loops/bass       →  [:loops :bass]

  ## Supported OSC argument types

    f — float32 (standard OSC single-precision float)
    i — int32
    s — OSC string (null-terminated, 4-byte aligned)
    d — float64 (double)

  Blob (b) arguments are silently skipped.

  ## Start / stop

    (osc/start-osc-server! :port 57120)
    (osc/stop-osc-server!)"
  (:require [clojure.string :as str]
            [cljseq.ctrl    :as ctrl]
            [cljseq.core    :as core])
  (:import  [java.net DatagramSocket DatagramPacket InetSocketAddress URLDecoder]
            [java.nio ByteBuffer ByteOrder]
            [java.nio.charset StandardCharsets]
            [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Private state
;; ---------------------------------------------------------------------------

(defonce ^:private server-atom (atom nil))

(declare stop-osc-server!)

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
  "Route a decoded OSC message to the appropriate handler."
  [{:keys [address args]}]
  (cond
    (= "/ping" address)
    (println "[osc] ping")

    (= "/bpm" address)
    (when-let [bpm (first args)]
      (core/set-bpm! (double bpm)))

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
            (let [data (Arrays/copyOfRange buf 0 (.getLength pkt))]
              (try (dispatch (decode-message data))
                   (catch Exception e
                     (binding [*out* *err*]
                       (println "[osc] decode error:" (.getMessage e)))))))
          (catch java.net.SocketException _
            ;; socket closed by stop-osc-server! — exit cleanly
            (reset! running? false)))
        (when @running? (recur))))))

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
