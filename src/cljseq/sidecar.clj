; SPDX-License-Identifier: EPL-2.0
(ns cljseq.sidecar
  "cljseq sidecar IPC.

  Manages the connection to the cljseq-sidecar binary (MIDI/CV) and
  cljseq-audio binary (CLAP/audio). Uses direct JVM-to-process IPC (Q16).
  Routing key in this namespace's dispatch map.

  `ctrl/send!` calls route through here to deliver physical MIDI CC /
  OSC messages to bound hardware and software targets.

  Key design decisions: Q16 (process topology, Option A direct IPC).")
