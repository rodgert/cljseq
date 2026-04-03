; SPDX-License-Identifier: EPL-2.0
(ns cljseq.m21
  "cljseq Music21 integration — Phase 1 spike.

  Wraps the Music21 Python extractor subprocess (script/m21_extract.py).
  Calls are synchronous; use `eventually!` to defer from a live loop.

  Music21 is used for musicological depth (corpus queries, score analysis);
  cljseq native code handles the hot path (Q23). The Music21 subprocess uses
  the user's own installation; no corpus is bundled with cljseq (Q24).

  ## Basic usage
    (m21/load-chorale 371)       ; => seq of chord maps
    (m21/play-chorale! 371)      ; play BWV 371 through active sidecar
    (m21/play-chorale! 371 1/2)  ; dur multiplier (0.5 = double speed)

  ## Chord map format (from extractor)
    {:pitches [48 55 62 67] :dur/beats 1.0}   ; chord — MIDI bass→soprano
    {:rest true :dur/beats 1.0}               ; rest

  Key design decisions: Q20 (m21/eventually!), Q22 (batch subprocess),
  Q23 (native hot-path / Music21 depth split), Q24 (no bundled corpus)."
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [clojure.string  :as str]
            [cljseq.core     :as core]
            [cljseq.dsl      :as dsl]
            [cljseq.loop     :as loop-ns])
  (:import  [java.lang ProcessBuilder ProcessBuilder$Redirect]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *python*
  "Path to the Python interpreter with music21 installed.
  Override with (alter-var-root #'m21/*python* (constantly \"/path/to/python\"))
  or bind per-call with (binding [m21/*python* p] ...)."
  (let [venv (str (System/getProperty "user.home") "/.venv/music21/bin/python3")]
    (if (.exists (io/file venv)) venv "python3")))

(def ^:private extractor-script "script/m21_extract.py")

;; ---------------------------------------------------------------------------
;; Subprocess extraction
;; ---------------------------------------------------------------------------

(defn- run-extractor
  "Run the m21_extract.py script for `bwv-id`, return stdout as a string.
  Throws if the script exits non-zero or the file is not found."
  [bwv-id]
  (let [script (io/file extractor-script)]
    (when-not (.exists script)
      (throw (ex-info "m21_extract.py not found"
                      {:expected (str (.getAbsolutePath script))})))
    (let [pb    (doto (ProcessBuilder. [*python* extractor-script (str bwv-id)])
                  (.redirectError ProcessBuilder$Redirect/INHERIT))
          proc  (.start pb)
          out   (slurp (.getInputStream proc))
          exit  (.waitFor proc)]
      (when-not (zero? exit)
        (throw (ex-info "m21_extract.py failed"
                        {:bwv bwv-id :exit exit :output out})))
      out)))

(defn list-chorales
  "Return a sorted vector of BWV numbers available in the music21 corpus."
  []
  (let [raw (run-extractor "list")]
    (edn/read-string raw)))

(defn load-chorale
  "Load a Bach chorale from the music21 corpus by BWV number.

  Returns a vector of chord maps:
    {:pitches [48 55 62 67] :dur/beats 1.0}  ; chord
    {:rest true :dur/beats 1.0}              ; rest

  :pitches are MIDI note numbers sorted ascending (bass to soprano).
  :dur/beats is duration in quarter-note beats.

  Example:
    (m21/load-chorale 371)   ; BWV 371 \"Ich dank' dir, lieber Herre\""
  [bwv-id]
  (let [raw  (run-extractor bwv-id)
        ;; Strip comment lines (start with ;) before EDN parsing
        edn-str (str/join "\n"
                          (remove #(str/starts-with? (str/trim %) ";")
                                  (str/split-lines raw)))]
    (edn/read-string edn-str)))

;; ---------------------------------------------------------------------------
;; Playback
;; ---------------------------------------------------------------------------

(defn play-chorale!
  "Play a Bach chorale through the active sidecar.

  `bwv-id`      — BWV number (integer), e.g. 371
  `dur-mult`    — duration multiplier (default 1.0); 0.5 = double speed

  Must be called from inside a live loop (or with *virtual-time* bound),
  since it calls sleep! to advance beat time between chords.

  Example (from REPL):
    (core/deflive-loop :chorale {}
      (m21/play-chorale! 371)
      (core/stop-loop! :chorale))"
  ([bwv-id]
   (play-chorale! bwv-id 1.0))
  ([bwv-id dur-mult]
   (let [chords (load-chorale bwv-id)]
     (doseq [{:keys [pitches rest dur/beats]} chords]
       (let [dur (* (double (or beats 1)) (double dur-mult))]
         (when-not rest
           (dsl/play-voicing! pitches))
         (core/sleep! dur))))))

;; ---------------------------------------------------------------------------
;; eventually! — defer m21 calls from a live loop
;; ---------------------------------------------------------------------------

(defn eventually!
  "Run `f` in a background thread, returning a promise.
  Safe to call from a live loop — does not block the loop thread.

  Example:
    (def result (m21/eventually! #(m21/load-chorale 371)))
    ;; ... later ...
    @result   ; => chord seq"
  [f]
  (let [p (promise)]
    (future (deliver p (f)))
    p))
