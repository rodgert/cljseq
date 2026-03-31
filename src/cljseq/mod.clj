; SPDX-License-Identifier: EPL-2.0
(ns cljseq.mod
  "cljseq modulator namespace.

  LFOs, envelopes, random generators, and other ITemporalValue modulators.
  All modulators are pure; they do not mutate state.

  Key design decisions: Q44 (ITemporalValue protocol), Q30 (LFO rate),
  Q37 (correlated channels, lerp perturbation).")
