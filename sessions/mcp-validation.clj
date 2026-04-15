; SPDX-License-Identifier: EPL-2.0
;;
;; mcp-validation.clj — MCP bridge live validation
;;
;; PURPOSE:
;;   Verify that Claude Code can steer a live cljseq session via the MCP bridge:
;;     1. cljseq nREPL boots headless
;;     2. Claude connects via the "cljseq" MCP server (.mcp.json)
;;     3. Claude uses the `evaluate` tool to read session state and fire transitions
;;
;; HOW TO RUN THIS VALIDATION:
;;
;;   Step 1 — Boot the nREPL headless (in the worktree directory):
;;     export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
;;     nohup lein repl :headless :port 7888 > /tmp/cljseq-repl.log 2>&1 &
;;     tail -f /tmp/cljseq-repl.log   (wait for "nREPL server started on port 7888")
;;
;;   Step 2 — Open a Claude Code session in this worktree.
;;     Claude Code will auto-detect .mcp.json and offer to connect the "cljseq" server.
;;     Accept. The MCP server (lein mcp) boots and connects to port 7888.
;;
;;   Step 3 — In this REPL session, evaluate the forms below to seed the session state.
;;
;;   Step 4 — Ask Claude: "What is the current bar? What phase state are voices A and B in?
;;             Suggest a mutation intervention for the next structural event."
;;             Claude uses evaluate/get-loops/get-ctrl to answer from live state.
;;
;;   Step 5 — Ask Claude to steer: "Start the filter journey on voice A."
;;             Claude sends: evaluate("(begin-emergence!)")
;;             You should hear the filter open.
;;
;; The MCP bridge is validated when Claude can both READ (get-loops, get-ctrl) and
;; WRITE (evaluate) against the live session.

;; ============================================================
;; Boot a minimal two-voice session for MCP validation
;; (evaluate these forms manually, or paste into the REPL)
;; ============================================================

(require '[cljseq.journey :as journey]
         '[cljseq.berlin  :as berlin]
         '[cljseq.user    :refer [session! make-scale trajectory]])

(session! :bpm 100)

(def rubycon-scale (make-scale :D 2 :dorian))

(def ost-a
  (berlin/ostinato
    [{:pitch/midi 38 :dur/beats 2.5 :mod/velocity 80}
     {:pitch/midi 43 :dur/beats 2.5 :mod/velocity 72}
     {:pitch/midi 41 :dur/beats 2.5 :mod/velocity 68}
     {:pitch/midi 40 :dur/beats 2.5 :mod/velocity 75}]
    {:scale rubycon-scale :mutation-rate 0.08}))

(def ost-b
  (berlin/ostinato
    [{:pitch/midi 50 :dur/beats 4.0 :mod/velocity 65}
     {:pitch/midi 53 :dur/beats 5.0 :mod/velocity 58}
     {:pitch/midi 55 :dur/beats 5.0 :mod/velocity 62}]
    {:scale rubycon-scale :mutation-rate 0.06}))

(journey/start-bar-counter!)

(deflive-loop :voice-a {}
  (play! (berlin/next-step! ost-a))
  (sleep! 3))

(let [step-sleeps (atom (cycle [5 6 6]))]
  (deflive-loop :voice-b {}
    (play! (journey/humanise (berlin/next-step! ost-b)
                             {:timing-variance-ms 8 :velocity-variance 6}))
    (sleep! (first (swap! step-sleeps rest)))))

;; ============================================================
;; MCP VALIDATION EXPRESSIONS
;; Claude will evaluate these via the `evaluate` tool
;; ============================================================

;; Read session state:
(comment
  ;; Current bar (via MCP get-ctrl or evaluate):
  (journey/current-bar)

  ;; Voice state:
  (select-keys @ost-a [:pos :pass :mutation-rate :mode])
  (select-keys @ost-b [:pos :pass :mutation-rate :mode])

  ;; Phase calculation:
  (journey/phase-pair 12 17))

;; Steer the session (Claude evaluates these via the `evaluate` MCP tool):
(comment
  ;; Raise mutation on voice A:
  (berlin/thaw-ostinato! ost-a 0.20)

  ;; Start a filter journey (requires sidecar connected):
  (berlin/filter-journey! [:filter-a :cutoff] 74 1 5 90 32 :breathe)

  ;; Switch voice A to Phrygian dominant:
  (swap! ost-a assoc :scale (make-scale :D 2 :phrygian-dominant))

  ;; Check live loop status:
  (map #(vector % (get-loop-state %)) [:voice-a :voice-b]))
