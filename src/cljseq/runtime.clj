; SPDX-License-Identifier: EPL-2.0
(ns cljseq.runtime
  "Ephemeral runtime state — process health, errors, subsystem status.

  Same observability model as cljseq.ctrl (path-based, watchable) but never
  persisted. Nothing here survives a JVM restart; nothing here belongs in a
  .cljseq export or crystallize output.

  Path convention:
    [:sc :status]       — :stopped :starting :running :error
    [:sc :errors]       — ring buffer of {:wall-ns :kind :message :cause}
    [:sc :sclang-pid]   — integer PID while sclang is running, nil otherwise
    [:sc :synthdefs]    — set of synthdef names sent this session
    [:midi :status]     — :connected :disconnected
    [:sidecar :status]  — :running :stopped
    [:loops :running]   — {loop-name {:ticks N :last-beat F}}

  Global watchers receive (path value) on every set! or conj! call.
  Path-scoped watchers receive (path old-value new-value) only when that path
  changes — zero-cost for unchanged paths."
  (:refer-clojure :exclude [get conj!]))

(defonce ^:private state           (atom {}))
(defonce ^:private global-watchers (atom {}))

;; ---------------------------------------------------------------------------
;; Internal notification
;; ---------------------------------------------------------------------------

(defn- notify! [path value]
  (doseq [[_ f] @global-watchers]
    (try (f path value)
         (catch Throwable _ nil))))

;; ---------------------------------------------------------------------------
;; Write
;; ---------------------------------------------------------------------------

(defn set!
  "Write value at path. Notifies all global and path-scoped watchers."
  [path value]
  (swap! state assoc-in path value)
  (notify! path value))

(defn clear!
  "Remove the key at path. Notifies global watchers with nil value."
  [path]
  (swap! state update-in (butlast path) dissoc (last path))
  (notify! path nil))

(defn conj!
  "Append value to the ring buffer at path, capping at limit (default 100).
  Notifies global watchers with the updated vector."
  ([path value]       (conj! path value 100))
  ([path value limit]
   (let [result (swap! state update-in path
                       #(vec (take-last limit (conj (or % []) value))))]
     (notify! path (get-in result path)))))

;; ---------------------------------------------------------------------------
;; Read
;; ---------------------------------------------------------------------------

(defn get
  "Return the value at path, or nil if absent."
  [path]
  (get-in @state path))

(defn snapshot
  "Return a point-in-time snapshot of the full runtime state map."
  []
  @state)

;; ---------------------------------------------------------------------------
;; Global watchers — called on every write with (path value)
;; ---------------------------------------------------------------------------

(defn watch-global!
  "Register f to be called with (path value) on every set! or conj!.
  k identifies the registration; re-registering the same k replaces it."
  [k f]
  (swap! global-watchers assoc k f))

(defn unwatch-global!
  "Deregister a global watcher registered with watch-global!."
  [k]
  (swap! global-watchers dissoc k))

;; ---------------------------------------------------------------------------
;; Path-scoped watchers — called only when a specific path changes
;; ---------------------------------------------------------------------------

(defn watch!
  "Register f on path. Fires (path old-value new-value) when the value at
  path changes. k identifies the registration."
  [path k f]
  (add-watch state k
    (fn [_ _ old new]
      (let [ov (get-in old path)
            nv (get-in new path)]
        (when (not= ov nv)
          (try (f path ov nv)
               (catch Throwable _ nil)))))))

(defn unwatch!
  "Deregister a path-scoped watcher registered with watch!."
  [k]
  (remove-watch state k))
