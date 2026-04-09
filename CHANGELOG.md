# Changelog

All notable changes to cljseq are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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
