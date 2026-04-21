; SPDX-License-Identifier: EPL-2.0
(ns cljseq.core
  "cljseq — music-theory-aware sequencer for Clojure.

  System lifecycle and primary user-facing API. Start with `(start!)`,
  stop with `(stop!)`. All interactive forms are available via:
    (require '[cljseq.core :refer :all])

  ## Hello clock session
    (start!)
    (deflive-loop :hello {}
      (play! {:pitch/midi 60 :dur/beats 1/2})
      (sleep! 1))
    (stop!)

  Key design decisions: Q47 (single system-state atom), Q48 (patch system),
  Q11 (live-loop alias), Q1 (virtual time), phase0-readiness.md."
  (:require [cljseq.clock           :as clock]
            [cljseq.conductor       :as conductor]
            [cljseq.config          :as config]
            [cljseq.ctrl            :as ctrl]
            [cljseq.dirs            :as dirs]
            [cljseq.flux            :as flux]
            [cljseq.link            :as link]
            [cljseq.loop            :as loop-ns]
            [cljseq.midi-in         :as midi-in]
            [cljseq.mod             :as mod]
            [cljseq.mpe             :as mpe]
            [cljseq.schema          :as schema]
            [cljseq.sidecar         :as sidecar]
            [cljseq.target          :as target]
            [cljseq.temporal-buffer :as tbuf]
            [cljseq.timeline        :as timeline]
            [clojure.edn            :as edn]
            [clojure.pprint         :as pprint]
            [clojure.string         :as str])
  (:import  [java.sql DriverManager]
            [java.util.concurrent.locks LockSupport]
            [java.time LocalDateTime]
            [java.time.format DateTimeFormatter])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; System state (Q47)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Session file naming
;; ---------------------------------------------------------------------------

(defn- session-filename
  "Generate <ISO-timestamp>[-<session-id>].sqlite"
  ^String [session-id]
  (let [ts (.format (LocalDateTime/now)
                    (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss"))]
    (if session-id
      (str ts "-" (name session-id) ".sqlite")
      (str ts ".sqlite"))))

;;; Single system-state atom. Shape per Q47:
;;;   :tree        — control tree (persistent map)
;;;   :serial      — structural change counter
;;;   :tx-log      — append-only transaction log (bounded vector)
;;;   :undo-stack  — bounded ring; default depth 50
;;;   :checkpoints — named snapshots for panic/revert
;;;   :loops       — live-loop registry
;;;   :config      — runtime configuration (BPM etc.)
(defonce system-state
  (atom {:tree         {}
         :serial       0
         :tx-log       []
         :undo-stack   []
         :checkpoints  {}
         :loops        {}
         :config       {:bpm 120 :beats-per-bar 4}
         :timeline     nil
         :session-path nil}))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(defn get-bpm
  "Return current BPM."
  []
  (get-in @system-state [:config :bpm]))

(defn get-beats-per-bar
  "Return the current beats-per-bar setting (default 4)."
  []
  (get-in @system-state [:config :beats-per-bar] 4))

(defn set-beats-per-bar!
  "Set the global beats-per-bar value (e.g. 3 for waltz time, 6 for compound).
  Used as the default by journey/start-bar-counter! and supervisor/start-watchdog!."
  [n]
  (swap! system-state assoc-in [:config :beats-per-bar] n)
  nil)

(defn get-timeline
  "Return the current timeline map for beat↔epoch-ms conversion.
  Returns nil before start! is called."
  []
  (:timeline @system-state))

(defn set-bpm!
  "Set the master BPM.

  Rolls the timeline anchor to the current wall-clock instant so that
  beat->epoch-ms is always valid relative to the new tempo (Q59).

  After rolling the anchor, unparks all sleeping loop threads (Q60) so that
  each recomputes its wall-clock deadline via park-until-beat! against the
  new timeline. The loops' virtual-time beat positions are unaffected —
  they continue from the same beat, arriving at the correct wall-clock time."
  [bpm]
  (swap! system-state
         (fn [s]
           (let [tl       (:timeline s)
                 now-ms   (System/currentTimeMillis)
                 cur-beat (if tl
                            (clock/epoch-ms->beat now-ms tl)
                            0.0)]
             (-> s
                 (assoc-in [:config :bpm] bpm)
                 (assoc :timeline {:bpm            bpm
                                   :beat0-epoch-ms now-ms
                                   :beat0-beat     cur-beat})))))
  ;; Unpark all sleeping loop threads so they recompute their deadline (Q60)
  (doseq [[_ entry] (:loops @system-state)]
    (when-let [^Thread t (:thread entry)]
      (LockSupport/unpark t)))
  ;; Phase 2: propagate tempo change to the Link session when active.
  ;; This makes (core/set-bpm! x) equivalent to (link/set-bpm! x) when linked.
  (when (link/active?)
    (link/set-bpm! bpm))
  ;; Sync BPM to ctrl tree so HTTP GET /ctrl/config/bpm reflects current value.
  ;; Guarded by timeline presence (proxy for start! having been called).
  (when (:timeline @system-state)
    (ctrl/set! [:config :bpm] (double bpm)))
  nil)

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Boot the cljseq system.

  Options:
    :bpm           — initial BPM (default 120)
    :beats-per-bar — beats per bar (default 4; use 3 for waltz, 6 for compound)
    :session-id    — keyword or string appended to the session filename
                     (e.g. :berlin-study → 2026-04-20T21-34-00-berlin-study.sqlite)
    :no-log        — when true, suppress durable session logging (default false)

  Registers the system-state atom with cljseq.loop and initialises the
  master timeline anchored at the current wall-clock instant / beat 0.

  A session file path is computed and stored under :session-path. Call
  `(open-session!)` after `(start-sidecar!)` to activate durable logging."
  [& {:keys [bpm beats-per-bar session-id no-log] :or {bpm 120 beats-per-bar 4}}]
  (let [now-ms      (System/currentTimeMillis)
        session-path (when-not no-log
                       (str (dirs/sessions-dir) "/"
                            (session-filename session-id)))]
    (swap! system-state
           (fn [s]
             (-> s
                 (assoc :config (assoc (config/default-config) :bpm bpm
                                                               :beats-per-bar beats-per-bar))
                 (assoc :timeline {:bpm            bpm
                                   :beat0-epoch-ms now-ms
                                   :beat0-beat     0.0})
                 (assoc :tx-log       [])
                 (assoc :undo-stack   [])
                 (assoc :session-path session-path)
                 (dissoc :link-state :link-timeline))))
    (loop-ns/-register-system! system-state)
    (ctrl/-register-system! system-state)
    (flux/-register-system! system-state)
    (tbuf/-register-system! system-state)
    (link/-register-system! system-state)
    (mod/-register-system! system-state)
    (config/-register-system! system-state)
    (timeline/-register-system! system-state)
    ;; Register side effects for params with runtime consequences.
    ;; :bpm — full timeline reanchor + thread unpark + Link propagation.
    (config/-register-effect! :bpm set-bpm!)
    ;; Seed ctrl tree nodes for all registry params (HTTP/OSC read access).
    (config/seed-ctrl-tree!)
    (println (str "cljseq started at " bpm " BPM"))
    nil))

(defn open-session!
  "Open a durable session log in the sidecar.

  Sends a SessionOpen (0x31) IPC message to the sidecar, directing it to create
  (or reuse) a SQLite file at the path computed by `start!`.

  Call this after `start-sidecar!`. If no session path was set (e.g. `:no-log true`
  was passed to `start!`), this is a no-op.

  Example:
    (start! :bpm 120 :session-id :berlin-study)
    (start-sidecar! :midi-port 0)
    (open-session!)   ; → writes to ~/.cljseq/sessions/2026-04-20T21-34-00-berlin-study.sqlite"
  []
  (when-let [path (:session-path @system-state)]
    (when (sidecar/connected?)
      (sidecar/open-session! path)
      (println (str "[session] logging to " path))))
  nil)

(defn session-path
  "Return the durable session file path for the current session, or nil if
  logging is disabled."
  []
  (:session-path @system-state))

;; ---------------------------------------------------------------------------
;; Session export / load
;; ---------------------------------------------------------------------------

;; Session state map shape:
;;   {:bpm N :beats-per-bar N
;;    :models        {model-id model-map ...}
;;    :realizations  {realization-id realization-map ...}
;;    :active        {model-id realization-id ...}
;;    :params        [[path value] ...]}

(defn- live-session-state
  "Read session state from the running ctrl tree."
  []
  (let [models  (or (schema/list-models) [])
        realzns (or (schema/list-realizations) [])]
    {:bpm           (get-bpm)
     :beats-per-bar (get-beats-per-bar)
     :models        (into {} (for [m models] [m (schema/get-model m)]))
     :realizations  (into {} (for [r realzns] [r (schema/get-realization r)]))
     :active        (into {} (for [m models
                                   :let [r (schema/active-realization m)]
                                   :when r]
                               [m r]))
     :params        (for [{:keys [path value]} (ctrl/all-nodes)
                          :when (and (some? value)
                                     (not= :cljseq/schema (first path))
                                     (not= :config        (first path)))]
                      [path value])}))

(defn- fold-journal-row
  "Merge one journal row (path vector + value) into the session state map."
  [state path value]
  (let [p0 (nth path 0 nil)
        p1 (nth path 1 nil)
        p2 (nth path 2 nil)]
    (cond
      (and (= p0 :config) (= p1 :bpm))           (assoc state :bpm value)
      (and (= p0 :config) (= p1 :beats-per-bar)) (assoc state :beats-per-bar value)
      (= p0 :config)                              state
      (and (= p0 :cljseq/schema) (= p1 :device-models))
      (assoc-in state [:models p2] value)
      (and (= p0 :cljseq/schema) (= p1 :realizations))
      (assoc-in state [:realizations p2] value)
      (and (= p0 :cljseq/schema) (= p1 :active-realizations))
      (assoc-in state [:active p2] value)
      (= p0 :cljseq/schema)                       state
      :else                                       (update state :params conj [path value]))))

(defn- journal-session-state
  "Fold a SQLite journal to last-value-per-path and return a session state map."
  [^String db-path]
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" db-path))
              stmt (.createStatement conn)
              rs   (.executeQuery stmt
                     "SELECT path, after FROM changes
                      WHERE id IN (SELECT MAX(id) FROM changes GROUP BY path)
                      AND after IS NOT NULL")]
    (loop [state {:bpm 120 :beats-per-bar 4
                  :models {} :realizations {} :active {} :params []}]
      (if-not (.next rs)
        state
        (let [path  (edn/read-string (.getString rs "path"))
              value (edn/read-string (.getString rs "after"))]
          (recur (if (vector? path)
                   (fold-journal-row state path value)
                   state)))))))

(defn- session-forms*
  "Return a seq of Clojure forms that recreate the given session state map."
  [{:keys [bpm beats-per-bar models realizations active params]}]
  (concat
    [`(cljseq.core/set-bpm! ~bpm)
     `(cljseq.core/set-beats-per-bar! ~beats-per-bar)]
    (for [[m model-map]       models]       `(cljseq.schema/defdevice-model ~m ~model-map))
    (for [[r realization-map] realizations] `(cljseq.schema/defrealization  ~r ~realization-map))
    (for [[m r]               active]       `(cljseq.schema/realize! ~m ~r))
    (for [[p v]               params]       `(cljseq.ctrl/set! ~p ~v))))

(defn- apply-session-state!
  "Apply a session state map to the running system — direct function calls, no eval."
  [{:keys [bpm beats-per-bar models realizations active params]}]
  (set-bpm! bpm)
  (set-beats-per-bar! beats-per-bar)
  (doseq [[m model-map]       models]       (schema/defdevice-model m model-map))
  (doseq [[r realization-map] realizations] (schema/defrealization r realization-map))
  (doseq [[m r]               active]       (schema/realize! m r))
  (doseq [[p v]               params]       (ctrl/set! p v)))

(defn- emit-session-file
  "Write a pprinted session definition file from forms. Returns out-path."
  [out-path header forms]
  (spit out-path
        (str header "\n"
             (str/join "\n" (map #(with-out-str (pprint/pprint %)) forms))))
  out-path)

(defn export-session!
  "Snapshot the current session state to a loadable Clojure script.

  Captures BPM, beats-per-bar, all device model and realization declarations,
  active realization bindings, and non-nil ctrl parameter values.  The
  resulting file can be loaded into a fresh session with `load-session!`.

  Options:
    :path — explicit output path (default: <sessions-dir>/<timestamp>-export.clj)

  Returns the file path written.

  Example:
    (export-session!)
    (export-session! :path \"/tmp/berlin-warmup.clj\")"
  [& {:keys [path]}]
  (let [out-path (or path
                     (str (dirs/sessions-dir) "/"
                          (.format (LocalDateTime/now)
                                   (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss"))
                          "-export.clj"))
        header   (str/join "\n"
                           ["; cljseq session definition"
                            (str "; exported " (LocalDateTime/now))
                            (str "; (load-session! \"" out-path "\") to restore")
                            ""])]
    (emit-session-file out-path header (session-forms* (live-session-state)))
    (println (str "[session] exported to " out-path))
    out-path))

(defn restore-session!
  "Restore session state from a SQLite journal file written by the sidecar.

  Reads the journal, folds each path to its last written value, and applies
  the same semantic calls as export-session!: set-bpm!, defdevice-model,
  defrealization, realize!, ctrl/set!.

  Use this for crash recovery when no export file exists.  To inspect the
  recovered state first, use `export-from-journal!` instead.

  The system must already be started — restore-session! does not call start!.

  Example:
    (start!)
    (restore-session! \"/path/to/session.sqlite\")"
  [db-path]
  (apply-session-state! (journal-session-state db-path))
  nil)

(defn export-from-journal!
  "Export a SQLite journal as a loadable session definition script.

  Reads the journal, folds to last-value-per-path, and writes a .clj file
  with the same structure as export-session!.  Useful for inspecting or
  editing a journal-recovered state before committing to it.

  Options:
    :path — output path (default: <db-path>-export.clj)

  Returns the path written.

  Example:
    (export-from-journal! \"/path/to/session.sqlite\")
    ;; edit the .clj, then:
    (start!)
    (load-session! \"/path/to/session.sqlite-export.clj\")"
  [db-path & {:keys [path]}]
  (let [out-path (or path (str db-path "-export.clj"))
        header   (str/join "\n"
                           ["; cljseq session definition"
                            (str "; recovered from journal " db-path)
                            (str "; (load-session! \"" out-path "\") to restore")
                            ""])]
    (emit-session-file out-path header (session-forms* (journal-session-state db-path)))
    (println (str "[session] exported journal to " out-path))
    out-path))

(defn load-session!
  "Load a session definition from a Clojure script exported by `export-session!`.

  Evaluates the file in the current runtime.  The system must be started
  first — `load-session!` does not call `start!`.

  Example:
    (start! :bpm 120)
    (load-session! \"/path/to/my-jam.clj\")"
  [path]
  (load-file path)
  nil)

(defn stop!
  "Gracefully stop all live loops and shut down the system."
  []
  (midi-in/close-all-inputs!)
  (flux/stop-all!)
  (tbuf/stop-all!)
  (conductor/stop-all-conductors!)
  (mod/mod-unroute-all!)
  (loop-ns/stop-all-loops!)
  ;; Stop the m21 server if it was ever loaded (dynamic to avoid circular dep)
  (when-let [stop-m21 (resolve 'cljseq.m21/stop-server!)]
    (stop-m21))
  ;; Close the durable session log if the sidecar is connected
  (when (sidecar/connected?)
    (sidecar/close-session!))
  ;; Wait briefly for threads to notice the stop signal
  (Thread/sleep 50)
  (swap! system-state assoc :loops {})
  (println "cljseq stopped")
  nil)

;; ---------------------------------------------------------------------------
;; Microtonal pitch bend configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *bend-range-cents*
  "Pitch bend range in cents (one direction) used for the :pitch/bend-cents
  step map key. Default 200.0 (±2 semitones — the MIDI standard default).

  Match this to the pitch bend range configured on your synth. Override
  per-block with binding:
    (binding [core/*bend-range-cents* 1200.0]  ; ±1 octave bend range
      (play! (scala/degree->step ms root 7) 1/4))"
  200.0)

(defn- cents->bend14
  "Convert a cents offset to a 14-bit MIDI pitch bend value using
  *bend-range-cents* as the range calibration.

  Returns integer in [0, 16383]; center (no bend) = 8192."
  ^long [cents]
  (let [range (double *bend-range-cents*)
        frac  (/ (double cents) range)
        steps (if (>= frac 0.0) 8191.0 8192.0)]
    (max 0 (min 16383 (long (Math/round (+ 8192.0 (* frac steps))))))))

;; ---------------------------------------------------------------------------
;; Note keyword → MIDI conversion
;; ---------------------------------------------------------------------------

(def ^:private note-class
  {"C" 0 "D" 2 "E" 4 "F" 5 "G" 7 "A" 9 "B" 11})

(defn keyword->midi
  "Convert a note keyword to a MIDI note number.

  Examples:
    (keyword->midi :C4)  => 60
    (keyword->midi :A4)  => 69
    (keyword->midi :G#3) => 56
    (keyword->midi :Bb2) => 46

  Returns nil if the keyword is not a valid note name."
  [k]
  (when (keyword? k)
    (when-let [[_ note acc octave]
               (re-matches #"([A-G])([#b]?)(-?\d+)" (name k))]
      (let [base  (note-class note)
            shift (case acc "#" 1 "b" -1 0)
            oct   (Integer/parseInt octave)]
        (+ (* (+ oct 1) 12) base shift)))))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; SC dispatch — optional backend, registered by cljseq.sc at load time
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Legacy dispatch atoms — kept for backward compatibility.
;; New code should use cljseq.target/register! and ITriggerTarget instead.
;; All audio backends register their ITriggerTarget via target/register! at
;; namespace load time. play! routes :target/:synth/:sample keys through the
;; target registry exclusively — no legacy dispatch atoms.
;; ---------------------------------------------------------------------------

;; play! — Phase 0 stdout stub
;; ---------------------------------------------------------------------------

(defn play!
  "Play a note. Routes to the sidecar for MIDI output when connected;
  falls back to stdout when running without a sidecar (Phase 0 mode).

  Accepts:
    (play! {:pitch/midi 60 :dur/beats 1/2})   ; step map (canonical form)
    (play! {:pitch/midi 60 :dur/beats 1/4
            :midi/channel 2 :mod/velocity 80}) ; with channel and velocity
    (play! :C4)                                ; note keyword, *default-dur*
    (play! :C4 1/2)                            ; note keyword + explicit dur
    (play! 60)                                 ; MIDI integer, *default-dur*
    (play! 60 1/4)                             ; MIDI integer + explicit dur

  Step map keys honoured:
    :pitch/midi     — MIDI note number (required)
    :dur/beats      — note duration in beats (default 1/4)
    :midi/channel   — MIDI channel 1–16 (default 1)
    :mod/velocity   — MIDI velocity 0–127 (default 64)"
  ([note]
   (play! note nil))
  ([note dur]
   ;; Target dispatch — routes :target/:synth/:sample step maps to registered
   ;; ITriggerTargets via the target registry.
   ;;
   ;; Routing rules:
   ;;   :target :foo          — explicit: look up :foo in registry
   ;;   :synth  :my-synthdef  — SC shorthand: routes to :sc target
   ;;                           (:synth names the SC SynthDef, not the target)
   ;;   :sample :my-buf       — sample shorthand: routes to :sample target
   ;;
   ;; Event normalisation: play! ensures :dur/beats is present before handing
   ;; the step map to trigger-note!. No :bpm or :dur keys are injected —
   ;; backends retrieve BPM via core/get-bpm when they need it.
   (cond
     ;; 1. Explicit :target key — look up named target directly
     (and (map? note) (contains? note :target) (target/lookup (:target note)))
     (let [tgt (target/lookup (:target note))
           ev  (cond-> note (nil? (:dur/beats note)) (assoc :dur/beats (or dur 1/4)))]
       (target/trigger-note! tgt ev))

     ;; 2. :synth key — SC shorthand; routes to :sc target regardless of synth name
     ;;    (:synth is the SynthDef name; :sc is the target)
     (and (map? note) (contains? note :synth) (target/lookup :sc))
     (let [tgt (target/lookup :sc)
           ev  (cond-> note (nil? (:dur/beats note)) (assoc :dur/beats (or dur 1/4)))]
       (target/trigger-note! tgt ev))

     ;; 3. :sample key — sample shorthand; routes to :sample target
     (and (map? note) (contains? note :sample) (target/lookup :sample))
     (let [tgt (target/lookup :sample)
           ev  (cond-> note (nil? (:dur/beats note)) (assoc :dur/beats (or dur 1/4)))]
       (target/trigger-note! tgt ev))

     :else
     (let [midi     (cond
                      (map? note)     (:pitch/midi note)
                      (keyword? note) (keyword->midi note)
                      (integer? note) note
                      :else           (throw (ex-info "play!: unrecognised note form"
                                                      {:note note})))
           beats    (or (and (map? note) (:dur/beats note))
                        dur
                        1/4)
           now-beat (double loop-ns/*virtual-time*)]
       (if (sidecar/connected?)
         (let [tl       (:timeline @system-state)
               timing   loop-ns/*timing-ctx*
               t-off-ns (if timing
                          (long (Math/round (* (clock/sample timing now-beat)
                                               (clock/beats->ms 1.0 (double (:bpm tl)))
                                               1000000.0)))
                          0)
               step     (if (map? note) note {:pitch/midi midi :dur/beats beats})]
           ;; Route through MPE allocator when enabled and step has per-note expression keys
           (if (and (mpe/mpe-enabled?) (mpe/mpe-step? step))
             (mpe/play-mpe! step now-beat tl)
             (let [channel   (or (and (map? note) (:midi/channel note)) 1)
                   velocity  (or (and (map? note) (:mod/velocity note)) 64)
                   wall-ns   (* (System/currentTimeMillis) 1000000)
                   on-ns     (max wall-ns (clock/beat->epoch-ns now-beat tl))
                   off-ns    (max (+ on-ns (* (long (clock/beats->ms beats (double (:bpm tl)))) 1000000))
                                  (clock/beat->epoch-ns (+ now-beat (double beats)) tl))
                   t-on-ns   (+ on-ns t-off-ns)]
               ;; Dispatch ctrl-path step-mods at note-on time (§24.6 Phase 3)
               (doseq [[k mod] (or loop-ns/*step-mod-ctx* [])
                       :when (vector? k)]
                 (let [val (if (satisfies? clock/ITemporalValue mod)
                             (clock/sample mod now-beat)
                             mod)]
                   (ctrl/send-at! t-on-ns k val)))
               ;; Microtonal pitch bend — send before note-on, reset after note-off
               (let [bend-cents (when (map? note) (:pitch/bend-cents note))
                     bend-14bit (when (and bend-cents (not (zero? (double bend-cents))))
                                  (cents->bend14 bend-cents))]
                 (when bend-14bit
                   (sidecar/send-pitch-bend! t-on-ns channel bend-14bit))
                 (sidecar/send-note-on!  t-on-ns channel midi velocity)
                 (sidecar/send-note-off! (+ off-ns t-off-ns) channel midi)
                 (when bend-14bit
                   (sidecar/send-pitch-bend! (+ off-ns t-off-ns) channel 8192))))))
         (let [ms (long (clock/beats->ms beats (get-bpm)))]
           (println (format "[play!] beat=%-8s midi=%-3d dur=%s beats (%dms)"
                            (str now-beat) midi (str beats) ms))))))))

;; ---------------------------------------------------------------------------
;; apply-trajectory! — continuous SC node parameter control
;; ---------------------------------------------------------------------------

(defn apply-trajectory!
  "Continuously apply an ITemporalValue trajectory by calling `setter-fn`
  with each sampled value at ~50ms intervals until the trajectory completes.

  `setter-fn` — a (fn [value]) called on each sample tick
  `traj`      — any ITemporalValue: Trajectory, OneShot, constant, etc.

  Returns a zero-argument cancel function. Call it to stop the trajectory
  before it completes naturally.

  Requires the system clock to be running (start!).

  This is the general form. For the SC-specific 3-arity convenience
  (apply-trajectory! node param traj), use cljseq.sc/apply-trajectory!

  Example:
    ;; General form — any setter
    (apply-trajectory! #(println \"val:\" %)
      (traj/trajectory :from 0.0 :to 1.0 :beats 8 :curve :s-curve :start (now)))

    ;; SC form (via cljseq.sc or cljseq.user)
    (let [node (sc/sc-synth! :saw-pad {:freq 220 :amp 0.5})]
      (apply-trajectory! node :cutoff
        (traj/trajectory :from 0.2 :to 0.9 :beats 16 :curve :s-curve
                         :start (now))))"
  [setter-fn traj]
  (let [running (atom true)
        thread  (Thread.
                  (fn []
                    (try
                      (loop []
                        (when @running
                          (let [tl    (get-timeline)
                                beat  (if tl
                                        (clock/epoch-ms->beat
                                          (System/currentTimeMillis) tl)
                                        0.0)
                                value (clock/sample traj beat)
                                nxt   (clock/next-edge traj beat)]
                            (setter-fn value)
                            (when (and @running (< nxt ##Inf))
                              (Thread/sleep 50)
                              (recur)))))
                      (catch InterruptedException _)
                      (catch Exception e
                        (when @running
                          (println "[apply-trajectory!] error:" (.getMessage e)))))))]
    (.setDaemon thread true)
    (.start thread)
    (fn cancel []
      (reset! running false)
      nil)))

;; ---------------------------------------------------------------------------
;; Re-export live-loop API from cljseq.loop
;; ---------------------------------------------------------------------------

(defn now
  "Return the current virtual beat position. Delegates to cljseq.loop/now."
  []
  (loop-ns/now))

(defn sleep!
  "Advance virtual time and sleep. Delegates to cljseq.loop/sleep!"
  [beats]
  (loop-ns/sleep! beats))

(defn sync!
  "Align to the next beat-grid boundary and park until it.
  See cljseq.loop/sync! for full docs.

  (sync!)    — next whole beat
  (sync! 4)  — next bar boundary (4 beats)
  (sync! 1/2) — next half-beat"
  ([] (loop-ns/sync!))
  ([divisor] (loop-ns/sync! divisor)))

(defmacro deflive-loop
  "Define a named live loop. See cljseq.loop/deflive-loop for full docs."
  [loop-name opts & body]
  `(loop-ns/deflive-loop ~loop-name ~opts ~@body))

(defmacro live-loop
  "Alias for deflive-loop (Q11). See cljseq.loop/deflive-loop."
  [loop-name opts & body]
  `(loop-ns/deflive-loop ~loop-name ~opts ~@body))

(defn stop-loop!
  "Stop a named live loop."
  [loop-name]
  (loop-ns/stop-loop! loop-name))

;; ---------------------------------------------------------------------------
;; ctrl namespace stubs (Phase 0: read-only access to system state)
;; ---------------------------------------------------------------------------

(defn ctrl-get
  "Read a value from the system state at `path`.
  Phase 0 stub for the full ctrl/get from cljseq.ctrl."
  [path]
  (get-in @system-state path))

;; ---------------------------------------------------------------------------
;; -main
;; ---------------------------------------------------------------------------

(defn -main
  [& _args]
  (println "cljseq — use at a Clojure nREPL"))
