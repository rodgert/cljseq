# ITemporalValue Design Spike — Decision Record

**Sprint**: Sprint 4
**Status**: Complete
**Spike namespace**: `cljseq.spike.temporal`

---

## Objective

Implement the minimal `ITemporalValue` protocol in a scratch namespace and
verify the three composition acceptance criteria from Q44 before committing
to implementations of `cljseq.clock`, `cljseq.mod`, `cljseq.timing`, and
control-tree parameter binding.

---

## Protocol Definition

```clojure
(defprotocol ITemporalValue
  (sample    [v beat] "Sample the value at `beat`. Returns a number.")
  (next-edge [v beat] "Return the beat of the next 0→1 rising edge after `beat`."))
```

`beat` is a rational number (Clojure ratio or integer). `sample` returns a
number whose interpretation is context-dependent:

| Consumer context       | Interpretation of return value          |
|------------------------|-----------------------------------------|
| Clock / gate           | ≥ 0.5 = high; < 0.5 = low              |
| Parameter modulator    | Numeric value applied to control tree   |
| Timing modulator       | Long nanosecond offset to `time_ns`     |

`next-edge` is used by `clock-div` and beat-sync primitives to determine
when to advance. Implementations must return a beat **strictly greater than**
the input beat (no infinite loops).

---

## Acceptance Criteria Verified

### Criterion 1 — Modulator as clock source

```clojure
(def divided-clock (clock-div 4 (lfo :rate 0.1)))
(sample divided-clock 0)     ;=> 1.0  (beat 0 is a multiple of 4)
(sample divided-clock 1)     ;=> 0.0
(sample divided-clock 4)     ;=> 1.0
(next-edge divided-clock 1)  ;=> 4
```

`ClockDiv` composes with any `ITemporalValue` as its source. An LFO is a
valid clock source — the divided clock fires on every 4th LFO rising edge.
This is the VCV Rack clock-division pattern; it works without any special
casing.

### Criterion 2 — Modulator bound to control tree parameter

```clojure
(ctrl-bind! [:filter/cutoff] (lfo :rate 0.25))
@*bound-params*
;=> {[:filter/cutoff] #cljseq.spike.temporal.Lfo{:rate 0.25 :shape :sine}}
```

The control tree binding accepts any `ITemporalValue`; the subsystem samples
it on each clock tick and writes the result via `ctrl/set!`. No special LFO
type required — any modulator that satisfies the protocol can drive any
parameter.

### Criterion 3 — Timing modulator bound to time_ns

```clojure
(timing-bind! (swing :amount 0.55))
@*bound-timing*
;=> [#cljseq.spike.temporal.Swing{:amount 0.55}]

(sample (first @*bound-timing*) 0.5)  ;=> 11000000  (~11ms push late on off-beat)
(sample (first @*bound-timing*) 0.0)  ;=> 0          (on the beat: no offset)
```

`Swing` returns a nanosecond offset. The timing pipeline applies it after
Ableton Link's `timeAtBeat`, then delivers the adjusted `time_ns` to the
IPC scheduler. Virtual time is unperturbed (Q35 resolution holds: jitter
is wall-clock only).

---

## Implementation Notes

### Record types chosen over multimethods

Each temporal value type is a `defrecord`. This gives:
- Value semantics (equality, hashability, printability)
- Protocol dispatch via interface (no reflection)
- Easy serialization for patch storage (EDN-readable)

Multimethods would add flexibility but introduce dispatch overhead on the
hot path. Records are the right choice here.

### Rational beat arithmetic

Clojure ratios (`1/4`, `3/8`) are the natural type for `beat`. The spike
uses `long` truncation in `ClockDiv` and `Swing` for simplicity; the
production implementation should use `clojure.lang.Ratio` or a dedicated
beat type to avoid floating-point accumulation across many beats.

### Composition is structural, not nominal

`clock-div` wraps any `ITemporalValue` as its source without caring about
its concrete type. This is the key insight from VCV Rack and SuperCollider:
modularity comes from a shared protocol, not a class hierarchy.

### `next-edge` contract

`next-edge` is only meaningful for gate/clock consumers. Parameter and
timing modulators implement it conservatively (returning `beat + 1`) because
their consumers never call it. The protocol imposes no penalty for this.

---

## Decisions Confirmed

| Decision | Confirmation |
|----------|-------------|
| Single `ITemporalValue` protocol (not split by role) | Confirmed — one protocol composes cleanly across all three criteria |
| `beat` as rational, not wall-clock time | Confirmed — all composition is beat-relative; wall-clock is a delivery concern only |
| Records, not maps or multimethods | Confirmed — value semantics + hot-path dispatch performance |
| Timing modulator returns `Long(ns)` | Confirmed — additive composition with Link `timeAtBeat` is natural |
| Virtual time unperturbed by jitter | Confirmed — `Swing.sample` returns wall-clock offset; virtual beat is clean |
| Option C (role wrappers) not needed | Confirmed YAGNI — the protocol is sufficient; add wrappers only on demonstrated need |

---

## Unblocked Subsystems

This spike clears the path for:
- `cljseq.clock` — `MasterClock` and `ClockDiv` implementations
- `cljseq.mod` — `Lfo`, `Envelope`, random generators
- `cljseq.timing` — `Swing`, `Humanize`, `GrooveTemplate`, `Quantize`
- `cljseq.ctrl` parameter binding — `ctrl/bind!` accepts any `ITemporalValue`
- C++ `ITemporalValue` mirror in `cpp/libcljseq-rt/include/cljseq/temporal.h`
  (already stubbed; ready for implementation)
