; SPDX-License-Identifier: EPL-2.0
(ns cljseq.spectral-test
  "Tests for cljseq.spectral — SpectralState ITexture implementation,
  compute-spectral, freeze/thaw, and SAM loop mechanics."
  (:require [clojure.test           :refer [deftest is testing use-fixtures]]
            [cljseq.spectral        :as spectral]
            [cljseq.texture         :as tx]
            [cljseq.temporal-buffer :as tb]
            [cljseq.loop            :as loop-ns]))

;; ---------------------------------------------------------------------------
;; compute-spectral (private — accessed via start-spectral! + with-redefs)
;; ---------------------------------------------------------------------------

;; We test compute-spectral indirectly through run-sam-tick! via with-redefs
;; on tb/temporal-buffer-snapshot. Direct tests of compute-spectral's math
;; are isolated here using a SpectralState's state-atom after a tick.

(defn- one-tick!
  "Drive a single SAM analysis tick against `events`, return the resulting
  spectral context. Uses with-redefs to avoid a live system."
  [events]
  (let [sa   (atom {:spectral/density  0.0
                    :spectral/centroid 0.5
                    :spectral/blur     0.0
                    :frozen?           false})
        cb   (atom nil)]
    (with-redefs [tb/temporal-buffer-snapshot (constantly events)]
      (#'spectral/run-sam-tick! :test-buf (fn [c] (reset! cb c)) sa))
    @cb))

(deftest spectral-density-all-twelve-pcs
  (testing "density = 1.0 when all 12 pitch classes are present"
    (let [events (mapv #(hash-map :pitch/midi %) (range 12 24))  ; C1–B1
          ctx    (one-tick! events)]
      (is (= 1.0 (:spectral/density ctx))))))

(deftest spectral-density-one-pc
  (testing "density = 1/12 when only one pitch class is present"
    (let [events [{:pitch/midi 60} {:pitch/midi 72} {:pitch/midi 84}]  ; all C
          ctx    (one-tick! events)]
      (is (= (/ 1.0 12.0) (:spectral/density ctx))))))

(deftest spectral-centroid-mid-range
  (testing "centroid is near 0.5 for mid-range MIDI pitches"
    (let [events [{:pitch/midi 60} {:pitch/midi 64} {:pitch/midi 67}]  ; C4 E4 G4
          ctx    (one-tick! events)]
      ;; mean = (60+64+67)/3 = 63.67, / 127 ≈ 0.501
      (is (< 0.48 (:spectral/centroid ctx) 0.52)))))

(deftest spectral-centroid-low-pitches
  (testing "centroid is low for bass-register pitches"
    (let [events [{:pitch/midi 24} {:pitch/midi 28} {:pitch/midi 31}]  ; C1 E1 G1
          ctx    (one-tick! events)]
      ;; mean = (24+28+31)/3 ≈ 27.7, / 127 ≈ 0.218
      (is (< (:spectral/centroid ctx) 0.3)))))

(deftest spectral-centroid-high-pitches
  (testing "centroid is high for treble-register pitches"
    (let [events [{:pitch/midi 96} {:pitch/midi 100} {:pitch/midi 103}]  ; C7 E7 G7
          ctx    (one-tick! events)]
      ;; mean ≈ 99.7, / 127 ≈ 0.785
      (is (> (:spectral/centroid ctx) 0.75)))))

(deftest spectral-blur-is-zero-after-live-tick
  (testing "blur is 0.0 after a live analysis tick"
    (let [events [{:pitch/midi 60} {:pitch/midi 64} {:pitch/midi 67}]
          ctx    (one-tick! events)]
      (is (= 0.0 (:spectral/blur ctx))))))

(deftest spectral-empty-events-retain-density-zero
  (testing "empty event list produces density 0.0"
    (let [ctx (one-tick! [])]
      (is (= 0.0 (:spectral/density ctx))))))

;; ---------------------------------------------------------------------------
;; SpectralState ITexture contract
;; ---------------------------------------------------------------------------

(defn- make-sam
  "Build a SpectralState with the given initial state without starting a loop."
  ([]     (make-sam {}))
  ([init] (spectral/->SpectralState
            (atom (merge {:spectral/density 0.0
                          :spectral/centroid 0.5
                          :spectral/blur 0.0
                          :frozen? false}
                         init))
            :test-buf)))

(deftest sam-freeze-sets-blur-and-frozen
  (testing "freeze! sets :frozen? true and :spectral/blur 1.0"
    (let [sam (make-sam)]
      (tx/freeze! sam)
      (is (true? (tx/frozen? sam)))
      (is (= 1.0 (:spectral/blur (tx/texture-state sam)))))))

(deftest sam-thaw-clears-frozen-and-blur
  (testing "thaw! clears :frozen? and :spectral/blur to 0.0"
    (let [sam (make-sam {:frozen? true :spectral/blur 1.0})]
      (tx/thaw! sam)
      (is (false? (tx/frozen? sam)))
      (is (= 0.0 (:spectral/blur (tx/texture-state sam)))))))

(deftest sam-frozen-predicate
  (testing "frozen? reflects the :frozen? state"
    (let [sam (make-sam)]
      (is (false? (tx/frozen? sam)))
      (tx/freeze! sam)
      (is (true?  (tx/frozen? sam)))
      (tx/thaw!  sam)
      (is (false? (tx/frozen? sam))))))

(deftest sam-texture-state-returns-spectral-keys
  (testing "texture-state returns only spectral/* keys"
    (let [sam (make-sam {:spectral/density 0.5 :spectral/centroid 0.6})]
      (let [s (tx/texture-state sam)]
        (is (contains? s :spectral/density))
        (is (contains? s :spectral/centroid))
        (is (contains? s :spectral/blur))
        (is (not (contains? s :frozen?)))))))

(deftest sam-texture-set-merges-params
  (testing "texture-set! merges params into spectral state"
    (let [sam (make-sam)]
      (tx/texture-set! sam {:spectral/density 0.75 :spectral/blur 0.3})
      (let [s (tx/texture-state sam)]
        (is (= 0.75 (:spectral/density s)))
        (is (= 0.3  (:spectral/blur s)))))))

;; ---------------------------------------------------------------------------
;; Frozen tick — analysis skipped, on-ctx still fires
;; ---------------------------------------------------------------------------

(deftest sam-frozen-tick-skips-analysis
  (testing "when frozen, run-sam-tick! does not update spectral fields"
    (let [sa    (atom {:spectral/density  0.5
                       :spectral/centroid 0.4
                       :spectral/blur     1.0
                       :frozen?           true})
          fired (atom false)
          evts  [{:pitch/midi 60} {:pitch/midi 64}]]
      (with-redefs [tb/temporal-buffer-snapshot (constantly evts)]
        (#'spectral/run-sam-tick! :test-buf (fn [_] (reset! fired true)) sa))
      ;; Density should not have changed (was 0.5, would be ~0.17 with only 2 PCs)
      (is (= 0.5 (:spectral/density @sa)))
      ;; on-ctx still fired
      (is (true? @fired)))))

(deftest sam-live-tick-updates-state
  (testing "when live, run-sam-tick! updates spectral fields from events"
    (let [sa   (atom {:spectral/density  0.0
                      :spectral/centroid 0.5
                      :spectral/blur     0.0
                      :frozen?           false})
          evts [{:pitch/midi 60} {:pitch/midi 64} {:pitch/midi 67}
                {:pitch/midi 72} {:pitch/midi 76} {:pitch/midi 79}]]
      (with-redefs [tb/temporal-buffer-snapshot (constantly evts)]
        (#'spectral/run-sam-tick! :test-buf nil sa))
      ;; C E G in two octaves → 3 distinct PCs → density = 3/12 = 0.25
      (is (= (/ 3.0 12.0) (:spectral/density @sa))))))

;; ---------------------------------------------------------------------------
;; spectral-ctx helper
;; ---------------------------------------------------------------------------

(deftest spectral-ctx-returns-texture-state
  (testing "spectral-ctx returns the same map as texture-state"
    (let [sam (make-sam {:spectral/density 0.4 :spectral/centroid 0.6})]
      (is (= (tx/texture-state sam)
             (spectral/spectral-ctx sam))))))

;; ---------------------------------------------------------------------------
;; ITexture registration — integrates with texture registry
;; ---------------------------------------------------------------------------

(deftest spectral-state-registerable-as-texture
  (testing "SpectralState can be registered in the texture registry"
    (let [sam (make-sam)]
      (tx/deftexture! ::spectral-device sam)
      (is (identical? sam (tx/get-texture ::spectral-device))))))
