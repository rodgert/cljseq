# cljseq Design Sprint 4 — Summary and Handoff

**Sprint dates**: 2026-03-30
**Status**: Complete

---

## What Was Accomplished

Sprint 4 was the execution sprint: no blocking design questions remained from
Sprint 3, so the work was entirely implementation preparation. The repository
now has a buildable scaffold, a complete licensing posture, skeleton Clojure
namespaces, a verified `ITemporalValue` implementation, and a growing body of
design research documents.

Sprint 4 also resolved:
- Q51 — Native C++ dependency strategy (standalone Asio, no Boost)
- `marbles` → `stochastic` rename (same pattern as `bloom` → `fractal`)
- Naming convention for hardware-inspired abstractions captured in `doc/attribution.md`
- `bloom` macro → `deffractal` in R&R §24.8 (missed in Sprint 3)
- Attribution error corrected: "Make Noise Marbles" → "Mutable Instruments Marbles"

---

## Deliverables

### Project Scaffold

Full directory layout established and committed:

```
cljseq/
  src/cljseq/           cljseq.core, .clock, .mod, .timing, .ctrl, .loop,
                        .morph, .fractal, .m21, .stochastic, .sidecar
  src/cljseq/spike/     cljseq.spike.temporal (ITemporalValue spike)
  cpp/
    libcljseq-rt/       CMakeLists.txt, include/cljseq/temporal.h, src/ stubs
    cljseq-sidecar/     CMakeLists.txt, src/ stubs
    cljseq-audio/       CMakeLists.txt, src/ stubs
  python/cljseq_m21/    __init__.py
  resources/
    scales/             (placeholder)
    grooves/            (placeholder)
    arpeggios/          (placeholder)
    devices/            korg-minilogue-xd.edn, arturia-keystep.edn
  CMakeLists.txt        Root CMake; FetchContent for standalone Asio; CLJSEQ_ENABLE_LINK flag
  pyproject.toml        hatchling build for cljseq-m21
  Makefile              build/test/clean/docs for all components
  project.clj           Updated: real description, lein-codox config
```

### Licensing (`doc/licensing.md`, `doc/attribution.md`)

- EPL-2.0 for the Clojure library
- LGPL-2.1-or-later for authored C++ and Python
- GPL-2.0-or-later when built with Ableton Link (`-DCLJSEQ_ENABLE_LINK=ON`)
- SPDX identifier headers on all source files
- Naming convention table for hardware-inspired abstractions
- Attribution entries for all referenced hardware and software

### Documentation Strategy (`doc/documentation-strategy.md`)

Toolchain: Codox (Clojure API), Doxygen (C++ API), mdBook (user guide). Build
targets in `Makefile`.

### `ITemporalValue` Design Spike

- Spike implementation: `src/cljseq/spike/temporal.clj`
  - `MasterClock`, `ClockDiv`, `Lfo`, `Swing` — all three Q44 acceptance criteria verified
- Decision record: `doc/spike-itemporalvalue.md`
  - Records confirmed: single protocol, rational beat arithmetic, record types,
    timing modulators return `Long(ns)`, virtual time unperturbed, Option C YAGNI

### Q51 — Native C++ Dependency Strategy (RESOLVED)

No Boost. Standalone Asio (BSL-1.0, header-only) via FetchContent for the OSC
server async I/O layer. Ableton Link via FetchContent behind opt-in flag.
CLAP headers as git submodule. General rule: FetchContent + pinned tag; no
`find_package` for divergence-prone system libraries.

Decision in `doc/open-design-questions.md`.

### DSL Usability Corpus (`doc/dsl-usability-corpus.md`)

12 comparison categories across cljseq, Sonic Pi, Overtone, and TidalCycles.

Key outputs:
- **9 syntactic sugar candidates** (high → low priority):
  `play!` shorthand, `phrase!`, `every`, `one-in`, `ring`, `play-chord!`,
  `choose-from-scale`, `arp!`, `use-synth!`
- **Advantages identified**: beat-native timing, ratio arithmetic, continuous
  `ctrl/bind!` modulation, `defensemble` harmonic coupling, checkpoint/panic,
  `deflive-set` patch navigation, DEJA VU locking
- **Gaps noted**: TidalCycles mini-notation, sample playback, pattern transform
  operator library

### MIDI Device Research (`doc/midi-device-research.md`)

6 devices surveyed; 4 archetypes defined:

| Archetype | Example | Key finding |
|-----------|---------|------------|
| Dense parameter synth | Korg Minilogue XD | Fixed CC map; NRPN for extended resolution |
| Drum machine | Elektron Digitakt | User-assignable CC slots; note map for voice triggers |
| MIDI/CV converter | Expert Sleepers FH-2 | No fixed CC map; user-configured routing in editor |
| Controller (send-only) | Arturia KeyStep | `:role :controller`; `:cljseq/bind-sources` orientation |

EDN device maps:
- `resources/devices/korg-minilogue-xd.edn` — full synth map (CCs, NRPN stub, discrete values)
- `resources/devices/arturia-keystep.edn` — controller map with bind-sources hints

### `marbles` → `stochastic` Rename

Following the same pattern as `bloom` → `fractal`:
- `src/cljseq/stochastic.clj`, `cljseq.stochastic`, `defstochastic`, `stochastic-loops`
- `/cljseq/stochastic/<name>/` in the control tree
- R&R §23: "Stochastic Generative Primitives (Marbles-Inspired)"
- Mutable Instruments Marbles and Moog Labyrinth added to `doc/attribution.md`
- Naming convention table added to `doc/attribution.md`

---

## Design Decisions Made This Sprint

| Decision | Outcome |
|----------|---------|
| Q51 — Native C++ dependency strategy | No Boost; standalone Asio; FetchContent policy |
| `ITemporalValue` spike confirmation | Single protocol; record types; rational beats; timing returns `Long(ns)` |
| `marbles` → `stochastic` rename | `defstochastic` / `cljseq.stochastic` / "(Marbles-Inspired)" |
| Naming convention for hardware-inspired abstractions | Generic name + "(X-Inspired)" subtitle; attribution.md table |

---

## Future Design Topics Captured This Sprint

- **Packaged releases** — AppImage / Homebrew formula / Windows installer; CPack
  integration; interaction with FetchContent dependency model. Deferred to a
  future sprint once C++ implementation is stable.
- **Moog Labyrinth exemplar analysis** — separate play/write head, CORRUPT
  mutation, BIT FLIP CV; will inform a future sequencer archetype name.
- **DSL sugar layer** — 9 candidates from the usability corpus; design as a
  focused sprint item or inline with Phase 0 implementation.
- **`defdevice` macro design** — based on EDN prototype; template + user
  configuration layer for the FH-2 / configurable archetype.
- **`cljseq.random` namespace** — probability distributions for `defstochastic`;
  prerequisite for Phase 6i implementation.

---

## Sprint 5 Plan

### Carried from Sprint 4

- **Arturia KeyStep Pro exemplar analysis** — track types, per-step probability,
  swing, pattern chaining, CV/gate model
- **Arturia BeatStep Pro exemplar analysis** — melodic vs. drum tracks, CV/gate
  model, polyrhythm

### New Sprint 5 Items

**Moog Labyrinth exemplar analysis**
Survey the Labyrinth manual for: separate play/write head model, CORRUPT mutation
(bit-level sequence corruption), BIT FLIP CV output, EG TRIG MIX, and scale mode
catalog. Output: `doc/exemplar-labyrinth.md`. Name the cljseq abstraction it will
inspire.

**DSL sugar layer design**
Resolve the 9 syntactic sugar candidates from the usability corpus as formal design
decisions. Determine: which belong in the core namespace, which are standalone sugar
macros, which require runtime support. Add resolved forms to R&R as a new §26 (DSL
Sugar Layer) and to the open-design-questions catalogue.

**`defdevice` macro design**
Design the Clojure macro based on the EDN prototype. Decide:
- Load-time vs. eval-time CC registration
- How discrete `:values` breakpoints map to `ctrl/set!` semantic arguments
- NRPN encoding in `ctrl/send!`
- Controller (`:role :controller`) binding to `ctrl/bind!` sources
Output: design decision in open-design-questions, updated R&R §17 (device model),
and a skeleton `src/cljseq/device.clj`.

**`cljseq.random` namespace design**
Design the probability distribution layer required by `defstochastic` (Phase 6i).
Core primitives: `distribution`, `weighted-scale`, `learn-scale-weights`,
`progressive-quantize`. Output: design notes added to R&R §23 (currently describes
the API intent), open-design-questions entry, skeleton `src/cljseq/random.clj`.

**Phase 0 implementation readiness review**
Review all open design questions and implementation plan prerequisites.
Confirm which Phase 0 tasks (bootstrap, nREPL, basic virtual time, master clock)
can begin. Identify any remaining design work that would block a "hello clock" REPL
session. Output: `doc/phase0-readiness.md`.
