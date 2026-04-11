# cljseq Attribution

This document records all third-party hardware, software, open-source projects,
and conceptual works that have influenced or are referenced in the cljseq design.

---

## Naming Convention for Inspired Abstractions

When a cljseq abstraction is directly inspired by a commercial hardware product,
the following convention applies:

- The **cljseq API name** is a generic descriptive term that accurately describes
  the abstraction's behaviour without embedding a trademarked product name.
- The **R&R section subtitle** retains "(X-Inspired)" as explicit attribution to
  the original design.
- The **attribution.md entry** (this file) records the full product name, its
  owner, and which cljseq abstraction draws from it.

| Hardware product              | Owner                    | cljseq abstraction           |
|-------------------------------|--------------------------|------------------------------|
| Bloom step sequencer          | Qu-Bit Electronix        | `deffractal` / `cljseq.fractal` |
| Marbles random sampler        | Mutable Instruments      | `defstochastic` / `cljseq.stochastic` |
| Labyrinth sequencer           | Moog Music Inc.          | `defflux` / `cljseq.flux`    |
| Mimeophon audio repeater      | MakeNoise                | `deftemporal-buffer` / `cljseq.temporal-buffer` |
| Cosmos drifting memory station | Soma Laboratory          | `deftemporal-buffer` / `cljseq.temporal-buffer` |
| Spectraphon spectral processor | MakeNoise                | `defspectral-resynthesizer` / `cljseq.spectral` |
| Panharmonium spectral oscillator | Rossum Electro-Music   | `defspectral-resynthesizer` / `cljseq.spectral` |
| GTE (Gestural Time Extractor)  | MakeNoise                | `defthreshold-extractor` / `cljseq.extractor`   |
| Solar 42 multi-voice drone synthesizer | Soma Laboratory          | `defpatch! :s42` / `cljseq.patch`               |
| Leviasynth 8-op FM synthesizer | ASM (Audiophile Synthesizer Machines) | `compile-fm :8op-cc` / `cljseq.fm` |
| Digitone 4-op FM synthesizer   | Elektron                 | `compile-fm :4op-cc` / `cljseq.fm`              |

New entries should be added to this table whenever a hardware-inspired abstraction
is named.

---

## Hardware Sequencers and Instruments

### Teenage Engineering Pocket Operator series
Pocket Operator devices (PO-14 Sub, PO-16 Factory, etc.) are products of
Teenage Engineering AB. Referenced in cljseq design as examples of compact,
expressive performance devices.

### Qu-Bit Electronix Bloom
The Bloom (v2) step sequencer is a product of Qu-Bit Electronix. The cljseq
`cljseq.fractal` namespace and `deffractal` abstraction are directly inspired
by Bloom's self-similar branching sequence model. The design document §24
retains the subtitle "Bloom-Inspired" as explicit attribution. The name
"fractal" was chosen for the cljseq API to avoid trademark conflict.

### Arturia KeyStep Pro
The KeyStep Pro is a product of Arturia. Referenced in cljseq design for its
track types (melodic, drum, CV/gate, chord), per-step probability, swing, and
pattern chaining capabilities. Identified as an exemplar for `defdevice`
design.

### Arturia BeatStep Pro
The BeatStep Pro is a product of Arturia. Referenced in cljseq design for its
melodic vs. drum track distinction, CV/gate model, and polyrhythm support.
Identified as an exemplar for `defdevice` design.

### Mutable Instruments Marbles
Marbles is an open-source Eurorack random sampler module by Mutable Instruments
(Émilie Gillet). The hardware and firmware source are MIT-licensed. The cljseq
`cljseq.stochastic` namespace and `defstochastic` abstraction are directly
inspired by Marbles' T/X section model, DEJA VU ring buffer, spread/bias
continuum, and correlated multi-channel generation. The design document §23
retains the subtitle "Marbles-Inspired" as explicit attribution. The name
"stochastic" was chosen for the cljseq API to avoid trademark conflict.

### Moog Labyrinth
The Labyrinth is a sequencer module produced by Moog Music Inc. (2024). The
cljseq `cljseq.flux` namespace and `defflux` abstraction are directly inspired
by the Labyrinth's play head / write head separation model, CORRUPT bit-level
mutation, and BIT FLIP side-channel trigger. The design document §26 retains the
subtitle "Labyrinth-Inspired" as explicit attribution. The name "flux" was chosen
for the cljseq API to avoid trademark conflict.

### MakeNoise Mimeophon
The Mimeophon ("Stereo Multi-Zone Color Audio Repeater", 2019) is a product of
MakeNoise. The cljseq `cljseq.temporal-buffer` namespace and `deftemporal-buffer`
abstraction are directly inspired by the Mimeophon's eight overlapping zone
topology, Rate/tape-Doppler coupling, Color per-pass event transform, Halo smear
with threshold-based feedback injection, Hold mode, and Flip (retrograde) mode.
The name "temporal-buffer" was chosen for the cljseq API to avoid trademark conflict.
The R&R section covering this abstraction retains "(Mimeophon-Inspired)" as
explicit attribution.

### Soma Laboratory Solar 42
The Solar 42 is a product of Soma Laboratory. The cljseq `defpatch! :s42` patch
definition in `cljseq.patch` is directly inspired by the Solar 42's multi-voice
architecture: independent drone oscillator voices each with per-oscillator gate
and tuning ratio controls, VCO voices with PWM and sub-oscillator, FM+AM+noise
voices (Papa Srapa section), and a dual 12 dB/oct resonant filter stage. The
voice synths (`:s42-drone-voice`, `:s42-vco-voice`, `:s42-papa-voice`) and filter
(`:s42-filter`) are structurally inspired by this topology; they are not a
licensed reproduction of any specific circuit. The name `:s42` was chosen to
avoid trademark conflict; the design notes retain "Solar42-inspired" as explicit
attribution.

### Soma Laboratory Cosmos
The Cosmos ("Drifting Memory Station") is a product of Soma Laboratory. The
Memory Field preset within `cljseq.temporal-buffer` is directly inspired by the
Cosmos' multiple drifting playback heads, probabilistic event decay, and
scale-gravity pitch drift model. Referenced in `design-memory-field.md` (now
superseded) and `design-temporal-buffer.md` §12.

### ASM Leviasynth
The Leviasynth is a product of ASM (Audiophile Synthesizer Machines). The cljseq
`compile-fm :8op-cc` backend in `cljseq.fm` is directly inspired by the
Leviasynth's 8-oscillator architecture, its per-OSC MIDI CC addressing scheme
(level CCs 24–31, pitch-detune CCs 33–41 with the gap at CC 38, feedback CCs
42–49), and its approach of using CC center-value 64 to represent zero pitch
detune.  The algorithm keyword `:8op-cc` was chosen to avoid trademark conflict;
the R&R design notes retain "Leviasynth-inspired" as explicit attribution.

### Elektron Digitone
The Digitone is a product of Elektron. The cljseq `compile-fm :4op-cc` backend
in `cljseq.fm` is directly inspired by the Digitone's 8-algorithm topology
table (4 operators arranged in fixed carrier/modulator roles with CC-addressable
ratios, levels, and envelopes via CCs 16, 19, 20, 75–82, 90–92) and its A/B
operator naming convention for modulator groups.  The algorithm keyword `:4op-cc`
was chosen to avoid trademark conflict; the R&R design notes retain
"Digitone-inspired" as explicit attribution.

### MakeNoise GTE (Gestural Time Extractor)
The GTE is a product of MakeNoise (released 2026-03-26). The planned
`cljseq.extractor` namespace and `defthreshold-extractor` abstraction are directly
inspired by the GTE's threshold-based gate extraction from continuous CV signals —
watching a voltage source and firing a gate each time the signal crosses a
configurable threshold band (Space parameter) with configurable crossing direction.
The name "extractor" was chosen for the cljseq API to avoid trademark conflict.

### MakeNoise Spectraphon
The Spectraphon is a product of MakeNoise. The planned `cljseq.spectral` namespace
and `defspectral-resynthesizer` abstraction are directly inspired by the
Spectraphon's SAM (Spectral Analysis Mode) / SAO (Spectral Array Oscillator) dual
architecture, its Array system for storing and recalling up to 1024 named spectral
snapshots, and its separate Odd/Even harmonic output routing. The name "spectral"
was chosen for the cljseq API to avoid trademark conflict.

### Rossum Electro-Music Panharmonium
The Panharmonium is a product of Rossum Electro-Music. The planned
`cljseq.spectral` namespace and `defspectral-resynthesizer` abstraction are
directly inspired by the Panharmonium's spectral lag processor (Blur parameter),
Voice density control, Center/Bandwidth pitch window, and self-sustaining feedback
behavior at unity gain. The name "spectral" was chosen for the cljseq API to avoid
trademark conflict.

### Conductive Labs NDLR
The NDLR (Next Dimensional Loop Rider) is a product of Conductive Labs.
Referenced in cljseq design for its ensemble/voice model (Pad, Motif, Arp),
strum articulation, and harmonic context architecture. The `defensemble`
design in cljseq is informed by the NDLR's role model.

### Expert Sleepers ES-9 / CLAP Integration
Expert Sleepers (https://www.expert-sleepers.co.uk/) produces the ES-9 and
related Eurorack modules for DC-coupled audio and CV. Referenced as a target
platform for direct Eurorack CV via the CLAP plugin interface.

---

## Software Projects

### Ableton Link
Ableton Link (https://github.com/Ableton/link) is an open-source sync
technology by Ableton AG, licensed GPL-2.0-or-later. cljseq optionally links
against Ableton Link for beat-clock synchronization. See `doc/licensing.md`
for the GPL interaction analysis.

### SuperCollider
SuperCollider (https://supercollider.github.io/) is an open-source audio
synthesis platform. The `ITemporalValue` protocol design in cljseq is
informed by SuperCollider's UGen/signal abstraction. Referenced as a primary
OSC backend target.

### VCV Rack
VCV Rack (https://vcvrack.com/) is an open-source modular synthesizer
platform by Andrew Belt. The `ITemporalValue` protocol design in cljseq is
directly informed by VCV Rack's CV signal abstraction (signals as
continuously-valued streams sampled at a rate). Referenced as a CV/gate
output backend target.

### Ableton Live
Ableton Live is a product of Ableton AG. Referenced as a DAW MIDI backend
target (`:daw-midi` in sched-ahead-time configuration).

### Music21
Music21 (https://web.mit.edu/music21/) is an open-source Python toolkit for
computational musicology developed at MIT, licensed BSD. cljseq uses Music21
as an optional backend for musicological analysis, score generation, and
corpus queries. The `python/cljseq_m21/` sidecar wraps the Music21 API.

### Overtone
Overtone (https://overtone.github.io/) is an open-source SuperCollider client
for Clojure. Referenced in cljseq DSL usability analysis as a comparable
live coding environment in the Clojure ecosystem.

### Sonic Pi
Sonic Pi (https://sonic-pi.net/) is an open-source live coding music
synthesizer by Sam Aaron, licensed MIT. Referenced in cljseq DSL usability
analysis as a primary comparable for sequencing and live coding syntax.

### TidalCycles
TidalCycles (https://tidalcycles.org/) is an open-source live coding
environment for pattern music by Alex McLean. Referenced in cljseq DSL
usability analysis for its pattern mini-notation and cycle-based sequencing
model.

### Clojure
Clojure (https://clojure.org/) is an open-source Lisp dialect for the JVM
by Rich Hickey, licensed EPL-1.0. cljseq is implemented in Clojure.

### nREPL
nREPL (https://nrepl.org/) is an open-source Clojure network REPL, licensed
EPL-1.0. cljseq uses standard nREPL for interactive development.

---

## Standards and Protocols

### MIDI 1.0 and MIDI 2.0
MIDI specifications are published by the MIDI Manufacturers Association (MMA)
and the MIDI Association. cljseq implements MIDI 1.0 output as a primary
target; MIDI 2.0 is a future consideration.

### OSC (Open Sound Control)
OSC is a communication protocol for musical instruments developed at CNMAT,
UC Berkeley. cljseq uses OSC as both a primary control plane and a data plane
for SuperCollider and compatible targets.

### Scala (.scl / .kbm) Tuning Format
The Scala scale format is an open standard for representing microtonal tuning
systems. cljseq uses `.scl` and `.kbm` files for scale and keyboard mapping
definition (Q26).

### CLAP (CLever Audio Plugin)
CLAP (https://github.com/free-audio/clap) is an open-source audio plugin API.
cljseq targets CLAP for direct Eurorack CV output via the `cljseq-audio`
sidecar binary.

---

## Musical Traditions

### Gamelan
Gamelan is a musical ensemble tradition from Indonesia (principally Java and
Bali). Referenced in cljseq design for its colotomic (stratified rhythmic
structure) organization model, irama (tempo levels), and use of non-12-TET
tuning systems. Design decisions Q26–Q28 address gamelan support explicitly.
