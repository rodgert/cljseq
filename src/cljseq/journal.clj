; SPDX-License-Identifier: EPL-2.0
(ns cljseq.journal
  "Transaction journal — read and query the SQLite session log written by the sidecar.

  Layer 1: `read-journal` decodes every persistence artifact at the boundary.
  All callers above this namespace see only Clojure data:

    {:tx/id      #uuid \"...\"
     :tx/beat    64.0
     :tx/wall-ns 1234567890000
     :tx/source  :user
     :tx/path    [:arp2600 :filter :cutoff]
     :tx/before  0.3
     :tx/after   0.7
     :tx/parent  nil}

  Layer 2: named folds over the seq returned by `read-journal`.

  Source kind vocabulary: `source-kind->int` and `int->source-kind` are the
  canonical encoding shared with the sidecar write path (cljseq.sidecar).

  Key design decisions: Q80."
  (:require [clojure.edn :as edn]
            [clojure.set :as set])
  (:import  [java.sql    DriverManager]
            [java.util   UUID]))

;; ---------------------------------------------------------------------------
;; Source kind vocabulary  (shared with cljseq.sidecar — must match C++ enum)
;; ---------------------------------------------------------------------------

(def source-kind->int
  "Map from source kind keyword to the integer stored in the SQLite `source` column.
  Must stay in sync with the C++ SourceKind enum in ipc.cpp."
  {:user        0
   :loop        1
   :input       2
   :trajectory  3
   :watcher     4
   :supervisor  5
   :schema      6
   :undo        7
   :error       8})

(def int->source-kind
  "Inverse of source-kind->int. Used by the read path to decode integers to keywords."
  (into {} (map (fn [[k v]] [v k]) source-kind->int)))

;; ---------------------------------------------------------------------------
;; Layer 1 — read-journal
;; ---------------------------------------------------------------------------

(defn- decode-uuid [^bytes bs]
  (when (and bs (= 16 (alength bs)))
    (let [bb (java.nio.ByteBuffer/wrap bs)]
      (UUID. (.getLong bb) (.getLong bb)))))

(defn- decode-row [rs]
  {:tx/id      (decode-uuid (.getBytes rs "tx_id"))
   :tx/beat    (.getDouble  rs "beat")
   :tx/wall-ns (.getLong    rs "wall_ns")
   :tx/source  (get int->source-kind (.getInt rs "source") :user)
   :tx/path    (edn/read-string (.getString rs "path"))
   :tx/before  (when-let [s (.getString rs "before")] (edn/read-string s))
   :tx/after   (when-let [s (.getString rs "after")]  (edn/read-string s))
   :tx/parent  (when-let [s (.getString rs "parent")] (edn/read-string s))})

(defn read-journal
  "Return a vector of tx maps from a SQLite journal file, ordered by id (write order).

  Every persistence artifact is decoded at this boundary:
    - `source` integer → keyword via `int->source-kind`
    - `path`, `before`, `after`, `parent` EDN strings → Clojure data
    - `tx_id` bytes → java.util.UUID (or nil if absent)

  The resulting maps carry `:tx/`-namespaced keys; see namespace docstring for shape.

  Example:
    (read-journal \"/path/to/session.sqlite\")
    ;; => [{:tx/beat 0.0 :tx/source :schema :tx/path [:cljseq/schema ...] ...} ...]"
  [^String db-path]
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" db-path))
              stmt (.createStatement conn)
              rs   (.executeQuery stmt "SELECT * FROM changes ORDER BY id")]
    (loop [rows []]
      (if-not (.next rs)
        rows
        (recur (conj rows (decode-row rs)))))))

;; ---------------------------------------------------------------------------
;; Layer 2 — query functions
;; ---------------------------------------------------------------------------

(defn tx-history
  "Return all writes to `path` in chronological order.

  Each entry: {:tx/beat N :tx/wall-ns N :tx/source kw :tx/before v :tx/after v}.

  Example:
    (tx-history (read-journal path) [:arp2600 :filter :cutoff])
    ;; => [{:tx/beat 0.0 :tx/after 0.5 :tx/source :schema} ...]"
  [txs path]
  (filter #(= path (:tx/path %)) txs))

(defn tx-at
  "Return the value of `path` at `beat` — last write at or before that beat.
  Returns nil if no write exists at or before the given beat."
  [txs path beat]
  (->> (tx-history txs path)
       (filter #(<= (:tx/beat %) beat))
       last
       :tx/after))

(defn tx-range
  "Return all writes within the beat window [beat-from beat-to] (inclusive).

  Options:
    :source — filter to a single source kind keyword (e.g. :user, :loop)
    :path   — filter to a specific path vector

  Example:
    (tx-range txs 0 32)
    (tx-range txs 64 128 :source :user)
    (tx-range txs 0 16  :path [:arp2600 :filter :cutoff])"
  [txs beat-from beat-to & {:keys [source path]}]
  (cond->> txs
    true   (filter #(let [b (:tx/beat %)] (and (>= b beat-from) (<= b beat-to))))
    source (filter #(= source (:tx/source %)))
    path   (filter #(= path   (:tx/path %)))))

(defn tx-by-source
  "Return all writes attributed to `source-kw` (e.g. :user, :loop, :trajectory).

  Example:
    (tx-by-source (read-journal path) :user)   ; deliberate parameter choices only"
  [txs source-kw]
  (filter #(= source-kw (:tx/source %)) txs))

(defn active-paths
  "Return the set of paths written at least once in `txs`.

  Useful for identifying the working parameter set of a performance window.

  Example:
    (->> (read-journal path)
         (tx-range 64 128 :source :user)
         active-paths)
    ;; => #{[:arp2600 :filter :cutoff] [:arp2600 :env :attack] ...}"
  [txs]
  (into #{} (map :tx/path txs)))

(defn latest-values
  "Return a map of {path → last-written-value} for all paths in `txs`.

  Equivalent to folding the log to its final state.  Paths whose last write
  was nil (cleared) are excluded.

  Example:
    (latest-values (read-journal path))
    ;; => {[:arp2600 :filter :cutoff] 0.7 ...}"
  [txs]
  (->> txs
       (group-by :tx/path)
       (keep (fn [[path writes]]
               (when-let [v (:tx/after (last writes))]
                 [path v])))
       (into {})))

;; ---------------------------------------------------------------------------
;; Layer 3 — semantic transforms
;; ---------------------------------------------------------------------------

(defn crystallize
  "Extract parameter writes in a beat window as a per-path timeline.

  Returns {path [{:beat N :value v} ...]} with beats normalized to be
  relative to `beat-from` (so beat-from maps to 0.0).  Useful for
  turning a live performance window into a trajectory or step sequence.

  Options:
    :source  — restrict to one source kind keyword (e.g. :loop, :user)
    :schema? — include [:cljseq/schema ...] paths (default false)

  Example:
    (crystallize txs 64.0 128.0 :source :loop)
    ;; => {[:arp2600 :filter :cutoff] [{:beat 0.0 :value 0.5}
    ;;                                  {:beat 16.0 :value 0.8}]}"
  [txs beat-from beat-to & {:keys [source schema?]}]
  (let [window (cond->> (tx-range txs beat-from beat-to)
                 source        (filter #(= source (:tx/source %)))
                 (not schema?) (remove #(= :cljseq/schema (first (:tx/path %))))
                 true          (filter #(some? (:tx/after %))))
        ->entry (fn [[path writes]]
                  [path (mapv #(hash-map :beat  (- (:tx/beat %) beat-from)
                                         :value (:tx/after %))
                               writes)])]
    (into {} (map ->entry (group-by :tx/path window)))))

(defn diff-sessions
  "Compare the final parameter state of two SQLite journal files.

  Returns:
    :added     — {path value}              paths in b absent from a
    :removed   — {path last-a-value}       paths in a absent from b
    :changed   — {path {:before va :after vb}}  present in both, different values
    :unchanged — #{path}                   present in both, same value

  Schema paths are included; filter :cljseq/schema from the journals first
  if not needed.

  Example:
    (diff-sessions \"tuesday.sqlite\" \"thursday.sqlite\")
    ;; => {:changed {[:arp2600 :filter :cutoff] {:before 0.5 :after 0.7}} ...}"
  [db-path-a db-path-b]
  (let [a      (latest-values (read-journal db-path-a))
        b      (latest-values (read-journal db-path-b))
        pa     (set (keys a))
        pb     (set (keys b))
        common (set/intersection pa pb)]
    {:added     (select-keys b (set/difference pb pa))
     :removed   (select-keys a (set/difference pa pb))
     :changed   (into {} (for [p common
                                :let [va (get a p) vb (get b p)]
                                :when (not= va vb)]
                            [p {:before va :after vb}]))
     :unchanged (into #{} (filter #(= (get a %) (get b %)) common))}))
