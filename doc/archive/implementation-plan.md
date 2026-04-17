# cljseq Implementation Plan

## Guiding Principles

1. **C++ sidecar first**: The sidecar is not an afterthought. It is built before any
   musical feature work begins. Every timing-sensitive feature depends on it.

2. **JVM generates; C++ delivers**: Clojure computes beat schedules. The sidecar
   fires events. These two concerns never cross: the JVM never touches a MIDI device
   directly, and the sidecar never generates musical content.

3. **Vertical slices**: Each phase delivers a playable, testable milestone — not a
   horizontal layer of infrastructure. Prefer "plays a note with correct timing" over
   "implements the full pitch type system."

4. **Ableton Link from day one**: Link SDK is included in the sidecar from the first
   build. It is not added later. The temporal model must be correct from the start.

5. **Clojure musical purity**: Pitch, scale, chord, duration, pattern, rhythm are
   immutable Clojure values. No state, no side effects, no JVM timing dependency.

---

## Repository Structure

```
cljseq/
├── src/
│   └── cljseq/
│       ├── sidecar.clj      ; IPC client: launch, connect, schedule!
│       ├── time.clj         ; virtual time, sleep!, in-thread
│       ├── link.clj         ; Link API (wraps sidecar IPC)
│       ├── pitch.clj
│       ├── scale.clj
│       ├── chord.clj
│       ├── duration.clj
│       ├── interval.clj
│       ├── rhythm.clj
│       ├── seq.clj          ; live-loop, cue!, sync!, pattern, motif
│       ├── harmony.clj      ; with-harmony, chord-seq, progression
│       ├── midi.clj         ; MIDI device model (data), feeds sidecar
│       ├── osc.clj          ; OSC message construction, feeds sidecar
│       ├── tuning.clj       ; Scala .scl/.kbm, microtonality
│       ├── analyze.clj      ; key detection, voice leading, post-tonal
│       └── corpus.clj       ; Bach chorales, search
├── native/
│   ├── CMakeLists.txt       ; top-level; adds all three targets
│   ├── third_party/
│   │   ├── link/            ; Ableton Link SDK (git submodule)
│   │   ├── rtmidi/          ; RtMidi (git submodule)
│   │   ├── rtaudio/         ; RtAudio (git submodule)
│   │   └── libpd/           ; libpd (git submodule)
│   ├── libcljseq-rt/        ; shared C++ library (static or dynamic)
│   │   ├── CMakeLists.txt
│   │   ├── include/
│   │   │   ├── ipc_framing.hpp      ; message types, encode/decode
│   │   │   ├── synth_target.hpp     ; SynthTarget abstract interface
│   │   │   ├── link_engine.hpp      ; ableton::Link wrapper
│   │   │   ├── osc_codec.hpp        ; OSC encode/decode
│   │   │   ├── spsc_queue.hpp       ; lock-free single-producer queue
│   │   │   └── logging.hpp          ; stderr logger
│   │   └── src/
│   │       ├── ipc_framing.cpp
│   │       ├── link_engine.cpp
│   │       └── osc_codec.cpp
│   ├── cljseq-sidecar/      ; MIDI/OSC timing process (links libcljseq-rt)
│   │   ├── CMakeLists.txt
│   │   ├── include/
│   │   │   ├── dispatcher.hpp
│   │   │   ├── midi_out.hpp
│   │   │   ├── osc_out.hpp
│   │   │   └── ipc_server.hpp
│   │   └── src/
│   │       ├── main.cpp
│   │       ├── dispatcher.cpp
│   │       ├── midi_out.cpp
│   │       ├── osc_out.cpp
│   │       └── ipc_server.cpp
│   └── cljseq-audio/        ; plugin host / audio engine (links libcljseq-rt)
│       ├── CMakeLists.txt
│       ├── include/
│       │   ├── audio_engine.hpp
│       │   ├── clap_target.hpp
│       │   ├── pd_target.hpp
│       │   └── ipc_client.hpp
│       └── src/
│           ├── main.cpp
│           ├── audio_engine.cpp
│           ├── clap_target.cpp
│           ├── pd_target.cpp
│           └── ipc_client.cpp
├── test/
│   └── cljseq/
│       ├── time_test.clj
│       ├── pitch_test.clj
│       ├── scale_test.clj
│       └── ...
├── project.clj
└── doc/
```

---

## Phase 0 — Sidecar Skeleton + IPC (C++ + Clojure)

**Goal**: A sidecar process that the Clojure side can start, ping, and shut down.
No MIDI, no Link yet. This phase establishes the IPC contract and the build system.

### C++ deliverables

**`libcljseq-rt` (built first; both binaries link against it)**

- [ ] Top-level `native/CMakeLists.txt` with three targets: `cljseq-rt` (static lib),
  `cljseq-sidecar` (executable), `cljseq-audio` (executable)
- [ ] Link SDK, RtMidi, RtAudio, libpd as git submodules under `native/third_party/`
- [ ] `ipc_framing.hpp/.cpp`: message types enum, `encode_frame()`, `decode_frame()`
- [ ] `synth_target.hpp`: `SynthTarget` abstract class (note_on, note_off, param_change,
  produces_audio, process, params)
- [ ] `link_engine.hpp/.cpp`: `LinkEngine` wrapping `ableton::Link`; `capture_audio_state()`,
  `capture_app_state()`, `commit_app_state()`
- [ ] `osc_codec.hpp/.cpp`: OSC type-tag string encode/decode (no external dep)
- [ ] `spsc_queue.hpp`: lock-free single-producer/single-consumer queue template
- [ ] `logging.hpp`: `log_info()`, `log_warn()`, `log_error()` writing to stderr

**`cljseq-sidecar` (links `cljseq-rt`)**

- [ ] Standalone Asio available via Link's bundled headers
  (`modules/link/include/ableton/platforms/asio/`); no separate Asio dependency
- [ ] `asio::io_context` constructed in `main()`; shared with Link SDK
- [ ] Asio thread pool: `std::thread` × N calling `io_ctx.run()`
- [ ] Unix domain socket IPC server via `asio::local::stream_protocol::acceptor`
- [ ] IPC message framing delegated to `libcljseq-rt`'s `ipc_framing`
- [ ] Ping (`0xFF`) / Pong (`0xFF`) round-trip
- [ ] Signal handling: `asio::signal_set` for `SIGTERM`/`SIGINT` graceful shutdown
- [ ] Logging via `libcljseq-rt` logger

**`cljseq-audio` (links `cljseq-rt`) — skeleton only in Phase 0**

- [ ] Empty `main.cpp` that links `cljseq-rt` and exits cleanly; confirms the shared
  library builds and links correctly on the target platform
- [ ] IPC client stub (connect to a Unix socket path passed as argv) — used in Phase 6
  when audio engine features are added

### Clojure deliverables

- [ ] `cljseq.sidecar` namespace
  - `(start! opts)` — launch sidecar subprocess, resolve socket path
  - `(stop!)` — send shutdown signal, wait for process exit
  - `(ping!)` — send ping, await pong with timeout; throws on failure
  - `(send-raw! msg-type payload)` — low-level send
  - `(connected?)` — true after successful ping

### Test milestone

```clojure
(sidecar/start! {})
(sidecar/ping!)      ;; => :ok within 10ms
(sidecar/stop!)
```

---

## Phase 1 — Timing Core + MIDI Dispatch (C++ + Clojure)

**Goal**: Play a MIDI note with accurate timing. This is the proof-of-concept for the
entire architecture.

### C++ deliverables

- [ ] `Dispatcher` class
  - `std::priority_queue` ordered by `time_ns`
  - `std::mutex` + `std::condition_variable` for producer/consumer
  - Dispatch thread: `pthread_setschedparam(SCHED_FIFO, 90)` on Linux;
    `THREAD_TIME_CONSTRAINT_POLICY` on macOS; MMCSS `Pro Audio` on Windows
  - Sleep primitive: `clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME)` on Linux;
    `mach_wait_until` on macOS; `CreateWaitableTimerEx` on Windows
  - Overrun detection: log when `delivery_time - actual_time > 1ms`
- [ ] `MidiOut` class wrapping RtMidi
  - Device enumeration → list pushed to JVM via IPC on startup
  - `send_note_on(port, channel, note, velocity)` — immediate
  - `send_note_off(port, channel, note, velocity)` — immediate
  - `send_cc(port, channel, cc, value)` — immediate
- [ ] IPC handler for message types `0x01` (note-on) and `0x02` (note-off):
  enqueue into Dispatcher with `time_ns`

### Clojure deliverables

- [ ] `cljseq.time` namespace
  - Dynamic vars: `*virtual-time*` (plain rational, not atom — see Q1),
    `*beat*`, `*start-time*`, `*bpm*`, `*sched-ahead-ns*`
  - `(sleep! beats)` — virtual time advance + `LockSupport/parkNanos`
  - `(in-thread & body)` macro — captures bindings, spawns future
  - `(beat->ns bpm beats)` — pure conversion function
- [ ] `cljseq.midi` namespace (data layer only — no device I/O)
  - `(note-on port ch note vel time-ns)` → IPC message map
  - `(note-off port ch note vel time-ns)` → IPC message map
  - `(cc port ch cc-num val time-ns)` → IPC message map
- [ ] `cljseq.sidecar/schedule!` — serialize event map → binary IPC message → send

### Test milestone

```clojure
;; 120 BPM, play C4 for one beat starting 100ms from now
(binding [*bpm* 120 *sched-ahead-ns* 100000000]
  (let [now-ns (System/nanoTime)
        on-ns  (+ now-ns *sched-ahead-ns*)
        off-ns (+ on-ns (beat->ns *bpm* 1))]
    (sidecar/schedule! (midi/note-on 0 1 60 100 on-ns))
    (sidecar/schedule! (midi/note-off 0 1 60 0   off-ns))))
```

Verify with a MIDI monitor: note arrives within 1ms of target.

---

## Phase 2 — Virtual Time Model + Live Loop (Clojure)

**Goal**: `live-loop` runs correctly; redefinition picks up on the next iteration;
`in-thread` spawns synchronized child threads.

### Clojure deliverables

- [ ] `cljseq.time`: `sleep!` full implementation with overrun detection
  - `*overrun-threshold-ns*` (default 10ms = weak warning, 100ms = kill)
- [ ] `cljseq.seq` namespace
  - `(live-loop name & body)` macro
    - Named loop registry (`defonce` atom of `{name → fn-atom}`)
    - Re-evaluation atomically replaces `fn-atom`; current iteration runs to
      completion before swap takes effect (see Q2: full-iteration semantics)
    - `(stop-loop! name)` — sets a poison-pill flag; loop exits after current iter
    - `(stop-all!)` — stops all running loops
  - `(cue! name & args)` — store in `EventHistory`, notify waiting threads
  - `(sync! name)` — block with time-windowed history lookup (see Q3)
  - `EventHistory` — `java.util.concurrent.ConcurrentSkipListMap` keyed by
    `[virtual-time, name]`
- [ ] Resolve Q4 naming: adopt `(tset! k v)` / `(tget k)` for time-indexed state

### Test milestone

```clojure
(live-loop :kick
  (sidecar/schedule! (midi/note-on 0 10 36 100 (+ *virtual-time* *sched-ahead-ns*)))
  (sleep! 1))

;; Runs for 4 beats, then redefine:
(live-loop :kick
  (sidecar/schedule! (midi/note-on 0 10 36 127 (+ *virtual-time* *sched-ahead-ns*)))
  (sleep! 1/2))

;; Verify: new body takes effect on next iteration; no gap or double-fire
```

---

## Phase 3 — Musical Abstractions (Clojure)

**Goal**: The full pitch/scale/chord/duration/interval value system. No timing
dependency — all pure functions.

### Clojure deliverables

- [ ] `cljseq.pitch`
  - Pitch record: `{:step :C :octave 4 :accidental nil :microtone 0}`
  - Derived views: `pitch->midi`, `pitch->ps`, `pitch->freq`, `pitch->pc`
  - `(spell midi)` — MIDI int → Pitch (with spelling inference)
  - `(enharmonic? p1 p2)` — same pitch space, different spelling
- [ ] `cljseq.interval`
  - `(interval name-kw)` — `:M3`, `:m7`, `:P5`, etc.
  - `(interval semitones)` — chromatic, spelling inferred
  - `(interval p1 p2)` — from two pitches
  - Properties: `:semitones`, `:interval-class`, `:directed`, `:diatonic`
- [ ] `cljseq.duration`
  - Duration record using Clojure rationals for `quarter-length`
  - `(duration type)` — `:quarter`, `:eighth`, `:whole`, etc.
  - `(dotted d)`, `(tuplet d actual normal)` — transformers
- [ ] `cljseq.scale`
  - IntervalNetwork representation (directed edge graph)
  - `(pitch-at scale root degree)`, `(degree-of scale root pitch)`
  - `(next-pitch scale root pitch direction)`
  - `(pitches scale root lo hi)` — lazy seq
  - Bundled scales: port Overtone's 50+ scales + Sonic Pi additions
- [ ] `cljseq.chord`
  - `(chord root quality)`, `(chord-at scale root numeral)`
  - Voicings: `:close`, `:open`, `:drop2`, `:drop3`
  - Post-tonal: `prime-form`, `forte-class`, `interval-vector`
  - `(progression key mode numerals)` → seq of chord maps

### Test milestone

```clojure
(= (pitch->midi (pitch :C 4)) 60)
(= (pitch->midi (pitch :B# 3)) 60)
(enharmonic? (pitch :C# 4) (pitch :Db 4))   ;; => true
(pitch-at major-scale :C 5)                  ;; => {:step :G, :octave 4, ...}
(chord :C :dom7)                             ;; => #{C4 E4 G4 Bb4}
```

---

## Phase 4 — Sequencing DSL (Clojure)

**Goal**: Pattern/rhythm orthogonality, Euclidean rhythm, harmonic context,
probabilistic triggers. `play!` wired to sidecar.

### Clojure deliverables

- [ ] `cljseq.harmony`
  - `*harmony*` dynamic var: `{:key :C :mode :major :degree :I :chord-type :maj7}`
  - `(with-harmony opts & body)` macro
  - `(resolve-note selector harmony)` — chord/scale index → Pitch
- [ ] `cljseq.rhythm`
  - `(euclid steps pulses)` → binary vector; `(euclid steps pulses rotate)`
  - `(rhythm velocities)` — nil = rest, `:tie` = tie
  - Clojure `Metronome` record: rational BPM arithmetic
- [ ] `cljseq.seq` additions
  - `(pattern type indices)` — `:chord`, `:scale`, `:pitch` selectors
  - `(motif pattern rhythm opts)` — zip and play; independent cycle lengths
  - `(play! note-or-chord opts)` — resolves pitch, computes timestamp, → sidecar
  - `(when-prob p & body)`, `(lfo opts)`, `(phrase opts)`
  - `(with-bpm bpm & body)`, `(with-bpm-mul factor & body)`

### Test milestone

```clojure
(live-loop :bass
  (with-harmony {:key :C :mode :dorian :degree :I}
    (let [pat (pattern :chord [1 5 3 2])
          rhy (rhythm [100 nil 80 nil])]
      (motif pat rhy {:channel 2 :clock-div 1/4}))
    (sleep! 1)))

;; Verify: 4 notes per bar, chord-relative pitches, independent cycle drift after 4 bars
```

---

## Phase 5 — Ableton Link Integration (C++ + Clojure)

**Goal**: Multi-machine beat alignment. Loops phase-lock to Link session.

### C++ deliverables (sidecar)

- [ ] `LinkEngine` class (see §15.2 C++ sketch)
  - `ableton::Link` instance, quantum configurable
  - `captureAudioSessionState()` called from dispatch thread for `time_at_beat`
  - `captureAppSessionState()` called from IPC handler thread for `set_bpm`
  - Callbacks: tempo, peers, start/stop → push `0x80` messages to JVM
- [ ] IPC handlers: `0x20` (set BPM), `0x21` (force beat), `0x22` (start/stop sync),
  `0x23` (play), `0x24` (stop)
- [ ] Request/response: `0x30`/`0xB0` (beat-at-time), `0x31`/`0xB1` (time-at-beat)
- [ ] MIDI clock output (24 PPQ) generated from Link timeline in dispatch thread

### Clojure deliverables

- [ ] `cljseq.link` namespace (see §15.8 API sketch)
- [ ] `sleep!` Link branch: `(link/time-at-beat target-beat *quantum*)` replaces
  local BPM formula
- [ ] `live-loop` Link preamble: park until `(link/next-quantum-start-ns)`
- [ ] `link-bpm` atom updated from push notifications; `*bpm*` synced at loop start
- [ ] Start/stop sync: `live-loop` polls `(link/playing?)` at each `sleep!` boundary

### Test milestone

1. Run cljseq on two machines on the same LAN
2. Start `live-loop` on machine A; start `live-loop` on machine B
3. Both loops should align to the same beat grid within one quantum (4 beats)
4. Change BPM on machine A; machine B's loop accelerates/decelerates to match
5. Verify: phase error < 1ms measured via simultaneous MIDI note capture

---

## Phase 6 — Extended Features

### 6a — OSC Output + OSC Server (C++ + Clojure)

**OSC output (sidecar → external targets):**

- [ ] `OscCodec`: encode/decode OSC 1.0 messages and bundles (type-tag string,
  argument types `i f s b`, NTP bundle timestamp). Implement in-house — OSC wire
  format is simple and a custom codec avoids a dependency.
- [ ] NTP timestamp alignment: at sidecar startup, compute
  `ntp_epoch_offset = wall_time_since_ntp_epoch - (System/nanoTime equivalent)`;
  use this offset to convert internal `time_ns` to the 64-bit NTP format that OSC
  bundles require.
- [ ] `OscOut`: `asio::ip::udp::socket`, `async_send_to` for non-blocking delivery.
  The RT dispatch thread posts `asio::post(io_ctx, [bundle]{ osc_out.send(bundle); })`
  to keep async I/O off the RT thread.
- [ ] IPC type `0x10` (OSC bundle) handler: decode from IPC frame → post to Asio for
  async UDP send at sidecar-computed delivery time.
- [ ] Test: send `/s_new default` to SuperCollider; verify sample-accurate scheduling
  against scsynth's bundle timestamp mechanism.

**OSC server — incoming OSC (external → sidecar → JVM):**

This is the pattern analogous to Sonic Pi's `tau` sidecar: a GC-free process
receives incoming OSC (from VCV Rack, TouchOSC, hardware OSC controllers) and
forwards matched events to the JVM without any GC-induced jitter on the receive side.

- [ ] `OscServer`: `asio::ip::udp::socket` in server mode, `async_receive_from` loop.
  Listens on a configurable port (default 57121, distinct from SuperCollider's 57110).
- [ ] OSC address pattern matching (OSC 1.0: `?`, `*`, `[ranges]`, `{alt,ernates}`).
  Maintain a `std::unordered_map<std::string, HandlerInfo>` of registered addresses;
  on receive, match against registered set and discard unmatched messages before
  any IPC overhead.
- [ ] Incoming matched OSC → IPC type `0x40` pushed to JVM asynchronously.
- [ ] IPC types `0x50`/`0x51`: JVM registers/deregisters OSC address patterns with
  the sidecar. Sidecar updates its match set; subsequent incoming messages matching
  the pattern are forwarded.
- [ ] Clojure: `cljseq.osc` namespace
  - `(on-osc address fn)` — register IPC type `0x50`; incoming `0x40` events fire
    `fn` as a `cue!` (inheriting virtual time from the cue mechanism)
  - `(send-osc! address args opts)` — encode to IPC type `0x10`, submit to sidecar
  - `(osc-bundle events time-ns)` — construct a bundle for simultaneous dispatch
- [ ] `(on-osc "/vcv/cv1" (fn [val] (set-param! :filter-cutoff val)))` — typical use:
  VCV Rack CV value controlling a cljseq parameter in real time

### 6b — Microtonality / Scala (Clojure)

- [ ] `cljseq.tuning` namespace
  - `(parse-scl text)`, `(parse-kbm text)` — pure parsers
  - `(build-freq-table scl kbm)` — MIDI note → Hz map
  - `(retune-note midi-note scl kbm pb-range)` → `{:note :pitch-bend}`
  - Named scale library (subset of Scala archive)
  - `(use-tuning name-or-scl)` — sets `*tuning*` dynamic var
- [ ] MTS SysEx generation wired to sidecar MIDI output

### 6c — Tone Row + Misha-style Navigation (Clojure)

- [ ] `cljseq.tone-row` namespace
  - `retrograde`, `invert`, `retrograde-invert`, `transpose`, `row-matrix`
  - Generalized rows over any scale
  - `(advance row-state interval)` → `[pitch next-state]`
  - `(play-row row opts)` — `:prime`, `:retrograde`, `:pendulum`, `:random`

### 6d — Unified Control Tree (Clojure)

*Implements §16. MIDI input surface is now tree bindings, not ad-hoc handlers.*

- [ ] `cljseq.ctrl` namespace
  - `ConcurrentHashMap<String, Node>` tree registry
  - `(defnode path opts)` — register value node (type, range, default); backed by atom
  - `(defmethod* path opts fn)` — register method node
  - `(get path)` — deref value node atom by path
  - `(set! path val)` — CAS write to value node atom; fires watches; validates type
  - `(call! path & args)` — invoke method node fn
  - `(ls path)` — list immediate children
  - `(describe path)` — full node metadata + current value + bindings
  - `(watch! path fn)` — register change observer (for TUI / display)
  - `(snapshot)` / `(restore! m)` — preset serialization
- [ ] `(bind! source-map path & opts)` — register a protocol binding
  - Source maps: `{:midi/cc N}`, `{:midi/note-on N}`, `{:osc address}`, `{:link/bpm}`
  - `:transform fn` for value nodes; `:args-fn fn` for method nodes
  - Conflict detection at bind time (Q9)
- [ ] `(deflive-loop path param-map body-fn)` — registers loop + param subtree +
  standard method nodes (`trigger`, `stop`); `(live-loop name ...)` is sugar for this
  with empty param-map and auto-generated path `/cljseq/loops/<name>` (Q11)
- [ ] `(defdevice path device-opts param-map)` — registers external MIDI device as
  tree node; writing a child node → MIDI CC send via `ctrl/send!` (Q10)
- [ ] MIDI input integration: sidecar receives MIDI, pushes IPC type `0x60`
  (new) to JVM; `cljseq.ctrl` dispatches to matching bindings
- [ ] OSC input integration: incoming `0x40` events route directly to tree by
  OSC address match — no registration needed beyond the tree node existing

### 6e — Control-Plane Server: Ring/OSC/HTTP/MCP (Clojure)

*Implements §17 and §18. Depends on §16 control tree (Phase 6d).*

- [ ] Add dependencies: `aleph`, `manifold`, `metosin/reitit` to `project.clj`
- [ ] `cljseq.server.handler` — core tree handler + middleware stack
  (`wrap-coerce`, `wrap-osc-wildcards`, `wrap-log`)
- [ ] `cljseq.server.http` — Ring ↔ `ctrl/*` adapter; HTTP start/stop
- [ ] `cljseq.server.osc` — `aleph.udp` socket; OSC decode → normalized request;
  optional reply encoding; control-plane port (57121) distinct from sidecar
  data-plane path (Q12 resolution)
- [ ] `cljseq.server.ws` — WebSocket subscription handler; `ctrl/watch!` integration
- [ ] Content negotiation: JSON (default), EDN, HTML (Hiccup SSR + HTMX for live
  value updates via WebSocket) — Q14 resolution
- [ ] `cljseq.server.mcp` — MCP JSON-RPC handler
  - `tools/list` → enumerate method nodes from tree
  - `tools/call` → `ctrl/call!` with arg coercion
  - `resources/list` / `resources/read` → `ctrl/ls` / `ctrl/describe` + value
  - `prompts/list` / `prompts/get` → static prompt registry
  - SSE stream for `notifications/resources/updated` (pushed by `ctrl/watch!`)
- [ ] `repl/eval` tool: per-session authorization prompt; namespace whitelist
  (`cljseq.user` by default); session transcript log (Q13)
- [ ] `cljseq.server/start!` / `stop!` — unified lifecycle; both HTTP and OSC
  share the same aleph `NioEventLoopGroup`
- [ ] Test: `curl http://localhost:7000/cljseq/global/bpm` returns current BPM;
  `curl -X PUT -d 138 /cljseq/global/bpm` changes it; TouchOSC OSC message does
  the same; MCP `tools/call ctrl_set` does the same

### 6f — Music21 Python Sidecar (Clojure + Python)

*Implements §20. Depends on: Python ≥ 3.10 and `pip install music21` on the host.*

- [ ] `sidecar/m21_server.py` — asyncio JSON-RPC 2.0 server over Unix socket (§20.9)
  - Length-prefixed framing (`u32` big-endian + UTF-8 JSON)
  - Method dispatch table: `corpus.*`, `analyze.*`, `tone-row.*`, `score.*`, `session.*`
  - Session handle registry (`_handles` dict) for server-side score objects
  - Graceful shutdown on `SIGTERM`
- [ ] `cljseq.m21` Clojure namespace (§20.6)
  - `(start!)` / `(stop!)` / `(running?)` lifecycle; `ProcessBuilder` launch
  - Background sender + reader threads; promise registry
  - Full RPC API: `analyze-key`, `analyze-key-floating`, `analyze-roman`,
    `check-voice-leading`, `prime-form`, `forte-class`, `interval-vector`
  - Tone row API: `tone-row`, `row-prime`, `row-inversion`, `row-retrograde`, `row-ri`,
    `row-matrix`, `row-all-forms`
  - Corpus API: `search-corpus`, `parse-corpus`, `parse-file`, `soprano`, `part`, `measures`
  - Export API: `export-xml`, `export-midi`, `show`
  - `(m21/eventually! atom method params)` helper for safe use near live loops (Q20)
- [ ] `cljseq.analyze` namespace — thin adapters delegating to `cljseq.m21` (§20.11)
- [ ] `cljseq.corpus` namespace — thin adapters delegating to `cljseq.m21` (§20.11)
- [ ] MCP bridge: register `m21_*` tools in `cljseq.server.mcp` dispatch (§20.12)
- [ ] Test: `(m21/start!)` + `@(m21/analyze-key [{:pitch/midi 60} ...])` returns key map;
  `@(m21/parse-corpus "bach/bwv66.6")` returns handle; `@(m21/soprano handle)` returns
  note sequence; corpus round-trip from EDN → MusicXML → parse → EDN preserves MIDI numbers

### 6j — Bloom-Inspired Sequence Architecture

*Implements §24. Depends on §3 (pitch/scale), §5 (rhythm), Phase 2 (live-loop).*

- [ ] **Unified step map spec** (`cljseq.step`):
  - `clojure.spec.alpha/def ::step` — validates all step map keys
  - Keys: `:pitch/midi :pitch/cents :dur/beats :gate/on? :gate/len :gate/ratchet`
    `:note/slew :mod/mode :mod/value :mod/shape :mod/rate`
  - `(step/make & kvs)` — constructor with defaults; validates via spec in dev mode
  - `play!` overload: `(play! target step-map)` dispatches note + ratchet + mod bundle

- [ ] **`cljseq.bloom` namespace** — sequence transformation algebra (§24.3, §24.4):
  - `(bloom/reverse-seq seq)` — reverse step order
  - `(bloom/inverse-seq seq scale root)` — mirror pitches around root
  - `(bloom/transpose-seq seq semitones scale)` — diatonic transposition
  - `(bloom/mutate-seq seq prob scale & opts)` — two-zone pitch/gate mutation
  - `(bloom/randomize-seq len scale & opts)` — fresh random sequence
  - `(bloom/build-tree trunk & {:keys [transforms branches scale mutate-prob]})` →
    vector of step-map sequences `[trunk b1 b2 ... bN]`
  - `(bloom/path branches & {:keys [path indices]})` → lazy seq of steps
  - `(bloom/resize-seq seq target-len algorithm)` — :spread :stretch :clone
  - `(bloom/rotate-seq seq offset)` — shift start point
  - All traversal order generators: `step-indices` multimethod with
    `:forward :reverse :pendulum :random :converge :diverge :converge-diverge :page-jump`

- [ ] **Ornamentation** (§24.5):
  - `(bloom/ornament step type context)` → seq of sub-step maps; durations sum to original
  - All 14 ornament types: anticipation, suspension, syncopation, octave-up, fifth-up,
    half-turn-toward, half-turn-away, run-toward, run-away, turn, arp-toward, arp-away,
    mordent-up, mordent-down, trill
  - `(bloom/ornament-seq seq & {:keys [prob types scale]})` — probabilistic application
  - Ornaments are scale-degree-aware; "toward/away" computed from scale interval network

- [ ] **Per-step Mod output** (§24.6):
  - `:mod/mode :mod/value :mod/shape :mod/rate` in step map
  - `play-bloom-step!` (or `play!` overload) sends mod CC alongside note event
  - Mod Shapes mode: LFO within step duration; sidecar schedules at step start with
    LFO waveform pre-computed as CC value series

- [ ] **`bloom` macro** (§24.8):
  - `(bloom name & opts)` — creates trunk + branch tree context
  - Uses `defonce`; auto-registers in control tree at `/cljseq/bloom/<name>/`
  - Reactive trunk watch with filter (Q39 resolution): `(bloom/freeze! name)` / `(bloom/thaw! name)`
  - `(next-step! my-bloom)`, `(set-path! my-bloom N)`, `(reseed! my-bloom)`,
    `(unmutate! my-bloom)`, `(mutate! my-bloom)`, `(current-branch my-bloom)`

- [ ] **Implementation plan additions for Q41-Q43**:
  - Q41: Use plain maps + clojure.spec (no record type)
  - Q42: Overload `play!` to accept step maps
  - Q43: `bloom/path` accepts integer, keyword, or explicit index vector

- [ ] Test: build-tree produces correct branch count; inverse-seq mirrors correctly;
  ornament sub-steps sum to original duration; `bloom` macro persists across REPL
  redefine; `play!` with step map fires note + ratchet + mod CC bundle

### 6i — Stochastic Generative Primitives (Marbles-Inspired)

*Implements §23. Depends on §5 (rhythm), §16 (control tree), Phase 2 (virtual time).*

- [ ] `cljseq.random` namespace:
  - `(distribution :spread S :bias B)` → sampler fn `(fn [] → float in [0,1])`
  - Distribution shapes: point-mass (spread=0), gaussian (0.25), uniform (0.5-0.75), quantized (1.0)
  - `(weighted-scale scale-type weights)` → weighted scale map with `:scale/weights`
  - `(learn-scale-weights notes & {:keys [key]})` → pitch-class histogram → weighted scale
  - `(progressive-quantize value weighted-scale steps)` — steps [0,1] controls degree survival
- [ ] `cljseq.stochastic` namespace:
  - `(stochastic-rhythm & opts)` → lazy seq of `{:beat offset :channel k}` events
    - Modes: `:complementary-bernoulli :independent-bernoulli :three-states :divider :drums`
    - Parameters: `:bias :jitter :channels :rate`
    - Jitter: perturbs IPC event `time_ns` only; virtual time stays clean (Q35 resolution)
  - `(stochastic-sequence & opts)` → lazy seq of values (MIDI notes or floats)
    - Parameters: `:spread :bias :steps :scale :range :deja-vu :loop-len`
    - Continuous param support: `:deja-vu` and `:bias` accept atoms for live control
  - `(correlated-sequences n & opts)` → map of `{:ch0 ... :ch1 ... :chN ...}` lazy seqs
    - Parameters: `:correlation :spread :bias :steps :scale :deja-vu :loop-len`
  - `(make-deja-vu-source gen-fn loop-len deja-vu-atom)` — ring buffer wrapper
  - `(next! source)` — advance source and return next value
  - `(defstochastic name & opts)` macro — creates full T+X context; auto-registers in control tree;
    uses `defonce` for ring buffer persistence across REPL redefines
  - `(stochastic-loops name targets durs)` — drives N live-loops from a single stochastic context
- [ ] LFO-as-X-source: `stochastic-sequence` with `:rhythm :continuous` feeds `param-loop`
- [ ] Control tree registration: all `defstochastic` parameters auto-registered at
  `/cljseq/stochastic/<name>/t-bias`, `/x-deja-vu`, etc. for live CC/OSC control
- [ ] Test: `(stochastic-rhythm :mode :complementary-bernoulli :bias 0.6)` produces mutually
  exclusive events; `(stochastic-sequence :spread 0.5 :deja-vu (atom 0.8) :loop-len 4)`
  repeats a 4-step pattern 80% of the time; jitter test confirms virtual time is unperturbed

### 6g — World Music Scale and Rhythm Library

*Implements §21. Depends on §3 (tuning), §5 (rhythm primitives).*

- [ ] `resources/scales/maqam/` — Scala `.scl` files for common maqamat (Rast, Bayati,
  Hijaz, Saba, Nahawand, Kurd, Ajam, Hijaz Kar) on all common roots
- [ ] `resources/scales/gamelan/` — reference Pelog and Slendro `.scl` files;
  Kyahi Kanyut Mesem pelog (documented reference tuning)
- [ ] `resources/scales/indian/` — selected ragas as `.scl` files
- [ ] `resources/rhythms/iqa.edn` — Arabic iqa'at hit patterns (§21.2.4)
- [ ] `resources/rhythms/timeline.edn` — African timeline patterns (§21.4.1)
- [ ] `resources/rhythms/clave.edn` — Afro-Cuban clave patterns (§21.5)
- [ ] `cljseq.rhythm` namespace additions:
  - `(rhythm/iqa name)` → `{:hits [...] :period N :subdivisions N}`
  - `(rhythm/timeline name)` → same format
  - `(rhythm/clave name)` → same format
  - `(play-timeline! target timeline opts)` — play all hits in a timeline over one period

### 6h — Rhythmic Modulation and Parameter Animation

*Implements §22. Depends on §16 (control tree), Phase 1 (sidecar CC output).*

- [ ] `cljseq.rhythm` LFO primitives (§22.3):
  - `(lfo shape & opts)` → returns a value function `(fn [beat bpm] → value)`
  - Shapes: `:sine :cosine :triangle :sawtooth :square :s&h :envelope`
  - All shapes are beat-relative; frequency auto-adjusts with BPM via Link
- [ ] `param-loop` macro (§22.2):
  - Step mode (`:rhythm` = Euclidean/timeline/explicit): expands to `live-loop`
    that calls `ctrl/set!` on each hit
  - Continuous mode (`:rhythm :continuous`): dedicated LFO thread using
    `LockSupport/parkNanos` at configured `:rate hz` (default 100 Hz)
  - `:target` accepts both control tree paths and `{:midi/cc N :channel C}` maps
  - `:interp :step | :linear | :exp` — for step mode, interpolate between hits
- [ ] LFO scheduler thread: separate `Thread` per continuous `param-loop`;
  reads current BPM from `link/bpm` atom to compute tick interval; self-terminates
  on `param-loop` stop
- [ ] Sidecar IPC additions for CC automation:
  - IPC type `0x05` (`CONTINUOUS_CC`): `[u8 channel][u8 cc][u8 value][u64 time_ns]`
  - Sidecar's dispatcher sends at specified wall-clock time
- [ ] Arpeggiation library (§22.5):
  - `resources/arpeggios/builtin.edn` — hand-curated patterns (`:alberti`, `:waltz-bass`,
    `:bounce`, `:guitar-pick`, `:jazz-stride`, `:montuno`, `:euclid-5-8`, etc.)
  - `(arp/ls)`, `(arp/get name)`, `(arp/play! target pattern chord & opts)`
  - `(corpus/extract-arpeggios! opts)` — Music21-backed extraction (development-time;
    output committed to `resources/arpeggios/corpus-derived.edn`)
- [ ] `play-arpeggio!` function — resolves `:order` indices against current chord,
  applies `:rhythm` timing, calls `play!` on each note
- [ ] Test: `(param-loop :test :target "/test/val" :rhythm (euclid 3 8) :values (cycle [1 2 3]) :dur 2)`
  writes 3 values per 2-beat cycle; LFO mode updates at specified rate; BPM change
  causes rate recalculation

---

## Phase 7 — Platform Hardening

### C++ sidecar

- [ ] macOS: measure dispatch jitter with `mach_absolute_time`; document expected
  < 0.5ms at 50ms sched-ahead
- [ ] Linux: `mlockall(MCL_CURRENT|MCL_FUTURE)` to prevent page faults; confirm
  `SCHED_FIFO` is available without root (use `setcap cap_sys_nice+ep`)
- [ ] Windows: MMCSS registration (`AvSetMmThreadCharacteristics("Pro Audio", ...)`),
  `timeBeginPeriod(1)`
- [ ] Reconnect logic: if JVM crashes, sidecar keeps running; on reconnect, pending
  queue is flushed (no stale events)
- [ ] IPC backpressure: if the JVM sends events faster than sidecar can process (queue
  depth > threshold), push a flow-control message back to the JVM

### Clojure / JVM

- [ ] Minimize allocation on the hot path in `sleep!` and `sidecar/schedule!`
  (avoid boxed primitives, reuse byte buffers for IPC frames)
- [ ] JVM startup flags: `-XX:+UseZGC -XX:SoftMaxHeapSize=256m` for lower GC pause
- [ ] `defonce` guards on all loop registries and sidecar connections to survive
  namespace reload in the nREPL session

---

## Dependency Summary

| Dependency | Language | License | Usage |
|---|---|---|---|
| Ableton Link SDK | C++ (header-only) | MIT | Tempo/beat sync; bundles standalone Asio |
| Standalone Asio (via Link) | C++ (header-only) | BSL-1.0 | All async I/O in both native binaries |
| RtMidi | C++ | MIT | Cross-platform MIDI I/O (`cljseq-sidecar`) |
| RtAudio | C++ | MIT | Cross-platform audio I/O (`cljseq-audio`) |
| libpd | C | BSD | Embeddable Pure Data (`cljseq-audio`) |
| CMake ≥ 3.25 | Build | — | Native build system for all three targets |
| Clojure ≥ 1.12 | JVM | EPL | DSL runtime |
| nREPL | Clojure | EPL | Live coding interface |
| tools.namespace | Clojure | EPL | REPL reload support |
| aleph | Clojure | MIT | Netty-backed HTTP + UDP + WebSocket |
| manifold | Clojure | MIT | Async streams and deferreds (aleph dep) |
| metosin/reitit | Clojure | MIT | Data-driven routing |
| hiccup | Clojure | EPL | Server-side HTML rendering |

| Python ≥ 3.10 | Python | — | Music21 sidecar runtime |
| Music21 ≥ 9.0 | Python | BSD | Analysis, corpus, tone row, notation (§20) |
| MuseScore 4 (optional) | External | GPL | Notation display via `m21/show` |
| LilyPond 2.24+ (optional) | External | GPL | Notation rendering via Music21 |

No other runtime dependencies are required. Standalone Asio is not a separate
install — it is present in the Link SDK submodule. RtAudio and libpd are only
pulled into `cljseq-audio`; `cljseq-sidecar` does not link them. The shared
`libcljseq-rt` library has no dependency on RtMidi, RtAudio, CLAP, or libpd headers.

Music21 and Python are optional — the system runs fully without them; `cljseq.m21/start!`
fails gracefully if Python is not found, and all `m21/` calls return `nil` with a
warning rather than throwing.

**Asio vs Boost.Asio**: The standalone Asio bundled with Link uses the `asio::`
namespace. If Boost is desired (e.g., for Boost.Beast, Boost.System, or developer
familiarity), replace `#include <asio.hpp>` with `#include <boost/asio.hpp>` and
`asio::` with `boost::asio::` throughout — the API is otherwise identical. This
substitution should be a project-wide decision made before Phase 0.

---

## Open Questions Addressed by This Plan

| Question | Resolution in plan |
|---|---|
| Q1: `*virtual-time*` atom | Phase 2: plain rational, not atom |
| Q2: `live-loop` iteration boundary | Phase 2: full-iteration semantics |
| Q3: `sync!` late-cue edge case | Phase 2: time-windowed EventHistory |
| Q4: `get!`/`set!` naming | Phase 2: adopt `tset!`/`tget` |
| Q5: sched-ahead per-target | Phase 1: per-target config map in sidecar |
| Q6: Stream consumption | Deferred to Phase 6+ (not blocking core) |
| Q7: Ableton Link | Phase 5, with C++ sidecar from Phase 0 |
| Q15: `libcljseq-rt` boundary | Phase 0: interfaces only; no RtMidi/RtAudio/CLAP/libpd headers |
| Q16: Process topology | Phase 0: Option A (direct JVM→process connections) |
| Q17: CLAP parameter count | Phase 6: lazy tree + bulk-fetch IPC `0x75` |
| Q18: Pd patch hot-swap | Phase 6: pre-load pool + atomic swap at buffer boundary |
| Q19: `cljseq-audio` Link peers | Phase 5/6: measure drift; fallback to single-peer IPC forwarding |
| Q20: Music21 async from live-loop | Phase 6f: `m21/eventually!` helper; doc constraint |
| Q21: Score serialization canonical form | Phase 6f: defined in Python server `_note_to_json()` |
| Q22: In-process Music21 | Resolved: subprocess is sufficient |
| Q23: cljseq vs Music21 overlap | Resolved: §20.8 division-of-labor table |
| Q24: Music21 corpus licensing | Resolved: no bundling; runtime delegation |
| Q25: Maqam microtonal representation | Phase 6g: cents-offset + CLAP_EXT_TUNING |
| Q26: Gamelan tuning | Phase 6g: `.scl` files in resources/ |
| Q27: Non-Western corpus | Phase 6g: `m21/register-corpus-path!` |
| Q28: Colotomic layering | Phase 6g: `irama` atom + `at-sync!` |
| Q29: `param-loop` unification | Phase 6h: step = live-loop sugar; LFO = separate thread |
| Q30: LFO scheduling rate | Phase 6h: 100 Hz default; per-buffer for CLAP |
| Q31: Arpeggiation library format | Phase 6h: EDN pattern maps + corpus extraction |
| Q32: Polyrhythm phase coherence | Phase 6h: beat-1 anchor + `:restart-on-bar` |
| Q33: nREPL integration | Resolved: standard nREPL; optional middleware post-core |
| Q34: DEJA VU ring buffer identity | Phase 6i: defonce source outside live-loop |
| Q35: Jitter and virtual time | Phase 6i: perturb IPC time_ns only; virtual time stays clean |
| Q36: Weighted scale vs IntervalNetwork | Phase 6i: extend scale map with :scale/weights |
| Q37: Correlated sequences shared draw | Phase 6i: promise-per-step + parent+perturbation lerp |
| Q38: stochastic context control tree registration | Phase 6i: auto-register all atom parameters |
| Q39: Branch regeneration | Phase 6j: reactive watch + filter predicate + freeze!/thaw! |
| Q40: Ornament sub-step timing | Phase 6j: rational durations summing to step duration |
| Q41: Unified step map type | Phase 6j: plain map + clojure.spec/def ::step |
| Q42: play! overload for step maps | Phase 6j: play! accepts step map; dispatches bundle |
| Q43: Path navigation API | Phase 6j: accepts integer, keyword, or index vector |
