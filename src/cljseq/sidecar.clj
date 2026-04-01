; SPDX-License-Identifier: EPL-2.0
(ns cljseq.sidecar
  "cljseq sidecar IPC client.

  Manages the lifecycle of the cljseq-sidecar process and its TCP connection.
  The sidecar handles real-time MIDI output; this namespace provides:

    - Process spawning and crash monitoring
    - Length-prefixed little-endian binary message framing
    - One function per IPC message type

  Wire format (little-endian, all fields):
    [uint32 payload_len][uint8 msg_type][uint8 reserved ×3][uint8[] payload]

  NoteOn/NoteOff/CC payload (12 bytes):
    [int64 time_ns][uint8 channel][uint8 note_or_cc][uint8 vel_or_val][uint8 reserved]

  time_ns is epoch-nanoseconds (System/currentTimeMillis × 1,000,000), matching
  std::chrono::system_clock on the C++ side.

  Key design decisions: Q16 (direct JVM-to-process IPC, Option A),
  Q51 (native dependency policy), §3.5 (process topology), §29."
  (:require [clojure.java.io     :as io]
            [clojure.string      :as str])
  (:import  [java.net        Socket ServerSocket]
            [java.io         InputStream OutputStream BufferedReader InputStreamReader]
            [java.lang       ProcessBuilder ProcessBuilder$Redirect]
            [java.nio        ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------------
;; Process / connection state
;; ---------------------------------------------------------------------------

(def ^:private sidecar-state
  (atom {:process       nil    ; java.lang.Process
         :socket        nil    ; java.net.Socket
         :out           nil    ; java.io.OutputStream (synchronized on send)
         :in            nil    ; java.io.InputStream (reader thread)
         :port          nil    ; long — TCP port the sidecar listens on
         :running?      false
         :stderr-lines  []
         :push-handlers {}}))  ; lines captured from sidecar stderr

(defn connected?
  "Return true if a sidecar connection is active."
  []
  (boolean (:out @sidecar-state)))

(defn- capture-stderr!
  "Start a daemon thread that reads proc's stderr, prints each line to *err*,
   and appends it to :stderr-lines in sidecar-state."
  [^Process proc]
  (let [t (Thread.
            (fn []
              (try
                (let [rdr (BufferedReader. (InputStreamReader. (.getErrorStream proc)))]
                  (loop []
                    (when-let [line (.readLine rdr)]
                      (binding [*out* *err*]
                        (println line))
                      (swap! sidecar-state update :stderr-lines conj line)
                      (recur))))
                (catch Exception _))))]
    (.setDaemon t true)
    (.start t)))

(defn await-dispatch
  "Poll sidecar stderr lines until `pred` matches a line or `timeout-ms` elapses.
  Returns the matching line string, or nil on timeout.

  Useful in tests to verify the scheduler dispatched an event without relying on
  a MIDI loopback device.

  Example — wait for a NoteOn of note 60:
    (sidecar/await-dispatch
      #(and (clojure.string/includes? % \"type=0x01\")
            (clojure.string/includes? % \"note=60 \"))
      2000)"
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (if-let [line (first (filter pred (:stderr-lines @sidecar-state)))]
        line
        (when (< (System/currentTimeMillis) deadline)
          (Thread/sleep 10)
          (recur))))))

;; ---------------------------------------------------------------------------
;; Message type constants (must match C++ MsgType enum in ipc.cpp)
;; ---------------------------------------------------------------------------

(def ^:private MSG-NOTE-ON      (unchecked-byte 0x01))
(def ^:private MSG-NOTE-OFF     (unchecked-byte 0x02))
(def ^:private MSG-CC           (unchecked-byte 0x03))
(def ^:private MSG-LINK-ENABLE  (unchecked-byte 0x10))
(def ^:private MSG-LINK-DISABLE (unchecked-byte 0x11))
(def ^:private MSG-LINK-SET-BPM (unchecked-byte 0x12))
(def ^:private MSG-LINK-STATE   (unchecked-byte 0x80))
(def ^:private MSG-PING         (unchecked-byte 0xF0))
(def ^:private MSG-SHUTDOWN     (unchecked-byte 0xFF))

;; ---------------------------------------------------------------------------
;; Frame serialization
;; ---------------------------------------------------------------------------

(defn- note-payload
  "Serialize a NoteOn/NoteOff payload (12 bytes, little-endian)."
  ^bytes [^long time-ns ^long channel ^long note ^long velocity]
  (let [buf (ByteBuffer/allocate 12)]
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (.putLong buf time-ns)
    (.put buf (unchecked-byte channel))
    (.put buf (unchecked-byte note))
    (.put buf (unchecked-byte velocity))
    (.put buf (byte 0))
    (.array buf)))

(defn- cc-payload
  "Serialize a CC payload (12 bytes, little-endian)."
  ^bytes [^long time-ns ^long channel ^long cc-num ^long value]
  (let [buf (ByteBuffer/allocate 12)]
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (.putLong buf time-ns)
    (.put buf (unchecked-byte channel))
    (.put buf (unchecked-byte cc-num))
    (.put buf (unchecked-byte value))
    (.put buf (byte 0))
    (.array buf)))

(defn- make-frame
  "Build a complete IPC frame: [uint32 len][uint8 type][3 reserved][payload]."
  ^bytes [msg-type ^bytes payload]
  (let [plen (alength payload)
        buf  (ByteBuffer/allocate (+ 8 plen))]
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (.putInt buf plen)
    (.put    buf msg-type)
    (.put    buf (byte 0))
    (.put    buf (byte 0))
    (.put    buf (byte 0))
    (.put    buf payload)
    (.array buf)))

(defn- send-frame!
  "Write a frame to the sidecar socket. Synchronized on the output stream."
  [^bytes frame]
  (when-let [^OutputStream out (:out @sidecar-state)]
    (locking out
      (.write out frame)
      (.flush out))))

;; ---------------------------------------------------------------------------
;; Public send API
;; ---------------------------------------------------------------------------

(defn send-note-on!
  "Send a NoteOn event to the sidecar for scheduled dispatch.

  time-ns  — epoch-nanoseconds (use clock/beat->epoch-ns for beat-based timing)
  channel  — MIDI channel 1–16
  note     — MIDI note number 0–127
  velocity — 0–127"
  [time-ns channel note velocity]
  (send-frame! (make-frame MSG-NOTE-ON (note-payload time-ns channel note velocity))))

(defn send-note-off!
  "Send a NoteOff event to the sidecar. Velocity is always 0."
  [time-ns channel note]
  (send-frame! (make-frame MSG-NOTE-OFF (note-payload time-ns channel note 0))))

(defn send-cc!
  "Send a Control Change event to the sidecar."
  [time-ns channel cc-num value]
  (send-frame! (make-frame MSG-CC (cc-payload time-ns channel cc-num value))))

(defn send-ping!
  "Send a keepalive Ping. No payload."
  []
  (send-frame! (make-frame MSG-PING (byte-array 0))))

(defn send-shutdown!
  "Send a Shutdown message. The sidecar process exits cleanly after receiving it."
  []
  (send-frame! (make-frame MSG-SHUTDOWN (byte-array 0))))

(defn send-link-enable!
  "Ask the sidecar to join a Link session.
  quantum — phrase length in beats (default 4). The sidecar responds with an
  immediate 0x80 LinkState push carrying the initial session tempo."
  [& {:keys [quantum] :or {quantum 4}}]
  (let [payload (byte-array [(unchecked-byte (int quantum))])]
    (send-frame! (make-frame MSG-LINK-ENABLE payload))))

(defn send-link-disable!
  "Ask the sidecar to leave the Link session."
  []
  (send-frame! (make-frame MSG-LINK-DISABLE (byte-array 0))))

(defn send-link-set-bpm!
  "Propose a tempo change to the Link session.
  bpm — new BPM as a double."
  [^double bpm]
  (let [buf (ByteBuffer/allocate 8)]
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (.putDouble buf bpm)
    (send-frame! (make-frame MSG-LINK-SET-BPM (.array buf)))))

;; ---------------------------------------------------------------------------
;; Inbound frame reader (sidecar → JVM)
;; ---------------------------------------------------------------------------

(defn register-push-handler!
  "Register a handler for inbound push frames from the sidecar.
  `msg-type-byte` — the raw uint8 message type (e.g. 0x80 for LinkState).
  `handler`       — (fn [^bytes payload]) called on the reader thread.

  Registrations survive stop!/start! cycles. Called by cljseq.link at load time."
  [msg-type-byte handler]
  (swap! sidecar-state assoc-in [:push-handlers msg-type-byte] handler))

(defn- read-fully!
  "Read exactly n bytes from in into buf starting at offset. Returns false on EOF."
  [^InputStream in ^bytes buf ^long offset ^long n]
  (loop [remaining n pos offset]
    (if (zero? remaining)
      true
      (let [read (.read in buf (int pos) (int remaining))]
        (if (neg? read)
          false
          (recur (- remaining read) (+ pos read)))))))

(defn- start-reader!
  "Start a daemon thread that reads inbound IPC frames from the sidecar socket
  and dispatches them to registered push handlers.

  The reader runs until the socket is closed (stop-sidecar! closes it)."
  [^InputStream in]
  (let [t (Thread.
            (fn []
              (let [header (byte-array 8)]
                (try
                  (loop []
                    (when (read-fully! in header 0 8)
                      (let [buf      (doto (ByteBuffer/wrap header)
                                       (.order ByteOrder/LITTLE_ENDIAN))
                            plen     (.getInt buf)
                            msg-type (bit-and (.get buf) 0xFF)
                            payload  (byte-array plen)]
                        (when (or (zero? plen) (read-fully! in payload 0 plen))
                          (when-let [h (get (:push-handlers @sidecar-state) msg-type)]
                            (try (h payload)
                                 (catch Exception e
                                   (binding [*out* *err*]
                                     (println "[cljseq.sidecar] push handler error:" e))))))
                        (recur))))
                  (catch Exception _)))))]
    (.setDaemon t true)
    (.setName t "cljseq-sidecar-reader")
    (.start t)))

;; ---------------------------------------------------------------------------
;; Process management
;; ---------------------------------------------------------------------------

(defn- find-free-port
  "Ask the OS for an available TCP port."
  ^long []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- find-binary
  "Locate the cljseq-sidecar binary in the build tree or current directory."
  []
  (let [candidates ["build/cpp/cljseq-sidecar/cljseq-sidecar"
                    "./cljseq-sidecar"]]
    (or (first (filter #(.exists (io/file %)) candidates))
        (throw (ex-info "cljseq-sidecar binary not found; build the C++ sidecar first"
                        {:searched candidates})))))

(defn- connect-with-retry!
  "Open a TCP connection to the sidecar, retrying with back-off.
  The sidecar needs a moment to bind and listen after process start."
  [^long port]
  (loop [attempt 0 delay-ms 50]
    (when (> attempt 10)
      (throw (ex-info "Could not connect to cljseq-sidecar after 10 attempts"
                      {:port port})))
    (if-let [result (try
                      (let [sock (Socket. "127.0.0.1" (int port))]
                        {:socket sock
                         :out (.getOutputStream sock)
                         :in  (.getInputStream  sock)})
                      (catch java.net.ConnectException _ nil))]
      (do (swap! sidecar-state merge result)
          (start-reader! (:in @sidecar-state)))
      (do (Thread/sleep delay-ms)
          (recur (inc attempt) (min 500 (* 2 delay-ms)))))))

(defn- monitor-process!
  "Watch the sidecar process in a future and log unexpected exits."
  [^Process proc ^long port]
  (future
    (.waitFor proc)
    (when (:running? @sidecar-state)
      (binding [*out* *err*]
        (println (str "[cljseq.sidecar] process on port " port
                      " exited (code " (.exitValue proc) ")"))))))

(defn start-sidecar!
  "Spawn the cljseq-sidecar binary and open the TCP IPC connection.

  Options:
    :binary — explicit path to the sidecar binary (default: searches build/)
    :port   — TCP port (default: OS-assigned free port)

  Returns the port number the sidecar is listening on.

  Example:
    (sidecar/start-sidecar!)
    (sidecar/start-sidecar! :binary \"/usr/local/bin/cljseq-sidecar\" :port 7777)"
  [& {:keys [binary port]}]
  (when (:running? @sidecar-state)
    (throw (ex-info "Sidecar already running; call stop-sidecar! first" {})))
  (let [port   (long (or port (find-free-port)))
        binary (or binary (find-binary))]
    (let [pb   (doto (ProcessBuilder. [binary "--port" (str port)])
                 ;; Inherit stdin and stdout; pipe stderr for capture+tee
                 (.redirectInput  ProcessBuilder$Redirect/INHERIT)
                 (.redirectOutput ProcessBuilder$Redirect/INHERIT))
          proc (.start pb)]
      (swap! sidecar-state assoc :process proc :port port :running? true
                                 :stderr-lines [])
      (capture-stderr! proc)
      (connect-with-retry! port)
      (monitor-process! proc port)
      (println (str "[cljseq.sidecar] connected to " binary " on port " port))
      port)))

(defn stop-sidecar!
  "Send Shutdown to the sidecar, close the TCP connection, and destroy the process."
  []
  (try (send-shutdown!) (catch Exception _))
  (Thread/sleep 200)
  (when-let [^Socket sock (:socket @sidecar-state)]
    (try (.close sock) (catch Exception _)))
  (when-let [^Process proc (:process @sidecar-state)]
    (.destroyForcibly proc))
  (swap! sidecar-state assoc :running? false :socket nil :out nil :in nil :process nil :port nil)
  (println "[cljseq.sidecar] stopped")
  nil)
