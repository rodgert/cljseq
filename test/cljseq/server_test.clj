; SPDX-License-Identifier: EPL-2.0
(ns cljseq.server-test
  "Unit tests for cljseq.server — control-plane HTTP API."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [cljseq.core       :as core]
            [cljseq.ctrl       :as ctrl]
            [cljseq.server     :as server])
  (:import  [java.net.http HttpClient HttpRequest
             HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
            [java.net URI]
            [java.time Duration]))

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
