# cljseq Design Sprint 1 — Summary

**Sprint dates**: March 2026
**Participants**: [@rodgert](https://github.com/rodgert), Claude Sonnet 4.6
**Outcome**: Complete research and requirements document (24 sections, ~5,200 lines),
implementation plan, and open design questions catalog. No implementation yet.

---

## What Was Accomplished

This sprint was a pure **design and architecture sprint**. The deliverables are
all in `doc/`:

| Document | Purpose |
|---|---|
| `research-and-requirements.md` | Full architecture specification (§1–§24) |
| `implementation-plan.md` | Phased build plan with C++ and Clojure deliverables |
| `open-design-questions.md` | 43 catalogued questions with stakes and exploration paths |
| `intro.md` | High-level project overview (pre-existing) |

---

## Key Architectural Decisions Made

### 1. Virtual Time Model (§3)
Thread-local rational beat counter (`*virtual-time*`, `*beat*`, `*bpm*`) as the
temporal spine. `sleep!` as a temporal barrier using `LockSupport/parkNanos`.
`cue!`/`sync!` for beat-aligned inter-thread coordination. **Not** `core.async`.

### 2. Native C++ Sidecar (§3.5)
`cljseq-sidecar`: a `SCHED_FIFO`/mach-thread-policy C++ process handles all
real-time MIDI and OSC dispatch. JVM communicates via Unix domain socket with a
binary IPC protocol (message types 0x10–0x5F). GC pauses affect only the
*generating* thread; the sidecar dispatches events at wall-clock precision
regardless of JVM GC.

### 3. `libcljseq-rt` Shared Library (§3.5, §19)
C++ static library shared by `cljseq-sidecar` and `cljseq-audio`: IPC framing,
`SynthTarget` abstract interface, `LinkEngine`, Asio utilities, OSC codec,
lock-free queues. Concrete implementations (RtMidi, CLAP, libpd) live in their
respective binaries, not in the shared library.

### 4. `cljseq-audio` Process (§19)
Separate process for CLAP/VST3 plugin hosting and libpd. Uses RtAudio; audio
callback thread runs at platform-native RT priority (CoreAudio / JACK / ASIO).
Events dispatched with sample-accurate timing: ns-stamped IPC events converted to
sample offsets within the audio buffer. CLAP first; VST3 in a later phase.

### 5. Ableton Link as First-Class Requirement (§15)
Direct C++ Link SDK integration in the sidecar (no Carabiner process hop).
`captureAudioSessionState()` called on the RT dispatch thread. When Link is active:
- `sleep!` queries `link/time-at-beat` instead of local BPM arithmetic
- BPM changes propagate implicitly on the next `sleep!` call
- `live-loop` launch phase-quantized to the next quantum boundary

### 6. Unified Control Model: OSC Address Tree (§16)
All system state is a hierarchical namespace of value nodes (atoms), method nodes
(IFns), and container nodes. Protocol adapters (MIDI CC, OSC, HTTP, Link, MCP) all
write the same tree. `ctrl/bind!` maps MIDI events to tree paths; OSC addresses are
tree paths natively. `ctrl/snapshot`/`restore!` = preset management.

### 7. Control-Plane Server: Ring/Netty/aleph (§17)
OSC and HTTP reduce to the same three tree ops (`ctrl/get`, `ctrl/set!`,
`ctrl/call!`). Unified Ring middleware stack. `aleph` provides HTTP + WebSocket +
UDP OSC on a shared Netty event loop. Content negotiation: JSON/EDN/HTML/OSC-namespace.
Every tree node is a URL; the running system is browser-navigable.

### 8. MCP Integration (§18)
Control tree maps directly to MCP: value nodes → Resources, method nodes → Tools.
`repl/eval` tool (authorization-gated, per-session). MCP server on HTTP+SSE at
`/mcp` alongside the existing REST server. Three client tiers:
nREPL (developer), HTTP/OSC/WS (hardware), MCP (AI assistant).

### 9. CLAP Synthesis Target (§19)
`SynthTarget` abstract class. CLAP first target: Surge XT as reference plugin
(open source, comprehensive CLAP, native Scala `.scl`/`.kbm` tuning via
`CLAP_EXT_TUNING`). Polyphonic microtonality without pitch-bend channel overhead.
Up to 600 Surge parameters as lazy tree nodes.

### 10. Music21 Python Sidecar (§20)
Separate asyncio Python process. JSON-RPC 2.0 over Unix domain socket. Handles
all musicological analysis (key detection, roman numeral, voice leading, serial
operations, corpus access, notation export). Rule: **performance path = cljseq
native; analysis path = Music21**. Optional; degrades gracefully when absent.
`cljseq.m21` functions return Clojure promises — never deref inside a `live-loop`.

### 11. World Music Scale and Rhythm Library (§21)
Bundled Scala `.scl` files for Arabic maqamat, Javanese gamelan (pelog/slendro,
per-ensemble), Indian ragas, Turkish makam. EDN rhythm files for iqa'at, African
timeline patterns, Afro-Cuban clave. Non-Western rhythms are first-class specs
for `live-loop` and `param-loop`.

### 12. Rhythmic Voice Abstraction (§22)
`param-loop` as the counterpart to `live-loop` for non-note parameters.
Both are instances of a unified `{:period :rhythm :value-source :target}` model.
LFO primitives (sine/triangle/sawtooth/square/s&h/envelope) defined in beats
→ phase-coherent with Link tempo changes. Step-mode `param-loop` expands to
`live-loop`; continuous LFO runs on a separate 100Hz wall-clock thread.
Arpeggiation library: 11 built-in named patterns + corpus-derived from
Bach/Mozart/Beethoven.

### 13. Marbles-Inspired Generative Primitives (§23)
`stochastic-rhythm` (T section: complementary-Bernoulli, independent-Bernoulli,
divider, drums modes). `stochastic-sequence` (X section: spread/bias/steps
continuum). DEJA VU ring-buffer memory (0=random, 1=locked loop). Correlated
multi-channel generation. `marbles` macro auto-registers all parameters in the
control tree. DEJA VU ring buffers persist across `live-loop` redefinitions via
`defonce`.

### 14. Bloom-Inspired Sequence Architecture (§24)
Trunk + branch tree: up to 8 sequences (1 trunk + 7 branches). Branch
transformations: reverse, inverse (mirror around root), transpose, mutate,
randomize — all pure functions. 128 traversal paths. 14 scale-aware ornament types.
Per-step Mod output (Velocity/Shapes/Envelope/Smooth). 8 playback order modes
(Forward/Reverse/Pendulum/Random/Converge/Diverge/C&D/PageJump). Unified step map
(§24.10) as the canonical data type consumed and produced by all sequencing
primitives.

---

## Open Questions for Sprint 2

Questions are grouped by whether they **block implementation** of the next
phase and by topic area.

### Blocking: Must Resolve Before Coding Starts

| # | Question | Stakes | Recommended Resolution |
|---|---|---|---|
| **Q4** | `set!`/`get!` naming conflict with `clojure.core` special form | Compile-time breakage | Rename to `(at! path val)` / `(at path)` or `(ctrl/set!)` |
| **Q1** | `*virtual-time*` as atom vs. plain rational | Thread-safety correctness | Remove atom; use plain rational + `set!` |
| **Q2** | `live-loop` redefinition semantics: full-iteration vs. `sleep!` boundary | Core semantic | Default = full iteration boundary; opt-in `:hot-swap` flag |
| **Q3** | `sync!` late-cue edge case: stale cue window | Phase drift in performance | `sync-window` = `sched-ahead-time` + 1-beat grace; search `EventHistory` |
| **Q11** | `deflive-loop` vs. `live-loop`: API unification | Duplicated API surface | `live-loop` = `deflive-loop` with empty param map (Option A) |
| **Q15** | `libcljseq-rt` boundary: what goes in the shared library | Coupling between C++ processes | Abstract interfaces only; no RtMidi/RtAudio/CLAP/libpd in shared lib |
| **Q29** | `param-loop` vs. `live-loop` unification | API and scheduler design | Step-mode = `live-loop` expansion; continuous LFO = separate 100Hz thread |
| **Q35** | Jitter and virtual-time coherence | Corrupts `sync!` coordination if wrong | Model A: perturb IPC `time_ns` only; `*virtual-time*` stays clean |
| **Q20** | Music21 async: safe usage from `live-loop` | Breaks virtual-time if deref'd inside loop | Document constraint + `m21/eventually!` helper |
| **Q25** | Microtonal pitch representation for maqamat | Correctness for §21 implementation | Cents-offset on MIDI note; MPE for polyphonic; CLAP tuning table for plugins |
| **Q44** | Unified temporal-value abstraction: do clocks and modulators share a protocol? | Highest-leverage Phase 1 decision — wrong answer means API friction across clock, mod, scheduler, and control-tree binding | Design spike: implement minimal `ITemporalValue` protocol; express 5–6 clock and modulator primitives; verify composition holds without coercion |

### Architecture: Needed Before §16–§19 Implementation

| # | Question | Stakes | Direction |
|---|---|---|---|
| **Q8** | Control tree node type system | Needed before any tree implementation | Minimal set: `:int :float :bool :keyword :enum :data`; defer `:pitch :harmony :pattern` |
| **Q9** | Multi-source conflict resolution (Link + MIDI both control BPM) | BPM fight in live performance | Fail-fast at `bind!` time; explicit priority override |
| **Q10** | `mod-route` write directionality: logical vs. physical write | Conflates state update with hardware send | `ctrl/set!` = logical; `ctrl/send!` = physical; device nodes store last-sent value |
| **Q12** | OSC control/data plane distinction | Misrouting data-plane events adds JVM latency | Bundle timestamp semantics (immediate = control; future-timestamped = data); fallback: two ports |
| **Q16** | Process topology: JVM↔N processes or sidecar-as-router | Latency and complexity | Option A (direct JVM connections to each process); recommended |
| **Q5** | `sched-ahead-time` per-target | Wrong value causes late/early delivery | Per-target config map: `{:midi-hardware 100 :sc-osc 30 :vcv-rack 50 :link 0}` |

### Algorithm and Model: Needed Before §22–§24 Implementation

| # | Question | Stakes | Direction |
|---|---|---|---|
| **Q30** | Continuous LFO scheduling rate | Smoothness vs. IPC load | Default 100Hz (10ms); CLAP gets per-buffer sample-accurate value instead |
| **Q32** | Polyrhythm phase coherence across loop restarts | Phase drift after live redefinition | `:restart-on-bar` option; LFO rate derived from BPM, self-corrects on tempo change |
| **Q36** | Weighted scale degrees: extend `IntervalNetwork` or separate type | Model consistency | Extension: add optional `:scale/weights` key to existing scale map |
| **Q37** | Correlated sequences: shared random draw model | Algorithm correctness in concurrent setting | `promise`-per-step for simultaneous channels; approximate for independently-clocked |
| **Q39** | Bloom branch regeneration: explicit call vs. reactive watch | Surprise regeneration during live editing | Reactive watch with filter predicate; `bloom/freeze!` for multi-step edits |

### Low-Effort / Already Have Direction

These questions have clear recommendations in the ODQ and can be resolved in a
single decision at the start of Sprint 2:

| # | Question | Resolution |
|---|---|---|
| Q6 | Stream consumption model | `Playable` protocol returning lazy seq; `realize` for offline |
| Q13 | MCP `repl/eval` authorization | Per-session; namespace whitelist; transcript log |
| Q14 | HTML rendering | SSR + HTMX; evaluate SPA only if needed |
| Q17 | CLAP parameter eager vs. lazy registration | Lazy (sidecar holds full map; tree is sparse projection) |
| Q18 | Pd patch hot-swap audio continuity | Pre-load + atomic swap at buffer boundary |
| Q19 | `cljseq-audio` Link peer count | Measure drift empirically; consider single-peer (sidecar only) |
| Q21 | Score serialization canonical form | Define rules in Python `_note_to_json`: enharmonic = preserve spelling; tied = merge; grace = omit |
| Q22 | In-process Music21 | Retain subprocess; batch calls to amortize IPC overhead |
| Q23 | cljseq vs. Music21 type overlap | Division-of-labor table in §20.8 is the answer |
| Q24 | Music21 corpus licensing | No bundling; delegate to user's Music21 installation |
| Q26 | Gamelan per-ensemble tuning | `.scl` per ensemble; user `~/.cljseq/scales/` takes priority |
| Q27 | Non-Western corpus | `m21/register-corpus-path!` calling `corpus.addPath()` |
| Q28 | Colotomic layering API | No special API; `irama` atom + `at-sync!` for ratio changes |
| Q31 | Arpeggiation library format | EDN with `:order :rhythm :dur :source`; ship pre-extracted |
| Q33 | nREPL integration scope | Resolved: standard nREPL; optional `cljseq-nrepl` middleware later |
| Q34 | DEJA VU ring buffer across redefines | `defonce` outside loop body; `marbles` macro does this automatically |
| Q38 | `marbles` context auto-registration | Auto-register at `/cljseq/marbles/<name>/`; matches `deflive-loop` behavior |
| Q40 | Ornament timing and virtual time | Ornaments = pure functions returning sub-step sequences; caller iterates with `sleep!` |
| Q41 | Unified step map: plain map vs. record | Plain map + `clojure.spec.alpha` for validation |
| Q42 | `play-bloom-step!` vs. `play!` overload | Overload `play!` to accept step map as second argument |
| Q43 | Path navigation: index/keyword/vector | Support all three; Bloom-compatible integers generated by firmware algorithm |

---

## R&R Traceability Matrix

Maps each R&R section to the open design questions and Sprint 2 tasks that depend on it. Keeps the relationship between architectural intent and actionable work explicit.

| R&R Section | Topic | Blocking Q's | Architecture Q's | Sprint 2 Tasks |
|---|---|---|---|---|
| §3 | Virtual time model | Q1, Q2, Q3, Q35, **Q44** | Q5, Q30 | Design spike: `ITemporalValue` protocol |
| §4 | Musical abstractions (pitch, scale, chord) | Q25 | Q36 | Maqam cents-offset; handpan scale catalog |
| §5 | Sequencing DSL | Q4, Q11, Q29 | Q10 | Naming decisions; `param-loop` unification |
| §6 | MIDI control binding | — | Q5 | MIDI device map survey; `defdevice` schema |
| §7 | Scala / EDO tuning | Q25 | Q36 | `.scl` corpus; EDN scale index |
| §8 | VCV Rack integration | — | — | Out of scope Phase 0–1 |
| §9 | Stream / Score model | Q6 | — | `Playable` protocol definition |
| §10 | Analysis primitives | — | — | Out of scope Phase 0–1 |
| §11 | Corpus | Q24, Q27 | Q31 | EDN corpus index; `VirtualCorpus` path registration |
| §12 | DSL design principles | Q4, Q11 | — | Naming conventions; `doc/conventions.md` |
| §13 | Open questions (original) | Q1–Q43 | — | ODQ full resolution pass |
| §15 | Ableton Link | — | — | Q7 resolved (post-Phase-1 implementation); opt-in build analysis |
| §16 | Unified control tree | Q29 | Q8, Q9, Q10, Q12, Q16 | `defdevice` schema; control-tree device layer spec |
| §17 | Ring/Netty OSC server | — | Q12, Q14 | Two-port vs. timestamp semantics decision |
| §18 | MCP integration | Q13 | — | MCP resource server prototype with `doc/*.md` corpus |
| §19 | CLAP plugin hosting | Q15, Q20 | Q17, Q18, Q19 | `libcljseq-rt` boundary spec; `cljseq-audio` stub |
| §20 | Music21 sidecar | Q20 | Q21, Q22, Q23 | `m21/eventually!` helper; serialization rules |
| §21 | World music / microtonality | Q25 | Q26, Q27, Q28 | Maqam representation; gamelan `.scl` files; colotomic API |
| §22 | Rhythmic modulation / param-loop | Q29, Q35 | Q30 | `param-loop` unification design decision |
| §23 | Marbles-inspired primitives | Q35 | Q30, Q32, Q34, Q37 | Jitter model; correlated sequence algorithm |
| §24 | Bloom / unified step map | Q2, Q3 | Q39, Q40, Q41, Q42, Q43 | Step map spec; branch regeneration policy |
| Cross-cutting | Licensing | — | — | EPL-2.0; LGPL/GPL sidecar; SPDX headers; REUSE compliance |
| Cross-cutting | Project structure | — | — | Directory layout; CMake skeleton; pyproject.toml; Makefile |
| Cross-cutting | Documentation | — | — | Codox; Doxygen; mdBook; `doc/conventions.md` |
| Cross-cutting | IP attribution | — | — | `ATTRIBUTION.md`; `README.md` disclaimer; design doc audit |

---

## Additional Design Research for Sprint 2

Two hardware sequencer paradigms were not covered in Sprint 1 and should be
analyzed before Sprint 2 closes out the design phase:

### Tracker-Style Sequencing (Polyend Tracker)
Tracker sequencers originated in the Amiga/demo-scene era (ProTracker, FastTracker)
and have seen a hardware renaissance with instruments like the Polyend Tracker.
Key concepts to analyze and map onto the cljseq design:

- **Pattern grid**: fixed rows × columns; rows = steps, columns = tracks/voices
- **Note, instrument, volume, effect columns per step** — more data per step than
  a classic step sequencer; relationship to the unified step map (§24.10)
- **Effect commands** (portamento, arpeggio, vibrato, note cut/delay) as per-step
  modifiers — overlap with ornaments (§24.5) and gate/ratchet fields
- **Song mode**: patterns chained into a linear arrangement; relationship to the
  Stream/Score model (§9)
- **Sample/instrument assignment per step** — how does this map to multi-target
  routing in the control tree (§16.8)?
- **Tracker file formats** (`.mod`, `.xm`, `.it`) as potential import/export targets
  alongside MusicXML/MIDI
- **What trackers do uniquely well** that cljseq could absorb: dense per-step
  articulation data, sample-accurate effect scheduling, the "note + instrument +
  volume + FX" column model as a richer step type

### Elektron-Style Sequencing (Digitakt / Digitone)
Elektron instruments (Digitakt, Digitone, Octatrack, Analog Four) define one of the
most influential hardware sequencer paradigms in contemporary electronic music.
Key concepts to analyze:

- **Parameter locks (p-locks)**: per-step parameter overrides — any synthesis
  parameter can be locked to a specific value for a single step, then returns to
  the track default. Relationship to the per-step Mod output (§24.6) and
  `param-loop` (§22.2); p-locks may motivate a richer step map
- **Trig conditions**: conditional step firing based on probability, pattern count
  (every Nth bar), previous trig state (A then B), fill mode, and neighbor trigs.
  Relationship to `stochastic-rhythm` (§23.3) and the gate model
- **Micro-timing / micro-shift**: per-step timing offset of ±23 steps (at 24 PPQN)
  — precise humanization within a step grid. Relationship to jitter (§23.3) and
  the virtual time model
- **Fill mode**: a second pattern layer that activates on a held button, enabling
  live variation without pattern switching
- **Song mode / chain mode**: patterns chained with repetition counts
- **Track scaling**: each track can have a different step count (1–64), enabling
  polyrhythm by default without explicit coprime period setup
- **Sound locks**: per-step instrument/preset change (Digitone). Relationship to
  program change and CLAP preset (§19.6)
- **What Elektron does uniquely well**: p-locks are the most expressive per-step
  parameter system in hardware sequencing — they may motivate extending the unified
  step map (§24.10) with a general `:step/param-locks` field that overrides any
  control tree path for the duration of a single step

### Moog Labyrinth (2024)
The Labyrinth is a semi-modular parallel generative analog synthesizer centered on
a dual 8-step sequencer. Source: [official manual](https://cdn.inmusicbrands.com/Moog/Labyrinth/LABYRINTH-MANUAL%201.pdf).
Key sequencing concepts to analyze:

- **Voltage-first, quantize-last architecture**: each Bit stores a raw random
  voltage (±5V). The `SEQ CV RANGE` knob attenuates that range before it reaches
  the internal quantizer, which maps it to the nearest note in the selected scale.
  This is distinct from storing notes directly — the musical pitch is an emergent
  property of (voltage × range attenuation × scale quantization). Relationship to
  `stochastic-sequence` (§23.4): the `:spread`/`:bias` and scale quantization stage
  are structurally similar, but the Labyrinth makes the pipeline explicit as
  hardware-visible stages. May motivate a `:raw-value` field in the generative step
  model alongside `:pitch/midi`

- **Play head and write head as separate cursors**: the sequencer has two
  independently-positioned cursors advancing together by default, but separable via
  `BIT SHIFT + ADVANCE`. The write head can lead or lag the play head, creating
  feedback-style self-modification where the CORRUPT function mutates a future
  position while the current position plays. This is a novel read-ahead / write-
  ahead model not present in Marbles or Bloom — and not currently in cljseq's
  generative model

- **BIT FLIP via CV**: the `BIT FLIP` patch bay input (per sequencer) flips the
  current write-head bit when driven high — enabling external CV to programmatically
  gate steps on/off. Relationship to `param-loop` (§22.2) driving gate state; and
  to the Elektron trig conditions concept. Could motivate a `:gate/flip-source` key
  in the unified step map or a `(bind-gate-flip! source path)` control tree helper

- **CORRUPT: tiered pitch-then-gate mutation**:
  - 0 → 12 o'clock: increasing probability (~0–25%) that each Bit's *voltage* is
    replaced with a new random value as the write head passes over it. Gate pattern
    preserved — only pitches drift
  - 12 o'clock → max: additionally begins flipping *gate states* randomly (0–50%
    probability). Both pitch and rhythm mutate simultaneously
  - This tiered behavior is more nuanced than a single probability parameter — it
    enforces a "pitch mutates before rhythm does" ordering that has direct musical
    consequences. May motivate a two-axis `:corrupt/pitch` and `:corrupt/gate`
    parameterization on `stochastic-rhythm` / `stochastic-sequence`, distinct from
    the current `:spread`/`:bias` axes

- **Independent sequence lengths → emergent polyrhythm**: SEQ1 and SEQ2 each
  have independently settable lengths (1–8). With different lengths, playheads
  drift out of phase producing polyrhythm as a natural consequence, not an explicit
  design. The LCM of the two lengths defines the full cycle. Mirrors the Elektron
  track-scaling concept; in cljseq this already works via coprime `live-loop`
  periods (§22.4) but the Labyrinth confirms it as a primary interaction model

- **CHAIN SEQ → 16-step unified sequence + independent play head offset**: when
  chained, SEQ1 and SEQ2 share steps as a single 16-step sequence, but their play
  heads can be offset independently via `BIT SHIFT 2` (in chain mode). Combined
  with independent CV RANGE attenuators, you get two 16-step sequences sharing
  bit data but with different pitch ranges and phase offsets. This is structurally
  related to the Bloom trunk with two voices traversing at different offsets (§24.3)

- **BUFFER: single-slot save + optional recall on RESET**: BUFFER saves current
  bit states, quantization, write head offsets, and lengths to one memory slot.
  A global setting makes the `RESET IN` jack optionally recall the buffer on
  rising edge — so an external clock reset simultaneously resets playback *and*
  restores a saved state. This is a hardware-native "snapshot + restore on bar
  boundary" operation; direct parallel to `ctrl/snapshot`/`restore!` (§16.6)
  triggered by a Link quantum boundary or `at-sync!`

- **MIDI Note On = root transposition**: when quantizer is active, an incoming
  MIDI note transposes the root of *both* sequencers' scales simultaneously.
  A keyboard or external sequencer drives the harmonic context while Labyrinth
  drives the melodic content. Mirrors `ctrl/set! "/cljseq/global/key" new-key`
  from the control tree; confirms the key/root-transposition-as-realtime-control
  model is hardware-validated

- **EG TRIG MIX: velocity-weighted trigger blending**: a crossfader between SEQ1
  and SEQ2 trigger streams sent to the envelope generators. At full left: only SEQ1
  triggers fire at full velocity. At 12 o'clock: both fire equally. In between:
  one stream fires at lower velocity, creating automatic accent patterns from the
  rhythmic interplay of two sequences. This is a simple but powerful dynamic
  shaping model — not currently in cljseq. Could motivate a `:trig-mix` parameter
  on `play!` or a velocity-blend node in the control tree

- **16 quantization modes**: Unquantized, Chromatic, Major, Pentatonic, Melodic
  Minor, Harmonic Minor, Diminished 6th, Whole Tone, Hirajoshi Pentatonic,
  7sus4 (1 4 5 b7), Major 7th (1 3 5 7), Major 13th (1 3 5 6 7 9),
  Minor 7th (1 b3 5 b7), Minor 11th (1 b3 4 5 b7 9), Hang Drum, Quads (minor 3rds).
  A useful reference set for the cljseq scale library; Hirajoshi, Hang Drum, and
  Quads are not in the current world music scale list (§21.8)

- **What the Labyrinth does uniquely well**: the separated play/write head model
  with CORRUPT operating on the write head is the most distinctive concept — it
  creates a self-modifying feedback loop where the sequence mutates itself ahead
  of playback. This has no direct analog in Marbles or Bloom and is worth modeling
  explicitly in cljseq as a `write-head-offset` parameter on `stochastic-sequence`
  that allows the mutation function to operate N steps ahead of the current play
  position

### Hang Drum / Handpan Tuning Systems (future sprint)

The Moog Labyrinth's "Hang Drum Tuning" quantization mode prompted this. Hang
drums (original Hang by PANArt) and the broader handpan family are acoustic
idiophones with a distinctive tuning philosophy: each instrument is built around
a single root + a carefully chosen set of overtone-resonant scale tones, often
drawn from non-Western modal or pentatonic systems (Kurd, Integral, Pygmy,
Celtic Minor, etc.). The result is an instrument that is harmonically
self-consistent — almost every combination of struck tones sounds musical.

**Why this matters for cljseq**:
- Handpan scales are small (7–9 tones), root-relative, and modal — a natural fit
  for the Scala tuning pipeline and the `scale-constrained` quantization layer
- The "any combination sounds good" property is a generative composition
  primitive: random walks and stochastic selection within a handpan scale produce
  pleasing output without additional harmonic filtering
- PANArt and the handpan community have published scale catalogs; these could
  seed a `handpan-scales` namespace alongside the existing world-music corpus
- Cross-reference with Labyrinth mode #15 (Hang Drum Tuning) and the Hirajoshi
  Pentatonic mode (#14) — both appear in hardware sequencer scale lists,
  confirming demand in the live-performance community

**Suggested sprint scope**: survey 10–15 common handpan scales, add them to the
Scala/EDO tuning corpus, verify they render correctly through the
`scale-constrained` quantizer, and add a `handpan-walk` generative primitive
that does a stochastic nearest-neighbor walk within the scale.

### Eurorack Modulator Vocabulary (future sprint)

The Eurorack maxim "you can never have enough VCAs" captures something
important: the most expressive modular patches are built by routing modulators
into other modulators, not just into sound sources. cljseq already contemplates
LFOs that can target any parameter in the control tree (§16), but there is a
richer vocabulary of modulation primitives worth formalising, grounded in the
module designs below.

#### Make Noise Maths (analog computer / function generator)

Maths is an analog computer whose four channels each implement the same
primitive: a **function generator** that can operate as a triggered envelope, a
free-running LFO, or a slew limiter depending entirely on patching — no mode
switch required. Key properties sourced from the official Make Noise manual:

- **Channels 1 & 4**: full function generators. A Trigger input fires a 0→10V
  rise/fall envelope. A Signal input applies slew (lag/portamento) to any
  incoming voltage. Cycle mode makes the circuit self-oscillate as an LFO.
  End-Of-Rise (CH1) and End-Of-Cycle (CH4) outputs emit gate pulses at the
  corresponding inflection points, enabling cascaded or clock-divided events.
- **Channels 2 & 3**: attenuverters. They scale, amplify, and invert any input;
  with nothing patched they generate DC offsets (±10V on CH2, ±5V on CH3).
- **Vari-Response**: a single knob sweeps the function curve continuously from
  logarithmic through linear to exponential to hyper-exponential — covering East
  Coast portamento and West Coast pluck envelopes with one control.
- **BOTH CV input**: exponential bipolar input that scales total function time;
  positive voltages speed up, negative slow down. Independent of Rise/Fall CVs.
- **SUM / Inverted SUM / OR bus**: all four channels are normalized into a
  summing bus via their attenuverters. SUM adds all signals algebraically;
  Inverted SUM negates the sum; OR applies analog logic OR (take the highest
  voltage). Patching a cable to a Variable Output removes that channel from the
  bus — a "normalling" convention that makes Maths an implicit four-input mixer.
- **Time range**: 1 kHz (audio rate) to 25 minutes per cycle — the same circuit
  is simultaneously useful as an envelope, an LFO, a very slow ramp, and a VCO.

#### Make Noise PoliMATHS (8-channel polyphonic function generator)

PoliMATHS takes the Maths function generator concept and makes it polyphonic:
one set of controls governs eight independent output channels, with two novel
mechanisms for distributing variation across channels:

- **8 channels, shared controls**: Rise, Fall, Curve, Strength (bipolar
  amplitude), oscillation Rate and Shape are set globally but distributed
  per-channel via Spread and Modulation Dissemination
- **Oscillation within envelope**: each channel's output is a rise/fall envelope
  *plus* a variable-shape oscillation (sawtooth→triangle→ramp) whose amplitude
  is scaled by the envelope — vibrato-within-note or tremolo-within-pluck shapes
  natively, without external LFO patching
- **Spread**: a bipolar control that distributes parameter variation across
  channels by position — channels further left or right receive more Spread
  influence depending on the control's direction; a single knob spreads rise
  times, strengths, or rates across all eight channels in a smooth gradient
- **Modulation Dissemination**: when a CV is patched to a Spreadable parameter
  input, each channel *samples and holds* that CV at its moment of Activation
  rather than tracking it continuously — polyphonic sample-and-hold, freezing
  the modulation value per-voice at note-on time
- **Activation modes**: Channel Index (CV-addressed), Round Robin (sequential
  with variable step size), Parallel (simultaneous with clock-divided
  relationships between channels set by Span)
- **Accumulate input**: holds pending activations until a gate releases them —
  a global strum-delay primitive
- **Channel Index Output**: emits a CV indicating the currently active channel,
  enabling inter-module routing of voice identity

#### Mutable Instruments Blinds (quad VC-polarizer / four-quadrant multiplier)

Blinds (open-sourced by Émilie Gillet / Mutable Instruments) is a quad
four-quadrant multiplier. The key distinction from a standard VCA: a negative
control voltage inverts rather than mutes the signal. This single capability
unlocks a range of modulation topologies:

- **Polarizer**: each channel multiplies its signal by a CV in the ±1 range.
  Positive CV = attenuation/amplification in phase; negative CV = inversion.
  The A knob sets manual gain+polarity offset; the B attenuverter scales
  modulation depth.
- **Ring modulator**: when audio-rate signals feed both the signal and modulation
  inputs, the channel performs ring modulation; the A knob becomes a carrier
  rejection control (0 = pure ring mod; full CW = unmodulated signal bleeds in)
- **VC attenuverter / offset**: with no signal patched, the +5V normalization
  makes the channel generate a ±5V CV controlled by the modulation input — a
  voltage-controlled offset source
- **Cascading sum**: unpatched outputs automatically feed forward into the next
  channel; if all four outputs are left unpatched except the last, output 4
  contains the sum of all four processed signals — an implicit mixer requiring
  no additional patching

#### cljseq implications: a modulator vocabulary

These three modules together define a vocabulary of modulation primitives that
maps naturally onto Clojure functions operating on time-varying values:

| Hardware primitive | cljseq DSL concept |
|---|---|
| Function generator (triggered) | `(envelope :rise t :fall t :curve :exp)` → time-indexed fn |
| Function generator (cycle mode) | `(lfo :rate hz :shape :exp :phase 0)` |
| Slew limiter | `(slew signal :rise t :fall t)` — applies lag to any param |
| Attenuverter | `(atv signal :gain g :offset v)` — scale, invert, shift |
| Polarizer / 4-quadrant multiply | `(polarize signal modulator)` — modulator sets gain+polarity |
| Ring modulation | `(ring-mod carrier modulator)` — audio-rate special case |
| SUM bus | `(mod-sum & modulators)` — algebraic mix of N sources |
| OR bus | `(mod-or & modulators)` — analog logic max of N sources |
| End-of-rise / end-of-cycle | Callback/event fired at envelope inflection points |
| Vari-Response curve | `:curve` keyword: `:log :linear :exp :hyper-exp` |
| Spread across voices | `(spread base :amount a :direction :right)` — distributes over voice index |
| Modulation Dissemination | `(sample-at-onset modulator)` — per-voice S&H at note-on |
| Activation modes | Round-robin, parallel, CV-addressed — voice allocation strategies |
| Oscillation-within-envelope | `(env-osc env :rate r :shape s :depth d)` |

The most important design insight is that **all of these are composable
functions on values that vary over virtual time** — which is exactly the model
cljseq already uses for LFOs targeting control-tree parameters (§16). The gap to
close is formalising the vocabulary: a `cljseq.mod` namespace that provides
these primitives as first-class values that can be passed anywhere a
time-varying number is expected.

The Eurorack "modulator of modulators" pattern then emerges naturally:

```clojure
;; An envelope whose fall time is itself modulated by a slow LFO
(def fall-mod (lfo :rate 0.1 :shape :sin :range [0.1 0.8]))
(def env     (envelope :rise 0.05 :fall fall-mod :curve :exp))

;; Spread eight voices across a range of rise times
(def voices  (spread (envelope :rise 0.1 :fall 0.4) :amount 0.3 :direction :right))
```

**Suggested sprint scope**: survey 3–5 additional Eurorack modules with
distinctive modulation vocabularies (candidates: Mutable Instruments Stages,
Tides; Befaco Rampage; Serge DUSG), then specify the `cljseq.mod` namespace
interface — function signatures, composability contract, and how modulator
values interact with the `ctrl/bind!` control tree.

### MIDI Implementation Chart → Device Node Extraction (future sprint)

Every MIDI device ships with a **MIDI Implementation Chart** — a standardized
table (required by the MIDI specification) that enumerates exactly which MIDI
messages the device transmits and recognizes: channel range, note numbers,
velocity, Control Change assignments, Program Change, System Exclusive, NRPN,
RPN, and so on. The research question is: **can we extract that chart and use it
to automatically populate a device node in the cljseq unified control tree
(§16)?**

#### Why this matters

The control tree's device node model (§16) maps named parameters to MIDI CC
numbers, note ranges, and SysEx patterns. Today those mappings must be authored
by hand or hard-coded per device. If we can parse a MIDI implementation chart
and generate a device node from it, we get:

- Instant device support for any instrument whose manual is available — no
  hand-authoring of CC maps
- A community-contributed library of device nodes, each traceable to an official
  source document
- A live-coding workflow where `(load-device "Korg Minilogue XD")` resolves
  parameter names like `:filter-cutoff` to their correct CC numbers automatically

#### Source formats to consider

MIDI implementation charts exist in several forms, roughly ordered from easiest
to hardest to parse:

| Source | Notes |
|---|---|
| MIDI 2.0 / MIDI-CI Property Exchange | Machine-readable; the right long-term answer for new devices |
| HTML table in online manual | Amenable to DOM scraping or direct LLM extraction |
| PDF table in printed manual | Requires PDF text extraction + table reconstruction; feasible (as demonstrated by Labyrinth manual work in this sprint) |
| Image/scan in manual | Requires OCR; hardest path; may not be worth automating |

#### MIDI 2.0 and MIDI-CI Property Exchange

MIDI 2.0 defines **MIDI Capability Inquiry (MIDI-CI)** and **Property Exchange**
— a protocol by which a device can be queried over SysEx for a machine-readable
description of its parameter set, current state, and supported profiles. For
devices that implement it, this supersedes manual chart parsing entirely: the
device self-describes. The Sprint 2 analysis should determine whether targeting
MIDI-CI Property Exchange as the primary device-discovery mechanism is feasible
for the devices we care about, with manual chart extraction as the fallback for
legacy instruments.

#### Connection to MCP and LLM tooling

The MCP resource server (§18, documentation strategy section above) opens an
interesting path: given a PDF or HTML MIDI implementation chart as an MCP
resource, an LLM tool call could extract the structured CC map and emit a
`defdevice` form ready to evaluate in the REPL. This would make device
onboarding a single-command operation:

```clojure
;; Hypothetical workflow
(load-device-from-chart "path/to/minilogue-xd-manual.pdf")
;; → emits and evals:
(defdevice :korg/minilogue-xd
  {:filter-cutoff    {:cc 43 :range [0 127]}
   :filter-resonance {:cc 44 :range [0 127]}
   :amp-eg-attack    {:cc 16 :range [0 127]}
   ;; ...
   })
```

#### Sprint 2 tasks

1. Survey MIDI implementation chart formats across 5–10 target devices (Elektron
   Digitakt, Korg Minilogue XD, Moog Subsequent 37, Roland MC-707, etc.) and
   categorize by parse difficulty
2. Prototype PDF/HTML extraction for one device using the pypdf + LLM approach
   proven in this sprint; produce a hand-verified `defdevice` map
3. Research MIDI-CI Property Exchange support across the same device set — is
   this a viable primary path or still too sparse in the installed base?
4. Define the `defdevice` / device node schema that the extraction output must
   conform to, as part of specifying the §16 control tree device layer

---

## Implementation Plan: Phase 0 Readiness

The next sprint can begin implementation of **Phase 0** (C++ foundation) as soon
as the blocking questions above are resolved. The Phase 0 deliverables are in
`doc/implementation-plan.md`:

1. **`libcljseq-rt`** — IPC framing, `SynthTarget` interface, `LinkEngine`,
   Asio utilities, OSC codec, lock-free queues, CMakeLists
2. **`cljseq-sidecar`** — RT dispatch thread, RtMidi, Link, Unix socket server,
   `MidiTarget`, `OscTarget`
3. **`cljseq-audio` skeleton** — `AudioEngine`, `RtAudio` callback, `ClapTarget`
   stub, `PdTarget` stub

Immediately after Phase 0, **Phase 1** (Clojure virtual time model) can begin:
the `cljseq.time`, `cljseq.pitch`, `cljseq.scale` namespaces — the pure-Clojure
foundation that does not depend on the C++ sidecar.

---

## Scope Boundaries Confirmed

The following are explicitly **out of scope** for the initial implementation:

- VCV Rack native C++ plugin (§13.6) — possible future add-on
- Visual TUI / REPL status dashboard (§13.7) — post-core
- ML-based generative models (§13.9) — future research
- VST3 plugin hosting (§19.2) — second phase after CLAP
- GraalPy / in-process Music21 (Q22) — subprocess is sufficient
- Non-Western corpus bundling (Q24, Q27) — user-provided via VirtualCorpus
- Polynesian/Hawaiian rhythmic patterns (§21.6) — lower priority than African/Caribbean

---

## Documentation Strategy (plan for upcoming sprint)

cljseq will accumulate documentation across four distinct layers that need to
stay coherent as the system grows:

| Layer | Source | Audience |
|---|---|---|
| Architecture intent | `doc/research-and-requirements.md` | Contributors / future-self |
| Decision log | Sprint summaries, `open-design-questions.md` | Contributors |
| API reference | Clojure docstrings + specs; C++ Doxygen headers | Users / integrators |
| Narrative guide | Hand-authored user guide | End users / live coders |

### Toolchain plan

**Clojure API docs — Codox**
- Add `codox` as a Leiningen plugin; configure `:output-path "doc/api"` and
  `:source-uri` pointing at GitHub so every var links to source
- Every public var must carry a docstring before it ships; enforce via a
  `lein codox` step in CI that fails on undocumented public vars
- `cljdoc` for hosted docs once the library is published to Clojars

**C++ sidecar docs — Doxygen**
- Doxygen config at `cpp/Doxyfile`; HTML output to `doc/cpp-api/`
- All public header symbols in `libcljseq-rt` and `cljseq-sidecar` carry
  `///` doc comments; implementation files use `//` comments only
- Run `doxygen` as part of the CMake `docs` target

**Narrative user guide — mdBook**
- `doc/guide/` as an mdBook source tree; `book.toml` at root of `doc/guide/`
- Chapters map to functional areas: Time Model, Pitch/Scale, Sequencing,
  Control Tree, Live Coding Patterns, Hardware Integration
- Each chapter cross-references the relevant R&R section (e.g. "§16 Unified
  Control Tree") so readers can trace design intent back to the source

**Design doc ↔ source cross-linking convention**
- Each Clojure namespace that implements a major R&R section opens with a
  comment block: `;;; Implements: §16 Unified Control Tree (R&R §16.1–§16.9)`
- Each C++ translation unit that implements a design decision carries an
  equivalent `// Implements:` line referencing the R&R section
- This makes `grep "Implements: §16"` a reliable way to find all code touching
  a given architectural decision

**MCP integration**
- The design documents (`doc/*.md`) should be exposed as MCP resources (§18)
  so that an LLM assistant (this one) can retrieve them at authoring time
  without manual copy-paste. The resource tree mirrors `doc/` structure.
- Sprint 2 task: prototype the MCP resource server with the existing doc set
  as its initial corpus

### Sprint 2 task
Add a `doc/documentation-strategy.md` that formalizes the above, records the
chosen toolchain with versions, and defines the "undocumented public var = CI
failure" policy.

---

## Licensing Strategy (to be resolved in Sprint 2)

cljseq spans two distinct technical domains with different dependency graphs and
different licensing ecosystems. Getting the licensing right early avoids
irreversible architectural commitments later.

### The two domains

**C++ sidecar components** (`libcljseq-rt`, `cljseq-sidecar`, `cljseq-audio`)
link against third-party C/C++ libraries with copyleft terms. The Clojure
library communicates with the sidecar exclusively over OSC/Unix sockets — there
is no link-time dependency, so copyleft does not propagate across the boundary.
The two domains can carry different licenses.

**Clojure library** (`cljseq` proper) lives in an ecosystem where EPL-2.0 is
the dominant license (Clojure itself, most clojure.contrib libraries, many
community libraries). A cljseq license that harmonizes with EPL-2.0 avoids
friction for users assembling EPL-licensed systems.

### C++ licensing: Ableton Link

The Ableton Link SDK is **GPL-2.0**. This is confirmed and is not a problem for
cljseq: the project is intended to be available on fully FOSS terms, so GPL on
the C++ sidecar components is acceptable. Our own C++ code can be authored as
LGPL-2.1-or-later; the act of linking against GPL-2.0 Link produces a
GPL-2.0-or-later combined work, which is a well-understood and legally clean
outcome for a FOSS project.

| Component | Links against | Authored as | Combined result |
|---|---|---|---|
| `libcljseq-rt` | Ableton Link (GPL-2.0), Asio (BSL-1.0) | LGPL-2.1-or-later | GPL-2.0-or-later |
| `cljseq-sidecar` | `libcljseq-rt`, RtMidi (MIT), Link (GPL-2.0) | LGPL-2.1-or-later | GPL-2.0-or-later |
| `cljseq-audio` | CLAP SDK (MIT), libpd (BSD-3), RtAudio (MIT) | LGPL-2.1-or-later | LGPL-2.1-or-later (no GPL dep) |

Authoring our code as LGPL-2.1-or-later (rather than GPL outright) preserves
the option to build `cljseq-audio` as a genuinely LGPL library if it is kept
free of direct Link linkage.

The OSC boundary between the sidecar and the Clojure library cleanly isolates
the Clojure library from any GPL obligation.

### Open question: opt-in Ableton Link support

Worth exploring in Sprint 2 — **but not necessarily worth implementing**: could
`libcljseq-rt` and `cljseq-sidecar` be designed so that Ableton Link support is
a compile-time opt-in (`-DCLJSEQ_LINK_ENABLED=ON`), with a Link-free build path
that has no GPL dependency?

**The case for it**: a Link-free sidecar binary would be LGPL-2.1 rather than
GPL-2.0, which marginally increases reusability of the sidecar components in
non-GPL contexts. It also allows users without a need for Ableton Link sync to
build a simpler system.

**The case against it**: cljseq as a whole is a FOSS project with no intention
of commercialization under proprietary terms, so the GPL vs LGPL distinction for
the sidecar has no practical downstream impact. Conditional compilation for an
optional subsystem adds meaningful implementation complexity — a second build
matrix, stub interfaces, extra CMake logic — for a benefit that may never be
realized.

**Sprint 2 task**: do the due-diligence analysis. Estimate the implementation
cost (interface surface that would need abstracting, CMake complexity, test
matrix impact). If the cost is low, implement it; if it requires significant
ongoing maintenance burden, document the decision not to and move on.

### Clojure library licensing: EPL-2.0

EPL-2.0 (Eclipse Public License 2.0) is the right choice for the Clojure side:

- Compatible with Clojure itself and the majority of the Clojure ecosystem
- EPL-2.0 includes an explicit "Secondary Licenses" provision allowing
  EPL-2.0-licensed code to be combined with GPL-2.0-or-later code — this
  matters if any tooling ever links the Clojure library with a GPL component
- EPL-1.0 (used by older Clojure libraries) does **not** have this secondary
  license clause and is formally GPL-incompatible; EPL-2.0 is the correct
  choice for new projects
- The current placeholder `LICENSE` file should be replaced with the full
  EPL-2.0 text; `project.clj` should declare `:license {:name "Eclipse Public
  License 2.0" :url "https://www.eclipse.org/legal/epl-2.0/"}`

### Other third-party dependencies to audit

| Dependency | Known license | Notes |
|---|---|---|
| RtMidi | MIT | No copyleft concern |
| RtAudio | MIT | No copyleft concern |
| CLAP SDK | MIT | No copyleft concern |
| libpd | BSD-3-Clause | No copyleft concern |
| Boost.Asio (header-only) | BSL-1.0 | No copyleft concern |
| music21 (subprocess) | BSD-3-Clause | Subprocess; no linkage |
| Surge XT (CLAP plugin, runtime) | GPL-3.0 | Runtime plugin, not linked; GPL does not propagate through plugin host boundary under CLAP's design — **verify** |

### Sprint 2 tasks

1. Analyse the opt-in Ableton Link build option — estimate implementation cost
   and make a go/no-go call
2. Confirm that the CLAP plugin host boundary is legally clean for hosting
   GPL-licensed plugins from a GPL host (community consensus says yes; worth a
   citation)
3. Replace the placeholder `LICENSE` file with EPL-2.0 full text
4. Update `project.clj` `:license` metadata
5. Add per-file SPDX license headers to all C++ source files:
   `// SPDX-License-Identifier: LGPL-2.1-or-later`
6. Add a `LICENSES/` directory (REUSE spec) or equivalent `LICENSE` files in
   each C++ subdirectory
7. Write `doc/licensing.md` recording these decisions and their rationale

---

## Intellectual Property Attribution (to be formalised in Sprint 2)

cljseq draws design inspiration from a wide range of commercial products and
open source projects. The following principles govern how that relationship is
represented:

### Principle

**Inspiration is not copying.** Observing that a hardware sequencer has a
distinctive feature and choosing to implement an analogous capability in a new
system is normal creative and engineering practice. The intellectual property
rights in the original products — trademarks, patents, trade dress, and any
unpublished algorithms — remain the exclusive property of their respective
owners. cljseq makes no claim to those rights and does not use proprietary
source code, firmware, or confidential technical specifications unless a
compatible FOSS license explicitly permits it.

Where FOSS-licensed source code is used (e.g. the Ableton Link SDK under
GPL-2.0), that use is expressly permitted by the applicable license, and the
license terms are honoured as described in the Licensing Strategy section above.

### Commercial products referenced for design inspiration

The following commercial products and their respective owners have informed the
design of cljseq. No source code, firmware, or proprietary materials from these
products are used. All trademarks are the property of their respective owners.

| Product | Owner | Concepts informing cljseq |
|---|---|---|
| Ableton Live / Ableton Link | Ableton AG | Tempo sync protocol (Link SDK used under GPL-2.0) |
| Elektron Digitakt / Digitone | Elektron Music Machines | P-locks, trig conditions, micro-timing, track scaling, sound locks |
| Polyend Tracker | Polyend | Pattern grid, effect columns, song mode, tracker-style step entry |
| Moog Labyrinth | Moog Music Inc. | Play/write head separation, CORRUPT mutation, BIT FLIP CV, EG TRIG MIX, scale mode catalog |
| Make Noise Marbles | Make Noise Music | Stochastic gate/pitch generation, X/Y/T output model, distribution bias |
| Ornament & Crime (Bloom) | Multiple open-source contributors | Euclidean sequencing, unified step map |
| VCV Rack | VCV (Andrew Belt) | Modular signal graph concepts; plugin architecture reference |

### Open source projects referenced but not used as code

The following open source projects informed design decisions but are not
included in the cljseq codebase. Their licenses and copyrights remain with
their respective authors.

| Project | License | Relationship to cljseq |
|---|---|---|
| Overtone | EPL-1.0 | Spiritual predecessor; informed the nREPL-centric live coding model |
| Sonic Pi | MIT | Live coding idiom reference; informed DSL design principles |
| SuperCollider | GPL-3.0 | Audio synthesis reference; not integrated |
| music21 | BSD-3-Clause | Used as a subprocess sidecar; no source code incorporated |
| tonal.js | MIT | Pitch/scale algorithm reference; independent reimplementation in Clojure |

### Sprint 2 tasks

1. Create an `ATTRIBUTION.md` (or `NOTICE`) file at the repository root listing
   all third-party open source components included in the build, their licenses,
   and copyright holders — required for GPL/LGPL compliance and good practice
   regardless
2. Add a brief IP disclaimer to `README.md` stating that all referenced product
   names and trademarks are the property of their respective owners and that
   cljseq is an independent work drawing on publicly available information
3. Review the design documents (`research-and-requirements.md` in particular)
   to ensure no section inadvertently reproduces proprietary documentation
   verbatim; paraphrase or cite publicly available sources where needed
4. Incorporate attribution and IP notes into `doc/licensing.md`

---

### Eurorack Clock Vocabulary (future sprint)

cljseq already has a BPM-based master clock and virtual time model (§3). But
the Eurorack clock ecosystem reveals that **a clock is not merely a pulse train
— it is a family of time-domain relationships**. Surveying three modules from
their official manuals reveals a vocabulary of clocking primitives that could
drive modulators, sequencers, and LFOs with much more expressiveness than a
single BPM value.

#### ALM/Busy Circuits Pamela's Pro Workout (ALM034)

Pam's is "a compact programmable clocked modulation source" — 8 independent
outputs, all derived from a master BPM clock, each with a deep per-output
parameter set. Key properties sourced from the official ALM manual:

- **Division/multiplication range**: /16384 to ×192, including non-integer
  values for dotted and triplet timings — any rational subdivision is available
- **Output shapes**: Gate/Pulse, Ratchet ×2/×4, Triangle, Trapezoid, Sine,
  Hump, Exp Envelope, Log Envelope, Classic Random (S&H), Smooth Random — a
  clock output can be any of these waveforms, phase-synced to the master
- **Width/Slew**: morphs each waveform shape continuously
- **Phase offset**: per-output phase shift; wraps back to 0 at full cycle
- **Probability**: per-step gate probability 0–100%; can be looped to create
  deterministic probabilistic patterns
- **Euclidean patterns**: Steps, Triggers, Pad (blank steps appended), Shift
  (rotation) — full Bjorklund parameterization
- **Loop**: beats-based loop with Loop Nap (sleep N loops) and Loop Wake (run N
  loops) for structured on/off cycles
- **Cross Operations**: one output can process another — MIX (average), MULT
  (ring mod), ADD, SUB, MIN, MAX, HOLD (freeze if source > 0), S&H (sample on
  rising edge), MASK (gate by source), NOT (invert gate), OR, XOR, AND, BitOR,
  BitXOR, BitAND, SEED (reset random seed on trigger)
- **FLEX micro-timing**: HUMAN (random timing jitter), SWING (alternate-step
  delay), RAMP UP (accelerating steps), RAMP DOWN (decelerating steps), HUMP
  (accelerate then decelerate), DELAY (waveform shift), PWR2 (power-of-2
  step-time doubling for ratchets)
- **Quantizer**: pitch-quantize the output voltage to a scale/mode; up to 3
  custom user scales
- **CV modulation**: all parameters CV-assignable via 4 inputs, each with
  per-parameter attenuation and bipolar offset (attenuverter semantics)

#### Make Noise Tempi

Tempi is "a 6 channel, polyphonic, time-shifting module" focused on the
**relationships** between clocks rather than the generation of individual
clock signals. Key properties from the official Make Noise manual:

- **Human programming**: tap a channel button at any rate and Tempi determines
  the nearest integer or non-integer multiple/division of the Leading Tempo —
  the relationship is computed from the gesture, not entered as a number
- **Machine programming**: explicit ÷n and ×n (up to 32), plus fine
  increment/decrement for non-integer values that drift in and out of phase
- **Phase programming**: per-channel phase offset at coarse (one Leading Tempo
  cycle) and fine (¼ cycle) resolution; non-integer multiples produce slow
  phase drift against the leading tempo — polymetric time as a first-class
  concept
- **Named temporal relationship sets** (factory states): Powers of 2, Primes
  (÷2 ÷3 ÷5 ÷7 ÷11 ÷13), Integers, Evens, Odds, Fibonacci (÷2 ÷3 ÷5 ÷8 ÷13
  ÷21) — a catalog of named polyrhythm structures ready to recall
- **64 States in 4 Banks**: complete clock configuration snapshots recallable
  by CV or button; State Mutate deliberately modifies a stored state
- **Mute**: any channel muted independently without stopping the clock
- **Mod mode**: a gate input temporarily activates an alternative timing on
  mod-enabled channels — a performance override without losing the stored state
- **Lead/Follow**: one Tempi follows the Tempo Bus of another via the Select
  Bus — inter-module clock routing

#### Make Noise (Richter) Wogglebug

The Wogglebug is a "random voltage generator" descended from the Buchla 265
"Source of Uncertainty". It is clock-correlated rather than a clock itself, but
it represents an important class: **clock-driven random voltage generators**
where the clock rate determines the rhythm of the randomness. Key properties:

- **Internal VC clock**: 1 min/cycle to ~200 Hz (audio rate); Speed/Chaos
  control simultaneously sets clock rate and the lag time of the Smooth CV —
  one knob couples timing and smoothness
- **External clock input**: decouples S&H rate from the internal clock;
  internal clock output continues unaffected, always available as a master
- **Stepped Output**: classic S&H — new random value on each clock tick;
  audio-rate clock turns it into a bit-crusher
- **Smooth Output**: the stepped value fed through a lag processor; smoothness
  determined by Speed/Chaos — continuously variable from stepped to fully
  glided
- **Woggle CV**: a "stepped voltage with decaying sinusoidal edges" — the
  circuit chases the Smooth/Stepped value and always lags, producing an organic
  wavering that sounds distinct from either stepped or smooth random
- **Burst Output**: random gate signal, clocked but probabilistically
  distributed — a randomized gate source driven by the clock
- **Ego/Id balance**: controls clustering of random values; with a CV at the
  Ego Input, it balances external signal against internal randomness
- **Disturb button**: manually sample (press) or pause/hold (hold) the S&H —
  a real-time performance override of the random process

The Wogglebug is a complete feedback system: Speed/Chaos, Ego/Id, and the
Influence input all interact. The whole system can "lock up" (hang at a fixed
voltage) and must be disturbed to restart — an intentional design feature.

#### cljseq implications: a clock vocabulary

These three modules together define a vocabulary of clocking primitives that
extends the cljseq BPM clock into a rich temporal language:

| Hardware primitive | cljseq DSL concept |
|---|---|
| Clock division/multiplication | `(clock-div n master)`, `(clock-mult n master)` — rational n for dotted/triplet |
| Phase offset | `(phase-shift clock :beats n)` or `:frac 1/4` |
| Non-integer drift | `(clock-mult 4.25 master)` — slow phase-drift polymetric time |
| Named relationship sets | `(polyrhythm :fibonacci master)`, `(polyrhythm :primes master)` |
| Output waveform shape | `:shape :sine/:tri/:exp-env/:log-env` on any clock output |
| Probability gate | `(prob-gate clock p)` — fires with probability p |
| Loop with Nap/Wake | `(loop-clock clock :beats 8 :nap 2 :wake 4)` |
| FLEX swing/jitter | `(swing clock :amount 0.3)`, `(humanize clock :amount 0.05)` |
| FLEX ramp/hump | `(ramp-clock clock :dir :up :amount 0.5)` — accelerating loop |
| Cross ops (AND/OR/S&H) | `(gate-and c1 c2)`, `(gate-or c1 c2)`, `(sample-hold c1 :trigger c2)` |
| State/scene snapshots | Connects to `ctrl/snapshot` (§16) |
| Stepped random (S&H) | `(stepped-random clock)` — new value per tick |
| Smooth random | `(smooth-random clock :slew t)` — slewed S&H |
| Woggle CV | `(woggle-random clock)` — decaying sinusoidal chase |
| Burst gate | `(burst-gate clock :prob p)` — probabilistic gate burst |

The key compositional insight is that **a clock output in cljseq should be a
first-class value that can be passed anywhere a time-domain reference is
expected** — as the timing source for a sequencer, as the modulation rate of an
LFO, as the S&H trigger for a random voltage, or as the input to a cross
operation with another clock. The vocabulary of modulators (Maths, Blinds,
PoliMATHS) and the vocabulary of clocks are the same abstraction at different
time scales, both operating on time-varying values in the `cljseq.mod` namespace.

```clojure
;; A slow sine LFO clocked at 1/3 of master, phase-shifted by half a cycle,
;; whose rate is itself gated probabilistically at 80%
(def base    (clock-div 3 master))
(def shifted (phase-shift base :frac 1/2))
(def gated   (prob-gate shifted 0.8))
(def lfo     (lfo :rate gated :shape :sine))
```

**Suggested sprint scope**: formalise the clock primitive taxonomy above,
specify the `cljseq.clock` namespace interface, and determine how clock values
interact with the `cljseq.mod` modulator vocabulary — specifically, whether
clocks and modulators share a single protocol or are distinct types with
explicit coercion.

#### Guiding design priority for Sprint 2: a unified temporal-value abstraction

This design question — whether clocks and modulators share a protocol — is one
of the highest-leverage architectural decisions in Phase 1, and it should be
answered *before* implementing `cljseq.clock`, `cljseq.mod`, or any scheduler
binding code. The goal is **best-in-class abstractions for timing and modulation
that harmonize with the rest of the system**.

The central observation is that everything varying over virtual time in cljseq
— clocks, LFOs, envelopes, slew limiters, stepped random, probability gates —
can be described as a function `Beat → Value`. The differences (binary output
vs. continuous, one-shot vs. repeating, edge-driven vs. sampled) are *roles*,
not necessarily distinct types. Hardware synthesizers (VCV Rack, SuperCollider)
consistently choose a single signal type with rate annotations over a parallel
type hierarchy, and benefit enormously from the resulting composability.

This is catalogued as **Q44** in `doc/open-design-questions.md` with Architecture
tier priority and a blocking designation — it is prerequisite to Phase 1
implementation. Sprint 2's first concrete technical task should be a focused
design spike: implement a minimal `ITemporalValue` protocol, express the clock
and modulator primitives from both vocabulary surveys as implementations, and
verify that the composition patterns hold without adapter code. The spike output
is a design decision record, not production code.

---

## Project Structure (to be decided in Sprint 2)

cljseq spans three language ecosystems and several categories of static data.
The Sprint 2 goal is to settle the top-level repository layout, the build
system strategy, and the conventions for static data files — before Phase 0
implementation begins and directory trees become hard to move.

### Component inventory

| Component | Language | Build tool | Role |
|---|---|---|---|
| `cljseq` library | Clojure | Leiningen | Core DSL, scheduler, pitch/scale, control tree, analysis |
| `libcljseq-rt` | C++ | CMake | IPC framing, `SynthTarget`, `LinkEngine`, lock-free queues |
| `cljseq-sidecar` | C++ | CMake | RT dispatch, RtMidi, Ableton Link, Unix socket server |
| `cljseq-audio` | C++ | CMake | CLAP host, `libpd`, RtAudio |
| `cljseq-m21` | Python | pyproject.toml | Music21 sidecar subprocess |
| `doc/guide` | Markdown | mdBook | Narrative user guide |
| Static data | EDN / .scl / etc. | — | Scales, tunings, device maps, corpus |

### Proposed top-level layout

```
cljseq/
├── src/cljseq/          # Clojure library source
├── test/cljseq/         # Clojure tests
├── cpp/                 # All C++ components
│   ├── CMakeLists.txt   # Top-level CMake; adds subdirs
│   ├── libcljseq-rt/    # Shared library
│   ├── cljseq-sidecar/  # Sidecar executable
│   └── cljseq-audio/    # Audio engine executable
├── python/              # Python sidecar
│   ├── cljseq_m21/      # Package source
│   └── pyproject.toml
├── resources/           # Static data consumed at runtime
│   ├── scales/          # .scl (Scala) files + EDN index
│   ├── tunings/         # EDN tuning definitions
│   ├── devices/         # EDN MIDI device maps (defdevice)
│   └── corpus/          # Symbolic music corpus (EDN + MusicXML)
├── doc/
│   ├── guide/           # mdBook source (narrative guide)
│   ├── api/             # Generated Codox output (gitignored)
│   ├── cpp-api/         # Generated Doxygen output (gitignored)
│   └── *.md             # Design documents (this sprint's output)
├── LICENSES/            # REUSE-spec per-component license files
├── ATTRIBUTION.md       # Third-party attribution (GPL compliance)
├── LICENSE              # EPL-2.0 (Clojure library)
├── project.clj          # Leiningen project
└── Makefile             # Top-level orchestration (see below)
```

### Build system orchestration

Three build systems need to coexist (Leiningen, CMake, pip/pyproject.toml).
The Sprint 2 task is to evaluate and choose one of:

- **Makefile** — minimal, universal, orchestrates `lein`, `cmake`, `pip`
  with targets like `make sidecar`, `make test`, `make docs`; no additional
  tooling required
- **`just` (justfile)** — a modern `make` alternative with cleaner syntax
  and no tab-sensitivity pitfalls; same orchestration capability, smaller
  cognitive footprint; requires `just` to be installed
- **Bazel / Gradle** — polyglot build graphs with dependency tracking;
  significantly more setup and maintenance cost; likely overkill for this
  project at this stage

Recommendation going into Sprint 2: start with a **Makefile** (no toolchain
dependency, universally available) and revisit if the dependency graph becomes
complex enough to warrant a proper DAG build tool.

### Static data format policy

Where no established standard format exists for a category of static data,
**EDN is the preferred format**. Rationale:

- EDN is natively readable and writable from Clojure without a parsing library
- EDN is a well-specified subset of Clojure syntax — data files are directly
  evaluable as Clojure literals
- EDN supports all Clojure collection types (maps, vectors, sets, keywords)
  making it expressive enough for most structured data without schema overhead
- EDN files are human-readable and diff-friendly in version control

**Where a standard format exists, use it in preference to EDN**:

| Data category | Format | Rationale |
|---|---|---|
| Scala tuning definitions | `.scl` (Scala) | Standard format; broad tool interop |
| MIDI Standard files | `.mid` (SMF) | Standard format; interop with all MIDI tools |
| MusicXML scores | `.musicxml` / `.xml` | Standard format; Music21 native |
| Humdrum / MEI | `.krn` / `.mei` | Standard formats for corpus research |
| C++ SPDX headers | `// SPDX-License-Identifier:` | REUSE spec |
| Python packages | `pyproject.toml` | PEP 517/518 standard |

**EDN is preferred for**:

| Data category | Notes |
|---|---|
| MIDI device maps (`defdevice`) | No standard machine-readable format; EDN maps cleanly to the control tree node model |
| Scale index / metadata | Augments `.scl` files with Clojure-readable names, aliases, cultural context |
| Tuning definitions beyond Scala | EDO tables, just-intonation rationals, maqam cent-offset tables |
| Handpan / world-music scale catalog | Small maps of root-relative intervals |
| Corpus index / metadata | Annotations on MusicXML/Humdrum files; query metadata |
| Configuration | All cljseq runtime configuration |
| Saved control-tree snapshots | `ctrl/snapshot` output format |

### Sprint 2 tasks

1. Finalise the top-level directory layout and create any empty scaffold
   directories not already present
2. Decide on the Makefile vs. justfile orchestration approach and add the
   initial file with `build`, `test`, `docs`, `clean` targets
3. Initialise `cpp/CMakeLists.txt` as a top-level CMake project with
   `add_subdirectory` stubs for the three C++ components — no implementation,
   just the build skeleton
4. Add `python/pyproject.toml` with package metadata and a `[project.scripts]`
   entry for the sidecar entry point
5. Document the EDN data format policy in `doc/conventions.md` alongside
   naming conventions, namespace layout, and code style guidelines

---

## Repository State

First commit. All files are new:
- `doc/research-and-requirements.md` — 5,200+ line architecture document
- `doc/implementation-plan.md` — phased build plan
- `doc/open-design-questions.md` — 43 questions with stakes and exploration paths
- `doc/intro.md` — project overview
- `doc/design-sprint-1-summary.md` — this document
- Project skeleton: `project.clj`, `src/`, `test/`, `resources/`, `LICENSE`,
  `README.md`, `CHANGELOG.md`
