; SPDX-License-Identifier: EPL-2.0
(ns cljseq.clock
  "cljseq clock subsystem.

  Implements ITemporalValue for the master clock and clock-division sources.
  Drives the live-loop tick delivery pipeline.

  Key design decisions: Q44 (ITemporalValue protocol), Q1 (virtual time),
  Q2–Q3 (sync! semantics), Q35 (jitter injection point).")
