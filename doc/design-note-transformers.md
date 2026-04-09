# Design: Note Transformer Vocabulary

*Gap analysis from Bitwig Studio Note FX library (22 devices). Documents transformer concepts not yet present in cljseq, to be evaluated for implementation priority.*

---

## What cljseq Already Has

| Bitwig device | cljseq equivalent |
|---|---|
| Arpeggiator | `cljseq.pattern` |
| Humanize | `cljseq.mod` humanise |
| Key Filter | `cljseq.scale` snap-to-scale |
| Micro-pitch | `cljseq.scala` + `*tuning-ctx*` |
| Note Transpose | `cljseq.pitch` / `cljseq.interval` |
| Transpose Map | DSL step mods (partial) |
| Note Length | DSL duration |
| Multi-note | `cljseq.chord` + DSL |
| Note Delay | DSL timing offset |
| Randomize (pitch/vel) | `cljseq.random` + `cljseq.stochastic` |
| Note Filter | DSL pitch/velocity gate |

The remaining concepts below have no cljseq equivalent.

---

## New Transformer Archetypes

### 1. Echo — Time + Pitch Decay

Bitwig's Echo is a note repeater where each repeat applies both a velocity scale *and* a pitch offset. Each echo is: shorter (gate), quieter (velocity × scale), differently pitched (pitch + offset).

```
Repeat N: velocity × scale^N, pitch + (offset × N), duration × gate
```

The pitch scaling can be filtered to apply only within a defined range — so echoes that drift out of range stop transposing but continue decaying in velocity.

**Musical effect**: An echo with `pitch-scale +2` and 4 repeats produces a rising cascade: root → M2 → M3 → tritone → M6, each quieter. With `pitch-scale -12` the echo drops an octave each repeat. A scale of `0` gives a conventional echo (no pitch drift).

**Key distinction from Temporal Buffer**: Temporal Buffer replays exact events. Echo transforms each repeat — the Nth echo is a derived event, not a stored one.

```clojure
(defecho piano-echo
  :repeats    4
  :time       (/ 1 8)           ; eighth-note spacing
  :gate       0.8               ; each echo 80% of previous duration
  :velocity-scale 0.75          ; each echo 75% of previous velocity
  :pitch-scale    2             ; each echo +2 semitones
  :pitch-range    [:C2 :C6])    ; stop transposing outside this range
```

---

### 2. Dribble — Gravity-Physics Timing

A note bouncer where the time between bounces follows gravity physics (t² decay), not equal spacing. Each bounce is shorter than the previous by a damping factor. The ball eventually comes to rest.

```
bounce_time(N) = first_bounce × damping^N × velocity_scale
```

At `damping=0.0`, bounce height stays constant (equal pulse). At `damping=0.7`, bounces converge rapidly. "Shortest Bounce" sets a minimum inter-event time — below this threshold the ball stops bouncing (prevents infinitely rapid events). "Hold Last Note" sustains the final bounce while the original note remains held.

**Musical effect**: A struck note generates a diminuendo cascade with natural rhythmic deceleration — like a ball dropping on a hard surface. The physics gives a timing curve no LFO can easily replicate.

```clojure
(defdribble physical-echo
  :first-bounce  (/ 1 4)      ; quarter-note at max velocity
  :damping       0.65         ; bounces converge at ~65% per hop
  :shortest      0.02         ; stop at 20ms minimum spacing
  :hold-last     true)        ; sustain final bounce note
```

---

### 3. Bend — Per-Note Pitch Envelope

Each incoming note receives its own pitch bend envelope: it starts at a relative pitch offset and bends *to* the note's actual pitch over a configurable shape and duration. A pre-delay postpones when the bend begins.

**Key distinction from global portamento**: `*tuning-ctx*` adjusts the static pitch mapping. Bend is a temporal envelope — each note independently bends from a starting displacement to its target, regardless of what the previous note was.

```
pitch(t) = target + (start_offset × (1 - shape_curve(t / duration)))
```

```clojure
(defbend slide-into
  :start-offset -12            ; begin an octave below target
  :shape        :ease-out      ; fast initial glide, slow arrival
  :duration     (/ 1 8)        ; eighth-note glide time
  :pre-delay    0.0)           ; immediate start
```

**Integration with non-ET tuning**: The bend envelope operates on the continuous pitch space, so it respects `*tuning-ctx*` — the target note is the KBM-mapped pitch, and the start offset is relative to that.

---

### 4. Strum — Chord Collection + Fragmentation

A chord fragmenter with a **grace period** concept: notes arriving within a time window are collected into a chord *before* strumming begins. This is fundamentally different from immediate sequential playback — you can play a chord "messily" and the strum collects the intended chord before fragmenting it.

**Parameters**:
- `grace-period` — time window for chord collection (before strum begins)
- `direction` — `:up` (lowest first), `:down`, `:random`, or a step pattern of directions (up to 4 steps, so consecutive chords alternate)
- `stride` — how many notes play simultaneously per strum step (1 = one at a time, 2 = pairs)
- `rate` — tempo-relative or absolute time between strum steps

```clojure
(defstrum guitar-strum
  :grace-period  0.05          ; 50ms collection window
  :direction     [:up :up :down :up]   ; 4-step pattern
  :stride        1
  :rate          (/ 1 32))     ; 32nd-note strum speed
```

**The grace period as a general concept**: Collecting events within a time window before processing is broadly useful — it could apply to chord identification in `cljseq.analyze`, harmonizer resolution, and voice assignment.

---

### 5. Note Repeats — Euclidean Distribution

A note repeater with two distinct pattern modes:

**Burst**: All retriggers lined up consecutively from the note onset.

**Euclid**: Retriggers distributed as evenly as possible across the pattern length (Bjorklund/Euclidean algorithm). Same density, different rhythmic feel — the spaces between hits are maximally even.

```
Euclid(hits=3, steps=8) → [1 0 0 1 0 0 1 0]   ; 3 hits in 8 steps
Burst(hits=3, steps=8)  → [1 1 1 0 0 0 0 0]   ; 3 hits at start
```

**Accent system**: A count of "strong" repeats receive full velocity; remaining repeats are attenuated. Accent pattern can be flipped. "Always play accents" overrides the per-repeat Chance parameter for accented hits. This creates a dynamic, rhythmically patterned repeat that's more musical than uniform repetition.

**Gate modes**:
- Fixed percentage of repeat rate
- Fermata: hold until the *next* trigger fires (open-ended duration)

**Chance**: Per-individual-repeat probability. Combined with Euclid distribution, this produces stochastic rhythms with Euclidean skeleton.

```clojure
(defnote-repeats euclidean-triplet
  :mode         :euclid
  :rate         (/ 1 16)
  :length       12              ; 12-step pattern
  :density      0.33            ; ~4 hits in 12 steps
  :rotate       2               ; offset pattern by 2 steps
  :chance       0.85            ; 85% per repeat
  :accent-count 2               ; every 3rd hit is accented
  :accent-boost 1.3)            ; accents at 130% velocity
```

---

### 6. Harmonize — Sidechain Conforming

Incoming notes are snapped to the nearest pitch from a set of pitches defined by *another track's current live notes* — not a static scale.

**The key distinction**: `cljseq.scale` snaps to a predefined scale. Harmonize snaps to whatever the sidechain track is playing *right now*. As the sidechain track changes chords, the allowed pitch set changes with it.

This is the follower-side complement to what `cljseq.analyze` + ImprovisationContext does from the leader's side. In ensemble improvisation terms:
- Leader: plays freely; `cljseq.analyze` builds harmonic context
- Follower (Harmonize analog): receives that context, snaps all output notes to it

**Implementation sketch**:

```clojure
;; The sidechain source is a shared atom updated by the leader's loop
;; (or by cljseq.analyze streaming the ImprovisationContext)
(defharmonize ensemble-follower
  :source     harmony-ctx-atom    ; shared ImprovisationContext
  :snap       :nearest            ; or :above :below :random-weighted
  :passthrough false)             ; or true: pass unsnapped + snapped
```

**Connection to Ensemble Improvisation design**: This is the concrete mechanism for the "harmonic complement" and "call and response" behaviors described in `design-ensemble-improvisation.md`. The ImprovisationContext (built from `cljseq.analyze` + Temporal Buffer) *is* the sidechain source.

---

### 7. Stepwise — Step Sequencer as Transformer Layer

Eight independent step sequencer rows running simultaneously. Incoming notes **pass through** and the sequencer plays *on top* — it is additive, not replacing.

**Mode**:
- **Pulse**: Each enabled step fires a note-on for the first half, note-off for second half (staccato)
- **Gate**: Consecutive enabled steps merge into one sustained note (legato)

Each row has: pitch, rate, step length (1–16), velocity, timing offset (±100% of rate — "+75% at Bar rate" = beat 4 in 4/4), mute/solo.

**The timing offset is the key creative parameter**: By setting different offsets across rows, you create polyrhythmic patterns with precise metric placement. A row at Bar rate with +75% offset always hits the "and" of beat 4 — you can build metric patterns structurally.

**Distinction from `deflive-loop`**: Stepwise is embedded *in the signal path* — it layers under the current note stream without a separate thread. For quick pattern layering without loop management overhead.

---

### 8. Latch — Stateful Note Sustaining

A stateful note sustainer with three modes:

- **Simple**: Hold the current note until the next one arrives. Each new note replaces the previous.
- **Toggle**: Every other note triggers. First press latches; second press releases. Behaves like a latching footswitch.
- **Velocity**: Toggle trigger threshold set by velocity — notes above threshold toggle on; below toggle off. Enables expression-pedal-like control via velocity-sensitive playing.

Operates polyphonically per pitch by default, or in mono mode (all held notes released when new note arrives).

**Musical use**: Latch in Toggle mode allows a single key press to sustain a drone indefinitely without a held key. In ensemble context, a performer can latch a chord, free their hands, then unlatch by playing the same note again.

---

### 9. Velocity Curve — Piecewise Breakpoint Mapping

A piecewise velocity shaper with three user-adjustable breakpoints defining a nonlinear input→output velocity curve. More expressive than a single curve type:

```
breakpoints: [(in₁, out₁), (in₂, out₂), (in₃, out₃)]
```

Allows: soft bottom (compress low velocities), hard top (limit peaks), or S-curve (compress midrange, expand extremes). Any arbitrary three-segment velocity response.

**cljseq enhancement**: `cljseq.mod` has velocity curves but likely uses named curve types (linear, exponential). Adding piecewise breakpoints would allow per-instrument velocity response calibration — essential for devices with non-linear response (the Hydrasynth's envelope depth perception, for instance).

---

### 10. Quantize — Probabilistic (Forgiveness)

Shifts notes toward the next timing interval with an amount parameter (0–100% of the distance) and a "forgiveness" parameter: notes close enough to the target interval may or may not be snapped depending on how close they are.

**Forgiveness** is probabilistic quantization: notes within the forgiveness window have a reduced (or zero) chance of snapping. This allows naturally well-placed notes to stay where they are while only pulling the clearly-off ones. Humanize + Quantize in the right balance produces "tight but not mechanical" timing.

---

## Implementation Priority

**Near-term (high musical value, straightforward):**
1. `Echo` — time + pitch decay; composable with Temporal Buffer input
2. `Strum` — grace period + stride; needed for chord-playback loops
3. `Bend` — per-note pitch envelope; complements `*tuning-ctx*`
4. `Note Repeats (Euclid)` — Euclidean repeat distribution; extends `cljseq.rhythm`

**Medium-term:**
5. `Harmonize (sidechain)` — requires ensemble-harmony-groundwork first
6. `Dribble` — physics-based timing; standalone utility
7. `Latch` — stateful utility; useful for performance/drone contexts

**Lower priority:**
8. `Stepwise` — pattern layering; overlap with `deflive-loop` patterns
9. `Velocity Curve (piecewise)` — device calibration utility
10. `Quantize (forgiveness)` — timing utility

---

## Namespace Sketch

```clojure
;; cljseq.transform — new namespace for these transformer types
;; All implement a common Transformer protocol:
;;   (transform [this event context] => [event*])

(defprotocol Transformer
  (transform [this event context]))

;; Stateful transformers hold state in an atom:
(defrecord Echo [params state])
(defrecord Dribble [params state])
(defrecord Latch [params state])

;; Stateless transformers are pure functions:
(defrecord Bend [params])
(defrecord Strum [params])
(defrecord VelocityCurve [params])
```

Stateful transformers (Dribble, Latch, Note Repeats) carry state between events; stateless transformers are pure functions of the event + context. The distinction matters for hot-swap: stateless transformers can be swapped atomically; stateful ones need state migration or reset.
