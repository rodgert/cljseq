; SPDX-License-Identifier: EPL-2.0
(ns cljseq.dsl
  "cljseq DSL sugar layer — concise interactive forms for live performance.

  All forms expand to the core protocol-level API (cljseq.core).
  Designed for live-coding ergonomics at the nREPL.

  ## Synth context
    Synth context is a plain map describing the MIDI routing for a scope:
      {:midi/channel 2 :mod/velocity 80}

    Three entry points, in increasing locality:
      (use-synth! ctx)            ; REPL default (root binding, not thread-safe)
      (with-synth ctx (play! …))  ; explicit scope
      (deflive-loop :foo {:synth ctx} …)  ; per-loop (in cljseq.loop opts)

    Priority (lowest → highest): use-synth! < with-synth < per-note step map keys.
    Bare (play! :C4) with no context uses channel 1, velocity 64.

  ## Note playback
    (play! :C4)                          ; uses *default-dur*
    (play! :C4 1/2)                      ; explicit duration
    (play! 60)                           ; MIDI integer
    (play! {:pitch/midi 60 :dur/beats 1/2})  ; step map
    (phrase! [:C4 :E4 :G4] :dur 1/4)    ; melodic phrase

  ## Structural / conditional
    (every 4 :beats (play! :C4))         ; fires every 4 beats
    (one-in 4 (play! :C4))               ; fires with probability 1/4

  ## Cycling sequences
    (def r (ring :C4 :E4 :G4))
    (tick! r)                            ; returns next element, advances ring

  Key design decisions: §27 (DSL Sugar Layer), R&R usability corpus."
  (:require [cljseq.core :as core]
            [cljseq.loop :as loop-ns]
            [cljseq.mod  :as mod-ns]))

;; ---------------------------------------------------------------------------
;; Dynamic configuration vars (Configuration Registry, R&R §25)
;; ---------------------------------------------------------------------------

(def ^:dynamic *default-dur*
  "Default note duration in beats. Override with binding or set-default-dur!.
  Registered in Configuration Registry (R&R §25)."
  1/4)

;; ---------------------------------------------------------------------------
;; Synth context management
;; *synth-ctx* is defined in cljseq.loop to avoid circular dependencies.
;; dsl provides the user-facing API for setting and scoping it.
;; ---------------------------------------------------------------------------

(defn use-synth!
  "Set the default synth context for subsequent play! calls at the REPL.

  `ctx` should be a map with at minimum :midi/channel (1–16) and optionally
  :mod/velocity (0–127), or nil to clear.

  Example:
    (def bass {:midi/channel 2 :mod/velocity 80})
    (use-synth! bass)
    (use-synth! nil)   ; clear

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, use the :synth key in deflive-loop opts instead."
  [ctx]
  (alter-var-root #'loop-ns/*synth-ctx* (constantly ctx))
  nil)

(defmacro with-synth
  "Execute `body` with `ctx` as the active synth context.

  `ctx` is a map with :midi/channel and optionally :mod/velocity, or nil.

  Usage:
    (def bass {:midi/channel 2 :mod/velocity 80})
    (with-synth bass
      (play! :C3)
      (phrase! [:C3 :E3 :G3] :dur 1/4))"
  [ctx & body]
  `(binding [loop-ns/*synth-ctx* ~ctx]
     ~@body))

;; ---------------------------------------------------------------------------
;; Timing context management
;; *timing-ctx* is defined in cljseq.loop; dsl provides the user-facing API.
;; ---------------------------------------------------------------------------

(defn use-timing!
  "Set the default timing modulator for subsequent play! calls at the REPL.

  `mod` should be a timing modulator (from cljseq.timing), or nil to clear.

  Example:
    (use-timing! (timing/swing :amount 0.6))
    (use-timing! nil)   ; clear

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, use the :timing key in deflive-loop opts instead."
  [mod]
  (alter-var-root #'loop-ns/*timing-ctx* (constantly mod))
  nil)

(defmacro with-timing
  "Execute `body` with `mod` as the active timing modulator.

  Usage:
    (with-timing (timing/swing :amount 0.55)
      (phrase! [:C4 :E4 :G4] :dur 1/4))"
  [mod & body]
  `(binding [loop-ns/*timing-ctx* ~mod]
     ~@body))

;; ---------------------------------------------------------------------------
;; play! — normalises all note forms to a step map, merges synth context
;; ---------------------------------------------------------------------------

(defn play!
  "Play a note. Routes to the sidecar for MIDI output when connected;
  falls back to stdout when running without a sidecar (Phase 0 mode).

  Accepts:
    (play! :C4)          ; keyword note, *default-dur*
    (play! :C4 1/2)      ; keyword + explicit duration
    (play! 60)           ; MIDI integer, *default-dur*
    (play! 60 1/4)       ; MIDI integer + explicit duration
    (play! {:pitch/midi 60 :dur/beats 1/2})  ; step map (passthrough)

  Synth context (*synth-ctx*) provides :midi/channel and :mod/velocity
  defaults. Per-note step map keys override context; context overrides the
  channel-1/velocity-64 fallback in cljseq.core/play!."
  ([note]
   (play! note nil))
  ([note dur]
   (let [ctx  loop-ns/*synth-ctx*
         step (cond
                (map? note)     note
                (keyword? note) {:pitch/midi (core/keyword->midi note)}
                (integer? note) {:pitch/midi (long note)}
                :else (throw (ex-info "play!: unrecognised note form" {:note note})))
         ;; Context is lowest priority; per-note step map keys override it.
         step (if ctx
                (merge (select-keys ctx [:midi/channel :mod/velocity]) step)
                step)
         d    (or (:dur/beats step) dur *default-dur*)]
     (core/play! (assoc step :dur/beats d)))))

;; ---------------------------------------------------------------------------
;; phrase! — play a sequence of notes
;; ---------------------------------------------------------------------------

(defmacro phrase!
  "Play a sequence of notes.

  Forms:
    (phrase! [:C4 :E4 :G4] :dur 1/4)       ; all notes same duration
    (phrase! [:C4 1/4 :E4 1/8 :G4 1/4])    ; alternating pitch/duration pairs

  Notes play sequentially; each sleeps for its own duration.
  Inherits the active synth context (*synth-ctx*)."
  [notes & {:keys [dur] :or {dur nil}}]
  (let [d (or dur `*default-dur*)]
    `(let [notes#  ~notes
           paired# (and (even? (count notes#))
                        (every? keyword? (take-nth 2 notes#)))]
       (if paired#
         (doseq [[n# d#] (partition 2 notes#)]
           (play! n# d#)
           (core/sleep! d#))
         (doseq [n# notes#]
           (play! n# ~d)
           (core/sleep! ~d))))))

;; ---------------------------------------------------------------------------
;; every — conditional firing on beat multiples
;; ---------------------------------------------------------------------------

(defmacro every
  "Execute `body` every `n` beats within a live loop.

  Usage: (every 4 :beats (play! :C4))

  Internally compares the current beat position (mod n) against zero.
  The :beats unit keyword is accepted but ignored (beats are implicit)."
  [n _unit & body]
  `(when (zero? (mod (long (core/now)) ~n))
     ~@body))

;; ---------------------------------------------------------------------------
;; one-in — probabilistic firing
;; ---------------------------------------------------------------------------

(defmacro one-in
  "Execute `body` with probability 1/n.

  Usage: (one-in 4 (play! :C4))  ; fires ~25% of the time"
  [n & body]
  `(when (zero? (rand-int ~n))
     ~@body))

;; ---------------------------------------------------------------------------
;; ring — stateful cycling sequence
;; ---------------------------------------------------------------------------

(defn ring
  "Create a cycling sequence (ring buffer).

  Usage:
    (def r (ring :C4 :E4 :G4))
    (tick! r)  ; => :C4, then :E4, then :G4, then :C4, ...

  Returns an atom holding {:elements [...] :pos 0}."
  [& elements]
  (atom {:elements (vec elements) :pos 0}))

(defn tick!
  "Advance a ring and return the current element.

  Usage: (tick! r)  ; returns current element, advances position"
  [ring-atom]
  (let [{:keys [elements pos]} @ring-atom
        current (elements pos)]
    (swap! ring-atom update :pos #(mod (inc %) (count elements)))
    current))

;; ---------------------------------------------------------------------------
;; play-chord! — absolute chord playback
;; ---------------------------------------------------------------------------

(defn play-chord!
  "Play all notes of a chord simultaneously.

  Usage: (play-chord! :C4 :major)

  Inherits the active synth context (*synth-ctx*)."
  [root quality]
  (let [intervals (case quality
                    :major [0 4 7]
                    :minor [0 3 7]
                    :dim   [0 3 6]
                    :aug   [0 4 8]
                    [0 4 7])
        root-midi (core/keyword->midi root)]
    (doseq [offset intervals]
      (play! (+ root-midi offset) *default-dur*))))

;; ---------------------------------------------------------------------------
;; choose-from-scale — random scale degree
;; ---------------------------------------------------------------------------

(def ^:private scale-intervals
  {:major [0 2 4 5 7 9 11]
   :minor [0 2 3 5 7 8 10]
   :dorian [0 2 3 5 7 9 10]
   :pentatonic [0 2 4 7 9]})

(defn choose-from-scale
  "Return a random MIDI note from the given key and scale.

  Usage: (choose-from-scale :C :major)  ; => random MIDI note in C major"
  [key scale & {:keys [octave] :or {octave 4}}]
  (let [root     (core/keyword->midi (keyword (str (name key) octave)))
        offsets  (get scale-intervals scale [0 2 4 5 7 9 11])]
    (+ root (rand-nth offsets))))

;; ---------------------------------------------------------------------------
;; arp! — arpeggiate a chord
;; ---------------------------------------------------------------------------

(defn arp!
  "Arpeggiate a chord.

  Usage: (arp! :C4 :major :up 1/16)

  Plays all chord tones in sequence, each separated by `step-dur` beats.
  Direction: :up (default), :down, :up-down.
  Inherits the active synth context (*synth-ctx*)."
  [root quality direction step-dur]
  (let [intervals (get {:major [0 4 7] :minor [0 3 7]} quality [0 4 7])
        root-midi (core/keyword->midi root)
        notes     (map #(+ root-midi %) intervals)
        ordered   (case direction
                    :down    (reverse notes)
                    :up-down (concat notes (rest (reverse notes)))
                    notes)]
    (doseq [n ordered]
      (play! n step-dur)
      (core/sleep! step-dur))))
