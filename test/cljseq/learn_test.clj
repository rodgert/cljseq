; SPDX-License-Identifier: EPL-2.0
(ns cljseq.learn-test
  "Unit tests for cljseq.learn — MIDI Learn device map authoring tool.

  All tests use -inject-message! to feed synthetic MIDI data directly into
  the capture queue, so no sidecar or physical hardware is required."
  (:require [clojure.edn      :as edn]
            [clojure.java.io  :as io]
            [clojure.string   :as str]
            [clojure.test     :refer [deftest is testing use-fixtures]]
            [cljseq.learn     :as learn]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- wait-idle
  "Spin (up to 2 s) until the learn runner exits and returns :idle status."
  []
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (if (learn/learning?)
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 20) (recur))
          (throw (ex-info "learn runner did not go idle" {})))
        :ok))))

;; Note-on: status = 0x90 | (ch - 1)
(defn- note-on-msg [ch note vel]
  {:status (bit-or 0x90 (dec (int ch))) :b1 note :b2 vel})

;; CC: status = 0xB0 | (ch - 1)
(defn- cc-msg [ch cc-num val]
  {:status (bit-or 0xB0 (dec (int ch))) :b1 cc-num :b2 val})

;; ---------------------------------------------------------------------------
;; Fixture — cancel any in-progress capture + clear sessions
;; ---------------------------------------------------------------------------

(defn with-clean-learn [f]
  (learn/cancel-learn!)
  (Thread/sleep 100)          ; allow runner to drain
  ;; clear any lingering sessions from prior tests by clearing named ones
  (doseq [id [:test/synth :test/drum :test/ctrl :test/export :test/seq]]
    (learn/clear-session! id))
  (try (f)
       (finally
         (learn/cancel-learn!)
         (Thread/sleep 100))))

(use-fixtures :each with-clean-learn)

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(deftest start-learn-session-test
  (testing "start-learn-session! returns session-id"
    (is (= :test/synth
           (learn/start-learn-session! :test/synth
             {:device/name "Test Synth" :midi/channel 1}))))
  (testing "learn-session-state has empty notes and ccs on fresh session"
    (learn/start-learn-session! :test/synth {:device/name "X" :midi/channel 1})
    (let [s (learn/learn-session-state :test/synth)]
      (is (= [] (:notes s)))
      (is (= [] (:ccs s)))))
  (testing "re-opening session preserves previously captured entries"
    (learn/start-learn-session! :test/drum {:device/name "Drum" :midi/channel 10})
    ;; inject a note-on to capture
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (note-on-msg 10 36 100)))
    (learn/learn-notes! :test/drum [:kick] :timeout-ms 2000)
    (wait-idle)
    ;; re-open same session with new meta
    (learn/start-learn-session! :test/drum {:device/name "Drum v2" :midi/channel 10})
    (let [s (learn/learn-session-state :test/drum)]
      (is (= 1 (count (:notes s))) "existing note entries preserved")
      (is (= "Drum v2" (:device/name (:meta s))) "meta updated"))))

(deftest clear-session-test
  (testing "clear-session! removes session from state"
    (learn/start-learn-session! :test/synth {:device/name "X" :midi/channel 1})
    (learn/clear-session! :test/synth)
    (is (nil? (learn/learn-session-state :test/synth)))))

;; ---------------------------------------------------------------------------
;; learn? / cancel lifecycle
;; ---------------------------------------------------------------------------

(deftest learning-predicate-test
  (testing "learning? returns false when idle"
    (is (false? (learn/learning?))))
  (testing "learning? returns true while a capture is active"
    (learn/start-learn-session! :test/synth {:device/name "X" :midi/channel 1})
    (learn/learn-cc! :test/synth [:filter :cutoff] :timeout-ms 5000)
    (is (true? (learn/learning?)))
    ;; inject to resolve the capture
    (learn/-inject-message! (cc-msg 1 74 64))
    (wait-idle)
    (is (false? (learn/learning?)))))

(deftest cancel-learn-test
  (testing "cancel-learn! stops an in-progress capture"
    (learn/start-learn-session! :test/synth {:device/name "X" :midi/channel 1})
    (learn/learn-cc! :test/synth [:filter :cutoff] :timeout-ms 30000)
    (is (true? (learn/learning?)))
    (learn/cancel-learn!)
    (wait-idle)
    (is (false? (learn/learning?))))
  (testing "entries captured before cancel are preserved"
    (learn/start-learn-session! :test/drum {:device/name "D" :midi/channel 10})
    ;; Inject first item quickly, then cancel before the second
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (note-on-msg 10 36 100)))
    (learn/learn-notes! :test/drum [:kick :snare] :timeout-ms 5000)
    (Thread/sleep 200)
    (learn/cancel-learn!)
    (wait-idle)
    (let [s (learn/learn-session-state :test/drum)]
      (is (= 1 (count (:notes s))) "kick captured before cancel"))))

;; ---------------------------------------------------------------------------
;; learn-notes! — note capture
;; ---------------------------------------------------------------------------

(deftest learn-notes-captures-note-on-test
  (testing "learn-notes! captures note number and channel for each name"
    (learn/start-learn-session! :test/drum {:device/name "LM DRUM" :midi/channel 10})
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (note-on-msg 10 36 100))
      (Thread/sleep 30)
      (learn/-inject-message! (note-on-msg 10 38 90)))
    (learn/learn-notes! :test/drum [:kick :snare] :timeout-ms 3000)
    (wait-idle)
    (let [notes (:notes (learn/learn-session-state :test/drum))]
      (is (= 2 (count notes)))
      (is (= {:type :note :name :kick  :channel 10 :note 36} (first notes)))
      (is (= {:type :note :name :snare :channel 10 :note 38} (second notes))))))

(deftest learn-notes-ignores-note-off-test
  (testing "learn-notes! ignores Note On with velocity 0 (note-off disguise)"
    (learn/start-learn-session! :test/drum {:device/name "D" :midi/channel 10})
    (future
      (Thread/sleep 50)
      ;; note-on with vel=0 is note-off — should be skipped
      (learn/-inject-message! {:status 0x99 :b1 36 :b2 0})
      (Thread/sleep 30)
      ;; real note-on follows
      (learn/-inject-message! (note-on-msg 10 36 80)))
    (learn/learn-notes! :test/drum [:kick] :timeout-ms 3000)
    (wait-idle)
    (let [notes (:notes (learn/learn-session-state :test/drum))]
      (is (= 1 (count notes)))
      (is (= 36 (:note (first notes)))))))

(deftest learn-notes-no-session-throws-test
  (testing "learn-notes! throws when no session has been started"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No session"
         (learn/learn-notes! :no/such-session [:kick])))))

;; ---------------------------------------------------------------------------
;; learn-cc! — single CC capture
;; ---------------------------------------------------------------------------

(deftest learn-cc-captures-cc-test
  (testing "learn-cc! captures CC number and channel"
    (learn/start-learn-session! :test/synth {:device/name "Synth" :midi/channel 1})
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (cc-msg 1 74 64)))
    (learn/learn-cc! :test/synth [:filter :cutoff] :timeout-ms 3000)
    (wait-idle)
    (let [ccs (:ccs (learn/learn-session-state :test/synth))]
      (is (= 1 (count ccs)))
      (is (= {:type :cc :name [:filter :cutoff] :channel 1 :cc 74}
             (first ccs))))))

(deftest learn-cc-ignores-note-messages-test
  (testing "learn-cc! ignores Note On messages and waits for CC"
    (learn/start-learn-session! :test/synth {:device/name "S" :midi/channel 1})
    (future
      (Thread/sleep 50)
      ;; send a note-on first — should be ignored
      (learn/-inject-message! (note-on-msg 1 60 100))
      (Thread/sleep 30)
      ;; then a CC
      (learn/-inject-message! (cc-msg 1 23 90)))
    (learn/learn-cc! :test/synth [:vco1 :wave] :timeout-ms 3000)
    (wait-idle)
    (let [ccs (:ccs (learn/learn-session-state :test/synth))]
      (is (= 1 (count ccs)))
      (is (= 23 (:cc (first ccs)))))))

(deftest learn-cc-no-session-throws-test
  (testing "learn-cc! throws when no session has been started"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No session"
         (learn/learn-cc! :no/such-session [:filter :cutoff])))))

;; ---------------------------------------------------------------------------
;; learn-sequence! — multi-CC capture
;; ---------------------------------------------------------------------------

(deftest learn-sequence-captures-all-paths-test
  (testing "learn-sequence! captures one CC per path in order"
    (learn/start-learn-session! :test/seq {:device/name "Seq" :midi/channel 1})
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (cc-msg 1 74 64))
      (Thread/sleep 30)
      (learn/-inject-message! (cc-msg 1 71 80))
      (Thread/sleep 30)
      (learn/-inject-message! (cc-msg 1 23 100)))
    (learn/learn-sequence! :test/seq
                           [[:filter :cutoff]
                            [:filter :resonance]
                            [:vco1   :wave]]
                           :timeout-ms 3000)
    (wait-idle)
    (let [ccs (:ccs (learn/learn-session-state :test/seq))]
      (is (= 3 (count ccs)))
      (is (= 74 (:cc (nth ccs 0))))
      (is (= 71 (:cc (nth ccs 1))))
      (is (= 23 (:cc (nth ccs 2))))
      (is (= [:filter :cutoff]    (:name (nth ccs 0))))
      (is (= [:filter :resonance] (:name (nth ccs 1))))
      (is (= [:vco1 :wave]        (:name (nth ccs 2)))))))

(deftest learn-sequence-no-session-throws-test
  (testing "learn-sequence! throws when no session has been started"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No session"
         (learn/learn-sequence! :no/such-session [[:a :b]])))))

;; ---------------------------------------------------------------------------
;; Concurrent capture guard
;; ---------------------------------------------------------------------------

(deftest concurrent-capture-throws-test
  (testing "starting a second capture while one is running throws"
    (learn/start-learn-session! :test/synth {:device/name "S" :midi/channel 1})
    (learn/learn-cc! :test/synth [:filter :cutoff] :timeout-ms 30000)
    (is (true? (learn/learning?)))
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already running"
           (learn/learn-cc! :test/synth [:filter :resonance] :timeout-ms 1000)))
      (finally
        (learn/cancel-learn!)
        (wait-idle)))))

;; ---------------------------------------------------------------------------
;; export-device-map!
;; ---------------------------------------------------------------------------

(deftest export-device-map-test
  (testing "export-device-map! writes an EDN file containing captured entries"
    (learn/start-learn-session! :test/export
      {:device/name "Export Test" :device/type :synthesizer :midi/channel 3})
    ;; Inject notes and CCs synchronously via inject then wait
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (cc-msg 3 74 64))
      (Thread/sleep 30)
      (learn/-inject-message! (cc-msg 3 71 80)))
    (learn/learn-sequence! :test/export
                           [[:filter :cutoff] [:filter :resonance]]
                           :timeout-ms 3000)
    (wait-idle)
    (let [tmp (str (System/getProperty "java.io.tmpdir")
                   "/learn-export-test-" (System/currentTimeMillis) ".edn")]
      (try
        (learn/export-device-map! :test/export tmp)
        (let [content  (slurp tmp)
              parsed   (edn/read-string content)]
          (is (= :test/export (:device/id parsed)))
          (is (= "Export Test" (:device/name parsed)))
          (is (= 3 (:midi/channel parsed)))
          (is (= :target (:device/role parsed)))
          (let [ccs (:midi/cc parsed)]
            (is (= 2 (count ccs)))
            (is (= 74 (:cc (first ccs))))
            (is (= [:filter :cutoff] (:path (first ccs))))
            (is (= [0 127] (:range (first ccs))))
            (is (= 71 (:cc (second ccs)))))
          ;; file should have EDN comment header
          (is (str/includes? content ";; cljseq device map")))
        (finally
          (.delete (io/file tmp)))))))

(deftest export-notes-device-map-test
  (testing "export-device-map! includes :midi/notes for drum sessions"
    (learn/start-learn-session! :test/drum
      {:device/name "Test Drum" :device/type :drum-machine :midi/channel 10})
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (note-on-msg 10 36 100))
      (Thread/sleep 30)
      (learn/-inject-message! (note-on-msg 10 38 90)))
    (learn/learn-notes! :test/drum [:kick :snare] :timeout-ms 3000)
    (wait-idle)
    (let [tmp (str (System/getProperty "java.io.tmpdir")
                   "/learn-notes-test-" (System/currentTimeMillis) ".edn")]
      (try
        (learn/export-device-map! :test/drum tmp)
        (let [parsed (edn/read-string (slurp tmp))]
          (is (= :drum-machine (:device/type parsed)))
          (let [notes (:midi/notes parsed)]
            (is (= 2 (count notes)))
            (is (= 36 (:note (first notes))))
            (is (= [:kick] (:path (first notes))))
            (is (= "kick" (:label (first notes))))))
        (finally
          (.delete (io/file tmp)))))))

(deftest export-no-session-throws-test
  (testing "export-device-map! throws for unknown session"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No session"
         (learn/export-device-map! :no/such-session "/tmp/nope.edn")))))

;; ---------------------------------------------------------------------------
;; -inject-message! test hook
;; ---------------------------------------------------------------------------

(deftest inject-message-feeds-queue-test
  (testing "-inject-message! is accepted by an active capture"
    (learn/start-learn-session! :test/ctrl {:device/name "Ctrl" :midi/channel 2})
    (future
      (Thread/sleep 50)
      (learn/-inject-message! (cc-msg 2 10 99)))
    (learn/learn-cc! :test/ctrl [:mod-wheel] :timeout-ms 3000)
    (wait-idle)
    (let [ccs (:ccs (learn/learn-session-state :test/ctrl))]
      (is (= 1 (count ccs)))
      (is (= 10 (:cc (first ccs))))
      (is (= 2 (:channel (first ccs)))))))

(deftest inject-note-on-ch10-test
  (testing "-inject-message! with raw 0x99 status is decoded as ch 10 Note On"
    (learn/start-learn-session! :test/drum {:device/name "D" :midi/channel 10})
    (future
      (Thread/sleep 50)
      (learn/-inject-message! {:status 0x99 :b1 36 :b2 100}))
    (learn/learn-notes! :test/drum [:kick] :timeout-ms 3000)
    (wait-idle)
    (let [notes (:notes (learn/learn-session-state :test/drum))]
      (is (= 10 (:channel (first notes))))
      (is (= 36 (:note (first notes)))))))
