; SPDX-License-Identifier: EPL-2.0
(ns cljseq.m21
  "cljseq Music21 integration.

  Wraps the Music21 Python sidecar subprocess. Calls are batched to amortize
  startup overhead (Q22). Music21 is used for musicological depth (corpus
  queries, score analysis); cljseq native code handles the hot path (Q23).

  The Music21 subprocess is the user's own installation; no corpus is bundled
  with cljseq (Q24). Additional corpus paths: `(m21/register-corpus-path! p)`.

  `m21/eventually!` is safe to call from inside a live-loop. Blocking calls
  that would stall the loop thread should use `eventually!` to defer to a
  background thread.

  Key design decisions: Q20 (m21/eventually!), Q22 (batch subprocess),
  Q23 (native hot-path / Music21 depth split), Q24 (no bundled corpus),
  Q27 (register-corpus-path!).")
