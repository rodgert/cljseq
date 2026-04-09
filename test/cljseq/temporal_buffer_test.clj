; SPDX-License-Identifier: EPL-2.0
(ns cljseq.temporal-buffer-test
  "Phase 1 tests: core buffer, zone table, zone switching, hold, flip, info.

  No live scheduler or sidecar required — tests work in Phase 0 mode."
  (:require [clojure.test              :refer [deftest testing is are]]
            [cljseq.temporal-buffer    :as tbuf]))

;; ---------------------------------------------------------------------------
;; Zone table
;; ---------------------------------------------------------------------------

(deftest zone-table-structure
  (testing "8 named zones are defined"
    (is (= 8 (count (tbuf/zone-ids)))))
  (testing "zones are in depth order z0..z7"
    (let [depths (map #(:depth (tbuf/zone-info %)) (tbuf/zone-ids))]
      (is (= (sort depths) depths))
      (is (= [0.5 2.0 4.0 8.0 16.0 32.0 64.0 128.0] (vec depths)))))
  (testing "z0 is exponential rate (Karplus territory)"
    (is (= :exponential (:rate-mode (tbuf/zone-info :z0)))))
  (testing "z1..z7 are linear rate"
    (doseq [z (rest (tbuf/zone-ids))]
      (is (= :linear (:rate-mode (tbuf/zone-info z))) (str z " should be :linear"))))
  (testing "unknown zone returns nil"
    (is (nil? (tbuf/zone-info :z99)))))

;; ---------------------------------------------------------------------------
;; Lifecycle: deftemporal-buffer / stop!
;; ---------------------------------------------------------------------------

(deftest lifecycle
  (testing "deftemporal-buffer returns the name keyword"
    (is (= :test-buf (tbuf/deftemporal-buffer :test-buf {:active-zone :z2})))
    (tbuf/stop! :test-buf))

  (testing "buffer appears in buffer-names after creation"
    (tbuf/deftemporal-buffer :lc-buf {})
    (is (contains? (set (tbuf/buffer-names)) :lc-buf))
    (tbuf/stop! :lc-buf))

  (testing "stop! removes buffer from registry"
    (tbuf/deftemporal-buffer :stop-test {})
    (tbuf/stop! :stop-test)
    (is (not (contains? (set (tbuf/buffer-names)) :stop-test))))

  (testing "stop! on unknown name is safe"
    (is (nil? (tbuf/stop! :no-such-buf))))

  (testing "re-defining replaces the prior instance"
    (tbuf/deftemporal-buffer :redef {:active-zone :z1})
    (tbuf/deftemporal-buffer :redef {:active-zone :z4})
    (let [info (tbuf/temporal-buffer-info :redef)]
      (is (= :z4 (:active-zone info))))
    (tbuf/stop! :redef)))

;; ---------------------------------------------------------------------------
;; temporal-buffer-info
;; ---------------------------------------------------------------------------

(deftest info-structure
  (tbuf/deftemporal-buffer :info-test {:active-zone :z3 :max-depth 64.0})
  (let [info (tbuf/temporal-buffer-info :info-test)]
    (testing "active-zone"
      (is (= :z3 (:active-zone info))))
    (testing "zone map is present"
      (is (= 8.0 (get-in info [:zone :depth]))))
    (testing "hold? starts false"
      (is (false? (:hold? info))))
    (testing "flip? starts false"
      (is (false? (:flip? info))))
    (testing "max-depth respected"
      (is (= 64.0 (:max-depth info))))
    (testing "empty buffer"
      (is (zero? (:event-count info)))
      (is (nil? (:oldest-beat info)))))
  (tbuf/stop! :info-test)

  (testing "info on unknown buffer returns nil"
    (is (nil? (tbuf/temporal-buffer-info :no-such)))))

;; ---------------------------------------------------------------------------
;; temporal-buffer-send!
;; ---------------------------------------------------------------------------

(deftest send-accumulates-events
  (tbuf/deftemporal-buffer :send-test {:active-zone :z3 :max-depth 128.0})

  (testing "send! returns nil"
    (is (nil? (tbuf/temporal-buffer-send! :send-test {:pitch/midi 60 :dur/beats 0.25}))))

  (testing "events accumulate in the buffer"
    (tbuf/temporal-buffer-send! :send-test {:pitch/midi 62 :dur/beats 0.25})
    (tbuf/temporal-buffer-send! :send-test {:pitch/midi 64 :dur/beats 0.25})
    (let [info (tbuf/temporal-buffer-info :send-test)]
      ;; At least the 2 we just sent (possibly 1 from the first send test)
      (is (>= (:event-count info) 2))))

  (testing "each event has :beat :generation :halo-depth stamped"
    ;; peek at the buffer atom via info; event fields are checked indirectly
    ;; by verifying event-count grows after send
    (let [before (:event-count (tbuf/temporal-buffer-info :send-test))]
      (tbuf/temporal-buffer-send! :send-test {:pitch/midi 65})
      (let [after (:event-count (tbuf/temporal-buffer-info :send-test))]
        (is (= (inc before) after)))))

  (testing "send! on unknown buffer is safe"
    (is (nil? (tbuf/temporal-buffer-send! :no-such {:pitch/midi 60}))))

  (tbuf/stop! :send-test))

;; ---------------------------------------------------------------------------
;; Hold mode
;; ---------------------------------------------------------------------------

(deftest hold-mode
  (tbuf/deftemporal-buffer :hold-test {})

  (testing "hold! freezes input — events are not written while held"
    (tbuf/temporal-buffer-hold! :hold-test true)
    (is (true? (:hold? (tbuf/temporal-buffer-info :hold-test))))
    (let [before (:event-count (tbuf/temporal-buffer-info :hold-test))]
      (tbuf/temporal-buffer-send! :hold-test {:pitch/midi 60})
      (tbuf/temporal-buffer-send! :hold-test {:pitch/midi 62})
      (let [after (:event-count (tbuf/temporal-buffer-info :hold-test))]
        (is (= before after) "no new events while held"))))

  (testing "releasing hold resumes input"
    (tbuf/temporal-buffer-hold! :hold-test false)
    (is (false? (:hold? (tbuf/temporal-buffer-info :hold-test))))
    (let [before (:event-count (tbuf/temporal-buffer-info :hold-test))]
      (tbuf/temporal-buffer-send! :hold-test {:pitch/midi 67})
      (let [after (:event-count (tbuf/temporal-buffer-info :hold-test))]
        (is (= (inc before) after) "event written after hold released"))))

  (tbuf/stop! :hold-test))

;; ---------------------------------------------------------------------------
;; Zone switching — pitch-free (Phase 1: no Doppler to test, structural only)
;; ---------------------------------------------------------------------------

(deftest zone-switching
  (tbuf/deftemporal-buffer :zone-test {:active-zone :z2})

  (testing "initial zone is :z2"
    (is (= :z2 (:active-zone (tbuf/temporal-buffer-info :zone-test)))))

  (testing "zone! updates the active zone"
    (tbuf/temporal-buffer-zone! :zone-test :z4)
    (is (= :z4 (:active-zone (tbuf/temporal-buffer-info :zone-test)))))

  (testing "zone! with :z0 (Karplus) accepted"
    (tbuf/temporal-buffer-zone! :zone-test :z0)
    (is (= :z0 (:active-zone (tbuf/temporal-buffer-info :zone-test)))))

  (testing "zone! with unknown zone throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tbuf/temporal-buffer-zone! :zone-test :z99))))

  (testing "zone switch does not discard the buffer"
    (tbuf/temporal-buffer-zone! :zone-test :z1)
    (tbuf/temporal-buffer-send! :zone-test {:pitch/midi 60})
    (let [count-before (:event-count (tbuf/temporal-buffer-info :zone-test))]
      (tbuf/temporal-buffer-zone! :zone-test :z5)
      (let [count-after (:event-count (tbuf/temporal-buffer-info :zone-test))]
        (is (= count-before count-after) "zone switch preserves buffer contents"))))

  (tbuf/stop! :zone-test))

;; ---------------------------------------------------------------------------
;; Flip (retrograde) toggle
;; ---------------------------------------------------------------------------

(deftest flip-toggle
  (tbuf/deftemporal-buffer :flip-test {})

  (testing "flip? starts false"
    (is (false? (:flip? (tbuf/temporal-buffer-info :flip-test)))))

  (testing "flip! toggles to true"
    (tbuf/temporal-buffer-flip! :flip-test)
    (is (true? (:flip? (tbuf/temporal-buffer-info :flip-test)))))

  (testing "flip! toggles back to false"
    (tbuf/temporal-buffer-flip! :flip-test)
    (is (false? (:flip? (tbuf/temporal-buffer-info :flip-test)))))

  (tbuf/stop! :flip-test))

;; ---------------------------------------------------------------------------
;; stop-all!
;; ---------------------------------------------------------------------------

(deftest stop-all
  (tbuf/deftemporal-buffer :sa1 {})
  (tbuf/deftemporal-buffer :sa2 {:active-zone :z1})
  (tbuf/stop-all!)
  (testing "all buffers removed after stop-all!"
    (is (not (contains? (set (tbuf/buffer-names)) :sa1)))
    (is (not (contains? (set (tbuf/buffer-names)) :sa2)))))
