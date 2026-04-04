# Building cljseq from source

cljseq has two independent build systems that both need to be run from a fresh
clone: **Leiningen** for the Clojure library, and **CMake** for the C++ sidecar
binary that handles real-time MIDI output.

---

## Prerequisites

### All platforms

| Tool | Minimum | Notes |
|------|---------|-------|
| Git | 2.x | For cloning and shallow FetchContent fetches |
| Java (JDK) | 21 | OpenJDK 21+ recommended; tested on OpenJDK 25 |
| Leiningen | 2.11+ | Clojure build tool; install via [leiningen.org](https://leiningen.org) |
| CMake | 3.20+ | C++ build system |
| C++17 compiler | — | See platform notes below |

### macOS

```
xcode-select --install          # installs Apple clang
brew install cmake leiningen
```

Apple clang (Xcode Command Line Tools 14+) is sufficient. Xcode itself is not
required. Tested on Apple clang 17 (Xcode 26).

### Linux

```
# Debian / Ubuntu
sudo apt install build-essential cmake default-jdk leiningen

# Fedora / RHEL
sudo dnf install gcc-c++ cmake java-21-openjdk leiningen
```

### Windows

CMake + MSVC (Visual Studio 2022, C++ Desktop workload) or clang-cl. Leiningen
runs under WSL2 or natively with the Windows installer. The MIDI sidecar uses
the Windows Multimedia API (WinMM) via RtMidi — no extra driver is required.

---

## 1. Clone

```bash
git clone https://github.com/rodgert/cljseq.git
cd cljseq
```

No submodules. All C++ dependencies are fetched automatically by CMake
FetchContent on first build.

---

## 2. Build the C++ sidecar

The sidecar (`cljseq-sidecar`) is a small native process that the JVM spawns
at runtime to handle scheduled MIDI output with sub-millisecond accuracy.

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
```

The resulting binary is at `build/cpp/cljseq-sidecar/cljseq-sidecar`.

**What CMake fetches on first run** (requires internet, ~60 MB total):

| Library | Version | License | Purpose |
|---------|---------|---------|---------|
| [Asio](https://github.com/chriskohlhoff/asio) | 1.30.2 | BSL-1.0 | Async I/O for IPC |
| [RtMidi](https://github.com/thestk/rtmidi) | 6.0.0 | MIT | Cross-platform MIDI output |

Subsequent `cmake --build` invocations skip the network entirely
(`FETCHCONTENT_UPDATES_DISCONNECTED=ON` is set by default).

### Build options

| Option | Default | Effect |
|--------|---------|--------|
| `CLJSEQ_BUILD_SIDECAR` | `ON` | Build `cljseq-sidecar` |
| `CLJSEQ_BUILD_AUDIO` | `ON` | Build `cljseq-audio` (CLAP host, requires more deps) |
| `CLJSEQ_ENABLE_LINK` | `OFF` | Add Ableton Link support — see §4 below |
| `CLJSEQ_BUILD_TESTS` | `OFF` | Build C++ unit tests |
| `CMAKE_BUILD_TYPE` | _(none)_ | Set to `Release` for production, `Debug` for dev |

To skip `cljseq-audio` (not needed for MIDI-only use):

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release -DCLJSEQ_BUILD_AUDIO=OFF
cmake --build build --parallel
```

### MIDI device setup (macOS)

The sidecar opens the first available MIDI output port on startup. On macOS,
enable the IAC Driver in **Audio MIDI Setup → MIDI Studio → IAC Driver** to
create a virtual loopback port for testing without hardware.

---

## 3. Build the Clojure library

```bash
lein deps          # download Clojure dependencies
lein test          # run the test suite
```

All tests should pass with 0 failures, 0 errors. Tests that exercise the
full MIDI path require the sidecar binary (step 2) to be present at
`build/cpp/cljseq-sidecar/cljseq-sidecar`.

### Start a REPL

```bash
lein repl
```

```clojure
(require '[cljseq.core :refer :all])
(start!)                                    ; spawns sidecar, starts clock

(deflive-loop :hello {}
  (play! :C4)
  (sleep! 1))

(stop!)
```

### Run tests only (no sidecar)

The unit tests for `cljseq.clock`, `cljseq.loop`, and `cljseq.ctrl` run
without the sidecar. Only `cljseq.integration-test` requires it.

```bash
lein test cljseq.core-test cljseq.ctrl-test cljseq.phasor-test
```

---

## 4. Build with Ableton Link support (optional, GPL-2.0-or-later)

Ableton Link provides beat-accurate tempo synchronization with other Link-
enabled applications on the same LAN (Ableton Live, Bitwig, Sonic Pi, etc.).

> **License note**: The [Ableton Link SDK](https://github.com/Ableton/link) is
> GPL-2.0-or-later. Enabling Link changes the license of the sidecar binary from
> LGPL-2.1-or-later to GPL-2.0-or-later. The Clojure library (EPL-2.0) is
> unaffected. See [doc/licensing.md](doc/licensing.md) for full details.

```bash
cmake -B build \
  -DCMAKE_BUILD_TYPE=Release \
  -DCLJSEQ_ENABLE_LINK=ON
cmake --build build --parallel
```

**Additional FetchContent download** (~40 MB shallow clone of the Link SDK):

| Library | Version | License |
|---------|---------|---------|
| [Ableton Link](https://github.com/Ableton/link) | 3.0.4 | GPL-2.0-or-later |

The Link SDK's own Asio dependency is satisfied by the Asio already fetched in
step 2 — no duplicate download.

Once built, enable Link from the REPL:

```clojure
(require '[cljseq.link :as link])
(start!)
(link/enable!)      ; joins or creates a Link session
(link/bpm)          ; => current session BPM
(link/peers)        ; => number of connected peers
```

---

## 5. Development workflow

### Iterating on C++

After editing sidecar source, rebuild with:

```bash
cmake --build build --parallel
```

The Clojure tests call `sidecar/start-sidecar!` which spawns the binary from
`build/cpp/cljseq-sidecar/cljseq-sidecar`, so a rebuilt binary is picked up
automatically on the next test run or REPL restart.

### Live coding at the REPL

The Clojure library supports hot-reloading without restarting the JVM.
Re-evaluating a `deflive-loop` form swaps its body on the next iteration:

```clojure
;; Running loop:
(deflive-loop :bass {}
  (play! :C2)
  (sleep! 1))

;; Edit and re-evaluate — takes effect on next iteration:
(deflive-loop :bass {}
  (play! :G2)
  (sleep! 1/2))
```

### Running tests during development

```bash
lein test                      # full suite
lein test cljseq.core-test     # single namespace
```

---

## 6. Directory layout

```
cljseq/
├── src/cljseq/         Clojure library (EPL-2.0)
│   ├── core.clj        System lifecycle, public API
│   ├── loop.clj        deflive-loop, sleep!, sync!
│   ├── clock.clj       Clock primitives, beat arithmetic
│   ├── ctrl.clj        Control tree (bind!, send!, undo!)
│   ├── link.clj        Ableton Link client
│   └── sidecar.clj     Sidecar process management + IPC
├── test/cljseq/        Clojure tests
├── cpp/
│   ├── libcljseq-rt/   Shared C++ runtime (LGPL-2.1-or-later)
│   │   ├── include/    Public headers (scheduler, link_bridge)
│   │   └── src/        Scheduler, clock, OSC codec
│   ├── libcljseq-link/ Ableton Link engine (GPL-2.0-or-later, opt-in)
│   ├── cljseq-sidecar/ MIDI sidecar binary (LGPL, or GPL when Link enabled)
│   └── cljseq-audio/   CLAP audio host binary
├── doc/                Design documents, R&R, open questions
├── CMakeLists.txt      Root C++ build
├── project.clj         Clojure build (Leiningen)
├── BUILD.md            This file
└── README.md           Project overview
```

---

## 7. Troubleshooting

**`cljseq-sidecar binary not found`**
Run `cmake --build build` (step 2) before starting the Clojure REPL or tests.

**`MIDI init failed — continuing without MIDI`**
The sidecar found no MIDI output ports. On macOS, enable the IAC Driver
in Audio MIDI Setup. The sidecar runs in a degraded mode; REPL sessions that
don't call `play!` are unaffected.

**`Could not connect to cljseq-sidecar after 10 attempts`**
The sidecar failed to start. Check `lein test` output for a preceding C++
error message. Common causes: missing MIDI driver on Linux
(`sudo apt install libasound2-dev` before building), or binary not rebuilt
after a source change.

**CMake FetchContent hangs or fails**
The first build requires internet access. If your environment is air-gapped,
pre-populate the FetchContent cache by running the build on a connected machine
and copying `build/_deps/` to the target machine before running CMake.
Set `-DFETCHCONTENT_UPDATES_DISCONNECTED=ON` (it is on by default).

**Clojure tests fail with `java.net.ConnectException`**
Integration tests spawn the sidecar on a random port. If the port is blocked
by a firewall, all connections are to `127.0.0.1` and only need the loopback
interface — ensure `lo` is not blocked.

**Apple Silicon (`arm64`)**
The build works natively on Apple Silicon. No Rosetta required. CMake selects
the native architecture automatically.

---

## 8. Dependency versions and update policy

All C++ dependencies are pinned to exact tags in `CMakeLists.txt`. Update
deliberately and record the reason in the CMakeLists comment. Do not use
`GIT_TAG main` or floating tags.

| Dependency | Pinned tag | How to update |
|------------|-----------|---------------|
| Asio | `asio-1-30-2` | Edit root `CMakeLists.txt`; re-run `cmake -B build` |
| RtMidi | `6.0.0` | Edit root `CMakeLists.txt`; delete `build/_deps/rtmidi-*`; re-run |
| Ableton Link | `Link-3.0.4` | Edit `cpp/libcljseq-link/CMakeLists.txt`; delete `build/_deps/ableton-link-*`; re-run |

To force a re-fetch after updating a pin:

```bash
rm -rf build/_deps/<library>-src build/_deps/<library>-build
cmake --build build
```
