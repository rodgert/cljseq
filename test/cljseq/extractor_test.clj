; SPDX-License-Identifier: EPL-2.0
(ns cljseq.extractor-test
  "Tests for cljseq.extractor — Phase 1: threshold arithmetic, hysteresis,
  direction filter, and event dispatch."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.extractor :as ext]))

;; ---------------------------------------------------------------------------
;; Helpers — access private vars
;; ---------------------------------------------------------------------------

(def ^:private hysteresis-ok?     @#'ext/hysteresis-ok?)
(def ^:private direction-allowed? @#'ext/direction-allowed?)
(def ^:private poll-once!         @#'ext/poll-once!)

;; ---------------------------------------------------------------------------
;; compute-thresholds
;; ---------------------------------------------------------------------------

(deftest compute-thresholds-full-space
  (testing "full-space comb (space=1.0) — N channels → N-1 evenly-spaced thresholds"
    (let [t (ext/compute-thresholds 4 1.0 0.5)]
      (is (= 3 (count t)))
      (is (< (Math/abs (- (nth t 0) 0.25)) 1e-9))
      (is (< (Math/abs (- (nth t 1) 0.50)) 1e-9))
      (is (< (Math/abs (- (nth t 2) 0.75)) 1e-9)))))

(deftest compute-thresholds-half-space
  (testing "half-space comb spans [0.25, 0.75] for space=0.5, center=0.5"
    (let [t (ext/compute-thresholds 4 0.5 0.5)]
      (is (= 3 (count t)))
      ;; step = (0.75 - 0.25) / 4 = 0.125; thresholds at 0.375, 0.5, 0.625
      (is (< (Math/abs (- (nth t 0) 0.375)) 1e-9))
      (is (< (Math/abs (- (nth t 1) 0.500)) 1e-9))
      (is (< (Math/abs (- (nth t 2) 0.625)) 1e-9)))))

(deftest compute-thresholds-comb-clamped-low
  (testing "comb clamped to [0.0, 0.5] when center=0.1 space=0.5"
    (let [t (ext/compute-thresholds 2 0.5 0.1)]
      ;; comb-low=max(0, 0.1-0.25)=0.0, comb-high=min(1, 0.1+0.25)=0.35
      (is (= 1 (count t)))
      (is (< (Math/abs (- (nth t 0) 0.175)) 1e-9)))))

(deftest compute-thresholds-comb-clamped-high
  (testing "comb clamped at 1.0 when center+space/2 > 1.0"
    (let [t (ext/compute-thresholds 2 0.5 0.9)]
      ;; comb-low=0.65, comb-high=min(1, 1.15)=1.0
      (is (= 1 (count t)))
      (is (< (Math/abs (- (nth t 0) 0.825)) 1e-9)))))

(deftest compute-thresholds-single-channel
  (testing "1 channel → 0 thresholds"
    (is (= [] (ext/compute-thresholds 1 1.0 0.5)))))

(deftest compute-thresholds-two-channels
  (testing "2 channels → 1 threshold at midpoint"
    (let [t (ext/compute-thresholds 2 1.0 0.5)]
      (is (= 1 (count t)))
      (is (< (Math/abs (- (nth t 0) 0.5)) 1e-9)))))

(deftest compute-thresholds-eight-channels
  (testing "8 channels (GTE default) → 7 thresholds at 1/8 spacing"
    (let [t (ext/compute-thresholds 8 1.0 0.5)]
      (is (= 7 (count t)))
      (doseq [[i thr] (map-indexed vector t)]
        (is (< (Math/abs (- thr (* (inc i) (/ 1.0 8)))) 1e-9)
            (str "threshold " i " wrong"))))))

;; ---------------------------------------------------------------------------
;; value->channel
;; ---------------------------------------------------------------------------

(deftest value->channel-basic
  (testing "value maps to correct 1-indexed channel for 4-channel comb"
    (let [thresholds [0.25 0.50 0.75]]
      ;; ch1: [0, 0.25), ch2: [0.25, 0.50), ch3: [0.50, 0.75), ch4: [0.75, 1.0]
      (is (= 1 (ext/value->channel 0.0  thresholds)))
      (is (= 1 (ext/value->channel 0.24 thresholds)))
      (is (= 2 (ext/value->channel 0.25 thresholds)))
      (is (= 2 (ext/value->channel 0.49 thresholds)))
      (is (= 3 (ext/value->channel 0.50 thresholds)))
      (is (= 4 (ext/value->channel 0.75 thresholds)))
      (is (= 4 (ext/value->channel 1.0  thresholds))))))

(deftest value->channel-single-channel
  (testing "no thresholds → everything in channel 1"
    (is (= 1 (ext/value->channel 0.0 [])))
    (is (= 1 (ext/value->channel 0.5 [])))
    (is (= 1 (ext/value->channel 1.0 [])))))

(deftest value->channel-at-threshold-boundary
  (testing "value exactly on threshold falls into higher channel"
    ;; threshold at 0.5 — value 0.5 is > nothing below 0.5, so count=1, ch=2
    (let [thresholds [0.5]]
      (is (= 1 (ext/value->channel 0.4999 thresholds)))
      (is (= 2 (ext/value->channel 0.5    thresholds))))))

;; ---------------------------------------------------------------------------
;; hysteresis-ok?
;; ---------------------------------------------------------------------------

(deftest hysteresis-ok-no-crossing
  (testing "same channel → false regardless of value"
    (let [thresholds [0.25 0.50 0.75]]
      (is (false? (hysteresis-ok? 0.3 thresholds 2 2 0.1))))))

(deftest hysteresis-ok-single-channel
  (testing "no thresholds → no crossings ever"
    (is (false? (hysteresis-ok? 0.5 [] 1 1 0.1)))))

(deftest hysteresis-ok-rising-clear
  (testing "rising crossing well past threshold + dead-zone → true"
    ;; thresholds=[0.5], spacing≈0.5 (single), hyst=0.01 → dead-zone≈0.005
    ;; boundary at 0.5, value=0.6, new-ch=2, last-ch=1
    ;; threshold=0.5, dead-zone=0.5*0.01=0.005, check v > 0.505 → 0.6 > 0.505 ✓
    (let [thresholds [0.5]]
      (is (true? (hysteresis-ok? 0.6 thresholds 1 2 0.01))))))

(deftest hysteresis-ok-rising-inside-dead-zone
  (testing "rising crossing just inside dead-zone → false"
    ;; boundary at 0.25, spacing=(0.5-0.25)=0.25, hyst=0.1 → dead-zone=0.025
    ;; check v > 0.275, value=0.27 → false
    (let [thresholds [0.25 0.50 0.75]]
      (is (false? (hysteresis-ok? 0.27 thresholds 1 2 0.1))))))

(deftest hysteresis-ok-falling-clear
  (testing "falling crossing well below threshold − dead-zone → true"
    ;; boundary at 0.5, value=0.4, new-ch=1, last-ch=2
    ;; check v < 0.495 → 0.4 < 0.495 ✓
    (let [thresholds [0.5]]
      (is (true? (hysteresis-ok? 0.4 thresholds 2 1 0.01))))))

(deftest hysteresis-ok-falling-inside-dead-zone
  (testing "falling value just inside dead-zone → false"
    ;; boundary at 0.25, spacing=0.25, hyst=0.1 → dead-zone=0.025
    ;; check v < 0.225, value=0.23 → false
    (let [thresholds [0.25 0.50 0.75]]
      (is (false? (hysteresis-ok? 0.23 thresholds 2 1 0.1))))))

(deftest hysteresis-ok-zero-hyst
  (testing "zero hysteresis — any crossing is accepted immediately"
    (let [thresholds [0.5]]
      (is (true? (hysteresis-ok? 0.5001 thresholds 1 2 0.0)))
      (is (true? (hysteresis-ok? 0.4999 thresholds 2 1 0.0))))))

;; ---------------------------------------------------------------------------
;; direction-allowed?
;; ---------------------------------------------------------------------------

(deftest direction-filter-both
  (testing ":both allows rising and falling"
    (is (true? (direction-allowed? :rising  :both)))
    (is (true? (direction-allowed? :falling :both)))))

(deftest direction-filter-rising-only
  (testing ":rising blocks falling crossings"
    (is (true?  (direction-allowed? :rising  :rising)))
    (is (false? (direction-allowed? :falling :rising)))))

(deftest direction-filter-falling-only
  (testing ":falling blocks rising crossings"
    (is (false? (direction-allowed? :rising  :falling)))
    (is (true?  (direction-allowed? :falling :falling)))))

;; ---------------------------------------------------------------------------
;; poll-once! — integration of detect + emit
;; ---------------------------------------------------------------------------

(deftest poll-once-first-sample-no-event
  (testing "on first poll (last-ch=0) no crossing is fired regardless of value"
    (let [events   (atom [])
          on-cross {:type :fn
                    :f    (fn [ch prev dir beat]
                            (swap! events conj {:ch ch :prev prev :dir dir}))}
          thresholds [0.5]
          [new-ch _] (poll-once! 0.0 (constantly 0.8) thresholds on-cross
                                 :both 0.01 0 -1.0)]
      ;; first sample: last-ch=0 so crossing is suppressed
      (is (empty? @events))
      ;; but the channel should be resolved
      (is (= 2 new-ch)))))

(deftest poll-once-rising-crossing-fires-event
  (testing "value moves from ch1 to ch2 — crossing event fires"
    (let [events   (atom [])
          on-cross {:type :fn
                    :f    (fn [ch prev dir beat]
                            (swap! events conj {:ch ch :prev prev :dir dir}))}
          thresholds [0.5]
          ;; last-ch=1, last-v=0.3; new value=0.8 → ch2
          [new-ch new-v] (poll-once! 1.0 (constantly 0.8) thresholds on-cross
                                     :both 0.01 1 0.3)]
      (is (= 1 (count @events)))
      (is (= {:ch 2 :prev 1 :dir :rising} (first @events)))
      (is (= 2 new-ch))
      (is (< (Math/abs (- new-v 0.8)) 1e-9)))))

(deftest poll-once-falling-crossing-fires-event
  (testing "value drops from ch3 to ch1 across two thresholds — one crossing recorded"
    (let [events     (atom [])
          on-cross   {:type :fn
                      :f    (fn [ch prev dir beat]
                              (swap! events conj {:ch ch :prev prev :dir dir}))}
          thresholds [0.25 0.50 0.75]
          ;; last-ch=3, last-v=0.6; new value=0.1 → ch1
          [new-ch _] (poll-once! 2.0 (constantly 0.1) thresholds on-cross
                                 :both 0.01 3 0.6)]
      (is (= 1 (count @events)))
      (is (= :falling (:dir (first @events))))
      (is (= 3 (:prev (first @events))))
      (is (= 1 (:ch  (first @events))))
      (is (= 1 new-ch)))))

(deftest poll-once-within-channel-no-event
  (testing "value stays in same channel — no event"
    (let [events   (atom [])
          on-cross {:type :fn :f (fn [& _] (swap! events conj :fired))}
          thresholds [0.5]
          ;; last-ch=1, last-v=0.3; new value=0.4 → still ch1
          [new-ch _] (poll-once! 1.0 (constantly 0.4) thresholds on-cross
                                 :both 0.01 1 0.3)]
      (is (empty? @events))
      (is (= 1 new-ch)))))

(deftest poll-once-direction-filter-blocks-falling
  (testing ":rising filter suppresses falling crossings"
    (let [events   (atom [])
          on-cross {:type :fn :f (fn [& _] (swap! events conj :fired))}
          thresholds [0.5]
          ;; last-ch=2, last-v=0.8; new value=0.2 → ch1 (falling)
          [new-ch _] (poll-once! 1.0 (constantly 0.2) thresholds on-cross
                                 :rising 0.01 2 0.8)]
      ;; Direction filter blocks the falling crossing
      (is (empty? @events))
      ;; Channel is NOT updated (direction blocked)
      (is (= 2 new-ch)))))

(deftest poll-once-direction-filter-allows-rising
  (testing ":rising filter allows rising crossings"
    (let [events   (atom [])
          on-cross {:type :fn :f (fn [ch & _] (swap! events conj ch))}
          thresholds [0.5]
          [new-ch _] (poll-once! 1.0 (constantly 0.8) thresholds on-cross
                                 :rising 0.01 1 0.2)]
      (is (= [2] @events))
      (is (= 2 new-ch)))))

(deftest poll-once-hysteresis-blocks-jitter
  (testing "value barely crosses threshold — hysteresis suppresses the event"
    (let [events   (atom [])
          on-cross {:type :fn :f (fn [& _] (swap! events conj :fired))}
          thresholds [0.5]
          ;; spacing≈0.5, hyst=0.1, dead-zone=0.05
          ;; value=0.51 is not > 0.5+0.05=0.55 → blocked
          [new-ch _] (poll-once! 1.0 (constantly 0.51) thresholds on-cross
                                 :both 0.1 1 0.3)]
      (is (empty? @events))
      (is (= 1 new-ch)))))

;; ---------------------------------------------------------------------------
;; defthreshold-extractor / extractor-set! / extractor-stop! lifecycle
;; ---------------------------------------------------------------------------

(deftest extractor-lifecycle
  (testing "start, status, stop"
    (ext/defthreshold-extractor :test-lifecycle
      {:source-fn  (constantly 0.5)
       :channels   4
       :on-cross   {:type :fn :f (fn [& _] nil)}})
    (let [status (ext/extractor-status :test-lifecycle)]
      (is (some? status))
      (is (true? (:running? status)))
      (is (= 3 (count (:thresholds status))))
      (is (= 4 (:n-channels status))))
    (ext/extractor-stop! :test-lifecycle)
    (is (nil? (ext/extractor-status :test-lifecycle)))))

(deftest extractor-set-source-fn
  (testing "extractor-set! replaces source-fn live"
    (ext/defthreshold-extractor :test-set-src
      {:source-fn (constantly 0.2) :on-cross {:type :fn :f (fn [& _] nil)}})
    (ext/extractor-set! :test-set-src :source-fn (constantly 0.9))
    (let [s (ext/extractor-status :test-set-src)]
      (is (= 0.9 ((:source-fn s) 0.0))))
    (ext/extractor-stop! :test-set-src)))

(deftest extractor-set-space-recomputes-thresholds
  (testing "extractor-set! :space recomputes threshold comb"
    (ext/defthreshold-extractor :test-set-space
      {:source-fn (constantly 0.5)
       :channels  4
       :space     1.0
       :on-cross  {:type :fn :f (fn [& _] nil)}})
    (let [before (:thresholds (ext/extractor-status :test-set-space))]
      (ext/extractor-set! :test-set-space :space 0.5)
      (let [after (:thresholds (ext/extractor-status :test-set-space))]
        ;; Thresholds should have changed
        (is (not= before after))
        ;; With space=0.5 center=0.5 n=4: comb=[0.25,0.75], step=0.125
        ;;   thresholds at 0.375, 0.5, 0.625
        (is (< (Math/abs (- (nth after 1) 0.5)) 1e-9))))
    (ext/extractor-stop! :test-set-space)))

(deftest extractor-re-register-stops-prior
  (testing "re-evaluating defthreshold-extractor with same name replaces the instance"
    (ext/defthreshold-extractor :test-rereg
      {:source-fn (constantly 0.3) :on-cross {:type :fn :f (fn [& _] nil)}})
    (ext/defthreshold-extractor :test-rereg
      {:source-fn (constantly 0.7) :on-cross {:type :fn :f (fn [& _] nil)}})
    (let [state (ext/extractor-status :test-rereg)]
      ;; After re-registration source-fn should return 0.7
      (is (= 0.7 ((:source-fn state) 0.0)))
      ;; Still running
      (is (true? (:running? state))))
    (ext/extractor-stop! :test-rereg)))

(deftest extractor-stop-idempotent
  (testing "extractor-stop! on unknown name is safe"
    (is (nil? (ext/extractor-stop! :does-not-exist)))))

(deftest extractor-stop-all
  (testing "extractor-stop-all! clears the registry"
    (ext/defthreshold-extractor :sa1 {:source-fn (constantly 0.5)
                                      :on-cross {:type :fn :f (fn [& _] nil)}})
    (ext/defthreshold-extractor :sa2 {:source-fn (constantly 0.2)
                                      :on-cross {:type :fn :f (fn [& _] nil)}})
    (ext/extractor-stop-all!)
    (is (nil? (ext/extractor-status :sa1)))
    (is (nil? (ext/extractor-status :sa2)))))
