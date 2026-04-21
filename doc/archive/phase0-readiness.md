# Phase 0 Implementation Readiness Review

**Sprint**: Sprint 5
**Date**: 2026-03-30
**Status**: Review complete — Phase 0 is cleared to begin

---

## Purpose

Review all open design questions and implementation plan prerequisites to confirm
which Phase 0 tasks can begin, and identify any remaining design work that would
block a "hello clock" REPL session: the simplest possible cljseq session that
ticks a master clock, runs a live loop, plays a note, and uses virtual time.

---

## What a "Hello Clock" Session Requires

```clojure
;; The minimum viable cljseq session
(require '[cljseq.core :refer :all])

(deflive-loop :hello {}
  (play! :C4)
  (sleep! 1))
```

For this to work, the following must be in place:

1. **Virtual time** — `*virtual-time*` dynamic var; `sleep!` advances it
2. **Master clock** — a clock that ticks at the configured BPM; drives loop wakeup
3. **`deflive-loop`** — macro that starts a thread, runs the body, respects virtual time
4. **`play!` (minimal)** — emits a MIDI note-on/note-off pair at the scheduled time
5. **MIDI output** — at minimum, a stub that prints to stdout in lieu of real MIDI
6. **System init** — `(start!)` boots the atom, sets up the clock, opens MIDI

---

## Design Question Status Review

### Blocking questions for Phase 0

All questions that are "Yes — needed before Phase 0/1 impl":

| Q | Status | Notes |
|---|--------|-------|
| Q1 — Virtual time model | **RESOLVED** | `^:dynamic *virtual-time*` rational; `sleep!` uses `set!` |
| Q2/Q3 — `sync!` semantics / sync-window | **RESOLVED** | `EventHistory`; system-wide constant |
| Q4 — `ctrl/set!` vs `ctrl/send!` | **RESOLVED** | Logical vs. physical write |
| Q5 — `sched-ahead-time` per target | **RESOLVED** | Per-target config map |
| Q11 — `live-loop` / `deflive-loop` | **RESOLVED** | `live-loop` = alias with auto-name |
| Q44 — `ITemporalValue` protocol | **RESOLVED** | Spike verified; all three criteria |
| Q47 — Control tree atom model | **RESOLVED** | Single atom; undo-stack; serial |
| Q48 — Patch system | **RESOLVED** | EDN patches; beat-aware; `ctrl/send!` propagation |

All Phase 0 blocking questions are resolved. ✓

### Open questions that are NOT blocking Phase 0

These are open but do not need to be resolved before starting:

| Q | Why not blocking |
|---|-----------------|
| Q30 — LFO rate | Empirical; resolved during `cljseq.mod` implementation |
| Q32 — Polyrhythm phase coherence | Resolved during multi-loop testing |
| Q52 — Per-step `:probability`/`:time-shift` | Optional step map fields; additive |
| Q53 — `defflux` concurrency model | `cljseq.flux` is a later phase |
| Q54 — Scale as `ITemporalValue` | Output stage; later phase |

---

## Implementation Plan Phase 0 Prerequisites

From `doc/implementation-plan.md`, Phase 0 tasks are:

### Phase 0a — Bootstrap

- [ ] `cljseq.core/start!` — initialises the system-state atom and starts the clock
- [ ] `cljseq.core/stop!` — graceful shutdown
- [ ] System-state atom shape confirmed (Q47): `{:tree {} :serial 0 :undo-stack [] :checkpoints {}}`
- [ ] nREPL server starts on `start!` (Q33 — standard nREPL; no new middleware)

**Prerequisites met**: Q47 resolved. nREPL is standard (no design work needed).

### Phase 0b — Virtual Time

- [ ] `*virtual-time*` dynamic var (rational)
- [ ] `sleep!` — advances `*virtual-time*`; calls `Thread/sleep` for wall-clock delay
- [ ] `(now)` — returns current `*virtual-time*`
- [ ] `sync!` — aligns to beat boundary within sync-window (Q2/Q3 resolved)

**Prerequisites met**: Q1, Q2, Q3 resolved. No open questions.

### Phase 0c — Master Clock

- [ ] `cljseq.clock/master-clock` — `ITemporalValue` returning 1.0; ticks on integer beats
- [ ] BPM → beat duration in ms
- [ ] Clock drives the live-loop wakeup pipeline
- [ ] `ITemporalValue` protocol defined (from spike: `sample` / `next-edge`)

**Prerequisites met**: Q44 resolved; spike implementation exists in `cljseq.spike.temporal`.

### Phase 0d — Live Loop

- [ ] `deflive-loop` macro — starts a thread; binds `*virtual-time*`; runs body; loops
- [ ] Loop registered in control tree at `/cljseq/loops/<name>/`
- [ ] Re-evaluation of `deflive-loop` replaces the running loop at next iteration
- [ ] `live-loop` alias (Q11 resolved)

**Prerequisites met**: Q1, Q11, Q47 resolved. No open questions.

### Phase 0e — Minimal MIDI Output

- [ ] `play!` accepting a step map `{:pitch/midi n :dur/beats d}`
- [ ] Converts beat duration to wall-clock ms using current BPM
- [ ] Emits MIDI note-on at scheduled `time_ns`; note-off after gate duration
- [ ] Initial target: print to stdout (no real MIDI hardware required for Phase 0)
- [ ] Real MIDI via sidecar IPC deferred to Phase 1

**Prerequisites met**: Q6 (step map format) resolved. MIDI output can start as a stub.

---

## Namespace Readiness

All Phase 0 namespaces have skeleton files with docstrings referencing their design decisions:

| Namespace | Skeleton | Design questions resolved |
|-----------|---------|--------------------------|
| `cljseq.core` | ✓ | Q47, Q48 |
| `cljseq.clock` | ✓ | Q44 (spike), Q1 |
| `cljseq.loop` | ✓ | Q11, Q1, Q2, Q3 |
| `cljseq.mod` | ✓ | Q44, Q30 (empirical) |
| `cljseq.timing` | ✓ | Q46, Q35 |
| `cljseq.ctrl` | ✓ | Q4, Q8, Q9, Q10, Q47 |
| `cljseq.sidecar` | ✓ | Q16 |
| `cljseq.spike.temporal` | ✓ | Q44 (verified) |

---

## Phase 0 Milestone Definition

**Definition of done for Phase 0**: evaluate the following at an nREPL session and
observe a MIDI note (or stdout stub) firing at the expected beat interval:

```clojure
(require '[cljseq.core :refer :all])

;; Boot
(start!)

;; Define a loop
(deflive-loop :hello {}
  (play! {:pitch/midi 60 :dur/beats 1/2})
  (sleep! 1))

;; Confirm it is ticking
(ctrl/get [:cljseq/loops :hello :tick-count])
;; => some positive integer, incrementing

;; Modify it without stopping
(deflive-loop :hello {}
  (play! {:pitch/midi 67 :dur/beats 1/4})
  (sleep! 1/2))

;; Confirm the loop updated
(ctrl/get [:cljseq/loops :hello :tick-count])
;; => still incrementing; note changed on next iteration

;; Stop
(stop!)
```

---

## Recommended Phase 0 Implementation Order

1. `cljseq.clock` — `ITemporalValue` protocol + `MasterClock` (copy from spike, promote)
2. `cljseq.core` — `start!`/`stop!`, system-state atom, BPM config
3. `cljseq.loop` — `deflive-loop` macro, virtual time binding, loop thread
4. `cljseq.clock` — wire `MasterClock` to loop wakeup pipeline
5. `cljseq.core` — `play!` stub (stdout output), step map → MIDI conversion
6. Integration test: "hello clock" session milestone above

This order minimises stubs — each step can be verified independently before the next
depends on it.

---

## What Phase 0 Does NOT Include

- Real MIDI hardware output (Phase 1 — requires sidecar IPC)
- Control tree full implementation (Phase 1 — stubs only in Phase 0)
- `sync!` full implementation (Phase 1 — basic form in Phase 0)
- `ctrl/bind!` (Phase 1)
- Any stochastic, fractal, morph, or flux abstractions (later phases)
- OSC server (Phase 2)
- Music21 integration (Phase 3+)

Phase 0 is deliberately minimal: clock + virtual time + live loop + note output.
Everything else builds on this foundation.
