; SPDX-License-Identifier: EPL-2.0
(ns cljseq.server
  "Control-plane HTTP server (§17 Phase 1) with WebSocket push (v0.13.0).

  Exposes the ctrl tree over a minimal JSON REST API so external tools,
  DAW scripts, and web UIs can inspect and drive the sequencer state.
  Uses http-kit — Ring-compatible, WebSocket-native.

  ## Routes

    GET  /ping                 — health check; {\"ok\":true}
    GET  /bpm                  — current BPM; {\"bpm\":120}
    PUT  /bpm                  — set BPM; body {\"bpm\":140} → {\"ok\":true}
    GET  /ctrl                 — dump all ctrl-tree nodes as a JSON array
    GET  /ctrl/<p0>/<p1>/...   — read node at path [kw(p0) kw(p1) ...]
    PUT  /ctrl/<p0>/<p1>/...   — write node value; body {\"value\":...}
    GET  /loops                — running deflive-loop status [{\"name\":\"bass\" \"running?\":true \"ticks\":42} ...]
    GET  /ws                   — WebSocket upgrade; receives ctrl-tree change
                                 broadcasts as JSON {\"path\":[...] \"value\":...}
    GET  /                     — control surface HTML (resources/public/index.html)
    GET  /css/*                — static stylesheets from resources/public/css/
    GET  /js/*                 — compiled ClojureScript from resources/public/js/
                                 (build with: npx shadow-cljs release app)

  ## Path encoding

  Each URL path segment is URL-decoded then converted to a keyword.
  Namespaced keywords (e.g. :filter/cutoff) require the slash to be
  percent-encoded in the URL:

    /ctrl/filter%2Fcutoff  →  [:filter/cutoff]
    /ctrl/loops/bass       →  [:loops :bass]

  ## WebSocket protocol

  On connect, the browser receives live ctrl-tree change broadcasts:
    {\"path\":[\"filter\",\"cutoff\"] \"value\":0.7}

  The browser may also send inbound ctrl writes:
    {\"path\":[\"filter\",\"cutoff\"] \"value\":0.7}
  These are applied via ctrl/set! (logical write only, no MIDI dispatch).

  ## Note on value types

  JSON has no keyword or ratio types.  On PUT, the value is stored as the
  JSON-decoded Clojure type (Long, Double, String, Boolean, nil, vec, map).
  On GET, keywords are serialised as their name string (\":foo\" → \"foo\"),
  and Clojure ratios are converted to doubles.  Other opaque values are
  rendered via pr-str.

  ## Start / stop

    (server/start-server! :port 7177)
    (server/stop-server!)"
  (:require [org.httpkit.server :as hk]
            [clojure.string    :as str]
            [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [cljseq.ctrl       :as ctrl]
            [cljseq.core       :as core]
            [cljseq.loop       :as loop-ns])
  (:import  [java.net URLDecoder]))

;; ---------------------------------------------------------------------------
;; Private state
;; ---------------------------------------------------------------------------

(defonce ^:private server-atom (atom nil))

;; Set of open WebSocket channels — http-kit channels are thread-safe.
(defonce ^:private ws-channels (atom #{}))

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
;; Static file serving (resources/public/)
;; ---------------------------------------------------------------------------

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "map"  "application/json; charset=utf-8"})

(defn- ext [path]
  (second (re-find #"\.([^./]+)$" path)))

(defn- serve-resource
  "Serve a classpath resource from resources/public/.
  Uses io/input-stream so the body can be any byte sequence (JS, CSS, HTML).
  Returns 404 if the resource is not found — e.g. before running the CLJS build."
  [resource-path]
  (if-let [r (io/resource resource-path)]
    {:status  200
     :headers {"Content-Type"  (get content-types (ext resource-path)
                                    "application/octet-stream")
               "Cache-Control" "no-cache"}
     :body    (io/input-stream r)}
    {:status  404
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body    (json/write-str {"error" "not found"})}))

;; ---------------------------------------------------------------------------
;; Ring response helpers
;; ---------------------------------------------------------------------------

(defn- respond [status body-map]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/write-str (->json-safe body-map))})

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
;; WebSocket broadcast
;; ---------------------------------------------------------------------------

(defn- broadcast-ctrl!
  "Send a ctrl-tree change to all connected WebSocket clients.
  Path segments are encoded via kw->str (consistent with the HTTP API):
    [:loops :bass] → [\"loops\" \"bass\"]
    [:filter/cutoff] → [\"filter/cutoff\"]"
  [tx _state]
  (let [{:keys [path after]} (first (:tx/changes tx))
        msg (json/write-str {"path"  (mapv kw->str path)
                             "value" (->json-safe after)})]
    (doseq [ch @ws-channels]
      (hk/send! ch msg))))

(defn- handle-ws [req]
  (hk/as-channel req
    {:on-open    (fn [ch]
                   (swap! ws-channels conj ch))
     :on-close   (fn [ch _status]
                   (swap! ws-channels disj ch))
     :on-receive (fn [_ch msg]
                   (try
                     (when-let [{:strs [path value]} (json/read-str msg)]
                       (when (sequential? path)
                         (ctrl/set! (mapv keyword path) value)))
                     (catch Exception _ nil)))}))

;; ---------------------------------------------------------------------------
;; Ring handler
;; ---------------------------------------------------------------------------

(defn- handler [req]
  (let [method (:request-method req)
        uri    (:uri req)
        path   (first (str/split uri #"\?"))]
    (try
      (cond
        ;; ---- WebSocket upgrade ----
        (= "/ws" path)
        (handle-ws req)

        ;; ---- GET / — control surface HTML ----
        (and (= :get method) (or (= "/" path) (= "" path)))
        (serve-resource "public/index.html")

        ;; ---- GET /css/* and /js/* — static assets ----
        (and (= :get method)
             (or (str/starts-with? path "/css/")
                 (str/starts-with? path "/js/")))
        (serve-resource (str "public" path))

        ;; ---- GET /ping ----
        (and (= :get method) (= "/ping" path))
        (respond 200 {"ok" true})

        ;; ---- GET /bpm ----
        (and (= :get method) (= "/bpm" path))
        (respond 200 {"bpm" (core/get-bpm)})

        ;; ---- GET /loops ----
        (and (= :get method) (= "/loops" path))
        (respond 200
          (mapv (fn [{:keys [name running? ticks]}]
                  {"name"     (clojure.core/name name)
                   "running?" running?
                   "ticks"    ticks})
                (loop-ns/loop-status)))

        ;; ---- PUT /bpm ----
        (and (= :put method) (= "/bpm" path))
        (let [data (json/read-str (slurp (:body req)))]
          (if-let [bpm (clojure.core/get data "bpm")]
            (do (core/set-bpm! (double bpm))
                (respond 200 {"ok" true}))
            (respond 400 {"error" "missing bpm field"})))

        ;; ---- GET /ctrl (full dump) ----
        (and (= :get method) (or (= "/ctrl" path) (= "/ctrl/" path)))
        (respond 200
          (mapv (fn [{:keys [path value type node-meta]}]
                  {"path"  (mapv kw->str path)
                   "value" (->json-safe value)
                   "type"  (kw->str type)
                   "meta"  (->json-safe node-meta)})
                (ctrl/all-nodes)))

        ;; ---- GET /ctrl/<path> ----
        (and (= :get method) (str/starts-with? path "/ctrl/"))
        (let [ctrl-path (parse-ctrl-path path)]
          (if (nil? ctrl-path)
            (respond 400 {"error" "empty ctrl path"})
            (let [node (ctrl/node-info ctrl-path)]
              (if (nil? node)
                (respond 404 {"error" "not found"})
                (respond 200
                  {"path"  (mapv kw->str ctrl-path)
                   "value" (->json-safe (:value node))
                   "type"  (kw->str (:type node))
                   "meta"  (->json-safe (or (:node-meta node) {}))})))))

        ;; ---- PUT /ctrl/<path> ----
        (and (= :put method) (str/starts-with? path "/ctrl/"))
        (let [ctrl-path (parse-ctrl-path path)]
          (if (nil? ctrl-path)
            (respond 400 {"error" "empty ctrl path"})
            (let [data (json/read-str (slurp (:body req)))]
              (if (contains? data "value")
                (do (ctrl/set! ctrl-path (clojure.core/get data "value"))
                    (respond 200 {"ok" true}))
                (respond 400 {"error" "missing value field"})))))

        :else
        (respond 404 {"error" "route not found"}))
      (catch Exception e
        (respond 500 {"error" (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn start-server!
  "Start the control-plane HTTP server with WebSocket broadcast.

  Options:
    :port — TCP port to listen on (default 7177)

  Idempotent: any previously running server is stopped first.
  Registers a global ctrl-tree watcher that broadcasts every change to all
  connected WebSocket clients.

  Example:
    (server/start-server! :port 7177)"
  [& {:keys [port] :or {port 7177}}]
  (when @server-atom
    (stop-server!))
  (let [stop-fn (hk/run-server #'handler {:port port})]
    (ctrl/watch-global! ::ws-broadcast broadcast-ctrl!)
    (reset! server-atom {:server stop-fn :port port})
    (println (str "[server] started on port " port))
    nil))

(defn stop-server!
  "Stop the control-plane HTTP server if running."
  []
  (when-let [{:keys [server]} @server-atom]
    (ctrl/unwatch-global! ::ws-broadcast)
    (server :timeout 100)
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
