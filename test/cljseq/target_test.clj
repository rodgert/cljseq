; SPDX-License-Identifier: EPL-2.0
(ns cljseq.target-test
  "Unit tests for cljseq.target — ITarget / ITriggerTarget / IParamTarget protocols,
  FnTarget, OscParamTarget, and the target registry."
  (:require [clojure.test  :refer [deftest is testing]]
            [cljseq.target :as target]
            [cljseq.ctrl   :as ctrl]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest register-and-lookup-test
  (testing "register! stores a target; lookup retrieves it"
    (let [tgt (target/fn-target :test-reg :live?-fn (constantly true))]
      (target/register! :test-reg tgt)
      (is (= tgt (target/lookup :test-reg)))))

  (testing "lookup returns nil for unknown key"
    (is (nil? (target/lookup :no-such-target-xyz))))

  (testing "registered-targets includes registered target"
    (let [tgt (target/fn-target :test-reg2 :live?-fn (constantly true))]
      (target/register! :test-reg2 tgt)
      (is (contains? (target/registered-targets) :test-reg2)))))

;; ---------------------------------------------------------------------------
;; FnTarget — ITarget
;; ---------------------------------------------------------------------------

(deftest fn-target-identity-test
  (testing "target-id returns the registered keyword"
    (let [tgt (target/fn-target :my-synth :live?-fn (constantly true))]
      (is (= :my-synth (target/target-id tgt)))))

  (testing "target-live? delegates to live?-fn"
    (let [live? (atom true)
          tgt   (target/fn-target :liveness-test :live?-fn #(deref live?))]
      (is (true?  (target/target-live? tgt)))
      (reset! live? false)
      (is (false? (target/target-live? tgt))))))

;; ---------------------------------------------------------------------------
;; FnTarget — ITriggerTarget
;; ---------------------------------------------------------------------------

(deftest fn-target-trigger-test
  (testing "trigger-note! calls trigger-fn with the event"
    (let [received (atom nil)
          tgt (target/fn-target :trig-test
                :trigger-fn (fn [ev] (reset! received ev) :handle-42)
                :live?-fn   (constantly true))]
      (let [handle (target/trigger-note! tgt {:pitch/midi 60 :dur/beats 1})]
        (is (= {:pitch/midi 60 :dur/beats 1} @received))
        (is (= :handle-42 handle)))))

  (testing "release-note! calls release-fn with handle"
    (let [released (atom nil)
          tgt (target/fn-target :rel-test
                :trigger-fn  (fn [_] :h)
                :release-fn  (fn [h] (reset! released h))
                :live?-fn    (constantly true))]
      (target/release-note! tgt :my-handle)
      (is (= :my-handle @released))))

  (testing "release-note! is safe with no release-fn"
    (let [tgt (target/fn-target :no-release
                :trigger-fn (fn [_] nil)
                :live?-fn   (constantly true))]
      (is (nil? (target/release-note! tgt :some-handle))))))

;; ---------------------------------------------------------------------------
;; FnTarget — IParamTarget
;; ---------------------------------------------------------------------------

(deftest fn-target-param-test
  (testing "param-root returns the configured path"
    (let [tgt (target/fn-target :param-root-test
                :live?-fn   (constantly true)
                :param-root [:studio :synth-a])]
      (is (= [:studio :synth-a] (target/param-root tgt)))))

  (testing "send-param! with keyword path sets ctrl at (conj root path)"
    (let [written (atom nil)
          tgt (target/fn-target :param-write-test
                :live?-fn   (constantly true)
                :param-root [:studio :test-dev])]
      (with-redefs [ctrl/set! (fn [path val] (reset! written [path val]))]
        (target/send-param! tgt :filter 64))
      (is (= [[:studio :test-dev :filter] 64] @written))))

  (testing "send-param! with vector path appends to root"
    (let [written (atom nil)
          tgt (target/fn-target :param-vec-test
                :live?-fn   (constantly true)
                :param-root [:studio :test-dev])]
      (with-redefs [ctrl/set! (fn [path val] (reset! written [path val]))]
        (target/send-param! tgt [:reverb :mix] 0.7))
      (is (= [[:studio :test-dev :reverb :mix] 0.7] @written)))))

;; ---------------------------------------------------------------------------
;; OscParamTarget — ITarget + IParamTarget
;; ---------------------------------------------------------------------------

(deftest osc-param-target-test
  (testing "osc-param-target satisfies ITarget and IParamTarget"
    (let [tgt (target/osc-param-target :nightsky
                :param-root [:studio :nightsky])]
      (is (= :nightsky (target/target-id tgt)))
      (is (true?  (target/target-live? tgt))) ; defaults to always true
      (is (= [:studio :nightsky] (target/param-root tgt)))))

  (testing "osc-param-target does NOT satisfy ITriggerTarget"
    (let [tgt (target/osc-param-target :fx-only :param-root [:fx])]
      (is (not (satisfies? target/ITriggerTarget tgt)))))

  (testing "send-param! mirrors value to ctrl tree"
    (let [written (atom nil)
          tgt (target/osc-param-target :osc-write-test
                :param-root [:studio :nightsky])]
      (with-redefs [ctrl/set! (fn [path val] (reset! written [path val]))]
        (target/send-param! tgt :mix 0.4))
      (is (= [[:studio :nightsky :mix] 0.4] @written)))))

;; ---------------------------------------------------------------------------
;; Protocol predicate checks
;; ---------------------------------------------------------------------------

(deftest protocol-satisfaction-test
  (testing "FnTarget satisfies all three protocols"
    (let [tgt (target/fn-target :full :live?-fn (constantly true))]
      (is (satisfies? target/ITarget         tgt))
      (is (satisfies? target/ITriggerTarget  tgt))
      (is (satisfies? target/IParamTarget    tgt))))

  (testing "OscParamTarget satisfies ITarget and IParamTarget but not ITriggerTarget"
    (let [tgt (target/osc-param-target :fx :param-root [])]
      (is (satisfies? target/ITarget         tgt))
      (is (satisfies? target/IParamTarget    tgt))
      (is (not (satisfies? target/ITriggerTarget tgt))))))
