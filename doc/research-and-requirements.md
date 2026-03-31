# cljseq: Research and Requirements

## 1. Vision

`cljseq` is a music-theory-aware live coding sequencer implemented as a Clojure nREPL environment. It targets MIDI and Open Sound Control (OSC) outputs — controlling hardware synthesizers, software instruments, DAWs (such as Bitwig), Eurorack modular environments (via VCV Rack), and SuperCollider — without tight coupling to any single synthesis engine.

The system is intended for live performance and composition. The Clojure REPL is the primary interface: musicians define, modify, and compose sequencing behavior interactively, with changes taking effect in real time. The full power of a general-purpose functional language — persistent data structures, lazy sequences, transducers, macros, and the JVM ecosystem — means `cljseq` can express musical ideas and transformations well beyond what dedicated hardware sequencers can offer, while drawing heavily on the best concepts those instruments have developed.

**Ensemble synchronization** via Ableton Link is a first-class requirement. `cljseq` must be able to join a Link session and maintain beat/phase alignment with Ableton Live, Bitwig, Sonic Pi, and any other Link-enabled peer on the same LAN — enabling live performance with multiple musicians or hybrid hardware/software setups. See §15 for the full analysis and design.

### 1.1 Broader scope: a toolkit for novel musical instruments

`cljseq` is not only a live coding environment. It is a **platform for building novel,
theory-aware musical instruments, effects processors, sequencers, loopers, and
performance appliances**.

The unified control tree (§16) presents cljseq as a single logical device to any
external control surface — MIDI controller, TouchOSC layout, MCP client, or another
cljseq peer. Below that surface, the system fans out to multiple physical synthesis
targets. This architecture means the same codebase that powers a live coder's REPL
session can equally serve as the firmware of a standalone hardware appliance:

- An embedded system (e.g. Raspberry Pi + touchscreen) running cljseq as headless
  firmware, presenting a novel sequencer with its own UI, participating in Ableton
  Link sessions, and supporting MIDI and OSC control from external surfaces
- A drum machine, MPC-style sampler, or Elektron-alike device, using a CLAP sampler
  plugin alongside cljseq's step sequencing and generative primitives
- An effects processor built from PureData's DSP capabilities combined with cljseq's
  modulation vocabulary
- A direct Eurorack interface via a CLAP plugin driving USB-to-CV hardware (e.g.
  Expert Sleepers ES-series), bypassing VCV Rack for lower-latency modular integration
- A headless DAW controller, managing Ardour or Bitwig via their OSC interfaces
  while cljseq handles all sequencing and parameter modulation

This broader scope should be kept in mind throughout the design: every abstraction
— the control tree, the ensemble model, the timing modulation framework, the
`ITemporalValue` protocol — is a building block for instruments that have not yet
been imagined, not only a convenience for the live coder at the REPL.

---

## 2. Prior Art and Inspiration

### 2.1 Sonic Pi (Sam Aaron, Cambridge / Kent)

Sonic Pi is the most important reference for timing architecture. The key paper is:

> Aaron, S., Orchard, D., & Blackwell, A. F. (2014). *Temporal Semantics for a Live Coding Language*. FARM 2014.
> https://www.cs.kent.ac.uk/people/staff/dao7/publ/farm14-sonicpi.pdf

**Core insight — virtual time separation**

Sonic Pi v1.0 used POSIX-style `sleep`, accumulating timing drift from computation overhead across every iteration. v2.0 introduced a dual-clock model:

- **Real time**: wall clock.
- **Virtual time**: thread-local; advanced *only* by `sleep` calls. Never reflects computation overhead.

`sleep t` becomes a temporal barrier: it blocks real execution until wall time has caught up to virtual time, absorbing all preceding computation overhead. Programs can only be on-time or late, never early (proved by structural induction). This eliminates drift accumulation entirely.

**Scheduled-ahead constant**

All sound-triggering messages are sent as `(currentVirtualTime + schedAheadTime)` to the synthesis engine. This pre-buffers events, decoupling computation jitter from audio timing. Provided computation overhead per `sleep` boundary never exceeds `schedAheadTime`, timing is accurate.

**Thread model**

Each concurrent strand (`in_thread` / `live_loop`) has independent virtual time. Threads launched simultaneously stay synchronized because each independently corrects against the shared wall clock. The `sync`/`cue` mechanism transfers the sender's logical time to the receiving thread, enabling deterministic coordination without manual time arithmetic.

**Implementation reference**: The Temporal monad in Haskell at https://github.com/dorchard/temporal-monad provides the formal denotational semantics.

**Sonic Pi's BEAM sidecar**

Sonic Pi offloads time-critical event delivery to an Elixir/BEAM process (`tau`). The BEAM has no stop-the-world GC, enabling ~1ms delivery accuracy. Ruby code runs ahead by `schedAheadTime` and sends pre-timestamped events to tau for future delivery. This is the key technique for surviving GC pauses in a managed runtime.

### 2.2 Overtone (Clojure / SuperCollider)

Overtone is a Clojure library targeting SuperCollider's `scsynth`. Its most reusable components:

- **`overtone.osc.*`**: A self-contained OSC peer library with priority-queue-based timestamped sending, handler trees with pattern matching, and two daemon threads (send/listen). Directly applicable or inspirational.
- **`overtone.music.pitch`**: ~50 scales as step-interval vectors, ~60 chord types as semitone offset sets, MIDI↔Hz conversion, `degree->interval` for Roman numeral navigation.
- **`overtone.music.rhythm`**: `Metronome` record with rational BPM arithmetic; `(metro beat)` returns absolute ms timestamp.
- **`overtone.music.time`**: `apply-at`, `apply-by` (300ms lead-time scheduling), `periodic`, `interspaced`.
- **`overtone.algo.euclidean_rhythm`**: Bjorklund's algorithm → binary vectors.
- **`overtone.algo.chance`**: `choose`, `weighted-choose`, `rrand`.
- **`overtone.midi`**: `javax.sound.midi` wrapper — device discovery, note-on/off, CC, note scheduling.

**Key design patterns from Overtone:**
- Thread-local OSC bundle accumulation via dynamic var `*at-time*`
- Dependency satisfaction for boot ordering (`satisfy-deps` / `wait-until-deps-satisfied`)
- `defonce` guards for server boot to survive namespace reload
- Temporal recursion idiom for live loops (no built-in `live_loop`)

### 2.3 Conductive Labs NDLR

The NDLR is a harmony-aware MIDI generator built around four simultaneous voices (Drone, Pad, Motif 1, Motif 2), all locked to a global Key + Mode + Chord. Its defining characteristic is **harmony-relative note indexing**: patterns store positions within the current chord or scale (not absolute pitches), so the same pattern sounds musically coherent across chord changes.

**Key NDLR concepts for the DSL:**

| Concept | Description |
|---|---|
| **Harmony-relative indices** | Note patterns reference positions in CHORD (1–20), SCALE (1–40), or CHROMATIC pools; follow chord changes automatically |
| **Pattern × Rhythm orthogonality** | Note content and rhythmic timing are independent values with independent lengths; mismatched lengths evolve the pattern naturally |
| **Notes:beats ratio** | A pattern of 5 notes against a rhythm of 8 beats creates an 8-beat cycle where the melodic alignment shifts each bar — emergent complexity from simple parameterization |
| **Clock divide per voice** | Each voice has its own subdivision (1/1 to 1/8T), creating polyrhythm without explicit programming |
| **Chord sequencer** | Automated harmonic progression: each step has duration, key, mode, degree, type |
| **Roman numeral chord buttons** | Physical buttons arranged in a circle and labeled I–VII select the diatonic degree of the current chord; this is a hardware proof-of-concept that musicians navigate harmony by scale degree rather than absolute chord name — the direct physical analogue of cljseq's `:I`…`:VII` keyword vocabulary (see §10.2) |
| **Pad with spread and strum** | Chord voicing (open/closed/bass+5ths) and arpeggiation are style parameters |
| **Modulation matrix** | 8 slots, sources (LFO, MIDI), destinations (any parameter including MIDI CC out); LFO can use a step pattern as its waveform |
| **MIDI control surface** | Full CC implementation; keyboard notes map to interval-relative functions (not absolute pitches) |
| **Arm semantics** | Parts can be armed to await transport — declare before triggering |

### 2.4 Torso Electronics T-1

The T-1 is a 16-track algorithmic pattern sequencer. Fundamental paradigm: **parameter-space composition** — dial in mathematical parameters that generate sequences, then modulate those parameters over time.

**Key T-1 concepts:**

| Concept | Description |
|---|---|
| **Euclidean rhythm as primitive** | Steps + Pulses + Rotate as a three-parameter rhythm spec; Bjorklund algorithm distributes K pulses across N steps |
| **Note repeater** | Repeats + Time + Pace (acceleration/deceleration) creates drum rolls, flams, bouncing-ball textures algorithmically |
| **Cycles (meta-parameter sequencer)** | Each track holds 16 Cycles — per-loop snapshots of all parameters; the sequence itself has a meta-sequence of parameter states |
| **Groove profiles** | Velocity variation follows named percussive templates (Conga, Agogo) or mathematical waveforms (Sine, Saw) — groove as a named vocabulary |
| **Phrase shapes** | LFO/cadence curves applied to the Range parameter give melodies automatic contour (cadence, saw, triangle, sine) |
| **Voice-leading Harmony knob** | Scrolls through chord voicings using classical voice-leading constraints |
| **Probability per note** | 0–100% chance each note fires |
| **Random with Slew** | Smoothly interpolated randomness (not stepped) on any parameter |
| **Per-track scale and root** | Unlike the NDLR's global key, each T-1 track has its own scale, enabling polytonal textures |
| **Microtiming/swing per track** | Independent swing amount per track |
| **Temp performance** | `with-temp` semantics: transient parameter override, restored on release |

### 2.5 Eventide Misha

The Misha is interval-based and tone-row-oriented. It represents the sharpest departure from conventional step sequencing.

**The tone row concept**

A tone row is an ordered pitch sequence where each scale degree appears at most once before cycling. Derived from Schoenberg's twelve-tone technique but generalized to any scale (4–48+ notes). The Misha enforces the no-repetition constraint, creating natural "harmonic completion" cycles.

**Interval-based navigation**

Nine buttons represent `{-4, -3, -2, -1, 0, +1, +2, +3, +4}` — relative scale-degree displacements, not absolute pitches. The same button sequence sounds different in every scale, but preserves melodic contour (interval relationships). Key insights:

- No stored pitches — only stored interval moves
- Transposing the key doesn't require re-recording
- Switching scales mid-performance preserves shape while changing pitches

**Playback transformations**

Prime (forward), Retrograde (backward), Pendulum, Random, Transpose-per-cycle (key shifts each loop), Translate-per-cycle (shifts row within scale each loop).

**MIDI control**

Every function is mappable to MIDI Note or CC: interval moves, key/scale selection, chord type, play mode, clock division, preset loading. Default white-key-to-interval mapping enables keyboard-as-controller. `PassThru/ActiveLastNote` mode lets an external sequencer set the anchor pitch; Misha's interval navigation then operates from that anchor.

**Chord system**

9 chord types, each a triple of scale-relative interval offsets. Voicing adapts automatically to the current scale.

**Scala support**

200 scale slots; user scales importable via microSD as `.scl` files. Uses MIDI pitch bend for microtonal output.

**Key DSL implications**

Expressing Misha-style behavior means working with intervals, scale contexts, tone row states, and playback transformations — not step positions and note numbers. A `(tone-row [+1 +2 -1 +3])` macro over an implicit harmonic context is the right abstraction level.

### 2.6 Music21 (MIT)

Music21 is a Python toolkit for computational musicology. It is not a real-time system — its domain is symbolic music, score analysis, and generation. But it contains abstractions far more sophisticated than anything in the Clojure music ecosystem.

**Most valuable abstractions for the DSL:**

**Pitch as a multi-dimensional value** (not an integer)

```
{:step :C, :octave 4, :accidental :sharp, :microtone 33 ; cents
 :ps 61.33,   ; pitch space (float)
 :midi 61,    ; rounded MIDI number
 :pitch-class 1,
 :frequency 282.4}
```

Spelling (C# vs Db) is independent of pitch space. Microtones are first-class as a cents offset. This separation of concerns is essential for a theory-aware system.

**Duration as quarterLength + type**

Quarter note = 1.0. Tuplets as `actual/normal` ratios. Clojure rationals handle this exactly: `3/2` for dotted quarter, `2/3` for triplet eighth.

**Stream as temporally-ordered collection**

A Stream is a sorted map of `offset → [elements]` with offset measured in quarter notes. Hierarchical nesting (Score → Part → Measure → Voice). `flatten` renormalizes offsets to absolute. `chordify` (salami-slicing to vertical chord snapshots) is a natural transducer.

**IntervalNetwork for scales**

Scales as directed weighted graphs of intervals, not lists of pitches. Enables:
- Ascending/descending variants (melodic minor)
- Probabilistically-weighted pathways (algorithmic generation)
- Microtonal scales by changing edge weights
- Non-octave-repeating scales

**Roman numeral as first-class type**

`[:I :IV :V7 :I]` in a key context, expanding to chords. With `functionalityScore` (0–100 harmonic expectedness heuristic) as a constraint for progression generation.

**Post-tonal / serial operations as pure functions**

- Prime form, normal order, Forte class, interval vector, Z-relation — all pure functions on sets of integers mod 12
- Twelve-tone row operations (P, I, R, RI with two convention choices) as pure functions on integer vectors
- 12×12 transformation matrix generation
- Historical row library (Berg, Schoenberg, Webern)

**TimespanTree / Verticality**

O(log n) AVL-tree temporal lookup. A Verticality captures all sounding pitches at a moment (start + overlap + stop timespans), enabling efficient harmonic analysis across polyphonic scores.

**Voice leading analysis**

Parallel 5ths/octaves, hidden parallels, voice crossing, non-harmonic tone classification — all as predicates on `VoiceLeadingQuartet` (two voices × two time-points).

**Key detection**

Krumhansl-Schmuckler and Aarden-Essen correlation algorithms over a pitch-class histogram. Returns ranked `{:key :G, :mode :major, :correlation 0.87}` maps.

---

## 3. Core Architecture

### 3.1 Timing Model

The virtual-time model follows the Sonic Pi FARM 2014 paper. All real-time event
delivery is handled by a native C++ sidecar process (§3.5), not by the JVM. The JVM
is responsible only for computing *what* to play and *when* (in beats and nanoseconds);
the sidecar is responsible for delivering events precisely at those times.

**Virtual time separation (JVM side)**

Every thread maintains two time values:
- `real-time`: `(System/nanoTime)` — wall clock, never modified by the thread
- `virtual-time`: a thread-local rational, advanced *only* by `sleep!` calls

`(sleep! beats)` semantics:
1. Compute `new-vt = virtual-time + (beats × beat-duration-nanoseconds)`
2. Set `virtual-time = new-vt`
3. Compute `drift = (System/nanoTime) - start-time`
4. If `new-vt > drift`: park the JVM thread for `(new-vt - drift)` ns via `LockSupport/parkNanos`
5. If `new-vt ≤ drift`: overrun — emit warning, proceed without sleeping

Note: this park is only to throttle the *generating* thread so it does not run
arbitrarily far ahead. The JVM thread does not need sub-millisecond precision here —
the `sched-ahead-ns` budget already absorbs any JVM GC pause that occurs between
event submission and event delivery. See §3.5 for the delivery model.

**Event submission to the sidecar**

All sound-triggering messages are submitted to the C++ sidecar at
`virtual-time + sched-ahead-ns` as their scheduled delivery timestamp. The sidecar
enqueues them and fires them precisely. The JVM is never on the delivery critical path.

**`sched-ahead-time`**

A configurable constant, per integration target (see open-design-questions.md §Q5).
Representative values:

| Target | Default sched-ahead |
|---|---|
| MIDI hardware (RtMidi) | 50ms |
| SuperCollider via OSC | 30ms |
| VCV Rack via OSC | 50ms |
| DAW virtual MIDI | 50ms |

The sched-ahead budget must exceed the maximum IPC round-trip time plus any JVM GC
pause. With a Unix domain socket IPC latency of < 0.5ms and a typical G1GC pause of
< 10ms, a 50ms sched-ahead provides substantial margin.

**Soft deadline semantics (JVM side)**

Overrun detection governs the *generating* thread, not delivery:
- `|overrun| < ε`: weak warning (log, continue)
- `|overrun| ≥ ε`: strong warning
- `|overrun| ≥ kill-threshold`: terminate the thread

### 3.2 Thread and Concurrency Model

**Thread-local state** via `binding`:

```clojure
(def ^:dynamic *virtual-time* (atom 0N))
(def ^:dynamic *start-time* nil)
(def ^:dynamic *bpm* 120)
(def ^:dynamic *scale* nil)
(def ^:dynamic *chord* nil)
(def ^:dynamic *sched-ahead-ns* 100000000)
```

**`in-thread` macro** — captures bindings at spawn time and re-binds in child:

```clojure
(defmacro in-thread [& body]
  `(let [captured# (get-thread-bindings)]
     (future (with-bindings captured# ~@body))))
```

**`live-loop` macro** — named looping with redefinable body, synchronized restart:

```clojure
(live-loop :beat
  (play! (chord :C :major))
  (sleep! 1))
```

Re-evaluating the `live-loop` form updates the loop body on the next iteration without stopping the loop. Implemented via an atom holding a function reference, replaced atomically.

**`cue` / `sync`** — primary cross-thread coordination:

- `(cue! :name & args)` — stores `{:time virtual-time, :beat beat, :bpm bpm, :args args}` in a shared `EventHistory` tree and notifies waiting threads
- `(sync! :name)` — blocks until a cue at or after the current virtual time; inherits the sender's virtual time on wakeup
- Wildcard matching: `(sync! "/midi/note-on/*")`

**`get` / `set!`** — Time State: deterministic shared state indexed by virtual time. `(set! :key value)` stores at current virtual time. `(get! :key)` retrieves the value current at this logical time — deterministic across runs.

### 3.3 Musical Time Abstraction

Virtual time tracks two parallel values:
- `*virtual-time*` — nanoseconds from start (for wall-clock comparison)
- `*beat*` — rational beat position (for musical calculations)

`(sleep! n)` advances both. BPM changes via `(with-bpm new-bpm ...)` wrap a scope with a different beat-to-time conversion. `(with-bpm-mul factor ...)` multiplies BPM for nested tempos (polyrhythm, density changes).

Beat arithmetic uses Clojure rationals (`1/3` for triplets, `3/2` for dotted values, etc.) to avoid floating-point drift.

### 3.4 Integration Targets

All real-time output is dispatched by the C++ sidecar (§3.5). The Clojure layer
submits pre-timestamped events; the sidecar handles device I/O.

**MIDI** — via RtMidi in the C++ sidecar:
- Device discovery by name/regex (sidecar enumerates; JVM receives device list)
- Note on/off, CC, pitch bend, program change, SysEx — all timestamped events
- MIDI clock output (24 PPQ) generated in sidecar from Link timeline or local BPM
- MIDI clock reception — sidecar receives, converts to Link-compatible timeline
- MPE: per-note channel allocation managed in sidecar; JVM submits note + bend pairs
- MTS SysEx for bulk microtuning

**OSC output** — via sidecar UDP socket:
- Priority-queue timestamped send (sidecar delivers at scheduled time)
- OSC bundle framing with NTP-format timestamps (sidecar constructs bundles)
- Sched-ahead: 30ms for SuperCollider; scsynth bundle timestamps provide
  additional sample-accurate scheduling within scsynth's own engine

**OSC server (incoming)** — sidecar runs a UDP OSC server (analogous to Sonic Pi's
`tau` BEAM sidecar, which offloads OSC I/O to the BEAM for GC-free delivery):
- Receives OSC messages from VCV Rack (`cvOSCcv`), TouchOSC controllers, hardware
  that speaks OSC, and other programs
- Decodes OSC 1.0 packets and bundles; pattern-matches address against registered
  handlers
- Forwards matched messages to the JVM as IPC push events (type `0x40`)
- The JVM side registers handlers with `(on-osc "/address" fn)` which fire as `cue!`
  events, enabling OSC-triggered live-loop coordination
- Handler registration is itself communicated to the sidecar so it can do
  server-side pre-filtering before forwarding (reduces IPC traffic)

**SuperCollider** — via OSC bundles from sidecar, using scsynth command set
(`/s_new`, `/n_set`, `/b_fill`, etc.) with bundle timestamps for sample-accurate
scheduling. The sidecar's 30ms sched-ahead is well within scsynth's latency window.

**VCV Rack** — via MIDI (Core `MIDI-CV` module) or via OSC (`cvOSCcv`/`OSC'elot`).
Both paths go through the sidecar. MIDI is the zero-friction path; OSC gives 32-bit
float precision for direct CV control.

**DAWs (Bitwig, etc.)** — via virtual MIDI ports dispatched by the sidecar (IAC
on macOS, JACK on Linux, loopMIDI on Windows). Ableton Live additionally via Link
(§15).

### 3.5 Native C++ Dispatch Sidecar

**Decision**: All real-time event delivery and all Ableton Link interaction are
handled by a native C++ sidecar process, not by the JVM. This decision is driven by:

1. **JVM GC unpredictability**: The JVM's garbage collector — even with G1GC or ZGC
   tuned for low pause times — cannot guarantee the < 1ms deadline precision required
   for tight musical timing. Stop-the-world pauses of 5–20ms are common under
   realistic workloads. These are acceptable for the *generating* thread (absorbed by
   `sched-ahead-ns`) but fatal for the *delivering* thread.

2. **Ableton Link SDK is C++**: The authoritative Link implementation is a header-only
   C++ library. Direct integration in a C++ sidecar requires no JNI/JNA bridge,
   no Carabiner intermediary, and allows `captureAudioSessionState()` to be called
   from the same high-priority thread that dispatches events — the correct usage.

3. **RtMidi is C++**: Cross-platform MIDI I/O via CoreMIDI/ALSA/WinMM is well-served
   by RtMidi (MIT). No wrapping or reflection overhead.

4. **Developer expertise**: The team has deep low-latency C++ and concurrency
   experience, making a C++ sidecar a lower-risk choice than a Rust or Go sidecar
   would be for this team.

**Sidecar responsibilities:**
- Receive pre-timestamped events from the JVM via Unix domain socket IPC
- Maintain a `std::priority_queue` of pending events ordered by delivery time (ns)
- Dispatch thread: `SCHED_FIFO` / `THREAD_TIME_CONSTRAINT_POLICY`; sleep via
  `clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME)` (Linux) or
  `mach_wait_until` (macOS)
- MIDI output via RtMidi
- OSC output via UDP sockets with bundle framing
- Ableton Link session management (direct `ableton::Link` instance)
- MIDI clock generation (24 PPQ) locked to Link or local BPM
- Push Link state updates (BPM, beat, phase) back to the JVM asynchronously

**JVM responsibilities (unchanged):**
- Virtual time model (`sleep!`, `live-loop`, `cue!`, `sync!`)
- All musical abstractions (pitch, scale, chord, pattern, rhythm)
- Beat-schedule computation (deciding *what* to play and *when* in beats)
- Converting beat schedules to absolute nanosecond timestamps
- Submitting pre-timestamped events to the sidecar

**IPC protocol:**

Unix domain socket (datagram mode for low latency), little-endian binary framing:

```
┌─ 4 bytes: payload length ─┬─ 1 byte: message type ─┬─ N bytes: payload ─┐
```

Message types (JVM → sidecar):

| Code | Message | Payload |
|---|---|---|
| `0x01` | MIDI note-on | `time_ns:u64, port:u8, ch:u8, note:u8, vel:u8` |
| `0x02` | MIDI note-off | `time_ns:u64, port:u8, ch:u8, note:u8, vel:u8` |
| `0x03` | MIDI CC | `time_ns:u64, port:u8, ch:u8, cc:u8, val:u8` |
| `0x04` | MIDI clock tick | `time_ns:u64, port:u8` |
| `0x05` | MIDI SysEx | `time_ns:u64, port:u8, len:u16, data[len]` |
| `0x10` | OSC bundle | `time_ns:u64, dest_ip:u32, dest_port:u16, len:u16, data[len]` |
| `0x20` | Link set BPM | `bpm:f64` |
| `0x21` | Link force beat | `beat:f64, time_us:i64, quantum:f64` |
| `0x22` | Link enable start/stop | `enabled:u8` |
| `0x23` | Link play | — |
| `0x24` | Link stop | — |
| `0x30` | Beat-at-time query | `req_id:u32, time_us:i64, quantum:f64` |
| `0x31` | Time-at-beat query | `req_id:u32, beat:f64, quantum:f64` |
| `0x50` | OSC handler register | `addr_len:u16, address[addr_len]` |
| `0x51` | OSC handler deregister | `addr_len:u16, address[addr_len]` |
| `0x60` | MIDI input subscribe | `port:u8, msg_types:u8 (bitmask)` |
| `0xFF` | Ping | `seq:u32` |

Message types (sidecar → JVM):

| Code | Message | Payload |
|---|---|---|
| `0x80` | Link state push | `bpm:f64, beat:f64, phase:f64, peers:u16, playing:u8` |
| `0xB0` | Beat-at-time reply | `req_id:u32, beat:f64` |
| `0xB1` | Time-at-beat reply | `req_id:u32, time_us:i64` |
| `0x40` | Incoming OSC event | `addr_len:u16, address[addr_len], osc_len:u16, osc_data[osc_len]` |
| `0x61` | Incoming MIDI event | `port:u8, status:u8, data1:u8, data2:u8` |
| `0xFF` | Pong | `seq:u32` |

Request/response pairs (`0x30`/`0xB0`, `0x31`/`0xB1`) use a `req_id` to match replies
to callers. The JVM uses a `CompletableFuture` keyed by `req_id`; the sidecar answers
synchronously from the Link session state (non-blocking `captureAppSessionState`).

**Dispatch thread precision targets:**

| Platform | Sleep primitive | Thread priority |
|---|---|---|
| macOS | `mach_wait_until(deadline)` | `THREAD_TIME_CONSTRAINT_POLICY` |
| Linux | `clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, ...)` | `SCHED_FIFO`, `mlockall(MCL_CURRENT\|MCL_FUTURE)` |
| Windows | `CreateWaitableTimerEx` + MMCSS `Pro Audio` task | `SetThreadPriority(THREAD_PRIORITY_TIME_CRITICAL)` |

**I/O framework: Boost.Asio / standalone Asio**

The Ableton Link SDK uses Asio internally for its UDP peer-discovery and clock
synchronization networking. The Link repository bundles the standalone Asio headers
(the non-Boost variant, `asio::` namespace) under
`modules/link/include/ableton/platforms/asio/`. This is the same codebase as
Boost.Asio — identical API, different namespace prefix (`asio::` vs `boost::asio::`).

The sidecar uses this same Asio instance as its I/O foundation, sharing an
`asio::io_context` with Link. This means:
- Link's peer-discovery UDP sockets and the sidecar's OSC/IPC sockets are all
  serviced by the same event loop
- No additional I/O library is needed
- Developers familiar with Boost.Asio can work with zero ramp-up

If Boost is available and preferred (e.g., for other Boost utilities), switching
from standalone Asio to `boost::asio::` is a namespace substitution.

**Sidecar threading model:**

```
┌──────────────────────────────────────────────────────────┐
│  Asio thread pool (2–4 threads, normal priority)         │
│  ┌──────────────────────────────────────────────────┐    │
│  │  io_context (shared with Link SDK)               │    │
│  │  ├── Unix domain socket (IPC from/to JVM)        │    │
│  │  ├── UDP socket: OSC output (async_send_to)      │    │
│  │  ├── UDP socket: OSC input server (async_recv)   │    │
│  │  └── Link SDK internal sockets (peer discovery)  │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  RT dispatch thread (1 thread, SCHED_FIFO / TTC policy)  │
│  ├── Reads from priority_queue (lock-free pop)           │
│  ├── Sleeps via clock_nanosleep / mach_wait_until        │
│  ├── Calls RtMidi directly (synchronous CoreMIDI/ALSA)   │
│  ├── Calls link_.captureAudioSessionState() for timing   │
│  └── Posts OSC bundles to Asio for async_send_to        │
└──────────────────────────────────────────────────────────┘
```

The RT dispatch thread does **not** run inside the Asio thread pool. It uses
`clock_nanosleep`/`mach_wait_until` for precise sleeping, which is a blocking call
incompatible with Asio's event loop. Instead, the dispatch thread posts completed
OSC messages to the Asio `io_context` via `asio::post(io_ctx, ...)` for
non-blocking async delivery. This keeps the RT thread off the I/O path while
preserving the timing guarantee.

The event queue between the Asio pool (producer, inserting IPC-decoded events) and
the RT dispatch thread (consumer, firing events) uses a lock-free MPSC queue to avoid
priority inversion.

**Comparison to Sonic Pi's tau**

Sonic Pi's `tau` sidecar (Elixir/BEAM) offloads OSC I/O and precise scheduling to
the BEAM runtime — which has no stop-the-world GC and a cooperative preemptive
scheduler that provides ~1ms fairness. The architecture is:

```
Sonic Pi (Ruby/JVM) ──OSC bundles──► tau (BEAM) ──MIDI/OSC──► hardware
                                          │
                                       OSC server ──incoming OSC──► Ruby
```

The cljseq C++ sidecar implements the equivalent role: it is the GC-free, precise
delivery layer that the JVM cannot be. Replacing the BEAM with a C++ RT thread gives
comparable (and on macOS/Linux with `THREAD_TIME_CONSTRAINT_POLICY` / `SCHED_FIFO`,
potentially better) timing precision, while keeping the entire implementation in a
language the team knows deeply and that is the natural host for the Link SDK.

**Shared library and process topology**

The sidecar is not a monolith. Timing/MIDI/OSC dispatch and audio/plugin hosting
are separate concerns with different failure modes — a crashing CLAP plugin must
not disrupt MIDI delivery to hardware. The architecture is therefore:

```
libcljseq-rt (shared C++ static/dynamic library)
├── IPC protocol framing and transport (Unix socket)
├── SynthTarget abstract interface
├── LinkEngine (ableton::Link wrapper)
├── Asio I/O context management
├── OSC codec
├── Lock-free event queues (SPSC / MPSC)
└── Common logging and error handling

cljseq-sidecar  (links libcljseq-rt)    ← always running
├── MidiTarget  (RtMidi)
├── OscTarget   (UDP)
├── MIDI dispatch thread
├── Control-plane OSC server
└── IPC server  (Unix socket: /tmp/cljseq-sidecar.sock)

cljseq-audio    (links libcljseq-rt)    ← launched on demand
├── AudioEngine  (RtAudio)
├── ClapTarget
├── PdTarget     (libpd)
└── IPC server  (Unix socket: /tmp/cljseq-audio-N.sock)
```

Both processes join the same Ableton Link session independently (they are two Link
peers that happen to be on the same machine — the GHOST protocol handles it). The
JVM Clojure side maintains separate IPC connections to each process:

```
JVM ──IPC──► cljseq-sidecar   (MIDI, OSC, Link timeline queries)
    ──IPC──► cljseq-audio-0   (CLAP/libpd plugin 0 — e.g., Surge XT)
    ──IPC──► cljseq-audio-1   (CLAP/libpd plugin 1 — e.g., Plugdata)
```

Each process has the same IPC framing protocol (§3.5 message table). The JVM routes
events to the correct process based on the target ID registered at connection time.

This separation provides:
- **Failure isolation**: a crashing audio process does not affect MIDI delivery
- **Independent lifecycle**: audio processes are started and stopped per-performance
  without restarting the timing core
- **Security**: CLAP plugins run in a separate process address space; they cannot
  corrupt the timing sidecar's memory
- **Scalability**: multiple `cljseq-audio` processes can run simultaneously,
  each hosting different plugins

**Build system**: CMake 3.25+. `libcljseq-rt` is built as a static library shared
by both binaries. Dependencies as git submodules:
- Ableton Link SDK (header-only, includes standalone Asio)
- RtMidi (used by `cljseq-sidecar`)
- RtAudio (used by `cljseq-audio`)
- libpd (used by `cljseq-audio`)

The Clojure startup sequence launches `cljseq-sidecar` unconditionally and optionally
launches one or more `cljseq-audio` instances as needed.

---

## 4. Musical Abstractions

### 4.1 Pitch

Pitch is a rich value type, not a raw integer:

```clojure
{:step :C
 :octave 4
 :accidental nil          ; :sharp, :flat, :natural, :double-sharp, :double-flat
 :microtone 0             ; cents offset (-100 to 100)
 :spelling-inferred false} ; true when derived from MIDI/ps
```

Derived (computed) views: `:ps` (pitch space float), `:midi` (0–127), `:pitch-class` (0–11), `:frequency` (Hz).

Enharmonic equivalents (`C#4` vs `Db4`) are equal in pitch space but distinct in spelling. Spelling matters for voice-leading analysis; for MIDI output only `:ps`/`:midi` matters.

### 4.2 Duration

Duration is quarterLength-centric:

```clojure
{:quarter-length 2/3      ; Clojure ratio — exact tuplet arithmetic
 :type :eighth            ; :maxima :long :breve :whole :half :quarter :eighth :16th ...
 :dots 0
 :tuplet {:actual 3, :normal 2}}  ; 3 in the time of 2
```

### 4.3 Interval

```clojure
(interval :M3)    ; major third — diatonic
(interval 4)      ; 4 semitones — chromatic (spelling inferred)
(interval p1 p2)  ; from two pitches
```

Properties: `:semitones`, `:interval-class` (0–6), `:directed`, `:name`, `:diatonic` (quality + generic degree).

### 4.4 Scale

Scales as `IntervalNetwork` — directed weighted graphs of intervals, following the Music21 model:

```clojure
(def major-scale
  {:name :major
   :edges [{:from :low, :to 1, :semitones 2}
           {:from 1,    :to 2, :semitones 2}
           {:from 2,    :to 3, :semitones 1}
           {:from 3,    :to 4, :semitones 2}
           {:from 4,    :to 5, :semitones 2}
           {:from 5,    :to 6, :semitones 2}
           {:from 6,    :to :high, :semitones 1}]})
```

Key scale operations:
- `(scale/pitch-at scale root degree)` → Pitch
- `(scale/degree-of scale root pitch)` → integer
- `(scale/next-pitch scale root pitch direction)` → adjacent Pitch
- `(scale/pitches scale root min-pitch max-pitch)` → lazy seq of Pitches
- `(scale/derive pitches)` → ranked list of compatible scales

Bundled: 100+ named scales as step-interval vectors (from Overtone + Sonic Pi + Scala archive).

### 4.5 Chord

```clojure
(chord :C :major)          ; → #{:C4 :E4 :G4}
(chord :C :dom7)           ; → #{:C4 :E4 :G4 :Bb4}
(chord-at scale :C :I)     ; → chord on degree I of scale rooted at C
```

Chord properties: root, quality, inversion, voicing. Post-tonal: prime form, Forte class, interval vector.

Roman numeral API:
```clojure
(progression :C :major [:I :IV :V7 :I])
;; → [{:chord ..., :numeral :I, :degree 1} ...]
```

### 4.6 Tone Row

Serial operations as pure functions on integer vectors mod 12:

```clojure
(def row [0 11 3 4 8 7 9 6 1 5 2 10])

(retrograde row)           ; → [10 2 5 1 6 9 7 8 4 3 11 0]
(invert row)               ; → [0 1 9 8 4 5 3 6 11 7 10 2]
(retrograde-invert row)    ; → [2 10 7 11 6 3 5 4 8 9 1 0]
(transpose row 5)          ; → [5 4 8 9 1 0 2 11 6 10 7 3]
(row-matrix row)           ; → 12×12 matrix of all 48 forms
```

**Generalized tone rows** (Misha-style): rows over any scale, not just chromatic. A row is an ordered sequence of scale-degree indices where each degree appears at most once.

```clojure
(defn tone-row
  "Build a tone row over a given scale by specifying interval moves."
  [scale root moves]
  ...)

;; Navigate a tone row by interval
(defn advance [row-state interval]
  "Move to the next unused pitch in direction of interval.")
```

---

## 5. Sequencing Primitives

### 5.1 Pattern and Rhythm Orthogonality (NDLR model)

Pattern and rhythm are independent, composable values:

```clojure
;; Pattern: a sequence of note selectors (scale/chord indices, pitches, or fns)
(def pat (pattern [:chord 1 3 5 2 4]))       ; chord-relative indices
(def pat (pattern [:scale 1 3 5 8 5 3]))     ; scale-relative indices
(def pat (pattern [:pitch :C4 :E4 :G4]))     ; absolute pitches

;; Rhythm: a sequence of velocity values (nil = REST, :tie = TIE)
(def rhy (rhythm [100 nil 80 nil 100 nil 80 nil]))  ; 8-beat pattern

;; Motif: pattern + rhythm, played against a harmonic context
(motif pat rhy {:clock-div 1/8, :channel 1})
```

When `(count pattern) ≠ (count rhythm)`, the two cycle independently, creating evolving alignment. This is a first-class concept.

### 5.2 Euclidean Rhythm

```clojure
(euclid 16 5)         ; → [1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]
(euclid 16 5 2)       ; → rotated by 2 steps
(euclid 8 3)          ; Tresillo
(euclid 16 7)         ; common Latin/African pattern
```

Returns a binary vector; pairs with a velocity pattern and note selector.

### 5.3 Live Loop

```clojure
(live-loop :drums
  (with-harmony {:key :C :mode :major :degree :I}
    (play! (euclid-beat 16 5) {:channel 10})
    (sleep! 1)))
```

Redefinable at any time; picks up changes on the next iteration.

### 5.4 Harmonic Context

A dynamic binding that all note-generating functions resolve against:

```clojure
(with-harmony {:key :C :mode :dorian :degree :IV :chord-type :m7}
  (motif pat rhy {:channel 2}))
```

`with-harmony` is a Clojure `binding` form. Thread-local, inheritable by child threads at spawn time.

### 5.5 Generative Features

**Probabilistic triggers**:
```clojure
(when-prob 0.75 (play! note))  ; fires 75% of the time
```

**Probability per note** (T-1 style):
```clojure
(motif pat rhy {:probability 0.8})  ; each note has 80% chance
```

**Random with slew** (T-1 style):
```clojure
(randomize! :velocity {:amount 0.3 :slew true})  ; smooth random variation
```

**Phrase shapes** (T-1 style):
```clojure
(phrase {:shape :cadence-1 :range 7 :period 8})  ; pitch contour over 8 beats
```

**LFO modulation**:
```clojure
(lfo {:shape :sine :rate 8 :amount 30 :target :position :probability 0.6})
(lfo {:shape :pattern :data [1 5 3 8 2] :rate 4 :target :velocity})
```

**Modulation routes** (NDLR matrix):
```clojure
(mod-route {:source (lfo {:shape :random :rate 4})
            :dest   :chord-degree
            :amount 40})
```

**Cycles** (T-1 meta-parameter sequencer):
```clojure
(cycles [{:velocity 80, :pitch 0}
         {:velocity 120, :pitch 5}
         {:velocity 60, :pitch -3}])
```

### 5.6 Chord Progression

```clojure
;; Explicit
(chord-seq [{:duration 2 :degree :I}
            {:duration 2 :degree :IV}
            {:duration 4 :degree :V7}
            {:duration 2 :degree :I}])

;; Voice-leading navigation (Misha/T-1 style)
(next-voicing!)    ; move one step through voice-leading space
(prev-voicing!)
```

### 5.7 Tone Row Sequencing (Misha model)

```clojure
;; Record a tone row by specifying interval moves
(def my-row
  (tone-row {:scale :minor :key :A}
            [+1 +2 -1 +3 +1 -2]))

;; Playback options
(play-row my-row {:mode :prime})       ; forward
(play-row my-row {:mode :retrograde})  ; backward
(play-row my-row {:mode :pendulum})    ; bounce
(play-row my-row {:mode :random})      ; random order

;; Live interval navigation
(defn row-loop [state]
  (let [[note next-state] (advance state (rand-nth [-2 -1 0 +1 +2]))]
    (play! note)
    (sleep! 1/2)
    (recur next-state)))
```

---

## 6. Control Interfaces

*The abstract model underlying all control interfaces is defined in §16. This section
covers the MIDI-specific surface. The §16 model supersedes the flat `midi-cc-map`
approach originally sketched here.*

### 6.1 MIDI as a Control Protocol Adapter

MIDI is a narrow-bandwidth, **address-less** protocol: CC 74 has no inherent meaning.
cljseq's control tree (§16) provides the address space; MIDI binding declarations map
CC numbers and note events onto tree paths.

```clojure
;; CC → value node
(ctrl/bind! {:midi/cc 74} "/cljseq/global/harmony/key"
  :transform (fn [v] (nth keys-in-fifths-order (int (* v 12/127)))))

(ctrl/bind! {:midi/cc 72 :midi/channel 16} "/cljseq/global/bpm"
  :transform (fn [v] (+ 60.0 (* v 180.0/127))))

;; Note-on → method node (Misha white-key interval model)
(ctrl/bind! {:midi/note-on 60 :midi/channel 16} "/cljseq/tone-row/advance"
  :args-fn (fn [vel] {:interval +1}))
(ctrl/bind! {:midi/note-on 62 :midi/channel 16} "/cljseq/tone-row/advance"
  :args-fn (fn [vel] {:interval +2}))

;; Note-on → trigger a live-loop restart
(ctrl/bind! {:midi/note-on 36 :midi/channel 16} "/cljseq/loops/bass/trigger")
```

Full binding model and rationale in §16.2.

### 6.2 MIDI Sync

- Receive MIDI clock (24 PPQ) and align virtual time to it (sidecar → JVM)
- Receive MIDI Start/Stop/Continue; Start/Stop sync bridges to Link transport (§15.6)
- Receive Song Position Pointer for chase/locate
- Transmit MIDI clock and transport when operating as master (generated in sidecar
  from Link timeline or local BPM)

### 6.3 MPE (MIDI Polyphonic Expression)

For microtonal and expressive output, allocate notes across MIDI channels for per-note
pitch bend, pressure, and timbre:

```clojure
(with-mpe {:channels (range 1 16) :pb-range 48}
  (play! chord))  ; each note gets its own channel with independent pitch bend
```

---

## 7. Tuning and Microtonality

### 7.1 Scala Format Support

`.scl` and `.kbm` files are the standard for microtonal tuning. Both formats parse trivially from plain text.

```clojure
;; Parse a .scl file → {:description "..." :intervals [0.0 204.0 386.3 ... 1200.0]}
(scala/parse-scl (slurp "my-scale.scl"))

;; Parse a .kbm file → {:map-size 12, :middle-note 60, :reference-note 69,
;;                       :reference-freq 440.0, :octave-degree 12,
;;                       :mapping [0 1 2 3 4 5 6 7 8 9 10 11]}
(scala/parse-kbm (slurp "my-mapping.kbm"))

;; Build a frequency table: MIDI note → Hz
(scala/build-freq-table scl kbm)

;; Compute pitch bend + note for a given MIDI note and tuning
(scala/retune-note 64 scl kbm {:pb-range 2})
;; → {:note 64, :pitch-bend 4823}
```

Bundle a subset of the 5,200+ Scala archive. Users reference scales by name:
```clojure
(use-tuning "meantone")
(use-tuning "pelog")
(use-tuning "partch_43")
```

### 7.2 OSC Frequency Output

When targeting SuperCollider or VCV Rack via OSC, skip MIDI pitch bend entirely — send Hz directly:

```clojure
;; In a SuperCollider context, send frequency as a float OSC parameter
(with-tuning pelog-scale
  (play-sc! :default {:freq (midi->hz 64)}))  ; exact Hz, no pitch bend needed
```

### 7.3 Runtime Tuning Swapping

Tuning is thread-local state swappable without stopping playback:

```clojure
(def current-tuning (atom standard-12tet))

;; Live: swap tuning; loops pick it up on next note
(reset! current-tuning (scala/load "partch_43.scl"))
```

---

## 8. VCV Rack Integration

VCV Rack accepts MIDI and OSC. No native code required from the Clojure side.

**Via MIDI (primary path)**:
- Create a virtual MIDI port (IAC on macOS, JACK on Linux)
- VCV Rack's Core `MIDI-CV` module converts MIDI to V/oct + Gate CV
- `cljseq` sends standard MIDI note messages; Rack handles D/A conversion to CV

**Via OSC (precision path)**:
- Install `trowaSoft cvOSCcv` or `OSC'elot` in VCV Rack
- `cljseq` sends float-precision OSC messages to Rack's OSC listener
- Address scheme: `/rack/module/param-name value` (OSC'elot) or `/tsseq/edit/step/N value` (trowaSoft)
- No 7-bit CC resolution limitation — full 32-bit float

**Misha as CV bridge**:
```
cljseq (MIDI) → Misha → 3× CV Out pairs → VCV Rack CV inputs
```
This uses Misha's interval/scale engine as a transformation layer. cljseq can set the anchor pitch via `PassThru/ActiveLastNote` mode, then let Misha's interval navigation drive the CV output.

---

## 9. Stream and Score Model

Drawing from Music21, a `Stream` is the fundamental container for musical content — a persistent sorted map of offset → events, with hierarchical nesting:

```
Score
  └─ Part
       └─ Measure
            └─ Voice
                 └─ Note | Chord | Rest
```

Operations:
- `(stream/flatten score)` — collapse hierarchy, renormalize offsets to absolute
- `(stream/chordify part)` — salami-slice to vertical chord snapshots at every change
- `(stream/at offset stream)` — all sounding elements at a given offset (Verticality)
- `(stream/transpose stream interval)` — deep-copy with all pitches transposed
- `(stream/retrograde stream)` — reverse temporal order

This stream model serves both real-time sequencing (events consumed and dispatched by the scheduler) and offline score generation (render to MIDI file, MusicXML, LilyPond).

---

## 10. Analysis Primitives

### 10.1 Key Detection

```clojure
(analyze/key events)
;; → [{:key :G, :mode :major, :correlation 0.87}
;;    {:key :E, :mode :minor, :correlation 0.84}
;;    ...]
```

Krumhansl-Schmuckler and Aarden-Essen profiles. Useful for auto-detecting key from an incoming MIDI stream and applying scale-aware sequencing.

### 10.2 Roman Numeral Analysis

The `:I`…`:VII` keyword vocabulary is the programmatic equivalent of the
Conductive Labs NDLR's physical Roman numeral chord buttons (§2.3): the key and
mode establish context, and the keyword selects a harmonic position within it.
The NDLR demonstrates that this degree-relative model is already the natural way
musicians think about chord navigation in a live performance setting.

```clojure
(analyze/roman-numeral chord key)
;; → {:numeral :IV, :figure "IV", :scale-degree 4, :functionality 72}
```

### 10.3 Voice Leading

```clojure
(analyze/voice-leading voice1 voice2)
;; → {:parallel-fifths? false, :parallel-octaves? false,
;;    :motion :contrary, :violations []}
```

### 10.4 Post-Tonal

```clojure
(analyze/prime-form #{0 3 7})       ; → [0 3 7]
(analyze/forte-class #{0 3 7})      ; → "3-11A"
(analyze/interval-vector #{0 3 7})  ; → [0 0 1 1 1 0]
```

---

## 11. Corpus

A built-in corpus of symbolic music (Bach chorales, folk songs, etc.) queryable as data:

```clojure
(corpus/search {:composer "Bach" :time-signature "6/8"})
(corpus/search {:key-signature [:sharps 3]})
(corpus/search {:num-notes [400 600]})

;; Find a chord progression matching a description
(corpus/find-progression {:key :C :mode :major :degrees [:I :IV :V :I]})
```

---

## 12. DSL Design Principles

Drawing from all prior art, the following principles guide DSL design:

1. **Harmony-relative by default**: Note patterns reference scale/chord positions, not absolute pitches. The harmonic context is a dynamic binding.

2. **Pattern and rhythm are orthogonal values**: Composable, independently specified, combinable at runtime.

3. **Euclidean rhythm is a primitive**: `(euclid n k r)` is a first-class rhythm constructor.

4. **Virtual time, not wall time**: `sleep!` is a temporal barrier. All musical calculations use the rational beat counter.

5. **Thread-local state with inheritance**: `binding` + capture at spawn. Child threads inherit parent musical context.

6. **`cue`/`sync` as primary coordination**: Not channels, not shared atoms. Events carry logical time, enabling beat-aligned coordination.

7. **Immutable data structures everywhere**: Pitches, durations, patterns, rhythms are values. No `inPlace=True` patterns.

8. **Tone rows and interval navigation as first-class**: The Misha model — intervals over scale contexts, tone row state machines — is a first-class sequencing paradigm alongside step sequencing.

9. **Scala tuning as a standard feature**: `.scl`/`.kbm` parsing, frequency table computation, pitch bend retuning, and named scale library.

10. **MIDI control is compositional**: Every sequencer parameter is addressable via MIDI CC/Note. MIDI handlers can trigger arbitrary Clojure code, not just parameter updates.

11. **Post-tonal operations are pure functions**: Prime form, Forte class, row transformations — no hidden state.

12. **Soft deadlines with graceful degradation**: Overruns emit warnings; extreme overruns self-terminate the thread. No hard real-time requirements.

13. **Named vocabularies for musical idioms**: Groove profiles, phrase shapes, chord types, scale names — use names, not raw numbers, wherever musically meaningful.

14. **Generativity is parameterized**: Randomness has `amount`, `slew`, `probability`, `phase` — not an ad-hoc `rand` call.

---

## 13. Open Questions and Future Research

1. **Scheduler sidecar**: ~~Should `cljseq` maintain a thin native sidecar?~~ **Resolved: yes, a native C++ sidecar handles all real-time dispatch. See §3.5 for the full design.** C++ was chosen over Rust/Go because (a) the Ableton Link SDK is C++, (b) RtMidi is C++, and (c) the team has deep low-latency C++ expertise. A JVM-only `ScheduledThreadPoolExecutor` is insufficient due to GC pause unpredictability.

2. **nREPL integration depth**: ~~Should `cljseq` extend nREPL with custom ops?~~
   **Resolved: standard nREPL with optional lightweight middleware; the control tree
   is the primary state model.**

   The question was: should cljseq add custom nREPL operations (clock status, parameter
   inspection, loop management) or rely on standard `eval`? The design decisions made
   since §13 was written answer it:

   - **The control tree (§16)** is the canonical state model. All system state —
     BPM, active loops, harmonic context, synthesizer parameters — is addressable at
     `/cljseq/...` paths. Parameter inspection and real-time status are already served
     by the HTTP/OSC/WebSocket control-plane server (§17).

   - **nREPL remains the Clojure developer's primary interface** for writing and
     evaluating live-loop code. No custom nREPL ops are needed for the core workflow:
     you call `(ctrl/set! ...)`, `(live-loop ...)`, `(m21/analyze-key ...)` — ordinary
     Clojure eval.

   - **Optional `cljseq-nrepl` middleware**: a thin middleware layer can inject
     cljseq context into nREPL sessions (current BPM in the prompt, `ctrl/describe`
     for a selected path on demand) and provide a custom op
     `cljseq/watch path callback-op` that pushes tree-change notifications to the
     connected editor. This is non-blocking development — implement after the core
     is stable.

   - **MCP (§18) covers AI-assistant interaction**. AI agents use `tools/call` on
     method nodes and `resources/read` on value nodes — no nREPL access is needed.

   The three front-ends and their audiences:

   | Interface | Audience | Primary use |
   |---|---|---|
   | nREPL | Clojure developer | Write and eval live-loop code |
   | HTTP/OSC/WS (§17) | Hardware controllers, browsers | Real-time parameter control |
   | MCP (§18) | AI assistants | Composition assistance, parameter exploration |

3. **Score rendering**: ~~Is offline score rendering in scope?~~ **Resolved: yes,
   via the Music21 Python sidecar (§20).** `(m21/export-xml ...)`, `(m21/export-midi
   ...)`, and `(m21/show ...)` provide MusicXML, MIDI file, and live notation display
   (MuseScore/LilyPond) without any Clojure-native rendering implementation.

4. **Corpus depth**: ~~Which works to bundle?~~ **Resolved: no bundling; corpus
   access is delegated to Music21 (§20.10).** The Music21 corpus (~4,000 works
   including all 371 Bach chorales and the Essen folksong collection) is available
   at runtime via `m21/search-corpus` and `m21/parse-corpus`. Non-Western corpora
   are supported via Music21's VirtualCorpus extension (§21.7).

5. **Ableton Link**: ~~Bidirectional tempo/phase sync with other Link-enabled devices.~~ **Resolved: elevated to first-class requirement. See §15 for full analysis and design.**

6. **VCV Rack module**: A native C++ VCV Rack plugin that exposes a high-bandwidth OSC interface (or a JACK MIDI bridge) would provide tighter Rack integration. Out of scope for the initial implementation but worth specifying.

7. **Visual feedback**: Real-time display of virtual time, beat, active loops, harmonic context. Could be a TUI (via clojure.lanterna or similar) or an nREPL client extension.

8. **Microtonality in polyphony**: With standard MIDI, polyphonic microtonality requires one channel per note (or MPE). How many simultaneous microtonal voices can `cljseq` support practically? 16 channels = 16 voices max.

9. **Generative models**: Machine-learning-based melody generation (Markov chains, RNNs trained on the corpus) as optional generation primitives. Music21's feature extraction provides training data.

---

## 14. Immediate Next Steps

1. Define the core namespace structure (`cljseq.time`, `cljseq.pitch`, `cljseq.scale`, `cljseq.rhythm`, `cljseq.midi`, `cljseq.osc`, `cljseq.seq`, `cljseq.harmony`, `cljseq.tuning`, `cljseq.analyze`)
2. Implement the virtual time / `sleep!` / thread model
3. Implement the `Pitch` value type with full Music21-style separation of concerns
4. Implement scale as IntervalNetwork, port Overtone's scale/chord data
5. Implement `javax.sound.midi` wrapper with priority-queue scheduled delivery
6. Implement OSC peer (port or adapt Overtone's `osc/` namespace)
7. Implement `live-loop`, `in-thread`, `cue!`, `sync!`
8. Implement Scala `.scl`/`.kbm` parser and frequency table computation
9. Implement Euclidean rhythm and basic pattern/rhythm orthogonality
10. Implement basic MIDI control surface (CC map, note-to-function map)

---

## 15. Ableton Link Integration

Ableton Link is a first-class requirement for `cljseq`. This section provides a
comprehensive analysis of the protocol, JVM integration options, and the design
for aligning Link's beat timeline with cljseq's virtual time model.

### 15.1 Protocol Overview

Ableton Link is an open-source (MIT) protocol and SDK for tempo/beat/phase
synchronization across networked applications on a LAN. It was developed by Ableton
and is supported by Ableton Live, Bitwig Studio, Sonic Pi, VCV Rack, and many others.

SDK repository: `https://github.com/Ableton/link` (C++, MIT license)

**What Link synchronizes**

Link maintains a shared *beat timeline* — a monotonically increasing mapping from
host time (microseconds since epoch) to beat number (floating point). All peers
contributing to a Link session share this timeline through distributed consensus.
Link does **not** synchronize individual note events; it synchronizes the tempo grid
so that each peer independently computes where any given beat falls in wall time.

**Session state**

Each peer has a `SessionState` consisting of:
- `tempo` — beats per minute (floating point)
- `beatAtTime(hostTime, quantum)` — beat number at a given host time, quantized to
  the nearest multiple of `quantum`
- `phaseAtTime(hostTime, quantum)` — position within the current quantum: a value in
  `[0, quantum)`
- `timeAtBeat(beat, quantum)` — host time at which a given beat will occur
- `isPlaying` — transport state (if start/stop sync is enabled)

**Quantum**

The quantum is a caller-supplied integer specifying the phrase length in beats
(typically 4 for 4/4 time). Link ensures that all peers' beat positions are equivalent
modulo the quantum — i.e., two peers at beat 1.5 and beat 9.5 with quantum 4 are
"in phase." When a peer joins a session, its beat timeline is adjusted to match
the quantum grid of the existing session rather than a hard beat-1 reset.

**Peer discovery**

Link uses UDP multicast on `224.76.78.75:20808` for peer discovery. No configuration
is required; peers on the same LAN find each other automatically. The underlying
synchronization protocol (GHOST — Globally Hinged Oscillator Synchronization
Technology) uses a distributed clock synchronization algorithm derived from
IEEE 1588 PTP concepts, achieving typical accuracy of < 1ms on a local network.

**Tempo negotiation**

Any peer can propose a tempo change; the session converges on the new tempo within
one or two network round trips. The beat timeline is adjusted to remain continuous
through tempo changes (no beat-number discontinuity).

### 15.2 C++ Sidecar Integration (No Carabiner Required)

Because `cljseq` uses a native C++ dispatch sidecar (§3.5), the Link SDK is
integrated directly into the sidecar — not through a Carabiner intermediary. The
sidecar *is* the Carabiner equivalent, plus the MIDI/OSC dispatcher.

**Architecture:**
```
cljseq (JVM) ──Unix socket──► cljseq-sidecar (C++) ──Link SDK──► Link session (LAN)
                                       │
                                       ├──RtMidi──► MIDI hardware
                                       └──UDP──────► OSC targets
```

This eliminates an entire process hop compared to the Carabiner approach and allows
`captureAudioSessionState()` — the non-blocking, real-time-safe Link API — to be
called directly from the sidecar's dispatch thread. This is the correct usage: the
Link documentation explicitly states that `captureAudioSessionState()` is designed
for audio/RT threads, while `captureAppSessionState()` is for UI threads.

**Link SDK integration in the sidecar:**

```cpp
#include <ableton/Link.hpp>

class LinkEngine {
    ableton::Link link_;
    double quantum_;

public:
    explicit LinkEngine(double initial_bpm, double quantum = 4.0)
        : link_(initial_bpm), quantum_(quantum)
    {
        link_.enable(true);
        link_.setNumPeersCallback([](std::size_t peers) { /* notify JVM */ });
        link_.setTempoCallback([](double bpm) { /* notify JVM */ });
        link_.setStartStopCallback([](bool playing) { /* notify JVM */ });
    }

    // Called from the dispatch thread (RT-safe)
    std::chrono::microseconds time_at_beat(double beat) const {
        const auto state = link_.captureAudioSessionState();
        return state.timeAtBeat(beat, quantum_);
    }

    double beat_at_time(std::chrono::microseconds t) const {
        const auto state = link_.captureAudioSessionState();
        return state.beatAtTime(t, quantum_);
    }

    double phase_at_time(std::chrono::microseconds t) const {
        const auto state = link_.captureAudioSessionState();
        return state.phaseAtTime(t, quantum_);
    }

    // Called from app thread (non-RT: IPC handler thread)
    void set_bpm(double bpm) {
        auto state = link_.captureAppSessionState();
        state.setTempo(bpm, link_.clock().micros());
        link_.commitAppSessionState(state);
    }
};
```

The `LinkEngine` instance lives inside the sidecar. The dispatch thread calls
`time_at_beat` directly — zero IPC overhead for the timing-critical path.

**Link state pushed to JVM**: The sidecar's tempo/peer/transport callbacks push
state-update messages (type `0x80`) over the Unix socket to the JVM asynchronously.
The JVM uses these for display and for `sleep!` quantum alignment queries (which use
the request/response protocol, types `0x31`/`0xB1`).

**Link clock and `System/nanoTime` alignment**:

Link's clock is in microseconds since an arbitrary epoch. `System/nanoTime` is also
arbitrary-epoch nanoseconds. The sidecar exposes a `host_time_us()` call (via IPC
type `0x30` with a `time_us` of 0 special-cased as "give me current time") that
returns `link_.clock().micros()` so the JVM can align its nanosecond timeline with
Link's microsecond timeline. In practice:

```
link_host_offset_ns = (link_.clock().micros() * 1000) - System.nanoTime()
```

This offset, computed once at sidecar attach time, translates between the two clocks.

### 15.3 Timeline Alignment with Virtual Time

The central design challenge: `cljseq`'s `sleep!` computes its target wall time from
`*beat* + beats-to-sleep × beat-duration-ns` using the thread-local `*bpm*`. When
Link is active, the source of truth for "when does beat N happen?" shifts from the
local BPM calculation to Link's `timeAtBeat` query. These two must be reconciled.

**Core insight**: when Link is active, `sleep!` should ask Link for the wall time of
the target beat rather than computing it locally. Local BPM is then a read-only
shadow of the Link session tempo — useful for display and for initial setup, but not
used in timing calculations.

**`sleep!` with Link active:**

```clojure
(defn sleep! [beats]
  (let [target-beat  (+ *beat* beats)
        target-ns    (if (link/active?)
                       ;; Ask Link: when does target-beat occur, in host nanos?
                       (link/time-at-beat target-beat *quantum*)
                       ;; Fall back to local BPM calculation
                       (+ *start-time* (* target-beat (beat->ns *bpm*))))]
    (set! *beat* target-beat)
    (set! *virtual-time* target-ns)
    (let [delta (- target-ns (System/nanoTime))]
      (when (pos? delta)
        (LockSupport/parkNanos delta)))))
```

Note: `link/time-at-beat` returns host time in microseconds (Link convention);
convert to nanoseconds before comparison with `System/nanoTime`.

**Initial sync when joining a Link session:**

When `(link/enable!)` is called, or when a `live-loop` starts while Link is active,
the thread must align its beat counter to the Link timeline:

```clojure
(defn link-align-beat! []
  (let [now-us   (link/host-time-us)   ; microseconds, per Link convention
        now-beat (link/beat-at-time now-us *quantum*)]
    (set! *beat* now-beat)
    (set! *virtual-time* (* now-us 1000))))  ; convert to ns
```

This is called once at loop start (or inside `live-loop`'s preamble when Link is
active) so that beat arithmetic from that point forward is aligned to the session.

### 15.4 Phase-Quantized Loop Launch

A key Link feature is that loops (phrases) should start on a *quantum boundary* — the
next beat that is a multiple of `*quantum*`. This ensures that a loop started at any
time joins the session in phase with all other peers, avoiding mid-bar starts.

```clojure
(defn next-quantum-beat
  "Return the beat number of the next quantum boundary at or after `beat`."
  [beat quantum]
  (* quantum (Math/ceil (/ (double beat) quantum))))

(defmacro live-loop [name & body]
  `(let [start-beat# (if (link/active?)
                       (next-quantum-beat
                         (link/beat-at-time (link/host-time-us) *quantum*)
                         *quantum*)
                       *beat*)]
     ;; Park until Link's timeline reaches start-beat
     (when (link/active?)
       (let [start-ns# (* (link/time-at-beat start-beat# *quantum*) 1000)]
         (LockSupport/parkNanos (- start-ns# (System/nanoTime)))))
     (set! *beat* start-beat#)
     ;; ... loop body ...
     ))
```

This gives the same UX as Sonic Pi's Link integration: redefining a live-loop causes
it to re-enter on the next bar boundary, staying in sync with the session.

### 15.5 BPM Change Propagation

Tempo changes from other Link peers arrive asynchronously via Carabiner's push
notifications. These must propagate to running threads without requiring them to poll.

**Problem**: `*bpm*` is a thread-local dynamic binding. A Carabiner push update
arriving in a listener thread cannot update `*bpm*` in 10 other running `live-loop`
threads.

**Solution**: When Link is active, threads do not use `*bpm*` for `sleep!`
calculations at all — they always query Link's `timeAtBeat`. Therefore, a tempo
change propagates *implicitly* on the next `sleep!` call: each thread's sleep target
is recomputed from the updated Link timeline. No explicit BPM broadcast is needed.

`*bpm*` is updated in a global atom for display purposes:

```clojure
(def link-bpm (atom nil))  ; nil when Link is inactive

;; Carabiner listener updates the display atom
(on-carabiner-status
  (fn [{:keys [bpm]}]
    (reset! link-bpm bpm)))

;; In the REPL display / status line, show link-bpm when active
```

Thread-local `*bpm*` is still used for non-Link timing and for operations that need
to reason about "current tempo" (e.g., converting beat durations to milliseconds for
user display). When Link is active, `*bpm*` should be synced to `@link-bpm` at the
start of each loop iteration via a hook in `live-loop`.

### 15.6 Start/Stop Synchronization

Link supports an optional start/stop transport sync. When enabled, pressing Play on
any peer starts all peers simultaneously (at the next quantum boundary); pressing Stop
stops all peers.

```clojure
(link/enable-start-stop-sync! true)

;; Transport commands
(link/play!)    ; request play in Link session → triggers all peers
(link/stop!)    ; request stop in Link session
```

`live-loop` should respect the Link transport state: if the session is stopped,
loops pause at their next `sleep!` boundary (not immediately — they complete the
current event, then park waiting for a play event).

```clojure
;; Inside sleep! when Link is active and start/stop sync is enabled
(when (and (link/active?) (link/start-stop-sync?) (not (link/playing?)))
  (link/wait-for-play!))  ; park until transport start
```

This mirrors the behavior of Bitwig and Sonic Pi: all participants start together on
the downbeat.

### 15.7 MPE and Per-Voice Timing with Link

Link synchronizes the beat grid; it does not affect per-note MIDI delivery. MPE note
events are still scheduled via the priority-queue dispatcher with `sched-ahead-ns`
lead time. The only change is that the beat timestamps used to compute note delivery
times are derived from Link's timeline rather than local BPM arithmetic.

### 15.8 Namespace Design

The Clojure `cljseq.link` namespace communicates with the Link engine in the sidecar
via the IPC protocol defined in §3.5. All timeline queries go through the sidecar's
request/response channel; state pushes arrive asynchronously.

```clojure
;; cljseq.link — Link integration via C++ sidecar IPC
(ns cljseq.link
  (:require [cljseq.sidecar :as sidecar]))

;; Lifecycle (sidecar must already be running)
(defn enable!
  "Join or create a Link session. quantum is the phrase length in beats."
  [& {:keys [quantum] :or {quantum 4}}])
(defn disable! [])
(defn active? [])

;; Timeline queries (request/response to sidecar, IPC types 0x30/0x31)
(defn host-time-us
  "Current host time in microseconds, per Link's clock."
  [] ...)
(defn beat-at-time
  "Beat number at given host time (µs), quantized to quantum."
  [time-us quantum] ...)
(defn time-at-beat
  "Host time (µs) at which the given beat will occur."
  [beat quantum] ...)
(defn phase-at-time
  "Position in [0, quantum) at given host time."
  [time-us quantum] ...)

;; State (from most-recent push, IPC type 0x80 — no round-trip needed)
(defn bpm    [] ...)   ; current session BPM (from last push)
(defn peers  [] ...)   ; number of Link peers
(defn playing? [] ...) ; transport state (if start/stop sync enabled)

;; Proposals to the session (fire-and-forget to sidecar)
(defn set-bpm! [bpm] ...)
(defn force-beat-at-time! [beat time-us quantum] ...)

;; Transport
(defn enable-start-stop-sync! [enabled] ...)
(defn play! [] ...)
(defn stop! [] ...)

;; Integration hooks (called by sleep! and live-loop internals)
(defn align-beat!
  "Set *beat* and *virtual-time* to match the current Link timeline."
  [] ...)
(defn next-quantum-start-ns
  "Wall-clock nanoseconds of the next quantum boundary."
  [] ...)
```

The `cljseq.time/sleep!` implementation checks `(link/active?)` and branches at
runtime: when Link is active, `time-at-beat` replaces the local BPM formula. The
virtual time model structure is otherwise identical.

### 15.9 Practical Accuracy and Limitations

| Property | Value |
|---|---|
| Sync accuracy (LAN, wired) | < 1ms typical (GHOST protocol) |
| Sync accuracy (WiFi) | 1–5ms typical (higher jitter) |
| IPC overhead (Unix socket) | < 0.1ms per round-trip on localhost |
| Link timeline query (sidecar) | < 0.01ms (in-process function call in C++) |
| Maximum peers | No hard limit; tested to 20+ |
| Minimum quantum | 1 beat |
| JVM GC interaction | GC pause may delay `sleep!` return, causing overrun in the *generating* thread. Event delivery is unaffected — it occurs in the C++ sidecar's RT thread, which has no GC. The loop rejoins at the next quantum boundary. |

**JVM GC and delivery**: With the C++ sidecar, JVM GC pauses affect only the
*generating* thread (the Clojure `live-loop`). The sidecar's dispatch thread runs
independently and delivers events exactly at their scheduled times regardless of JVM
GC. This is the fundamental improvement over the JVM-only approach: JVM GC pauses are
absorbed by the `sched-ahead-ns` budget on the input side; they do not degrade output
timing.

**WiFi note**: Link requires multicast UDP. Many home WiFi access points block
multicast between devices on the same SSID. For reliable Link sessions, use wired
Ethernet or a router with multicast forwarding enabled. Document this prominently.

### 15.10 Implementation Phasing

Link integration is part of the C++ sidecar build from the start. It is not a
separate phase that bolts on later — the Link SDK is included in the initial sidecar
CMakeLists.txt so that the temporal foundation is correct from day one.

1. **Sidecar Phase 1** (with core timing): Link SDK as submodule. `LinkEngine` class
   instantiated in sidecar. IPC messages `0x30`/`0xB0` (beat-at-time) and
   `0x31`/`0xB1` (time-at-beat) implemented. `0x80` state-push on tempo/peer change.
   Verify: connect two machines running cljseq, confirm beat alignment < 1ms.

2. **Sidecar Phase 2**: `sleep!` delegates to Link when active. Test with Ableton Live
   and Bitwig Studio — confirm loops stay in phase across a 10-minute session.

3. **Sidecar Phase 3**: Phase-quantized `live-loop` launch. `next-quantum-start-ns`
   implemented using `time_at_beat(ceil_to_quantum(beat_now, quantum))`. Test:
   redefine a `live-loop` while Link peers are running; confirm it re-enters on the
   downbeat.

4. **Sidecar Phase 4**: Start/stop sync. IPC types `0x22`/`0x23`/`0x24`. `live-loop`
   pauses at next `sleep!` boundary when transport is stopped; resumes on play.

5. **Hardening**: Validate MIDI clock output phase-locked to Link timeline. Test WiFi
   degradation (simulate 5ms jitter). Measure actual dispatch jitter on macOS and
   Linux using the RT thread model specified in §3.5.

---

## 16. Unified Control Model

### 16.1 Motivation: From Flat Maps to a Control Tree

The original §6 sketch described MIDI control as a flat map from CC numbers to
parameter keywords:

```clojure
(midi-cc-map {:73 :key, :74 :mode, :26 :chord-degree ...})
```

This works for simple cases but has fundamental limitations:

1. **No address space**: `73` and `:key` are both opaque identifiers. Their relationship
   is documented only in the map literal. Nothing in the system can reason about what
   parameters exist, what their types or ranges are, or which are currently bound.

2. **Protocol coupling**: MIDI CC and OSC are handled by separate code paths. A
   parameter changed via MIDI is not observable via OSC and vice versa.

3. **No introspection**: a hardware controller that wants to display current values,
   or a TUI that wants to show active parameters, has nowhere to look.

4. **Functions are second-class**: `on-cc` handler registration is ad-hoc. There is
   no unified model for "this thing can be called with these arguments."

The OSC specification implicitly defines the right abstraction: an **address-space
tree** where leaf nodes are callable methods and interior nodes are containers.
Every OSC message is an address + arguments directed at a node. This model, generalized
and made first-class in cljseq, resolves all four problems above.

### 16.2 The Control Tree

The control tree is a hierarchical namespace of **nodes**. Three node types:

**Value node** — holds a current value; accepts reads and writes.

```
/cljseq/global/bpm           :float  [20.0 300.0]  default: 120.0
/cljseq/global/harmony/key   :keyword              default: :C
/cljseq/global/harmony/mode  :keyword              default: :major
/cljseq/loops/bass/velocity  :int     [0 127]       default: 100
/cljseq/loops/bass/mute      :bool                 default: false
```

**Method node** — a callable Clojure function with a declared argument schema.

```
/cljseq/loops/bass/trigger   fn()         → start or restart the bass loop
/cljseq/loops/bass/stop      fn()         → stop the bass loop
/cljseq/tone-row/advance     fn(interval) → step the tone row by interval
/cljseq/harmony/next-chord   fn()         → advance chord progression one step
```

**Container node** — an interior node grouping children; addressable but not
directly callable or settable.

```
/cljseq
/cljseq/global
/cljseq/global/harmony
/cljseq/loops
/cljseq/loops/bass
```

The tree is backed by a `ConcurrentHashMap<String, Node>` on the Clojure side. Value
nodes hold a `clojure.lang.Atom`; method nodes hold a `clojure.lang.IFn`. The atom
provides CAS-safe concurrent reads and writes from multiple threads (live-loops, MIDI
handler threads, OSC handler callbacks).

### 16.3 Protocol Adapters

The tree is the single source of truth. Protocol adapters translate their native
wire format into tree operations.

**OSC — natural mapping (no configuration required)**

An OSC address is a tree path. An incoming OSC message routes directly:

```
OSC:  /cljseq/global/harmony/key "G"  →  (ctrl/set! "/cljseq/global/harmony/key" :G)
OSC:  /cljseq/global/bpm 140.0        →  (ctrl/set! "/cljseq/global/bpm" 140.0)
OSC:  /cljseq/loops/bass/trigger      →  (ctrl/call! "/cljseq/loops/bass/trigger")
```

OSC wildcards (`*`, `?`, `{a,b}`) dispatch to all matching nodes — one OSC message
can update a set of nodes simultaneously. This is how a single "mute all" OSC message
reaches every `/cljseq/loops/*/mute` node.

**MIDI — mapped binding**

MIDI carries no address. Binding declarations map MIDI events onto tree paths:

```clojure
;; Value bindings: CC → value node with a transform
(ctrl/bind! {:midi/cc 74} "/cljseq/global/harmony/key"
  :transform (fn [v] (nth keys-in-fifths-order (int (/ (* v 12) 127)))))

(ctrl/bind! {:midi/cc 72 :midi/channel 16} "/cljseq/global/bpm"
  :transform (fn [v] (+ 60.0 (* v (/ 180.0 127)))))

;; Method bindings: note-on → method node
(ctrl/bind! {:midi/note-on 60 :midi/channel 16} "/cljseq/tone-row/advance"
  :args-fn (fn [vel] {:interval +1}))

;; Interval keyboard (Misha model) — bind a range of notes as a batch
(ctrl/bind-range! {:midi/notes [60 68] :midi/channel 16}
                  "/cljseq/tone-row/advance"
  :args-fn (fn [note vel]
              {:interval (nth [-4 -3 -2 -1 0 +1 +2 +3 +4] (- note 60))}))
```

Bindings are stored in the tree itself as metadata on the target node, so
introspection shows both the node's current value and what controls it:

```clojure
(ctrl/describe "/cljseq/global/bpm")
;; → {:type :float :range [20.0 300.0] :current 120.0
;;    :bindings [{:source {:midi/cc 72 :midi/channel 16}
;;                :transform #fn ...}]}
```

**Link — implicit binding**

When Link is active, the sidecar pushes BPM updates (IPC `0x80`) that the JVM
reflects into the tree node `/cljseq/global/bpm`. This is the same mechanism as MIDI
binding; Link is just another adapter. The tree records which source last wrote a
value node, enabling conflict detection when multiple sources try to control the same
parameter simultaneously.

### 16.4 Live Loops as Tree Nodes

A `live-loop` declaration registers a container node with a standard set of children:

```clojure
(deflive-loop "/cljseq/loops/bass"
  {:velocity {:type :int    :range [0 127]  :default 100}
   :pattern  {:type :data                   :default default-pat}
   :mute     {:type :bool                   :default false}}
  (fn []
    (when-not (ctrl/get "mute")           ; relative path within loop subtree
      (play! (resolve-pattern (ctrl/get "pattern"))
             {:velocity (ctrl/get "velocity")}))
    (sleep! 1)))
```

`deflive-loop` automatically creates:
- `/cljseq/loops/bass` — container
- `/cljseq/loops/bass/velocity` — value node, backed by an atom
- `/cljseq/loops/bass/pattern` — value node
- `/cljseq/loops/bass/mute` — value node
- `/cljseq/loops/bass/trigger` — method node: start/restart
- `/cljseq/loops/bass/stop` — method node: stop
- `/cljseq/loops/bass/status` — value node: `:running | :stopped | :overrun`
- `/cljseq/loops/bass/beat` — value node: current beat counter (read-only, updated by loop)

The loop body calls `(ctrl/get "mute")` using a *path relative to its own subtree
root*. Absolute paths work too: `(ctrl/get "/cljseq/loops/bass/mute")`. Short
relative paths are the idiomatic in-loop form.

**Reading tree values at iteration boundaries**

Value nodes are atoms; `ctrl/get` is a deref with path lookup. Each loop iteration
reads the current value at the top of the iteration — not as a dynamic binding, not
pushed, not observed. This is the simplest possible model: pull on read. MIDI or OSC
updates the atom between iterations; the loop sees the new value on the next read.

### 16.5 Relationship to `with-harmony` and Dynamic Bindings

The tree does not replace dynamic bindings. The two levels serve different scopes:

| | Dynamic binding (`with-harmony`) | Control tree (`ctrl/get`) |
|---|---|---|
| **Scope** | Thread-local, for the duration of the `binding` form | Global shared state |
| **Override** | Explicit, lexically scoped | Any protocol can write at any time |
| **Use case** | Temporary local deviation from global state | Global parameter; controllable externally |
| **Concurrency** | No sharing; each thread's value is independent | CAS-safe atom; all threads see same value |

The typical pattern: the control tree holds the *default* harmony context; a
`with-harmony` form in a live-loop body can temporarily override it for that
iteration:

```clojure
(deflive-loop "/cljseq/loops/melody"
  {:key {:type :keyword :default :C}
   :mode {:type :keyword :default :major}}
  (fn []
    ;; Use the tree-global harmony, unless the loop has local overrides
    (with-harmony {:key  (ctrl/get "key")
                   :mode (ctrl/get "mode")}
      (motif pat rhy {:channel 1}))
    (sleep! 1)))

;; Now a MIDI CC change to /cljseq/loops/melody/key takes effect on next iteration
;; without stopping or restarting the loop
```

For global parameters (BPM, master key/mode, global scale), the tree atom is the
live value. `*bpm*` and `*harmony*` dynamic vars read from the tree atoms at the
start of each iteration when Link or external control is active:

```clojure
;; live-loop preamble (generated by deflive-loop)
(binding [*bpm*     (or @link-bpm (ctrl/get "/cljseq/global/bpm"))
          *harmony* (ctrl/get "/cljseq/global/harmony")]
  (loop-body-fn))
```

### 16.6 Introspection and Discovery

Because the tree is a first-class data structure, it is fully introspectable:

```clojure
;; List all nodes
(ctrl/ls "/cljseq")            ; → #{"/cljseq/global" "/cljseq/loops" "/cljseq/tone-row"}
(ctrl/ls "/cljseq/loops")      ; → #{"/cljseq/loops/bass" "/cljseq/loops/drums"}

;; Read a node's full description
(ctrl/describe "/cljseq/loops/bass/velocity")
;; → {:path "/cljseq/loops/bass/velocity"
;;    :type :int
;;    :range [0 127]
;;    :default 100
;;    :current 85
;;    :bindings [{:source {:midi/cc 25 :midi/channel 16}}]}

;; Snapshot entire tree state (for saving a performance preset)
(ctrl/snapshot)
;; → {"/cljseq/global/bpm" 138.0
;;    "/cljseq/global/harmony/key" :G
;;    "/cljseq/loops/bass/velocity" 85
;;    ...}

;; Restore from snapshot
(ctrl/restore! snapshot)

;; Watch a node: fire fn on every change (for TUI display)
(ctrl/watch! "/cljseq/global/bpm"
  (fn [old new] (tui/update-bpm-display new)))
```

The `ctrl/snapshot` / `ctrl/restore!` pair enables **preset management**: saving and
loading complete performance setups, including all parameter values and MIDI/OSC
bindings. This is the cljseq equivalent of a hardware synthesizer's patch system.

### 16.7 OSC Namespace Query Support (OSC 1.1)

OSC 1.1 defines a query mechanism via the `#bundle` special address. When a client
sends an OSC query to `/_osc`, the server responds with its namespace. The cljseq OSC
server in the sidecar (§3.4, §3.5) can implement this by serializing `(ctrl/ls)`
results into an OSC 1.1 namespace response. This enables TouchOSC, Lemur, and other
controller software to *autodiscover* what cljseq exposes without manual configuration.

### 16.8 MIDI Output Targets as Tree Nodes

The control tree model extends naturally to *output* as well as input. A MIDI output
destination can itself be a tree node — not just a raw port/channel pair, but a named
node with a declared interface:

```clojure
;; Register an external synth as a tree node
(ctrl/defdevice "/cljseq/devices/prophet"
  {:port   "Prophet Rev2"   ; RtMidi port name
   :channel 1}
  {:filter-cutoff {:cc 74 :range [0 127]}
   :resonance     {:cc 71 :range [0 127]}
   :lfo-rate      {:cc 76 :range [0 127]}})
```

This creates:
- `/cljseq/devices/prophet/filter-cutoff` — a value node; writing it sends MIDI CC 74
  to the Prophet on channel 1 via the sidecar
- `/cljseq/devices/prophet/resonance` — similarly
- A Clojure function `(play-note! "/cljseq/devices/prophet" pitch vel)` that routes
  to the declared port/channel

Now a modulation route from the NDLR model (§2.3) can be expressed entirely in terms
of the tree:

```clojure
(mod-route {:source "/cljseq/global/harmony/degree"   ; input: current chord degree
            :dest   "/cljseq/devices/prophet/filter-cutoff"  ; output: CC 74
            :transform (fn [degree] (* degree 18))})  ; degree 1–7 → CC 18–126
```

MIDI targets as tree nodes means that any value node — including those updated via
external OSC or MIDI input — can be routed to any MIDI CC output through a
`mod-route`, without writing explicit `on-cc` handlers.

### 16.9 Design Questions Raised

See `doc/open-design-questions.md` Q8–Q11 for questions surfaced by this model.

---

## 17. Control-Plane Server: Ring/OSC/HTTP via Netty

### 17.1 Two OSC Planes

The C++ sidecar (§3.5) handles **data-plane OSC**: pre-timestamped messages destined
for SuperCollider, VCV Rack, and other synthesis engines. These have sub-millisecond
delivery requirements and must not touch the JVM.

This section describes **control-plane OSC**: messages that read or write the control
tree (§16) — changing key, muting a loop, querying current BPM. These have musical
latency tolerance (10–100ms is imperceptible for a parameter change) and are best
handled on the JVM where the tree lives.

The distinction is structural: data-plane OSC addresses name synthesis targets
(`/s_new`, `/vcv/module/param`); control-plane OSC addresses name tree nodes
(`/cljseq/**`). A configurable prefix or separate UDP port keeps them separate.

### 17.2 Ring as the Abstraction

Clojure's Ring specification defines three things:

1. **Handler**: a function `request-map → response-map`
2. **Middleware**: a function `handler → handler` — wraps and extends a handler
3. **Adapter**: translates a transport (HTTP, OSC, ...) into the request/response maps

The Ring model's power is that handlers are pure functions and middleware composes
via ordinary function composition. A handler that controls the cljseq tree looks
identical whether it was invoked via HTTP or via OSC.

The insight here is that OSC and HTTP share the same tree-traversal semantics:
- HTTP `GET /cljseq/global/bpm` → read the value at that path
- HTTP `PUT /cljseq/global/bpm` body `140` → write the value
- HTTP `POST /cljseq/loops/bass/trigger` → call the method
- OSC `/cljseq/global/bpm 140.0` → write the value (implicit set)
- OSC `?/cljseq/global/bpm` → query the value (OSC 1.1 prefix convention)
- OSC `/cljseq/loops/bass/trigger` (no args) → call the method

Both reduce to the same three tree operations: `ctrl/get`, `ctrl/set!`, `ctrl/call!`.

### 17.3 Normalized Request Map

OSC and HTTP messages are normalized into a common request map before reaching the
handler. This is the adapter step:

```clojure
;; A control-plane request (protocol-agnostic)
{:ctrl/path    "/cljseq/global/bpm"   ; tree path (canonical)
 :ctrl/op      :set                   ; :get, :set, :call, :list
 :ctrl/args    [140.0]                ; arguments (for :set and :call)
 :ctrl/source  :osc                   ; :osc, :http, :ws, :internal

 ;; Protocol-specific fields preserved for middleware
 :osc/address  "/cljseq/global/bpm"
 :osc/type-tag ",f"
 :osc/src-addr #<InetSocketAddress>}
```

```clojure
;; Same shape from an HTTP PUT
{:ctrl/path    "/cljseq/global/bpm"
 :ctrl/op      :set
 :ctrl/args    [140.0]
 :ctrl/source  :http

 ;; Standard Ring keys preserved
 :request-method :put
 :uri            "/cljseq/global/bpm"
 :headers        {"content-type" "application/json"}
 :body           "140.0"}
```

The core tree handler operates only on `:ctrl/*` keys. Protocol-specific middleware
populates those keys from the raw transport data.

### 17.4 The Handler Stack

Handlers and middleware compose as in Ring — function wrapping, no framework magic:

```clojure
(ns cljseq.server.handler
  (:require [cljseq.ctrl :as ctrl]))

;; Core handler: dispatches to the control tree
(defn tree-handler [req]
  (case (:ctrl/op req)
    :get  (if-let [node (ctrl/node (:ctrl/path req))]
            {:ctrl/status :ok
             :ctrl/value  (ctrl/get (:ctrl/path req))
             :ctrl/meta   (ctrl/describe (:ctrl/path req))}
            {:ctrl/status :not-found})
    :set  (do (ctrl/set! (:ctrl/path req) (first (:ctrl/args req)))
              {:ctrl/status :ok})
    :call (do (apply ctrl/call! (:ctrl/path req) (:ctrl/args req))
              {:ctrl/status :ok})
    :list {:ctrl/status   :ok
           :ctrl/children (ctrl/ls (:ctrl/path req))}
    {:ctrl/status :bad-request}))

;; Middleware: type coercion (OSC sends floats; tree node expects keyword)
(defn wrap-coerce [handler]
  (fn [req]
    (let [node (ctrl/node (:ctrl/path req))
          coerced (when (and node (seq (:ctrl/args req)))
                    (update req :ctrl/args
                            #(mapv (partial ctrl/coerce (:type node)) %)))]
      (handler (or coerced req)))))

;; Middleware: OSC wildcard expansion
;; /cljseq/loops/*/mute → dispatch to all matching paths
(defn wrap-osc-wildcards [handler]
  (fn [req]
    (if (re-find #"[*?\[\{]" (:ctrl/path req))
      (let [paths   (ctrl/match (:ctrl/path req))
            results (mapv #(handler (assoc req :ctrl/path %)) paths)]
        {:ctrl/status :ok :ctrl/results results})
      (handler req))))

;; Middleware: logging
(defn wrap-log [handler]
  (fn [req]
    (let [t0   (System/nanoTime)
          resp (handler req)
          ms   (/ (- (System/nanoTime) t0) 1e6)]
      (when (> ms 5) ; log slow control requests
        (log/warn "Slow control request" (:ctrl/path req) ms "ms"))
      resp)))

;; Compose the stack (same for OSC and HTTP)
(def control-stack
  (-> tree-handler
      wrap-coerce
      wrap-osc-wildcards
      wrap-log))
```

### 17.5 Transport Adapters

**HTTP adapter** (via `aleph` — Netty-backed Ring server):

```clojure
(ns cljseq.server.http
  (:require [aleph.http :as http]
            [cljseq.server.handler :refer [control-stack]]))

(defn ring->ctrl [ring-req]
  (let [method (:request-method ring-req)
        uri    (:uri ring-req)]
    (merge ring-req
           {:ctrl/path   uri
            :ctrl/source :http
            :ctrl/op     (case method
                           :get  (if (ctrl/container? uri) :list :get)
                           :put  :set
                           :post :call
                           :delete :call)
            :ctrl/args   (when-let [b (:body ring-req)]
                           [(parse-body ring-req b)])})))

(defn ctrl->ring [ctrl-resp]
  (case (:ctrl/status ctrl-resp)
    :ok        {:status 200 :body (encode-response ctrl-resp)}
    :not-found {:status 404 :body "Not found"}
    :bad-request {:status 400}))

(def http-handler
  (comp ctrl->ring control-stack ring->ctrl))

(defn start-http! [port]
  (http/start-server http-handler {:port port}))
```

`aleph` provides Netty-backed HTTP with Ring compatibility and WebSocket support via
the same server. No separate WebSocket server is needed.

**OSC adapter** (UDP via Netty, through `aleph.udp`):

```clojure
(ns cljseq.server.osc
  (:require [aleph.udp :as udp]
            [manifold.stream :as s]
            [cljseq.osc.codec :as codec]
            [cljseq.server.handler :refer [control-stack]]))

(defn osc-msg->ctrl [msg]
  (let [decoded (codec/decode (:message msg))
        address (:address decoded)
        args    (:args decoded)
        ;; OSC 1.1 convention: "?" prefix = query (get), no prefix + args = set,
        ;; no prefix + no args = call
        op      (cond
                  (str/starts-with? address "?") :get
                  (seq args)                      :set
                  :else                           :call)]
    {:ctrl/path   (cond-> address (str/starts-with? address "?") (subs 1))
     :ctrl/op     op
     :ctrl/args   args
     :ctrl/source :osc
     :osc/address address
     :osc/src     (:sender msg)}))

(defn start-osc! [port]
  (let [socket @(udp/socket {:port port})]
    (s/consume
      (fn [msg]
        ;; Non-blocking: handler runs on aleph's I/O thread pool
        (let [req  (osc-msg->ctrl msg)
              resp (control-stack req)]
          ;; Optionally send OSC reply if caller included return address
          (when (:osc/reply-to req)
            (s/put! socket {:host    (:host (:osc/reply-to req))
                            :port    (:port (:osc/reply-to req))
                            :message (codec/encode-reply resp)}))))
      socket)
    socket))
```

`aleph.udp` provides a Netty UDP channel as a manifold stream of `{:sender :message}`
maps. The single `NioEventLoopGroup` shared between `aleph.http` and `aleph.udp` means
both the HTTP server and the OSC server multiplex on the same thread pool.

### 17.6 WebSocket: Reactive Subscriptions

Reactive updates — watching a node change in real time — are a natural fit for
WebSocket. `aleph` exposes WebSocket connections as bidirectional manifold streams:

```clojure
(defn ws-handler [req]
  (-> (http/websocket-connection req)
      (d/chain
        (fn [socket]
          (let [path (str/replace (:uri req) #"^/ws" "")]
            ;; Send current value immediately
            (s/put! socket (encode {:path path :value (ctrl/get path)}))
            ;; Subscribe to changes
            (ctrl/watch! path
              (fn [_old new-val]
                (when-not (s/closed? socket)
                  (s/put! socket (encode {:path path :value new-val})))))
            ;; Handle incoming messages (allow writes from WS client too)
            (s/consume
              (fn [msg]
                (let [req (parse-ws-message msg path)]
                  (control-stack req)))
              socket))))))
```

WebSocket paths mirror tree paths with a `/ws` prefix to distinguish them from
REST endpoints. A browser application can subscribe to any node:

```javascript
const ws = new WebSocket("ws://localhost:7000/ws/cljseq/global/bpm");
ws.onmessage = e => updateBpmDisplay(JSON.parse(e.data).value);
ws.send(JSON.stringify({op: "set", value: 138})); // also allows writes
```

### 17.7 The System as a Web-Addressable Resource

Because every tree node is a URL, the running cljseq system becomes navigable via
a browser. With content negotiation (`Accept: text/html`), the HTTP handler renders
tree nodes as HTML pages:

```
GET /cljseq/loops                          → HTML: list of all loops + status
GET /cljseq/loops/bass                     → HTML: bass loop parameters + controls
GET /cljseq/loops/bass/velocity            → HTML: slider + current value
GET /cljseq/global/harmony                 → HTML: key/mode/degree selector
GET /cljseq                                → HTML: full system dashboard
```

The HTML rendering can be server-side Hiccup or a single-page app that queries the
REST API. Either approach works because the API is fully hypermedia-capable: every
response includes links to child resources, making the tree navigable without
out-of-band knowledge.

**Content negotiation matrix:**

| Accept header | Response format | Use case |
|---|---|---|
| `application/json` | JSON value/metadata | API clients, JavaScript |
| `application/edn` | EDN | Clojure clients |
| `text/html` | Rendered HTML page | Browser navigation |
| `application/x-osc-namespace` | OSC 1.1 namespace | OSC controller autodiscovery |
| `*/*` (default) | JSON | |

`ctrl/snapshot` (§16.6) serialized as EDN or JSON gives a complete, loadable preset.
`GET /cljseq?format=edn` returns the full system state in one request.

### 17.8 Server Structure and Aleph

`aleph` (by Zach Tellman) is the Netty wrapper for Clojure. It provides:
- `aleph.http/start-server` — Netty-backed Ring server with WebSocket
- `aleph.udp/socket` — Netty UDP channel as a manifold stream
- Shared `NioEventLoopGroup`: HTTP, WebSocket, and UDP multiplex on the same thread pool
- `manifold.deferred` and `manifold.stream` for composable async primitives

```clojure
(ns cljseq.server
  (:require [aleph.http  :as http]
            [aleph.udp   :as udp]
            [cljseq.server.http  :refer [http-handler ws-handler]]
            [cljseq.server.osc   :refer [start-osc!]]))

(defn start! [{:keys [http-port osc-port]
               :or   {http-port 7000 osc-port 57121}}]
  {:http (http/start-server
           (fn [req]
             (if (http/websocket-upgrade-request? req)
               (ws-handler req)
               (http-handler req)))
           {:port http-port})
   :osc  (start-osc! osc-port)})

(defn stop! [{:keys [http osc]}]
  (.close http)
  (s/close! osc))
```

**Dependencies** (additions to `project.clj`):

```clojure
[aleph "0.7.1"]      ; Netty-backed HTTP + UDP, Ring-compatible
[manifold "0.4.2"]   ; Async streams and deferreds (aleph dependency)
[metosin/reitit "0.7.2"]  ; Data-driven routing (Compojure successor)
```

`reitit` is recommended over Compojure for routing because its routes are pure data
maps, which compose naturally with the control tree's data-driven node definitions.

---

## 18. MCP Integration

### 18.1 What MCP Is

The Model Context Protocol (MCP) is an open standard (Anthropic, 2024) for connecting
AI language models to external tools and data sources. An MCP server exposes:

- **Resources**: data the model can read (current system state, loop definitions)
- **Tools**: functions the model can invoke (set parameters, call methods, eval code)
- **Prompts**: parameterized interaction templates for common tasks

The client (Claude Desktop, Claude Code, or any MCP-compatible host) connects to the
MCP server, discovers its capabilities, and can use them in response to user requests.

MCP uses JSON-RPC 2.0 as its wire protocol, over either stdio (for subprocess
communication) or HTTP + Server-Sent Events (for networked servers).

### 18.2 Why MCP Belongs Here

The control tree (§16) already models cljseq as a namespace of readable values and
callable functions. That is precisely MCP's resource/tool model. Building MCP support
in from the start — rather than retrofitting it later — costs almost nothing: the
tree is the implementation, and the MCP server is a thin adapter over it.

What it enables is qualitatively different from what any control surface can provide:

- **Natural language control**: "Make the bass line more aggressive" → the model reads
  the tree state, sets velocity and chord type, starts a more rhythmically dense
  pattern
- **Generative assistance**: "Write a four-bar chord progression that modulates to the
  relative minor" → the model generates a `chord-seq` call and submits it to nREPL
- **Real-time commentary**: "What's happening musically right now?" → the model reads
  the harmony, loop states, and active patterns and describes them in plain language
- **Debugging help**: "My loop keeps overrunning — what might be wrong?" → the model
  reads overrun counts and the loop body and diagnoses the issue

### 18.3 Tree → MCP Mapping

**Value nodes → MCP Resources**

Every value node is an MCP resource. Resources have a URI, a name, and a content type.

```
Resource URI:  cljseq://global/bpm
Name:          "Global BPM"
Description:   "Current beats per minute for the Link session"
MIME type:     application/json
Content:       {"value": 120.0, "range": [20.0, 300.0], "source": "local"}

Resource URI:  cljseq://loops/bass/status
Name:          "Bass loop status"
Content:       {"status": "running", "beat": 4.25, "overruns": 0}

Resource URI:  cljseq://snapshot
Name:          "Full system state"
Content:       <ctrl/snapshot as JSON>
```

Container nodes expose their children as resource lists. `GET cljseq://loops` returns
a resource list of all registered loops.

**Method nodes → MCP Tools**

Every method node is an MCP tool. Tools have a name, description, and JSON Schema
input parameters derived from the node's declared argument schema.

```json
{
  "name": "loops_bass_trigger",
  "description": "Start or restart the bass live-loop",
  "inputSchema": {"type": "object", "properties": {}, "required": []}
}

{
  "name": "tone_row_advance",
  "description": "Step the tone row by a scale-degree interval",
  "inputSchema": {
    "type": "object",
    "properties": {"interval": {"type": "integer", "minimum": -4, "maximum": 4}},
    "required": ["interval"]
  }
}

{
  "name": "ctrl_set",
  "description": "Set any value node in the control tree",
  "inputSchema": {
    "type": "object",
    "properties": {
      "path":  {"type": "string", "description": "Tree path, e.g. /cljseq/global/bpm"},
      "value": {"description": "New value (type must match node's declared type)"}
    },
    "required": ["path", "value"]
  }
}
```

**nREPL as an MCP Tool (authorization-gated)**

The most powerful tool: submitting Clojure code to the live nREPL session.

```json
{
  "name": "repl_eval",
  "description": "Evaluate Clojure code in the live cljseq nREPL session. Use with care — this executes arbitrary code.",
  "inputSchema": {
    "type": "object",
    "properties": {"code": {"type": "string"}},
    "required": ["code"]
  }
}
```

`repl/eval` is gated behind explicit user authorization. The first time any MCP client
requests it, the user is prompted to allow it (same model as Claude Code's tool-use
permission prompts). The authorization persists for the session or can be made
permanent in the cljseq config.

### 18.4 MCP Prompts

MCP prompts are parameterized templates that guide the model toward common tasks:

```clojure
;; Defined in cljseq.server.mcp/prompts
[{:name        "compose-progression"
  :description "Compose a chord progression in a given key and mode"
  :arguments   [{:name "key"    :description "Root note (e.g. G)" :required true}
                {:name "mode"   :description "Mode name (e.g. dorian)" :required true}
                {:name "length" :description "Number of bars" :required false}
                {:name "style"  :description "e.g. jazz, folk, modal" :required false}]}

 {:name        "analyze-state"
  :description "Describe what cljseq is playing right now in musical terms"
  :arguments   []}

 {:name        "generate-motif"
  :description "Generate a melodic motif for a named loop based on current harmony"
  :arguments   [{:name "loop"  :description "Loop name (e.g. melody)" :required true}
                {:name "style" :required false}]}

 {:name        "debug-loop"
  :description "Diagnose why a loop is overrunning or behaving unexpectedly"
  :arguments   [{:name "loop" :required true}]}]
```

When a user invokes "compose-progression" with `{:key "G" :mode "dorian"}`, the model
receives the current system state as context (via resources), generates a
`(chord-seq [...])` expression, and submits it via `repl/eval`.

### 18.5 Transport and Server Integration

MCP over HTTP+SSE integrates directly into the §17 Netty/aleph server:

```
POST /mcp          → JSON-RPC request endpoint (tools/call, resources/read, etc.)
GET  /mcp/sse      → Server-Sent Events stream (server → client notifications)
GET  /mcp/manifest → Server capabilities declaration
```

The SSE stream is used for:
- **Resource update notifications**: when a tree node value changes, push a
  `notifications/resources/updated` event so the model can refresh stale reads
- **Tool result streaming**: for long-running tools (e.g., `repl/eval` with a complex
  computation), stream partial results

```clojure
(ns cljseq.server.mcp
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [cljseq.ctrl :as ctrl]
            [cljseq.server.mcp.tools :as tools]
            [cljseq.server.mcp.resources :as resources]))

(def mcp-handler
  (reitit/router
    [["/mcp"
      [""     {:post mcp-rpc-handler}]
      ["/sse" {:get  mcp-sse-handler}]
      ["/manifest" {:get (fn [_] {:status 200 :body mcp-manifest})}]]]))

(defn mcp-rpc-handler [req]
  (let [{:keys [method params]} (json/decode (:body req))
        result (case method
                 "tools/list"      (tools/list-all)
                 "tools/call"      (tools/call (:name params) (:arguments params))
                 "resources/list"  (resources/list-all)
                 "resources/read"  (resources/read (:uri params))
                 "prompts/list"    (prompts/list-all)
                 "prompts/get"     (prompts/get (:name params) (:arguments params))
                 {:error {:code -32601 :message "Method not found"}})]
    {:status 200
     :headers {"content-type" "application/json"}
     :body (json/encode {:jsonrpc "2.0" :id (:id req) :result result})}))
```

### 18.6 Unified Server Architecture

All three protocol surfaces — Ring/HTTP, OSC, and MCP — share the same Netty event
loop and the same control tree:

```
┌─────────────────────────────────────────────────────────┐
│                  cljseq.server (aleph/Netty)             │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  NioEventLoopGroup (shared thread pool)          │   │
│  │                                                  │   │
│  │  HTTP/WS :7000                 UDP OSC :57121    │   │
│  │  ├── GET/PUT/POST /cljseq/** ──┐                 │   │
│  │  ├── WS  /ws/cljseq/**        │                 │   │
│  │  └── POST/GET /mcp/**         │                 │   │
│  └─────────────────────┬─────────┘                 │   │
│                         │                           │   │
│              ┌──────────▼──────────┐               │   │
│              │  control-stack      │               │   │
│              │  (Ring middleware)  │               │   │
│              └──────────┬──────────┘               │   │
│                         │                          │   │
│              ┌──────────▼──────────┐               │   │
│              │  cljseq.ctrl tree   │               │   │
│              │  (ConcurrentHashMap │               │   │
│              │   of atoms + fns)   │               │   │
│              └─────────────────────┘               │   │
└─────────────────────────────────────────────────────────┘

Clients:
  Browser         → HTTP REST + WebSocket
  TouchOSC/Lemur  → Control-plane OSC UDP
  Claude Desktop  → MCP HTTP+SSE
  Claude Code     → MCP HTTP+SSE
  curl / httpie   → HTTP REST
  Clojure REPL    → ctrl/* ns directly
```

### 18.7 Security Model

The control server binds to `localhost` by default. Remote access (for multi-machine
setups) requires explicit opt-in: `(server/start! {:bind "0.0.0.0"})`.

Tool authorization tiers:
1. **Read-only** (default for unauthenticated clients): `ctrl/get`, `ctrl/list`,
   `resources/read` — harmless
2. **Control** (opt-in per session): `ctrl/set!`, `ctrl/call!`, `tools/call` for
   non-destructive nodes — parameter changes, loop triggers
3. **Eval** (explicit user authorization required): `repl/eval` — arbitrary code
   execution; user is prompted each session; configurable to allow permanently

MCP clients are identified by their client ID in the MCP handshake. The authorization
tier can be set per client ID in cljseq config.

### 18.8 Design Questions Raised

See `doc/open-design-questions.md` Q12–Q14.

---

## 19. Synthesis Target Abstraction and Plugin Hosting

### 19.1 The Synthesis Target

The sidecar routes events to *synthesis targets* — destinations that accept note and
parameter events and produce sound (or CV, or MIDI, or any other time-critical
output). The `SynthTarget` abstract class lives in `libcljseq-rt` and is implemented
by both `cljseq-sidecar` (for non-audio targets) and `cljseq-audio` (for
audio-generating targets). The C++ type hierarchy:

```cpp
class SynthTarget {
public:
    virtual ~SynthTarget() = default;

    // Event delivery (all targets)
    virtual void note_on (uint8_t ch, uint8_t note, float vel, uint64_t time_ns) = 0;
    virtual void note_off(uint8_t ch, uint8_t note, float vel, uint64_t time_ns) = 0;
    virtual void param_change(uint32_t param_id, double value, uint64_t time_ns) = 0;

    // Audio production (only audio-generating targets)
    virtual bool produces_audio() const { return false; }
    virtual void process(float** out_bufs, int32_t num_frames,
                         const AudioCallbackContext& ctx) {}

    // Parameter introspection (for control tree auto-registration)
    virtual std::vector<ParamInfo> params() const { return {}; }
    virtual std::string target_id() const = 0;
};
```

Concrete implementations:

| Class | Delivery mechanism | Audio? |
|---|---|---|
| `MidiTarget` | RtMidi (existing) | No — external hardware |
| `OscTarget` | UDP bundle (existing) | No — external engine |
| `ClapTarget` | CLAP `plugin->process()` | Yes |
| `Vst3Target` | VST3 `IAudioProcessor::process()` | Yes (future) |
| `PdTarget` | libpd C API | Yes |

The `SynthTarget` abstraction means that from the JVM's perspective, submitting a
note event to Surge XT looks identical to submitting one to a MIDI port — same IPC
message types, same timestamp semantics. The sidecar routes based on the target ID
embedded in the event.

The same abstraction on the Clojure side:

```clojure
;; All synthesis targets accept the same event API
(play-note! "/cljseq/synths/surge"   :C4 100)
(play-note! "/cljseq/synths/prophet" :C4 100)  ; MIDI hardware
(play-note! "/cljseq/synths/sc"      :C4 100)  ; SuperCollider via OSC
(play-note! "/cljseq/synths/pd"      :C4 100)  ; Pure Data via libpd

;; live-loop targeting a synth
(live-loop :lead
  (with-target "/cljseq/synths/surge"
    (play! (scale/pitch-at *scale* *root* (rand-nth [1 3 5])) 80))
  (sleep! 1/2))
```

### 19.2 Plugin Standards: CLAP and VST3

**CLAP (CLever Audio Plugin)**

CLAP is an open plugin standard developed by Bitwig and u-he (2022), with Surge XT
as an early and comprehensive adopter.

| Property | Detail |
|---|---|
| License | MIT (SDK itself) |
| API | C (not C++) |
| Threading | Explicit thread annotations on every function |
| Parameter automation | Sample-accurate, with modulation support |
| Polyphonic expression | First-class: per-note parameter modulation |
| Tuning | `CLAP_EXT_TUNING`: per-note pitch in Hz (microtonal native) |
| Headless | Explicit: `CLAP_EXT_GUI` is optional; plugins must work without it |
| Latency | Plugin declares its own latency; host adjusts scheduling |
| State | `CLAP_EXT_STATE`: binary or free-form save/restore |

CLAP is the primary target for cljseq plugin hosting. The MIT SDK license, C API
(natural from our C++ sidecar), and the `CLAP_EXT_TUNING` extension for microtonal
synthesis make it the best fit.

**VST3**

VST3 (Steinberg) has the widest plugin catalog. The SDK is available under a dual
license (GPL-3.0 or proprietary). The API is C++ with a COM-like object model.
Threading contracts are less explicit than CLAP. VST3 is the second target after CLAP
is established — the `SynthTarget` abstraction isolates the hosting code so adding
VST3 is a new `Vst3Target` subclass without architectural changes.

**Recommendation**: CLAP first; VST3 in a subsequent phase.

### 19.3 Audio Engine Architecture

Plugin hosting requires an audio I/O layer. This lives entirely in the
**`cljseq-audio` process** (§3.5), not in `cljseq-sidecar`. The audio process is
launched on demand by the Clojure side:

```sh
# Launched by cljseq.audio/start! in Clojure — not by the user directly
cljseq-audio --rate 48000 --buf 256
cljseq-audio --rate 44100 --buf 128     # lower latency
cljseq-audio --device "BlackHole 2ch"   # virtual device → DAW
```

The audio engine uses **RtAudio** (same family as RtMidi, MIT, wraps
CoreAudio/JACK/WASAPI/ASIO). A single `RtAudio` instance drives the audio callback.

```cpp
class AudioEngine {
    RtAudio         dac_;
    uint64_t        sample_position_{0};    // monotonic sample counter
    double          sample_rate_;
    uint32_t        buffer_size_;
    ableton::Link&  link_;                  // shared with LinkEngine
    EventQueue&     event_queue_;           // fed by IPC handler thread

    // Registered audio-producing targets in processing order
    std::vector<std::shared_ptr<SynthTarget>> audio_targets_;

    static int rtaudio_callback(void* out, void* /*in*/, unsigned int nframes,
                                double stream_time, RtAudioStreamStatus,
                                void* self_ptr)
    {
        static_cast<AudioEngine*>(self_ptr)->process(
            static_cast<float*>(out), (int32_t)nframes);
        return 0;
    }

    void process(float* out, int32_t num_frames);
};
```

The `process()` method runs in the audio callback — the highest-priority RT context:

```cpp
void AudioEngine::process(float* out, int32_t num_frames) {
    // 1. Capture Link state (captureAudioSessionState — RT-safe)
    auto link_state   = link_.captureAudioSessionState();
    auto now_us       = link_.clock().micros();
    double beat_start = link_state.beatAtTime(now_us, quantum_);
    double bpm        = link_state.tempo();

    // 2. Build callback context (passed to each target)
    AudioCallbackContext ctx{
        .link_state     = link_state,
        .sample_pos     = sample_position_,
        .sample_rate    = sample_rate_,
        .num_frames     = num_frames,
        .beat_at_start  = beat_start,
        .bpm            = bpm,
        .buffer_start_ns = /* derived from link_.clock().micros() * 1000 */
    };

    // 3. Dequeue IPC events falling within [now, now + buffer_duration)
    //    (lock-free MPSC queue; IPC handler thread pushes; audio thread pops)
    auto events = event_queue_.drain_window(
        sample_position_, sample_position_ + num_frames);

    // 4. Process each audio-generating target
    std::fill(out, out + num_frames * 2, 0.0f);
    for (auto& target : audio_targets_) {
        target->process(/* out_bufs */, num_frames, ctx);
    }

    // 5. Advance counter
    sample_position_ += num_frames;
}
```

**Audio callback thread priority**: RtAudio sets up the callback thread with the
appropriate RT priority for the platform automatically — CoreAudio on macOS uses a
time-constraint thread policy; JACK on Linux runs under `SCHED_FIFO`; ASIO on Windows
uses its own RT model. No manual thread priority setting is needed when using RtAudio.

### 19.4 Sample-Accurate Event Scheduling

This is the key precision upgrade over MIDI hardware delivery. MIDI dispatch fires
events at a nanosecond wall-clock time ± dispatch jitter. CLAP events are delivered
at a *sample offset* within a buffer — exact to one sample (20.8 µs at 48kHz).

The JVM always stamps events in nanoseconds (unchanged). The audio engine converts:

```cpp
// Convert a nanosecond-stamped event to a sample offset in the current buffer
uint32_t ns_to_sample_offset(uint64_t event_time_ns,
                              uint64_t buffer_start_ns,
                              double   sample_rate,
                              uint32_t num_frames)
{
    if (event_time_ns <= buffer_start_ns) return 0;
    int64_t delta_ns = (int64_t)(event_time_ns - buffer_start_ns);
    uint32_t offset  = (uint32_t)(delta_ns * sample_rate / 1e9);
    return std::min(offset, num_frames - 1);
}
```

Events timestamped before the current buffer window (`time_ns < buffer_start_ns`)
are delivered at sample offset 0 (beginning of buffer) — they are late but not
dropped. This is the audio equivalent of the virtual-time overrun semantic.

CLAP parameter changes and note events both carry a `time` field (sample offset)
in the `clap_event_header_t`. Building the input event list:

```cpp
void ClapTarget::process(float** out, int32_t num_frames,
                          const AudioCallbackContext& ctx)
{
    clap_input_events_t in_events = build_event_list(
        events_for_target_, ctx.buffer_start_ns,
        ctx.sample_rate, num_frames);

    clap_process_t p{};
    p.steady_time  = ctx.sample_pos;
    p.frames_count = num_frames;
    p.transport    = &build_transport(ctx);
    p.in_events    = &in_events;
    p.out_events   = &null_output_handler_;
    p.audio_inputs  = nullptr;
    p.audio_outputs = output_buffers_;  // plugin writes here

    plugin_->process(plugin_, &p);

    // Mix plugin output into the master output buffer
    mix_into(out, output_buffers_, num_frames);
}
```

### 19.5 Link Transport Sync for CLAP Plugins

CLAP's `clap_event_transport_t` carries full transport state (tempo, beat position,
time signature, play state) to the plugin each buffer. Plugins use this for tempo
sync of LFOs, arpeggiators, delay times, and sequencer phases. This replaces MIDI
clock for plugin targets — the plugin reads tempo directly from the transport struct,
not from an external clock signal.

```cpp
clap_event_transport_t AudioEngine::build_transport(
    const AudioCallbackContext& ctx) const
{
    const double beats_per_sample = ctx.bpm / (60.0 * ctx.sample_rate);

    clap_event_transport_t t{};
    t.header = {sizeof(t), 0, CLAP_CORE_EVENT_SPACE_ID,
                CLAP_EVENT_TRANSPORT, 0};
    t.flags  = CLAP_TRANSPORT_HAS_TEMPO
             | CLAP_TRANSPORT_HAS_BEATS_TIMELINE
             | CLAP_TRANSPORT_HAS_TIME_SIGNATURE
             | CLAP_TRANSPORT_IS_PLAYING;

    // Beat position at the start of this buffer (high-resolution fixed-point)
    t.song_pos_beats = (clap_beattime)(ctx.beat_at_start * CLAP_BEATTIME_FACTOR);
    t.tempo          = ctx.bpm;
    t.tempo_inc      = 0.0;  // no tempo ramp within buffer

    // Bar position (integer bars elapsed)
    double beats_per_bar = (double)t.tsig_num;
    t.bar_number   = (int32_t)(ctx.beat_at_start / beats_per_bar);
    t.bar_start    = (clap_beattime)(t.bar_number * beats_per_bar * CLAP_BEATTIME_FACTOR);
    t.tsig_num     = 4;
    t.tsig_denom   = 4;

    return t;
}
```

Because `ctx.beat_at_start` is derived from `link_.captureAudioSessionState()`, the
transport is locked to the Link session — Surge XT's internal arpeggiator, effects,
and modulators all sync to the same beat grid as every other Link peer on the network.

### 19.6 CLAP Plugin Hosting: The Host Interface

CLAP requires the host to provide a `clap_host_t` struct with callbacks the plugin
uses to request services (log, parameter flush, GUI resize, etc.). For headless
hosting:

```cpp
static const clap_host_t cljseq_host = {
    .clap_version = CLAP_VERSION_INIT,
    .host_data    = nullptr,
    .name         = "cljseq",
    .vendor       = "cljseq",
    .url          = "https://github.com/cljseq/cljseq",
    .version      = "0.1.0",

    .get_extension = [](const clap_host_t*, const char* id) -> const void* {
        // We provide: log, thread-check, latency, state
        // We do NOT provide: gui, audio-ports-config (headless)
        if (!strcmp(id, CLAP_EXT_LOG))          return &host_log;
        if (!strcmp(id, CLAP_EXT_THREAD_CHECK)) return &host_thread_check;
        return nullptr;
    },
    .request_restart   = [](const clap_host_t*) { /* handle restart */ },
    .request_process   = [](const clap_host_t*) { /* already processing */ },
    .request_callback  = [](const clap_host_t*) { /* schedule on main thread */ },
};
```

Plugins that require a GUI to function (`!plugin->features` includes
`CLAP_PLUGIN_FEATURE_REQUIRES_HARDWARE_GUI`) are rejected at load time with a clear
error message.

### 19.7 Surge XT as Reference Target

Surge XT (https://surge-synthesizer.github.io/) is the ideal reference plugin:
- Open source (GPL-3.0 for the plugin; the CLAP SDK used is MIT)
- Comprehensive CLAP implementation — one of the earliest and most complete
- 600+ exposed parameters via CLAP
- Native Scala `.scl`/`.kbm` tuning support via `CLAP_EXT_TUNING`
- Full MPE support
- Tempo-sync of all modulators via CLAP transport
- Active development team; responsive to CLAP ecosystem questions

**Tuning integration with Surge XT via `CLAP_EXT_TUNING`:**

CLAP's tuning extension (`CLAP_EXT_TUNING`) allows the host to supply a per-note
frequency table to the plugin, bypassing MIDI pitch-bend entirely for microtonal
synthesis. The sidecar's `ClapTarget` can push a frequency table derived from
cljseq's `cljseq.tuning` namespace:

```cpp
// CLAP_EXT_TUNING: host pushes a tuning table to the plugin
// Table maps MIDI note number to frequency in Hz
void ClapTarget::push_tuning(const std::array<double, 128>& freq_table)
{
    if (auto* tuning_ext = static_cast<const clap_plugin_tuning_t*>(
            plugin_->get_extension(plugin_, CLAP_EXT_TUNING))) {
        // Encode as a clap_tuning_info_t and push via host
        // (implementation follows CLAP tuning spec)
    }
}
```

On the JVM side:
```clojure
;; Change Surge XT's tuning to Partch 43-tone
(ctrl/set! "/cljseq/synths/surge/tuning" "partch_43")
;; → computes freq table from .scl file
;; → sends IPC message to sidecar
;; → sidecar calls push_tuning() on the ClapTarget
;; → Surge XT renders notes at microtonal frequencies, no pitch-bend needed
```

This is the cleanest possible microtonal synthesis integration — zero pitch-bend
budget consumed, polyphonic microtonality with no channel-per-note constraint.

**Surge XT parameter tree:**

Loading Surge XT auto-registers ~600 parameters as child nodes of
`/cljseq/synths/surge/params/`. Because 600 tree nodes is unwieldy, a lazy
registration policy is used: parameters are loaded into the sidecar's parameter map
at plugin instantiation, but are only promoted to tree nodes when first accessed
or explicitly registered:

```clojure
;; Eager: register all parameters (600 nodes — useful for exhaustive OSC mapping)
(ctrl/register-all-params! "/cljseq/synths/surge")

;; Lazy default: register named parameters as needed
(ctrl/register-param! "/cljseq/synths/surge" "filter_cutoff_0")
;; → creates /cljseq/synths/surge/params/filter-cutoff-0 as a value node

;; Bind by CLAP parameter ID directly (no tree node required)
(ctrl/bind! {:midi/cc 74} {:plugin "/cljseq/synths/surge" :param-id 107})
```

### 19.8 Pure Data via libpd and Plugdata

**Pure Data** (Miller Puckette) is a dataflow visual programming environment for
audio, optimized for real-time synthesis and signal processing. Its relevance to
cljseq:

- Pd patches implement synthesis algorithms as dataflow graphs
- Pd processes audio in blocks (default 64 samples)
- Pd objects communicate via messages (bang, float, symbol, list) — analogous to
  the control tree's value and method nodes
- Pd has built-in OSC (`osc~`, `udpsend`, `udpreceive`) and MIDI I/O

**libpd** (https://github.com/libpd/libpd) is the embeddable C API for Pure Data:

```cpp
#include <z_libpd.h>

libpd_init();
libpd_init_audio(0, 2, 48000);    // 0 inputs, 2 outputs, 48kHz

// Load a patch
auto patch = libpd_openfile("lead.pd", patch_dir.c_str());

// Send control messages to named Pd receivers
libpd_float("cutoff", 0.7f);     // → [r cutoff] in Pd receives this
libpd_float("resonance", 0.3f);
libpd_bang("trigger");            // → [r trigger] receives a bang
libpd_noteon(0, 60, 100);         // standard MIDI note input

// In audio callback (must call from same thread)
libpd_process_float(
    num_frames / libpd_blocksize(),  // number of ticks
    input_buffer,
    output_buffer);
```

**PdTarget as a SynthTarget:**

```cpp
class PdTarget : public SynthTarget {
    std::string patch_path_;
    void*       patch_handle_{nullptr};
    std::unordered_map<uint32_t, std::string> param_receivers_;
    // param_receivers_ maps param_id → Pd receiver name

public:
    void note_on(uint8_t ch, uint8_t note, float vel, uint64_t) override {
        libpd_noteon(ch, note, (int)(vel * 127.0f));
    }

    void param_change(uint32_t id, double value, uint64_t) override {
        if (auto it = param_receivers_.find(id); it != param_receivers_.end())
            libpd_float(it->second.c_str(), (float)value);
    }

    bool produces_audio() const override { return true; }

    void process(float** out, int32_t num_frames, const AudioCallbackContext&) override {
        int ticks = num_frames / libpd_blocksize();  // libpd default blocksize = 64
        libpd_process_float(ticks, nullptr, out[0]);
    }

    // Hot-swap: load a new patch without stopping the audio engine
    void load_patch(const std::string& path) {
        if (patch_handle_) libpd_closefile(patch_handle_);
        patch_handle_ = libpd_openfile(
            std::filesystem::path(path).filename().c_str(),
            std::filesystem::path(path).parent_path().c_str());
        patch_path_ = path;
    }
};
```

**The hot-swap capability** is particularly significant: `load_patch()` can be called
at runtime, replacing the synthesis algorithm while the audio engine continues
running. This is the Pd equivalent of redefining a `live-loop` — except here it
replaces the DSP graph rather than the event-generation logic.

**Plugdata as CLAP** (https://plugdata.org/) wraps Pure Data as a CLAP/VST3 plugin,
with a graphical patch editor. If you want to edit patches live and see the visual
graph, loading Plugdata as a CLAP plugin (via the general `ClapTarget` path) is the
right approach. For headless operation with programmatic patch loading, direct libpd
embedding in a `PdTarget` is simpler and avoids the CLAP plugin loading overhead.

Both paths can coexist in the same sidecar: Plugdata loaded as a CLAP plugin for
interactive patch development; libpd for deployment-time headless synthesis.

**libpd and cljseq control tree:**

A Pd patch's named receivers become tree nodes automatically:

```clojure
(ctrl/defsyn "/cljseq/synths/pd"
  {:type     :libpd
   :patch    "lead-synth.pd"
   :receivers {:cutoff    {:type :float :range [0.0 1.0]}
               :resonance {:type :float :range [0.0 1.0]}
               :waveform  {:type :int   :range [0 3]}
               :trigger   {:type :bang}}})

;; Auto-registers:
;; /cljseq/synths/pd/params/cutoff    (value node)
;; /cljseq/synths/pd/params/resonance (value node)
;; /cljseq/synths/pd/params/waveform  (value node)
;; /cljseq/synths/pd/methods/trigger  (method node → libpd_bang)

;; Hot-swap the patch:
(ctrl/call! "/cljseq/synths/pd/load-patch" "bass-synth.pd")
```

**The Pd + Clojure programming model:**

This creates a natural division of labour:
- **Clojure**: musical logic — harmony, patterns, rhythm, live-loops, sequencing
- **Pd patch**: DSP — synthesis algorithm, filter topology, modulation routing

The interface is the control tree: Clojure writes values to Pd receiver nodes;
Pd synthesizes audio. The synthesis algorithm (the Pd patch) is as live-codeable
as the Clojure musical logic — both can be replaced at runtime without silence.

### 19.9 Threading Model Across Both Processes

The two-process architecture means each process has its own threading model. There is
no shared memory between them; they communicate only via their respective IPC sockets
to the JVM and via the Link network protocol (for beat alignment).

**`cljseq-sidecar` threads (unchanged from §3.5):**

```
┌──────────────────────────────────────────────────────────────────┐
│  MIDI Dispatch Thread (SCHED_FIFO / TTC policy)                  │
│  Reads from MPSC event queue; sleeps via clock_nanosleep         │
│  Fires MidiTarget / OscTarget events at wall-clock target time   │
└──────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────┐
│  Asio Thread Pool                                                │
│  IPC server, OSC server, Link networking                         │
│  Pushes decoded events into MIDI dispatch queue                  │
└──────────────────────────────────────────────────────────────────┘
```

**`cljseq-audio` threads:**

```
┌──────────────────────────────────────────────────────────────────┐
│  Audio Callback Thread (RtAudio — platform RT priority)          │
│  ├── captureAudioSessionState() — RT-safe Link query             │
│  ├── drain_window() — lock-free MPSC queue pop                   │
│  ├── ClapTarget::process() — CLAP plugin rendering               │
│  ├── PdTarget::process() — libpd DSP block                       │
│  └── writes mixed output to hardware buffer                      │
└──────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────┐
│  Asio Thread Pool                                                │
│  IPC server (receives events from JVM)                           │
│  Link networking (second Link peer on same machine)              │
│  Pushes decoded events into audio event queue                    │
└──────────────────────────────────────────────────────────────────┘
```

**Two timing references, two processes:**

| Process | Timing reference | Delivery mechanism |
|---|---|---|
| `cljseq-sidecar` | Wall-clock ns | `clock_nanosleep` in MIDI dispatch thread |
| `cljseq-audio` | Sample position | Audio callback window conversion |

The JVM routes events based on the registered target type. Because both processes are
independent Link peers, they maintain beat alignment without explicit synchronization
— the Link protocol handles it across both sockets on the same machine.

### 19.10 IPC Protocol Additions

New message types for plugin hosting:

**JVM → sidecar:**

| Code | Message | Payload |
|---|---|---|
| `0x70` | Plugin load | `req_id:u32, type:u8 (0=CLAP, 1=VST3, 2=libpd), path_len:u16, path[path_len], plugin_index:u16` |
| `0x71` | Plugin unload | `plugin_id:u16` |
| `0x72` | Plugin note-on | `plugin_id:u16, time_ns:u64, ch:u8, note:u8, vel:f32` |
| `0x73` | Plugin note-off | `plugin_id:u16, time_ns:u64, ch:u8, note:u8, vel:f32` |
| `0x74` | Plugin param set | `plugin_id:u16, time_ns:u64, param_id:u32, value:f64` |
| `0x75` | Plugin param query | `req_id:u32, plugin_id:u16, param_id:u32` |
| `0x76` | Plugin state save | `req_id:u32, plugin_id:u16` |
| `0x77` | Plugin state restore | `plugin_id:u16, len:u32, data[len]` |
| `0x78` | Plugin tuning set | `plugin_id:u16, notes[128]:f64` (Hz per MIDI note) |
| `0x79` | libpd send float | `plugin_id:u16, recv_len:u8, recv[recv_len], value:f32` |
| `0x7A` | libpd send bang | `plugin_id:u16, recv_len:u8, recv[recv_len]` |
| `0x7B` | libpd load patch | `plugin_id:u16, path_len:u16, path[path_len]` |
| `0x7C` | Audio engine start | `sample_rate:u32, buf_size:u16, device_len:u8, device[device_len]` |
| `0x7D` | Audio engine stop | — |

**Sidecar → JVM:**

| Code | Message | Payload |
|---|---|---|
| `0x90` | Plugin loaded | `req_id:u32, plugin_id:u16, num_params:u32` |
| `0x91` | Plugin param info | `req_id:u32, param_id:u32, flags:u32, min:f64, max:f64, default:f64, name_len:u16, name[name_len]` |
| `0x92` | Plugin param value | `req_id:u32, value:f64` |
| `0x93` | Plugin state data | `req_id:u32, len:u32, data[len]` |
| `0x94` | Plugin error | `req_id:u32, code:u16, msg_len:u16, msg[msg_len]` |

### 19.11 Design Questions Raised

See `doc/open-design-questions.md` Q15–Q18.

---

## 20. Music21 Python Sidecar

### 20.1 Overview

[Music21](https://web.mit.edu/music21/) is the most comprehensive symbolic music
analysis library available. Developed at MIT since 2008, it provides over 15 years of
accumulated musicological algorithms with a coherent object model. No Clojure library
comes close to its breadth: Krumhansl-Schmuckler key analysis, Fux counterpoint
validation, twelve-tone row matrices, MusicXML/MIDI/Humdrum/MEI I/O, roman numeral
analysis, figured bass realization, modulation detection, and a searchable corpus
including Bach chorales, Beethoven sonatas, Josquin masses, and the Essen folksong
collection.

The philosophy is that cljseq should not reimplement what Music21 already does well.
The analysis and corpus functionality sketched in §10 and §11 can be largely backed by
Music21. cljseq provides the performance-critical real-time path; Music21 provides the
deep musicological intelligence.

Like the C++ sidecar, the Music21 sidecar runs as a **separate process**. Unlike the
C++ sidecar, it is not real-time and never touches audio or MIDI directly. Its role is
**analysis, composition assistance, and I/O** — all operations where latency of tens
to hundreds of milliseconds is acceptable.

### 20.2 Capabilities Relevant to cljseq

#### Score I/O

Music21 reads and writes the most important symbolic music formats:

| Format | Read | Write | Notes |
|---|---|---|---|
| MusicXML | Yes | Yes | Standard interchange; Sibelius, Finale, MuseScore |
| MIDI | Yes | Yes | Import existing MIDI as seed material |
| ABC notation | Yes | Yes | Folk music notation |
| Humdrum (`**kern`) | Yes | No | Academic corpus format |
| MEI | Yes | Partial | Music Encoding Initiative |
| LilyPond | No | Yes | High-quality notation rendering |
| MIDI file | Yes | Yes | Batch conversion |

`(m21/parse "path/to/score.xml")` returns a cljseq score map; `(m21/export score
"path.xml")` serializes a captured live-loop output to MusicXML.

#### Key and Harmonic Analysis

- **Krumhansl-Schmuckler** key-finding algorithm: correlates pitch-class histogram
  against major/minor profiles; returns ranked candidates with correlation coefficients
- **Bellman-Budge** variant: alternative profile, more accurate for Baroque music
- **Modulation detection**: `floatingKey` analysis over a windowed stream detects
  tonicizations and key changes
- **Roman numeral analysis**: `harmony.romanNumeral(chord, key)` returns figured
  bass notation, scale degree, inversion, and secondary dominant analysis
- **Chord quality**: `chord.Chord.commonName`, `.quality`, `.root()`, `.bass()`

#### Voice Leading

`voiceLeading.VoiceLeadingQuartet` checks any two simultaneous two-voice motions:

- Parallel fifths and octaves
- Hidden (direct) fifths and octaves
- Voice crossing
- Contrary, oblique, similar motion
- Unequal fifths (diminished → perfect fifth)

Four-voice counterpoint: `voiceLeading.NNoteLinearSegment` checks across an entire
phrase. The Fux counterpoint species rules are implemented for first through fifth
species.

#### Twelve-Tone and Serial Operations

`serial.ToneRow` is the authoritative implementation:

```python
row = serial.ToneRow([0, 11, 3, 4, 8, 7, 9, 6, 1, 5, 2, 10])
row.matrix()          # 12×12 transformation matrix
row.prime()           # P-0
row.inversion()       # I-0
row.retrograde()      # R-0
row.retrogradeInversion()  # RI-0
row.findZRelation()   # Z-related row if one exists
row.isTwelveTone()    # validation
row.isAllInterval()   # all-interval row check
```

The full matrix provides all 48 canonical forms (12 transpositions × 4 operations).

#### Corpus Access

```python
corpus.search(composer='bach', numberOfParts=4)
corpus.parse('bach/bwv66.6')           # by BWV number
corpus.parse('essenFolksong/test0132') # Essen collection
corpus.getComposer('beethoven')         # all Beethoven works
```

The built-in corpus contains ~4,000 works. The virtual corpus extension allows
registering local directories.

#### Rhythm and Meter

- Beat strength analysis (metric position, accent hierarchy)
- Syncopation detection
- Meter comparison between parts
- Isorhythm and talea/color analysis
- Mensural notation parsing (Josquin-era scores)

#### Notation Output

Music21 can write LilyPond `.ly` files; the system calls `lilypond` or opens MuseScore
for rendering. In a live session, `(m21/show score)` could open a notation window in
MuseScore without interrupting the audio thread.

### 20.3 Sidecar Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  JVM (cljseq)                                                   │
│  cljseq.m21 namespace                                           │
│  - JSON-RPC 2.0 request queue (ConcurrentLinkedQueue)           │
│  - Response promise registry (ConcurrentHashMap<id, promise>)   │
│  - Background sender thread: drains queue → Unix socket         │
│  - Background reader thread: reads responses → resolves promise │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Unix domain socket (JSON-RPC 2.0)
                            │ (framed: u32 length prefix)
┌───────────────────────────▼─────────────────────────────────────┐
│  cljseq-m21-server (Python process)                             │
│  - asyncio event loop                                           │
│  - Unix socket server                                           │
│  - JSON-RPC dispatch table → Music21 calls                      │
│  - Per-connection session: retains parsed scores, cached keys   │
└─────────────────────────────────────────────────────────────────┘
```

Key properties of this design:

1. **Not real-time**: all requests are asynchronous from the Clojure caller's
   perspective. The JVM caller gets a `promise`; Music21 operations run in the Python
   event loop.

2. **Session state**: the Python process maintains an in-memory session — parsed scores
   and computed results can be referenced by handle in subsequent requests, avoiding
   repeated serialization of large score objects.

3. **Shared type vocabulary**: communication uses JSON-serialized maps with
   field names that match cljseq's keyword conventions (`:pitch/midi`, `:dur/beats`,
   etc.), making round-trips transparent.

4. **Optional**: the Music21 sidecar is not required for core playback functionality.
   `cljseq.m21` degrades gracefully when the sidecar is not running — analysis
   functions return `nil` or throw with a clear message.

5. **Single instance**: unlike the C++ sidecar (always running), the Music21 sidecar
   is started on demand (first `m21/` call) and can be stopped and restarted without
   affecting the audio/MIDI path.

### 20.4 IPC Protocol

**JSON-RPC 2.0 over a length-prefixed Unix domain socket.**

Each message is framed: `[u32 big-endian length][UTF-8 JSON payload]`.

Request:
```json
{
  "jsonrpc": "2.0",
  "id": "req-42",
  "method": "analyze.key",
  "params": {
    "notes": [
      {"pitch": 60, "dur": 1.0}, {"pitch": 62, "dur": 0.5},
      {"pitch": 64, "dur": 0.5}, {"pitch": 65, "dur": 1.0}
    ],
    "algorithm": "krumhansl"
  }
}
```

Response:
```json
{
  "jsonrpc": "2.0",
  "id": "req-42",
  "result": {
    "tonic": "G",
    "mode": "major",
    "correlation": 0.871,
    "alternatives": [
      {"tonic": "E", "mode": "minor", "correlation": 0.849}
    ]
  }
}
```

Error response:
```json
{
  "jsonrpc": "2.0",
  "id": "req-42",
  "error": {"code": -32603, "message": "music21.exceptions21.Music21Exception: ..."}
}
```

**RPC method table:**

| Method | Input | Output |
|---|---|---|
| `corpus.search` | `{:composer :time-sig :num-parts ...}` | `[{:id :title :composer}]` |
| `corpus.parse` | `{:id}` | score handle `{:handle "h1", :num-parts N}` |
| `score.soprano` | `{:handle}` | note sequence |
| `score.part` | `{:handle :part-index}` | note sequence |
| `score.measures` | `{:handle :start :end}` | note sequence |
| `analyze.key` | `{:notes :algorithm}` | key map + alternatives |
| `analyze.key-floating` | `{:notes :window-size}` | `[{:beat :tonic :mode}]` |
| `analyze.roman` | `{:chord :key}` | roman numeral map |
| `analyze.voice-leading` | `{:parts [[notes] ...]}` | `{:violations [...]}` |
| `analyze.prime-form` | `{:pitch-classes}` | prime form vector |
| `analyze.forte-class` | `{:pitch-classes}` | Forte class string |
| `analyze.interval-vector` | `{:pitch-classes}` | 6-element vector |
| `tone-row.create` | `{:pitch-classes}` | row handle |
| `tone-row.matrix` | `{:handle}` | 12×12 matrix |
| `tone-row.prime` | `{:handle :transposition}` | pitch class sequence |
| `tone-row.inversion` | `{:handle :transposition}` | pitch class sequence |
| `tone-row.retrograde` | `{:handle :transposition}` | pitch class sequence |
| `tone-row.retrograde-inversion` | `{:handle :transposition}` | pitch class sequence |
| `tone-row.all-forms` | `{:handle}` | map of all 48 forms |
| `tone-row.is-all-interval` | `{:handle}` | boolean |
| `score.export` | `{:notes :format}` | `{:path}` or `{:data}` |
| `score.show` | `{:notes}` | opens MuseScore/LilyPond |
| `session.drop` | `{:handle}` | — |
| `session.gc` | — | frees all handles |

### 20.5 Semantic Model Mapping

The table below defines the canonical JSON representation of each Music21 type as it
crosses the IPC boundary, and its equivalent cljseq Clojure type.

| Concept | Music21 Python | JSON wire | cljseq Clojure |
|---|---|---|---|
| **Pitch** | `pitch.Pitch('C#4')` | `{"midi": 61, "name": "C#", "octave": 4, "acc": 1}` | `{:pitch/midi 61 :pitch/name :C# :pitch/octave 4}` |
| **Microtone** | `pitch.Pitch('C4', microtone=25)` | `{"midi": 60, "cents_offset": 25}` | `{:pitch/midi 60 :pitch/cents 25}` |
| **Interval** | `interval.Interval('P5')` | `{"semitones": 7, "name": "P5", "directed": 7}` | `{:interval/name :P5 :interval/semitones 7}` |
| **Duration** | `duration.Duration(1.0)` | `{"beats": 1.0, "type": "quarter"}` | `{:dur/beats 1}` |
| **Note** | `note.Note('C4', quarterLength=1)` | `{"midi": 60, "dur": 1.0, "beat": 1.0}` | `{:pitch/midi 60 :dur/beats 1 :time/beat 1}` |
| **Rest** | `note.Rest(quarterLength=1)` | `{"rest": true, "dur": 1.0}` | `{:rest? true :dur/beats 1}` |
| **Chord** | `chord.Chord(['C4','E4','G4'])` | `{"pitches": [60,64,67], "quality": "major", "root": "C"}` | `{:chord/pitches [60 64 67] :chord/quality :major :chord/root :C}` |
| **Key** | `key.Key('G')` | `{"tonic": "G", "mode": "major", "sharps": 1}` | `{:key/tonic :G :key/mode :major}` |
| **Time sig** | `meter.TimeSignature('6/8')` | `{"numerator": 6, "denominator": 8}` | `{:time-sig/num 6 :time-sig/den 8}` |
| **Note sequence** | `stream.Stream` (flat) | `[{"midi": N, "dur": D, "beat": B}, ...]` | `[{:pitch/midi N :dur/beats D :time/beat B} ...]` |
| **Pitch class set** | `set([0, 3, 7])` | `[0, 3, 7]` | `#{0 3 7}` |
| **Tone row** | `serial.ToneRow([...])` | `{"handle": "row-1", "pc": [...]}` | `{:row/handle "row-1" :row/pc [...]}` |
| **Score handle** | `stream.Score` (server-side) | `{"handle": "h1", "num_parts": 4, "title": "..."}` | `{:m21/handle "h1" :m21/num-parts 4}` |

Score objects are kept server-side and referenced by handle to avoid repeated
serialization of large data structures. Only the flat note sequences extracted
from scores cross the wire.

### 20.6 Clojure Namespace: `cljseq.m21`

```clojure
(ns cljseq.m21
  "Client for the Music21 Python sidecar.
  All calls return Clojure promises. Deref with a timeout to avoid blocking
  live loops. Start the sidecar with (m21/start!) before first use.")

;;; Lifecycle

(defn start!
  "Start the Music21 Python sidecar subprocess. Idempotent."
  [& {:keys [python-bin socket-path timeout-ms]
      :or   {python-bin "python3"
             timeout-ms 5000}}])

(defn stop! [] ...)
(defn running? [] ...)

;;; Corpus

(defn search-corpus
  "Returns a promise of [{:id :title :composer :num-parts}].
  opts: :composer :time-sig :num-parts :key-sig"
  [opts])

(defn parse-corpus
  "Returns a promise of a score handle map.
  path: Music21 corpus path, e.g. \"bach/bwv66.6\""
  [path])

(defn parse-file
  "Returns a promise of a score handle map. Accepts MusicXML, MIDI, ABC."
  [file-path])

;;; Score extraction

(defn soprano [score-handle])
(defn part    [score-handle part-index])
(defn measures [score-handle start end])
;; All return promises of note sequences

;;; Analysis

(defn analyze-key
  "Krumhansl-Schmuckler key analysis.
  notes: seq of {:pitch/midi N :dur/beats D}.
  Returns promise of {:key/tonic :K :key/mode :m :correlation 0.87}"
  [notes & {:keys [algorithm] :or {algorithm :krumhansl}}])

(defn analyze-key-floating
  "Windowed key analysis for modulation detection.
  Returns promise of [{:time/beat B :key/tonic :K :key/mode :m}]"
  [notes & {:keys [window-beats] :or {window-beats 4}}])

(defn analyze-roman
  "Roman numeral analysis for a chord in a key.
  chord: {:chord/pitches [midi...]}  key: {:key/tonic :C :key/mode :major}
  Returns promise of {:numeral \"IV\" :scale-degree 4 :inversion 0}"
  [chord key])

(defn check-voice-leading
  "Four-voice counterpoint check.
  parts: vector of 4 note sequences (SATB order).
  Returns promise of {:violations [{:beat B :type :parallel-fifths :voices [0 1]}]}"
  [parts])

;;; Post-tonal / serial

(defn prime-form   [pitch-class-set])   ; → promise of vector
(defn forte-class  [pitch-class-set])   ; → promise of string "3-11A"
(defn interval-vector [pitch-class-set]) ; → promise of 6-vector

(defn tone-row
  "Construct a ToneRow from a 12-element pitch class vector.
  Returns a promise of {:row/handle H :row/pc [...]}"
  [pitch-classes])

(defn row-prime    [row-handle transposition])  ; → promise of pc vector
(defn row-inversion [row-handle transposition]) ; → promise of pc vector
(defn row-retrograde [row-handle transposition]) ; → promise of pc vector
(defn row-ri       [row-handle transposition])  ; → promise of pc vector
(defn row-matrix   [row-handle])                ; → promise of 12×12 vec-of-vecs
(defn row-all-forms [row-handle])               ; → promise of map of all 48 forms

;;; Export / notation

(defn export-xml
  "Serialize a note sequence to MusicXML. Returns promise of {:path P}"
  [notes out-path])

(defn export-midi
  "Serialize a note sequence to MIDI file. Returns promise of {:path P}"
  [notes out-path & {:keys [bpm] :or {bpm 120}}])

(defn show
  "Open a note sequence in MuseScore or LilyPond for notation display.
  Non-blocking; returns immediately."
  [notes])

;;; Session management

(defn drop-handle [handle])  ; release server-side score object
(defn gc! [])                ; release all handles
```

### 20.7 Use Cases in Live Coding

#### Corpus as Seed Material

```clojure
;; Import Bach chorale BWV 66.6, extract soprano line
(def chorale @(m21/parse-corpus "bach/bwv66.6"))
(def soprano-line @(m21/soprano chorale))

;; Use the pitch sequence as material for a live loop
(live-loop :melody
  (doseq [note (take 8 soprano-line)]
    (play! :midi/piano (:pitch/midi note) :dur (:dur/beats note)))
  (sleep! 8))
```

#### Adaptive Harmonic Context

```clojure
;; Analyze key from recent MIDI input, update harmony binding
(defonce last-notes (atom []))

(live-loop :adaptive-harmony
  (let [key-result @(m21/analyze-key @last-notes)]
    (set-harmony! (:key/tonic key-result) (:key/mode key-result)))
  (sleep! 4))
```

#### Twelve-Tone Row Composition

```clojure
;; Define a row and build a matrix of all 48 forms
(def my-row @(m21/tone-row [0 11 3 4 8 7 9 6 1 5 2 10]))
(def matrix @(m21/row-matrix my-row))

;; Use rows as melodic material
(live-loop :serial-melody
  (let [form (rand-nth [:prime :inversion :retrograde :ri])
        trans (rand-int 12)
        pcs   @(case form
                 :prime      (m21/row-prime my-row trans)
                 :inversion  (m21/row-inversion my-row trans)
                 :retrograde (m21/row-retrograde my-row trans)
                 :ri         (m21/row-ri my-row trans))]
    (doseq [pc pcs]
      (play! :midi/piano (+ 60 pc))
      (sleep! 1/4))))
```

#### Voice Leading Validation

```clojure
;; Before committing a chord progression, check voice leading
(defn safe-progression? [satb-parts]
  (let [result @(m21/check-voice-leading satb-parts)]
    (empty? (:violations result))))
```

#### Notation Export

```clojure
;; Capture a live loop's output and export to MusicXML
(def captured (capture-loop :melody 16))  ; 16 beats
@(m21/export-xml captured "/tmp/melody.xml")
@(m21/show captured)   ; opens MuseScore
```

### 20.8 Division of Labor

The critical question is: when does cljseq compute locally, and when does it delegate
to Music21? The rule is simple: **performance path = cljseq; analysis path = Music21**.

| Concern | cljseq native | Music21 sidecar | Notes |
|---|---|---|---|
| Pitch arithmetic (transpose, add interval) | Yes | No | Sub-microsecond, on hot path |
| Scale construction (interval networks) | Yes | No | Used in every `play!` call |
| Chord spelling from Roman numeral | Yes (basic) | Yes (full) | cljseq handles playback cases; Music21 handles figured bass |
| Key analysis | Heuristic only | Yes — full K-S | Music21 result used to update `*key*` binding |
| Roman numeral parsing | Partial | Yes | cljseq parses `:I :IV :V`; Music21 handles secondary dominants, suspensions |
| Voice leading check | No | Yes | Pre-composition check; not on hot path |
| Tone row prime/retrograde/inversion | Yes (pure functions) | Yes (handles all 48) | cljseq handles common cases; Music21 for matrix and Z-relations |
| Post-tonal set theory | Yes (prime form, Forte) | Yes (canonical) | Music21's implementation is the reference |
| MusicXML / MIDI file import | No | Yes | |
| MusicXML / MIDI file export | No | Yes | |
| Corpus search | No | Yes | |
| Notation rendering (LilyPond, MuseScore) | No | Yes | |
| Modulation detection | No | Yes | |
| Figured bass realization | No | Yes | |
| Counterpoint (Fux rules) | No | Yes | |
| Mensural notation | No | Yes | Josquin / early music |
| Beat strength analysis | No | Yes | |

### 20.9 Python Server Implementation

The server is a single Python file (`sidecar/m21_server.py`), no framework, using only
`asyncio` from the standard library plus Music21.

```python
#!/usr/bin/env python3
"""cljseq Music21 analysis sidecar."""

import asyncio
import json
import struct
import sys
from pathlib import Path
import music21
from music21 import corpus, key, serial, voiceLeading, harmony

# ── Session state ────────────────────────────────────────────────
_handles: dict[str, object] = {}
_handle_counter = 0

def _new_handle(obj) -> str:
    global _handle_counter
    _handle_counter += 1
    h = f"h{_handle_counter}"
    _handles[h] = obj
    return h

# ── Serialization helpers ────────────────────────────────────────
def _pitch_to_json(p) -> dict:
    return {"midi": p.midi, "name": p.name,
            "octave": p.octave, "acc": p.accidental.alter if p.accidental else 0}

def _note_to_json(n) -> dict:
    if n.isRest:
        return {"rest": True, "dur": float(n.duration.quarterLength),
                "beat": float(n.beat)}
    return {"midi": n.pitch.midi, "name": n.pitch.name,
            "octave": n.pitch.octave,
            "dur": float(n.duration.quarterLength),
            "beat": float(n.beat)}

def _stream_to_json(s) -> list:
    return [_note_to_json(n) for n in s.flat.notesAndRests]

# ── Method dispatch ──────────────────────────────────────────────
async def _dispatch(method: str, params: dict) -> object:
    if method == "corpus.search":
        results = corpus.search(**{k: v for k, v in params.items()})
        return [{"id": r.corpusFilepath, "title": r.metadata.title or "",
                 "composer": r.metadata.composer or ""}
                for r in results[:50]]

    elif method == "corpus.parse":
        score = corpus.parse(params["id"])
        h = _new_handle(score)
        return {"handle": h, "num_parts": len(score.parts),
                "title": score.metadata.title or ""}

    elif method == "score.part":
        score = _handles[params["handle"]]
        part = score.parts[params["part_index"]]
        return _stream_to_json(part)

    elif method == "analyze.key":
        from music21 import stream as m21stream
        s = m21stream.Stream()
        for n in params["notes"]:
            from music21 import note as m21note, duration as m21dur
            note = m21note.Note(n["midi"])
            note.duration = m21dur.Duration(n.get("dur", 1.0))
            s.append(note)
        k = s.analyze("key")
        return {"tonic": k.tonic.name, "mode": k.mode,
                "correlation": k.correlationCoefficient}

    elif method == "tone-row.create":
        row = serial.ToneRow(params["pitch_classes"])
        h = _new_handle(row)
        return {"handle": h, "pc": list(row.pitchClasses)}

    elif method == "tone-row.matrix":
        row = _handles[params["handle"]]
        m = row.matrix()
        return [[int(pc) for pc in row_form] for row_form in m]

    elif method == "tone-row.prime":
        row = _handles[params["handle"]]
        t = params.get("transposition", 0)
        return list(row.prime().transpose(t).pitchClasses)

    elif method == "analyze.voice-leading":
        # ... (four-part VL check implementation)
        pass

    elif method == "session.gc":
        _handles.clear()
        return {"dropped": True}

    else:
        raise ValueError(f"Unknown method: {method}")

# ── JSON-RPC server ──────────────────────────────────────────────
async def handle_client(reader, writer):
    while True:
        header = await reader.readexactly(4)
        length = struct.unpack(">I", header)[0]
        payload = await reader.readexactly(length)
        req = json.loads(payload)

        response = {"jsonrpc": "2.0", "id": req.get("id")}
        try:
            result = await _dispatch(req["method"], req.get("params", {}))
            response["result"] = result
        except Exception as e:
            response["error"] = {"code": -32603, "message": str(e)}

        data = json.dumps(response).encode()
        writer.write(struct.pack(">I", len(data)) + data)
        await writer.drain()

async def main():
    socket_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cljseq-m21.sock"
    Path(socket_path).unlink(missing_ok=True)
    server = await asyncio.start_unix_server(handle_client, path=socket_path)
    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    asyncio.run(main())
```

### 20.10 Deployment and Dependencies

| Dependency | Version | How acquired |
|---|---|---|
| Python | ≥ 3.10 | System Python or virtualenv |
| Music21 | ≥ 9.0 | `pip install music21` |
| MuseScore (optional) | ≥ 4 | For `m21/show` notation display |
| LilyPond (optional) | ≥ 2.24 | For `m21/show` notation display |

The sidecar server file (`sidecar/m21_server.py`) requires no additional Python
packages beyond Music21 and the standard library. The Clojure `cljseq.m21` namespace
launches it via `ProcessBuilder`, passing the socket path as argv[1].

**Startup sequence:**
1. `(m21/start!)` calls `ProcessBuilder("python3", "sidecar/m21_server.py", socket-path)`
2. Server writes `"READY\n"` to stdout when the Unix socket is listening
3. Clojure reads stdout until `"READY"` is seen (with a 5-second timeout)
4. First `m21/` call sends a ping; server responds immediately
5. All subsequent calls are pipelined over the same socket connection

**No Music21 corpus licensing concern for distribution**: cljseq does not bundle any
corpus files. Users install Music21 via pip, which includes the corpus under its own
terms. cljseq only provides the `corpus.search` / `corpus.parse` RPC calls that
delegate to Music21 at runtime.

### 20.11 Relationship to §10 and §11

The analysis primitives in §10 and the corpus API in §11 should be understood as the
Clojure-facing specification; §20 is the implementation engine behind them:

| §10 / §11 API | §20 backing implementation |
|---|---|
| `analyze/key` | `m21/analyze-key` → `analyze.key` RPC |
| `analyze/roman-numeral` | `m21/analyze-roman` → `analyze.roman` RPC |
| `analyze/voice-leading` | `m21/check-voice-leading` → `analyze.voice-leading` RPC |
| `analyze/prime-form` | `m21/prime-form` → `analyze.prime-form` RPC |
| `analyze/forte-class` | `m21/forte-class` → `analyze.forte-class` RPC |
| `analyze/interval-vector` | `m21/interval-vector` → `analyze.interval-vector` RPC |
| `corpus/search` | `m21/search-corpus` → `corpus.search` RPC |
| `corpus/parse` | `m21/parse-corpus` → `corpus.parse` RPC |
| `corpus/find-progression` | `m21/search-corpus` + harmonic filtering |

The thin `analyze.clj` and `corpus.clj` namespaces become adapters over
`cljseq.m21` rather than standalone implementations. This gives §10 and §11 full
musicological depth with zero reimplementation cost.

### 20.12 Music21 as an MCP Source

The Music21 sidecar's capabilities map cleanly onto MCP concepts, making it a
natural source of tools and resources that an AI assistant (or an MCP client
of any kind) can invoke to assist in composition.

#### MCP Tools from Music21

| MCP tool name | Underlying RPC | Description |
|---|---|---|
| `m21_analyze_key` | `analyze.key` | Krumhansl-Schmuckler key analysis |
| `m21_analyze_key_floating` | `analyze.key-floating` | Modulation detection |
| `m21_analyze_roman` | `analyze.roman` | Roman numeral of a chord in a key |
| `m21_check_voice_leading` | `analyze.voice-leading` | Four-voice counterpoint check |
| `m21_prime_form` | `analyze.prime-form` | Normal-order prime form of pitch class set |
| `m21_forte_class` | `analyze.forte-class` | Forte set class label |
| `m21_tone_row` | `tone-row.create` | Create and cache a 12-tone row |
| `m21_row_matrix` | `tone-row.matrix` | Full 12×12 transformation matrix |
| `m21_row_all_forms` | `tone-row.all-forms` | All 48 row forms (P/I/R/RI × 12 transpositions) |
| `m21_search_corpus` | `corpus.search` | Search Music21 corpus by metadata |
| `m21_parse_corpus` | `corpus.parse` | Load a corpus work; return score handle |
| `m21_score_part` | `score.part` | Extract a part as a note sequence |
| `m21_export_xml` | `score.export` | Export note sequence to MusicXML |
| `m21_show` | `score.show` | Open in MuseScore/LilyPond |

These tools are registered in the MCP tool registry at server startup if the Music21
sidecar is running. They appear alongside the control-tree method nodes (§18.2).

#### Compositional Use Cases for AI Assistance

With Music21 tools exposed via MCP, an AI assistant connected to cljseq can:

1. **Analyze ongoing performance**: "What key am I playing in?" → `m21_analyze_key`
   on the recent note buffer → propose a `with-harmony` binding change.

2. **Suggest voice leading corrections**: "My chord progression has parallel fifths"
   → `m21_check_voice_leading` → report specific violations → suggest corrected
   voicing.

3. **Retrieve corpus material**: "Find a Bach chorale with a Hijaz-like interval in
   the soprano" → `m21_search_corpus` with custom criteria → `m21_parse_corpus` →
   `m21_score_part` → return the soprano as a pitch sequence the user can seed into
   a `live-loop`.

4. **Twelve-tone assistance**: "Generate all 48 forms of this row and suggest which
   forms would create maximum contrast with the current material" → `m21_tone_row` +
   `m21_row_all_forms` → harmonic distance analysis.

5. **Live notation review**: "Show me what I just played as a score" → `m21_export_xml`
   on the captured loop → `m21_show` → MuseScore opens in a window alongside the REPL.

#### MCP Prompt Templates

The MCP `prompts` registry includes Music21-aware prompt templates:

```json
{
  "name": "analyze_and_harmonize",
  "description": "Analyze the key of a note sequence and suggest a harmony binding",
  "arguments": [{"name": "notes", "description": "Recent notes as MIDI numbers", "required": true}]
}
```

```json
{
  "name": "find_corpus_seed",
  "description": "Find a corpus passage matching stylistic criteria for use as a live-loop seed",
  "arguments": [
    {"name": "style", "description": "e.g. 'Bach chorale', 'Essen folksong'"},
    {"name": "key", "description": "Target key, e.g. 'D minor'"},
    {"name": "rhythm", "description": "e.g. '6/8', 'syncopated 4/4'"}
  ]
}
```

```json
{
  "name": "suggest_tone_row",
  "description": "Suggest a 12-tone row with specific intervallic properties",
  "arguments": [
    {"name": "property", "description": "e.g. 'all-interval', 'Z-related', 'symmetric'"}
  ]
}
```

#### Architecture: MCP ↔ Music21 Bridge

The cljseq MCP server (§18.3) dispatches `tools/call` requests for `m21_*` tool names
to the Music21 sidecar via the existing `cljseq.m21` IPC client rather than
directly to the control tree. The bridge is a simple method dispatch in the MCP handler:

```clojure
(defmethod mcp/handle-tool "m21_analyze_key" [params session]
  (let [notes (:notes params)
        algo  (:algorithm params "krumhansl")]
    @(m21/analyze-key notes :algorithm (keyword algo))))

(defmethod mcp/handle-tool "m21_search_corpus" [params session]
  @(m21/search-corpus params))
```

Because Music21 calls return promises, the MCP handler deref's them within a
bounded timeout (default 5 seconds). For long-running operations (corpus parsing),
the tool can return a "job handle" and support a separate `m21_job_status` tool
for polling — but in practice the 5-second timeout covers all common cases.

### 20.13 Design Questions Raised

See `doc/open-design-questions.md` Q20–Q24.

---

## 21. World Music Traditions

### 21.1 Motivation

Western common-practice theory dominates most music software. cljseq should be equally
at home generating a Hijaz maqam taqsim, a Javanese gamelan colotomic layer, an
Afro-Cuban clave montuno, or a Polynesian hula bell pattern. Each tradition brings
distinct tuning systems, modal structures, and rhythmic frameworks that enrich the
live-coding vocabulary. The Scala tuning format (§7) and polyrhythmic scheduler
(§22) are the technical foundations; this section defines the musical vocabulary
that sits on top of them.

### 21.2 Arabic and Middle-Eastern Music (Maqamat)

#### 21.2.1 Scale Structure

Arabic music is organized around **maqamat** (singular: maqam). Each maqam is a
mode built from **ajnas** (singular: jins) — tetrachordal or pentachordal cells
that characterize the mode's intervallic flavor. Unlike Western modes, a maqam
specifies not only the scale but also:

- Characteristic melodic phrases and ornaments
- A **qarar** (resting tone / finalis)
- A **ghammaz** (secondary dominant tone, often a fifth above the tonic)
- Typical phrase shapes (ascending vs. descending variants may differ)

Intervals are measured in **mujannab** (quarter-tone steps = 50 cents). Standard
Arabic practice uses 24-tone equal temperament (24-TET) as a common approximation,
though regional traditions deviate:

| Interval name | Semitones | Cents |
|---|---|---|
| Half tone | 0.5 | 50 |
| Mujannab (3/4 tone) | 0.75 | 150 |
| Tone | 1 | 200 |
| Augmented second | 1.5 | 300 (hijaz jins) |

#### 21.2.2 Common Maqamat

| Maqam | Lower jins | Upper jins | Characteristic interval | Western approximate |
|---|---|---|---|---|
| **Rast** | Rast (0-2-3.5-5) | Nahawand | 3/4-tone on 3rd | "Major with flat 3" |
| **Bayati** | Bayati (0-1.5-3-5) | Rast | 3/4-tone on 2nd | "Dorian with flat 2" |
| **Hijaz** | Hijaz (0-1-4-5) | Rast | Augmented 2nd | "Harmonic minor scale" |
| **Saba** | Saba (0-1.5-3-4.5) | Hijaz | Lowered 4th | Unique to Arabic tradition |
| **Nahawand** | Nahawand (0-2-3-5) | Hijaz | — | Natural minor |
| **Kurd** | Kurd (0-1-3-5) | Nahawand | Minor 2nd | Phrygian |
| **Ajam** | Ajam (0-2-4-5) | Ajam | — | Major |
| **Hijaz Kar** | Hijaz | Hijaz | Two augmented 2nds | Double harmonic scale |

Cents offsets from 12-TET for Bayati on D: D=0, E♭+50¢=150, F=300, G=500, A=700,
B♭=1000, C=1100, D=1200.

These are representable as Scala `.scl` files and loaded via §7's tuning machinery.
cljseq ships a `resources/scales/maqam/` directory with `.scl` files for all
common maqamat.

#### 21.2.3 Modulation (Intiqal)

In performance, maqam can shift to a related maqam — typically sharing a jins at
the ghammaz. For example, Rast can modulate to Bayati at its fifth. cljseq represents
this as a tuning and `*key*` binding swap, phase-quantized to a beat boundary via Link.

```clojure
;; Modulate from Rast on C to Bayati on G at the next phrase boundary
(at-sync! 8 :key  ; wait for 8-beat phrase boundary
  (set-maqam! :bayati :on :G))
```

#### 21.2.4 Iqa'at (Rhythmic Cycles)

Arabic rhythmic cycles:

| Iqa' | Time feel | Pattern (D=dum, T=tek, K=ka) | Beats |
|---|---|---|---|
| **Wahda** | 4/4 slow | D . . T | 4 |
| **Maqsum** | 4/4 fast | D . T K T . | 8 |
| **Baladi** | 4/4 | D D T K . T . | 8 |
| **Samai Thaqil** | 10/8 | D . T . D D . T . . | 10 |
| **Jurjina** | 6/8 | D T K . T K | 6 |
| **Ayyub** | 2/4 | D . K T | 4 (cycled) |
| **Malfuf** | 2/4 fast | D K T K | 4 |

These are implemented as named patterns in the `cljseq.rhythm` namespace (§22):

```clojure
(rhythm/iqa :maqsum)
;; → {:hits [0 2 3 5] :period 8 :subdivisions 8}
```

### 21.3 Javanese and Balinese Gamelan

#### 21.3.1 Tuning: Pelog and Slendro

Gamelan ensembles use two tuning systems, never mixed in a single piece:

**Slendro**: approximately 5-tone equal temperament (~240-cent steps), but the actual
intervals vary by ensemble and rarely match any equal-tempered system. Each physical
gamelan has its own unique tuning.

**Pelog**: a 7-tone system (from which 5-tone subsets are drawn for specific *pathet*):
- Approximate step pattern: 1, 1.5, 0.5, 1, 1.5, 0.5, 1 (in semitones)
- Three pathet (modes): Nem (tones 1-2-4-5-6), Barang (2-3-5-6-7), Lima (1-3-4-5-7)
- Like maqam, the pathet specifies more than just the scale: characteristic phrases,
  performance times, and emotional register

Since every gamelan has unique tuning, cljseq must support **per-ensemble tuning
tables**. A Scala `.kbm` mapping file pairs the scale's theoretical intervals to the
specific instrument's physical tuning. The `resources/scales/gamelan/` directory can
ship reference tunings from documented ensembles (e.g., Kyahi Kanyut Mesem from
Wesleyan, which has been carefully measured and documented).

```clojure
;; Load a specific gamelan's tuning
(tuning/load! :gamelan/kyahi-kanyut-mesem-pelog)
(with-tuning :gamelan/kyahi-kanyut-mesem-pelog
  (live-loop :balungan
    (play-scale! :pelog-nem [1 2 4 5 6] :dur 1)
    (sleep! 4)))
```

#### 21.3.2 Colotomic Structure

The defining feature of gamelan music is its **colotomic structure**: different
instruments play the same cycle at different metric densities, creating an interlocking
temporal architecture.

| Instrument role | Density | Clojure layer |
|---|---|---|
| Saron (balungan/skeleton) | Every beat | `live-loop :balungan` |
| Bonang (elaboration) | 2× balungan | `live-loop :bonang` |
| Kendhang (drum) | Structural markers | `live-loop :kendhang` |
| Kempul (gong) | 1/2 balungan | `live-loop :kempul` |
| Kenong | 1/4 balungan | `live-loop :kenong` |
| Gong ageng | 1 full cycle | `live-loop :gong` |

The **irama** system defines tempo ratios between layers. In irama tanggung,
the bonang plays 4 notes per balungan note; in irama dadi, 8 notes. cljseq
represents irama as a ratio applied to `sleep!` durations.

```clojure
(defonce irama (atom 4))  ; current irama ratio

(live-loop :bonang
  (doseq [n (bonang-pattern (current-balungan-note))]
    (play! :synth/bonang n :dur (/ 1 @irama))
    (sleep! (/ 1 @irama))))
```

The gong cycle (gongan) plays every 16 or 32 beats; each gamelan piece defines the
length of its gongan. cljseq expresses this as a long-period `live-loop` or via
`sync!` with a named cue at the gong stroke.

### 21.4 African Polyrhythm and Timeline Patterns

#### 21.4.1 The Timeline Principle

Sub-Saharan African music is organized around a **timeline pattern** (bell pattern)
— a fixed asymmetric pattern played on a bell or claves that all other parts
reference. The timeline is neither a meter nor a melody but a rhythmic reference
frame.

The most widespread timeline pattern is the **standard African pattern**
(also called the "5-against-8" or the "12-pulse" timeline):

```
Pulse:    1 . . 2 . . 3 . . 4 . . (12 pulses per cycle)
Bell:     X . . X . X . X . . X .
Position: 0   3   5   7   10  (onset positions in 12-pulse grid)
```

In 12/8 time this reads: beat 1, the "and" of beat 1, beat 2, beat 3, and the "and"
of beat 3. This pattern spans both triple and duple metric interpretations, making it
a powerful cross-rhythm anchor.

cljseq provides named timeline patterns:

```clojure
(rhythm/timeline :standard-african)  ; [0 3 5 7 10] in 12 pulses
(rhythm/timeline :ewe-gankogui)       ; [0 2 4 7 9] in 12 pulses
(rhythm/timeline :son-clave)          ; [0 3 6 8] in 16 pulses (2:3 feel)
(rhythm/timeline :rumba-clave)        ; [0 3 7 8] in 16 pulses (2:3 feel)
(rhythm/timeline :bossa-nova-clave)   ; [0 3 6 10 12] in 16 pulses
```

#### 21.4.2 Cross-Rhythm and Polymeter

African polyrhythm often involves **cross-rhythm**: simultaneous patterns whose
periods are coprime, creating an emergent composite pattern. The standard cross-rhythms:

| Pattern | Period | Against | Total cycle |
|---|---|---|---|
| 3:2 | 3 notes in 2 beats | 2 notes in 2 beats | 6 pulses |
| 4:3 | 4 notes in 3 beats | 3 notes in 3 beats | 12 pulses |
| 3:4 | 3 notes in 4 beats | 4 notes in 4 beats | 12 pulses |
| 2:3:4 | Three independent layers | — | 12 pulses |

```clojure
;; 3-against-4 cross-rhythm
(live-loop :cross-3
  (play! :midi/clave 76)
  (sleep! 4/3))   ; 4 beats / 3 hits

(live-loop :cross-4
  (play! :midi/clave 77)
  (sleep! 1))     ; on every beat
```

Link synchronizes both loops to the same beat grid, preserving the phase relationship
across tempo changes.

#### 21.4.3 Hemiola

**Hemiola** is the specific superimposition of 3:2 — three notes in the space of two.
In Afro-Cuban music this is the foundation of clave: the 3-side and 2-side alternate
in successive bars. cljseq expresses this as consecutive `sleep!` durations:

```clojure
;; Son clave (2-3 orientation)
(live-loop :son-clave
  ;; 2-side (first bar of two)
  (play! :midi/clave 76) (sleep! 1)
  (play! :midi/clave 76) (sleep! 1)
  ;; 3-side (second bar of two)
  (play! :midi/clave 76) (sleep! 2/3)
  (play! :midi/clave 76) (sleep! 2/3)
  (play! :midi/clave 76) (sleep! 2/3))
```

Or using the pattern primitive:

```clojure
(live-loop :clave
  (play-timeline! :midi/clave (rhythm/timeline :son-clave) :dur 1/8)
  (sleep! 2))
```

### 21.5 Caribbean: Clave and Montuno

The Cuban and Caribbean tradition extends the African timeline into rich arranged
forms. Key patterns:

**Tresillo** (3-3-2 over 8 pulses): the fundamental African rhythmic cell that
arrived in Cuba and became the basis of most Afro-Cuban and North American
popular music (reggaeton, hip-hop's "boom-chick"):

```
Pulse: 1 . . 2 . . 3 .
Hit:   X . . X . . X .
```

**Cinquillo** (2-1-2-1-2 over 8 pulses): the elaboration of tresillo used in danzón,
mambo, and cha-chá-chá:

```
Pulse: 1 . 2 . 3 . 4 .
Hit:   X . X X . X X .
```

**Montuno** pattern: a repeating piano/keyboard figure built from the clave grid,
combining chord voicings with the syncopated clave rhythm. In cljseq, this is an
arpeggio pattern locked to the clave timeline:

```clojure
(live-loop :montuno
  (play-arpeggio! :midi/piano :pattern :montuno :chord (current-chord)
                  :timeline (rhythm/timeline :son-clave))
  (sleep! 2))
```

### 21.6 Polynesian and Hawaiian

Polynesian and Hawaiian music centers on vocal harmony and percussion timekeeping.

**'Ukulele patterns**: strum patterns in 4/4 with characteristic syncopation
(D DU UDU is the "island strum"). While primarily a chord strumming idiom, the
rhythmic pattern maps to a `timeline` object.

**Hula**: rhythmic accompaniment by pahu drum and 'ili'ili stones; patterns are
based on syllabic delivery of text. Less relevant to cljseq's sequencing model
than the rhythmic patterns of African/Caribbean traditions, but the tuning systems
(Hawaiian uses standard 12-TET) present no special challenges.

**Slack-key guitar tunings**: various open tunings (C wahine, G wahine, etc.)
are expressible as `.kbm` files that retune MIDI pitch numbers to the open-string
voicings. Relevant for any guitar-controller workflow.

### 21.7 Music21 Coverage of Non-Western Traditions

Music21's non-Western coverage:

| Tradition | Music21 support | Notes |
|---|---|---|
| Arabic maqam | `scale.MaqamScale` (experimental) | Some maqamat; quarter-tone pitch support via `microtone` |
| Gamelan | Minimal native; `.scl` via `environment` | Corpus: a few documented pieces |
| Indian ragas | `scale.RagAsawari`, etc. | Several ragas in `music21.scale` |
| West African | Minimal | No dedicated timeline module |
| Chinese scales | `scale.ChromaticScale` + custom | pentatonic, gong/shang/jiao/zhi/yu |

The `m21/analyze-key` call accepts custom scale profiles; for maqam key detection,
a Bayati or Hijaz pitch-class histogram can be passed as the `algorithm` parameter
via a custom Music21 `WeightedHexatachord` profile.

### 21.8 Scale Library

cljseq ships a bundled scale library covering non-Western tunings. It is separate from
Music21 — these are Scala `.scl` / `.kbm` files and EDN scale definitions that load
into §7's tuning engine at startup.

```
resources/
├── scales/
│   ├── maqam/
│   │   ├── rast-c.scl          ; Rast on C, 24-TET approximation
│   │   ├── bayati-d.scl        ; Bayati on D
│   │   ├── hijaz-d.scl         ; Hijaz on D
│   │   ├── saba-d.scl          ; Saba on D
│   │   └── ...                  ; all common maqamat
│   ├── gamelan/
│   │   ├── pelog-nem.scl       ; Reference pelog nem tuning
│   │   ├── pelog-barang.scl
│   │   ├── slendro.scl         ; Reference slendro
│   │   └── kyahi-kanyut-mesem-pelog.scl  ; Documented ensemble
│   ├── indian/
│   │   ├── raga-bhairav.scl
│   │   ├── raga-yaman.scl
│   │   └── ...
│   └── other/
│       ├── turkish-makam-*.scl
│       └── ...
└── rhythms/
    ├── iqa.edn                 ; Arabic iqa'at as hit patterns
    ├── timeline.edn            ; African timeline patterns
    └── clave.edn               ; Afro-Cuban clave patterns
```

### 21.9 Design Questions Raised

See `doc/open-design-questions.md` Q25–Q28.

---

## 22. Rhythmic Modulation and Parameter Animation

### 22.1 The Core Insight: Rhythm as a General Modulator

The `live-loop` + `play!` model treats rhythm as timing for note events. But rhythm is
a more general concept: **any controllable parameter can be animated by a rhythmic
pattern**. A Euclidean rhythm driving a filter cutoff is structurally identical to a
Euclidean rhythm driving note events — both are a sequence of time offsets within a
repeating cycle that trigger a value change at an addressable target.

This generalizes the NDLR's "Drone" part (a rhythmically repeating single note) to
any parameter: filters, amplitudes, reverb send, transpositions, chord voicings,
synthesis parameters, MIDI CC, CV voltages, OSC messages. The control tree (§16)
provides the addressing; the rhythmic engine (§22) provides the timing.

Three categories of rhythmic parameter animation:

1. **Step animation**: at each hit in a rhythm pattern, set a parameter to the
   next value in a value sequence. The value changes discretely per hit.
2. **Timed envelope**: between hits, interpolate from the current value to the next
   over some portion of the inter-hit interval (attack, decay, sustain, release).
3. **Continuous LFO**: a periodic function (sine, triangle, sawtooth, etc.) evaluated
   at each scheduling tick; the frequency is specified in beats, not Hz.

### 22.2 `param-loop`: Rhythmic Parameter Animation

`param-loop` is the counterpart to `live-loop` for non-note parameters:

```clojure
(param-loop name
  :target path-or-midi-cc     ; control tree path or {:midi/cc N :channel C}
  :rhythm rhythm-spec          ; Euclidean, timeline, or explicit step pattern
  :values value-source         ; sequence, function, or cycle
  :dur beat-count              ; total period in beats
  :interp :step | :linear | :exp  ; value interpolation between steps
  :quantize beat-align?        ; default true; aligns to Link beat
)
```

Examples:

```clojure
;; Animate filter cutoff with a Euclidean rhythm (5 hits in 16 steps)
(param-loop :filter-step
  :target "/cljseq/synths/surge/vcf/cutoff"
  :rhythm (euclid 5 16)
  :values (cycle [200 800 400 3200 1600])
  :dur 4)

;; Continuous sine LFO on reverb send (period = 3 beats)
(param-loop :reverb-lfo
  :target {:midi/cc 91 :channel 1}
  :rhythm :continuous
  :values (lfo :sine :period 3 :lo 0 :hi 127)
  :dur 3)

;; Sawtooth LFO on filter cutoff synced to beat grid
(param-loop :filter-saw
  :target "/cljseq/synths/surge/vcf/cutoff"
  :rhythm :continuous
  :values (lfo :sawtooth :period 2 :lo 300 :hi 8000)
  :dur 2)

;; Step-sequence an arpeggio transposition offset
(param-loop :transpose-seq
  :target "/cljseq/loops/melody/transpose"
  :rhythm (timeline :son-clave)
  :values (cycle [0 0 5 0 7 0 5 0])
  :dur 2)
```

The `:rhythm :continuous` mode calls the value function at every scheduling tick
(every 1ms by default) rather than on discrete hits. The sidecar sends CC/OSC
messages at the specified rate. For CLAP targets, the parameter changes are injected
as sample-accurate automation events within the audio buffer (§19.4).

### 22.3 LFO Primitives

```clojure
(lfo :sine      :period beats :lo lo :hi hi :phase phase-offset)
(lfo :cosine    :period beats :lo lo :hi hi :phase phase-offset)
(lfo :triangle  :period beats :lo lo :hi hi :phase phase-offset)
(lfo :sawtooth  :period beats :lo lo :hi hi :phase phase-offset)
(lfo :square    :period beats :lo lo :hi hi :duty duty-cycle)
(lfo :s&h       :period beats :lo lo :hi hi)  ; sample-and-hold (stepped random)
(lfo :envelope  :attack a :decay d :sustain s :release r)  ; one-shot ADSR
```

LFOs are defined relative to beats (not Hz), so they stay phase-coherent with Link
tempo changes. At 120 BPM, `:period 2` = 1 Hz; at 60 BPM, `:period 2` = 0.5 Hz —
always two beats per cycle regardless of tempo.

`phase-offset` allows multiple LFOs with the same period to run at different phases,
creating movement without additional complexity:

```clojure
;; Two LFOs 180° out of phase: one opens filter while the other closes it
(param-loop :filter-a :target "/cljseq/synths/lead/vcf/cutoff"
  :rhythm :continuous :values (lfo :sine :period 4 :lo 300 :hi 5000 :phase 0) :dur 4)
(param-loop :filter-b :target "/cljseq/synths/bass/vcf/cutoff"
  :rhythm :continuous :values (lfo :sine :period 4 :lo 300 :hi 5000 :phase 0.5) :dur 4)
```

### 22.4 Polyrhythmic Independence

Because `live-loop` and `param-loop` run as independent Clojure threads, any number
of rhythmic layers can run simultaneously with different periods. The Link clock is
the common reference — every loop's `sleep!` is relative to virtual time on the
Link beat grid.

```clojure
;; Melody: 4-beat period
(live-loop :melody :period 4
  (play! :midi/lead (scale-degree (next-degree!)) :dur 1/2)
  (sleep! 1/2))

;; Filter animation: 5-beat period (creates phasing with melody)
(param-loop :filter :period 5
  :target "/cljseq/synths/lead/vcf/cutoff"
  :rhythm (euclid 3 10)
  :values (cycle [1000 4000 2000 6000 800])
  :dur 5)

;; Reverb swell: 7-beat period
(param-loop :reverb :period 7
  :target {:midi/cc 91 :channel 1}
  :rhythm :continuous
  :values (lfo :sine :period 7 :lo 0 :hi 64)
  :dur 7)

;; Drummer: 2-beat period (plays at double time relative to melody)
(live-loop :drums :period 2
  (play! :midi/kick 36) (sleep! 1)
  (play! :midi/snare 38) (sleep! 1))
```

When periods are coprime (4, 5, 7), the combined pattern takes 4×5×7=140 beats to
fully cycle — producing apparent complexity from three simple patterns. This is the
core mechanism of Steve Reich's phase-shifting works and African cross-rhythm.

### 22.5 Arpeggiation Library

An **arpeggio** is a chord played note-by-note in a specific order, with a specific
rhythm. Unlike a static chord, an arpeggio is a pattern: it has an order, a rhythm,
and a period, and it can be played against any chord voicing.

#### 22.5.1 Pattern Representation

```clojure
;; An arpeggio is a map with:
;; :order — indices into the chord's pitches (0=root, 1=third, 2=fifth, ...)
;;           may extend beyond the chord (wrap around octave)
;; :rhythm — a rhythm spec (Euclidean, timeline, explicit)
;; :dur    — note duration as a fraction of step duration

{:name    :alberti
 :order   [0 2 1 2]       ; root-fifth-third-fifth
 :rhythm  [1 1 1 1]       ; four equal steps
 :dur     3/4}             ; each note lasts 3/4 of a step

{:name    :mozart-up
 :order   [0 1 2 3 4]     ; open position up
 :rhythm  [1 1 1 1 2]     ; last note held longer
 :dur     1/2}

{:name    :flamenco-rasgueado
 :order   [4 3 2 1 0 0]   ; down-strum then bass note
 :rhythm  [1/4 1/4 1/4 1/4 1 1/2]
 :dur     3/4}
```

#### 22.5.2 Corpus-Derived Arpeggios

The Bach chorales, Mozart piano sonatas, and Beethoven sonatas in the Music21 corpus
contain characteristic figuration patterns that can be extracted as named arpeggios.
A batch analysis job runs at project setup time:

```clojure
(corpus/extract-arpeggios!
  {:composers ["bach" "mozart" "beethoven"]
   :textures [:alberti :broken :harp]
   :min-frequency 50        ; only patterns appearing ≥ 50 times
   :output "resources/arpeggios/corpus-derived.edn"})
```

The resulting `.edn` file is bundled with cljseq. At runtime:

```clojure
(arp/ls)                          ; list all named patterns
(arp/get :alberti)                ; get pattern definition
(arp/play! :midi/piano :alberti (chord :C :major) :dur 1/4)
```

#### 22.5.3 Built-in Arpeggios

In addition to corpus-derived patterns, cljseq ships a library of named arpeggios:

| Name | Pattern | Notes |
|---|---|---|
| `:up` | 0-1-2-3... | Simple ascending |
| `:down` | n-n-1-...-0 | Simple descending |
| `:bounce` | 0-1-2-3-2-1 | Up then down |
| `:alberti` | 0-2-1-2 | Classical piano accompaniment |
| `:waltz-bass` | 0-2-2 | Waltz oom-pah-pah |
| `:broken-triad` | 0-1-2-0-1-2 | Repeated broken chord |
| `:guitar-pick` | 0-2-1-2-0-2 | Fingerpicking pattern |
| `:jazz-stride` | 0-0-2-1-2 | Stride bass-chord-chord |
| `:montuno` | 0-2-1-3-2-3 | Cuban montuno figure |
| `:raga-alap` | 0-1-0-2-1-0-3 | Elaborated ornamental |
| `:euclid-5-8` | Euclidean 5 in 8 | 5 notes from chord, 8 steps |

#### 22.5.4 Play-arpeggiation

```clojure
(play-arpeggio! target pattern-name-or-map chord
  :dur       note-dur       ; duration of each note
  :vel       velocity        ; or a velocity sequence
  :oct-range 1               ; how many octaves to span
  :timeline  timeline-spec   ; optional clave/timeline alignment
)
```

### 22.6 NDLR Drone Generalized

The NDLR's Drone part plays a single note repeatedly — the root of the current chord —
with a fixed rhythmic pattern and optional octave variation. cljseq generalizes this
to a **rhythmic voice**: any pitch pattern, any parameter-animation logic, bound to the
same harmonic context as the other parts.

```clojure
;; NDLR-style drone: root note, Euclidean rhythm
(live-loop :drone
  (play! :midi/bass (root) :dur 1/4 :vel 90)
  (sleep! (euclid-sleep! 3 8)))

;; Generalized: bass line that follows chord roots + fifth
(live-loop :bass
  (play! :midi/bass (root)  :dur 1/8 :vel 100) (sleep! 1/4)
  (play! :midi/bass (fifth) :dur 1/8 :vel 80)  (sleep! 1/4)
  (play! :midi/bass (root)  :dur 1/8 :vel 90)  (sleep! 1/4)
  (play! :midi/bass (root)  :dur 1/8 :vel 70)  (sleep! 1/4))

;; Fully generalized rhythmic voice: drives MIDI CC, not notes
(param-loop :tremolo-depth
  :target {:midi/cc 92 :channel 1}
  :rhythm (euclid 5 16)
  :values (fn [step beat] (* 64 (Math/sin (* Math/PI step 0.3))))
  :dur 4)
```

The key abstraction: **a rhythmic voice is a `param-loop` or `live-loop` with a period,
a rhythm, and a value source**. Notes are one possible value type; CC values, pitch
bend, program change, OSC parameter addresses, and CLAP automation events are others.

### 22.7 The Rhythmic Voice Abstraction

All of cljseq's time-domain control descends from a single abstract concept:

```clojure
;; A rhythmic voice — the unified abstraction
{:name         keyword
 :period       beats          ; total cycle length
 :rhythm       rhythm-spec    ; when to fire (Euclidean, timeline, continuous)
 :value-source value-fn-or-seq ; what to send
 :target       path-or-cc     ; where to send it
 :interp       :step | :linear | :exp  ; interpolation between firings
 :phase        0.0–1.0        ; phase offset relative to beat 1
 :quantize?    true           ; snap launches to beat boundary
}
```

`live-loop` is sugar for a rhythmic voice where `:target` is a MIDI output and
`:value-source` is a note-playing function. `param-loop` is sugar for a rhythmic
voice where the value is a scalar parameter. Both are backed by the same scheduler.

This abstraction connects directly to the control tree (§16): any tree path is a
valid `:target` for a rhythmic voice, and the watch mechanism fires downstream
observers (including MIDI CC sends, OSC messages, and CLAP parameter automation)
when a value changes.

### 22.8 Non-Western Rhythm Integration

The iqa'at (§21.2.4) and timeline patterns (§21.4.1) are first-class rhythm specs
in the `param-loop` / `live-loop` interfaces:

```clojure
;; Afro-Cuban filter animation: clave-locked filter sweep
(param-loop :clave-filter
  :target "/cljseq/synths/lead/vcf/cutoff"
  :rhythm (rhythm/timeline :son-clave)
  :values [2000 4000 1000 6000 800 3200 500 8000]
  :dur 2)

;; Gamelan colotomic layer: kenong (every 4 beats)
(live-loop :kenong
  (play! :synth/kenong (current-balungan-tone) :dur 1/2)
  (sleep! 4))

;; Arabic iqa maqsum driving CC (8 pulses per 2 beats)
(param-loop :oud-volume
  :target {:midi/cc 7 :channel 2}
  :rhythm (rhythm/iqa :maqsum)
  :values [100 80 90 70 85 75 95 65]
  :dur 2)
```

### 22.9 Sample-Accurate Parameter Automation (CLAP)

For CLAP plugin targets, parameter changes injected via `param-loop` carry sample-
accurate timing (§19.4). Each value change from the rhythmic voice is converted to a
`clap_event_param_value_t` with the correct `sample_offset` within the audio buffer:

```cpp
// In AudioEngine::process() for ClapTarget
for (auto& change : param_changes_this_buffer) {
    clap_event_param_value_t ev{};
    ev.header.size       = sizeof(ev);
    ev.header.type       = CLAP_EVENT_PARAM_VALUE;
    ev.header.time       = change.sample_offset;  // within this buffer
    ev.param_id          = change.param_id;
    ev.value             = change.value;
    in_events->try_push(in_events, &ev.header);
}
```

This means a `param-loop` animating Surge XT's filter cutoff operates at the same
timing precision as note events — sub-sample accuracy at 48kHz.

### 22.10 Design Questions Raised

See `doc/open-design-questions.md` Q29–Q33.

---

## 23. Stochastic Generative Primitives (Marbles-Inspired)

### 23.1 Overview

Mutable Instruments' Marbles is a Eurorack module that distills decades of
generative-music thinking into a small set of orthogonal controls. Its design
principles are more transferable than its surface features. The key insight is not
"random CV generator" but rather: **every generative decision is a point in a
continuous probability space, and that space can be navigated smoothly in real time**.

cljseq can absorb several of Marbles' conceptual mechanisms as first-class primitives:

1. **Stochastic rhythm generation** — decoupled from pitch, parameterized by
   mode, bias, and jitter
2. **Distribution-shaped value sequences** — spread, bias, and progressive
   quantization as independent dimensions
3. **Weighted scale degrees** — probability-weighted notes for musical randomness
4. **DEJA VU / ring-buffer memory** — continuous interpolation between pure random
   and looping
5. **Correlated multi-channel generation** — multiple related outputs sharing a
   generative context

### 23.2 The Two-Section Model

Marbles separates the **when** (T section — timing/rhythm) from the **what** (X
section — values/pitch). These are independent generative processes that happen to
be synchronized. This maps directly onto a distinction cljseq already makes:

| Marbles | cljseq equivalent |
|---|---|
| T section (gate/rhythm) | `stochastic-rhythm` — produces a stream of beat offsets |
| X section (CV/pitch) | `stochastic-sequence` — produces a stream of values |
| T clocks X | rhythm stream drives value stream via `clocked-by` |
| Y output (slow LFO) | `param-loop` with large `:period` and `:rhythm :continuous` |
| External clock + jitter | Link tempo + `with-jitter` |

The critical observation: in cljseq, a `live-loop` already implicitly couples rhythm
and pitch — the `sleep!` calls define timing and the `play!` calls define values.
Marbles shows the value of **explicitly decoupling** them so each can be independently
randomized, looped, and parameterized.

### 23.3 Stochastic Rhythm Generation

The T section produces three related but not identical rhythm streams using one of
several modes. Each mode is a different algorithm for partitioning clock pulses across
multiple output channels.

```clojure
;; stochastic-rhythm returns a lazy seq of {:beat offset :channel k}
;; events, ready to be consumed by a live-loop

(stochastic-rhythm
  :mode    :complementary-bernoulli  ; or :independent-bernoulli :divider :drums
  :rate    1                         ; beats per clock pulse
  :bias    0.5                       ; probability split between channels
  :jitter  0.0                       ; ±jitter fraction of beat duration
  :channels 3)                       ; number of output channels
```

**Modes (directly from Marbles):**

| Mode | Algorithm | cljseq behavior |
|---|---|---|
| `:complementary-bernoulli` | Each pulse: coin toss → ch1 OR ch3 fires (mutually exclusive) | Two channels never fire simultaneously |
| `:independent-bernoulli` | Each pulse: independent coin toss for each channel | Channels can fire together or both silent |
| `:three-states` | Each pulse: random choice among ch1, ch3, or silence | Weighted by `:bias` |
| `:divider` | Deterministic division/multiplication ratios | No randomness; `:bias` selects ratio |
| `:drums` | Pattern-table lookup (from Grids algorithm) | Kick/snare/hat grid with `fill` parameter |

**Jitter** perturbs the scheduled beat offset of each generated event:

```clojure
;; With jitter 0.1: each beat can arrive ±10% of one beat duration early or late
(stochastic-rhythm :jitter 0.1 ...)
```

In cljseq, jitter is implemented as a perturbation on the `sleep!` duration:
`(sleep! (* beat-dur (+ 1.0 (* jitter (- (rand) 0.5) 2))))`. Because virtual-time
advances by the *intended* duration and the IPC event is sent at the *perturbed*
wall-clock time, jitter affects audible timing without corrupting the beat counter.

**Usage in a live-loop:**

```clojure
(def rhythm-stream
  (stochastic-rhythm :mode :complementary-bernoulli :bias 0.6 :jitter 0.05))

(live-loop :marbles-kick
  (let [{:keys [channel]} (next! rhythm-stream)]
    (when (= channel 0) (play! :midi/kick 36 :vel 100))
    (when (= channel 1) (play! :midi/snare 38 :vel 80)))
  (sleep! 1/4))
```

### 23.4 Distribution-Shaped Value Sequences

The X section's key innovation is treating the **statistical distribution** as a
first-class parameter rather than a mode selection. Four distribution shapes form a
continuum controlled by a single `spread` value in [0, 1]:

| spread | Distribution | Character |
|---|---|---|
| 0.0 | Point mass | Static: always returns the same value (set by `bias`) |
| 0.25 | Narrow Gaussian | Concentrated around `bias`; small variation |
| 0.5 | Gaussian | Natural bell curve around `bias` |
| 0.75 | Uniform | Flat distribution; any value equally likely |
| 1.0 | Discrete / quantized | Jumps directly between scale degrees |

```clojure
(stochastic-sequence
  :spread  0.5     ; 0=constant, 0.5=gaussian, 1.0=uniform/discrete
  :bias    0.5     ; center of distribution in [0,1], mapped to output range
  :steps   0.5     ; 0=slewed/smooth, 0.5=raw, 1.0=fully quantized to scale
  :scale   (weighted-scale :major)
  :range   [48 72] ; MIDI note range [lo hi]
  :lo      48
  :hi      72)
;; → a lazy seq of MIDI note numbers
```

**The `bias` parameter** shifts the attractor of the distribution. At `bias=0.5`, the
distribution is centered in the output range. At `bias=0.2`, values cluster toward
the low end; at `bias=0.8`, toward the high end. This enables musical behaviors like
"stay mostly in the lower register" or "favor high notes":

```clojure
;; A melody that mostly dwells in mid-register, occasionally reaching high
(stochastic-sequence :spread 0.4 :bias 0.55 :range [48 84])

;; A sparse, high-register melodic line
(stochastic-sequence :spread 0.3 :bias 0.8 :steps 0.8 :scale (weighted-scale :major))
```

**The `steps` parameter** controls a three-region continuum:

- `steps` < 0.5: apply *slewing* (portamento) to the output — smoothly interpolate
  between target values. The lower the value, the slower the slew.
- `steps` = 0.5: raw output (no quantization, no slewing)
- `steps` > 0.5: progressively apply scale quantization with probability weighting.
  As `steps` → 1.0, lower-weight scale degrees are eliminated; only root and fifth
  survive near 1.0; only root at exactly 1.0.

### 23.5 Weighted Scale Degrees

Standard scale quantization is binary: a note is either in the scale or it isn't.
Marbles replaces this with **probability weighting**: each scale degree has a salience
value [0, 1]. High-salience notes (root, fifth) are always accessible; low-salience
notes (leading tone, chromatic neighbor) fade out as `steps` increases.

```clojure
;; Weighted scale: each degree has a probability weight
(weighted-scale :major
  {:tonic   1.0    ; always present
   :second  0.4
   :third   0.8    ; emphasized (common melodic tone)
   :fourth  0.5
   :fifth   0.9    ; emphasized (stable tone)
   :sixth   0.4
   :seventh 0.2})  ; first to disappear as steps → 1.0

;; Uniform weights: all degrees equally likely
(weighted-scale :major)  ; default: uniform weights

;; Progressive quantization behavior:
;; steps=0.6: seventh fades (weight → 0)
;; steps=0.7: second, sixth fade
;; steps=0.8: fourth fades
;; steps=0.9: third fades
;; steps=1.0: only tonic and fifth remain
;; steps>1.0: only tonic
```

**Scale learning from corpus** — analogous to Marbles' custom scale recorder:

```clojure
;; Learn scale weights from a note sequence (e.g., a Bach soprano line)
(def soprano (m21/soprano @(m21/parse-corpus "bach/bwv66.6")))
(def learned (learn-scale-weights soprano :key {:key/tonic :G :key/mode :major}))
;; learned is a weighted-scale with weights derived from note frequency histogram
;; Most common notes in the Bach soprano → highest weights

;; Learn from a live session (real-time capture):
(learn-scale-weights! :live-scale :from-midi-input :channel 1 :duration 30)
;; Records 30 seconds of input; builds a weighted scale from played notes
```

This mirrors Marbles' scale recording button, but generalized: any note sequence can
be the "teacher", including corpus pieces, live MIDI input, or programmatic note
sequences.

### 23.6 DEJA VU: Probabilistic Loop Memory

DEJA VU is the most musically powerful mechanism in Marbles. It maintains a ring
buffer of the last N generated values and, on each new generation request, either
samples fresh random data or replays a buffered value based on a probability
parameter. The result is a smooth continuum:

```
deja-vu=0.0 → pure stochastic: each value is independently random
deja-vu=0.3 → slight tendency to repeat recent patterns
deja-vu=0.7 → strong tendency; clearly recognizable loops forming
deja-vu=1.0 → fully locked loop; exact repetition of the ring buffer
deja-vu>1.0 → locked loop with occasional shuffle (variation within loop)
```

In live performance this is enormously useful: you let a good pattern "crystallize"
by slowly turning `deja-vu` toward 1.0, then at the moment you want change, sweep it
back toward 0.0 to dissolve the pattern back into randomness.

**Implementation in cljseq:**

```clojure
(defn make-deja-vu-source
  "Wraps a value-generating function with DEJA VU ring-buffer memory.
  loop-len: number of steps in the memory buffer (1–32)
  deja-vu:  atom holding current deja-vu parameter [0.0, 1.1]"
  [gen-fn loop-len deja-vu-atom]
  (let [buf (ring-buffer loop-len)
        pos (atom 0)]
    (fn []
      (let [dv @deja-vu-atom
            replay? (< (rand) dv)
            val (if (and replay? (seq (contents buf)))
                  (nth (contents buf) (mod @pos loop-len))
                  (gen-fn))]
        (when (not replay?) (conj! buf val))
        (swap! pos inc)
        val))))
```

**DEJA VU as a live parameter** on stochastic sequences:

```clojure
(def dv (atom 0.0))

(def pitch-source
  (stochastic-sequence
    :spread 0.5 :bias 0.5 :steps 0.7
    :scale (weighted-scale :major)
    :deja-vu dv          ; live parameter: changes take effect immediately
    :loop-len 8))

(live-loop :melody
  (play! :midi/synth (next! pitch-source))
  (sleep! 1/4))

;; During performance: gradually crystallize
(ctrl/set! "/cljseq/loops/melody/deja-vu" 0.85)
;; Later: dissolve back to randomness
(ctrl/set! "/cljseq/loops/melody/deja-vu" 0.1)
```

**Independent DEJA VU for rhythm and pitch** (matching Marbles' T/X independence):

```clojure
(def rhythm-dv (atom 0.0))
(def pitch-dv  (atom 0.0))

;; Lock the rhythm pattern while keeping pitch fresh:
(reset! rhythm-dv 1.0)
(reset! pitch-dv  0.0)

;; Lock the pitch sequence while keeping rhythm random:
(reset! rhythm-dv 0.0)
(reset! pitch-dv  1.0)
```

This is a core performance tool: you can independently "freeze" the rhythmic pattern
while the pitches evolve, or freeze the pitch sequence while the rhythm shuffles.

### 23.7 Correlated Multi-Channel Generation

Marbles generates three T outputs and three X outputs that share a generative context
but are not identical. This "correlated family" pattern is musically rich: the outputs
share statistical character (same spread, same scale) while diverging in specific
values.

```clojure
;; A correlated family of three sequences sharing the same statistical context
(def trio
  (correlated-sequences 3
    :spread 0.5 :bias 0.5 :steps 0.7
    :scale  (weighted-scale :major)
    :correlation 0.6  ; 0=independent, 1=identical
    :deja-vu (atom 0.0)
    :loop-len 8))

;; Drive three MIDI channels with related but distinct melodic material
(live-loop :voice-1
  (play! :midi/synth-1 (next! (:ch0 trio))) (sleep! 1/4))
(live-loop :voice-2
  (play! :midi/synth-2 (next! (:ch1 trio))) (sleep! 1/4))
(live-loop :voice-3
  (play! :midi/synth-3 (next! (:ch2 trio))) (sleep! 1/4))
```

The `:correlation` parameter controls how much the channels share their random draws.
At 0.0, each channel draws independently. At 1.0, all channels generate the same value
(perfect correlation). At intermediate values, a shared "parent" random draw is mixed
with a per-channel perturbation:

```
channel_value = lerp(parent_draw, per_channel_draw, 1.0 - correlation)
```

This produces musically coherent families: at high correlation, the voices move in
parallel (like parallel voicing); at low correlation, they behave independently
(polyphonic counterpoint).

### 23.8 The `defstochastic` Macro

Bringing the complete Marbles model together, cljseq provides a `defstochastic` macro that
creates a fully parametrized generative context:

```clojure
(defstochastic my-gen
  ;; T section: rhythm
  :t-mode     :complementary-bernoulli
  :t-bias     0.5
  :t-jitter   0.05
  :t-deja-vu  (atom 0.0)
  :t-loop-len 8
  ;; X section: pitch/value
  :x-spread   0.5
  :x-bias     0.5
  :x-steps    0.7
  :x-scale    (weighted-scale :major)
  :x-deja-vu  (atom 0.0)
  :x-loop-len 8
  ;; Number of correlated outputs per section
  :channels   3)

;; Access rhythm events and pitch values:
(next-t! my-gen 0)   ; next gate event for channel 0
(next-t! my-gen 1)   ; channel 1
(next-x! my-gen 0)   ; next pitch value for channel 0
(next-x! my-gen 1)   ; channel 1

;; Shortcut: drive three live-loops from a single stochastic context
(stochastic-loops my-gen
  :targets [:midi/bass :midi/lead :midi/pad]
  :durs    [1/4 1/4 1/2])
```

All T and X parameters are atoms, so they are live-update-capable via `ctrl/bind!`
or `ctrl/set!`. The `defstochastic` macro registers the context in the control tree at
`/cljseq/stochastic/<name>/`, making all parameters addressable from MIDI CC, OSC,
HTTP, and MCP.

### 23.9 Integration with the Existing Design

**With `with-harmony` (§5)**:

`stochastic-sequence` respects the current harmonic context when a weighted scale
is active. The `:scale` parameter is a *default* fallback; if `*harmony*` is bound,
`with-harmony`'s chord/scale takes precedence. This means Marbles-style generation
naturally "tracks" chord changes in a progression without requiring manual
scale-switching.

**With `param-loop` (§22)**:

The X section is just a value source. Any `stochastic-sequence` can feed a
`param-loop` target — pitches, CC values, OSC parameters, CLAP automation:

```clojure
(def filter-vals
  (stochastic-sequence :spread 0.6 :bias 0.5 :steps 0.3
                       :range [0.0 1.0] :deja-vu (atom 0.5)))

(param-loop :filter-mod
  :target "/cljseq/synths/lead/vcf/cutoff"
  :rhythm (stochastic-rhythm :mode :independent-bernoulli :bias 0.6)
  :values filter-vals
  :dur 4)
```

**With DEJA VU and `live-loop` redefinition**:

DEJA VU solves a problem with live-loop redefinition: when you redefine a loop, you
lose the sequence state. With a DEJA VU source that is `defonce`d, the ring buffer
persists across redefinitions — the loop continues from where it was, and the degree
of historical memory is preserved:

```clojure
(defonce melody-gen
  (stochastic-sequence :spread 0.4 :steps 0.7 :scale (weighted-scale :major)
                       :deja-vu (atom 0.7) :loop-len 8))

;; Redefining the loop doesn't lose the buffer — melody-gen persists
(live-loop :melody
  (play! :midi/synth (next! melody-gen) :dur 3/4)
  (sleep! 1/4))
```

**With Music21 corpus (§20)**:

A corpus-extracted soprano line can seed the DEJA VU ring buffer, giving the
generative process a "genetic material" derived from a specific historical piece:

```clojure
(def bach-soprano
  @(m21/soprano @(m21/parse-corpus "bach/bwv66.6")))

(def melody-gen
  (stochastic-sequence-from-seed bach-soprano
    :spread 0.3      ; mostly follows the seed, small deviations
    :deja-vu (atom 0.8)
    :scale (learn-scale-weights bach-soprano)))
```

Here the seed pitches initialize the ring buffer; `deja-vu=0.8` means 80% of the
time the generator replays from the seed, 20% of the time it generates fresh values
within the same weighted scale.

### 23.10 Algorithmic Summary

| Marbles mechanism | cljseq primitive | Key parameters |
|---|---|---|
| T section (complementary Bernoulli) | `stochastic-rhythm :mode :complementary-bernoulli` | `:bias :jitter` |
| T section (independent Bernoulli) | `stochastic-rhythm :mode :independent-bernoulli` | `:bias :jitter` |
| T section (divider) | `stochastic-rhythm :mode :divider` | `:bias` (selects ratio) |
| T section (drums) | `stochastic-rhythm :mode :drums` | `:bias` (fill density) |
| X spread + bias | `stochastic-sequence :spread S :bias B` | 0=constant, 0.5=gaussian, 1=uniform |
| X steps (quantization) | `:steps S :scale (weighted-scale ...)` | 0=slewed, 0.5=raw, 1=root-only |
| X steps (slewing) | `:steps S` (S<0.5) | portamento coefficient |
| DEJA VU | `:deja-vu atom :loop-len N` | 0=random, 1=locked loop |
| Correlated channels | `correlated-sequences N :correlation C` | 0=independent, 1=identical |
| Custom scale learning | `learn-scale-weights notes` | note frequency histogram |
| Y output | `param-loop :rhythm :continuous :period (* 16 x2-period)` | slow LFO |
| Full module | `(defstochastic name & opts)` | all parameters |

### 23.11 Design Questions Raised

See `doc/open-design-questions.md` Q34–Q38.

---

## 24. Fractal Sequence Architecture (Bloom-Inspired)

### 24.1 Overview

Qu-Bit Bloom v2 is a three-channel, 64-step "fractal sequencer" organized around a
central metaphor: plant a seed sequence (the trunk), and let it grow into a tree of
algorithmically derived variations (the branches). While Marbles (§23) generates
sequences stochastically from distributions, Bloom takes an explicitly-programmed
sequence as its starting point and derives variation through deterministic
**transformations** of that material. The generative engine is algebraic rather than
probabilistic — the same trunk always produces the same branches; variability comes
from navigating different **paths** through the branch tree, and from **mutation**
applied to subsequent branches.

Bloom introduces several concepts that cljseq does not yet have:

1. **Trunk + branch tree**: a sequence as a source of derived variations
2. **Sequence transformation algebra**: reverse, inverse, transpose, mutate, randomize
3. **Path navigation**: a traversal strategy through a tree of related sequences
4. **Musical ornamentation**: scale-aware per-step flourishes (anticipation, mordent,
   run, trill) as algorithmic step expansion
5. **Per-step Mod output**: a parallel modulation voice embedded in the sequence itself
6. **Playback order modes**: Converge, Diverge, Pendulum, Page Jump, and others
7. **Sequence resize**: time-domain scaling with three algorithms (Spread, Stretch, Clone)

### 24.2 Trunk and Branch Tree

A **trunk** is a base sequence: a vector of up to 64 step maps, each containing pitch,
gate, slew, ratchet, and mod data. **Branches** are algorithmically derived variants of
the trunk (or the most recent branch). Bloom generates up to 7 branches, giving a total
of up to 8 sequences (1 trunk + 7 branches) — a 64-step trunk at max branches yields
a 512-step generative sequence before any repetition.

Each branch is produced by one of five transformations applied to the preceding
sequence:

| Transformation | Algorithm |
|---|---|
| **Reverse** | Play the previous branch in reverse order |
| **Inverse** | Invert each pitch: `new_pitch = root + (root - old_pitch)` (mirror around tonic) |
| **Transpose** | Shift all pitches up by one octave (within the scale) |
| **Mutate** | Apply step-level pitch/gate mutation at the current Mutate probability |
| **Randomize** | Replace with a fully random sequence in the current scale |

The branch type is determined by the module's internal algorithm; in cljseq, the
programmer selects or sequences the transformations:

```clojure
;; Define a trunk sequence
(def trunk
  [{:pitch/midi 60 :dur/beats 1/4 :gate-len 0.5}
   {:pitch/midi 64 :dur/beats 1/4 :gate-len 0.5}
   {:pitch/midi 67 :dur/beats 1/2 :gate-len 0.8}
   {:pitch/midi 65 :dur/beats 1/4 :gate-len 0.5}])

;; Generate branches explicitly
(def branches
  (bloom/build-tree trunk
    :transforms [:reverse :inverse :transpose :mutate :randomize :mutate :reverse]
    :scale      (scale :C :major)
    :mutate-prob 0.25))

;; branches is a vector of 8 sequences: [trunk b1 b2 b3 b4 b5 b6 b7]
```

**Branch regeneration**: in Bloom, any edit to the trunk regenerates all branches. In
cljseq, `bloom/build-tree` is a pure function — call it whenever the trunk changes.
The control tree (§16) holds both the trunk and the regeneration trigger:

```clojure
(defnode "/cljseq/bloom/melody/trunk" :data)
(watch! "/cljseq/bloom/melody/trunk"
  (fn [_ _ _ new-trunk]
    (reset! branches-atom (bloom/build-tree new-trunk opts))))
```

### 24.3 Path Navigation

The **path** determines which branches play and in what order. At maximum branches
(7), Bloom offers 128 distinct paths — each path is a specific traversal of the
branch tree.

In cljseq, a path is a sequence of branch indices:

```clojure
;; Path = sequence of indices into the branches vector
;; Bloom's path 0: [0 1 2 3 4 5 6 7] (trunk then all branches forward)
;; Bloom's path 1: [0 7 6 5 4 3 2 1] (trunk then all branches reverse)
;; etc.

(bloom/path branches :path 3)
;; → lazy seq that plays branches in the order defined by path 3

;; Custom path:
(bloom/path branches :indices [0 2 0 4 0 6])
;; → trunk, branch2, trunk, branch4, trunk, branch6
```

Live path switching: the current path is a live atom. Changing it mid-performance
takes effect at the next branch boundary (when one complete sequence finishes):

```clojure
(defonce current-path (atom 0))

(live-loop :bloom-melody
  (let [seqs (bloom/path @branches-atom :path @current-path)]
    (doseq [step seqs]
      (play! :midi/synth (:pitch/midi step) :dur (:dur/beats step))
      (sleep! (:dur/beats step)))))
```

### 24.4 Sequence Transformation Algebra

Each branch transformation is a pure function on a sequence. These functions are
independently useful outside the branch-tree context:

```clojure
;; Namespace: cljseq.bloom

(bloom/reverse-seq seq)
;; → reverse the step order

(bloom/inverse-seq seq scale root-midi)
;; → mirror each pitch around the root: new = root + (root - old)
;; pitch inversion — a serial music operation, also Marbles' "invert" branch

(bloom/transpose-seq seq semitones scale)
;; → transpose all pitches by N semitones, keeping in scale (diatonic transposition)

(bloom/mutate-seq seq probability scale & {:keys [note-prob gate-prob]})
;; → randomly modify pitches (note-prob) and gate state/length (gate-prob)
;; Two zones: note-only mutations in [0, 0.5); gate mutations enter in [0.5, 1.0]

(bloom/randomize-seq len scale & opts)
;; → fresh random sequence in the given scale

(bloom/resize-seq seq target-len algorithm)
;; algorithm: :spread (empty steps between), :stretch (repeat each step),
;;            :clone (repeat whole sequence)

(bloom/rotate-seq seq offset)
;; → shift the start point by offset steps

(bloom/reverse-order seq)       ; play in reverse
(bloom/pendulum-order seq)      ; forward then reverse
(bloom/random-order seq)        ; shuffle each pass
(bloom/converge-order seq)      ; outer → inner
(bloom/diverge-order seq)       ; inner → outer
(bloom/page-jump-order seq page-len)  ; jump to random page when a page completes
```

Transformations compose naturally:

```clojure
;; A 4-bar phrase: original + retrograde-inversion (serial technique)
(def phrase
  (concat trunk
          (-> trunk bloom/reverse-seq (bloom/inverse-seq (scale :C :major) 60))))
```

This makes Bloom's branch logic accessible as a toolkit for serial and post-tonal
composition workflows, complementing Music21's `tone-row` operations (§20).

### 24.5 Musical Ornamentation

Bloom's Performance mode ornamentation is the module's most musically sophisticated
feature. Ornaments are scale-aware **step expansion functions**: they replace a single
step with 1–8 sub-steps that elaborate the original note according to a classical
musical gesture.

**Two-step ornaments** (current step → 2 output events):

| Ornament | Description | cljseq |
|---|---|---|
| Anticipation | Play next step's pitch one eighth note early | `(ornament :anticipation step context)` |
| Suspension | Hold previous pitch for 1/8 note, then current | `(ornament :suspension step context)` |
| Syncopation | Rest for 1/8 note, then current pitch | `(ornament :syncopation step context)` |
| Octave Up | Current note + octave above 1/8 later | `(ornament :octave-up step context)` |
| Fifth Up | Current note + fifth above 1/8 later | `(ornament :fifth-up step context)` |
| Half Turn Toward | 1/8 note one degree beyond the next note | `(ornament :half-turn-toward step context)` |
| Half Turn Away | 1/8 note one degree away from direction of next | `(ornament :half-turn-away step context)` |

**Four-step ornaments** (current step → 4 output events):

| Ornament | Description | cljseq |
|---|---|---|
| Run Toward | 4 scale steps toward the next note | `(ornament :run-toward step context)` |
| Run Away | 4 scale steps away from the next note | `(ornament :run-away step context)` |
| Turn | current → up → down → current | `(ornament :turn step context)` |
| Arp Toward | 4-note arpeggio toward next step | `(ornament :arp-toward step context)` |
| Arp Away | 4-note arpeggio away from next step | `(ornament :arp-away step context)` |
| Mordent Up | rapid alternation: current → above → current | `(ornament :mordent-up step context)` |
| Mordent Down | rapid alternation: current → below → current | `(ornament :mordent-down step context)` |

**Trill** (max ornamentation → 8 events): rapid alternation between current note and
the note above, for the full duration of the step.

All ornaments are scale-aware: "above/below/toward/away" refer to scale degrees, not
chromatic semitones. The `context` map carries `{:scale S :next-step N :prev-step P}`.

```clojure
;; Apply ornaments probabilistically to a sequence
(bloom/ornament-seq seq
  :scale    (scale :C :major)
  :prob     0.3        ; 30% of steps get an ornament
  :types    [:anticipation :mordent-up :run-toward :turn]
  :max-steps :four)    ; only use two-step and four-step ornaments

;; Or per-step:
(bloom/ornament step :mordent-up {:scale (scale :C :major) :next-step next-step})
;; → [{:pitch/midi 60 :dur 1/16} {:pitch/midi 62 :dur 1/16} {:pitch/midi 60 :dur 1/8}]
```

**Connection to §22 arpeggiation**: The Arp ornaments (`arp-toward`, `arp-away`) are
specialized arpeggio patterns that connect the current note to the next, using the
scale as the pitch pool. They are more context-aware than the static arpeggio patterns
in §22.5 because they reference the next step as a destination.

### 24.6 Per-Step Mod Output

Bloom's Mod output is a per-step modulation voice that runs in parallel with the
melody. Each step carries its own Mod value, and the Mod output follows the same
rhythm as the note sequence. This is more tightly integrated than a separate
`param-loop` — the modulation is embedded in the sequence data itself.

Four Mod modes:

| Mode | Per-step value | Output |
|---|---|---|
| **Shapes** | LFO shape selection (8 shapes) + subdivision | LFO at configurable rate within step |
| **Velocity** | Fixed CV per step (0–5V) | Velocity-style step articulation |
| **Envelope** | Attack/Decay morphing | ADSR-style envelope triggered per step |
| **Smooth** | CV value with automatic inter-step portamento | Smooth stepped automation |

In cljseq, mod data is added to the step map:

```clojure
{:pitch/midi  60
 :dur/beats   1/4
 :gate-len    0.5
 :slew        0
 :ratchet     1
 :mod/mode    :velocity    ; :velocity :shape :envelope :smooth
 :mod/value   0.8          ; 0.0–1.0, mapped to 0V–5V in sidecar
 :mod/shape   :sine        ; for :shape mode
 :mod/rate    2}            ; LFO rate multiplier for :shape mode
```

The `:mod/*` keys drive a dedicated sidecar output or MIDI CC alongside the note:

```clojure
(defdevice :bloom-channel-1
  :note-target  {:midi/channel 1}
  :mod-target   {:midi/cc 1 :channel 1})   ; CC 1 (mod wheel) carries Bloom's Mod output

(play-bloom-step! step :device :bloom-channel-1)
;; → schedules note-on at step's time_ns + schedules CC 1 at same time_ns
```

This extends the `play!` primitive: `play-bloom-step!` is a sequence-step player that
understands all Bloom step fields and dispatches note, gate, ratchet, slew, and mod
events as a bundle.

### 24.7 Playback Order Modes

Bloom's 8 playback order modes are step-index traversal strategies. These generalize
the basic forward/backward traversal to musical patterns that create interesting
phrase shapes:

```clojure
(defmulti step-indices (fn [order len opts] order))

(defmethod step-indices :forward  [_ len _] (range len))
(defmethod step-indices :reverse  [_ len _] (range (dec len) -1 -1))
(defmethod step-indices :pendulum [_ len _]
  (concat (range len) (range (- len 2) 0 -1)))  ; 0 1 2 3 2 1 0 1 2 ...
(defmethod step-indices :random   [_ len _]
  (shuffle (range len)))    ; new shuffle each pass
(defmethod step-indices :converge [_ len _]
  ;; outer-in: 0, len-1, 1, len-2, 2, len-3, ...
  (let [ixs (range len)] (interleave ixs (reverse ixs))))
(defmethod step-indices :diverge  [_ len _]
  ;; inner-out: center, center±1, center±2, ...
  (let [c (quot len 2)]
    (mapcat (fn [d] [(- c d) (+ c d)]) (range (inc c)))))
(defmethod step-indices :converge-diverge [_ len _]
  ;; alternates between converge and diverge passes
  ...)
(defmethod step-indices :page-jump [_ len {:keys [page-len]}]
  ;; play a page, then jump to random page start
  ...)
```

These traversal modes compose with any sequence, including Bloom branch trees and
plain `live-loop` steps. They are especially interesting when applied to the branch
tree: a Converge path through 7 branches creates a phrase that gravitates toward
the innermost (most transformed) branch, then returns.

### 24.8 The `deffractal` Macro

```clojure
(deffractal my-fractal
  ;; Trunk sequence (hand-programmed or generated)
  :trunk    [{:pitch/midi 60 :dur/beats 1/4}
             {:pitch/midi 64 :dur/beats 1/4}
             {:pitch/midi 67 :dur/beats 1/2}
             {:pitch/midi 65 :dur/beats 1/4}]
  ;; Branch generation
  :branches 4                ; generate 4 branches (total = 5 sequences)
  :transforms [:reverse :inverse :transpose :mutate]
  :scale    (scale :C :major)
  :mutate-prob 0.25
  ;; Path navigation
  :path     0                ; or an atom for live path switching
  ;; Playback order per branch
  :order    :forward         ; or :pendulum :converge etc.
  ;; Ornamentation (Performance mode equivalent)
  :ornament-prob 0.2
  :ornament-types [:mordent-up :anticipation :run-toward]
  ;; Device routing
  :note-target  :midi/synth
  :mod-target   {:midi/cc 1 :channel 1})

;; Access:
(next-step! my-fractal)       ; advance and return next step (with mod data)
(current-branch my-fractal)   ; which branch is currently playing
(fractal/path my-fractal 3)   ; live path switch at next branch boundary
(mutate! my-fractal)          ; reseed mutations on all branches
(reseed! my-fractal)          ; randomize trunk (like Bloom's Reseed button)
(unmutate! my-fractal)        ; return to last manual trunk state
```

Like `defstochastic`, the `deffractal` macro uses `defonce` internally and auto-registers all
parameters in the control tree at `/cljseq/fractal/<name>/`, making Branch count, Path,
Mutate probability, and playback Order all live-addressable.

### 24.9 Interplay with Stochastic Contexts (§23)

Bloom and Marbles represent orthogonal generative strategies that combine:

| | Marbles approach | Bloom approach |
|---|---|---|
| **Starting point** | Distribution parameters | Explicit trunk sequence |
| **Variation** | Stochastic sampling | Deterministic transformation |
| **Memory** | DEJA VU ring buffer | Branch tree (fixed until trunk changes) |
| **Control** | Spread/Bias/Steps knobs | Branch count, Path, Mutate knob |
| **Predictability** | Low (random) | Medium (deterministic transforms + small mutations) |
| **Musical character** | Organic, drift-like | Structured variation, theme-and-development |

The most interesting combinations:

```clojure
;; Bloom trunk generated from a Marbles sequence:
(def seed (take 8 (stochastic-sequence :spread 0.4 :steps 0.8 :scale (scale :C :major))))
(bloom crystallized-bloom :trunk (vec seed) :branches 3 ...)

;; Bloom trunk seeded from a Music21 corpus passage:
(def bach-motif (take 4 @(m21/soprano @(m21/parse-corpus "bach/bwv66.6"))))
(bloom corpus-bloom :trunk bach-motif :transforms [:inverse :transpose :mutate] ...)

;; Marbles X-section drives Bloom's Root (diatonic transposition):
(ctrl/bind! {:midi/cc 74} "/cljseq/bloom/my-bloom/root"
  :transform #(int (* % 7)))  ; 7 diatonic steps per octave
```

### 24.10 Sequence as Data: The Unified Step Map

Bloom's comprehensive per-step data model (pitch, gate length, slew, ratchet count,
mod value/mode) motivates a **unified step map** for cljseq that all sequencing
primitives understand:

```clojure
;; The canonical cljseq step map
{:pitch/midi    60        ; MIDI note number (absolute)
 :pitch/cents   0         ; microtonal offset in cents
 :dur/beats     1/4       ; step duration in beats
 :gate/on?      true      ; step enabled (false = rest)
 :gate/len      0.5       ; gate length as fraction of step duration
 :gate/ratchet  1         ; number of equally-spaced gate repeats within step
 :note/slew     0         ; portamento time (0 = none, 1 = max)
 :mod/mode      :velocity ; :velocity :shape :envelope :smooth
 :mod/value     0.8       ; 0.0–1.0
 :mod/shape     :sine     ; for :shape mode only
 :mod/rate      1}        ; LFO rate multiplier for :shape mode only
```

This is the data vocabulary that `bloom`, `defstochastic`, `live-loop`, `param-loop`,
`play-arpeggio!`, and `play-timeline!` all produce and consume. The step map is
a plain Clojure map — immutable, serializable, printable, comparable. Musical
operations on sequences are pure functions from `[step-map]` to `[step-map]`.

### 24.11 Design Questions Raised

See `doc/open-design-questions.md` Q39–Q43.

---

## 25. Configuration Registry

All tuneable system parameters with their defaults. Each entry records the default
value, valid range or type, the subsystem that owns it, and the design question or
sprint where the value was chosen. Parameters are configurable at startup via the
system config map unless noted otherwise.

A future configurability sprint will establish the EDN config file format, runtime
override mechanisms (control tree, environment variables, JVM properties), and
validation rules. Until then, these values are compile-time defaults.

| Parameter | Default | Type / Range | Subsystem | Source |
|---|---|---|---|---|
| `sched-ahead-time` (`:midi-hardware`) | 100ms | `Long` ms, > 0 | Scheduler / IPC | Q5, §3.1 |
| `sched-ahead-time` (`:sc-osc`) | 30ms | `Long` ms, > 0 | Scheduler / IPC | Q5, §3.1 |
| `sched-ahead-time` (`:vcv-rack`) | 50ms | `Long` ms, > 0 | Scheduler / IPC | Q5, §3.1 |
| `sched-ahead-time` (`:daw-midi`) | 50ms | `Long` ms, > 0 | Scheduler / IPC | Q5, §3.1 |
| `sched-ahead-time` (`:link`) | 0ms | `Long` ms, ≥ 0 | Scheduler / Link | Q5, §15 |
| `sync-window` | `sched-ahead-time` + 1 beat | Derived rational | `sync!` / EventHistory | Q3 |
| `lfo-update-rate` | 100 Hz (10ms) | `Long` Hz, 1–1000 | `param-loop` continuous | Q30 |
| `undo-stack-depth` | 50 | `Long`, 1–∞ | Control tree / undo | Q47 |
| `link-quantum` | 4 beats | `Long` beats, > 0 | Ableton Link | §15.4 |
| `osc-control-port` | 57121 | `Long`, 1024–65535 | OSC control-plane server | Q12, §17.1 |
| `osc-data-port` | 57110 | `Long`, 1024–65535 | OSC data-plane / sidecar passthrough | Q12, §17.1 |

---

## 26. Flux Sequence Architecture (Labyrinth-Inspired)

Inspired by the Moog Labyrinth Eurorack sequencer (see `doc/attribution.md`).
`defflux` is a step sequencer archetype in which a **read head** and a **write
head** operate independently on a shared circular step buffer.

### 26.1 Core Model

A `defflux` instance maintains:
- A circular step buffer: N positions, each holding a current value (MIDI note
  or parameter value) and a gate state
- A read head: advances at a configurable clock rate and direction; emits events
  to an output target on each step
- A write head: advances at its own clock rate and direction; writes values from
  a configurable source (any `ITemporalValue`) into buffer positions
- An optional CORRUPT process: applies probabilistic bit-level mutation to stored
  values at its own clock rate, with an `ITemporalValue` intensity control

The three processes (read, write, CORRUPT) are concurrent but operate on independent
atoms per step position, so they do not contend unless two heads are at the same
position simultaneously — which is musically interesting, not an error.

### 26.2 API

```clojure
(defflux :evolving
  {:steps   16
   :scale   (scale :C :dorian)       ; optional output-stage quantization
   :read    {:clock     master-clock
             :direction :forward}
   :write   {:clock     (clock-div 3 master-clock)
             :source    (lfo :rate 0.1)
             :direction :forward}
   :corrupt {:intensity (lfo :rate 0.05)  ; ITemporalValue drives corruption rate
             :clock     (clock-div 4 master-clock)}
   :output  {:target :midi/synth :channel 1}})

;; React to corruption events (BIT FLIP equivalent)
(flux/on-corrupt! :evolving
  (fn [step-idx _old-val _new-val]
    (ctrl/send! [:env/trig] 1)))

;; Freeze the read head at current position
(flux/freeze! :evolving)

;; Query current read-head position
(flux/read-pos :evolving)    ;=> 7

;; Query current write-head position
(flux/write-pos :evolving)   ;=> 3
```

### 26.3 CORRUPT Mutation Model

CORRUPT applies a probabilistic bit-flip to the integer representation of stored
step values. The probability of flipping bit N decreases with N's significance:
high-order bits (octave-level) are least likely; low-order bits (semitone-level)
are most likely. At low intensity the mutation is subtle pitch drift; at high
intensity octave jumps and register crossing occur.

### 26.4 Scale as `ITemporalValue` (Q54)

The `:scale` parameter in `defflux` (and in any output-stage quantization) accepts
either a static keyword (`:dorian`, `:major`, etc.) or an `ITemporalValue`. When an
`ITemporalValue` is provided, it is sampled on each step output and the resulting
keyword is applied as the quantization scale. This enables CV-controlled scale
morphing matching the Labyrinth's scale mode behavior.

### 26.5 Concurrency Model (Q53)

Each step position is an independent Clojure atom (`(vec (repeatedly n atom))`).
The read and write heads hold only their current position index and clock state.
Concurrent access to the same position is handled by `swap!` on the per-step atom;
no global lock is required. The CORRUPT process uses `swap!` on randomly selected
step atoms.

### 26.6 Design Questions

- **Q53**: `defflux` read/write head concurrency model — resolved: vector of atoms
- **Q54**: Scale as `ITemporalValue` in output quantization — resolved: accepted; sampled per step output

### 26.7 Complete API Reference

All public symbols are in `cljseq.flux`. Import with:

```clojure
(require '[cljseq.flux :as flux])
```

#### `defflux` — define a flux sequence

```clojure
(defflux name option-map)
```

Defines and starts a named flux sequence. Registers it in the control tree at
`/cljseq/flux/<name>/`. Returns `name`.

**Option map keys:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:steps` | integer | 16 | Number of positions in the circular step buffer |
| `:init` | value or `[value]` | 60 | Initial MIDI value written to all (or each) step |
| `:scale` | keyword or `ITemporalValue` | `nil` | Output-stage scale quantization; sampled per read step (Q54) |
| `:read` | map | `{:clock master-clock :direction :forward}` | Read head configuration (see below) |
| `:write` | map or `nil` | `nil` | Write head configuration; `nil` disables the write head |
| `:corrupt` | map or `nil` | `nil` | CORRUPT process configuration; `nil` disables CORRUPT |
| `:output` | map | `{:target :stdout}` | Output routing for read-head events |

**`:read` sub-keys:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:clock` | `ITemporalValue` | `master-clock` | Clock source driving the read head |
| `:direction` | `:forward` `:backward` `:ping-pong` `:random` | `:forward` | Read head advance direction |
| `:start` | integer | 0 | Initial read position (0-indexed) |

**`:write` sub-keys:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:clock` | `ITemporalValue` | `(clock-div 2 master-clock)` | Clock source driving the write head |
| `:source` | `ITemporalValue` or fn | required | Value generator; sampled on each write step |
| `:direction` | same as `:read` | `:forward` | Write head advance direction |
| `:start` | integer | 0 | Initial write position |
| `:transform` | fn or `nil` | `nil` | `(fn [old-val new-val] -> val)` applied before writing; enables merge strategies |

**`:corrupt` sub-keys:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:clock` | `ITemporalValue` | `(clock-div 4 master-clock)` | Clock source driving the CORRUPT process |
| `:intensity` | number or `ITemporalValue` | 0.1 | Mutation intensity [0.0–1.0]; sampled per CORRUPT tick |
| `:target` | `:all` `:read-region` `:write-region` | `:all` | Which steps are eligible for corruption |

**`:output` sub-keys:**

| Key | Type | Description |
|-----|------|-------------|
| `:target` | keyword or path | Output target (`:stdout`, `:midi/synth`, control tree path) |
| `:channel` | integer | MIDI channel (1–16); only meaningful for MIDI targets |

**Example:**

```clojure
(defflux :melody
  {:steps   16
   :init    60
   :scale   :C :dorian
   :read    {:clock     master-clock
             :direction :forward}
   :write   {:clock     (clock-div 3 master-clock)
             :source    (lfo :rate 0.1)
             :direction :forward}
   :corrupt {:intensity (lfo :rate 0.05)
             :clock     (clock-div 8 master-clock)}
   :output  {:target :midi/synth :channel 1}})
```

---

#### `flux/freeze!` / `flux/unfreeze!` — suspend/resume the read head

```clojure
(flux/freeze!   name)   ; suspends read head at current position
(flux/unfreeze! name)   ; resumes read head
```

The write head and CORRUPT process continue while frozen. Freeze captures the
current read position; `unfreeze!` resumes from that position.

---

#### `flux/freeze-write!` / `flux/unfreeze-write!` — suspend/resume the write head

```clojure
(flux/freeze-write!   name)
(flux/unfreeze-write! name)
```

Suspends the write head; the step buffer is no longer updated. Useful to lock
in an evolved sequence state.

---

#### `flux/read-pos` / `flux/write-pos` — query head positions

```clojure
(flux/read-pos  name)   ;=> integer [0, steps)
(flux/write-pos name)   ;=> integer [0, steps)
```

Returns the current 0-indexed position of the read or write head.

---

#### `flux/peek` — read buffer without advancing

```clojure
(flux/peek name)            ;=> vector of all step values
(flux/peek name step-idx)   ;=> value at step-idx
```

Non-destructive read. Does not advance any head or trigger output.

---

#### `flux/set-step!` — manual step write

```clojure
(flux/set-step! name step-idx value)
```

Writes `value` directly to position `step-idx`. This is a manual override that
bypasses the write head and its clock. Useful for seeding the buffer or
correcting corruption.

---

#### `flux/reset!` — reinitialise the step buffer

```clojure
(flux/reset! name)           ; reset to the :init value from defflux
(flux/reset! name init-val)  ; reset all steps to init-val
(flux/reset! name [v0 v1 …]) ; reset each step to the corresponding value
```

---

#### `flux/on-corrupt!` — register a corruption event listener

```clojure
(flux/on-corrupt! name callback-fn)
```

Registers `callback-fn` to be called each time the CORRUPT process mutates a
step. See §26.9 for the full callback contract.

---

#### `flux/remove-corrupt-listener!` — deregister a listener

```clojure
(flux/remove-corrupt-listener! name callback-fn)
```

Removes a previously registered `on-corrupt!` callback (by identity).

---

#### `flux/stop!` — stop and deregister

```clojure
(flux/stop! name)
```

Stops all three processes (read, write, CORRUPT) and removes the entry from the
control tree. Analogous to stopping a `deflive-loop`.

---

### 26.8 CORRUPT Mutation Algorithm

The CORRUPT process runs on its own clock (`(clock-div k master-clock)` by default).
On each tick it:

1. Samples `:intensity` (an `ITemporalValue` or constant in [0.0, 1.0])
2. Selects a target step (uniform random draw from eligible positions, per `:target` policy)
3. Applies `corrupt-value` to the current step value
4. If the value changed, writes the new value back to the step atom and fires all
   registered `on-corrupt!` callbacks

```clojure
(defn corrupt-value
  "Apply probabilistic bit-level mutation to MIDI integer `v` at `intensity`.

  Bits are indexed 0 (LSB) through 6 (covers MIDI range 0–127).
  Flip probability for bit N is: intensity / (1 + N)
  This creates a profile where low-order bits (semitone detail) are most
  likely to flip at any intensity, and high-order bits (octave register) flip
  only at high intensity.

  Result is clamped to [0, 127] to remain a valid MIDI note number."
  [v intensity]
  (let [flip-mask (reduce bit-or 0
                   (for [bit (range 7)
                         :when (< (rand) (/ (double intensity) (inc bit)))]
                     (bit-shift-left 1 bit)))]
    (max 0 (min 127 (bit-xor v flip-mask)))))
```

**Probability schedule at representative intensities:**

| Intensity | Bit 0 (1¢ equiv.) | Bit 1 | Bit 2 | Bit 3 | Bit 4 | Bit 5 | Bit 6 (octave) |
|-----------|-------------------|-------|-------|-------|-------|-------|----------------|
| 0.05 | 5% | 2.5% | 1.7% | 1.25% | 1% | 0.8% | 0.7% |
| 0.2  | 20% | 10% | 6.7% | 5% | 4% | 3.3% | 2.9% |
| 0.5  | 50% | 25% | 17% | 12.5% | 10% | 8.3% | 7.1% |
| 1.0  | 100% | 50% | 33% | 25% | 20% | 17% | 14% |

At intensity 1.0, bit 0 always flips (±1 semitone guaranteed). Bits 5–6 flip
roughly once every 6–7 ticks — octave jumps are occasional even at maximum
intensity, matching the hardware character.

**Musical character by intensity range:**

| Range | Character |
|-------|-----------|
| 0.0–0.1 | Imperceptible; rare ±1 semitone micro-drift |
| 0.1–0.3 | Subtle pitch drift; occasional semitone neighbour-note ornaments |
| 0.3–0.6 | Melodic mutation; 3rds and 5ths appear; sequence evolves perceptibly |
| 0.6–0.9 | Aggressive mutation; octave jumps; original sequence partially obscured |
| 0.9–1.0 | Near-random; sequence degrades toward noise |

---

### 26.9 `on-corrupt!` Callback Contract

```clojure
(flux/on-corrupt! :my-flux
  (fn [step-idx old-val new-val]
    ;; react to mutation
    ))
```

**Arguments passed to the callback:**

| Arg | Type | Description |
|-----|------|-------------|
| `step-idx` | integer | 0-indexed position in the step buffer that was mutated |
| `old-val` | integer | Value before the bit-flip(s) |
| `new-val` | integer | Value after the bit-flip(s) and clamping |

**Contract:**

- The callback is invoked **in the CORRUPT process thread** (not the read-head
  thread, not the calling thread). Side-effecting code must be thread-safe.
- The callback is called **after** the new value is written to the step atom,
  so a concurrent read head reading that position will see the new value.
- **Multiple callbacks** registered on the same flux are called in registration
  order. All are called even if one is slow; they share the CORRUPT tick budget.
- **Exceptions** thrown from a callback are caught, printed to `*err*`, and do
  not abort the CORRUPT process or prevent subsequent callbacks from running.
- **Deregistration** is by identity (`=` on the fn object). Store the fn in a
  var if you intend to deregister it later.
- **No callback** is fired if `corrupt-value` returns `old-val` unchanged (no
  bits flipped, or all flips cancelled out). The no-op case is common at low
  intensity.

**Typical uses:**

```clojure
;; Trigger an envelope each time a corruption fires (BIT FLIP equivalent)
(flux/on-corrupt! :melody
  (fn [_idx _old _new]
    (ctrl/send! [:env/trig] 1)))

;; Log the mutation delta for diagnostic purposes
(flux/on-corrupt! :melody
  (fn [idx old new]
    (println (format "corrupt step=%d %d→%d (Δ%+d)" idx old new (- new old)))))

;; Suppress large jumps: if new-val is more than an octave away, revert
(flux/on-corrupt! :melody
  (fn [idx old new]
    (when (> (Math/abs (- new old)) 12)
      (flux/set-step! :melody idx old))))
```

The last example shows the "soft limit" pattern: an `on-corrupt!` callback
that rolls back mutations exceeding a musical threshold, effectively bounding
the CORRUPT character while retaining fine drift.

---

### 26.10 Interaction with `defstochastic` (§23)

`defflux` and `defstochastic` address orthogonal aspects of generative sequencing
and compose naturally.

#### Write head driven by a stochastic X-section

The write head's `:source` accepts any `ITemporalValue`, including a
`defstochastic` X-section output. This combines Marbles-style distribution
shaping with Labyrinth-style buffer inscription:

```clojure
(defstochastic :gen
  :x-spread 0.5 :x-bias 0.6 :x-steps 0.7
  :x-scale (weighted-scale :C :major)
  :channels 1)

(defflux :stoch-flux
  {:steps  16
   :write  {:clock  (clock-div 2 master-clock)
            :source #(next-x! :gen 0)}  ; fn wrapping stochastic draw
   :output {:target :midi/synth :channel 1}})
```

The write head inscribes stochastically-drawn pitches into the flux buffer at
half the read rate. The buffer accumulates the distribution's shape over time;
the read head plays the running record. CORRUPT then adds another layer of
probabilistic drift on top.

#### DEJA VU + flux buffer = frozen stochastic loops

The Marbles DEJA VU mechanism (§23.6) locks the X-section into a repeating
loop. When DEJA VU is high, the stochastic sequence repeats; the write head
inscribes that loop into the flux buffer repeatedly. The flux buffer then
holds a stable stochastic loop that CORRUPT can gradually erode:

```clojure
;; Lock the stochastic generator into a loop
(ctrl/set! [:stochastic :gen :x-deja-vu] 1.0)

;; CORRUPT at low intensity to erode it slowly
(defflux :crystallized
  {:steps   8
   :write   {:source #(next-x! :gen 0) :clock (clock-div 4 master-clock)}
   :corrupt {:intensity 0.05 :clock (clock-div 8 master-clock)}
   :output  {:target :midi/synth :channel 1}})
```

Starting from a locked loop, CORRUPT introduces imperfections at a rate
controlled by `:intensity`. The result evolves from the DEJA VU loop toward
entropy — a gradual "crystallization erosion" arc.

#### Three-layer generative stack

| Layer | Component | Role |
|-------|-----------|------|
| 1 | `defstochastic` | Generates raw pitch material from a distribution |
| 2 | `defflux` write head | Inscribes that material into a circular buffer |
| 3 | `defflux` CORRUPT | Perturbs the buffered material over time |

The flux buffer acts as a shared "short-term memory" between the stochastic
generator and the play head. CORRUPT is the forgetting process.

---

### 26.11 Interaction with `deffractal` (§24)

`defflux` and `deffractal` represent contrasting philosophies — fractal sequences
are deterministically transformed from an explicit trunk; flux sequences are
continuously mutated from a shared buffer. They are complementary rather than
competing.

#### Fractal branch piped into a flux write head

A `deffractal` branch output is a sequence of step maps. These can be wrapped
into an `ITemporalValue`-compatible source and piped to a `defflux` write head:

```clojure
(deffractal :theme
  {:trunk      [60 62 64 65 67]   ; C major scale fragment
   :branches   2
   :transforms [:inverse :transpose]})

;; Pipe fractal branch 0 into a flux write head
(defflux :eroding-theme
  {:steps  16
   :write  {:clock  (clock-div 3 master-clock)
            :source #(:pitch/midi (next-step! :theme 0))}
   :corrupt {:intensity 0.15 :clock (clock-div 6 master-clock)}
   :output  {:target :midi/synth :channel 1}})
```

The fractal branch inscribes a deterministically transformed version of the
trunk into the flux buffer. CORRUPT then degrades that inscription. The result
is a fractal theme that gradually drifts away from its origin — "theme and
dissolution" rather than "theme and development".

#### Trunk seeded from a flux buffer

The reverse direction — seeding a `deffractal` trunk from the current state of
a `defflux` buffer — creates a feedback arc where corruption feeds back into
structure:

```clojure
;; Capture the current flux buffer as a new fractal trunk
(let [buffer (flux/peek :eroding-theme)]
  (deffractal :recrystallized
    {:trunk      buffer
     :branches   3
     :transforms [:retrograde :transpose :mutate]}))
```

This is the "recrystallization" pattern: CORRUPT erodes a sequence until it is
captured as a new fractal seed, which then branches into structured variations
of the eroded material. Repeating this cycle produces long-form evolution
without composer intervention.

#### Contrast summary

| Dimension | `deffractal` | `defflux` |
|-----------|--------------|-----------|
| Starting point | Explicit trunk | Seeded buffer (or any source) |
| Variation mechanism | Deterministic transforms (inverse, retrograde, transpose) | Continuous probabilistic mutation |
| Memory model | Fixed branch tree until trunk changes | Rolling circular buffer |
| Predictability | High (same input → same output) | Low (entropy accumulates) |
| Long-form arc | Development (structured variation) | Erosion / crystallization |
| Interaction | Fractal feeds flux; flux seeds fractal | Both directions valid |

#### EG TRIG MIX / trigger merge

The Labyrinth's EG TRIG MIX concept (combining step-gate triggers and CORRUPT
event triggers into a single envelope trigger) maps to the `on-corrupt!`
callback plus `ctrl/send!`:

```clojure
;; Envelope fires on both step events and corrupt events (EG TRIG MIX = :or)
(flux/on-corrupt! :eroding-theme
  (fn [_idx _old _new]
    (ctrl/send! [:bass-voice :env/trig] 1)))
```

When the fractal's step gate fires, it also calls `ctrl/send! [:bass-voice :env/trig] 1`
through the normal output pipeline. The two trigger streams merge at the
`ctrl/send!` level — the envelope sees both, firing more densely as CORRUPT
intensity increases.

---

### 26.12 Compositional Chaining

`defflux` instances can be chained, with the read head of one driving the write
head of another:

```clojure
;; Two-stage flux chain: upstream evolves slowly; downstream evolves faster
(defflux :upstream
  {:steps  8
   :write  {:source (lfo :rate 0.05) :clock (clock-div 4 master-clock)}
   :output {:target :internal}})  ; suppress direct MIDI output

(defflux :downstream
  {:steps  16
   :write  {:source #(flux/peek :upstream (mod (long (now)) 8))
            :clock  (clock-div 2 master-clock)}
   :corrupt {:intensity 0.2 :clock (clock-div 4 master-clock)}
   :output  {:target :midi/synth :channel 1}})
```

The upstream flux evolves its buffer slowly via an LFO. The downstream flux
reads from the upstream buffer (via `flux/peek`) as its write source, adds its
own CORRUPT layer, and sends the result to MIDI. The downstream sequence contains
a delayed, further-evolved version of the upstream material.

This chaining pattern is analogous to patching one Eurorack module's output into
another's input — compositional depth through composition of primitives.


## 27. DSL Sugar Layer

The sugar layer provides concise interactive forms layered on top of the core API.
All sugar is implemented as macros or functions in `cljseq.dsl` and re-exported
from `cljseq.core`. The underlying protocol-level API (`play!` with a full step map,
`deflive-loop`, `ctrl/bind!`, etc.) remains available and is what the sugar expands to.

The forms below are prioritised by frequency of use at the REPL during live performance.

### 27.1 Note Playback

```clojure
;; Shorthand play — pitch keyword or MIDI integer; uses *default-dur*
(play! :C4)          ;=> (play! {:pitch/midi 60 :dur/beats *default-dur*})
(play! 60)           ;=> (play! {:pitch/midi 60 :dur/beats *default-dur*})
(play! :C4 1/2)      ;=> (play! {:pitch/midi 60 :dur/beats 1/2})

;; *default-dur* is a dynamic var; default value 1/4 beat
;; Set globally:
(set-default-dur! 1/8)

;; Set for a scope:
(with-dur 1/8
  (play! :C4)
  (play! :E4))
```

### 27.2 Phrase Playback

```clojure
;; Uniform duration
(phrase! [:C4 :E4 :G4 :C5] :dur 1/4)

;; Pitch/duration pairs
(phrase! [:C4 1/4  :E4 1/8  :G4 1/8  :C5 1/2])

;; Both expand to: (doseq [...] (play! n d) (sleep! d))
```

`phrase!` is a macro so that the pitch vector is not evaluated eagerly in the
context of the surrounding `live-loop`.

### 27.3 Conditional / Structural Forms

```clojure
;; Fire every N beats (relative to loop's internal beat counter)
(every 4 :beats
  (play! :C4 1))

;; Fire with probability 1/N
(one-in 4
  (play! :C4))

;; Both are macros — body is only evaluated when the condition is met
```

`every` tracks a loop-local counter using a `defonce` atom. It is designed for
use inside `deflive-loop`.

### 27.4 Cycling Sequences

```clojure
;; Returns an infinite cycling seq with a cljseq ring type that tracks position
(def my-ring (ring :C4 :E4 :G4 :B4))

;; tick! returns the next value and advances the ring
(tick! my-ring)     ;=> :C4
(tick! my-ring)     ;=> :E4

;; reset! returns ring to start
(ring/reset! my-ring)
```

`ring` returns a stateful object backed by a Clojure atom holding the current
index. Compared to `(cycle [...])` it is:
- Bounded (its length is known: `(ring/len my-ring)`)
- Resettable
- Registered in the control tree if named with `defring`

### 27.5 Chord Playback

```clojure
;; Absolute chord (no harmonic context required)
(play-chord! :C4 :major)
(play-chord! :C4 :minor :dur 1)

;; Arpeggiate a chord
(arp! :C4 :major :up 1/16)     ; ascending, 16th-note steps
(arp! :C4 :major :down 1/8)    ; descending, 8th-note steps
(arp! :C4 :major :up-down 1/16)
```

`play-chord!` resolves the chord to MIDI notes and calls `play!` for each.
`arp!` plays notes sequentially with `sleep!` between each.

### 27.6 Scale / Random Helpers

```clojure
;; Random note from a scale (interactive improvisation helper)
(choose-from-scale :C :major)    ;=> 67 (random MIDI note in C major)
(choose-from-scale :C :dorian 4) ;=> MIDI note in C dorian within octave 4

;; Use with play!
(play! (choose-from-scale :C :minor))
```

### 27.7 Synth Context

```clojure
;; Set default routing for following play! calls within scope
(with-synth :piano
  (play! :C4)
  (phrase! [:E4 :G4] :dur 1/4))

;; Persistent default (until changed)
(use-synth! :piano)
```

`use-synth!` sets a control tree path `[:default/synth]`. `with-synth` binds
it dynamically within a scope.

### 27.8 Implementation Notes

All sugar forms live in `cljseq.dsl` and are re-exported from `cljseq.core` via
`:refer :all` in the ns form (or explicit refer list). The underlying `play!`
accepting a full step map remains the canonical form; sugar forms expand to it.

Dynamic vars used by the sugar layer:
- `*default-dur*` — default note duration in beats; default `1/4`
- `*default-synth*` — overridden by `use-synth!` / `with-synth`

Both are registered in the Configuration Registry (§25).

---

## 28. Phasor Signal Architecture

Established during Sprint 7 research. This section documents the foundational signal
model that underlies clocks, LFOs, envelopes, rhythm, and `defflux` head relationships.
It supersedes the `MasterClock` and `ClockDiv` record types from the `ITemporalValue`
spike (Q44).

### 28.1 Motivation

The original `ITemporalValue` spike introduced `MasterClock`, `ClockDiv`, `Lfo`, and
`Swing` as separate record types. Two problems emerged on review:

1. **Gate semantics vs. signal semantics.** `MasterClock.sample` always returns 1.0;
   `ClockDiv.sample` returns 0.0 or 1.0 at beat boundaries. These are gate signals —
   suitable for trigger detection but incompatible with shape functions that expect
   values in [0.0, 1.0). Passing a gate signal to `fold` or `sine-uni` silently
   produces wrong results at the shape function endpoints.

2. **Vestigial `source` field.** `ClockDiv` carries a `source` field expressing
   "this clock derives from source," but neither `sample` nor `next-edge` uses it.
   The field is a structural lie about a dependency that doesn't exist in the
   implementation.

Both problems disappear when clocks are expressed as **unipolar phasors** — linear
ramps in [0.0, 1.0) cycling at a given rate. The phasor is the correct primitive: it
is compatible with shape functions, it has no hidden state dependencies, and trigger
detection falls out explicitly via `delta`.

### 28.2 The Phasor type

A phasor is parameterised by `rate` (cycles per beat, rational) and `phase-offset`
(initial phase shift, [0.0, 1.0)). Its value at beat `b` is:

```
p = frac(b × rate + offset)
```

where `frac(x) = x − floor(x)`, always in [0.0, 1.0).

As an `ITemporalValue` (defined in `cljseq.clock`):

```clojure
(defrecord Phasor [rate phase-offset]
  ITemporalValue
  (sample [_ beat]
    (phasor/wrap (+ (* (double rate) (double beat))
                    (double phase-offset))))
  (next-edge [_ beat]
    (let [accumulated (+ (* (double rate) (double beat))
                         (double phase-offset))
          next-n      (+ (Math/floor accumulated) 1.0)]
      (/ (- next-n (double phase-offset)) (double rate)))))
```

`next-edge` returns the exact beat at which the phasor next wraps to 0 — the
rising edge, the scheduling event. For integer-rate cases this is numerically
identical to the old `MasterClock`/`ClockDiv` results. For fractional rates
(e.g., `(->Phasor 3/7 0)`) the result is exact with no ClockDiv equivalent.

### 28.3 Core operation vocabulary (`cljseq.phasor`)

All operations are pure functions over doubles. No dependencies.

```clojure
;; Fundamental
(defn wrap  [x]      (- x (Math/floor x)))              ; → [0.0, 1.0)
(defn fold  [p]      (- 1.0 (Math/abs (- (* 2.0 p) 1.0)))) ; → [0.0, 1.0], triangle
(defn scale [p lo hi] (+ lo (* p (- hi lo))))            ; → [lo, hi]

;; Step / rhythm
(defn index [p n]    (int (Math/floor (* p (double n)))) ; → integer [0, n)
(defn step  [p n]    (/ (Math/floor (* p (double n))) (double n))) ; staircase [0,1)

;; Trigger / sync
(defn delta [p p-prev] (if (< p p-prev) 1 0))           ; 1 at wrap, 0 otherwise
(defn sync  [p trigger] (if (pos? trigger) 0.0 p))       ; reset phase on trigger
```

`delta` is the only operation that requires the previous sample. This is correct:
trigger detection is inherently stateful, and the phasor model makes that explicit
at the call site rather than hiding it inside a record.

### 28.4 Shape functions (`cljseq.phasor`)

Shape functions map a phasor value in [0.0, 1.0) to an output value. They are plain
functions — not records, not protocols — and compose freely with each other and with
`scale`.

```clojure
(defn sine-uni  [p] (/ (+ 1.0 (Math/sin (* 2.0 Math/PI p))) 2.0))  ; [0.0, 1.0]
(defn sine-bi   [p] (Math/sin (* 2.0 Math/PI p)))                    ; [-1.0, 1.0]
(defn triangle  [p] (fold p))                                         ; [0.0, 1.0]
(defn saw-up    [p] p)                                                ; [0.0, 1.0) = phasor
(defn saw-down  [p] (- 1.0 p))                                        ; (0.0, 1.0]
(defn square    [pw] (fn [p] (if (< p (double pw)) 1.0 0.0)))        ; {0.0, 1.0}
```

`square` returns a function parameterised by pulse width `pw` ∈ [0.0, 1.0). This
is the only shape function that is not a direct mapping — the pulse width is the
configurable parameter, not a sample-time input.

### 28.5 Namespace layering

```
cljseq.phasor   — pure math, no dependencies
                  wrap, fold, scale, index, step, delta, sync
                  sine-uni, sine-bi, triangle, saw-up, saw-down, square
       ↑
cljseq.clock    — requires cljseq.phasor
                  ITemporalValue protocol
                  Phasor record (implements ITemporalValue)
                  BPM utilities: beats->ms, bpm->ms-per-beat, now-ns
                  Named constructors: master-clock, clock-div, clock-mul, clock-shift
       ↑
cljseq.mod      — requires cljseq.clock + cljseq.phasor
                  lfo      — Phasor + shape fn → ITemporalValue
                  envelope — breakpoint list → shape fn → ITemporalValue
                  one-shot — ITemporalValue that clamps after first cycle
                  (Q30 — LFO rate: empirical during implementation)

cljseq.timing   — requires cljseq.clock + cljseq.phasor
                  swing, humanize, groove templates
                  All return Long nanosecond offsets (Q35, Q46)
```

`cljseq.phasor` has no Clojure protocol or record dependencies — it is pure
arithmetic and can be used independently. `cljseq.clock` builds the protocol layer
on top, keeping `ITemporalValue` and `Phasor` co-located so that implementors of
the protocol do not need a separate protocol-namespace import.

### 28.6 Named clock constructors (replacing MasterClock and ClockDiv)

`MasterClock` and `ClockDiv` are removed. Their functionality is subsumed by `Phasor`
with named constructors that preserve the musical vocabulary:

```clojure
;; cljseq.clock
(def master-clock     (->Phasor 1   0))         ; one cycle per beat
(defn clock-div   [n] (->Phasor (/ 1.0 n) 0))  ; one cycle per n beats
(defn clock-mul   [n] (->Phasor (double n) 0))  ; n cycles per beat
(defn clock-shift [n offset]                     ; clock-div with phase offset
  (->Phasor (/ 1.0 n) offset))
```

`master-clock` as a named var retains its role as the canonical reference phasor.
The BPM field that `MasterClock` carried is removed — BPM belongs exclusively to
the scheduling layer (system-state `:config :bpm`), not to the clock signal.

**`next-edge` equivalence for integer-rate cases** (verified):

| beat | old ClockDiv(4) | new Phasor(1/4,0) |
|------|----------------|-------------------|
| 0 | 4 | 4.0 |
| 1 | 4 | 4.0 |
| 4 | 8 | 8.0 |
| 5 | 8 | 8.0 |

Scheduling contracts are preserved.

### 28.7 LFOs and envelopes (`cljseq.mod`)

An LFO is a `Phasor` composed with a shape function:

```clojure
;; cljseq.mod
(defrecord Lfo [ph shape-fn]
  ITemporalValue
  (sample    [_ beat] (shape-fn (clock/sample ph beat)))
  (next-edge [_ beat] (clock/next-edge ph beat)))

(defn lfo
  "Construct an LFO from a Phasor and a shape function.
  shape-fn: [0.0, 1.0) → number"
  [phasor shape-fn]
  (->Lfo phasor shape-fn))
```

Usage:

```clojure
(def filter-mod
  (lfo (->Phasor 1/16 0) phasor/sine-uni))   ; slow sine, unipolar

(def pan-mod
  (lfo (->Phasor 1/16 0) phasor/triangle))   ; same rate, different shape, phase-locked
```

The same `Phasor` instance can be shared between multiple `lfo` calls. Phase-locking
is free — they share the same rate and offset.

An **envelope** is a shape function defined by piecewise-linear breakpoints over
[0.0, 1.0]:

```clojure
(def adsr
  (envelope [[0.00 0.0]    ; silence
             [0.10 1.0]    ; attack peak
             [0.30 0.7]    ; decay to sustain
             [0.80 0.7]    ; sustain hold
             [1.00 0.0]])) ; release

(def voice-env (lfo (->Phasor 1/4 0) adsr))  ; 4-beat envelope cycle
```

A **one-shot** envelope runs once and clamps. It wraps any `ITemporalValue` (typically
an `lfo` with an envelope shape) and returns the clamped value after the first cycle:

```clojure
(def attack-env (one-shot (->Phasor 1/4 0) adsr start-beat))
```

`one-shot` makes `next-edge` return `##Inf` after the first cycle completes,
signalling to the scheduler that no further wakeup is needed.

### 28.8 Rhythm and step sequences as phasor derivations

A step sequence of N steps over L beats:

```clojure
(def seq-ph (->Phasor (/ 1.0 L) 0))           ; one cycle per L beats

;; Current step index at beat b:
(phasor/index (clock/sample seq-ph b) N)       ; → [0, N)

;; Trigger at each step boundary:
(phasor/delta (clock/sample seq-ph b)
              (clock/sample seq-ph b-prev))    ; → 1 at boundary, 0 otherwise

;; Read a pattern at the current step:
(def pattern [60 62 64 65 67 65 64 62])
(nth pattern (phasor/index (clock/sample seq-ph b) (count pattern)))
```

The pattern is data. The phasor is the read head. Nothing else is needed.

**Polyrhythm** is two phasors at incommensurate rates. Their coincidence period
is `lcm(1/r₁, 1/r₂)` beats — the phasor model expresses the relationship directly
without additional bookkeeping.

**Clock multiplication / subdivision** falls out from the phasor rate:

```clojure
(->Phasor 4   0)   ; 4 triggers per beat (sixteenth notes at 4/4)
(->Phasor 3/4 0)   ; 3 triggers per 4 beats — triplet quarter notes
(->Phasor 3/2 0)   ; 3:2 polyrhythm relationship to master-clock
```

### 28.9 `defflux` head relationships as phasors (§26)

The `defflux` read and write heads, expressed as phasors, make their phase
relationship first-class:

```clojure
(def read-ph  (->Phasor r-read  0))
(def write-ph (->Phasor r-write 0))

;; Phase gap: how far ahead in the cycle the read head is relative to write
(defn head-gap [beat]
  (phasor/wrap (- (clock/sample read-ph beat)
                  (clock/sample write-ph beat))))
```

`head-gap` is itself a phasor at rate `(r-read − r-write)`. Its value at any
beat is directly interpretable: 0.0 = heads aligned; 0.5 = read head is halfway
around the buffer ahead of the write head; the write head is composing notes that
the read head will reach in `N/2` steps.

This is a musically meaningful parameter — the "lookahead distance" — that the
original `defflux` design had no direct expression for. With phasors it is a
first-class derived value, exposable in the control tree and bindable via
`ctrl/bind!`.

`defflux` implementation (Phase 6+) will use `Phasor` for both heads, `phasor/index`
for step position, and `phasor/delta` for step boundary triggers.

### 28.10 Design decisions

| Decision | Rationale |
|----------|-----------|
| Replace `MasterClock`/`ClockDiv` with `Phasor` | Gate semantics incompatible with shape functions; `source` field vestigial |
| `cljseq.phasor` as a no-dependency namespace | Pure math; usable independently; shapes compose cleanly |
| `ITemporalValue` and `Phasor` co-located in `cljseq.clock` | Avoids circular dep between protocol and implementation namespaces |
| `Lfo` moves from `cljseq.clock` to `cljseq.mod` | `cljseq.clock` is clock infrastructure; LFOs are modulation signals |
| `Swing` moves from `cljseq.clock` to `cljseq.timing` | Timing modulators have their own namespace (Q46) |
| BPM removed from clock record | BPM is a scheduling concern; phasors are beat-relative, BPM-agnostic |

---

## 29. Time Model and Synchronization

Time is the most fundamental concern of a sequencer, and the most treacherous. cljseq
operates across five distinct time representations simultaneously. Getting the
conversions between them correct — and understanding which representation is
authoritative in each context — is critical to correctness under real-world studio
conditions.

This section establishes the complete time model: what each representation is, how it
relates to the others, where authority lies, and what the known failure modes are.

### 29.1 Time Representation Inventory

| Representation | Domain | Unit | Authority |
|----------------|--------|------|-----------|
| **Beat position** | Musical / logical | rational beats | Link (networked) or local BPM |
| **BPM** | Musical / tempo | quarter notes per minute | User or Link peer |
| **Bar position** | Musical / structural | bars + beat offset | Time signature + beat position |
| **PPQN ticks** | MIDI internal | integer ticks | Selected PPQN resolution |
| **MIDI Clock** | MIDI sync | 24 pulses per quarter note | cljseq (master) or external (slave) |
| **MIDI Time Code** | Wall-clock (SMPTE) | HH:MM:SS:FF | cljseq (master) or external (slave) |
| **Wall-clock ns** | Physical | nanoseconds | System clock / Link |
| **Sample position** | Audio / physical | integer samples | Audio interface / word clock |

None of these is strictly derivable from another without additional parameters:
- Beat → wall-clock requires BPM (and its entire tempo history if tempo has changed)
- Beat → bar requires time signature
- Beat → PPQN ticks requires the chosen PPQN resolution
- Beat → SMPTE requires BPM, SMPTE frame rate, and a reference zero point
- Beat → sample position requires BPM and sample rate

### 29.2 The Quarter Note as MIDI's Time Anchor

The MIDI specification defines tempo as **microseconds per quarter note** (μs/QN) in
a 24-bit value range [1, 16777215]. This is the `Set Tempo` meta-event (0x51) in
Standard MIDI Files and the implicit tempo model in MIDI Clock.

The BPM↔μs/QN conversion:

```
μs_per_QN = 60,000,000 / BPM
BPM       = 60,000,000 / μs_per_QN
```

Common reference points:

| BPM | μs per quarter note | ms per quarter note |
|-----|--------------------|--------------------|
| 60  | 1,000,000 | 1000.00 |
| 90  | 666,667   | 666.67  |
| 120 | 500,000   | 500.00  |
| 140 | 428,571   | 428.57  |
| 180 | 333,333   | 333.33  |

**The quarter note is a notation convention, not a physical unit.** The MIDI
specification anchors all tempo to the quarter note by convention. This means that in
any time signature, the BPM value always counts quarter notes per minute — even when
the musical beat is not a quarter note (see §29.3).

cljseq adopts this convention: `sleep! 1` advances by one quarter note, regardless of
the active time signature. This matches DAW behaviour universally.

### 29.3 Time Signatures — Simple, Compound, and the Bar Problem

A time signature N/D communicates two things:
- **N** (numerator): how many beat units fill one bar
- **D** (denominator): which note value is the beat unit (2=half, 4=quarter, 8=eighth, 16=sixteenth)

#### Simple time

In simple time signatures (2/4, 3/4, 4/4, 5/4, 7/4), the beat unit is a single note
value and N counts felt beats directly.

| Time sig | Felt beats per bar | Beat unit | Bar = N quarter notes |
|----------|-------------------|-----------|----------------------|
| 2/4      | 2                 | quarter   | 2 QN |
| 3/4      | 3                 | quarter   | 3 QN |
| 4/4      | 4                 | quarter   | 4 QN |
| 5/4      | 5                 | quarter   | 5 QN |
| 7/4      | 7                 | quarter   | 7 QN |
| 3/8      | 3                 | eighth    | 1.5 QN |
| 5/8      | 5                 | eighth    | 2.5 QN |

#### Compound time — where the trap is

In compound time (6/8, 9/8, 12/8), the numerator counts subdivisions, not felt beats.
The felt beat is a **dotted note** containing D/2 of the notated subdivision.

| Time sig | Felt beats per bar | Beat unit | Bar = N quarter notes | BPM ref |
|----------|-------------------|-----------|----------------------|---------|
| 6/8      | **2** (not 6)     | dotted quarter (= 3 eighth notes) | 4 QN = 3 × (4/3) | dotted quarter |
| 9/8      | **3** (not 9)     | dotted quarter | 4.5 QN | dotted quarter |
| 12/8     | **4** (not 12)    | dotted quarter | 6 QN | dotted quarter |

**The BPM ambiguity in compound time**: When a conductor or DAW says "120 BPM" in
6/8, do they mean 120 dotted-quarter beats per minute, or 120 quarter notes per
minute? These differ by a factor of 3/2.

- 120 dotted-quarter BPM in 6/8: dotted quarter = (3/2) × (60/120) s = 750 ms; bar = 2 × 750 ms = 1500 ms
- 120 quarter-note BPM in 6/8: quarter = 500 ms; bar = 4 × 500 ms = 2000 ms

MIDI always uses quarter-note BPM. Most notation software does too. Live performers
and conductors typically mean dotted-quarter BPM in 6/8. **cljseq follows the MIDI
convention: BPM always counts quarter notes.**

#### Bar length in cljseq

```clojure
;; In system-state:
{:config {:bpm          120
          :time-sig     {:n 4 :d 4}}}

;; Bar length in quarter notes:
(defn beats-per-bar [{:keys [n d]}]
  ;; For simple time: n × (4/d) quarter notes
  ;; For compound time: (n/3) × (4/(d/2)) = (n/3) × (8/d) — but we flag compound
  (* n (/ 4.0 d)))

;; 4/4: 4 × (4/4) = 4 QN per bar
;; 3/4: 3 × (4/4) = 3 QN per bar
;; 6/8: 6 × (4/8) = 3 QN per bar  ← but felt as 2 dotted-quarter beats
;; 7/8: 7 × (4/8) = 3.5 QN per bar
```

**Grouping within compound and irregular time** (5/4, 7/8, etc.) is
performer/arrangement information, not derivable from the time signature alone.
A 7/8 bar is typically grouped as either 3+2+2 or 2+2+3 eighth notes. cljseq
exposes grouping as an optional annotation (`:groups [3 2 2]`) in the time signature
map for display and quantization purposes; it does not affect the underlying beat
arithmetic.

#### The `bar` phasor

Given the system time signature, a bar-level phasor:

```clojure
(defn bar-phasor []
  (let [bpb (beats-per-bar (get-in @system-state [:config :time-sig]))]
    (->Phasor (/ 1.0 bpb) 0)))

;; Bar index at beat b:
(phasor/index (clock/sample (bar-phasor) b) ...)

;; Beat within bar (0-indexed) at beat b:
(int (* (clock/sample (bar-phasor) b) (beats-per-bar ...)))
```

### 29.4 Rational Beat Arithmetic and PPQN

#### Why rationals subsume PPQN

Internal beat positions in cljseq are exact Clojure rationals. Every PPQN resolution
is exactly representable:

| PPQN | 1 tick in beats | Rational form |
|------|----------------|--------------|
| 24   | 1/24 beat      | `1/24` |
| 96   | 1/96 beat      | `1/96` |
| 480  | 1/480 beat     | `1/480` |
| 960  | 1/960 beat     | `1/960` |

There is no need to choose a PPQN for internal representation. A 32nd note is `1/8`
beat exactly. A 32nd-note triplet is `1/12` beat exactly. These are not approximations
in rational arithmetic; they are exact.

**PPQN is an output format concern, not an internal representation concern.**

#### When PPQN matters

| Context | Required PPQN | Reason |
|---------|--------------|--------|
| MIDI Clock output | 24 | MIDI spec: 24 Clock messages per quarter note |
| Sync to vintage DIN sync hardware | 24 | Roland/Korg DIN sync standard |
| High-resolution drum machine sync | 96 | 32nd-note accuracy at 3× normal resolution |
| Standard MIDI File (SMF) | 480 or 960 | DAW import/export convention |
| Pro Tools / Logic SMF | 960 | Matches their internal tick resolution |
| Ableton Live SMF | 96 | Ableton's native PPQN |

cljseq must be capable of exporting at any of these PPQN values by quantising
rational beat positions to the nearest tick. Quantisation error is at most
`0.5 / PPQN` beats; at 960 PPQN and 120 BPM this is `0.5/960 × 500 ms ≈ 0.26 ms` —
well within perceptual thresholds.

#### PPQN conversion

```clojure
;; Beat position → tick number at given PPQN
(defn beat->tick [beat ppqn]
  (Math/round (* (double beat) (double ppqn))))

;; Tick number → beat position at given PPQN
(defn tick->beat [tick ppqn]
  (/ tick ppqn))   ; returns exact rational

;; Beat position → time_ns at given BPM
(defn beat->ns [beat bpm]
  (long (* (double beat) (/ 6.0e10 (double bpm)))))   ; 60e9 ns/min ÷ BPM
```

### 29.5 MIDI Clock — Production and Reception

MIDI Clock is a tempo-relative sync protocol: 24 pulse messages per quarter note,
regardless of BPM. The receiver counts pulses and infers tempo from their timing.

#### Production via phasor

MIDI Clock output maps directly to the phasor model:

```clojure
;; A phasor at 24 cycles per beat fires 24 times per quarter note.
;; Each rising edge (delta = 1) triggers a MIDI Clock message.
(def midi-clock-phasor (clock/clock-mul 24))

;; In the scheduler: at each next-edge, send a MIDI Clock message and schedule the next
(defn schedule-midi-clock [beat]
  (let [next-beat (clock/next-edge midi-clock-phasor beat)
        time-ns   (beat->ns next-beat (get-bpm))]
    (sidecar/send! {:type :midi-clock :time-ns time-ns})
    (schedule-midi-clock next-beat)))
```

At 120 BPM, clock messages arrive every `500ms / 24 = 20.833 ms`.

#### MIDI transport messages

MIDI Clock is accompanied by three transport messages:

| Message | Byte | Meaning |
|---------|------|---------|
| Start   | 0xFA | Begin from tick 0 |
| Continue| 0xFB | Resume from current position |
| Stop    | 0xFC | Halt transport |

A cljseq `start!` when MIDI Clock output is enabled sends Start + begins Clock
messages. `stop!` sends Stop. Re-starting from a non-zero beat sends Continue, not
Start.

#### Song Position Pointer

The Song Position Pointer (SPP) message encodes a position in MIDI beats (1 beat = 6
MIDI Clock pulses = 1/4 of a quarter note). Range: 0–16383 beats (0–682 bars in 4/4).
SPP is sent before Continue to allow receivers to locate to the correct position before
transport resumes.

```clojure
;; Song Position Pointer: position in 1/6-beat units (MIDI "beats")
(defn beat->spp [beat]
  (Math/round (* (double beat) 6.0)))   ; 6 MIDI beats per quarter note
```

#### Reception (cljseq as MIDI Clock slave)

When slaving to an external MIDI Clock:
1. Receive Clock messages on the MIDI input
2. Measure the interval between successive Clock messages (wall-clock time)
3. Derive BPM: `BPM = 60,000 / (interval_ms × 24)`
4. Apply jitter filtering (exponential moving average or median filter over N pulses)
5. Update the system-state BPM; live loops pick up the new BPM on their next `sleep!`

Jitter filtering is critical: raw MIDI Clock intervals have ±1–2 ms USB MIDI latency
variance, which corresponds to ±2.4–4.8 BPM error at 120 BPM without filtering.

### 29.6 MIDI Time Code — SMPTE Framing

MIDI Time Code (MTC) encodes wall-clock time in SMPTE timecode format over MIDI,
decoupled from musical tempo. It is used for synchronization to video and to devices
that track real time (tape machines, video recorders, film projectors).

#### SMPTE frame rates

| Rate | Frames/sec | Frame duration | Context |
|------|-----------|----------------|---------|
| 24 fps | 24 | 41.667 ms | Cinema (film) |
| 25 fps | 25 | 40.000 ms | PAL video; European broadcast; 48 kHz audio (clean math) |
| 29.97 fps drop-frame | ≈29.97 | 33.367 ms | NTSC colour video; US broadcast |
| 29.97 fps non-drop | ≈29.97 | 33.367 ms | NTSC broadcast (legacy); some US audio work |
| 30 fps | 30 | 33.333 ms | Audio-only; film (in some regions); MIDI default |

**Drop frame (DF) explained**: NTSC colour video runs at 30000/1001 ≈ 29.97 Hz,
not 30 Hz exactly. Over 1 hour, 30 fps timecode drifts from real time by
`3600 × (30 − 30000/1001) ≈ 3.6 seconds`. Drop-frame corrects this by skipping
frame numbers 00 and 01 of the first second of every minute, except every tenth
minute. Drop-frame timecode is therefore accurate to wall-clock time over long
durations; non-drop timecode accumulates error. **Drop-frame is a number-skipping
convention — no frames are actually dropped from the video.**

For pure audio work with no video sync requirement, **30 fps non-drop** or **25 fps**
are the safest choices. 25 fps is particularly clean because `1 frame = 40 ms =
1920 samples at 48 kHz` — a perfect integer.

#### MTC quarter-frame protocol

MTC transmits timecode as a stream of quarter-frame messages (0xF1), each carrying
4 bits of data. Eight quarter-frame messages complete one full timecode value, covering
two SMPTE frames of time. The eight messages in order:

| QF index | Data nibble |
|----------|------------|
| 0 | frame number LSB (bits 0–3) |
| 1 | frame number MSB (bits 4–5, plus 2 spare) |
| 2 | seconds LSB (bits 0–3) |
| 3 | seconds MSB (bits 4–6) |
| 4 | minutes LSB (bits 0–3) |
| 5 | minutes MSB (bits 4–6) |
| 6 | hours LSB (bits 0–3) |
| 7 | hours MSB (bit 4) + frame-rate code (bits 5–6) |

Frame-rate codes in QF 7: `00`=24fps, `01`=25fps, `10`=29.97df, `11`=30fps.

Quarter-frame messages arrive at `fps × 4` per second:
- 25 fps: 100 QF messages/second, one every 10 ms
- 30 fps: 120 QF messages/second, one every 8.333 ms

**The 2-frame decode latency**: by the time all 8 quarter-frame messages have been
received to reconstruct a timecode value, 2 frames of time have elapsed. The decoded
timecode describes a moment 2 frames in the past. A receiver must add 2 frames to the
decoded value to obtain the current position.

#### MTC production (cljseq as master)

```clojure
;; System state: MTC configuration
{:config {:mtc {:enabled    true
                :frame-rate 25
                :origin     0}}}   ; SMPTE time at beat 0 (in frames)

;; Beat position → SMPTE components at frame-rate fps
(defn beat->smpte [beat bpm fps]
  (let [wall-sec (/ (* (double beat) 60.0) (double bpm))
        total-frames (long (* wall-sec (double fps)))
        hours   (quot total-frames (* fps 3600))
        minutes (quot (rem total-frames (* fps 3600)) (* fps 60))
        seconds (quot (rem total-frames (* fps 60)) fps)
        frames  (rem  total-frames fps)]
    {:hours hours :minutes minutes :seconds seconds :frames frames}))

;; Each quarter-frame MTC message carries 4 bits of the timecode.
;; qf-index cycles 0–7; produces the correct nibble for that index.
(defn mtc-quarter-frame [qf-index {:keys [hours minutes seconds frames]} fps]
  (let [fr-code ({24 0, 25 1, 29.97 2, 30 3} fps 3)]
    (case qf-index
      0 (bit-and frames 0x0F)
      1 (bit-shift-right (bit-and frames 0x30) 4)  ; frames MSB (max 29 = 5 bits)
      2 (bit-and seconds 0x0F)
      3 (bit-shift-right (bit-and seconds 0x70) 4)
      4 (bit-and minutes 0x0F)
      5 (bit-shift-right (bit-and minutes 0x70) 4)
      6 (bit-and hours 0x0F)
      7 (bit-or (bit-shift-right (bit-and hours 0x10) 4)
                (bit-shift-left fr-code 1)))))

;; MTC quarter-frame messages fire at fps×4 per second, scheduled via sidecar
```

#### MTC reception (cljseq as slave)

1. Collect 8 consecutive quarter-frame messages to assemble a full timecode
2. Validate: QF indices should arrive in order 0–7 repeatedly (or 7–0 in reverse on
   rewind — reverse-chase mode, frame rate must still be inferred)
3. Add 2 frames to the decoded value to account for decode latency
4. Convert SMPTE → wall-clock seconds: `wall_sec = H×3600 + M×60 + S + F/fps`
   (for drop-frame: also apply the dropped-frame correction)
5. Convert wall-clock seconds → beat position: `beat = wall_sec × BPM / 60`
6. Apply this beat position to the virtual timeline; update system BPM if tempo
   messages are also being received on the same connection

**Full-frame MTC message (0xF0 7F 7F 01 01 H M S F 0xF7)**: A SysEx message
containing the complete timecode in a single packet. Sent when transport locates to
a new position. Receiver should apply it immediately without the 2-frame correction
(it describes the current position precisely).

**MTC vs. MIDI Clock — when to use each:**

| Use case | Protocol |
|----------|---------|
| Sync among music hardware | MIDI Clock |
| Sync to video timeline | MTC |
| Sync to tape / film | MTC (via SMPTE-to-MTC converter) |
| Record position in SMF | PPQN ticks (not MTC) |
| Long-form session with video | MTC (drop-frame if NTSC) |
| Live performance (tempo-elastic) | MIDI Clock or Link |

### 29.7 Ableton Link — The Shared Timeline

Ableton Link is a network protocol (UDP multicast on the local subnet) that
synchronizes **beat position**, **tempo**, and **phase** among participating
applications and devices. It is embedded in the cljseq C++ sidecar.

#### What Link provides

Link maintains a **shared timeline**: a monotonically increasing beat counter that all
peers agree on. The timeline maps between beat position and wall-clock time:

```
beat_at(wall_ns)  → double    ; beat position at a given wall-clock instant
time_at(beat)     → int64_t   ; wall-clock nanoseconds when a given beat will occur
```

These two functions are the complete interface to the Link timeline. They replace the
simple `beats × ms_per_beat` calculation that the Phase 0 `sleep!` uses.

#### Tempo authority under Link

Link's tempo model: **any peer may suggest a tempo change**. The Link protocol
propagates tempo changes to all peers. The new tempo takes effect at a specific beat
position (not immediately), so all peers change tempo at the same musical moment.

In cljseq:
- `(set-bpm! 140)` at the REPL sends a tempo suggestion to Link
- Other Link peers may simultaneously suggest different tempos — the protocol
  negotiates
- The system-state BPM reflects the Link-agreed tempo, updated in real time
- When no peers are connected, cljseq's local BPM is authoritative

#### Phase quantization

Link's `RequestBeat` mechanism: when cljseq starts its transport or a loop wants to
re-enter on a beat boundary, it can request that Link quantise the start to the
nearest beat (or bar). This is the network equivalent of `sync!`.

#### The critical implication for `sleep!`

Phase 0's `sleep!`:
```clojure
(Thread/sleep (long (clock/beats->ms beats (get-bpm))))
```
This is **incorrect under tempo change**. If BPM changes from 120 to 140 while a
loop is sleeping for 4 beats, the thread will wake up at the wrong wall-clock time.

The correct Phase 1 `sleep!`:
```clojure
(defn sleep! [beats]
  (let [target-beat (+ *virtual-time* (rationalize beats))
        target-ns   (sidecar/time-at target-beat)]  ; from Link timeline or local BPM
    (set! *virtual-time* target-beat)
    (LockSupport/parkUntil (long (/ target-ns 1000000)))))  ; park until epoch ms
```

`LockSupport/parkUntil` parks the thread until an absolute epoch-millisecond
timestamp, allowing the JVM to unpark it early if a tempo change requires rescheduling.
Under Link, `time_at(beat)` always returns the correct wall-clock time for the current
timeline, accounting for all tempo changes that have occurred.

This redesign is the primary Phase 1 scheduler task on the Clojure side.

### 29.8 Audio Sample Clock and Word Clock

The cljseq-audio sidecar processes audio at a **sample rate** (typically 44100 or
48000 Hz). In a studio context, the audio interface is clocked by an external **word
clock** signal — a square wave at the sample rate, distributed via BNC cable to all
digital audio devices. All devices on the same word clock will have sample-synchronous
audio; devices on different clocks will drift and produce clicks at the sample-rate
difference.

#### Beat-to-sample conversion

```
samples_per_beat = sample_rate × 60 / BPM
```

Reference table at 48000 Hz:

| BPM | Samples per beat | Samples per bar (4/4) |
|-----|-----------------|----------------------|
| 60  | 48,000 | 192,000 |
| 90  | 32,000 | 128,000 |
| 120 | 24,000 | 96,000  |
| 140 | 20,571 | 82,286  |
| 180 | 16,000 | 64,000  |

At 120 BPM and 48 kHz, one MIDI Clock pulse (1/24 beat) = `24,000/24 = 1,000 samples`
exactly. One tick at 96 PPQN = 250 samples. One tick at 960 PPQN = 25 samples ≈ 0.52 ms.

#### Sample-accurate MIDI scheduling

For plugin parameter automation and sample-accurate note delivery to CLAP plugins, the
cljseq-audio sidecar must deliver MIDI events at a specific **sample offset within the
current audio buffer**. The DAW/host calls the plugin's `process()` function with a
buffer of N samples (typically 64–512 samples); events within that buffer are tagged
with their sample offset [0, N).

```
sample_offset = round((event_beat - buffer_start_beat) × samples_per_beat)
```

This requires the audio sidecar to know:
- The beat position at the start of the current audio buffer
- The BPM at that moment
- The sample rate

Under Link, the audio sidecar gets `beat_at(buffer_start_time_ns)` from the Link
library to compute `buffer_start_beat` with sub-sample accuracy.

#### Word clock and sample rate mismatch

If the studio master clock is at 48000 Hz and cljseq's audio interface is at 44100 Hz
(different projects, different hardware), the audio interface's clock will drift from
the master at a rate of `(48000 − 44100) / 48000 ≈ 8%` — a severe and immediately
audible pitch shift.

Sample rate mismatches must be resolved at the hardware level (change the interface
setting, or use a sample rate converter). cljseq logs a warning if the audio interface
reports a sample rate inconsistent with the system configuration but cannot fix the
underlying hardware clock mismatch.

### 29.9 The cljseq Master Timeline

#### System-state time configuration

```clojure
{:config
 {:bpm       120               ; quarter notes per minute
  :time-sig  {:n 4 :d 4        ; 4/4 time
              :groups nil}      ; optional grouping for 5/8, 7/8 etc.
  :ppqn      960               ; for MIDI file export / DAW interchange
  :sample-rate 48000           ; audio interface sample rate
  :link {:enabled false}       ; Ableton Link
  :midi-clock {:out false      ; generate MIDI Clock on output
               :in  false}     ; slave to external MIDI Clock
  :mtc {:out false             ; generate MTC on output
        :in  false             ; slave to external MTC
        :frame-rate 25         ; SMPTE frame rate for MTC
        :origin 0}}}           ; wall-clock ns at beat 0
```

#### The unified conversion map

All time representations derive from two values: **beat position** and **BPM**.
Given these, every other representation is derivable:

```clojure
(defn time-map
  "Return a map of all time representations at the given beat position."
  [beat bpm {:keys [time-sig sample-rate mtc]}]
  (let [bpb        (beats-per-bar time-sig)
        wall-sec   (/ (* (double beat) 60.0) (double bpm))
        wall-ns    (long (* wall-sec 1e9))
        samples    (long (* wall-sec (double sample-rate)))
        smpte      (beat->smpte beat bpm (:frame-rate mtc 25))]
    {:beat          beat
     :bar           (long (/ beat bpb))
     :beat-in-bar   (mod beat bpb)
     :wall-ns       wall-ns
     :wall-sec      wall-sec
     :samples       samples
     :tick-96ppqn   (beat->tick beat 96)
     :tick-960ppqn  (beat->tick beat 960)
     :midi-clock-pulse (beat->tick beat 24)
     :smpte         smpte}))
```

#### Authority hierarchy under different sync modes

| Mode | Beat authority | BPM authority | Wall-clock authority |
|------|---------------|---------------|---------------------|
| Standalone | local counter | `(set-bpm!)` | System clock |
| MIDI Clock slave | external clock pulses | derived from pulse interval | System clock |
| MTC slave | derived from SMPTE | externally fixed | MTC timecode |
| Link peer | Link timeline | Link negotiated | Link (`time_at`) |
| Link + MTC out | Link timeline | Link negotiated | MTC generated from Link |

When Link is active, `time_at(beat)` from the Link library is the single source of
truth for wall-clock time. All other calculations derive from it. The local BPM value
in system-state is kept in sync with Link's tempo as a read-only display value.

### 29.10 Design Decisions and Open Questions

#### Resolved in this section

| Decision | Resolution |
|----------|-----------|
| BPM unit | Always quarter notes per minute, matching MIDI convention |
| Internal time representation | Exact Clojure rationals — PPQN is an output format |
| Compound time | Bar length computed as N×(4/D) quarter notes; felt beats are a display annotation |
| MIDI Clock PPQN | 24 PPQN for hardware output (MIDI spec); internal positions remain rational |
| `sleep!` correctness | Phase 0 implementation is tempo-constant only; Phase 1 must use `time_at(beat)` |

#### New open questions

**Q56 — Time signature in system-state: where does it affect the API?**

Does the active time signature affect `sleep! 1` (always = 1 quarter note) or the
bar-level `sync!`? Recommendation: BPM and `sleep!` are always quarter-note relative
(MIDI convention). Time signature affects only bar boundaries, bar-level `sync!`, and
display. Needs explicit API decision before `sync!` Phase 1 implementation.

**Q57 — PPQN for MIDI Clock output: 24 only, or configurable?**

24 PPQN is required for hardware MIDI Clock compatibility. Some devices also accept
96 PPQN (for higher resolution sync). Should cljseq allow the output PPQN to be
configured, or always emit at 24? Recommendation: always 24 PPQN for MIDI Clock
output (matches the MIDI standard exactly); expose 96/960 PPQN only for SMF export.

**Q58 — MTC frame rate default and drop-frame handling**

25 fps is the most internally consistent choice for 48 kHz audio (1 frame = 1920
samples exactly). 29.97 drop-frame is required for NTSC video sync. Should cljseq
implement drop-frame correction? Recommendation: yes, but gated behind explicit
configuration (`{:frame-rate 29.97 :drop-frame true}`). Default: 25 fps.

**Q59 — `sleep!` redesign for tempo-correctness (Phase 1 blocker)**

The Phase 1 `sleep!` must use `time_at(beat)` from Link (or local BPM) to compute
an absolute wall-clock target, then use `LockSupport/parkUntil` rather than
`Thread/sleep`. Loops parked via `parkUntil` can be woken early (unparked) when a
tempo change requires rescheduling. This is the primary scheduler task for Phase 1.

**Q60 — Tempo change propagation to sleeping loops**

When BPM changes mid-session (Link peer, `set-bpm!`, or tempo automation), loops
currently sleeping with `Thread/sleep` will wake up at the wrong time. The Phase 1
redesign (`parkUntil` + reschedule signal) must define: which loops are affected, how
they are notified, and whether the partial sleep is absorbed or restarted. This
interacts with the control tree (§Q47) and Link's timeline API.

---

## 30. Plugin Container and Virtual Performance Rack (Future Sprint)

**Status**: Future design sprint — not Phase 0/1/2.
**Reference**: Gig Performer 5 user manual (gigperformer.com) as a commercial
exemplar of the concept. Also: Pedalboard2 (open source), Carla (JACK plugin host),
Blue Cat's Patchwork.

Section §19 covers single CLAP/VST3 plugin hosting: one plugin, one audio output,
controlled via `ctrl/send!`. This section captures the design space for the
**next level up**: a configurable signal flow graph in which multiple plugins are
wired together as a virtual effects rack or pedalboard, with cljseq's sequencing and
modulation system as the control layer.

### 30.1 The Concept

A **plugin rack** in cljseq is a directed signal flow graph where:

- **Nodes** are audio/MIDI processing units: CLAP plugins, VST3 plugins, PureData
  patches (via PlugData), audio inputs/outputs, MIDI inputs/outputs, and built-in
  utility nodes (mixers, splitters, gain stages)
- **Edges** are audio signal connections (carrying stereo or multichannel audio
  buffers) or MIDI/event connections (carrying note and CC streams)
- The graph has designated **input nodes** (hardware audio in, MIDI in) and
  **output nodes** (hardware audio out, recording bus, MIDI out)

The signal flows from inputs through the graph to outputs on every audio buffer
callback, in topological order.

This is the architecture used by Gig Performer ("Wiring View"), Blue Cat's Patchwork,
and DAW insert chains — but in cljseq it is defined in Clojure data, live-codeable
at the nREPL, and directly controlled by `deflive-loop`, `defstochastic`, `defflux`,
and all other cljseq sequencing primitives.

### 30.2 Motivating Use Cases

**Live performance pedalboard replacement**: A guitarist or bassist defines their
complete signal chain as a plugin rack — tuner, compressor, overdrive, delay, reverb,
IR loader — wired in series. The rack runs in `cljseq-audio`. Parameters (delay time,
reverb mix, drive level) are controlled by MIDI foot controllers via `ctrl/bind!`.
`deflive-loop` sequences tempo-synced delay times and modulates reverb decay.

**Modular synthesizer emulation**: An electronic musician creates a virtual modular
system: oscillator (CLAP synth) → filter (CLAP effect) → envelope (cljseq `one-shot`)
→ VCA (gain node) → reverb (CLAP effect). The LFO is a cljseq `lfo` bound to the
filter cutoff via `ctrl/bind!`. The envelope fires from `deflive-loop`'s step gate.

**Multi-instrument live setup**: A keyboard player loads an organ plugin on channel 1,
a piano plugin on channel 2, and an effects rack (chorus → reverb) on a shared bus.
MIDI routing directs channel 1 notes to the organ and channel 2 notes to the piano.
Both feed the effects bus before the hardware output. Switching between "jazz" and
"gospel" rackspaces (§30.4) changes the plugin parameters and effects chain
simultaneously.

**PureData/PlugData DSP integration**: A PlugData patch implements a custom granular
processor. It is a node in the rack, receiving audio from an upstream synth plugin
and sending to the output bus. Parameters exposed by the Pd patch are addressable
via `ctrl/send!` and can be driven by `defstochastic` sequences.

### 30.3 The Plugin Graph as Clojure Data

The rack is a pure Clojure data structure — a map of nodes and edges. It is defined
at the REPL, serialisable to EDN, and sent to `cljseq-audio` via IPC for realisation.

```clojure
;; Declarative rack definition
(defrack :main
  {:nodes
   {:audio-in   {:type :audio-in  :channels 2}
    :guitar-di  {:type :audio-in  :channels 1 :device "BlackHole 2ch"}
    :comp       {:type :clap      :plugin "com.cableguys.magnetiq" :preset "Opto"}
    :drive      {:type :clap      :plugin "com.airwindows.Overdrive"}
    :delay      {:type :clap      :plugin "com.tokyodawn.tdmultiband"}
    :reverb     {:type :clap      :plugin "com.valhalladsp.ValhallaRoom"}
    :mixer      {:type :mixer     :channels 2 :stereo true}
    :audio-out  {:type :audio-out :channels 2}}

   :audio-edges
   [[:guitar-di :comp]
    [:comp      :drive]
    [:drive     :delay]
    [:delay     :mixer 0]   ; delay → mixer input 0
    [:drive     :mixer 1]   ; dry signal → mixer input 1 (parallel compression)
    [:mixer     :reverb]
    [:reverb    :audio-out]]

   :midi-edges
   [[:midi-in :comp]
    [:midi-in :delay]]})   ; MIDI CC to comp and delay for tempo sync

;; Modulation: bind delay time to a beat-synced phasor value
(ctrl/bind! [:main :delay :params :delay-time]
  (lfo (clock-div 2) phasor/saw-up))

;; Automation: sweep reverb mix with a live loop
(deflive-loop :reverb-swell {}
  (ctrl/send! [:main :reverb :params :wet-level]
    (phasor/sine-uni (phasor/wrap (* (now) 0.25))))
  (sleep! 1/8))
```

The graph is sent to `cljseq-audio` as an IPC command. The audio process
builds the processing graph, loads plugins, and begins the audio callback.

### 30.4 Rackspaces — Named Graph Configurations

A **rackspace** is a complete named rack definition. Multiple rackspaces can be
defined and switched atomically during performance — the audio equivalent of `patch!`
(Q48) at the graph level.

```clojure
(defrack :clean   { ... })   ; clean guitar tone
(defrack :crunch  { ... })   ; overdrive + chorus
(defrack :lead    { ... })   ; high gain + delay + reverb

;; Switch rackspace on the next bar boundary
(rack/switch! :lead :on :next-bar)

;; Switch immediately (may cause audio glitch)
(rack/switch! :clean :on :now)
```

Rackspace switching concerns:
- **Audio continuity**: reverb and delay tails from the outgoing rackspace should
  ring out rather than cut abruptly. The audio process fades out the old graph while
  the new one fades in (crossfade duration configurable).
- **State preservation**: on switch-back, a rackspace should restore to its last
  parameter state (not reset to defaults). This is the **variations** concept below.
- **Latency compensation**: different plugin chains have different latency. The audio
  process must recompute latency compensation when switching.

### 30.5 Variations — Parameter Snapshots Within a Rackspace

A **variation** is a parameter snapshot for a rackspace — the same plugin graph,
different parameter values. Analogous to Gig Performer's "Variations" or a DAW's
A/B comparison.

```clojure
;; Save current parameter state as a variation
(rack/save-variation! :main :verse)
(rack/save-variation! :main :chorus)

;; Recall a variation
(rack/recall-variation! :main :chorus)

;; Interpolate between variations over N beats (parameter morphing)
(rack/morph-variation! :main :verse :chorus :beats 4)
```

`morph-variation!` is a natural extension of `defmorph` (§24 — Bloom-Inspired
transformations) applied to plugin parameter spaces rather than pitch sequences.

### 30.6 Audio Routing Topology

The plugin graph in `cljseq-audio` must handle:

**Serial chains** (most common): A → B → C — straightforward topological sort.

**Parallel paths with a mixer**: A → B and A → C, then B → mix input 0, C → mix input 1.
The mixer sums the signals.

**Send/return (FX bus)**: Main chain sends a portion of its signal to a reverb
bus (return), which is summed back to the main output. Classic wet/dry parallel routing.

**Sidechain**: The kick drum audio feeds the sidechain input of a compressor on the
bass bus. The kick's envelope triggers the compressor regardless of the bass level.

**Feedback loops**: An output of a plugin is routed back to an earlier input (e.g.,
delay feedback). This requires a one-buffer-cycle delay on the feedback path to avoid
an algebraic loop. The audio process detects cycles in the graph and inserts feedback
delay buffers automatically.

**Constraints to decide in the design sprint**: maximum node count, maximum bus width
(stereo vs. surround), latency compensation algorithm, and whether the graph must be
a DAG (no feedback) or whether feedback loops are supported with the delay-buffer
approach.

### 30.7 Integration with cljseq Modulation and Sequencing

The plugin rack is not a passive signal chain — it is a **live-modulated performance
instrument**. Every plugin parameter is a leaf node in the cljseq control tree,
addressable via its rack path:

```clojure
[:rack-name :node-name :params :param-name]
;; e.g.
[:main :reverb :params :room-size]
```

This means every cljseq modulation primitive applies directly:

| cljseq primitive | Plugin rack use |
|-----------------|----------------|
| `ctrl/bind!` | Bind hardware knob to plugin parameter |
| `ctrl/send!` | Directly set parameter value from a loop or macro |
| `lfo` | Continuously modulate a parameter (vibrato, tremolo, filter sweep) |
| `envelope` + `one-shot` | Envelope-shape a parameter on note trigger |
| `deflive-loop` | Sequence parameter changes in musical time |
| `defstochastic` | Randomly vary parameter within a distribution |
| `defflux` | Gradually evolve parameter via CORRUPT-style mutation |
| `patch!` | Atomically update multiple parameters simultaneously |
| `rack/morph-variation!` | Interpolate between parameter presets over time |

The plugin rack exposes its parameters to the cljseq modulation system via `defdevice`
(§Q55) EDN maps, automatically generated by interrogating the plugin's CLAP parameter
extension at load time.

### 30.8 Relationship to Existing Design

| Existing component | Role in plugin rack |
|-------------------|---------------------|
| `cljseq-audio` (§3.5) | The process that hosts and runs the plugin graph |
| `defdevice` (Q55) | Parameter maps for each plugin node in the graph |
| `ctrl/send!` (Q4) | Routes parameter updates to the audio process via IPC |
| `patch!` (Q48) | Atomically updates a set of parameters at a beat boundary |
| `defrack` (new) | Defines the plugin graph data structure |
| `rack/switch!` (new) | Switches between rackspaces |
| PureData / PlugData (§19) | A plugin node type in the graph |
| Ableton Link (§29.7) | Tempo reference for time-synced parameters in the rack |
| `cljseq.phasor` (§28) | LFO sources driving plugin parameters |

The primary new component is the `defrack` macro and the IPC protocol for
communicating graph topology to `cljseq-audio`. Everything else is already in the
design.

### 30.9 What Makes This Different from Gig Performer

Gig Performer is a strong reference implementation but is fundamentally a
**GUI-first** tool: plugins are wired by dragging cables, parameters are mapped via
a widget surface, and scripting (GPScript) is a secondary layer for power users.

cljseq's plugin rack is **code-first**: the graph is defined in Clojure data, wired
by evaluating a `defrack` form at the REPL, and controlled by live-coding primitives
that have no GUI analogue. The key differences:

| Dimension | Gig Performer | cljseq plugin rack |
|-----------|--------------|---------------------|
| Graph definition | GUI drag-and-drop | Clojure data / `defrack` |
| Parameter control | Widget surface + MIDI map | `ctrl/send!` / `ctrl/bind!` |
| Automation | GUI lane editor | `deflive-loop`, `lfo`, `defstochastic` |
| Rackspace switching | GUI button / MIDI PC | `rack/switch!` (beat-aware) |
| Scripting | GPScript (proprietary) | Full Clojure DSL |
| Generative capabilities | None | `defflux`, `defstochastic`, `deffractal` |
| Version control | Binary project file | Plain Clojure / EDN — git-friendly |
| Network sync | None | Ableton Link integration |

The live-coding paradigm also enables capabilities that GUI-first tools cannot easily
provide: a `deflive-loop` that morphs reverb size over 32 bars, a `defstochastic`
sequence driving delay feedback, or a `defflux` sequence gradually eroding a filter
cutoff pattern. These are idiomatic cljseq expressions, not special plugin-rack
features.

### 30.10 Design Questions for the Sprint

**Q61 — Plugin graph DSL: `defrack` macro vs. imperative API**

Should the graph be defined declaratively (`:nodes` + `:edges` map, entire graph sent
at once) or imperatively (`(add-node! :comp ...)`, `(connect! :comp :drive)`)? Or
both — a macro that compiles a declarative form to imperative calls?

Recommendation: declarative as the primary form (idiomatic Clojure data; git-diffable;
serialisable to EDN for saving/loading). Imperative functions for interactive REPL
patching. The macro compiles the declarative form at eval time.

**Q62 — Rackspace switching and audio continuity**

The crossfade approach (old graph rings out, new graph fades in) requires running
two graphs simultaneously during the transition. How long is the crossfade window?
How is it interruptible (e.g., a second switch arrives before the first crossfade
completes)?

**Q63 — Audio routing topology constraints**

DAG-only (simpler) or allow feedback with delay-buffer insertion (more powerful)?
Maximum polyphony per plugin node? Latency compensation algorithm (PDC)?

**Q64 — VST3 in the plugin container**

The plugin catalog for VST3 is much larger than CLAP (2024). The architecture
isolates plugin hosting behind `SynthTarget` (§19.2), so VST3 is a new subclass.
When should VST3 support be prioritised? Does it belong in the same sprint as
`defrack`, or as a subsequent phase?

**Q65 — Live graph modification (hot-patching)**

Can an audio connection be added or removed while the graph is running? This requires
careful synchronisation between the audio callback thread and the IPC command thread.
The audio process must apply graph modifications between buffer callbacks (not during),
which requires a double-buffer or copy-on-write approach for the processing graph.

---

## 31. DAWProject Interchange Format (Future Sprint)

**Status**: Future design sprint — not Phase 0/1/2.
**Reference**: https://github.com/bitwig/dawproject — MIT license, official Java SDK,
XSD schemas. Developed by Bitwig and Steinberg; supported by Bitwig Studio 5+,
Studio One 6.5+, Cubase 14, VST Live 2.2.

### 31.1 What DAWProject Is

DAWProject (`.dawproject`) is an open DAW interchange format: a ZIP archive containing
`project.xml` (main project data), `metadata.xml`, embedded audio files, and plugin
state files. XML schema (XSD) governs the format; a Java reference SDK is available.

Key capabilities captured by the format:

| Category | Content |
|----------|---------|
| Timing | Tempo track (BPM automation), time signature changes, markers |
| Arrangement | Tracks, clips (MIDI and audio), clip launcher scenes |
| MIDI | Note sequences with velocity and expression |
| Automation | Parameter curves (points-based with interpolation modes) |
| Plugin state | CLAP, VST3, VST2, AU — state blobs as ZIP-embedded files |
| Mix | Channel hierarchy, sends, built-in EQ/compressor/gate parameters |
| Metadata | Artist, composer, copyright, tempo, key, genre |

Intentionally excluded: view settings, preferences, low-level MIDI events.

### 31.2 Value Assessment for cljseq

**High value — export path (cljseq → DAW)**:

The primary workflow is "sketch in cljseq, finish in a DAW." A live-coded session
produces patterns, modulation curves, and plugin configurations that a producer then
wants to arrange, mix, and export. DAWProject provides a clean bridge:

```
deflive-loop patterns  →  MIDI clips in arrangement
ctrl/send! automation  →  parameter automation curves
defdevice + CLAP state →  plugin instances with loaded presets
:config :bpm / :time-sig →  tempo track + time signature
```

This is the "sketch-to-DAW" workflow: cljseq as a generative composition and
performance tool that hands off to Bitwig, Studio One, or Cubase for production.

**Medium value — import path (DAW → cljseq)**:

Selective import is useful for specific use cases:
- **Tempo map**: import BPM automation and time signature changes from a video project
  or collaboration; ingest into cljseq's system-state timeline (§29.9)
- **MIDI clips**: import reference material as seed sequences for `deffractal` trunks,
  `defstochastic` weighted scale learning, or `defflux` buffer initialisation
- **Plugin states**: load a plugin's `.dawproject` state blob into a running
  `cljseq-audio` session — equivalent to loading a preset saved in Bitwig

Full round-trip import (entire project) is lower priority; the format's "notable
omissions" (MIDI events at the granular level, view data) mean it is not a lossless
representation of a cljseq session.

**Low value — using the Java SDK as a dependency**:

The official SDK is a Java 16+ Gradle/Maven project using XML class annotations to
generate schemas. Taking it as a JVM dependency is feasible but adds build complexity.
A thin Clojure implementation using `clojure.data.xml` and `java.util.zip` covers the
cljseq-relevant subset with no added dependency and is straightforward to write from
the published XSD schemas.

### 31.3 cljseq Concept → DAWProject Mapping

| cljseq concept | DAWProject equivalent |
|---------------|----------------------|
| `deflive-loop` pattern (one pass) | MIDI clip on an instrument track |
| `play!` note events | MIDI note elements with velocity |
| `ctrl/send!` parameter automation | Automation lane on a plugin parameter |
| `lfo` / `envelope` curves | Automation points with interpolation mode |
| `:config :bpm` | Tempo track point |
| `:config :time-sig` | TimeSignature element |
| `defdevice` + loaded CLAP state | `<ClapPlugin>` element + state file |
| Rack variation snapshot (§30.5) | DAWProject clip with parameter values |
| Session markers / loop points | Marker track elements |

The tempo track and time signature elements in DAWProject align directly with the
§29.9 master timeline design — a cljseq session's time configuration serialises
naturally.

### 31.4 Technical Notes

**Format**: ZIP archive. In Clojure: `java.util.zip.ZipInputStream` /
`ZipOutputStream` with `clojure.data.xml` for XML serialisation. No external
dependencies needed.

**Automation curves**: DAWProject uses a points-based model (list of `[time value]`
pairs with interpolation mode: `linear`, `hold`, `curved`). This maps directly to
the `envelope` breakpoints from §28.7 — cljseq's piecewise-linear envelopes can be
serialised as DAWProject automation with `linear` interpolation.

**Plugin state blobs**: CLAP plugin states are stored as opaque binary files within
the ZIP, referenced by path from `project.xml`. The `cljseq-audio` sidecar can
request a state blob from a loaded plugin via `CLAP_EXT_STATE` and write it into the
ZIP; on import, it can deliver the blob back to the plugin to restore its state.

**MIDI representation**: DAWProject's MIDI note model (pitch, velocity, start time,
duration) maps directly to the cljseq step map (`:pitch/midi`, `:dur/beats`,
`:gate/on?`, `:mod/value` for velocity). The time values in DAWProject are in beats
(quarter notes) — same anchor as cljseq's rational beat positions (§29.2).

**Bitwig as the primary interchange target**: Bitwig Studio and cljseq share two
foundational choices — CLAP as the primary plugin standard and Ableton Link for
network sync. A cljseq session exported to DAWProject will have the best fidelity
when imported into Bitwig, including CLAP plugin states.

### 31.5 Design Questions for the Sprint

**Q66 — Export scope: what cljseq constructs should export to DAWProject?**

Should export be limited to captured patterns (MIDI clips from rendered loop
iterations) or should it also include live modulation curves, plugin configurations,
and the tempo/time-signature timeline? A phased approach: Phase A exports MIDI clips
and tempo map; Phase B adds automation curves and plugin states.

**Q67 — Import granularity: full project or selective?**

Full project import requires mapping the complete DAWProject track hierarchy to
cljseq constructs (or producing a `defrack` form from the DAWProject's plugin chain).
Selective import (tempo map only, or MIDI clips only) is simpler and covers the
immediate use cases. Recommendation: selective import first; full import as a later
phase if the demand exists.
