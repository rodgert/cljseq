; SPDX-License-Identifier: EPL-2.0
(ns cljseq.server
  "Control-plane HTTP server (§17 Phase 1).

  Exposes the ctrl tree over a minimal JSON REST API so external tools,
  DAW scripts, and web UIs can inspect and drive the sequencer state.
  Uses Java's built-in com.sun.net.httpserver.HttpServer — no external HTTP
  dependency required.

  ## Routes

    GET  /ping                 — health check; {\"ok\":true}
    GET  /bpm                  — current BPM; {\"bpm\":120}
    PUT  /bpm                  — set BPM; body {\"bpm\":140} → {\"ok\":true}
    GET  /ctrl                 — dump all ctrl-tree nodes as a JSON array
    GET  /ctrl/<p0>/<p1>/...   — read node at path [kw(p0) kw(p1) ...]
    PUT  /ctrl/<p0>/<p1>/...   — write node value; body {\"value\":...}

  ## Path encoding

  Each URL path segment is URL-decoded then converted to a keyword.
  Namespaced keywords (e.g. :filter/cutoff) require the slash to be
  percent-encoded in the URL:

    /ctrl/filter%2Fcutoff  →  [:filter/cutoff]
    /ctrl/loops/bass       →  [:loops :bass]

  ## Note on value types

  JSON has no keyword or ratio types.  On PUT, the value is stored as the
  JSON-decoded Clojure type (Long, Double, String, Boolean, nil, vec, map).
  On GET, keywords are serialised as their name string (\":foo\" → \"foo\"),
  and Clojure ratios are converted to doubles.  Other opaque values are
  rendered via pr-str.

  ## Start / stop

    (server/start-server! :port 7177)
    (server/stop-server!)"
  (:require [clojure.string    :as str]
            [clojure.data.json :as json]
            [cljseq.ctrl       :as ctrl]
            [cljseq.core       :as core])
  (:import  [com.sun.net.httpserver HttpServer HttpHandler]
            [java.net InetSocketAddress URLDecoder]
            [java.nio.charset StandardCharsets]
            [java.util.concurrent Executors]))

;; ---------------------------------------------------------------------------
;; Private state
;; ---------------------------------------------------------------------------

(defonce ^:private server-atom (atom nil))

(declare stop-server!)

;; ---------------------------------------------------------------------------
;; JSON value coercion
;; ---------------------------------------------------------------------------

(defn- ->json-safe
  "Recursively coerce a Clojure value to something clojure.data.json can encode.

  - keywords → name string  (\":filter/cutoff\" → \"filter/cutoff\")
  - ratios   → double
  - records / unknown types → pr-str fallback"
  [v]
  (cond
    (nil? v)        nil
    (boolean? v)    v
    (keyword? v)    (name v)
    (ratio? v)      (double v)
    (number? v)     v
    (string? v)     v
    (map? v)        (reduce-kv (fn [m k val]
                                 (assoc m (if (keyword? k) (name k) (str k))
                                          (->json-safe val)))
                               {} v)
    (sequential? v) (mapv ->json-safe v)
    :else           (pr-str v)))

;; ---------------------------------------------------------------------------
;; Low-level HTTP helpers
;; ---------------------------------------------------------------------------

(defn- respond!
  "Write a JSON response to `exchange` with the given HTTP `status` and `body-map`."
  [exchange status body-map]
  (let [body-str (json/write-str (->json-safe body-map))
        body     (.getBytes body-str StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "application/json; charset=utf-8"))
    (.sendResponseHeaders exchange status (count body))
    (with-open [os (.getResponseBody exchange)]
      (.write os body))))

(defn- read-body
  "Read the full request body of `exchange` as a UTF-8 string."
  [exchange]
  (with-open [r (java.io.BufferedReader.
                  (java.io.InputStreamReader.
                    (.getRequestBody exchange)
                    StandardCharsets/UTF_8))]
    (str/join "\n" (line-seq r))))

;; ---------------------------------------------------------------------------
;; Keyword serialisation
;; ---------------------------------------------------------------------------

(defn- kw->str
  "Render a keyword to its full qualified string, without the leading colon.
  :foo → \"foo\",  :filter/cutoff → \"filter/cutoff\""
  [k]
  (subs (str k) 1))

;; ---------------------------------------------------------------------------
;; Path parsing
;; ---------------------------------------------------------------------------

(defn- parse-ctrl-path
  "Convert a URI path string like \"/ctrl/loops/bass\" to [:loops :bass].
  Returns nil for an empty path (i.e. bare \"/ctrl\" with no segments)."
  [uri-path]
  (let [without-prefix (str/replace-first uri-path #"^/ctrl/?" "")
        segments       (remove empty? (str/split without-prefix #"/"))]
    (when (seq segments)
      (mapv (fn [s] (keyword (URLDecoder/decode s "UTF-8")))
            segments))))

;; ---------------------------------------------------------------------------
;; Request dispatcher
;; ---------------------------------------------------------------------------

(defn- dispatch
  "Ring-style dispatch: read the method + path from `exchange`, handle, respond."
  [exchange]
  (let [method (str (.getRequestMethod exchange))
        uri    (str (.getRequestURI exchange))
        path   (first (str/split uri #"\?"))]
    (try
      (cond
        ;; ---- GET /ping ----
        (and (= "GET" method) (= "/ping" path))
        (respond! exchange 200 {"ok" true})

        ;; ---- GET /bpm ----
        (and (= "GET" method) (= "/bpm" path))
        (respond! exchange 200 {"bpm" (core/get-bpm)})

        ;; ---- PUT /bpm ----
        (and (= "PUT" method) (= "/bpm" path))
        (let [data (json/read-str (read-body exchange))]
          (if-let [bpm (clojure.core/get data "bpm")]
            (do (core/set-bpm! (double bpm))
                (respond! exchange 200 {"ok" true}))
            (respond! exchange 400 {"error" "missing bpm field"})))

        ;; ---- GET /ctrl (full dump) ----
        (and (= "GET" method) (or (= "/ctrl" path) (= "/ctrl/" path)))
        (respond! exchange 200
          (mapv (fn [{:keys [path value type]}]
                  {"path"  (mapv kw->str path)
                   "value" (->json-safe value)
                   "type"  (kw->str type)})
                (ctrl/all-nodes)))

        ;; ---- GET /ctrl/<path> ----
        (and (= "GET" method) (str/starts-with? path "/ctrl/"))
        (let [ctrl-path (parse-ctrl-path path)]
          (if (nil? ctrl-path)
            (respond! exchange 400 {"error" "empty ctrl path"})
            (let [node (ctrl/node-info ctrl-path)]
              (if (nil? node)
                (respond! exchange 404 {"error" "not found"})
                (respond! exchange 200
                  {"path"  (mapv kw->str ctrl-path)
                   "value" (->json-safe (:value node))
                   "type"  (kw->str (:type node))})))))

        ;; ---- PUT /ctrl/<path> ----
        (and (= "PUT" method) (str/starts-with? path "/ctrl/"))
        (let [ctrl-path (parse-ctrl-path path)]
          (if (nil? ctrl-path)
            (respond! exchange 400 {"error" "empty ctrl path"})
            (let [data (json/read-str (read-body exchange))]
              (if (contains? data "value")
                (do (ctrl/set! ctrl-path (clojure.core/get data "value"))
                    (respond! exchange 200 {"ok" true}))
                (respond! exchange 400 {"error" "missing value field"})))))

        :else
        (respond! exchange 404 {"error" "route not found"}))
      (catch Exception e
        (try (respond! exchange 500 {"error" (.getMessage e)})
             (catch Exception _ nil))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start-server!
  "Start the control-plane HTTP server.

  Options:
    :port — TCP port to listen on (default 7177)

  Idempotent: any previously running server is stopped first.

  Example:
    (server/start-server! :port 7177)"
  [& {:keys [port] :or {port 7177}}]
  (when @server-atom
    (stop-server!))
  (let [srv (HttpServer/create (InetSocketAddress. (int port)) 0)]
    (.createContext srv "/"
      (reify HttpHandler
        (handle [_ exchange]
          (dispatch exchange))))
    (.setExecutor srv (Executors/newFixedThreadPool 4))
    (.start srv)
    (reset! server-atom {:server srv :port port})
    (println (str "[server] started on port " port))
    nil))

(defn stop-server!
  "Stop the control-plane HTTP server if running."
  []
  (when-let [{:keys [^HttpServer server]} @server-atom]
    (.stop server 0)
    (reset! server-atom nil)
    (println "[server] stopped"))
  nil)

(defn server-port
  "Return the port the server is currently listening on, or nil if not running."
  []
  (:port @server-atom))

(defn server-running?
  "Return true if the server is running."
  []
  (some? @server-atom))
