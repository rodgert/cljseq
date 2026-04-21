; SPDX-License-Identifier: EPL-2.0
(ns cljseq.arc-test
  "Tests for ctrl watcher API and cljseq.arc fan-out routing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.arc  :as arc]
            [cljseq.core :as core]
            [cljseq.ctrl :as ctrl]))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

(defn- tx-val
  "Extract the :after value from the first change in a transaction."
  [tx]
  (-> tx :tx/changes first :after))

(defn- tx-path
  "Extract the :path from the first change in a transaction."
  [tx]
  (-> tx :tx/changes first :path))

;; ---------------------------------------------------------------------------
;; ctrl/watch! — basic registration and firing
;; ---------------------------------------------------------------------------

(deftest watch-fires-on-send-test
  (testing "watch! callback fires when send! writes to path"
    (ctrl/defnode! [:watch/test-send] :type :float :node-meta {:range [0.0 1.0]})
    (ctrl/bind! [:watch/test-send] {:type :midi-cc :channel 1 :cc-num 1})
    (let [seen (atom nil)]
      (ctrl/watch! [:watch/test-send] ::test-watcher
                   (fn [tx _] (reset! seen {:path (tx-path tx) :value (tx-val tx)})))
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (ctrl/send! [:watch/test-send] 0.5))
      (is (= {:path [:watch/test-send] :value 0.5} @seen))
      (ctrl/unwatch-all! [:watch/test-send]))))

(deftest watch-fires-on-set-test
  (testing "watch! callback fires when set! writes to path"
    (ctrl/defnode! [:watch/test-set] :type :float)
    (let [seen (atom nil)]
      (ctrl/watch! [:watch/test-set] ::test-watcher
                   (fn [tx _] (reset! seen (tx-val tx))))
      (ctrl/set! [:watch/test-set] 0.9)
      (is (= 0.9 @seen))
      (ctrl/unwatch-all! [:watch/test-set]))))

(deftest watch-multiple-keys-test
  (testing "multiple watch keys on same path each fire independently"
    (ctrl/defnode! [:watch/multi] :type :float)
    (let [a (atom nil) b (atom nil)]
      (ctrl/watch! [:watch/multi] ::key-a (fn [tx _] (reset! a (tx-val tx))))
      (ctrl/watch! [:watch/multi] ::key-b (fn [tx _] (reset! b (tx-val tx))))
      (ctrl/set! [:watch/multi] 0.3)
      (is (= 0.3 @a))
      (is (= 0.3 @b))
      (ctrl/unwatch-all! [:watch/multi]))))

(deftest unwatch-removes-specific-key-test
  (testing "unwatch! removes only the specified key, others still fire"
    (ctrl/defnode! [:watch/unwatch-one] :type :float)
    (let [a (atom 0) b (atom 0)]
      (ctrl/watch! [:watch/unwatch-one] ::key-a (fn [tx _] (reset! a (tx-val tx))))
      (ctrl/watch! [:watch/unwatch-one] ::key-b (fn [tx _] (reset! b (tx-val tx))))
      (ctrl/unwatch! [:watch/unwatch-one] ::key-a)
      (ctrl/set! [:watch/unwatch-one] 0.7)
      (is (= 0 @a) "key-a was removed — should not fire")
      (is (= 0.7 @b) "key-b still fires")
      (ctrl/unwatch-all! [:watch/unwatch-one]))))

(deftest unwatch-all-removes-all-test
  (testing "unwatch-all! prevents any further callbacks"
    (ctrl/defnode! [:watch/unwatch-all] :type :float)
    (let [seen (atom 0)]
      (ctrl/watch! [:watch/unwatch-all] ::k1 (fn [_ _s] (swap! seen inc)))
      (ctrl/watch! [:watch/unwatch-all] ::k2 (fn [_ _s] (swap! seen inc)))
      (ctrl/unwatch-all! [:watch/unwatch-all])
      (ctrl/set! [:watch/unwatch-all] 0.5)
      (is (= 0 @seen) "no callbacks after unwatch-all!"))))

(deftest watcher-exception-does-not-propagate-test
  (testing "exception in watcher is swallowed; caller is not affected"
    (ctrl/defnode! [:watch/throws] :type :float)
    (ctrl/watch! [:watch/throws] ::thrower
                 (fn [_ _s] (throw (ex-info "deliberate" {}))))
    (is (nil? (ctrl/set! [:watch/throws] 0.5))
        "set! returns nil even when watcher throws")
    (ctrl/unwatch-all! [:watch/throws])))

(deftest watcher-receives-correct-value-test
  (testing "watcher receives the exact value passed to send!/set!"
    (ctrl/defnode! [:watch/value-check] :type :float)
    (let [received (atom [])]
      (ctrl/watch! [:watch/value-check] ::collector
                   (fn [tx _] (swap! received conj (tx-val tx))))
      (ctrl/set! [:watch/value-check] 0.1)
      (ctrl/set! [:watch/value-check] 0.5)
      (ctrl/set! [:watch/value-check] 0.9)
      (is (= [0.1 0.5 0.9] @received))
      (ctrl/unwatch-all! [:watch/value-check]))))

;; ---------------------------------------------------------------------------
;; arc-bind! — fan-out routing
;; ---------------------------------------------------------------------------

(deftest arc-bind-fans-out-test
  (testing "arc-bind! fans out to all downstream paths on ctrl/send!"
    (ctrl/defnode! [:arc/test-tension] :type :float :node-meta {:range [0.0 1.0]})
    (ctrl/defnode! [:arc/out-cutoff]   :type :float)
    (ctrl/defnode! [:arc/out-res]      :type :float)
    (let [cutoff-vals (atom [])
          res-vals    (atom [])]
      (ctrl/watch! [:arc/out-cutoff] ::capture (fn [tx _] (swap! cutoff-vals conj (tx-val tx))))
      (ctrl/watch! [:arc/out-res]    ::capture (fn [tx _] (swap! res-vals conj (tx-val tx))))
      (arc/arc-bind! [:arc/test-tension]
                     {[:arc/out-cutoff] {:range [0.3 1.0]}
                      [:arc/out-res]    {:range [0.0 0.6]}})
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (arc/arc-send! [:arc/test-tension] 0.0)
        (arc/arc-send! [:arc/test-tension] 1.0))
      (is (= [0.3 1.0] @cutoff-vals) "cutoff: 0→0.3, 1→1.0")
      (is (= [0.0 0.6] @res-vals)    "resonance: 0→0.0, 1→0.6")
      (arc/arc-unbind! [:arc/test-tension])
      (ctrl/unwatch-all! [:arc/out-cutoff])
      (ctrl/unwatch-all! [:arc/out-res]))))

(deftest arc-bind-midpoint-scaling-test
  (testing "arc-bind! scales midpoint value correctly"
    (ctrl/defnode! [:arc/mid-src] :type :float)
    (ctrl/defnode! [:arc/mid-dst] :type :float)
    (let [seen (atom nil)]
      (ctrl/watch! [:arc/mid-dst] ::cap (fn [tx _] (reset! seen (tx-val tx))))
      (arc/arc-bind! [:arc/mid-src]
                     {[:arc/mid-dst] {:range [0.0 100.0]}})
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (arc/arc-send! [:arc/mid-src] 0.5))
      (is (= 50.0 @seen) "0.5 maps to midpoint of [0, 100]")
      (arc/arc-unbind! [:arc/mid-src])
      (ctrl/unwatch-all! [:arc/mid-dst]))))

(deftest arc-bind-default-range-test
  (testing "arc-bind! with no :range passes value through unchanged"
    (ctrl/defnode! [:arc/passthru-src] :type :float)
    (ctrl/defnode! [:arc/passthru-dst] :type :float)
    (let [seen (atom nil)]
      (ctrl/watch! [:arc/passthru-dst] ::cap (fn [tx _] (reset! seen (tx-val tx))))
      (arc/arc-bind! [:arc/passthru-src] {[:arc/passthru-dst] {}})
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (arc/arc-send! [:arc/passthru-src] 0.73))
      (is (= 0.73 @seen) "no :range → value passes through [0, 1] identity")
      (arc/arc-unbind! [:arc/passthru-src])
      (ctrl/unwatch-all! [:arc/passthru-dst]))))

(deftest arc-rebind-replaces-routes-test
  (testing "re-calling arc-bind! replaces downstream routes without duplicating watchers"
    (ctrl/defnode! [:arc/rebind-src] :type :float)
    (ctrl/defnode! [:arc/rebind-dst-a] :type :float)
    (ctrl/defnode! [:arc/rebind-dst-b] :type :float)
    (let [calls-a (atom 0) calls-b (atom 0)]
      (ctrl/watch! [:arc/rebind-dst-a] ::cap (fn [_ _s] (swap! calls-a inc)))
      (ctrl/watch! [:arc/rebind-dst-b] ::cap (fn [_ _s] (swap! calls-b inc)))
      ;; First bind: only dst-a
      (arc/arc-bind! [:arc/rebind-src] {[:arc/rebind-dst-a] {}})
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (arc/arc-send! [:arc/rebind-src] 0.5))
      (is (= 1 @calls-a) "dst-a receives first send")
      (is (= 0 @calls-b) "dst-b not yet bound")
      ;; Rebind: only dst-b
      (arc/arc-bind! [:arc/rebind-src] {[:arc/rebind-dst-b] {}})
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (arc/arc-send! [:arc/rebind-src] 0.5))
      (is (= 1 @calls-a) "dst-a no longer receives after rebind")
      (is (= 1 @calls-b) "dst-b now receives")
      (arc/arc-unbind! [:arc/rebind-src])
      (ctrl/unwatch-all! [:arc/rebind-dst-a])
      (ctrl/unwatch-all! [:arc/rebind-dst-b]))))

(deftest arc-unbind-stops-fan-out-test
  (testing "arc-unbind! stops further fan-out to downstream paths"
    (ctrl/defnode! [:arc/stop-src] :type :float)
    (ctrl/defnode! [:arc/stop-dst] :type :float)
    (let [count (atom 0)]
      (ctrl/watch! [:arc/stop-dst] ::cap (fn [_ _s] (swap! count inc)))
      (arc/arc-bind! [:arc/stop-src] {[:arc/stop-dst] {}})
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (arc/arc-send! [:arc/stop-src] 0.5))
      (is (= 1 @count) "fires before unbind")
      (arc/arc-unbind! [:arc/stop-src])
      (with-redefs [cljseq.sidecar/connected? (constantly false)]
        (arc/arc-send! [:arc/stop-src] 0.8))
      (is (= 1 @count) "does not fire after unbind")
      (ctrl/unwatch-all! [:arc/stop-dst]))))

(deftest arc-routes-reflects-current-state-test
  (testing "arc-routes returns current binding table"
    (arc/arc-bind! [:arc/inspect-src]
                   {[:arc/inspect-dst] {:range [0.0 1.0]}})
    (is (= {[:arc/inspect-dst] {:range [0.0 1.0]}}
           (get (arc/arc-routes) [:arc/inspect-src])))
    (arc/arc-unbind! [:arc/inspect-src])
    (is (nil? (get (arc/arc-routes) [:arc/inspect-src])))))

(deftest arc-unbind-all-clears-all-routes-test
  (testing "arc-unbind-all! removes all routes"
    (arc/arc-bind! [:arc/all-a] {[:arc/dummy] {}})
    (arc/arc-bind! [:arc/all-b] {[:arc/dummy] {}})
    (arc/arc-unbind-all!)
    (is (empty? (arc/arc-routes)))))

(deftest arc-send-fires-set-watcher-test
  (testing "arc-send! updates arc node value in ctrl tree"
    (ctrl/defnode! [:arc/val-check] :type :float)
    (arc/arc-bind! [:arc/val-check] {})
    (with-redefs [cljseq.sidecar/connected? (constantly false)]
      (arc/arc-send! [:arc/val-check] 0.42))
    (is (= 0.42 (ctrl/get [:arc/val-check])) "ctrl/get reflects sent value")
    (arc/arc-unbind! [:arc/val-check])))
