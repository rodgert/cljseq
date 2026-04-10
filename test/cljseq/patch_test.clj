; SPDX-License-Identifier: EPL-2.0
(ns cljseq.patch-test
  "Tests for cljseq.patch — registry, compile-patch, and cljseq.sc patch lifecycle."
  (:require [clojure.test   :refer [deftest is testing]]
            [clojure.string :as str]
            [cljseq.patch   :as patch]
            [cljseq.sc      :as sc]
            [cljseq.osc     :as osc]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest defpatch-registers-test
  (testing "defpatch! stores and retrieves a patch"
    (patch/defpatch! ::reg-test
      {:nodes [{:id :a :synth :sine :args {:freq 440}}]})
    (is (some? (patch/get-patch ::reg-test)))
    (is (contains? (set (patch/patch-names)) ::reg-test))))

(deftest defpatch-name-embedded-test
  (testing "defpatch! embeds :name into the stored map"
    (patch/defpatch! ::name-test
      {:nodes [{:id :a :synth :sine :args {}}]})
    (is (= ::name-test (:name (patch/get-patch ::name-test))))))

(deftest defpatch-idempotent-test
  (testing "defpatch! replaces previous definition"
    (patch/defpatch! ::idem-test {:nodes [{:id :a :synth :sine-bus :args {:freq 220}}]})
    (patch/defpatch! ::idem-test {:nodes [{:id :a :synth :sine-bus :args {:freq 880}}]})
    (is (= 880 (get-in (patch/get-patch ::idem-test) [:nodes 0 :args :freq])))))

(deftest defpatch-requires-nodes-test
  (testing "defpatch! throws when :nodes is missing"
    (is (thrown? Exception (patch/defpatch! ::bad {:buses {}})))))

(deftest defpatch-requires-keyword-name-test
  (testing "defpatch! throws when name is not a keyword"
    (is (thrown? Exception (patch/defpatch! "not-a-keyword" {:nodes []})))))

;; ---------------------------------------------------------------------------
;; patch-params
;; ---------------------------------------------------------------------------

(deftest patch-params-test
  (testing "patch-params returns the :params map"
    (patch/defpatch! ::params-test
      {:nodes  [{:id :osc :synth :sine-bus :args {:freq 440}}]
       :params {:freq [:osc :freq] :amp [:osc :amp]}})
    (let [p (patch/patch-params ::params-test)]
      (is (map? p))
      (is (= [:osc :freq] (:freq p)))
      (is (= [:osc :amp]  (:amp p))))))

(deftest patch-params-nil-when-missing-test
  (testing "patch-params returns nil when :params is absent"
    (patch/defpatch! ::no-params {:nodes [{:id :a :synth :sine :args {}}]})
    (is (nil? (patch/patch-params ::no-params)))))

;; ---------------------------------------------------------------------------
;; compile-patch :sc
;; ---------------------------------------------------------------------------

(deftest compile-patch-sc-structure-test
  (testing "compile-patch :sc returns expected top-level keys"
    (patch/defpatch! ::sc-struct
      {:buses  {:bus-a {:channels 1 :rate :audio}}
       :nodes  [{:id :n :synth :sine-bus :args {:freq 440}
                 :out {:out-bus :bus-a}}]
       :params {:freq [:n :freq]}})
    (let [c (patch/compile-patch :sc ::sc-struct)]
      (is (= ::sc-struct  (:patch-name c)))
      (is (map?    (:buses  c)))
      (is (vector? (:nodes  c)))
      (is (map?    (:params c))))))

(deftest compile-patch-sc-merges-args-test
  (testing "compile-patch :sc merges :args, :in, :out into node :args"
    (patch/defpatch! ::sc-merge
      {:buses  {:b {:channels 1 :rate :audio}}
       :nodes  [{:id :x :synth :sine-bus
                 :args {:freq 330 :amp 0.4}
                 :out  {:out-bus :b}}]})
    (let [node (first (:nodes (patch/compile-patch :sc ::sc-merge)))]
      (is (= 330   (get-in node [:args :freq])))
      (is (= 0.4   (get-in node [:args :amp])))
      (is (= :b    (get-in node [:args :out-bus])) "bus-key stays as keyword placeholder"))))

(deftest compile-patch-sc-integer-out-passthrough-test
  (testing "integer values in :out pass through unchanged"
    (patch/defpatch! ::sc-int-out
      {:nodes [{:id :v :synth :reverb-bus :args {:room 0.5}
                :in  {:in-bus 8}
                :out {:out 0}}]})
    (let [node (first (:nodes (patch/compile-patch :sc ::sc-int-out)))]
      (is (= 0 (get-in node [:args :out])))
      (is (= 8 (get-in node [:args :in-bus]))))))

(deftest compile-patch-sc-node-order-test
  (testing "compile-patch :sc preserves node declaration order"
    (patch/defpatch! ::sc-order
      {:buses {:b {:channels 1 :rate :audio}}
       :nodes [{:id :first  :synth :sine-bus :args {} :out {:out-bus :b}}
               {:id :second :synth :reverb-bus :args {} :in {:in-bus :b} :out {:out 0}}]})
    (let [nodes (:nodes (patch/compile-patch :sc ::sc-order))]
      (is (= :first  (:id (nth nodes 0))))
      (is (= :second (:id (nth nodes 1)))))))

(deftest compile-patch-sc-buses-test
  (testing "compile-patch :sc includes the buses map"
    (patch/defpatch! ::sc-buses
      {:buses  {:main {:channels 2 :rate :audio}
                :ctrl {:channels 1 :rate :control}}
       :nodes  [{:id :n :synth :sine-bus :args {}}]})
    (let [c (patch/compile-patch :sc ::sc-buses)]
      (is (contains? (:buses c) :main))
      (is (contains? (:buses c) :ctrl))
      (is (= 2 (get-in c [:buses :main :channels])))
      (is (= :control (get-in c [:buses :ctrl :rate]))))))

(deftest compile-patch-unknown-test
  (testing "compile-patch throws for unknown patch name"
    (is (thrown? Exception (patch/compile-patch :sc ::does-not-exist-xyz)))))

(deftest compile-patch-unknown-backend-test
  (testing "compile-patch throws for unknown backend"
    (patch/defpatch! ::bknd-test {:nodes [{:id :a :synth :sine :args {}}]})
    (is (thrown? Exception (patch/compile-patch :unknown-backend ::bknd-test)))))

;; ---------------------------------------------------------------------------
;; compile-patch :pd
;; ---------------------------------------------------------------------------

(deftest compile-patch-pd-returns-text-test
  (testing "compile-patch :pd returns a map with :pd-text string"
    (patch/defpatch! ::pd-test
      {:nodes [{:id :osc :synth :sine-bus :args {:freq 440}}
               {:id :out :synth :reverb-bus :args {:mix 0.3}}]})
    (let [r (patch/compile-patch :pd ::pd-test)]
      (is (map? r))
      (is (string? (:pd-text r)))
      (is (map?    (:patch   r))))))

(deftest compile-patch-pd-canvas-header-test
  (testing "compile-patch :pd emits a #N canvas header"
    (patch/defpatch! ::pd-canvas
      {:nodes [{:id :a :synth :sine-bus :args {}}]})
    (let [text (:pd-text (patch/compile-patch :pd ::pd-canvas))]
      (is (str/starts-with? text "#N canvas")))))

(deftest compile-patch-pd-obj-lines-test
  (testing "compile-patch :pd emits one #X obj per node"
    (patch/defpatch! ::pd-obj
      {:nodes [{:id :a :synth :sine-bus :args {}}
               {:id :b :synth :reverb-bus :args {}}]})
    (let [text  (:pd-text (patch/compile-patch :pd ::pd-obj))
          lines (str/split-lines text)
          objs  (filter #(str/starts-with? % "#X obj") lines)]
      (is (= 2 (count objs))))))

(deftest compile-patch-pd-connect-lines-test
  (testing "compile-patch :pd emits #X connect lines between nodes"
    (patch/defpatch! ::pd-conn
      {:nodes [{:id :a :synth :sine-bus :args {}}
               {:id :b :synth :reverb-bus :args {}}
               {:id :c :synth :reverb-bus :args {}}]})
    (let [text  (:pd-text (patch/compile-patch :pd ::pd-conn))
          lines (str/split-lines text)
          conns (filter #(str/starts-with? % "#X connect") lines)]
      (is (= 2 (count conns)) "N-1 connect lines for N nodes"))))

;; ---------------------------------------------------------------------------
;; Built-in :reverb-chain preset
;; ---------------------------------------------------------------------------

(deftest reverb-chain-registered-test
  (testing ":reverb-chain is registered at load time"
    (is (some? (patch/get-patch :reverb-chain)))))

(deftest reverb-chain-structure-test
  (testing ":reverb-chain has two nodes, one bus, and correct params"
    (let [p (patch/get-patch :reverb-chain)]
      (is (= 2 (count (:nodes p))))
      (is (contains? (:buses p) :dry))
      (is (contains? (:params p) :freq))
      (is (contains? (:params p) :room))
      (is (contains? (:params p) :mix)))))

(deftest reverb-chain-compile-sc-test
  (testing ":reverb-chain compiles cleanly to :sc"
    (let [c (patch/compile-patch :sc :reverb-chain)]
      (is (= :reverb-chain (:patch-name c)))
      (is (= 2 (count (:nodes c))))
      (is (= :dry (get-in c [:nodes 0 :args :out-bus])) "osc node carries bus-key placeholder")
      (is (= :dry (get-in c [:nodes 1 :args :in-bus]))  "verb node carries bus-key placeholder"))))

;; ---------------------------------------------------------------------------
;; Bus allocation (cljseq.sc)
;; ---------------------------------------------------------------------------

(deftest sc-bus-allocates-audio-test
  (testing "sc-bus! allocates sequential audio bus indices starting at 8"
    ;; Reset to clean state for isolation
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (let [b1 (sc/sc-bus! ::bus-a)
          b2 (sc/sc-bus! ::bus-b)]
      (is (= :audio (:rate b1)))
      (is (= 1      (:channels b1)))
      (is (= 8      (:idx b1)))
      (is (= 9      (:idx b2)) "second bus follows first")
      (sc/free-bus! ::bus-a)
      (sc/free-bus! ::bus-b))))

(deftest sc-bus-allocates-control-test
  (testing "sc-bus! uses separate counter for :control buses"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (let [b (sc/sc-bus! ::ctrl-bus 1 :control)]
      (is (= :control (:rate b)))
      (is (= 0        (:idx b)))
      (sc/free-bus! ::ctrl-bus))))

(deftest sc-bus-multi-channel-test
  (testing "sc-bus! advances counter by channel count"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (let [b1 (sc/sc-bus! ::stereo 2 :audio)
          b2 (sc/sc-bus! ::mono   1 :audio)]
      (is (= 8  (:idx b1)))
      (is (= 10 (:idx b2)) "mono bus follows 2-channel stereo bus")
      (sc/free-bus! ::stereo)
      (sc/free-bus! ::mono))))

(deftest sc-bus-idx-test
  (testing "bus-idx returns the allocated index"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (sc/sc-bus! ::idx-test 1 :audio)
    (is (= 8 (sc/bus-idx ::idx-test)))
    (sc/free-bus! ::idx-test)))

(deftest free-bus-removes-test
  (testing "free-bus! removes the bus from tracking"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (sc/sc-bus! ::to-free)
    (sc/free-bus! ::to-free)
    (is (nil? (sc/bus-idx ::to-free)))))

;; ---------------------------------------------------------------------------
;; instantiate-patch! (mocked SC)
;; ---------------------------------------------------------------------------

(defn- capture-osc []
  (let [calls (atom [])]
    {:calls calls
     :osc-fn (fn [& args] (swap! calls conj (vec args)))}))

(deftest instantiate-patch-allocates-buses-test
  (testing "instantiate-patch! allocates all patch buses"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (reset! @#'sc/sent-synthdefs #{})
    (let [{:keys [calls osc-fn]} (capture-osc)]
      (with-redefs [sc/sc-connected?   (constantly true)
                    osc/osc-send!       osc-fn]
        (let [inst (sc/instantiate-patch! :reverb-chain)]
          (is (contains? (:buses inst) :dry))
          (is (integer? (get (:buses inst) :dry))))))))

(deftest instantiate-patch-sends-snew-per-node-test
  (testing "instantiate-patch! sends /s_new for each node"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (reset! @#'sc/sent-synthdefs #{})
    (let [{:keys [calls osc-fn]} (capture-osc)]
      (with-redefs [sc/sc-connected? (constantly true)
                    osc/osc-send!     osc-fn]
        (sc/instantiate-patch! :reverb-chain))
      (let [snew-calls (filter #(= "/s_new" (nth % 2)) @calls)]
        (is (= 2 (count snew-calls)) "one /s_new per node")))))

(deftest instantiate-patch-resolves-bus-keys-test
  (testing "instantiate-patch! resolves bus-key placeholders to integer indices"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (reset! @#'sc/sent-synthdefs #{})
    (let [{:keys [calls osc-fn]} (capture-osc)]
      (with-redefs [sc/sc-connected? (constantly true)
                    osc/osc-send!     osc-fn]
        (sc/instantiate-patch! :reverb-chain))
      ;; Find the /s_new calls and check that bus index 8.0 appears (not the keyword :dry)
      (let [snew-args (mapcat #(drop 3 %) (filter #(= "/s_new" (nth % 2)) @calls))
            values    (set (filter number? snew-args))]
        (is (contains? values 8.0) "allocated bus index 8 appears in /s_new args")))))

(deftest instantiate-patch-returns-instance-test
  (testing "instantiate-patch! returns map with :patch-name :buses :nodes :params"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0 :named {}})
    (reset! @#'sc/sent-synthdefs #{})
    (with-redefs [sc/sc-connected? (constantly true)
                  osc/osc-send!     (fn [& _])]
      (let [inst (sc/instantiate-patch! :reverb-chain)]
        (is (= :reverb-chain (:patch-name inst)))
        (is (map? (:buses  inst)))
        (is (map? (:nodes  inst)))
        (is (map? (:params inst)))
        (is (contains? (:nodes inst) :osc))
        (is (contains? (:nodes inst) :verb))))))

(deftest instantiate-patch-not-connected-test
  (testing "instantiate-patch! throws when SC is not connected"
    (with-redefs [sc/sc-connected? (constantly false)]
      (is (thrown? Exception (sc/instantiate-patch! :reverb-chain))))))

;; ---------------------------------------------------------------------------
;; set-patch-param!
;; ---------------------------------------------------------------------------

(deftest set-patch-param-calls-set-param-test
  (testing "set-patch-param! calls set-param! on the correct node"
    (let [calls   (atom [])
          fake-inst {:patch-name :reverb-chain
                     :buses  {:dry 8}
                     :nodes  {:osc 1001 :verb 1002}
                     :params {:freq [:osc :freq]
                              :room [:verb :room]}}]
      (with-redefs [sc/set-param! (fn [node param val]
                                    (swap! calls conj {:node node :param param :val val}))]
        (sc/set-patch-param! fake-inst :freq 660)
        (sc/set-patch-param! fake-inst :room 0.9))
      (is (= {:node 1001 :param :freq :val 660} (first @calls)))
      (is (= {:node 1002 :param :room :val 0.9} (second @calls))))))

(deftest set-patch-param-unknown-param-test
  (testing "set-patch-param! throws for unknown param key"
    (let [fake-inst {:nodes {:osc 1001} :params {:freq [:osc :freq]}}]
      (with-redefs [sc/set-param! (fn [& _])]
        (is (thrown? Exception (sc/set-patch-param! fake-inst :not-a-param 0.5)))))))

;; ---------------------------------------------------------------------------
;; free-patch!
;; ---------------------------------------------------------------------------

(deftest free-patch-frees-all-nodes-test
  (testing "free-patch! calls free-synth! for every node"
    (let [freed (atom #{})
          fake-inst {:patch-name :reverb-chain
                     :buses  {:dry 8}
                     :nodes  {:osc 1001 :verb 1002}
                     :params {}}]
      (with-redefs [sc/free-synth! (fn [id] (swap! freed conj id))]
        (sc/free-patch! fake-inst))
      (is (contains? @freed 1001))
      (is (contains? @freed 1002)))))

(deftest free-patch-removes-buses-test
  (testing "free-patch! removes buses from allocation tracking"
    (reset! @#'sc/bus-alloc {:audio-next 8 :control-next 0
                              :named {:dry {:idx 8 :channels 1 :rate :audio}}})
    (let [fake-inst {:patch-name :reverb-chain
                     :buses  {:dry 8}
                     :nodes  {:osc 1001}
                     :params {}}]
      (with-redefs [sc/free-synth! (fn [_])]
        (sc/free-patch! fake-inst))
      (is (nil? (sc/bus-idx :dry)) "bus :dry should be removed after free-patch!"))))

;; ---------------------------------------------------------------------------
;; New bus-aware synths registered
;; ---------------------------------------------------------------------------

(deftest sine-bus-registered-test
  (testing ":sine-bus is registered in the synth registry"
    (is (some? (cljseq.synth/get-synth :sine-bus)))))

(deftest reverb-bus-registered-test
  (testing ":reverb-bus is registered in the synth registry"
    (is (some? (cljseq.synth/get-synth :reverb-bus)))))

(deftest sine-bus-has-out-bus-arg-test
  (testing ":sine-bus has :out-bus in its args"
    (is (contains? (:args (cljseq.synth/get-synth :sine-bus)) :out-bus))))

(deftest reverb-bus-has-in-bus-arg-test
  (testing ":reverb-bus has :in-bus in its args"
    (is (contains? (:args (cljseq.synth/get-synth :reverb-bus)) :in-bus))))

(deftest sine-bus-compiles-sc-test
  (testing ":sine-bus compiles to valid SC with Out.ar(out_bus, ...)"
    (let [s (sc/synthdef-str :sine-bus)]
      (is (clojure.string/includes? s "out_bus"))
      (is (clojure.string/includes? s "Out.ar")))))

(deftest reverb-bus-compiles-sc-test
  (testing ":reverb-bus compiles to valid SC with In.ar(in_bus, ...)"
    (let [s (sc/synthdef-str :reverb-bus)]
      (is (clojure.string/includes? s "in_bus"))
      (is (clojure.string/includes? s "In.ar"))
      (is (clojure.string/includes? s "FreeVerb.ar")))))
