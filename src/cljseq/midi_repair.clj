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

(def ^:private mode->scale-name
  "Map from detect-mode output names to cljseq scale library names.
  detect-mode returns church mode names; the scale library uses :major and
  :natural-minor for the diatonic endpoints."
  {:ionian     :major
   :aeolian    :natural-minor
   :dorian     :dorian
   :phrygian   :phrygian
   :lydian     :lydian
   :mixolydian :mixolydian
   :locrian    :locrian})

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
        sc        (scale/scale (:tonic result) 4
                               (mode->scale-name (:mode result) (:mode result)))]
    (assoc result :scale sc)))

;; ---------------------------------------------------------------------------
;; 3. Voice separation — NDLR structure-aware heuristic
;; ---------------------------------------------------------------------------

(def ^:private ndlr-thresholds
  "Heuristic thresholds for NDLR voice classification.

  Voice separation is duration-first: notes are bucketed by duration before
  any chord-cluster analysis. This prevents dense motif runs (where consecutive
  notes may be <60ms apart) from being misidentified as pad chords."
  {:drone-dur-beats    4.0    ; notes held ≥ 4 beats are drone candidates
   :drone-midi-max     50     ; drone lives below MIDI 50 (D3)
   :pad-dur-min        0.5    ; pad notes are ≥ 0.5 beats (eighth note at 120 BPM)
   :pad-dur-max        4.0    ; pad notes are < 4 beats (else they'd be drone)
   :pad-cluster-ms     80     ; within pad-dur subset: notes within 80ms = chord spread
   :pad-midi-range     [48 84]; pad lives between C3 and C6
   :motif-dur-max      0.5    ; motif notes are shorter than 0.5 beats
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

  Uses a duration-first strategy:
    1. Drone: long duration (≥ 4 beats) AND low register
    2. Motif candidates: short duration (< pad-dur-min beats) — pulled out BEFORE
       any cluster analysis so that dense motif runs are not mistaken for pad chords
    3. Pad candidates: medium duration notes, then chord-cluster analysis within that set
    4. Motif-1 vs motif-2: split by register

  This ordering is critical for dense recordings where consecutive motif notes
  may be closer than the pad-cluster-ms window.

  `parsed`  — result map from parse-midi!, or a flat note vector
  `opts`:
    :structure  — :ndlr (default) or :generic (4-voice, less accurate)
    :thresholds — override any entry in ndlr-thresholds
    :tempo      — BPM used for ms→beats conversion (default 120)

  Returns:
    {:drone    [notes...]
     :pad      [notes...]
     :motif-1  [notes...]
     :motif-2  [notes...]
     :ambiguous [notes...]}      ; notes that failed classification"
  ([parsed] (separate-voices! parsed {}))
  ([parsed {:keys [structure thresholds tempo]
            :or   {structure :ndlr tempo 120}}]
   (let [notes     (if (map? parsed) (:notes parsed) (vec parsed))
         bpm       (if (map? parsed) (get parsed :tempo tempo) tempo)
         t         (merge ndlr-thresholds thresholds)
         sorted    (sort-by :start/beats notes)

         ;; Step 1: drone — long duration + low register
         {drone-cands true rest-1 false}
         (group-by #(and (>= (:dur/beats %) (:drone-dur-beats t))
                         (<= (:pitch/midi %) (:drone-midi-max t)))
                   sorted)

         ;; Step 2: duration-first split — separate motif candidates from pad candidates
         ;; BEFORE any time-clustering so dense motif runs don't absorb into pad
         {motif-cands true pad-cands false}
         (group-by #(< (:dur/beats %) (:motif-dur-max t)) rest-1)

         ;; Step 3: chord cluster analysis — applied ONLY to pad-duration candidates
         ;; Convert ms window to beats using actual tempo
         cluster-window (/ (:pad-cluster-ms t) 1000.0 (/ 60.0 bpm))
         clusters  (cluster-by-time pad-cands cluster-window)
         {pad-clusters true lone-pad false}
         (group-by #(> (count %) 1) clusters)

         pad-notes  (mapcat identity pad-clusters)
         ;; Single pad-duration notes that don't cluster: treat as ambiguous
         ambiguous-pad (mapcat identity lone-pad)

         ;; Validate pad register
         {pad-in-range true out-of-range false}
         (group-by #(let [lo (first (:pad-midi-range t))
                          hi (second (:pad-midi-range t))]
                      (and (>= (:pitch/midi %) lo) (<= (:pitch/midi %) hi)))
                   pad-notes)

         ;; Step 4: split motif candidates by register into motif-1/2
         {motif-1 true motif-2 false}
         (group-by #(> (:pitch/midi %) (:motif-split-midi t)) motif-cands)

         ambiguous (concat ambiguous-pad out-of-range)]

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
;; 6. Ambiguous note resolution — secondary voice classification
;; ---------------------------------------------------------------------------

(defn resolve-ambiguous!
  "Attempt secondary classification of notes the primary heuristic left ambiguous.

  The ambiguous pool consists of mid-duration notes (0.5–4 beats) that did not
  cluster with neighbouring notes in the pad spread window. Three passes:

  Pass 1 — temporal context: if a note's nearest neighbours (within 1 beat) are
  predominantly motif notes, pull it into the appropriate motif voice.

  Pass 2 — pitch register: notes above :motif-split-midi → motif-1;
  at/below → motif-2.  This handles single sustained notes in the upper register
  that a melodic player held between motif runs.

  Pass 3 — what remains goes to pad as single sparse harmonics.

  `voices`  — result map from separate-voices! (must include :ambiguous key)
  `opts`:
    :thresholds — override ndlr-thresholds entries

  Returns an updated voices map with :ambiguous merged into the named voices."
  ([voices] (resolve-ambiguous! voices {}))
  ([voices {:keys [thresholds]}]
   (let [t          (merge ndlr-thresholds thresholds)
         ambiguous  (:ambiguous voices [])
         motif-all  (into (vec (:motif-1 voices [])) (:motif-2 voices []))
         motif-set  (set (map :start/beats motif-all))

         context-window 1.0   ; beats: look within this window for motif neighbours

         ;; Pass 1: temporal context — is this note surrounded by motif activity?
         {ctx-motif true ctx-rest false}
         (group-by (fn [n]
                     (let [lo (- (:start/beats n) context-window)
                           hi (+ (:start/beats n) context-window)]
                       (boolean (some #(and (>= % lo) (<= % hi)) motif-set))))
                   ambiguous)

         ;; ctx-motif notes: assign by register
         {ctx-motif-1 true ctx-motif-2 false}
         (group-by #(> (:pitch/midi %) (:motif-split-midi t)) (or ctx-motif []))

         ;; Pass 2: remaining notes — register-only decision
         {reg-motif-1 true reg-rest false}
         (group-by #(> (:pitch/midi %) (:motif-split-midi t)) (or ctx-rest []))

         ;; Pass 3: reg-rest goes to pad (sparse single harmonics in lower register)
         new-pad     (concat (:pad voices []) reg-rest)
         new-motif-1 (concat (:motif-1 voices []) ctx-motif-1 reg-motif-1)
         new-motif-2 (concat (:motif-2 voices []) ctx-motif-2)]

     (-> voices
         (assoc :drone    (:drone voices []))
         (assoc :pad      (vec (sort-by :start/beats new-pad)))
         (assoc :motif-1  (vec (sort-by :start/beats new-motif-1)))
         (assoc :motif-2  (vec (sort-by :start/beats new-motif-2)))
         (assoc :ambiguous [])))))

;; ---------------------------------------------------------------------------
;; 7. Resolution generation — corpus-guided harmonic completion
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
                     :velocity    45}

         ;; Motif-1: descending scale walk from degree 7 to tonic over the resolution
         ;; Notes get quieter and shorter as the passage approaches bar end (fade-out)
         total-beats   (* bars 4.0)
         motif-degrees (range 7 -1 -1)   ; degrees 7..0 inclusive = 8 notes
         motif-spacing (/ total-beats (count motif-degrees))
         motif-1-notes (mapv (fn [i deg]
                               (let [midi (pitch/pitch->midi (scale/pitch-at sc deg))
                                     ;; Keep motif in singable upper register (oct 4-5)
                                     midi (cond (< midi 60) (+ midi 12)
                                                (> midi 84) (- midi 12)
                                                :else midi)
                                     frac (/ i (double (dec (count motif-degrees))))
                                     vel  (int (Math/round (* 65.0 (- 1.0 (* frac 0.6)))))]
                                 {:pitch/midi  midi
                                  :start/beats (* i motif-spacing)
                                  :dur/beats   (max 0.25 (* motif-spacing 0.85))
                                  :velocity    vel}))
                             (range) motif-degrees)]
     {:bars       bars
      :style      style
      :progression prog
      :voices     {:drone   [drone-note]
                   :pad     pad-notes
                   :motif-1 motif-1-notes
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
         voices-raw (separate-voices! parsed {:structure structure :tempo (:tempo parsed)})
         _          (println (format "  drone=%d pad=%d motif-1=%d motif-2=%d ambiguous=%d"
                                     (count (:drone voices-raw))
                                     (count (:pad voices-raw))
                                     (count (:motif-1 voices-raw))
                                     (count (:motif-2 voices-raw))
                                     (count (:ambiguous voices-raw))))
         _          (println "Resolving ambiguous notes...")
         voices     (resolve-ambiguous! voices-raw)
         _          (println (format "  → drone=%d pad=%d motif-1=%d motif-2=%d ambiguous=%d"
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

;; ---------------------------------------------------------------------------
;; 9. MIDI export — round-trip back to a .mid file for DAW import
;; ---------------------------------------------------------------------------

(defn- beats->ticks ^long [beats ppq]
  (long (Math/round (double (* beats ppq)))))

(defn- make-tempo-track!
  "Add a tempo MetaMessage (type 0x51) to track 0."
  [^javax.sound.midi.Sequence sequence bpm]
  (let [track  (.createTrack sequence)
        uspqn  (long (/ 60000000.0 bpm))
        bytes  (byte-array [(unchecked-byte (bit-and (bit-shift-right uspqn 16) 0xFF))
                            (unchecked-byte (bit-and (bit-shift-right uspqn 8)  0xFF))
                            (unchecked-byte (bit-and uspqn 0xFF))])
        msg    (doto (javax.sound.midi.MetaMessage.)
                 (.setMessage 0x51 bytes 3))]
    (.add track (javax.sound.midi.MidiEvent. msg 0))
    track))

(defn- add-note-events!
  "Emit NOTE_ON + NOTE_OFF pair onto `track` at the given tick positions.
  `ch` is 1-based (as used throughout cljseq); MIDI API is 0-based."
  [^javax.sound.midi.Track track ch midi vel start-tick dur-ticks]
  (let [ch0     (dec (int ch))
        on-msg  (doto (javax.sound.midi.ShortMessage.)
                  (.setMessage javax.sound.midi.ShortMessage/NOTE_ON ch0 (int midi) (int vel)))
        off-msg (doto (javax.sound.midi.ShortMessage.)
                  (.setMessage javax.sound.midi.ShortMessage/NOTE_OFF ch0 (int midi) 0))]
    (.add track (javax.sound.midi.MidiEvent. on-msg  start-tick))
    (.add track (javax.sound.midi.MidiEvent. off-msg (+ start-tick (max 1 dur-ticks))))))

(defn- voice->track!
  "Write a sequence of note maps onto a new MIDI track.

  Handles both:
    {:pitch/midi N ...}        — single note (drone, motif)
    {:chord [N N N] ...}       — simultaneous notes (resolution pad)"
  [^javax.sound.midi.Sequence sequence notes ch ppq beat-offset]
  (let [track (.createTrack sequence)]
    (doseq [n notes]
      (let [start (beats->ticks (+ (double (:start/beats n 0)) beat-offset) ppq)
            dur   (beats->ticks (double (:dur/beats n 0.25)) ppq)
            vel   (int (:velocity n 64))
            midis (or (:chord n)
                      (when-let [m (:pitch/midi n)] [m]))]
        (doseq [midi midis]
          (add-note-events! track ch midi vel start dur))))
    track))

(defn save-midi!
  "Write the repaired score to a Standard MIDI File for DAW import.

  Produces Format 1 MIDI (multi-track) so each voice lands on a separate
  lane in Bitwig/Ableton/Logic:
    Track 0 — tempo map
    Track 1 — drone    (ch 1)
    Track 2 — pad      (ch 2)
    Track 3 — motif-1  (ch 3)
    Track 4 — motif-2  (ch 4)

  The resolution passage (if included) is appended after the last original
  bar, time-shifted so it starts exactly at bar (:bars meta).

  `score`  — result map from repair-pipeline! or load-score
  `path`   — output path, e.g. \"shipwreck-piano-repaired.mid\"
  `opts`:
    :include-resolution? — append resolution passage (default true)
    :channels — voice-kw → MIDI-channel map
    :ppq      — ticks per quarter note (default 480)"
  ([score path] (save-midi! score path {}))
  ([score path {:keys [include-resolution? channels ppq]
                :or   {include-resolution? true
                       channels {:drone 1 :pad 2 :motif-1 3 :motif-2 4}
                       ppq 480}}]
   (let [bpm           (double (get-in score [:meta :tempo] 120.0))
         bars          (get-in score [:meta :bars] 0)
         time-sig      (get-in score [:meta :time-sig] "4/4")
         beats-per-bar (Double/parseDouble (first (str/split time-sig #"/")))
         score-beats   (* bars beats-per-bar)
         sequence      (javax.sound.midi.Sequence. javax.sound.midi.Sequence/PPQ ppq)]
     (make-tempo-track! sequence bpm)
     (doseq [[voice-kw ch] channels]
       (let [orig-notes (get-in score [:voices voice-kw] [])
             res-notes  (when include-resolution?
                          (mapv #(update % :start/beats + score-beats)
                                (get-in score [:resolution :voices voice-kw] [])))]
         (voice->track! sequence
                        (into (vec orig-notes) res-notes)
                        ch ppq 0)))
     ;; Any remaining ambiguous notes get their own track (ch 5) so nothing is lost
     (when-let [ambig (seq (get-in score [:voices :ambiguous]))]
       (voice->track! sequence ambig 5 ppq 0))
     (with-open [out (java.io.FileOutputStream. path)]
       (javax.sound.midi.MidiSystem/write sequence 1 out))
     (println (format "Wrote %s  (%.1f BPM, %d bars%s)"
                      path bpm bars
                      (if include-resolution?
                        (format " + %d bar resolution" (get-in score [:resolution :bars] 0))
                        "")))
     path)))

;; ---------------------------------------------------------------------------
;; Phase 3 — Composition as data
;;   to-score, transpose, mode-shift, retrograde
;; ---------------------------------------------------------------------------

;; --- Internal helpers -------------------------------------------------------

(def ^:private tonic->pc
  "Pitch-class (0–11) for each tonic keyword."
  {:C 0 :C# 1 :Db 1 :D 2 :D# 3 :Eb 3 :E 4 :F 5 :F# 6 :Gb 6 :G 7 :G# 8 :Ab 8 :A 9 :A# 10 :Bb 10 :B 11})

(def ^:private pc->tonic
  "Canonical tonic keyword for each pitch-class 0–11 (flat spellings for black keys)."
  {0 :C 1 :Db 2 :D 3 :Eb 4 :E 5 :F 6 :Gb 7 :G 8 :Ab 9 :A 10 :Bb 11 :B})

(defn- shift-note
  "Shift all MIDI pitch values in a note map by `semitones`."
  [note semitones]
  (cond-> note
    (:pitch/midi note) (update :pitch/midi + semitones)
    (:chord note)      (update :chord #(mapv (fn [m] (+ m semitones)) %))))

(defn- shift-voices
  "Transpose all notes in every voice of `voices` by `semitones`."
  [voices semitones]
  (reduce-kv (fn [m k notes]
               (assoc m k (mapv #(shift-note % semitones) notes)))
             {}
             voices))

(defn- note-end-beat
  "Return the beat at which `note` ends."
  [note]
  (+ (:start/beats note 0.0) (:dur/beats note 0.0)))

(defn- voice-span
  "Return the total beat span of a voice (latest note end time)."
  [notes]
  (if (seq notes)
    (apply max (map note-end-beat notes))
    0.0))

(defn- retrograde-voice
  "Time-reverse the notes in `notes` within `span` beats."
  [notes span]
  (->> notes
       (mapv (fn [note]
               (assoc note :start/beats (- span (note-end-beat note)))))
       (sort-by :start/beats)
       vec))

(defn- nearest-in-scale-degree
  "Return the scale degree of the nearest in-scale neighbor to `midi` in `sc`.
  Searches ±6 semitones. Returns nil if none found."
  [sc midi]
  (let [candidates (->> (range (max 0 (- midi 6)) (min 128 (+ midi 7)))
                        (keep (fn [m]
                                (when-let [d (scale/degree-of sc (pitch/midi->pitch m))]
                                  [(Math/abs (- m midi)) d])))
                        (sort-by first))]
    (second (first candidates))))

(defn- remap-note-midi
  "Remap `midi` from `old-sc` to `new-sc` by scale degree.
  Preserves harmonic function; adjusts pitch class where modes diverge."
  [midi old-sc new-sc]
  (let [degree (or (scale/degree-of old-sc (pitch/midi->pitch midi))
                   (nearest-in-scale-degree old-sc midi))
        new-p  (when (some? degree) (scale/pitch-at new-sc degree))]
    (if new-p (pitch/pitch->midi new-p) midi)))

(defn- remap-note
  "Remap all MIDI pitch values in `note` from `old-sc` to `new-sc`."
  [note old-sc new-sc]
  (cond-> note
    (:pitch/midi note)
    (update :pitch/midi remap-note-midi old-sc new-sc)
    (:chord note)
    (update :chord #(mapv (fn [m] (remap-note-midi m old-sc new-sc)) %))))

(defn- remap-all-voices
  "Remap all notes in `voices` from `old-sc` to `new-sc`."
  [voices old-sc new-sc]
  (reduce-kv (fn [m k notes]
               (assoc m k (mapv #(remap-note % old-sc new-sc) notes)))
             {}
             voices))

;; --- Public API -------------------------------------------------------------

(defn to-score
  "Convert the repair-pipeline! output to a canonical EDN-serializable cljseq score.

  The score is the composition as a Clojure value: simultaneously the analysis
  and the artefact. Standard Clojure operates on it directly — no special API,
  no query language.

  `repair-out` — result of repair-pipeline!
  opts:
    :apply-corrections? — apply proposed corrections before building (default false)
    :include-coda?      — include generated resolution as :coda (default true)

  Returns:
    {:meta    {:tonic :G :mode :ionian :tempo 110.0 :bars 117 :time-sig \"4/4\"
               :key-sig \"G ionian\"}
     :voices  {:drone [...] :pad [...] :motif-1 [...] :motif-2 [...] :ambiguous [...]}
     :arc     {:progression [...] :tension-arc [...] :peak-bar N :peak-tension F
               :final-chord {...}}
     :coda    {:bars 4 :style :ambient-fade :voices {...}}}"
  ([repair-out] (to-score repair-out {}))
  ([repair-out {:keys [apply-corrections? include-coda?]
                :or   {apply-corrections? false include-coda? true}}]
   (let [{:keys [meta voices structure corrections resolution]} repair-out
         voices' (if (and apply-corrections? (seq (:corrected corrections)))
                   (:voices (accept-corrections {:voices voices :corrections corrections}))
                   voices)
         meta'   (assoc meta :key-sig (str (name (:tonic meta)) " " (name (:mode meta))))]
     (cond-> {:meta   meta'
              :voices voices'
              :arc    (select-keys structure [:progression :tension-arc
                                              :peak-bar :peak-tension :final-chord])}
       include-coda? (assoc :coda resolution)))))

(defn transpose
  "Shift all note pitches in a score by `semitones`. Updates :meta :tonic and :key-sig.

  Works on both to-score output (with :arc / :coda) and raw repair-pipeline!
  output (with :structure / :resolution).

  `score`     — score map from to-score or repair-pipeline!
  `semitones` — integer semitone offset (positive = up, negative = down)"
  [score semitones]
  (let [old-tonic (get-in score [:meta :tonic])
        new-pc    (mod (+ (get tonic->pc old-tonic 0) semitones) 12)
        new-tonic (pc->tonic new-pc)]
    (cond-> score
      true
      (assoc-in [:meta :tonic] new-tonic)
      (get-in score [:meta :mode])
      (assoc-in [:meta :key-sig]
                (str (name new-tonic) " " (name (get-in score [:meta :mode]))))
      (:voices score)
      (update :voices shift-voices semitones)
      (:coda score)
      (update-in [:coda :voices] shift-voices semitones)
      (:resolution score)
      (update-in [:resolution :voices] shift-voices semitones))))

(defn mode-shift
  "Re-colour a score's harmony to a new mode while keeping the same tonic.

  Maps each note from its degree in the original mode to the corresponding
  pitch in `new-mode` (same harmonic function, different pitch class where
  the modes diverge — e.g. Dorian ♮6 → Aeolian ♭6).

  Notes outside the original scale are snapped to the nearest in-scale
  neighbor before remapping.

  `score`    — score map from to-score or repair-pipeline!
  `new-mode` — target mode keyword, e.g. :aeolian, :phrygian, :mixolydian"
  [score new-mode]
  (let [tonic    (get-in score [:meta :tonic])
        old-mode (get-in score [:meta :mode])
        old-sc   (scale/scale tonic 4 old-mode)
        new-sc   (scale/scale tonic 4 new-mode)]
    (cond-> score
      true
      (-> (assoc-in [:meta :mode] new-mode)
          (assoc-in [:meta :key-sig] (str (name tonic) " " (name new-mode))))
      (:voices score)
      (update :voices remap-all-voices old-sc new-sc)
      (:coda score)
      (update-in [:coda :voices] remap-all-voices old-sc new-sc)
      (:resolution score)
      (update-in [:resolution :voices] remap-all-voices old-sc new-sc))))

(defn retrograde
  "Time-reverse the notes in a score.

  Without options: retrograde all voices using a common span (the full piece
  duration), so voices remain mutually aligned after reversal.

  With :voice kw: retrograde only that voice using its own span.

  `score` — score map from to-score or repair-pipeline!
  opts:
    :voice — keyword of a single voice to retrograde (e.g. :motif-1)
             omit to retrograde all voices"
  ([score] (retrograde score {}))
  ([score {:keys [voice]}]
   (let [voices (:voices score {})]
     (if voice
       (let [notes (get voices voice [])
             span  (voice-span notes)]
         (assoc-in score [:voices voice] (retrograde-voice notes span)))
       (let [all-spans (map voice-span (vals voices))
             span      (if (seq all-spans) (apply max all-spans) 0.0)]
         (update score :voices
                 (fn [vs]
                   (reduce-kv (fn [m k notes]
                                (assoc m k (retrograde-voice notes span)))
                              {} vs))))))))
