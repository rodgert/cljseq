; SPDX-License-Identifier: EPL-2.0
(ns cljseq.mcp-test
  (:require [clojure.test      :refer [deftest is testing]]
            [clojure.data.json :as json]
            [cljseq.mcp        :as mcp]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- mock-eval
  "Return a mock eval-fn that records calls and returns canned responses.
  `responses` is a map of code-str → result-map."
  [responses]
  (fn [code-str]
    (get responses code-str
         {:value "nil" :status #{:done}})))

(defn- run-messages
  "Run the MCP message loop against `messages` (a seq of JSON strings).
  Returns a vector of parsed JSON response maps.
  `eval-fn` is injected for nREPL eval."
  ([messages] (run-messages messages (constantly {:value "nil" :status #{:done}})))
  ([messages eval-fn]
   (let [output (atom [])
         lines  (atom messages)]
     (binding [mcp/*eval-fn* eval-fn
               mcp/*io-fns*  {:read-line  (fn []
                                             (let [[h & t] @lines]
                                               (reset! lines t)
                                               h))
                              :write-line (fn [s]
                                            (when-not (nil? s)
                                              (swap! output conj
                                                     (json/read-str s))))}]
       (loop []
         (when-let [line ((:read-line mcp/*io-fns*))]
           (when-not (clojure.string/blank? line)
             (mcp/process-message line))
           (recur))))
     @output)))

(defn- request
  "Build a JSON-RPC request string."
  ([id method] (request id method {}))
  ([id method params]
   (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params})))

(defn- notification
  "Build a JSON-RPC notification string (no id)."
  ([method] (notification method {}))
  ([method params]
   (json/write-str {"jsonrpc" "2.0" "method" method "params" params})))

;; ---------------------------------------------------------------------------
;; Protocol tests
;; ---------------------------------------------------------------------------

(deftest initialize-test
  (testing "initialize returns protocolVersion and serverInfo"
    (let [[resp] (run-messages [(request 0 "initialize"
                                         {"protocolVersion" "2024-11-05"
                                          "capabilities"    {}
                                          "clientInfo"      {"name" "test"}})])]
      (is (= "2.0" (get resp "jsonrpc")))
      (is (= 0     (get resp "id")))
      (let [result (get resp "result")]
        (is (= "2024-11-05" (get result "protocolVersion")))
        (is (= "cljseq"     (get-in result ["serverInfo" "name"])))
        (is (map?           (get-in result ["capabilities" "tools"])))))))

(deftest notification-no-response-test
  (testing "notifications/initialized produces no response"
    (let [responses (run-messages [(notification "notifications/initialized")])]
      (is (empty? responses)))))

(deftest ping-test
  (testing "ping returns empty result"
    (let [[resp] (run-messages [(request 1 "ping")])]
      (is (= 1     (get resp "id")))
      (is (= {}    (get resp "result"))))))

(deftest unknown-method-test
  (testing "unknown method returns -32601 error"
    (let [[resp] (run-messages [(request 2 "unknown/method")])]
      (is (= 2 (get resp "id")))
      (is (= -32601 (get-in resp ["error" "code"]))))))

;; ---------------------------------------------------------------------------
;; tools/list test
;; ---------------------------------------------------------------------------

(deftest tools-list-test
  (testing "tools/list returns the expected tool names"
    (let [[resp] (run-messages [(request 1 "tools/list")])]
      (let [names (->> (get-in resp ["result" "tools"])
                       (map #(get % "name"))
                       set)]
        (is (contains? names "evaluate"))
        (is (contains? names "get-ctrl"))
        (is (contains? names "set-ctrl"))
        (is (contains? names "get-harmony-ctx"))
        (is (contains? names "get-spectral"))
        (is (contains? names "get-loops"))
        (is (contains? names "define-loop"))
        (is (contains? names "stop-loop"))
        (is (contains? names "play-note"))
        (is (contains? names "set-bpm"))
        (is (contains? names "get-config"))
        (is (contains? names "set-config"))
        (is (contains? names "get-score")))))

  (testing "each tool has name, description, and inputSchema"
    (let [[resp]  (run-messages [(request 1 "tools/list")])
          tools   (get-in resp ["result" "tools"])]
      (doseq [t tools]
        (is (string? (get t "name"))        (str "missing name: " t))
        (is (string? (get t "description")) (str "missing description: " t))
        (is (map?    (get t "inputSchema")) (str "missing inputSchema: " t))))))

;; ---------------------------------------------------------------------------
;; tools/call — evaluate
;; ---------------------------------------------------------------------------

(deftest evaluate-success-test
  (testing "evaluate returns the value from nREPL eval"
    (let [eval-fn (mock-eval {"(+ 1 2)" {:value "3" :status #{:done}}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "evaluate" "arguments" {"code" "(+ 1 2)"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (= "3"  (get-in resp ["result" "content" 0 "text"]))))))

(deftest evaluate-error-test
  (testing "evaluate returns isError true when nREPL returns an exception"
    (let [eval-fn (mock-eval {"(/ 1 0)"
                              {:ex     "java.lang.ArithmeticException"
                               :err    "Divide by zero"
                               :status #{:done :error}}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "evaluate" "arguments" {"code" "(/ 1 0)"}})]
                   eval-fn)]
      (is (true? (get-in resp ["result" "isError"])))
      (let [text (get-in resp ["result" "content" 0 "text"])]
        (is (string? text))
        (is (re-find #"ArithmeticException|Divide" text))))))

(deftest evaluate-with-out-test
  (testing "evaluate includes stdout in the response"
    (let [eval-fn (mock-eval {"(println \"hello\")"
                              {:value "nil" :out "hello\n" :status #{:done}}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "evaluate"
                              "arguments" {"code" "(println \"hello\")"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (let [text (get-in resp ["result" "content" 0 "text"])]
        (is (re-find #"hello" text))))))

;; ---------------------------------------------------------------------------
;; tools/call — named tools
;; ---------------------------------------------------------------------------

(deftest get-ctrl-test
  (testing "get-ctrl evals the correct ctrl/get-in form"
    (let [called  (atom nil)
          eval-fn (fn [code]
                    (reset! called code)
                    {:value "{:bpm 120}" :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "get-ctrl"
                              "arguments" {"path" "[:config :bpm]"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"ctrl/get-in" @called))
      (is (re-find #"\[:config :bpm\]" @called)))))

(deftest set-ctrl-test
  (testing "set-ctrl evals a ctrl/set-in! form"
    (let [called  (atom nil)
          eval-fn (fn [code]
                    (reset! called code)
                    {:value ":ok" :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "set-ctrl"
                              "arguments" {"path" "[:config :bpm]" "value" "140"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"set-in!" @called)))))

(deftest get-harmony-ctx-test
  (testing "get-harmony-ctx evals *harmony-ctx*"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value "{}" :status #{:done}})
          _       (run-messages
                   [(request 1 "tools/call"
                             {"name" "get-harmony-ctx" "arguments" {}})]
                   eval-fn)]
      (is (re-find #"\*harmony-ctx\*" @called)))))

(deftest set-bpm-test
  (testing "set-bpm evals set-bpm! with the given number"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value ":ok" :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "set-bpm" "arguments" {"bpm" 140}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"set-bpm!" @called))
      (is (re-find #"140" @called)))))

(deftest define-loop-test
  (testing "define-loop builds a deflive-loop form"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value ":loop-name" :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name"      "define-loop"
                              "arguments" {"name" ":bass"
                                           "opts" "{:bar 4}"
                                           "body" "(play! {:pitch :C2 :dur 1})"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"deflive-loop" @called))
      (is (re-find #":bass"        @called))
      (is (re-find #":bar 4"       @called)))))

(deftest stop-loop-test
  (testing "stop-loop evals stop! with the given name"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value "nil" :status #{:done}})
          _       (run-messages
                   [(request 1 "tools/call"
                             {"name" "stop-loop" "arguments" {"name" ":bass"}})]
                   eval-fn)]
      (is (re-find #"stop!" @called))
      (is (re-find #":bass" @called)))))

(deftest play-note-test
  (testing "play-note evals play! with the event map"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value "nil" :status #{:done}})
          _       (run-messages
                   [(request 1 "tools/call"
                             {"name"      "play-note"
                              "arguments" {"event" "{:pitch :C4 :dur 1/4}"}})]
                   eval-fn)]
      (is (re-find #"play!" @called))
      (is (re-find #":C4"   @called)))))

;; ---------------------------------------------------------------------------
;; Composition pipeline tools
;; ---------------------------------------------------------------------------

(deftest ingest-midi-test
  (testing "ingest-midi evals ingest! and binds composition"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code)
                    {:value (pr-str {:key "G" :mode "ionian" :tempo 110.0
                                     :bars 32 :voices {} :peak-tension-bar 28
                                     :peak-tension 0.95 :corrections 0})
                     :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name"      "ingest-midi"
                              "arguments" {"path" "/tmp/test.mid"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"ingest!" @called))
      (is (re-find #"/tmp/test.mid" @called))
      (is (re-find #"def composition" @called))))

  (testing "ingest-midi passes structure and resolution opts when provided"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value "{}" :status #{:done}})
          _       (run-messages
                   [(request 1 "tools/call"
                             {"name"      "ingest-midi"
                              "arguments" {"path"       "/tmp/test.mid"
                                           "structure"  "ndlr"
                                           "resolution" "authentic-cadence"
                                           "resolution-bars" 8}})]
                   eval-fn)]
      (is (re-find #":ndlr"               @called))
      (is (re-find #":authentic-cadence"  @called))
      (is (re-find #"8"                   @called)))))

(deftest show-corrections-test
  (testing "show-corrections evals print-corrections on composition"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value "No corrections." :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "show-corrections" "arguments" {}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"print-corrections" @called))
      (is (re-find #"composition"       @called)))))

(deftest accept-corrections-test
  (testing "accept-corrections evals accept-corrections and rebinds composition"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code)
                    {:value (pr-str {:key "G" :mode "ionian" :bars 32
                                     :corrections-applied :ok})
                     :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "accept-corrections" "arguments" {}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"accept-corrections" @called))
      (is (re-find #"def composition"    @called)))))

(deftest save-score-test
  (testing "save-score evals save-score with the given path"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code)
                    {:value (pr-str {:saved "/tmp/out.edn"}) :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name"      "save-score"
                              "arguments" {"path" "/tmp/out.edn"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"save-score"   @called))
      (is (re-find #"/tmp/out.edn" @called)))))

(deftest tools-list-includes-composition-tools-test
  (testing "tools/list includes the composition pipeline tools"
    (let [[resp] (run-messages [(request 1 "tools/list")])]
      (let [names (->> (get-in resp ["result" "tools"])
                       (map #(get % "name"))
                       set)]
        (is (contains? names "ingest-midi"))
        (is (contains? names "show-corrections"))
        (is (contains? names "accept-corrections"))
        (is (contains? names "play-score"))
        (is (contains? names "save-score"))))))

;; ---------------------------------------------------------------------------
;; Peer tools
;; ---------------------------------------------------------------------------

(deftest list-peers-test
  (testing "list-peers evals cljseq.peer/peers"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value "{:ubuntu {}}" :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name" "list-peers" "arguments" {}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"peer/peers" @called)))))

(deftest evaluate-on-peer-test
  (testing "evaluate-on-peer proxies through eval-on-peer!"
    (let [called  (atom nil)
          eval-fn (fn [code] (reset! called code) {:value "\"42\"" :status #{:done}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name"      "evaluate-on-peer"
                              "arguments" {"peer" ":ubuntu"
                                           "code" "(+ 20 22)"}})]
                   eval-fn)]
      (is (false? (get-in resp ["result" "isError"])))
      (is (re-find #"eval-on-peer!" @called))
      (is (re-find #":ubuntu"       @called))
      (is (re-find #"20 22" @called))))

  (testing "evaluate-on-peer surfaces peer errors as isError"
    (let [eval-fn (fn [_] {:ex     "clojure.lang.ExceptionInfo"
                           :err    "peer eval error: ..."
                           :status #{:done :error}})
          [resp]  (run-messages
                   [(request 1 "tools/call"
                             {"name"      "evaluate-on-peer"
                              "arguments" {"peer" ":offline-node"
                                           "code" "(+ 1 1)"}})]
                   eval-fn)]
      (is (true? (get-in resp ["result" "isError"]))))))

(deftest tools-list-includes-peer-tools-test
  (testing "tools/list includes list-peers and evaluate-on-peer"
    (let [[resp] (run-messages [(request 1 "tools/list")])]
      (let [names (->> (get-in resp ["result" "tools"])
                       (map #(get % "name"))
                       set)]
        (is (contains? names "list-peers"))
        (is (contains? names "evaluate-on-peer"))))))

(deftest unknown-tool-test
  (testing "calling an unknown tool returns isError true"
    (let [[resp] (run-messages
                  [(request 1 "tools/call"
                            {"name" "nonexistent-tool" "arguments" {}})])]
      (is (true? (get-in resp ["result" "isError"]))))))

;; ---------------------------------------------------------------------------
;; Multiple messages in one session
;; ---------------------------------------------------------------------------

(deftest multi-message-session-test
  (testing "multiple requests in one session all get responses"
    (let [responses (run-messages
                     [(request 1 "tools/list")
                      (request 2 "ping")
                      (notification "notifications/initialized")
                      (request 3 "tools/call"
                               {"name" "evaluate"
                                "arguments" {"code" "(+ 1 1)"}})]
                     (constantly {:value "2" :status #{:done}}))]
      ;; 3 responses: tools/list, ping, evaluate (notification has no response)
      (is (= 3 (count responses)))
      (is (= 1 (get (first  responses) "id")))
      (is (= 2 (get (second responses) "id")))
      (is (= 3 (get (nth responses 2) "id"))))))

;; ---------------------------------------------------------------------------
;; parse-args (via -main path — test only parse logic)
;; ---------------------------------------------------------------------------

(deftest parse-args-test
  (testing "parse-args extracts key-value pairs"
    ;; Access parse-args via the public surface (it drives -main)
    ;; Verified indirectly: -main uses it to select host/port
    ;; Direct test via call to private fn using var reference
    (let [parse-args @#'cljseq.mcp/parse-args]
      (is (= {"nrepl-host" "10.0.0.1" "nrepl-port" "9999"}
             (parse-args ["--nrepl-host" "10.0.0.1" "--nrepl-port" "9999"])))
      (is (= {}
             (parse-args []))))))
