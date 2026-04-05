# cljseq

A music-theory-aware Clojure sequencer for live coding. Runs at a Clojure
nREPL; sends MIDI to hardware synthesisers, controllers, and FX units via a
native real-time sidecar process.

```clojure
(require '[cljseq.user :refer :all])

(start! :bpm 120)

(deflive-loop :kick {}
  (play! :C2)
  (sleep! 1))

(deflive-loop :melody {}
  (use-harmony! (make-scale :C 4 :dorian))
  (play! (rand-nth [:C4 :Eb4 :G4 :Bb4]))
  (sleep! 1/2))

;; Edit and re-evaluate any loop live — takes effect on the next beat.

(set-bpm! 140)
(stop!)
```

---

## Features

### Live coding engine
- **Live loops** — named, continuously repeating loops; re-evaluate a
  `deflive-loop` form to swap the body mid-performance without stopping
- **Virtual time** — drift-free beat scheduling with `LockSupport/parkUntil`;
  BPM changes take effect on the next `sleep!` with zero drift
- **Hot-swap** — long `sleep!` calls are interrupted immediately when a loop
  body is reloaded; no waiting for the current bar to finish

### Real-time MIDI
- **Native sidecar** — a C++ scheduler delivers notes at wall-clock time,
  independent of JVM garbage collection; sub-millisecond jitter
- **Full MIDI coverage** — NoteOn/Off, CC, pitch bend, channel pressure,
  SysEx, MIDI clock; per-note MPE expression on all active channels
- **MIDI input monitoring** — incoming messages pushed to the JVM as events;
  `await-midi-message` for test and interactive workflows
- **MTS Bulk Dump** — retune Hydrasynth / MicroFreak to any Scala scale
  without per-note pitch bend; `send-mts!` sends the 408-byte SysEx

### Music theory
- **Scale / chord / interval** — first-class records for all common modes,
  chord qualities, and intervals; pitch-class arithmetic
- **Key detection** — Krumhansl-Schmuckler probe-tone key detection from any
  pitch-class histogram
- **Chord identification** — identify chords by pitch set; Roman numeral analysis
- **Tension scoring** — tension model per chord quality and scale degree;
  `suggest-progression` generates progressions from a tension arc
- **Borrowed chords** — mode-mixture detection; suggest borrowed chords from
  parallel major/minor

### Microtonal
- **Scala files** — parse `.scl` and `.kbm` files; full Scala library compatible
- **`*tuning-ctx*`** — per-loop microtonal tuning context; `play!` auto-translates
  MIDI note numbers to the nearest scale degree + pitch bend
- **MTS** — `scale->mts-bytes` generates standards-compliant bulk dump SysEx

### Ableton Link
- **Tempo sync** — join any Link session; all loops phase-quantize to the bar
  boundary; propose tempo changes that propagate to all peers
- **MIDI clock** — 24 PPQN MIDI clock output derived from the Link timeline
  (zero drift, hardware-grade accuracy)

### Device maps
- **EDN device files** — structured MIDI implementation maps for synthesisers,
  controllers, and FX units under `resources/devices/`
- **Semantic CC access** — `device-send!` by parameter name, not raw CC number
- **NRPN dispatch** — 14-bit NRPN sequences built and sent automatically
- **MPE device profiles** — LinnStrument, Hydrasynth; per-note axis documentation

### Generative
- **Stochastic** — weighted random, Markov chains, Euclidean rhythms
- **Fractal** — L-system melody expansion, recursive phrase generation
- **Flux** — evolving parameter sequences; freeze/unfreeze read head
- **Conductor** — section-based compositional arc with built-in gestures
  (buildup, drop, breakdown, tension-peak, anticipation)
- **Trajectory** — smooth automation curves (linear, breathe, smooth-step,
  bounce) for parameter automation over bars or sections

### Bach corpus (Music21)
- **`cljseq.m21`** — load and play Bach chorales from the Music21 corpus;
  chordified or SATB per-voice on separate MIDI channels; persistent server
  with two-level cache (memory + disk)

---

## Quick start

### Prerequisites

- Java 21+ and [Leiningen](https://leiningen.org)
- CMake 3.20+ and a C++17 compiler (Apple clang, GCC, or MSVC)

See [BUILD.md](BUILD.md) for platform-specific instructions and all options.

### Build

```bash
git clone https://github.com/rodgert/cljseq.git
cd cljseq

# Build the real-time MIDI sidecar (fetches Asio + RtMidi on first run)
cmake -B build -DCMAKE_BUILD_TYPE=Release -DCLJSEQ_BUILD_AUDIO=OFF
cmake --build build --parallel

# Run the Clojure test suite
lein test
```

### REPL

```bash
lein repl
```

```clojure
(require '[cljseq.user :refer :all])

;; Connect MIDI output (by port name substring)
(start-sidecar! :midi-port "IAC")

(start! :bpm 120)

(deflive-loop :hi-hat {}
  (play! {:pitch/midi 42 :dur/beats 1/8 :mod/velocity 80})
  (sleep! 1/4))

(set-bpm! 140)
(stop-loop! :hi-hat)
(stop!)
```

See [doc/user-manual.md](doc/user-manual.md) for a complete guide.

---

## Architecture

```
Clojure REPL
  │  deflive-loop / play! / sleep! / ctrl/set!
  │
  ├── cljseq.loop      — virtual time, live-loop threads, *tuning-ctx*
  ├── cljseq.ctrl      — control tree (MIDI CC binding, undo, checkpoints)
  ├── cljseq.dsl       — play!, arp!, phrase!, harmony/chord/tuning context
  ├── cljseq.device    — device map loader, device-send!, NRPN dispatch
  ├── cljseq.link      — Ableton Link client (optional)
  └── cljseq.sidecar
        │  TCP IPC (little-endian framed messages)
        │
        └── cljseq-sidecar (C++)
              ├── Scheduler    — priority queue, fires at wall-clock time
              ├── MIDI output  — RtMidi → IAC / hardware
              ├── MIDI input   — RtMidi input monitor, pushes 0x20 frames to JVM
              ├── MIDI clock   — 24 PPQN from Link timeline, zero drift
              └── LinkEngine   — Ableton Link SDK (opt-in, GPL)
```

The JVM never parks on a MIDI write. All note delivery happens in the C++
scheduler thread, independent of JVM garbage collection.

---

## Included device maps

| Device | Type | Notes |
|--------|------|-------|
| ASM Hydrasynth Explorer | Synth | MPE, full NRPN map, MTS |
| ASM Hydrasynth Desktop | Synth | MPE, full NRPN map, MTS |
| ASM Hydrasynth Deluxe | Synth | MPE, full NRPN map, MTS |
| ASM Hydrasynth Keyboard 61 | Synth | MPE, full NRPN map, MTS |
| Roger Linn Design LinnStrument 200 | Controller | MPE, per-note X/Y/Z axes |
| Arturia KeyStep Pro | Controller / Sequencer | 4-track seq, CV/Gate, polyphonic |
| Arturia KeyStep (original) | Controller | Hardware-unverified; see note |
| Korg Minilogue XD | Synth | — |
| Strymon NightSky | Reverb synth | Texture automation, freeze cue |
| Strymon Volante | Delay / echo | Head matrix, SOS |
| Boss DD-500 | Digital delay | Dual engine, tempo sync |
| Boss RV-500 | Reverb | Dual engine |
| Boss MD-500 | Modulation | 32 algorithm types |
| Boss SDE-3000D | Dual delay | Stereo engine A/B |

> **Hardware verification:** Boss 500-series and Strymon CC assignments are from
> published MIDI implementation charts; verify against the official Owner's Manual
> before relying on specific CC numbers in production. The original Arturia KeyStep
> device map is included for completeness but has not been tested on hardware.

---

## Ableton Link

Build with Link to sync with other apps on the LAN:

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release \
      -DCLJSEQ_BUILD_AUDIO=OFF \
      -DCLJSEQ_ENABLE_LINK=ON
cmake --build build --parallel
```

> **License**: enabling Link changes the sidecar binary's license from
> LGPL-2.1-or-later to GPL-2.0-or-later. The Clojure library (EPL-2.0) is
> unaffected. See [doc/licensing.md](doc/licensing.md).

---

## License

| Component | License |
|-----------|---------|
| Clojure library (`src/`, `test/`) | [EPL-2.0](LICENSE) |
| C++ runtime (`cpp/libcljseq-rt/`, `cpp/cljseq-sidecar/`) | [LGPL-2.1-or-later](LICENSES/LGPL-2.1.txt) |
| Link engine (`cpp/libcljse-link/`) | [GPL-2.0-or-later](LICENSES/GPL-2.0.txt) |

See [doc/licensing.md](doc/licensing.md) for the full licensing strategy and
the implications of building with Ableton Link enabled.
