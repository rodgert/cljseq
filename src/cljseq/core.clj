; SPDX-License-Identifier: EPL-2.0
(ns cljseq.core
  "cljseq — music-theory-aware sequencer for Clojure.

  Entry point and system lifecycle. Start the system with `(start!)`,
  stop it with `(stop!)`. All subsystems are initialized here."
  (:gen-class))

(defn -main
  [& _args]
  (println "cljseq — use at a Clojure nREPL"))
