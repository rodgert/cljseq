; SPDX-License-Identifier: EPL-2.0
(ns cljseq.mod-test
  "Unit tests for cljseq.mod — Lfo, envelope, OneShot, and mod-route! runner."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.clock  :as clock]
            [cljseq.core   :as core]
            [cljseq.ctrl   :as ctrl]
            [cljseq.mod    :as mod]
            [cljseq.phasor :as phasor])
)

;; ---------------------------------------------------------------------------
;; Lfo — sample and next-edge via shape fn
;; ---------------------------------------------------------------------------

(deftest lfo-sample-test
  (testing "lfo delegates sample through shape-fn"
    ;; Phasor rate=1, offset=0: at beat 0.0 phasor=0.0, sine-uni=0.5
    (let [ph  (clock/->Phasor 1 0)
          l   (mod/lfo ph phasor/sine-uni)]
      (is (= (phasor/sine-uni 0.0) (clock/sample l 0.0)))
      (is (= (phasor/sine-uni 0.25) (clock/sample l 0.25))))))

(deftest lfo-next-edge-delegates-to-phasor-test
  (testing "lfo next-edge matches phasor next-edge"
    (let [ph (clock/->Phasor 1 0)
          l  (mod/lfo ph phasor/triangle)]
      (is (= (clock/next-edge ph 0.0) (clock/next-edge l 0.0)))
      (is (= (clock/next-edge ph 0.5) (clock/next-edge l 0.5))))))

;; ---------------------------------------------------------------------------
;; envelope — piecewise-linear interpolation
;; ---------------------------------------------------------------------------

(deftest envelope-linear-interpolation-test
  (testing "envelope interpolates linearly between breakpoints"
    (let [env (mod/envelope [[0.0 0.0] [0.5 1.0] [1.0 0.0]])]
      (is (= 0.0 (env 0.0))   "start")
      (is (= 1.0 (env 0.5))   "peak")
      (is (= 0.0 (env 1.0))   "end")
      (is (= 0.5 (env 0.25))  "midpoint of rising segment")
      (is (= 0.5 (env 0.75))  "midpoint of falling segment"))))

(deftest envelope-clamps-past-end-test
  (testing "envelope returns last value when phase exceeds 1.0"
    (let [env (mod/envelope [[0.0 0.0] [1.0 0.8]])]
      (is (= 0.8 (env 1.0)))
      (is (= 0.8 (env 1.5))))))

(deftest envelope-adsr-shape-test
  (testing "ADSR envelope hits expected levels at key phases"
    (let [env (mod/envelope [[0.00 0.0]
                              [0.10 1.0]
                              [0.30 0.7]
                              [0.80 0.7]
                              [1.00 0.0]])]
      (is (= 0.0 (env 0.0))   "pre-attack silence")
      (is (= 1.0 (env 0.1))   "attack peak")
      (is (= 0.7 (env 0.3))   "decay to sustain")
      (is (= 0.7 (env 0.5))   "sustain hold")
      (is (= 0.0 (env 1.0))   "release to silence"))))

;; ---------------------------------------------------------------------------
;; OneShot — clamps after first cycle
;; ---------------------------------------------------------------------------

(deftest one-shot-runs-during-cycle-test
  (testing "one-shot samples normally while beat < end-beat"
    (let [ph  (clock/->Phasor 1 0)         ; 1 cycle per beat, starts at 0
          env (mod/envelope [[0.0 0.0] [1.0 1.0]])
          os  (mod/one-shot ph env 0.0)
          ;; end-beat = next-edge of ph from 0.0 = 1.0
          ]
      ;; At beat 0.5 phasor=0.5 → env(0.5)=0.5
      (is (= 0.5 (clock/sample os 0.5)) "mid-cycle sample"))))

(deftest one-shot-clamps-after-cycle-test
  (testing "one-shot returns env(1.0) after end-beat"
    (let [ph  (clock/->Phasor 1 0)
          env (mod/envelope [[0.0 0.0] [1.0 0.9]])
          os  (mod/one-shot ph env 0.0)]
      (is (= 0.9 (clock/sample os 1.0))  "at end-beat")
      (is (= 0.9 (clock/sample os 2.0))  "past end-beat"))))

(deftest one-shot-next-edge-inf-test
  (testing "one-shot next-edge returns ##Inf after end-beat"
    (let [ph (clock/->Phasor 1 0)
          os (mod/one-shot ph phasor/sine-uni 0.0)]
      (is (< (clock/next-edge os 0.5) ##Inf)  "during cycle: finite edge")
      (is (= ##Inf (clock/next-edge os 1.0))  "at end: ##Inf")
      (is (= ##Inf (clock/next-edge os 2.0))  "past end: ##Inf"))))

;; ---------------------------------------------------------------------------
;; mod-route! and mod-unroute! — runner integration
;; ---------------------------------------------------------------------------

(deftest mod-route-dispatches-to-ctrl-send-test
  (testing "mod-route! runner calls ctrl/send! with sampled value"
    ;; Use a very fast BPM so the phasor cycles in milliseconds.
    ;; Phasor rate=1, period=1 beat → at 60000 BPM, 1 beat = 1ms.
    (core/start! :bpm 60000)
    (try
      (ctrl/defnode! [:mod/test-cc] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      ;; Bind so ctrl/send! doesn't try sidecar dispatch (no sidecar in tests)
      ;; — defnode! alone is enough; send! still updates the atom even w/o bindings.
      (let [received  (promise)
            orig-send ctrl/send!]
        (with-redefs [ctrl/send! (fn [path value]
                                   (when (= path [:mod/test-cc])
                                     (deliver received value))
                                   (orig-send path value))]
          (mod/mod-route! [:mod/test-cc]
                          (mod/lfo (clock/->Phasor 1 0) phasor/saw-up))
          (let [result (deref received 500 ::timeout)]
            (is (not= ::timeout result) "runner dispatched within 500ms")
            (is (number? result)        "dispatched value is a number")
            (is (<= 0.0 result 1.0)     "saw-up value in [0.0, 1.0]"))))
      (finally
        (mod/mod-unroute! [:mod/test-cc])
        (core/stop!)))))

(deftest mod-route-hot-swap-test
  (testing "re-calling mod-route! on same path updates the active mod"
    (core/start! :bpm 60000)
    (try
      (ctrl/defnode! [:mod/hot-swap] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      (let [la (mod/lfo (clock/->Phasor 1 0) phasor/saw-up)
            lb (mod/lfo (clock/->Phasor 2 0) phasor/triangle)]
        (mod/mod-route! [:mod/hot-swap] la)
        (is (= la (get (mod/mod-routes) [:mod/hot-swap])) "initial mod registered")
        (mod/mod-route! [:mod/hot-swap] lb)
        (is (= lb (get (mod/mod-routes) [:mod/hot-swap])) "mod updated after hot-swap"))
      (finally
        (mod/mod-unroute! [:mod/hot-swap])
        (core/stop!)))))

(deftest mod-unroute-stops-runner-test
  (testing "mod-unroute! removes route and stops dispatch"
    (core/start! :bpm 60000)
    (try
      (ctrl/defnode! [:mod/stop-test] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      (mod/mod-route! [:mod/stop-test] (mod/lfo (clock/->Phasor 1 0) phasor/saw-up))
      (Thread/sleep 20)  ; let runner start
      (mod/mod-unroute! [:mod/stop-test])
      (is (nil? (get (mod/mod-routes) [:mod/stop-test]))
          "route absent after unroute!")
      (finally
        (core/stop!)))))

(deftest mod-routes-returns-active-routes-test
  (testing "mod-routes returns map of active path->mod"
    (core/start! :bpm 60000)
    (try
      (ctrl/defnode! [:mod/routes-a] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      (ctrl/defnode! [:mod/routes-b] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      (let [la (mod/lfo (clock/->Phasor 1 0) phasor/saw-up)
            lb (mod/lfo (clock/->Phasor 2 0) phasor/triangle)]
        (mod/mod-route! [:mod/routes-a] la)
        (mod/mod-route! [:mod/routes-b] lb)
        (let [r (mod/mod-routes)]
          (is (= la (get r [:mod/routes-a])) "route-a present")
          (is (= lb (get r [:mod/routes-b])) "route-b present"))
        (mod/mod-unroute! [:mod/routes-a])
        (mod/mod-unroute! [:mod/routes-b]))
      (finally
        (core/stop!)))))

(deftest mod-unroute-all-test
  (testing "mod-unroute-all! clears all active routes"
    (core/start! :bpm 60000)
    (try
      (ctrl/defnode! [:mod/all-a] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      (ctrl/defnode! [:mod/all-b] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      (mod/mod-route! [:mod/all-a] (mod/lfo (clock/->Phasor 1 0) phasor/saw-up))
      (mod/mod-route! [:mod/all-b] (mod/lfo (clock/->Phasor 2 0) phasor/triangle))
      (mod/mod-unroute-all!)
      (is (empty? (mod/mod-routes)) "all routes cleared")
      (finally
        (core/stop!)))))

(deftest one-shot-runner-auto-stops-test
  (testing "runner auto-stops and removes route when one-shot next-edge returns ##Inf"
    ;; At 60000 BPM, 1 beat = 1ms. Phasor rate=1 → period=1 beat=1ms.
    ;; start-beat=0 means end-beat=1; current beat at start will be >> 1,
    ;; so next-edge immediately returns ##Inf and the runner self-terminates.
    (core/start! :bpm 60000)
    (try
      (ctrl/defnode! [:mod/one-shot-test] :type :float :meta {:range [0.0 1.0]} :value 0.0)
      (let [env (mod/envelope [[0.0 0.0] [1.0 1.0]])
            ph  (clock/->Phasor 1 0)
            os  (mod/one-shot ph env 0.0)]
        (mod/mod-route! [:mod/one-shot-test] os)
        ;; Wait briefly for runner to detect ##Inf and self-remove
        (Thread/sleep 100)
        (is (nil? (get (mod/mod-routes) [:mod/one-shot-test]))
            "one-shot runner removed itself from routes"))
      (finally
        (core/stop!)))))
