; SPDX-License-Identifier: EPL-2.0
(ns cljseq.fractal
  "cljseq fractal sequencer — self-similar branching sequence generation.

  Inspired by the Teenage Engineering Bloom sequencer (see doc/attribution.md).
  Generates sequences that exhibit self-similar (fractal) branching structure.

  Primary API:
    (deffractal name opts body)   — define a fractal sequence
    (fractal/freeze! name)        — pin current path; stop reactive regen
    (fractal/path name)           — query current active path

  Key design decisions: Q39–Q43 (fractal sequencer design),
  §24 Fractal Sequence Architecture (Bloom-Inspired).")
