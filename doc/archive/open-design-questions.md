# cljseq: Open Design Questions

This document tracks design ambiguities, tensions, and unresolved questions surfaced
during review of `doc/research-and-requirements.md`. Each entry states the question,
the stakes if it is answered wrong, and a concrete exploration path.

Questions marked **RESOLVED** have been answered in the requirements document and are
kept here for traceability.

---

## Q1 — `*virtual-time*` as `(atom 0N)` vs a plain rational — **RESOLVED**

**R&R reference**: §3.2

**Resolution** (Sprint 2)

`*virtual-time*` is a plain `^:dynamic` rational var, not an atom. `binding`
already provides per-thread isolation; the atom wrapper is redundant and
dangerous — a child thread inheriting the binding could `swap!` the parent's
time, causing silent phase corruption in a live performance context.

- `sleep!` advances virtual time via `clojure.core/set!` (the special form for
  mutating thread-local dynamic bindings)
- No design scenario requires one thread to observe another's virtual time
  directly; `cue!` transfers a *copy* of the value at cue-fire time
- If a debug dashboard needs to display running virtual time, it reads from an
  `EventHistory` side-channel that `sleep!` updates as a secondary effect — the
  var itself is never shared across threads

---

## Q2 — `live-loop` redefinition semantics: what constitutes an "iteration"? — **RESOLVED**

**R&R reference**: §3.2, §5.3

**Resolution** (Sprint 2)

Full-iteration semantics by default: a redefined `live-loop` body takes effect
after the current invocation of the body function returns — i.e., after all
`sleep!` calls within the current iteration complete. This matches Sonic Pi's
`live_loop` and avoids any question of what to do with partially-completed
iteration state.

- The body function is held in an atom; the loop's tail-call reads the atom
  *after* each full iteration, picking up any redefinition at that point
- The precise invariant: "the new body takes effect after the current value of
  `(the-loop-body-fn)` returns"
- For situations where sub-iteration responsiveness is needed (e.g., a 4-bar
  phrase where you want a change to land within the bar), opt in with
  `:hot-swap true` on the `live-loop` form — the scheduler then checks for a
  new body at each `sleep!` boundary
- `(checkpoint!)` is a named explicit hot-swap point within a body, usable as
  an alternative to `:hot-swap true` when only specific boundaries should be
  checked

---

## Q3 — `sync!` time inheritance: late cue edge case — **RESOLVED**

**R&R reference**: §3.2

**Resolution** (Sprint 2)

Time-windowed `EventHistory`. When `(sync! :name)` is called, the system
searches `EventHistory` for the most recent cue matching `:name` within the
window `[current-virtual-time - sync-window, ∞)`:

- **If found**: inherit the cue's virtual time and return immediately — the
  thread is phase-aligned with the original cue fire, not with "now"
- **If not found**: block on a condition variable until the next `:name` cue
  fires, then inherit that time

`sync-window` is a **system-wide constant** set at startup (default:
`sched-ahead-time` + one beat at the current tempo). It is not overridable
per call — a fixed window keeps the behaviour predictable and prevents
accidental long-range time travel from a caller that sets too large a window.

**Why per-call override is intentionally absent**: inheriting virtual time from
2+ beats ago means all subsequent `sleep!` and `play!` calls generate events
scheduled in the past relative to the sched-ahead horizon. Those events are
silently dropped by the scheduler — the loop executes but produces no sound,
with no diagnostic. This failure mode is harder to debug than accepting a
one-period gap. If a loop is known to arrive consistently late, the right fix
is to restructure the loop's scheduling, not to widen the sync window at the
call site.

This gives `sync!` "at-most-one-beat-late" semantics: threads that arrive
within one beat of a cue remain phase-aligned; threads that are genuinely
more than one beat late wait for the next cue and accept a one-period gap.

---

## Q4 — `get!`/`set!` naming conflict with `clojure.core` — **RESOLVED**

**R&R reference**: §3.2

**Resolution** (Sprint 2)

The musical time-indexed state API is the control tree itself. There is no separate
primitive; `ctrl/set!` and `ctrl/get` (the control tree's own setter/getter) are the
canonical way to write and read time-indexed musical state:

```clojure
(ctrl/set! [:chord] :C-major)   ; set state on the control tree at virtual now
(ctrl/get  [:chord])            ; read from the control tree
```

This eliminates the naming conflict entirely — `clojure.core/set!` (a special form
taking a symbol as its first argument) is unambiguous from `ctrl/set!` (a function
taking a path vector). The `clojure.core/set!` special form is used internally by
`sleep!` to advance `*virtual-time*` and remains unambiguous in that context.

If a short ergonomic alias proves useful at the REPL, `at!`/`at` or `tset!`/`tget`
can be introduced later as thin wrappers over `ctrl/set!`/`ctrl/get`. Any such alias
builds on the control tree's capabilities rather than introducing a parallel
state-management mechanism.

**Original issue**

`clojure.core/set!` is a special form that cannot be shadowed, creating a compile-time
ambiguity if `set!` were used as a cljseq musical state setter. Resolved by routing
all musical state through the control tree rather than inventing a new top-level name.

---

## Q5 — `sched-ahead-time` should be per-integration-target, not per-protocol-class — **RESOLVED**

**R&R reference**: §3.1, §3.4

**Resolution** (Sprint 3)

Replace the single `*sched-ahead-ns*` binding with a per-target config map. Each
integration target's `play!` implementation reads its own value. `:bitwig-midi` is
generalised to `:daw-midi` — the relevant characteristic is DAW MIDI input latency,
not any specific DAW.

```clojure
{:midi-hardware 100   ; javax.sound.midi hardware DMA
 :sc-osc         30   ; scsynth bundles; engine handles its own scheduling
 :vcv-rack        50  ; Rack audio buffer (~256–512 samples)
 :daw-midi        50  ; DAW MIDI input latency (configurable per DAW)
 :link             0} ; Link timeline is absolute; no sched-ahead needed
```

All values in milliseconds; all configurable at startup (see R&R §25). Values should
be measured empirically per target — connect and measure actual note-to-sound latency
at different sched-ahead values. `(with-target-config target opts ...)` provides
per-session override for tuning.

---

## Q6 — Stream model real-time consumption path is underspecified — **RESOLVED**

**R&R reference**: §9

**Resolution** (Sprint 3)

Two consumption modes share the same `Stream` data structure (a sorted map of
beat-offset → events) but have distinct contracts:

- **Offline**: fully materialized sorted map; `(realize stream)` forces complete
  materialization before playback. Iteration is deterministic and look-ahead is
  unbounded.
- **Real-time**: lazy seq pulled event-by-event by the scheduler in virtual time
  order. The `Playable` protocol's `play!` implementation pulls the next event,
  schedules it, then advances.

The `Playable` protocol is the unifying abstraction; both modes implement it. The
caller does not need to know which mode is active.

---

## Q7 — Ableton Link: timing model integration

**R&R reference**: §13 (formerly open question #5) — **RESOLVED; see §15 of R&R doc**

**Summary of resolution:**

Ableton Link is elevated from an open question to a first-class requirement.

**Integration approach**: Carabiner (§15.2 Option A) for the initial implementation.
Carabiner is a pre-compiled standalone process (MIT, by James Elliott / Deep Symmetry)
that wraps the Link C++ SDK and exposes it over a local TCP socket using a simple
newline-delimited protocol. No JNI/JNA required. This is the same approach used by
Sonic Pi and Beat Link Trigger in production. A direct JNA binding (§15.2 Option B)
is designed as a future drop-in replacement.

**Key design decisions:**

1. **`sleep!` delegates to Link when active** (§15.3). When Link is running,
   `sleep!` computes its wall-time target by calling `link/time-at-beat` rather than
   using the local BPM formula. This is a single branch in `sleep!`; no structural
   change to the virtual time model is required.

2. **BPM changes propagate implicitly** (§15.5). Because threads always query Link's
   `timeAtBeat` in `sleep!`, a tempo change from another peer takes effect on the
   next `sleep!` call with no explicit broadcast. Thread-local `*bpm*` becomes a
   display-only value updated asynchronously from a Carabiner listener.

3. **Phase-quantized launch** (§15.4). When a `live-loop` starts with Link active, it
   parks until the next quantum boundary (default quantum = 4 beats = 1 bar). This
   ensures loops always join the session on the downbeat.

4. **Start/stop sync** (§15.6) is supported as an opt-in. When enabled, all peers
   start and stop together; `live-loop` pauses at its next `sleep!` boundary when the
   Link transport is stopped.

5. **Accuracy**: < 1ms on wired LAN (GHOST protocol). JVM GC overruns cause late
   arrival at a beat boundary; the thread skips to the next quantum rather than
   catching up, maintaining phase alignment at phrase level.

**Namespace**: `cljseq.link` — see §15.8 for full API sketch.

**Implementation phasing**: 4 phases post-core (§15.10), starting with Carabiner
client and beat-timeline integration, ending with start/stop sync.

---

## Q8 — Control tree node type system: richness vs. simplicity — **RESOLVED**

**R&R reference**: §16.2

**Resolution** (Sprint 3)

Minimal initial type set, stored as metadata in the persistent map node alongside
the value (consistent with Q47's atom model):

| Type | Description |
|---|---|
| `:int` | Integer; range-constrained optional |
| `:float` | Floating-point; range-constrained optional |
| `:bool` | Boolean |
| `:keyword` | Arbitrary keyword value |
| `:enum` | Keyword from a declared finite set (scales, modes, chord types) |
| `:data` | Opaque Clojure value; no coercion or serialization guarantee |

**Deferred**: `:pitch`, `:harmony`, `:pattern` — high-value but complex; deferred
to a second pass when the type system has proven itself in practice.

**Security boundary**: `:fn` nodes are *callable* but never *settable* via MIDI or
OSC. Method nodes are declared in Clojure only. This is a hard boundary: remote code
execution via an OSC message is not a supported operation.

**`:data` escape hatch**: OSC or MIDI can set a `:data` node only if an explicit
transform function is declared at binding time. Without a transform, `:data` nodes
are read-only from external protocols.

---

## Q9 — Conflict resolution when multiple sources control the same node — **RESOLVED**

**R&R reference**: §16.3

**Resolution** (Sprint 3)

Fail-fast at `bind!` time: `ctrl/bind!` raises an error if a node already has a
conflicting binding. Conflicts are resolved explicitly via `:priority`:

```clojure
(ctrl/bind! [:global/bpm] link-source)               ; succeeds; Link owns :bpm
(ctrl/bind! [:global/bpm] midi-cc-72)                ; error — already bound
(ctrl/bind! [:global/bpm] midi-cc-72 :priority 10)   ; succeeds — higher priority wins
```

Default priority ordering (lower number = higher priority):

| Source | Default priority |
|---|---|
| Clojure code (`ctrl/set!`) | 0 (always wins) |
| Ableton Link | 10 |
| MIDI | 20 |
| OSC | 30 |

Catching conflicts at binding time prevents BPM fights and similar live-performance
hazards. Last-write-wins is not supported as a policy — it is the failure mode this
design prevents.

---

## Q10 — `mod-route` directionality: input node → output node → sidecar — **RESOLVED**

**R&R reference**: §16.8

**Resolution** (Sprint 3)

Two write operations with distinct semantics:

| Operation | Updates atom? | Fires sidecar? | Use when |
|---|---|---|---|
| `ctrl/set!` | Yes | No | Logical update: change displayed state, feed watches |
| `ctrl/send!` | Yes | Yes | Physical update: send MIDI CC / OSC to hardware |

`mod-route` uses `ctrl/send!` internally. Patch application (Q48) fires `ctrl/send!`
for every changed node that has a physical binding, synchronizing connected hardware
automatically.

A device node's value in the atom represents the *last sent* value. This can diverge
from actual hardware state if the hardware was adjusted manually (e.g. turning a knob
on a Prophet). Reconciliation (SysEx readback → `ctrl/set!` without send) is an
explicit user action, not an automatic sync.

---

## Q11 — `deflive-loop` vs. existing `live-loop`: migration path — **RESOLVED**

**R&R reference**: §16.4

**Resolution** (Sprint 2)

`live-loop` is syntactic sugar for `deflive-loop` with an auto-generated path and an
empty parameter map:

```clojure
;; These two forms are equivalent:
(live-loop :bass
  (play! :C2 :quarter)
  (sleep! :quarter))

(deflive-loop "/cljseq/loops/bass" {}
  (play! :C2 :quarter)
  (sleep! :quarter))
```

The loop is always registered in the control tree under `/cljseq/loops/<name>`.
Plain `live-loop` loops have no declared controllable parameters; to expose parameters
for external control, use `deflive-loop` with an explicit parameter map.

This gives one mental model with two ergonomic levels: `live-loop` for quick REPL
sketches, `deflive-loop` for production loops that expose parameters to the control
tree. There is no separate registration path — both forms share the same underlying
loop registry and tree node lifecycle.

**Original issue**

§16.4 introduced `deflive-loop` as tree-aware while §5.3 used plain `live-loop`,
creating two registration models. Resolved by making `live-loop` a macro alias that
always registers in the tree.

---

## Q12 — Control-plane vs. data-plane OSC: how to distinguish at the wire level? — **RESOLVED**

**R&R reference**: §17.1

**Resolution** (Sprint 3)

**Primary**: OSC bundle timestamp semantics. An immediate-timestamp bundle
(`0x00000000 0x00000001`) is control-plane (no scheduling intent); a bundle with a
future NTP timestamp is data-plane (scheduling intent). This is the semantically
correct distinction — OSC bundles were designed for exactly this.

**Fallback**: two ports. Control-plane OSC on port 57121 (cljseq default);
data-plane OSC on a user-configured port (default 57110, the SuperCollider default).
Port separation is provided for hardware controllers that cannot send OSC bundles.

**Address prefix** (`/cljseq/**` = control-plane) is documented convention for
human readers, not an enforcement mechanism. Enforcement is by port or bundle
timestamp; address prefix is unreliable in the face of user-defined tree paths
outside `/cljseq/`.

| Mechanism | Primary? | Fallback? |
|---|---|---|
| Bundle immediate timestamp | Yes | — |
| Two ports (57121 / user-configured) | — | Yes |
| Address prefix `/cljseq/**` | No | Convention only |

---

## Q13 — MCP `repl/eval` authorization: per-session or capability-based? — **RESOLVED**

**R&R reference**: §18.3, §18.7

**Resolution** (Sprint 3)

Per-session authorization: the user confirms once at session start. Revoke by
restarting the nREPL session — the same model as Claude Code.

- **Namespace whitelist**: all `repl/eval` submissions are prepended with
  `(in-ns 'cljseq.user)` and may only refer to `cljseq.*` namespaces unless the
  user explicitly grants full JVM access via a session flag.
- **Audit log**: every eval is appended to a session transcript file
  (`~/.cljseq/sessions/<timestamp>.log`) for auditability.
- **Sandboxing**: deferred — revisit after evaluating real-world usage patterns.
  The namespace whitelist provides a practical safety boundary for now.

---

## Q14 — HTML rendering of the tree: server-side or client-side? — **RESOLVED**

**R&R reference**: §17.7

**Resolution** (Sprint 3)

Start with SSR + HTMX: Hiccup templates in the Ring handler; HTMX `hx-ws` for
WebSocket-driven live value updates. No JavaScript build step, no JS framework,
fully functional in any browser. The REST API is built regardless.

Add a lightweight SPA only if richer views (piano roll visualization, waveform
display) prove necessary — and only for those specific views, not as a wholesale
replacement.

---

## Q15 — `libcljseq-rt` boundary: what belongs in the shared library? — **RESOLVED**

**R&R reference**: §3.5, §19

**Resolution** (Sprint 2)

The dependency graph is `cljseq-sidecar → libcljseq-rt ← cljseq-audio`. The boundary
is: shared = protocol + interfaces + infrastructure; binary-local = implementations +
backends.

| Component | Location | Rationale |
|---|---|---|
| IPC framing/transport | `libcljseq-rt` | Both processes talk to the JVM |
| OSC codec | `libcljseq-rt` | Both send/receive OSC |
| `SynthTarget` abstract interface | `libcljseq-rt` | Interface only; impls in binaries |
| `LinkEngine` | `libcljseq-rt` | Both processes need beat-clock access |
| Asio I/O context helpers | `libcljseq-rt` | Shared async infrastructure |
| Lock-free queue primitives | `libcljseq-rt` | Shared IPC plumbing |
| Common logging | `libcljseq-rt` | Both processes emit logs |
| `MidiTarget`, `ClapTarget` impls | Binary-local | Single-process concerns |
| RtMidi, RtAudio | Binary-local | MIDI in sidecar; audio in cljseq-audio only |
| CLAP plugin loader | Binary-local | `cljseq-audio` only |
| libpd | Binary-local | `cljseq-audio` only |

**Key invariant**: nothing in `libcljseq-rt` pulls in RtMidi, RtAudio, CLAP, or libpd
headers. The library compiles without any synthesis or I/O backend. Enforce at Phase 0
by auditing the CMake target's transitive dependencies.

**Future layering note**: as the system grows, further library extraction may be
warranted — for example, separating the OSC codec, IPC framing, or `LinkEngine` into
their own targets. This is deferred to a later design sprint or post-Phase-0
refactoring when the actual dependency graph is visible in code.

---

## Q16 — Process topology: JVM ↔ N processes, or sidecar-as-router? — **RESOLVED**

**R&R reference**: §3.5

**Resolution** (Sprint 3)

**Option A**: direct JVM-to-process IPC connections.

```
JVM ──IPC──► cljseq-sidecar
    ──IPC──► cljseq-audio-0
    ──IPC──► cljseq-audio-1
```

Event routing happens in the JVM. A note event for Surge XT goes directly to
`cljseq-audio-0`; a note event for a Prophet goes to `cljseq-sidecar`. The routing
key (control tree target prefix → process handle) lives in a Clojure map in
`cljseq.sidecar`. Process lifecycle management (spawn, monitor, restart) is handled
by `cljseq.sidecar` for all N processes.

Option B (sidecar-as-router) was rejected: it adds a routing hop for audio-destined
events and makes `cljseq-sidecar` a more complex process with no architectural benefit.

---

## Q17 — CLAP parameter count: eager vs. lazy tree registration? — **RESOLVED**

**R&R reference**: §19.7

**Resolution** (Sprint 3)

The sidecar holds the complete parameter map for each loaded plugin. The JVM
control tree is a sparse projection, populated lazily on demand:

- `ctrl/ls` on a device node triggers a bulk-fetch of the full parameter list from
  the sidecar and populates the tree.
- `(ctrl/register-all-params! target)` forces eager registration when full
  MIDI/OSC addressability is required from the start.
- Individual parameter access always works regardless of registration state — the
  sidecar is the authoritative store.

This keeps the tree navigable (a 600-parameter Surge XT subtree is only populated
when the user asks for it) while preserving full addressability on demand.

---

## Q18 — Pd patch hot-swap: audio continuity during patch reload? — **RESOLVED**

**R&R reference**: §19.8

**Resolution** (Sprint 3)

Pool of ≤2 `PdTarget` instances per slot:

1. `(ctrl/preload-patch! slot path)` — loads the new patch into the standby instance
   while the current instance continues playing.
2. `(ctrl/swap-patch! slot)` — promotes the standby to active atomically at the
   next audio buffer boundary. No audible gap.

The prior active instance becomes the new standby (or is released if memory
pressure demands it).

---

## Q19 — `cljseq-audio` and Link: second peer on the same machine? — **RESOLVED**

**R&R reference**: §3.5, §19.9

**Resolution** (Sprint 3)

Measure beat-time drift between the JVM's Carabiner connection and `cljseq-audio`'s
direct Link instance empirically. Acceptable limit: < 100µs on a wired LAN.

If drift exceeds the limit: use a single Link peer in the sidecar; forward Link
beat-time to `cljseq-audio` via IPC rather than having `cljseq-audio` join the
session independently. This is a Phase 0 measurement task.

---

## Q20 — Music21 sidecar: synchronous vs. async from Clojure? — **RESOLVED**

**R&R reference**: §20.3, §20.6

**Resolution** (Sprint 2)

`cljseq.m21` returns Clojure promises. Two safe usage patterns exist without
any API change:

1. **Pre-fetch before the loop** — call `m21/` outside, deref, close over the value:
   ```clojure
   (let [score @(m21/parse-corpus "bach/bwv66.6")]
     (live-loop :bach
       (play! (next-note score))
       (sleep! :quarter)))
   ```

2. **Fire-and-forget via `m21/eventually!`** — fires the RPC, swaps an atom when
   resolved; the loop observes the atom:
   ```clojure
   (def current-key (atom :C-major))
   (m21/eventually! current-key m21/analyze-key notes)
   (live-loop :adaptive
     (play! :C :key @current-key)
     (sleep! :quarter))
   ```

`m21/eventually!` is part of the initial `cljseq.m21` API.

**Documentation constraint**: never deref an `m21/` promise inside a running
`live-loop`. Blocking on a slow call (up to 500ms for corpus parsing) will cause the
loop to miss its `sync!` window, breaking virtual-time coherence.

**Enforcement** (deferred): a `^:dynamic *in-loop-context*` var is reserved in
`cljseq.loop`. It will be bound to `true` inside every `live-loop` body. The `m21/`
deref path — and any other subsystem where blocking inside a loop is dangerous — can
check this var and throw an informative error. The check is additive and non-breaking
when wired in; deferral keeps the initial implementation simple while the constraint
is validated in practice. Other subsystems (e.g., blocking I/O, slow control-tree
queries) may use the same hint.

---

## Q21 — Score serialization: canonical representation across the IPC boundary? — **RESOLVED**

**R&R reference**: §20.5

**Resolution** (Sprint 3)

Edge case rules for the JSON wire format (implemented in Python `_note_to_json`):

| Case | Rule |
|---|---|
| Enharmonic equivalents | Preserve Music21 spelling (`C#` stays `C#`; not rewritten to `Db`) |
| Microtonal pitches | Carry `cents_offset` field alongside MIDI note number |
| Tied notes | Merge into single note (flatten ties; sum durations) |
| Grace notes | Omit (zero-duration events cause scheduler undefined behaviour) |
| Chords | Expand to simultaneous notes with identical onset timestamps |

---

## Q22 — In-process Music21: Jython, GraalPy, or JPype? — **RESOLVED**

**R&R reference**: §20.3

**Resolution** (Sprint 3)

Retain the subprocess design. 1–5ms IPC overhead per call is acceptable for all
current use cases. Batch high-frequency calls to amortize overhead (e.g. parse a
full corpus piece once, not per-note). Revisit only if profiling identifies a
concrete bottleneck.

---

## Q23 — Overlap between cljseq pitch/scale types and Music21? — **RESOLVED**

**R&R reference**: §20.8

**Resolution** (Sprint 3)

Division of labour: use cljseq native operations for hot-path work (pitch
arithmetic, scale lookup, MIDI conversion — microseconds); delegate musicological
depth to Music21 via `cljseq.m21` (corpus analysis, key detection, voice-leading —
milliseconds). The §20.8 table is the authoritative mapping; no duplication of
implementations.

---

## Q24 — Music21 corpus licensing: distribution policy? — **RESOLVED**

**R&R reference**: §20.10

**Resolution** (Sprint 3)

cljseq does not redistribute any Music21 corpus files. `m21/search-corpus` and
all corpus access functions operate on the user's locally installed Music21
installation at runtime. Distribution policy: no bundling.

---

## Q25 — Microtonal pitch representation for maqamat and other non-12-TET systems — **RESOLVED**

**R&R reference**: §21.2, §7

**Resolution** (Sprint 2)

Three strategies are used, selected by target type:

| Strategy | How it works | Polyphony | Targets |
|---|---|---|---|
| **Cents offset** | `{:pitch/midi 63 :pitch/cents 50}` — MIDI note + pitch bend | 1 microtonal note per channel | All MIDI targets |
| **MPE** | Each note on its own channel with per-channel pitch bend | 15–30 simultaneous microtonal notes | MPE-capable synths |
| **CLAP tuning table** | Floating-point Hz-per-key table via `CLAP_EXT_TUNING` | Full polyphony, no pitch-bend tricks | CLAP plugins only |

**Target routing**:
- MIDI targets: cents-offset for monophonic/low-polyphony; MPE for polyphonic
  microtonality. Add `:pitch/mpe-channel` to the pitch type.
- CLAP targets: build a `CLAP_EXT_TUNING` table from the `.scl` file; `cljseq-audio`
  sends the tuning table to the plugin on load (IPC type `0x7C`, added to §3.5's
  message table).

**`Pitch` type**: `:pitch/cents` is an **optional field** in `cljseq.pitch`'s
canonical representation. Absent means 0 cents offset. This keeps the common 12-TET
case clean at the REPL (`{:pitch/midi 60}` rather than `{:pitch/midi 60 :pitch/cents 0}`);
microtonal code only encounters `:pitch/cents` when it is actually needed. The wire
format (§20.5) already includes `cents_offset`.

---

## Q26 — Gamelan tuning: per-ensemble files vs. generalized scale parameters? — **RESOLVED**

**R&R reference**: §21.3.1, §7

**Resolution** (Sprint 3)

The Scala `.scl` / `.kbm` system (§7) handles both reference approximations and
measured per-ensemble tunings without gamelan-specific code. Ship reference tuning
files in `resources/scales/gamelan/`; allow user files in `~/.cljseq/scales/`.
`(tuning/load! :my-gamelan/pelog)` searches the user directory first, then bundled
resources.

---

## Q27 — Non-Western corpus access via Music21's VirtualCorpus? — **RESOLVED**

**R&R reference**: §20.7, §21.7

**Resolution** (Sprint 3)

`(m21/register-corpus-path! dir)` wraps Music21's `corpus.addPath()`. Users
supply their own non-Western corpus files; cljseq ships none. Documentation
explains the mechanism and points to known public domain sources (e.g. IRCAM
Multimedia Library, Humdrum toolkit resources).

---

## Q28 — Colotomic structure: special API or just `live-loop` with `sleep!`? — **RESOLVED**

**R&R reference**: §21.3.2

**Resolution** (Sprint 3)

No special API. Colotomic layers are polyrhythmic `live-loop`s with a shared irama
ratio held in an atom or control tree node. Loops read the ratio on each iteration.
Phase-quantize irama changes via `at-sync!` to the next gong stroke boundary.

---

## Q29 — `param-loop` and `live-loop`: unification via rhythmic voice abstraction? — **RESOLVED**

**R&R reference**: §22.7

**Resolution** (Sprint 2)

`param-loop` is split by mode:

| Mode | Scheduling | Expansion |
|---|---|---|
| Discrete rhythm spec | Virtual-time `sleep!` | Expands to `live-loop` |
| `:rhythm :continuous` (LFO) | Wall-clock `LockSupport/parkNanos` at 1ms | Separate high-resolution thread |

**Discrete mode**: `param-loop` with a rhythm spec expands to a `live-loop` body that
iterates over the rhythm and calls `ctrl/set!` on each hit. Same virtual-time semantics,
same redefinition boundary, same tree registration.

**Continuous mode**: runs on a dedicated wall-clock thread outside the virtual-time
model. This is necessary because LFO modulation requires sub-millisecond resolution
that virtual-time `sleep!` cannot provide.

**Redefinition in continuous mode**: swap at the next tick (~1ms), not at a period
boundary. "Period boundary" is ill-defined for a continuous waveform; phase-continuous
swap at next tick is consistent with the high-resolution nature of the loop and
preserves waveform continuity.

**Control tree**: both modes register under `/cljseq/loops/<name>` identically.
The scheduling implementation differs but the tree node interface — start, stop,
inspect, parameter control — is uniform.

---

## Q30 — Continuous LFO scheduling: resolution, rate, and sidecar handoff?

**R&R reference**: §22.3, §22.2

**The issue**

A continuous LFO needs to send value updates at regular intervals. For a smooth filter
sweep perceptible to a human listener, update rates of 20–100 Hz are typical.
At 48kHz, sample-accurate CLAP automation would allow 48,000 updates per second, but
that would saturate the IPC channel. For MIDI CC, the MIDI spec recommends no faster
than 31.25kHz but practical controllers send 100–1000 CC messages/second.

Options for update rate:
- **Fixed 50 Hz (20ms)**: Good for MIDI CC; smooth enough for most parameter changes
- **Fixed 200 Hz (5ms)**: Smooth for most modulation; higher IPC load
- **Per-target rate**: Different rates for different targets (e.g., 50 Hz for MIDI CC,
  200 Hz for OSC, "per buffer" for CLAP)

For CLAP targets, the right model is: the LFO value function is called once per audio
buffer with the buffer's beat-time range, generating one sample-accurate value per
buffer. The sidecar calls the value function and injects a CLAP automation event with
the correct sample offset — no fixed-rate JVM polling needed for CLAP.

**Recommendation**

1. Default LFO update rate: 100 Hz (10ms intervals) — configurable per `param-loop`
   via `:rate hz`.
2. For CLAP targets: compute one value per buffer (the IPC type `0x76` "parameter
   automation" message carries both the value and the sample offset). No high-frequency
   JVM polling.
3. For MIDI CC / OSC: 100 Hz polling from the JVM LFO thread, batched and sent to
   the sidecar via the existing IPC channel.

---

## Q31 — Arpeggiation library format and corpus extraction pipeline? — **RESOLVED**

**R&R reference**: §22.5

**Resolution** (Sprint 3)

EDN pattern maps with `:order :rhythm :dur :source` fields. Ship pre-extracted
patterns in `resources/arpeggios/`. Extraction pipeline uses Music21's
`analysis.elements`; minimum corpus frequency of 50 occurrences filters noise.
The `:source` field records the corpus work each pattern was extracted from.

---

## Q32 — Polyrhythmic phase coherence across loop restarts and tempo changes?

**R&R reference**: §22.4, §15

**The issue**

When multiple `live-loop`s run with different periods, their phase relationship is
determined by when they were started. If a loop is redefined (a common live-coding
operation), it restarts with a potentially arbitrary phase relative to other loops.

Two problems:
1. **Restart phase**: redefining `:melody` (period 4) mid-way through a `:filter`
   loop (period 5) breaks the intentional polyrhythmic relationship.
2. **Tempo change via Link**: if the BPM changes, the beat grid shifts. Loops that
   use virtual beats are unaffected (they count beats, not wall time), but LFO loops
   that use wall-clock rates (for MIDI CC output) will drift.

**Exploration path**

1. Loop restarts: provide `:restart-on-bar` option (restarts at the next multiple of
   `:period` beats from beat 1 of the Link session) to maintain phase alignment.
2. Tempo changes: LFO rate in Hz is always derived from BPM (`freq = beats/period *
   BPM/60`). When BPM changes, recalculate the interval. The LFO scheduler reads
   the current BPM from `LinkEngine::captureAppSessionState()` on each tick, so the
   rate self-corrects without restart.
3. Global phase reference: `beat-1` of the Link session (timestamp when the session
   started) is the global phase anchor. All period-based loops compute their current
   phase as `(current-beat % period) / period`, which is stable across restarts.

---

## Q39 — Branch regeneration trigger: pure function vs. reactive watch? — **RESOLVED**

**R&R reference**: §24.2

**Resolution** (Sprint 3)

Reactive watch with filter predicate. The `deffractal` macro installs a watch on
the trunk; regeneration fires only when fields matching the filter change:

```clojure
(deffractal :my-seq :regen-filter #{:pitch :gate} ...)
```

`(fractal/freeze! name)` disables regeneration temporarily for multi-step edits;
`(fractal/thaw! name)` re-enables it. Prevents surprise regeneration mid-edit.

Note: the cljseq implementation uses the name `fractal` throughout (namespace
`cljseq.fractal`, macro `deffractal`). The Teenage Engineering Bloom sequencer is
the design inspiration; "Bloom" is retained only as an attribution label in §24 of
the R&R.

---

## Q40 — Ornament timing: how do ornament sub-steps interact with virtual time? — **RESOLVED**

**R&R reference**: §24.5

**Resolution** (Sprint 3)

Ornaments are pure functions returning a sequence of sub-step maps
`{:pitch p :dur d :vel v}`. The caller iterates the sequence with ordinary
`sleep!` calls. Sub-step durations sum to the parent step's duration, so the
virtual time budget is consumed correctly. No special scheduler support is needed.

---

## Q41 — Unified step map: is it a protocol, a record, or a plain map? — **RESOLVED**

**R&R reference**: §24.10

**Resolution** (Sprint 3)

Plain Clojure map with namespace-qualified keys. `clojure.spec.alpha/def` provides
validation. Maps are printable, serializable, REPL-friendly, and require no
import. Namespace qualification (`:step/pitch`, `:step/dur`, `:step/gate`) avoids
key collisions with user data.

---

## Q42 — `play-bloom-step!` vs. extending `play!` with step-map overload? — **RESOLVED**

**R&R reference**: §24.6

**Resolution** (Sprint 3)

Overload `play!` to accept a step map as its argument. `play!` dispatches on the
argument type: a step map triggers note, ratchet, slew, and mod fields as a
coordinated bundle. Keeps the API surface small; no separate `play-fractal-step!`
function needed.

---

## Q43 — Path navigation: index sequence or named path strategy? — **RESOLVED**

**R&R reference**: §24.3

**Resolution** (Sprint 3)

Support all three path forms via dispatch in `(fractal/path branches :path p)`:

- **Integer**: firmware-compatible path generated from binary tree encoding
  (e.g. `3` → left-right-left in a binary tree)
- **Named strategy**: `:leftmost`, `:rightmost`, `:random`, `:depth-first`
- **Explicit vector**: `[0 1 0]` for direct branch address

All three are interchangeable at the call site.

---

## Q34 — DEJA VU ring buffer: identity across live-loop redefinitions? — **RESOLVED**

**R&R reference**: §23.6, §23.9

**Resolution** (Sprint 3)

Declare the sequence source with `defonce` outside the loop body. `defonce` survives
REPL redefinitions and namespace reloads, preserving the ring buffer's accumulated
history. The `defstochastic` macro wraps this automatically — users do not need to think
about it for normal usage.

---

## Q35 — Jitter and virtual-time coherence: should jitter perturb virtual time or wall-clock time? — **RESOLVED**

**R&R reference**: §23.3, §3.1

**Resolution** (Sprint 2)

**Model A**: virtual time stays clean; jitter is applied only to the IPC event
`time_ns` at the sidecar delivery boundary.

```
*virtual-time*  ──────●──────────●──────────●──  (clean beat spine)
wall-clock IPC  ──────●────●──────────●───●────  (jittered delivery times)
```

- `sleep!` advances `*virtual-time*` by the exact intended duration — no perturbation
- IPC event timestamp = `nominal_time_ns + jitter_offset_ns`, drawn per-event
- `sync!` coordination sees the clean beat; phase coherence across loops is preserved
- The listener hears humanized timing; coordination is unaffected

Model B (perturbing virtual time) was rejected: jitter would accumulate in the beat
counter, desynchronizing `sync!` from other loops and making humanization a
correctness hazard.

**Connection to timing modulation** (see Q46): swing, groove, and humanization are
time-dimension modulators — they produce a `time_ns` offset rather than a pitch or
velocity value. This is consistent with Model A: all timing perturbation operates
at the IPC delivery layer, never on `*virtual-time*`. See Q46 for the full design
question on how swing/humanize fit within the `ITemporalValue` modulation framework.

---

## Q36 — Weighted scale degrees: extension of IntervalNetwork or separate type? — **RESOLVED**

**R&R reference**: §23.5, §4

**Resolution** (Sprint 3)

Add an optional `:scale/weights` map to the existing scale map — no new type.
Interval structure says *which* pitches are in the scale; weights say *how likely*
each degree is to be chosen. Default when `:scale/weights` is absent: uniform
weights over all degrees.

```clojure
{:scale/type    :major
 :scale/root    :C
 :scale/weights {0 1.0, 2 0.4, 4 0.8, 5 0.5, 7 0.9, 9 0.4, 11 0.2}}
```

---

## Q37 — Correlated sequences: how is correlation implemented without shared mutable state? — **RESOLVED**

**R&R reference**: §23.7

**Resolution** (Sprint 3)

`promise`-per-step for simultaneously-clocked channels: the first channel to
advance on a given step computes the parent random draw and delivers the promise;
all other channels deref the same promise. For independently-clocked channels,
correlation is approximate (no shared step counter).

Per-channel value:
```clojure
(lerp parent-draw (gen-fn) (- 1 correlation))
```
where `gen-fn` is an independent per-channel draw and `lerp` blends toward the
parent at high correlation values.

---

## Q38 — `stochastic` context in the control tree: auto-registration? — **RESOLVED**

**R&R reference**: §23.8, §16

**Resolution** (Sprint 3)

Auto-register all T/X parameters as control tree nodes at
`/cljseq/stochastic/<name>/` when the `defstochastic` macro is evaluated. Makes all
parameters MIDI-CC-bindable, OSC-addressable, and MCP-accessible without any
explicit registration call. Consistent with `deflive-loop` auto-registration
behaviour (Q11).

---

## Q33 — nREPL integration: resolved — **RESOLVED**

**R&R reference**: §13.2

**Resolution** (Sprint 3)

Standard nREPL is the developer interface; no custom nREPL ops required for core
functionality. The control tree is the canonical state model. Optional
`cljseq-nrepl` middleware for editor-side parameter display and loop inspection can
be added later as a separate library without affecting the core.

---

## Q44 — Unified temporal-value abstraction: do clocks and modulators share a protocol? — **RESOLVED**

**R&R reference**: §3 (virtual time), §16 (control tree / LFO binding), §22 (param-loop),
§23 (Stochastic, Marbles-Inspired); informed by Sprint 1 design research on Maths/PoliMATHS/Blinds
(modulator vocabulary) and Pam's Pro Workout / Tempi / Wogglebug (clock vocabulary)

**Resolution** (Sprint 2)

**Option A — single `ITemporalValue` protocol**. All time-varying signals implement
one protocol; role and routing are determined at the binding site, not in the type.

```clojure
(defprotocol ITemporalValue
  (sample    [v beat])   ; value at this beat position
  (next-edge [v beat]))  ; beat of next 0→1 transition, or nil if continuous
```

Every time-varying entity in cljseq implements this protocol:

| Signal | Output | Routing |
|---|---|---|
| Clock / gate | `{0, 1}` | Scheduler watches for 0→1 edges |
| LFO / envelope / CV | `ℝ` | Sampled at `watch!` resolution; bound to parameter |
| Stepped / smooth random | `ℝ` | Sampled; value changes at clock edges |
| Probability gate | `{0, 1}` stochastic | Edge fires at probability P |
| Swing / humanize / quantize | `Long` (ns offset) | Routed to `time_ns` at IPC delivery boundary (see Q46) |

**Conceptual basis**: this is identical to VCV Rack's signal model (untyped `float`
cables; role is a matter of patching) and SuperCollider's UGen model (`ar`/`kr` is
a rate annotation, not a type). Two established, well-understood systems converge on
the same answer: production and interpretation of a signal are orthogonal.

**Option B** (separate `IClock`/`IModulator` protocols) was rejected: explicit
coercion at every boundary accumulates API friction, and with Q46 timing modulators
in scope it would require a third protocol with no architectural benefit.

**Option C** (thin role wrappers over `ITemporalValue`) is deferred by YAGNI. If
profiling demonstrates that scheduling performance requires explicit edge-detection
or interpolation policy annotations, Option C wrappers can be introduced without
breaking any existing code. VCV Rack's production codebase demonstrates that untyped
signals scale to thousands of concurrent signal paths without performance annotations.

**Composition invariant**: the following must work without coercion:
```clojure
(clock-div 4 (lfo :rate 0.1))                       ; modulator as clock source
(ctrl/bind! [:filter/cutoff] (lfo :rate 0.25))      ; modulator to parameter
(timing/bind! (swing :amount 0.55))                 ; timing modulator to time_ns
```

These three compositions are the acceptance criteria for the implementation spike.

**Connection to Q46**: timing modulators (swing, humanize, groove, quantize) are
`ITemporalValue` instances whose output is a nanosecond offset routed to `time_ns`
at the IPC delivery boundary. They are not a special case — they are `ITemporalValue`
with a different binding target.

---

## Q45 — Parts vs. tracks: what is the unifying voice-organization abstraction? — **RESOLVED**

**R&R reference**: §2.3 (NDLR), §2.4 (T-1), §5 (sequencing DSL), §16 (control tree /
`deflive-loop`); informed by Sprint 2 analysis of Elektron Digitakt/Digitone track model

**Resolution** (Sprint 2)

**Abstraction**: `defensemble` / `ensemble`. A named grouping of N voices sharing a
harmonic context, with optional per-voice semantic role annotations.

```clojure
(defensemble :morning-raga
  {:key :D :mode :bhairav}
  (voice :drone  {:role :drone}                        (drone-pattern ...))
  (voice :chords {:role :pad
                  :articulation (strum :dir :desc
                                       :rate 1/32)}    (pad-pattern ...))
  (voice :melody {:role :motif}                        (motif-pattern ...))
  (voice :bass   {}                                    (bass-pattern ...))   ; generic
  (voice :tabla  {}                                    (tabla-pattern ...))) ; fifth voice
```

**Naming rationale**: `ensemble` has no conflicting use in Music21, SuperCollider,
or common DAW terminology. `scene` (Ableton), `section` (Music21 score structure),
and `group` (SuperCollider audio routing) were rejected for collision risk.

**Control tree placement**: ensemble voices register under
`/cljseq/ensembles/<name>/voices/<voice-name>/`. The ensemble is a container node at
`/cljseq/ensembles/<name>/` with shared harmonic context as child nodes:

```
/cljseq/ensembles/morning-raga/
  key        → :D
  mode       → :bhairav
  chord      → :I
  voices/
    drone/   → (subtree for drone live-loop)
    chords/  → (subtree for pad live-loop)
    melody/  → (subtree for motif live-loop)
```

Group operations (`start!`, `stop!`, `arm!`, `chord!`) act on the container node
and propagate to all voices. Ensemble start/stop is phase-quantized to the Link beat.

**Role semantics**: roles define *harmonic content policy*, not temporal articulation.
Role and articulation are orthogonal:

| Role | Harmonic behavior |
|---|---|
| `:drone` | Ignore chord changes; always use root of current key |
| `:pad` | Resolve current chord to its constituent tones |
| `:motif` | Transpose scale-degree indices to current chord |
| `nil` | Receive current chord root as raw context; loop interprets it |

A role determines what `(current-chord)` returns inside the voice body — it does not
prescribe temporal delivery.

**Articulation is separate from role**: strum, block-chord, arpeggiation direction and
rate are `:articulation` parameters on the voice, not baked into `:pad`. The NDLR's
strum parameter is the canonical example: strum spreads chord tones sequentially
across a time window — a temporal articulation concern, not a harmonic content concern.

```clojure
;; role provides the notes; articulation spreads them in time
(voice :chords {:role :pad
                :articulation (strum :dir :desc :rate 1/32)} ...)
```

**Strum direction API**: `:asc` (pitch-ascending, low→high) and `:desc`
(pitch-descending, high→low). Guitar players will recognise these as down-strum
(`:asc`) and up-strum (`:desc`); the API uses pitch-order terms to avoid
instrument-specific connotations. Strum is an ornament on a chord voicing and
belongs in the ornament vocabulary (§24.5); its timing implementation uses the
Q46 `ITemporalValue` delivery offset mechanism.

**Membership**: fixed at definition time. Ad-hoc voice joining is deferred.

**Connection to existing questions**

- Q11 — ensemble voices are `deflive-loop` forms; Q11's alias resolution fits cleanly
- Q29 — `param-loop` voices inside an ensemble are the continuous-modulation
  equivalent of Elektron's LFO tracks
- Q46 — strum articulation uses per-note `time_ns` offsets at the IPC delivery
  boundary, consistent with the timing modulation framework
- Q9 — if two ensembles declare conflicting global keys, the conflict policy applies

---

## Q46 — Timing modulation: is swing/humanization a time-dimension `ITemporalValue`? — **RESOLVED**

**R&R reference**: §23.3, §22 (param-loop), §3.1 (virtual time); informed by Q35 and Q44 resolutions

**Resolution** (Sprint 2)

Swing, humanize, groove templates, and quantize are all `ITemporalValue` instances
whose output is a nanosecond offset routed to `time_ns` at the IPC delivery boundary.
They are not special cases — they are `Beat → Long` functions bound via `timing/bind!`
rather than `ctrl/bind!`.

**The full family of timing modulators**

| Operation | Behavior | Deterministic? | Output |
|---|---|---|---|
| `swing` | Phase-dependent offset; off-beats pushed late | Yes | `Long` (ns) |
| `humanize` | Bounded random offset per event | No | `Long` (ns) |
| `groove-template` | Step-indexed offset sequence replayed in a loop | Yes | `Long` (ns) |
| `quantize` | Snap onset to nearest grid subdivision | Yes | `Long` (ns) |
| `input-quantize` | Snap incoming MIDI/OSC events to grid at ingestion | Yes | `Long` (ns) |

**Routing: binding site, not type**

```clojure
(ctrl/bind!   [:filter/cutoff] (lfo :rate 0.25))   ; → parameter value
(timing/bind! (swing :amount 0.55))                ; → time_ns offset, all events in scope
(timing/bind! :voice/chords (humanize :amount 5))  ; → time_ns offset, named voice only
```

`timing/bind!` lives in `cljseq.timing`. No type tag on the modulator — the same
`ITemporalValue` instance could be used as a parameter modulator or timing modulator
depending on binding target.

**`time_ns` offset type**: plain `Long`; no protocol extension needed. `ITemporalValue`
is `Beat → Value`; the value type is unconstrained. The binding site interprets it.

**Groove templates**: a finite step-indexed EDN sequence of nanosecond offsets,
looped. Implements `ITemporalValue` directly — `(sample groove beat)` returns the
offset for the step at that beat position. Loaded from EDN, consistent with the
data format policy.

```clojure
(def mpc-swing (groove/load "resources/grooves/mpc60-swing66.edn"))
(timing/bind! mpc-swing)
```

**Quantization resolution**: arbitrary rational fractions of a beat. Standard
subdivisions are named constants; user-defined grids are just rationals:

```clojure
(quantize :grid 1/16)   ; nearest 16th note
(quantize :grid 1/12)   ; nearest 8th-note triplet
(quantize :grid 1/32)   ; nearest 32nd note
```

`input-quantize` applies the same mechanism at the MIDI/OSC ingestion point rather
than at IPC delivery.

**Link ordering**: timing modulator offsets are applied *after* Link's `timeAtBeat`
computation, never before:

```
beat position → Link timeAtBeat → nominal_time_ns → (+offset) → final time_ns → IPC
```

The offset is local to this process and invisible to Link's consensus timeline.
Phase alignment across Link peers is preserved.

---

## Q47 — Control tree as persistent atom: undo stack, panic, and tree serial number — **RESOLVED**

**R&R reference**: §16 (control tree), §3.1 (virtual time); raised in Sprint 3 planning

**Resolution** (Sprint 3)

The control tree is a Clojure persistent map held in an atom. All state is co-located
in a single system-state atom:

```clojure
(def ^:private system-state
  (atom {:tree        {}    ; the control tree (persistent map)
         :serial      0     ; structural change counter
         :undo-stack  []    ; bounded ring; depth configurable
         :checkpoints {}})) ; named snapshots, keyed by name
```

**Atom model**: every structural change is a CAS over the atom. Structural sharing
makes snapshots cheap — holding 50 prior states costs little more than 50 root
pointers plus the diff. Real-time parameter writes (`ctrl/set!`) also go through the
atom but are lightweight and not undo targets.

**Undo stack**:
- Bounded ring buffer; default depth **50 structural changes**
- Depth is configurable at startup via `:undo-stack-depth` in the system config map;
  to be revisited as part of a broader configurability sprint
- Holds entries only for undo-target changes (see table below)

| Change type | Undo target? |
|---|---|
| Add/remove loop, ensemble, voice | Yes |
| Load patch | Yes |
| `live-loop` body redefinition | Yes |
| Real-time parameter modulation | No (default) |
| Parameter change annotated `:undoable true` | Yes (opt-in) |

**`(undo!)` timing**: beat-aware by default — queues the revert for the next bar
to avoid mid-iteration inconsistency. `(undo! :now)` is available for emergencies.

**Panic**: `(panic!)` reverts to the most recent named checkpoint at the next loop
boundary without missing a beat. Distinct from undo: jumps to a tagged known-good
state rather than stepping back one change at a time.

```clojure
(checkpoint! :known-good)   ; snapshot current tree state
;; ... live editing ...
(panic!)                    ; revert to :known-good at next loop boundary
```

**Checkpoints** are held in `:checkpoints` alongside the tree, not inside it —
avoiding self-referential snapshots. They are still queryable via OSC at
`/cljseq/checkpoints/`; the handler reads from the system-state atom directly.
Checkpoints are not themselves undo targets.

**Serial number**: increments on structural changes only — not on real-time
parameter writes. Exposed at `/cljseq/tree/serial` via OSC and the web interface.
Allows external tools and peers to detect stale snapshots without diffing the tree.

---

## Q48 — Patch system: named tree states with beat-aware application — **RESOLVED**

**R&R reference**: §16 (control tree); informed by Elektron sequencer patch model,
hardware synth patch/settings separation

**Resolution** (Sprint 3)

A **patch** is a named EDN map describing a partial or full tree state, applied
atomically at a beat boundary.

```clojure
(defpatch :verse
  {:ensembles/main {:chord :I  :mode :dorian}
   :loops/bass     {:octave 2  :velocity 90}})

(patch! :verse)                      ; apply at next bar (default)
(patch! :verse :on :next-phrase)     ; apply at next 8-bar phrase
(patch! :verse :on :now)             ; immediate
```

**Key properties**

- **Partial or full**: patches describe only the nodes they change. Unmentioned nodes
  retain their current values. Mirrors hardware synth settings/patch separation.
- **Beat-aware**: default boundary is next bar (consistent with Elektron). `:on`
  accepts `:next-bar`, `:next-phrase`, `:next-sleep`, `:now`.
- **Sequencer state optional**: patches may include loop step positions as Elektron
  devices do, or omit them as the NDLR does. Both are valid.
- **EDN only**: patches are plain EDN maps. No `live-loop` body code in patches —
  serializing Clojure functions is fragile, namespace-sensitive, and a security
  surface. Loop bodies are changed via the REPL; patches change parameters.
- **Undo target**: patch application goes on the undo stack. `(undo!)` after
  `(patch! :verse)` restores the pre-patch state. The undo stack *is* the patch
  history — no separate tracking needed.

**Physical propagation**: patch application is not limited to the logical tree.
For every node changed by the patch that has a physical binding (MIDI CC route,
OSC send target, CLAP parameter), the patch engine fires `ctrl/send!` after the
CAS completes, pushing the new value out to the bound hardware or software synth.
This means a patch change automatically synchronizes connected instruments — a
hardware synth's filter cutoff, a software synth's oscillator detune, a DAW
channel fader — without the user issuing separate send commands. The `ctrl/set!`
(logical) / `ctrl/send!` (physical) separation from Q10 means patch application
always updates both layers in a single operation.

**`*in-loop-context*` interaction**: patch application fires at a loop boundary,
so loops mid-body at the moment of the CAS see new parameter values on their next
iteration. Covered by the existing full-iteration boundary semantics (Q2); no
special handling required.

**Live set**: an ordered sequence of patches with beat-aware navigation, stored in
the system-state atom:

```clojure
(deflive-set [:intro :verse :chorus :breakdown :outro])
(next-patch!)                          ; advance to next patch at next bar
(prev-patch!)                          ; step back
(goto-patch! :chorus :on :next-phrase) ; jump to named patch
```

`:live-set` (the vector) and `:live-set-pos` (the current index) are held in the
system-state atom alongside the tree, making them queryable via OSC and undoable.

---

## Q49 — Synth-style macros: named input→parameter-set mappings — **RESOLVED**

**R&R reference**: §16 (control tree); inspired by Hydrasynth macro model

**Resolution** (Sprint 3)

**Name**: `morph` — `defmorph` / `cljseq.morph`. Registers at
`/cljseq/morphs/<name>/`. No Clojure collision; clear musical connotation.

**Form**: Clojure macro (not a function). Validates transfer function arity at
compile time, auto-generates the control tree path, produces better error messages.
Consistent with `deflive-loop`, `defensemble`, `deffractal`.

**Transfer functions**: pure functions only in the initial design. Transfer
functions must be serializable into patches (Q48) and transmittable to peers (Q50).
Arbitrary closures with captured state defeat both. All common cases (linear
scaling, exponential curves, threshold gates, polarity inversion) are expressible
as pure functions. Arbitrary code deferred as a `:fn :unsafe` opt-in.

**Single-input morph**:
```clojure
(defmorph :filter-brightness
  {:inputs  {:value [:float 0.0 1.0]}
   :targets [{:path [:filter/cutoff]    :fn #(* % 8000)}
             {:path [:filter/resonance] :fn #(* % 0.7)}
             {:path [:amp/brightness]   :fn identity}]})

(ctrl/bind! [:morphs/filter-brightness :value] (ctrl/get [:osc/fader-1]))
(ctrl/bind! [:morphs/filter-brightness :value] (lfo :rate 0.1 :min 0.0 :max 1.0))
```

**Multi-input morph** (XY pad, joystick):
```clojure
(defmorph :xy-filter
  {:inputs  {:x [:float 0.0 1.0]
             :y [:float 0.0 1.0]}
   :targets [{:path [:filter/cutoff]    :fn (fn [{:keys [x]}]   (* x 8000))}
             {:path [:filter/resonance] :fn (fn [{:keys [y]}]   (* y 0.9))}
             {:path [:filter/drive]     :fn (fn [{:keys [x y]}] (* x y 4.0))}]})

(ctrl/bind! [:morphs/xy-filter :x] (ctrl/get [:osc/xy-pad-x]))
(ctrl/bind! [:morphs/xy-filter :y] (ctrl/get [:osc/xy-pad-y]))
```

Each target function receives the full input map, enabling cross-axis interactions.

**Morph vocabulary library** (future sprint): common pure transfer functions for
morph targets — rectangular-to-polar conversion, exponential/logarithmic curves,
S-curves, dead-zone clipping, vector rotation, polarity inversion. Analogous to
the modulator vocabulary in `cljseq.mod`. Captured as a future design topic in
`design-sprint-3-summary.md`.

**Connection to existing questions**

- Q44 (`ITemporalValue`) — morph inputs can be any `ITemporalValue`; the morph
  fans out the value to multiple binding targets
- Q48 (patches) — morphs are included in patches; their current input values are
  part of the patch state

---

## Q50 — P2P control plane: peer discovery and remote tree composition — **DEFERRED**

**R&R reference**: §15 (Ableton Link), §16 (control tree), §17 (OSC server);
raised as a future capability in Sprint 3 planning

**Status**: Acknowledged and deferred. No design decisions required until the core
control tree implementation is stable. The current design does not close off this
capability:

- OSC addresses are stable and navigable (already the design)
- The tree serial number (Q47) enables peers to detect stale snapshots without
  diffing the full tree
- The tree is fully EDN-serializable (data format policy), enabling peer state
  transmission and merging
- Discovery can use mDNS/Bonjour (same mechanism as AirPlay and embedded devices)
  rather than a custom protocol

**Capability summary** (for a future sprint):
- Peer discovery via mDNS; discovered instances appear as device subtrees at
  `/cljseq/peers/<peer-id>/`
- Conductor pattern: one instance sends patch changes and parameter updates to peers
- Distributed live coding: musicians share control trees across a LAN session

Flag for design when the control tree (§16) implementation is complete and stable.

---

## Q51 — Native C++ dependency strategy — **RESOLVED**

**R&R reference**: build system (§0); raised in Sprint 4 scaffold discussion

**Context**: cljseq's C++ layer (`libcljseq-rt`, `cljseq-sidecar`, `cljseq-audio`) requires
decisions on how to manage native dependencies (OSC, MIDI, Ableton Link, CLAP) across
macOS, Linux, and Windows. System packages (Boost as shipped by e.g. Fedora) apply
distro-specific errata and diverge across platforms; this cannot be assumed consistent.

**Decision**:

1. **Avoid Boost entirely.** C++17 STL covers the value previously obtained from Boost
   (`std::optional`, `std::variant`, `std::filesystem`, `std::string_view`, `std::chrono`,
   `std::span` in C++20). No `find_package(Boost)` will appear in any cljseq CMakeLists.

2. **Standalone Asio for the OSC server.** The OSC server requires an async I/O layer with
   cross-platform POSIX/Windows socket abstraction. Standalone Asio (header-only, no Boost
   dependency, BSL-1.0 licensed) is the right tool:
   - Same API as Boost.Asio, so documentation transfers, but zero Boost dependency
   - Fetched via CMake FetchContent pinned to a specific Asio tag
   - Eliminates the platform divergence concern entirely

3. **Ableton Link**: CMake FetchContent, pinned tag, behind `CLJSEQ_ENABLE_LINK=ON` opt-in
   flag. Link ships its own CMake integration and is a well-behaved FetchContent target.

4. **CLAP headers**: header-only, git submodule pinned to a release tag. No build step.

5. **General rule for any future dependency**: prefer header-only; use FetchContent with
   a pinned version for anything with a build step; document the pinned version and
   rationale in the CMakeLists comment. Never use `find_package` for a library that
   could diverge across platforms via distro packaging.

6. **Packaged releases**: captured as a future sprint topic. Binary packaging (AppImage,
   Homebrew formula, Windows installer) and CPack integration will need to account for
   the FetchContent dependency model. See Future Design Topics.

---

## Q52 — Per-step `:probability` and `:time-shift` on the step map — **RESOLVED**

**R&R reference**: §6 (step map); raised by KeyStep Pro exemplar analysis (Sprint 5)

**Decision**: Both keys are added to the core step map spec as optional keys. `nil`
(or absent) means the default behaviour — the step fires unconditionally and has zero
timing offset.

```clojure
;; Step with both optional keys
{:pitch/midi  64
 :dur/beats   1/4
 :velocity    80
 :gate        0.75
 :probability 0.75   ; fires ~75% of passes; nil = always fire
 :time-shift  1/32}  ; push 1/32 beat late; nil = no offset
```

**`:probability` mechanics**: sampled once per step pass via `(< (rand) probability)`.
No new protocol machinery — it is evaluated in the step scheduler before the event
is dispatched.

**`:time-shift` mechanics**: a beat-fraction offset (rational), additive with any
`timing/bind!` modulators. Positive = late, negative = early. Applied at the same
point as the swing offset: after Link `timeAtBeat`, before IPC dispatch.

**Default cascade**: `:velocity` and `:gate` already inherit from track/global defaults
when absent. `:probability` defaults to `1.0` (unconditional) and `:time-shift`
defaults to `0` when absent.

---

## Q53 — `defflux` read/write head concurrency model — **RESOLVED**

**R&R reference**: §26.5; raised by Moog Labyrinth exemplar analysis (Sprint 5)

**Decision**: Vector of atoms — one Clojure atom per step position.

```clojure
;; Conceptual structure of the defflux step buffer
(vec (repeatedly n #(atom {:value 60 :gate true})))
```

**Rationale**: Independent atoms per position means:
- The read head and write head can operate concurrently without any global lock
- Contention only arises when both heads are at the same position simultaneously —
  this is a rare, musically interesting event (the write head is overwriting a step
  that is currently playing), not a performance problem
- The CORRUPT process uses `swap!` on randomly selected position atoms — no lock needed
- Single-atom-of-vector (`swap!` replacing the whole vector) would create allocation
  pressure at high mutation rates and would serialize all write operations

**Consequence**: `defflux` initializes its buffer as:
```clojure
(defonce buffer (vec (repeatedly steps #(atom default-step))))
```

---

## Q54 — Scale as `ITemporalValue` in the output quantization stage — **RESOLVED**

**R&R reference**: §26.4; raised by Moog Labyrinth exemplar analysis (Sprint 5)

**Decision**: The output stage's scale quantization accepts either a static keyword
or any `ITemporalValue`. When an `ITemporalValue` is provided, it is sampled once
per step output and the resulting keyword is used as the scale.

```clojure
;; Static scale (common case)
(defflux :simple {:scale :dorian ...})

;; Dynamic scale (ITemporalValue; sampled per step)
(defflux :morphing
  {:scale (fn [beat]                           ; ITemporalValue as fn
             (nth [:major :dorian :mixolydian]
                  (mod (long (* beat 0.05)) 3)))
   ...})
```

This extends Q8 (control tree node types) — a `:scale` node can hold a keyword
or an `ITemporalValue`. The same pattern applies wherever a static parameter could
benefit from time-varying behaviour (a general principle, not a special case of `defflux`).

---

## Exploration Priorities

| # | Question | Blocking? | Effort |
|---|---|---|---|
| Q4 | `set!`/`get!` naming | **Yes** — compile-time breakage | Low — pick a name |
| Q1 | `*virtual-time*` atom | Yes — correctness risk | Low — remove atom |
| Q2 | `live-loop` iteration boundary | Yes — core semantic | Medium — study Sonic Pi |
| Q3 | `sync!` late-cue edge case | Yes — phase correctness | Medium — design + test |
| Q11 | `deflive-loop` vs `live-loop` | Yes — API surface | Low — option (a) |
| Q5 | `sched-ahead-time` per-target | No — tunable post-facto | Low — config refactor |
| Q6 | Stream consumption model | No — deferred to §9 impl | High — API design |
| Q8 | Control tree type system | No — needed before §16 impl | Medium — enumerate types |
| Q9 | Multi-source conflict resolution | No — needed before §16 impl | Medium — design policy |
| Q10 | `mod-route` write directionality | No — needed before §16.8 impl | Medium — separate logical/physical |
| Q12 | OSC control/data plane distinction | No — needed before §17 impl | Low — two ports + bundle timestamp |
| Q13 | MCP `repl/eval` authorization | No — needed before §18 impl | Medium — per-session + NS whitelist |
| Q14 | HTML rendering strategy | No — post-§17 | Low — start with SSR+HTMX |
| Q15 | `libcljseq-rt` boundary | **Yes** — needed before Phase 0 | Medium — dependency graph analysis |
| Q16 | Process topology (JVM↔N or sidecar-router) | No — architecture decision | Low — Option A recommended |
| Q17 | CLAP parameter count (eager vs. lazy) | No — needed before §19.7 impl | Low — lazy + bulk-fetch recommended |
| Q18 | Pd patch hot-swap audio continuity | No — needed before §19.8 impl | Medium — pre-load + atomic swap |
| Q19 | `cljseq-audio` Link peer count | No — empirical test needed | Medium — measure drift, consider single-peer |
| Q25 | Maqam microtonal representation | **Yes** — needed before §21 impl | Medium — cents-offset model via §7 |
| Q26 | Gamelan per-ensemble tuning | No — tuning file strategy | Low — .scl per ensemble in resources/ |
| Q27 | Non-Western corpus in Music21 | No — post-§20 | Low — VirtualCorpus extension |
| Q28 | Colotomic layering API | No — post-core | Low — irama as sleep! ratio |
| Q29 | `param-loop` vs `live-loop` API unification | **Yes** — needed before §22 impl | High — rhythmic voice abstraction |
| Q30 | Continuous LFO scheduling resolution | No — performance tuning | Medium — 1ms default tick rate |
| Q31 | Arpeggiation library format | No — needed before corpus extraction | Low — EDN pattern maps |
| Q32 | Polyrhythm coordination across loop restarts | No — correctness question | Medium — phase-lock to Link beat |
| Q33 | nREPL middleware scope | No — post-core feature | Low — resolved: standard nREPL + optional middleware |
| Q39 | Branch regeneration: explicit vs. reactive | No — API design | Low — reactive with filter predicate |
| Q40 | Ornament timing and virtual time | No — correctness question | Low — sub-step rationals |
| Q41 | Unified step map: plain map vs. record vs. spec | No — type model | Low — plain map + spec |
| Q42 | `play-bloom-step!` vs. `play!` overload | No — API design | Low — overload `play!` |
| Q43 | Path navigation: integer vs. keyword vs. vector | No — API design | Low — support all three |
| Q34 | DEJA VU ring buffer identity across redefines | No — correctness question | Low — defonce the source |
| Q35 | Jitter vs. virtual-time clock coherence | **Yes** — needed before §23.3 impl | Medium — perturb wall-clock only |
| Q36 | Weighted scale vs. IntervalNetwork (§4) | No — model question | Medium — extension, not replacement |
| Q37 | Correlated sequences: shared random draw model | No — algorithm design | Medium — parent+perturbation lerp |
| Q38 | `stochastic`/`defstochastic` context in control tree | No — API design | Low — auto-register at startup |
| Q20 | Music21 async from live-loop | **Yes** — usage convention needed | Low — document + `m21/eventually!` helper |
| Q21 | Score serialization canonical form | No — needed before Music21 impl | Low — define rules in Python server |
| Q22 | In-process Music21 (GraalPy) | No — performance question | Low — subprocess is sufficient |
| Q23 | cljseq vs Music21 type overlap | No — architecture decision | Low — §20.8 table is the answer |
| Q24 | Music21 corpus licensing | No — distribution policy | Low — resolved: no bundling |
| Q7 | Link integration | No — post-core feature | High — see §15 |
| Q44 | Unified temporal-value abstraction: `ITemporalValue` protocol | **Yes** — needed before Phase 1 impl | High — design spike recommended |
| Q45 | Parts vs. tracks: unifying voice-organization abstraction (`ensemble`?) | **Yes** — needed before multi-voice DSL impl | Medium — conceptual synthesis + naming decision |
| Q46 | Timing modulation: swing, humanize, groove, quantize as time-dimension `ITemporalValue` | **Yes** — needed before humanization/quantization impl | Medium — connects Q35, Q44, `cljseq.clock` vocabulary |
| Q47 | Control tree as persistent atom: undo stack, panic, serial number | **Yes** — needed before control tree impl | Medium — impacts Q8, Q9, Q10 |
| Q48 | Patch system: named tree states with beat-aware application | **Yes** — needed before control tree impl | Medium — builds on Q47 atom model |
| Q49 | Synth-style morphs: named input→parameter-set mappings | No — needed before §16 morph impl | Medium — surface explicit concept in DSL |
| Q50 | P2P control plane: peer discovery and remote tree composition | No — future capability | Low — ensure design doesn't close it off |
| Q51 | Native C++ dependency strategy: Boost, Asio, Link, CLAP | No — build infrastructure | Medium — resolved: STL + standalone Asio + FetchContent |
| Q52 | Per-step `:probability` and `:time-shift` on step map | No — step map extension | Low — add as optional keys; nil = unconditional/zero-offset |
| Q53 | `defflux` read/write head concurrency model | No — impl detail for `cljseq.flux` | Medium — vector-of-atoms vs. single-atom-of-vector |
| Q54 | Scale as `ITemporalValue` in output quantization stage | No — needed before `defflux` scale morphing | Medium — extends Q8 node types |

---

## Q56 — Time signature API impact: does it affect `sleep!`?

**R&R reference**: §29.3, §29.10

**Status**: Open — needed before Phase 1 `sync!` implementation

**Question**: Does the active time signature affect the semantics of `sleep! 1`, or is
`sleep! 1` always exactly one quarter note?

**Recommendation**: `sleep!` and all beat arithmetic is always quarter-note relative,
matching the MIDI convention. Time signature affects only bar boundaries, bar-level
`sync!`, and display/annotation. This keeps the beat model uniform and avoids the
compound-time BPM ambiguity documented in §29.3.

**Blocking**: Phase 1 `sync!` implementation; bar-level phasor in `cljseq.loop`.

---

## Q57 — PPQN for MIDI Clock output: 24 only, or configurable?

**R&R reference**: §29.4, §29.5, §29.10

**Status**: Open — needed before MIDI Clock output implementation

**Question**: Should the MIDI Clock output be fixed at 24 PPQN (MIDI standard) or
configurable (e.g. 96 PPQN for drum machines that support higher resolution)?

**Recommendation**: Always emit at 24 PPQN for MIDI Clock output — this is what the
MIDI specification defines and all hardware expects. Higher PPQN (96, 960) is exposed
only for SMF export, not for live MIDI Clock messages.

**Blocking**: Phase 1 MIDI Clock output in `cljseq-sidecar`.

---

## Q58 — MTC frame rate: default, drop-frame handling

**R&R reference**: §29.6, §29.10

**Status**: Open — needed before MTC implementation

**Question**: What frame rate should cljseq default to for MTC production? Must
drop-frame be implemented for 29.97 NTSC sync?

**Recommendation**: Default 25 fps (cleanest math at 48 kHz: 1 frame = 1920 samples
exactly). Implement drop-frame correction gated behind explicit config
`{:frame-rate 29.97 :drop-frame true}`. 30 fps non-drop as a secondary default for
pure-audio North American contexts.

**Blocking**: Phase 2 MTC implementation.

---

## Q59 — `sleep!` redesign for tempo correctness (Phase 1 blocker)

**R&R reference**: §29.7, §29.10

**Status**: Resolved — Sprint 8 (2026-03-31)

**Question**: Phase 0's `sleep!` uses `Thread/sleep(beats × ms_per_beat)`, which is
correct only at constant tempo. Under Link (or any tempo change), threads wake at the
wrong wall-clock time.

**Decision**: Implemented in `cljseq.loop/sleep!` and supporting infrastructure:

1. `cljseq.core/start!` anchors the master timeline `{:bpm, :beat0-epoch-ms,
   :beat0-beat}` at the current epoch-ms when the system starts.
2. `cljseq.clock/beat->epoch-ms` converts an absolute beat position to an
   epoch-ms deadline using the timeline anchor — a pure linear mapping valid
   within the current tempo segment.
3. `sleep!` computes `target-beat = *virtual-time* + beats`, calls
   `beat->epoch-ms`, and parks via `LockSupport/parkUntil` (loops on spurious
   wakeup). No accumulated drift at constant tempo.
4. `set-bpm!` rolls the anchor atomically: reads the current beat via
   `epoch-ms->beat`, writes a new `{:bpm, :beat0-epoch-ms, :beat0-beat}` at
   that instant. The one sleep straddling a BPM change fires at the old
   deadline; all subsequent sleeps are correct.
5. `deflive-loop` initialises `*virtual-time*` from `-current-beat` (current
   epoch-ms → beat via timeline) rather than `0N`, so `beat->epoch-ms` is
   always valid relative to the master anchor.

**Remaining**: Early unpark of sleeping threads on BPM change (Q60). Under
Link, `beat->epoch-ms` will delegate to `link.time_at(beat)` — the
`park-until!` loop structure is already compatible with that future change.

---

## Q60 — Tempo change propagation to sleeping loops — **RESOLVED**

**R&R reference**: §29.7, §29.9, §29.10

**Resolution** (Sprint 9)

Three sub-questions, all answered:

1. **Detection**: `set-bpm!` in `cljseq.core` is the mutation point; it atomically
   rolls the timeline anchor and then iterates `[:loops * :thread]`.

2. **Notification**: `set-bpm!` calls `LockSupport/unpark` on every registered loop
   thread immediately after the CAS, waking any thread sleeping in `park-until-beat!`.

3. **Partial sleep semantics**: the loop continues from `*virtual-time*` unchanged —
   same beat position, new wall-clock mapping. `park-until-beat!` re-evaluates
   `beat->epoch-ms(target-beat, new-tl)` after each wakeup and re-parks at the
   revised deadline if still in the future. Virtual time is never rewound.

**Key implementation change** (Sprint 9): `park-until!` was refactored into
`park-until-beat!` which takes a `target-beat` (not a fixed `epoch-ms`) and
recomputes the deadline on every wakeup from the current timeline. This makes BPM
changes instantaneously effective — the sleeping thread does not wait until the old
deadline before noticing the change.

```clojure
;; cljseq.loop/park-until-beat!
(defn- park-until-beat! [^double target-beat]
  (loop []
    (when-let [tl (get-timeline)]
      (let [epoch-ms (clock/beat->epoch-ms target-beat tl)]
        (when (< (System/currentTimeMillis) epoch-ms)
          (LockSupport/parkUntil epoch-ms)
          (recur))))))

;; cljseq.core/set-bpm! — after CAS:
(doseq [[_ entry] (:loops @system-state)]
  (when-let [^Thread t (:thread entry)]
    (LockSupport/unpark t)))
```

Compatible with the Ableton Link sprint: when Link is active, `get-timeline` returns
a Link-backed timeline; `park-until-beat!` will recompute via `link/time-at-beat`
without further changes to the loop structure.

---

## Q61 — Plugin graph DSL: declarative `defrack` vs. imperative API

**R&R reference**: §30.3, §30.10

**Status**: Future sprint — not blocking any current phase

**Question**: Should the plugin graph be defined declaratively (`:nodes` + `:edges`
EDN map, compiled at eval time) or imperatively (`add-node!`, `connect!`), or both?

**Recommendation**: Declarative `defrack` macro as primary form; imperative API for
interactive REPL patching. Declarative form compiles to imperative calls internally.

---

## Q62 — Rackspace switching: crossfade and audio continuity

**R&R reference**: §30.4, §30.10

**Status**: Future sprint

**Question**: How long is the rackspace crossfade window? How are overlapping switch
requests handled? How does latency compensation change during transition?

---

## Q63 — Plugin graph topology: DAG only or feedback loops supported?

**R&R reference**: §30.6, §30.10

**Status**: Future sprint

**Question**: Should the plugin graph require a DAG (no feedback), or support feedback
loops with automatic delay-buffer insertion? What are the PDC (plugin delay
compensation) requirements?

---

## Q64 — VST3 support in the plugin container

**R&R reference**: §19.2, §30.8, §30.10

**Status**: Future sprint

**Question**: VST3 has a much larger plugin catalog than CLAP (2024). The architecture
isolates plugin hosting behind `SynthTarget`, making VST3 a new subclass. When should
VST3 be prioritised — same sprint as `defrack`, or a subsequent phase?

---

## Q65 — Live graph modification (hot-patching while audio runs)

**R&R reference**: §30.3, §30.10

**Status**: Future sprint

**Question**: Can audio connections be added or removed while the graph is running?
Requires synchronisation between audio callback thread and IPC thread; likely needs
double-buffer or copy-on-write for the processing graph applied between callbacks.

---

## Q66 — DAWProject export scope

**R&R reference**: §31.5

**Status**: Future sprint

**Question**: Should DAWProject export be limited to captured MIDI clips and the tempo
map (Phase A), or should it also include automation curves and CLAP plugin states
(Phase B)? What triggers a capture — explicit `export!` call, or automatic on
`stop!`?

---

## Q67 — DAWProject import granularity

**R&R reference**: §31.5

**Status**: Future sprint

**Question**: Should import support full project reconstruction (mapping DAWProject
track hierarchy to cljseq constructs and generating a `defrack` form) or only
selective import (tempo map, MIDI clips)? Selective import is lower risk for Phase 1;
full import requires resolving the `defrack` generation strategy first.
