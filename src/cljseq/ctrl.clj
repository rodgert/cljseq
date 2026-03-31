; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ctrl
  "cljseq control tree.

  The control tree is a Clojure persistent map held in a single system-state atom:

    {:tree        {}   ; the control tree
     :serial      0    ; structural change counter
     :undo-stack  []   ; bounded ring; default depth 50
     :checkpoints {}}  ; named snapshots for panic/revert

  Primary API:
    (ctrl/set!  path value)   — logical write (atom only)
    (ctrl/send! path value)   — physical write (atom + sidecar dispatch)
    (ctrl/get   path)         — read current value
    (ctrl/bind! path source)  — bind a physical control source
    (undo!)                   — revert last structural change (beat-aware)
    (undo! :now)              — revert immediately
    (checkpoint! name)        — save named snapshot
    (panic!)                  — jump to most recent checkpoint

  Key design decisions: Q4 (ctrl/set! vs ctrl/send!), Q8 (node types),
  Q9 (conflict resolution), Q10 (write directionality), Q47 (persistent atom),
  Q48 (patch system).")
