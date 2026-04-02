; SPDX-License-Identifier: EPL-2.0
(ns cljseq.stochastic-test
  "Unit tests for cljseq.stochastic — DEJA VU ring buffer, X/T sections, defstochastic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.core       :as core]
            [cljseq.ctrl       :as ctrl]
            [cljseq.random     :as random]
            [cljseq.stochastic :as stochastic]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; make-deja-vu-source
;; ---------------------------------------------------------------------------

(deftest deja-vu-source-returns-fn-test
  (testing "make-deja-vu-source returns a callable"
    (let [src (stochastic/make-deja-vu-source #(rand-int 127) 8 (atom 0.0))]
      (is (fn? src)))))

(deftest deja-vu-source-produces-values-in-range-test
  (testing "values from deja-vu-source are drawn from gen-fn"
    (let [dv  (atom 0.0)
          src (stochastic/make-deja-vu-source #(rand-int 10) 8 dv)]
      (dotimes [_ 50]
        (is (<= 0 (src) 9))))))

(deftest deja-vu-locked-at-1-replays-test
  (testing "deja-vu=1.0 replays the same buffer value every time (no fresh draws)"
    (let [counter (atom 0)
          gen-fn  (fn [] (swap! counter inc) @counter)
          dv      (atom 0.0)
          src     (stochastic/make-deja-vu-source gen-fn 4 dv)]
      ;; Prime the buffer (4 fresh draws at dv=0)
      (dotimes [_ 4] (src))
      (let [draws-so-far @counter]
        ;; Lock deja-vu to 1.0 — subsequent calls must NOT draw fresh values
        (reset! dv 1.0)
        (dotimes [_ 20] (src))
        (is (= draws-so-far @counter) "no fresh draws after deja-vu locked to 1.0")))))

(deftest deja-vu-zero-always-fresh-test
  (testing "deja-vu=0.0 always calls gen-fn"
    (let [counter (atom 0)
          gen-fn  (fn [] (swap! counter inc) @counter)
          dv      (atom 0.0)
          src     (stochastic/make-deja-vu-source gen-fn 4 dv)]
      ;; 4 initial fills + 10 draws = 14 total calls
      (dotimes [_ 10] (src))
      (is (= 14 @counter) "each draw calls gen-fn when dv=0"))))

;; ---------------------------------------------------------------------------
;; stochastic-sequence (X section)
;; ---------------------------------------------------------------------------

(deftest stochastic-sequence-returns-midi-range-test
  (testing "stochastic-sequence returns values in specified MIDI range"
    (let [mel (stochastic/stochastic-sequence :spread 0.5 :bias 0.5 :range [48 72])]
      (dotimes [_ 100]
        (let [v (mel)]
          (is (integer? v) "returns integer")
          (is (<= 48 v 72) (str "out of range: " v)))))))

(deftest stochastic-sequence-default-range-test
  (testing "default range is [48 84]"
    (let [mel (stochastic/stochastic-sequence)]
      (dotimes [_ 100]
        (let [v (mel)]
          (is (<= 48 v 84) (str "out of range: " v)))))))

(deftest stochastic-sequence-with-scale-test
  (testing "stochastic-sequence with scale and steps>0.5 quantizes output"
    (let [ws  (random/weighted-scale :major)
          mel (stochastic/stochastic-sequence :spread 0.5 :bias 0.5
                                              :scale ws :steps 1.0
                                              :range [60 72])]
      (dotimes [_ 50]
        (let [v    (mel)
              pc   (mod v 12)
              ;; C major pitch classes
              cmaj #{0 2 4 5 7 9 11}]
          (is (cmaj pc) (str "note " v " (pc=" pc ") not in C major")))))))

(deftest stochastic-sequence-constant-at-zero-spread-test
  (testing "spread=0 always returns a value near the bias center"
    (let [;; bias=0.5 with range [60 60] → always 60
          mel (stochastic/stochastic-sequence :spread 0.0 :bias 0.5 :range [60 60])]
      (dotimes [_ 20]
        (is (= 60 (mel)) "constant output when spread=0 and range=[60 60]")))))

;; ---------------------------------------------------------------------------
;; stochastic-rhythm (T section)
;; ---------------------------------------------------------------------------

(deftest stochastic-rhythm-returns-boolean-test
  (testing "stochastic-rhythm returns true or false"
    (let [gates (stochastic/stochastic-rhythm :bias 0.5)]
      (dotimes [_ 50]
        (is (boolean? (gates)))))))

(deftest stochastic-rhythm-bias-high-fires-often-test
  (testing "bias=1.0 fires on every call"
    (let [gates (stochastic/stochastic-rhythm :bias 1.0)]
      (dotimes [_ 30]
        (is (true? (gates)) "bias=1 always fires")))))

(deftest stochastic-rhythm-bias-zero-never-fires-test
  (testing "bias=0.0 never fires"
    (let [gates (stochastic/stochastic-rhythm :bias 0.0)]
      (dotimes [_ 30]
        (is (false? (gates)) "bias=0 never fires")))))

(deftest stochastic-rhythm-independent-bernoulli-test
  (testing ":independent-bernoulli fires near bias=0.5 half the time"
    (let [gates   (stochastic/stochastic-rhythm :mode :independent-bernoulli :bias 0.5)
          results (repeatedly 1000 gates)
          fires   (count (filter true? results))]
      (is (< 400 fires 600) "roughly half fire at bias=0.5"))))

(deftest stochastic-rhythm-three-states-test
  (testing ":three-states with bias=1.0 fires about 2/3 of the time"
    (let [gates   (stochastic/stochastic-rhythm :mode :three-states :bias 1.0)
          results (repeatedly 1000 gates)
          fires   (count (filter true? results))]
      ;; P(fire) = 1.0 * 2/3 ≈ 0.667
      (is (< 550 fires 780) (str "fires=" fires " expected ~667")))))

;; ---------------------------------------------------------------------------
;; defstochastic macro
;; ---------------------------------------------------------------------------

(deftest defstochastic-creates-var-test
  (testing "defstochastic creates a var with context map"
    (stochastic/defstochastic test-gen-a
      :x-spread 0.5 :x-bias 0.5 :x-range [48 72]
      :t-mode :independent-bernoulli :t-bias 0.5
      :channels 2)
    (is (map? test-gen-a))
    (is (= 2 (:channels test-gen-a)))
    (is (vector? (:x-sources test-gen-a)))
    (is (vector? (:t-sources test-gen-a)))))

(deftest defstochastic-registers-ctrl-node-test
  (testing "defstochastic registers at [:stochastic name] in ctrl tree"
    (stochastic/defstochastic test-gen-b :channels 1)
    (is (some? (ctrl/node-info [:stochastic :test-gen-b])) "ctrl node registered")))

(deftest defstochastic-correct-channel-count-test
  (testing "defstochastic creates the specified number of X and T sources"
    (stochastic/defstochastic test-gen-c :channels 3)
    (is (= 3 (count (:x-sources test-gen-c))))
    (is (= 3 (count (:t-sources test-gen-c))))))

;; ---------------------------------------------------------------------------
;; next-x! / next-t!
;; ---------------------------------------------------------------------------

(deftest next-x-returns-integer-in-range-test
  (testing "next-x! returns integer in the configured MIDI range"
    (stochastic/defstochastic test-nx
      :x-spread 0.5 :x-bias 0.5 :x-range [60 72]
      :channels 2)
    (dotimes [_ 50]
      (let [v (stochastic/next-x! test-nx 0)]
        (is (integer? v))
        (is (<= 60 v 72) (str "x out of range: " v))))))

(deftest next-t-returns-boolean-test
  (testing "next-t! returns a boolean"
    (stochastic/defstochastic test-nt
      :t-bias 0.5 :channels 2)
    (dotimes [_ 20]
      (is (boolean? (stochastic/next-t! test-nt 0)))
      (is (boolean? (stochastic/next-t! test-nt 1))))))

;; ---------------------------------------------------------------------------
;; Complementary Bernoulli — mutual exclusion correlation (Q37)
;; ---------------------------------------------------------------------------

(deftest complementary-bernoulli-mutual-exclusion-test
  (testing ":complementary-bernoulli: ch0 and ch1 never both fire on same tick"
    (stochastic/defstochastic test-comp
      :t-mode :complementary-bernoulli :t-bias 0.5
      :channels 2)
    (dotimes [_ 100]
      (let [ch0 (stochastic/next-t! test-comp 0)
            ch1 (stochastic/next-t! test-comp 1)]
        (is (not (and ch0 ch1)) "ch0 and ch1 cannot both fire")))))

(deftest complementary-bernoulli-exactly-one-fires-test
  (testing ":complementary-bernoulli: exactly one of ch0/ch1 fires each tick"
    (stochastic/defstochastic test-comp2
      :t-mode :complementary-bernoulli :t-bias 0.5
      :channels 2)
    (dotimes [_ 100]
      (let [ch0 (stochastic/next-t! test-comp2 0)
            ch1 (stochastic/next-t! test-comp2 1)]
        (is (not= ch0 ch1) "exactly one of ch0/ch1 fires")))))

;; ---------------------------------------------------------------------------
;; stochastic-names
;; ---------------------------------------------------------------------------

(deftest stochastic-names-returns-seq-test
  (testing "stochastic-names returns a seq (possibly empty)"
    (is (sequential? (stochastic/stochastic-names)))))

(deftest defstochastic-registers-in-stochastic-names-test
  (testing "defstochastic registers name so stochastic-names includes it"
    (stochastic/defstochastic test-reg-a :channels 1)
    (is (some #{:test-reg-a} (stochastic/stochastic-names)))))
