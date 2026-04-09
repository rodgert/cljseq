# cljseq Threshold Extractor — Design Document

**Status:** Design / pre-implementation
**Author:** Thomas Rodgers
**Date:** 2026-04-07
**Branch:** TBD (`feature/threshold-extractor` proposed)

---

## 1. Motivation

The MakeNoise GTE (Gestural Time Extractor) is an 8HP Eurorack module that divides
a voltage range into eight threshold zones and fires gate/trigger outputs as a CV
signal crosses those boundaries. The rate of crossing *is* the rhythm: a fast-moving
signal generates dense pulses; a slow-moving signal generates sparse pulses; a
stationary signal generates nothing. It is a discrete first-derivative detector
expressed as pulse density rather than a continuous voltage.

cljseq currently has no analog of this concept. Every event source is either:
- A **generator**: creates events from patterns, algorithms, or stochastic processes
- A **transformer**: takes events in and emits transformed events (see Memory Field)

The Threshold Extractor introduces a third paradigm: the **extractor**. It watches
a continuously changing value — a trajectory curve, a phasor, a live CC input, any
fn of beat-position — and converts the motion of that value into discrete note or
control events. The rhythm emerges from the shape of the curve, not from a pattern
or a random process.

**Hardware reference:** MakeNoise GTE (released 2026-03-26). The GTE operates in
the CV/voltage domain; the Threshold Extractor is its note-event analog, with
extensions that the hardware cannot offer: scale awareness, harmonic routing, and
integration with cljseq's trajectory and phasor infrastructure.

---

## 2. Core Concept

A **Threshold Extractor** watches a source function `f(beat) → [0.0, 1.0]` and
maintains a **threshold comb**: N evenly-spaced boundaries that divide the source
range into N zones (channels). Whenever the source value crosses a boundary, the
extractor fires an output event.

```
Source value:  0.0 ──────────────────────────────── 1.0
                    |    |    |    |    |    |    |
Channels:          ch1  ch2  ch3  ch4  ch5  ch6  ch7  ch8
```

As the source traverses this comb:
- **Crossing rate** → event density (the GTE Output equivalent)
- **Current channel** → which of N outputs is active
- **Direction** → rising or falling (can be used to differentiate output type)

The `Space` parameter (following GTE nomenclature) controls what fraction of the
source range the comb spans. At full Space the comb covers [0.0, 1.0]; at half
Space it covers a tighter band around the current center, making the extractor
hypersensitive to small movements in that region.

---

## 3. Source Types

Any continuously varying value can serve as an extractor source:

### 3.1 Trajectory Curves

```clojure
;; A tension arc rises from 0→1 over 32 bars
;; The extractor fires events at the rate of the curve's slope
(defthreshold-extractor :tension-rhythm
  {:source   (trajectory :smooth-step {:bars 32})
   :channels 8
   :space    1.0})
```

A trajectory curve that steepens near a peak generates denser events approaching
the peak and sparser events during the plateau. The rhythm *is* the tension arc —
structural and expressive information collapse into the same signal.

### 3.2 Phasors

```clojure
;; A phasor cycling every 4 bars fires 7 events per cycle (one per crossing)
(defthreshold-extractor :phasor-rhythm
  {:source   (phasor {:cycle-bars 4})
   :channels 8
   :space    1.0})
```

A regular phasor produces regular crossings — evenly-spaced events within the
cycle. Changing the phasor rate changes the event rate proportionally. Non-linear
phasors (accelerating, decelerating) produce non-uniform rhythms from the same
threshold comb.

### 3.3 Live Parameter Values

```clojure
;; Extract events from an incoming CC value (e.g., expression pedal)
(defthreshold-extractor :pedal-rhythm
  {:source   (cc-source :expression-pedal {:channel 1 :cc 11})
   :channels 8
   :space    0.7
   :clock    :eighth-note})  ; clock-gated: only update on eighth-note boundaries
```

Gesture input — expression pedal, mod wheel, aftertouch — becomes a rhythmic
generator. Slow, sweeping gestures produce gradual rhythmic density changes; quick
gestures produce bursts.

### 3.4 Memory Field Output

```clojure
;; The pitch stream from a Memory Field head drives the extractor
;; Pitch mapped 0→1 over a defined range, thresholds at scale degrees
(defthreshold-extractor :pitch-follower
  {:source   (memory-field-pitch :cosmos :head-1 {:range [:C3 :C5]})
   :channels 12   ; one per chromatic pitch class
   :space    1.0
   :tuning   *tuning-ctx*})
```

This is the scale-aware mode: threshold boundaries sit at scale degree positions
rather than equal divisions of the value range (see §5).

---

## 4. Clock-Gated Mode

Without a clock input, the extractor tracks the source continuously and fires
events on every crossing. With a clock input, channel selection is sampled only
at clock edges — the current channel is latched until the next edge.

```
Clock-gated behavior:
  Source value moves freely between clocks
  On each clock edge: sample current channel → fire event if channel changed
  Between clock edges: no output regardless of source motion
```

This transforms the extractor from a continuous gesture follower into a rhythmic
quantizer. The pattern that emerges depends on the phase relationship between the
source curve's motion and the clock rate. Small changes in either produce very
different rhythmic outputs — a generative surface with rich sensitivity.

```clojure
{:clock :quarter-note}     ; sample and fire on every quarter note
{:clock :eighth-note}      ; finer resolution
{:clock :bar}              ; coarse — one event per bar maximum
{:clock nil}               ; continuous (default)
```

---

## 5. Scale-Aware Threshold Placement

In the standard GTE model, thresholds are evenly spaced across the source range.
cljseq can place thresholds at musically meaningful positions instead.

### 5.1 Scale-Degree Thresholds

Map the source range to a pitch range; place thresholds at scale degree boundaries
in the active tuning context:

```
Source [0.0, 1.0] → Pitch range [C3, C5]
Thresholds: at each scale degree boundary within that range
```

In 12-TET: thresholds at each semitone — 24 channels over two octaves.
In diatonic: thresholds at the 7 scale degrees — sparser, harmonically structured.
In just intonation: thresholds at JI ratio positions — unequal spacing, wider gaps
between some degrees, narrower between others.

The unequal spacing in non-ET tunings means the extractor is naturally more
sensitive near closely-spaced scale degrees and less sensitive in wider gaps. This
is musically meaningful: the rhythm produced by traversing a JI scale has different
density characteristics than traversing 12-TET.

### 5.2 Interval-Based Thresholds

Place thresholds at specific harmonic intervals rather than full scale:

```clojure
{:thresholds :fifths}      ; 0, 7, 14, 21 semitones (cycle of fifths positions)
{:thresholds :pentatonic}  ; 5 thresholds at pentatonic scale degrees
{:thresholds :overtone}    ; thresholds at harmonic series ratios
```

### 5.3 Custom Threshold Placement

```clojure
{:thresholds [0.1 0.25 0.4 0.5 0.65 0.8 0.95]}  ; arbitrary positions in [0,1]
```

Unequal threshold spacing creates rhythmic accent structures: thresholds clustered
together generate rapid bursts when the source passes through that region; wide
gaps create pauses.

---

## 6. Output Routing

The GTE has three output types: individual channel gates, Odd/Even gates, and the
GTE crossing pulse. cljseq generalizes these:

### 6.1 Crossing Events (GTE Output equivalent)

A trigger-like event fires on every threshold crossing. Output type is configurable:

```clojure
{:on-cross {:type    :note
            :pitch   :C4           ; fixed pitch, or derived from channel (see below)
            :velocity 80
            :duration 0.1}}        ; short — drum-like

{:on-cross {:type    :cc
            :cc      48
            :value   127}}         ; CC burst on each crossing

{:on-cross {:type    :fn
            :f       (fn [ch prev-ch direction beat] ...)}}  ; arbitrary
```

### 6.2 Channel Events (individual channel outputs)

On each channel change, fire an event associated with that specific channel:

```clojure
{:channels
 {:routing :scale-degree     ; ch1→tonic, ch2→2nd degree, etc. in *harmony-ctx*
  :type    :note
  :velocity-from :speed}}    ; crossing speed → velocity (fast crossing = loud)
```

Channel routing options:
- `:scale-degree` — each channel maps to a scale degree in `*harmony-ctx*`
- `:voice` — each channel activates a different live loop or Memory Field
- `:parameter` — each channel sets a specific CC value on a target device
- `:fn` — arbitrary routing function

### 6.3 Odd/Even Outputs

```clojure
{:odd-even
 {:odd  {:type :note :pitch :C3}   ; fires when active channel is odd
  :even {:type :note :pitch :G3}}} ; fires when active channel is even
```

Odd/even differentiation is a simple accent structure: with 8 channels, every
other crossing fires a different event — a natural backbeat or emphasis pattern
emerges from the traversal.

### 6.4 Velocity from Crossing Speed

The rate at which the source crosses a threshold encodes musical velocity:

```clojure
{:velocity-from :crossing-speed
 :velocity-range [20 120]   ; map slowest→fastest crossings to this range
 :velocity-curve :log}      ; or :linear, :exp
```

Fast traversal (high tension, gesture urgency) → high velocity.
Slow traversal (relaxed motion) → low velocity.
This is the most direct musical translation of the GTE's implicit pulse density
information — velocity encodes what GTE encodes as pulse rate.

---

## 7. Space Parameter — Sensitivity Control

Space controls what fraction of the source range the threshold comb spans. The
center of the comb can be fixed or tracked.

```clojure
{:space       0.5      ; comb spans half the source range
 :space-center 0.5     ; centered at midpoint
 :space-track  true}   ; center follows a slow-moving average of the source
```

With `:space-track true`, the comb follows the gesture like a magnifying glass —
always covering the region where the source is currently active, at high resolution.
Small gestures within a larger motion still generate rich event streams.

**Space as a CV-targetable parameter:**

```clojure
;; Space itself can be driven by another trajectory
(memory-set! :rhythm-extractor :space
  (trajectory :breathe {:bars 16 :range [0.2 0.9]}))
```

As Space opens and closes over 16 bars, the same source gesture produces alternating
dense and sparse rhythmic textures — without changing the gesture itself.

---

## 8. Relationship to Existing cljseq Concepts

### 8.1 The Generator / Transformer / Extractor Family

| Type | Concept | Input | Output |
|------|---------|-------|--------|
| Generator | `deflive-loop`, stochastic, fractal | None / seed | Note events |
| Transformer | Memory Field (`cljseq.memory`) | Note events | Note events |
| Extractor | Threshold Extractor (`cljseq.extractor`) | Continuous value | Note/control events |

These three are compositionally orthogonal. A generator can feed a transformer;
a transformer's pitch stream can feed an extractor; an extractor's output can
trigger a generator. Complex textures emerge from their combination.

### 8.2 Phasor Integration

`cljseq.phasor` generates advancing phase values. A phasor is the ideal extractor
source: it advances monotonically (every threshold crossed exactly once per cycle),
making event timing predictable while remaining sensitive to rate changes.

Non-linear phasors (generated by trajectory-modulated rate) produce non-uniform
rhythms from a uniform threshold comb — the rhythm encodes the rate envelope
of the phasor.

### 8.3 Trajectory Integration

`cljseq.trajectory` curves are the most musically expressive extractor sources.
The slope of a trajectory curve at any point determines the local crossing rate —
the rhythm produced by a `:tension-peak` gesture accelerates through the build,
bursts at the peak, and relaxes into silence at the resolution.

Trajectory curves become **latent rhythmic generators**: the same curve that
shapes a filter sweep or a dynamics arc *also* contains rhythmic information that
the extractor surfaces.

### 8.4 Harmony / Scale Integration

`*harmony-ctx*` provides the scale degree positions that scale-aware threshold
placement uses. The extractor is the first cljseq concept that makes rhythm and
harmony *structurally coupled*: the scale determines where events fire, so the
same gesture in different keys or modes produces different rhythmic patterns.

---

## 9. Example Patches

### 9.1 Tension Arc as Drummer

```clojure
;; A 32-bar tension arc generates a drum pattern that gets denser as tension builds
(def tension (trajectory :smooth-step {:bars 32}))

(defthreshold-extractor :tension-drums
  {:source      tension
   :channels    8
   :space       0.8
   :on-cross    {:type :note :pitch :C1 :velocity-from :crossing-speed}
   :odd-even    {:odd  {:pitch :C1}   ; kick on odd channels
                 :even {:pitch :D1}}}) ; snare on even channels
```

### 9.2 Memory Field Pitch → Harmonic Rhythm

```clojure
;; The drifting pitches from a Memory Field head trigger notes when
;; they cross scale degree boundaries — harmony and rhythm become one signal
(defthreshold-extractor :harmonic-extractor
  {:source      (memory-field-pitch :cosmos :head-1 {:range [:C3 :C5]})
   :thresholds  :scale-degree
   :channels    :diatonic          ; 7 channels per octave
   :on-channel  {:type    :note
                 :pitch   :from-channel   ; fire the scale degree being crossed
                 :velocity-from :crossing-speed
                 :duration 0.5}})
```

### 9.3 Pedal Gesture as Melodic Trigger

```clojure
;; Expression pedal motion generates a melody from scale degrees
(defthreshold-extractor :pedal-melody
  {:source      (cc-source {:channel 1 :cc 11})
   :thresholds  :pentatonic
   :channels    10               ; two octaves of pentatonic
   :clock       :eighth-note     ; only fire on eighth-note boundaries
   :on-channel  {:type    :note
                 :pitch   :from-channel
                 :velocity 70
                 :duration :until-next}})  ; hold until next crossing
```

---

## 10. Relationship to N.U.S.S. / Eurorack Integration

In the studio, the MakeNoise GTE operates in the CV domain. The Threshold Extractor
operates in the note-event domain. They are complementary, not redundant:

| | GTE (hardware) | Threshold Extractor (cljseq) |
|--|----------------|------------------------------|
| Domain | CV / voltage | Note events / MIDI |
| Source | Any CV signal | Any cljseq value function |
| Output | Gate pulses | Notes, CCs, control events |
| Scale-aware | No | Yes |
| Harmony-aware | No | Yes |
| Velocity encoding | Implicit (pulse rate) | Explicit (crossing speed) |
| Feedback to Memory Field | Via CV | Direct API |

A compound patch: cljseq Threshold Extractor fires MIDI notes → Cascadia converts
to CV → GTE watches the Cascadia's filter envelope output → GTE channel gates fire
Eurorack envelope generators → audio result feeds back into the DAW. cljseq and
the GTE are working at different layers of the same signal chain.

The N.U.S.S. Channel Index concept (0.5V per step staircase) has a direct cljseq
analog: a phasor quantized to N steps that encodes which voice, scale degree, or
parameter set is currently active. This could serve as an inter-module addressing
scheme within cljseq's distributed ctrl tree — a shared phasor that multiple live
loops or Memory Fields subscribe to, each activating when the phasor enters their
channel zone.

---

## 11. Open Design Questions

1. **Source normalization**: Sources must map to [0.0, 1.0]. Should normalization
   be automatic (with declared min/max) or the caller's responsibility?
   *Proposed: automatic with declared range; extractor normalizes internally.*

2. **Hysteresis**: To prevent rapid re-triggering when the source hovers near a
   threshold, a hysteresis band can be added (must move past threshold + ε before
   re-triggering). Essential for noisy or jittery sources.
   *Proposed: configurable hysteresis parameter, default small non-zero value.*

3. **Polarity**: Should rising and falling crossings produce different events?
   The GTE does not differentiate. But rising-vs-falling is musically meaningful
   (attack vs. release, in vs. out).
   *Proposed: `:direction :both | :rising | :falling` per extractor.*

4. **Multi-extractor combination**: Can two extractors be logically combined?
   (e.g., fire only when both are in the same channel — AND logic, like the GTE's
   Odd/Even outputs used together.) *Proposed: defer to Phase 2.*

5. **Namespace**: `cljseq.extractor`? `cljseq.gte`? `cljseq.threshold`?
   *Proposed: `cljseq.extractor` — most general name, not tied to hardware.*

6. **Interaction with `*tuning-ctx*`**: Same as Memory Field — read dynamically
   at crossing time so tuning changes mid-piece affect threshold placement.
   *Proposed: yes, dynamic.*

---

## 12. Implementation Phases

### Phase 1 — Core extractor
- `cljseq.extractor` namespace
- `defthreshold-extractor` macro
- Uniform threshold placement (N equal zones)
- Continuous mode (no clock gating)
- `:on-cross` event output (note or CC)
- Source: trajectory curves and phasors
- Tests: crossing detection, event timing, space parameter

### Phase 2 — Clock gating and velocity
- Clock-gated mode (`:clock` parameter)
- Crossing speed → velocity mapping
- `:on-channel` routing
- Odd/even output differentiation
- Hysteresis parameter
- Tests: gated behavior, velocity scaling, odd/even logic

### Phase 3 — Scale-aware thresholds
- `:thresholds :scale-degree` mode
- Integration with `*harmony-ctx*` and `*tuning-ctx*`
- Non-ET threshold placement (JI, maqam, pelog)
- Pentatonic, fifths, overtone threshold presets
- Tests: threshold positions in various tunings, non-uniform spacing

### Phase 4 — Live sources and advanced routing
- Live CC/parameter sources
- Memory Field pitch stream as source
- `:pitch :from-channel` routing
- Space tracking (center follows source)
- Space as trajectory target

### Phase 5 — Integration
- Conductor/trajectory integration (space, channel routing as arc targets)
- Inter-extractor combination (AND/OR logic)
- N.U.S.S.-style phasor channel addressing (shared phasor → multiple subscribers)

---

## 13. References

- **MakeNoise GTE** — Gestural Time Extractor; released 2026-03-26; 8HP, $179 USD.
  The hardware inspiration. Manual: makenoise-manuals.com/gte/gte-manual.pdf
- **MakeNoise PoliMATHS** — N.U.S.S. source module; Channel Index Output encodes
  active channel as staircase (0.5V/channel); FDD for simultaneous multi-channel
- **N.U.S.S.** (New Universal Synthesizer System) — MakeNoise inter-module
  protocol; Channel Index as shared addressing medium
- **cljseq.phasor** — advancing phase values; ideal extractor source
- **cljseq.trajectory** — curve functions; latent rhythmic generators via extractor
- **cljseq.memory** (`design-memory-field.md`) — the transformer paradigm;
  extractor output can feed Memory Field input
- **cljseq.harmony / *harmony-ctx*** — scale degree positions for threshold placement
- **Intellijel Cascadia** — MIDI→CV gateway; extractor MIDI output → Cascadia CV →
  GTE CV input closes the loop between cljseq and hardware GTE
