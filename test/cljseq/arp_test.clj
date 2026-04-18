; SPDX-License-Identifier: EPL-2.0
(ns cljseq.arp-test
  "Unit tests for cljseq.arp — pattern registry, playback, and step engine."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [cljseq.arp    :as arp]
            [cljseq.core   :as core]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private played-notes (atom []))

(defn- capture-play! [note dur]
  (swap! played-notes conj {:midi (:pitch/midi note) :dur (:dur/beats note)})
  nil)

(defn- with-mock-play [f]
  (reset! played-notes [])
  (with-redefs [core/play! capture-play!]
    (f)))

;; ---------------------------------------------------------------------------
;; Pattern registry
;; ---------------------------------------------------------------------------

(deftest built-in-patterns-loaded-test
  (testing "built-in chord patterns are registered at load time"
    (doseq [kw [:up :down :bounce :alberti :waltz-bass
                :broken-triad :guitar-pick :jazz-stride
                :montuno :raga-alap :euclid-5-8]]
      (is (some? (arp/get-pattern kw))
          (str "expected pattern " kw " to be registered"))))
  (testing ":alberti has correct :order and :type"
    (let [p (arp/get-pattern :alberti)]
      (is (= :chord (:type p)))
      (is (= [0 2 1 2] (:order p))))))

(deftest hydrasynth-phrases-loaded-test
  (testing "all 64 Hydrasynth phrases are registered"
    (doseq [n (range 1 65)]
      (let [kw (keyword (format "phrase-%02d" n))]
        (is (some? (arp/get-pattern kw))
            (str "expected " kw " to be registered")))))
  (testing ":phrase-01 has :steps with semitone data"
    (let [p (arp/get-pattern :phrase-01)]
      (is (= :phrase (:type p)))
      (is (seq (:steps p)))
      (is (every? #(or (:semi %) (:rest %)) (:steps p))))))

(deftest register-custom-pattern-test
  (testing "register! adds a pattern retrievable by get-pattern"
    (let [kw :test-custom-pattern]
      (arp/register! kw {:type :chord :order [0 1 2] :rhythm [1 1 1] :dur 3/4})
      (is (= [0 1 2] (:order (arp/get-pattern kw)))))))

(deftest ls-returns-sorted-list-test
  (testing "ls returns a sorted sequence of [keyword description] pairs"
    (let [result (arp/ls)]
      (is (seq result))
      (is (every? (fn [[k d]] (and (keyword? k) (string? d))) result))
      ;; should be sorted by keyword
      (is (= result (sort-by first result))))))

;; ---------------------------------------------------------------------------
;; Playback — chord patterns
;; ---------------------------------------------------------------------------

(deftest play-chord-pattern-note-count-test
  (testing "play! :alberti against a triad plays 4 notes"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj note) nil)
                    cljseq.loop/sleep! (fn [_] nil)]
        (arp/play! :alberti [60 64 67]))
      (is (= 4 (count @notes))))))

(deftest play-chord-pattern-pitch-mapping-test
  (testing "play! :alberti [0 2 1 2] on C major gives root-fifth-third-fifth"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    cljseq.loop/sleep! (fn [_] nil)]
        (arp/play! :alberti [60 64 67]))
      ;; order [0 2 1 2] → chord[0]=60, chord[2]=67, chord[1]=64, chord[2]=67
      (is (= [60 67 64 67] @notes)))))

(deftest play-chord-wraps-octave-test
  (testing "order index beyond chord size wraps up an octave"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    cljseq.loop/sleep! (fn [_] nil)]
        ;; :up with 4-note chord in pattern with order [0 1 2 3 4]
        (arp/play! {:type :chord :order [0 1 2 3 4] :rhythm [1 1 1 1 1] :dur 3/4}
                   [60 64 67]))
      ;; chord has 3 notes; index 3 wraps → chord[0]+12=72; index 4 → chord[1]+12=76
      (is (= [60 64 67 72 76] @notes)))))

;; ---------------------------------------------------------------------------
;; Playback — phrase patterns
;; ---------------------------------------------------------------------------

(deftest play-phrase-root-offset-test
  (testing "play! :phrase-01 with root=60 transposes semitones from 60"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    cljseq.loop/sleep! (fn [_] nil)]
        (arp/play! :phrase-01 {:root 60}))
      (let [p     (arp/get-pattern :phrase-01)
            steps (filter #(not (:rest %)) (:steps p))
            expected (map #(+ 60 (:semi %)) steps)]
        (is (= (vec expected) @notes))))))

(deftest play-phrase-rest-steps-skipped-test
  (testing "rest steps are not played (no pitch/midi in play! call)"
    (let [notes (atom [])
          test-pattern {:type  :phrase
                        :steps [{:semi 0 :beats 1}
                                {:rest true :beats 1}
                                {:semi 7 :beats 1}]}]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    cljseq.loop/sleep! (fn [_] nil)]
        (arp/play! test-pattern {:root 60}))
      ;; Only notes at offset 0 and 7 from root should have been played
      (is (= [60 67] @notes)))))

(deftest play-phrase-rate-test
  (testing ":rate 2.0 doubles all step durations"
    (let [sleeps (atom [])]
      (with-redefs [core/play!  (fn [_ & _] nil)
                    cljseq.loop/sleep! (fn [b] (swap! sleeps conj b) nil)]
        (arp/play! {:type  :phrase
                    :steps [{:semi 0 :beats 1}
                            {:semi 4 :beats 1}]}
                   {:root 60}
                   :rate 2.0))
      (is (= [2.0 2.0] @sleeps)))))

(deftest play-phrase-dur-gate-test
  (testing ":dur on a phrase pattern controls the gate fraction"
    (let [gates (atom [])]
      (with-redefs [core/play!         (fn [note & _] (swap! gates conj (:dur/beats note)) nil)
                    cljseq.loop/sleep! (fn [_] nil)]
        (arp/play! {:type  :phrase
                    :dur   1/2
                    :steps [{:semi 0 :beats 1}
                            {:semi 4 :beats 2}]}
                   {:root 60}))
      ;; gate = beats × :dur (1/2): 1×1/2=0.5, 2×1/2=1.0
      (is (= [0.5 1.0] (mapv double @gates))))))

;; ---------------------------------------------------------------------------
;; arp-loop! / stop-arp!
;; ---------------------------------------------------------------------------

(deftest arp-loop-and-stop-test
  (testing "arp-loop! returns a stoppable handle; stop-arp! sets :running? false"
    (with-redefs [core/play!         (fn [_ & _] nil)
                  cljseq.loop/sleep! (fn [_] (Thread/sleep 5) nil)]
      (let [handle (arp/arp-loop! :alberti [60 64 67])]
        (is (map? handle))
        (is (true? @(:running? handle)))
        (is (future? (:future handle)))
        (arp/stop-arp! handle)
        (is (false? @(:running? handle)))
        (is (future-cancelled? (:future handle)))))))

;; ---------------------------------------------------------------------------
;; next-step! stateful engine
;; ---------------------------------------------------------------------------

(deftest next-step-chord-advances-test
  (testing "next-step! cycles through :alberti order"
    (let [state (arp/make-arp-state :alberti [60 64 67])]
      ;; [0 2 1 2] → 60, 67, 64, 67, then wraps
      (is (= 60 (:pitch/midi (arp/next-step! state))))
      (is (= 67 (:pitch/midi (arp/next-step! state))))
      (is (= 64 (:pitch/midi (arp/next-step! state))))
      (is (= 67 (:pitch/midi (arp/next-step! state))))
      ;; wraps back
      (is (= 60 (:pitch/midi (arp/next-step! state)))))))

(deftest next-step-phrase-advances-test
  (testing "next-step! cycles through phrase steps"
    (let [state (arp/make-arp-state :phrase-01 {:root 60})]
      (let [p     (arp/get-pattern :phrase-01)
            steps (:steps p)
            n     (count steps)]
        ;; Advance through all steps; verify no exceptions and wrap-around
        (dotimes [i (* 2 n)]  ; two full cycles
          (let [result (arp/next-step! state)]
            (is (map? result))
            (is (number? (:beats result)))))))))

(deftest next-step-rest-returns-no-pitch-test
  (testing "next-step! on a rest step returns no :pitch/midi"
    (let [state (arp/make-arp-state
                  {:type  :phrase
                   :steps [{:rest true :beats 1}
                            {:semi 7 :beats 1}]}
                  {:root 60})]
      (let [rest-result (arp/next-step! state)]
        (is (nil? (:pitch/midi rest-result)))
        (is (= 1.0 (double (:beats rest-result)))))
      (let [note-result (arp/next-step! state)]
        (is (= 67 (:pitch/midi note-result)))))))

;; ---------------------------------------------------------------------------
;; Pattern total-beats sanity
;; ---------------------------------------------------------------------------

(deftest phrase-total-beats-test
  (testing "all 64 Hydrasynth phrases have steps that sum to positive beats"
    (doseq [n (range 1 65)]
      (let [kw    (keyword (format "phrase-%02d" n))
            p     (arp/get-pattern kw)
            total (reduce + (map #(double (:beats % 0)) (:steps p)))]
        (is (pos? total) (str kw " has zero total beats"))))))
