; SPDX-License-Identifier: EPL-2.0
(ns cljseq.midi-repair
  "MIDI repair pipeline — from raw single-channel recording to cljseq score.

  Designed around the TheoryBoard + NDLR user story: a single-channel MIDI
  recording whose four structural voices (drone, pad, motif-1, motif-2) need
  to be separated, whose key/mode has been forgotten, whose motif position
  modulation introduced a few out-of-place notes, and whose journey arc
  ended without harmonic resolution.

  The output of `repair-pipeline!` is a Clojure map — the score — that is
  simultaneously the composition and the analysis. Because it is a Clojure
  value, it can be transformed, serialised to EDN, queried with standard
  Clojure, and fed directly into live loops.

  ## Quick start
    (require '[cljseq.midi-repair :as repair])

    ;; Full pipeline — returns the score map
    (def score (repair/repair-pipeline! \"recording.mid\" {:structure :ndlr}))

    ;; Inspect what was detected
    (get-in score [:meta :mode])          ; => :dorian
    (get-in score [:meta :tonic])         ; => :D

    ;; Play the repaired voices back
    (repair/play-score! score)

    ;; Review correction proposals before accepting
    (repair/print-corrections score)

    ;; Accept all corrections
    (def accepted (repair/accept-corrections score))

    ;; Save the score as EDN
    (spit \"shipwreck-piano.edn\" (pr-str score))"
  (:require [cljseq.analyze   :as analyze]
            [cljseq.scale     :as scale]
            [cljseq.pitch     :as pitch]
            [cljseq.m21       :as m21]
            [cljseq.loop      :as loop-ns]
            [cljseq.sidecar   :as sidecar]
            [clojure.edn      :as edn]
            [clojure.string   :as str]))

;; ---------------------------------------------------------------------------
;; 1. MIDI file ingestion via m21 server
;; ---------------------------------------------------------------------------

(defn parse-midi!
  "Parse a MIDI file into a vector of note maps via the music21 server.

  Each note map:
    {:pitch/midi N :start/beats F :dur/beats F :velocity N :channel N}

  Also returns :tempo, :time-sig, :bars in the result.

  `path` — absolute or relative path to a .mid / .midi file

  Returns:
    {:notes    [{:pitch/midi ...} ...]
     :tempo    120.0
     :time-sig \"4/4\"
     :bars     32}"
  [path]
  (m21/ensure-server!)
  (let [abs-path (.getAbsolutePath (java.io.File. path))
        resp     (m21/server-call! {:op "parse-midi" :path abs-path})]
    (when (= "error" (:status resp))
      (throw (ex-info "parse-midi failed" {:message (:message resp) :path abs-path})))
    {:notes    (edn/read-string (:notes resp))
     :tempo    (:tempo resp)
     :time-sig (:time-sig resp)
     :bars     (:bars resp)}))

;; ---------------------------------------------------------------------------
;; 2. Key / mode detection
;; ---------------------------------------------------------------------------

(defn detect-key!
  "Detect the tonic and church mode from a collection of notes or MIDI ints.

  Accepts either a parsed MIDI map (from parse-midi!) or a raw vector of
  MIDI integers.

  Returns:
    {:tonic        :D
     :mode         :dorian
     :confidence   0.84
     :ks-score     0.91
     :scale        #Scale{...}   ; cljseq Scale record, ready for use
     :alternatives [{:mode :aeolian :modal-score 0.38} ...]}"
  [notes-or-parsed]
  (let [midi-ints (if (map? notes-or-parsed)
                    (mapv :pitch/midi (:notes notes-or-parsed))
                    (vec notes-or-parsed))
        result    (analyze/detect-mode midi-ints)
        sc        (scale/scale (:tonic result) 4 (:mode result))]
    (assoc result :scale sc)))

;; ---------------------------------------------------------------------------
;; 3. Voice separation — NDLR structure-aware heuristic
;; ---------------------------------------------------------------------------

(def ^:private ndlr-thresholds
  "Heuristic thresholds for NDLR voice classification."
  {:drone-dur-beats    4.0    ; notes held ≥ 4 beats are drone candidates
   :drone-midi-max     50     ; drone lives below MIDI 50 (D3)
   :pad-cluster-ms     60     ; notes within 60ms are a chord cluster (pad spread)
   :pad-dur-min        0.4    ; pad notes are at least 0.4 beats (16th at 150bpm)
   :pad-midi-range     [48 72]; pad lives between C3 and C5
   :motif-dur-max      0.6    ; motif notes are shorter than 0.6 beats
   :motif-split-midi   62})   ; motif-1 above this, motif-2 at/below

(defn- cluster-by-time
  "Group notes whose :start/beats values fall within `window-beats` of each other."
  [notes window-beats]
  (when (seq notes)
    (reduce (fn [clusters note]
              (let [last-group (peek clusters)
                    last-start (:start/beats (peek last-group) -999)]
                (if (<= (- (:start/beats note) last-start) window-beats)
                  (update clusters (dec (count clusters)) conj note)
                  (conj clusters [note]))))
            [[(first (sort-by :start/beats notes))]]
            (rest (sort-by :start/beats notes)))))

(defn separate-voices!
  "Separate a flat note stream into NDLR structural voices.

  Uses duration, register, and chord-cluster detection as the primary
  discriminants. The NDLR's known architecture (drone/pad/motif-1/motif-2)
  is the prior — this is not generic voice separation.

  `parsed`  — result map from parse-midi!, or a flat note vector
  `opts`:
    :structure  — :ndlr (default) or :generic (4-voice, less accurate)
    :thresholds — override any entry in ndlr-thresholds

  Returns:
    {:drone    [notes...]
     :pad      [notes...]        ; each entry has :chord [midi...] merged
     :motif-1  [notes...]
     :motif-2  [notes...]
     :ambiguous [notes...]}      ; notes that failed classification"
  ([parsed] (separate-voices! parsed {}))
  ([parsed {:keys [structure thresholds]
            :or   {structure :ndlr}}]
   (let [notes     (if (map? parsed) (:notes parsed) (vec parsed))
         t         (merge ndlr-thresholds thresholds)
         sorted    (sort-by :start/beats notes)

         ;; Step 1: drone candidates — long duration + low register
         {drone-cands true rest-1 false}
         (group-by #(and (>= (:dur/beats %) (:drone-dur-beats t))
                         (<= (:pitch/midi %) (:drone-midi-max t)))
                   sorted)

         ;; Step 2: chord clusters (pad) — notes firing within 60ms window
         ;; Convert beats to approx ms assuming 120 BPM if tempo unknown
         cluster-window (/ (:pad-cluster-ms t) 1000.0 (/ 60.0 120.0))
         clusters  (cluster-by-time rest-1 cluster-window)
         {pad-clusters true mono-notes false}
         (group-by #(> (count %) 1) clusters)

         pad-notes  (mapcat identity pad-clusters)
         mono-notes (mapcat identity mono-notes)

         ;; Validate pad register
         {pad-in-range true rest-2 false}
         (group-by #(let [lo (first (:pad-midi-range t))
                          hi (second (:pad-midi-range t))]
                      (and (>= (:pitch/midi %) lo) (<= (:pitch/midi %) hi)))
                   pad-notes)

         ;; Step 3: split remaining single notes by register into motif-1/2
         {motif-1 true motif-2 false}
         (group-by #(> (:pitch/midi %) (:motif-split-midi t)) mono-notes)

         ;; Ambiguous: pad notes outside expected range, and rest-2
         ambiguous (concat rest-2)]

     {:drone     (vec (or drone-cands []))
      :pad       (vec (or pad-in-range []))
      :motif-1   (vec (or motif-1 []))
      :motif-2   (vec (or motif-2 []))
      :ambiguous (vec ambiguous)})))

;; ---------------------------------------------------------------------------
;; 4. Structural annotation — chord detection and tension arc
;; ---------------------------------------------------------------------------

(defn annotate-structure!
  "Detect chords from the pad voice and annotate the full harmonic arc.

  Groups pad notes into chords by time window, identifies each chord,
  calculates tension, and returns a structural summary.

  `voices`    — result of separate-voices!
  `key-result` — result of detect-key!

  Returns:
    {:progression [{:chord [...] :roman :i :tension 0.4 :bar N} ...]
     :tension-arc  [{:bar 0 :tension 0.1} ...]
     :peak-bar     28
     :peak-tension 0.85
     :final-chord  {:chord [...] :roman :V :tension 0.7}}"
  [voices key-result]
  (let [sc          (:scale key-result)
        pad-notes   (:pad voices)
        ;; Group pad notes by bar (assuming 4 beats/bar)
        by-bar      (group-by #(int (/ (:start/beats %) 4.0)) pad-notes)
        bars        (sort (keys by-bar))
        progression (mapv (fn [bar]
                            (let [pitches (mapv :pitch/midi (get by-bar bar []))
                                  chord   (when (seq pitches)
                                            (analyze/identify-chord pitches))
                                  roman   (when (and chord sc)
                                            (analyze/chord->roman sc pitches))
                                  tension (if roman
                                            (analyze/tension-score roman)
                                            0.0)]
                              {:bar     bar
                               :chord   pitches
                               :roman   (:roman roman)
                               :tension tension}))
                          bars)
        tension-arc (mapv #(select-keys % [:bar :tension]) progression)
        peak        (apply max-key :tension progression)
        final       (last progression)]
    {:progression  progression
     :tension-arc  tension-arc
     :peak-bar     (:bar peak)
     :peak-tension (:tension peak)
     :final-chord  final}))

;; ---------------------------------------------------------------------------
;; 5. Wrong note detection and correction proposals
;; ---------------------------------------------------------------------------

(defn- chromatic-passing?
  "Return true if `note` is a chromatic passing tone — it lies between
  the previous and next note's pitch and creates a half-step approach."
  [prev-midi midi next-midi]
  (when (and prev-midi next-midi)
    (let [lo (min prev-midi next-midi)
          hi (max prev-midi next-midi)]
      (and (> midi lo) (< midi hi)
           (or (= 1 (- midi lo)) (= 1 (- hi midi)))))))

(defn- contour-preserving-correction
  "Find the nearest scale degree to `midi` that preserves melodic direction
  relative to `prev-midi`."
  [sc midi prev-midi]
  (let [direction   (if prev-midi (if (> midi prev-midi) :up :down) :none)
        candidates  (->> (range (- midi 3) (+ midi 4))
                         (filter #(scale/in-scale? sc (pitch/midi->pitch %)))
                         (sort-by #(Math/abs (- % midi))))]
    (case direction
      :up   (first (filter #(>= % midi) candidates))
      :down (first (filter #(<= % midi) candidates))
      (first candidates))))

(defn correct-outliers!
  "Identify out-of-place notes in motif voices and propose corrections.

  A note is flagged if it is:
    1. Not a diatonic scale degree in the detected key/mode, AND
    2. Not explainable as a chromatic passing tone

  Corrections preserve melodic contour — the proposed replacement maintains
  the same direction of motion as the original, with the smallest correction
  interval.

  Returns PROPOSALS only — corrections are never applied automatically.
  Review with `print-corrections`, apply selectively with `accept-corrections`.

  `voices`     — result of separate-voices!
  `key-result` — result of detect-key!

  Returns:
    {:corrected  [{:voice :motif-1 :index N
                   :original  {:pitch/midi 61 ...}
                   :proposed  {:pitch/midi 62 ...}
                   :reason    \"out-of-scale, no chromatic context\"} ...]
     :unchanged  [notes...]}"
  [voices key-result]
  (let [sc      (:scale key-result)
        results (atom {:corrected [] :unchanged []})]
    (doseq [voice-kw [:motif-1 :motif-2]]
      (let [notes (get voices voice-kw [])]
        (doseq [[i note] (map-indexed vector notes)]
          (let [midi      (:pitch/midi note)
                in-scale  (scale/in-scale? sc (pitch/midi->pitch midi))
                prev-midi (:pitch/midi (get notes (dec i)))
                next-midi (:pitch/midi (get notes (inc i)))
                passing   (chromatic-passing? prev-midi midi next-midi)]
            (cond
              in-scale
              (swap! results update :unchanged conj note)

              passing
              (swap! results update :unchanged conj note)

              :else
              (let [correction (contour-preserving-correction sc midi prev-midi)]
                (swap! results update :corrected conj
                       {:voice    voice-kw
                        :index    i
                        :original note
                        :proposed (when correction (assoc note :pitch/midi correction))
                        :reason   "out-of-scale, no chromatic passing context"})))))))
    @results))

(defn print-corrections
  "Pretty-print the correction proposals from correct-outliers!."
  [corrections]
  (if (empty? (:corrected corrections))
    (println "No out-of-place notes detected.")
    (do
      (println (format "%d correction(s) proposed:\n" (count (:corrected corrections))))
      (doseq [{:keys [voice index original proposed reason]} (:corrected corrections)]
        (println (format "  %s[%d] midi %d → %d  (%s)"
                         (name voice) index
                         (:pitch/midi original)
                         (or (:pitch/midi proposed) \?)
                         reason))))))

(defn accept-corrections
  "Apply all proposed corrections to a score map.

  Updates the :voices in the score with corrected pitch values.
  Does not modify corrections for which :proposed is nil (no valid correction found).

  Returns the updated score map."
  [score]
  (reduce (fn [s {:keys [voice index proposed]}]
            (if proposed
              (assoc-in s [:voices voice index :pitch/midi] (:pitch/midi proposed))
              s))
          score
          (get-in score [:corrections :corrected] [])))

;; ---------------------------------------------------------------------------
;; 6. Resolution generation — corpus-guided harmonic completion
;; ---------------------------------------------------------------------------

(defn- resolution-progression
  "Choose a resolution chord sequence from the final harmonic state.

  Ending styles:
    :authentic-cadence — V7→i in 2 bars (Bach-style, hard landing)
    :ambient-fade      — gradual descent over :bars bars, thin to tonic
    :cyclical-return   — return briefly to opening texture then fade"
  [final-chord key-result style bars]
  (let [sc      (:scale key-result)
        tonic   (:tonic key-result)
        mode    (:mode key-result)
        tension (:tension final-chord 0.5)]
    (case style
      :authentic-cadence
      ;; V7 → i (2 bar cadence)
      (analyze/suggest-progression sc [tension 0.9 0.0] :include-borrowed? true)

      :ambient-fade
      ;; Generate a gradual tension descent to 0
      (let [steps    (max 2 bars)
            targets  (mapv #(* tension (- 1.0 (/ % (double steps))))
                           (range (inc steps)))]
        (analyze/suggest-progression sc targets :include-borrowed? false))

      :cyclical-return
      ;; Return to opening texture (low tension) then drop to tonic
      (analyze/suggest-progression sc [tension 0.2 0.1 0.0]
                                   :include-borrowed? false))))

(defn generate-resolution!
  "Generate a resolution passage from the detected harmonic endpoint.

  Queries the Bach corpus for authentic voice-leading patterns matching
  the detected mode and final chord, then generates step sequences for
  all four NDLR voices.

  `structure`  — result of annotate-structure!
  `key-result` — result of detect-key!
  `opts`:
    :bars   — resolution length in bars (default 4)
    :style  — :authentic-cadence, :ambient-fade (default), :cyclical-return
    :tempo  — BPM for note duration calculation (default 120)

  Returns:
    {:bars     4
     :style    :ambient-fade
     :voices   {:drone [...] :pad [...] :motif-1 [...] :motif-2 [...]
     :progression [...chord maps...]}"
  ([structure key-result] (generate-resolution! structure key-result {}))
  ([structure key-result {:keys [bars style tempo]
                          :or   {bars 4 style :ambient-fade tempo 120}}]
   (let [final     (:final-chord structure)
         prog      (resolution-progression final key-result style bars)
         sc        (:scale key-result)
         beats-per-chord (/ (* bars 4.0) (count prog))
         ;; Build simple pad voice from the resolution progression
         pad-notes (mapv (fn [i chord-map]
                           (let [root-p  (scale/pitch-at sc (:degree chord-map 0))
                                 third-p (scale/pitch-at sc (+ (:degree chord-map 0) 2))
                                 fifth-p (scale/pitch-at sc (+ (:degree chord-map 0) 4))]
                             {:chord       (mapv pitch/pitch->midi [root-p third-p fifth-p])
                              :start/beats (* i beats-per-chord)
                              :dur/beats   beats-per-chord
                              :velocity    55}))
                         (range) prog)
         ;; Drone: hold tonic throughout resolution
         tonic-midi (pitch/pitch->midi (scale/pitch-at sc 0))
         drone-note {:pitch/midi  (- tonic-midi 12)   ; one octave below
                     :start/beats 0.0
                     :dur/beats   (* bars 4.0)
                     :velocity    45}]
     {:bars       bars
      :style      style
      :progression prog
      :voices     {:drone   [drone-note]
                   :pad     pad-notes
                   :motif-1 []    ; motifs thin to silence in resolution
                   :motif-2 []}})))

;; ---------------------------------------------------------------------------
;; 7. Full pipeline
;; ---------------------------------------------------------------------------

(defn repair-pipeline!
  "Run the complete MIDI repair pipeline on a recording file.

  Combines all phases: ingest → key detect → voice separate →
  structure annotate → outlier correct → resolution generate.

  `path` — path to .mid file
  `opts`:
    :structure     — voice separation prior: :ndlr (default)
    :resolution    — :ambient-fade (default), :authentic-cadence, :cyclical-return
    :resolution-bars — bars for resolution passage (default 4)

  Returns a score map:
    {:meta        {:tonic :D :mode :dorian :tempo 112 :bars 32}
     :voices      {:drone [...] :pad [...] :motif-1 [...] :motif-2 [...]}
     :structure   {:progression [...] :tension-arc [...] :peak-bar N ...}
     :corrections {:corrected [...] :unchanged [...]}
     :resolution  {:bars 4 :style :ambient-fade :voices {...}}}"
  ([path] (repair-pipeline! path {}))
  ([path {:keys [structure resolution resolution-bars]
          :or   {structure :ndlr resolution :ambient-fade resolution-bars 4}}]
   (println "Parsing MIDI file...")
   (let [parsed   (parse-midi! path)
         _        (println (format "  %d notes, %.1f BPM, %d bars"
                                   (count (:notes parsed))
                                   (:tempo parsed)
                                   (:bars parsed)))
         _        (println "Detecting key and mode...")
         key-res  (detect-key! parsed)
         _        (println (format "  %s %s (confidence %.2f)"
                                   (name (:tonic key-res))
                                   (name (:mode key-res))
                                   (:confidence key-res)))
         _        (println "Separating voices...")
         voices   (separate-voices! parsed {:structure structure})
         _        (println (format "  drone=%d pad=%d motif-1=%d motif-2=%d ambiguous=%d"
                                   (count (:drone voices))
                                   (count (:pad voices))
                                   (count (:motif-1 voices))
                                   (count (:motif-2 voices))
                                   (count (:ambiguous voices))))
         _        (println "Annotating structure...")
         struct   (annotate-structure! voices key-res)
         _        (println (format "  %d chords, peak tension %.2f at bar %d, final tension %.2f"
                                   (count (:progression struct))
                                   (:peak-tension struct)
                                   (:peak-bar struct)
                                   (get-in struct [:final-chord :tension] 0)))
         _        (println "Identifying out-of-place notes...")
         corr     (correct-outliers! voices key-res)
         _        (println (format "  %d correction(s) proposed" (count (:corrected corr))))
         _        (println "Generating resolution...")
         res      (generate-resolution! struct key-res
                                        {:bars  resolution-bars
                                         :style resolution
                                         :tempo (:tempo parsed)})]
     (println "Done.\n")
     {:meta        {:tonic   (:tonic key-res)
                    :mode    (:mode key-res)
                    :tempo   (:tempo parsed)
                    :time-sig (:time-sig parsed)
                    :bars    (:bars parsed)}
      :voices      voices
      :structure   struct
      :corrections corr
      :resolution  res})))

;; ---------------------------------------------------------------------------
;; 8. Score playback and serialisation
;; ---------------------------------------------------------------------------

(defn play-score!
  "Play back a repaired score via cljseq live loops.

  Starts one live loop per voice. Plays the original voices followed by
  the resolution passage.

  `score`  — result map from repair-pipeline!
  `opts`:
    :include-resolution? — play the generated resolution after original (default true)
    :channels — voice → MIDI channel map (default {:drone 1 :pad 2 :motif-1 3 :motif-2 4})"
  ([score] (play-score! score {}))
  ([score {:keys [include-resolution? channels]
           :or   {include-resolution? true
                  channels {:drone 1 :pad 2 :motif-1 3 :motif-2 4}}}]
   (doseq [[voice-kw ch] channels]
     (let [notes     (get-in score [:voices voice-kw] [])
           res-notes (when include-resolution?
                       (get-in score [:resolution :voices voice-kw] []))
           all-notes (into (vec notes) (or res-notes []))]
       (when (seq all-notes)
         (let [bpm     (get-in score [:meta :tempo] 120.0)
               beat-ns (long (/ (* 60.0 1e9) bpm))
               base-ns (* (System/currentTimeMillis) 1000000)]
           (doseq [n all-notes]
             (let [midi    (:pitch/midi n (first (:chord n)))
                   vel     (:velocity n 64)
                   start   (long (* (:start/beats n 0) beat-ns))
                   dur     (long (* (:dur/beats n 0.25) beat-ns))]
               (sidecar/send-note-on!  (+ base-ns start) ch midi vel)
               (sidecar/send-note-off! (+ base-ns start dur) ch midi)))))))))

(defn save-score
  "Serialise a score map to an EDN string. Load back with load-score."
  [score]
  (pr-str score))

(defn load-score
  "Load a score map from an EDN string produced by save-score."
  [edn-str]
  (edn/read-string edn-str))
