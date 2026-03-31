; SPDX-License-Identifier: EPL-2.0
(ns cljseq.morph
  "cljseq morph namespace — synth-style parameter mapping.

  `defmorph` defines a named, multi-input parameter mapping. Transfer functions
  must be pure (patch-serializable). Single-input and multi-input (XY pad,
  joystick) forms are supported. Morphs register at /cljseq/morphs/<name>/.

  Example:
    (defmorph :xy-filter
      {:inputs  {:x [:float 0.0 1.0] :y [:float 0.0 1.0]}
       :targets [{:path [:filter/cutoff]    :fn (fn [{:keys [x]}]   (* x 8000))}
                 {:path [:filter/resonance] :fn (fn [{:keys [y]}]   (* y 0.9))}
                 {:path [:filter/drive]     :fn (fn [{:keys [x y]}] (* x y 4.0))}]})

  Key design decisions: Q49 (defmorph / cljseq.morph).")
