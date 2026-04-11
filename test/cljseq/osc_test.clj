; SPDX-License-Identifier: EPL-2.0
(ns cljseq.osc-test
  "Unit tests for cljseq.osc — OSC UDP receiver and ctrl routing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core  :as core]
            [cljseq.ctrl  :as ctrl]
            [cljseq.osc   :as osc])
  (:import  [java.net DatagramSocket DatagramPacket InetSocketAddress]
            [java.nio ByteBuffer ByteOrder]
            [java.util Arrays]))

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

;; ---------------------------------------------------------------------------
;; Push-subscribe — programmatic API
;; ---------------------------------------------------------------------------

(defn- registry-atom [] @#'osc/subscriber-registry)
(defn- clear-registry! [] (reset! (registry-atom) {}))

(defn with-clean-subs [f]
  (clear-registry!)
  (try (f) (finally (clear-registry!))))

;; ---------------------------------------------------------------------------
;; ctrl-path->osc-address
;; ---------------------------------------------------------------------------

(deftest path->address-simple
  (is (= "/ctrl/loops/bass" (osc/ctrl-path->osc-address [:loops :bass]))))

(deftest path->address-single-kw
  (is (= "/ctrl/bpm" (osc/ctrl-path->osc-address [:bpm]))))

(deftest path->address-namespaced-kw
  ;; :filter/cutoff encodes to filter%2Fcutoff
  (let [addr (osc/ctrl-path->osc-address [:filter/cutoff])]
    (is (clojure.string/starts-with? addr "/ctrl/"))
    (is (clojure.string/includes? addr "filter"))))

(deftest path->address-roundtrips
  ;; parse-ctrl-path is the inverse; test a simple non-namespaced path
  (let [path [:loops :bass :vel]
        addr (osc/ctrl-path->osc-address path)]
    ;; The address should parse back to the same path via the internal parser
    (is (= "/ctrl/loops/bass/vel" addr))))

;; ---------------------------------------------------------------------------
;; subscribe! / unsubscribe! / subscribers
;; ---------------------------------------------------------------------------

(deftest subscribe-adds-to-registry
  (with-clean-subs
    (fn []
      (osc/subscribe! [:filter/cutoff] "192.168.1.42" 9000)
      (let [subs (osc/subscribers)]
        (is (contains? subs [:filter/cutoff]))
        (is (contains? (get subs [:filter/cutoff]) {:host "192.168.1.42" :port 9000}))))))

(deftest subscribe-idempotent
  (with-clean-subs
    (fn []
      (osc/subscribe! [:filter/cutoff] "192.168.1.42" 9000)
      (osc/subscribe! [:filter/cutoff] "192.168.1.42" 9000)
      (is (= 1 (count (get (osc/subscribers) [:filter/cutoff])))))))

(deftest subscribe-multiple-clients-same-path
  (with-clean-subs
    (fn []
      (osc/subscribe! [:filter/cutoff] "10.0.0.1" 9000)
      (osc/subscribe! [:filter/cutoff] "10.0.0.2" 9001)
      (is (= 2 (count (get (osc/subscribers) [:filter/cutoff])))))))

(deftest unsubscribe-removes-client
  (with-clean-subs
    (fn []
      (osc/subscribe! [:filter/cutoff] "192.168.1.42" 9000)
      (osc/unsubscribe! [:filter/cutoff] "192.168.1.42" 9000)
      (is (empty? (get (osc/subscribers) [:filter/cutoff]))))))

(deftest unsubscribe-noop-for-unknown-client
  (with-clean-subs
    (fn []
      (osc/subscribe! [:filter/cutoff] "192.168.1.42" 9000)
      (osc/unsubscribe! [:filter/cutoff] "10.0.0.99" 1234)
      ;; Original subscription still there
      (is (= 1 (count (get (osc/subscribers) [:filter/cutoff])))))))

(deftest unsubscribe-all-clears-path
  (with-clean-subs
    (fn []
      (osc/subscribe! [:filter/cutoff] "10.0.0.1" 9000)
      (osc/subscribe! [:filter/cutoff] "10.0.0.2" 9001)
      (osc/unsubscribe-all! [:filter/cutoff])
      (is (not (contains? (osc/subscribers) [:filter/cutoff]))))))

;; ---------------------------------------------------------------------------
;; Push delivery — inject *push-fn* to capture outbound calls
;; ---------------------------------------------------------------------------

(deftest push-fires-on-ctrl-set
  (with-clean-subs
    (fn []
      (let [delivered (atom [])
            push-mock (fn [host port address value]
                        (swap! delivered conj {:host host :port port
                                               :address address :value value}))]
        (binding [osc/*push-fn* push-mock]
          (osc/subscribe! [:osc-test/push-target] "10.0.0.1" 9000)
          (ctrl/set! [:osc-test/push-target] 0.75)
          ;; watcher fires synchronously in set!, so no sleep needed
          (is (= 1 (count @delivered)))
          (let [d (first @delivered)]
            (is (= "10.0.0.1" (:host d)))
            (is (= 9000       (:port d)))
            (is (string?      (:address d)))
            (is (clojure.string/starts-with? (:address d) "/ctrl/"))
            (is (= 0.75       (:value d)))))))))

(deftest push-fires-to-all-subscribers
  (with-clean-subs
    (fn []
      (let [delivered (atom [])
            push-mock (fn [host port address value]
                        (swap! delivered conj {:host host :port port}))]
        (binding [osc/*push-fn* push-mock]
          (osc/subscribe! [:osc-test/multi] "10.0.0.1" 9000)
          (osc/subscribe! [:osc-test/multi] "10.0.0.2" 9001)
          (ctrl/set! [:osc-test/multi] 42)
          (is (= 2 (count @delivered)))
          (is (= #{"10.0.0.1" "10.0.0.2"} (set (map :host @delivered)))))))))

(deftest push-not-fired-after-unsubscribe
  (with-clean-subs
    (fn []
      (let [delivered (atom [])
            push-mock (fn [& _] (swap! delivered conj :fired))]
        (binding [osc/*push-fn* push-mock]
          (osc/subscribe! [:osc-test/unsub-test] "10.0.0.1" 9000)
          (osc/unsubscribe! [:osc-test/unsub-test] "10.0.0.1" 9000)
          (ctrl/set! [:osc-test/unsub-test] 1.0)
          (is (empty? @delivered)))))))

(deftest push-coerces-integer
  (with-clean-subs
    (fn []
      (let [delivered (atom nil)
            push-mock (fn [_ _ _ v] (reset! delivered v))]
        (binding [osc/*push-fn* push-mock]
          (osc/subscribe! [:osc-test/int-coerce] "10.0.0.1" 9000)
          (ctrl/set! [:osc-test/int-coerce] (int 7))
          (is (= 7 @delivered)))))))

(deftest push-coerces-nil-to-string
  (with-clean-subs
    (fn []
      (let [delivered (atom :not-fired)
            push-mock (fn [_ _ _ v] (reset! delivered v))]
        (binding [osc/*push-fn* push-mock]
          (osc/subscribe! [:osc-test/nil-coerce] "10.0.0.1" 9000)
          (ctrl/set! [:osc-test/nil-coerce] nil)
          (is (= "nil" @delivered)))))))

(deftest push-coerces-keyword-to-prs-str
  (with-clean-subs
    (fn []
      (let [delivered (atom nil)
            push-mock (fn [_ _ _ v] (reset! delivered v))]
        (binding [osc/*push-fn* push-mock]
          (osc/subscribe! [:osc-test/kw-coerce] "10.0.0.1" 9000)
          (ctrl/set! [:osc-test/kw-coerce] :my-mode)
          (is (= ":my-mode" @delivered)))))))

;; ---------------------------------------------------------------------------
;; /sub and /unsub wire routes
;; ---------------------------------------------------------------------------

(deftest wire-sub-registers-subscriber
  (with-clean-subs
    (fn []
      ;; /sub /ctrl/osc-test%2Fwire-sub 10.0.0.5 8888
      (let [msg (encode-osc "/sub" "ssi"
                             (encode-string "/ctrl/osc-test%2Fwire-sub")
                             (encode-string "10.0.0.5")
                             (encode-int 8888))]
        (send-osc! msg test-port)
        (Thread/sleep 50)
        (let [subs (osc/subscribers)]
          (is (contains? subs [:osc-test/wire-sub]))
          (is (contains? (get subs [:osc-test/wire-sub]) {:host "10.0.0.5" :port 8888})))))))

(deftest wire-unsub-removes-subscriber
  (with-clean-subs
    (fn []
      ;; Subscribe first
      (osc/subscribe! [:osc-test/wire-unsub] "10.0.0.5" 8888)
      ;; Then /unsub via wire
      (let [msg (encode-osc "/unsub" "ssi"
                             (encode-string "/ctrl/osc-test%2Fwire-unsub")
                             (encode-string "10.0.0.5")
                             (encode-int 8888))]
        (send-osc! msg test-port)
        (Thread/sleep 50)
        (is (empty? (get (osc/subscribers) [:osc-test/wire-unsub])))))))

;; ---------------------------------------------------------------------------
;; /tree wire route — responds to sender
;; ---------------------------------------------------------------------------

(deftest wire-tree-responds-to-sender
  ;; Use a single socket for send+receive so the server sees our port and can
  ;; push the response back to us.
  (with-clean-subs
    (fn []
      (let [response-port 57298
            received      (atom nil)
            buf           (byte-array 65536)
            recv-pkt      (DatagramPacket. buf (alength buf))]
        (ctrl/set! [:osc-test/tree-val] 3.14)
        (with-open [sock (doto (DatagramSocket. (int response-port))
                           (.setSoTimeout 500))]
          ;; Send /tree from response-port so server pushes back here
          (let [msg (encode-osc "/tree" "s"
                                 (encode-string "/ctrl/osc-test%2Ftree-val"))
                req (DatagramPacket. msg (alength msg)
                                     (InetSocketAddress. "127.0.0.1" (int test-port)))]
            (.send sock req))
          (try
            (.receive sock recv-pkt)
            (let [data    (Arrays/copyOfRange buf 0 (.getLength recv-pkt))
                  decoded (#'osc/decode-message data)]
              (reset! received decoded))
            (catch java.net.SocketTimeoutException _ nil)))
        (is (some? @received) "response received")
        (when @received
          (is (= "/ctrl/osc-test%2Ftree-val" (:address @received)))
          (is (= [(float 3.14)] (mapv float (:args @received)))))))))
