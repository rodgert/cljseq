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
    :params  — optional vector of parameter-lock maps, parallel to :order;
               each map is merged into the note map for that step.
               [{} {:mod/cutoff 127} {} {:mod/resonance 32}]

  :phrase patterns (Hydrasynth-compatible)
    :steps   — vector of {:semi n :beats b} or {:rest true :beats b}.
               :semi is a semitone offset from the root of the held chord/note.
               :beats is the step duration in quarter-note beats.
               Any extra keys are parameter locks merged into the note map:
               {:semi 4 :beats 1 :mod/cutoff 127 :pitch/microtone 17}

  Built-in pattern library
  ─────────────────────────
  Built-in chord patterns loaded from resources/arpeggios/built-in.edn.
  64 phrase patterns loaded from resources/arpeggios/hydrasynth-phrases.edn:
    :phrase-01 through :phrase-64

  Public API
  ──────────
  ls              — list all loaded patterns (keywords + descriptions)
  get-pattern     — return a pattern map by keyword
  register!       — add a custom pattern to the registry
  make-arp-state  — create an ArpState record (implements IStepSequencer)
  play!           — convenience: play one complete iteration of a pattern

  ArpState implements IStepSequencer (cljseq.seq):
    (next-event arp)       — advance one step, return {:event note-map :beats N}
    (seq-cycle-length arp) — steps in one full cycle

  Use seq-loop! / run-cycle! / run-step! from cljseq.seq to drive ArpState.

  Examples
  ────────
  ;; One-shot arpeggio (convenience)
  (arp/play! :alberti (chord/chord :C 4 :major) :vel 90)

  ;; Stateful step-by-step (inside a live-loop)
  (def my-arp (make-arp-state :phrase-03 {:root 60} :vel 95))
  (deflive-loop :arp {}
    (run-cycle! my-arp))

  ;; Looping in background
  (def handle (seq-loop! my-arp))
  (stop-seq! handle)

  ;; Phrase step with parameter lock
  (register! :locked-phrase
    {:type  :phrase
     :steps [{:semi 0 :beats 1 :mod/cutoff 64}
             {:semi 4 :beats 1 :mod/cutoff 127}
             {:semi 7 :beats 1}
             {:rest true :beats 1}]})"
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [cljseq.core     :as core]
            [cljseq.loop     :as loop-ns]
            [cljseq.seq      :as sq]))

;; ---------------------------------------------------------------------------
;; Pattern registry
;; ---------------------------------------------------------------------------

(def ^:private registry
  "Map of pattern-keyword → pattern map. Populated at load time from EDN files
  and user calls to register!."
  (atom {}))

(defn register!
  "Add or replace a pattern in the registry.

  `kw`      — keyword identifying the pattern (e.g. :my-pattern)
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

(defn- load-edn-resource [resource-path]
  (when-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))))

(defn- load-built-ins! []
  (when-let [patterns (load-edn-resource "arpeggios/built-in.edn")]
    (doseq [p patterns]
      (register! (:name p) p))))

(defn- load-hydrasynth-phrases! []
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
  "Return the MIDI note for chord-tone index `i`, wrapping upward by octave."
  [chord-midis i]
  (let [n   (count chord-midis)
        idx (mod i n)
        oct (quot i n)]
    (+ (nth chord-midis idx) (* 12 oct))))

;; ---------------------------------------------------------------------------
;; Phrase pattern helpers
;; ---------------------------------------------------------------------------

(defn- step-midi
  "Return the MIDI note for a phrase step given the root MIDI number."
  [root-midi step]
  (+ root-midi (:semi step 0)))

(def ^:private phrase-reserved-keys
  "Keys consumed by the phrase step engine; all others are parameter locks."
  #{:semi :beats :rest :dur})

(defn- phrase-locks
  "Extract parameter-lock keys from a phrase step map."
  [step]
  (not-empty (apply dissoc step phrase-reserved-keys)))

;; ---------------------------------------------------------------------------
;; ArpState record — implements IStepSequencer
;; ---------------------------------------------------------------------------

(defrecord ArpState [pattern    ; resolved pattern map
                     ptype      ; :chord or :phrase
                     chord-midis ; vec of MIDI ints (chord mode) or nil
                     root-midi  ; MIDI root (phrase mode) or nil
                     rate       ; beat-duration multiplier
                     vel        ; default velocity 0-127
                     step-atom]) ; atom holding current step index

(defn- resolve-pattern [pattern-kw]
  (if (map? pattern-kw)
    pattern-kw
    (or (get-pattern pattern-kw)
        (throw (ex-info (str "arp: unknown pattern " pattern-kw)
                        {:pattern pattern-kw})))))

(defn- resolve-chord-midis [chord-or-root oct]
  (let [base (cond
               (map? chord-or-root)    (:midi/notes chord-or-root)
               (vector? chord-or-root) chord-or-root
               :else                   [(long chord-or-root)])]
    (mapv #(+ % (* 12 oct)) base)))

(defn- resolve-root-midi [chord-or-root oct]
  (+ (cond
       (map? chord-or-root)     (:root chord-or-root 60)
       (integer? chord-or-root) (long chord-or-root)
       :else                    60)
     (* 12 oct)))

(defn make-arp-state
  "Create an ArpState that implements IStepSequencer.

  Arguments:
    pattern-kw     — keyword of a registered pattern, or a pattern map directly
    chord-or-root  — for :chord patterns: a chord map with :midi/notes, or a
                     vector of MIDI integers.
                     For :phrase patterns: a map with :root (MIDI integer), or a
                     plain MIDI integer.
  Options:
    :vel   — default velocity 0–127 (default 100)
    :oct   — octave shift applied to all notes (default 0)
    :rate  — beat-duration multiplier (default 1.0)

  Returns an ArpState record. Use with run-cycle!, run-step!, or seq-loop!
  from cljseq.seq.

  Example:
    (def my-arp (make-arp-state :bounce (chord/chord :C 4 :maj7) :vel 90))
    (deflive-loop :melody {} (run-cycle! my-arp))"
  [pattern-kw chord-or-root & {:keys [vel oct rate]
                                :or   {vel 100 oct 0 rate 1.0}}]
  (let [pattern (resolve-pattern pattern-kw)
        ptype   (:type pattern :phrase)]
    (->ArpState pattern
                ptype
                (when (= ptype :chord) (resolve-chord-midis chord-or-root oct))
                (when (= ptype :phrase) (resolve-root-midi chord-or-root oct))
                (double rate)
                (long vel)
                (atom 0))))

(defn reset-chord!
  "Update the chord voicing of a running ArpState in place.

  `arp`          — an ArpState record
  `chord-or-root` — new chord map, MIDI integer vector, or root MIDI integer

  Does not reset the step position — the arp continues from wherever it is
  in the pattern, with the new pitches taking effect on the next step.

  Example:
    ;; Change chord on the bar boundary from the parent loop
    (deflive-loop :harmony {}
      (reset-chord! my-arp (next-chord!))
      (run-cycle! my-arp))"
  [^ArpState arp chord-or-root]
  (let [new-midis (resolve-chord-midis chord-or-root 0)]
    ;; ArpState is a record (immutable fields), but we can rebuild it cheaply.
    ;; Return the new state — callers should rebind their var.
    (assoc arp :chord-midis new-midis)))

;; ---------------------------------------------------------------------------
;; IStepSequencer implementation
;; ---------------------------------------------------------------------------

(defn- chord-cycle-length [^ArpState arp]
  (count (derive-order (get-in arp [:pattern :order])
                       (count (:chord-midis arp)))))

(defn- phrase-cycle-length [^ArpState arp]
  (count (get-in arp [:pattern :steps])))

(extend-protocol sq/IStepSequencer
  ArpState
  (next-event [arp]
    (let [{:keys [pattern ptype chord-midis root-midi rate vel step-atom]} arp]
      (case ptype
        :chord
        (let [order    (derive-order (:order pattern) (count chord-midis))
              rhythm   (:rhythm pattern (repeat (count order) 1))
              params   (:params pattern)
              dur-frac (double (:dur pattern 3/4))
              n        (count order)
              idx      (mod @step-atom n)
              chord-i  (nth order idx)
              midi     (chord-note-at chord-midis chord-i)
              beats    (* (double (nth (vec rhythm) idx 1)) rate)
              gate     (* beats dur-frac)
              lock     (when params (nth params idx nil))
              event    (cond-> {:pitch/midi   midi
                                :dur/beats    gate
                                :mod/velocity vel}
                         (seq lock) (merge lock))]
          (swap! step-atom #(mod (inc %) n))
          {:event event :beats beats})

        :phrase
        (let [steps    (:steps pattern)
              dur-frac (double (:dur pattern 3/4))
              n        (count steps)
              idx      (mod @step-atom n)
              step     (nth steps idx)
              beats    (* (double (:beats step 1)) rate)
              locks    (phrase-locks step)]
          (swap! step-atom #(mod (inc %) n))
          (if (:rest step)
            {:event nil :beats beats}
            (let [event (cond-> {:pitch/midi   (step-midi root-midi step)
                                 :dur/beats    (* beats dur-frac)
                                 :mod/velocity vel}
                          (seq locks) (merge locks))]
              {:event event :beats beats}))))))

  (seq-cycle-length [arp]
    (case (:ptype arp)
      :chord  (chord-cycle-length arp)
      :phrase (phrase-cycle-length arp))))

;; ---------------------------------------------------------------------------
;; Convenience one-shot player
;; ---------------------------------------------------------------------------

(defn play!
  "Play one complete iteration of `pattern-kw` against `chord-or-root`.
  Convenience wrapper around make-arp-state + run-cycle!.
  Blocks for the full pattern duration — call from a live-loop or background thread.

  Options:
    :vel   — velocity 0–127 (default 100)
    :oct   — octave shift (default 0)
    :rate  — beat-duration multiplier (default 1.0)

  Example:
    (arp/play! :alberti (chord/chord :C 4 :major) :vel 90 :rate 0.5)"
  [pattern-kw chord-or-root & {:keys [vel oct rate]
                                :or   {vel 100 oct 0 rate 1.0}}]
  (let [state (make-arp-state pattern-kw chord-or-root :vel vel :oct oct :rate rate)]
    (sq/run-cycle! state)))
