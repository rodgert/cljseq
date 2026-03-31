; SPDX-License-Identifier: EPL-2.0
(ns cljseq.dsl
  "cljseq DSL sugar layer — concise interactive forms for live performance.

  All forms in this namespace expand to the core protocol-level API.
  Re-exported from cljseq.core.

  ## Note playback
    (play! :C4)              ; shorthand; uses *default-dur*
    (play! :C4 1/2)          ; with explicit duration
    (play! 60)               ; MIDI integer form
    (phrase! [:C4 :E4] :dur 1/4)   ; melodic phrase
    (phrase! [:C4 1/4 :E4 1/8])    ; pitch/duration pairs

  ## Chords and scales
    (play-chord! :C4 :major)
    (arp! :C4 :major :up 1/16)
    (choose-from-scale :C :major)

  ## Structural / conditional
    (every 4 :beats (play! :C4))
    (one-in 4 (play! :C4))

  ## Cycling sequences
    (def r (ring :C4 :E4 :G4))
    (tick! r)

  ## Synth context
    (use-synth! :piano)
    (with-synth :piano ...)

  Key design decisions: §27 (DSL Sugar Layer), DSL usability corpus.")

;; ---------------------------------------------------------------------------
;; Dynamic vars
;; ---------------------------------------------------------------------------

(def ^:dynamic *default-dur*
  "Default note duration in beats. Override with `with-dur` or `set-default-dur!`.
  Registered in Configuration Registry (R&R §25)."
  1/4)

(def ^:dynamic *default-synth*
  "Default synth/target for play! calls. Set with `use-synth!` or `with-synth`."
  nil)

;; ---------------------------------------------------------------------------
;; Stub implementations — will be filled during Phase 0/1 implementation
;; ---------------------------------------------------------------------------

;; play! shorthand — multi-arity wrapper around the core play! step-map form
;; (defn play! ...)

;; phrase! — melodic phrase macro
;; (defmacro phrase! ...)

;; every — fires body every N beats (loop-local counter)
;; (defmacro every ...)

;; one-in — fires body with probability 1/N
;; (defmacro one-in ...)

;; ring — stateful cycling sequence
;; (defn ring ...)
;; (defn tick! ...)

;; play-chord! — absolute chord playback
;; (defn play-chord! ...)

;; arp! — arpeggiate a chord
;; (defn arp! ...)

;; choose-from-scale — random note from scale
;; (defn choose-from-scale ...)

;; use-synth! / with-synth — synth routing context
;; (defn use-synth! ...)
;; (defmacro with-synth ...)
