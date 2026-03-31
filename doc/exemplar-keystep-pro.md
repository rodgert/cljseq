# Exemplar Analysis: Arturia KeyStep Pro

**Sprint**: Sprint 5
**Source**: Arturia KeyStep Pro owner's manual, firmware changelog, field research
**Confidence**: High

---

## Overview

The KeyStep Pro (2020) is Arturia's flagship standalone sequencer/keyboard controller.
It combines four independent sequencing tracks — one polyphonic, two monophonic, one
drum — with 37 velocity-sensitive keys, a rich per-step parameter model, and independent
CV/gate/mod outputs per track. It is the contemporary consensus instrument for what a
"complete" mid-price hardware step sequencer should do, and its per-step parameter set
is the most directly actionable reference for cljseq's step map design.

---

## Track Architecture

The KeyStep Pro has four tracks with distinct capabilities:

| Track | Type | Notes |
|-------|------|-------|
| Track 1 | Melodic, polyphonic | Up to 4 notes per step; chord entry from keyboard |
| Tracks 2–3 | Melodic, monophonic | Single note per step; full per-step parameters |
| Track 4 | Drum | 8 lanes × 16 steps; per-lane MIDI note/channel routing |
| All tracks | Arpeggiator mode | Replaces step playback with arpeggiator; chord from keyboard |

### DSL implications

Three distinct step schemas emerge:
- **Monophonic step**: `{:pitch :velocity :gate :probability :time-shift :pitch-rand}`
- **Polyphonic step**: `{:pitches [:C4 :E4 :G4] :velocity :gate :probability ...}` — `:pitch` is a collection
- **Drum lane**: per-lane `{:active :velocity}` at each step position; no pitch field

The arpeggiator is a **track mode**, not a track type — it replaces the step playback
engine at runtime. A track in arp mode takes its chord input from held keys (or a latched
chord). This maps cleanly to a `live-loop` variant that applies an arp transform to an
input chord rather than replaying stored steps.

---

## Per-Step Parameter Set

This is the KeyStep Pro's most influential contribution to cljseq's design. Every step
on a melodic track carries:

| Parameter | Range | Notes |
|-----------|-------|-------|
| `:pitch` | MIDI 0–127 | Or `:rest` / `:tie` |
| `:velocity` | 0–127 | Or `:default` (inherits track default) |
| `:gate` | 0–100% of step duration | Proportional; `:legato` ties to next |
| `:probability` | 0–100% | Step fires with this probability per pass |
| `:time-shift` | ±50% of step width | Positive = late, negative = early |
| `:pitch-rand` | ±N semitones | Random pitch offset at playback; base pitch ± range |

### Implications for the cljseq step map

The cljseq step map (as designed in Q6 and the unified step map §25) should extend to
include these fields. None of them require new protocol machinery — they are data keys:

```clojure
;; Full KeyStep Pro-equivalent step
{:pitch/midi      64
 :dur/beats       1/4
 :velocity        80          ; or :default
 :gate            0.75        ; proportion 0.0–1.0; :legato for tie
 :probability     0.75        ; fires 75% of the time
 :time-shift      1/16        ; push 1/16th beat late
 :pitch-rand      2}          ; ±2 semitones random offset at playback
```

`:probability` and `:time-shift` are the two additions not currently in the design.

**`:probability` resolution**: The step-level `ITemporalValue` for swing already handles
timing perturbation at the `time_ns` level (Q46). Per-step `:probability` is simpler —
it is sampled once per step pass: `(< (rand) probability)`. No new protocol needed.

**`:time-shift` resolution**: This is a per-step timing offset, additive with the global
swing modulator. It should be expressed as a beat fraction (rational) and accumulated with
the `timing/bind!` pipeline at step playback time. A step with `:time-shift 1/16` is
equivalent to a local swing offset applied only to that step.

**Cascade of defaults**: `:velocity :default` inheriting from a track/global default is
the right behaviour. In cljseq, this maps to `(or step-velocity track-velocity global-velocity)`.
The control tree holds the track and global defaults; step-level values shadow them.

---

## Swing and Groove

- Applied globally **or** per-track (firmware-dependent; later firmware added per-track)
- Swing amount: 50% (straight) to 75% (maximum)
- Operates at 8th-note or 16th-note resolution

### DSL implications

The `Swing` `ITemporalValue` (already designed in Q46) captures the global model. The
per-track extension confirms that swing should be bindable at the loop/track level with
a global fallback:

```clojure
;; Global swing
(timing/bind! (swing :amount 0.55))

;; Per-loop swing (overrides global for this loop)
(deflive-loop :bass {}
  (timing/bind! (swing :amount 0.62))  ; bassier, heavier swing
  ...)
```

---

## CV/Gate Model

Each melodic track has three CV outputs:

| Output | Type | Source |
|--------|------|--------|
| Pitch CV | V/oct | Current step pitch |
| Gate | 0V/5V | Step active/inactive; proportional gate length |
| Mod CV | User-assignable | Velocity, gate length, LFO, or expression |

Track 1 (polyphonic) additionally has up to 4 pitch CV outputs for hardware voice
allocation — the sequencer assigns MIDI voices to specific CV jacks rather than leaving
it to the synthesizer.

### Key architectural insight: velocity as CV

Velocity is simultaneously a MIDI byte (0–127) and a continuous CV signal (the Mod CV
output). The KeyStep Pro makes this explicit with a dedicated physical jack. This
confirms that velocity is a **dual-encoding parameter**: the output adapter layer must
handle both MIDI and CV representations of the same semantic value.

In cljseq:
```clojure
;; Velocity expressed as MIDI byte
{:velocity 80}

;; Same velocity, expressed as CV voltage (0–5V scaled from 0–127)
;; Output adapter handles the conversion when routing to a CV target
```

---

## Pattern Chaining and Song Mode

- Patterns: 1–64 steps, adjustable per track independently → polyrhythm
- Chain mode: ordered list of patterns with repeat counts
- Song mode: linear arrangement of chains with scene-like sections

### DSL implications

Pattern chaining maps to `deflive-set` / `next-patch!` / `goto-patch!` (already
designed in Q48). Song mode maps to a higher-level arrangement structure not yet
designed; captured as a future design topic.

The polyrhythm model — independent step counts per track — is the DSL's native model
via independent `live-loop` bodies with different `sleep!` patterns.

---

## Novel Concepts for cljseq

| Concept | DSL candidate |
|---------|--------------|
| `:probability` key on step map | Extend step map; sample once per pass |
| `:time-shift` key on step map | Per-step timing offset; additive with swing |
| Velocity as a CV signal | Dual-encoding in output adapter layer |
| Scale quantization at output stage | `(quantize-to-scale :dorian :C)` output transform |
| Arpeggiator as a track mode | `live-loop` variant with arp transform |
| Polyphonic CV voice allocation | Future: `defensemble` + CV routing table |
| Per-track swing | Loop-level `timing/bind!` override |

---

## New Design Question Raised: Q52

**Q52 — Per-step probability and time-shift on the step map**

Should `:probability` and `:time-shift` be added to the core step map spec, or
treated as optional extensions? If core:
- They appear in the `Playable` protocol's materialized map output
- The loop/scheduler must handle them for all step types
- Drum steps gain probability and micro-timing (desirable)

If optional extensions:
- Core steps work without them (backwards compatible)
- Loops that don't need them have no overhead
- Implementation can be deferred

Recommendation: add both to the core step map spec as optional keys with `nil`
meaning "not set" (inherit defaults). A step with no `:probability` key fires
unconditionally. A step with no `:time-shift` key has zero offset.
