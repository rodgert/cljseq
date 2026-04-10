# Changelog

All notable changes to cljseq are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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

[Unreleased]: https://github.com/rodgert/cljseq/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rodgert/cljseq/releases/tag/v0.1.0
