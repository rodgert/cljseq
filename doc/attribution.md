# cljseq Attribution

This document records all third-party hardware, software, open-source projects,
and conceptual works that have influenced or are referenced in the cljseq design.

---

## Hardware Sequencers and Instruments

### Teenage Engineering Pocket Operator series
Pocket Operator devices (PO-14 Sub, PO-16 Factory, etc.) are products of
Teenage Engineering AB. Referenced in cljseq design as examples of compact,
expressive performance devices.

### Teenage Engineering Bloom Sequencer
The Bloom step sequencer is a product of Teenage Engineering AB. The cljseq
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
