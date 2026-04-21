; SPDX-License-Identifier: EPL-2.0
(ns cljseq.journal-test
  (:require [clojure.test    :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.set     :as set]
            [cljseq.journal  :as journal])
  (:import  [java.sql DriverManager]))

;; ---------------------------------------------------------------------------
;; Test fixture — in-memory SQLite journal
;; ---------------------------------------------------------------------------

(def ^:dynamic *db-path* nil)

(defn- write-row! [conn beat wall-ns source path before after]
  (let [ps (.prepareStatement conn
             "INSERT INTO changes (beat,wall_ns,source,path,before,after)
              VALUES (?,?,?,?,?,?)")]
    (.setDouble ps 1 beat)
    (.setLong   ps 2 wall-ns)
    (.setInt    ps 3 source)
    (.setString ps 4 (pr-str path))
    (if before
      (.setString ps 5 (pr-str before))
      (.setNull   ps 5 java.sql.Types/VARCHAR))
    (if (some? after)
      (.setString ps 6 (pr-str after))
      (.setNull   ps 6 java.sql.Types/VARCHAR))
    (.executeUpdate ps)
    (.close ps)))

(defn with-test-journal [f]
  (let [tmp (java.io.File/createTempFile "cljseq-journal-test" ".sqlite")]
    (.deleteOnExit tmp)
    (let [path (.getAbsolutePath tmp)]
      (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))
                  stmt (.createStatement conn)]
        (.execute stmt
          "CREATE TABLE changes (
             id INTEGER PRIMARY KEY AUTOINCREMENT,
             tx_id BLOB, beat REAL NOT NULL, wall_ns INTEGER NOT NULL,
             source INTEGER NOT NULL, parent TEXT,
             path TEXT NOT NULL, before TEXT, after TEXT)"))
      (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
        ;; beat 0 — schema writes
        (write-row! conn 0.0 100 (journal/source-kind->int :schema)
                    [:cljseq/schema :device-models :arp2600]
                    nil {:model/id :arp2600 :model/name "ARP 2600"})
        (write-row! conn 0.0 101 (journal/source-kind->int :schema)
                    [:cljseq/schema :realizations :grey-meanie]
                    nil {:model :arp2600 :binding :midi})
        (write-row! conn 0.0 102 (journal/source-kind->int :schema)
                    [:cljseq/schema :active-realizations :arp2600]
                    nil :grey-meanie)
        ;; beat 1 — user sets initial values
        (write-row! conn 1.0 200 (journal/source-kind->int :user)
                    [:arp2600 :filter :cutoff] nil 0.5)
        (write-row! conn 1.0 201 (journal/source-kind->int :user)
                    [:arp2600 :filter :res] nil 0.2)
        ;; beat 16 — loop writes cutoff
        (write-row! conn 16.0 300 (journal/source-kind->int :loop)
                    [:arp2600 :filter :cutoff] 0.5 0.8)
        ;; beat 32 — user adjusts cutoff, loop adjusts res
        (write-row! conn 32.0 400 (journal/source-kind->int :user)
                    [:arp2600 :filter :cutoff] 0.8 0.6)
        (write-row! conn 32.0 401 (journal/source-kind->int :loop)
                    [:arp2600 :filter :res] 0.2 0.9))
      (binding [*db-path* path] (f)))))

(use-fixtures :each with-test-journal)

;; ---------------------------------------------------------------------------
;; Layer 1 — read-journal
;; ---------------------------------------------------------------------------

(deftest read-journal-returns-all-rows-test
  (let [txs (journal/read-journal *db-path*)]
    (is (= 8 (count txs)))))

(deftest read-journal-decodes-source-integers-test
  (let [sources (map :tx/source (journal/read-journal *db-path*))]
    (is (every? keyword? sources))
    (is (= [:schema :schema :schema :user :user :loop :user :loop]
           sources))))

(deftest read-journal-decodes-path-strings-test
  (let [txs (journal/read-journal *db-path*)]
    (is (every? vector? (map :tx/path txs)))
    (is (= [:arp2600 :filter :cutoff] (:tx/path (nth txs 3))))))

(deftest read-journal-decodes-before-after-test
  (let [txs (journal/read-journal *db-path*)
        row (nth txs 5)]  ; beat-16 loop write
    (is (= 0.5 (:tx/before row)))
    (is (= 0.8 (:tx/after  row)))))

(deftest read-journal-nil-before-on-first-write-test
  (let [txs (journal/read-journal *db-path*)
        row (nth txs 3)]  ; beat-1 first user write
    (is (nil? (:tx/before row)))
    (is (= 0.5 (:tx/after row)))))

;; ---------------------------------------------------------------------------
;; Layer 2 — tx-history
;; ---------------------------------------------------------------------------

(deftest tx-history-returns-writes-for-path-test
  (let [txs  (journal/read-journal *db-path*)
        hist (journal/tx-history txs [:arp2600 :filter :cutoff])]
    (is (= 3 (count hist)))
    (is (= [0.5 0.8 0.6] (map :tx/after hist)))))

(deftest tx-history-empty-for-unknown-path-test
  (let [txs (journal/read-journal *db-path*)]
    (is (empty? (journal/tx-history txs [:no/such :path])))))

;; ---------------------------------------------------------------------------
;; Layer 2 — tx-at
;; ---------------------------------------------------------------------------

(deftest tx-at-returns-value-at-beat-test
  (let [txs (journal/read-journal *db-path*)]
    (is (= 0.5 (journal/tx-at txs [:arp2600 :filter :cutoff] 1.0)))
    (is (= 0.8 (journal/tx-at txs [:arp2600 :filter :cutoff] 16.0)))
    (is (= 0.6 (journal/tx-at txs [:arp2600 :filter :cutoff] 32.0)))))

(deftest tx-at-interpolates-to-last-write-before-beat-test
  (let [txs (journal/read-journal *db-path*)]
    ;; beat 20 — loop wrote at beat 16, user hasn't touched it yet
    (is (= 0.8 (journal/tx-at txs [:arp2600 :filter :cutoff] 20.0)))))

(deftest tx-at-nil-before-any-write-test
  (let [txs (journal/read-journal *db-path*)]
    (is (nil? (journal/tx-at txs [:arp2600 :filter :cutoff] 0.5)))))

;; ---------------------------------------------------------------------------
;; Layer 2 — tx-range
;; ---------------------------------------------------------------------------

(deftest tx-range-returns-writes-in-window-test
  (let [txs (journal/read-journal *db-path*)
        win (journal/tx-range txs 1.0 32.0)]
    (is (= 5 (count win)))))

(deftest tx-range-source-filter-test
  (let [txs  (journal/read-journal *db-path*)
        user (journal/tx-range txs 0.0 64.0 :source :user)]
    (is (= 3 (count user)))
    (is (every? #(= :user (:tx/source %)) user))))

(deftest tx-range-path-filter-test
  (let [txs     (journal/read-journal *db-path*)
        cutoffs (journal/tx-range txs 0.0 64.0 :path [:arp2600 :filter :cutoff])]
    (is (= 3 (count cutoffs)))))

;; ---------------------------------------------------------------------------
;; Layer 2 — tx-by-source
;; ---------------------------------------------------------------------------

(deftest tx-by-source-isolates-source-test
  (let [txs  (journal/read-journal *db-path*)
        loop (journal/tx-by-source txs :loop)]
    (is (= 2 (count loop)))
    (is (every? #(= :loop (:tx/source %)) loop))))

;; ---------------------------------------------------------------------------
;; Layer 2 — active-paths
;; ---------------------------------------------------------------------------

(deftest active-paths-returns-written-paths-test
  (let [txs   (journal/read-journal *db-path*)
        paths (journal/active-paths (journal/tx-range txs 1.0 32.0))]
    (is (contains? paths [:arp2600 :filter :cutoff]))
    (is (contains? paths [:arp2600 :filter :res]))))

;; ---------------------------------------------------------------------------
;; Layer 2 — latest-values
;; ---------------------------------------------------------------------------

(deftest latest-values-folds-to-last-write-test
  (let [txs    (journal/read-journal *db-path*)
        latest (journal/latest-values txs)]
    (is (= 0.6 (get latest [:arp2600 :filter :cutoff])))
    (is (= 0.9 (get latest [:arp2600 :filter :res])))))

(deftest latest-values-excludes-schema-paths-when-filtered-test
  (let [txs    (journal/read-journal *db-path*)
        latest (journal/latest-values
                 (remove #(= :cljseq/schema (first (:tx/path %))) txs))]
    (is (not (contains? latest [:cljseq/schema :device-models :arp2600])))))

;; ---------------------------------------------------------------------------
;; Vocabulary
;; ---------------------------------------------------------------------------

(deftest source-kind-roundtrip-test
  (doseq [[kw n] journal/source-kind->int]
    (is (= kw (get journal/int->source-kind n))
        (str "roundtrip failed for " kw))))

;; ---------------------------------------------------------------------------
;; Layer 3 — crystallize
;; ---------------------------------------------------------------------------

(deftest crystallize-groups-by-path-test
  (let [txs    (journal/read-journal *db-path*)
        result (journal/crystallize txs 0.0 64.0)]
    (is (contains? result [:arp2600 :filter :cutoff]))
    (is (contains? result [:arp2600 :filter :res]))))

(deftest crystallize-normalizes-beats-relative-to-from-test
  (let [txs    (journal/read-journal *db-path*)
        result (journal/crystallize txs 16.0 64.0)
        cutoff (get result [:arp2600 :filter :cutoff])]
    ;; beat-16 loop write normalizes to 0.0, beat-32 user write to 16.0
    (is (= 0.0  (:beat (first cutoff))))
    (is (= 16.0 (:beat (second cutoff))))))

(deftest crystallize-source-filter-test
  (let [txs    (journal/read-journal *db-path*)
        result (journal/crystallize txs 0.0 64.0 :source :loop)]
    ;; only loop writes — cutoff at beat 16, res at beat 32
    (is (= 1 (count (get result [:arp2600 :filter :cutoff]))))
    (is (= 1 (count (get result [:arp2600 :filter :res]))))
    (is (= 0.8 (:value (first (get result [:arp2600 :filter :cutoff])))))))

(deftest crystallize-excludes-schema-by-default-test
  (let [txs    (journal/read-journal *db-path*)
        result (journal/crystallize txs 0.0 64.0)]
    (is (not (some #(= :cljseq/schema (first %)) (keys result))))))

(deftest crystallize-includes-schema-when-requested-test
  (let [txs    (journal/read-journal *db-path*)
        result (journal/crystallize txs 0.0 64.0 :schema? true)]
    (is (some #(= :cljseq/schema (first %)) (keys result)))))

(deftest crystallize-empty-window-returns-empty-map-test
  (let [txs (journal/read-journal *db-path*)]
    (is (= {} (journal/crystallize txs 100.0 200.0)))))

;; ---------------------------------------------------------------------------
;; Layer 3 — diff-sessions
;; ---------------------------------------------------------------------------

(defn- make-temp-db [rows]
  (let [tmp  (java.io.File/createTempFile "cljseq-diff-test" ".sqlite")
        path (.getAbsolutePath tmp)]
    (.deleteOnExit tmp)
    (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))
                stmt (.createStatement conn)]
      (.execute stmt
        "CREATE TABLE changes (
           id INTEGER PRIMARY KEY AUTOINCREMENT,
           tx_id BLOB, beat REAL NOT NULL, wall_ns INTEGER NOT NULL,
           source INTEGER NOT NULL, parent TEXT,
           path TEXT NOT NULL, before TEXT, after TEXT)"))
    (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
      (doseq [[path after] rows]
        (write-row! conn 1.0 (System/nanoTime)
                    (journal/source-kind->int :user) path nil after)))
    path))

(deftest diff-sessions-added-test
  (let [db-a (make-temp-db {[:synth :osc :freq] 440.0})
        db-b (make-temp-db {[:synth :osc :freq] 440.0
                             [:synth :lfo :rate] 2.0})
        diff (journal/diff-sessions db-a db-b)]
    (is (contains? (:added diff) [:synth :lfo :rate]))
    (is (= 2.0 (get-in diff [:added [:synth :lfo :rate]])))))

(deftest diff-sessions-removed-test
  (let [db-a (make-temp-db {[:synth :osc :freq] 440.0
                             [:synth :lfo :rate] 2.0})
        db-b (make-temp-db {[:synth :osc :freq] 440.0})
        diff (journal/diff-sessions db-a db-b)]
    (is (contains? (:removed diff) [:synth :lfo :rate]))))

(deftest diff-sessions-changed-test
  (let [db-a (make-temp-db {[:synth :osc :freq] 440.0})
        db-b (make-temp-db {[:synth :osc :freq] 880.0})
        diff (journal/diff-sessions db-a db-b)]
    (is (= {:before 440.0 :after 880.0}
           (get-in diff [:changed [:synth :osc :freq]])))))

(deftest diff-sessions-unchanged-test
  (let [db-a (make-temp-db {[:synth :osc :freq] 440.0
                             [:synth :lfo :rate] 2.0})
        db-b (make-temp-db {[:synth :osc :freq] 440.0
                             [:synth :lfo :rate] 3.0})
        diff (journal/diff-sessions db-a db-b)]
    (is (contains? (:unchanged diff) [:synth :osc :freq]))
    (is (not (contains? (:unchanged diff) [:synth :lfo :rate])))))

(deftest diff-sessions-all-keys-present-test
  (let [db-a (make-temp-db {[:a] 1})
        db-b (make-temp-db {[:b] 2})
        diff (journal/diff-sessions db-a db-b)]
    (is (set/subset? #{:added :removed :changed :unchanged} (set (keys diff))))))
