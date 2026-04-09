# cljseq Memory Field — Design Document

**Status:** Superseded — see `design-temporal-buffer.md`
**Author:** Thomas Rodgers  
**Date:** 2026-04-07  
**Branch:** TBD — Memory Field is now a named preset of `cljseq.temporal-buffer`

> **Note:** The Memory Field concept is fully preserved and extended in
> `design-temporal-buffer.md` (2026-04-07). The Cosmos preset in that document
> is the Memory Field. This file is retained for design history.

---

## 1. Motivation

Every sequencer in cljseq is a *generator* — it produces note events from patterns,
algorithms, or stochastic processes. The Memory Field introduces a second paradigm:
the *temporal transformer*. It takes note events as input, stores them in a living
buffer, and emits a drifted, smeared, decaying version of that material through one
or more independent playback cursors.

The concept is inspired by the Soma Cosmos "Drifting Memory Station" — a hardware
device that achieves a similar effect in the audio domain — and by Mutable Instruments
Clouds / VCVRack Texture Synthesizer patches that approximate it in Eurorack. The
Memory Field is the note-event analog: structurally equivalent, but operating on
discrete MIDI events rather than audio samples. This gives it properties audio cannot
offer: harmonic awareness, scale-degree gravity, and full integration with cljseq's
music theory and tuning infrastructure.

The closest existing cljseq primitive is `cljseq.flux` — a buffer with a single
read cursor. Flux is a degenerate Memory Field. The goal is to generalize the
abstraction so that Flux, loopers, tape-echo patterns, and the full Cosmos-style
drifting memory are all instances of one concept.

---

## 2. Core Abstraction

A **Memory Field** is:

```
Memory Field = { buffer, cursors[], decay, feedback }
```

### 2.1 Buffer

A beat-timestamped sequence of note events. Events are not stored as separate
note-on / note-off pairs; each event is a self-contained tuple:

```clojure
{:pitch-cents  7200.0    ; pitch in cents (Middle C = 6000¢); float for sub-cent precision
 :velocity     80        ; 0-127
 :duration     0.5       ; beats
 :beat         42.375    ; absolute beat position when written
 :generation   0}        ; feedback generation count (0 = original input)
```

Pitch is stored in cents (not MIDI note number) to preserve sub-semitone precision
through drift and scale-gravity operations. Output is converted back to MIDI
note + pitch-bend before transmission.

The buffer is a beat-timeline: events sit at positions in beat-time. The buffer
has a configurable capacity (in beats) and a decay model that thins it over time.

### 2.2 Cursors

A cursor is a moving read or write position in the buffer. Each cursor has:

```clojure
{:id         :head-1
 :mode       :read          ; :read | :write | :read-write
 :position   0.0            ; current beat position in buffer
 :speed      1.0            ; current playback rate (mutated by drift)
 :offset     0.0            ; initial offset from write head (beats behind)
 :drift      {...}          ; drift parameters (nil = no drift)
 :pitch-drift {...}         ; pitch drift parameters (nil = timing-only)
 :channel    1}             ; MIDI output channel for this cursor
```

**Write cursors** always operate at 1.0x speed — they record input events at
real time. Speed drift applies to read cursors only.

**Read cursors** move through the buffer at their current speed, firing events
as their position crosses event beat timestamps. The same event can be fired
multiple times if a cursor wraps around — this is the looping quality, modulated
by decay so the buffer is never quite the same on the second pass.

**Note duration at read time:** When a read cursor fires an event with duration D
at speed S, the corresponding note-off fires after D / S beats. A cursor at 0.9x
speed stretches both the timing *and* the note duration — tape-authentic behavior.

### 2.3 Cursor Count and Texture

| Cursors | Effect |
|---------|--------|
| 1 read, no drift | Clean playback — equivalent to Flux |
| 1 read, drifting | Single smeared voice |
| 2–4 read, drifting | Overlapping cloud — the Cosmos texture |
| N read, fixed offsets | Tape echo / multi-tap delay |
| 1 write + 1 read, locked | Traditional looper |

---

## 3. Drift Model

Each read cursor has an independent drift state — a random walk on its speed
parameter.

```clojure
{:drift
 {:range           0.15    ; max deviation from 1.0x speed (±0.15 = ±15%)
  :rate            0.01    ; step size per beat (how fast the walk moves)
  :mean-reversion  0.05}}  ; pull back toward 1.0x per beat (prevents runaway)
```

### 3.1 Speed Update (per tick)

```
speed ← speed + random(-rate, +rate)
speed ← speed + mean-reversion × (1.0 - speed)   ; pull toward 1.0
speed ← clamp(speed, 1.0 - range, 1.0 + range)
```

Mean-reversion is essential for musical usefulness. Without it, cursors eventually
drift to extreme speeds and the texture becomes incoherent. With mean-reversion,
they wander organically but remain in a musically useful range.

### 3.2 Speed Range Guidelines

| Range | Character |
|-------|-----------|
| ±0.02 | Subtle shimmer — almost imperceptible timing variation |
| ±0.08 | Gentle drift — clearly human-feeling timing |
| ±0.15 | Noticeable smear — tape-echo-like |
| ±0.30 | Heavy drift — texture dissolves from original |

---

## 4. Pitch Drift

Pitch drift is independent of timing drift. A cursor can drift in time without
drifting in pitch (`:timing` mode), or both can drift together.

### 4.1 Modes

#### `:timing`
No pitch modification. Output pitch = stored pitch. Only event timing is affected
by cursor speed. Clean harmony, smeared rhythm.

#### `:tape`
Pitch offset is proportional to speed deviation:

```
pitch_offset_cents = (speed - 1.0) × tape-scale
output_pitch_cents = stored_pitch_cents + pitch_offset_cents
```

`tape-scale` controls sensitivity. At `tape-scale = 100`:
- Speed 0.9x → −10¢ (just below pitch)
- Speed 1.1x → +10¢ (just above pitch)
- Speed 0.8x → −20¢ (significant flat drift)

Recommended range: 50–200¢ depending on desired subtlety.

#### `:scale-gravity`
Pitch drift (from `:tape` calculation) is modified by a gravitational pull toward
the nearest scale degree in the active tuning context:

```
drifted_cents  = stored_pitch_cents + (speed - 1.0) × tape-scale
nearest_degree = nearest-scale-degree(*tuning-ctx*, drifted_cents)
gravity_pull   = (nearest_degree - drifted_cents) × gravity-strength
output_cents   = drifted_cents + gravity_pull
```

`gravity-strength` [0.0–1.0] controls the behavior:

| Strength | Effect |
|----------|--------|
| 0.0 | Pure tape drift — scale unaware |
| 0.05–0.15 | Soft gravity — pitch wanders through between-note space, tends toward scale degrees |
| 0.3–0.5 | Medium gravity — clear attraction, still fluid |
| 1.0 | Instant snap — equivalent to hard quantization |

### 4.2 Tuning and Non-Equal-Temperament

Scale-gravity is where the Memory Field connects directly to cljseq's tuning
infrastructure. Gravity wells sit at scale degree positions — which in non-ET
tunings are *not* at 12-TET semitone positions.

**Examples:**

- **Just intonation**: Major third gravity well at 386¢ (vs. ET 400¢). A pitch
  drifting near the major third slowly migrates toward the pure interval.

- **Maqam Rast**: Neutral third at ~350¢, neutral seventh at ~1050¢ become
  attractors. Drift naturally inhabits these quarter-tone positions rather than
  passing through them as errors.

- **Pelog**: Highly unequal spacing creates asymmetric gravity wells — some
  intervals attract strongly, wide gaps between degrees create open drift space.

This is musically meaningful: the drift becomes a way of *inhabiting* the tuning
rather than imposing it. Notes don't snap to the scale; they are slowly drawn
toward it while remaining organic.

### 4.3 Per-Cursor Pitch Mode

Different cursors can operate in different pitch modes simultaneously:

```clojure
{:cursors
 [{:id :head-1 :pitch-drift {:mode :scale-gravity :strength 0.1
                              :tuning-ctx *tuning-ctx*}}
  {:id :head-2 :pitch-drift {:mode :tape :tape-scale 80}}
  {:id :head-3 :pitch-drift {:mode :timing}}]}
```

Head-1 drifts toward scale degrees; head-2 drifts freely in pitch; head-3
drifts only in time. The three voices create a texture where the same source
material is simultaneously harmonically anchored, freely drifting, and
rhythmically smeared — each from the same buffer.

---

## 5. Decay

The buffer is not permanent. Decay is what separates the Memory Field from a
traditional looper — it ensures the texture evolves rather than repeating.

### 5.1 Probabilistic Sweep

At configurable intervals (default: every beat), each event in the buffer
survives with probability `survival-rate`:

```clojure
{:decay
 {:mode          :probabilistic
  :sweep-rate    1     ; beats between sweeps
  :survival      0.97  ; per-sweep survival probability
  :min-velocity  10}}  ; events below this velocity threshold are always removed
```

With `survival = 0.97` and `sweep-rate = 1`:
- Expected half-life ≈ 23 beats (≈ 6 bars at 4/4, 110 BPM)
- Events don't die at a fixed time — they fade stochastically, unpredictably

### 5.2 Generation-Based Decay

Feedback events (see §6) carry a `:generation` count. Decay can be biased
against higher generations — original input survives longest, each feedback
generation decays faster:

```clojure
{:decay
 {:mode           :probabilistic
  :survival       0.97
  :generation-penalty 0.02}}  ; subtract per generation from survival rate
```

Generation 0 (original): 0.97 survival  
Generation 1: 0.95 survival  
Generation 2: 0.93 survival  

This creates a natural hierarchy: original material persists longest; echoes
of echoes dissolve fastest.

### 5.3 Decay Modes

| Mode | Behavior |
|------|----------|
| `:none` | No decay — buffer accumulates indefinitely (Flux, looper) |
| `:probabilistic` | Stochastic sweep — events fade unpredictably (Cosmos-like) |
| `:age` | Events older than N beats are removed deterministically |
| `:capacity` | When buffer exceeds capacity, oldest events are dropped (FIFO) |

---

## 6. Feedback

Feedback re-injects read cursor output back into the buffer as new write events.
This is the primary mechanism for sustained, self-generating texture — without
external input, feedback keeps the field alive (until decay drains it).

```clojure
{:feedback
 {:enabled          true
  :from             [:head-1 :head-2]  ; which cursors feed back
  :probability      0.25               ; per-event feedback probability
  :velocity-scale   0.85               ; attenuate velocity each generation
  :pitch-transform  :identity          ; :identity | :transpose | :scale-quantize
  :max-generation   4}}                ; stop feedback after N generations
```

### 6.1 Velocity Attenuation

`velocity-scale < 1.0` ensures feedback decays — each generation is quieter.
At `velocity-scale = 0.85`, a note at velocity 100 becomes:
- Gen 1: 85
- Gen 2: 72
- Gen 3: 61
- Gen 4: 52 (min-velocity threshold reached → removed by decay sweep)

This is the physical model: each pass through the feedback loop loses energy.

### 6.2 Pitch Transform on Feedback

`:identity` — pitch unchanged. Echo of the original.

`:transpose` — pitch shifted by a fixed interval on each feedback pass.
With `+7` semitones, each generation is a fifth higher — creates rising harmonic
accumulation before decay claims it.

`:scale-quantize` — each generation's pitch is snapped to the nearest scale
degree. Feedback gradually "corrects" toward the scale even when the original
input was drifted or chromatic.

---

## 7. Existing Concepts as Memory Field Presets

The Memory Field generalizes the following existing or planned concepts:

### Flux (existing)
```clojure
{:buffer  {:mode :pre-populated}
 :cursors [{:mode :read :speed 1.0 :drift nil :pitch-drift nil}]
 :decay   {:mode :none}
 :feedback {:enabled false}}
```

### Looper
```clojure
{:buffer  {:mode :input}
 :cursors [{:mode :write :speed 1.0}
           {:mode :read  :speed 1.0 :offset 0.0 :drift nil}]
 :decay   {:mode :none}
 :feedback {:enabled false}}
```

### Tape Multi-Echo
```clojure
{:buffer  {:mode :input}
 :cursors [{:mode :write :speed 1.0}
           {:mode :read :offset -2.0 :drift nil :pitch-drift {:mode :timing}}
           {:mode :read :offset -4.0 :drift nil :pitch-drift {:mode :timing}}
           {:mode :read :offset -6.0 :drift nil :pitch-drift {:mode :timing}}]
 :decay   {:mode :none}
 :feedback {:enabled false}}
```

### Cosmos (Drifting Memory)
```clojure
{:buffer  {:mode :input :capacity 64}
 :cursors [{:mode :read :offset -1.0
            :drift {:range 0.12 :rate 0.01 :mean-reversion 0.04}
            :pitch-drift {:mode :scale-gravity :strength 0.12}}
           {:mode :read :offset -3.5
            :drift {:range 0.18 :rate 0.008 :mean-reversion 0.03}
            :pitch-drift {:mode :tape :tape-scale 80}}
           {:mode :read :offset -6.0
            :drift {:range 0.08 :rate 0.015 :mean-reversion 0.06}
            :pitch-drift {:mode :timing}}]
 :decay   {:mode :probabilistic :sweep-rate 1 :survival 0.97}
 :feedback {:enabled true :from [:head-1] :probability 0.2
            :velocity-scale 0.85 :max-generation 3}}
```

---

## 8. API Sketch

```clojure
;; Define a named memory field (analogous to deflive-loop)
(defmemory-field :cosmos
  {:buffer   {:mode :input :capacity 64}
   :cursors  [...]
   :decay    {:mode :probabilistic :survival 0.97}
   :feedback {:enabled true :probability 0.2 :velocity-scale 0.85}})

;; Send notes into the field (from a live loop or manually)
(memory-send! :cosmos :C4 80 1)     ; pitch, velocity, duration-beats
(memory-send! :cosmos note)         ; or pass an event map

;; Macro-level control (trajectory targets)
(memory-set! :cosmos :decay/survival 0.92)     ; faster forgetting
(memory-set! :cosmos :head-1/drift-range 0.20) ; more drift
(memory-set! :cosmos :head-2/pitch-drift/strength 0.05) ; looser gravity

;; Inspect state
(memory-info :cosmos)   ; => {:buffer-size 42 :cursor-positions [...] ...}
(memory-clear! :cosmos) ; drain the buffer immediately

;; Flush all pending notes (for clean transitions)
(memory-flush! :cosmos)
```

### Integration with live loops

```clojure
;; A feeder loop sends material into the field
(deflive-loop :feeder {}
  (memory-send! :cosmos (rand-nth [:C4 :E4 :G4]) 70 2)
  (sleep! 3))

;; The field plays back through its own internal cursor threads
;; No additional loop needed — the field is self-running once started
```

### Trajectory integration

The `memory-set!` parameters are trajectory targets — the conductor can shape
the field's character over the arc of a composition:

```clojure
;; Tension builds → field becomes denser and more drifted
(section :build
  (over 32 (trajectory :smooth-step
    {:cosmos/head-1.drift-range [0.05 0.25]
     :cosmos/decay.survival     [0.99 0.94]})))
```

---

## 9. Relationship to Studio Integration

The Memory Field operates at the note-event level — a unique layer not covered
by the hardware equivalents:

| Layer | Device | CV-controllable | Note-aware | Tuning-aware |
|-------|--------|-----------------|------------|--------------|
| Audio | Soma Cosmos | No | No | No |
| Audio/CV | VCVRack Clouds patch | Yes (CV) | No | No |
| Eurorack | MakeNoise ReSynthesizer | Yes (CV) | No | No |
| Note-event | cljseq Memory Field | Yes (memory-set!) | Yes | Yes |

All four layers can coexist. cljseq generates note material → Memory Field
transforms it → output drives Cascadia → CV feeds Eurorack → audio goes
through VCVRack/Cosmos.

The Memory Field is the only layer that can reason about pitch classes, scales,
and harmonic context. The others are acoustically richer; this is compositionally
smarter.

---

## 10. Open Design Questions

1. **Write cursor drift**: Always 1.0x? Or should write cursors be allowed to
   drift (recording at variable rates)? A drifting write cursor would create
   a "warped recording" effect — interesting but complex to reason about.
   *Proposed: write cursors always 1.0x in Phase 1.*

2. **Buffer position model**: Beat-based (events sit at absolute beat positions)
   vs. event-index-based (events are in a simple array, cursor is an index).
   Beat-based is more natural for music; event-index is simpler to implement.
   *Proposed: beat-based; enables precise timing interaction with the clock.*

3. **Multiple MIDI channels**: Each cursor can output on a different MIDI channel,
   enabling the N drifting voices to be separated at the DAW or hardware level.
   Channel-per-cursor enables independent processing downstream.
   *Proposed: yes, channel is a per-cursor parameter.*

4. **Polyphony management**: When multiple cursors fire simultaneously (or near-
   simultaneously) on the same channel, note collision can occur. Options:
   - Voice-steal (cancel oldest note)
   - Allow polyphony (rely on downstream synth's polyphony)
   - Spread to multiple channels automatically
   *Proposed: allow polyphony by default; channel-per-cursor is the clean solution.*

5. **Interaction with `*tuning-ctx*`**: Scale-gravity reads the tuning context
   at event-fire time (dynamic) or at field-definition time (static)?
   Dynamic is more flexible — tuning changes during a piece affect gravity.
   *Proposed: dynamic; read `*tuning-ctx*` at event-fire time.*

6. **Flux migration**: Does `cljseq.flux` remain a separate namespace
   (backwards compatibility) or become a thin wrapper over `cljseq.memory`?
   *Proposed: keep flux as a separate namespace, implement it as a preset
   internally, expose the same public API. No breaking change.*

---

## 11. Implementation Phases

### Phase 1 — Core (Flux generalization)
- `cljseq.memory` namespace
- Buffer data structure (beat-timestamped event store)
- Single read cursor, no drift, no decay (Flux parity)
- `defmemory-field`, `memory-send!`, `memory-info`, `memory-clear!`
- Tests: buffer write/read, cursor advance, event timing

### Phase 2 — Drift
- Multiple read cursors
- Speed drift (random walk + mean-reversion)
- `:timing` pitch mode
- `:tape` pitch mode
- Tests: cursor divergence, speed clamping, duration scaling

### Phase 3 — Scale Gravity
- `:scale-gravity` pitch mode
- Integration with `*tuning-ctx*` and `cljseq.scala`
- Tests: gravity pull toward JI degrees, maqam neutral intervals

### Phase 4 — Decay and Feedback
- Probabilistic sweep decay
- Generation tracking
- Feedback with velocity attenuation
- `memory-flush!`
- Tests: half-life calculation, feedback convergence, max-generation termination

### Phase 5 — Trajectory Integration
- `memory-set!` as trajectory target
- Conductor integration
- Named presets (`:cosmos`, `:tape-echo`, `:looper`)

---

## 12. References and Influences

- **Soma Cosmos** ("Drifting Memory Station") — the conceptual origin; audio-domain
  drifting buffer with multiple orbiting playback heads
- **Mutable Instruments Clouds / Beads** — granular texture synthesizer; similar
  freeze/scatter/feedback model in audio
- **Omri Cohen VCVRack patch** — concrete Clouds-based approximation of Cosmos
  behavior; the VCVRack implementation in the studio's Windows host
- **MakeNoise ReSynthesizer** — Eurorack approximation in Case 1
- **cljseq.flux** — existing single-cursor buffer; the direct predecessor
- **cljseq.scala / *tuning-ctx*** — tuning infrastructure that scale-gravity extends
- **cljseq.trajectory** — provides the curve functions that will drive `memory-set!`
  parameters in composition arcs
