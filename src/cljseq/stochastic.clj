; SPDX-License-Identifier: EPL-2.0
(ns cljseq.stochastic
  "cljseq stochastic generative sequencer.

  Inspired by Mutable Instruments' Marbles Eurorack module (see doc/attribution.md).
  Provides non-deterministic, probabilistic pattern generation with controllable
  bias — the 'when' (T section / rhythm) and 'what' (X section / pitch) are
  independently configurable.

  `defstochastic` defines a named stochastic context and registers it in the
  control tree at /cljseq/stochastic/<name>/. All parameters are live-addressable
  via MIDI CC, OSC, or ctrl/set!.

  `stochastic-loops` drives N live-loops from a single stochastic context.

  Key design decisions: Q34 (defonce ring buffer), Q37 (correlated channels,
  lerp perturbation), Q38 (auto-register stochastic context).")
