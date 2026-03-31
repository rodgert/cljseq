# Exemplar Analysis: Arturia BeatStep Pro

**Sprint**: Sprint 5
**Source**: Arturia BeatStep Pro owner's manual, firmware changelog, field research
**Confidence**: High

---

## Overview

The BeatStep Pro (2015) is Arturia's combined melodic and rhythmic step sequencer.
Its core architectural insight is the structural separation of two sequencer types —
melodic (Seq1, Seq2) and drum (Drum) — each with independent CV/gate outputs, different
step schemas, and independently configurable step counts. The per-drum-lane independent
step count is its most influential innovation: it enables true per-voice polyrhythm within
a single pattern without any external sequencer tricks. The BeatStep Pro is the
"baseline" step sequencer reference; the KeyStep Pro extends it with per-step probability
and micro-timing.

---

## Two Structural Sequencer Types

### Melodic sequencers (Seq1, Seq2)

- Each is a 1–16 step, monophonic step sequencer
- Per-step parameters: pitch, velocity, gate length (short/med/long/tie), active/rest
- Steps entered via pads in step-input mode or played in real time
- Scale quantization: configurable; chromatic or any named scale
- Each has its own MIDI channel and CV/gate/velocity-CV outputs

### Drum sequencer

- 16 drum lanes, each with 1–16 independent steps
- Per-step per-lane: active/rest, velocity (pad-pressure captured during live record)
- No per-step pitch variation — each lane has a fixed MIDI note assignment
- Gate length is per-lane, not per-step
- Each lane routes to a specific MIDI note (configurable) on a shared MIDI channel

This structural separation confirms what the KeyStep Pro also shows: **melodic steps and
drum steps are fundamentally different data types**. A melodic step is a note event with
pitch, velocity, and duration. A drum step is a presence/absence for a fixed-pitch voice
with optional velocity.

---

## Per-Drum-Lane Independent Step Count

This is the BeatStep Pro's defining architectural contribution. Within a single drum
pattern, each of the 16 lanes can have an independent step count from 1 to 16. The lanes
run against the same master clock. Polyrhythm emerges from different cycle lengths.

**Example**: a pattern with:
- Kick: 16 steps → repeats every 16 steps
- Snare: 7 steps → repeats every 7 steps
- Hi-hat: 3 steps → repeats every 3 steps
- Ride: 11 steps → repeats every 11 steps

All four lanes run in lockstep on the same clock, cycling at their own rates. The full
pattern only repeats after the LCM of all cycle lengths — potentially hundreds of beats.
This is polyrhythm as emergent complexity from simple parametric choices.

### DSL implications

This is the strongest argument for a drum-voice model where each voice is an independent
`live-loop` with its own body length, grouped under a shared ensemble:

```clojure
;; BeatStep Pro per-lane polyrhythm in cljseq terms
(defensemble :drums {}
  {:kick    (live-loop :kick    {} (play! kick-pattern)    (sleep! 16))
   :snare   (live-loop :snare   {} (play! snare-pattern)   (sleep! 7))
   :hihat   (live-loop :hihat   {} (play! hihat-pattern)   (sleep! 3))
   :ride    (live-loop :ride    {} (play! ride-pattern)    (sleep! 11))})
```

The ensemble grouping provides harmonic/timing context; the per-voice independent loops
provide the polyrhythm. No special polyrhythm API is needed — it is the natural
consequence of the independent `live-loop` model.

This confirms the design choice made in Q32 (polyrhythm phase coherence) to use
independent loop lengths rather than a grid scheduler. The BeatStep Pro is the hardware
validation of that approach.

---

## Randomize Function

The BeatStep Pro's Randomize button applies a **one-shot mutation** to the current
pattern:

- What gets randomized is selectable: pitch, velocity, gate, or combinations
- A Randomize intensity control limits the magnitude of change
- Randomization is applied to the stored pattern data immediately — it is not a
  probability that affects playback; it changes the pattern permanently (until
  manually restored or randomized again)

This is categorically different from per-step probability (KeyStep Pro):

| Mechanism | What it affects | When it operates | Reversible? |
|-----------|----------------|-----------------|-------------|
| Per-step probability | Playback (stochastic firing) | Every pass | Never changes stored data |
| BeatStep Pro Randomize | Pattern data | On button press (one-shot) | Yes (undo or re-entry) |

### DSL implications

The BeatStep Pro Randomize maps to a `mutate!` function on a pattern:

```clojure
;; Mutate the stored pattern data (one-shot, like BeatStep Pro Randomize)
(mutate! my-pattern :target :pitch :intensity 0.3)

;; vs. per-step probability (stochastic playback, no pattern change)
{:pitch/midi 64 :probability 0.5}
```

`mutate!` belongs in `cljseq.morph` as a named mutation strategy. The `:intensity`
parameter is a magnitude of change (0.0 = no change, 1.0 = fully random). This is
a cleaner model than having `defstochastic`'s DEJA VU handle mutation, since mutation
here is a deliberate live action, not a continuous stochastic process.

---

## Pattern-Coherent Simultaneous Switching

When the BeatStep Pro advances to the next chained pattern, **all three sequencers
(Seq1, Seq2, Drum) switch simultaneously** at the same beat boundary. A pattern
transition is an ensemble-level event, not a per-track event.

### DSL implications

This validates the `patch!` model from Q48: a patch switch applies to the entire
control tree at a beat boundary. The ensemble-level coherence is built into the design.

For the drum ensemble model above, pattern switching works because all loops share
the same control tree context and a `patch!` can update all their patterns atomically.

---

## CV/Gate Model

| Track | CV outputs |
|-------|-----------|
| Seq1 | Pitch (V/oct) + Gate + Velocity CV |
| Seq2 | Pitch (V/oct) + Gate + Velocity CV |
| Drum | 8 dedicated Gate outputs (per lane) |

The Drum section has no pitch CV outputs — pitches are fixed per lane. The 8 gate
outputs directly correspond to 8 drum voices, allowing direct gate triggering of
envelope generators or VCAs in a Eurorack patch without MIDI in the signal path.

### Velocity as CV (confirmed again)

Both Seq1 and Seq2 have a dedicated **Velocity CV output** (0V–5V, 0=MIDI vel 0,
5V=MIDI vel 127). This is the second device in this survey to confirm velocity as
a dual-encoding parameter. The output adapter must handle:

```clojure
;; Same velocity value, two output encodings
{:velocity 100}
;; → MIDI note-on byte: 100
;; → CV output: 100/127 * 5.0V ≈ 3.94V
```

---

## Novel Concepts for cljseq

| Concept | DSL candidate | Priority |
|---------|--------------|----------|
| Per-drum-lane independent step count | Per-voice `live-loop` in drum ensemble; already supported | Confirmed |
| One-shot pattern mutation (Randomize) | `(mutate! pattern :target :pitch :intensity 0.3)` in `cljseq.morph` | Medium |
| Velocity as dedicated CV output | Dual-encoding in output adapter layer | Already noted (KSP) |
| Simultaneous pattern switching | `patch!` on beat boundary; already in Q48 design | Confirmed |

---

## Step Map — Baseline vs. Extended

The BeatStep Pro establishes the **minimal melodic step record**:

```clojure
;; BeatStep Pro baseline step (no probability, no time-shift)
{:pitch/midi 64
 :dur/beats  1/4
 :velocity   80       ; or :default
 :gate       0.75}    ; proportion; :legato for tie
```

The KeyStep Pro **extends** this with:

```clojure
;; KeyStep Pro extended step
{:pitch/midi  64
 :dur/beats   1/4
 :velocity    80
 :gate        0.75
 :probability 0.75    ; new: per-step probability
 :time-shift  1/32    ; new: per-step micro-timing
 :pitch-rand  2}      ; new: per-step pitch randomization
```

Both are valid cljseq step maps. The optional keys default to "not set" (`nil`),
meaning the baseline behaviour matches the BeatStep Pro's simpler model.
