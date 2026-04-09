# Design: Spectral Resynthesizer

*Inspired by MakeNoise Spectraphon (SAM/SAO/Array architecture) and Rossum Electro-Music Panharmonium (spectral lag, density, pitch window, harmonic memories).*

---

## Core Insight

The Temporal Buffer (design-temporal-buffer.md) operates on **event sequences** — it replays what happened, in order.

The Spectral Resynthesizer operates on a **harmonic probability distribution** — a weighted map of pitch-space that evolves continuously. It does not replay events; it maintains a living picture of *what is harmonically active* and generates new events from that picture.

These are orthogonal abstractions. You can have both.

---

## The Fundamental Abstraction

```
SpectralState = Map<PitchClass, Weight>   ; 0.0–1.0 per pitch position
```

The state is:
- **Updated** by incoming note events (analysis direction — SAM analog)
- **Decayed** continuously at a rate controlled by Blur
- **Frozen** on demand, storing a named harmonic snapshot
- **Used to generate** outgoing note events (synthesis direction — SAO analog)

The key parameter is **Blur** (from Panharmonium's spectral lag processor):
- `blur=0.0` — instant update; state reflects only notes currently sounding
- `blur=0.5` — slow decay; recent notes dominate, history fades gracefully
- `blur=1.0` — no decay; cumulative, historical harmonic gravity

Blur transforms the Spectral Resynthesizer from a present-moment analyzer into a kind of harmonic memory that rewards repetition and builds gravity around frequently visited pitches.

---

## Operating Modes

### SAM Mode — Spectral Analysis

Incoming note events update the internal SpectralState. No notes are generated.

```clojure
;; Incoming event:
{:pitch 64 :velocity 90 :duration 0.5}

;; SpectralState update:
(update-state state pitch (+ current-weight (* velocity 0.0079)))
;; ... then per-tick decay:
(update-all state #(* % (- 1.0 blur-coefficient)))
```

Slide (analog) = which harmonic series to use for pitch-class projection.  
In SAM, this sets the **fundamental** against which incoming pitches are measured as harmonic ratios.

Focus (analog) = the **harmonic aperture**: how many partials around the activated harmonic are "lit up" by a single input note. Wide Focus → neighboring harmonics gain weight. Narrow Focus → only the exact partial.

### SAO Mode — Spectral Synthesis

The module generates note events from the current SpectralState without requiring live input. It oscillates continuously.

Voice parameter (Panharmonium analog) controls **density**: how many pitch positions to voice simultaneously. Voice=1 picks the highest-weight partial only. Voice=8 voices the top-8. Voice=33 voices all active positions above a threshold.

Slide in SAO = scans through a stored **Harmonic Library** (see below), selecting which snapshot's weights to use for generation.

### Live Mode — Analysis + Synthesis Together

Input events continuously shape the SpectralState via SAM. The SAO synthesis reads from the same state simultaneously. Blur controls the disconnect between input and output:

- Low Blur: output closely mirrors input (near real-time harmonic following)
- High Blur: output lags behind input; as input harmony shifts, the output lingers on the ghost of what was, slowly migrating

This is the most musically interesting operating zone. A loop playing over a changing chord progression will have its spectral resynthesizer output still "stuck" in the previous harmony for a while before migrating — creating organic harmonic overlap rather than hard cuts.

---

## The Harmonic Library (Array System)

Inspired by Spectraphon's Arrays (up to 1024 spectra per Array, 16 Arrays per side).

A **Harmonic Library** is a named, ordered collection of SpectralState snapshots:

```clojure
{:name :piano-resonances
 :spectra [
   {:weights {:C 0.9 :E 0.7 :G 0.5}}      ; snapshot 0 — C major
   {:weights {:A 0.8 :C 0.6 :E 0.7}}      ; snapshot 1 — A minor
   {:weights {:F 0.9 :A 0.7 :C 0.5 :E 0.3}} ; snapshot 2 — F major 7
   ;; ... up to N snapshots
 ]}
```

Snapshots are captured via Freeze while in SAM/Live mode. The library accumulates the harmonic character of a session's events.

**Slide** in SAO scans through library snapshots:
- `slide=0.0` → snapshot 0
- `slide=0.5` → snapshot N/2
- `slide=1.0` → last snapshot

**Clock input** steps through snapshots rhythmically — creating a spectral arpeggio: the harmonic character shifts at each clock tick to the next stored state. This is one of the most distinctive creative uses: the resynthesizer generates notes, but the *character* of what it generates is itself sequenced by stepping through a library of frozen moments.

---

## Controls

| Control | Range | Description |
|---------|-------|-------------|
| Fundamental | any pitch | Root of the harmonic series |
| Mode | `:sam` `:sao` `:live` | Operating mode |
| Voice | 1–33 | How many partials to voice simultaneously |
| Blur | 0.0–1.0 | Spectral lag: how slowly harmonic weights decay |
| Focus | 0.0–1.0 | Harmonic aperture (narrow = exact partial; wide = ±N neighbors lit) |
| Slide | 0.0–1.0 | In SAO: selects position in Harmonic Library |
| Center | pitch | Center of analysis pitch window |
| Bandwidth | semitones or `:notch` | Width of analysis window; notch excludes center register |
| Feedback | 0.0–1.0 | Output events fed back into analysis; at 1.0, self-sustaining |
| Glide | 0.0–1.0 | Per-voice portamento as spectrum shifts |
| Freeze | bool | Hold current SpectralState |
| Density | `:odd` `:even` `:all` | Which harmonic positions to voice |

---

## Odd/Even Routing

Inspired by Spectraphon's separate Odd/Even harmonic outputs.

Odd harmonics of fundamental C: C (1st), G (3rd), E (5th), Bb (7th), D (9th), F# (11th)...
Even harmonics: octave doublings — C (2nd), G (6th), C (4th, 8th)...

In note-event space: Odd harmonics tend to be the harmonically "interesting" tones (fifths, thirds, sevenths); Even harmonics tend toward octave reinforcement.

```clojure
;; Route odd partials to lead voice, even to pad
(defspectral-resynthesizer piano-resonator
  ...
  :odd-target  :kalimba-voice
  :even-target :string-pad)
```

This allows a single spectral state to simultaneously drive two voices with complementary harmonic character — one carrying the melody-like partials, one carrying the foundational/octave partials.

---

## Blur and the cljseq.analyze Connection

Blur is immediately applicable to `cljseq.analyze` without building the full Resynthesizer.

Currently, `analyze` returns a point-in-time snapshot of harmonic activity. With Blur added as an option, the analyzer maintains a **running weighted pitch distribution** that decays at the configured rate:

```clojure
(analyze/start! {:blur 0.3 :fundamental :C :harmonic-series :just})

;; Returns a ref to the live SpectralState rather than point-in-time snapshots.
;; ImprovisationContext reads from this state directly.
```

This immediately improves the Ensemble Improvisation design — the ImprovisationContext's `:recent-pitches` and `:current-chord` fields become continuous harmonic gravity maps rather than discrete snapshots.

**Near-term action**: Add `:blur` parameter to `cljseq.analyze` before the full Spectral Resynthesizer is implemented.

---

## Pitch Window (Center + Bandwidth)

From Panharmonium's Center Freq + Bandwidth.

Analysis can be limited to a pitch register:

```clojure
:center :C4
:bandwidth 12  ; ±12 semitones = C3–C5 analyzed; outside ignored
```

Or inverted as a notch:

```clojure
:center :C4
:bandwidth {:notch 12}  ; events near C4 are EXCLUDED from analysis
```

The notch mode is musically interesting: if a lead melody is playing in the middle register, a resynthesizer with a notch centered there will analyze everything *except* the melody — building harmonic context from the accompaniment without contamination from the primary voice.

---

## Feedback at Unity

When Feedback reaches 1.0 (Panharmonium behavior), the resynthesized output becomes the analysis input. The system is self-sustaining — it generates notes from its current spectral state, those notes update the state, which generates notes...

The behavior is governed entirely by the current SpectralState + Blur:
- Low Blur + high Feedback: rapid convergence to a fixed point (drone on most-weighted partial)
- High Blur + high Feedback: slow evolution, the system "remembers" all past harmonics and cycles through them gradually
- With Focus shaping: Feedback + Focus creates recursive harmonic inflation — each cycle lights up more neighbors

This is the note-event analog of a self-oscillating filter, and like a self-oscillating filter, it can be used as a sustained generative texture source with no external input.

---

## Named Presets

Following Temporal Buffer's preset pattern:

| Preset Name | Voice | Blur | Focus | Mode | Character |
|-------------|-------|------|-------|------|-----------|
| `:harmonic-ghost` | 5 | 0.8 | 0.3 | `:live` | Slow-fading harmonic halo |
| `:drone-lock` | 1 | 0.95 | 0.1 | `:live` | Single dominant partial, very sticky |
| `:spectral-arp` | 3 | 0.2 | 0.5 | `:sao` | Clock-step through library; fast-updating |
| `:resonant-body` | 8 | 0.6 | 0.4 | `:live` | Dense harmonic response, medium lag |
| `:self-oscillator` | 4 | 0.7 | 0.3 | `:sao` | Feedback=0.8; self-sustaining drone |
| `:notch-accompanist` | 6 | 0.4 | 0.5 | `:live` | Bandwidth notch around lead melody register |

---

## Relationship to Other cljseq Abstractions

```
             Temporal Buffer               Spectral Resynthesizer
             ───────────────               ──────────────────────
Stores:      Event sequences               Harmonic weight distributions
Replays:     What happened, in order       What is harmonically gravitating
Key param:   Rate (speed of replay)        Blur (speed of harmonic decay)
Freeze:      Exact loop                    Frozen harmonic state
Feedback:    Halo enters feedback          Output feeds analysis input
Output:      Replayed note events          Generated note events from spectrum
```

```
             Threshold Extractor           Spectral Resynthesizer
             ───────────────────           ──────────────────────
Input:       Continuous values             Note events
Output:      Note events on crossing       Note events from harmonic state
Watches:     Voltage levels/phasors        Pitch weight distributions
```

**Integration path:**
1. Threshold Extractor fires events → feeds Spectral Resynthesizer analysis input
2. Temporal Buffer replays events → feeds Spectral Resynthesizer analysis input (re-analyzing the past)
3. Spectral Resynthesizer generates events → feeds Temporal Buffer (its output becomes history)
4. ImprovisationContext reads from live SpectralState for harmonic context
5. Ensemble behavior: each loop has its own Spectral Resynthesizer in SAM; a shared Resynthesizer in SAO synthesizes from the aggregate harmonic state

---

## Three Event Paradigms + One Spectral Paradigm

```
Generator     →  creates events from rules/sequences
Transformer   →  events in → transformed events out
Extractor     →  continuous values → events on threshold crossing
Resynthesizer →  events in → harmonic state → events out
```

The Resynthesizer is not purely any of the first three. It is a **stateful harmonic transformer**: input shapes state, state generates output, state persists between events. The Blur time constant determines whether it behaves more like a transformer (low Blur) or a generator (high Blur / SAO mode).

---

## Implementation Priority

**Near-term (pre–Temporal Buffer):**
- Add `:blur` weighted decay to `cljseq.analyze` SpectralState  
- Connect to ImprovisationContext as living harmonic gravity map

**Medium-term (after Temporal Buffer):**
- `cljseq.spectral` namespace: SpectralState type, SAM analysis loop, SAO synthesis loop
- `defspectral-resynthesizer` macro
- Harmonic Library: capture, store, recall named snapshots
- Odd/Even routing via `:odd-target` / `:even-target`

**Longer-term:**
- Clock-stepped Harmonic Library scanning (spectral arpeggio)
- Feedback path with convergence detection
- Full integration with Ensemble Improvisation shared harmonic state

---

## Motivating Use Case

The Shipwreck Piano corpus (see project memory) has pitches that cluster in non-obvious harmonic regions — it does not conform to standard Western scales. A Spectral Resynthesizer fed the Shipwreck Piano live loops would:

1. In SAM: build a SpectralState reflecting the actual pitch gravity of the piano (its idiosyncratic tuning clusters)
2. Freeze → store as a Harmonic Library entry
3. In SAO: generate accompaniment lines drawn from *those* pitch weights — naturally in tune with the piano's actual intonation rather than imposed equal temperament

This is the note-event analog of a resonator tuned to a physical object's resonant frequencies.
