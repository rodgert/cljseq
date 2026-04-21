# cljseq Temporal Buffer — Design Document

**Status:** Design / pre-implementation
**Author:** Thomas Rodgers
**Date:** 2026-04-07
**Branch:** TBD (`feature/temporal-buffer` proposed)
**Supersedes:** `design-memory-field.md` (incorporated here as a named preset)

---

## 1. Motivation

Two hardware modules drove this design:

**Soma Laboratory Cosmos** ("Drifting Memory Station") — a continuously-written
audio buffer with multiple drifting playback heads, probabilistic decay, and
feedback. Its note-event translation produced the Memory Field design
(`design-memory-field.md`).

**MakeNoise Mimeophon** ("Stereo Multi-Zone Color Audio Repeater") — a single
continuously-written audio buffer with 8 overlapping windowed zones, a Rate
parameter that couples timing and pitch (tape-Doppler), a Color transform on the
feedback path, and a Halo smear that undergoes a threshold-based phase transition
into the feedback network.

Examining both together reveals that the Memory Field design, while correct, is
incomplete. The Mimeophon introduces concepts the Memory Field does not capture:

- **Overlapping zones** — multiple named windows into one accumulated buffer
- **Zone/Rate orthogonality** — window size changes are pitch-free; playback
  speed changes cause coupled pitch+timing Doppler
- **Color** — a per-pass event transform function richer than velocity attenuation
- **Halo threshold** — smear that qualitatively transitions into the feedback path
  above a threshold, producing a phase shift from discrete echoes to wash
- **Hold mode** — freeze input, continue reading and transforming the frozen buffer

The Memory Field and the Note Mimeophon are both instances of one more general
concept: the **Temporal Buffer**. Flux is a third instance. This document defines
the Temporal Buffer as the root abstraction and derives all three as named presets.

---

## 2. Core Abstraction: The Temporal Buffer

A **Temporal Buffer** is:

```
Temporal Buffer = { buffer, zones[], cursors[], color, halo, feedback, hold }
```

A single continuously-written buffer of beat-timestamped note events. Multiple
overlapping **zones** define named windows into that buffer at different temporal
depths. **Cursors** read from the currently active zone at configurable speeds.
**Color** transforms note events on each feedback pass. **Halo** adds temporal
neighbors and, above a threshold, injects into the feedback network. **Hold**
freezes input and continues evolving the frozen content.

This is not a delay line. It is a time-accumulation system with windowed access.

---

## 3. The Buffer

A single continuously-written store of note events:

```clojure
{:pitch-cents  7200.0   ; stored in cents for sub-semitone precision
 :velocity     80
 :duration     0.5      ; beats
 :beat         42.375   ; absolute beat position when written
 :generation   0        ; feedback generation (0 = original input)
 :halo-depth   0}       ; smear generation (0 = original, 1+ = halo copy)
```

The buffer accumulates note events continuously as long as the Temporal Buffer
is active and not in Hold mode. Its total depth (in beats) sets the maximum zone
size. A 64-bar buffer at 110 BPM ≈ ~35 seconds — comparable to the Mimeophon's
42-second physical limit.

---

## 4. Zones — Overlapping Windows

Zones are named, overlapping windows into the buffer. Each zone defines a
**depth** (how many beats of accumulated history are visible) and an **offset**
(how far back from the write head the window starts).

```clojure
;; Eight overlapping zones, each ~2x the depth of the previous
;; (following Mimeophon's 50% overlap structure)
{:zones
 [{:id :z0 :depth  0.5  :label "sub-beat — Karplus territory"}
  {:id :z1 :depth  2.0  :label "slapback — 2 beats"}
  {:id :z2 :depth  4.0  :label "phrase — 4 beats"}
  {:id :z3 :depth  8.0  :label "phrase — 8 beats"}
  {:id :z4 :depth 16.0  :label "section — 16 beats"}
  {:id :z5 :depth 32.0  :label "section — 32 beats"}
  {:id :z6 :depth 64.0  :label "long — 64 beats"}
  {:id :z7 :depth 128.0 :label "extended — 128 beats"}]}
```

**Zone switching is pitch-free.** When the active zone changes, cursors
reposition to the new window without changing their playback speed. Pitch is
unaffected. This is the defining distinction between Zone and Rate:

| Parameter | Changes | Pitch consequence |
|-----------|---------|-------------------|
| Zone | Window depth / temporal scale | None — pitch-free |
| Rate (cursor speed) | Playback speed within zone | Tape-Doppler: faster = shorter time + higher pitch |

Zone switching in the note domain is implemented by repositioning cursors to the
new window boundary with a brief crossfade — the equivalent of the Mimeophon's
"instantaneous switching without Doppler."

Zones overlap: zone N and zone N+1 share approximately 50% of their depth. This
means switching from zone 3 (8 beats) to zone 4 (16 beats) does not jump to
entirely new material — the last 8 beats are common to both. "Time travel" into
deeper history is gradual.

**Zone 0 — Karplus territory:**
Zone 0's depth is sub-beat (e.g., 0.5 beats at 110 BPM ≈ ~270ms). With high
feedback and a single impulse note, the zone length determines the pitch of the
resonating repeat — the note-event analog of Karplus-Strong physical modeling.
The playback cursor cycles through a handful of notes so rapidly that the result
has the character of a pitched, decaying resonance. Rate in Zone 0 tracks
exponentially (1V/Oct equivalent: doubling rate raises pitch one octave), enabling
melodic Karplus-mode playing.

---

## 5. Rate — Tape-Speed Cursor Control

Rate controls cursor playback speed within the active zone. It couples timing and
pitch — identical to the Mimeophon's tape-speed behavior:

```
repeat_interval = zone_depth / (cursor_speed × rate)
pitch_offset_cents = (rate - 1.0) × tape-scale
```

- **Rate 1.0x** — repeats at natural tempo, no pitch change
- **Rate 2.0x** — repeats twice as fast, pitch raised by `tape-scale` cents
- **Rate 0.5x** — repeats half as fast, pitch lowered by `tape-scale` cents

`tape-scale` [50–200¢] controls pitch sensitivity per unit of rate deviation.
At 100¢: rate 2.0x = +100¢ (~one semitone above original pitch).

**Clocked mode (pitch-free Rate):**
When an external clock source is provided, Rate becomes a clock divider/multiplier
without pitch artifacts — the cursor speed is inferred from the clock division
rather than from the raw speed parameter. Rate changes in clocked mode reposition
the cursor without Doppler. This is the "clean" mode for musical applications
where unintentional pitch effects are unwanted.

**microRate (Zone 0 only, exponential):**
Zone 0 has a separate fine Rate parameter with exponential response — in Zone 0,
doubling microRate raises pitch by one octave. Used for melodic Karplus synthesis.
Equivalent to the Mimeophon's 1V/Oct microRate tracking in Zone 0.

---

## 6. Color — Per-Pass Event Transform

Color is a transform function applied to every note event on each feedback pass.
It shapes the *character* of the feedback tail without changing the overall
feedback amount. Modulating Color mid-performance changes the timbral trajectory
of the accumulated material.

Color operates on a spectrum from dark to bright:

| Color | Per-pass transform |
|-------|--------------------|
| Dark | Velocity × 0.90; duration × 1.15; pitch − N¢ (flat drift) |
| Warm | Velocity × 0.93; duration × 1.05; pitch ≈ 0 |
| Neutral | Velocity × 0.95; duration × 1.0; pitch ≈ 0 |
| Tape | Velocity × 0.95; duration × 0.98; pitch + small random ¢ |
| Bright | Velocity × 0.97; duration × 0.90; pitch + N¢ (sharp drift) |
| Crisp | Velocity × 0.98; duration × 0.80; pitch + N¢ |

The specific multipliers are tunable parameters, not fixed values. The shape of
the Color spectrum is: dark = notes fade, lengthen, and sag; bright = notes hold
velocity longer, shorten, and sharpen. This creates distinctly different feedback
tail characters.

Color interacts with Halo and Feedback — the three form a coupled system:
- Color shapes what is fed back
- Halo determines how much smear enters the feedback
- Feedback determines how many generations accumulate

Sweeping Color while feedback is active changes the *direction* of timbral drift
in the accumulated material — the same notes take on different characters depending
on the Color trajectory.

**Color as a trajectory target:**
```clojure
;; Color darkens through a 16-bar section, brightens for resolution
(over 16 (trajectory :smooth-step
  {:temporal-buffer/color [:warm :dark]}))
```

---

## 7. Halo — Smear with Threshold Feedback Injection

Halo adds temporal neighbors to each note event and, above a threshold, injects
those neighbors into the feedback path.

### 7.1 Below Threshold — Discrete Smear

At low Halo, each note event generates N±ε timing copies (slightly earlier and
later versions of the same note) that are mixed into the output without entering
the feedback chain. This is perceptible as temporal blur around each repeat —
individual notes remain distinguishable.

```clojure
{:halo
 {:amount    0.3          ; [0.0, 1.0]
  :copies    3            ; neighbor copies per note
  :spread    0.1          ; ± beats offset for copies
  :pitch-spread 5}}       ; ± cents offset for copies
```

### 7.2 Above Threshold — Smear Enters Feedback

At high Halo (above ~0.7), the temporal neighbors are injected into the feedback
path. They become inputs to the next feedback generation, each generating their
own neighbors, each subject to Color transformation. This is the qualitative
phase transition from discrete echoes to wash:

- Low Halo: discrete repeats with slight blur around edges
- High Halo: exponentially growing density as smear copies generate more smear
  copies; reverb-like wash emerges from the note event network

The threshold behavior is not gradual — it is a structural change in the signal
path. This models the Mimeophon's Halo behavior where "approaching maximum, the
smeared signal enters the feedback network" while "below maximum, Halo is kept
out of the feedback chain entirely."

**Halo × Color interaction:**
When smear copies enter feedback, they are subject to Color transformation on
each pass. Dark Color + high Halo = dark, dense wash; Bright Color + high Halo =
bright, granular cloud. The emergent texture is shaped by both parameters together.

---

## 8. Feedback

Feedback controls how many generations of note events accumulate. Combines with
Color (transform per pass) and Halo (smear injection above threshold):

```clojure
{:feedback
 {:amount       0.7        ; [0.0, 1.0]; approaches self-oscillation near 1.0
  :max-generation 8        ; stop feedback after N generations
  :velocity-floor 5}}      ; events below this velocity are dropped
```

**Self-oscillation:**
Above ~0.85 feedback, the buffer sustains indefinitely without external input —
events cycle through Color transformation and Halo smear, maintaining velocity
through the Color multiplier. The result is controlled: Color darkening prevents
explosive runaway; the feedback tail evolves in timbre without growing to
saturation. This is the "ghostly self-oscillation" quality of the Mimeophon —
usable as a continuous generative source.

**Generation-weighted Color:**
Color transforms can be applied with increasing effect per generation — a note
that has cycled 5 times is darker/brighter than one that has cycled once. This
creates natural timbral hierarchy in the feedback tail.

---

## 9. Hold Mode

Hold freezes the buffer input gate. No new note events enter. Existing events
continue to be read by cursors, transformed by Color, and smeared by Halo.

```clojure
(temporal-buffer-hold! :mimeophon true)
```

**In Hold mode, controls remap:**

In large zones (Zone 4–7), the Feedback knob shifts to control the playback
start point within the frozen window — Rate then determines the read length.
This enables improvised loop slicing: with a frozen 64-beat buffer, you can
select any 4-beat or 8-beat segment for continuous readback.

In Zone 0 (Karplus), Hold sustains the Karplus resonance indefinitely — the
resonating pitch continues until Hold is released or velocity decays.

**Hold + trajectory:**
```clojure
;; Freeze at the tension peak; sculpt the frozen material during resolution
(at-bar 28 (temporal-buffer-hold! :mimeophon true))
(over 8 (trajectory :smooth-step
  {:temporal-buffer/color [:neutral :dark]
   :temporal-buffer/halo  [0.3 0.7]}))
(at-bar 36 (temporal-buffer-hold! :mimeophon false))
```

---

## 10. Skew — Per-Cursor Rate Divergence

Skew diverges the playback rates of multiple cursors, creating independent
timing from a single Rate control:

```clojure
{:skew
 {:mode   :inverse   ; cursor A rate increases while cursor B rate decreases
  :amount 0.2}}      ; max divergence from nominal Rate
```

| Skew mode | Behavior |
|-----------|---------|
| `:inverse` | Cursors A and B diverge inversely — one faster, one slower |
| `:ping-pong` | Output alternates between cursors; cursors swap on each Repeat |
| `:swap` | Feedback paths cross — cursor A output feeds cursor B path |

Skew in note space creates polyrhythmic textures: two notes played simultaneously
return at different times and different pitches (via Doppler) depending on their
cursor's Rate. The temporal structure becomes stereo-like even in a mono context.

---

## 11. Flip — Retrograde Mode

Flip reverses the read direction of cursors within the current zone:

- **Large zones**: events are read in retrograde order — the last notes played
  become the first heard in the repeat. Melodic phrases reverse.
- **Zone 0**: "Total Protonic Reversal" — cycling through a very short buffer
  in reverse produces distortion-like timbral effects (the note-space equivalent
  of the Mimeophon's buffer-flip-every-70-samples behavior in Zone 0).

```clojure
(temporal-buffer-flip! :mimeophon)  ; toggle retrograde
```

Flip can be modulated — rapid flip toggling in large zones produces
back-and-forth phrase fragments; in Zone 0 it creates percussive artifacts.

---

## 12. Named Presets — Flux, Memory Field, Note Mimeophon

All prior cljseq temporal concepts are presets of the Temporal Buffer:

### Flux
```clojure
{:zones    [{:id :z0 :depth :configured}]
 :cursors  [{:mode :read :speed 1.0 :drift nil}]
 :color    {:mode :neutral :per-pass-transform nil}
 :halo     {:amount 0.0}
 :feedback {:amount 0.0}
 :hold     false
 :buffer   {:mode :pre-populated}}
```
Single zone, single cursor, no drift, no transform, no smear, no feedback.
Playback of a pre-populated sequence. Flux is the degenerate Temporal Buffer.

### Memory Field (Cosmos preset)
```clojure
{:zones    [{:id :z0 :depth 64}]   ; single configurable-depth zone
 :cursors  [{:mode :read :drift {:range 0.12 :mean-reversion 0.04}
              :pitch-drift {:mode :scale-gravity :strength 0.12}}
             {:mode :read :drift {:range 0.18 :mean-reversion 0.03}
              :pitch-drift {:mode :tape :tape-scale 80}}]
 :color    {:mode :neutral}
 :halo     {:amount 0.2 :spread 0.08}
 :feedback {:amount 0.65 :velocity-floor 8}
 :buffer   {:mode :input :decay {:mode :probabilistic :survival 0.97}}}
```
The Memory Field's drift, scale-gravity, and probabilistic decay live naturally
in the Temporal Buffer framework. Scale-gravity pitch drift becomes one Color-
adjacent transform. Probabilistic decay becomes a buffer management policy.

### Note Mimeophon
```clojure
{:zones    [{:id :z0 :depth 0.5}
             {:id :z1 :depth 2.0}
             {:id :z2 :depth 4.0}
             {:id :z3 :depth 8.0}
             {:id :z4 :depth 16.0}
             {:id :z5 :depth 32.0}
             {:id :z6 :depth 64.0}
             {:id :z7 :depth 128.0}]
 :active-zone :z3             ; current zone (switchable pitch-free)
 :cursors  [{:mode :read :speed 1.0 :tape-scale 100}
             {:mode :read :speed 1.0 :tape-scale 100 :skew true}]
 :color    {:mode :neutral}   ; adjustable warm→crisp
 :halo     {:amount 0.3 :feedback-threshold 0.7}
 :feedback {:amount 0.6}
 :hold     false
 :flip     false
 :buffer   {:mode :input :depth 128}}
```

---

## 13. The Generator / Transformer / Extractor / Buffer Family

cljseq now has four distinct event paradigms:

| Paradigm | Concept | Input | Output | Namespace |
|----------|---------|-------|--------|-----------|
| Generator | `deflive-loop`, stochastic, fractal | None / seed | Note events | various |
| Transformer | Temporal Buffer | Note events | Note events | `cljseq.temporal-buffer` |
| Extractor | Threshold Extractor | Continuous values | Note/control events | `cljseq.extractor` |
| Buffer | Temporal Buffer (self-oscillating) | None (after priming) | Note events | `cljseq.temporal-buffer` |

The Temporal Buffer unifies the Transformer and Buffer roles — at low feedback
it transforms input; at high feedback it sustains without input.

**Composition of paradigms:**

```
Generator → Temporal Buffer (transform) → Threshold Extractor (rhythm extraction)
                    ↑ feedback ──────────────────────────────────┘
```

A generator feeds the Temporal Buffer. The Buffer's accumulated pitch stream
feeds the Threshold Extractor. The Extractor's crossing events re-trigger the
Generator. A self-modifying, self-regulating compositional system from three
primitives.

---

## 14. Studio Integration

The Temporal Buffer operates at the note-event layer, complementing but not
replacing audio-domain hardware:

| Layer | Device | Zone concept | Color concept | Halo concept |
|-------|--------|-------------|---------------|--------------|
| Audio | Mimeophon | 8 overlapping zones | Spectral filter chain | Temporal smear |
| Audio/CV | VCVRack Mimeophon-patch | Configurable | Filter module | Diffusion |
| Note-event | `cljseq.temporal-buffer` | Beat-depth zones | Event transform fn | Timing neighbors |

cljseq Temporal Buffer → MIDI → Cascadia → CV → Eurorack → audio →
Mimeophon (audio-domain processing). The note-event and audio layers process
the same musical material at different abstraction levels simultaneously.

The Threshold Extractor can watch the Temporal Buffer's pitch output stream and
fire additional events when accumulated pitches cross scale-degree thresholds —
the note accumulation drives its own rhythm extraction.

---

## 15. Open Design Questions

1. **Zone switching crossfade:** How long should the crossfade be when switching
   zones? Instant (like Mimeophon's slew-less crossfade) or configurable?
   *Proposed: configurable; default one beat crossfade.*

2. **Multiple simultaneous active zones:** Can multiple zones be active at once,
   each feeding a different cursor? Or is exactly one zone active at a time?
   *Proposed: one active zone per cursor; different cursors can read from
   different zones simultaneously.*

3. **Buffer write rate during Hold:** In Hold mode, should the write head stop
   entirely, or continue writing (so that releasing Hold immediately introduces
   new accumulated material)?
   *Proposed: write head stops in Hold — releasing Hold returns to real-time
   input. Continuous writing in Hold would be a separate "overdub" mode.*

4. **Color as user-defined transform:** Should Color be a built-in spectrum
   (dark→bright presets) or an arbitrary Clojure function `(fn [event pass] ...)`?
   *Proposed: both; built-in presets for common cases, arbitrary fn for custom.*

5. **Halo copies as separate MIDI notes or pitch-bend offsets:** Halo neighbors
   that are close in pitch to the original — should they be separate Note On
   events (chords) or pitch-bend deviations on the same note?
   *Proposed: separate Note On events on adjacent MIDI channels to avoid
   pitch-bend collision; or configurable.*

6. **Namespace:** `cljseq.temporal-buffer`? `cljseq.buffer`? `cljseq.mimeophon`?
   *Proposed: `cljseq.temporal-buffer` — descriptive without tying to hardware.*

7. **Flux migration:** Same question as Memory Field — does `cljseq.flux` remain
   a separate namespace or become a thin wrapper over `cljseq.temporal-buffer`?
   *Proposed: keep flux API unchanged; implement as temporal-buffer preset
   internally. No breaking change for existing code.*

8. **Karplus microRate in Zone 0:** Does Zone 0 require special-casing in the
   implementation (exponential rate scaling) or can exponential scaling be a
   per-zone configuration?
   *Proposed: per-zone rate scaling mode — Zone 0 defaults to :exponential,
   other zones default to :linear. Configurable.*

---

## 16. Implementation Phases

### Phase 1 — Core buffer and zones
- `cljseq.temporal-buffer` namespace
- Single-zone buffer (Flux parity)
- Zone definition and switching (pitch-free)
- `deftemporal-buffer`, `temporal-buffer-send!`, `temporal-buffer-info!`
- Tests: zone switching, pitch-free zone change, cursor repositioning

### Phase 2 — Rate / Doppler
- Multi-cursor support
- Rate parameter with tape-Doppler behavior
- Clocked mode (pitch-free Rate with external clock)
- Zone 0 exponential microRate
- Skew (cursor rate divergence)
- Tests: Doppler pitch coupling, clocked mode isolation, Zone 0 exponential

### Phase 3 — Color and Feedback
- Color transform function (built-in spectrum + arbitrary fn)
- Feedback with generation tracking
- Generation-weighted Color
- Self-oscillation (high-feedback stability)
- Tests: Color per-pass transform, generation decay, self-oscillation convergence

### Phase 4 — Halo
- Temporal neighbor generation
- Halo threshold: below = output only; above = feedback injection
- Halo × Color interaction
- Tests: discrete smear below threshold, wash above threshold, Color interaction

### Phase 5 — Hold, Flip, Presets
- Hold mode (freeze input, remap controls in large zones)
- Flip (retrograde mode)
- Named presets: `:flux`, `:cosmos`, `:mimeophon`, `:looper`, `:tape-echo`
- Trajectory integration: all parameters as trajectory targets
- Tests: Hold freeze/sculpt/release, retrograde playback, preset instantiation

### Phase 6 — Extractor integration
- Temporal Buffer pitch stream as Threshold Extractor source
- Crossing events re-triggering generators
- Full generator→buffer→extractor→generator feedback loop

---

## 17. References

- **MakeNoise Mimeophon** — "Stereo Multi-Zone Color Audio Repeater"; 2019;
  16HP; coded by Tom Erbe (soundhack). The primary hardware inspiration for
  zones, Rate/Doppler, Color, Halo, Hold. Manual: makenoise-manuals.com/mimeophon/
- **Soma Laboratory Cosmos** — "Drifting Memory Station"; the inspiration for
  Memory Field presets; drifting heads, probabilistic decay, scale-gravity pitch drift
- **design-memory-field.md** — Memory Field design (2026-04-07); superseded by
  this document; Memory Field is now a named preset of Temporal Buffer
- **design-threshold-extractor.md** — Threshold Extractor design (2026-04-07);
  Extractor can consume Temporal Buffer pitch output as its source
- **cljseq.flux** — existing single-cursor buffer; becomes Flux preset
- **cljseq.phasor** — phasor values as Rate-equivalent sources in Zone 0
- **cljseq.trajectory** — curves as Color, Halo, Rate, and Zone trajectory targets
- **cljseq.scala / *tuning-ctx*** — scale-gravity pitch drift uses tuning for
  Halo neighbor pitch offsets and Color pitch bias in non-ET contexts
- **Intellijel Cascadia** — note-event output → CV → the audio-domain
  Mimeophon closes the loop between cljseq and hardware processing
