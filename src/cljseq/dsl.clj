; SPDX-License-Identifier: EPL-2.0
(ns cljseq.dsl
  "cljseq DSL sugar layer — concise interactive forms for live performance.

  All forms expand to the core protocol-level API (cljseq.core).
  Designed for live-coding ergonomics at the nREPL.

  ## Note playback
    (play! :C4)                          ; uses *default-dur*
    (play! :C4 1/2)                      ; explicit duration
    (play! 60)                           ; MIDI integer
    (phrase! [:C4 :E4 :G4] :dur 1/4)    ; melodic phrase

  ## Structural / conditional
    (every 4 :beats (play! :C4))         ; fires every 4 beats
    (one-in 4 (play! :C4))               ; fires with probability 1/4

  ## Cycling sequences
    (def r (ring :C4 :E4 :G4))
    (tick! r)                            ; returns next element, advances ring

  ## Synth context
    (use-synth! :piano)
    (with-synth :piano (play! :C4))

  Key design decisions: §27 (DSL Sugar Layer), R&R usability corpus."
  (:require [cljseq.core :as core]))

;; ---------------------------------------------------------------------------
;; Dynamic configuration vars (Configuration Registry, R&R §25)
;; ---------------------------------------------------------------------------

(def ^:dynamic *default-dur*
  "Default note duration in beats. Override with binding or set-default-dur!.
  Registered in Configuration Registry (R&R §25)."
  1/4)

(def ^:dynamic *default-synth*
  "Default synth/target for play! calls. Set with use-synth! or with-synth."
  nil)

;; ---------------------------------------------------------------------------
;; play! — multi-arity shorthand (delegates to cljseq.core/play!)
;; ---------------------------------------------------------------------------

(defn play!
  "Play a note. Concise shorthand wrapping cljseq.core/play!.

  Accepts:
    (play! :C4)          ; keyword note, *default-dur*
    (play! :C4 1/2)      ; keyword + explicit duration
    (play! 60)           ; MIDI integer, *default-dur*
    (play! 60 1/4)       ; MIDI integer + explicit duration
    (play! {:pitch/midi 60 :dur/beats 1/2})  ; step map (passthrough)"
  ([note]
   (core/play! note *default-dur*))
  ([note dur]
   (core/play! note dur)))

;; ---------------------------------------------------------------------------
;; phrase! — play a sequence of notes
;; ---------------------------------------------------------------------------

(defmacro phrase!
  "Play a sequence of notes.

  Forms:
    (phrase! [:C4 :E4 :G4] :dur 1/4)       ; all notes same duration
    (phrase! [:C4 1/4 :E4 1/8 :G4 1/4])    ; alternating pitch/duration pairs

  Notes play sequentially; each sleeps for its own duration."
  [notes & {:keys [dur] :or {dur nil}}]
  (let [d (or dur `*default-dur*)]
    ;; Detect paired form: [:C4 1/4 :E4 1/8 ...]
    ;; If all even-indexed elements are keywords and odd are numbers, treat as pairs.
    ;; For the macro, we expand to sequential play!+sleep! forms at runtime.
    `(let [notes#  ~notes
           paired# (and (even? (count notes#))
                        (every? keyword? (take-nth 2 notes#)))]
       (if paired#
         (doseq [[n# d#] (partition 2 notes#)]
           (core/play! n# d#)
           (core/sleep! d#))
         (doseq [n# notes#]
           (core/play! n# ~d)
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
;; play-chord! — absolute chord playback (stub; Phase 1)
;; ---------------------------------------------------------------------------

(defn play-chord!
  "Play all notes of a chord simultaneously.
  Phase 0 stub — prints each note.

  Usage: (play-chord! :C4 :major)"
  [root quality]
  ;; Chord intervals: root + offsets; Phase 0: stdout only
  (let [intervals (case quality
                    :major [0 4 7]
                    :minor [0 3 7]
                    :dim   [0 3 6]
                    :aug   [0 4 8]
                    [0 4 7])
        root-midi (core/keyword->midi root)]
    (doseq [offset intervals]
      (core/play! (+ root-midi offset) *default-dur*))))

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
  Direction: :up (default), :down, :up-down."
  [root quality direction step-dur]
  (let [intervals (get {:major [0 4 7] :minor [0 3 7]} quality [0 4 7])
        root-midi (core/keyword->midi root)
        notes     (map #(+ root-midi %) intervals)
        ordered   (case direction
                    :down    (reverse notes)
                    :up-down (concat notes (rest (reverse notes)))
                    notes)]
    (doseq [n ordered]
      (core/play! n step-dur)
      (core/sleep! step-dur))))

;; ---------------------------------------------------------------------------
;; use-synth! / with-synth — synth routing context (stubs; Phase 1)
;; ---------------------------------------------------------------------------

(defn use-synth!
  "Set the default synth for subsequent play! calls.
  Phase 0 stub — stores in *default-synth*."
  [synth]
  ;; Full implementation in Phase 1 when sidecar routing is available.
  (alter-var-root #'*default-synth* (constantly synth))
  nil)

(defmacro with-synth
  "Execute `body` with the given synth as the default.

  Usage: (with-synth :piano (play! :C4) (play! :E4))"
  [synth & body]
  `(binding [*default-synth* ~synth]
     ~@body))
