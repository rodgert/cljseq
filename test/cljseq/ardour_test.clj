; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ardour-test
  "Unit tests for cljseq.ardour and the OSC encoding in cljseq.osc.

  Tests are divided into three tiers:

  1. Pure encoding — cljseq.osc/encode-message round-trip via the existing
     OSC receiver infrastructure. No network I/O, no Ardour required.

  2. State machine — ardour/connect!, capture!, capture-stop!, capture-discard!
     state transitions. No network I/O, no Ardour required.

  3. Wire verification — osc-send! is called against a local DatagramSocket
     listener; the received bytes are decoded to verify the correct OSC message
     was sent. Tests Ardour transport/track functions at the wire level.
     No Ardour required."
  (:require [clojure.test   :refer [deftest is testing use-fixtures]]
            [cljseq.ardour  :as ardour]
            [cljseq.osc     :as osc])
  (:import  [java.net DatagramSocket DatagramPacket InetSocketAddress]
            [java.nio ByteBuffer ByteOrder]
            [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; OSC decode helpers (test-only — inverse of osc/encode-message)
;; ---------------------------------------------------------------------------

(defn- osc-align ^long [^long n] (bit-and (+ n 3) (bit-not 3)))

(defn- decode-osc-string
  "Read a null-terminated, 4-byte-aligned OSC string from buf."
  [^ByteBuffer buf]
  (let [start (.position buf)]
    (loop [] (when (not= 0 (.get buf)) (recur)))
    (let [consumed (- (.position buf) start)
          s        (String. (.array buf) start (dec consumed) "UTF-8")]
      (.position buf (int (+ start (osc-align consumed))))
      s)))

(defn- decode-osc-message
  "Decode an OSC message byte array → {:address <str> :args [...]}.
  Handles i (int32) and f (float32) arg types."
  [^bytes data]
  (let [buf     (doto (ByteBuffer/wrap data) (.order ByteOrder/BIG_ENDIAN))
        address (decode-osc-string buf)
        tag-str (decode-osc-string buf)
        types   (if (clojure.string/starts-with? tag-str ",")
                  (subs tag-str 1) "")
        args    (reduce (fn [acc t]
                          (case t
                            \i (conj acc (.getInt buf))
                            \f (conj acc (.getFloat buf))
                            \s (conj acc (decode-osc-string buf))
                            acc))
                        [] types)]
    {:address address :args args}))

;; ---------------------------------------------------------------------------
;; Local UDP echo server for wire-level tests
;; ---------------------------------------------------------------------------

(defn- start-echo-server!
  "Open a DatagramSocket on a random port, receive one packet (with 500ms
  timeout), decode it as an OSC message, and return the result.
  Used to verify what osc-send! puts on the wire."
  []
  (let [sock (DatagramSocket. 0)          ; OS-assigned port
        port (.getLocalPort sock)]
    (.setSoTimeout sock 500)
    {:sock sock :port port}))

(defn- receive-one!
  "Receive one UDP packet from sock and decode it as an OSC message.
  Returns nil on timeout."
  [{:keys [^DatagramSocket sock]}]
  (let [buf (byte-array 1024)
        pkt (DatagramPacket. buf (alength buf))]
    (try
      (.receive sock pkt)
      (decode-osc-message (Arrays/copyOfRange buf 0 (.getLength pkt)))
      (catch java.net.SocketTimeoutException _ nil)
      (finally (.close sock)))))

;; ---------------------------------------------------------------------------
;; Fixtures — reset ardour state between tests
;; ---------------------------------------------------------------------------

(defn with-clean-ardour [f]
  ;; Reset to localhost defaults; stop any accidental in-progress capture
  (ardour/connect!)
  (when (ardour/recording?)
    (ardour/capture-discard!))
  (f))

(use-fixtures :each with-clean-ardour)

;; ---------------------------------------------------------------------------
;; Tier 1: OSC encoding (cljseq.osc/encode-message)
;; ---------------------------------------------------------------------------

(deftest encode-no-arg-message-test
  (testing "no-arg message encodes address + empty type tag"
    (let [msg (osc/encode-message "/transport_play" [])
          dec (decode-osc-message msg)]
      (is (= "/transport_play" (:address dec)))
      (is (= [] (:args dec))))))

(deftest encode-int-arg-test
  (testing "int32 arg encodes correctly"
    (let [msg (osc/encode-message "/transport_record" [(int 1)])
          dec (decode-osc-message msg)]
      (is (= "/transport_record" (:address dec)))
      (is (= [1] (:args dec)))))
  (testing "Long arg is treated as int32"
    (let [msg (osc/encode-message "/transport_record" [0])
          dec (decode-osc-message msg)]
      (is (= [0] (:args dec))))))

(deftest encode-float-arg-test
  (testing "float32 arg encodes and decodes correctly"
    (let [msg (osc/encode-message "/route/gain" [(int 1) (float 1.0)])
          dec (decode-osc-message msg)]
      (is (= "/route/gain" (:address dec)))
      (is (= 1 (first (:args dec))))
      (is (< (Math/abs (- 1.0 (second (:args dec)))) 1e-5))))
  (testing "Double arg is encoded as float32"
    (let [msg (osc/encode-message "/route/fader" [(int 2) 0.75])
          dec (decode-osc-message msg)]
      (is (< (Math/abs (- 0.75 (second (:args dec)))) 1e-5)))))

(deftest encode-string-arg-test
  (testing "String arg encodes correctly"
    (let [msg (osc/encode-message "/save_state" ["mysession"])
          dec (decode-osc-message msg)]
      (is (= "/save_state" (:address dec)))
      (is (= ["mysession"] (:args dec))))))

(deftest encode-message-4byte-alignment-test
  (testing "total message length is a multiple of 4"
    (doseq [addr ["/transport_play" "/transport_record" "/goto_start"
                  "/route/recenable" "/save_state"]]
      (let [msg (osc/encode-message addr [])]
        (is (zero? (mod (alength msg) 4))
            (str addr " message length not 4-byte aligned"))))))

(deftest encode-multi-arg-test
  (testing "multiple args of mixed types"
    (let [msg (osc/encode-message "/route/recenable" [(int 3) (int 1)])
          dec (decode-osc-message msg)]
      (is (= "/route/recenable" (:address dec)))
      (is (= [3 1] (:args dec))))))

;; ---------------------------------------------------------------------------
;; Tier 2: State machine (ardour/connect!, capture!, etc.)
;; ---------------------------------------------------------------------------

(deftest connect-defaults-test
  (testing "connect! with no args uses localhost:3819"
    (ardour/connect!)
    (is (= "127.0.0.1" (ardour/ardour-host)))
    (is (= 3819        (ardour/ardour-port)))))

(deftest connect-host-test
  (testing "connect! with host sets host and default port"
    (ardour/connect! "10.0.0.5")
    (is (= "10.0.0.5" (ardour/ardour-host)))
    (is (= 3819       (ardour/ardour-port)))))

(deftest connect-host-port-test
  (testing "connect! with host and port"
    (ardour/connect! "10.0.0.5" 3820)
    (is (= "10.0.0.5" (ardour/ardour-host)))
    (is (= 3820       (ardour/ardour-port)))))

(deftest connected-predicate-test
  (testing "connected? returns true after connect!"
    (ardour/connect!)
    (is (ardour/connected?))))

(deftest capture-initial-state-test
  (testing "no capture in progress initially"
    (is (not (ardour/recording?)))
    (let [s (ardour/capture-status)]
      (is (false? (:recording? s)))
      (is (nil? (:started-at s)))
      (is (nil? (:session-id s))))))

(deftest capture-start-state-test
  (testing "capture! marks recording as true and returns a session ID"
    ;; connect! to a port nothing is listening on — send will silently fail
    (ardour/connect! "127.0.0.1" 19999)
    (let [sid (ardour/capture!)]
      (is (string? sid))
      (is (clojure.string/starts-with? sid "cap-"))
      (is (ardour/recording?))
      (let [s (ardour/capture-status)]
        (is (true? (:recording? s)))
        (is (some? (:started-at s)))
        (is (= sid (:session-id s))))
      ;; clean up
      (ardour/capture-discard!))))

(deftest capture-stop-clears-state-test
  (testing "capture-stop! clears recording? flag"
    (ardour/connect! "127.0.0.1" 19999)
    (ardour/capture!)
    (ardour/capture-stop!)
    (is (not (ardour/recording?)))))

(deftest capture-discard-clears-state-test
  (testing "capture-discard! clears all capture state"
    (ardour/connect! "127.0.0.1" 19999)
    (ardour/capture!)
    (ardour/capture-discard!)
    (is (not (ardour/recording?)))
    (let [s (ardour/capture-status)]
      (is (nil? (:started-at s)))
      (is (nil? (:session-id s))))))

(deftest double-capture-throws-test
  (testing "capture! throws if already recording"
    (ardour/connect! "127.0.0.1" 19999)
    (ardour/capture!)
    (is (thrown? clojure.lang.ExceptionInfo (ardour/capture!)))
    (ardour/capture-discard!)))

;; ---------------------------------------------------------------------------
;; Tier 3: Wire verification (osc-send! + ardour transport functions)
;; ---------------------------------------------------------------------------

(deftest osc-send-transport-play-test
  (testing "transport-play! sends /transport_play with no args"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/transport-play!)
      (let [msg (receive-one! srv)]
        (is (= "/transport_play" (:address msg)))
        (is (= [] (:args msg)))))))

(deftest osc-send-transport-stop-test
  (testing "transport-stop! sends /transport_stop with no args"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/transport-stop!)
      (let [msg (receive-one! srv)]
        (is (= "/transport_stop" (:address msg)))
        (is (= [] (:args msg)))))))

(deftest osc-send-transport-record-arm-test
  (testing "transport-record! true sends /transport_record 1"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/transport-record! true)
      (let [msg (receive-one! srv)]
        (is (= "/transport_record" (:address msg)))
        (is (= [1] (:args msg))))))
  (testing "transport-record! false sends /transport_record 0"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/transport-record! false)
      (let [msg (receive-one! srv)]
        (is (= "/transport_record" (:address msg)))
        (is (= [0] (:args msg)))))))

(deftest osc-send-goto-start-test
  (testing "goto-start! sends /goto_start"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/goto-start!)
      (let [msg (receive-one! srv)]
        (is (= "/goto_start" (:address msg)))))))

(deftest osc-send-add-marker-test
  (testing "add-marker! sends /add_marker"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/add-marker!)
      (let [msg (receive-one! srv)]
        (is (= "/add_marker" (:address msg)))))))

(deftest osc-send-save-test
  (testing "save! sends /save_state"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/save!)
      (let [msg (receive-one! srv)]
        (is (= "/save_state" (:address msg)))))))

(deftest osc-send-track-recenable-test
  (testing "track-recenable! sends /route/recenable ssid 1/0"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/track-recenable! 2 true)
      (let [msg (receive-one! srv)]
        (is (= "/route/recenable" (:address msg)))
        (is (= [2 1] (:args msg)))))))

(deftest osc-send-track-gain-test
  (testing "track-gain! sends /route/gain ssid gain"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/track-gain! 1 1.0)
      (let [msg (receive-one! srv)]
        (is (= "/route/gain" (:address msg)))
        (is (= 1 (first (:args msg))))
        (is (< (Math/abs (- 1.0 (second (:args msg)))) 1e-5))))))

(deftest osc-send-track-fader-test
  (testing "track-fader! sends /route/fader ssid position"
    (let [srv (start-echo-server!)]
      (ardour/connect! "127.0.0.1" (:port srv))
      (ardour/track-fader! 3 0.8)
      (let [msg (receive-one! srv)]
        (is (= "/route/fader" (:address msg)))
        (is (= 3 (first (:args msg))))
        (is (< (Math/abs (- 0.8 (second (:args msg)))) 1e-5))))))
