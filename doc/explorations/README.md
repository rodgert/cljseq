# cljseq — Explorations

Explorations are first-class artifacts that document compositional practice with
cljseq. They are distinct from the user manual (which documents the system) and
from tests (which verify correctness).

An exploration tells the story of *using* cljseq to make something — the musical
decisions, the sequencing layers in play, the harmonic context, what was tried and
why. Think composer's notebook, not API reference.

---

## Format

Each exploration lives in its own subdirectory:

```
doc/explorations/
  kosmische/          ← one exploration per directory
    README.md         ← narrative, intent, what was learned
    session.clj       ← runnable REPL session / literate namespace
    notes.md          ← scratchpad, dead ends, variant ideas
```

The `session.clj` should be runnable: evaluating it (or key sections of it) at
the REPL plays the music. This makes explorations living documents — not just
descriptions of what happened, but re-playable artefacts.

---

## Relationship to the codebase

- Explorations are **not** required to use stable APIs. They can use REPL-only
  patterns, in-progress features, or direct atom manipulation.
- When an exploration reveals a gap or a natural API surface, capture it in
  `design-decisions-open.md`.
- When an exploration exercises a feature well, the session code is a candidate
  for the user manual's example sections.
- When a feature branch is named after a musical exploration (e.g.
  `explore/kosmische`), the exploration document is the narrative companion to
  that branch's commit history.

---

## Index

| Exploration | Status | What it explores |
|---|---|---|
| [kosmische](kosmische/README.md) | In progress | Berlin-school drones, journey arcs, s42 + ostinati; seed of the explore/kosmische branch |
| [step-locks](step-locks/README.md) | Planned | IStepSequencer + per-note parameter locks (Q68/Q69); to be written as the implementation lands |
