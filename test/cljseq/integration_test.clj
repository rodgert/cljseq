; SPDX-License-Identifier: EPL-2.0
(ns cljseq.integration-test
  "End-to-end integration test: JVM → sidecar binary → MIDI dispatch.

  Verifies the complete pipeline:
    Clojure play! → IPC framing → sidecar recv → scheduler → MIDI dispatch

  Dispatch is confirmed via sidecar stderr log lines (see sidecar/await-dispatch).
  This approach is portable and does not require a MIDI loopback device or a
  functional javax.sound.midi input — the latter is unreliable on macOS/OpenJDK.

  Requires:
    cljseq-sidecar binary at build/cpp/cljseq-sidecar/cljseq-sidecar

  Run with:
    lein test cljseq.integration-test

  Skips gracefully if the binary is not available."
  (:require [clojure.test   :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string  :as str]
            [cljseq.core    :as core]
            [cljseq.loop    :as loop-ns]
            [cljseq.sidecar :as sidecar]))

;; ---------------------------------------------------------------------------
;; Test configuration
;; ---------------------------------------------------------------------------

(def ^:private sidecar-binary "build/cpp/cljseq-sidecar/cljseq-sidecar")

;; Timeout for each dispatch assertion
(def ^:private timeout-ms 2000)

;; ---------------------------------------------------------------------------
;; Skip predicate
;; ---------------------------------------------------------------------------

(defn- skip-unless-binary
  "Run `f` only if the sidecar binary exists and is executable."
  [f]
  (let [file (io/file sidecar-binary)]
    (if (and (.exists file) (.canExecute file))
      (f)
      (println (str "  [skip] sidecar binary not found: " sidecar-binary)))))

(use-fixtures :each skip-unless-binary)

;; ---------------------------------------------------------------------------
;; IPC-log helpers
;;
;; The sidecar logs each received IPC frame as:
;;   [ipc] enqueue type=0x01 ch=1 note=60 vel=64 time_ns=...
;;
;; We match these lines to verify:
;;   - Clojure serialized the frame correctly
;;   - The sidecar received and parsed it
;;   - The scheduler was given the right parameters
;;
;; Scheduler dispatch (type→MIDI send) is covered by unit tests in scheduler_test.
;; ---------------------------------------------------------------------------

(defn- await-note-on-enqueue
  "Wait up to timeout-ms for the sidecar to log receipt of a NoteOn for `note`.

  Sidecar log format (post-MPE): [ipc] enqueue type=0x01 ch=N b9=NOTE b10=VEL time_ns=..."
  [note timeout-ms]
  (sidecar/await-dispatch
   #(and (str/includes? % "[ipc] enqueue")
         (str/includes? % "type=0x01")
         (str/includes? % (str "b9=" note " ")))
   timeout-ms))

(defn- await-note-off-enqueue
  "Wait up to timeout-ms for the sidecar to log receipt of a NoteOff for `note`.

  Sidecar log format (post-MPE): [ipc] enqueue type=0x02 ch=N b9=NOTE b10=0 time_ns=..."
  [note timeout-ms]
  (sidecar/await-dispatch
   #(and (str/includes? % "[ipc] enqueue")
         (str/includes? % "type=0x02")
         (str/includes? % (str "b9=" note " ")))
   timeout-ms))

(defn- enqueue-channel
  "Extract the channel number from an '[ipc] enqueue' log line."
  [line]
  (when-let [[_ ch] (re-find #"ch=(\d+)" line)]
    (Long/parseLong ch)))

(defn- enqueue-vel
  "Extract velocity from an '[ipc] enqueue' log line.
  Post-MPE log uses b10= for velocity on NoteOn."
  [line]
  (when-let [[_ v] (re-find #"b10=(\d+)" line)]
    (Long/parseLong v)))

;; ---------------------------------------------------------------------------
;; Session helper
;; ---------------------------------------------------------------------------

(defn- with-session
  "Start core + sidecar, run `f`, then clean up regardless of outcome."
  [f]
  (try
    (core/start! :bpm 120)
    (sidecar/start-sidecar! :binary sidecar-binary)
    (Thread/sleep 100)
    (f)
    (finally
      (try (sidecar/stop-sidecar!) (catch Exception _))
      (try (core/stop!)            (catch Exception _)))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest note-on-dispatch-test
  (testing "play! sends NoteOn IPC frame with correct note"
    (with-session
      (fn []
        (binding [loop-ns/*virtual-time* (loop-ns/-current-beat)]
          (core/play! {:pitch/midi 60 :dur/beats 1/4}))
        (let [line (await-note-on-enqueue 60 timeout-ms)]
          (is (some? line) "NoteOn for MIDI 60 should be received by sidecar")
          (when line
            (is (= 1 (enqueue-channel line)) "channel should be 1")))))))

(deftest note-off-dispatch-test
  (testing "play! sends NoteOff IPC frame after the note duration"
    (with-session
      (fn []
        (binding [loop-ns/*virtual-time* (loop-ns/-current-beat)]
          ;; 1/8 beat at 120 BPM = 250ms — NoteOff should arrive within timeout
          (core/play! {:pitch/midi 62 :dur/beats 1/8}))
        (let [line (await-note-off-enqueue 62 timeout-ms)]
          (is (some? line) "NoteOff for MIDI 62 should be received after note duration"))))))

(deftest channel-and-velocity-dispatch-test
  (testing "play! respects :midi/channel and :mod/velocity"
    (with-session
      (fn []
        (binding [loop-ns/*virtual-time* (loop-ns/-current-beat)]
          (core/play! {:pitch/midi 64 :dur/beats 1/4
                       :midi/channel 2 :mod/velocity 80}))
        (let [line (await-note-on-enqueue 64 timeout-ms)]
          (is (some? line) "NoteOn for MIDI 64 should be received by sidecar")
          (when line
            (is (= 2  (enqueue-channel line)) "channel should be 2")
            (is (= 80 (enqueue-vel line))     "velocity should be 80")))))))

(deftest keyword-shorthand-dispatch-test
  (testing "play! with keyword shorthand sends correct note to sidecar"
    (with-session
      (fn []
        (binding [loop-ns/*virtual-time* (loop-ns/-current-beat)]
          (core/play! :A4))
        (let [line (await-note-on-enqueue 69 timeout-ms)]
          (is (some? line) "NoteOn for A4 (MIDI 69) should be received by sidecar"))))))

(deftest shutdown-test
  (testing "stop-sidecar! sends Shutdown and process exits cleanly"
    (with-session
      (fn []
        (sidecar/stop-sidecar!)
        (Thread/sleep 300)
        (is (not (sidecar/connected?)) "sidecar should be disconnected after stop")))))
