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

  SysEx payload: raw SysEx bytes including F0 and F7 delimiters.

  time_ns is epoch-nanoseconds (System/currentTimeMillis × 1,000,000), matching
  std::chrono::system_clock on the C++ side.

  Key design decisions: Q16 (direct JVM-to-process IPC, Option A),
  Q51 (native dependency policy), §3.5 (process topology), §29."
  (:require [clojure.java.io     :as io]
            [clojure.string      :as str]
            [cljseq.scala        :as scala])
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
(def ^:private MSG-PITCH-BEND   (unchecked-byte 0x04))  ; per-note MPE pitch bend
(def ^:private MSG-CHAN-PRESSURE (unchecked-byte 0x05)) ; per-channel pressure (MPE)
(def ^:private MSG-SYSEX        (unchecked-byte 0x06))  ; raw SysEx blob (F0 ... F7)
(def ^:private MSG-LINK-ENABLE  (unchecked-byte 0x10))
(def ^:private MSG-LINK-DISABLE (unchecked-byte 0x11))
(def ^:private MSG-LINK-SET-BPM (unchecked-byte 0x12))
(def ^:private MSG-MIDI-IN      (unchecked-byte 0x20))  ; sidecar→JVM: MIDI input received
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

(defn- pitch-bend-payload
  "Serialize a MIDI pitch bend payload (12 bytes, little-endian).
  value-14bit — 0–16383; center = 8192; lsb = low 7 bits, msb = high 7 bits."
  ^bytes [^long time-ns ^long channel ^long value-14bit]
  (let [buf (ByteBuffer/allocate 12)
        lsb (bit-and value-14bit 0x7F)
        msb (bit-and (bit-shift-right value-14bit 7) 0x7F)]
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (.putLong buf time-ns)
    (.put buf (unchecked-byte channel))
    (.put buf (unchecked-byte lsb))
    (.put buf (unchecked-byte msb))
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

(defn send-pitch-bend!
  "Send a MIDI pitch bend event (0xEn) to the sidecar.

  time-ns      — epoch-nanoseconds
  channel      — MIDI channel 1–16
  value-14bit  — 0–16383; center (no bend) = 8192

  Wire payload: [int64 time-ns][uint8 channel][uint8 lsb][uint8 msb][uint8 reserved]
  The C++ sidecar encodes this as a standard MIDI pitch-bend message (0xEn lsb msb)."
  [time-ns channel value-14bit]
  (send-frame! (make-frame MSG-PITCH-BEND
                           (pitch-bend-payload time-ns channel value-14bit))))

(defn send-channel-pressure!
  "Send a MIDI channel pressure (aftertouch) event (0xDn) to the sidecar.

  time-ns  — epoch-nanoseconds
  channel  — MIDI channel 1–16
  pressure — 0–127

  Reuses the CC payload layout (same byte structure, different message type)."
  [time-ns channel pressure]
  (send-frame! (make-frame MSG-CHAN-PRESSURE (cc-payload time-ns channel 0 pressure))))

(defn send-sysex!
  "Send a raw SysEx message to the sidecar for immediate dispatch.

  `data` must be a byte array that includes the leading 0xF0 status byte
  and the trailing 0xF7 end-of-exclusive byte.

  Throws if data is nil, fewer than 2 bytes, or if the first/last byte are
  not 0xF0/0xF7.

  Example (reset all controllers on ch 1):
    (sidecar/send-sysex! (byte-array [0xF0 0x7E 0x7F 0x09 0x01 0xF7]))"
  [^bytes data]
  (when (or (nil? data) (< (alength data) 2)
            (not= (bit-and (aget data 0) 0xFF) 0xF0)
            (not= (bit-and (aget data (dec (alength data))) 0xFF) 0xF7))
    (throw (ex-info "send-sysex!: data must be a byte[] starting with F0 and ending with F7"
                    {:len (when data (alength data))})))
  (send-frame! (make-frame MSG-SYSEX data)))

(defn send-mts!
  "Retune the connected synthesiser using MIDI Tuning Standard (MTS) Bulk Dump.

  Generates a 408-byte Non-Realtime Universal SysEx (F0 7E 7F 08 01 ...) from
  `ms` (a cljseq.scala/MicrotonalScale) and sends it via the sidecar.

  `kbm` — optional cljseq.scala/KeyboardMap; when nil an identity map is used
          (MIDI key 60 = middle-note, linear 12-TET key span).

  Hydrasynth Explorer/Desktop/Deluxe all accept MTS Bulk Dump. After receiving
  this message the synth retunes all 128 keys for the life of the patch.

  Example:
    (def ms (scala/load-scl \"31edo.scl\"))
    (sidecar/send-mts! ms)

    (def kbm (scala/load-kbm \"whitekeys.kbm\"))
    (sidecar/send-mts! ms kbm)"
  ([ms]      (send-mts! ms nil))
  ([ms kbm]  (send-sysex! (scala/scale->mts-bytes ms kbm))))

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
;; MIDI input monitor — inbound 0x20 MidiIn frames
;; ---------------------------------------------------------------------------

(def midi-in-messages
  "Atom holding a vector of all MIDI messages received from the sidecar's MIDI
  input port.  Each entry is a map:
    {:time-ns <epoch-ns> :status <uint8> :b1 <uint8> :b2 <uint8>}

  Use `await-midi-message` in tests, or deref directly for inspection.
  Reset with (reset! sidecar/midi-in-messages [])."
  (atom []))

(defn- decode-midi-in-payload
  "Decode a 12-byte MidiIn push payload into a map."
  [^bytes payload]
  (let [buf (doto (ByteBuffer/wrap payload)
              (.order ByteOrder/LITTLE_ENDIAN))]
    {:time-ns (.getLong buf)
     :status  (bit-and (.get buf) 0xFF)
     :b1      (bit-and (.get buf) 0xFF)
     :b2      (bit-and (.get buf) 0xFF)}))

;; Register the push handler for 0x20 MidiIn at load time.
;; cljseq.link registers 0x80 the same way (see cljseq.link/start-link!).
(register-push-handler!
  0x20
  (fn [^bytes payload]
    (when (>= (alength payload) 11)
      (let [msg (decode-midi-in-payload payload)]
        (swap! midi-in-messages conj msg)))))

(defn await-midi-message
  "Block until a MIDI input message matching `pred` arrives (or timeout).

  `pred`       — (fn [msg]) where msg is {:time-ns N :status N :b1 N :b2 N}
  `timeout-ms` — max wait in milliseconds (default 2000)

  Returns the matching message map, or nil on timeout.

  Example — wait for any NoteOn on any channel:
    (sidecar/await-midi-message
      #(= 0x90 (bit-and (:status %) 0xF0))
      3000)"
  ([pred] (await-midi-message pred 2000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
     (loop [seen 0]
       (let [msgs @midi-in-messages
             n    (count msgs)]
         (if-let [found (first (filter pred (subvec msgs seen)))]
           found
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 10)
             (recur n))))))))

;; ---------------------------------------------------------------------------
;; Process management helpers (must precede port discovery)
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

;; ---------------------------------------------------------------------------
;; MIDI port discovery
;; ---------------------------------------------------------------------------

(defn list-midi-ports
  "Return all available MIDI ports enumerated by the sidecar binary.

  Returns a map with :output and :input keys, each a vector of
  {:index N :name \"<port name>\"} maps.

  Example:
    (sidecar/list-midi-ports)
    ;; => {:output [{:index 0 :name \"IAC Driver Bus 1\"}
    ;;               {:index 1 :name \"Hydrasynth\"}]
    ;;      :input  [{:index 0 :name \"IAC Driver Bus 1\"}
    ;;               {:index 1 :name \"Hydrasynth\"}]}"
  []
  (let [bin  (find-binary)
        proc (.start (ProcessBuilder. ^java.util.List [bin "--list-ports"]))
        out  (slurp (java.io.InputStreamReader. (.getInputStream proc)))
        _    (.waitFor proc)
        lines  (str/split-lines (str/trim out))
        parsed (keep (fn [line]
                       (when-let [[_ dir idx nm] (re-matches #"(output|input) (\d+): (.*)" line)]
                         {:dir (keyword dir) :index (Long/parseLong idx) :name nm}))
                     lines)
        grouped (group-by :dir parsed)]
    {:output (mapv #(dissoc % :dir) (get grouped :output []))
     :input  (mapv #(dissoc % :dir) (get grouped :input []))}))

(defn find-midi-port
  "Find a MIDI port index by name substring (case-insensitive).

  Returns the integer :index of the first port whose name contains
  `name-substr`.

  Options:
    :direction — :output (default) or :input

  Throws ex-info if no matching port is found.

  Example:
    (sidecar/find-midi-port \"IAC\")                    ; output port
    (sidecar/find-midi-port \"Hydra\" :direction :input) ; input port"
  [name-substr & {:keys [direction] :or {direction :output}}]
  (let [ports      (list-midi-ports)
        candidates (get ports direction [])
        lower      (str/lower-case name-substr)
        match      (first (filter #(str/includes? (str/lower-case (:name %)) lower)
                                  candidates))]
    (or (:index match)
        (throw (ex-info (str "No MIDI " (name direction) " port matching: " name-substr)
                        {:query     name-substr
                         :direction direction
                         :available (mapv :name candidates)})))))

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
    :binary       — explicit path to the sidecar binary (default: searches build/)
    :port         — TCP port (default: OS-assigned free port)
    :midi-port    — MIDI output port: integer index or name substring string.
                    (default 0; use (list-midi-ports) to discover ports)
    :midi-in-port — MIDI input port: integer index or name substring string.
                    (default nil = disabled)
                    When set, every received MIDI message is pushed to the JVM as
                    a 0x20 MidiIn frame and appended to `midi-in-messages`.
                    Use `await-midi-message` to wait for a specific message in tests.

  Returns the port number the sidecar is listening on.

  Example:
    (sidecar/start-sidecar!)
    (sidecar/start-sidecar! :binary \"/usr/local/bin/cljseq-sidecar\" :port 7777)
    (sidecar/start-sidecar! :midi-port 2)                     ; by index
    (sidecar/start-sidecar! :midi-port \"IAC\")                ; by name substring
    (sidecar/start-sidecar! :midi-port \"Hydra\" :midi-in-port \"Hydra\") ; both by name"
  [& {:keys [binary port midi-port midi-in-port]}]
  (when (:running? @sidecar-state)
    (throw (ex-info "Sidecar already running; call stop-sidecar! first" {})))
  (let [port         (long (or port (find-free-port)))
        binary       (or binary (find-binary))
        midi-port    (cond
                       (string? midi-port)    (find-midi-port midi-port :direction :output)
                       :else                  midi-port)
        midi-in-port (cond
                       (string? midi-in-port) (find-midi-port midi-in-port :direction :input)
                       :else                  midi-in-port)
        cmd          (cond-> [binary "--port" (str port)]
                       midi-port    (conj "--midi-port"    (str midi-port))
                       midi-in-port (conj "--midi-in-port" (str midi-in-port)))]
    (let [pb   (doto (ProcessBuilder. ^java.util.List cmd)
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
