; SPDX-License-Identifier: EPL-2.0

# Test Data Policy

## The problem

The MIDI repair pipeline was developed against a real recording — a TheoryBoard +
NDLR + SampleTank session (the "Shipwreck Piano" jam). That file is a personal
creative work and does not belong in the repository. At the same time, tests that
only run against synthetic data can miss edge cases that only emerge from real
material (dense note streams, unusual voice distributions, ambiguous harmony).

## The three-tier model

### Tier 1 — Synthetic fixtures (always committed)

Hand-crafted note sequences that exhibit exactly the property being tested.
Examples from `test/cljseq/midi_repair_test.clj`:

- `drone-notes` — two long low notes, exercises drone detection
- `pad-notes` — D Dorian chord progression with characteristic B♮, exercises mode
  detection and voice clustering
- `motif-1-with-outlier` — clean motif with one deliberate C# substitution,
  exercises outlier detection and correction

**Rules:**
- Each fixture is the minimum data needed to test one property
- Fixtures are commented to explain the musical intent (why B♮ not Bb, etc.)
- No personal recordings; all content is purpose-written for the test
- Committed and shipped with the library

### Tier 2 — Minimal derived excerpts (committed when necessary)

A distilled excerpt from a real recording — typically 4–16 bars — kept only when
the synthetic fixtures cannot reproduce a specific edge case. For example: if the
real recording revealed that voice separation fails at a particular note density
that no synthetic fixture exercises, a small anonymised slice may be committed.

**Rules:**
- Only add a derived fixture if Tier 1 cannot reproduce the edge case
- Strip metadata: no filename, session date, or identifying information
- Keep the excerpt to the minimum bars that trigger the behaviour
- Document in a comment what property it tests and why synthetic data was insufficient
- Committed and shipped

### Tier 3 — Local corpus and personal recordings (never committed)

Full recordings, private MIDI files, and local corpus downloads (e.g. the music21
Bach chorales). These live outside the repository and are used for exploratory
analysis and integration smoke tests run at-desk.

**Convention:**
- Personal recordings: `../../<name>.mid` / `../../<name>.edn` (adjacent to the
  repo checkout, outside version control)
- Local corpus: wherever the tool installed it (e.g. `~/.venv/music21/...`)
- Integration scripts that reference these paths are kept in `script/` with a
  comment noting the external dependency

**Never committed.** If a bug found via Tier 3 data needs a regression test,
distil a Tier 1 or Tier 2 fixture that reproduces the failure.

## Deriving a Tier 1 fixture from real data

The workflow for turning an observation on real data into a committed test:

1. Identify the edge case (e.g. "ambiguous notes cluster near motif activity")
2. Determine the minimum musical context: which voices, how many bars, what
   note durations and pitches reproduce the behaviour?
3. Write the fixture by hand using that musical description — do not copy note
   data verbatim from the recording
4. Add a comment explaining the musical reasoning
5. Verify the fixture triggers the same code path as the real data

This keeps tests deterministic, self-documenting, and license-clean, while
ensuring they reflect problems discovered in practice.
