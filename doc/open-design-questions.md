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

## Q5 — `sched-ahead-time` should be per-integration-target, not per-protocol-class

**R&R reference**: §3.1, §3.4

**The issue**

The document specifies 100ms for MIDI and 500ms for audio. But the relevant dimension
is the synthesis engine's buffering model, not the protocol:

| Target | Appropriate sched-ahead | Why |
|---|---|---|
| `javax.sound.midi` hardware | 50–100ms | Low-latency MIDI hardware DMA |
| SuperCollider via OSC | 10–50ms | scsynth bundles; engine handles its own scheduling |
| VCV Rack via OSC | 50ms | Rack's audio buffer (typically 256–512 samples) |
| Bitwig via virtual MIDI | 30–100ms | DAW MIDI input latency varies |
| SuperCollider audio synth | 500ms+ | Audio synthesis needs larger buffer |
| Ableton Link timeline | 0ms | Link's timeline is absolute; no sched-ahead needed for beat alignment |

A single 500ms value applied to OSC-to-SuperCollider would cause notes to be
unnecessarily early relative to scsynth's own scheduling, and could exceed scsynth's
bundle time window.

**Stakes**

Too large: notes arrive early relative to the target's own scheduling, causing
collisions with pre-queued events. Too small: GC pauses cause late delivery and
audible glitches.

**Exploration path**

1. Replace the single `*sched-ahead-ns*` binding with a per-target config map:
   ```clojure
   {:midi-hardware 100 :sc-osc 30 :vcv-rack 50 :link 0}  ; milliseconds
   ```
2. Each integration target's `play!` implementation reads its own sched-ahead value.
3. Provide a `(with-target-config target opts ...)` override for per-session tuning.
4. Measure empirically: connect to each target and measure actual note-to-sound latency
   at different sched-ahead values.

---

## Q6 — Stream model real-time consumption path is underspecified

**R&R reference**: §9

**The issue**

Section 9 describes the Stream/Score model (a sorted map of offset → events) but
does not specify how a Stream is consumed by the real-time scheduler. Two distinct
use cases share the same data structure but require different consumption semantics:

**Offline score generation**: A Stream is rendered completely before playback begins.
Iteration order is deterministic; the consumer can look ahead arbitrarily.

**Real-time scheduling**: A Stream is consumed event by event in virtual time order.
The consumer must not look ahead past the next event (dynamic patterns may still be
generating events for later offsets). The scheduler must also handle infinite Streams
(lazy seqs of generated events).

Without specifying the consumption contract, it is unclear whether a Stream is:
- A pure value (fully materialized sorted map)?
- A lazy seq (pull-based, event by event)?
- A channel (push-based, async delivery)?

**Stakes**

The consumption model affects memory usage (a 3-hour score as a pure value is large),
composability (lazy seqs compose with transducers), and the live-coding interaction
model (can you modify a Stream mid-playback?).

**Exploration path**

1. Define a `Playable` protocol with a single method `(events stream)` → lazy-seq of
   `[offset event]` pairs.
2. The real-time scheduler consumes this lazy seq, parking between events using the
   existing `sleep!`/`LockSupport` machinery.
3. For offline rendering, `(realize stream)` fully materializes the seq into a sorted map.
4. A `live-loop` body that constructs a Stream can be swapped mid-playback because the
   scheduler holds only a reference to the current lazy seq head.
5. Reference: examine how Overtone's `apply-at` / `apply-by` chain achieves a similar
   effect with temporal recursion, and whether the Stream abstraction adds enough over
   recursive `live-loop` to justify the complexity.

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

## Q8 — Control tree node type system: richness vs. simplicity

**R&R reference**: §16.2

**The issue**

The control tree needs a type system for value nodes. At minimum: `:int`, `:float`,
`:bool`, `:keyword`, `:data` (opaque Clojure value). But musical parameters motivate
richer types:

- `:pitch` — a Pitch value; what does a MIDI CC → Pitch transform look like? (CC has
  128 steps; pitch space is much larger)
- `:harmony` — a compound value `{:key :C :mode :major :degree :I}`; can an OSC
  message set the whole thing atomically?
- `:pattern` — a Pattern value; can this be set via MIDI at all? Via OSC with a
  JSON-encoded value?
- `:fn` — a callable; can it be replaced at runtime via an OSC message carrying
  Clojure source?

The tension is between a rich type system that enables powerful coercions and
validation, and a simple one that is easy to implement and reason about.

**Stakes**

An overly rich type system becomes a language-within-a-language. An overly simple
one forces every transform to be a raw function — which can't be serialized for
`ctrl/snapshot` or transmitted as a tree description to an OSC autodiscovery client.

**Exploration path**

1. Define the minimal set of types needed for all parameters in §16.2's examples:
   `{:int :float :bool :keyword :enum :data}`. `:enum` is a keyword with a declared
   finite set of valid values (scales, modes, chord types).
2. Defer `:pitch`, `:harmony`, `:pattern` to a second pass — these are high-value but
   complex.
3. `:fn` is never settable via MIDI or OSC (security boundary: remote code execution).
   Method nodes are declared in Clojure only; they are *callable* but not *replaceable*
   via external protocols.
4. The `:data` type is the escape hatch for complex values — no coercion, no
   serialization guarantee. OSC can set a `:data` node only if a transform function
   is provided.

---

## Q9 — Conflict resolution when multiple sources control the same node

**R&R reference**: §16.3

**The issue**

When Link is active, it writes `/cljseq/global/bpm`. If the user also binds MIDI CC
72 to `/cljseq/global/bpm`, two asynchronous sources compete to write the same atom.
Last-write-wins is the simplest semantics but leads to a BPM "fight" where Link and
the MIDI controller continuously overwrite each other.

The same problem applies whenever OSC and MIDI are both bound to the same node.

**Stakes**

In a live performance, an unexpected BPM fight would be catastrophic. The system must
have a defined, configurable resolution policy.

**Exploration path**

Options:
1. **Priority ordering**: each binding has a priority; higher priority wins. Link is
   highest by default; MIDI next; OSC last; Clojure code overrides all.
2. **Source locking**: the first source to write a node "owns" it; other sources are
   silently ignored until the lock is released.
3. **Last-write-wins** (simple, no policy): the user is responsible for not binding
   competing sources. Document the risk.
4. **Explicit conflict declaration**: `ctrl/bind!` raises an error if the node is
   already bound to a conflicting source, unless `:allow-conflict true` is passed.

Recommendation: option 4 (fail-fast at binding time) with option 1 (priority) as
the explicit override mechanism. The error at binding time catches accidental
conflicts before the performance.

---

## Q10 — `mod-route` directionality: input node → output node → sidecar

**R&R reference**: §16.8

**The issue**

§16.8 sketches a `mod-route` from an input value node to a device output node. This
requires the tree to know that writing `/cljseq/devices/prophet/filter-cutoff`
triggers a MIDI CC send via the sidecar. The write path is:

```
ctrl/set! → atom CAS → post-write hook → sidecar/schedule! MIDI CC
```

But this means a plain `ctrl/set!` call from Clojure code also triggers a MIDI send.
That may or may not be desired — sometimes you want to update the displayed state of
a parameter without sending a MIDI message (e.g., when reading the current hardware
state back via SysEx).

**Stakes**

The post-write hook model conflates "the logical value changed" with "send a hardware
event." These are distinct concerns and need to be separable.

**Exploration path**

1. Distinguish `:logical` writes (update the atom, fire watches, but no hardware
   output) from `:physical` writes (update the atom AND fire the sidecar).
2. `ctrl/set!` is logical by default; `ctrl/send!` is physical (also updates the atom
   and fires a hardware event).
3. `mod-route` uses `ctrl/send!` internally.
4. A device node's atom represents the *last sent* value; it can diverge from actual
   hardware state if the hardware was adjusted manually.

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

## Q12 — Control-plane vs. data-plane OSC: how to distinguish at the wire level?

**R&R reference**: §17.1

**The issue**

§17.1 asserts that `/cljseq/**` addresses are control-plane and everything else is
data-plane (routed to the C++ sidecar for timestamped delivery to synthesis engines).
But this address-prefix convention is a policy, not a protocol guarantee. Ambiguous
cases:

1. A TouchOSC layout might send `/cljseq/loops/bass/velocity` to change a parameter
   (control plane), but another layout might send the same address as a scheduled
   note trigger (data plane).
2. An OSC message to `/s_new` from the JVM side (via `cljseq.osc/send-osc!`) is
   clearly data-plane — but what if a user defines a tree node at `/s_new`?
3. Incoming OSC from VCV Rack might use addresses that overlap with cljseq tree paths.

**Stakes**

Misrouting a data-plane event through the control stack delays it by the JVM handler
latency (~1ms), losing timing precision. Worse, if the control stack tries to match
the address against the tree and finds no node, it returns a 404-equivalent error
that the sender did not expect.

**Exploration path**

1. **Two ports**: control-plane OSC on port 57121 (cljseq default); data-plane OSC
   on port 57110 (SuperCollider default) or a user-configured output port. Port is
   the cleanest separation — no address-space ambiguity.
2. **Address prefix**: `/cljseq/**` is control-plane; everything else passes through
   to the sidecar for timestamped delivery. Simple, but fragile if a user defines
   tree nodes at non-`/cljseq` paths.
3. **Explicit opt-in**: OSC messages with a `#bundle` immediate timestamp
   (`0x00000000 0x00000001`) are control-plane (no scheduling intent); bundles with
   a future timestamp are data-plane (scheduling intent). This is actually the
   semantically correct distinction — OSC bundles were designed for exactly this.

Recommendation: option 3 (bundle timestamp semantics) as the primary distinction,
with option 1 (two ports) as a user-visible fallback for hardware controllers that
don't support bundles.

---

## Q13 — MCP `repl/eval` authorization: per-session or capability-based?

**R&R reference**: §18.3, §18.7

**The issue**

`repl/eval` allows an MCP client (Claude) to execute arbitrary Clojure in the live
session. The authorization model in §18.7 proposes a tiered system with explicit
user confirmation. But there are three unresolved specifics:

1. **Granularity**: is authorization per-tool (allow `repl/eval` forever), per-session
   (allow it for this nREPL session), per-call (confirm each eval), or per-code-class
   (allow read-only code, deny side-effecting code)?

2. **Revocation**: how does the user revoke a previously granted authorization without
   restarting the server?

3. **Sandboxing**: should `repl/eval` run in a restricted namespace that can only
   access `cljseq.*` namespaces, or full JVM access? A sandbox provides a safety net
   but requires a Clojure security model (ClojureSandbox or custom classpath filtering).

**Stakes**

`repl/eval` without sandboxing is arbitrary code execution on the user's machine.
Getting this wrong undermines user trust. Getting it over-restricted makes the most
powerful MCP capability useless.

**Exploration path**

1. Start with per-session authorization (user confirms once at session start; revoke
   by restarting). This is the Claude Code model.
2. Add a namespace whitelist: `repl/eval` submissions are prepended with
   `(in-ns 'cljseq.user)` and can only refer to `cljseq.*` namespaces unless the
   user explicitly grants full access.
3. Log every eval to a session transcript file for auditability.
4. Revisit sandboxing after evaluating real-world usage patterns.

---

## Q14 — HTML rendering of the tree: server-side or client-side?

**R&R reference**: §17.7

**The issue**

§17.7 describes the system as a web-navigable resource. Two implementation paths:

**Server-side rendering (SSR)**: Hiccup templates in the Ring handler generate HTML
at request time. Simple, no JavaScript required, fully functional in any browser.
Interaction (sliders, buttons) requires form POSTs or HTMX-style partial updates.

**Client-side (SPA)**: Serve a static JavaScript application that queries the REST
API. Richer interaction, WebSocket integration for live updates, but adds a JavaScript
build step and a JS framework dependency.

**Stakes**

SSR with HTMX is surprisingly capable for a real-time dashboard (HTMX supports
WebSocket-driven partial updates). An SPA is more work but provides a better UX for
parameter exploration during a performance.

**Exploration path**

1. Start with SSR + HTMX: no build tooling, zero JS framework, Ring-native. HTMX's
   `hx-ws` can subscribe to WebSocket streams for live value updates.
2. Evaluate whether SSR+HTMX is sufficient after a prototype. If a richer UI is
   needed (e.g., piano roll visualization, waveform display), add a lightweight SPA
   for those specific views only.
3. The REST API is built regardless; the frontend is an optional layer over it.

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

## Q16 — Process topology: JVM ↔ N processes, or sidecar-as-router?

**R&R reference**: §3.5

**The issue**

Two topologies are plausible:

**Option A (current design)**: JVM maintains separate IPC connections to each process.
Event routing happens in the JVM: a note event for Surge XT goes directly to
`cljseq-audio-0`; a note event for the Prophet goes to `cljseq-sidecar`.

```
JVM ──IPC──► cljseq-sidecar
    ──IPC──► cljseq-audio-0
    ──IPC──► cljseq-audio-1
```

**Option B (sidecar-as-router)**: JVM always talks to `cljseq-sidecar`; the sidecar
routes audio-destined events to `cljseq-audio` processes via an internal IPC.

```
JVM ──IPC──► cljseq-sidecar ──IPC──► cljseq-audio-0
                            ──IPC──► cljseq-audio-1
```

**Stakes**

Option A is simpler and avoids adding latency in the sidecar for routed events.
But the JVM must discover and manage multiple process lifecycles. Option B hides
complexity from the JVM but adds a hop for audio-destined events and makes the
sidecar a more complex process.

**Recommendation**

Option A: direct JVM-to-process connections. The JVM's `cljseq.sidecar` namespace
already manages one process connection; extending it to manage N is straightforward.
The routing key (target ID prefix: `/cljseq/synths/surge` → `cljseq-audio-0`) lives
in a Clojure map.

---

## Q17 — CLAP parameter count: eager vs. lazy tree registration?

**R&R reference**: §19.7

**The issue**

Surge XT exposes ~600 CLAP parameters. Registering all 600 as tree nodes at plugin
load time:
- Pro: full OSC and MIDI addressability from the start; `ctrl/ls` returns complete
  parameter list; MCP tools can discover and set any parameter
- Con: 600 atoms and tree entries with rich metadata is not free; navigating the
  tree in a TUI or browser becomes unwieldy

Lazy registration (only create a tree node when the parameter is first accessed or
explicitly registered) is lighter but means the tree is incomplete until parameters
are used.

**Exploration path**

1. Distinguish between the *parameter map* (complete index of CLAP parameter
   IDs + names, loaded in the sidecar, queryable via IPC `0x75`) and the *tree*
   (populated lazily on JVM side as parameters are bound or accessed).
2. The sidecar always has the full map; the tree is a sparse projection of it.
3. `ctrl/ls "/cljseq/synths/surge/params"` triggers a bulk fetch that populates the
   tree on demand.
4. `(ctrl/register-all-params! path)` is available for explicit eager registration
   when full addressability is needed.

---

## Q18 — Pd patch hot-swap: audio continuity during patch reload?

**R&R reference**: §19.8

**The issue**

`PdTarget::load_patch()` closes the current patch and opens a new one. This
interrupts audio: there will be a gap (one or more buffers of silence) while Pd
reinitializes the new patch. In a live performance, this gap is audible.

Options:
1. **Accept the gap**: document it; performers prepare for patch changes at a
   natural break.
2. **Crossfade**: run old patch and new patch in parallel for N buffers, fade out
   old, fade in new. Doubles DSP cost for the transition period.
3. **Pre-load**: allow pre-loading a new patch into a dormant PdTarget instance;
   `swap!` is just a pointer update (atomic) with no re-initialization gap.

**Exploration path**

Option 3 (pre-load + atomic swap) is achievable: `cljseq-audio` maintains a pool
of at most 2 PdTarget instances per "voice slot"; one is active, one can be
initialized in the background. `(ctrl/preload-patch! path target)` warms up the
standby; `(ctrl/swap-patch! target)` atomically promotes it to active at the next
buffer boundary.

---

## Q19 — `cljseq-audio` and Link: second peer on the same machine?

**R&R reference**: §3.5, §19.9

**The issue**

Both `cljseq-sidecar` and `cljseq-audio` instantiate `ableton::Link` and join the
session. The Link documentation states that multiple peers on the same machine are
supported and is a tested use case. However:

1. Each `ableton::Link` instance consumes network resources (multicast socket,
   background thread). Two instances on the same machine with the same interface
   means two multicast join/leave cycles.
2. The two processes will see each other as Link peers, incrementing the peer count
   visible to other machines on the network. This might confuse users who see 2 peers
   reported when only one cljseq instance is running.
3. If the beat timelines of the two processes drift relative to each other (even by
   a few microseconds), audio events and MIDI events will be out of phase.

**Exploration path**

1. Test: run both processes and measure the beat-time difference between their
   respective `captureAudioSessionState()` calls at the same wall-clock moment.
   Acceptable drift: < 100µs (< 5 samples at 48kHz).
2. If drift is unacceptable: implement a lightweight IPC beat-sync mechanism
   between the two processes (not Link — just a direct socket message from
   `cljseq-sidecar` to `cljseq-audio` carrying its current beat estimate, used
   as a correction signal).
3. Alternatively: only `cljseq-sidecar` is a Link peer; `cljseq-audio` receives
   beat timeline via IPC from the sidecar rather than from Link directly.

Option 3 is architecturally cleanest (single Link peer per cljseq instance) but
adds IPC beat-forwarding to `libcljseq-rt`.

---

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

## Q21 — Score serialization: canonical representation across the IPC boundary?

**R&R reference**: §20.5

**The issue**

The semantic mapping table in §20.5 defines the JSON wire format. Several edge cases
need a canonical answer before implementation:

- **Enharmonic equivalents**: `C#4` and `Db4` have the same MIDI number (61). The wire
  representation uses `{"midi": 61, "name": "C#", "octave": 4}`. Should the name be
  canonical (always sharps? context-dependent)? Music21 preserves the spelled name;
  cljseq may not care.
- **Microtonal pitches**: `{"midi": 60, "cents_offset": 25}` — the cljseq Pitch type
  needs a `:pitch/cents` field (see §7). Needs to round-trip faithfully.
- **Tied notes**: Music21 has `tie.Tie` objects. A tied quarter + eighth = dotted
  quarter or two separate notes? Flat serialization loses tie information. Decision:
  flatten ties on the Music21 side (merge durations) before serialization.
- **Grace notes**: zero-duration notes in Music21. Map to `:dur/beats 0` or omit?
  Decision: omit from flat serialization (performance-path irrelevant).
- **Chords**: Music21 `chord.Chord` can appear in a `Stream`. Map to multiple
  simultaneous notes (same `:time/beat`) in the flat sequence.

**Exploration path**

1. Define the canonical rules in the Python server's `_note_to_json()` helper:
   enharmonic = preserve Music21 spelling; microtonal = carry cents offset; tied =
   merge durations; grace = omit; chord = simultaneous notes.
2. Write round-trip tests: Clojure note map → (m21/export-xml) → (m21/parse-file) →
   Clojure note map; verify MIDI numbers and durations are preserved.
3. Document the lossy cases (enharmonic spelling, ties) in `cljseq.m21` docstrings.

---

## Q22 — In-process Music21: Jython, GraalPy, or JPype?

**R&R reference**: §20.3

**The issue**

The subprocess architecture adds a round-trip over a Unix socket (1–5ms overhead
per call). For very fast repeated calls (e.g., analyzing each measure in a long score),
this overhead accumulates. Three alternatives exist for in-process Music21:

| Option | Status | Notes |
|---|---|---|
| **Jython** | Dead (Python 2.7 only) | Not viable |
| **GraalPy** | Experimental (GraalVM) | Music21 compatibility unknown; adds GraalVM JVM requirement |
| **JPype** | Active (Python ← JVM bridge) | Wrong direction: JPype calls Java from Python |
| **Subprocess (current)** | Chosen | Simple, isolated, works with CPython |

**Recommendation**

Retain the subprocess design. The overhead is acceptable for all planned use cases.
The only high-frequency case (key analysis per measure) can be batched:
`(m21/analyze-key-floating notes :window-beats 4)` analyzes all windows in one RPC
call rather than N calls.

Mark this question resolved unless GraalPy matures significantly.

---

## Q23 — Overlap between cljseq pitch/scale types and Music21?

**R&R reference**: §20.8

**The issue**

cljseq defines its own Pitch, Interval, Scale, Chord types (§4) and will implement
operations on them (transpose, add-interval, chord-build). Music21 can also perform
these operations. The risk: duplicate implementations diverge; the prize: cljseq
operations are microsecond-fast (no IPC), Music21 operations are millisecond-slow
but exhaustive.

The correct division is:
- **cljseq native**: all operations on the performance path (transpose, scale lookup,
  chord spelling from Roman numeral, interval arithmetic). These are called in
  `play!`, `with-harmony`, `sleep!` — sub-millisecond budget.
- **Music21**: operations that need full musicological depth or corpus access (key
  analysis, voice leading check, figured bass, counterpoint). These are explicitly
  async; never on the hot path.

Specifically: cljseq's `analyze/key` should be a thin wrapper over `m21/analyze-key`,
not a reimplementation of Krumhansl-Schmuckler. The tradeoff is acceptable because
key analysis is never called per-note.

**Resolution**

Division of labor as defined in §20.8 table. No pitch/interval/scale operations in
the `cljseq.analyze` namespace should duplicate work Music21 does; they should
delegate to `cljseq.m21`.

---

## Q24 — Music21 corpus licensing: distribution policy?

**R&R reference**: §20.10

**The issue**

Music21 ships with a built-in corpus including MIDI and MusicXML files. The files have
varied provenance: some are public domain (Bach, Beethoven, Josquin), some have unclear
licensing. The cljseq project must not bundle Music21 corpus files in its repository.

The design already handles this: cljseq delegates all corpus access to the Music21
Python process. No corpus files are copied into the cljseq repo or distribution
artifact.

The Essen Folksong Collection (included in Music21) is public domain for research use.
The NKS (virtual corpus) must be installed separately by users.

**Resolution**

cljseq does not redistribute corpus files. `cljseq.m21/search-corpus` and
`parse-corpus` operate on the user's installed Music21 corpus at runtime. Add a
documentation note that the corpus is provided by Music21's installation, not cljseq.

---

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

## Q26 — Gamelan tuning: per-ensemble files vs. generalized scale parameters?

**R&R reference**: §21.3.1, §7

**The issue**

Each physical gamelan has its own unique tuning — there is no "standard Pelog" in the
way there is a standard Western A440. This means:

1. A reference Pelog `.scl` file (as in §21.8) is only an approximation.
2. For authentic gamelan simulation, the user needs the actual measured tuning of
   their specific reference ensemble.
3. For composition, an approximate reference tuning is fine.

The system needs to support both use cases without special-casing gamelan.

**Resolution**

The Scala `.scl` / `.kbm` system (§7) already handles this: ship reference tuning
files in `resources/scales/gamelan/`; allow users to add their own files to
`~/.cljseq/scales/`. `(tuning/load! :my-gamelan/pelog)` loads from the user
directory first, then the bundled resources. No gamelan-specific code needed.

---

## Q27 — Non-Western corpus access via Music21's VirtualCorpus?

**R&R reference**: §20.7, §21.7

**The issue**

Music21's built-in corpus has limited non-Western content. The Essen folksong
collection has some global coverage, but there is no substantial Arabic or gamelan
corpus. Options:

1. **VirtualCorpus**: Music21 supports registering local directories as additional
   corpus paths. Users can add their own MusicXML or MIDI files.
2. **External datasets**: Several non-Western musical corpora exist as research
   datasets (Farabi corpus for maqam, MIDICPD for gamelan), but they have varied
   formats and licensing.
3. **Generate from scale**: for non-Western music, "corpus" may be less relevant
   than "scale library" — a composer works from the scale/mode structure, not
   extracted passages.

**Resolution**

Implement `(m21/register-corpus-path! dir)` which calls Music21's `corpus.addPath()`
via IPC. Document that non-Western corpus access requires the user to provide their
own files. The cljseq project does not bundle third-party corpus data.

---

## Q28 — Colotomic structure: special API or just `live-loop` with `sleep!`?

**R&R reference**: §21.3.2

**The issue**

Gamelan colotomic layers (balungan, bonang, kenong, kendhang, gong) are just
`live-loop`s with different periods and irama-modulated `sleep!` durations. There
is no structural difference from ordinary polyrhythm. However, the irama ratio —
which determines how many bonang notes fit in each balungan note — needs to be
shared state that all loops read in sync.

**Resolution**

No special API needed. Use `ctrl/bind!` or a shared atom for the irama ratio:

```clojure
(defonce irama (atom 4))  ; bonang notes per balungan note

(live-loop :bonang
  (play! :synth/bonang (bonang-elaboration) :dur (/ 1 @irama))
  (sleep! (/ 1 @irama)))
```

When `irama` is updated (typically at phrase boundaries), all loops reading the atom
adapt on their next iteration. Phase-quantize irama changes via `at-sync!` to a gong
stroke boundary to avoid rhythmic tears.

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

## Q31 — Arpeggiation library format and corpus extraction pipeline?

**R&R reference**: §22.5

**The issue**

The arpeggiation library consists of two tiers:
1. **Hand-curated built-in patterns**: defined as EDN maps in `resources/arpeggios/`.
2. **Corpus-extracted patterns**: derived by the Music21 sidecar from the Bach chorales
   and other works; written to `resources/arpeggios/corpus-derived.edn`.

Key questions:
- What constitutes a "pattern" in the corpus? Figuration patterns must be extracted
  from analysis of the score texture (Alberti bass = sustained bass + alternating
  chord notes, etc.). This requires Music21's feature extraction.
- How is the corpus extraction triggered? As a build step, a user command
  `(corpus/extract-arpeggios!)`, or never (ship pre-extracted)?
- What is the minimum pattern frequency for inclusion (avoid noise patterns)?

**Exploration path**

1. Ship pre-extracted patterns (avoid requiring the user to run a long extraction job
   at first use). Extraction is a development-time step that updates the bundled file.
2. Pattern extraction uses Music21's `analysis.elements` module to identify repeated
   figurations in each voice. Minimum frequency: 50 occurrences in the Bach corpus.
3. Pattern format: EDN with `:order`, `:rhythm`, `:dur`, `:source` fields. The
   `:source` field lists the corpus works where the pattern was found.

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

## Q39 — Branch regeneration trigger: pure function vs. reactive watch?

**R&R reference**: §24.2

**The issue**

Bloom regenerates all branches whenever the trunk is edited. Two models in cljseq:

**Option A (pure function, explicit call)**: `bloom/build-tree` is called by the
programmer whenever the trunk changes. No reactivity; the programmer controls when
branches update.

**Option B (reactive watch)**: the trunk is a control tree node; a `watch!` callback
automatically calls `build-tree` on every trunk change and stores the result.

**Stakes**: Option A is simpler and avoids surprise re-generation during live editing.
Option B matches Bloom's behavior exactly. The configurable "Regenerate Branches
Filter" in Bloom (controls which trunk edits trigger regeneration) suggests Option B
with a filter predicate is the right design.

**Recommendation**

Option B with filtering: `(bloom name :trunk-path path :regen-filter #{:pitch :gate})`
— watches the trunk node and regenerates branches only on changes to the allowed
fields. The programmer can call `(bloom/freeze! name)` to temporarily disable
regeneration during a multi-step trunk edit.

---

## Q40 — Ornament timing: how do ornament sub-steps interact with virtual time?

**R&R reference**: §24.5

**The issue**

An ornament like a mordent expands one step into 3 sub-steps (current → above →
current), each lasting 1/3 of the original step duration. The `play!` calls for these
sub-steps must happen within the original step's virtual time slot.

If the original step has `:dur/beats 1/4`, the three mordent sub-steps should each
be `1/12` beats. This requires sub-beat `sleep!` calls, which cljseq already supports
(rationals).

The more complex case: a Trill expands to 8 sub-steps, each `1/32` beats (if the
step is `1/4` beats). At 120 BPM this is a 31.25ms step — within the scheduler's
timing budget.

**Resolution**

Ornaments are pure functions that return a sequence of sub-steps. The caller iterates
over them with ordinary `sleep!`:

```clojure
(doseq [sub-step (bloom/ornament step :mordent-up context)]
  (play! target (:pitch/midi sub-step) :dur (:dur/beats sub-step))
  (sleep! (:dur/beats sub-step)))
```

No special scheduler support needed. The sub-step durations sum to the original step
duration by construction.

---

## Q41 — Unified step map: is it a protocol, a record, or a plain map?

**R&R reference**: §24.10

**The issue**

The canonical step map (§24.10) is used by all sequencing primitives. Should it be:
- **Plain map**: zero overhead, most Clojure-idiomatic, no enforcement
- **Defrecord**: faster field access, protocol dispatch, but less flexible
- **Spec**: validation via `clojure.spec.alpha`; documents the schema without a type

**Recommendation**

Plain map with `clojure.spec.alpha/def` for the `:cljseq/step` spec. This provides
validation (in development, via `instrument`) without imposing a record type on every
function. The `:pitch/midi`, `:dur/beats`, etc. keys are namespace-qualified, making
accidental collision with other maps unlikely.

---

## Q42 — `play-bloom-step!` vs. extending `play!` with step-map overload?

**R&R reference**: §24.6

**The issue**

Section §24.6 proposes `play-bloom-step!` as a function that dispatches note, gate,
ratchet, slew, and mod events from a step map. But `play!` already exists as the
primary note-playing primitive. Two options:

**Option A**: `play!` accepts a step map as its second argument when passed a map:
```clojure
(play! :midi/synth step-map)   ; dispatches all step fields
(play! :midi/synth 60)          ; legacy: just a MIDI note
```

**Option B**: `play-bloom-step!` is a separate, explicit name for the richer dispatch.

**Recommendation**

Option A: overload `play!` to accept both a MIDI note and a step map. When a step map
is passed, dispatch note, ratchet, slew, and mod as a bundle. The mod target is taken
from the current device's `:mod-target` if not specified in the step map. This keeps
the API surface small and makes the step map a first-class citizen of `play!`.

---

## Q43 — Path navigation: index sequence or named path strategy?

**R&R reference**: §24.3

**The issue**

Bloom's 128 paths at max branches are specific index sequences determined by the
module's firmware. In cljseq, paths can be:
- **Integer index** (0–127, matching Bloom's numbering): for hardware compatibility
- **Named strategy** (`:forward :reverse :random :alternate`): more readable
- **Explicit index vector** `[0 2 4 2 0 6 4 6]`: maximum flexibility

**Recommendation**

Support all three. `(bloom/path branches :path 3)` accepts integer (Bloom-compatible),
keyword (named strategy), or vector (explicit indices). Bloom-compatible integer paths
are generated by the same algorithm as the firmware: path N is determined by treating
N as a binary number that encodes the branch traversal direction at each level of the
tree.

---

## Q34 — DEJA VU ring buffer: identity across live-loop redefinitions?

**R&R reference**: §23.6, §23.9

**The issue**

A `stochastic-sequence` with DEJA VU maintains an internal ring buffer. When a
`live-loop` is redefined (the central live-coding operation), the loop body is
re-evaluated — but if the sequence source is defined inside the loop body, the ring
buffer is lost and the accumulated "crystallized" pattern resets to empty.

**Resolution**

Declare the sequence source with `defonce` outside the loop body. Because `defonce`
is a no-op if the var already has a value, the ring buffer persists across REPL
redefinitions:

```clojure
(defonce melody-gen
  (stochastic-sequence :spread 0.4 :deja-vu (atom 0.7) :loop-len 8 ...))

(live-loop :melody
  (play! :midi/synth (next! melody-gen))
  (sleep! 1/4))
```

Document this pattern prominently. The `marbles` macro uses `defonce` internally to
ensure the context persists when the `live-loop` body is re-evaluated.

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

## Q36 — Weighted scale degrees: extension of IntervalNetwork or separate type?

**R&R reference**: §23.5, §4

**The issue**

The `IntervalNetwork` model (§4) describes scales as graphs of interval relationships.
A weighted scale (§23.5) adds probability weights to each degree. Are these the same
type with an optional weight field, or distinct types?

**Exploration path**

1. The simplest approach: `weighted-scale` is a map from pitch-class (relative to
   tonic) to weight, alongside the scale's interval structure:
   ```clojure
   {:scale/type :major
    :scale/root :C
    :scale/weights {0 1.0, 2 0.4, 4 0.8, 5 0.5, 7 0.9, 9 0.4, 11 0.2}}
   ```
2. `IntervalNetwork` generates the pitches in the scale; the weight map selects among
   them. They are orthogonal: interval structure says *which* pitches are in the scale;
   weights say *how likely* each is to be chosen.
3. Default `weighted-scale` with no weights specified = uniform weights over the
   IntervalNetwork's degrees.
4. No new type needed beyond adding an optional `:scale/weights` key to the existing
   scale map.

---

## Q37 — Correlated sequences: how is correlation implemented without shared mutable state?

**R&R reference**: §23.7

**The issue**

Multiple correlated channels share a "parent" random draw but diverge via per-channel
perturbation. In a concurrent Clojure setting with multiple live-loops reading
different channels, the parent draw must be the same for all channels on the same
"step" — but channels may advance at different rates (if clocked differently).

**Exploration path**

1. Correlation is only meaningful when channels advance simultaneously (same clock).
   For simultaneously-clocked channels, use a shared `step-counter` atom: on each
   `next!` call, check if the step has already been computed; if so, return the
   pre-computed parent draw for this step.
2. Implement with a `promise`-per-step: each new step creates a `promise` for the
   parent draw; the first channel to advance computes the parent draw and delivers
   it; subsequent channels deref the same promise.
3. Per-channel perturbation: `channel_value = lerp(parent, (gen-fn), 1 - correlation)`
   where `(gen-fn)` is an independent per-channel draw.
4. For independently-clocked channels, correlation is approximate — the "parent" is
   the most recent shared draw, which may be stale by a few steps.

---

## Q38 — `marbles` context in the control tree: auto-registration?

**R&R reference**: §23.8, §16

**The issue**

The `marbles` macro creates a generative context with many live parameters (all T and
X parameters are atoms). Should the macro automatically register these atoms as tree
nodes at `/cljseq/marbles/<name>/`, or should registration be explicit?

**Resolution**

Auto-register, matching the behavior of `deflive-loop` (§16). The `marbles` macro
expands to include `ctrl/defnode` calls for each parameter:

```
/cljseq/marbles/my-gen/t-bias       → atom wrapping t-bias
/cljseq/marbles/my-gen/t-jitter     → atom wrapping t-jitter
/cljseq/marbles/my-gen/t-deja-vu    → atom wrapping t-deja-vu
/cljseq/marbles/my-gen/x-spread     → atom wrapping x-spread
/cljseq/marbles/my-gen/x-bias       → atom wrapping x-bias
/cljseq/marbles/my-gen/x-steps      → atom wrapping x-steps
/cljseq/marbles/my-gen/x-deja-vu    → atom wrapping x-deja-vu
```

All parameters become MIDI-CC-bindable, OSC-addressable, WebSocket-subscribable,
and MCP-tool-accessible automatically. A single hardware knob can live-control the
`deja-vu` parameter of any `marbles` context.

---

## Q33 — nREPL integration: resolved

**R&R reference**: §13.2

**Resolution**

See §13 item 2 (updated). Standard nREPL is the developer interface. The control tree
(§16) is the canonical state model — all introspection happens via `ctrl/ls`,
`ctrl/describe`, WebSocket subscriptions (§17), or MCP resources (§18). Optional
`cljseq-nrepl` middleware can add a `cljseq/watch` op for editor-side real-time
display, but this is a non-blocking development item.

No custom nREPL ops are required for core functionality.

---

## Q44 — Unified temporal-value abstraction: do clocks and modulators share a protocol? — **RESOLVED**

**R&R reference**: §3 (virtual time), §16 (control tree / LFO binding), §22 (param-loop),
§23 (Marbles stochastic); informed by Sprint 1 design research on Maths/PoliMATHS/Blinds
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
| Q38 | `marbles` context in control tree | No — API design | Low — auto-register at startup |
| Q20 | Music21 async from live-loop | **Yes** — usage convention needed | Low — document + `m21/eventually!` helper |
| Q21 | Score serialization canonical form | No — needed before Music21 impl | Low — define rules in Python server |
| Q22 | In-process Music21 (GraalPy) | No — performance question | Low — subprocess is sufficient |
| Q23 | cljseq vs Music21 type overlap | No — architecture decision | Low — §20.8 table is the answer |
| Q24 | Music21 corpus licensing | No — distribution policy | Low — resolved: no bundling |
| Q7 | Link integration | No — post-core feature | High — see §15 |
| Q44 | Unified temporal-value abstraction: `ITemporalValue` protocol | **Yes** — needed before Phase 1 impl | High — design spike recommended |
| Q45 | Parts vs. tracks: unifying voice-organization abstraction (`ensemble`?) | **Yes** — needed before multi-voice DSL impl | Medium — conceptual synthesis + naming decision |
| Q46 | Timing modulation: swing, humanize, groove, quantize as time-dimension `ITemporalValue` | **Yes** — needed before humanization/quantization impl | Medium — connects Q35, Q44, `cljseq.clock` vocabulary |
