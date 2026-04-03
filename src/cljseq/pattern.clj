; SPDX-License-Identifier: EPL-2.0
(ns cljseq.pattern
  "Pattern and Rhythm — orthogonal sequencing primitives (R&R §5.1, NDLR model).

  Pattern holds note *selectors* (chord-relative indices, scale-relative indices,
  or absolute pitches/MIDI numbers). Rhythm holds a velocity sequence where nil
  means REST and :tie means HOLD (extend previous note, no retrigger).

  The key insight from the NDLR: pattern and rhythm are independent values that
  cycle at their own rates.  When their lengths differ, the combined phrase does
  not repeat until lcm(len-pattern, len-rhythm) steps — emergent complexity from
  simple parameterization.

  ## Constructors
    (pattern [:chord 1 3 5 2 4])      ; chord-relative indices (1-indexed)
    (pattern [:scale 1 3 5 8 5 3])    ; scale-relative indices (1-indexed)
    (pattern [:pitch :C4 :E4 :G4])    ; absolute pitches (keyword/Pitch/MIDI)
    (pattern [:midi  60  64  67])     ; raw MIDI integers

    (rhythm [100 nil 80 nil 100])     ; velocity/rest sequence
    (rhythm [127 :tie :tie nil 90])   ; with ties and rests

  ## Playing motifs
    ;; Set harmonic context (needed for :chord and :scale patterns)
    (use-harmony! (scale/scale :C 4 :major))
    (use-chord!   (chord/chord :C 4 :maj7))

    ;; motif! plays one full lcm-length cycle; call inside a live loop
    (motif! pat rhy {:clock-div 1/8})

    ;; deflive-loop :harmony and :chord opts wire the context per-thread
    (deflive-loop :melody {:harmony my-scale :chord my-chord}
      (motif! pat rhy {:clock-div 1/8}))

  ## Options for motif!
    :clock-div   — duration per step in beats (default 1/8)
    :gate        — note duration as fraction of clock-div (default 0.9)
    :probability — probability [0.0,1.0] that each note fires (default 1.0)
    :channel     — MIDI channel override (integer 1–16)

  ## Chord-relative resolution
    Index 1 = chord root, 2 = next chord tone, etc.
    Indices beyond chord size cycle with an octave shift upward.
    The current chord is read from *chord-ctx* (set via use-chord! or :chord opt).

  ## Scale-relative resolution
    Index 1 = scale root, 2 = 2nd degree, etc.
    Indices beyond scale size cycle with octave wrapping (via IScale/pitch-at).
    The current scale is read from *harmony-ctx* (set via use-harmony! or :harmony opt).

  Key design decisions: R&R §5.1 (NDLR model), Q47 (pattern × rhythm orthogonality)."
  (:require [cljseq.chord :as chord-ns]
            [cljseq.core  :as core]
            [cljseq.dsl   :as dsl]
            [cljseq.loop  :as loop-ns]
            [cljseq.pitch :as pitch]
            [cljseq.scale :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Records
;; ---------------------------------------------------------------------------

(defrecord Pattern [ptype data])
;; ptype — :chord | :scale | :pitch | :midi
;; data  — vector of selectors

(defrecord Rhythm [data])
;; data — vector of velocity values (int 0–127), nil (rest), or :tie (hold)

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn pattern
  "Construct a Pattern from a tagged vector.

  The first element is the type tag (:chord, :scale, :pitch, or :midi);
  the remaining elements are the note selectors.

  Examples:
    (pattern [:chord 1 3 5 2 4])   ; chord-relative, 1-indexed
    (pattern [:scale 1 3 5 8])     ; scale-relative, 1-indexed
    (pattern [:pitch :C4 :E4 :G4]) ; absolute pitch keywords
    (pattern [:midi 60 64 67])     ; raw MIDI integers"
  [v]
  (let [ptype (first v)
        data  (vec (rest v))]
    (when-not (#{:chord :scale :pitch :midi} ptype)
      (throw (ex-info "pattern: unknown type tag"
                      {:tag ptype :valid #{:chord :scale :pitch :midi}})))
    (when (empty? data)
      (throw (ex-info "pattern: data must be non-empty" {:tag ptype})))
    (->Pattern ptype data)))

(defn rhythm
  "Construct a Rhythm from a velocity vector.

  Each element is one of:
    integer 0–127 — note-on velocity
    nil            — rest (no note; virtual time still advances)
    :tie           — hold/extend previous note (no retrigger; time advances)

  Example:
    (rhythm [100 nil 80 nil 100 nil 80 nil])"
  [v]
  (when (empty? v)
    (throw (ex-info "rhythm: data must be non-empty" {})))
  (->Rhythm (vec v)))

;; ---------------------------------------------------------------------------
;; Resolution helpers
;; ---------------------------------------------------------------------------

(defn- gcd ^long [^long a ^long b]
  (if (zero? b) a (recur b (long (mod a b)))))

(defn- lcm ^long [^long a ^long b]
  (/ (* a b) (gcd a b)))

(defn- resolve-chord-selector
  "Resolve 1-indexed chord selector n to a MIDI note.
  Indices beyond chord size cycle upward by octave."
  [n midis]
  (let [n0     (dec (long n))
        cnt    (count midis)
        idx    (Math/floorMod n0 cnt)
        octave (Math/floorDiv n0 cnt)]
    (+ (long (nth midis idx)) (* 12 octave))))

(defn- resolve-selector
  "Resolve a single selector to a MIDI integer given the active contexts."
  [ptype selector harmony-ctx chord-ctx]
  (case ptype
    :chord
    (if chord-ctx
      (resolve-chord-selector selector (chord-ns/chord->midis chord-ctx))
      (throw (ex-info "chord-relative pattern requires *chord-ctx*" {})))

    :scale
    (if harmony-ctx
      (pitch/pitch->midi (scale-ns/pitch-at harmony-ctx (dec (long selector))))
      (throw (ex-info "scale-relative pattern requires *harmony-ctx*" {})))

    :pitch
    (pitch/->midi selector)

    :midi
    (long selector)))

;; ---------------------------------------------------------------------------
;; motif!
;; ---------------------------------------------------------------------------

(defn motif!
  "Play one full lcm-length cycle of `pat` × `rhy` against the current context.

  Steps through (lcm (count pat-data) (count rhy-data)) steps. At each step:
    - pattern cycles at its own length (independent of rhythm)
    - rhythm  cycles at its own length (independent of pattern)
    - nil velocity → rest (sleep only)
    - :tie velocity → hold (sleep only, no retrigger)
    - integer velocity → play the resolved pitch, then sleep

  Pitch resolution uses *chord-ctx* (for :chord patterns) and *harmony-ctx*
  (for :scale patterns) — both are thread-local and set by deflive-loop opts.

  Options:
    :clock-div   — duration per step in beats (default 1/8)
    :gate        — note duration fraction of clock-div (default 0.9)
    :probability — per-note fire probability 0.0–1.0 (default 1.0)
    :channel     — MIDI channel override (overrides *synth-ctx*)

  Example (from a live loop):
    (deflive-loop :melody {:harmony (scale/scale :C 4 :major)
                           :chord   (chord/chord :C 4 :maj7)}
      (motif! (pattern [:chord 1 3 5 2 4])
              (rhythm  [100 nil 80 nil 100])
              {:clock-div 1/8}))"
  ([pat rhy]
   (motif! pat rhy {}))
  ([pat rhy opts]
   (let [pd          (:data pat)
         rd          (:data rhy)
         ptype       (:ptype pat)
         pcnt        (long (count pd))
         rcnt        (long (count rd))
         n-steps     (lcm pcnt rcnt)
         clock-div   (get opts :clock-div 1/8)
         gate        (double (get opts :gate 0.9))
         prob        (double (get opts :probability 1.0))
         channel     (:channel opts)
         harmony-ctx loop-ns/*harmony-ctx*
         chord-ctx   loop-ns/*chord-ctx*
         note-dur    (* (double clock-div) gate)]
     (dotimes [i n-steps]
       (let [selector (nth pd (mod i pcnt))
             vel      (nth rd (mod i rcnt))]
         (when (and (some? vel) (not= :tie vel) (< (rand) prob))
           (let [midi (resolve-selector ptype selector harmony-ctx chord-ctx)
                 step {:pitch/midi   (long midi)
                       :dur/beats    note-dur
                       :mod/velocity (long vel)}
                 step (if channel (assoc step :midi/channel (long channel)) step)]
             (dsl/play! step)))
         (core/sleep! clock-div))))))

;; ---------------------------------------------------------------------------
;; Pattern/Rhythm utilities
;; ---------------------------------------------------------------------------

(defn pat-len
  "Return the number of selectors in the pattern."
  [p]
  (count (:data p)))

(defn rhy-len
  "Return the number of steps in the rhythm."
  [r]
  (count (:data r)))

(defn cycle-len
  "Return the lcm-length of one full pattern × rhythm cycle."
  [pat rhy]
  (lcm (long (pat-len pat)) (long (rhy-len rhy))))
