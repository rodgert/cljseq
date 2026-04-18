# Exploration: Step Locks

**Branch**: TBD (will follow Q68/Q69 implementation)
**Status**: Planned
**Style**: To be determined — the exploration should demonstrate parameter locks
musically, not just as a feature demonstration

---

## Intent

As `IStepSequencer` (Q68) and per-note parameter locks (Q69) are implemented,
this exploration will exercise them in a real compositional context. The goal
is not a demo patch but a piece where parameter locks are doing genuine musical
work — the kind of thing that would be tedious or impossible to express without
them.

Candidate ideas:
- A bass line where filter cutoff is locked to specific steps, giving a
  characteristic rhythmic "wah" independent of the note pitch
- A melodic phrase where certain steps are microtonally inflected (17 cents
  flat for a blues note, 14 cents sharp for a leading tone) using
  `:pitch/microtone` on Hydrasynth or Iridium
- A chord sequence where the Iridium wavetable position is locked per step,
  making each chord a different timbre while the harmony stays consistent

---

## What this should validate

- `IStepSequencer` protocol implemented for both Pattern+Rhythm and ArpState
- Phrase steps with extra keys forwarded into the note map
- Chord arp `:params` vector merged correctly
- `Locks` record cycling independently of Pattern and Rhythm (three-way lcm)
- `core/play!` routing `:mod/*` keys through ctrl bindings to MIDI CC / NRPN
- `:lock/restore` behaviour on a synth that requires it

---

## Session

`session.clj` to be written as the implementation lands.
