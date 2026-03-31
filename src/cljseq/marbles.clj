; SPDX-License-Identifier: EPL-2.0
(ns cljseq.marbles
  "cljseq marbles — probabilistic random pattern generator.

  `marbles` instances maintain a ring buffer outside loop bodies (using
  `defonce`) and auto-register at /cljseq/marbles/<name>/.

  Key design decisions: Q34 (defonce ring buffer), Q38 (auto-register),
  Q37 (correlated channels, lerp perturbation).")
