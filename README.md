# cljseq

A music-theory-aware Clojure sequencer for live coding. Runs at a Clojure
nREPL; sends MIDI to hardware and software instruments via a native real-time
sidecar process.

```clojure
(require '[cljseq.core :refer :all])

(start! :bpm 120)

(deflive-loop :kick {}
  (play! :C2)
  (sleep! 1))

(deflive-loop :melody {}
  (play! (rand-nth [:C4 :E4 :G4 :B4]))
  (sleep! 1/2))

;; Edit and re-evaluate any loop live — takes effect on the next iteration.

(stop!)
```

---

## Features

- **Live loops** — named, continuously repeating loops started from the REPL;
  re-evaluate a `deflive-loop` form to swap the body without stopping the loop
- **Virtual time** — drift-free beat scheduling backed by `LockSupport/parkUntil`;
  BPM changes take effect immediately without waiting for the current sleep to expire
- **Control tree** — hierarchical parameter store with MIDI CC binding, undo,
  checkpoints, and multi-source conflict resolution
- **Real-time MIDI output** — a native C++ sidecar handles scheduled note delivery
  with sub-millisecond accuracy, independent of JVM garbage collection pauses
- **Ableton Link** (optional) — beat-accurate tempo sync with other Link-enabled
  applications (Ableton Live, Bitwig, Sonic Pi, VCV Rack) on the same LAN;
  loops phase-quantize to the bar boundary when joining a Link session

---

## Quick start

### Prerequisites

- Java 21+ and [Leiningen](https://leiningen.org)
- CMake 3.20+ and a C++17 compiler (Apple clang, GCC, or MSVC)

See [BUILD.md](BUILD.md) for platform-specific instructions and all options.

### Build

```bash
git clone https://github.com/YOUR_ORG/cljseq.git
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
(require '[cljseq.core :refer :all])

(start! :bpm 120)

(deflive-loop :hi-hat {}
  (play! {:pitch/midi 42 :dur/beats 1/8 :mod/velocity 80})
  (sleep! 1/4))

(set-bpm! 140)   ; change tempo live; all loops adjust on their next sleep

(stop-loop! :hi-hat)
(stop!)
```

---

## Architecture overview

```
Clojure REPL
  │  deflive-loop / play! / sleep! / ctrl/set!
  │
  ├── cljseq.loop   — virtual time, live-loop threads
  ├── cljseq.ctrl   — control tree (MIDI CC binding, undo)
  ├── cljseq.link   — Ableton Link client (optional)
  └── cljseq.sidecar
        │  TCP IPC (little-endian framed messages)
        │
        └── cljseq-sidecar (C++)
              ├── Scheduler    — priority queue, fires at wall-clock time
              ├── MIDI output  — RtMidi → IAC / hardware
              └── LinkEngine   — Ableton Link SDK (opt-in, GPL)
```

The JVM never parks on a MIDI write. All note delivery happens in the C++
scheduler thread, which is independent of JVM garbage collection.

---

## Ableton Link

Build with Link support to sync with other apps on the LAN:

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release \
      -DCLJSEQ_BUILD_AUDIO=OFF \
      -DCLJSEQ_ENABLE_LINK=ON
cmake --build build --parallel
```

> **License**: enabling Link changes the sidecar binary's license from
> LGPL-2.1-or-later to GPL-2.0-or-later. The Clojure library (EPL-2.0) is
> unaffected. See [doc/licensing.md](doc/licensing.md).

```clojure
(require '[cljseq.link :as link])

(start!)
(link/enable!)        ; join or create a Link session

(link/bpm)            ; => session BPM (from most recent peer push)
(link/peers)          ; => number of connected peers

;; Loops started while Link is active wait for the next bar boundary
;; before their first iteration, staying in phase with all peers.
(deflive-loop :arp {}
  (play! (rand-nth [:C4 :E4 :G4]))
  (sleep! 1/4))

(link/set-bpm! 130)   ; propose tempo change to the session
(link/disable!)
```

---

## License

| Component | License |
|-----------|---------|
| Clojure library (`src/`, `test/`) | [EPL-2.0](LICENSE) |
| C++ runtime (`cpp/libcljseq-rt/`, `cpp/cljseq-sidecar/`) | [LGPL-2.1-or-later](LICENSES/LGPL-2.1.txt) |
| Link engine (`cpp/libcljseq-link/`) | [GPL-2.0-or-later](LICENSES/GPL-2.0.txt) |

See [doc/licensing.md](doc/licensing.md) for the full licensing strategy and
the implications of building with Ableton Link enabled.
