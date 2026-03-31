; SPDX-License-Identifier: EPL-2.0
(ns cljseq.loop
  "cljseq live-loop subsystem.

  `deflive-loop` defines a named, repeating loop registered in the control
  tree at /cljseq/loops/<name>. `live-loop` is a macro alias for `deflive-loop`
  with an auto-generated name.

  Loop execution uses virtual time (`*virtual-time*`) for scheduling.
  `sleep!` advances virtual time. `sync!` aligns to a beat boundary within
  the sync window.

  Key design decisions: Q11 (live-loop = deflive-loop alias), Q1 (virtual time),
  Q2 (sync! semantics), Q3 (sync-window), Q29 (step vs continuous modes).")
