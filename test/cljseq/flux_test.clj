; SPDX-License-Identifier: EPL-2.0
(ns cljseq.flux-test
  "Unit tests for cljseq.flux — step buffer, heads, CORRUPT, and inspection API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cljseq.clock  :as clock]
            [cljseq.core   :as core]
            [cljseq.ctrl   :as ctrl]
            [cljseq.flux   :as flux]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 60000)   ; fast BPM: 1 beat = 1ms
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; corrupt-value — pure function (no system required)
;; ---------------------------------------------------------------------------

(deftest corrupt-value-clamps-to-midi-range-test
  (testing "corrupt-value result is always in [0, 127]"
    (dotimes [_ 200]
      (let [v   (rand-int 128)
            res (flux/corrupt-value v 1.0)]
        (is (<= 0 res 127) "result in MIDI range")))))

(deftest corrupt-value-zero-intensity-test
  (testing "corrupt-value at intensity 0.0 never flips any bits"
    (dotimes [_ 50]
      (let [v (rand-int 128)]
        (is (= v (flux/corrupt-value v 0.0)) "no change at zero intensity")))))

(deftest corrupt-value-full-intensity-bit0-always-flips-test
  (testing "at intensity 1.0, bit 0 always flips (LSB guaranteed)"
    (dotimes [_ 20]
      (let [v   (+ 2 (rand-int 126))    ; avoid 0 and 127 edge cases
            res (flux/corrupt-value v 1.0)]
        (is (not= (bit-and v 1) (bit-and res 1)) "LSB flipped")))))

;; ---------------------------------------------------------------------------
;; defflux — basic construction
;; ---------------------------------------------------------------------------

(deftest defflux-returns-name-test
  (testing "defflux returns the flux name"
    (is (= :test/basic (flux/defflux :test/basic {:steps 4 :init 60
                                                   :read {:clock (clock/->Phasor 1 0)}
                                                   :output {:target :stdout}})))
    (flux/stop! :test/basic)))

(deftest defflux-initialises-buffer-test
  (testing "defflux fills buffer with :init scalar"
    (flux/defflux :test/init-scalar {:steps 4 :init 48
                                     :read {:clock (clock/->Phasor 1 0)}
                                     :output {:target :stdout}})
    (is (= [48 48 48 48] (flux/peek :test/init-scalar)))
    (flux/stop! :test/init-scalar))
  (testing "defflux fills buffer with :init vector (cycling)"
    (flux/defflux :test/init-vec {:steps 6 :init [60 62 64]
                                   :read {:clock (clock/->Phasor 1 0)}
                                   :output {:target :stdout}})
    (is (= [60 62 64 60 62 64] (flux/peek :test/init-vec)))
    (flux/stop! :test/init-vec)))

(deftest defflux-registers-ctrl-node-test
  (testing "defflux registers [:flux name] in the ctrl tree"
    (flux/defflux :test/ctrl-reg {:steps 4 :read {:clock (clock/->Phasor 1 0)}
                                   :output {:target :stdout}})
    (is (some? (ctrl/node-info [:flux :test/ctrl-reg])) "ctrl node exists")
    (flux/stop! :test/ctrl-reg)))

;; ---------------------------------------------------------------------------
;; peek / set-step! / reset!
;; ---------------------------------------------------------------------------

(deftest peek-single-step-test
  (testing "peek with index returns value at that step"
    (flux/defflux :test/peek {:steps 4 :init 60
                               :read {:clock (clock/->Phasor 1 0)}
                               :output {:target :stdout}})
    (is (= 60 (flux/peek :test/peek 0)))
    (is (= 60 (flux/peek :test/peek 3)))
    (flux/stop! :test/peek)))

(deftest set-step-writes-value-test
  (testing "set-step! writes directly to the step buffer"
    (flux/defflux :test/set-step {:steps 4 :init 60
                                   :read {:clock (clock/->Phasor 1 0)}
                                   :output {:target :stdout}})
    (flux/set-step! :test/set-step 2 72)
    (is (= 72 (flux/peek :test/set-step 2)))
    (is (= 60 (flux/peek :test/set-step 0)) "other steps unaffected")
    (flux/stop! :test/set-step)))

(deftest reset-restores-init-test
  (testing "reset! with no args restores :init value"
    (flux/defflux :test/reset {:steps 4 :init 55
                                :read {:clock (clock/->Phasor 1 0)}
                                :output {:target :stdout}})
    (flux/set-step! :test/reset 0 99)
    (flux/reset! :test/reset)
    (is (= [55 55 55 55] (flux/peek :test/reset)))
    (flux/stop! :test/reset)))

(deftest reset-with-scalar-test
  (testing "reset! with scalar overwrites all steps"
    (flux/defflux :test/reset-scalar {:steps 3 :init 60
                                       :read {:clock (clock/->Phasor 1 0)}
                                       :output {:target :stdout}})
    (flux/reset! :test/reset-scalar 72)
    (is (= [72 72 72] (flux/peek :test/reset-scalar)))
    (flux/stop! :test/reset-scalar)))

(deftest reset-with-vector-test
  (testing "reset! with vector seeds each step (cycling)"
    (flux/defflux :test/reset-vec {:steps 4 :init 60
                                    :read {:clock (clock/->Phasor 1 0)}
                                    :output {:target :stdout}})
    (flux/reset! :test/reset-vec [60 64])
    (is (= [60 64 60 64] (flux/peek :test/reset-vec)))
    (flux/stop! :test/reset-vec)))

;; ---------------------------------------------------------------------------
;; freeze! / unfreeze! — read head
;; ---------------------------------------------------------------------------

(deftest freeze-stops-read-advance-test
  (testing "freeze! prevents read head from advancing"
    (flux/defflux :test/freeze {:steps 8 :init 60
                                 :read {:clock (clock/->Phasor 1 0)}
                                 :output {:target :stdout}})
    (Thread/sleep 5)   ; let runner start
    ;; Measure stability after freeze — avoids the race between reading pos-before
    ;; and calling freeze! (a tick can slip between the two).
    (flux/freeze! :test/freeze)
    (let [pos-at-freeze (flux/read-pos :test/freeze)]
      (Thread/sleep 20)   ; allow several potential advances if freeze were broken
      (is (= pos-at-freeze (flux/read-pos :test/freeze)) "frozen head did not advance"))
    (flux/unfreeze! :test/freeze)
    (flux/stop! :test/freeze)))

;; ---------------------------------------------------------------------------
;; read head — routes to ctrl tree
;; ---------------------------------------------------------------------------

(deftest read-head-routes-to-ctrl-test
  (testing "read head calls ctrl/send! on the output path each step"
    (ctrl/defnode! [:test/flux-out] :type :int :value 0)
    (flux/defflux :test/route {:steps 4 :init 72
                                :read  {:clock (clock/->Phasor 1 0)}
                                :output {:target [:test/flux-out]}})
    (Thread/sleep 50)   ; wait for at least one read tick at 60000 BPM
    (is (= 72 (ctrl/get [:test/flux-out])) "ctrl node updated with step value")
    (flux/stop! :test/route)))

;; ---------------------------------------------------------------------------
;; write head — updates buffer
;; ---------------------------------------------------------------------------

(deftest write-head-updates-buffer-test
  (testing "write head samples source and writes to the buffer"
    (let [written-val 88
          ;; constant ITemporalValue source
          const-src   (reify clock/ITemporalValue
                        (sample    [_ _beat] written-val)
                        (next-edge [_ beat]  (+ beat 1.0)))]
      (flux/defflux :test/write {:steps 4 :init 60
                                  :read  {:clock (clock/->Phasor 1/8 0)}
                                  :write {:clock  (clock/->Phasor 1 0)
                                          :source const-src}
                                  :output {:target :stdout}})
      (Thread/sleep 100)  ; allow write head to tick several times
      (is (some #(= written-val %) (flux/peek :test/write))
          "at least one step updated by write head")
      (flux/stop! :test/write))))

;; ---------------------------------------------------------------------------
;; CORRUPT — mutates buffer and fires callbacks
;; ---------------------------------------------------------------------------

(deftest corrupt-mutates-buffer-test
  (testing "CORRUPT process changes at least one step over time"
    ;; Seed with a known value, run CORRUPT at intensity 1.0 and fast clock.
    ;; At intensity 1.0 bit 0 always flips, so after one tick every value changes.
    (flux/defflux :test/corrupt {:steps 4 :init 64
                                  :read   {:clock (clock/->Phasor 1/8 0)}
                                  :corrupt {:intensity 1.0
                                            :clock     (clock/->Phasor 1 0)}
                                  :output {:target :stdout}})
    (Thread/sleep 50)
    (let [buf (flux/peek :test/corrupt)]
      (is (some #(not= 64 %) buf) "CORRUPT changed at least one step"))
    (flux/stop! :test/corrupt)))

(deftest on-corrupt-callback-fires-test
  (testing "on-corrupt! callback is called when CORRUPT mutates a step"
    (let [fired (promise)]
      (flux/defflux :test/cb {:steps 4 :init 64
                               :read    {:clock (clock/->Phasor 1/8 0)}
                               :corrupt {:intensity 1.0
                                         :clock     (clock/->Phasor 1 0)}
                               :output  {:target :stdout}})
      (flux/on-corrupt! :test/cb
                        (fn [idx old new]
                          (deliver fired {:idx idx :old old :new new})))
      (let [result (deref fired 300 ::timeout)]
        (is (not= ::timeout result) "callback fired within 300ms")
        (when (not= ::timeout result)
          (is (integer? (:idx result)))
          (is (integer? (:old result)))
          (is (integer? (:new result)))
          (is (not= (:old result) (:new result)) "old and new differ")))
      (flux/stop! :test/cb))))

(deftest remove-corrupt-listener-test
  (testing "remove-corrupt-listener! stops the callback from firing"
    (let [count-atom (atom 0)
          cb         (fn [_ _ _] (swap! count-atom inc))]
      (flux/defflux :test/rm-cb {:steps 4 :init 64
                                  :read    {:clock (clock/->Phasor 1/8 0)}
                                  :corrupt {:intensity 1.0
                                            :clock     (clock/->Phasor 1 0)}
                                  :output  {:target :stdout}})
      (flux/on-corrupt! :test/rm-cb cb)
      (Thread/sleep 30)                         ; let some callbacks fire
      (flux/remove-corrupt-listener! :test/rm-cb cb)
      (Thread/sleep 5)                          ; allow any in-flight tick to finish
      (let [count-after-removal @count-atom]
        (Thread/sleep 30)                       ; 30ms more — no new ticks should fire cb
        (is (= count-after-removal @count-atom) "count unchanged after removal"))
      (flux/stop! :test/rm-cb))))

;; ---------------------------------------------------------------------------
;; stop! / stop-all! / flux-names
;; ---------------------------------------------------------------------------

(deftest stop-removes-from-registry-test
  (testing "stop! removes the instance from flux-names"
    (flux/defflux :test/stop {:steps 4 :read {:clock (clock/->Phasor 1 0)}
                               :output {:target :stdout}})
    (is (some #{:test/stop} (flux/flux-names)) "present before stop")
    (flux/stop! :test/stop)
    (is (not (some #{:test/stop} (flux/flux-names))) "absent after stop")))

(deftest stop-all-clears-registry-test
  (testing "stop-all! stops all instances"
    (flux/defflux :test/all-a {:steps 4 :read {:clock (clock/->Phasor 1 0)} :output {:target :stdout}})
    (flux/defflux :test/all-b {:steps 4 :read {:clock (clock/->Phasor 1 0)} :output {:target :stdout}})
    (flux/stop-all!)
    (is (not (some #{:test/all-a} (flux/flux-names))))
    (is (not (some #{:test/all-b} (flux/flux-names))))))

;; ---------------------------------------------------------------------------
;; Scale quantization
;; ---------------------------------------------------------------------------

(deftest scale-quantization-applied-on-read-test
  (testing "output value is quantized to :scale on each read step"
    ;; Seed buffer with C# (61, not in C major). Expect quantization to C (60) or D (62).
    (ctrl/defnode! [:test/scale-out] :type :int :value 0)
    (flux/defflux :test/scale {:steps 4 :init 61   ; C# — not in C major
                                :scale :major
                                :read  {:clock (clock/->Phasor 1 0)}
                                :output {:target [:test/scale-out]}})
    (Thread/sleep 50)
    (let [v (ctrl/get [:test/scale-out])]
      (is (some #{v} [60 62]) "C# quantized to C or D in major scale"))
    (flux/stop! :test/scale)))

;; ---------------------------------------------------------------------------
;; Direction: :forward / :backward / :ping-pong
;; ---------------------------------------------------------------------------

(deftest forward-direction-advances-test
  (testing "read head in :forward mode advances through the buffer"
    (flux/defflux :test/fwd {:steps 4 :init 60
                              :read  {:clock (clock/->Phasor 1 0) :direction :forward}
                              :output {:target :stdout}})
    (Thread/sleep 5)
    (let [p0 (flux/read-pos :test/fwd)]
      (Thread/sleep 5)
      (let [p1 (flux/read-pos :test/fwd)]
        ;; >= would fail on a valid modular wrap (e.g. 3→0); just assert movement
        (is (not= p0 p1) "position advanced (or wrapped)")))
    (flux/stop! :test/fwd)))
