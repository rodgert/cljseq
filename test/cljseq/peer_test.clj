; SPDX-License-Identifier: EPL-2.0
(ns cljseq.peer-test
  "Tests for cljseq.peer — node identity, beacon format, peer registry,
  ctrl-tree mounting, and ImprovisationContext serialization helpers.

  Network IO (UDP sockets, HTTP) is tested via the *http-get* dynamic binding
  and direct atom manipulation; no live network is required."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [clojure.edn   :as edn]
            [cljseq.peer   :as peer]
            [cljseq.pitch  :as pitch-ns]
            [cljseq.scale  :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Helpers — private atom access
;; ---------------------------------------------------------------------------

(defn- profile-atom    [] @#'peer/node-profile-atom)
(defn- registry-atom  [] @#'peer/peer-registry)
(defn- mount-atom     [] @#'peer/mount-registry)
(defn- disc-atom      [] @#'peer/discovery-state)

(defn- set-registry!  [v] (reset! (registry-atom) v))
(defn- set-mount!     [v] (reset! (mount-atom)    v))

;; ---------------------------------------------------------------------------
;; Fixture — reset all state between tests
;; ---------------------------------------------------------------------------

(defn- backend-atom [] @#'peer/backend-registry)
(defn- set-backends! [v] (reset! (backend-atom) v))

(use-fixtures :each
  (fn [t]
    ;; Save and restore profile so tests do not bleed into each other
    (let [saved @(profile-atom)]
      (reset! (profile-atom) {:node-id    :local
                               :role       :main
                               :http-port  7177
                               :nrepl-port 7888
                               :version    "0.4.0"})
      (set-registry! {})
      (set-mount!    {})
      (set-backends! {})
      (t)
      (set-registry! {})
      (set-mount!    {})
      (set-backends! {})
      (reset! (profile-atom) saved))))

;; ---------------------------------------------------------------------------
;; Node profile API
;; ---------------------------------------------------------------------------

(deftest set-node-profile-merges
  (testing "set-node-profile! merges into existing profile"
    (peer/set-node-profile! {:node-id :mac-mini :role :satellite})
    (let [p (peer/node-profile)]
      (is (= :mac-mini   (:node-id p)))
      (is (= :satellite  (:role p)))
      ;; Unchanged defaults should still be present
      (is (= 7177        (:http-port p))))))

(deftest node-id-reflects-profile
  (testing "node-id returns :node-id from current profile"
    (peer/set-node-profile! {:node-id :rpi})
    (is (= :rpi (peer/node-id)))))

(deftest node-profile-returns-full-map
  (testing "node-profile returns the complete profile map"
    (peer/set-node-profile! {:node-id :ubuntu :http-port 7200})
    (let [p (peer/node-profile)]
      (is (= :ubuntu (:node-id p)))
      (is (= 7200    (:http-port p)))
      (is (contains? p :version)))))

;; ---------------------------------------------------------------------------
;; Beacon payload format
;; ---------------------------------------------------------------------------

(deftest beacon-payload-is-valid-edn
  (testing "beacon-payload returns valid EDN parseable to a map"
    (peer/set-node-profile! {:node-id :test-node :http-port 9000})
    (let [payload (#'peer/beacon-payload)
          parsed  (edn/read-string payload)]
      (is (map? parsed))
      (is (= :test-node (:node-id parsed)))
      (is (= 9000       (:http-port parsed)))
      (is (number?      (:timestamp-ms parsed))))))

(deftest beacon-payload-has-required-keys
  (testing "beacon-payload contains all keys required by the discovery protocol"
    (peer/set-node-profile! {:node-id :mac-mini :role :main :http-port 7177})
    (let [parsed (edn/read-string (#'peer/beacon-payload))]
      (doseq [k [:node-id :role :http-port :version :timestamp-ms]]
        (is (contains? parsed k) (str "missing key: " k))))))

;; ---------------------------------------------------------------------------
;; Peer registry — direct atom manipulation
;; ---------------------------------------------------------------------------

(deftest peers-returns-registry-snapshot
  (testing "peers returns a snapshot of the peer registry"
    (set-registry! {:ubuntu {:node-id :ubuntu :host "192.168.1.10" :http-port 7177
                              :last-seen-ms (System/currentTimeMillis)}})
    (let [r (peer/peers)]
      (is (contains? r :ubuntu))
      (is (= "192.168.1.10" (get-in r [:ubuntu :host]))))))

(deftest peer-info-returns-entry
  (testing "peer-info returns info for a known peer"
    (set-registry! {:rpi {:node-id :rpi :host "10.0.0.2" :http-port 7177
                           :last-seen-ms (System/currentTimeMillis)}})
    (let [info (peer/peer-info :rpi)]
      (is (= :rpi        (:node-id info)))
      (is (= "10.0.0.2"  (:host info))))))

(deftest peer-info-returns-nil-for-unknown
  (testing "peer-info returns nil for a node not in the registry"
    (is (nil? (peer/peer-info :unknown-node)))))

(deftest peers-empty-when-registry-clear
  (testing "peers returns empty map when registry is empty"
    (is (empty? (peer/peers)))))

;; ---------------------------------------------------------------------------
;; handle-beacon! — beacon parsing (no network required)
;; ---------------------------------------------------------------------------

(deftest handle-beacon-ignores-self
  (testing "handle-beacon! does not add self to the registry"
    (peer/set-node-profile! {:node-id :self-node})
    ;; Simulate a packet from self
    (let [data (.getBytes (pr-str {:node-id    :self-node
                                   :http-port  7177
                                   :role       :main
                                   :timestamp-ms 0})
                          java.nio.charset.StandardCharsets/UTF_8)
          addr (java.net.InetAddress/getByName "127.0.0.1")
          pkt  (java.net.DatagramPacket. data (count data) addr 7743)]
      (#'peer/handle-beacon! pkt)
      (is (not (contains? (peer/peers) :self-node))
          "self should not appear in the peer registry"))))

(deftest handle-beacon-adds-peer
  (testing "handle-beacon! adds a foreign peer to the registry"
    (peer/set-node-profile! {:node-id :mac-mini})
    (let [data (.getBytes (pr-str {:node-id   :ubuntu
                                   :http-port 7177
                                   :role      :satellite
                                   :version   "0.4.0"
                                   :timestamp-ms (System/currentTimeMillis)})
                          java.nio.charset.StandardCharsets/UTF_8)
          addr (java.net.InetAddress/getByName "192.168.1.10")
          pkt  (java.net.DatagramPacket. data (count data) addr 7743)]
      (#'peer/handle-beacon! pkt)
      (let [info (peer/peer-info :ubuntu)]
        (is (some? info))
        (is (= :ubuntu         (:node-id info)))
        (is (= 7177            (:http-port info)))
        (is (= "192.168.1.10"  (:host info)))
        (is (number?           (:last-seen-ms info)))))))

;; ---------------------------------------------------------------------------
;; expire-stale-peers! — time-based eviction
;; ---------------------------------------------------------------------------

(deftest expire-stale-removes-old-peers
  (testing "expire-stale-peers! removes peers beyond the timeout window"
    (set-registry! {:stale {:node-id :stale :host "1.2.3.4" :http-port 7177
                              :last-seen-ms 0}             ; epoch — definitely stale
                    :fresh {:node-id :fresh :host "5.6.7.8" :http-port 7177
                              :last-seen-ms (System/currentTimeMillis)}})
    (#'peer/expire-stale-peers!)
    (is (not (contains? (peer/peers) :stale)))
    (is (contains? (peer/peers) :fresh))))

;; ---------------------------------------------------------------------------
;; ctx->serial — ImprovisationContext serialization
;; ---------------------------------------------------------------------------

(deftest ctx-serial-removes-record-fields
  (testing "ctx->serial drops :harmony/key, :harmony/chord, :harmony/pcs"
    (let [s   (scale-ns/scale :D 3 :dorian)
          ctx {:harmony/key    s
               :harmony/chord  {:root :A :quality :min7}
               :harmony/pcs    {2 4 9 3}
               :harmony/tension 0.65}
          ser (peer/ctx->serial ctx)]
      (is (not (contains? ser :harmony/key)))
      (is (not (contains? ser :harmony/chord)))
      (is (not (contains? ser :harmony/pcs))))))

(deftest ctx-serial-encodes-scale-as-primitives
  (testing "ctx->serial encodes Scale as root/octave/intervals primitives"
    (let [s   (scale-ns/scale :D 3 :dorian)
          ctx {:harmony/key s :harmony/tension 0.65}
          ser (peer/ctx->serial ctx)]
      (is (= "D"               (:harmony/root ser)))
      (is (= 3                 (:harmony/octave ser)))
      (is (= [2 1 2 2 2 1 2]  (:harmony/intervals ser))))))

(deftest ctx-serial-passes-through-primitives
  (testing "ctx->serial preserves :harmony/tension, :ensemble/* and other primitives"
    (let [ctx {:harmony/key     (scale-ns/scale :C 4 :major)
               :harmony/tension  0.3
               :harmony/mode-conf 0.9
               :ensemble/density  0.5
               :ensemble/register :mid}
          ser (peer/ctx->serial ctx)]
      (is (= 0.3  (:harmony/tension ser)))
      (is (= 0.9  (:harmony/mode-conf ser)))
      (is (= 0.5  (:ensemble/density ser)))
      (is (= :mid (:ensemble/register ser))))))

(deftest ctx-serial-no-key-passthrough
  (testing "ctx->serial handles ctx without :harmony/key gracefully"
    (let [ctx {:ensemble/density 0.4 :ensemble/register :low}
          ser (peer/ctx->serial ctx)]
      (is (= 0.4  (:ensemble/density ser)))
      (is (not (contains? ser :harmony/root))))))

;; ---------------------------------------------------------------------------
;; serial->scale — reconstruction from serialized form
;; ---------------------------------------------------------------------------

(deftest serial->scale-round-trips
  (testing "serial->scale reconstructs the original Scale from serialized form"
    (let [orig (scale-ns/scale :D 3 :dorian)
          ctx  {:harmony/key orig :harmony/tension 0.65}
          ser  (peer/ctx->serial ctx)
          back (peer/serial->scale ser)]
      (is (some? back))
      (is (= :D  (-> back :root :step)))
      (is (= 3   (-> back :root :octave)))
      (is (= [2 1 2 2 2 1 2] (:intervals back))))))

(deftest serial->scale-returns-nil-on-missing-keys
  (testing "serial->scale returns nil when required keys are absent"
    (is (nil? (peer/serial->scale {})))
    (is (nil? (peer/serial->scale {:harmony/root "D"})))))  ; missing octave + intervals

(deftest serial->scale-produces-functional-scale
  (testing "reconstructed Scale supports scale-pitches"
    (let [orig  (scale-ns/scale :C 4 :major)
          ctx   {:harmony/key orig}
          back  (peer/serial->scale (peer/ctx->serial ctx))
          orig-pitches (scale-ns/scale-pitches orig)
          back-pitches (scale-ns/scale-pitches back)]
      (is (= (count orig-pitches) (count back-pitches)))
      (is (= (map (comp :step) orig-pitches)
             (map (comp :step) back-pitches))))))

;; ---------------------------------------------------------------------------
;; keywordize-keys — JSON key re-hydration
;; ---------------------------------------------------------------------------

(deftest keywordize-keys-converts-strings
  (testing "keywordize-keys converts string keys to keywords recursively"
    (let [m {"harmony/root" "D" "harmony/octave" 3
              "nested" {"key" "val"}}
          r (#'peer/keywordize-keys m)]
      (is (= "D" (:harmony/root r)))
      (is (= 3   (:harmony/octave r)))
      (is (= "val" (get-in r [:nested :key]))))))

(deftest keywordize-keys-handles-vectors
  (testing "keywordize-keys recurses into sequential values"
    (let [v [{"a" 1} {"b" 2}]
          r (#'peer/keywordize-keys v)]
      (is (= 1 (:a (first r))))
      (is (= 2 (:b (second r)))))))

(deftest keywordize-keys-passes-through-scalars
  (testing "keywordize-keys passes through non-collection values unchanged"
    (is (= 42     (#'peer/keywordize-keys 42)))
    (is (= "str"  (#'peer/keywordize-keys "str")))
    (is (nil?     (#'peer/keywordize-keys nil)))))

;; ---------------------------------------------------------------------------
;; poll-ctrl-node! — HTTP polling via *http-get* mock
;; ---------------------------------------------------------------------------

(deftest poll-ctrl-node-writes-to-ctrl-tree
  (testing "poll-ctrl-node! calls *http-get* and stores keywordized value"
    (let [written (atom nil)
          resp    {"path" ["ensemble" "harmony-ctx"]
                   "value" {"harmony/root" "D" "harmony/octave" 3
                             "harmony/tension" 0.65}
                   "type" "data"}]
      (binding [peer/*http-get* (fn [_] resp)]
        (with-redefs [cljseq.ctrl/set! (fn [path v] (reset! written {:path path :value v}) nil)]
          (#'peer/poll-ctrl-node! "http://10.0.0.1:7177"
                                  "/ctrl/ensemble/harmony-ctx"
                                  [:peers :ubuntu :ensemble :ctx])))
      (is (= [:peers :ubuntu :ensemble :ctx] (:path @written)))
      (is (= "D" (get-in @written [:value :harmony/root])))
      (is (= 3   (get-in @written [:value :harmony/octave]))))))

(deftest poll-ctrl-node-returns-nil-on-http-miss
  (testing "poll-ctrl-node! returns nil when *http-get* returns nil"
    (let [written (atom ::not-called)]
      (binding [peer/*http-get* (constantly nil)]
        (with-redefs [cljseq.ctrl/set! (fn [_ v] (reset! written v) nil)]
          (let [r (#'peer/poll-ctrl-node! "http://10.0.0.1:7177"
                                          "/ctrl/ensemble/harmony-ctx"
                                          [:peers :ubuntu :ensemble :ctx])]
            (is (nil? r))
            (is (= ::not-called @written))))))))

(deftest poll-ctrl-node-skips-nil-value-field
  (testing "poll-ctrl-node! skips write when response has no 'value' key"
    (let [written (atom ::not-called)
          resp    {"path" ["ensemble" "harmony-ctx"] "type" "data"}]
      (binding [peer/*http-get* (constantly resp)]
        (with-redefs [cljseq.ctrl/set! (fn [_ v] (reset! written v) nil)]
          (#'peer/poll-ctrl-node! "http://10.0.0.1:7177"
                                  "/ctrl/ensemble/harmony-ctx"
                                  [:peers :ubuntu :ensemble :ctx])))
      (is (= ::not-called @written)))))

;; ---------------------------------------------------------------------------
;; mount-peer! and unmount-peer! — lifecycle (no live network)
;; ---------------------------------------------------------------------------

(deftest mount-peer-throws-when-not-discovered
  (testing "mount-peer! throws ExceptionInfo when peer not in registry"
    (is (thrown? clojure.lang.ExceptionInfo
                 (peer/mount-peer! :not-registered)))))

(deftest mount-peer-starts-poll-thread
  (testing "mount-peer! starts a thread and registers it in mount-registry"
    (set-registry! {:target {:node-id :target :host "127.0.0.1"
                              :http-port 19999 :last-seen-ms (System/currentTimeMillis)}})
    ;; Use a *http-get* that immediately returns nil so the thread doesn't hang
    (binding [peer/*http-get* (constantly nil)]
      (peer/mount-peer! :target))
    (is (contains? (peer/mounted-peers) :target))
    ;; Clean up
    (peer/unmount-peer! :target)))

(deftest unmount-peer-removes-from-registry
  (testing "unmount-peer! removes peer from mount-registry"
    (set-registry! {:target {:node-id :target :host "127.0.0.1"
                              :http-port 19999 :last-seen-ms (System/currentTimeMillis)}})
    (binding [peer/*http-get* (constantly nil)]
      (peer/mount-peer! :target))
    (peer/unmount-peer! :target)
    (is (not (contains? (peer/mounted-peers) :target)))))

(deftest unmount-peer-noop-when-not-mounted
  (testing "unmount-peer! is a no-op when peer is not mounted"
    (is (nil? (peer/unmount-peer! :not-mounted)))))

;; ---------------------------------------------------------------------------
;; mounted-peers
;; ---------------------------------------------------------------------------

(deftest mounted-peers-empty-initially
  (testing "mounted-peers returns empty set with no mounts"
    (is (empty? (peer/mounted-peers)))))

;; ---------------------------------------------------------------------------
;; peer-harmony-ctx and peer-spectral-ctx — nil when not mounted
;; ---------------------------------------------------------------------------

(deftest peer-harmony-ctx-nil-when-not-mounted
  (testing "peer-harmony-ctx returns nil for an unmounted peer"
    (is (nil? (peer/peer-harmony-ctx :not-mounted)))))

(deftest peer-spectral-ctx-nil-when-not-mounted
  (testing "peer-spectral-ctx returns nil for an unmounted peer"
    (is (nil? (peer/peer-spectral-ctx :not-mounted)))))

;; ---------------------------------------------------------------------------
;; discovery-running? — lifecycle state
;; ---------------------------------------------------------------------------

(deftest discovery-not-running-initially
  (testing "discovery-running? returns false when no discovery has been started"
    ;; The defonce atoms start at nil; we may have stopped in the fixture
    (when (peer/discovery-running?) (peer/stop-discovery!))
    (is (false? (peer/discovery-running?)))))

;; ---------------------------------------------------------------------------
;; Backend registry — Layer 3
;; ---------------------------------------------------------------------------

(deftest register-backend-adds-entry
  (testing "register-backend! stores backend info"
    (peer/register-backend! :sc {:host "127.0.0.1" :sc-port 57110})
    (is (= {:sc {:host "127.0.0.1" :sc-port 57110}}
           (peer/active-backends)))))

(deftest deregister-backend-removes-entry
  (testing "deregister-backend! removes a previously registered backend"
    (peer/register-backend! :sc {:host "127.0.0.1" :sc-port 57110})
    (peer/deregister-backend! :sc)
    (is (empty? (peer/active-backends)))))

(deftest deregister-backend-noop-when-not-present
  (testing "deregister-backend! is a no-op for unknown backends"
    (is (nil? (peer/deregister-backend! :missing)))))

(deftest active-backends-empty-initially
  (testing "active-backends returns empty map when no backends registered"
    (set-backends! {})
    (is (= {} (peer/active-backends)))))

(deftest active-backends-multiple
  (testing "multiple backends can be registered simultaneously"
    (peer/register-backend! :sc       {:host "127.0.0.1" :sc-port 57110})
    (peer/register-backend! :surge-xt {:host "127.0.0.1" :osc-port 9001})
    (is (= 2 (count (peer/active-backends))))
    (is (contains? (peer/active-backends) :sc))
    (is (contains? (peer/active-backends) :surge-xt))))

(deftest beacon-includes-backends
  (testing "beacon-payload includes :backends map from backend registry"
    (set-backends! {:sc {:host "127.0.0.1" :sc-port 57110}})
    (let [parsed (edn/read-string (#'peer/beacon-payload))]
      (is (contains? parsed :backends))
      (is (= {:sc {:host "127.0.0.1" :sc-port 57110}}
             (:backends parsed))))))

(deftest beacon-backends-empty-when-no-backends
  (testing "beacon-payload :backends is empty map when no backends registered"
    (set-backends! {})
    (let [parsed (edn/read-string (#'peer/beacon-payload))]
      (is (= {} (:backends parsed))))))

(deftest peer-backends-returns-backends-from-beacon
  (testing "peer-backends returns the :backends from a discovered peer's beacon data"
    (set-registry! {:ubuntu {:node-id :ubuntu
                              :host "192.168.1.10"
                              :http-port 7177
                              :backends {:sc {:host "192.168.1.10" :sc-port 57110}}
                              :last-seen-ms (System/currentTimeMillis)}})
    (is (= {:sc {:host "192.168.1.10" :sc-port 57110}}
           (peer/peer-backends :ubuntu)))))

(deftest peer-backends-nil-for-unknown-peer
  (testing "peer-backends returns nil when peer not in registry"
    (set-registry! {})
    (is (nil? (peer/peer-backends :nonexistent)))))

(deftest peer-backends-nil-when-no-backends-field
  (testing "peer-backends returns nil when peer beacon has no :backends"
    (set-registry! {:old-node {:node-id :old-node :host "10.0.0.1"
                                :http-port 7177
                                :last-seen-ms (System/currentTimeMillis)}})
    (is (nil? (peer/peer-backends :old-node)))))
