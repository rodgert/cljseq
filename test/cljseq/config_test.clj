; SPDX-License-Identifier: EPL-2.0
(ns cljseq.config-test
  "Tests for cljseq.config — configuration registry, get/set/all, ctrl tree
  seeding, and validation. No live sidecar required."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core   :as core]
            [cljseq.ctrl   :as ctrl]
            [cljseq.config :as config]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (core/start! :bpm 120)
    (t)
    (core/stop!)))

;; ---------------------------------------------------------------------------
;; Registry introspection
;; ---------------------------------------------------------------------------

(deftest all-param-keys-returns-set
  (testing "all-param-keys returns a set containing known params"
    (let [ks (config/all-param-keys)]
      (is (set? ks))
      (is (contains? ks :bpm))
      (is (contains? ks :link/quantum))
      (is (contains? ks :ctrl/undo-stack-depth))
      (is (contains? ks :osc/control-port)))))

(deftest param-info-returns-registry-entry
  (testing "param-info returns :default :type :doc :validate for known key"
    (let [info (config/param-info :bpm)]
      (is (map? info))
      (is (= 120 (:default info)))
      (is (= :float (:type info)))
      (is (string? (:doc info)))))
  (testing "param-info returns nil for unknown key"
    (is (nil? (config/param-info :no-such-param)))))

;; ---------------------------------------------------------------------------
;; get-config
;; ---------------------------------------------------------------------------

(deftest get-config-returns-current-bpm
  (testing "get-config :bpm returns current BPM"
    (is (= 120.0 (double (config/get-config :bpm))))))

(deftest get-config-returns-defaults-for-non-bpm
  (testing "get-config returns registry defaults for params not in system-state"
    (is (= 4    (config/get-config :link/quantum)))
    (is (= 50   (config/get-config :ctrl/undo-stack-depth)))
    (is (= 100  (config/get-config :sched-ahead-ms/midi-hardware)))
    (is (= 57121 (config/get-config :osc/control-port)))))

(deftest get-config-returns-nil-for-unknown-key
  (is (nil? (config/get-config :unknown/param))))

;; ---------------------------------------------------------------------------
;; set-config!
;; ---------------------------------------------------------------------------

(deftest set-config-updates-value
  (testing "set-config! stores new value readable by get-config"
    (config/set-config! :link/quantum 8)
    (is (= 8 (config/get-config :link/quantum)))))

(deftest set-config-bpm-applies-side-effect
  (testing "set-config! :bpm calls the registered effect (core/set-bpm!)"
    (config/set-config! :bpm 140.0)
    (is (= 140.0 (core/get-bpm)))))

(deftest set-config-updates-ctrl-tree
  (testing "set-config! writes to the ctrl tree so HTTP GET reflects the value"
    (config/set-config! :link/quantum 16)
    (is (= 16 (ctrl/get [:config :link/quantum])))))

(deftest set-config-throws-on-unknown-key
  (testing "set-config! throws ex-info for an unknown key"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/set-config! :no-such-key 42)))))

(deftest set-config-throws-on-invalid-value
  (testing "set-config! throws ex-info when validation fails"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/set-config! :bpm -1.0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/set-config! :link/quantum 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/set-config! :osc/control-port 80)))))

;; ---------------------------------------------------------------------------
;; all-configs
;; ---------------------------------------------------------------------------

(deftest all-configs-returns-all-keys
  (testing "all-configs snapshot contains all registered param keys"
    (let [m (config/all-configs)]
      (is (map? m))
      (doseq [k (config/all-param-keys)]
        (is (contains? m k) (str "missing key: " k))))))

(deftest all-configs-reflects-current-bpm
  (testing "all-configs :bpm reflects current BPM"
    (core/set-bpm! 160)
    (is (= 160.0 (double (:bpm (config/all-configs)))))))

;; ---------------------------------------------------------------------------
;; Ctrl tree seeding (seed-ctrl-tree! called from start!)
;; ---------------------------------------------------------------------------

(deftest ctrl-tree-seeded-on-start
  (testing "start! seeds all config params into the ctrl tree"
    (doseq [k (config/all-param-keys)]
      (is (some? (ctrl/get [:config k]))
          (str "ctrl tree missing [:config " k "]")))))

(deftest ctrl-tree-bpm-node-matches-start-bpm
  (testing "[:config :bpm] ctrl node has the BPM passed to start!"
    (is (= 120.0 (double (ctrl/get [:config :bpm]))))))

;; ---------------------------------------------------------------------------
;; BPM sync — core/set-bpm! keeps ctrl tree in sync
;; ---------------------------------------------------------------------------

(deftest set-bpm-updates-ctrl-tree
  (testing "core/set-bpm! keeps [:config :bpm] in the ctrl tree in sync"
    (core/set-bpm! 180)
    (is (= 180.0 (double (ctrl/get [:config :bpm]))))))

;; ---------------------------------------------------------------------------
;; :link/quantum connected to loop quantum
;; ---------------------------------------------------------------------------

(deftest link-quantum-config-is-readable
  (testing "link/quantum defaults to 4 and can be updated"
    (is (= 4 (config/get-config :link/quantum)))
    (config/set-config! :link/quantum 8)
    (is (= 8 (config/get-config :link/quantum)))))

;; ---------------------------------------------------------------------------
;; :ctrl/undo-stack-depth connected to undo stack
;; ---------------------------------------------------------------------------

(deftest undo-stack-depth-default
  (testing "ctrl/undo-stack-depth defaults to 50"
    (is (= 50 (config/get-config :ctrl/undo-stack-depth)))))

(deftest undo-stack-depth-configurable
  (testing "set-config! :ctrl/undo-stack-depth changes the effective depth"
    (config/set-config! :ctrl/undo-stack-depth 5)
    ;; Push 7 undoable changes; only 5 should be retained
    (dotimes [i 7]
      (ctrl/set! [:test/undo-depth-check] i :undoable true))
    (let [stack (:undo-stack @core/system-state)]
      (is (<= (count stack) 5)
          (str "undo stack should be bounded at 5, got " (count stack))))))
