; SPDX-License-Identifier: EPL-2.0
(defproject cljseq "0.1.0-SNAPSHOT"
  :description "cljseq — music-theory-aware Clojure sequencer targeting MIDI and OSC"
  :url "https://github.com/YOUR_ORG/cljseq"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]]
  :main ^:skip-aot cljseq.core
  :source-paths ["src"]
  :test-paths   ["test"]
  :resource-paths ["resources"]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies []}}
  :plugins [[lein-mcp "0.1.0-SNAPSHOT"]
            [lein-codox "0.10.8"]]
  :codox {:output-path "target/codox"
          :namespaces [cljseq.clock
                       cljseq.ctrl
                       cljseq.fractal
                       cljseq.loop
                       cljseq.m21
                       cljseq.marbles
                       cljseq.mod
                       cljseq.morph
                       cljseq.sidecar
                       cljseq.timing]
          :source-uri "https://github.com/YOUR_ORG/cljseq/blob/{version}/{filepath}#L{line}"})
