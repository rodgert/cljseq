# cljseq Design Sprint 3 — Summary and Handoff

**Sprint dates**: 2026-03-30
**Status**: Complete

---

## What Was Accomplished

Sprint 3 completed the question catalogue. Every open design question is now either
resolved or explicitly deferred. The project has no unresolved blocking questions;
the next sprint can focus entirely on execution.

Sprint 3 also produced:
- R&R §1.1: broader scope framing — cljseq as a platform for novel instruments
- R&R §25: Configuration Registry — all tuneable system parameters with defaults
- The `morph` abstraction (`defmorph` / `cljseq.morph`) — synth-style parameter
  mapping, fully designed
- The `fractal` sequencer rename — `cljseq.fractal`, `deffractal` — avoiding IP
  concerns while accurately describing the self-similar branching model
- A morph vocabulary library queued as a future design topic

---

## Key Decisions Made

### Control Tree Foundation

**Q47 — Persistent atom model with undo, panic, and serial number**

The control tree is a Clojure persistent map held in a single system-state atom:

```clojure
{:tree        {}    ; the control tree
 :serial      0     ; structural change counter (queryable at /cljseq/tree/serial)
 :undo-stack  []    ; bounded ring; default depth 50, configurable
 :checkpoints {}}   ; named snapshots for panic/revert
```

Undo targets: structural changes (add/remove loop/ensemble/voice, patch load, loop
body redefinition, opt-in parameter changes). Real-time parameter modulation is not
an undo target. `(undo!)` is beat-aware by default; `(undo! :now)` for emergencies.
`(panic!)` jumps to the most recent named checkpoint at the next loop boundary.
Serial number increments on structural changes only.

**Q48 — Patch system**

Patches are EDN maps (partial or full tree state) applied atomically at a beat
boundary. Beat-aware application: `:next-bar` default, `:next-phrase`, `:now`
available. No loop body code in patches — parameters and tree structure only.
Patch application is an undo target. Physical bindings are propagated via
`ctrl/send!` automatically on patch apply.

```clojure
(defpatch :verse {:ensembles/main {:chord :I :mode :dorian}})
(patch! :verse)                       ; next bar
(patch! :verse :on :next-phrase)
```

`deflive-set` / `next-patch!` / `prev-patch!` / `goto-patch!` for live set
navigation; position held in system-state atom.

### Architecture Questions

**Q5 — `sched-ahead-time` per integration target**

Per-target config map; `:bitwig-midi` generalised to `:daw-midi`:

```clojure
{:midi-hardware 100 :sc-osc 30 :vcv-rack 50 :daw-midi 50 :link 0}  ; ms
```

**Q8 — Control tree node types**

Minimal initial set: `:int :float :bool :keyword :enum :data`. `:fn` nodes are
callable but never settable via external protocols (hard security boundary).
`:pitch`, `:harmony`, `:pattern` deferred to a second pass.

**Q9 — Conflict resolution**

Fail-fast at `bind!` time; explicit `:priority` to override. Default ordering:
Clojure code (0) > Link (10) > MIDI (20) > OSC (30).

**Q10 — Write directionality**

`ctrl/set!` = logical (atom only); `ctrl/send!` = physical (atom + sidecar dispatch).
Device node value = last-sent; diverges from hardware if hardware is adjusted manually.

**Q12 — OSC control/data plane**

Primary: bundle timestamp semantics (immediate = control-plane; future = data-plane).
Fallback: two ports (57121 control, 57110 data) for controllers without bundle support.

**Q16 — Process topology**

Option A: direct JVM-to-process IPC. Routing key in `cljseq.sidecar` Clojure map.

### Low-Effort Sweep (24 questions resolved)

| Questions | Summary |
|---|---|
| Q6 | `Playable` protocol; offline = materialized map; real-time = lazy seq |
| Q13 | Per-session MCP auth; `cljseq.*` namespace whitelist; audit log |
| Q14 | SSR + HTMX; SPA deferred |
| Q17 | Sparse JVM tree; sidecar holds full CLAP param map; lazy fetch |
| Q18 | PdTarget pool ≤2; preload + atomic swap at buffer boundary |
| Q19 | Measure Link drift < 100µs empirically; single-peer fallback if needed |
| Q21 | Score wire format: preserve enharmonics; flatten ties; omit grace notes |
| Q22 | Retain Music21 subprocess; batch calls to amortize overhead |
| Q23 | cljseq native hot-path; Music21 for musicological depth |
| Q24 | No corpus bundling; user's Music21 at runtime |
| Q26 | `.scl`/`.kbm` for gamelan; user `~/.cljseq/scales/` priority |
| Q27 | `m21/register-corpus-path!` wraps `corpus.addPath()` |
| Q28 | No special colotomic API; irama atom + `at-sync!` |
| Q31 | EDN pattern maps; pre-extracted; min 50 corpus occurrences |
| Q33 | Standard nREPL; optional `cljseq-nrepl` middleware later |
| Q34 | `defonce` ring buffer outside loop; `marbles` handles it |
| Q36 | `:scale/weights` optional key on existing scale map |
| Q37 | `promise`-per-step for correlated channels; `lerp` perturbation |
| Q38 | `marbles` auto-registers at `/cljseq/marbles/<name>/` |
| Q39–Q43 | Fractal sequencer: `deffractal`, `fractal/freeze!`, `fractal/path`; reactive regen with filter predicate; ornaments as pure functions; step maps as plain maps + spec; `play!` overloaded for step maps; all three path forms supported |

### New Abstractions

**Q49 — `defmorph` / `cljseq.morph`**

Named, multi-input parameter mapping. Pure transfer functions only (patch-serializable).
Single-input and multi-input (XY pad, joystick) forms. Registers at
`/cljseq/morphs/<name>/`.

```clojure
(defmorph :xy-filter
  {:inputs  {:x [:float 0.0 1.0] :y [:float 0.0 1.0]}
   :targets [{:path [:filter/cutoff]    :fn (fn [{:keys [x]}]   (* x 8000))}
             {:path [:filter/resonance] :fn (fn [{:keys [y]}]   (* y 0.9))}
             {:path [:filter/drive]     :fn (fn [{:keys [x y]}] (* x y 4.0))}]})
```

**Q50 — P2P control plane** — Deferred. Current design (stable OSC addresses, tree
serial number, EDN serialization) does not close off the capability. Revisit once
the control tree implementation is stable.

### Naming

**`fractal`** replaces `bloom` throughout the cljseq codebase and API. The Teenage
Engineering Bloom sequencer is the design inspiration; §24 of the R&R retains the
subtitle "Bloom-Inspired" as attribution. The namespace is `cljseq.fractal`, the
form is `deffractal`.

### R&R Additions

**§1.1 — Broader scope**: cljseq as a platform for novel instruments, not only a
live coding environment. Covers hardware appliances, embedded firmware, drum
machines, effects processors, direct Eurorack CV via CLAP.

**§25 — Configuration Registry**: running table of all tuneable system parameters
with defaults, types, valid ranges, and source questions. Seeded with all values
fixed to date; to be updated as new configurable parameters are decided.

---

## Configuration Registry additions this sprint

| Parameter | Default | Source |
|---|---|---|
| `sched-ahead-time` (`:daw-midi`) | 50ms | Q5 |
| `osc-control-port` | 57121 | Q12 |
| `osc-data-port` | 57110 | Q12 |

(Full registry in R&R §25.)

---

## Future Design Topics Captured

- **Morph vocabulary library** (`cljseq.morph.vocab` or `cljseq.xform`): common
  pure transfer functions — coordinate transforms (rect↔polar, vector rotation),
  curve shaping (exponential, S-curve, power-law), signal conditioning (dead-zone,
  range remap, polarity inversion), multi-axis combinators.
- **Arturia KeyStep Pro and BeatStep Pro** exemplar analysis (Sprint 1 summary).
- **P2P control plane** (Q50) — deferred to post-control-tree-implementation sprint.

---

## Sprint 4 Plan

### Primary objective

Execute the scaffold and infrastructure tasks that make Phase 0 actionable. No
blocking questions remain; Sprint 4 is implementation preparation.

### `ITemporalValue` design spike

Implement the minimal protocol in a scratch namespace (`src/cljseq/spike/temporal.clj`)
and verify the three composition acceptance criteria from Q44:

```clojure
(clock-div 4 (lfo :rate 0.1))                       ; modulator as clock source
(ctrl/bind! [:filter/cutoff] (lfo :rate 0.25))      ; modulator to parameter
(timing/bind! (swing :amount 0.55))                 ; timing modulator to time_ns
```

Output: a design decision record in `doc/spike-itemporalvalue.md`. This unblocks
implementation of `cljseq.clock`, `cljseq.mod`, `cljseq.timing`, and control-tree
parameter binding.

### Project scaffold

Establish the directory layout and build system stubs:

```
cljseq/
  src/cljseq/          ; Clojure source
  test/cljseq/         ; Clojure tests
  cpp/
    libcljseq-rt/      ; shared C++ library
    cljseq-sidecar/    ; MIDI/CV sidecar binary
    cljseq-audio/      ; CLAP/audio binary
  python/
    cljseq_m21/        ; Music21 sidecar package
  resources/
    scales/            ; .scl/.kbm tuning files
    grooves/           ; EDN groove templates
    arpeggios/         ; EDN arpeggio patterns
  doc/
  CMakeLists.txt       ; root CMake
  pyproject.toml       ; Python package
  Makefile             ; orchestration
  project.clj          ; Clojure project
```

Deliverables: `CMakeLists.txt` stubs for `libcljseq-rt`, `cljseq-sidecar`,
`cljseq-audio`; `pyproject.toml`; root `Makefile` with `build`, `test`, `clean`;
skeleton Clojure namespaces.

### Licensing and attribution

- Replace `LICENSE` with EPL-2.0 text
- SPDX identifier headers on all source files
- `doc/licensing.md` — full rationale: EPL-2.0 for Clojure library, LGPL-2.1-or-later
  for authored C++, GPL-2.0-or-later when combined with Ableton Link, opt-in build
  flag design
- `doc/attribution.md` — formal IP attribution list for all referenced hardware,
  software, and open source projects

### Documentation strategy

- `doc/documentation-strategy.md` — toolchain decision: Codox (Clojure API docs),
  Doxygen (C++ API docs), mdBook (user guide). Conventions for extracting design
  document content into user-facing docs.

### DSL usability corpus

Begin a systematic comparison of equivalent musical concepts expressed in cljseq vs.
Sonic Pi, Overtone, and TidalCycles. Identifies syntactic sugar opportunities.
Output: `doc/dsl-usability-corpus.md` (living document, grows with DSL design).

### MIDI device research

- Survey 5–10 MIDI implementation charts from representative devices (hardware
  synths, controllers, Eurorack MIDI modules)
- Prototype `defdevice` extraction pipeline: chart → EDN device map
- Output: `doc/midi-device-research.md` and at least one example EDN device map

### Exemplar analysis (if time permits)

- Arturia KeyStep Pro manual analysis — focus: track types, per-step probability,
  swing, pattern chaining
- Arturia BeatStep Pro manual analysis — focus: melodic vs. drum tracks, CV/gate
  model, polyrhythm

---

## Repository State at Sprint 3 Close

All changes are in design documents. No implementation code exists.

The open design question catalogue is fully resolved:
- **Resolved**: Q1–Q29, Q31, Q33–Q49 (all blocking, architecture, and low-effort)
- **Deferred**: Q50 (P2P control plane — future sprint)
- **Open**: Q30 (LFO rate — empirical), Q32 (polyrhythm phase coherence)

Q30 and Q32 remain open but are not blocking; they will be resolved during
implementation when empirical data is available.

Files modified this sprint:
- `doc/open-design-questions.md` — Q47–Q50, Q5–Q10, Q12, Q16, Q6, Q13–Q14,
  Q17–Q19, Q21–Q24, Q26–Q28, Q31, Q33–Q34, Q36–Q43, Q49 resolved; Q50 deferred
- `doc/research-and-requirements.md` — §1.1 broader scope; §25 Configuration Registry
- `doc/design-sprint-1-summary.md` — morph vocabulary library future topic added
- `doc/design-sprint-3-summary.md` — this file (new)
