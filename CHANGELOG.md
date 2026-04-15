# Changelog

All notable changes to cljseq are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.11.0] — 2026-04-14

### Added

#### Process supervisor (`cljseq.supervisor`)

- **`cljseq.supervisor` namespace** — Erlang-style background watchdog that
  monitors registered services (SC server, sidecar, live loop threads), emits
  lifecycle events, optionally auto-restarts failed services, and restores
  last-known state after recovery
- **Event bus** — `on-event!` / `off-event!` register per-(service, event-type)
  handlers identified by a user-chosen key; `emit!` fires all registered
  handlers, swallowing individual handler exceptions so one bad handler can't
  suppress others
  - `:up` — service transitioned from `:down`/`:unknown` to `:up`
  - `:down` — service transitioned from `:up`/`:unknown` to `:down`
  - `:restore-failed` — restore-fn threw after a recovery
- **Service registry** — `register!` / `deregister!` with three optional hooks:
  `:check-fn` (health probe), `:restart-fn` (reconnect attempt), `:restore-fn`
  (state reload after recovery); `service-status` / `all-statuses` / `any-down?`
  for status queries
- **Built-in registrations** — `register-sc!` (health=`sc-connected?`,
  restore=`resend-sent-synthdefs!`, optional `:restart-fn`) and
  `register-sidecar!` (health=`sidecar/connected?`,
  restart=`restart-sidecar!`) wire up the two primary external processes with
  a single call
- **Watchdog thread** — `start-watchdog!` / `stop-watchdog!` control a named
  daemon thread (`cljseq-supervisor-watchdog`) that runs health checks and,
  optionally, dead loop-thread detection on each tick
- **BPM-derived scan interval** — when `:interval-ms` is not provided the
  watchdog sleeps one bar's worth of ms at the current BPM, re-derived each
  tick so a live `set-bpm!` is automatically reflected; `:bars` (default 1)
  and `:beats-per-bar` (default reads `core/get-beats-per-bar`) control the
  period; e.g. dropping to 72 BPM for doom territory slows the watchdog to
  match without any intervention
- **Loop pause/resume integration** — `deflive-loop` accepts `:pause-on-down`
  (list of service names) and `:resume-on-bar` (beat boundary, default 4);
  when a dependency is `:down` the loop sleeps one `:resume-on-bar` period and
  retries, maintaining virtual-time continuity without playing into silence; on
  recovery the loop re-enters at the next beat boundary automatically
- **29 tests, 34 assertions** in `cljseq.supervisor-test`

#### Global beats-per-bar (`core/system-state`)

- **`:beats-per-bar` in `core/system-state :config`** — global time-signature
  numerator stored alongside `:bpm`; defaults to `4`
- **`get-beats-per-bar` / `set-beats-per-bar!`** — accessors exported from
  `cljseq.user`; `set-beats-per-bar! 3` switches to waltz time globally
- **`start!` `:beats-per-bar` option** — `(start! :bpm 100 :beats-per-bar 3)`
  sets both clock and meter at boot; pattern for future meter-aware namespaces
- **`journey/start-bar-counter!` default** — the no-arg arity now reads
  `core/get-beats-per-bar` instead of hardcoding 4, so `set-beats-per-bar!`
  automatically propagates to new bar counters
- **`supervisor/start-watchdog!` default** — `:beats-per-bar` reads
  `core/get-beats-per-bar` on each tick when not explicitly overridden

#### Bombproof loop error recovery

- **`catch Throwable` in `deflive-loop`** — loop body exceptions now catch
  `Throwable` (not just `Exception`), covering `AssertionError`,
  `OutOfMemoryError`, and other JVM errors; class name is included in the
  stderr log so errors are diagnosable without killing the loop
- **Atomic stale-entry check** — the dead-thread cleanup at loop exit is now a
  single `swap!` that checks and removes the stale entry atomically, preventing
  a race where two exits could double-remove the same entry
- **`restart-loop!`** — restarts a dead loop thread aligned to the next N-beat
  bar boundary (`:align-beats`, default 4); safe to call on a live loop (returns
  nil); uses the same `-park-until-beat!` / `-start-beat-for-period`
  infrastructure as `deflive-loop`

#### SC and sidecar reliability

- **`sc-play!` stuck-note containment** — the release-daemon thread now catches
  and logs `free-synth!` failures (e.g. node already freed by SC server) to
  stderr instead of swallowing them silently; the caller thread is never
  interrupted
- **`resend-sent-synthdefs!`** — clears the sent-synthdefs cache and re-sends
  all previously loaded synthdefs to sclang; called automatically by
  `register-sc!` as the restore-fn so synthdefs survive an SC server restart
- **`restart-sidecar!`** — restarts the sidecar process using the opts from the
  last `start-sidecar!` call (saved in sidecar state as `:last-start-opts`);
  called automatically by `register-sidecar!` as the restart-fn

### Changed

- **`start!`** — accepts `:beats-per-bar` alongside `:bpm`; both are stored in
  `:config` and readable via `get-beats-per-bar`
- **`journey/start-bar-counter!`** — no-arg arity reads `core/get-beats-per-bar`
  (was hardcoded to 4); explicit-arity form still accepts a positional override

### Sessions

- **`sessions/rubycon-core.clj`** — Kosmische polyrhythmic session extended with
  a fourth S42 drone voice (SC-only; `drone-ramp!` fade helper), full
  four-movement arc wiring (`begin-emergence!` through `end-silence!`),
  Frippertronics SOS buffer section, and REPL variation palette

---

## [0.10.0] — 2026-04-11

### Added

#### Keyboard input framework (`cljseq.ivk`)

- **`cljseq.ivk` namespace** — extensible computer-keyboard control surface for
  live performance; the laptop keyboard becomes a real-time note/harmony input
  device without any additional hardware
- **Layout registry** — layouts are pure Clojure data maps (`{char action-map}`);
  `register-layout!` adds to the global registry; `set-layout!` switches live;
  `handle-action!` is an open multimethod — extend with `defmethod` for new
  action types without touching ivk core
- **Five built-in layouts:**
  - `:interval` — Samchillian/Misha-style diatonic interval steps; home row
    steps up/down by scale degree; lower row jumps by larger intervals; bracket
    keys shift octave
  - `:chromatic` — piano-style chromatic layout; home row = white keys, top row
    = black keys; familiar to keyboard players
  - `:scale` — absolute diatonic scale degree triggers; each key plays a fixed
    scale degree regardless of :current-pitch
  - `:ndlr` — NDLR-inspired chord surface; left hand (asdf) selects chord
    functions (I/II/IV/V/vi/vii); right hand (hjkl;) plays chord tones over the
    active chord; bracket key cycles chord extension depth (:triad/:seventh/:ninth)
  - `:harmonic` — TheoryBoard-inspired home row split; left hand (asdf) = harmonic
    context (I/IV/V/vi); right hand (hjkl;) = melody (chord tones + upper octave);
    number row = absolute scale root pivot pads (1=C..7=B, 8=♭, 9=♯)
- **Arp mode** — any layout supports arp playback; `start-arp!` / `stop-arp!`
  cycle queued notes at current Link tempo; `:arp-rate` default 1/4 beat
- **`:toggle-voiced-mode`** — when voiced mode is on, chord-function key presses
  also play the root note of the new chord (silent chord switch vs. voiced transition)
- **`:octave-offset`** on `:play-chord-tone` — reach upper octave chord tones
  without shifting :current-pitch; `:octave-offset 1` adds one octave to the note
- **`:set-scale-root`** — pivot to a new scale root by MIDI pitch class; preserves
  the interval pattern (mode); resets `:chord-fn` to :I; number row uses this
- **`:transpose-scale`** — shift scale root by semitones; 8=♭ (−1) and 9=♯ (+1)
  in the `:harmonic` layout
- **`:cc-delta` / `:pitch-bend-delta`** — modulation actions that update
  `:modulation` state and send CC/bend immediately via sidecar; supports static
  values or phasor functions for automated sweeps
- **`render-layout`** — renders any layout as a two-row ASCII keyboard cheatsheet
  (key labels + action labels); works on any registered keyword or inline layout map;
  exported as `(render-layout :harmonic)` from `cljseq.user`
- **Sidecar integration** — keyboard events arrive as `0x21 KbdEvent` frames from
  the C++ sidecar (macOS `CGEventTap`); `start-kbd!` registers the push handler
  and optionally restarts the sidecar with `--kbd` flag

#### MIDI input (`cljseq.midi-in`)

- **`cljseq.midi-in` namespace** — JVM MIDI input via `javax.sound.midi`;
  independent of the C++ sidecar; opens any available MIDI input port by index
  or name substring
- **`open-input!` / `close-input!` / `close-all-inputs!`** — lifecycle for MIDI
  input connections; multiple ports can be open simultaneously
- **`register-note-handler!` / `unregister-note-handler!`** — register callbacks
  for NoteOn/NoteOff events; handlers can filter by source port, channel, and
  pitch range; multiple handlers per port; thread-safe
- **T-1 PassThru pattern** — `register-note-handler!` with `:passthru? true` sets
  `ivk/set-pitch!` from incoming T-1 notes; the keyboard layout then navigates
  intervals relative to the T-1's last played note; enables T-1 + laptop keyboard
  as a unified performance instrument
- **`torso-t1.edn` device map** — complete NRPN and CC map for the Torso T-1;
  includes all sequencer parameters, euclidean rhythm controls, and MIDI output
  configuration; usable standalone or via T-1 PassThru pattern

#### Q32 — `:restart-on-bar` for `deflive-loop`

- **`:restart-on-bar` loop option** — snaps a new loop's start beat to a beat
  boundary; parks the loop body until the boundary arrives; works with or without
  Ableton Link active (parks unconditionally when set)
  - `true` — snap to next system quantum boundary (default: 4 beats, reads
    `:config :link/quantum`)
  - `<N>` — snap to next N-beat boundary (e.g. `{:restart-on-bar 2}` for 2-beat
    grid; `{:restart-on-bar 8}` for 2-bar grid)
  - `nil` / absent — default Link behaviour; no boundary snap

---

## [0.9.1] — 2026-04-10

### Changed

#### Attribution hygiene — generic API keywords (no trademarks)

- **`compile-fm :digitone` → `:4op-cc`** — public keyword no longer embeds a
  product trademark; internal functions renamed (`fm->op4-cc`,
  `op4-cc-algorithms`, `match-op4-cc-algorithm`).  The `:4op-cc` backend is
  documented as "Digitone-inspired" in the docstring.
- **`compile-fm :leviasynth` → `:8op-cc`** — same treatment; internal functions
  renamed (`fm->op8-cc`, `ratio->op8-pitch-cc`, `op8-pitch-cc`).
- **`defpatch! :solar42` → `:s42`** — patch keyword and all four constituent
  synth IDs renamed (`solar-drone-voice` → `s42-drone-voice`,
  `solar-vco-voice` → `s42-vco-voice`, `solar-papa-voice` → `s42-papa-voice`,
  `solar-filter` → `s42-filter`); EDN resource files renamed to match.
- **`doc/attribution.md`** — added table rows and prose sections for
  ASM Leviasynth, Elektron Digitone, and Soma Laboratory Solar 42.
- **User manual §30** — section title updated; `:8op-cc` backend note added with
  attribution pointer.

---

## [0.9.0] — 2026-04-10

### Added

#### MCP bridge (`cljseq.mcp`)

- **`cljseq.mcp` namespace** — MCP (Model Context Protocol) server that exposes
  the cljseq API as AI-legible compositional tools; runs as a standalone process
  communicating over stdio JSON-RPC with any MCP client (Claude Code, etc.)
- **`lein mcp` alias** — start the MCP server: `lein mcp [--nrepl-host H] [--nrepl-port P]`
- **`connect-nrepl!` / `disconnect-nrepl!`** — lifecycle for the nREPL connection to
  a running cljseq session (default: `localhost:7888`)
- **`process-message`** — public single-message entry point (used by tests and
  the main loop)
- **`run-server!`** — main loop; reads JSON-RPC messages from stdin until EOF
- **`-main`** — entry point; parses `--nrepl-host` / `--nrepl-port` args
- **20 MCP tools across three groups:**

  *Session control (12 tools):*
  - `evaluate` — eval any Clojure expression on the running session
  - `get-ctrl` / `set-ctrl` — read/write ctrl tree paths
  - `get-harmony-ctx` — current `*harmony-ctx*` (root, scale, tensions)
  - `get-spectral` — spectral analysis state (centroid, flux, density)
  - `get-loops` — running live loop inventory with tick counts
  - `define-loop` / `stop-loop` — hot-swap or stop a named loop
  - `play-note` — trigger a single note event
  - `set-bpm` — change master tempo (propagates to Link peers)
  - `get-config` / `set-config` — §25 configuration registry
  - `get-score` — full ctrl tree snapshot as EDN

  *Composition pipeline (5 tools):*
  - `ingest-midi` — full pipeline: parse → key/mode detect → voice separation →
    tension arc → outlier flag → resolution; binds result to `composition` in the
    nREPL session; returns a summary map
  - `show-corrections` — display outlier note proposals from the last ingest
  - `accept-corrections` — apply corrections, rebind `composition`
  - `play-score` — play the current `composition` score via the event engine
  - `save-score` — write `composition` to an EDN file (music area handoff artifact)

  *Topology (3 tools):*
  - `list-peers` — peer topology registry: all discovered nodes with backends
  - `evaluate-on-peer` — eval on any named node in the topology; proxied through
    `cljseq.remote/eval-on-peer!` on the primary nREPL
- **`*eval-fn*` / `*io-fns*` dynamic vars** — test injection for nREPL eval and
  stdin/stdout; no live network required in tests
- **26 tests, 144 assertions** in `cljseq.mcp-test`

---

## [0.8.0] — 2026-04-10

### Added

#### cljseq.composition (renamed from cljseq.midi-repair)

- **Namespace renamed** — `cljseq.midi-repair` → `cljseq.composition`; framing changed from
  "repair" to "composition": capturing a musical idea from hardware, working it as a Clojure
  value with the full cljseq vocabulary
- **`ingest!` replaces `repair-pipeline!`** — same pipeline, clearer name

#### Leviasynth 8-op FM (`cljseq.fm`)

- **Per-operator waveforms** — `:waveform` key per op; `:sin`, `:saw`, `:tri`, `:pulse`,
  `:sin-fb` (auto-selects `SinOscFB.ar` with `:feedback` depth)
- **TZFM routing** — routing values accept `{:to target :depth n :tzfm? true}`; frequency
  scaling makes timbre pitch-invariant; shorthand `{mod-id target-id}` still supported
- **Cross-feedback** — `:cross-feedback [{:from A :to B :depth n} ...]` generates
  `LocalIn.ar` / `LocalOut.ar` SC pairs for inter-operator feedback loops
- **5 new 8-op algorithm templates**: `:8op-stack`, `:4-pairs`, `:2x4-stacks`, `:8-carriers`,
  `:4mod-1`
- **4 named 8-op presets**: `:8op-brass` (series chain + TZFM), `:8op-bells` (4 C+M pairs
  with cross-feedback), `:8op-evolving-pad` (transient mods + `SinOscFB` op),
  `:8op-dx7-pno` (DX7-inspired piano)

#### OSC push-subscribe (§17 Phase 3)

- **`subscribe!`** — register `{host port}` to receive OSC push when a ctrl tree path changes;
  installs a `ctrl/watch!` watcher on the subscribed path
- **`unsubscribe!`** — cancel a specific subscription; removes the watcher when last subscriber leaves
- **`unsubscribe-all!`** — clear all subscribers for a path
- **`subscribers`** — return the subscriber registry snapshot
- **`ctrl-path->osc-address`** — convert `[:filter/cutoff]` → `"/ctrl/filter%2Fcutoff"` (inverse
  of the existing `parse-ctrl-path`); exported from `cljseq.user`
- **`/sub` wire route** — inbound OSC: `/sub <ctrl-addr> <callback-host> <callback-port>`;
  registers a subscription the same way as `subscribe!`
- **`/unsub` wire route** — inbound OSC: `/unsub <ctrl-addr> <callback-host> <callback-port>`
- **`/tree` wire route** — pull-on-demand: `/tree <ctrl-addr>`; server pushes the current value
  back to the packet's sender address; complements HTTP GET `/ctrl/<path>`
- **`*push-fn*` dynamic var** — injectable for tests; defaults to `osc-send!`
- All functions exported from `cljseq.user` as `osc-subscribe!`, `osc-unsubscribe!`,
  `osc-unsubscribe-all!`, `osc-subscribers`, `ctrl-path->osc-address`, plus the existing
  `start-osc-server!`, `stop-osc-server!`, `osc-running?`, `osc-port`, `osc-send!`

#### Configuration Registry (§25)

- **`cljseq.config`** — new namespace: registry of all §25 tunable parameters with
  defaults, types, docs, and validation functions
- **`get-config`** / **`set-config!`** / **`all-configs`** / **`all-param-keys`** / **`param-info`** —
  Clojure API for reading and writing config values with validation
- **Ctrl tree seeding** — `core/start!` calls `config/seed-ctrl-tree!` which creates typed
  ctrl nodes for all registry params; all 11 parameters immediately visible via
  `GET /ctrl/config/<key>` without extra wiring
- **BPM sync** — `core/set-bpm!` now writes to `[:config :bpm]` in the ctrl tree so
  `GET /ctrl/config/bpm` always reflects the live tempo
- **`set-config! :bpm v`** applies the full side effect (timeline reanchor + thread
  unpark + Link propagation) via the injected effect registry
- **`:link/quantum` connected** — `loop/-start-beat` reads the quantum from
  `system-state [:config :link/quantum]` (default 4) instead of a hard-coded literal;
  `set-config! :link/quantum 8` takes effect on the next loop start
- **`:ctrl/undo-stack-depth` connected** — `ctrl/push-undo` reads the depth from
  `system-state [:config :ctrl/undo-stack-depth]` (default 50)
- **`core/start!` resets config and undo stack** — each `start!` call resets `:config`
  to registry defaults (with BPM overridden by the `:bpm` argument) and clears
  `:undo-stack`, ensuring no state leaks between sessions
- All functions exported from `cljseq.user` as `get-config`, `set-config!`,
  `all-configs`, `all-param-keys`, `param-info`

#### Ableton Link Phase 2 — bidirectional BPM sync + transport control

- **BPM push (JVM → Link)** — `core/set-bpm!` now calls `link/set-bpm!` when Link is active,
  propagating local tempo changes to all Link peers automatically
- **`start-transport!`** — sends IPC 0x13 to the sidecar; commits `isPlaying=true` to the Link
  session state; all Link peers transition to playing
- **`stop-transport!`** — sends IPC 0x14; commits `isPlaying=false`; all peers transition to stopped
- **`on-transport-change!`** — register a `(fn [playing?])` callback fired on every Link transport
  state transition; hook key allows replacement and removal
- **`remove-transport-hook!`** — deregister by key; no-op if absent
- **`transport-hooks`** — return a snapshot of the registered hook map
- **C++ `LinkEngine::start_playing` / `stop_playing`** — uses
  `captureAppSessionState` + `setIsPlaying` + `commitAppSessionState` on the Ableton Link session
- **IPC 0x13 / 0x14** — new `MsgType` entries `LinkTransportStart` / `LinkTransportStop`
  dispatched in `ipc.cpp`; pure no-ops when Link is not active
- **`core/start!` clears Link state** — `start!` now dissocs `:link-state` and `:link-timeline`
  so a fresh system session always begins with Link inactive (prevents stale timeline leaking
  into tests)
- All new functions exported from `cljseq.user` as `link-enable!`, `link-disable!`, `link-bpm`,
  `link-peers`, `link-playing?`, `link-active?`, `link-set-bpm!`, `link-start-transport!`,
  `link-stop-transport!`, `link-on-transport-change!`, `link-remove-transport-hook!`,
  `link-transport-hooks`

#### Topology Layer 3 — nREPL remote eval + backend registry

- **`cljseq.bencode`** — minimal hand-rolled bencode encoder/decoder; implements the nREPL
  wire protocol without adding nREPL as a dependency (see namespace docstring for rationale)
- **`cljseq.remote`** — nREPL client: `connect!`, `disconnect!`, `remote-eval!`,
  `eval-on-peer!`, `with-peer` macro; `*open-connection*` dynamic var for test injection
- **Backend registry in `cljseq.peer`** — `register-backend!`, `deregister-backend!`,
  `active-backends`, `peer-backends`; beacon now includes `:backends` map so peers see
  what synthesis backends are reachable
- **`publish-session-profile!`** — pushes a session profile map to `[:session :profile]`
  in the ctrl tree for peer discovery
- **`connect-sc!` / `disconnect-sc!`** now auto-register / deregister the `:sc` backend
  in the peer backend registry
- All new public functions exported from `cljseq.user`:
  `connect-peer!`, `disconnect-peer!`, `remote-eval!`, `eval-on-peer!`, `with-peer`,
  `register-backend!`, `deregister-backend!`, `active-backends`, `peer-backends`,
  `publish-session-profile!`

---

## [0.7.0] — 2026-04-10

### Added

#### Waveshaping synthesis

- **`:wshape-saturate` synth** — tanh soft saturation on VarSaw; `:drive` 0–1 adds odd
  harmonics monotonically (sine → warm tube → square-ish); natural `apply-trajectory!` target
- **`:wshape-fold` synth** — wavefold (`fold2`) on SinOsc with optional FM; `:drive` 0–1
  adds both even and odd harmonics non-monotonically; each fold depth is a qualitatively
  distinct timbral state, not just "more"
- **Compiler support for `:tanh`, `:softclip`, `:distort`** — emit SC method-call syntax
  (`signal.tanh`, `signal.softclip`, `signal.distort`)
- **Compiler support for `:fold2`** — emits `signal.fold2(limit)` method call

#### Spectral bridge

- **`bind-spectral!`** — connects `cljseq.spectral` SAM output to any live SC node parameter
  via `ctrl/watch!`; fires on every SAM tick; returns a cancel fn; available in `cljseq.user`
- **`unbind-spectral!`** — detaches binding by node id and param key; available in `cljseq.user`
- Spectral keys available: `:spectral/centroid` (Hz brightness), `:spectral/density` (0–1
  busyness), `:spectral/blur` (0–1 spread)

#### DynKlank physical modeling

- **`:klank-bell` synth** — four-mode DynKlank bell using Chowning/Fletcher-Rossing inharmonic
  partial ratios (1.0, 2.756, 5.404, 8.933); `:fN`, `:aN`, `:dN` expose each mode's
  frequency ratio, amplitude, and decay time as live `set-param!` / trajectory targets
- **`:klank-bars` synth** — three-mode DynKlank bar instrument (marimba, vibraphone,
  glockenspiel voicings); BPF noise excitation; `:exc-bw` controls mallet hardness
- **`:klank-ensemble` patch** — bell1 + bell2 + bars voices in shared FreeVerb space;
  12 independently addressable frequency/amplitude/decay params plus `:effects-room`/`:effects-mix`
- **Compiler support for `:dyn-klank`** — emits SC backtick-Ref array syntax
  `` DynKlank.ar(`[[freqs],[amps],[decays]], excitation, freq_scale) ``

#### Example files

- **`examples/waveshaper_demo.clj`** — 8-section showcase: saturation and fold exploration,
  drive-arc comparison on both voices, `bind-spectral!` audio-reactive drive, chaos-to-waveshaper
  compound arc, per-note drive arc in live loop
- **`examples/klank_demo.clj`** — 8-section showcase: bell and bar variants, klank-ensemble,
  partial-ratio detuning guide, decay arc from xylophone to bell, live melody loop,
  `bind-spectral!` decay sculpting

#### Documentation

- **`doc/user-manual.md` §27** — Waveshaping and Spectral Bridge: saturation vs fold comparison,
  `:wshape-saturate` / `:wshape-fold` arg tables, `bind-spectral!` reference with spectral key table
- **`doc/user-manual.md` §28** — DynKlank Physical Modeling: DynKlank vs KS comparison,
  bell and bar voicing guide, decay as compositional arc, updated patch table

---

## [0.6.0] — 2026-04-10

### Added

#### SC synthesis vocabulary

- **`:pluck` synth** — Karplus-Strong plucked string via SC `Pluck` UGen; `:coef` controls
  reflection coefficient (string material: -1.0 bright/metallic → 0.95 dark gut)
- **15 Sonic-Pi/Overtone parity synths** — `:saw`, `:tri`, `:pulse`, `:subpulse`, `:dsaw`,
  `:dpulse`, `:dtri`, `:pretty-bell`, `:hollow`, `:zawa`, `:dark-ambience`, `:growl`,
  `:noise`, `:bass` registered from EDN at load time
- **Solar42-inspired instrument model** — four synths: `:solar-drone-voice` (6 detuned saws,
  per-oscillator gate + tuning ratio), `:solar-vco-voice` (VarSaw + PWM + sub), `:solar-papa-voice`
  (FM + AM + Sample&Hold noise blend), `:solar-filter` (dual cascaded RLPF, 24 dB/oct)
- **`:solar42` patch** — 4 drone + 2 VCO + 2 papa voices → dual RLPF filter → reverb;
  25 externally addressable params via `set-patch-param!` / `apply-trajectory!`
- **`:superkar-voice` synth** — warpable Karplus-Strong: `delaytime = warp / freq`;
  `warp ≠ 1.0` produces inharmonic partials (bell/gong character); per-voice body RLPF
  (`:body-freq`, `:body-res`)
- **`:superkar` patch** — 4-voice warpable KS ensemble (A2/E3/A3/E4) on shared bus + reverb;
  all warp/coef/body params externally addressable
- **Chaos synthesis** — three new synths:
  - `:chaos-lorenz` — Lorenz chaotic oscillator; `:chaos` [0..1] maps to Rayleigh number
    `r = 10 + 40 × chaos` (stable fixed point → butterfly attractor → deep chaos)
  - `:chaos-henon` — Hénon discrete map; period-doubling cascade audible as pitch subdivision
  - `:lorenz-fm` — FM synthesis with Lorenz chaos modulation; carrier destabilizes as `:chaos` rises
- **`:chaos-ensemble` patch** — two Lorenz voices (s=10 / s=12 for organic attractor divergence)
  + Hénon voice + reverb; all chaos/filter params externally addressable

#### Scale additions (`cljseq.scale`)

- `:pelog` — Balinese pelog (7-note equal-temperament approximation)
- `:slendro` — Javanese slendro (same intervals as `:yo`, named for gamelan context)
- `:hijaz` — Arabian hijaz maqam (same intervals as `:phrygian-dominant`)
- `:folk` — Folk/klezmer flavour (same as `:romanian-minor`)
- `:in-sen` — Japanese in-sen (alias for `:in-scale`)
- `:persian` — Persian maqam [1 3 1 1 2 3 1]; Dune palette scale, genuinely new interval pattern
- `:dune` — Double harmonic / Byzantine alias [1 3 1 2 1 3 1]; the "Arrakis sound"

#### Sample player (`cljseq.sample`)

- `defbuffer!` / `load-sample!` / `unload-sample!` — SC buffer lifecycle; idempotent by path
- `sample!` / `loop-sample!` — one-shot and looping playback
- `granular-cloud!` — convenience wrapper instantiating `:granular-cloud` patch with a named buffer
- `play!` `:sample` dispatch — routes `:sample` step-map key to SC buffer player
- `:granular-cloud` patch registered at load time (granular voice → reverb bus)

#### Freesound integration (`cljseq.freesound`)

- `set-freesound-key!` / `FREESOUND_API_KEY` env var — API authentication
- `fetch-sample! id kw` — downloads by Freesound ID to `~/.cache/cljseq/samples/`, registers buffer
- `fetch-and-load! id kw` — fetch + SC buffer allocation in one call
- `sound-info id` — fetch and cache Freesound metadata (JSON)
- `search-freesound query opts` — text search; returns candidates with license/tag info
- `load-essentials!` — bulk-fetches all catalog entries that have IDs; skips nil placeholders
- `load-and-prime-essentials!` — fetch + load into SC buffers
- `curate-essentials!` — searches Freesound for each nil-ID entry, prints candidate IDs
- `resources/samples/essentials.edn` — 56 entries mirroring Sonic-Pi category vocabulary
  (`:ambi/*`, `:bass/*`, `:bd/*`, `:elec/*`, `:guit/*`, `:loop/*`, `:perc/*`, `:sn/*`,
  `:tabla/*`, `:vinyl/*`); IDs are `nil` placeholders pending curation

#### Examples and documentation

- `examples/ks_string_demo.clj` — 6-section KS showcase: coef vocabulary, per-note arc
  sampling, dual-drone `apply-trajectory!` animation, chord section with simultaneous arcs,
  Solar42 filter sweep integration
- `examples/superkar_demo.clj` — 7-section SuperKarplus showcase: warp vocabulary,
  patch instantiation, staggered per-voice warp/body trajectories, live melody loop
- `examples/chaos_demo.clj` — 8-section chaos showcase: Lorenz/Hénon parameter exploration,
  bifurcation arc via `apply-trajectory!`, period-doubling cascade, chaos ensemble,
  live loop sampling chaos-arc for melodic filter color
- **User manual §25** — Sample Player and Freesound (buffer lifecycle, granular, `play!`
  dispatch, fetch-on-use API, essentials catalog)
- **User manual §26** — SC Synthesis Showcase (KS/coef vocabulary, Solar42 patch,
  SuperKarplus warp technique, chaos synthesis bifurcation table, all built-in patches)

### Changed

- `user.clj` — exports `cljseq.freesound` API (`set-freesound-key!`, `fetch-sample!`,
  `fetch-and-load!`, `freesound-info`, `search-freesound`, `load-essentials!`,
  `load-and-prime-essentials!`, `curate-essentials!`)
- User manual §25 (Reference) renumbered to §27

---

## [0.5.0] — 2026-04-09

### Added

#### SuperCollider integration (`cljseq.sc`)
- SC dispatch bridge — `register-sc-dispatch!` in `cljseq.core` allows `sc` to hook
  `play!` at load time without a compile-time circular dependency
- `play!` routes events with `:synth` key to `sc-play-dispatch!` automatically;
  non-SC events are unaffected
- `ensure-synthdef!` — sends a SynthDef to SC on first use and caches to avoid
  redundant network calls; `sent-synthdefs` atom reset on connect/disconnect
- `connect-sc!` / `disconnect-sc!` / `sc-connected?` — OSC session lifecycle
- `sc-play!` — fire a SC synth event (`:synth`, `:freq`, `:dur-ms`, optional params)
- `set-param!` / `free-synth!` / `kill-synth!` — live node control
- `sc-synth!` — allocate a persistent SC synth node; returns node-id for continuous control
- `synthdef-str` / `send-synthdef!` / `send-all-synthdefs!` — SynthDef compilation and upload
- `apply-trajectory!` (3-arity, `sc/apply-trajectory!`) — drives a live SC node parameter
  via a trajectory; delegates to `core/apply-trajectory!` with a `set-param!` setter
- `sc-status` — query SC server status map
- `midi->freq` private helper (440 × 2^((midi−69)/12))

#### Synthesis graph vocabulary (`cljseq.synth`)
- `defsynth!` / `get-synth` / `synth-names` — named synthesis graph registry
- `compile-synth` — compile a graph map to a SynthDef string (`:sc` target)
- `load-synth-map` — load a synth library from an EDN resource file
- `map-graph` — transform all nodes of a synthesis graph
- `synth-ugens` — extract the set of UGen types used in a graph
- `transpose-synth` / `scale-amp` / `replace-arg` — composable graph transformers

#### FM synthesis (`cljseq.fm`)
- `def-fm!` / `get-fm` / `fm-names` — named FM algorithm registry
- `fm-algorithm` — declare operator topology (modulation matrix + ratios + indices)
- `compile-fm` — emit SynthDef SClang string from FM algorithm

#### Physics particle field (`cljseq.spatial-field`)
- N-gon geometry — `ngon-walls` builds walls for any N; decimal N (e.g. `4.7`) uses
  angular spacing 2π/N with `ceil(N)` vertices producing asymmetric reflection angles
  and polyrhythmic character
- Per-wall type options: `:reflect` (elastic), `:generate` (fire MIDI event on collision),
  `:absorb` (kill velocity), `:portal` (teleport to opposite wall)
- `find-next-collision` — parametric ray-wall intersection; returns `{:t ##Inf}` when
  no collision is reachable
- `step-particle` — advance one particle through up to one wall collision per dt;
  returns `{:particle p :events [...]}` where events carry wall-index and position
- `apply-forces` — gravity (`:down`/`:up` with configurable strength) and
  damping coefficient applied each tick
- `quad-gains` — constant-power VBAP decomposition from [x y]∈[0,1]² to [FL FR RL RR]
- `map-particle` — axis-mapping layer: `:x`, `:y`, `:r`, `:θ`, `:vx`, `:vy`,
  `:speed`, `:age` mapped to arbitrary parameter keys
- `defspatial-field!` / `get-field` / `field-names` — named field registry
- `start-field!` / `stop-field!` / `stop-all-fields!` / `field-state` — field lifecycle
- Two modes: `:generation` (collision events drive MIDI) and `:modulation`
  (continuous parameter sweep on a BPM-derived tick)
- `SpatialFieldTransformer` — `ITransformer` implementation; passes through the
  triggering event with `delay-beats 0` and queues deferred collision events
- `field-transformer` — returns a `SpatialFieldTransformer` for a running field
- `play-through-field!` — convenience wrapper for `play-transformed!` + `field-transformer`
- **8 named presets** registered at load time:
  - `:ricochet` — fast-ball generation, 6-sided room
  - `:dribble` — low gravity, floor-bounce generation
  - `:drift` — slow multi-particle modulation
  - `:orbit` — circular orbit modulation (low damping)
  - `:pinball` — chaotic 7-sided room with portals
  - `:gravity-well` — strong downward pull, 4-sided room
  - `:portal-room` — mixed reflect/portal boundary types
  - `:instrument-quad` — quad-gains–mapped spatial panning

#### Rosetta Stone (`examples/rosetta_stone.clj`)
- Side-by-side translation guide: Sonic Pi and Overtone vocabulary → cljseq
- 13 sections covering: single notes, tempo/timing, live loops, scales, chords,
  randomization, synth selection, harmony context, FX/device control, analysis,
  pattern sequencing, note transformers, multi-loop ensemble

### Fixed

- `core/apply-trajectory!` (2-arity) — new overload drives any setter-fn with a
  trajectory at ~50 ms ticks on a daemon thread; returns a cancel function
- `cljseq.user/with-dur` and `phrase!` — were incorrectly re-exported as `def`
  (macros cannot be `def`'d); changed to `defmacro` wrappers

---

## [0.4.0] — 2026-04-09

### Added

#### ITexture protocol (`cljseq.texture`)
- `ITexture` protocol — `freeze!/thaw!/frozen?/texture-state/texture-set!/texture-fade!`
  unified interface for software and hardware temporal devices
- Shadow state registry — `shadow-init!/shadow-update!/shadow-get` for write-only hardware
  devices (NightSky, Volante, etc.) that have no readback
- `TemporalBufferTexture` — ITexture wrapper delegating to `cljseq.temporal-buffer`
- `texture-transition!` — multi-device ops map `{device-id {:target params :beats N}}`;
  calls `texture-fade!` when `:beats` is present, `texture-set!` otherwise
- `deftexture!/get-texture/texture-names` — named texture registry

#### Spectral analysis model (`cljseq.spectral`)
- `SpectralState` record implementing ITexture — derives three spectral fields from
  live Temporal Buffer snapshots: `:spectral/density` (distinct pitch classes / 12),
  `:spectral/centroid` (mean MIDI / 127), `:spectral/blur` (0 = live, 1 = frozen)
- `start-spectral!` — starts the SAM analysis loop (`:spectral-sam` live loop) watching
  a named Temporal Buffer; returns a `SpectralState` that participates in `ITexture`
- `stop-spectral!`, `spectral-ctx` — stop the loop; read the current spectral state
- Freeze semantics — `freeze!` arrests analysis (blur→1.0) but the loop keeps running
  so `:on-ctx` callbacks fire with the held state; `thaw!` resumes (blur→0.0)
- Ctrl tree publish — `run-sam-tick!` writes `[:spectral :state]` after each cycle
  for peer sharing via the HTTP server

#### Ensemble improvisation agent (`cljseq.ensemble-improv`)
- `start-improv!` / `stop-improv!` — single named live loop (`:improv-agent`) driven
  entirely by an atom; profile and routing changes take effect without restart
- `default-profile` — `:step-beats`, `:gate`, `:dur-beats`, `:velocity`, `:vel-variance`,
  `:channel`
- Note generation — `degree-pool` selects pitches by tension (triad / pentatonic / full
  scale) and register (:low/:mid/:high octave spread); `pick-step` gates by density
- Texture routing — per-tick `{device-id {:params-fn f :beats N}}` map drives
  `texture-transition!` from the live ImprovisationContext; params-fn exceptions swallowed
- `update-improv!` — merge profile overrides or replace routing without restarting the loop
- `improv-gesture-fn` — returns an `:on-start`-compatible `(fn [])` for conductor sections;
  merges profile, updates routing, fires an immediate texture transition at the boundary

#### Peer discovery and ctrl-tree mounting (`cljseq.peer`)
- UDP multicast beacon — group `239.255.43.99:7743`; 5 s interval; EDN payload with
  node-id, role, http-port, version, timestamp-ms; daemon sender thread
- Discovery listener — `MulticastSocket` joined to the group; 1 s SO_TIMEOUT so
  interruption is responsive; 15 s peer expiry; self-beacons ignored
- `set-node-profile!/node-profile/node-id` — node identity before `start-discovery!`
- `start-discovery!/stop-discovery!/discovery-running?/peers/peer-info`
- HTTP pull mount — `mount-peer!` starts a daemon poll thread that GETs
  `/ctrl/ensemble/harmony-ctx` and `/ctrl/spectral/state` from the peer's HTTP server
  and stores the results at `[:peers id :ensemble :ctx]` / `[:peers id :spectral :ctx]`
- `unmount-peer!/mounted-peers/peer-harmony-ctx/peer-spectral-ctx`
- `ctx->serial` / `serial->scale` — lossless round-trip between ImprovisationContext
  (containing Scale/Pitch records) and a JSON-safe primitive map
- `*http-get*` dynamic var — injectable for unit tests; no live network required
- `cljseq.ensemble` — `run-ear-tick!` now publishes `[:ensemble :harmony-ctx]` to the
  ctrl tree after each analysis cycle (try-wrapped; ctrl not starting does not break tests)

#### Note transformers (`cljseq.transform`)
- `ITransformer` protocol — `(transform [xf event] → seq of {:event map :delay-beats num})`
- `play-transformed!` — plays immediate events via `core/play!`; daemon threads for
  delayed events (beats→ms via current BPM)
- `compose-xf` — chains transformers left-to-right; delay-beats accumulate so an
  echo of a strum plays the full strummed chord N times
- **8 built-in transformers:**
  - `velocity-curve` — piecewise linear velocity mapping via breakpoints
  - `quantize` — snap pitch to nearest scale degree; configurable forgiveness threshold
    (events outside threshold are dropped, not clamped)
  - `harmonize` — add chromatic interval voices; `:snap?` snaps harmony notes to the
    nearest scale degree (reads `*harmony-ctx*` by default); `:include-original?`
  - `echo` — decay repeat chain; configurable `:repeats`, `:decay`, `:delay-beats`,
    `:pitch-step` per repeat
  - `note-repeat` — rhythmic copies: uniform spacing or Euclidean (Bjorklund) distribution
  - `strum` — spread `:chord/notes` over time; `:up`/`:down`/`:random` direction
  - `dribble` — bouncing-ball gravity timing: exponentially shrinking inter-onset gaps
    via configurable coefficient of restitution
  - `latch` — `:toggle` (open/closed on successive calls) or `:velocity` threshold gate

---

## [0.3.0] — 2026-04-09

### Added

#### Ensemble harmony groundwork (`cljseq.ensemble`)
- `analyze-buffer` — pure fn: Temporal Buffer snapshot → ImprovisationContext map
  (`{:harmony/key Scale :harmony/tension float :harmony/chord map
     :ensemble/register kw :ensemble/density float …}`)
- `start-harmony-ear!` / `stop-harmony-ear!` — opt-in background live loop
  (`:harmony-ear`) that continuously updates `*harmony-ctx*` from a named buffer;
  supports `:cadence`, `:key-scale` pin, `:transform` fn, `:on-ctx` callback

#### Topology Layer 1 (`cljseq.topology`)
- Logical MIDI device aliases — `defdevice-alias!`, `resolve-alias`, `device-aliases`
- Port-pattern resolution — substring match at sidecar startup; works with `start-sidecar!`
- `doc/topology-example.edn` — annotated 3-host studio topology configuration

#### Threshold extractor (`cljseq.extractor`)
- `IThresholdExtractor` protocol — Schmitt trigger hysteresis; configurable high/low bands
- `defextractor!` / `get-extractor` / `extractor-names` — named registry
- `watch-ctrl!` — watches a ctrl tree path; fires callbacks on threshold crossings
- Built-in extractors: `rising-edge`, `falling-edge`, `band-enter`, `band-leave`

---

## [0.2.0] — 2026-04-08

### Added

#### Temporal Buffer (`cljseq.temporal-buffer`) — Phases 1–5
- Named, zoned ring buffers for live event capture; Mimeophon/Cosmos-inspired design
- Phase 1 — core buffer and zones: `deftemporal-buffer!`, `temporal-buffer-push!`,
  `temporal-buffer-snapshot`, `temporal-buffer-info`, zone configuration
- Phase 2 — Rate/Doppler: `temporal-buffer-rate!` for playback speed and pitch modulation
- Phase 3 — Color and Feedback: `temporal-buffer-color!`, internal feedback coefficient
- Phase 4 — Halo: harmonic shimmer layer; density and spread controls
- Phase 5 — Hold/loop-slice: `temporal-buffer-hold!`; named presets; trajectory integration
  (`temporal-buffer-trajectory!` applies an arc map to buffer parameters over time)

#### Device maps (22 EDN files, `resources/devices/`)
- Strymon NightSky, Volante
- Boss DD-500, RV-500, MD-500, SDE-3000D
- Elektron Analog Heat FX+, Digitone
- Moog Sub 37, Minitaur, Subsequent 25
- Novation Peak, Summit
- Behringer 2600, Pro-1, Pro-800, Neutron, Proton, K-2 MkII, Kobol Expander
- Intellijel Cascadia, Singular Sound BeatBuddy, Singular Sound MIDI Maestro,
  Two Notes Torpedo Captor X, MuseKinetiks 12Step, Boss ES-8, Boss WAZA-AIR TAE

#### Infrastructure
- GitHub Actions CI — `ubuntu-latest` + `macos-latest` matrix; green on first run
- Fixed missing `<cstddef>` includes exposed by GCC strict headers on Ubuntu

---

## [0.1.1] — 2026-04-08

### Fixed

- **C++ build on GCC/Linux**: `midi_dispatch.h` was not included in
  `ipc.cpp`, causing `midi_send_sysex` (added in 0.1.0 for MTS/SysEx
  support) to be undeclared. Apple clang accepted it via transitive
  includes; GCC correctly rejects it. Found during Ubuntu Studio
  clean-clone verification.

### Verified

- Clean-clone build and full test suite (886 tests, 5718 assertions,
  0 failures) confirmed on Ubuntu Studio 24.04 LTS.

---

## [0.1.0] — 2026-04-04

First public MVP release. macOS is the primary tested platform for this release;
Linux (Ubuntu Studio, Fedora) and device CC map verification are targeted for 0.1.1.

### Added

#### Live coding engine
- `deflive-loop` / `live-loop` — named, continuously repeating loops with hot-swap
  on re-evaluation; no click or timing gap at the swap boundary
- Virtual time scheduler — drift-free beat scheduling via `LockSupport/parkUntil`;
  `sleep!` accepts integers, rationals (`1/4`), and decimals
- `set-bpm!` — tempo changes take effect on the next `sleep!` with zero drift (Q59)
- Hot-swap unpark (Q60) — long `sleep!` calls interrupted immediately on body reload
- `sync!` — align to the next beat-grid boundary before proceeding

#### Real-time MIDI sidecar
- Native C++ sidecar process (`cljseq-sidecar`) — priority-queue scheduler delivers
  notes at wall-clock time, independent of JVM GC
- Full MIDI output: NoteOn/Off, CC, pitch bend, channel pressure, program change
- SysEx output: `send-sysex!` with F0/F7 framing validation; `send-mts!` for MTS
  Bulk Dump (MIDI Tuning Standard, 408-byte Non-Realtime Universal SysEx)
- MIDI input monitor — incoming messages pushed to JVM as events;
  `await-midi-message` for test and interactive workflows
- MIDI clock output — 24 PPQN derived from Ableton Link timeline (zero drift);
  `0xFA` start, `0xFC` stop, `0xF8` tick
- `start-sidecar!` / `stop-sidecar!` / `list-midi-ports`

#### Music theory
- `make-scale` / `make-chord` — first-class Scale and Chord records for all common
  modes and chord qualities; pitch-class arithmetic
- `identify-chord` — identify chord by MIDI note set
- `chord->roman` — Roman numeral analysis relative to a scale
- `detect-key` — Krumhansl-Schmuckler probe-tone key detection from pitch-class set
- `analyze-progression` — annotate a chord sequence with Roman numerals
- `tension-score` / `progression-tension` — tension model per chord quality and degree
- `suggest-progression` — generate a progression from a tension arc
- `borrowed-chord?` / `annotate-progression` — mode-mixture / borrowed chord detection
- `suggest-scales` — find scales that fit a set of pitch classes
- `use-harmony!` / `with-harmony` — per-REPL and per-scope harmony context;
  `play!` snaps pitches to the active scale

#### Microtonal tuning
- `cljseq.scala` — parse `.scl` and `.kbm` (Scala) files; full library compatible
- `*tuning-ctx*` — per-loop tuning context; `play!` auto-translates MIDI note numbers
  to nearest scale degree + pitch-bend offset
- `scale->mts-bytes` — generate standards-compliant MTS Bulk Dump SysEx
- `send-mts!` — retune Hydrasynth / MicroFreak to any Scala scale without per-note
  pitch bend; tuning persists until patch change or power cycle

#### MPE (MIDI Polyphonic Expression)
- MPE lower-zone allocator — per-note pitch bend, CC 74 slide, channel pressure
  routed through sidecar pitch-bend and channel-pressure IPC frames
- `send-pitch-bend!` / `send-channel-pressure!` — direct per-channel expression sends

#### Ableton Link
- `cljseq.link` — join / leave Link sessions; `link/enable!`, `link/bpm`,
  `link/peers`, `link/set-bpm!`, `link/disable!`
- All loops phase-quantize to the bar boundary on Link-enabled sessions
- MIDI clock output derived from Link timeline (zero drift)
- Build flag: `-DCLJSEQ_ENABLE_LINK=ON` (changes sidecar license to GPL-2.0)

#### Device maps (14 EDN files, `resources/devices/`)
- ASM Hydrasynth Explorer, Desktop, Deluxe, Keyboard 61 — MPE, full NRPN map,
  MTS retune support
- Roger Linn Design LinnStrument 200 — MPE lower zone, per-note X/Y/Z axes,
  pad layout, split and one-channel modes
- Arturia KeyStep Pro — 4-track sequencer, CV/Gate×4, polyphonic, DIN in+out
- Arturia KeyStep (original) — hardware-unverified; single-track, touch strip
- Korg Minilogue XD — full CC/NRPN map
- Strymon NightSky — texture parameters, freeze cue, program change
- Strymon Volante — 4 independent tape heads, SOS record/mix
- Boss DD-500 — dual engine delay, tempo sync
- Boss RV-500 — dual engine reverb
- Boss MD-500 — dual engine modulation, 32 algorithm types
- Boss SDE-3000D — stereo dual delay

#### DSL and sequencing
- `play!` — step maps, pitch keywords, MIDI integers; harmony + tuning pipeline
- `play-chord!` / `play-voicing!` / `arp!` — chord playback and arpeggiation
- `phrase!` — melodic phrase helper
- `ring` / `tick!` — cycling sequence ring buffer
- `with-dur` / `set-default-dur!` — scoped and global default duration

#### Generative
- `cljseq.stochastic` — `make-stochastic-context`, `next-x!`, `stochastic-rhythm`
  (Euclidean rhythms); Markov-chain note generation
- `cljseq.fractal` — `make-fractal-context`, `next-step!`; branch tree with
  `:reverse`, `:inverse`, `:transpose`, `:mutate`, `:randomize` transforms
- `cljseq.random` — `weighted-scale` — scale-biased random pitch selection
- `cljseq.flux` — evolving parameter sequences; `freeze!` / `thaw!` read head
- `cljseq.trajectory` — smooth automation curves: `:linear`, `:breathe`,
  `:smooth-step`, `:bounce`; named gestures: `buildup`, `trance-buildup`,
  `breakdown`, `anticipation`, `groove-lock`, `swell`, `tension-peak`
- `cljseq.conductor` — `defconductor!`, `fire!`, `cue!`, `abort!`; section-based
  compositional arc with per-section `:gesture`, `:repeat`, `:when`, `:on-transition`

#### Control tree
- `cljseq.ctrl` — hierarchical parameter store: `set!`, `send!`, `get`,
  `defnode!`, `bind!`, `unbind!`
- MIDI CC and NRPN binding: `ctrl/bind!` with `{:type :midi-cc ...}` /
  `{:type :midi-nrpn ...}`; 14-bit NRPN with full CC99/98/6/38 wire encoding
- `checkpoint!` / `panic!` / `undo!` — snapshot and revert

#### Bach corpus (Music21)
- `cljseq.m21` — load and play Bach chorales from the Music21 corpus;
  chordified or SATB per-voice on separate MIDI channels
- Persistent Python server with two-level cache (memory + disk at
  `~/.local/share/cljseq/corpora/m21/`)
- `list-chorales`, `play-chorale!`, `play-chorale-parts!`, `load-chorale`,
  `stop-server!`

#### Mod routing and arc automation
- `cljseq.mod` — `mod-route!` / `mod-unroute!`; LFO-to-ctrl-path automation
- `cljseq.arc` — `arc-bind!`, `arc-send!`; trajectory-to-ctrl-path wiring

#### Ardour DAW integration
- `cljseq.ardour` — transport control (play/stop/record/goto-start), marker
  insertion, session save, capture lifecycle

#### Infrastructure
- CMake FetchContent build — Asio (BSL-1.0) and RtMidi (MIT) auto-fetched;
  no manual dependency installation on macOS
- REUSE-compliant SPDX headers on all 112 source files; `LICENSES/` directory
  with EPL-2.0, LGPL-2.1, GPL-2.0 canonical texts
- `cljseq.user` — single REPL convenience namespace;
  `(require '[cljseq.user :refer :all])` gives the complete public API

### Known limitations (targeted for 0.1.1)
- Tested on macOS only; Linux (Ubuntu Studio, Fedora) build verification pending
- Boss 500-series and Strymon device map CC assignments are representative;
  verification against official MIDI implementation charts pending
- Arturia KeyStep (original) device map is hardware-unverified

### License

| Component | License |
|-----------|---------|
| Clojure library (`src/`, `test/`) | EPL-2.0 |
| C++ sidecar (default build) | LGPL-2.1-or-later |
| C++ sidecar + Ableton Link | GPL-2.0-or-later |

---

[Unreleased]: https://github.com/rodgert/cljseq/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/rodgert/cljseq/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/rodgert/cljseq/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/rodgert/cljseq/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/rodgert/cljseq/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/rodgert/cljseq/releases/tag/v0.1.0
