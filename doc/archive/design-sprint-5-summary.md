# cljseq Design Sprint 5 — Summary and Handoff

**Sprint dates**: 2026-03-30
**Status**: Complete

---

## What Was Accomplished

Sprint 5 completed the exemplar analysis programme, named the third hardware-inspired
abstraction (`defflux`), resolved the DSL sugar layer, designed `defdevice`, and
produced the Phase 0 implementation readiness review. The project is now cleared to
begin implementation.

---

## Deliverables

### Exemplar Analyses

**Arturia KeyStep Pro** (`doc/exemplar-keystep-pro.md`)
- Per-step parameter set established: `:probability`, `:time-shift`, `:pitch-rand`
  added to step map as optional keys (Q52 resolved)
- Velocity as dual-encoding parameter (MIDI byte + CV voltage) confirmed
- Per-track swing bindable via loop-level `timing/bind!`
- Scale quantization as a separable output-stage transform

**Arturia BeatStep Pro** (`doc/exemplar-beatstep-pro.md`)
- Per-drum-lane independent step count → polyrhythm via independent `live-loop`s confirmed
- One-shot mutation (Randomize) → `(mutate! pattern :target :pitch :intensity n)` in `cljseq.morph`
- Baseline step map established; KeyStep Pro extensions are additive
- Pattern-coherent simultaneous switching → `patch!` on beat boundary confirmed

**Moog Labyrinth** (`doc/exemplar-labyrinth.md`)
- Abstraction named: **`defflux`** / `cljseq.flux`
- R&R §26 title: "Flux Sequence Architecture (Labyrinth-Inspired)"
- Play/write head separation → circular step buffer, independent read/write processes
- CORRUPT → continuous bit-level mutation; `ITemporalValue` intensity control
- BIT FLIP → `flux/on-corrupt!` side-channel callback
- CV-controlled scale selection → scale as `ITemporalValue` (Q54)
- Concurrency model → vector of atoms, one per step position (Q53)

### New Abstractions Named

| Abstraction | Namespace | Attribution |
|-------------|-----------|-------------|
| `defflux` | `cljseq.flux` | Moog Labyrinth (Labyrinth-Inspired) |

`doc/attribution.md` naming convention table complete for all three hardware-inspired
abstractions: fractal, stochastic, flux.

### Design Questions Resolved

| Q | Decision |
|---|---------|
| Q52 | Per-step `:probability` and `:time-shift` — optional step map keys; nil = unconditional/zero |
| Q53 | `defflux` concurrency — vector of atoms (one per step) |
| Q54 | Scale as `ITemporalValue` — accepted; sampled per step output |
| Q55 | `defdevice` design — eval-time registration; resolver map for semantic addresses; NRPN via sidecar |

### DSL Sugar Layer (`doc/research-and-requirements.md` §27)

All 9 candidates from the usability corpus resolved:

| Form | Priority | Namespace |
|------|----------|-----------|
| `(play! :C4)` / `(play! :C4 1/2)` | High | `cljseq.dsl` |
| `(phrase! [...] :dur 1/4)` | High | `cljseq.dsl` |
| `(every 4 :beats form)` | High | `cljseq.dsl` |
| `(one-in 4 form)` | Medium | `cljseq.dsl` |
| `(ring :C4 :E4 :G4)` / `(tick! r)` | Medium | `cljseq.dsl` |
| `(play-chord! :C4 :major)` | Medium | `cljseq.dsl` |
| `(choose-from-scale :C :major)` | Medium | `cljseq.dsl` |
| `(arp! :C4 :major :up 1/16)` | Medium | `cljseq.dsl` |
| `(use-synth! :piano)` / `(with-synth ...)` | Low | `cljseq.dsl` |

Dynamic vars added to Configuration Registry: `*default-dur*` (default `1/4`),
`*default-synth*` (default `nil`).

### New Skeleton Namespaces

| Namespace | Purpose |
|-----------|---------|
| `cljseq.dsl` | DSL sugar layer; all 9 forms stubbed |
| `cljseq.device` | `defdevice` macro; EDN map loading; Q55 decisions inline |
| `cljseq.random` | Probability distributions for `defstochastic`; Phase 6i prerequisite |
| `cljseq.flux` | `defflux` sequencer archetype; Q53/Q54 resolved |

R&R §26 (Flux Sequence Architecture) and §27 (DSL Sugar Layer) appended.

### Phase 0 Readiness Review (`doc/phase0-readiness.md`)

**Result: Phase 0 is cleared to begin.**

All blocking design questions for the "hello clock" milestone are resolved.
Recommended implementation order:
1. `cljseq.clock` — `ITemporalValue` protocol + `MasterClock` (from spike)
2. `cljseq.core` — `start!`/`stop!`, system-state atom, BPM
3. `cljseq.loop` — `deflive-loop`, virtual time binding, loop thread
4. Wire clock → loop wakeup pipeline
5. `play!` stub (stdout) + step map → MIDI conversion
6. "Hello clock" milestone: `deflive-loop` ticking at nREPL

---

## Sprint 6 Plan

### Primary objective: Phase 0 implementation

Deliver the "hello clock" milestone defined in `doc/phase0-readiness.md`.

**Phase 0a — Bootstrap**
- `cljseq.core/start!` and `stop!`
- System-state atom initialisation
- BPM configuration in Configuration Registry

**Phase 0b — Virtual Time**
- `*virtual-time*` dynamic var
- `sleep!`, `(now)`
- `sync!` (basic beat-boundary alignment)

**Phase 0c — Master Clock**
- Promote `ITemporalValue` protocol from spike to `cljseq.clock`
- `MasterClock` implementation
- Clock-driven loop wakeup

**Phase 0d — Live Loop**
- `deflive-loop` macro
- Loop thread management; re-evaluation semantics
- Control tree registration at `/cljseq/loops/<name>/`

**Phase 0e — Minimal Play**
- `play!` accepting a step map (Phase 0 target: stdout stub)
- Beat-duration → wall-clock ms conversion
- `play!` shorthand sugar forms from `cljseq.dsl`

**Phase 0 integration test**
- "Hello clock" session as defined in `doc/phase0-readiness.md`
- Verify re-evaluation of `deflive-loop` takes effect on the next iteration

### Secondary: R&R §26 `defflux` detail

Write out the full `defflux` design (§26) in the R&R with:
- Complete API reference
- CORRUPT mutation algorithm
- `on-corrupt!` callback contract
- Interaction with `defstochastic` (§23) and `deffractal` (§24)
