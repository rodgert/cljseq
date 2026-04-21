; SPDX-License-Identifier: EPL-2.0
(ns cljseq.pattern
  "Pattern and Rhythm — orthogonal sequencing primitives (R&R §5.1, NDLR model).

  Pattern holds note *selectors* (chord-relative indices, scale-relative indices,
  absolute pitches, raw MIDI numbers, or chromatic offsets from root). Rhythm holds
  a velocity sequence where nil means REST and :tie means HOLD.

  The key insight from the NDLR: pattern and rhythm are independent values that
  cycle at their own rates.  When their lengths differ, the combined phrase does
  not repeat until lcm(len-pattern, len-rhythm) steps — emergent complexity from
  simple parameterization.

  ## Constructors
    (pattern [:chord 1 3 5 2 4])       ; chord-relative indices (1-indexed)
    (pattern [:scale 1 3 5 8 5 3])     ; scale-relative indices (1-indexed)
    (pattern [:chromatic 0 2 4 5 7])   ; semitone offsets from *harmony-ctx* root
    (pattern [:pitch :C4 :E4 :G4])     ; absolute pitches (keyword/Pitch/MIDI)
    (pattern [:midi  60  64  67])      ; raw MIDI integers

    (rhythm [100 nil 80 nil 100])      ; velocity/rest sequence
    (rhythm [127 :tie :tie nil 90])    ; with ties and rests

  ## Named library
    (named-pattern :bounce)            ; => Pattern record (chord-relative)
    (named-pattern :scale-up)          ; => Pattern record (scale-relative)
    (pattern-names)                    ; => sorted list of all names

    (named-rhythm :tresillo)           ; => Rhythm record (3 in 8)
    (named-rhythm :son-clave)          ; => Rhythm record (5 in 8)
    (rhythm-names)                     ; => sorted list of all names

    (rhythm-from-euclid 5 8)           ; => Rhythm (5 onsets in 8 steps, vel 100)
    (rhythm-from-euclid 5 8 90)        ; => Rhythm with custom velocity
    (rhythm-from-euclid 5 8 90 1)      ; => rotated by 1 position

  ## Pattern transformations
    (reverse-pattern   p)              ; reverse selector order
    (rotate-pattern    p 2)            ; cyclic shift right by 2
    (transpose-pattern p 2)            ; add 2 to all numeric indices
    (invert-pattern    p)              ; reflect around min+max of indices

  ## Playing motifs
    ;; Set harmonic context (needed for :chord, :scale, :chromatic patterns)
    (use-harmony! (scale/scale :C 4 :major))
    (use-chord!   (chord/chord :C 4 :maj7))

    ;; motif! plays one full lcm-length cycle; call inside a live loop
    (motif! pat rhy {:clock-div 1/8})

    ;; deflive-loop :harmony and :chord opts wire the context per-thread
    (deflive-loop :melody {:harmony my-scale :chord my-chord}
      (motif! (named-pattern :bounce) (named-rhythm :tresillo) {:clock-div 1/8}))

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

  ## Chromatic resolution
    Index 0 = root, 1 = one semitone above root, -1 = one semitone below, etc.
    Root pitch is taken from *harmony-ctx*; no *chord-ctx* required.

  Key design decisions: R&R §5.1 (NDLR model), R&R §22.5.3 (named arpeggio library)."
  (:require [cljseq.chord  :as chord-ns]
            [cljseq.core   :as core]
            [cljseq.live  :as live]
            [cljseq.loop   :as loop-ns]
            [cljseq.pitch  :as pitch]
            [cljseq.rhythm :as rhythm-ns]
            [cljseq.scale  :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Records
;; ---------------------------------------------------------------------------

(defrecord Pattern [ptype data])
;; ptype — :chord | :scale | :chromatic | :pitch | :midi
;; data  — vector of selectors

(defrecord Rhythm [data])
;; data — vector of velocity values (int 0–127), nil (rest), or :tie (hold)

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(def ^:private valid-ptypes #{:chord :scale :chromatic :pitch :midi})

(defn pattern
  "Construct a Pattern from a tagged vector.

  The first element is the type tag; the remaining elements are note selectors:
    :chord      — 1-indexed chord tones (1=root); cycles with octave shift
    :scale      — 1-indexed scale degrees; wraps via IScale/pitch-at
    :chromatic  — semitone offsets from *harmony-ctx* root (0=root, negative ok)
    :pitch      — keyword/Pitch/MIDI absolute pitches
    :midi       — raw MIDI integers

  Examples:
    (pattern [:chord 1 3 5 2 4])
    (pattern [:scale 1 2 3 4 5])
    (pattern [:chromatic 0 2 4 7 9])
    (pattern [:pitch :C4 :E4 :G4])
    (pattern [:midi 60 64 67])"
  [v]
  (let [ptype (first v)
        data  (vec (rest v))]
    (when-not (valid-ptypes ptype)
      (throw (ex-info "pattern: unknown type tag"
                      {:tag ptype :valid valid-ptypes})))
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
;; Euclidean → Rhythm bridge
;; ---------------------------------------------------------------------------

(defn rhythm-from-euclid
  "Construct a Rhythm by distributing `k` onsets across `n` steps (Euclidean).
  Onset positions receive `velocity` (default 100); gaps become nil (rest).
  `offset` rotates the pattern right by that many positions (default 0).

  Examples:
    (rhythm-from-euclid 3 8)          ; tresillo — 3 in 8
    (rhythm-from-euclid 5 8 90)       ; 5 in 8, velocity 90
    (rhythm-from-euclid 5 8 100 1)    ; 5 in 8, rotated by 1"
  ([k n]
   (rhythm-from-euclid k n 100 0))
  ([k n velocity]
   (rhythm-from-euclid k n velocity 0))
  ([k n velocity offset]
   (rhythm (mapv #(if (pos? %) (long velocity) nil)
                 (rhythm-ns/euclidean k n offset)))))

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

    :chromatic
    (if harmony-ctx
      (+ (pitch/pitch->midi (:root harmony-ctx)) (long selector))
      (throw (ex-info "chromatic pattern requires *harmony-ctx*" {})))

    :pitch
    (pitch/->midi selector)

    :midi
    (long selector)))

;; ---------------------------------------------------------------------------
;; Pattern transformations
;; ---------------------------------------------------------------------------

(defn reverse-pattern
  "Return a new Pattern with the selector sequence reversed.

  Example:
    (reverse-pattern (pattern [:chord 1 2 3 4]))
    ;; => Pattern[:chord [4 3 2 1]]"
  [p]
  (->Pattern (:ptype p) (vec (reverse (:data p)))))

(defn rotate-pattern
  "Return a new Pattern with selectors cyclically shifted right by `n` positions.
  Negative `n` shifts left.

  Example:
    (rotate-pattern (pattern [:chord 1 2 3 4]) 1)
    ;; => Pattern[:chord [4 1 2 3]]"
  [p n]
  (let [data (:data p)
        cnt  (count data)
        n    (Math/floorMod (long n) (long cnt))]
    (->Pattern (:ptype p) (vec (concat (drop (- cnt n) data) (take (- cnt n) data))))))

(defn transpose-pattern
  "Return a new Pattern with `n` added to every numeric selector.
  Meaningful for :chord, :scale, and :chromatic patterns; also works for :midi.
  For :pitch patterns the selectors are keywords and this will throw.

  Example:
    (transpose-pattern (pattern [:chord 1 2 3]) 2)
    ;; => Pattern[:chord [3 4 5]]  — shifted up two chord tones"
  [p n]
  (->Pattern (:ptype p) (mapv #(+ % (long n)) (:data p))))

(defn invert-pattern
  "Return a new Pattern with selectors reflected around the min+max of the data.
  Preserves the same pitch range but mirrors interval direction.
  Meaningful for :chord, :scale, :chromatic, and :midi patterns.

  For a pattern [1 2 3 5] (min=1, max=5): each value v becomes (6 - v),
  giving [5 4 3 1]. An ascending run becomes descending.

  Example:
    (invert-pattern (pattern [:scale 1 2 3 4 5]))
    ;; => Pattern[:scale [5 4 3 2 1]] — same as reverse for linear runs"
  [p]
  (let [data  (:data p)
        lo    (apply min data)
        hi    (apply max data)
        pivot (+ lo hi)]
    (->Pattern (:ptype p) (mapv #(- pivot %) data))))

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

  Pitch resolution uses *chord-ctx* (for :chord patterns), *harmony-ctx*
  (for :scale and :chromatic patterns), or neither (for :pitch/:midi patterns).

  Options:
    :clock-div   — duration per step in beats (default 1/8)
    :gate        — note duration fraction of clock-div (default 0.9)
    :probability — per-note fire probability 0.0–1.0 (default 1.0)
    :channel     — MIDI channel override (overrides *synth-ctx*)

  Example (from a live loop):
    (deflive-loop :melody {:harmony (scale/scale :C 4 :major)
                           :chord   (chord/chord :C 4 :maj7)}
      (motif! (named-pattern :bounce)
              (named-rhythm  :tresillo)
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
             (live/play! step)))
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

;; ---------------------------------------------------------------------------
;; Named pattern library (R&R §22.5.3 + NDLR factory patterns)
;;
;; All chord patterns are 1-indexed (1=root) to match the NDLR convention.
;; All scale patterns are 1-indexed (1=scale root).
;; ---------------------------------------------------------------------------

(def ^:private pattern-library
  {;; -------------------------------------------------------------------------
   ;; Chord arpeggios
   ;; -------------------------------------------------------------------------
   :up            (pattern [:chord 1 2 3 4])
   :down          (pattern [:chord 4 3 2 1])
   :bounce        (pattern [:chord 1 2 3 4 3 2])
   :alberti       (pattern [:chord 1 3 2 3])          ; oom-pah-pah (piano)
   :waltz-bass    (pattern [:chord 1 3 3])             ; oom-pah-pah (bass)
   :broken-triad  (pattern [:chord 1 2 3 1 2 3])
   :guitar-pick   (pattern [:chord 1 3 2 3 1 3])
   :montuno       (pattern [:chord 1 3 2 4 3 4])       ; Cuban montuno
   :stride        (pattern [:chord 1 1 3 2 3])         ; jazz stride bass
   :raga-alap     (pattern [:chord 1 2 1 3 2 1 4])    ; ornamental ascent
   :root-fifth    (pattern [:chord 1 4 1 4])           ; root+5th alternation
   :root-octave   (pattern [:chord 1 5 1 5])           ; root+octave (4-note triad → idx 4 wraps)
   :shell-up      (pattern [:chord 1 3 5 7])           ; 7th-chord shell ascending
   :shell-down    (pattern [:chord 7 5 3 1])           ; 7th-chord shell descending

   ;; -------------------------------------------------------------------------
   ;; Scale runs
   ;; -------------------------------------------------------------------------
   :scale-up      (pattern [:scale 1 2 3 4 5 6 7 8])
   :scale-down    (pattern [:scale 8 7 6 5 4 3 2 1])
   :scale-bounce  (pattern [:scale 1 2 3 4 5 4 3 2])
   :pentatonic    (pattern [:scale 1 2 3 5 6 8])       ; major pentatonic shape
   :zigzag        (pattern [:scale 1 3 2 4 3 5 4 6])  ; up-skip pattern
   :thirds-up     (pattern [:scale 1 3 2 4 3 5 4 6 5 7])
   :peak          (pattern [:scale 1 3 5 7 5 3])       ; rise and fall

   ;; -------------------------------------------------------------------------
   ;; Chromatic colour patterns (offsets from root; need *harmony-ctx*)
   ;; -------------------------------------------------------------------------
   :chromatic-4   (pattern [:chromatic 0 1 2 3])
   :chromatic-bl  (pattern [:chromatic 0 3 5 6 7 10])  ; blues hexatonic
   })

(defn named-pattern
  "Look up a Pattern by name from the built-in library.
  Throws if the name is not found.

  Examples:
    (named-pattern :bounce)      ; chord-relative bounce
    (named-pattern :scale-up)    ; ascending scale run"
  [name]
  (or (get pattern-library name)
      (throw (ex-info "named-pattern: not found"
                      {:name name :available (sort (keys pattern-library))}))))

(defn pattern-names
  "Return a sorted list of all built-in pattern names."
  []
  (sort (keys pattern-library)))

;; ---------------------------------------------------------------------------
;; Named rhythm library
;; ---------------------------------------------------------------------------

(def ^:private rhythm-library
  {;; Euclidean rhythms — all at velocity 100
   :tresillo       (rhythm-from-euclid 3 8)     ; [1 0 0 1 0 0 1 0]
   :cinquillo      (rhythm-from-euclid 5 8)     ; [1 0 1 1 0 1 1 0]
   :son-clave      (rhythm-from-euclid 5 8)     ; same as cinquillo
   :rumba-clave    (rhythm-from-euclid 5 8 100 1) ; rotated son clave
   :bossa-nova     (rhythm-from-euclid 3 16)    ; sparser bossa feel
   :four-on-floor  (rhythm-from-euclid 4 4)     ; straight quarters
   :straight-8     (rhythm-from-euclid 8 8)     ; all eighths
   :straight-16    (rhythm-from-euclid 16 16)   ; all sixteenths
   :euclid-5-16    (rhythm-from-euclid 5 16)    ; African 5-in-16
   :euclid-7-16    (rhythm-from-euclid 7 16)    ; 7-in-16

   ;; Hand-crafted rhythms
   :offbeat        (rhythm [nil 100 nil 100 nil 100 nil 100])   ; backbeat 8ths
   :onbeat         (rhythm [100 nil 100 nil 100 nil 100 nil])   ; downbeat 8ths
   :syncopated     (rhythm [100 nil nil 90 nil 80 nil nil])     ; common syncopation
   :habanera       (rhythm [100 nil nil 80 nil nil 70 nil])     ; habanera cell
   :waltz          (rhythm [100 80 80])                         ; 3/4 waltz
   :shuffle        (rhythm [100 nil 80 nil])                    ; 2-step swing feel
   })

(defn named-rhythm
  "Look up a Rhythm by name from the built-in library.
  Throws if the name is not found.

  Examples:
    (named-rhythm :tresillo)    ; 3-in-8 Euclidean
    (named-rhythm :son-clave)   ; 5-in-8 Euclidean"
  [name]
  (or (get rhythm-library name)
      (throw (ex-info "named-rhythm: not found"
                      {:name name :available (sort (keys rhythm-library))}))))

(defn rhythm-names
  "Return a sorted list of all built-in rhythm names."
  []
  (sort (keys rhythm-library)))
