; SPDX-License-Identifier: EPL-2.0
(ns cljseq.device-test
  "Unit tests for cljseq.device — device registration, CC routing, semantic resolution."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [cljseq.core   :as core]
            [cljseq.ctrl   :as ctrl]
            [cljseq.device :as device]))

;; ---------------------------------------------------------------------------
;; Fixtures — start/stop system around tests that touch ctrl tree
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; load-device-map
;; ---------------------------------------------------------------------------

(deftest load-device-map-test
  (testing "loads a valid EDN device map from resources"
    (let [dmap (device/load-device-map "devices/korg-minilogue-xd.edn")]
      (is (= :korg/minilogue-xd (:device/id dmap)))
      (is (= :target (:device/role dmap)))
      (is (seq (:midi/cc dmap)))))
  (testing "throws on missing resource"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not found"
         (device/load-device-map "devices/nonexistent.edn")))))

;; ---------------------------------------------------------------------------
;; defdevice — target device registration
;; ---------------------------------------------------------------------------

(deftest defdevice-registers-device-test
  (testing "defdevice returns device-id"
    (is (= :test/synth
           (device/defdevice :test/synth
             {:device/role :target
              :midi/channel 2
              :midi/cc [{:cc 74 :path [:filter :cutoff] :range [0 127]}]}))))
  (testing "device appears in list-devices after registration"
    (device/defdevice :test/synth
      {:device/role :target :midi/channel 1 :midi/cc []})
    (is (some #{:test/synth} (device/list-devices)))))

(deftest defdevice-creates-ctrl-nodes-test
  (testing "ctrl node created for each concrete CC entry"
    (device/defdevice :test/synth
      {:device/role :target
       :midi/channel 1
       :midi/cc [{:cc 74 :path [:filter :cutoff] :range [0 127]}
                 {:cc 23 :path [:vco1 :wave] :range [0 127]
                  :values [{:value 0 :label :triangle}
                           {:value 43 :label :saw}
                           {:value 86 :label :pulse}]}]})
    (let [cutoff-node (ctrl/node-info [:test/synth :filter :cutoff])
          wave-node   (ctrl/node-info [:test/synth :vco1 :wave])]
      (is (some? cutoff-node) "cutoff node registered")
      (is (= :int (:type cutoff-node)))
      (is (some? wave-node) "wave node registered")
      (is (= :enum (:type wave-node)))
      (is (= [:triangle :saw :pulse] (get-in wave-node [:node-meta :values]))))))

(deftest defdevice-creates-midi-cc-bindings-test
  (testing "ctrl node has a :midi-cc binding after defdevice"
    (device/defdevice :test/synth
      {:device/role :target
       :midi/channel 3
       :midi/cc [{:cc 40 :path [:filter :cutoff] :range [0 127]}]})
    (let [node     (ctrl/node-info [:test/synth :filter :cutoff])
          bindings (:bindings node)]
      (is (= 1 (count bindings)))
      (is (= :midi-cc (:type (first bindings))))
      (is (= 40 (:cc-num (first bindings))))
      (is (= 3 (:channel (first bindings)))))))

(deftest defdevice-skips-configurable-cc-test
  (testing "configurable CC entries (not integers) are not registered as nodes"
    (device/defdevice :test/ctrl
      {:device/role :controller
       :midi/channel 1
       :midi/cc [{:cc :configurable :path [:touch-strip] :range [0 127]}]})
    (is (nil? (ctrl/node-info [:test/ctrl :touch-strip]))
        "configurable CC should not create a ctrl node")))

(deftest defdevice-reregistration-test
  (testing "re-calling defdevice replaces the existing registration without error"
    (let [map1 {:device/role :target :midi/channel 1
                :midi/cc [{:cc 74 :path [:filter :cutoff] :range [0 127]}]}
          map2 {:device/role :target :midi/channel 2
                :midi/cc [{:cc 74 :path [:filter :cutoff] :range [0 127]}]}]
      (device/defdevice :test/synth map1)
      ;; Re-registration should not throw (no duplicate priority error)
      (is (= :test/synth (device/defdevice :test/synth map2)))
      ;; New channel should be reflected
      (let [node (ctrl/node-info [:test/synth :filter :cutoff])]
        (is (= 2 (:channel (first (:bindings node)))))))))

;; ---------------------------------------------------------------------------
;; Full device map loading
;; ---------------------------------------------------------------------------

(deftest minilogue-xd-loads-test
  (testing "Minilogue XD device map loads and registers without error"
    (is (= :korg/minilogue-xd
           (device/defdevice :korg/minilogue-xd
             (device/load-device-map "devices/korg-minilogue-xd.edn"))))
    ;; Spot-check a few known nodes
    (is (some? (ctrl/node-info [:korg/minilogue-xd :filter :cutoff])))
    (is (some? (ctrl/node-info [:korg/minilogue-xd :eg :attack])))
    (is (some? (ctrl/node-info [:korg/minilogue-xd :lfo :wave])))))

;; ---------------------------------------------------------------------------
;; device-send! — numeric values
;; ---------------------------------------------------------------------------

(deftest device-send-numeric-test
  (testing "device-send! with a numeric value calls ctrl/send! with device path"
    (device/defdevice :test/synth
      {:device/role :target :midi/channel 1
       :midi/cc [{:cc 40 :path [:filter :cutoff] :range [0 127]}]})
    (let [calls (atom [])]
      (with-redefs [ctrl/send! (fn [path val] (swap! calls conj {:path path :val val}))]
        (device/device-send! :test/synth [:filter :cutoff] 80))
      (is (= 1 (count @calls)))
      (is (= [:test/synth :filter :cutoff] (:path (first @calls))))
      (is (= 80 (:val (first @calls)))))))

;; ---------------------------------------------------------------------------
;; device-send! — semantic keyword resolution
;; ---------------------------------------------------------------------------

(deftest device-send-keyword-resolves-test
  (testing "device-send! resolves a keyword label to its CC value"
    (device/defdevice :test/synth
      {:device/role :target :midi/channel 1
       :midi/cc [{:cc 23 :path [:vco1 :wave] :range [0 127]
                  :values [{:value 0 :label :triangle}
                           {:value 43 :label :saw}
                           {:value 86 :label :pulse}]}]})
    (let [calls (atom [])]
      (with-redefs [ctrl/send! (fn [path val] (swap! calls conj {:path path :val val}))]
        (device/device-send! :test/synth [:vco1 :wave] :saw))
      (is (= 43 (:val (first @calls))) ":saw resolves to 43")))
  (testing "resolves :bpm-sync on lfo mode"
    (device/defdevice :test/synth
      {:device/role :target :midi/channel 1
       :midi/cc [{:cc 54 :path [:lfo :mode] :range [0 127]
                  :values [{:value 0 :label :fast}
                           {:value 43 :label :slow}
                           {:value 86 :label :bpm-sync}]}]})
    (let [calls (atom [])]
      (with-redefs [ctrl/send! (fn [path val] (swap! calls conj {:path path :val val}))]
        (device/device-send! :test/synth [:lfo :mode] :bpm-sync))
      (is (= 86 (:val (first @calls)))))))

(deftest device-send-unknown-label-throws-test
  (testing "device-send! with unknown keyword label throws"
    (device/defdevice :test/synth
      {:device/role :target :midi/channel 1
       :midi/cc [{:cc 23 :path [:vco1 :wave] :range [0 127]
                  :values [{:value 43 :label :saw}]}]})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"no resolver for label"
         (device/device-send! :test/synth [:vco1 :wave] :sine)))))

(deftest device-send-unknown-device-throws-test
  (testing "device-send! with unregistered device throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown device"
         (device/device-send! :no/such-device [:filter :cutoff] 64)))))

;; ---------------------------------------------------------------------------
;; device-bind! — controller device sources
;; ---------------------------------------------------------------------------

(deftest device-bind-registers-intent-test
  (testing "device-bind! registers a :midi-device-input binding on the ctrl node"
    (device/defdevice :test/ctrl
      {:device/role :controller :midi/channel 1
       :midi/cc []
       :cljseq/bind-sources
       {:touch-strip {:source :channel-pressure :yields :pressure-0-127}}})
    (ctrl/defnode! [:synth/cutoff] :type :float :meta {:range [0.0 1.0]})
    (device/device-bind! [:synth/cutoff]
                         {:device :test/ctrl :source :touch-strip})
    (let [node     (ctrl/node-info [:synth/cutoff])
          bindings (:bindings node)]
      (is (some #(= :midi-device-input (:type %)) bindings)
          "binding of type :midi-device-input should be present")
      (let [b (first (filter #(= :midi-device-input (:type %)) bindings))]
        (is (= :test/ctrl (:device b)))
        (is (= :touch-strip (:source b)))))))

(deftest device-bind-unknown-source-throws-test
  (testing "device-bind! throws on unknown source name"
    (device/defdevice :test/ctrl
      {:device/role :controller :midi/channel 1 :midi/cc []
       :cljseq/bind-sources {:sustain {:source :cc-64 :yields :boolean}}})
    (ctrl/defnode! [:some/param] :type :float)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown bind source"
         (device/device-bind! [:some/param]
                              {:device :test/ctrl :source :unknown-strip})))))

;; ---------------------------------------------------------------------------
;; get-device
;; ---------------------------------------------------------------------------

(deftest get-device-test
  (testing "get-device returns registry entry including resolvers"
    (device/defdevice :test/synth
      {:device/role :target :midi/channel 5
       :midi/cc [{:cc 23 :path [:vco1 :wave] :range [0 127]
                  :values [{:value 43 :label :saw}]}]})
    (let [entry (device/get-device :test/synth)]
      (is (= 5 (:channel entry)))
      (is (= :target (:role entry)))
      (is (= 43 (get-in entry [:resolvers [:vco1 :wave] :saw])))))
  (testing "get-device returns nil for unknown device"
    (is (nil? (device/get-device :no/such-device)))))

;; ---------------------------------------------------------------------------
;; NRPN registration
;; ---------------------------------------------------------------------------

(deftest defdevice-registers-nrpn-nodes-test
  (testing "ctrl node created for each NRPN entry with :midi-nrpn binding"
    (device/defdevice :test/synth
      {:device/role :target
       :midi/channel 1
       :midi/cc   []
       :midi/nrpn [{:nrpn 1 :path [:portamento]}
                   {:nrpn 0 :path [:eg :type] :bits 7 :range [0 127]}]})
    (let [port-node (ctrl/node-info [:test/synth :portamento])
          eg-node   (ctrl/node-info [:test/synth :eg :type])]
      (is (some? port-node) "portamento node registered")
      (is (= :int (:type port-node)))
      (is (= [0 16383] (get-in port-node [:node-meta :range]))
          "default 14-bit range")
      (let [b (first (:bindings port-node))]
        (is (= :midi-nrpn (:type b)))
        (is (= 1 (:nrpn b)))
        (is (= 14 (:bits b))))
      (is (some? eg-node) "eg type node registered")
      (let [b (first (:bindings eg-node))]
        (is (= 7 (:bits b)))
        (is (= [0 127] (:range b)))))))

(deftest defdevice-nrpn-reregistration-test
  (testing "re-registration cleans up NRPN bindings without error"
    (let [dmap {:device/role :target :midi/channel 1 :midi/cc []
                :midi/nrpn [{:nrpn 1 :path [:portamento]}]}]
      (device/defdevice :test/synth dmap)
      (is (= :test/synth (device/defdevice :test/synth dmap))
          "second defdevice should not throw"))))

(deftest minilogue-xd-nrpn-loads-test
  (testing "Minilogue XD NRPN entries register correctly"
    (device/defdevice :korg/minilogue-xd
      (device/load-device-map "devices/korg-minilogue-xd.edn"))
    (let [port-node (ctrl/node-info [:korg/minilogue-xd :portamento])
          eg-node   (ctrl/node-info [:korg/minilogue-xd :eg :type])]
      (is (some? port-node) "portamento NRPN node present")
      (is (some? eg-node)   "eg type NRPN node present")
      (is (= :midi-nrpn (:type (first (:bindings port-node))))))))
