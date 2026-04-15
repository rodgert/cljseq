; SPDX-License-Identifier: EPL-2.0
(ns cljseq.sc-test
  "Tests for cljseq.sc — sclang code generation and compile-synth :sc backend."
  (:require [clojure.test   :refer [deftest is testing]]
            [clojure.set    :as set]
            [clojure.string :as str]
            [cljseq.synth   :as synth]
            [cljseq.core    :as core]
            [cljseq.sc      :as sc]))

;; ---------------------------------------------------------------------------
;; synthdef-str — code generation
;; ---------------------------------------------------------------------------

(deftest synthdef-str-basic-test
  (testing "synthdef-str produces a SynthDef string"
    (let [s (sc/synthdef-str :sine)]
      (is (string? s))
      (is (str/starts-with? s "SynthDef(\\sine"))
      (is (str/includes? s ".add;")))))

(deftest synthdef-str-args-test
  (testing "synthdef-str includes all arg names with defaults"
    (let [s (sc/synthdef-str :sine)]
      (is (str/includes? s "freq="))
      (is (str/includes? s "amp="))
      (is (str/includes? s "gate=1")))))

(deftest synthdef-str-all-builtins-test
  (testing "synthdef-str generates valid strings for all built-in synths"
    (doseq [name [:beep :sine :saw-pad :blade :prophet :supersaw :dull-bell :fm :tb303 :perc]]
      (testing name
        (let [s (sc/synthdef-str name)]
          (is (string? s) (str name " should produce a string"))
          (is (str/includes? s "SynthDef") (str name " should contain SynthDef"))
          (is (str/includes? s ".add;")    (str name " should end with .add;")))))))

(deftest synthdef-str-unknown-synth-test
  (testing "synthdef-str throws for unknown synth"
    (is (thrown? Exception (sc/synthdef-str :not-a-real-synth)))))

;; ---------------------------------------------------------------------------
;; UGen code generation
;; ---------------------------------------------------------------------------

(deftest sine-osc-generation-test
  (testing "SinOsc appears in sine synth sclang"
    (is (str/includes? (sc/synthdef-str :sine) "SinOsc.ar"))))

(deftest saw-generation-test
  (testing "Saw.ar appears in saw-pad synth"
    (is (str/includes? (sc/synthdef-str :saw-pad) "Saw.ar"))))

(deftest env-gen-done-action-test
  (testing "EnvGen.ar includes doneAction: 2"
    (let [s (sc/synthdef-str :sine)]
      (is (str/includes? s "doneAction: 2")))))

(deftest adsr-generation-test
  (testing "Env.adsr appears in padlike synths"
    (is (str/includes? (sc/synthdef-str :sine)    "Env.adsr"))
    (is (str/includes? (sc/synthdef-str :saw-pad) "Env.adsr"))
    (is (str/includes? (sc/synthdef-str :blade)   "Env.adsr"))
    (is (str/includes? (sc/synthdef-str :prophet) "Env.adsr"))))

(deftest perc-env-generation-test
  (testing "Env.perc appears in :perc synth"
    (is (str/includes? (sc/synthdef-str :perc) "Env.perc"))))

(deftest pan2-generation-test
  (testing "Pan2.ar appears in stereo synths"
    (is (str/includes? (sc/synthdef-str :sine)    "Pan2.ar"))
    (is (str/includes? (sc/synthdef-str :saw-pad) "Pan2.ar"))))

(deftest mix-generation-test
  (testing "Mix.ar appears in multi-oscillator synths"
    (is (str/includes? (sc/synthdef-str :saw-pad) "Mix.ar"))
    (is (str/includes? (sc/synthdef-str :blade)   "Mix.ar"))))

(deftest lpf-generation-test
  (testing "LPF appears in blade synth"
    (is (str/includes? (sc/synthdef-str :blade) "LPF.ar"))))

(deftest rlpf-generation-test
  (testing "RLPF appears in prophet synth"
    (is (str/includes? (sc/synthdef-str :prophet) "RLPF.ar"))))

(deftest var-saw-generation-test
  (testing "VarSaw appears in blade synth"
    (is (str/includes? (sc/synthdef-str :blade) "VarSaw.ar"))))

(deftest out-ar-generation-test
  (testing "Out.ar appears in all synths"
    (doseq [name [:sine :saw-pad :blade :perc :prophet]]
      (is (str/includes? (sc/synthdef-str name) "Out.ar")))))

(deftest math-operators-test
  (testing "math operators are emitted inline"
    (synth/defsynth! ::math-test
      {:args  {:freq 440 :detune 0.5}
       :graph [:out 0 [:saw [:* :freq [:+ 1.0 [:/ :detune 100.0]]]]]})
    (let [s (sc/synthdef-str ::math-test)]
      (is (str/includes? s " * "))
      (is (str/includes? s " + "))
      (is (str/includes? s " / ")))))

;; ---------------------------------------------------------------------------
;; compile-synth :sc multimethod
;; ---------------------------------------------------------------------------

(deftest compile-synth-sc-test
  (testing "compile-synth :sc returns same string as synthdef-str"
    (is (= (sc/synthdef-str :sine)
           (synth/compile-synth :sc :sine)))))

;; ---------------------------------------------------------------------------
;; Custom synth roundtrip
;; ---------------------------------------------------------------------------

(deftest custom-synth-generates-test
  (testing "a custom-defined synth compiles to sclang correctly"
    (synth/defsynth! ::custom-sc-test
      {:args  {:freq 220 :amp 0.3 :attack 0.5 :release 3.0}
       :graph [:out 0
                [:pan2
                 [:* [:env-gen [:adsr :attack 0.1 0.7 :release] :gate :amp]
                     [:sin-osc :freq]]
                 0.0]]})
    (let [s (sc/synthdef-str ::custom-sc-test)]
      (is (string? s))
      (is (str/includes? s "SinOsc.ar"))
      (is (str/includes? s "Env.adsr"))
      (is (str/includes? s "EnvGen.ar"))
      (is (str/includes? s "freq=220.0"))
      (is (str/includes? s "amp=0.3")))))

;; ---------------------------------------------------------------------------
;; sc-status (no connection required)
;; ---------------------------------------------------------------------------

(deftest sc-status-returns-map-test
  (testing "sc-status returns a map with expected keys"
    (let [st (sc/sc-status)]
      (is (map? st))
      (is (contains? st :host))
      (is (contains? st :sc-port))
      (is (contains? st :lang-port))
      (is (contains? st :connected)))))

;; ---------------------------------------------------------------------------
;; ensure-synthdef! — lazy loading
;; ---------------------------------------------------------------------------

(deftest ensure-synthdef-no-op-when-disconnected-test
  (testing "ensure-synthdef! does nothing when SC is not connected"
    ;; sc-state is disconnected by default in tests
    (let [sent (atom [])]
      (with-redefs [cljseq.sc/send-synthdef! (fn [n] (swap! sent conj n))]
        (sc/ensure-synthdef! :sine))
      (is (empty? @sent) "send-synthdef! must not be called when not connected"))))

(deftest ensure-synthdef-sends-once-test
  (testing "ensure-synthdef! sends the def exactly once per session"
    ;; Reset session state
    (reset! @#'sc/sent-synthdefs #{})
    (swap! @#'sc/sc-state assoc :connected true)
    (try
      (with-redefs [cljseq.osc/osc-send! (fn [& _])]
        (sc/ensure-synthdef! :sine)
        (sc/ensure-synthdef! :sine))  ; second call — no-op
      (is (contains? @@#'sc/sent-synthdefs :sine)
          "sent-synthdefs should record the sent name")
      (finally
        (swap! @#'sc/sc-state assoc :connected false)
        (reset! @#'sc/sent-synthdefs #{})))))

;; ---------------------------------------------------------------------------
;; sc-sync! — OSC handshake
;; ---------------------------------------------------------------------------

(deftest sc-sync-throws-when-disconnected-test
  (testing "sc-sync! throws when SC is not connected"
    (is (thrown? clojure.lang.ExceptionInfo (sc/sc-sync!)))))

(deftest sc-sync-delivers-synced-test
  (testing "sc-sync! returns :synced when /sc-ready arrives before timeout"
    (swap! @#'sc/sc-state assoc :connected true)
    (try
      (let [osc-sends (atom [])
            result    (atom nil)]
        (with-redefs [cljseq.osc/osc-running?        (constantly true)
                      cljseq.osc/osc-port             (constantly 57121)
                      cljseq.osc/osc-send!            (fn [& args] (swap! osc-sends conj args))
                      cljseq.osc/register-handler!    (fn [addr f] (f []) nil)]
          (reset! result (sc/sc-sync! :timeout-ms 2000)))
        (is (= :synced @result) "sc-sync! should return :synced")
        (is (some #(and (= (nth % 2) "/cmd")
                        (clojure.string/includes? (nth % 3) "s.sync"))
                  @osc-sends)
            "should send s.sync to sclang via /cmd"))
      (finally
        (swap! @#'sc/sc-state assoc :connected false)))))

(deftest sc-sync-times-out-test
  (testing "sc-sync! throws on timeout when no /sc-ready arrives"
    (swap! @#'sc/sc-state assoc :connected true)
    (try
      (with-redefs [cljseq.osc/osc-running?      (constantly true)
                    cljseq.osc/osc-port           (constantly 57121)
                    cljseq.osc/osc-send!          (fn [& _])
                    cljseq.osc/register-handler!  (fn [_ _])] ; never fires
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timed out"
                              (sc/sc-sync! :timeout-ms 100))))
      (finally
        (swap! @#'sc/sc-state assoc :connected false)))))

;; ---------------------------------------------------------------------------
;; ensure-synthdefs! — batch send + sync
;; ---------------------------------------------------------------------------

(deftest ensure-synthdefs-sends-all-unsent-test
  (testing "ensure-synthdefs! sends all defs not yet in sent-synthdefs"
    (reset! @#'sc/sent-synthdefs #{:sine})  ; :sine already sent
    (swap! @#'sc/sc-state assoc :connected true)
    (try
      (let [sent (atom [])]
        (with-redefs [cljseq.sc/send-synthdef! (fn [n] (swap! sent conj n))
                      cljseq.sc/sc-sync!       (fn [& _] :synced)]
          (sc/ensure-synthdefs! [:sine :blade :prophet])))
      ;; :sine already in sent-synthdefs — only :blade and :prophet should be sent
      (is (= #{:blade :prophet} (set/difference @@#'sc/sent-synthdefs #{:sine})))
      (finally
        (swap! @#'sc/sc-state assoc :connected false)
        (reset! @#'sc/sent-synthdefs #{})))))

(deftest ensure-synthdefs-no-op-when-all-sent-test
  (testing "ensure-synthdefs! is a no-op when all defs already sent"
    (reset! @#'sc/sent-synthdefs #{:sine :blade})
    (swap! @#'sc/sc-state assoc :connected true)
    (try
      (let [sync-called (atom false)]
        (with-redefs [cljseq.sc/send-synthdef! (fn [_] (throw (ex-info "should not send" {})))
                      cljseq.sc/sc-sync!       (fn [& _] (reset! sync-called true) :synced)]
          (sc/ensure-synthdefs! [:sine :blade]))
        (is (false? @sync-called) "sc-sync! should not be called when nothing to send"))
      (finally
        (swap! @#'sc/sc-state assoc :connected false)
        (reset! @#'sc/sent-synthdefs #{})))))

;; ---------------------------------------------------------------------------
;; apply-trajectory! — 3-arity SC form
;; ---------------------------------------------------------------------------

(deftest apply-trajectory-sc-form-test
  (testing "sc apply-trajectory! calls set-param! with sampled values"
    (core/start! :bpm 120)
    (try
      (let [calls     (atom [])
            ;; A constant ITemporalValue that always returns 0.5 and is already done
            const-traj (reify cljseq.clock/ITemporalValue
                         (sample [_ _beat] 0.5)
                         (next-edge [_ _beat] ##Inf))]
        (with-redefs [sc/sc-connected? (constantly true)
                      sc/set-param!    (fn [node param val]
                                         (swap! calls conj {:node node :param param :val val}))]
          (let [cancel (sc/apply-trajectory! 1001 :cutoff const-traj)]
            (Thread/sleep 80)  ; allow at least one tick
            (cancel)
            (Thread/sleep 20)))
        (is (seq @calls) "set-param! must be called at least once")
        (is (every? #(= {:node 1001 :param :cutoff :val 0.5} %) @calls)
            "every call must target node 1001, param :cutoff, value 0.5"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; play! → SC dispatch (registered by sc namespace load)
;; ---------------------------------------------------------------------------

(deftest play-routes-synth-to-sc-test
  (testing "play! routes a :synth step map to the SC backend"
    (core/start! :bpm 120)
    (try
      (let [calls (atom [])]
        (with-redefs [sc/sc-connected?   (constantly true)
                      sc/ensure-synthdef! (fn [_])
                      sc/sc-play!         (fn [event] (swap! calls conj event))]
          (binding [cljseq.loop/*virtual-time* 0.0]
            (core/play! {:synth :sine :pitch/midi 60 :dur/beats 1/4})))
        (is (= 1 (count @calls)) "sc-play! called exactly once")
        (is (contains? (first @calls) :synth) "event retains :synth key")
        (is (contains? (first @calls) :dur-ms) ":dur-ms computed from beats+bpm")
        (is (contains? (first @calls) :freq)   ":freq computed from :pitch/midi"))
      (finally (core/stop!)))))

(deftest play-non-synth-skips-sc-dispatch-test
  (testing "play! with no :synth key does not hit SC dispatch"
    (core/start! :bpm 120)
    (try
      (let [sc-calls (atom 0)]
        (with-redefs [sc/sc-play! (fn [_] (swap! sc-calls inc))
                      cljseq.sidecar/connected? (constantly false)]
          (binding [cljseq.loop/*virtual-time* 0.0]
            (core/play! {:pitch/midi 60 :dur/beats 1/4})))
        (is (zero? @sc-calls) "sc-play! must not be called for plain MIDI steps"))
      (finally (core/stop!)))))
