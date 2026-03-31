# cljseq Sprint 8 — C++ Native Handoff for GitHub Copilot

**Date**: 2026-03-31
**Prepared by**: Claude (Anthropic) for GitHub Copilot handoff
**Scope**: CMake build, `libcljseq-rt`, `cljseq-sidecar` — Phase 1 MIDI output

---

## What cljseq is

cljseq is a music-theory-aware Clojure sequencer targeting MIDI and OSC, designed
for live coding at an nREPL. The JVM process is the user-facing layer (DSL, live
loops, control tree). A companion C++ sidecar process (`cljseq-sidecar`) handles
real-time MIDI hardware output. A second C++ binary (`cljseq-audio`) handles CLAP
plugin hosting — that is out of scope for Sprint 8.

```
┌─────────────────────────────────────────┐
│  JVM (Clojure)                          │
│  cljseq.core / cljseq.loop / cljseq.dsl │
│  deflive-loop, play!, ctrl/send!        │
│                    │                    │
│          cljseq.sidecar (Clojure ns)    │
│          spawns + monitors processes    │
└────────────────────┬────────────────────┘
                     │  TCP localhost IPC
          ┌──────────▼──────────┐
          │  cljseq-sidecar     │
          │  (C++ executable)   │
          │  ipc.cpp            │
          │  midi_dispatch.cpp  │
          │  → platform MIDI    │
          └─────────────────────┘
```

---

## Repository layout (C++ relevant files)

```
CMakeLists.txt                        Root build — Asio FetchContent, options
cpp/
  libcljseq-rt/
    CMakeLists.txt                    Shared library target (cljseq-rt)
    include/cljseq/temporal.h         C++ mirror of ITemporalValue — NEEDS UPDATE
    src/clock.cpp                     Stub
    src/scheduler.cpp                 Stub
    src/osc.cpp                       Stub
  cljseq-sidecar/
    CMakeLists.txt                    Executable target
    src/main.cpp                      Stub (int main() { return 0; })
    src/ipc.cpp                       Stub
    src/midi_dispatch.cpp             Stub
    src/osc_dispatch.cpp              Stub
  cljseq-audio/
    CMakeLists.txt                    (Out of scope for Sprint 8)
    src/main.cpp                      Stub
    src/audio_io.cpp                  Stub
    src/clap_host.cpp                 Stub
doc/
  licensing.md                        EPL-2.0 / LGPL-2.1-or-later / GPL-2.0-or-later
  open-design-questions.md            All design decisions — read before changing anything
  research-and-requirements.md        Full R&R document
```

---

## Key design decisions already made — do not revisit

### Licensing
- All authored C++ is **LGPL-2.1-or-later**. SPDX header required on every file.
- Builds with `CLJSEQ_ENABLE_LINK=ON` (Ableton Link) become **GPL-2.0-or-later**.
- See `doc/licensing.md`.

### Native dependency policy (Q51 — RESOLVED)
- **No Boost**. C++17 STL is sufficient for everything except async I/O.
- **Standalone Asio** (BSL-1.0, header-only) for async I/O — already in
  `CMakeLists.txt` via FetchContent pinned at `asio-1-30-2`.
- **FetchContent + pinned tag** for any new dependency with a build step.
- **No `find_package`** for libraries that vary across distro packaging.
- When pinning a new dep, document the tag and reason in the CMakeLists comment.

### Process topology (Q16 — RESOLVED)
- The JVM talks **directly** to each sidecar/audio process. There is no router.
- `cljseq.sidecar` (Clojure namespace) is responsible for spawning, monitoring,
  and restarting child processes.
- Event routing (which note goes to which process) is decided in the JVM.

### IPC transport — **DECISION REQUIRED for Sprint 8**
Q16 establishes direct JVM-to-process IPC but does not specify the transport.
**Recommended choice: TCP localhost socket via Asio**.

Rationale:
- Asio is already a build dependency — no new dep needed.
- Uniform API across macOS, Linux, Windows — no platform `#ifdef` for socket type.
- Port is allocated dynamically; JVM passes it as a command-line argument when
  spawning the sidecar.
- Latency on loopback is sub-millisecond — acceptable for MIDI scheduling.

Unix domain sockets have lower latency but require platform branching on Windows.
stdin/stdout is simpler but conflates logging with the event stream.
TCP localhost via Asio is the right trade-off for Phase 1.

### C++ standard
- **C++17** throughout. `set(CMAKE_CXX_STANDARD 17)` is already set in root CMake.
- User is a libstdc++ maintainer. Modern C++17 idioms are welcome.

---

## Sprint 8 objectives

### 1. Update `temporal.h` for the phasor architecture (R&R §28)

The Clojure side has just been refactored. `ITemporalValue` protocol is unchanged
but `MasterClock` and `ClockDiv` are gone, replaced by `Phasor`. The C++ header
needs to reflect this.

The new `temporal.h` should contain:

**a) `ITemporalValue` interface** — unchanged from current except `Beat` should
use `double` internally (rational beat arithmetic happens on the Clojure side;
the C++ side receives `double` beat positions from the scheduler):

```cpp
// Keep Beat as a value type but add a double constructor for convenience
struct Beat {
    int64_t num;
    int64_t den;
    constexpr double to_double() const noexcept {
        return static_cast<double>(num) / static_cast<double>(den);
    }
    static constexpr Beat from_double(double d) {
        // Phase 1: represent as integer + fractional using 1000000 denominator
        return Beat{ static_cast<int64_t>(d * 1'000'000), 1'000'000 };
    }
};
```

**b) Phasor operations** as `constexpr` free functions in `namespace cljseq::phasor`
(mirrors `cljseq.phasor` Clojure namespace — R&R §28.3):

```cpp
namespace cljseq::phasor {
    constexpr double wrap(double x) noexcept;
    constexpr double fold(double p) noexcept;
    constexpr double scale(double p, double lo, double hi) noexcept;
    constexpr int    index(double p, int n) noexcept;
    constexpr double step(double p, int n) noexcept;
    constexpr int    delta(double p, double p_prev) noexcept;  // 1 at wrap, 0 otherwise
    constexpr double sync(double p, double trigger) noexcept;
}
```

**c) `Phasor` struct** implementing `ITemporalValue` (mirrors the Clojure
`Phasor` record — R&R §28.2):

```cpp
struct Phasor : public ITemporalValue {
    double rate;          // cycles per beat
    double phase_offset;  // [0.0, 1.0)

    double sample(Beat beat) const override;
    Beat   next_edge(Beat beat) const override;
};

// Named constructors (mirror Clojure clock/master-clock etc.)
inline Phasor master_clock()             { return {1.0, 0.0}; }
inline Phasor clock_div(double n)        { return {1.0 / n, 0.0}; }
inline Phasor clock_mul(double n)        { return {n, 0.0}; }
inline Phasor clock_shift(double n, double offset) { return {1.0 / n, offset}; }
```

**d) Shape functions** in `namespace cljseq::phasor` (mirrors Clojure shape fns):

```cpp
namespace cljseq::phasor {
    double sine_uni(double p) noexcept;
    double sine_bi(double p) noexcept;
    double triangle(double p) noexcept;   // = fold(p)
    double saw_up(double p) noexcept;
    double saw_down(double p) noexcept;
    // square: returns a lambda capturing pulse_width
    auto   square(double pw) noexcept;
}
```

All of these are single-line implementations — `cmath` for sin, direct arithmetic
for the rest.

---

### 2. IPC framing layer (`ipc.cpp`)

Implement length-prefixed binary framing over the Asio TCP socket.

**Message format** (little-endian throughout):

```
┌──────────────────────────────────────────────────────────┐
│ uint32_t  msg_len   (bytes of payload, excluding header) │
│ uint8_t   msg_type  (see enum below)                     │
│ uint8_t   reserved[3]                                    │
│ uint8_t   payload[msg_len]                               │
└──────────────────────────────────────────────────────────┘
```

**Message types** for Phase 1 (MIDI only):

```cpp
enum class MsgType : uint8_t {
    NoteOn      = 0x01,
    NoteOff     = 0x02,
    CC          = 0x03,
    Ping        = 0xF0,   // keepalive
    Shutdown    = 0xFF,
};
```

**NoteOn/NoteOff payload**:

```
int64_t  time_ns    (absolute wall-clock dispatch time, nanoseconds)
uint8_t  channel    (1–16)
uint8_t  note       (MIDI 0–127)
uint8_t  velocity   (0–127; NoteOff velocity typically 0)
uint8_t  reserved
```

**CC payload**:

```
int64_t  time_ns
uint8_t  channel
uint8_t  cc_number  (0–127)
uint8_t  value      (0–127)
uint8_t  reserved
```

The IPC layer should:
- Accept a single connection from the JVM on a TCP port passed via `--port N` argv
- Use Asio async I/O (not blocking reads)
- Dispatch received messages to `midi_dispatch` on a single thread
- Log errors to stderr; never crash on malformed messages

---

### 3. MIDI dispatch layer (`midi_dispatch.cpp`)

**Dependency**: RtMidi — cross-platform MIDI I/O library.
- License: MIT
- Add to root `CMakeLists.txt` via FetchContent:

```cmake
FetchContent_Declare(rtmidi
  GIT_REPOSITORY https://github.com/thestk/rtmidi.git
  GIT_TAG        6.0.0
  GIT_SHALLOW    TRUE)
set(RTMIDI_BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(rtmidi)
```

Then in `cljseq-sidecar/CMakeLists.txt`, add `rtmidi` to `target_link_libraries`.

**What `midi_dispatch.cpp` must implement for Phase 1:**

```cpp
// Initialise: enumerate and open the first available MIDI output port.
// Log available ports to stderr on startup.
bool midi_init();

// Dispatch a pre-scheduled note-on event.
// Called when wall-clock time >= time_ns.
void midi_note_on(uint8_t channel, uint8_t note, uint8_t velocity);

// Dispatch note-off.
void midi_note_off(uint8_t channel, uint8_t note);

// Dispatch CC.
void midi_cc(uint8_t channel, uint8_t cc, uint8_t value);

// Clean shutdown: close MIDI port.
void midi_shutdown();
```

Port selection for Phase 1: first available output port is sufficient.
Phase 2 will add named port selection via `defdevice` EDN maps.

---

### 4. Scheduler stub (`scheduler.cpp` in `libcljseq-rt`)

For Phase 1, the scheduler's only job is **timed dispatch**: hold a priority queue
of events sorted by `time_ns`, sleep until the next event's scheduled time, then
call the appropriate dispatch function.

```cpp
// Event types correspond to IPC MsgType
struct ScheduledEvent {
    int64_t   time_ns;
    MsgType   type;
    uint8_t   channel;
    uint8_t   note_or_cc;
    uint8_t   velocity_or_value;
};

// Thread-safe enqueue (called from IPC receive thread)
void scheduler_enqueue(ScheduledEvent ev);

// Scheduler loop — runs on its own thread
// Calls midi_note_on / midi_note_off / midi_cc at the right wall-clock time
void scheduler_run();
```

Use `std::priority_queue` with `std::chrono::high_resolution_clock` for timing.
`std::condition_variable` to wake the scheduler thread when a new event is
enqueued earlier than the current sleep target.

---

### 5. `main.cpp` wiring

```cpp
int main(int argc, char* argv[]) {
    // 1. Parse --port N from argv
    // 2. midi_init() — open first MIDI port
    // 3. Start scheduler thread
    // 4. Start Asio IPC server on localhost:N
    // 5. Run Asio event loop (blocks until Shutdown message received)
    // 6. midi_shutdown()
    return 0;
}
```

---

## Files NOT to touch in Sprint 8

| File | Reason |
|------|--------|
| `src/cljseq/*.clj` | Clojure side — separate work stream |
| `cpp/cljseq-audio/**` | Out of scope; CLAP hosting is Phase 3+ |
| `doc/research-and-requirements.md` | Design doc — no code changes needed |
| `doc/open-design-questions.md` | Already reflects all decisions |
| `CMakeLists.txt` root | OK to add FetchContent for rtmidi; do not change Asio pin |

---

## Acceptance criteria for Sprint 8

**Minimum viable sidecar (Phase 1 done):**

1. `cmake -S . -B build && cmake --build build` succeeds on macOS and Linux
   with no warnings at `-Wall -Wextra`.
2. `./build/cpp/cljseq-sidecar/cljseq-sidecar --port 7777` starts, logs available
   MIDI ports to stderr, and waits for a connection.
3. Sending a `NoteOn` message over TCP to port 7777 causes the note to sound on
   the first available MIDI output (verified with a soft-synth or MIDI monitor).
4. A `Shutdown` message causes the process to exit cleanly.
5. `temporal.h` compiles cleanly under `-std=c++17 -Wall -Wextra` with all
   phasor operations implemented and shape functions present.

**Stretch goal (not required for Phase 1):**

- `CLJSEQ_BUILD_TESTS=ON` builds a test executable that verifies `phasor::wrap`,
  `phasor::fold`, `phasor::delta` against known values (mirrors the Clojure
  `phasor_test.clj` assertions).

---

## R&R sections relevant to this work

| Section | Topic |
|---------|-------|
| §3.5 | Process topology and sidecar model |
| §28 | Phasor Signal Architecture — the model `temporal.h` must reflect |
| Q16 | IPC topology decision (direct JVM-to-process, Option A) |
| Q51 | Native dependency policy (no Boost, FetchContent, pinned tags) |
| Licensing | LGPL-2.1-or-later for authored C++; see `doc/licensing.md` |

---

## What the Clojure side will do (for context — do not implement)

When Phase 1 is complete, `cljseq.core/play!` will stop printing to stdout and
instead:
1. Convert beat duration to `time_ns` using current BPM and `System/nanoTime`
2. Serialise a `NoteOn` + `NoteOff` pair into the IPC wire format
3. Send both messages to the sidecar via the TCP socket managed by `cljseq.sidecar`

The Clojure IPC client (`cljseq.sidecar`) is not yet implemented — it will be
written after the sidecar binary is proven to accept and dispatch events correctly.
