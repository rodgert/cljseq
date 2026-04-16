; SPDX-License-Identifier: EPL-2.0
(ns cljseq.server-test
  "Unit tests for cljseq.server — control-plane HTTP API and WebSocket broadcast."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [clojure.string    :as str]
            [clojure.data.json :as json]
            [cljseq.core       :as core]
            [cljseq.ctrl       :as ctrl]
            [cljseq.peer       :as peer]
            [cljseq.server     :as server])
  (:import  [java.net.http HttpClient HttpRequest
             HttpRequest$BodyPublishers HttpResponse$BodyHandlers
             WebSocket WebSocket$Listener]
            [java.net URI]
            [java.time Duration]
            [java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit]))

;; ---------------------------------------------------------------------------
;; Fixtures and HTTP helpers
;; ---------------------------------------------------------------------------

(def ^:private test-port 7278)
(def ^:private base (str "http://localhost:" test-port))

(defn- client [] (HttpClient/newHttpClient))

(defn- http-get [path]
  (let [req (.build (doto (HttpRequest/newBuilder)
                      (.uri (URI/create (str base path)))
                      (.GET)
                      (.timeout (Duration/ofSeconds 5))))]
    (let [resp (.send (client) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp)
       :body   (json/read-str (.body resp))})))

(defn- http-put [path body-map]
  (let [body-str (json/write-str body-map)
        req      (.build (doto (HttpRequest/newBuilder)
                           (.uri (URI/create (str base path)))
                           (.PUT (HttpRequest$BodyPublishers/ofString body-str))
                           (.header "Content-Type" "application/json")
                           (.timeout (Duration/ofSeconds 5))))]
    (let [resp (.send (client) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp)
       :body   (json/read-str (.body resp))})))

(defn with-system [f]
  (core/start! :bpm 120)
  (server/start-server! :port test-port)
  (Thread/sleep 20)  ; brief settle for server bind
  (try (f)
       (finally
         (server/stop-server!)
         (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; GET /ping
;; ---------------------------------------------------------------------------

(deftest ping-test
  (testing "GET /ping returns 200 with ok:true"
    (let [{:keys [status body]} (http-get "/ping")]
      (is (= 200 status))
      (is (= true (get body "ok"))))))

;; ---------------------------------------------------------------------------
;; GET/PUT /bpm
;; ---------------------------------------------------------------------------

(deftest bpm-get-test
  (testing "GET /bpm returns current BPM"
    (let [{:keys [status body]} (http-get "/bpm")]
      (is (= 200 status))
      (is (= 120 (get body "bpm"))))))

(deftest bpm-put-test
  (testing "PUT /bpm changes BPM and GET /bpm reflects it"
    (let [put-resp (http-put "/bpm" {"bpm" 140})]
      (is (= 200 (:status put-resp)))
      (is (= true (get (:body put-resp) "ok"))))
    (let [{:keys [status body]} (http-get "/bpm")]
      (is (= 200 status))
      (is (= 140.0 (get body "bpm"))))))

(deftest bpm-put-missing-field-test
  (testing "PUT /bpm without bpm field returns 400"
    (let [{:keys [status body]} (http-put "/bpm" {"tempo" 120})]
      (is (= 400 status))
      (is (string? (get body "error"))))))

;; ---------------------------------------------------------------------------
;; GET /ctrl/<path>
;; ---------------------------------------------------------------------------

(deftest ctrl-get-missing-test
  (testing "GET /ctrl/<unknown> returns 404"
    (let [{:keys [status body]} (http-get "/ctrl/no/such/path")]
      (is (= 404 status))
      (is (string? (get body "error"))))))

(deftest ctrl-get-existing-test
  (testing "GET /ctrl/<path> returns value and type for a known node"
    (ctrl/defnode! [:server-test/cutoff] :type :float :value 0.5)
    (let [{:keys [status body]} (http-get "/ctrl/server-test%2Fcutoff")]
      (is (= 200 status))
      (is (= 0.5  (get body "value")))
      (is (= "float" (get body "type")))
      (is (= ["server-test/cutoff"] (get body "path"))))))

(deftest ctrl-get-nested-path-test
  (testing "GET /ctrl/loops/bass/vel reads a nested ctrl path"
    (ctrl/set! [:loops :bass :vel] 80)
    (let [{:keys [status body]} (http-get "/ctrl/loops/bass/vel")]
      (is (= 200 status))
      (is (= 80 (get body "value")))
      (is (= ["loops" "bass" "vel"] (get body "path"))))))

;; ---------------------------------------------------------------------------
;; PUT /ctrl/<path>
;; ---------------------------------------------------------------------------

(deftest ctrl-put-and-get-test
  (testing "PUT then GET roundtrips a numeric value"
    (ctrl/defnode! [:server-test/gain] :type :float :value 1.0)
    (let [put-resp (http-put "/ctrl/server-test%2Fgain" {"value" 0.75})]
      (is (= 200 (:status put-resp)))
      (is (= true (get (:body put-resp) "ok"))))
    (let [{:keys [status body]} (http-get "/ctrl/server-test%2Fgain")]
      (is (= 200 status))
      (is (= 0.75 (get body "value"))))))

(deftest ctrl-put-missing-value-test
  (testing "PUT /ctrl/<path> without value field returns 400"
    (ctrl/defnode! [:server-test/x] :type :float :value 0.0)
    (let [{:keys [status body]} (http-put "/ctrl/server-test%2Fx" {"wrong" 1})]
      (is (= 400 status))
      (is (string? (get body "error"))))))

(deftest ctrl-put-creates-node-test
  (testing "PUT /ctrl/<path> creates node if it does not exist"
    (let [put-resp (http-put "/ctrl/server-test/new-key" {"value" 42})]
      (is (= 200 (:status put-resp))))
    (is (= 42 (ctrl/get [:server-test :new-key])))))

;; ---------------------------------------------------------------------------
;; GET /ctrl (full dump)
;; ---------------------------------------------------------------------------

(deftest ctrl-dump-test
  (testing "GET /ctrl returns a JSON array of node entries"
    (ctrl/defnode! [:server-test/dump-node] :type :int :value 7)
    (let [{:keys [status body]} (http-get "/ctrl")]
      (is (= 200 status))
      (is (vector? body))
      (let [entry (first (filter #(= ["server-test/dump-node"]
                                     (get % "path"))
                                 body))]
        (is (some? entry) "dump contains the registered node")
        (is (= 7     (get entry "value")))
        (is (= "int" (get entry "type")))))))

;; ---------------------------------------------------------------------------
;; Unknown route
;; ---------------------------------------------------------------------------

(deftest unknown-route-test
  (testing "GET /unknown returns 404"
    (let [{:keys [status body]} (http-get "/unknown")]
      (is (= 404 status))
      (is (string? (get body "error"))))))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(deftest server-running-predicate-test
  (testing "server-running? returns true while started"
    (is (server/server-running?)))
  (testing "server-port returns the configured port"
    (is (= test-port (server/server-port)))))

(deftest server-idempotent-restart-test
  (testing "start-server! stops existing server and starts fresh"
    (server/start-server! :port test-port)  ; re-start on same port
    (Thread/sleep 20)
    (let [{:keys [status]} (http-get "/ping")]
      (is (= 200 status)))))

;; ---------------------------------------------------------------------------
;; Peer-facing ctrl paths — integration tests
;; ---------------------------------------------------------------------------

(deftest harmony-ctx-peer-path-test
  (testing "GET /ctrl/ensemble/harmony-ctx returns serialized harmony context"
    ;; Write a serialized ctx directly to the ctrl tree (as ensemble-improv would)
    (let [ctx {:harmony/root      "D"
               :harmony/octave    3
               :harmony/intervals [2 1 2 2 2 1 2]
               :harmony/tension   0.65
               :ensemble/density  0.4}]
      (ctrl/set! [:ensemble :harmony-ctx] ctx))
    (let [{:keys [status body]} (http-get "/ctrl/ensemble/harmony-ctx")]
      (is (= 200 status))
      (let [v (get body "value")]
        ;; ->json-safe converts keywords via (name k), so :harmony/root → "root"
        (is (map? v))
        (is (= "D"             (get v "root")))
        (is (= 3               (get v "octave")))
        (is (= [2 1 2 2 2 1 2] (get v "intervals")))
        (is (= 0.65            (get v "tension")))))))

(deftest harmony-ctx-roundtrip-test
  (testing "Harmony ctx served by HTTP can be reconstructed via serial->scale"
    (let [ctx {:harmony/root      "C"
               :harmony/octave    4
               :harmony/intervals [2 2 1 2 2 2 1]}]
      (ctrl/set! [:ensemble :harmony-ctx] ctx))
    (let [{:keys [status body]} (http-get "/ctrl/ensemble/harmony-ctx")
          v (get body "value")]
      (is (= 200 status))
      ;; ->json-safe converts :harmony/root → "root" etc. via (name k)
      ;; peer/serial->scale expects :harmony/root etc., so re-qualify
      (let [scale (peer/serial->scale
                    {:harmony/root      (get v "root")
                     :harmony/octave    (get v "octave")
                     :harmony/intervals (get v "intervals")})]
        (is (some? scale) "serial->scale reconstructs a Scale record")))))

(deftest spectral-state-peer-path-test
  (testing "GET /ctrl/spectral/state returns serialized spectral state"
    (let [state {:spectral/centroid 880.0
                 :spectral/flux     0.3
                 :spectral/density  0.7}]
      (ctrl/set! [:spectral :state] state))
    (let [{:keys [status body]} (http-get "/ctrl/spectral/state")]
      (is (= 200 status))
      (let [v (get body "value")]
        ;; ->json-safe converts :spectral/centroid → "centroid" etc.
        (is (map? v))
        (is (= 880.0 (get v "centroid")))
        (is (= 0.3   (get v "flux")))
        (is (= 0.7   (get v "density")))))))

;; ---------------------------------------------------------------------------
;; WebSocket — connect and broadcast
;; ---------------------------------------------------------------------------

(defn- ws-connect
  "Open a WebSocket connection to /ws on the test server.
  Returns [ws-ref, messages-queue] where messages-queue is a LinkedBlockingQueue
  that receives decoded JSON maps for each text message received."
  []
  (let [queue    (LinkedBlockingQueue.)
        ws-ref   (atom nil)
        listener (proxy [WebSocket$Listener] []
                   (onOpen [ws]
                     (reset! ws-ref ws)
                     (.request ws 10))
                   (onText [ws data _last?]
                     (.offer queue (json/read-str (str data)))
                     (.request ws 1)
                     (CompletableFuture/completedFuture nil))
                   (onClose [_ws _code _reason]
                     (CompletableFuture/completedFuture nil))
                   (onError [_ws _err]
                     nil))
        _ws      (.join (.buildAsync
                          (.newWebSocketBuilder (HttpClient/newHttpClient))
                          (URI/create (str "ws://localhost:" test-port "/ws"))
                          listener))]
    ;; Brief settle for the on-open registration to complete on the server
    (Thread/sleep 30)
    [@ws-ref queue]))

(deftest ws-connect-and-broadcast-test
  (testing "ctrl/set! after WS connect delivers a JSON broadcast to the client"
    (let [[ws queue] (ws-connect)]
      (try
        (ctrl/set! [:ws-test :param] 42)
        (let [msg (.poll queue 2 TimeUnit/SECONDS)]
          (is (some? msg) "message received within 2 s")
          (is (= ["ws-test" "param"] (get msg "path")))
          (is (= 42 (get msg "value"))))
        (finally
          (.sendClose ws WebSocket/NORMAL_CLOSURE "done"))))))

(deftest ws-inbound-ctrl-write-test
  (testing "JSON sent over WebSocket applies ctrl/set! on the server"
    (let [[ws _queue] (ws-connect)]
      (try
        (.sendText ws (json/write-str {"path" ["ws-in" "val"] "value" 99}) true)
        (Thread/sleep 50)  ; allow server-side set! to complete
        (is (= 99 (ctrl/get [:ws-in :val])) "inbound WS write applied to ctrl tree")
        (finally
          (.sendClose ws WebSocket/NORMAL_CLOSURE "done"))))))

(deftest ws-disconnect-removes-channel-test
  (testing "disconnecting a WS client stops it receiving broadcasts"
    (let [[ws queue] (ws-connect)]
      ;; Close the connection
      (.sendClose ws WebSocket/NORMAL_CLOSURE "bye")
      (Thread/sleep 50)  ; allow server to process close
      ;; This set! should not arrive in the closed client's queue
      (ctrl/set! [:ws-disc/param] :gone)
      (let [msg (.poll queue 300 TimeUnit/MILLISECONDS)]
        (is (nil? msg) "no message received after disconnect")))))

;; ---------------------------------------------------------------------------
;; Static file serving — control surface
;; ---------------------------------------------------------------------------

(defn- http-get-raw [path]
  (let [req (.build (doto (HttpRequest/newBuilder)
                      (.uri (URI/create (str base path)))
                      (.GET)
                      (.timeout (Duration/ofSeconds 5))))]
    (let [resp (.send (client) req (HttpResponse$BodyHandlers/ofString))]
      {:status  (.statusCode resp)
       :headers (into {} (.map (.headers resp)))
       :body    (.body resp)})))

(defn- content-type
  "Extract the first Content-Type value from a header map.
  Java HttpClient returns header values as lists."
  [headers]
  (first (get headers "content-type" [""])))

(deftest control-surface-html-test
  (testing "GET / serves the control surface HTML with text/html content-type"
    (let [{:keys [status headers body]} (http-get-raw "/")]
      (is (= 200 status))
      (is (str/starts-with? (content-type headers) "text/html"))
      (is (str/includes? body "<div id=\"app\">")))))

(deftest control-surface-css-test
  (testing "GET /css/style.css serves the stylesheet"
    (let [{:keys [status headers]} (http-get-raw "/css/style.css")]
      (is (= 200 status))
      (is (str/starts-with? (content-type headers) "text/css")))))

(deftest control-surface-js-missing-test
  (testing "GET /js/main.js returns 404 when the CLJS build has not run"
    ;; resources/public/js/ is gitignored and not present in the test classpath
    ;; until shadow-cljs compiles. A 404 here is the expected/correct response.
    (let [{:keys [status]} (http-get-raw "/js/main.js")]
      (is (= 404 status)))))
