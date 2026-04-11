; SPDX-License-Identifier: EPL-2.0
(ns cljseq.fm
  "FM synthesis vocabulary — operator graphs as Clojure data.

  An FM synth definition describes operators and their routing explicitly,
  then compiles to multiple backends:

    (compile-fm :sc        my-alg)  — sclang SynthDef string
    (compile-fm :digitone  my-alg)  — algorithm index + CC map
    (compile-fm :leviasynth my-alg) — per-OSC CC values (8-op)

  ## Operators

  Each operator is a sine oscillator with its own envelope and level:

    {:id       1           ; unique identifier (keyword or integer)
     :ratio    1.0         ; frequency ratio relative to base note
     :level    1.0         ; output amplitude [0.0, 1.0]
     :feedback 0.0         ; self-modulation amount [0.0, 1.0]
     :env      :adsr       ; :adsr, :perc, or {:attack A :decay D :sustain S :release R}}

  ## Algorithms

  An algorithm defines which operators are carriers (output to audio)
  and how operators modulate each other:

    {:fm/name      :my-alg
     :fm/args      {:freq 440 :amp 0.5 :attack 0.01 :release 1.0}
     :fm/operators [{:id 1 :ratio 1.0  :level 1.0  :env :adsr}
                    {:id 2 :ratio 14.0 :level 0.5  :env :perc}]
     :fm/carriers  #{1}           ; op 1 goes to audio output
     :fm/routing   {2 1}}         ; op 2 modulates op 1

  :fm/routing is a map {modulator-id → modulated-id}.

  ## Named algorithms

    (fm-algorithm :2op)         — 1 carrier, 1 modulator
    (fm-algorithm :4op-stack)   — series stack: 4→3→2→1(carrier)
    (fm-algorithm :2pairs)      — two parallel C+M pairs
    (fm-algorithm :3-to-1)      — one carrier, 3 parallel modulators
    (fm-algorithm :additive-4)  — all 4 as carriers (FM off)

  ## Classic sounds

    (compile-fm :sc :dx-ep)        — DX7-style electric piano
    (compile-fm :sc :dx-bass)      — FM bass (series stack, high ratio)
    (compile-fm :sc :dx-bell)      — bell (high-ratio modulator, perc env)
    (compile-fm :sc :dt-metal)        — metallic perc (inharmonic, short)"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private fm-registry (atom {}))

(defn def-fm!
  "Register a named FM algorithm definition.

  Example:
    (def-fm! :my-alg
      {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                      {:id 2 :ratio 7.0 :level 0.5 :env :perc}]
       :fm/carriers  #{1}
       :fm/routing   {2 1}})"
  [name-kw fm-map]
  (swap! fm-registry assoc name-kw (assoc fm-map :fm/name name-kw))
  name-kw)

(defn get-fm
  "Return the FM definition for `name-kw`, or nil."
  [name-kw]
  (get @fm-registry name-kw))

(defn fm-names
  "Return sorted seq of all registered FM algorithm names."
  []
  (sort (keys @fm-registry)))

;; ---------------------------------------------------------------------------
;; Algorithm templates — structural skeletons without specific ratios/levels
;; ---------------------------------------------------------------------------

(def ^:private algorithm-templates
  {:2op
   {:fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 2 :ratio 2.0  :level 0.5 :env :perc}]
    :fm/carriers  #{1}
    :fm/routing   {2 1}}

   :4op-stack
   {:fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 2 :ratio 1.0  :level 0.8 :env :perc}
                   {:id 3 :ratio 2.0  :level 0.5 :env :perc}
                   {:id 4 :ratio 4.0  :level 0.3 :env :perc}]
    :fm/carriers  #{1}
    :fm/routing   {2 1, 3 2, 4 3}}

   :2pairs
   {:fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 2 :ratio 14.0 :level 0.5 :env :perc}
                   {:id 3 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 4 :ratio 1.0  :level 0.6 :env :perc}]
    :fm/carriers  #{1 3}
    :fm/routing   {2 1, 4 3}}

   :3-to-1
   {:fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 2 :ratio 2.0  :level 0.5 :env :perc}
                   {:id 3 :ratio 3.0  :level 0.4 :env :perc}
                   {:id 4 :ratio 7.0  :level 0.3 :env :perc}]
    :fm/carriers  #{1}
    :fm/routing   {2 1, 3 1, 4 1}}

   :additive-4
   {:fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 2 :ratio 2.0  :level 0.5 :env :adsr}
                   {:id 3 :ratio 3.0  :level 0.3 :env :adsr}
                   {:id 4 :ratio 4.0  :level 0.2 :env :adsr}]
    :fm/carriers  #{1 2 3 4}
    :fm/routing   {}}

   ;; -------------------------------------------------------------------------
   ;; 8-operator templates
   ;; -------------------------------------------------------------------------

   ;; Full series stack: op8 → op7 → … → op1 (carrier).
   ;; Maximum modulation depth — each op modulates the one below it.
   ;; DX7 algorithms 5 and 7 are this shape (with some variations).
   :8op-stack
   {:fm/operators [{:id 1 :ratio 1.0   :level 1.0  :env :adsr}
                   {:id 2 :ratio 1.0   :level 0.9  :env :perc}
                   {:id 3 :ratio 2.0   :level 0.7  :env :perc}
                   {:id 4 :ratio 4.0   :level 0.5  :env :perc}
                   {:id 5 :ratio 8.0   :level 0.4  :env :perc}
                   {:id 6 :ratio 16.0  :level 0.3  :env :perc}
                   {:id 7 :ratio 1.0   :level 0.2  :env :perc}
                   {:id 8 :ratio 2.0   :level 0.15 :env :perc}]
    :fm/carriers  #{1}
    :fm/routing   {2 1, 3 2, 4 3, 5 4, 6 5, 7 6, 8 7}}

   ;; Four independent carrier+modulator pairs.
   ;; Gives four voices with different timbres summed together.
   ;; Useful for organ-like layering or complex chordal textures.
   :4-pairs
   {:fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 2 :ratio 2.0  :level 0.5 :env :perc}
                   {:id 3 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 4 :ratio 4.0  :level 0.4 :env :perc}
                   {:id 5 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 6 :ratio 3.0  :level 0.3 :env :perc}
                   {:id 7 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 8 :ratio 7.0  :level 0.2 :env :perc}]
    :fm/carriers  #{1 3 5 7}
    :fm/routing   {2 1, 4 3, 6 5, 8 7}}

   ;; Two parallel 4-op series stacks.
   ;; Two carriers, each with a 3-op modulation chain behind them.
   ;; Gives a richer, two-voice layered sound vs. a single stack.
   :2x4-stacks
   {:fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 2 :ratio 1.0  :level 0.8 :env :perc}
                   {:id 3 :ratio 2.0  :level 0.6 :env :perc}
                   {:id 4 :ratio 4.0  :level 0.4 :env :perc}
                   {:id 5 :ratio 1.0  :level 1.0 :env :adsr}
                   {:id 6 :ratio 1.0  :level 0.8 :env :perc}
                   {:id 7 :ratio 3.0  :level 0.5 :env :perc}
                   {:id 8 :ratio 9.0  :level 0.3 :env :perc}]
    :fm/carriers  #{1 5}
    :fm/routing   {2 1, 3 2, 4 3, 6 5, 7 6, 8 7}}

   ;; All 8 operators as carriers — pure additive synthesis.
   ;; FM is off; each operator is an independent partial.
   ;; Set ratios to harmonic series for organ; inharmonic for bells.
   :8-carriers
   {:fm/operators [{:id 1 :ratio 1.0 :level 1.0  :env :adsr}
                   {:id 2 :ratio 2.0 :level 0.5  :env :adsr}
                   {:id 3 :ratio 3.0 :level 0.33 :env :adsr}
                   {:id 4 :ratio 4.0 :level 0.25 :env :adsr}
                   {:id 5 :ratio 5.0 :level 0.2  :env :adsr}
                   {:id 6 :ratio 6.0 :level 0.16 :env :adsr}
                   {:id 7 :ratio 7.0 :level 0.14 :env :adsr}
                   {:id 8 :ratio 8.0 :level 0.12 :env :adsr}]
    :fm/carriers  #{1 2 3 4 5 6 7 8}
    :fm/routing   {}}

   ;; One main carrier with 4 parallel modulators, plus 3 extra carriers.
   ;; Op 1 receives dense modulation; ops 5–7 are independent carriers
   ;; adding harmonic richness. Mix of complex and pure tones.
   :4mod-1
   {:fm/operators [{:id 1 :ratio 1.0 :level 1.0 :env :adsr}
                   {:id 2 :ratio 2.0 :level 0.6 :env :perc}
                   {:id 3 :ratio 3.0 :level 0.5 :env :perc}
                   {:id 4 :ratio 5.0 :level 0.4 :env :perc}
                   {:id 5 :ratio 7.0 :level 0.3 :env :perc}
                   {:id 6 :ratio 1.0 :level 0.7 :env :adsr}
                   {:id 7 :ratio 2.0 :level 0.5 :env :adsr}
                   {:id 8 :ratio 3.0 :level 0.3 :env :adsr}]
    :fm/carriers  #{1 6 7 8}
    :fm/routing   {2 1, 3 1, 4 1, 5 1}}})

(defn fm-algorithm
  "Return a named algorithm template map. Customize :fm/operators to
  set specific ratios, levels, and envelopes.

  4-op templates: :2op :4op-stack :2pairs :3-to-1 :additive-4
  8-op templates: :8op-stack :4-pairs :2x4-stacks :8-carriers :4mod-1"
  [alg-key]
  (or (get algorithm-templates alg-key)
      (throw (ex-info "fm-algorithm: unknown template" {:key alg-key}))))

;; ---------------------------------------------------------------------------
;; Topological sort — operators must be computed before what they modulate
;; ---------------------------------------------------------------------------

(defn- topo-sort
  "Sort operator IDs so each modulator appears before its target.
  `routing` is {mod-id → target-id}."
  [op-ids routing]
  (let [;; For each op, which ops it depends on (its modulators)
        deps (reduce (fn [m [mod target]]
                       (update m target (fnil conj #{}) mod))
                     {}
                     routing)
        in-deg (into {} (map (fn [id] [id (count (get deps id #{}))]) op-ids))
        ;; Forward: mod → [ops it modulates]
        fwd (reduce (fn [m [mod target]]
                      (update m mod (fnil conj []) target))
                    {}
                    routing)]
    (loop [q    (sort (keep (fn [[id d]] (when (= 0 d) id)) in-deg))
           seen []
           deg  in-deg]
      (if (empty? q)
        seen
        (let [n     (first q)
              rest-q (vec (rest q))
              succs  (get fwd n [])
              [new-deg new-q]
              (reduce (fn [[d qs] s]
                        (let [nd (dec (get d s 1))]
                          [(assoc d s nd) (if (zero? nd) (conj qs s) qs)]))
                      [deg rest-q]
                      succs)]
          (recur new-q (conj seen n) new-deg))))))

;; ---------------------------------------------------------------------------
;; SC compiler
;; ---------------------------------------------------------------------------

(defn- sc-name [k]
  (str/replace (name k) #"[^a-zA-Z0-9_]" "_"))

;; ---------------------------------------------------------------------------
;; Routing normalisation
;;
;; Routing accepts two forms:
;;   short  {mod-id target-id}
;;   extended {mod-id {:to target-id :depth 1.0 :tzfm? false}}
;;
;; :depth  — modulation strength.
;;           Non-TZFM: added Hz offset = op_mod * depth
;;           TZFM:     modulation index = op_mod * freq * ratio * depth
;; :tzfm?  — when true, scale modulation by the carrier's freq * ratio so
;;           the modulation index (timbre) stays constant across pitch.
;; ---------------------------------------------------------------------------

(defn- normalize-routing
  "Return routing in canonical form: {mod-id {:to target-id :depth 1.0 :tzfm? false}}.
  Both short-form {2 1} and extended-form {2 {:to 1 :depth 0.5 :tzfm? true}} accepted."
  [routing]
  (into {}
        (map (fn [[mod-id target]]
               [mod-id (if (map? target)
                         (merge {:depth 1.0 :tzfm? false} target)
                         {:to target :depth 1.0 :tzfm? false})])
             routing)))

(defn- routing->forward
  "Extract a plain {mod-id target-id} map from normalised routing (for topo-sort)."
  [norm-routing]
  (into {} (map (fn [[k v]] [k (:to v)]) norm-routing)))

;; ---------------------------------------------------------------------------
;; Per-operator UGen call
;; ---------------------------------------------------------------------------

(defn- ugen-call-str
  "Return the SC UGen expression for one operator.

  Waveform selection:
    :sin    (default) — SinOsc.ar(freq * ratio + mod)
    :sin-fb           — SinOscFB.ar(freq * ratio + mod, feedback)
    :saw              — Saw.ar(freq * ratio + mod)
    :tri              — LFTri.ar(freq * ratio + mod)
    :pulse            — Pulse.ar(freq * ratio + mod)

  Self-feedback (:feedback > 0 with no explicit :waveform) auto-selects :sin-fb.

  TZFM modulation (from routing) scales the modulation signal by the carrier
  frequency (freq * ratio) so the modulation index is pitch-invariant."
  [op norm-routing cross-feedback fb-idx-map n-fb]
  (let [op-id    (:id op)
        ratio-s  (str (double (:ratio op 1.0)))
        waveform (or (:waveform op)
                     (when (pos? (:feedback op 0)) :sin-fb)
                     :sin)
        feedback (double (:feedback op 0.0))

        ;; Standard routing modulation terms
        std-mods (keep (fn [[mod-id conn]]
                         (when (= op-id (:to conn))
                           (let [depth  (double (:depth conn 1.0))
                                 tzfm?  (:tzfm? conn false)]
                             (if tzfm?
                               (str "op_" mod-id " * freq * " ratio-s " * " depth)
                               (str "op_" mod-id " * " depth)))))
                       norm-routing)

        ;; Cross-feedback modulation terms (LocalIn channels)
        xfb-mods (keep (fn [{:keys [from to depth]}]
                         (when (= to op-id)
                           (let [idx (get fb-idx-map from)
                                 d   (double (or depth 0.1))]
                             (when (some? idx)
                               (if (= n-fb 1)
                                 (str "fb_bus * " d)
                                 (str "fb_bus[" idx "] * " d))))))
                       cross-feedback)

        all-mods (concat std-mods xfb-mods)
        mod-str  (when (seq all-mods)
                   (str " + " (str/join " + " all-mods)))
        freq-arg (str "freq * " ratio-s (or mod-str ""))]

    (case waveform
      :sin    (str "SinOsc.ar("    freq-arg ")")
      :sin-fb (str "SinOscFB.ar("  freq-arg ", " feedback ")")
      :saw    (str "Saw.ar("       freq-arg ")")
      :tri    (str "LFTri.ar("     freq-arg ")")
      :pulse  (str "Pulse.ar("     freq-arg ")"))))

(defn- env->sc-str [env gate done-action?]
  (let [done (if done-action? ", doneAction: 2" "")]
    (cond
      (or (nil? env) (= :adsr env))
      (str "EnvGen.ar(Env.adsr(attack, decay, sustain, release), " gate done ")")

      (= :perc env)
      (str "EnvGen.ar(Env.perc(attack, decay), " gate done ")")

      (= :short-perc env)
      (str "EnvGen.ar(Env.perc(0.001, 0.15), " gate done ")")

      (map? env)
      (let [{a :attack d :decay s :sustain r :release} env]
        (if (and s r)
          (str "EnvGen.ar(Env.adsr(" (double a) ", " (double d) ", "
               (double s) ", " (double r) "), " gate done ")")
          (str "EnvGen.ar(Env.perc(" (double (or a 0.001)) ", "
               (double (or d 0.3)) "), " gate done ")"))))))

(defn- fm->sc-str
  "Generate a sclang SynthDef string from an FM definition map.

  Supports:
    - Per-operator waveform (:waveform :sin/:saw/:tri/:pulse/:sin-fb)
    - Self-feedback (:feedback > 0 → SinOscFB)
    - TZFM routing ({mod-id {:to target :depth D :tzfm? true}})
    - Cross-feedback loops (:fm/cross-feedback) via LocalIn/LocalOut"
  [fm-def]
  (let [{:fm/keys [name operators carriers routing args cross-feedback]} fm-def
        carriers       (set (or carriers #{}))
        cross-feedback (or cross-feedback [])
        norm-routing   (normalize-routing (or routing {}))
        fwd-routing    (routing->forward norm-routing)
        args           (merge {:freq 440 :amp 0.5 :attack 0.01 :decay 0.3
                               :sustain 0.5 :release 1.0}
                              args)
        op-map         (into {} (map (fn [op] [(:id op) op]) operators))
        sorted-ids     (topo-sort (map :id operators) fwd-routing)

        ;; Cross-feedback channel index map: {from-op-id → LocalIn-channel-index}
        n-fb        (count cross-feedback)
        fb-idx-map  (into {} (map-indexed (fn [i {:keys [from]}] [from i]) cross-feedback))

        ;; sclang arg list
        args-str (str "|"
                      (str/join ", "
                                (map (fn [[k v]] (str (sc-name k) "=" (double v)))
                                     (merge {:gate 1} args)))
                      "|")

        ;; LocalIn declaration — one channel per cross-feedback entry
        local-in-lines (when (pos? n-fb)
                         [(str "    var fb_bus = LocalIn.ar(" n-fb ");")])

        ;; Operator var declarations in topo order
        var-lines
        (mapcat
          (fn [id]
            (let [op       (get op-map id)
                  carrier? (contains? carriers id)
                  env-str  (env->sc-str (:env op) "gate" carrier?)
                  ugen-str (ugen-call-str op norm-routing cross-feedback fb-idx-map n-fb)
                  level-s  (str (double (:level op 1.0)))]
              [(str "    var env_" id " = " env-str ";")
               (str "    var op_"  id " = " ugen-str " * " level-s " * env_" id ";")]))
          sorted-ids)

        ;; LocalOut — write cross-feedback signals
        local-out-lines (when (pos? n-fb)
                          (let [sigs (mapv (fn [{:keys [from depth]}]
                                            (str "op_" from " * " (double (or depth 0.1))))
                                          cross-feedback)
                                arr  (if (= 1 (count sigs))
                                       (first sigs)
                                       (str "[" (str/join ", " sigs) "]"))]
                            [(str "    LocalOut.ar(" arr ");")]))

        ;; Mix carriers
        carrier-ids (filter #(contains? carriers %) sorted-ids)
        mix-str     (if (= 1 (count carrier-ids))
                      (str "op_" (first carrier-ids))
                      (str "Mix.ar([" (str/join ", " (map #(str "op_" %) carrier-ids)) "])"))

        synth-name (sc-name (or name :fm_synth))

        all-lines (concat (or local-in-lines [])
                          var-lines
                          (or local-out-lines []))]

    (str "SynthDef(\\" synth-name ", {\n"
         "    " args-str "\n"
         (str/join "\n" all-lines) "\n"
         "    Out.ar(0, Pan2.ar(" mix-str " * amp, 0))\n"
         "}).add;")))

;; ---------------------------------------------------------------------------
;; Digitone algorithm matching
;; ---------------------------------------------------------------------------

;; Digitone's 8 algorithms described structurally:
;; Each entry: {:carriers N :topology kw :description str}
;; topology: :stack (series chain), :parallel-mods (multiple mods on one carrier),
;;           :paired (independent C+M pairs), :additive (all carriers)
(def ^:private digitone-algorithms
  [{:index 0 :carriers 1 :topology :stack         :max-mods 3
    :description "A1←A2←B1←B2 — full series stack, one carrier"}
   {:index 1 :carriers 2 :topology :paired         :max-mods 1
    :description "A1←A2, B1←B2 — two independent carrier+modulator pairs"}
   {:index 2 :carriers 1 :topology :parallel-mods  :max-mods 2
    :description "A1←[A2, B1←B2] — one carrier with nested modulator pair"}
   {:index 3 :carriers 2 :topology :mixed          :max-mods 2
    :description "A1←[A2, B1], B2 — one carrier with 2 mods + independent carrier"}
   {:index 4 :carriers 2 :topology :mixed          :max-mods 2
    :description "A1←A2←B2, B1 — series pair + independent carrier"}
   {:index 5 :carriers 1 :topology :parallel-mods  :max-mods 3
    :description "A1←[A2, B1, B2] — one carrier with 3 parallel modulators"}
   {:index 6 :carriers 2 :topology :shared-mod     :max-mods 2
    :description "A1←B2, B1←B2 — B2 modulates both carriers (shared modulator)"}
   {:index 7 :carriers 4 :topology :additive       :max-mods 0
    :description "A1, A2, B1, B2 — all carriers, additive synthesis (FM off)"}])

(defn- classify-routing
  "Classify a routing map into a topology keyword."
  [carriers routing]
  (let [carrier-set (set carriers)
        mod-count   (count (keys routing))
        carrier-ct  (count carrier-set)]
    (cond
      (zero? mod-count)              :additive
      (= 1 carrier-ct)
      (let [targets (vals routing)
            unique-targets (set targets)]
        (if (= 1 (count unique-targets))
          ;; All mods target the same op — either stack or parallel
          (if (> (count (filter #(contains? (set (keys routing)) %) targets)) 0)
            :stack
            :parallel-mods)
          :stack))
      (= 2 carrier-ct)
      (let [shared (filter (fn [m]
                             (> (count (filter #(= m %) (vals routing))) 1))
                           (keys routing))]
        (if (seq shared) :shared-mod :paired))
      :else :mixed)))

(defn- match-digitone-algorithm
  "Return the Digitone algorithm index (0–7) closest to the given fm-def."
  [fm-def]
  (let [carriers  (set (or (:fm/carriers fm-def) #{}))
        routing   (or (:fm/routing fm-def) {})
        n-cars    (count carriers)
        topology  (classify-routing carriers routing)
        match     (first (filter (fn [alg]
                                   (and (= n-cars (:carriers alg))
                                        (= topology (:topology alg))))
                                 digitone-algorithms))]
    (or (:index match)
        ;; Fallback: pick by carrier count
        (:index (first (filter #(= n-cars (:carriers %)) digitone-algorithms)))
        0)))

(defn- ratio->cc
  "Map an FM ratio [0.25, 16.0] to a 7-bit CC value [0, 127]."
  [ratio]
  (int (Math/round (double (* 127 (/ (- (max 0.25 (min 16.0 ratio)) 0.25) 15.75))))))

(defn- level->cc [level]  (int (Math/round (double (* 127 (max 0.0 (min 1.0 level)))))))
(defn- env-time->cc [t]   (int (Math/round (double (* 127 (Math/sqrt (/ (max 0 (min 10.0 t)) 10.0)))))))

(defn- fm->digitone
  "Generate a Digitone CC map from an FM definition.

  Returns {:algorithm N :ccs [{:path [...] :value N} ...]}
  plus a :note explaining any limitations."
  [fm-def]
  (let [{:fm/keys [operators carriers routing args]} fm-def
        alg-idx   (match-digitone-algorithm fm-def)
        carrier-set (set (or carriers #{}))
        ;; Sort operators into C (carriers) and mod groups A, B
        carrier-ops (filter #(contains? carrier-set (:id %)) operators)
        mod-ops     (filter #(not (contains? carrier-set (:id %))) operators)
        ;; Map to Digitone's C / A / B roles
        op-c   (first carrier-ops)
        op-a   (first mod-ops)
        op-b   (second mod-ops)
        ;; Shared args for envelope
        a-env  (:env op-a {:attack 0.01 :decay 0.3})
        b-env  (:env op-b {:attack 0.01 :decay 0.3})
        ccs
        (cond-> []
          op-c (conj {:path [:fm :ratio-c]  :cc 91 :value (ratio->cc (:ratio op-c 1.0))})
          op-a (conj {:path [:fm :ratio-a]  :cc 92 :value (ratio->cc (:ratio op-a 1.0))})
          op-b (conj {:path [:fm :ratio-b]  :cc 16 :value (ratio->cc (:ratio op-b 1.0))})
          true (conj {:path [:fm :algorithm] :cc 90 :value alg-idx})
          true (conj {:path [:fm :feedback]  :cc 19 :value (level->cc (apply max 0 (map #(:feedback % 0) operators)))})
          true (conj {:path [:fm :mix]       :cc 20 :value (if (and op-a op-b) 64 127)})
          op-a (conj {:path [:op-a :level]       :cc 78 :value (level->cc (:level op-a 1.0))})
          op-a (conj {:path [:op-a :env-attack]  :cc 75 :value (env-time->cc (get a-env :attack 0.01))})
          op-a (conj {:path [:op-a :env-decay]   :cc 76 :value (env-time->cc (get a-env :decay 0.3))})
          op-b (conj {:path [:op-b :level]       :cc 82 :value (level->cc (:level op-b 1.0))})
          op-b (conj {:path [:op-b :env-attack]  :cc 79 :value (env-time->cc (get b-env :attack 0.01))})
          op-b (conj {:path [:op-b :env-decay]   :cc 80 :value (env-time->cc (get b-env :decay 0.3))}))]
    {:algorithm   alg-idx
     :description (:description (get digitone-algorithms alg-idx))
     :ccs         ccs
     :note        (str "Digitone topology is fixed per algorithm. "
                       "Ratios, levels, and envelopes are mapped to CCs; "
                       "algorithm selection (CC 90 = " alg-idx ") must match "
                       "the patch's loaded algorithm.")}))

;; ---------------------------------------------------------------------------
;; Leviasynth compiler (8-op, CC-only for level/pitch/feedback)
;; ---------------------------------------------------------------------------

;; Leviasynth OSC pitch CCs are centered at 64 (= no detune).
;; The pitch range is ±24 semitones over 0–127 (approx 2.67 cents/unit).
;; ratio → pitch detune in semitones → CC value
(defn- ratio->leviasynth-pitch-cc
  "Map a frequency ratio to a Leviasynth OSC pitch CC (centered 64).
  ratio 1.0 = CC 64, ratio 2.0 = +12 semitones = CC ~99."
  [ratio]
  (let [semitones (* 12.0 (/ (Math/log ratio) (Math/log 2.0)))
        clamped   (max -24.0 (min 24.0 semitones))
        cc        (int (Math/round (+ 64.0 (* clamped (/ 63.0 24.0)))))]
    (max 0 (min 127 cc))))

;; OSC level CCs: 24–31 (OSC 1–8)
;; OSC pitch CCs: 33–41 (OSC 1–5 = 33–37, then 39–41 for OSC 6–8; 38 absent)
(defn- leviasynth-pitch-cc [osc-num]
  (cond (<= osc-num 5) (+ 32 osc-num)
        :else          (+ 33 osc-num)))  ; 38 is absent; OSC 6→39, 7→40, 8→41

(defn- fm->leviasynth
  "Generate a Leviasynth CC map from an FM definition (up to 8 operators).

  Returns {:ccs [{:osc N :path [...] :cc N :value N} ...] :note str}.
  Only level, pitch-detune, and feedback are CC-addressable.
  Algorithm/topology and synthesis mode must be set on the device."
  [fm-def]
  (let [{:fm/keys [operators]} fm-def
        ops (take 8 operators)
        ccs (into []
              (mapcat
                (fn [op n]
                  (let [osc  (inc n)
                        lcc  (+ 23 osc)        ; CC 24–31
                        pcc  (leviasynth-pitch-cc osc)
                        fcc  (+ 41 osc)]       ; CC 42–49
                    (cond-> []
                      true (conj {:osc osc :path [:osc osc :level]
                                  :cc lcc :value (level->cc (:level op 1.0))})
                      true (conj {:osc osc :path [:osc osc :pitch]
                                  :cc pcc :value (ratio->leviasynth-pitch-cc (:ratio op 1.0))})
                      (pos? (:feedback op 0))
                      (conj {:osc osc :path [:osc osc :feedback]
                             :cc fcc :value (level->cc (:feedback op 0))}))))
                ops (range)))]
    {:ccs  ccs
     :note (str "Leviasynth: synthesis mode (FM/PM/etc.), waveform, and algorithm topology "
                "must be set on the device panel — not accessible via MIDI CC. "
                "These CCs set per-OSC level and pitch detune only.")}))

;; ---------------------------------------------------------------------------
;; compile-fm multimethod
;; ---------------------------------------------------------------------------

(defmulti compile-fm
  "Compile a registered FM definition to a backend-specific representation.

  `name-or-def` — a registered name keyword or an inline fm-def map.

  Backends:
    :sc         — sclang SynthDef string
    :digitone   — {:algorithm N :ccs [...] :note str}
    :leviasynth — {:ccs [...] :note str}"
  (fn [backend _] backend))

(defn- resolve-fm [name-or-def]
  (if (keyword? name-or-def)
    (or (get-fm name-or-def)
        (throw (ex-info "compile-fm: FM definition not found" {:name name-or-def})))
    name-or-def))

(defmethod compile-fm :sc [_ name-or-def]
  (fm->sc-str (resolve-fm name-or-def)))

(defmethod compile-fm :digitone [_ name-or-def]
  (fm->digitone (resolve-fm name-or-def)))

(defmethod compile-fm :leviasynth [_ name-or-def]
  (fm->leviasynth (resolve-fm name-or-def)))

(defmethod compile-fm :default [backend _]
  (throw (ex-info "compile-fm: unknown backend" {:backend backend})))

;; ---------------------------------------------------------------------------
;; Named FM synths
;; ---------------------------------------------------------------------------

(def-fm! :dx-ep
  {:fm/args     {:freq 440 :amp 0.5 :attack 0.001 :decay 0.5 :sustain 0.0 :release 1.5}
   :fm/operators [{:id 1 :ratio 1.0  :level 1.0 :env :adsr}
                  {:id 2 :ratio 14.0 :level 0.6 :env :perc}
                  {:id 3 :ratio 1.0  :level 1.0 :env :adsr}
                  {:id 4 :ratio 14.0 :level 0.4 :env :perc}]
   :fm/carriers  #{1 3}
   :fm/routing   {2 1, 4 3}
   :fm/doc "DX7-style electric piano. Two carrier+modulator pairs; high-ratio
            modulator (14×) gives the characteristic bell-like attack transient.
            Low sustain on modulator = timbre decays from bright to pure."})

(def-fm! :dx-bass
  {:fm/args     {:freq 110 :amp 0.7 :attack 0.001 :decay 0.4 :sustain 0.3 :release 0.8}
   :fm/operators [{:id 1 :ratio 1.0 :level 1.0  :env :adsr}
                  {:id 2 :ratio 1.0 :level 0.8  :env {:attack 0.001 :decay 0.2 :sustain 0.0 :release 0.5}}
                  {:id 3 :ratio 2.0 :level 0.5  :env {:attack 0.001 :decay 0.15 :sustain 0.0 :release 0.4}}
                  {:id 4 :ratio 4.0 :level 0.35 :env {:attack 0.001 :decay 0.1  :sustain 0.0 :release 0.3}}]
   :fm/carriers  #{1}
   :fm/routing   {2 1, 3 2, 4 3}
   :fm/doc "FM bass — 4-op series stack. Each modulator decays faster than the carrier,
            pulling timbre from complex to pure over the note duration. High modulator
            levels at note onset give percussive click; fast decay = punch, not mud."})

(def-fm! :dx-bell
  {:fm/args     {:freq 440 :amp 0.6 :attack 0.001 :decay 0.001 :sustain 0.0 :release 4.0}
   :fm/operators [{:id 1 :ratio 1.0  :level 1.0  :env :adsr}
                  {:id 2 :ratio 3.5  :level 0.8  :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 3.0}}
                  {:id 3 :ratio 1.0  :level 0.5  :env :adsr}
                  {:id 4 :ratio 11.0 :level 0.3  :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 1.5}}]
   :fm/carriers  #{1 3}
   :fm/routing   {2 1, 4 3}
   :fm/doc "FM bell — two C+M pairs with inharmonic ratios (3.5× and 11×) and very
            long release. Ratio 3.5 is slightly inharmonic vs. octave (3.0 or 4.0),
            giving the slightly 'wrong' bell character. Ratio 11 adds shimmer."})

(def-fm! :dt-metal
  {:fm/args     {:freq 200 :amp 0.8 :attack 0.001 :decay 0.001 :sustain 0.0 :release 0.4}
   :fm/operators [{:id 1 :ratio 1.0  :level 1.0  :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 0.4}}
                  {:id 2 :ratio 1.41 :level 0.9  :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 0.2}}
                  {:id 3 :ratio 2.4  :level 0.7  :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 0.15}}
                  {:id 4 :ratio 1.0  :level 0.6  :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 0.4}}]
   :fm/carriers  #{1 4}
   :fm/routing   {2 1, 3 4}
   :fm/doc "Metallic percussion — Digitone-style. Inharmonic ratios (1.41 ≈ √2, 2.4)
            produce the non-integer spectral components of metal. Two carrier pairs
            with different timbres blended for complex attack. Very short envelopes."})

(def-fm! :dx-brass
  {:fm/args     {:freq 440 :amp 0.6 :attack 0.05 :decay 0.1 :sustain 0.8 :release 0.3}
   :fm/operators [{:id 1 :ratio 1.0 :level 1.0  :env :adsr}
                  {:id 2 :ratio 1.0 :level 0.9  :env {:attack 0.05 :decay 0.1 :sustain 0.7 :release 0.3}}
                  {:id 3 :ratio 1.0 :level 0.7  :env {:attack 0.05 :decay 0.1 :sustain 0.6 :release 0.3}}]
   :fm/carriers  #{1}
   :fm/routing   {2 1, 3 2}
   :fm/doc "FM brass — 3-op stack with matching ratios (all 1×). The envelopes on the
            modulators shape the brightness — attack builds with a slight delay,
            giving the characteristic brass 'bray' at note onset."})

;; ---------------------------------------------------------------------------
;; 8-operator named synths
;; ---------------------------------------------------------------------------

(def-fm! :8op-brass
  {:fm/args     {:freq 440 :amp 0.6 :attack 0.05 :decay 0.1 :sustain 0.8 :release 0.3}
   :fm/operators [{:id 1 :ratio 1.0 :level 1.0
                   :env :adsr}
                  {:id 2 :ratio 1.0 :level 0.95
                   :env {:attack 0.05 :decay 0.1 :sustain 0.75 :release 0.3}}
                  {:id 3 :ratio 1.0 :level 0.85
                   :env {:attack 0.04 :decay 0.09 :sustain 0.65 :release 0.28}}
                  {:id 4 :ratio 1.0 :level 0.65
                   :env {:attack 0.03 :decay 0.07 :sustain 0.0  :release 0.2}}
                  {:id 5 :ratio 2.0 :level 0.5
                   :env {:attack 0.02 :decay 0.05 :sustain 0.0  :release 0.15}}
                  {:id 6 :ratio 3.0 :level 0.38
                   :env {:attack 0.01 :decay 0.04 :sustain 0.0  :release 0.1}}
                  {:id 7 :ratio 4.0 :level 0.25
                   :env {:attack 0.001 :decay 0.03 :sustain 0.0 :release 0.08}}
                  {:id 8 :ratio 8.0 :level 0.15
                   :env {:attack 0.001 :decay 0.02 :sustain 0.0 :release 0.05}}]
   :fm/carriers  #{1}
   :fm/routing   {2 {:to 1 :depth 1.2 :tzfm? true}
                  3 {:to 2 :depth 0.8}
                  4 {:to 3 :depth 0.6}
                  5 {:to 4 :depth 0.5}
                  6 {:to 5 :depth 0.4}
                  7 {:to 6 :depth 0.3}
                  8 {:to 7 :depth 0.2}}
   :fm/doc "8-op FM brass — full series stack. TZFM on the first modulation stage
            (op2→op1) ensures the modulation index stays constant across pitch so
            the brightness character is consistent at all registers.
            Envelopes stagger: modulators decay faster than the carrier, pulling
            timbre from bright and brassy at onset to pure and focused on sustain.
            Recommended: sweep op2 :depth via apply-trajectory! for filter-like brightness arc."})

(def-fm! :8op-bells
  {:fm/args     {:freq 440 :amp 0.6 :attack 0.001 :decay 0.001 :sustain 0.0 :release 8.0}
   :fm/operators [{:id 1 :ratio 1.0   :level 1.0  :env :adsr}
                  {:id 2 :ratio 3.5   :level 0.8
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 6.0}}
                  {:id 3 :ratio 1.0   :level 0.65 :env :adsr}
                  {:id 4 :ratio 11.0  :level 0.55
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 4.0}}
                  {:id 5 :ratio 1.0   :level 0.5  :env :adsr}
                  {:id 6 :ratio 5.667 :level 0.4
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 3.0}}
                  {:id 7 :ratio 1.0   :level 0.35 :env :adsr}
                  {:id 8 :ratio 8.933 :level 0.28
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 2.0}}]
   :fm/carriers  #{1 3 5 7}
   :fm/routing   {2 1, 4 3, 6 5, 8 7}
   :fm/cross-feedback [{:from 1 :to 3 :depth 0.05}
                       {:from 3 :to 5 :depth 0.03}]
   :fm/doc "8-op FM bell — four carrier+modulator pairs using Chowning/Fletcher-Rossing
            inharmonic partial ratios (3.5×, 11×, 5.667×, 8.933×). Each pair decays
            independently; the high-ratio modulators die out first, leaving pure sinusoidal
            partials ringing.
            Cross-feedback between op1→op3 and op3→op5 adds subtle coupling between
            partial modes — as op1 rings, it slightly inflects op3's partial, as in a
            real struck bell where modes exchange energy.
            The ratio 5.667 is the Chowning bell IV partial; 8.933 is partial V."})

(def-fm! :8op-evolving-pad
  {:fm/args     {:freq 220 :amp 0.4 :attack 2.0 :decay 1.0 :sustain 0.8 :release 5.0}
   :fm/operators [{:id 1 :ratio 1.0   :level 1.0 :env :adsr}
                  {:id 2 :ratio 1.003 :level 0.9 :env :adsr}
                  {:id 3 :ratio 0.5   :level 0.7 :env :adsr}
                  {:id 4 :ratio 2.0   :level 0.5 :env :adsr}
                  {:id 5 :ratio 1.0   :level 0.4
                   :env {:attack 0.001 :decay 0.6 :sustain 0.0 :release 2.0}}
                  {:id 6 :ratio 3.0   :level 0.3
                   :env {:attack 0.001 :decay 0.4 :sustain 0.0 :release 1.5}}
                  {:id 7 :ratio 1.0   :level 0.25 :feedback 0.3
                   :env {:attack 0.001 :decay 0.4 :sustain 0.0 :release 1.0}}
                  {:id 8 :ratio 2.0   :level 0.2
                   :env {:attack 0.001 :decay 0.25 :sustain 0.0 :release 0.8}}]
   :fm/carriers  #{1 2 3 4}
   :fm/routing   {5 1, 6 2, 7 3, 8 4}
   :fm/cross-feedback [{:from 1 :to 2 :depth 0.025}
                       {:from 2 :to 1 :depth 0.025}]
   :fm/doc "8-op slowly evolving pad — four carrier voices (ops 1–4) each with a
            dedicated transient modulator (ops 5–8) that decays quickly, pulling
            timbre from rich to pure over the note duration.
            Op 2 is 1.003× ratio — a 3-cent detune above op 1 for natural beating.
            Op 7 uses self-feedback (:feedback 0.3) for a slightly gritty edge.
            Cross-feedback ops 1 ↔ 2 at low depth creates a gentle symbiotic
            modulation between the two detuned partials — the beating is subtly
            inflected by FM coupling, not just amplitude interference.
            Recommended: long attack (2+ s) lets the timbre settle in slowly."})

(def-fm! :8op-dx7-pno
  {:fm/args     {:freq 440 :amp 0.7 :attack 0.001 :decay 1.8 :sustain 0.0 :release 2.5}
   :fm/operators [{:id 1 :ratio 1.0  :level 1.0  :env :adsr}
                  {:id 2 :ratio 14.0 :level 0.72
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 1.2}}
                  {:id 3 :ratio 1.0  :level 0.85 :env :adsr}
                  {:id 4 :ratio 14.0 :level 0.55
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 1.0}}
                  {:id 5 :ratio 1.0  :level 0.65 :env :adsr}
                  {:id 6 :ratio 14.0 :level 0.42
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 0.8}}
                  {:id 7 :ratio 1.0  :level 0.45 :env :adsr}
                  {:id 8 :ratio 14.0 :level 0.3
                   :env {:attack 0.001 :decay 0.001 :sustain 0.0 :release 0.6}}]
   :fm/carriers  #{1 3 5 7}
   :fm/routing   {2 1, 4 3, 6 5, 8 7}
   :fm/doc "DX7-style electric piano with 8 operators — four carrier+modulator pairs.
            High-ratio modulators (14×) produce the characteristic transient click and
            bell-like attack. Each pair has a slightly lower carrier level than the
            last, creating a natural spectral roll-off: op1/op2 is the loudest voice,
            op7/op8 is a quiet upper harmonic layer.
            The 14× ratio is the classic DX7 E-piano ratio — just sharp of the 14th
            partial, giving a slightly 'off' harmonic that catches the ear.
            Compared to :dx-ep (4-op), this version adds two more harmonic layers for
            a richer, more complex attack transient and slower decay tail."})
