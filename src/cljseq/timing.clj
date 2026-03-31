; SPDX-License-Identifier: EPL-2.0
(ns cljseq.timing
  "cljseq timing modulator namespace.

  Swing, humanize, groove templates, quantize, and input-quantize are all
  ITemporalValue instances that return Long (nanoseconds offset).

  Bound to the time_ns pipeline via `timing/bind!`. Applied after
  Link timeAtBeat; multiple modulators compose additively.

  Key design decisions: Q46 (timing modulators as ITemporalValue).")
