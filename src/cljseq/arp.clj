; SPDX-License-Identifier: EPL-2.0
(ns cljseq.arp
  "cljseq.arp — first-class arpeggiator engine with named pattern library.

  Two pattern formats:

  :chord patterns (R&R §22.5)
    :order   — indices into a chord voicing (0=root, 1=3rd, 2=5th, ...).
               May be :ascending or :descending (auto-derived from chord size).
               Indices ≥ chord length wrap with octave shifts upward.
    :rhythm  — vector of step durations in beats (1=quarter, 1/2=eighth, etc.)
    :dur     — gate fraction per step (3/4 = 75%)

  :phrase patterns (Hydrasynth-compatible)
    :steps   — vector of {:semi n :beats b} or {:rest true :beats b}.
               :semi is a semitone offset from the root of the held chord/note.
               :beats is the step duration in quarter-note beats.

  Built-in pattern library
  ─────────────────────────
  11 chord patterns loaded from resources/arpeggios/built-in.edn:
    :up :down :bounce :alberti :waltz-bass :broken-triad
    :guitar-pick :jazz-stride :montuno :raga-alap :euclid-5-8

  64 phrase patterns loaded from resources/arpeggios/hydrasynth-phrases.edn:
    :phrase-01 through :phrase-64

  Public API
  ──────────
  ls              — list all loaded patterns (keywords + descriptions)
  get-pattern     — return a pattern map by keyword
  register!       — add a custom pattern to the registry
  play!           — play one complete iteration of a pattern
  next-step!      — advance an arp-state atom one step, return MIDI event
  arp-loop        — start a stateful looping arp (returns loop-id atom)
  stop-arp!       — stop a running arp loop

  Examples
  ────────
  ;; One-shot chord arpeggio
  (arp/play! :alberti (chord/make :C4 :major) :vel 90)

  ;; One-shot Hydrasynth phrase (root = C4, semitone offsets applied)
  (arp/play! :phrase-14 {:root 60} :vel 80)

  ;; Looping phrase arp — stays alive until stop-arp! called
  (def my-arp (arp/arp-loop! :phrase-03 {:root 60} :vel 95))
  (arp/stop-arp! my-arp)"
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [cljseq.chord    :as chord]
            [cljseq.core     :as core]
            [cljseq.loop     :as loop-ns]))

;; ---------------------------------------------------------------------------
;; Pattern registry
;; ---------------------------------------------------------------------------

(def ^:private registry
  "Map of pattern-keyword → pattern map. Populated at load time from EDN files
  and user calls to register!."
  (atom {}))

(defn register!
  "Add or replace a pattern in the registry.

  `kw`      — keyword identifying the pattern (e.g. :my-alberti)
  `pattern` — map with at least :type and either :order/:rhythm/:dur (chord)
               or :steps (phrase)."
  [kw pattern]
  (swap! registry assoc kw pattern)
  kw)

(defn get-pattern
  "Return the pattern map for `kw`, or nil if not registered."
  [kw]
  (get @registry kw))

(defn ls
  "List all registered patterns. Returns a sorted vector of
  [keyword description] pairs."
  []
  (sort-by first
           (for [[kw pat] @registry]
             [kw (or (:description pat) (:name pat) (name kw))])))

;; ---------------------------------------------------------------------------
;; EDN loading helpers
;; ---------------------------------------------------------------------------

(defn- load-edn-resource
  "Read a vector of pattern maps from `resource-path` on the classpath.
  Returns nil if the resource is not found."
  [resource-path]
  (when-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))))

(defn- load-built-ins!
  "Load built-in chord patterns from resources/arpeggios/built-in.edn."
  []
  (when-let [patterns (load-edn-resource "arpeggios/built-in.edn")]
    (doseq [p patterns]
      (register! (:name p) p))))

(defn- load-hydrasynth-phrases!
  "Load 64 Hydrasynth phrases from resources/arpeggios/hydrasynth-phrases.edn.
  Each phrase is registered as :phrase-NN (zero-padded two digits)."
  []
  (when-let [phrases (load-edn-resource "arpeggios/hydrasynth-phrases.edn")]
    (doseq [p phrases]
      (let [kw (keyword (format "phrase-%02d" (:id p)))]
        (register! kw (assoc p :type :phrase :name (name kw)))))))

;; Load on namespace init
(load-built-ins!)
(load-hydrasynth-phrases!)

;; ---------------------------------------------------------------------------
;; Chord pattern helpers
;; ---------------------------------------------------------------------------

(defn- derive-order
  "Expand :ascending / :descending order keyword for a given chord size."
  [order chord-size]
  (cond
    (= order :ascending)  (vec (range chord-size))
    (= order :descending) (vec (range (dec chord-size) -1 -1))
    :else                 order))

(defn- chord-note-at
  "Return the MIDI note for chord-tone index `i`.
  Indices ≥ chord-size wrap upward by octave."
  [chord-midis i]
  (let [n    (count chord-midis)
        idx  (mod i n)
        oct  (quot i n)]
    (+ (nth chord-midis idx) (* 12 oct))))

;; ---------------------------------------------------------------------------
;; Phrase pattern helpers
;; ---------------------------------------------------------------------------

(defn- step-midi
  "Return the MIDI note for a phrase step given the root MIDI number."
  [root-midi step]
  (+ root-midi (:semi step 0)))

;; ---------------------------------------------------------------------------
;; Core playback
;; ---------------------------------------------------------------------------

(defn play!
  "Play one complete iteration of `pattern-kw` against `chord-or-root`.
  Blocks (sleeps) for the full pattern duration — call from inside a
  `live-loop` or from a background thread.

  Arguments
  ─────────
  pattern-kw     — keyword of a registered pattern, or a pattern map directly
  chord-or-root  — for :chord patterns: a chord map with :midi/notes key, or a
                   vector of MIDI integers.
                   For :phrase patterns: a map with :root (MIDI integer), or a
                   plain MIDI integer for the root note.
  opts           — keyword args:
                     :vel      velocity 0–127 (default 100)
                     :oct      octave shift applied to all notes (default 0)
                     :rate     beat-duration multiplier (default 1.0);
                               use 2.0 for half-speed, 0.5 for double-speed

  Each note is sent via core/play!; timing advances via loop-ns/sleep!
  (works correctly inside live-loops and falls back to Thread/sleep outside)."
  [pattern-kw chord-or-root & {:keys [vel oct rate]
                                :or   {vel 100 oct 0 rate 1.0}}]
  (let [pattern  (if (map? pattern-kw)
                   pattern-kw
                   (or (get-pattern pattern-kw)
                       (throw (ex-info (str "arp: unknown pattern " pattern-kw)
                                       {:pattern pattern-kw}))))
        ptype    (:type pattern :phrase)]
    (case ptype
      :chord
      (let [chord-midis (cond
                          (map? chord-or-root)    (:midi/notes chord-or-root)
                          (vector? chord-or-root) chord-or-root
                          :else                   [chord-or-root])
            order       (derive-order (:order pattern) (count chord-midis))
            rhythm      (:rhythm pattern (repeat (count order) 1))
            dur-frac    (:dur pattern 3/4)]
        (doseq [[idx beats] (map vector order rhythm)]
          (let [midi (+ (chord-note-at chord-midis idx) (* 12 oct))
                db   (* beats rate)
                gate (* db dur-frac)]
            (core/play! (cond-> {:pitch/midi midi :dur/beats gate}
                          vel (assoc :mod/velocity vel)))
            (loop-ns/sleep! db))))

      :phrase
      (let [root-midi (cond
                        (map? chord-or-root)     (+ (:root chord-or-root 60) (* 12 oct))
                        (integer? chord-or-root) (+ chord-or-root (* 12 oct))
                        :else                    (+ 60 (* 12 oct)))
            steps     (:steps pattern)
            dur-frac  (:dur pattern 3/4)]
        (doseq [step steps]
          (let [db (* (:beats step 1) rate)]
            (when-not (:rest step)
              (core/play! (cond-> {:pitch/midi (step-midi root-midi step)
                                   :dur/beats  (* db dur-frac)}
                            vel (assoc :mod/velocity vel))))
            (loop-ns/sleep! db)))))))

;; ---------------------------------------------------------------------------
;; Looping arp engine
;; ---------------------------------------------------------------------------

(defn arp-loop!
  "Start a looping arp that repeats `pattern-kw` until stopped.

  Arguments mirror `play!`. Returns a loop handle — a map with:
    :running?  — atom; true while the loop is active
    :future    — the background future

  Pass the handle to stop-arp! to cancel the loop. The handle is
  self-contained: no global registry is needed.

  The loop is phase-aware: each iteration starts after the previous one
  completes, so tempo changes (via core/set-bpm!) take effect on the next
  cycle.

  Example:
    (def my-arp (arp/arp-loop! :alberti [60 64 67] :vel 90))
    (arp/stop-arp! my-arp)"
  [pattern-kw chord-or-root & opts]
  (let [running? (atom true)
        f (future
            (try
              (loop []
                (when @running?
                  (apply play! pattern-kw chord-or-root opts)
                  (recur)))
              (catch InterruptedException _)
              (catch Exception e
                (binding [*out* *err*]
                  (println "[arp] loop error:" (.getMessage e))))))]
    {:running? running? :future f}))

(defn stop-arp!
  "Stop a running arp loop.

  `loop-handle` — the map returned by arp-loop!."
  [loop-handle]
  (when loop-handle
    (reset! (:running? loop-handle) false)
    (future-cancel (:future loop-handle))
    nil))

;; ---------------------------------------------------------------------------
;; Step-by-step engine (for integration with live-loops and Berlin ostinati)
;; ---------------------------------------------------------------------------

(defn make-arp-state
  "Create a mutable arp state atom for use with next-step!.

  `pattern-kw`     — pattern keyword or map
  `chord-or-root`  — as in play!
  opts:
    :rate   — beat-duration multiplier (default 1.0)
    :vel    — velocity (default 100)
    :oct    — octave shift (default 0)

  The returned atom holds:
    :pattern       — resolved pattern map
    :chord-midis   — resolved MIDI integers (chord mode) or nil
    :root-midi     — resolved root MIDI (phrase mode) or nil
    :step-index    — current position in the pattern
    :rate :vel :oct — as provided"
  [pattern-kw chord-or-root & {:keys [rate vel oct]
                                :or   {rate 1.0 vel 100 oct 0}}]
  (let [pattern (if (map? pattern-kw)
                  pattern-kw
                  (or (get-pattern pattern-kw)
                      (throw (ex-info (str "arp: unknown pattern " pattern-kw)
                                      {:pattern pattern-kw}))))
        ptype   (:type pattern :phrase)]
    (atom {:pattern     pattern
           :ptype       ptype
           :chord-midis (when (= ptype :chord)
                          (let [m chord-or-root]
                            (cond
                              (map? m)    (:midi/notes m)
                              (vector? m) m
                              :else       [m])))
           :root-midi   (when (= ptype :phrase)
                          (+ (if (map? chord-or-root)
                               (:root chord-or-root 60)
                               (if (integer? chord-or-root) chord-or-root 60))
                             (* 12 oct)))
           :step-index  0
           :rate        rate
           :vel         vel
           :oct         oct})))

(defn next-step!
  "Advance `arp-state` one step.

  Returns a map:
    {:pitch/midi N   — MIDI note to play (absent if this step is a rest)
     :dur/beats  D   — gate duration in beats
     :vel        V   — velocity
     :beats      B   — total step duration in beats (advance the clock by this)}

  Mutates arp-state to advance :step-index, wrapping at pattern length."
  [arp-state]
  (let [{:keys [pattern ptype chord-midis root-midi step-index rate vel]} @arp-state]
    (case ptype
      :chord
      (let [order   (derive-order (:order pattern) (count chord-midis))
            rhythm  (:rhythm pattern (repeat (count order) 1))
            dur-frac (:dur pattern 3/4)
            n       (count order)
            idx     (mod step-index n)
            midi    (chord-note-at chord-midis (nth order idx))
            beats   (* (nth (vec rhythm) idx 1) rate)
            gate    (* beats dur-frac)]
        (swap! arp-state update :step-index #(mod (inc %) n))
        {:pitch/midi midi :dur/beats gate :vel vel :beats beats})

      :phrase
      (let [steps    (get-in @arp-state [:pattern :steps])
            dur-frac (:dur (:pattern @arp-state) 3/4)
            n        (count steps)
            idx      (mod step-index n)
            step     (nth steps idx)
            beats    (* (:beats step 1) rate)]
        (swap! arp-state update :step-index #(mod (inc %) n))
        (if (:rest step)
          {:beats beats :vel vel}
          {:pitch/midi (step-midi root-midi step)
           :dur/beats  (* beats dur-frac)
           :vel        vel
           :beats      beats})))))
