; SPDX-License-Identifier: EPL-2.0
(ns cljseq.m21
  "cljseq Music21 integration — Phase 1 + Phase 2.

  Wraps the Music21 Python extractor subprocess (script/m21_extract.py).
  Calls are synchronous; use `eventually!` to defer from a live loop.

  Music21 is used for musicological depth (corpus queries, score analysis);
  cljseq native code handles the hot path (Q23). The Music21 subprocess uses
  the user's own installation; no corpus is bundled with cljseq (Q24).

  ## Phase 1 — chordified playback
    (m21/load-chorale 371)       ; => vec of chord maps {:pitches [...] :dur/beats N}
    (m21/play-chorale! 371)      ; play BWV 371 — all voices on one channel
    (m21/play-chorale! 371 1/2)  ; dur multiplier (0.5 = double speed)

  ## Phase 2 — per-voice SATB on separate MIDI channels
    (m21/load-chorale-parts 371)              ; => {:soprano [...] :alto [...] ...}
    (m21/play-chorale-parts! 371)             ; SATB on ch 1/2/3/4, blocks until done
    (m21/play-chorale-parts! 371 {:dur-mult 0.8
                                  :channels {:soprano 5 :alto 6 :tenor 7 :bass 8}})

  ## Caching
  Extracted EDN is cached at two levels to avoid repeated music21 subprocess
  overhead (startup alone costs 2-4 seconds):
    - In-memory: atom keyed by [bwv-id mode]; cleared on REPL restart or clear-cache!
    - On-disk:   ~/.cache/cljseq/m21/bwv371-chords.edn etc.; invalidated when
                 m21_extract.py changes (script mtime comparison)

    (m21/clear-cache!)       ; evict everything
    (m21/clear-cache! 371)   ; evict BWV 371 only (both modes)

  ## Phase 1 chord map format
    {:pitches [48 55 62 67] :dur/beats 1.0}   ; chord — MIDI bass→soprano
    {:rest true :dur/beats 1.0}               ; rest

  ## Phase 2 per-voice event format
    {:pitch 67 :dur/beats 1.0}   ; single note
    {:rest true :dur/beats 1.0}  ; rest

  Key design decisions: Q20 (m21/eventually!), Q22 (batch subprocess),
  Q23 (native hot-path / Music21 depth split), Q24 (no bundled corpus)."
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [clojure.string  :as str]
            [cljseq.core     :as core]
            [cljseq.dirs     :as dirs]
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

(def ^:dynamic *cache-dir*
  "Directory for on-disk EDN cache files.
  Default: (dirs/corpora-dir)/m21  (~/.local/share/cljseq/corpora/m21 on XDG)
  Override: (alter-var-root #'m21/*cache-dir* (constantly \"/my/path\"))
  or set CLJSEQ_DATA_DIR to redirect the entire user data tree."
  (str (dirs/corpora-dir) "/m21"))

;; ---------------------------------------------------------------------------
;; Cache — in-memory + on-disk EDN cache for extracted corpora
;; ---------------------------------------------------------------------------

;; In-memory cache: {[bwv-id mode] parsed-data}.
;; defonce survives namespace reload so extracted data persists across eval cycles.
(defonce ^:private mem-cache (atom {}))

(defn- script-mtime
  "Return last-modified timestamp (ms) of m21_extract.py, or 0 if not found."
  ^long []
  (.lastModified (io/file extractor-script)))

(defn- cache-file
  "Return the on-disk cache java.io.File for bwv-id and mode (:chords or :parts)."
  [bwv-id mode]
  (io/file *cache-dir* (str "bwv" bwv-id "-" (name mode) ".edn")))

(defn- parse-edn
  "Strip comment lines (starting with ;) from s and parse as EDN."
  [s]
  (->> (str/split-lines s)
       (remove #(str/starts-with? (str/trim %) ";"))
       (str/join "\n")
       edn/read-string))

(defn- write-disk-cache!
  "Write raw EDN string to the on-disk cache, prefixed with a script-mtime header."
  [bwv-id mode raw-edn]
  (try
    (let [f (cache-file bwv-id mode)]
      (io/make-parents f)
      (spit f (str "; cljseq-m21-cache script-mtime=" (script-mtime) "\n" raw-edn)))
    (catch Exception e
      (binding [*out* *err*]
        (println "[cljseq.m21] disk cache write failed:" (.getMessage e))))))

(defn- read-disk-cache
  "Return on-disk cache content for bwv-id/mode if it exists and script mtime
  still matches; return nil if missing or stale."
  [bwv-id mode]
  (try
    (let [f (cache-file bwv-id mode)]
      (when (.exists f)
        (let [content    (slurp f)
              first-line (first (str/split-lines content))
              cached-mtime (when (str/starts-with? first-line "; cljseq-m21-cache")
                             (some-> (re-find #"script-mtime=(\d+)" first-line)
                                     second
                                     Long/parseLong))]
          (when (= cached-mtime (script-mtime))
            content))))
    (catch Exception _
      nil)))

(defn- cached-load
  "Load bwv-id in mode (:chords or :parts) through the two-level cache.

  Lookup order:
    1. In-memory atom  — instant
    2. On-disk EDN file — fast (file read); validates script mtime
    3. Python subprocess — slow; populates both caches on success"
  [bwv-id mode extract-fn]
  (let [k [bwv-id mode]]
    (or (get @mem-cache k)
        (when-let [raw (read-disk-cache bwv-id mode)]
          (let [data (parse-edn raw)]
            (swap! mem-cache assoc k data)
            data))
        (let [raw  (extract-fn)
              data (parse-edn raw)]
          (write-disk-cache! bwv-id mode raw)
          (swap! mem-cache assoc k data)
          data))))

(defn clear-cache!
  "Evict cached extractions from both in-memory and on-disk stores.

  With no args: clears all cached chorales.
  With bwv-id:  clears that chorale only (both :chords and :parts modes).

  Call after upgrading music21 or modifying m21_extract.py if you want to
  force re-extraction before the script mtime check would trigger it.

  Examples:
    (m21/clear-cache!)       ; evict everything
    (m21/clear-cache! 371)   ; evict BWV 371 only"
  ([]
   (reset! mem-cache {})
   (let [dir (io/file *cache-dir*)]
     (when (.exists dir)
       (doseq [^java.io.File f (.listFiles dir)]
         (.delete f))))
   (println "[cljseq.m21] cache cleared")
   nil)
  ([bwv-id]
   (swap! mem-cache
          #(dissoc % [bwv-id :chords] [bwv-id :parts]))
   (doseq [mode [:chords :parts]]
     (let [f (cache-file bwv-id mode)]
       (when (.exists f) (.delete f))))
   nil))

;; ---------------------------------------------------------------------------
;; Subprocess extraction
;; ---------------------------------------------------------------------------

(defn- run-extractor
  "Run the m21_extract.py script for `bwv-id`, return stdout as a string.
  `extra-args` is an optional seq of additional CLI flags (e.g. [\"--parts\"]).
  Throws if the script exits non-zero or the file is not found."
  ([bwv-id] (run-extractor bwv-id nil))
  ([bwv-id extra-args]
   (let [script (io/file extractor-script)]
     (when-not (.exists script)
       (throw (ex-info "m21_extract.py not found"
                       {:expected (str (.getAbsolutePath script))})))
     (let [cmd  (into [*python* extractor-script (str bwv-id)] extra-args)
           pb   (doto (ProcessBuilder. ^java.util.List cmd)
                  (.redirectError ProcessBuilder$Redirect/INHERIT))
           proc (.start pb)
           out  (slurp (.getInputStream proc))
           exit (.waitFor proc)]
       (when-not (zero? exit)
         (throw (ex-info "m21_extract.py failed"
                         {:bwv bwv-id :exit exit :output out})))
       out))))

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
  (cached-load bwv-id :chords #(run-extractor bwv-id)))

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
;; Phase 2 — per-voice SATB extraction and playback
;; ---------------------------------------------------------------------------

(def ^:private satb-channels
  "Default MIDI channel mapping for SATB voices."
  {:soprano 1 :alto 2 :tenor 3 :bass 4})

(defn load-chorale-parts
  "Load a Bach chorale from the music21 corpus by BWV number, extracting
  each voice part independently.

  Returns a map of voice keyword → event vector:
    {:soprano [{:pitch 67 :dur/beats 1.0} {:rest true :dur/beats 0.5} ...]
     :alto    [{:pitch 62 :dur/beats 1.0} ...]
     :tenor   [{:pitch 55 :dur/beats 1.0} ...]
     :bass    [{:pitch 48 :dur/beats 1.0} ...]}

  :pitch is a single MIDI note number (highest pitch when the score has
  a chord on a single-voice part, e.g. double-stops).
  :dur/beats is duration in quarter-note beats.

  Example:
    (m21/load-chorale-parts 371)   ; BWV 371 \"Ich dank' dir, lieber Herre\""
  [bwv-id]
  (cached-load bwv-id :parts #(run-extractor bwv-id ["--parts"])))

(defn- play-voice-events!
  "Play a voice event sequence on `channel`, advancing *virtual-time* via sleep!.
  Must be called from a thread that has *virtual-time* bound."
  [events channel dur-mult]
  (binding [loop-ns/*synth-ctx* {:midi/channel channel}]
    (doseq [{:keys [pitch rest dur/beats]} events]
      (let [dur (* (double (or beats 1)) (double dur-mult))]
        (when-not rest
          (dsl/play! {:pitch/midi pitch :dur/beats dur}))
        (core/sleep! dur)))))

(defn play-chorale-parts!
  "Play a Bach chorale with SATB voices on separate MIDI channels.

  Each voice is played in a background thread sharing the same start beat,
  so all four voices stay phase-locked. Blocks until all voices complete.

  `bwv-id`  — BWV number (integer), e.g. 371
  `opts`    — optional map:
    :dur-mult — duration multiplier (default 1.0); 0.5 = double speed
    :channels — {voice-kw channel} override; default {:soprano 1 :alto 2 :tenor 3 :bass 4}

  Safe to call from inside a live loop. The loop thread blocks until all
  voice threads finish, then the loop body continues.

  Example:
    ;; Play BWV 371 SATB on channels 1-4
    (m21/play-chorale-parts! 371)

    ;; Double speed, custom channels
    (m21/play-chorale-parts! 371 {:dur-mult 0.5
                                  :channels {:soprano 5 :alto 6 :tenor 7 :bass 8}})

    ;; From a live loop (one-shot):
    (core/deflive-loop :satb {}
      (m21/play-chorale-parts! 371)
      (core/stop-loop! :satb))"
  ([bwv-id] (play-chorale-parts! bwv-id {}))
  ([bwv-id {:keys [dur-mult channels] :or {dur-mult 1.0}}]
   (let [parts      (load-chorale-parts bwv-id)
         ch-map     (merge satb-channels channels)
         ;; Capture current beat so all voice threads start in phase.
         start-beat (loop-ns/-current-beat)
         futures    (mapv (fn [[voice events]]
                            (let [ch (get ch-map voice 1)]
                              (future
                                (binding [loop-ns/*virtual-time* start-beat]
                                  (play-voice-events! events ch dur-mult)))))
                          parts)]
     ;; Block until all voices complete
     (doseq [f futures] @f)
     nil)))

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
