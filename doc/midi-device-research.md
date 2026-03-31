# MIDI Device Research

**Sprint**: Sprint 4
**Status**: Initial survey — verify CC numbers against official implementation charts before production use

---

## Purpose

Survey representative MIDI devices across categories to:
1. Understand the diversity of MIDI implementation chart structures
2. Prototype the `defdevice` extraction pipeline (chart → EDN device map)
3. Identify edge cases the EDN format must handle

EDN device maps live in `resources/devices/` (directory to be created when
implementation begins).

---

## Devices Surveyed

| Device | Manufacturer | Category | Confidence |
|--------|-------------|----------|------------|
| Korg Minilogue XD | Korg | Analog polysynth | Medium-High |
| Moog Subsequent 37 | Moog Music | Analog monosynth | Medium-High |
| Roland TR-8S | Roland | Drum machine | Medium |
| Elektron Digitakt | Elektron | Drum machine / sampler | Medium-High |
| Expert Sleepers FH-2 | Expert Sleepers | Eurorack MIDI-to-CV | Low-Medium |
| Arturia KeyStep (original) | Arturia | MIDI/CV controller | Medium-High |

**Note**: CC assignments are sourced from training knowledge and should be
cross-checked against manufacturer-published MIDI implementation PDFs before
use in production `defdevice` maps.

---

## Device Category Archetypes

### Archetype A: Dense Parameter Synth

**Examples**: Korg Minilogue XD, Moog Subsequent 37

Characteristics:
- 50–100+ CC assignments, most fixed by the manufacturer
- Parameters map cleanly to front-panel knobs and switches
- NRPN for extended resolution on continuous parameters
- Program change for patch recall
- SysEx for patch dump/restore

The dense parameter synth is the primary target for `defdevice` — a full
CC map enables the control tree to address every synthesis parameter by
path (`[:filter :cutoff]`, `[:vco1 :wave]`) rather than raw CC number.

### Archetype B: Drum Machine

**Examples**: Roland TR-8S, Elektron Digitakt

Characteristics:
- Note numbers map to drum voices (kit map); note map is often user-reconfigurable
- CCs per-instrument when voices are assigned to individual MIDI channels
- Program change for pattern/kit recall
- MIDI clock master or slave
- The Elektron Digitakt MIDI tracks have **user-assignable CC slots** (CC A–H),
  not a fixed map — this is an important `defdevice` edge case (see §Edge Cases)

### Archetype C: MIDI/CV Converter

**Examples**: Expert Sleepers FH-2

Characteristics:
- **No fixed CC map** — all MIDI routing is user-configured in editor software
- Device is essentially a MIDI router: any CC can be mapped to any CV/gate output
- `defdevice` for a converter describes the available outputs and their types
  (V/oct pitch, gate, trigger, clock-div CV, etc.) rather than a fixed CC list
- The user's FH-2 configuration determines which CCs to target from cljseq

This is the most challenging archetype for `defdevice`. The right model is a
device template with configurable CC→output bindings, filled in per-user-setup.

### Archetype D: Controller (Send-Only)

**Examples**: Arturia KeyStep (original)

Characteristics:
- Sends notes, velocity, clock, and a limited set of CCs
- Does **not receive** meaningful MIDI (no onboard sounds or parameters to control)
- DIN MIDI output only (no input) in the case of the KeyStep
- Touch strip is the primary CC source (assignable via MIDI Control Center)
- `defdevice` for a controller describes what it *emits*, not what it receives
  — used to configure cljseq's `ctrl/bind!` source mappings

---

## Key Design Findings for `defdevice`

### 1. Fixed vs. Configurable CC maps

Most synthesizers have a fixed, manufacturer-defined CC map. Drum machines and
controllers often have partially or fully user-assignable CC slots. The EDN format
must distinguish:
- `:fixed` — CC number is defined by the manufacturer, always the same
- `:configurable` — CC number is user-assigned (e.g. Digitakt CC A–H slots)
- `:undefined` — device receives the CC but has no assigned function

### 2. Discrete vs. Continuous Parameters

Many CC parameters are continuous (0–127 maps to a parameter range). Others are
discrete (e.g. Minilogue XD VCO wave: 0=triangle, 43=saw, 86=pulse). The EDN
format should capture `:values` breakpoints for discrete parameters to enable
semantic addressing (`(ctrl/set! [:vco1 :wave] :saw)` rather than `43`).

### 3. Bipolar Parameters

Parameters with a zero-point at CC 64 (e.g. Filter EG Intensity: -63 to +63)
need `:bipolar true` in the device map so the control tree can express them
as a signed range.

### 4. NRPN

High-end devices (Moog Subsequent 37, Korg Minilogue XD) use NRPN for
parameters requiring resolution beyond 7-bit CC. NRPN uses two CCs (99/98 for
MSB/LSB parameter select, 6/38 for data entry). The EDN format should support
`:nrpn` parameter entries alongside `:cc` entries.

### 5. Note Maps (Drum Machines)

Drum machines map MIDI notes to drum voices rather than pitch. The `:note-map`
section captures this. Notes are often user-reconfigurable; the default map
(usually General MIDI positions) should be captured as the baseline.

### 6. Multi-Channel Devices

The Elektron Digitakt in multi-channel mode assigns each MIDI track to its own
channel. The FH-2 in polyphonic mode uses N channels for N voices. The device
map should express this with a `:channels` configuration block.

### 7. Controllers Emit, Not Receive

Controllers need a `:role :controller` designation and a `:transmits` block
rather than the usual `:receives` CC map. cljseq uses `:transmits` to configure
`ctrl/bind!` source mappings for incoming controller data.

---

## Edge Cases

### Elektron Digitakt — User-Assigned CC Slots

The Digitakt MIDI tracks expose 8 assignable CC slots (CC A through CC H) per
track, visualized on the CTRL 1 and CTRL 2 pages. The user assigns any CC number
to each slot via the Digitakt interface; the step sequencer then p-locks values
for those CCs per step.

This means a `defdevice` for the Digitakt as a *sequencer* (driving external
gear) is different from a `defdevice` for the Digitakt as a *target* (being
driven by cljseq). The EDN format handles this with:
- `:role :sequencer` — Digitakt drives external gear; describe its CC slot model
- `:role :target` — cljseq drives the Digitakt's audio engine; fixed CC map for the sampler

### Expert Sleepers FH-2 — Configuration-Dependent Map

The FH-2's CC-to-output routing is defined in the FH-2 editor, not by the
manufacturer. A `defdevice` for the FH-2 is therefore a *user-specific template*
capturing the user's current routing configuration. The EDN format uses
`:configurable-by :fh2-editor` to flag this.

cljseq's `defdevice` for FH-2 would look like:

```clojure
(defdevice :fh2-eurorack
  ;; This map reflects a specific FH-2 editor configuration.
  ;; CV outputs 1-8 are assigned by the user in the FH-2 editor.
  {:outputs
   {:cv-pitch-1   {:midi :cc  :number 20 :type :pitch-v/oct :range [-5 5]}
    :cv-pitch-2   {:midi :cc  :number 21 :type :pitch-v/oct :range [-5 5]}
    :gate-1       {:midi :note :trigger-note 60 :type :gate}
    :cv-lfo-rate  {:midi :cc  :number 24 :type :unipolar}}})
```

### Moog Subsequent 37 — DIN Only

The Subsequent 37 has no USB MIDI. cljseq's `:connectivity :din-only` flag
ensures the sidecar always routes through a DIN MIDI interface rather than
attempting USB MIDI direct connection.

---

## `defdevice` Extraction Pipeline (Prototype)

The pipeline from MIDI implementation chart to usable EDN map has three stages:

### Stage 1: Transcription

Source the MIDI implementation chart (PDF or HTML from manufacturer website).
Transcribe CC assignments into the EDN template. Flag confidence per entry.

### Stage 2: Semantic Annotation

For each CC:
- Assign a `:path` vector (control-tree-friendly semantic name)
- Categorize the parameter (`:vco`, `:filter`, `:env`, `:lfo`, `:fx`, `:global`)
- Annotate discrete parameters with `:values` breakpoints
- Mark bipolar parameters
- Note NRPN alternatives where available

### Stage 3: Validation

```clojure
;; Proposed validation fn (not yet implemented)
(device/validate! my-device-map)
;; => {:errors [] :warnings [{:cc 38 :msg "Range annotation missing"}]}
```

---

## Prototype EDN Maps

See:
- `resources/devices/korg-minilogue-xd.edn` — full example (dense parameter synth)
- `resources/devices/arturia-keystep.edn` — controller example (send-only)

---

## Recommendations for Implementation Sprint

1. Start `defdevice` with Archetype A (dense parameter synth) — it exercises the
   most format features and is the highest-value target for `ctrl/bind!`.
2. Add NRPN support in the second pass — it is present on several high-quality
   instruments and meaningfully improves parameter resolution.
3. The FH-2 / user-configurable archetype should wait until the core device model
   is stable — it requires a "template + user configuration" layer on top of the
   base `defdevice` format.
4. For drum machines, the note map is higher priority than the per-instrument CC
   map — most drum machine control happens via note triggers, not CC.
5. Consider a `defdevice-from-chart` macro that reads an EDN file at load time,
   registers the device in the control tree, and creates the appropriate
   `ctrl/bind!` source mappings automatically.
