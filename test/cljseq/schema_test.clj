; SPDX-License-Identifier: EPL-2.0
(ns cljseq.schema-test
  "Tests for cljseq.schema — device model, realization, and realize!."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.schema :as schema]
            [cljseq.ctrl   :as ctrl]
            [cljseq.core   :as core]))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Test model and realization data
;; ---------------------------------------------------------------------------

(def ^:private test-model
  {:model/name   "Test Synth"
   :profile/base
   {:filter {:cutoff {:type :float :range [0.0 1.0]}
             :res    {:type :float :range [0.0 1.0]}}
    :env    {:attack  {:type :float :range [0.0 1.0]}
             :release {:type :float :range [0.0 1.0]}}}
   :profile/extended
   {:analog {:drift {:type :float :range [0.0 1.0]}}}})

(def ^:private midi-realization
  {:model      :test/synth
   :satisfies  #{:test.synth/base}
   :binding    :midi
   :channel    1
   :cc-map     {[:filter :cutoff] {:cc 74 :range [0 127]}
                [:filter :res]    {:cc 71 :range [0 127]}
                [:env :attack]    {:cc 73 :range [0 127]}
                [:env :release]   {:cc 72 :range [0 127]}}})

;; ---------------------------------------------------------------------------
;; defdevice-model
;; ---------------------------------------------------------------------------

(deftest defdevice-model-stores-in-ctrl-tree-test
  (testing "defdevice-model stores the model in [:cljseq/schema :device-models ...]"
    (schema/defdevice-model :test/synth test-model)
    (let [stored (schema/get-model :test/synth)]
      (is (some? stored) "model is stored")
      (is (= :test/synth (:model/id stored)) "model/id is set")
      (is (= "Test Synth" (:model/name stored)))
      (is (map? (:profile/base stored))))))

(deftest defdevice-model-appears-in-tx-log-test
  (testing "defdevice-model write appears in the transaction log"
    (schema/defdevice-model :test/synth-log test-model)
    (let [log  (ctrl/tx-log)
          txs  (filter #(= :schema (-> % :tx/source :source/kind)) log)]
      (is (seq txs) "at least one schema transaction in the log")
      (let [last-tx (last txs)]
        (is (= [:cljseq/schema :device-models :test/synth-log]
               (-> last-tx :tx/changes first :path)))))))

(deftest defdevice-model-idempotent-test
  (testing "re-calling defdevice-model replaces the model"
    (schema/defdevice-model :test/idempotent {:model/name "v1" :profile/base {}})
    (schema/defdevice-model :test/idempotent {:model/name "v2" :profile/base {}})
    (is (= "v2" (:model/name (schema/get-model :test/idempotent))))))

(deftest list-models-test
  (testing "list-models reflects registered models"
    (schema/defdevice-model :test/ls-a {:model/name "A" :profile/base {}})
    (schema/defdevice-model :test/ls-b {:model/name "B" :profile/base {}})
    (let [models (set (schema/list-models))]
      (is (contains? models :test/ls-a))
      (is (contains? models :test/ls-b)))))

;; ---------------------------------------------------------------------------
;; defrealization
;; ---------------------------------------------------------------------------

(deftest defrealization-stores-in-ctrl-tree-test
  (testing "defrealization stores the realization in [:cljseq/schema :realizations ...]"
    (schema/defrealization :test/midi-rig midi-realization)
    (let [stored (schema/get-realization :test/midi-rig)]
      (is (some? stored) "realization is stored")
      (is (= :test/midi-rig (:realization/id stored)))
      (is (= :test/synth (:model stored)))
      (is (= #{:test.synth/base} (:satisfies stored))))))

(deftest defrealization-appears-in-tx-log-test
  (testing "defrealization write appears in the transaction log"
    (schema/defrealization :test/r-log-test midi-realization)
    (let [log (ctrl/tx-log)
          txs (filter #(= :schema (-> % :tx/source :source/kind)) log)]
      (is (seq txs))
      (let [paths (set (map #(-> % :tx/changes first :path) txs))]
        (is (contains? paths [:cljseq/schema :realizations :test/r-log-test]))))))

(deftest satisfies-profile-test
  (testing "satisfies-profile? reflects declared :satisfies set"
    (schema/defrealization :test/base-only
      {:model :test/synth :satisfies #{:test.synth/base} :binding :midi :channel 1})
    (is (true?  (schema/satisfies-profile? :test/base-only :test.synth/base)))
    (is (false? (schema/satisfies-profile? :test/base-only :test.synth/extended)))))

(deftest list-realizations-test
  (testing "list-realizations reflects registered realizations"
    (schema/defrealization :test/real-a {:model :test/synth :satisfies #{} :binding :midi :channel 1})
    (schema/defrealization :test/real-b {:model :test/synth :satisfies #{} :binding :midi :channel 2})
    (let [rs (set (schema/list-realizations))]
      (is (contains? rs :test/real-a))
      (is (contains? rs :test/real-b)))))

;; ---------------------------------------------------------------------------
;; realize!
;; ---------------------------------------------------------------------------

(deftest realize-creates-ctrl-nodes-test
  (testing "realize! creates ctrl tree nodes for all base profile parameters"
    (schema/defdevice-model :test/synth test-model)
    (schema/defrealization  :test/node-rig midi-realization)
    (schema/realize! :test/synth :test/node-rig)
    (is (some? (ctrl/node-info [:test/synth :filter :cutoff]))
        "filter/cutoff node exists")
    (is (some? (ctrl/node-info [:test/synth :filter :res]))
        "filter/res node exists")
    (is (some? (ctrl/node-info [:test/synth :env :attack]))
        "env/attack node exists")))

(deftest realize-installs-midi-bindings-test
  (testing "realize! installs MIDI CC bindings from cc-map"
    (schema/defdevice-model :test/synth test-model)
    (schema/defrealization  :test/bind-rig midi-realization)
    (schema/realize! :test/synth :test/bind-rig)
    (let [node (ctrl/node-info [:test/synth :filter :cutoff])]
      (is (seq (:bindings node)) "cutoff node has bindings")
      (let [b (first (:bindings node))]
        (is (= :midi-cc (:type b)))
        (is (= 74 (:cc-num b)))
        (is (= 1  (:channel b)))))))

(deftest realize-records-active-realization-test
  (testing "realize! records active realization in the ctrl tree"
    (schema/defdevice-model :test/synth test-model)
    (schema/defrealization  :test/active-rig midi-realization)
    (schema/realize! :test/synth :test/active-rig)
    (is (= :test/active-rig (schema/active-realization :test/synth)))))

(deftest realize-active-appears-in-tx-log-test
  (testing "realize! active-realization write appears in transaction log"
    (schema/defdevice-model :test/synth test-model)
    (schema/defrealization  :test/log-rig midi-realization)
    (schema/realize! :test/synth :test/log-rig)
    (let [log  (ctrl/tx-log)
          txs  (filter #(= :schema (-> % :tx/source :source/kind)) log)
          paths (set (map #(-> % :tx/changes first :path) txs))]
      (is (contains? paths
                     [:cljseq/schema :active-realizations :test/synth])))))

(deftest realize-node-type-from-profile-test
  (testing "realize! assigns correct type from profile spec"
    (schema/defdevice-model :test/synth test-model)
    (schema/defrealization  :test/type-rig midi-realization)
    (schema/realize! :test/synth :test/type-rig)
    (let [node (ctrl/node-info [:test/synth :filter :cutoff])]
      (is (= :float (:type node))))))

(deftest realize-unknown-model-throws-test
  (testing "realize! throws when model not registered"
    (schema/defrealization :test/orphan-rig
      {:model :test/no-such-model :satisfies #{} :binding :midi :channel 1})
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/realize! :test/no-such-model :test/orphan-rig)))))

(deftest realize-unknown-realization-throws-test
  (testing "realize! throws when realization not registered"
    (schema/defdevice-model :test/throw-synth test-model)
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/realize! :test/throw-synth :test/no-such-rig)))))

(deftest realize-model-mismatch-throws-test
  (testing "realize! throws when realization model-id doesn't match"
    (schema/defdevice-model :test/mismatch-synth test-model)
    (schema/defrealization  :test/mismatch-rig
      {:model :test/some-other-model :satisfies #{} :binding :midi :channel 1})
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/realize! :test/mismatch-synth :test/mismatch-rig)))))

(deftest realize-teardown-on-rebind-test
  (testing "realize! removes prior MIDI bindings before installing new ones"
    (let [r1 {:model :test/swap-synth :satisfies #{:test.swap-synth/base}
              :binding :midi :channel 1
              :cc-map {[:filter :cutoff] {:cc 74 :range [0 127]}}}
          r2 {:model :test/swap-synth :satisfies #{:test.swap-synth/base}
              :binding :midi :channel 2
              :cc-map {[:filter :cutoff] {:cc 74 :range [0 127]}}}]
      (schema/defdevice-model :test/swap-synth test-model)
      (schema/defrealization  :test/swap-r1 r1)
      (schema/defrealization  :test/swap-r2 r2)
      (schema/realize! :test/swap-synth :test/swap-r1)
      ;; After first realize, channel should be 1
      (let [b (first (:bindings (ctrl/node-info [:test/swap-synth :filter :cutoff])))]
        (is (= 1 (:channel b)) "channel 1 after first realize"))
      ;; Rebind — should tear down and reinstall
      (schema/realize! :test/swap-synth :test/swap-r2)
      (let [bindings (:bindings (ctrl/node-info [:test/swap-synth :filter :cutoff]))]
        (is (= 1 (count bindings)) "exactly one binding after rebind")
        (is (= 2 (:channel (first bindings))) "channel 2 after second realize")))))
