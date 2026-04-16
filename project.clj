; SPDX-License-Identifier: EPL-2.0
(defproject cljseq "0.12.0"
  :description "cljseq — music-theory-aware Clojure sequencer targeting MIDI and OSC"
  :url "https://github.com/rodgert/cljseq"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [org.clojure/data.json "2.5.1"]]
  :main ^:skip-aot cljseq.core
  :source-paths ["src"]
  :test-paths   ["test"]
  :resource-paths ["resources"]
  :target-path "target/%s"
  :repl-options {:timeout 120000}
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies []}}
  :aliases {"mcp" ["run" "-m" "cljseq.mcp"]}
  :plugins [[lein-codox "0.10.8"]]
  :codox {:output-path "target/codox"
          :namespaces [cljseq.clock
                       cljseq.ctrl
                       cljseq.device
                       cljseq.dsl
                       cljseq.flux
                       cljseq.fractal
                       cljseq.loop
                       cljseq.m21
                       cljseq.stochastic
                       cljseq.mod
                       cljseq.morph
                       cljseq.phasor
                       cljseq.random
                       cljseq.sidecar
                       cljseq.timing]
          :source-uri "https://github.com/YOUR_ORG/cljseq/blob/{version}/{filepath}#L{line}"})
