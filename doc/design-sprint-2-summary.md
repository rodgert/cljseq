# cljseq Design Sprint 2 — Summary and Handoff

**Sprint dates**: 2026-03-30
**Status**: Complete

---

## What Was Accomplished

Sprint 2 had one primary objective: resolve all blocking design questions before
implementation begins. Every blocking question from the Sprint 1 handoff was resolved,
plus two new questions were identified, analyzed, and resolved within the sprint.

The sprint also produced:
- A refined understanding of the timing model through the strum/articulation analysis
- A unified framing of timing modulation (swing, humanize, groove, quantize) as
  `ITemporalValue` instances routed to `time_ns`
- The `ensemble` abstraction fully designed, including role/articulation separation
- Two new exemplar sequencers (Arturia KeyStep Pro and BeatStep Pro) queued for
  future analysis
- Reservation of `*in-loop-context*` as a system-wide loop-context hint

---

## Key Decisions Made

### Virtual Time and Scheduling

**Q1 — `*virtual-time*` is a plain rational, not an atom**
`sleep!` advances it via `clojure.core/set!` (per-thread dynamic var binding).
No inter-thread reads of virtual time; debug dashboard uses `EventHistory`
as a side-channel. Prevents false sharing and enforces per-thread isolation.

**Q2 — `live-loop` redefinition: full-iteration boundary by default**
New code takes effect at the start of the next full iteration. `:hot-swap true`
opt-in for immediate-`sleep!`-boundary swap. `(checkpoint!)` for explicit
mid-body swap points. Follow-up confirmed: `sync-window` remains a system-wide
constant — per-call override was considered and rejected (inheriting stale virtual
time silently drops events at the scheduler).

**Q3 — `sync!` uses time-windowed `EventHistory`**
`sync-window` = `sched-ahead-time` + one beat. System-wide constant; not
overridable per call. "At-most-one-beat-late" semantics: threads within one beat
of a cue phase-align; genuinely late threads wait for the next cue.

**Q35 — Jitter is applied to `time_ns` only, never to `*virtual-time*`**
Virtual time is the clean beat spine. IPC event timestamp =
`nominal_time_ns + jitter_offset_ns` drawn per-event. Preserves `sync!`
coordination while delivering audible timing humanization.

### API Naming

**Q4 — Musical state API routes through the control tree**
`ctrl/set!` and `ctrl/get` are the canonical names. No separate `at!`/`at`
primitive — all musical state is the control tree. Ergonomic aliases may be
added later as thin wrappers if REPL experience warrants it.

**Q11 — `live-loop` is an alias for `deflive-loop`**
`(live-loop :bass ...)` expands to `(deflive-loop "/cljseq/loops/bass" {} ...)`.
Always registered in the control tree; no declared parameters unless `deflive-loop`
is used directly. One mental model, two ergonomic levels.

### C++ Layer

**Q15 — `libcljseq-rt` boundary**
Shared: IPC framing, OSC codec, `SynthTarget` interface, `LinkEngine`, Asio helpers,
lock-free queues, logging. Binary-local: `MidiTarget`, `ClapTarget`, RtMidi, RtAudio,
CLAP loader, libpd. Key invariant: `libcljseq-rt` compiles with no synthesis or I/O
backend headers. Future layering deferred to post-Phase-0 refactoring.

### Music21 Integration

**Q20 — Never deref `m21/` promises inside a `live-loop`**
`m21/eventually!` added to the initial API: fires the RPC, swaps an atom when resolved;
the loop observes the atom. Enforcement (throw when `*in-loop-context*` is true) is
deferred; `*in-loop-context*` is reserved now. The var may be used by other blocking
subsystems (slow I/O, long control-tree queries) as a general loop-context hint.

### Pitch and Tuning

**Q25 — Microtonal pitch: `:pitch/cents` is an optional field**
Absent = 0 cents offset. Keeps the 12-TET common case clean. MIDI targets use
cents-offset (monophonic) or MPE (polyphonic); CLAP targets use `CLAP_EXT_TUNING`
floating-point Hz-per-key table built from `.scl` files.

### Scheduling Primitives

**Q29 — `param-loop` splits by mode**
Discrete rhythm specs expand to `live-loop`. `:rhythm :continuous` runs on a
dedicated wall-clock `LockSupport/parkNanos` thread. Continuous-mode redefinition
swaps at next tick (phase-continuous, ~1ms). Both modes register identically in
the control tree.

### Unified Temporal-Value Abstraction

**Q44 — Single `ITemporalValue` protocol, Option A**
```clojure
(defprotocol ITemporalValue
  (sample    [v beat])   ; value at this beat position
  (next-edge [v beat]))  ; beat of next 0→1 transition, or nil if continuous
```
All time-varying signals implement this one protocol. Role and routing are
determined at the binding site, not in the type. Conceptually identical to
VCV Rack (untyped float cables) and SuperCollider (UGen rate annotation, not type).
Option C (thin wrappers for scheduling metadata) deferred by YAGNI — VCV Rack's
production codebase validates that untyped signals scale without performance
annotations.

**Composition acceptance criteria** (must work without coercion):
```clojure
(clock-div 4 (lfo :rate 0.1))                       ; modulator as clock source
(ctrl/bind! [:filter/cutoff] (lfo :rate 0.25))      ; modulator to parameter
(timing/bind! (swing :amount 0.55))                 ; timing modulator to time_ns
```

### Timing Modulation (new — Q46)

**Swing, humanize, groove templates, and quantize are `ITemporalValue` instances**
routed to `time_ns` via `timing/bind!`. Output type is plain `Long` (nanoseconds);
no protocol extension needed.

```clojure
(timing/bind! (swing :amount 0.55))               ; all events in scope
(timing/bind! :voice/chords (humanize :amount 5)) ; named voice only
(timing/bind! (groove/load "resources/grooves/mpc60-swing66.edn"))
```

Quantization uses arbitrary rational grid fractions:
```clojure
(quantize :grid 1/16)   ; nearest 16th note
(quantize :grid 1/12)   ; nearest 8th-note triplet
```

Link ordering: timing offsets applied *after* `timeAtBeat`, never before.
Phase alignment across Link peers is preserved.

### Voice Organization

**Q45 — `defensemble` / `ensemble`**
Named grouping of N voices with shared harmonic context and optional per-voice
semantic role annotations. Voices register under `/cljseq/ensembles/<name>/voices/<name>/`.

**Role/articulation separation** (refined during sprint):
Role (`:drone`, `:pad`, `:motif`) determines *harmonic content policy* — what
`(current-chord)` returns inside the voice. Articulation (`:strum`, etc.) determines
*temporal delivery* — how those notes are spread across time. These are orthogonal.

```clojure
(defensemble :morning-raga
  {:key :D :mode :bhairav}
  (voice :drone  {:role :drone}                        (drone-pattern ...))
  (voice :chords {:role :pad
                  :articulation (strum :dir :desc
                                       :rate 1/32)}    (pad-pattern ...))
  (voice :melody {:role :motif}                        (motif-pattern ...))
  (voice :tabla  {}                                    (tabla-pattern ...)))
```

Strum direction API: `:asc` (pitch-ascending, low→high) and `:desc`
(pitch-descending, high→low). Guitar players will recognise these as down-strum
and up-strum; the API uses pitch-order terms to avoid instrument-specific
connotations. Strum belongs in the ornament vocabulary (§24.5) and uses Q46's
per-note `time_ns` offset mechanism for delivery.

---

## New Questions Added

| Q | Topic | Status |
|---|---|---|
| Q46 | Timing modulation: swing/humanize/groove/quantize as time-dimension `ITemporalValue` | Resolved in Sprint 2 |

---

## New Work Captured for Future Sprints

**Arturia KeyStep Pro and BeatStep Pro** — both owned by the project author; queued
as exemplar hardware sequencers for a future analysis sprint. Topics: semantic vs.
generic track types, per-step probability, polyrhythm / independent track lengths,
chord/scale lock, CV/gate output model, swing, pattern chaining. See
`design-sprint-1-summary.md` § "Arturia KeyStep Pro and BeatStep Pro (future sprint)".

---

## Resolved Questions Summary

All 13 blocking questions are resolved (12 from Sprint 1 handoff + Q46 new):

| Q | Summary |
|---|---|
| Q1 | `*virtual-time*` = plain rational; `sleep!` uses `clojure.core/set!` |
| Q2 | Full-iteration boundary default; `:hot-swap true` opt-in; `(checkpoint!)` |
| Q3 | Time-windowed `EventHistory`; system-wide `sync-window`; at-most-one-beat-late |
| Q4 | Musical state = control tree; `ctrl/set!` / `ctrl/get` |
| Q11 | `live-loop` = `deflive-loop` alias with auto-path and empty param map |
| Q15 | `libcljseq-rt`: interfaces + infrastructure only; no synthesis backends |
| Q20 | `m21/eventually!` in initial API; `*in-loop-context*` reserved |
| Q25 | `:pitch/cents` optional; MPE for polyphony; `CLAP_EXT_TUNING` for CLAP |
| Q29 | Step-mode → `live-loop` expansion; continuous → wall-clock thread |
| Q35 | Jitter at `time_ns` only; virtual time clean |
| Q44 | Single `ITemporalValue` protocol; routing at binding site; Option C deferred |
| Q45 | `defensemble`; role ≠ articulation; `:asc`/`:desc` strum direction |
| Q46 | Timing modulators are `ITemporalValue → Long (ns)`; `timing/bind!` routes them |

---

## Sprint 3 Plan

### Primary objective

Resolve the new blocking questions (Q47, Q48) and architecture-tier questions
(Q5, Q8, Q9, Q10, Q12, Q16), sweep the low-effort question batch, execute the
`ITemporalValue` design spike, and begin the project scaffold.

### New blocking questions (added post-Sprint 2)

These were identified during Sprint 2 close and must be resolved before control
tree implementation:

| Q | Topic | Dependency |
|---|---|---|
| Q47 | Control tree as persistent atom: undo, panic, serial number | Precedes Q8, Q9, Q10 |
| Q48 | Patch system: named tree states, beat-aware application | Builds on Q47 |

### Blocking architecture questions (decision pass)

These have clear recommended resolutions in the ODQ and need only a confirm/amend.
Note: Q8, Q9, Q10 should be revisited in light of Q47's atom model.

| Q | Topic | Recommended resolution |
|---|---|---|
| Q5 | `sched-ahead-time` per-target | Per-target config map: `{:midi 100 :sc-osc 30 :vcv 50 :link 0}` |
| Q8 | Control tree node type system | Minimal: `:int :float :bool :keyword :enum :data`; defer `:pitch :harmony :pattern` |
| Q9 | Multi-source conflict resolution | Fail-fast at `bind!`; explicit priority override |
| Q10 | `mod-route` write directionality | `ctrl/set!` = logical; `ctrl/send!` = physical |
| Q12 | OSC control/data plane | Bundle timestamp semantics; two-port fallback |
| Q16 | Process topology | Option A: direct JVM connections to each process |

### Additional Sprint 3 questions

| Q | Topic | Priority |
|---|---|---|
| Q49 | Synth-style morphs: named input→parameter-set mappings | Medium — surface concept in DSL |
| Q50 | P2P control plane | Low — ensure design stays open; no decisions needed yet |

### Low-effort question sweep

24 questions with clear directions ready for a rapid decision pass:
Q6, Q13, Q14, Q17–Q19, Q21–Q24, Q26–Q28, Q31, Q33–Q34, Q36–Q43.

### Design spike: `ITemporalValue`

Implement the minimal protocol in a scratch namespace and verify the three composition
acceptance criteria from Q44. Output: a design decision record (not production code).
This unblocks implementation of `cljseq.clock`, `cljseq.mod`, and control-tree
parameter binding.

### Project scaffold

Establish the directory layout and build system stubs to make Phase 0 actionable:
- Top-level directory structure per the Sprint 1 project structure decision
- `CMakeLists.txt` stubs for `libcljseq-rt`, `cljseq-sidecar`, `cljseq-audio`
- `pyproject.toml` for the Music21 sidecar Python package
- Root `Makefile` with `build`, `test`, `clean` targets

### Licensing and attribution

- Replace `LICENSE` with EPL-2.0 text
- SPDX headers on all source files
- `doc/licensing.md` — full license rationale including Ableton Link GPL-2.0 analysis
  and opt-in build flag design
- `doc/attribution.md` — formal IP attribution list

### Documentation strategy

- `doc/documentation-strategy.md` — Codox (Clojure API), Doxygen (C++),
  mdBook (user guide) toolchain plan
- Establish conventions for extracting design document content into user-facing docs

### DSL usability corpus

Build a systematic comparison of equivalent musical concepts expressed in cljseq vs.
Sonic Pi, Overtone, and TidalCycles. Identifies areas where syntactic sugar would
improve the live coding experience. Grows alongside DSL design decisions.

### MIDI device research

- Survey 5–10 device MIDI implementation charts
- Prototype `defdevice` extraction pipeline from chart → EDN device map

### Exemplar analysis (if time permits)

- Begin Arturia KeyStep Pro and BeatStep Pro manual analysis
- Focus on: track type model, per-step probability, swing, pattern chaining

---

## Repository State at Sprint 2 Close

All changes are in design documents only. No implementation code exists yet.
The project is at Phase 0 readiness: all blocking architectural decisions are made;
the next sprint can begin executing the scaffold and spike tasks.

Files modified this sprint:
- `doc/open-design-questions.md` — Q1–Q4, Q11, Q15, Q20, Q25, Q29, Q35, Q44, Q45
  marked RESOLVED; Q46 added and resolved
- `doc/design-sprint-1-summary.md` — Arturia KeyStep Pro / BeatStep Pro exemplar
  section added
- `doc/design-sprint-2-summary.md` — this file (new)
