; SPDX-License-Identifier: EPL-2.0
(ns cljseq.topology-test
  "Tests for cljseq.topology -- Layer 1: load, query, port resolution."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cljseq.topology :as topology]))

;; ---------------------------------------------------------------------------
;; Test topology EDN (written to a temp file for each test)
;; ---------------------------------------------------------------------------

(def ^:private test-topology-edn
  {:topology/id          :test-studio
   :topology/version     "0.1.0"
   :topology/description "Test topology"
   :devices
   {:synth-a {:device-id    :test/synth-a
              :port-pattern "Synth A"
              :midi/channel  1
              :role          :synth}
    :synth-b {:device-id    :test/synth-b
              :port-pattern "Synth B Out"
              :in-pattern   "Synth B In"
              :midi/channel  3
              :role          :synth}
    :ctrl-1  {:device-id    :test/ctrl-1
              :port-pattern "Controller One"
              :midi/channel  1
              :role          :controller}}
   :interfaces
   {:iface-1 {:manufacturer "Test" :model "TestMio" :port-pattern "TestMio"
              :host :host-a}}
   :hosts
   {:host-a {:role :cljseq-host :os :macos}}})

(def ^:private bad-topology-edn
  {:topology/id :bad
   :devices     "not-a-map"})

(defn- write-temp-edn [m]
  (let [f (java.io.File/createTempFile "topo-test-" ".edn")]
    (.deleteOnExit f)
    (spit f (pr-str m))
    (.getAbsolutePath f)))

;; Reset topology atom before each test
(use-fixtures :each
  (fn [t]
    (reset! @#'topology/topology nil)
    (t)
    (reset! @#'topology/topology nil)))

;; ---------------------------------------------------------------------------
;; load-topology!
;; ---------------------------------------------------------------------------

(deftest load-topology-valid
  (testing "loads a valid topology and returns the map"
    (let [path (write-temp-edn test-topology-edn)
          result (topology/load-topology! path)]
      (is (map? result))
      (is (= :test-studio (:topology/id result)))
      (is (map? (:devices result)))
      (is (topology/loaded?)))))

(deftest load-topology-sets-state
  (testing "loaded topology is accessible via query fns after load"
    (let [path (write-temp-edn test-topology-edn)]
      (topology/load-topology! path)
      (is (= "0.1.0" (:topology/version (topology/topology-meta)))))))

(deftest load-topology-missing-file
  (testing "throws ex-info when file does not exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                          (topology/load-topology! "/no/such/file.edn")))))

(deftest load-topology-bad-devices
  (testing "throws ex-info when :devices is not a map"
    (let [path (write-temp-edn bad-topology-edn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid topology"
                            (topology/load-topology! path))))))

(deftest load-topology-example-doc-exists
  (testing "doc/topology-example.edn schema reference is present in the repo"
    (is (.exists (io/file "doc/topology-example.edn"))
        "doc/topology-example.edn should exist as the schema reference")))

(deftest load-topology-example-doc-loadable
  (testing "doc/topology-example.edn parses without error"
    (let [result (topology/load-topology! "doc/topology-example.edn")]
      (is (map? result))
      (is (seq (:devices result))))))

(deftest load-topology-replaces-prior
  (testing "reloading with a new file replaces the prior topology"
    (let [path1 (write-temp-edn test-topology-edn)
          path2 (write-temp-edn (assoc test-topology-edn :topology/version "9.9.9"))]
      (topology/load-topology! path1)
      (is (= "0.1.0" (:topology/version (topology/topology-meta))))
      (topology/load-topology! path2)
      (is (= "9.9.9" (:topology/version (topology/topology-meta)))))))

;; ---------------------------------------------------------------------------
;; loaded?
;; ---------------------------------------------------------------------------

(deftest loaded-false-before-load
  (testing "loaded? returns false when no topology is loaded"
    (is (false? (topology/loaded?)))))

(deftest loaded-true-after-load
  (testing "loaded? returns true after load-topology!"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (true? (topology/loaded?)))))

;; ---------------------------------------------------------------------------
;; device-ids
;; ---------------------------------------------------------------------------

(deftest device-ids-returns-sorted-aliases
  (testing "device-ids returns sorted alias keywords"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (= [:ctrl-1 :synth-a :synth-b] (topology/device-ids)))))

(deftest device-ids-nil-before-load
  (testing "device-ids returns nil when nothing is loaded"
    (is (nil? (topology/device-ids)))))

;; ---------------------------------------------------------------------------
;; device-info
;; ---------------------------------------------------------------------------

(deftest device-info-known-alias
  (testing "device-info returns the descriptor for a known alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [d (topology/device-info :synth-a)]
      (is (= "Synth A" (:port-pattern d)))
      (is (= 1 (:midi/channel d)))
      (is (= :synth (:role d)))
      (is (= :test/synth-a (:device-id d))))))

(deftest device-info-unknown-alias-returns-nil
  (testing "device-info returns nil for an unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (nil? (topology/device-info :no-such-device)))))

(deftest device-info-nil-before-load
  (testing "device-info returns nil when nothing is loaded"
    (is (nil? (topology/device-info :synth-a)))))

;; ---------------------------------------------------------------------------
;; interface-info
;; ---------------------------------------------------------------------------

(deftest interface-info-known
  (testing "interface-info returns the interface descriptor"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [i (topology/interface-info :iface-1)]
      (is (= "Test" (:manufacturer i)))
      (is (= :host-a (:host i))))))

(deftest interface-info-unknown-returns-nil
  (testing "interface-info returns nil for unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (nil? (topology/interface-info :no-such-iface)))))

;; ---------------------------------------------------------------------------
;; topology-meta
;; ---------------------------------------------------------------------------

(deftest topology-meta-contents
  (testing "topology-meta returns id/version/description keys"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [m (topology/topology-meta)]
      (is (= :test-studio (:topology/id m)))
      (is (= "0.1.0" (:topology/version m)))
      (is (= "Test topology" (:topology/description m))))))

(deftest topology-meta-nil-before-load
  (testing "topology-meta returns nil when nothing is loaded"
    (is (nil? (topology/topology-meta)))))

;; ---------------------------------------------------------------------------
;; resolve-output-port / resolve-input-port (mocked)
;; ---------------------------------------------------------------------------

(deftest resolve-output-port-uses-port-pattern
  (testing "resolve-output-port calls find-midi-port with :port-pattern"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [calls (atom [])]
      (with-redefs [cljseq.sidecar/find-midi-port
                    (fn [pat & {:keys [direction]}]
                      (swap! calls conj {:pat pat :dir direction})
                      42)]
        (let [result (topology/resolve-output-port :synth-a)]
          (is (= 42 result))
          (is (= 1 (count @calls)))
          (is (= "Synth A" (:pat (first @calls))))
          (is (= :output (:dir (first @calls)))))))))

(deftest resolve-input-port-uses-in-pattern
  (testing "resolve-input-port uses :in-pattern when present"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [calls (atom [])]
      (with-redefs [cljseq.sidecar/find-midi-port
                    (fn [pat & {:keys [direction]}]
                      (swap! calls conj {:pat pat :dir direction})
                      7)]
        (let [result (topology/resolve-input-port :synth-b)]
          (is (= 7 result))
          (is (= "Synth B In" (:pat (first @calls))))
          (is (= :input (:dir (first @calls)))))))))

(deftest resolve-input-port-falls-back-to-port-pattern
  (testing "resolve-input-port falls back to :port-pattern when :in-pattern absent"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [calls (atom [])]
      (with-redefs [cljseq.sidecar/find-midi-port
                    (fn [pat & {:keys [direction]}]
                      (swap! calls conj {:pat pat :dir direction})
                      3)]
        (topology/resolve-input-port :synth-a)   ; synth-a has no :in-pattern
        (is (= "Synth A" (:pat (first @calls))))))))

(deftest resolve-output-port-unknown-throws
  (testing "resolve-output-port throws for unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown topology device"
                          (topology/resolve-output-port :no-such)))))

(deftest resolve-input-port-unknown-throws
  (testing "resolve-input-port throws for unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown topology device"
                          (topology/resolve-input-port :no-such)))))

;; ---------------------------------------------------------------------------
;; start-sidecar!
;; ---------------------------------------------------------------------------

(deftest start-sidecar-output-only
  (testing "start-sidecar! resolves alias and passes port index to sidecar"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [sidecar-calls (atom nil)]
      (with-redefs [cljseq.sidecar/find-midi-port (fn [_ & _] 2)
                    cljseq.sidecar/start-sidecar!  (fn [& args]
                                                     (reset! sidecar-calls args))]
        (topology/start-sidecar! :synth-a)
        (let [args @sidecar-calls]
          (is (some? args))
          (is (= 2 (second (drop-while #(not= :midi-port %) args)))))))))

(deftest start-sidecar-with-input
  (testing "start-sidecar! with :input resolves both output and input ports"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [sidecar-calls (atom nil)]
      (with-redefs [cljseq.sidecar/find-midi-port (fn [_ & _] 5)
                    cljseq.sidecar/start-sidecar!  (fn [& args]
                                                     (reset! sidecar-calls args))]
        (topology/start-sidecar! :synth-a :input :ctrl-1)
        (let [args @sidecar-calls]
          (is (some (fn [[k v]] (and (= k :midi-in-port) (= v 5)))
                    (partition 2 args))))))))

(deftest start-sidecar-without-loaded-topology-throws
  (testing "start-sidecar! throws when no topology is loaded"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No topology loaded"
                          (topology/start-sidecar! :synth-a)))))
