; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ensemble-test
  "Tests for cljseq.ensemble — analyze-buffer, start-harmony-ear!, and
  ImprovisationContext derivation."
  (:require [clojure.test             :refer [deftest is testing use-fixtures]]
            [cljseq.ensemble          :as ensemble]
            [cljseq.live  :as live]
            [cljseq.loop              :as loop-ns]
            [cljseq.pitch             :as pitch]
            [cljseq.scale             :as scale]
            [cljseq.temporal-buffer   :as tb]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    ;; Clear any root harmony binding left by prior tests
    (live/use-harmony! nil)
    (t)
    (live/use-harmony! nil)))

;; ---------------------------------------------------------------------------
;; analyze-buffer — no live system needed; we mock the snapshot
;; ---------------------------------------------------------------------------

(defn- fake-events
  "Build a minimal event vector suitable for analyze-buffer, using explicit
  beat timestamps and MIDI pitches."
  [pitches]
  (mapv (fn [midi]
          {:pitch/midi    midi
           :beat          0.0
           :dur/beats     0.25
           :mod/velocity  64
           :midi/channel  1})
        pitches))

(deftest analyze-buffer-nil-when-no-events
  (testing "analyze-buffer returns nil when snapshot is empty"
    (with-redefs [tb/temporal-buffer-snapshot (fn [_] [])
                  tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
      (is (nil? (ensemble/analyze-buffer :test))))))

(deftest analyze-buffer-nil-when-buffer-missing
  (testing "analyze-buffer returns nil when buffer is not registered"
    (with-redefs [tb/temporal-buffer-snapshot (fn [_] nil)]
      (is (nil? (ensemble/analyze-buffer :missing))))))

(deftest analyze-buffer-basic-density
  (testing "analyze-buffer computes density as events-per-beat"
    (let [events (fake-events [60 62 64 65 67])]
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (let [ctx (ensemble/analyze-buffer :test)]
          (is (some? ctx))
          (is (= (double (/ 5 8)) (:ensemble/density ctx))))))))

(deftest analyze-buffer-register-mid
  (testing "analyze-buffer classifies mid-range pitches as :mid register"
    (let [events (fake-events [60 62 64 67 69])]  ; C4 D4 E4 G4 A4 — all mid-range
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (is (= :mid (:ensemble/register (ensemble/analyze-buffer :test))))))))

(deftest analyze-buffer-register-low
  (testing "analyze-buffer classifies low pitches as :low register"
    (let [events (fake-events [36 38 40 43])]  ; C2 D2 E2 G2
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (is (= :low (:ensemble/register (ensemble/analyze-buffer :test))))))))

(deftest analyze-buffer-register-high
  (testing "analyze-buffer classifies high pitches as :high register"
    (let [events (fake-events [84 86 88 91])]  ; C6 D6 E6 G6
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (is (= :high (:ensemble/register (ensemble/analyze-buffer :test))))))))

(deftest analyze-buffer-pcs-populated
  (testing "analyze-buffer includes pitch-class distribution"
    (let [events (fake-events [60 64 67])]  ; C E G — pitch classes 0, 4, 7
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (let [ctx (ensemble/analyze-buffer :test)]
          (is (map? (:harmony/pcs ctx)))
          (is (= 3 (count (:harmony/pcs ctx))))
          (is (contains? (:harmony/pcs ctx) 0))   ; C
          (is (contains? (:harmony/pcs ctx) 4))   ; E
          (is (contains? (:harmony/pcs ctx) 7))))))) ; G

(deftest analyze-buffer-key-detection-requires-3-pcs
  (testing "analyze-buffer omits :harmony/key when fewer than 3 distinct PCs"
    ;; Two pitch classes — not enough for meaningful key detection
    (let [events (fake-events [60 60 60 64 64])]  ; only C and E
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (let [ctx (ensemble/analyze-buffer :test)]
          (is (some? ctx))
          (is (not (contains? ctx :harmony/key)))
          (is (not (contains? ctx :harmony/chord))))))))

(deftest analyze-buffer-key-scale-hint-skips-detection
  (testing ":key-scale hint bypasses auto-detection"
    (let [events    (fake-events [60 62 64 65 67 69 71])  ; C major scale
          pinned    (scale/scale :D 3 :dorian)]
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (let [ctx (ensemble/analyze-buffer :test :key-scale pinned)]
          (is (some? ctx))
          ;; Key must be the pinned scale, not auto-detected C major
          (is (= pinned (:harmony/key ctx)))
          ;; No ks-score or mode-conf since detection was skipped
          (is (not (contains? ctx :harmony/ks-score)))
          (is (not (contains? ctx :harmony/mode-conf))))))))

(deftest analyze-buffer-tension-present-with-sufficient-notes
  (testing "analyze-buffer includes :harmony/tension when key is detected"
    ;; Strong C-major signal — enough for detection + chord ID
    (let [events (fake-events [60 64 67 60 64 67 60 62 65 69])]
      (with-redefs [tb/temporal-buffer-snapshot (fn [_] events)
                    tb/temporal-buffer-info     (fn [_] {:zone {:depth 8.0}})]
        (let [ctx (ensemble/analyze-buffer :test)]
          (when (:harmony/key ctx)
            (is (contains? ctx :harmony/tension))
            (is (number? (:harmony/tension ctx)))
            (is (<= 0.0 (:harmony/tension ctx) 1.0))))))))

;; ---------------------------------------------------------------------------
;; ->harmony-scale extraction — tested indirectly through dsl
;; ---------------------------------------------------------------------------

(deftest harmony-ctx-as-improv-ctx-map
  (testing "live/root works when *harmony-ctx* is an ImprovisationContext map"
    (let [key-scale (scale/scale :G 4 :major)
          ctx-map   {:harmony/key    key-scale
                     :harmony/tension 0.3
                     :ensemble/density 0.5}]
      (live/with-harmony ctx-map
        (is (= 67 (pitch/pitch->midi (live/root))))))))

(deftest harmony-ctx-scale-degree-via-improv-ctx
  (testing "live/scale-degree works with ImprovisationContext"
    (let [key-scale (scale/scale :C 4 :major)
          ctx-map   {:harmony/key key-scale}]
      (live/with-harmony ctx-map
        (is (= 60 (pitch/pitch->midi (live/scale-degree 0))))  ; C4
        (is (= 67 (pitch/pitch->midi (live/scale-degree 4)))))))) ; G4

(deftest harmony-ctx-in-key-via-improv-ctx
  (testing "live/in-key? works with ImprovisationContext"
    (let [key-scale (scale/scale :C 4 :major)
          ctx-map   {:harmony/key key-scale}]
      (live/with-harmony ctx-map
        (is (live/in-key? :C4))
        (is (not (live/in-key? :C#4)))))))

(deftest harmony-ctx-classic-scale-still-works
  (testing "live/root works when *harmony-ctx* is a plain Scale (backward compat)"
    (live/with-harmony (scale/scale :C 4 :major)
      (is (= 60 (pitch/pitch->midi (live/root)))))))

(deftest use-harmony-accepts-improv-ctx
  (testing "use-harmony! accepts and stores an ImprovisationContext map"
    (let [key-scale (scale/scale :D 3 :dorian)
          ctx-map   {:harmony/key key-scale :harmony/tension 0.5}]
      (live/use-harmony! ctx-map)
      (is (= ctx-map loop-ns/*harmony-ctx*))
      (live/use-harmony! nil))))

;; ---------------------------------------------------------------------------
;; temporal-buffer-snapshot
;; ---------------------------------------------------------------------------

(deftest buffer-snapshot-nil-for-unregistered
  (testing "temporal-buffer-snapshot returns nil for unknown buffer"
    (is (nil? (tb/temporal-buffer-snapshot :no-such-buffer-xyz)))))
