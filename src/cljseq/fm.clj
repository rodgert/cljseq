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
    :fm/routing   {}}})

(defn fm-algorithm
  "Return a named algorithm template map. Customize :fm/operators to
  set specific ratios, levels, and envelopes.

  Available templates: :2op :4op-stack :2pairs :3-to-1 :additive-4"
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

(defn- op-modulation-str
  "Build the modulation input string for an op: sum of its modulator op vars."
  [op-id routing]
  (let [mods (keep (fn [[m t]] (when (= t op-id) m)) routing)]
    (when (seq mods)
      (str " + " (str/join " + " (map #(str "op_" %) mods))))))

(defn- fm->sc-str
  "Generate a sclang SynthDef string from an FM definition map."
  [fm-def]
  (let [{:fm/keys [name operators carriers routing args]} fm-def
        carriers   (set (or carriers #{}))
        routing    (or routing {})
        args       (merge {:freq 440 :amp 0.5 :attack 0.01 :decay 0.3
                           :sustain 0.5 :release 1.0}
                          args)
        op-map     (into {} (map (fn [op] [(:id op) op]) operators))
        sorted-ids (topo-sort (map :id operators) routing)

        ;; sclang arg list
        args-str   (str "|"
                        (str/join ", "
                                  (map (fn [[k v]] (str (sc-name k) "=" (double v)))
                                       (merge {:gate 1} args)))
                        "|")

        ;; Generate env + op var declarations in topo order
        var-lines
        (mapcat
          (fn [id]
            (let [op          (get op-map id)
                  carrier?    (contains? carriers id)
                  env-str     (env->sc-str (:env op) "gate" carrier?)
                  mod-str     (op-modulation-str id routing)
                  ratio-str   (str (double (:ratio op 1.0)))
                  level-str   (str (double (:level op 1.0)))]
              [(str "    var env_" id " = " env-str ";")
               (str "    var op_"  id " = SinOsc.ar(freq * " ratio-str
                    (or mod-str "") ") * " level-str " * env_" id ";")]))
          sorted-ids)

        ;; Mix carriers
        carrier-ids (filter #(contains? carriers %) sorted-ids)
        mix-str     (if (= 1 (count carrier-ids))
                      (str "op_" (first carrier-ids))
                      (str "Mix.ar([" (str/join ", " (map #(str "op_" %) carrier-ids)) "])"))

        synth-name  (sc-name (or name :fm_synth))]

    (str "SynthDef(\\" synth-name ", {\n"
         "    " args-str "\n"
         (str/join "\n" var-lines) "\n"
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
