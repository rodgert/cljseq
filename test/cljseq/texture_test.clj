; SPDX-License-Identifier: EPL-2.0
(ns cljseq.texture-test
  "Tests for cljseq.texture — ITexture protocol, registry, TemporalBufferTexture,
  and texture-transition!."
  (:require [clojure.test           :refer [deftest is testing use-fixtures]]
            [cljseq.texture         :as tx]
            [cljseq.temporal-buffer :as tb]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    ;; Clear the texture registry between tests via re-registration
    (t)))

;; ---------------------------------------------------------------------------
;; Minimal ITexture stub for protocol tests
;; ---------------------------------------------------------------------------

(defrecord StubTexture [state-atom]
  tx/ITexture
  (freeze!       [_]   (swap! state-atom assoc :hold? true) nil)
  (thaw!         [_]   (swap! state-atom assoc :hold? false) nil)
  (frozen?       [_]   (boolean (:hold? @state-atom)))
  (texture-state [_]   @state-atom)
  (texture-set!  [_ p] (swap! state-atom merge p) nil)
  (texture-fade! [this target beats]
    ;; Synchronous stub — applies target immediately for testability
    (tx/texture-set! this target)
    nil))

(defn- stub-texture
  "Build a fresh StubTexture with the given initial params."
  ([]     (stub-texture {}))
  ([init] (->StubTexture (atom init))))

;; ---------------------------------------------------------------------------
;; Protocol contract
;; ---------------------------------------------------------------------------

(deftest freeze-thaw-roundtrip
  (testing "freeze!/thaw! toggle the frozen? predicate"
    (let [t (stub-texture)]
      (is (false? (tx/frozen? t)))
      (tx/freeze! t)
      (is (true? (tx/frozen? t)))
      (tx/thaw! t)
      (is (false? (tx/frozen? t))))))

(deftest texture-set-merges-params
  (testing "texture-set! merges params into device state"
    (let [t (stub-texture {:rate 1.0 :active-zone :z3})]
      (tx/texture-set! t {:rate 1.5})
      (is (= 1.5 (:rate (tx/texture-state t))))
      (is (= :z3 (:active-zone (tx/texture-state t)))))))

(deftest texture-set-multiple-params
  (testing "texture-set! applies multiple params atomically"
    (let [t (stub-texture {})]
      (tx/texture-set! t {:rate 0.8 :active-zone :z4 :hold? false})
      (let [s (tx/texture-state t)]
        (is (= 0.8 (:rate s)))
        (is (= :z4 (:active-zone s)))
        (is (false? (:hold? s)))))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest deftexture-and-get-texture
  (testing "deftexture! registers; get-texture retrieves"
    (let [t (stub-texture)]
      (tx/deftexture! ::test-device t)
      (is (identical? t (tx/get-texture ::test-device))))))

(deftest get-texture-nil-for-unknown
  (testing "get-texture returns nil for unregistered id"
    (is (nil? (tx/get-texture ::no-such-device-xyz)))))

(deftest texture-names-includes-registered
  (testing "texture-names includes just-registered device"
    (tx/deftexture! ::named-device (stub-texture))
    (is (contains? (set (tx/texture-names)) ::named-device))))

(deftest deftexture-replaces-existing
  (testing "re-registering same id replaces the previous entry"
    (let [t1 (stub-texture {:rate 1.0})
          t2 (stub-texture {:rate 2.0})]
      (tx/deftexture! ::replace-me t1)
      (tx/deftexture! ::replace-me t2)
      (is (identical? t2 (tx/get-texture ::replace-me))))))

;; ---------------------------------------------------------------------------
;; Shadow state
;; ---------------------------------------------------------------------------

(deftest shadow-init-and-get
  (testing "shadow-init! seeds the shadow registry; shadow-get retrieves it"
    (tx/shadow-init! ::hw-device {:reverb-time 3.0 :freeze false})
    (let [s (tx/shadow-get ::hw-device)]
      (is (some? s))
      (is (= {:reverb-time 3.0 :freeze false} (:shadow s)))
      (is (false? (:confident? s)))
      (is (= :init (:source s))))))

(deftest shadow-update-merges-and-marks-confident
  (testing "shadow-update! merges into the shadow and sets :confident? true"
    (tx/shadow-init! ::hw-device2 {:reverb-time 3.0 :freeze false})
    (tx/shadow-update! ::hw-device2 :texture-set! {:reverb-time 5.0})
    (let [s (tx/shadow-get ::hw-device2)]
      (is (true? (:confident? s)))
      (is (= :texture-set! (:source s)))
      (is (= 5.0  (:reverb-time (:shadow s))))
      (is (false? (:freeze (:shadow s)))))))  ; unchanged key preserved

;; ---------------------------------------------------------------------------
;; TemporalBufferTexture — tests using with-redefs to avoid live system
;; ---------------------------------------------------------------------------

(defn- tb-state-atom []
  (atom {:hold?       false
         :flip?       false
         :active-zone :z3
         :rate        1.0
         :feedback    {:amount 0.0}}))

(deftest buffer-texture-freeze-thaw
  (testing "TemporalBufferTexture freeze!/thaw! delegate to temporal-buffer-hold!"
    (let [calls (atom [])]
      (with-redefs [tb/temporal-buffer-hold! (fn [n h] (swap! calls conj [n h]) nil)
                    tb/temporal-buffer-info  (constantly {:hold? false})]
        (let [t (tx/buffer-texture :echo)]
          (tx/freeze! t)
          (tx/thaw! t)
          (is (= [[:echo true] [:echo false]] @calls)))))))

(deftest buffer-texture-frozen-predicate
  (testing "TemporalBufferTexture frozen? reads :hold? from temporal-buffer-info"
    (with-redefs [tb/temporal-buffer-info (constantly {:hold? true})
                  tb/temporal-buffer-hold! (fn [_ _] nil)]
      (let [t (tx/buffer-texture :echo)]
        (is (true? (tx/frozen? t)))))))

(deftest buffer-texture-texture-state
  (testing "TemporalBufferTexture texture-state returns temporal-buffer-info result"
    (let [expected {:hold? false :rate 1.0 :active-zone :z3}]
      (with-redefs [tb/temporal-buffer-info (constantly expected)]
        (let [t (tx/buffer-texture :echo)]
          (is (= expected (tx/texture-state t))))))))

(deftest buffer-texture-set-delegates
  (testing "TemporalBufferTexture texture-set! delegates to temporal-buffer-set!"
    (let [received (atom nil)]
      (with-redefs [tb/temporal-buffer-set! (fn [n p] (reset! received [n p]) nil)]
        (let [t (tx/buffer-texture :echo)]
          (tx/texture-set! t {:rate 1.5 :active-zone :z4})
          (is (= :echo (first @received)))
          (is (= {:rate 1.5 :active-zone :z4} (second @received))))))))

;; ---------------------------------------------------------------------------
;; texture-transition!
;; ---------------------------------------------------------------------------

(deftest texture-transition-immediate
  (testing "texture-transition! applies target immediately when no :beats given"
    (let [t (stub-texture {:rate 1.0})]
      (tx/deftexture! ::tx-dev t)
      (tx/texture-transition! {::tx-dev {:target {:rate 2.0}}})
      (is (= 2.0 (:rate (tx/texture-state t)))))))

(deftest texture-transition-skips-unknown-device
  (testing "texture-transition! silently skips unknown device ids and returns nil"
    (is (nil? (tx/texture-transition! {::definitely-not-registered {:target {:rate 1.0}}})))))

(deftest texture-transition-with-fade
  (testing "texture-transition! applies target via fade when :beats present"
    ;; StubTexture.texture-fade! is synchronous — applies target immediately.
    ;; This confirms the transition reaches the device regardless of the path.
    (let [t (stub-texture {:rate 1.0})]
      (tx/deftexture! ::fade-dev t)
      (tx/texture-transition! {::fade-dev {:target {:rate 3.0} :beats 8}})
      (is (= 3.0 (:rate (tx/texture-state t)))))))
