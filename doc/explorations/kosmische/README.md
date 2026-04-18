# Exploration: Kosmische

**Branch**: `explore/kosmische`
**Status**: In progress
**Style**: Berlin school — slow drone evolution, long arcs, s42 patch, Frippertronics-influenced tape delay texture

---

## Intent

An extended piece built entirely from cljseq's compositional stack:
drone voices via the s42 patch, ostinati layered over the top, a journey arc
governing harmonic evolution over tens of minutes. The exploration is also a
live test of the travel-rig setup — headless SC, Hydrasynth, mobile dev
workflow via SSH.

The musical intent: the kind of slowly evolving, harmonically patient music
associated with the Berlin school (early Tangerine Dream, Klaus Schulze,
Cluster). Long attack envelopes, gradual harmonic drift, repetition as a
compositional tool rather than a limitation.

---

## What this exploration exercises

- `cljseq.journey` — `start-journey!`, `phase-pair`, bar counter as
  compositional clock; section boundaries derived from bar position rather than
  wall-clock time
- `cljseq.berlin` — drone and texture namespaces ported from `explore/berlin-school`
- `cljseq.sc` — headless scsynth startup, sc-play!, sc-sync! reliability fixes
- s42 patch — four-voice SuperCollider synth (drone, VCO, papa, filter);
  stereo reverb workaround for SC 3.14 integer-arg bug
- `cljseq.conductor` — section-based arc management across the full piece
- Frippertronics arc — tape-delay-style feedback loop built from live-loops
  with overlapping sleep periods

---

## What was learned / design signals

- `ctrl/set!` broadcasting via WebSocket made it possible to observe harmonic
  context changes live in the browser control surface — confirmed the REST/WS
  architecture at realistic musical timescales
- The gap between `pattern`/`motif!` and `arp`/`next-step!` became visible when
  trying to drive arp chord selection from a journey-derived harmonic sequence —
  led to Q68 (IStepSequencer)
- Per-step timbral variation (brightness on the downbeat, slight filter opening
  on tension notes) was done by hand via conditional `ctrl/set!` inside loops —
  the pattern that led to Q69 (parameter locks)
- Long-form arc reveals that `conductor` sections need a way to crossfade rather
  than cut — currently transitions are abrupt; a future gesture type

---

## Session

See `session.clj` — runnable at the REPL. Requires:
- SC running (`sc-start!` or headless)
- `(core/start! :bpm 72)`
- s42 patch registered: `(sc/defpatch! :s42 ...)`

---

## Notes and variants

See `notes.md`.
