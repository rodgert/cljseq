# cljseq — Open Design Decisions

Active tracker for unresolved design questions. Resolved items have been moved to
`doc/archive/open-design-questions.md` (67 questions, all resolved except those here).

Each entry carries the original Q-number for cross-reference with the archive.

---

## LFO and polyrhythm

### Q30 — Continuous LFO scheduling: update rate and sidecar handoff

**Blocking**: `param-loop` LFO implementation.

**Question**: At what rate should the JVM poll and dispatch LFO values to the
sidecar for MIDI CC and OSC targets? For CLAP targets, the sidecar can compute
one value per audio buffer — no JVM polling needed.

**Recommendation** (not yet implemented):
- Default: 100 Hz (10 ms intervals), configurable per `param-loop` via `:rate hz`.
- CLAP targets: compute one value per buffer via IPC type `0x76` automation message
  with sample offset. No high-frequency JVM polling.
- MIDI CC / OSC: 100 Hz from JVM LFO thread, batched to sidecar.

---

### Q32 — Polyrhythmic phase coherence across restarts and tempo changes

**Blocking**: Nothing currently; quality issue for multi-loop live sets.

**Question**: When a `live-loop` is redefined mid-run, it can restart with an
arbitrary phase offset relative to other loops, breaking intentional polyrhythmic
relationships. Under Link, LFO loops using wall-clock rates drift when BPM changes.

**Recommendation** (partially implemented — `:restart-on-bar` exists in `deflive-loop`):
1. `:restart-on-bar` restarts at the next bar boundary — use this for phase-critical
   loops.
2. LFO rate in Hz should always derive from BPM (`freq = beats/period × BPM/60`) so
   rate self-corrects when BPM changes.
3. Full solution: global phase anchor from Link beat-1 timestamp; all period-based
   loops compute phase as `(current-beat % period) / period`.

---

## Timing and MIDI clock

### Q56 — Time signature API: does it affect `sleep!`?

**Blocking**: Bar-level `sync!`; bar-level phasor in `cljseq.loop`.

**Recommendation** (not yet implemented): `sleep!` and all beat arithmetic always
refers to quarter notes (MIDI convention). Time signature affects only bar boundaries,
bar-level `sync!`, and display. Avoids compound-time BPM ambiguity.

---

### Q57 — PPQN for MIDI Clock output: 24 fixed or configurable?

**Blocking**: MIDI Clock output implementation in `cljseq-sidecar`.

**Recommendation** (not yet implemented): Always emit at 24 PPQN for live MIDI Clock
(MIDI spec; all hardware expects it). Higher PPQN (96, 960) for SMF export only.

---

### Q58 — MTC frame rate: default and drop-frame handling

**Blocking**: MTC implementation (Phase 2).

**Recommendation** (not yet implemented): Default 25 fps (1920 samples/frame at
48 kHz — clean math). Drop-frame correction behind explicit config
`{:frame-rate 29.97 :drop-frame true}`. 30 fps non-drop as secondary North American
default.

---

## Plugin graph (future sprint)

Questions Q61–Q65 are all pre-design for `cljseq-rack` — the hosted CLAP/VST3 plugin
graph. None block current work. Grouped here for when that sprint begins.

### Q61 — Plugin graph DSL: declarative `defrack` vs. imperative API

**Recommendation**: Declarative `defrack` macro as primary form (`:nodes` + `:edges`
EDN map); imperative `add-node!` / `connect!` for interactive REPL patching.
Declarative form compiles to imperative calls internally.

### Q62 — Rackspace switching: crossfade and audio continuity

How long is the crossfade window? How are concurrent switch requests serialised? How
does plugin delay compensation change during transition?

### Q63 — Plugin graph topology: DAG only or feedback loops?

DAG-only is simpler; feedback requires automatic delay-buffer insertion and PDC
accounting. Start with DAG-only and revisit.

### Q64 — VST3 support: same sprint as CLAP or deferred?

VST3 has larger catalog than CLAP. `SynthTarget` abstraction isolates it — VST3 is a
new subclass. Decision: prioritise after `defrack` CLAP baseline is stable.

### Q65 — Live graph modification (hot-patching while audio runs)

Requires double-buffer or copy-on-write for the processing graph applied between
audio callbacks. Coordinate with Q62 crossfade design.

---

## DAWProject I/O (future sprint)

### Q66 — DAWProject export scope

Phase A: tempo map + captured MIDI clips only. Phase B: automation curves + CLAP
plugin states. Decide trigger: explicit `export!` call vs. automatic on `stop!`.

### Q67 — DAWProject import granularity

Selective import (tempo map + MIDI clips) is lower risk for Phase 1. Full project
reconstruction (generating `defrack` from track hierarchy) depends on Q61 first.

---

## Deferred

### Q50 — P2P control plane: peer discovery and remote tree composition

**Status**: Deferred; `cljseq.peer` implements single-peer HTTP polling. Full P2P
(mDNS discovery, distributed ctrl-tree merge, conductor pattern) deferred until the
ctrl tree is stable and a multi-musician use case is concrete.

**Design is not foreclosed**: OSC addresses are stable, tree serial number enables
stale-snapshot detection, EDN serialisation allows peer state transmission. When the
time comes: mDNS/Bonjour for discovery; discovered peers appear as device subtrees at
`[:cljseq :peers <peer-id>]`.
