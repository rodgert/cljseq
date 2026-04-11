; SPDX-License-Identifier: EPL-2.0
(ns cljseq.remote
  "Topology Layer 3 — nREPL client for remote eval across cljseq peers.

  Provides a minimal nREPL client that speaks the standard wire protocol
  so any nREPL-compatible server (Clojure REPL, Babashka, etc.) can be
  a target.

  ## Quick start

    ;; Connect to a peer's nREPL server
    (def conn (connect! \"192.168.1.42\" 7888))

    ;; Evaluate an expression and get the result
    (remote-eval! conn \"(+ 1 2)\")
    ;=> {:value \"3\" :status #{:done}}

    ;; Evaluate within a named session
    (with-peer conn
      (remote-eval! *conn* \"(peer/peers)\"))

    ;; Disconnect
    (disconnect! conn)

  ## Connection map

  `connect!` returns a connection map:

    {:host     \"192.168.1.42\"
     :port     7888
     :socket   <java.net.Socket>
     :in       <InputStream>
     :out      <OutputStream>
     :session  nil}     ; populated after clone

  ## Error handling

  `remote-eval!` throws on network error. On nREPL-level errors
  (`:ex` in response) it returns the response map with `:ex` set.
  Callers should check `(contains? result :ex)`.

  ## Test injection

  `*open-connection*` is a dynamic var holding the function used to
  open a TCP socket. Tests can rebind it to avoid hitting a live network:

    (binding [remote/*open-connection* mock-open]
      (connect! \"localhost\" 7888))"
  (:require [cljseq.bencode :as bc])
  (:import  [java.net Socket]
            [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Dynamic var for test injection
;; ---------------------------------------------------------------------------

(defn- open-connection-impl [host port]
  (let [sock (Socket. ^String host ^int port)]
    {:socket  sock
     :in      (.getInputStream sock)
     :out     (.getOutputStream sock)}))

(def ^:dynamic *open-connection*
  "Function `(fn [host port] -> {:socket ... :in ... :out ...})`.
  Redefine in tests to avoid live TCP connections."
  open-connection-impl)

;; ---------------------------------------------------------------------------
;; Connection lifecycle
;; ---------------------------------------------------------------------------

(defn connect!
  "Open a TCP connection to an nREPL server at `host`:`port`.
  Returns a connection map. Clones a session immediately."
  [host port]
  (let [{:keys [socket in out]} (*open-connection* host port)
        conn {:host host :port port :socket socket :in in :out out
              :session (atom nil)}]
    ;; clone a session
    (bc/write-message {:op "clone" :id (str (UUID/randomUUID))} out)
    (when-let [resp (bc/decode-stream in)]
      (reset! (:session conn) (:new-session resp)))
    conn))

(defn disconnect!
  "Close the connection `conn`."
  [{:keys [socket]}]
  (when socket (.close ^java.io.Closeable socket)))

;; ---------------------------------------------------------------------------
;; Eval
;; ---------------------------------------------------------------------------

(defn- collect-responses
  "Read nREPL response frames from `in` until a frame contains `:done` in
  its `:status`. Returns the merged result map."
  [^java.io.InputStream in req-id]
  (loop [result {}]
    (let [frame (bc/decode-stream in)]
      (if (nil? frame)
        (assoc result :status #{:eof})
        (let [merged (merge result (dissoc frame :id :session))
              status (set (map keyword (get frame :status [])))]
          (if (contains? status :done)
            (assoc merged :status status)
            (recur merged)))))))

(defn remote-eval!
  "Evaluate `code-str` on the remote nREPL server via `conn`.
  Returns a map with at least `:value` and `:status`.

  Options:
    :ns  — evaluate in this namespace (string, default \"user\")"
  ([conn code-str] (remote-eval! conn code-str {}))
  ([{:keys [in out session]} code-str opts]
   (let [req-id  (str (UUID/randomUUID))
         session-id @session
         msg     (cond-> {:op "eval" :code code-str :id req-id}
                   session-id         (assoc :session session-id)
                   (:ns opts)         (assoc :ns (:ns opts)))]
     (bc/write-message msg out)
     (collect-responses in req-id))))

;; ---------------------------------------------------------------------------
;; with-peer convenience macro
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn*
  "Current connection, bound by `with-peer`."
  nil)

(defmacro with-peer
  "Evaluate `body` with `conn` bound to `*conn*`.

    (with-peer conn
      (remote-eval! *conn* \"(peer/peers)\"))"
  [conn & body]
  `(binding [*conn* ~conn]
     ~@body))

;; ---------------------------------------------------------------------------
;; Convenience: eval on a named peer from the peer registry
;; ---------------------------------------------------------------------------

(defn eval-on-peer!
  "Look up `peer-id` in the local peer registry and remote-eval `code-str`
  against its nREPL port.

  Requires `cljseq.peer/peers` to have an entry for `peer-id` with a
  `:nrepl-port` field in the beacon data.

  Opens a fresh connection per call (stateless helper). For interactive
  use, prefer `connect!` + `remote-eval!` directly."
  [peer-id code-str]
  (let [peer-info (get (deref (requiring-resolve 'cljseq.peer/peers)) peer-id)]
    (when-not peer-info
      (throw (ex-info "eval-on-peer!: peer not found" {:peer peer-id})))
    (let [{:keys [host nrepl-port]} peer-info]
      (when-not nrepl-port
        (throw (ex-info "eval-on-peer!: peer has no :nrepl-port" {:peer peer-id})))
      (let [conn (connect! host nrepl-port)]
        (try
          (remote-eval! conn code-str)
          (finally
            (disconnect! conn)))))))
