# Handoff: WebSocket ctrl-tree broadcast — v0.13.0

**Feature:** Add WebSocket push to the control-plane server so browser clients
receive live ctrl-tree updates without polling.

**Scope:** Step 1 of the web control surface story. No frontend in this PR —
just the server-side WebSocket endpoint and the ctrl-tree broadcast hook.

---

## Current state

`cljseq.server` (server.clj) uses `com.sun.net.httpserver.HttpServer` — Java's
built-in HTTP server, no external dependencies. Routes: GET/PUT `/ctrl`, GET/PUT
`/bpm`, GET `/ping`. Two total deps: `org.clojure/clojure` and
`org.clojure/data.json`.

`cljseq.ctrl` already has `ctrl/watch!` / `ctrl/unwatch!` — per-path watcher
registration with a `{path {watch-key fn}}` atom. `fire-watchers!` is called
from `ctrl/set!` and `ctrl/send!` on every value change.

## What needs to change

### 1. Add http-kit to project.clj

`com.sun.net.httpserver` has no WebSocket support. http-kit is the natural
replacement — Ring-compatible, WebSocket-native, single lightweight JAR:

```clojure
:dependencies [[org.clojure/clojure       "1.12.2"]
               [org.clojure/data.json     "2.5.1"]
               [http-kit                  "2.8.0"]]
```

### 2. Migrate server.clj from HttpServer to http-kit Ring handlers

The existing routes are simple — direct translation. Replace the Java interop
with Ring handler functions and `org.httpkit.server/run-server`:

```clojure
(ns cljseq.server
  (:require [org.httpkit.server :as hk]
            [clojure.data.json  :as json]
            [cljseq.ctrl        :as ctrl]
            [cljseq.core        :as core]))

(defn- handler [req]
  (let [method (:request-method req)
        path   (:uri req)]
    (cond
      (and (= :get method)  (= "/ping"  path)) (respond 200 {"ok" true})
      (and (= :get method)  (= "/bpm"   path)) (respond 200 {"bpm" (core/get-bpm)})
      (and (= :put method)  (= "/bpm"   path)) (handle-put-bpm req)
      (and (= :get method)  (= "/ctrl"  path)) (handle-get-ctrl-all)
      (str/starts-with? path "/ctrl/")         (handle-ctrl req method path)
      (= "/ws" path)                           (handle-ws req)
      :else                                    (respond 404 {"error" "not found"}))))
```

Keep `->json-safe` and the existing coercion logic — it's correct. Ring body
is an InputStream; use `slurp` instead of the current `read-body` helper.

### 3. Add WebSocket broadcast

```clojure
;; Channel registry — set of open WebSocket channels
(defonce ^:private ws-channels (atom #{}))

(defn- handle-ws [req]
  (hk/as-channel req
    {:on-open    (fn [ch]     (swap! ws-channels conj ch))
     :on-close   (fn [ch _]  (swap! ws-channels disj ch))
     :on-receive (fn [ch msg]
                   ;; Accept inbound ctrl writes from browser
                   (when-let [{:strs [path value]} (json/read-str msg)]
                     (ctrl/set! (mapv keyword path) value)))}))

(defn- broadcast-ctrl! [path value]
  (let [msg (json/write-str {"path"  (mapv name path)
                             "value" (->json-safe value)})]
    (doseq [ch @ws-channels]
      (hk/send! ch msg))))
```

### 4. Add ctrl/watch-all! to ctrl.clj

`ctrl/watch!` is per-path. Broadcasting needs a global hook — a watcher that
fires on any ctrl-tree change. Add to ctrl.clj:

```clojure
;; Global watchers — fire on every set!/send! regardless of path.
;; Stored separately from per-path watchers.
(defonce ^:private global-watchers (atom {}))

(defn watch-all!
  "Register `f` to be called with [path value] on every ctrl-tree change.
  `watch-key` identifies this watcher for later removal via unwatch-all!."
  [watch-key f]
  (swap! global-watchers assoc watch-key f))

(defn unwatch-all!
  "Remove the global watcher identified by `watch-key`."
  [watch-key]
  (swap! global-watchers dissoc watch-key))
```

Then in `fire-watchers!`, add a tail call that fires global watchers:

```clojure
(defn- fire-watchers! [path value]
  ;; existing per-path watchers ...
  (doseq [[_ f] @global-watchers]
    (try (f path value)
         (catch Exception e
           (println (str "[ctrl] global watcher error: " (.getMessage e)))))))
```

### 5. Wire broadcast into server startup

In `start-server!`, register the global watcher after starting http-kit:

```clojure
(defn start-server! [& {:keys [port] :or {port 7177}}]
  (when @server-atom (stop-server!))
  (let [srv (hk/run-server #'handler {:port port :legacy-return-value? false})]
    (ctrl/watch-all! ::ws-broadcast broadcast-ctrl!)
    (reset! server-atom {:server srv :port port})
    (println (str "[server] started on port " port))
    nil))

(defn stop-server! []
  (when-let [{:keys [server]} @server-atom]
    (ctrl/unwatch-all! ::ws-broadcast)
    (server :timeout 100)
    (reset! server-atom nil)
    (println "[server] stopped"))
  nil)
```

Note: `unwatch-all!` in ctrl.clj is already defined for per-path removal —
the new global `unwatch-all!` needs a different name. Suggest `unwatch-global!`
to avoid the collision.

---

## Test coverage needed

- `cljseq.server-test`: WebSocket connect → receive ctrl-tree change broadcast
- `cljseq.ctrl-test`: `watch-all!` fires on `set!` and `send!`; `unwatch-global!`
  removes it correctly
- Existing server tests: all routes still pass after http-kit migration
- Thread-safety: concurrent `swap!` on `ws-channels` is safe; http-kit channel
  sends from multiple threads are safe (http-kit guarantees this)

## SPDX

New function in ctrl.clj: header already present.  
server.clj: header already present; no new files.

## What this is NOT

- No frontend in this PR — just the server-side endpoint
- No shadow-cljs build, no ClojureScript, no HTML
- Browser can connect to `ws://localhost:7177/ws` and receive JSON push updates;
  that's the deliverable

## Next step after this lands

Step 2: shadow-cljs + Reagent frontend, served as static files from the same
http-kit server. See `src/handoff-vocabulary-and-patches.md` and the UI
research in areas/cljseq memory `project_ui_speculation.md`.

## References

- Research session: 2026-04-15 — WebSocket UI research
- http-kit WebSocket docs: https://http-kit.github.io/server.html
- Broadcasting pattern: https://yogthos.net/posts/2015-06-11-Websockets.html
- Memory: `project_ui_speculation.md` (in areas/cljseq-src-cljseq project memory)
