; SPDX-License-Identifier: EPL-2.0
(ns cljseq.remote-test
  (:require [clojure.test   :refer [deftest is testing use-fixtures]]
            [cljseq.bencode :as bc]
            [cljseq.remote  :as remote]))

;; ---------------------------------------------------------------------------
;; Mock socket infrastructure
;; ---------------------------------------------------------------------------

(defn- make-pipe
  "Returns [server-in client-out] where bytes written to client-out appear
  on server-in. Backed by a PipedInputStream/PipedOutputStream pair."
  []
  (let [pin  (java.io.PipedInputStream.)
        pout (java.io.PipedOutputStream. pin)]
    [pin pout]))

(defn- make-mock-server
  "Build a mock nREPL server that handles `request-handler` in a background
  thread. Returns `{:conn-fn ...}` suitable for binding to
  `remote/*open-connection*`.

  `request-handler` is `(fn [req] -> [responses...])` — for each decoded
  request it should return a seq of response maps to send back."
  [request-handler]
  (let [;; client writes here → server reads from client-out-in
        [client-out-in  client-out]  (make-pipe)
        ;; server writes here → client reads from server-in
        [server-in      server-out]  (make-pipe)
        mock-socket (reify java.io.Closeable (close [_] nil))
        server-thread
        (doto (Thread.
               (fn []
                 (try
                   (loop []
                     (when-let [req (bc/decode-stream client-out-in)]
                       (doseq [resp (request-handler req)]
                         (bc/write-message resp server-out))
                       (recur)))
                   (catch Exception _))))
          (.setDaemon true)
          (.start))]
    {:conn-fn  (fn [_host _port]
                 {:socket mock-socket
                  :in     server-in
                  :out    client-out})
     :server-thread server-thread}))

;; ---------------------------------------------------------------------------
;; clone + connect
;; ---------------------------------------------------------------------------

(deftest connect-clones-session
  (let [{:keys [conn-fn]}
        (make-mock-server
         (fn [req]
           (if (= "clone" (:op req))
             [{:id (:id req) :new-session "sess-abc" :status ["done"]}]
             [])))]
    (binding [remote/*open-connection* conn-fn]
      (let [conn (remote/connect! "localhost" 7888)]
        (is (= "sess-abc" @(:session conn)))
        (remote/disconnect! conn)))))

;; ---------------------------------------------------------------------------
;; remote-eval! — success
;; ---------------------------------------------------------------------------

(deftest remote-eval-returns-value
  (let [{:keys [conn-fn]}
        (make-mock-server
         (fn [req]
           (condp = (:op req)
             "clone" [{:id (:id req) :new-session "s1" :status ["done"]}]
             "eval"  [{:id (:id req) :value "3"  :status ["done"]}]
             [])))]
    (binding [remote/*open-connection* conn-fn]
      (let [conn   (remote/connect! "localhost" 7888)
            result (remote/remote-eval! conn "(+ 1 2)")]
        (is (= "3" (:value result)))
        (is (contains? (:status result) :done))
        (remote/disconnect! conn)))))

;; ---------------------------------------------------------------------------
;; remote-eval! — multi-frame response (stdout + value + done)
;; ---------------------------------------------------------------------------

(deftest remote-eval-merges-multi-frame
  (let [{:keys [conn-fn]}
        (make-mock-server
         (fn [req]
           (condp = (:op req)
             "clone" [{:id (:id req) :new-session "s1" :status ["done"]}]
             "eval"  [{:id (:id req) :out "hello\n"}
                      {:id (:id req) :value "nil"}
                      {:id (:id req) :status ["done"]}]
             [])))]
    (binding [remote/*open-connection* conn-fn]
      (let [conn   (remote/connect! "localhost" 7888)
            result (remote/remote-eval! conn "(println \"hello\")")]
        (is (= "hello\n" (:out result)))
        (is (= "nil"     (:value result)))
        (is (contains? (:status result) :done))
        (remote/disconnect! conn)))))

;; ---------------------------------------------------------------------------
;; remote-eval! — error response
;; ---------------------------------------------------------------------------

(deftest remote-eval-error-response
  (let [{:keys [conn-fn]}
        (make-mock-server
         (fn [req]
           (condp = (:op req)
             "clone" [{:id (:id req) :new-session "s1" :status ["done"]}]
             "eval"  [{:id (:id req)
                       :ex  "class clojure.lang.ExceptionInfo"
                       :err "error msg\n"
                       :status ["eval-error" "done"]}]
             [])))]
    (binding [remote/*open-connection* conn-fn]
      (let [conn   (remote/connect! "localhost" 7888)
            result (remote/remote-eval! conn "(throw (ex-info \"oops\" {}))")]
        (is (contains? result :ex))
        (is (contains? (:status result) :done))
        (remote/disconnect! conn)))))

;; ---------------------------------------------------------------------------
;; with-peer macro
;; ---------------------------------------------------------------------------

(deftest with-peer-binds-conn
  (let [{:keys [conn-fn]}
        (make-mock-server
         (fn [req]
           (condp = (:op req)
             "clone" [{:id (:id req) :new-session "s1" :status ["done"]}]
             "eval"  [{:id (:id req) :value "42" :status ["done"]}]
             [])))]
    (binding [remote/*open-connection* conn-fn]
      (let [conn (remote/connect! "localhost" 7888)]
        (remote/with-peer conn
          (is (= "42" (:value (remote/remote-eval! remote/*conn* "42")))))
        (remote/disconnect! conn)))))

;; ---------------------------------------------------------------------------
;; :ns option forwarded in request
;; ---------------------------------------------------------------------------

(deftest remote-eval-sends-ns-option
  (let [received-ns (atom nil)
        {:keys [conn-fn]}
        (make-mock-server
         (fn [req]
           (condp = (:op req)
             "clone" [{:id (:id req) :new-session "s1" :status ["done"]}]
             "eval"  (do (reset! received-ns (:ns req))
                         [{:id (:id req) :value "ok" :status ["done"]}])
             [])))]
    (binding [remote/*open-connection* conn-fn]
      (let [conn (remote/connect! "localhost" 7888)]
        (remote/remote-eval! conn "1" {:ns "cljseq.peer"})
        (is (= "cljseq.peer" @received-ns))
        (remote/disconnect! conn)))))
