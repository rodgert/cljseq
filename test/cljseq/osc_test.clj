; SPDX-License-Identifier: EPL-2.0
(ns cljseq.osc-test
  "Unit tests for cljseq.osc — OSC UDP receiver and ctrl routing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core  :as core]
            [cljseq.ctrl  :as ctrl]
            [cljseq.osc   :as osc])
  (:import  [java.net DatagramSocket DatagramPacket InetSocketAddress]
            [java.nio ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------------
;; OSC encoder helpers (test-only)
;; ---------------------------------------------------------------------------

(defn- osc-align ^long [^long n] (bit-and (+ n 3) (bit-not 3)))

(defn- encode-string ^bytes [^String s]
  (let [slen    (inc (.length s))             ; +1 for null terminator
        padlen  (int (osc-align slen))
        result  (byte-array padlen)]
    (System/arraycopy (.getBytes s "UTF-8") 0 result 0 (.length s))
    result))

(defn- encode-int ^bytes [n]
  (.array (doto (ByteBuffer/allocate 4)
            (.order ByteOrder/BIG_ENDIAN)
            (.putInt (int n)))))

(defn- encode-float ^bytes [f]
  (.array (doto (ByteBuffer/allocate 4)
            (.order ByteOrder/BIG_ENDIAN)
            (.putFloat (float f)))))

(defn- encode-osc
  "Build a minimal OSC message byte array.

  `address`  — OSC address string, e.g. \"/bpm\"
  `type-tag` — OSC type tag chars without comma, e.g. \"f\" or \"si\"
  `arg-bytes` — pre-encoded argument byte arrays"
  ^bytes [address type-tag & arg-bytes]
  (let [addr-b (encode-string address)
        tag-b  (encode-string (str "," type-tag))
        total  (+ (alength addr-b) (alength tag-b)
                  (reduce + 0 (map alength arg-bytes)))
        result (byte-array total)
        pos    (atom 0)]
    (doseq [chunk (into [addr-b tag-b] arg-bytes)]
      (System/arraycopy chunk 0 result @pos (alength chunk))
      (swap! pos + (alength chunk)))
    result))

(defn- send-osc!
  "Send a raw OSC `payload` (byte[]) to `localhost:port` via UDP."
  [^bytes payload port]
  (with-open [s (DatagramSocket.)]
    (let [pkt (DatagramPacket. payload (alength payload)
                               (InetSocketAddress. "127.0.0.1" (int port)))]
      (.send s pkt))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private test-port 57299)   ; avoid collision with default 57120

(defn with-system [f]
  (core/start! :bpm 120)
  (osc/start-osc-server! :port test-port)
  (Thread/sleep 30)   ; allow socket to bind
  (try (f)
       (finally
         (osc/stop-osc-server!)
         (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(deftest osc-running-predicate-test
  (testing "osc-running? returns true while server is up"
    (is (osc/osc-running?)))
  (testing "osc-port returns the configured port"
    (is (= test-port (osc/osc-port)))))

(deftest osc-idempotent-restart-test
  (testing "start-osc-server! stops existing server and restarts cleanly"
    (osc/start-osc-server! :port test-port)
    (Thread/sleep 30)
    (is (osc/osc-running?))
    (is (= test-port (osc/osc-port)))))

;; ---------------------------------------------------------------------------
;; /bpm route
;; ---------------------------------------------------------------------------

(deftest osc-bpm-float-test
  (testing "/bpm with float arg sets master BPM"
    (let [msg (encode-osc "/bpm" "f" (encode-float 140.0))]
      (send-osc! msg test-port)
      (Thread/sleep 50)
      (is (= 140.0 (core/get-bpm))))))

(deftest osc-bpm-int-test
  (testing "/bpm with int arg sets master BPM"
    (let [msg (encode-osc "/bpm" "i" (encode-int 100))]
      (send-osc! msg test-port)
      (Thread/sleep 50)
      (is (= 100.0 (core/get-bpm))))))

;; ---------------------------------------------------------------------------
;; /ctrl/<path> route
;; ---------------------------------------------------------------------------

(deftest osc-ctrl-set-float-test
  (testing "/ctrl/<path> with float arg writes to ctrl tree"
    (ctrl/defnode! [:osc-test/cutoff] :type :float :value 0.0)
    (let [msg (encode-osc "/ctrl/osc-test%2Fcutoff" "f" (encode-float 0.7))]
      (send-osc! msg test-port)
      (Thread/sleep 50)
      (is (= (float 0.7) (float (ctrl/get [:osc-test/cutoff])))))))

(deftest osc-ctrl-set-int-test
  (testing "/ctrl/<path> with int arg writes to ctrl tree"
    (ctrl/defnode! [:osc-test/channel] :type :int :value 0)
    (let [msg (encode-osc "/ctrl/osc-test%2Fchannel" "i" (encode-int 5))]
      (send-osc! msg test-port)
      (Thread/sleep 50)
      (is (= 5 (ctrl/get [:osc-test/channel]))))))

(deftest osc-ctrl-nested-path-test
  (testing "/ctrl/loops/bass/vel sets a nested ctrl path"
    (ctrl/set! [:loops :bass :vel] 0)
    (let [msg (encode-osc "/ctrl/loops/bass/vel" "i" (encode-int 88))]
      (send-osc! msg test-port)
      (Thread/sleep 50)
      (is (= 88 (ctrl/get [:loops :bass :vel]))))))

(deftest osc-ctrl-creates-node-test
  (testing "/ctrl/<path> creates node if absent"
    (let [msg (encode-osc "/ctrl/osc-test/new-key" "f" (encode-float 3.14))]
      (send-osc! msg test-port)
      (Thread/sleep 50)
      (is (some? (ctrl/node-info [:osc-test :new-key])) "node created"))))

;; ---------------------------------------------------------------------------
;; Decode robustness
;; ---------------------------------------------------------------------------

(deftest osc-unknown-address-no-crash-test
  (testing "unknown OSC address does not crash the receiver"
    (let [msg (encode-osc "/unknown/path" "i" (encode-int 42))]
      (send-osc! msg test-port)
      (Thread/sleep 50)
      (is (osc/osc-running?) "server still running after unknown address"))))

(deftest osc-malformed-packet-no-crash-test
  (testing "malformed UDP payload does not crash the receiver"
    (let [garbage (byte-array [0 0 0 0 0 0 0 0])]
      (send-osc! garbage test-port)
      (Thread/sleep 50)
      (is (osc/osc-running?) "server still running after malformed packet"))))
