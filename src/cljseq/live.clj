; SPDX-License-Identifier: EPL-2.0
(ns cljseq.live
  "cljseq.live — execution context and live-coding sugar for live performance.

  Manages the dynamic execution environment that governs how notes are routed,
  tuned, and modulated. All playback forms merge the active context before
  dispatching to cljseq.core.

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

  ## Default duration
    (set-default-dur! 1/8)              ; global default
    (with-dur 1/16 (play! :C4))         ; scoped override

  ## Structural / conditional
    (every 4 :beats (play! :C4))         ; fires every 4 beats
    (one-in 4 (play! :C4))               ; fires with probability 1/4

  ## Cycling sequences
    (def r (ring :C4 :E4 :G4))
    (tick! r)                            ; returns next element, advances ring
    (ring-reset! r)                      ; reset to first element
    (ring-len r)                         ; => 3
    (defring my-ring :C4 :E4 :G4)       ; named ring, registered in ctrl tree

  ## Mod routing context
    (use-mod! {[:filter/cutoff] my-lfo}) ; REPL default
    (with-mod {[:filter/cutoff] my-lfo} …) ; scoped
    (tick-mods!)                         ; sample all *mod-ctx* mods → ctrl/send!"
  (:require [cljseq.chord  :as chord-ns]
            [cljseq.clock  :as clock]
            [cljseq.core  :as core]
            [cljseq.ctrl  :as ctrl]
            [cljseq.loop  :as loop-ns]
            [cljseq.mod   :as mod-ns]
            [cljseq.pitch :as pitch]
            [cljseq.scala :as scala]
            [cljseq.scale :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Dynamic configuration vars (Configuration Registry, R&R §25)
;; ---------------------------------------------------------------------------

(def ^:dynamic *default-dur*
  "Default note duration in beats. Override with set-default-dur! or with-dur.
  Registered in Configuration Registry (R&R §25)."
  1/4)

(defn set-default-dur!
  "Set the default note duration globally (root binding).

  `dur` — duration in beats (rational or double).

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, pass :dur to phrase! or use with-dur.

  Example:
    (set-default-dur! 1/8)   ; all play!/phrase! use 1/8 until changed"
  [dur]
  (alter-var-root #'*default-dur* (constantly dur))
  nil)

(defmacro with-dur
  "Execute `body` with `dur` as the active default note duration.

  Example:
    (with-dur 1/8
      (play! :C4)
      (play! :E4))"
  [dur & body]
  `(binding [*default-dur* ~dur]
     ~@body))

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
;; Mod context management
;; *mod-ctx* is defined in cljseq.loop; dsl provides the user-facing API.
;; ---------------------------------------------------------------------------

(defn use-mod!
  "Set the default mod routing context for subsequent tick-mods! calls at the REPL.

  `ctx` should be a map from ctrl-path (vector) to ITemporalValue (LFO/envelope),
  or nil to clear.

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, use the :mod key in deflive-loop opts instead.

  Example:
    (use-mod! {[:filter/cutoff] (mod/lfo (->Phasor 1/4 0) phasor/sine-uni)})
    (use-mod! nil)   ; clear"
  [ctx]
  (alter-var-root #'loop-ns/*mod-ctx* (constantly ctx))
  nil)

(defmacro with-mod
  "Execute `body` with `ctx` as the active mod routing context.

  `ctx` is a map from ctrl-path (vector) to ITemporalValue, or nil.

  Example:
    (with-mod {[:filter/cutoff] my-lfo}
      (phrase! [:C4 :E4 :G4] :dur 1/4))"
  [ctx & body]
  `(binding [loop-ns/*mod-ctx* ~ctx]
     ~@body))

;; ---------------------------------------------------------------------------
;; Per-step mod context management (§24.6)
;; *step-mod-ctx* is defined in cljseq.loop; dsl provides the user-facing API.
;; ---------------------------------------------------------------------------

(defn use-step-mods!
  "Set the default per-step mod context for subsequent play! calls at the REPL.

  `ctx` should be a map from step-key keyword to ITemporalValue or constant number,
  or nil to clear.

  At each play! call, each entry is sampled at *virtual-time* and merged into
  the step map with the highest priority (overrides both synth context and
  explicit step map values).

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, use the :step-mods key in deflive-loop opts instead.

  Example:
    (use-step-mods! {:mod/velocity (mod/lfo (->Phasor 1/4 0) phasor/triangle)
                     :gate/len     0.8})
    (use-step-mods! nil)   ; clear"
  [ctx]
  (alter-var-root #'loop-ns/*step-mod-ctx* (constantly ctx))
  nil)

(defmacro with-step-mods
  "Execute `body` with `ctx` as the active per-step mod context.

  `ctx` is a map from step-key keyword to ITemporalValue or constant number, or nil.

  Example:
    (with-step-mods {:mod/velocity vel-lfo}
      (play! :C4)
      (phrase! [:C4 :E4 :G4] :dur 1/4))"
  [ctx & body]
  `(binding [loop-ns/*step-mod-ctx* ~ctx]
     ~@body))

(defn apply-step-mods
  "Apply *step-mod-ctx* to a step map, returning a modified step map.

  Each entry in *step-mod-ctx* is sampled at *virtual-time* if it satisfies
  ITemporalValue; otherwise the value is used directly.  The result is merged
  into the step map with highest priority — step-mod values override both the
  synth context and any explicit per-note keys.

  Returns `step` unchanged when *step-mod-ctx* is nil.

  Intended for use outside play! when you need the modified step for other
  purposes (e.g. passing to a fractal context or stochastic router).

  Example:
    (let [step (fractal/next-step! melody)
          step (live/apply-step-mods step)]
      (play! step))"
  [step]
  (if-let [ctx loop-ns/*step-mod-ctx*]
    (let [beat (double loop-ns/*virtual-time*)]
      (reduce-kv (fn [s k mod]
                   (assoc s k
                          (if (satisfies? clock/ITemporalValue mod)
                            (clock/sample mod beat)
                            mod)))
                 step
                 ctx))
    step))

;; ---------------------------------------------------------------------------
;; Harmony context management (§4.3)
;; *harmony-ctx* is defined in cljseq.loop; dsl provides the user-facing API.
;;
;; *harmony-ctx* may hold either:
;;   - a cljseq.scale/Scale record  (classic per-loop binding)
;;   - an ImprovisationContext map   (from cljseq.ensemble/start-harmony-ear!)
;;     which carries :harmony/key (Scale), :harmony/tension, :harmony/chord etc.
;;
;; ->harmony-scale extracts the Scale from either form so that dsl consumers
;; remain backward-compatible without knowing which shape is active.
;; ---------------------------------------------------------------------------

(defn- ->harmony-scale
  "Extract a Scale record from *harmony-ctx*, which may be a Scale record
  directly or an ImprovisationContext map with a :harmony/key entry.

  Clojure records are maps, so we check the concrete type first."
  [ctx]
  (cond
    (nil? ctx)                           nil
    (instance? cljseq.scale.Scale ctx)   ctx              ; Scale record — classic path
    (map? ctx)                           (:harmony/key ctx) ; ImprovisationContext — ensemble path
    :else                                nil))

(defn use-harmony!
  "Set the default harmonic context for the REPL session.

  `s` may be a cljseq.scale/Scale record, an ImprovisationContext map
  (from cljseq.ensemble/analyze-buffer), or nil to clear.

  Example:
    (use-harmony! (scale/scale :C 4 :major))
    (use-harmony! nil)   ; clear

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, use the :harmony key in deflive-loop opts instead."
  [s]
  (alter-var-root #'loop-ns/*harmony-ctx* (constantly s))
  nil)

(defmacro with-harmony
  "Execute `body` with `s` as the active harmonic context.

  `s` — a cljseq.scale/Scale record (from cljseq.scale/scale) or an
  ImprovisationContext map (from cljseq.ensemble/analyze-buffer).

  Example:
    (with-harmony (scale/scale :C 4 :major)
      (play! (root))
      (play! (scale-degree 4)))"
  [s & body]
  `(binding [loop-ns/*harmony-ctx* ~s]
     ~@body))

(defn root
  "Return the root Pitch of the current harmonic context (*harmony-ctx*).
  Throws if no harmonic context is active."
  []
  (if-let [s (->harmony-scale loop-ns/*harmony-ctx*)]
    (:root s)
    (throw (ex-info "root: no *harmony-ctx* active" {}))))

(defn fifth
  "Return the fifth-degree Pitch of the current harmonic context.
  Throws if no harmonic context is active."
  []
  (if-let [s (->harmony-scale loop-ns/*harmony-ctx*)]
    (scale-ns/pitch-at s 4)
    (throw (ex-info "fifth: no *harmony-ctx* active" {}))))

(defn scale-degree
  "Return the Pitch at 0-based scale degree `n` in the current harmonic context.
  Negative degrees and degrees beyond the octave are supported.
  Throws if no harmonic context is active."
  [n]
  (if-let [s (->harmony-scale loop-ns/*harmony-ctx*)]
    (scale-ns/pitch-at s n)
    (throw (ex-info "scale-degree: no *harmony-ctx* active" {}))))

(defn in-key?
  "Return true if pitch `p` (Pitch, keyword, or MIDI int) is in the current
  harmonic context. Throws if no harmonic context is active."
  [p]
  (if-let [s (->harmony-scale loop-ns/*harmony-ctx*)]
    (scale-ns/in-scale? s (pitch/->pitch p))
    (throw (ex-info "in-key?: no *harmony-ctx* active" {}))))

;; ---------------------------------------------------------------------------
;; Chord context management (§5.1)
;; *chord-ctx* is defined in cljseq.loop; dsl provides the user-facing API.
;; ---------------------------------------------------------------------------

(defn use-chord!
  "Set the default chord context for the REPL session.

  `c` should be a cljseq.chord/Chord record (e.g. from chord/chord), or nil
  to clear.

  Used by cljseq.pattern/motif! to resolve chord-relative pattern indices.

  Example:
    (use-chord! (chord-ns/chord :C 4 :maj7))
    (use-chord! nil)   ; clear

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, use the :chord key in deflive-loop opts instead."
  [c]
  (alter-var-root #'loop-ns/*chord-ctx* (constantly c))
  nil)

(defmacro with-chord
  "Execute `body` with `c` as the active chord context.

  `c` — a cljseq.chord/Chord record (from cljseq.chord/chord).
  Used by cljseq.pattern/motif! for chord-relative (:chord) patterns.

  Example:
    (with-chord (chord-ns/chord :G 3 :dom7)
      (motif! (pattern [:chord 1 2 3 4]) (rhythm [100 nil 80 nil]) {}))"
  [c & body]
  `(binding [loop-ns/*chord-ctx* ~c]
     ~@body))

(defn tick-mods!
  "Sample all modulators in *mod-ctx* at the current virtual time and route
  each sampled value to ctrl/send!.

  Intended to be called once per loop iteration, typically at the top of a
  deflive-loop body. Each entry in *mod-ctx* is {path -> ITemporalValue};
  tick-mods! calls (ctrl/send! path (clock/sample mod beat)) for each.

  No-op if *mod-ctx* is nil.

  Example:
    (deflive-loop :bass {:mod {[:filter/cutoff] my-lfo}}
      (tick-mods!)
      (play! :C2)
      (sleep! 1/4))"
  []
  (when-let [ctx loop-ns/*mod-ctx*]
    (let [beat (double loop-ns/*virtual-time*)]
      (doseq [[path mod] ctx]
        (ctrl/send! path (clock/sample mod beat))))))

;; ---------------------------------------------------------------------------
;; Tuning context management (§ microtonal)
;; *tuning-ctx* is defined in cljseq.loop; dsl provides the user-facing API.
;; ---------------------------------------------------------------------------

;; Identity KBM: map-size 0 means midi->note uses (degree->note ms middle-note
;; (- midi middle-note)), mapping the entire MIDI range with C4(60) as origin.
(def ^:private identity-kbm
  (scala/->KeyboardMap 0 0 127 60 69 440.0 12 []))

(defn- apply-tuning
  "Apply *tuning-ctx* to a step map.

  Looks up :pitch/midi in the active KBM+scale and replaces it with the
  retuned MIDI note, merging :pitch/bend-cents when the tuning deviates from
  12-TET. Uses an identity KBM when :kbm is absent.

  Returns `step` unchanged when:
    - *tuning-ctx* is nil
    - :pitch/midi is absent from the step map
    - the MIDI note is out of the KBM range or maps to an unmapped (:x) key"
  [step]
  (if-let [{:keys [kbm scale]} loop-ns/*tuning-ctx*]
    (if-let [midi (:pitch/midi step)]
      (let [effective-kbm (or kbm identity-kbm)
            result        (scala/midi->step effective-kbm scale (int midi))]
        (if result
          (merge step result)
          step))
      step)
    step))

(defn use-tuning!
  "Set the default microtonal tuning context for subsequent play! calls at the REPL.

  `ctx` should be a map with :scale (MicrotonalScale) and optionally :kbm
  (KeyboardMap), or nil to disable retuning.

  Example:
    (use-tuning! {:scale (scala/load-scl \"31edo.scl\")})
    (use-tuning! {:kbm   (scala/load-kbm \"whitekeys.kbm\")
                  :scale (scala/load-scl \"pythagorean.scl\")})
    (use-tuning! nil)   ; back to 12-TET

  Not thread-safe — intended for interactive REPL use only.
  Inside live loops, use the :tuning key in deflive-loop opts instead."
  [ctx]
  (alter-var-root #'loop-ns/*tuning-ctx* (constantly ctx))
  nil)

(defmacro with-tuning
  "Execute `body` with `ctx` as the active microtonal tuning context.

  `ctx` is a map with :scale (MicrotonalScale) and optionally :kbm (KeyboardMap),
  or nil to disable retuning.

  Example:
    (def ms (scala/load-scl \"31edo.scl\"))
    (with-tuning {:scale ms}
      (play! 60)   ; retuned to 31-EDO degree 0
      (play! 62)   ; retuned to 31-EDO degree 2)"
  [ctx & body]
  `(binding [loop-ns/*tuning-ctx* ~ctx]
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
    (play! (root))       ; Pitch record from *harmony-ctx*

  Synth context (*synth-ctx*) provides :midi/channel and :mod/velocity
  defaults. Per-note step map keys override context; context overrides the
  channel-1/velocity-64 fallback in cljseq.core/play!."
  ([note]
   (play! note nil))
  ([note dur]
   (let [ctx  loop-ns/*synth-ctx*
         step (cond
                ;; Pitch record check must precede map? since defrecords are maps.
               ;; Thread :microtone through as :pitch/bend-cents so play! sends pitch bend.
                (instance? cljseq.pitch.Pitch note)
                (let [m  (pitch/pitch->midi note)
                      mt (double (:microtone note 0))]
                  (cond-> {:pitch/midi m}
                    (not (zero? mt)) (assoc :pitch/bend-cents mt)))
                (map? note)     note
                (keyword? note) {:pitch/midi (core/keyword->midi note)}
                (integer? note) {:pitch/midi (long note)}
                :else (throw (ex-info "play!: unrecognised note form" {:note note})))
         ;; Context is lowest priority; per-note step map keys override it.
         step (if ctx
                (merge (select-keys ctx [:midi/channel :mod/velocity]) step)
                step)
         ;; Step-mods are highest priority — sample at *virtual-time* and merge.
         step (apply-step-mods step)
         ;; Tuning: retune :pitch/midi through *tuning-ctx* KBM+scale if active.
         step (apply-tuning step)
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

(defn ring-reset!
  "Reset a ring to its first element.

  Usage: (ring-reset! r)  ; next (tick! r) returns first element"
  [ring-atom]
  (swap! ring-atom assoc :pos 0)
  nil)

(defn ring-len
  "Return the number of elements in a ring.

  Usage: (ring-len r)  ; => 4 for (ring :C4 :E4 :G4 :B4)"
  [ring-atom]
  (count (:elements @ring-atom)))

(defmacro defring
  "Create a named cycling sequence and register it in the ctrl tree.

  Defines a var with the given name and registers it as a :data node at
  [:rings/<name>] in the ctrl tree. Requires the system to be started.

  Usage:
    (defring my-melody :C4 :E4 :G4 :B4)
    (tick! my-melody)   ; => :C4
    (ctrl/get [:rings/my-melody])    ; => the ring atom

  The ring can be reset via (ring-reset! my-melody) or by re-evaluating
  the defring form."
  [name & elements]
  `(do
     (def ~name (ring ~@elements))
     (ctrl/defnode! [~(keyword "rings" (clojure.core/name name))]
                    :type :data :value ~name)
     ~name))

;; ---------------------------------------------------------------------------
;; play-chord! — absolute chord playback
;; ---------------------------------------------------------------------------

(defn play-chord!
  "Play all notes of a chord simultaneously.

  Accepts:
    (play-chord! :C4 :major)          ; root keyword + quality keyword
    (play-chord! :C4 :major 1)        ; with inversion
    (play-chord! chord-record)         ; cljseq.chord/Chord record
    (play-chord! [60 64 67])           ; raw MIDI integer vector

  Inherits the active synth context (*synth-ctx*)."
  ([chord-or-root]
   (let [midis (cond
                 (instance? cljseq.chord.Chord chord-or-root)
                 (chord-ns/chord->midis chord-or-root)
                 (sequential? chord-or-root)
                 (vec chord-or-root)
                 :else (throw (ex-info "play-chord!: unrecognised form"
                                       {:arg chord-or-root})))]
     (doseq [m midis] (play! m *default-dur*))))
  ([root quality]
   (play-chord! root quality 0))
  ([root quality inversion]
   (let [p (pitch/keyword->pitch root)]
     (when-not p
       (throw (ex-info "play-chord!: unrecognised root keyword" {:root root})))
     (doseq [m (chord-ns/chord->midis (chord-ns/->Chord p quality (int inversion)))]
       (play! m *default-dur*)))))

;; ---------------------------------------------------------------------------
;; Voice-leading live helpers
;; ---------------------------------------------------------------------------

(defn play-voicing!
  "Play a voiced chord (vector of MIDI integers) simultaneously.

  Usage:
    (play-voicing! [60 64 67])                       ; C major close
    (play-voicing! (voice/close-position my-chord))  ; via cljseq.voice

  Inherits the active synth context (*synth-ctx*)."
  [midis]
  (doseq [m midis] (play! m *default-dur*)))

(defn play-progression!
  "Play a voice-led chord progression, one chord per `dur` beats.

  `chords` — seq of Chord records (cljseq.chord/Chord).
  `dur`    — duration in beats for each chord (default: 1).

  Applies smooth voice leading between chords. Plays all notes of each
  chord simultaneously, then sleeps for `dur` beats.

  Example:
    (play-progression! [(chord/chord :C 4 :major)
                        (chord/chord :F 4 :major)
                        (chord/chord :G 4 :dom7)
                        (chord/chord :C 4 :major)]
                       4)"
  ([chords]
   (play-progression! chords 1))
  ([chords dur]
   (let [voice-ns (requiring-resolve 'cljseq.voice/smooth-progression)
         voiced   (voice-ns chords)]
     (doseq [v voiced]
       (play-voicing! v)
       (core/sleep! dur)))))
