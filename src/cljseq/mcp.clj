; SPDX-License-Identifier: EPL-2.0
(ns cljseq.mcp
  "MCP (Model Context Protocol) server — exposes the cljseq API as AI-legible
  compositional tools.

  Runs as a standalone process launched by an MCP client (e.g. Claude Code).
  Connects to a running cljseq nREPL session and bridges the MCP JSON-RPC
  protocol to it.

  ## Transport

  MCP stdio: one JSON object per line on stdin/stdout. stderr is for logging.

  ## Usage

    # Start the MCP server (connects to running cljseq REPL on localhost:7888)
    lein mcp

    # Custom host/port
    lein mcp --nrepl-host 192.168.1.10 --nrepl-port 7888

  ## Claude Code configuration

    # Add to Claude Code as a local MCP server:
    claude mcp add cljseq -- lein -C mcp
    # or in .mcp.json:
    {\"mcpServers\":{\"cljseq\":{\"command\":\"lein\",\"args\":[\"-C\",\"mcp\"]}}}

  ## Tools

    evaluate       — eval any Clojure expression on the running session
    get-ctrl       — read a ctrl tree path; returns EDN
    set-ctrl       — write a value to a ctrl tree path
    get-harmony-ctx — current *harmony-ctx* (root, scale, tension)
    get-spectral   — current spectral analysis state (centroid, flux, density)
    get-loops      — enumerate running live loops and their tick counts
    define-loop    — create or hot-swap a named live loop
    stop-loop      — stop a named loop
    play-note      — trigger a single note event
    set-bpm        — change the master tempo
    get-config     — read the configuration registry (§25 parameters)
    set-config     — write a configuration registry parameter
    get-score      — dump the full ctrl tree as an EDN score snapshot

  ## Test injection

    `*eval-fn*` and `*io-fns*` are dynamic vars for test injection."
  (:require [clojure.data.json :as json]
            [clojure.string    :as str]
            [cljseq.remote     :as remote])
  (:import  [java.io BufferedReader InputStreamReader PrintWriter]))

;; ---------------------------------------------------------------------------
;; Protocol constants
;; ---------------------------------------------------------------------------

(def ^:private protocol-version "2024-11-05")
(def ^:private server-version   "0.9.0")

;; ---------------------------------------------------------------------------
;; Dynamic vars for test injection
;; ---------------------------------------------------------------------------

(def ^:dynamic *eval-fn*
  "Called as `(*eval-fn* code-str)` → {:value \"...\" :status #{:done}}.
  Nil in production; replaced with a mock in tests."
  nil)

(def ^:dynamic *io-fns*
  "Map with :read-line (fn [] -> String|nil) and :write-line (fn [String]).
  Nil in production; replaced with mock I/O in tests."
  nil)

;; ---------------------------------------------------------------------------
;; nREPL connection
;; ---------------------------------------------------------------------------

(defonce ^:private conn-atom (atom nil))

(defn- nrepl-eval
  "Evaluate `code-str` via the nREPL connection in `conn-atom`.
  Returns the remote-eval! result map."
  [code-str]
  (if *eval-fn*
    (*eval-fn* code-str)
    (if-let [conn @conn-atom]
      (remote/remote-eval! conn code-str)
      {:ex "not-connected" :status #{:done :error}
       :err "MCP server is not connected to a cljseq nREPL session."})))

(defn connect-nrepl!
  "Connect to a cljseq nREPL server at `host`:`port`. Stores the connection
  in `conn-atom`. Returns the connection map."
  [host port]
  (let [conn (remote/connect! host port)]
    (reset! conn-atom conn)
    conn))

(defn disconnect-nrepl!
  "Close the nREPL connection and clear `conn-atom`."
  []
  (when-let [conn @conn-atom]
    (remote/disconnect! conn)
    (reset! conn-atom nil)))

;; ---------------------------------------------------------------------------
;; JSON-RPC I/O
;; ---------------------------------------------------------------------------

(defn- read-line*
  "Read one line from stdin (or injected reader)."
  []
  (if-let [f (:read-line *io-fns*)]
    (f)
    (let [line (.readLine ^BufferedReader *in*)]
      line)))

(defn- write-line*
  "Write one line to stdout (or injected writer), flushing immediately."
  [^String s]
  (if-let [f (:write-line *io-fns*)]
    (f s)
    (do (println s)
        (flush))))

(defn- write-msg
  "Serialise `msg` as JSON and write to the output stream."
  [msg]
  (write-line* (json/write-str msg)))

;; ---------------------------------------------------------------------------
;; JSON-RPC response helpers
;; ---------------------------------------------------------------------------

(defn- ok-response [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- error-response
  ([id code message]
   {:jsonrpc "2.0" :id id :error {:code code :message message}})
  ([id code message data]
   {:jsonrpc "2.0" :id id :error {:code code :message message :data data}}))

(defn- tool-ok [text]
  {:content [{:type "text" :text text}] :isError false})

(defn- tool-error [text]
  {:content [{:type "text" :text text}] :isError true})

;; ---------------------------------------------------------------------------
;; nREPL result → tool content
;; ---------------------------------------------------------------------------

(defn- format-eval-result
  "Convert a remote-eval! result map to a tool content string."
  [{:keys [value out err ex status]}]
  (let [error? (contains? status :error)
        parts  (cond-> []
                 (and out (not (str/blank? out))) (conj (str ";; stdout\n" (str/trim out)))
                 (and err (not (str/blank? err))) (conj (str ";; stderr\n" (str/trim err)))
                 ex                               (conj (str ";; exception: " ex))
                 value                            (conj value))]
    {:text  (str/join "\n" parts)
     :error? error?}))

(defn- eval->tool
  "Evaluate `code` on the nREPL and return a tool content map."
  [code]
  (let [result (nrepl-eval code)
        {:keys [text error?]} (format-eval-result result)]
    (if error?
      (tool-error (if (str/blank? text) "(error — no detail)" text))
      (tool-ok    (if (str/blank? text) "nil" text)))))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def ^:private tools
  [{:name        "evaluate"
    :description "Evaluate any Clojure expression on the running cljseq session.
Returns the pr-str of the result. Use this for anything not covered by a
named tool. All cljseq namespaces are available via cljseq.user aliases."
    :inputSchema {:type       "object"
                  :properties {:code {:type        "string"
                                      :description "Clojure expression to evaluate"}}
                  :required   ["code"]}}

   {:name        "get-ctrl"
    :description "Read a ctrl tree path. Returns the value at that path as EDN.
Example path: '[:harmony :ctx]' or '[:config :bpm]'"
    :inputSchema {:type       "object"
                  :properties {:path {:type        "string"
                                      :description "EDN-encoded vector path, e.g. '[:loops :bass]'"}}
                  :required   ["path"]}}

   {:name        "set-ctrl"
    :description "Write a value to a ctrl tree path.
The value is an EDN-encoded Clojure value."
    :inputSchema {:type       "object"
                  :properties {:path  {:type "string" :description "EDN path vector"}
                               :value {:type "string" :description "EDN value to set"}}
                  :required   ["path" "value"]}}

   {:name        "get-harmony-ctx"
    :description "Return the current *harmony-ctx*: root note, scale, active tensions,
borrowed chords, and any ImprovisationContext. This is the primary harmonic
state of the running session."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "get-spectral"
    :description "Return the current spectral analysis state: centroid, flux, density,
and blur from the Spectraphon-inspired SAM analysis loop. Published to
the ctrl tree at [:spectral :state]."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "get-loops"
    :description "List all registered live loops: name, running state, tick count,
and whether the loop is currently active. Returns an EDN map."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "define-loop"
    :description "Create or hot-swap a named live loop. If the loop is already
running, the body is replaced without stopping it (hot-swap). The loop
restarts on the next beat boundary.

`opts` is an EDN map, e.g. '{:bar 4 :channel 1}'.
`body` is the Clojure loop body forms as a string."
    :inputSchema {:type       "object"
                  :properties {:name {:type "string" :description "Loop name keyword, e.g. ':bass'"}
                               :opts {:type "string" :description "EDN opts map, e.g. '{:bar 4}'"}
                               :body {:type "string" :description "Loop body Clojure forms"}}
                  :required   ["name" "body"]}}

   {:name        "stop-loop"
    :description "Stop a named live loop. The loop finishes its current iteration
then exits cleanly."
    :inputSchema {:type       "object"
                  :properties {:name {:type "string" :description "Loop name keyword, e.g. ':bass'"}}
                  :required   ["name"]}}

   {:name        "play-note"
    :description "Trigger a single note event on the running session. The event is
an EDN map with keys such as :pitch, :dur, :velocity, :channel, :device."
    :inputSchema {:type       "object"
                  :properties {:event {:type        "string"
                                       :description "EDN event map, e.g. '{:pitch :C4 :dur 1/4}'"}}
                  :required   ["event"]}}

   {:name        "set-bpm"
    :description "Change the master tempo. Propagates to Ableton Link peers when
Link is active."
    :inputSchema {:type       "object"
                  :properties {:bpm {:type "number" :description "Beats per minute"}}
                  :required   ["bpm"]}}

   {:name        "get-config"
    :description "Return all §25 configuration registry parameters as EDN:
:bpm, :sched-ahead-ms/*, :link/quantum, :osc/*, :lfo/update-rate-hz, etc."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "set-config"
    :description "Write a configuration registry parameter. Changes take effect
immediately; BPM changes also reanchor the timeline and notify Link peers."
    :inputSchema {:type       "object"
                  :properties {:key   {:type "string" :description "EDN keyword, e.g. ':link/quantum'"}
                               :value {:type "string" :description "EDN value"}}
                  :required   ["key" "value"]}}

   {:name        "get-score"
    :description "Return a snapshot of the full ctrl tree as EDN. This is the
complete current musical state of the session: config, harmony, spectral,
running loops, peer states, and any application-specific keys."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "list-peers"
    :description "Return the peer topology registry as EDN: all discovered nodes
with their node-id, host, role, nrepl-port, http-port, active backends, and
last-seen timestamp. Use this to learn what nodes are available before
calling evaluate-on-peer."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "ingest-midi"
    :description "Ingest a MIDI recording through the full composition pipeline:
parse → key/mode detect → voice separation → tension arc → outlier flagging →
resolution generation. Binds the result to `composition` in the nREPL session
so subsequent evaluate calls can reference it.

Returns a summary: key, mode, tempo, bars, voice note counts, peak tension bar,
and number of correction proposals. Use show-corrections to review them and
accept-corrections to apply them."
    :inputSchema {:type       "object"
                  :properties {:path      {:type        "string"
                                           :description "Path to .mid / .midi file"}
                               :structure {:type        "string"
                                           :description "Voice separation prior: 'ndlr' (default)"}
                               :resolution {:type       "string"
                                            :description "'ambient-fade' (default), 'authentic-cadence', 'cyclical-return'"}
                               :resolution-bars {:type  "number"
                                                 :description "Bars for the resolution passage (default 4)"}}
                  :required   ["path"]}}

   {:name        "show-corrections"
    :description "Display the outlier note corrections proposed by the last ingest-midi
run. Returns each proposed correction: the original note, the suggested replacement,
and the reason. Does not modify the score — use accept-corrections to apply."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "accept-corrections"
    :description "Apply the proposed corrections from the last ingest-midi run.
Replaces `composition` in the nREPL session with the corrected score and returns
the updated summary. Idempotent if there are no corrections."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "play-score"
    :description "Play back the current `composition` score via the cljseq event engine.
Triggers the drone, pad, and motif voices in sequence. The score must have been
ingested first via ingest-midi."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name        "save-score"
    :description "Save the current `composition` score as an EDN file. Returns the
absolute path of the written file. The EDN file can be loaded back with
(cljseq.composition/load-score path) and is the handoff artifact to the music area."
    :inputSchema {:type       "object"
                  :properties {:path {:type        "string"
                                      :description "Destination path for the .edn file"}}
                  :required   ["path"]}}

   {:name        "evaluate-on-peer"
    :description "Evaluate a Clojure expression on a remote cljseq node in the
local topology. The peer is identified by its node-id keyword (e.g. ':ubuntu').
Use list-peers first to discover available node ids and their backends.
Returns the result value, or an error if the peer is unreachable."
    :inputSchema {:type       "object"
                  :properties {:peer {:type        "string"
                                      :description "Node-id keyword, e.g. ':ubuntu' or ':rpi'"}
                               :code {:type        "string"
                                      :description "Clojure expression to evaluate on the remote node"}}
                  :required   ["peer" "code"]}}])

;; ---------------------------------------------------------------------------
;; Tool handlers
;; ---------------------------------------------------------------------------

(defmulti ^:private call-tool
  "Dispatch MCP tool calls. `tool-name` is a string; `args` is a map."
  (fn [tool-name _args] tool-name))

(defmethod call-tool "evaluate" [_ {:strs [code]}]
  (eval->tool code))

(defmethod call-tool "get-ctrl" [_ {:strs [path]}]
  (eval->tool (str "(pr-str (cljseq.ctrl/get-in " path "))")))

(defmethod call-tool "set-ctrl" [_ {:strs [path value]}]
  (eval->tool (str "(do (cljseq.ctrl/set-in! " path " " value ") :ok)")))

(defmethod call-tool "get-harmony-ctx" [_ _]
  (eval->tool "(pr-str @cljseq.loop/*harmony-ctx*)"))

(defmethod call-tool "get-spectral" [_ _]
  (eval->tool "(pr-str (cljseq.ctrl/get-in [:spectral :state]))"))

(defmethod call-tool "get-loops" [_ _]
  (eval->tool
   "(pr-str
      (when-let [sys-atom @#'cljseq.loop/system-ref]
        (reduce-kv
          (fn [m k v]
            (assoc m k {:running?   @(:running? v)
                        :tick-count @(:tick-count v)}))
          {}
          (:loops @sys-atom))))"))

(defmethod call-tool "define-loop" [_ {:strs [name opts body]}]
  (let [opts-str (or opts "{}")
        code     (str "(deflive-loop " name " " opts-str "\n  " body ")")]
    (eval->tool code)))

(defmethod call-tool "stop-loop" [_ {:strs [name]}]
  (eval->tool (str "(stop! " name ")")))

(defmethod call-tool "play-note" [_ {:strs [event]}]
  (eval->tool (str "(play! " event ")")))

(defmethod call-tool "set-bpm" [_ {:strs [bpm]}]
  (eval->tool (str "(set-bpm! " bpm ")")))

(defmethod call-tool "get-config" [_ _]
  (eval->tool "(pr-str (cljseq.config/all-configs))"))

(defmethod call-tool "set-config" [_ {:strs [key value]}]
  (eval->tool (str "(do (cljseq.config/set-config! " key " " value ") :ok)")))

(defmethod call-tool "get-score" [_ _]
  (eval->tool "(pr-str (cljseq.ctrl/all))"))

(defmethod call-tool "ingest-midi" [_ {:strs [path structure resolution resolution-bars]}]
  (let [opts (cond-> {}
               structure       (assoc :structure       (keyword structure))
               resolution      (assoc :resolution      (keyword resolution))
               resolution-bars (assoc :resolution-bars (long resolution-bars)))]
    (eval->tool
     (str "(do"
          "  (def composition (cljseq.composition/ingest! " (pr-str path) " " (pr-str opts) "))"
          "  (let [m (:meta composition)"
          "        s (:structure composition)"
          "        c (:corrections composition)"
          "        v (:voices composition)]"
          "    (pr-str {:key     (name (:tonic m))"
          "             :mode    (name (:mode m))"
          "             :tempo   (:tempo m)"
          "             :bars    (:bars m)"
          "             :voices  (reduce-kv (fn [acc k v] (assoc acc k (count v))) {} v)"
          "             :peak-tension-bar (:peak-bar s)"
          "             :peak-tension     (:peak-tension s)"
          "             :corrections      (count (:corrected c))})))"))))

(defmethod call-tool "show-corrections" [_ _]
  (eval->tool
   (str "(if-let [score-var (resolve 'composition)]"
        "  (with-out-str (cljseq.composition/print-corrections @score-var))"
        "  \"No composition loaded — run ingest-midi first.\")")))

(defmethod call-tool "accept-corrections" [_ _]
  (eval->tool
   (str "(if-let [score-var (resolve 'composition)]"
        "  (do (def composition (cljseq.composition/accept-corrections @score-var))"
        "      (let [m (:meta composition)]"
        "        (pr-str {:key  (name (:tonic m)) :mode (name (:mode m))"
        "                 :bars (:bars m) :corrections-applied :ok})))"
        "  \"No composition loaded — run ingest-midi first.\")")))

(defmethod call-tool "play-score" [_ _]
  (eval->tool
   (str "(if-let [score-var (resolve 'composition)]"
        "  (do (cljseq.composition/play-score! @score-var) :playing)"
        "  \"No composition loaded — run ingest-midi first.\")")))

(defmethod call-tool "save-score" [_ {:strs [path]}]
  (eval->tool
   (str "(if-let [score-var (resolve 'composition)]"
        "  (do (cljseq.composition/save-score @score-var " (pr-str path) ")"
        "      (pr-str {:saved " (pr-str path) "}))"
        "  \"No composition loaded — run ingest-midi first.\")")))

(defmethod call-tool "list-peers" [_ _]
  (eval->tool "(pr-str (cljseq.peer/peers))"))

(defmethod call-tool "evaluate-on-peer" [_ {:strs [peer code]}]
  ;; Proxy through the primary nREPL: eval-on-peer! does the peer registry
  ;; lookup, opens a fresh connection to the target node, and returns the
  ;; remote result map.  We extract just the :value (or surface the error).
  (let [code-lit (pr-str code)]  ; safely quote code as a Clojure string literal
    (eval->tool
     (str "(let [r (cljseq.remote/eval-on-peer! " peer " " code-lit ")]"
          "  (if (contains? (:status r) :error)"
          "    (throw (ex-info (str \"peer eval error: \" (:ex r) \" \" (:err r)) r))"
          "    (:value r)))"))))

(defmethod call-tool :default [tool-name _]
  (tool-error (str "Unknown tool: " tool-name)))

;; ---------------------------------------------------------------------------
;; MCP method handlers
;; ---------------------------------------------------------------------------

(defmulti ^:private handle-method
  "Dispatch an MCP JSON-RPC method.
  Returns a result map (for request methods) or nil (for notifications)."
  (fn [method _params] method))

(defmethod handle-method "initialize" [_ params]
  {:protocolVersion protocol-version
   :capabilities    {:tools {}}
   :serverInfo      {:name "cljseq" :version server-version}})

(defmethod handle-method "notifications/initialized" [_ _]
  nil)

(defmethod handle-method "ping" [_ _]
  {})

(defmethod handle-method "tools/list" [_ _]
  {:tools tools})

(defmethod handle-method "tools/call" [_ params]
  (let [tool-name (get params "name")
        args      (get params "arguments" {})]
    (call-tool tool-name args)))

(defmethod handle-method :default [method _]
  ::unknown-method)

;; ---------------------------------------------------------------------------
;; Message processing (public — forward declaration resolved below)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Main loop
;; ---------------------------------------------------------------------------

(defn process-message
  "Parse one JSON-RPC message string and write any required response.
  Public so tests can drive individual messages without running the full loop."
  [json-str]
  (try
    (let [msg    (json/read-str json-str)
          id     (get msg "id")
          method (get msg "method")
          params (get msg "params" {})]
      (when method
        (let [result (handle-method method params)]
          (cond
            (nil? id)
            nil

            (= result ::unknown-method)
            (write-msg (error-response id -32601 (str "Method not found: " method)))

            (nil? result)
            (write-msg (ok-response id {}))

            :else
            (write-msg (ok-response id result))))))
    (catch Exception e
      (binding [*out* *err*]
        (println "mcp: error processing message:" (.getMessage e))))))

(defn run-server!
  "Run the MCP server main loop. Reads JSON-RPC messages from stdin until
  EOF or the connection drops. `nrepl-host` and `nrepl-port` name the
  running cljseq session to connect to."
  [nrepl-host nrepl-port]
  (binding [*out* (PrintWriter. System/out true)]
    (try
      (connect-nrepl! nrepl-host nrepl-port)
      (binding [*in* (BufferedReader. (InputStreamReader. System/in))]
        (loop []
          (when-let [line (read-line*)]
            (when-not (str/blank? line)
              (process-message line))
            (recur))))
      (catch java.net.ConnectException e
        (binding [*out* *err*]
          (println "mcp: cannot connect to cljseq nREPL at"
                   (str nrepl-host ":" nrepl-port)
                   "—" (.getMessage e))
          (System/exit 1)))
      (finally
        (disconnect-nrepl!)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- parse-args
  "Parse --key value pairs from `args`. Returns a map with string keys."
  [args]
  (loop [acc {} remaining args]
    (if (< (count remaining) 2)
      acc
      (let [[k v & rest] remaining]
        (if (str/starts-with? k "--")
          (recur (assoc acc (subs k 2) v) rest)
          (recur acc (rest remaining)))))))

(defn -main
  "Start the cljseq MCP server.

  Options:
    --nrepl-host HOST   nREPL server host (default: localhost)
    --nrepl-port PORT   nREPL server port (default: 7888)"
  [& args]
  (let [opts      (parse-args args)
        nrepl-host (get opts "nrepl-host" "localhost")
        nrepl-port (Integer/parseInt (get opts "nrepl-port" "7888"))]
    (binding [*out* *err*]
      (println "cljseq MCP server starting — connecting to"
               (str nrepl-host ":" nrepl-port)))
    (run-server! nrepl-host nrepl-port)))
