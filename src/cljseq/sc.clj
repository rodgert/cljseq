; SPDX-License-Identifier: EPL-2.0
(ns cljseq.sc
  "SuperCollider integration — sclang code generation and scsynth node control.

  ## Architecture

  cljseq talks to SC on two ports:
    sclang  (default 57120) — receives sclang code strings for evaluation
    scsynth (default 57110) — receives OSC messages for node control

  SynthDef lifecycle:
    1. `compile-synth :sc name` — generate sclang SynthDef string from graph
    2. `send-synthdef! name`    — compile + send to sclang; loads def into scsynth
    3. `sc-synth! name args`   — instantiate a running node → returns node-id
    4. `set-param! id k v`     — update a running node's parameter live
    5. `free-synth! id`        — release the node (triggers gate=0 envelope tail)

  ## Connection

    (sc/connect-sc!)                            ; defaults: localhost 57110 57120
    (sc/connect-sc! :host \"192.168.1.5\")       ; remote SC
    (sc/sc-connected?)                          ; => true

  ## Defining and loading a synth

    (sc/send-synthdef! :blade)                  ; compile + add to server

  ## Playing notes

    (sc/sc-synth! :blade {:freq 440 :amp 0.6}) ; => node-id 1000
    (sc/set-param! 1000 :cutoff 60)            ; live update
    (sc/free-synth! 1000)                      ; release

  ## Compile to string (inspect / paste to SC IDE)

    (sc/synthdef-str :prophet)
    ;=> \"SynthDef(\\\\prophet, { |freq=440.0, ..., gate=1| ... }).add;\"

  ## Generating sclang from a custom synth

    (require '[cljseq.synth :as synth])
    (synth/defsynth! :my-pad
      {:args  {:freq 440 :amp 0.5 :attack 0.5 :release 2.0}
       :graph [:out 0 [:pan2 [:* [:env-gen [:adsr :attack 0.1 0.8 :release] :gate :amp]
                                  [:sin-osc :freq]] 0.0]]})
    (sc/send-synthdef! :my-pad)"
  (:require [clojure.string  :as str]
            [cljseq.clock    :as clock]
            [cljseq.core     :as core]
            [cljseq.osc      :as osc]
            [cljseq.patch    :as patch]
            [cljseq.synth    :as synth]))

;; ---------------------------------------------------------------------------
;; Connection state
;; ---------------------------------------------------------------------------

(defonce ^:private sc-state
  (atom {:host      "127.0.0.1"
         :sc-port   57110    ; scsynth
         :lang-port 57120    ; sclang
         :connected false
         :next-node (atom 1000)}))

;; Track which SynthDefs have been sent to sclang in this session.
;; Reset on connect/disconnect so a fresh SC server gets all defs re-sent.
(defonce ^:private sent-synthdefs (atom #{}))

;; Client-side bus allocation tracking.
;; SC audio buses 0–7 are hardware I/O by default; internal buses start at 8.
;; Control buses start at 0. Indices are allocated monotonically per session.
(defonce ^:private bus-alloc
  (atom {:audio-next   8
         :control-next 0
         :named        {}}))

(declare sc-connected? send-synthdef!)

(defn connect-sc!
  "Configure the SC connection parameters.

  Options:
    :host      — hostname or IP (default \"127.0.0.1\")
    :sc-port   — scsynth OSC port (default 57110)
    :lang-port — sclang OSC port (default 57120)

  Does not open a socket — cljseq.osc/osc-send! creates transient sockets.
  Call this before send-synthdef! or sc-synth!."
  [& {:keys [host sc-port lang-port]
      :or   {host "127.0.0.1" sc-port 57110 lang-port 57120}}]
  (swap! sc-state assoc
         :host host
         :sc-port sc-port
         :lang-port lang-port
         :connected true)
  (reset! sent-synthdefs #{})
  (reset! bus-alloc {:audio-next 8 :control-next 0 :named {}})
  (println (str "[sc] connected to " host " (scsynth:" sc-port " sclang:" lang-port ")"))
  nil)

(defn disconnect-sc!
  "Mark the SC connection as inactive."
  []
  (swap! sc-state assoc :connected false)
  (reset! sent-synthdefs #{})
  (reset! bus-alloc {:audio-next 8 :control-next 0 :named {}})
  (println "[sc] disconnected")
  nil)

(defn ensure-synthdef!
  "Send a named SynthDef to sclang if it has not already been sent this session.
  No-op when SC is not connected or the def is already resident.

  Called automatically by play! when routing to SC. Safe to call manually
  at session start to pre-load defs before the first note.

  Example:
    (sc/ensure-synthdef! :blade)"
  [synth-name]
  (when (and (sc-connected?) (not (contains? @sent-synthdefs synth-name)))
    (send-synthdef! synth-name)
    (swap! sent-synthdefs conj synth-name))
  nil)

(defn sc-connected?
  "Return true if connect-sc! has been called."
  []
  (:connected @sc-state))

(defn- sc-host    [] (:host      @sc-state))
(defn- sc-port    [] (:sc-port   @sc-state))
(defn- lang-port  [] (:lang-port @sc-state))
(defn- next-node! []
  (let [id-atom (:next-node @sc-state)]
    (swap! id-atom inc)))

;; ---------------------------------------------------------------------------
;; sclang code generator
;; ---------------------------------------------------------------------------

;; UGen name → sclang class name
(def ^:private ugen-class
  {:sin-osc    "SinOsc"
   :saw        "Saw"
   :pulse      "Pulse"
   :var-saw    "VarSaw"
   :lf-tri     "LFTri"
   :lf-saw     "LFSaw"
   :lf-pulse   "LFPulse"
   :white-noise "WhiteNoise"
   :pink-noise  "PinkNoise"
   :brown-noise "BrownNoise"
   :lpf        "LPF"
   :hpf        "HPF"
   :rlpf       "RLPF"
   :bpf        "BPF"
   :moog-ff    "MoogFF"
   :env-gen    "EnvGen"
   :free-verb  "FreeVerb"
   :free-verb2 "FreeVerb2"
   :comb-n     "CombN"
   :comb-l     "CombL"
   :allpass-n  "AllpassN"
   :allpass-l  "AllpassL"
   :delay-n    "DelayN"
   :delay-l    "DelayL"
   :pan2       "Pan2"
   :balance2   "Balance2"
   :x-fade2    "XFade2"
   :limiter    "Limiter"
   :compander  "Compander"
   :clip       "Clip"
   :lag        "Lag"
   :lag2       "Lag2"
   :slew       "Slew"
   :in         "In"          ; In.ar(bus, numChannels) — read from audio bus
   :in-feedback "InFeedback" ; InFeedback.ar — feedback loop read
   :buf-rd     "BufRd"       ; BufRd.ar — buffer reader
   :play-buf   "PlayBuf"     ; PlayBuf.ar — one-shot / looping buffer player
   :t-grains   "TGrains"     ; TGrains.ar — triggered granular synthesis
   :grain-sin  "GrainSin"    ; GrainSin.ar — sinusoidal granular
   :grain-fm   "GrainFM"     ; GrainFM.ar — FM granular
   :b-alloc    "Buffer"      ; Buffer.alloc (used in sclang code gen)
   :shaper     "Shaper"      ; Shaper.ar — waveshaping
   :dyn-klank  "DynKlank"    ; DynKlank.ar — resonant filter bank
   :comb-c     "CombC"       ; CombC.ar — cubic interpolating comb
   :lorenz     "Lorenz"      ; Lorenz.ar — Lorenz chaos UGen
   :henon      "Henon"})

(defn- sc-arg-name
  "Convert a Clojure keyword to a valid sclang argument name.
  Hyphens become underscores: :cutoff-attack → \"cutoff_attack\"."
  [k]
  (str/replace (name k) "-" "_"))

;; Envelope shape constructors
(def ^:private env-class
  {:adsr  "Env.adsr"
   :perc  "Env.perc"
   :linen "Env.linen"
   :asr   "Env.asr"
   :dadsr "Env.dadsr"})

(defn- emit-node
  "Recursively emit a sclang expression for a graph node."
  [node]
  (cond
    ;; Argument reference — hyphens → underscores for valid sclang identifiers
    (keyword? node)
    (sc-arg-name node)

    ;; Numeric literal
    (number? node)
    (str (double node))

    ;; Vector: UGen call
    (vector? node)
    (let [[op & args] node]
      (cond
        ;; Output bus
        (= :out op)
        (str "Out.ar(" (emit-node (first args)) ", " (emit-node (second args)) ")")

        ;; Math operators — infix
        (#{:* :+ :- :/} op)
        (str "(" (emit-node (first args)) " " (name op) " " (emit-node (second args)) ")")

        ;; Mix — takes a vector of signals
        (= :mix op)
        (let [sigs (first args)]
          (str "Mix.ar([" (str/join ", " (map emit-node sigs)) "])"))

        ;; Envelope shapes
        (contains? env-class op)
        (str (env-class op) "(" (str/join ", " (map emit-node args)) ")")

        ;; EnvGen — append doneAction:2 automatically
        (= :env-gen op)
        (let [[env gate & rest-args] args
              base (str "EnvGen.ar(" (emit-node env) ", " (emit-node (or gate :gate)))]
          (if (seq rest-args)
            (str base ", " (str/join ", " (map emit-node rest-args)) ", doneAction: 2)")
            (str base ", doneAction: 2)")))

        ;; Clip — method call style
        (= :clip op)
        (str (emit-node (first args))
             ".clip(" (emit-node (second args)) ", " (emit-node (nth args 2)) ")")

        ;; Lag, Lag2, Slew — method call style
        (#{:lag :lag2 :slew} op)
        (str (emit-node (first args)) "." (name op) "(" (emit-node (second args)) ")")

        ;; BufFrames — initialization rate (buffer size is static after alloc)
        (= :buf-frames op)
        (str "BufFrames.ir(" (emit-node (first args)) ")")

        ;; Default: UGenClass.ar(args...)
        :else
        (let [class-name (or (ugen-class op)
                             ;; Fallback: PascalCase the keyword
                             (str/join "" (map str/capitalize
                                              (str/split (name op) #"-"))))]
          (str class-name ".ar(" (str/join ", " (map emit-node args)) ")"))))

    :else
    (str node)))

(defn- arg-list-str
  "Build the sclang arg list string: |freq=440.0, amp=0.5, gate=1|"
  [args-map]
  (let [;; Always include gate for envelope release
        with-gate (merge {:gate 1} args-map)
        pairs     (map (fn [[k v]]
                         (str (sc-arg-name k) "=" (double (or v 0))))
                       with-gate)]
    (str "|" (str/join ", " pairs) "|")))

(defn synthdef-str
  "Generate a sclang SynthDef string for a registered synth.

  Returns a string suitable for evaluating in the SC IDE or sending
  to sclang via /cmd.

  Example:
    (sc/synthdef-str :blade)
    ;=> \"SynthDef(\\\\blade, {\\n  |freq=440.0, ...|\\n  ...\\n}).add;\""
  [synth-name]
  (let [synth (synth/get-synth synth-name)]
    (when-not synth
      (throw (ex-info "synthdef-str: synth not found" {:name synth-name})))
    (let [sc-name   (-> (name synth-name)
                        (str/replace #"[^a-zA-Z0-9_]" "_"))
          args-str  (arg-list-str (:args synth))
          graph-str (emit-node (:graph synth))]
      (str "SynthDef(\\" sc-name ", {\n"
           "    " args-str "\n"
           "    " graph-str "\n"
           "}).add;"))))

;; Register :sc backend with the compile-synth multimethod
(defmethod synth/compile-synth :sc [_ synth-name]
  (synthdef-str synth-name))

;; ---------------------------------------------------------------------------
;; Send SynthDef to sclang
;; ---------------------------------------------------------------------------

(defn send-synthdef!
  "Compile a registered synth to sclang and send it to the sclang interpreter.

  Requires connect-sc! to have been called.

  The sclang process evaluates the SynthDef and adds it to scsynth,
  making it available for sc-synth! calls.

  Example:
    (sc/connect-sc!)
    (sc/send-synthdef! :blade)"
  [synth-name]
  (when-not (sc-connected?)
    (throw (ex-info "send-synthdef!: not connected — call (sc/connect-sc!) first"
                    {:synth synth-name})))
  (let [code (synthdef-str synth-name)]
    (osc/osc-send! (sc-host) (lang-port) "/cmd" code)
    (println (str "[sc] sent SynthDef " synth-name " to sclang"))
    nil))

(defn send-all-synthdefs!
  "Send all registered synths to sclang. Useful at session start."
  []
  (doseq [name (synth/synth-names)]
    (send-synthdef! name)))

;; ---------------------------------------------------------------------------
;; Node lifecycle — talk directly to scsynth
;; ---------------------------------------------------------------------------

(defn sc-synth!
  "Instantiate a synth on scsynth. Returns the node ID.

  `args-map` overrides the synth's default arg values.

  The node is created at the tail of the default group (group 1).
  It will free itself when its envelope completes (doneAction: 2).

  Example:
    (sc/sc-synth! :blade {:freq 440 :amp 0.6 :attack 0.1})
    ;=> 1001"
  [synth-name & [args-map]]
  (when-not (sc-connected?)
    (throw (ex-info "sc-synth!: not connected" {:synth synth-name})))
  (let [synth    (synth/get-synth synth-name)
        _        (when-not synth
                   (throw (ex-info "sc-synth!: synth not found" {:name synth-name})))
        node-id  (next-node!)
        defaults (:args synth)
        merged   (merge defaults args-map)
        ;; /s_new args: name, id, addAction(1=tail), targetGroup(1), [k v ...]
        sc-name  (-> (name synth-name)
                     (str/replace #"[^a-zA-Z0-9_]" "_"))
        flat-args (into [] (mapcat (fn [[k v]] [(sc-arg-name k) (double v)]) merged))]
    (apply osc/osc-send! (sc-host) (sc-port) "/s_new"
           sc-name (int node-id) (int 1) (int 1)
           flat-args)
    node-id))

(defn set-param!
  "Update a parameter on a running scsynth node.

  Example:
    (sc/set-param! 1001 :cutoff 60)"
  [node-id param value]
  (when-not (sc-connected?)
    (throw (ex-info "set-param!: not connected" {:node node-id})))
  (osc/osc-send! (sc-host) (sc-port) "/n_set"
                 (int node-id) (sc-arg-name param) (double value))
  nil)

(defn free-synth!
  "Release a running node by setting gate=0, allowing the envelope tail.

  Example:
    (sc/free-synth! 1001)"
  [node-id]
  (when-not (sc-connected?)
    (throw (ex-info "free-synth!: not connected" {:node node-id})))
  (osc/osc-send! (sc-host) (sc-port) "/n_set"
                 (int node-id) "gate" (double 0))
  nil)

(defn kill-synth!
  "Immediately remove a node from scsynth (no envelope tail).

  Use free-synth! for graceful release. Use this for stuck notes."
  [node-id]
  (when-not (sc-connected?)
    (throw (ex-info "kill-synth!: not connected" {:node node-id})))
  (osc/osc-send! (sc-host) (sc-port) "/n_free" (int node-id))
  nil)

;; ---------------------------------------------------------------------------
;; Convenience — play a note with automatic release
;; ---------------------------------------------------------------------------

(defn sc-play!
  "Play a note on SC and schedule release after `dur-ms` milliseconds.

  `event` keys:
    :synth      — synth name keyword (required)
    :freq       — frequency in Hz (or :pitch/midi → converted)
    :amp        — amplitude 0–1
    :dur-ms     — note duration in milliseconds (default 500)
    any other key matching a synth arg is passed through.

  Returns node-id.

  Example:
    (sc/sc-play! {:synth :blade :freq 440 :amp 0.5 :dur-ms 1000})"
  [event]
  (let [synth-name (:synth event)
        dur-ms     (or (:dur-ms event) 500)
        midi       (:pitch/midi event)
        freq       (or (:freq event)
                       (when midi (* 440.0 (Math/pow 2.0 (/ (- midi 69) 12.0)))))
        args       (-> (dissoc event :synth :dur-ms :pitch/midi)
                       (cond-> freq (assoc :freq freq)))
        node-id    (sc-synth! synth-name args)]
    (doto (Thread. (fn []
                     (try
                       (Thread/sleep (long dur-ms))
                       (free-synth! node-id)
                       (catch Exception _))))
      (.setDaemon true)
      (.start))
    node-id))

;; ---------------------------------------------------------------------------
;; apply-trajectory! — SC convenience form (3-arity)
;; ---------------------------------------------------------------------------

(defn apply-trajectory!
  "Apply an ITemporalValue trajectory to a running SC node parameter.

  Calls `set-param!` on `node-id` at ~50ms intervals until the trajectory
  completes. Stops automatically when the trajectory returns ##Inf from
  next-edge, or when the returned cancel function is called.

  Returns a zero-argument cancel function.

  Example:
    (let [node (sc-synth! :blade {:freq 440 :amp 0.5})]
      (apply-trajectory! node :cutoff
        (traj/trajectory :from 0.2 :to 0.9 :beats 16 :curve :s-curve
                         :start (now))))"
  [node-id param traj]
  (core/apply-trajectory!
    (fn [v] (when (sc-connected?) (set-param! node-id param v)))
    traj))

;; ---------------------------------------------------------------------------
;; core/play! dispatch — register SC backend at namespace load time
;; ---------------------------------------------------------------------------

(defn- midi->freq [midi]
  (* 440.0 (Math/pow 2.0 (/ (- (double midi) 69.0) 12.0))))

(defn- sc-play-dispatch!
  "Handle a :synth step map routed from core/play!.
  Signature matches what core/play! passes: [note dur bpm]."
  [note dur bpm]
  (when (sc-connected?)
    (let [beats  (or (:dur/beats note) dur 1/4)
          midi   (:pitch/midi note)
          freq   (when midi (midi->freq midi))
          dur-ms (long (clock/beats->ms (double beats) (double bpm)))
          event  (cond-> (dissoc note :dur/beats :pitch/midi)
                   freq (assoc :freq freq)
                   true (assoc :dur-ms dur-ms))]
      (ensure-synthdef! (:synth note))
      (sc-play! event))))

;; Register with core when sc is loaded — no circular dep because core
;; stores only the fn reference, not a compile-time require of cljseq.sc.
(core/register-sc-dispatch! sc-play-dispatch!)

;; ---------------------------------------------------------------------------
;; Session helpers
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Buffer allocation — client-side ID tracking
;; ---------------------------------------------------------------------------

(defonce ^:private next-buffer-id (atom 0))

(defn- alloc-buf-id! []
  (let [id @next-buffer-id]
    (swap! next-buffer-id inc)
    id))

(defn sc-buffer-alloc!
  "Allocate an SC buffer and load an audio file from `path`.

  Sends `/b_allocRead` to scsynth. Returns the buffer ID (integer) which
  can be passed to synths as the `:buf` arg.

  Idempotency is the caller's responsibility — this function always allocates
  a new buffer ID. Use cljseq.sample/load-sample! for path-deduplication.

  Example:
    (sc/connect-sc!)
    (def buf-id (sc/sc-buffer-alloc! \"/samples/kick.wav\"))
    (sc/sc-play! {:synth :buf-player :buf buf-id})"
  [path]
  (when-not (sc-connected?)
    (throw (ex-info "sc-buffer-alloc!: SC not connected" {:path path})))
  (let [buf-id (alloc-buf-id!)]
    (osc/osc-send! (sc-host) (sc-port) "/b_allocRead"
                   (int buf-id) path (int 0) (int 0))
    (println (str "[sc] buffer " buf-id " ← " path))
    buf-id))

(defn sc-buffer-free!
  "Free an SC buffer by ID. Sends `/b_free` to scsynth."
  [buf-id]
  (when (sc-connected?)
    (osc/osc-send! (sc-host) (sc-port) "/b_free" (int buf-id)))
  nil)

;; ---------------------------------------------------------------------------
;; Audio / control bus allocation
;; ---------------------------------------------------------------------------

(defn sc-bus!
  "Allocate a named audio or control bus and record it in the session.

  `bus-key`  — keyword identifier for this bus within the session
  `channels` — number of channels (default 1)
  `rate`     — :audio (default) or :control

  Returns {:idx n :channels n :rate kw}.

  SC buses are index ranges — no OSC message is needed to allocate them.
  Indices are monotonically assigned starting from 8 (audio) or 0 (control).
  Call reset! on bus-alloc or reconnect-sc! to reclaim indices.

  Example:
    (sc/sc-bus! :reverb-send)           ; 1-channel audio bus
    (sc/sc-bus! :mod-lfo 1 :control)    ; 1-channel control bus"
  ([bus-key] (sc-bus! bus-key 1 :audio))
  ([bus-key channels rate]
   (let [next-k (if (= rate :audio) :audio-next :control-next)
         result (atom nil)]
     (swap! bus-alloc
            (fn [s]
              (let [idx (get s next-k)]
                (reset! result {:idx idx :channels channels :rate rate})
                (-> s
                    (update next-k + channels)
                    (assoc-in [:named bus-key] {:idx idx :channels channels :rate rate})))))
     @result)))

(defn free-bus!
  "Remove a named bus from the session tracking.
  Does not reclaim the index — reconnect to reset allocation."
  [bus-key]
  (swap! bus-alloc update :named dissoc bus-key)
  nil)

(defn bus-idx
  "Return the SC bus index allocated for `bus-key`, or nil."
  [bus-key]
  (get-in @bus-alloc [:named bus-key :idx]))

;; ---------------------------------------------------------------------------
;; Patch instantiation — multi-node signal chains
;; ---------------------------------------------------------------------------

(defn- resolve-bus-args
  "Replace bus-key keywords in an args map with their SC integer indices.
  Integer values pass through unchanged."
  [args bus-indices]
  (into {} (map (fn [[k v]]
                  [k (if (and (keyword? v) (contains? bus-indices v))
                       (double (get bus-indices v))
                       v)])
                args)))

(defn instantiate-patch!
  "Instantiate a registered patch on the SC server.

  Allocates audio/control buses, sends SynthDefs as needed, and starts each
  node in declaration order. Returns a patch instance map for use with
  `set-patch-param!` and `free-patch!`.

  Example:
    (sc/connect-sc!)
    (def inst (sc/instantiate-patch! :reverb-chain))
    (sc/set-patch-param! inst :freq 550)
    (sc/free-patch! inst)"
  [patch-name]
  (when-not (sc-connected?)
    (throw (ex-info "instantiate-patch!: SC not connected" {:patch patch-name})))
  (let [compiled    (patch/compile-patch :sc patch-name)
        ;; Allocate all named buses
        bus-indices (into {}
                          (map (fn [[bus-key {:keys [channels rate]
                                              :or   {channels 1 rate :audio}}]]
                                 [bus-key (:idx (sc-bus! bus-key channels rate))])
                               (:buses compiled)))
        ;; Instantiate nodes in order; collect node-id → sc-node-id
        node-ids    (into {}
                          (map (fn [{:keys [id synth args]}]
                                 (ensure-synthdef! synth)
                                 (let [resolved (resolve-bus-args args bus-indices)
                                       node-id  (sc-synth! synth resolved)]
                                   [id node-id]))
                               (:nodes compiled)))]
    {:patch-name (:patch-name compiled)
     :buses      bus-indices
     :nodes      node-ids
     :params     (:params compiled)}))

(defn set-patch-param!
  "Update a named parameter on a running patch instance.

  `instance` — map returned by `instantiate-patch!`
  `param-key` — keyword from the patch's :params map
  `value`     — new value (number)

  Example:
    (sc/set-patch-param! inst :room 0.8)
    (sc/set-patch-param! inst :freq 660)"
  [instance param-key value]
  (let [[node-id arg-key] (get (:params instance) param-key)]
    (when-not node-id
      (throw (ex-info "set-patch-param!: unknown param" {:param param-key})))
    (let [sc-node-id (get (:nodes instance) node-id)]
      (when-not sc-node-id
        (throw (ex-info "set-patch-param!: node not in instance" {:node node-id})))
      (set-param! sc-node-id arg-key value))))

(defn free-patch!
  "Release all nodes in a running patch instance and free their buses.

  Calls `free-synth!` (gate=0) on each node in reverse instantiation order,
  then removes the buses from the session's allocation tracking.

  Example:
    (sc/free-patch! inst)"
  [instance]
  (doseq [[_ sc-node-id] (reverse (:nodes instance))]
    (free-synth! sc-node-id))
  (doseq [bus-key (keys (:buses instance))]
    (free-bus! bus-key))
  nil)

(defn sc-status
  "Return a map of current SC connection state."
  []
  (dissoc @sc-state :next-node))
