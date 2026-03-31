; SPDX-License-Identifier: EPL-2.0
(ns cljseq.flux
  "cljseq flux sequence — circular step buffer with independent read and write heads.

  Inspired by the Moog Labyrinth Eurorack sequencer (see doc/attribution.md).
  A `defflux` sequence has two independent processes operating on a shared
  circular step buffer:

    - Read head:  advances at its clock rate, emits events to a target
    - Write head: advances at its own clock rate, writes values from a source

  An optional CORRUPT process applies probabilistic bit-level mutation to stored
  values, with a side-channel `on-corrupt!` callback for reactive secondary behaviour.

  Example:

    (defflux :evolving
      {:steps   16
       :scale   (scale :C :dorian)
       :read    {:clock     master-clock  :direction :forward}
       :write   {:clock     (clock-div 3 master-clock)
                 :source    (lfo :rate 0.1)
                 :direction :forward}
       :corrupt {:intensity (lfo :rate 0.05)
                 :clock     (clock-div 4 master-clock)}
       :output  {:target :midi/synth :channel 1}})

    (flux/on-corrupt! :evolving
      (fn [step-idx _old _new]
        (ctrl/send! [:env/trig] 1)))

  Key design decisions: Q53 (concurrency model), Q54 (scale as ITemporalValue),
  §26 Flux Sequence Architecture (Labyrinth-Inspired).")
