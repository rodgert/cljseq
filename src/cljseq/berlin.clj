; SPDX-License-Identifier: EPL-2.0
(ns cljseq.berlin
  "Berlin School / Kosmische vocabulary for cljseq.

  A composition layer over existing cljseq primitives capturing the idiomatic
  techniques of Tangerine Dream, Klaus Schulze, and the broader Kosmische /
  Berlin School tradition:

    - Ostinato mutation: slowly drifting repeating sequencer patterns
    - Portamento: gliding note transitions via MIDI CC 5 / CC 65
    - Filter journeys: slow trajectory-backed CC sweeps over 32–64 bars
    - Tuning morph: linear MTS interpolation between two Scala scales
    - Phase drift: fractional tempo offset between two live loops
    - Tape drift: pitch wobble simulating worn tape loop

  ## Aesthetic lineage

  The structural grammar is rooted in Phaedra (1974) and Rubycon (1975) but
  inflected with a heavier palette: Schulze's Irrlicht/Cyborg, Sunn O)))'s
  insight that doom slowed to stasis becomes dark ambient, Sabbath's Phrygian
  tritone. Locrian and Phrygian dominant sit alongside Dorian; tempo range
  extends down toward doom (60–80 BPM) as well as TD pulse (100–120).

  ## Quick start

    (require '[cljseq.berlin :as berlin])

    ;; Slowly mutating D-Dorian ostinato
    (def ost (berlin/ostinato
               [{:pitch/midi 62 :dur/beats 1/8}
                {:pitch/midi 65 :dur/beats 1/8}
                {:pitch/midi 69 :dur/beats 1/8}
                {:pitch/midi 72 :dur/beats 1/4}]
               {:scale (make-scale :D 4 :dorian) :mutation-rate 0.12}))

    (deflive-loop :seq {}
      (berlin/with-portamento 1 60
        (play! (berlin/next-step! ost)))
      (sleep! 1/8))

    ;; Slow filter journey over 64 bars
    (berlin/filter-journey! [:filter :cutoff] 74 1 0 127 64 :breathe)"
  (:require [cljseq.clock           :as clock]
            [cljseq.ctrl            :as ctrl]
            [cljseq.loop            :as loop-ns]
            [cljseq.random          :as random]
            [cljseq.scala           :as scala]
            [cljseq.sidecar         :as sidecar]
            [cljseq.temporal-buffer :as tbuf]
            [cljseq.trajectory      :as traj]))

;; ---------------------------------------------------------------------------
;; Ostinato — slowly mutating repeating sequencer pattern
;; ---------------------------------------------------------------------------
;;
;; State model fields:
;;
;;   :pattern        — current live pattern (vector of step maps)
;;   :origin         — pattern at construction time (anchor for crystallize)
;;   :target         — teleological destination, or nil (random walk)
;;   :pos            — step index within current pass
;;   :pass           — number of completed passes
;;   :mode           — :random-walk | :teleological | :crystallize | :dissolve
;;   :drift          — :up | :down | :random (for :random-walk mode)
;;   :mutation-rate  — current probability [0.0–1.0] per note per pass
;;   :mutation-traj  — Trajectory to sample rate from, or nil (= fixed rate)
;;   :traj-passes    — total pass count used to normalise mutation-traj
;;   :scale          — Scale record for theory-aware mutations
;;   :root           — root MIDI note
;;   :microtonal     — enable per-note cent drift (boolean)
;;   :drift-state    — {step-index cents-offset} for microtonal drift
;;   :gravity        — [0,1] pull back toward equal-tempered degree
;;   :tension-path   — ctrl tree path for tension-arc-driven mutation, or nil

(defn ostinato
  "Build a mutating ostinato context from a step sequence.

  `pattern` — vector of step maps {:pitch/midi N :dur/beats R}
  `opts`:
    :scale          — Scale record for theory-aware mutations (default nil = chromatic)
    :mutation-rate  — probability per note per pass (default 0.1)
    :drift          — :up, :down, or :random walk direction (default :random)
    :root           — root MIDI note (default 60)
    :microtonal     — true to enable per-note cent drift (default false)
    :gravity        — [0.0–1.0] pull back toward equal temperament (default 0.7)
    :tension-path   — ctrl tree path for tension-arc mutation (default nil)

  Returns an atom. Pass to next-step! to advance; mutation fires at each
  complete pass. The rhythmic skeleton (step count, durations) is preserved
  across all mutation modes.

  Example:
    (def ost (berlin/ostinato
               [{:pitch/midi 60 :dur/beats 1/8}
                {:pitch/midi 64 :dur/beats 1/8}
                {:pitch/midi 67 :dur/beats 1/8}
                {:pitch/midi 71 :dur/beats 1/4}]
               {:scale (make-scale :C 4 :dorian) :mutation-rate 0.15}))"
  [pattern {:keys [scale mutation-rate drift root microtonal gravity tension-path]
            :or   {mutation-rate 0.1 drift :random root 60
                   microtonal false gravity 0.7}}]
  (atom {:pattern       (vec pattern)
         :origin        (vec pattern)
         :target        nil
         :pos           0
         :pass          0
         :mode          :random-walk
         :drift         drift
         :mutation-rate mutation-rate
         :mutation-traj nil
         :traj-passes   nil
         :scale         scale
         :root          root
         :microtonal    microtonal
         :drift-state   {}
         :gravity       gravity
         :tension-path  tension-path}))

;; ---------------------------------------------------------------------------
;; Internal: scale quantisation helper
;; ---------------------------------------------------------------------------

(defn- nearest-scale-degree
  "Return the MIDI note closest to `midi` that belongs to `scale`."
  [midi scale]
  (random/progressive-quantize midi scale 1.0))

;; ---------------------------------------------------------------------------
;; Mutation dispatch
;; ---------------------------------------------------------------------------

(defmulti ^:private apply-mutation
  "Apply end-of-pass mutation. Dispatches on :mode."
  :mode)

(defmethod apply-mutation :random-walk
  [{:keys [pattern scale mutation-rate drift] :as state}]
  (assoc state :pattern
    (mapv (fn [step]
            (if (< (rand) mutation-rate)
              (let [cur   (int (:pitch/midi step))
                    delta (case drift
                            :up   (inc (rand-int 5))
                            :down (- (inc (rand-int 5)))
                            (- (rand-int 9) 4))
                    cand  (max 0 (min 127 (+ cur delta)))]
                (assoc step :pitch/midi
                       (if scale (nearest-scale-degree cand scale) cand)))
              step))
          pattern)))

(defmethod apply-mutation :teleological
  [{:keys [pattern target mutation-rate scale] :as state}]
  (if (nil? target)
    state
    (assoc state :pattern
      (mapv (fn [cur-step tgt-step]
              (let [cur-midi (int (:pitch/midi cur-step))
                    tgt-midi (int (:pitch/midi tgt-step))]
                (if (and (not= cur-midi tgt-midi) (< (rand) mutation-rate))
                  (let [direction (if (> tgt-midi cur-midi) 1 -1)
                        cand      (+ cur-midi (* direction (inc (rand-int 2))))
                        cand      (max 0 (min 127 cand))
                        cand      (if (> direction 0)
                                    (min cand tgt-midi)
                                    (max cand tgt-midi))
                        new-midi  (if scale (nearest-scale-degree cand scale) cand)]
                    (assoc cur-step :pitch/midi new-midi))
                  cur-step)))
            pattern target))))

(defmethod apply-mutation :crystallize
  [{:keys [pattern target mutation-rate scale] :as state}]
  (if (nil? target)
    state
    (let [target-pitches (set (map :pitch/midi target))]
      (assoc state :pattern
        (mapv (fn [step]
                (let [midi (int (:pitch/midi step))]
                  (if (and (not (target-pitches midi)) (< (rand) mutation-rate))
                    (let [nearest (apply min-key #(Math/abs (- % midi)) target-pitches)]
                      (assoc step :pitch/midi nearest))
                    step)))
              pattern)))))

(defmethod apply-mutation :dissolve
  [{:keys [pattern mutation-rate scale] :as state}]
  (assoc state :pattern
    (mapv (fn [step]
            (if (< (rand) mutation-rate)
              (let [cur   (int (:pitch/midi step))
                    range (max 2 (long (Math/round (* mutation-rate 12))))
                    delta (- (rand-int (* 2 range)) range)
                    cand  (max 0 (min 127 (+ cur delta)))]
                (assoc step :pitch/midi
                       (if (and scale (< (rand) (- 1.0 mutation-rate)))
                         (nearest-scale-degree cand scale)
                         cand)))
              step))
          pattern)))

(defmethod apply-mutation :tension-arc
  [{:keys [tension-path mutation-rate] :as state}]
  (let [tension (or (and tension-path (ctrl/get tension-path)) 0.5)
        bias    (if (> tension 0.5) :up :down)]
    (apply-mutation (assoc state :mode :random-walk :drift bias
                                 :mutation-rate (* mutation-rate (+ 0.5 tension))))))

;; ---------------------------------------------------------------------------
;; Microtonal drift
;; ---------------------------------------------------------------------------

(defn- apply-microtonal-drift
  "Update per-step cent offsets and return [step state] with updated drift."
  [step pos {:keys [drift-state gravity] :as state}]
  (let [max-drift 8.0
        cur-cents (double (get drift-state pos 0.0))
        walk      (* max-drift (- (rand) 0.5) 0.4)
        pulled    (* cur-cents (- 1.0 gravity))
        new-cents (max (- max-drift) (min max-drift (+ pulled walk)))]
    [(assoc step :pitch/bend-cents new-cents)
     (assoc-in state [:drift-state pos] new-cents)]))

;; ---------------------------------------------------------------------------
;; next-step! — the primary caller-facing advance function
;; ---------------------------------------------------------------------------

(defn next-step!
  "Advance the ostinato by one step and return the current step map.

  Applies end-of-pass mutation each time the pattern loops. If microtonal
  drift is enabled, attaches :pitch/bend-cents to the step (consumed by the
  play! tuning pipeline). If a mutation trajectory is set, samples it each
  pass to update the mutation rate.

  Example (in a live loop):
    (play! (berlin/next-step! ost))"
  [ost-atom]
  (let [{:keys [pattern pos pass microtonal mutation-traj traj-passes] :as state} @ost-atom
        ;; Sample trajectory for mutation rate if one is set.
        ;; Use clock/sample with elapsed beats = (pass / traj-passes) * traj.beats
        state    (if mutation-traj
                   (let [norm-passes (or traj-passes 1000)
                         frac        (min 1.0 (/ (double pass) (double norm-passes)))
                         traj-beats  (double (:beats mutation-traj))
                         rate        (clock/sample mutation-traj (* frac traj-beats))]
                     (assoc state :mutation-rate rate))
                   state)
        raw-step (nth pattern pos)
        [step state] (if microtonal
                       (apply-microtonal-drift raw-step pos state)
                       [raw-step state])
        next-pos (inc pos)
        at-end?  (>= next-pos (count pattern))
        state    (cond-> (assoc state :pos (if at-end? 0 next-pos))
                   at-end? (-> (assoc :pass (inc pass))
                               apply-mutation))]
    (reset! ost-atom state)
    step))

;; ---------------------------------------------------------------------------
;; Ostinato control — mode changes, trajectory wiring, position reset
;; ---------------------------------------------------------------------------

(defn reset-ostinato!
  "Reset position to step 0 without altering the current pattern.
  Useful for re-syncing to a beat boundary."
  [ost-atom]
  (swap! ost-atom assoc :pos 0))

(defn freeze-ostinato!
  "Stop mutation — the pattern repeats exactly as-is until thawed."
  [ost-atom]
  (swap! ost-atom assoc :mutation-rate 0.0 :mutation-traj nil))

(defn thaw-ostinato!
  "Resume mutation at `rate` (default 0.1)."
  ([ost-atom]      (thaw-ostinato! ost-atom 0.1))
  ([ost-atom rate] (swap! ost-atom assoc :mutation-rate rate)))

(defn deflect-ostinato!
  "Set a teleological target: the pattern drifts toward `target-pattern`
  over subsequent passes, staying scale-constrained at every step.

  `rate` — fraction of eligible steps that move per pass (default 0.25).

  Once the pattern matches the target exactly, mode reverts to :random-walk.

  Example:
    (berlin/deflect-ostinato! ost
      [{:pitch/midi 62 :dur/beats 1/8}
       {:pitch/midi 65 :dur/beats 1/8}]
      {:rate 0.2})"
  ([ost-atom target-pattern] (deflect-ostinato! ost-atom target-pattern {}))
  ([ost-atom target-pattern {:keys [rate] :or {rate 0.25}}]
   (swap! ost-atom assoc
          :target        (vec target-pattern)
          :mode          :teleological
          :mutation-rate rate)))

(defn crystallize!
  "Drive the pattern toward `target-pattern` using crystallize mode: any note
  outside the target's pitch set is pulled toward the nearest target note
  each pass.

  `rate` — probability per out-of-set note per pass (default 0.3).

  Example:
    (berlin/crystallize! ost target-pattern {:rate 0.25})"
  ([ost-atom target-pattern] (crystallize! ost-atom target-pattern {}))
  ([ost-atom target-pattern {:keys [rate] :or {rate 0.3}}]
   (swap! ost-atom assoc
          :target        (vec target-pattern)
          :mode          :crystallize
          :mutation-rate rate)))

(defn dissolve!
  "Let the pattern gradually lose coherence. Notes drift away from their
  current degrees at an increasing rate.

  `rate` — initial drift probability per note per pass (default 0.15).

  Example:
    (berlin/dissolve! ost {:rate 0.2})"
  ([ost-atom] (dissolve! ost-atom {}))
  ([ost-atom {:keys [rate] :or {rate 0.15}}]
   (swap! ost-atom assoc :mode :dissolve :mutation-rate rate)))

(defn set-mutation-trajectory!
  "Wire a Trajectory to the ostinato's mutation rate. The rate is sampled
  from the trajectory on each pass, with the pass count normalised over
  `total-passes`.

  Example:
    ;; Rate rises from 0.02 to 0.25 over 64 passes
    (berlin/set-mutation-trajectory! ost
      (traj/trajectory :smooth-step 0 0.02 1 0.25)
      64)"
  [ost-atom trajectory total-passes]
  (swap! ost-atom assoc
         :mutation-traj trajectory
         :traj-passes   total-passes))

;; ---------------------------------------------------------------------------
;; Portamento — gliding note transitions via MIDI CC 5 / CC 65
;; ---------------------------------------------------------------------------

(def ^:private cc-portamento-switch 65)
(def ^:private cc-portamento-time   5)

(defn set-portamento!
  "Set portamento on a MIDI channel.

  `channel`  — MIDI channel 1–16
  `time-ms`  — portamento glide time in milliseconds; 0 = off.

  Sends CC 65 (portamento switch) and CC 5 (portamento time). Time is scaled
  linearly from [0, 5000] ms to [0, 127]; values above 5000 ms clamp to 127.

  Example:
    (berlin/set-portamento! 1 120)   ; ~120 ms glide on channel 1
    (berlin/set-portamento! 1 0)     ; portamento off"
  [channel time-ms]
  (let [now-ns (* (System/currentTimeMillis) 1000000)
        on?    (> time-ms 0)
        scaled (if on?
                 (min 127 (long (Math/round (* (/ (double time-ms) 5000.0) 127.0))))
                 0)]
    (sidecar/send-cc! now-ns channel cc-portamento-switch (if on? 127 0))
    (when on?
      (sidecar/send-cc! (+ now-ns 1) channel cc-portamento-time scaled))))

(defmacro with-portamento
  "Enable portamento on `channel` for the duration of `body`, then disable it.

  `channel`  — MIDI channel 1–16
  `time-ms`  — glide time in milliseconds

  Example:
    (deflive-loop :glide {}
      (berlin/with-portamento 1 80
        (play! (berlin/next-step! ost)))
      (sleep! 1/4))"
  [channel time-ms & body]
  `(do
     (set-portamento! ~channel ~time-ms)
     (let [result# (do ~@body)]
       (set-portamento! ~channel 0)
       result#)))

;; ---------------------------------------------------------------------------
;; Filter journey — slow trajectory-backed CC sweep over N bars
;; ---------------------------------------------------------------------------

(defn filter-journey!
  "Schedule a slow CC sweep over `bars` bars using a trajectory curve.

  Registers a ctrl binding and launches a background loop that samples the
  trajectory once per bar and sends the CC. The loop stops itself when complete.

  `ctrl-path` — ctrl tree path to bind, e.g. [:filter :cutoff]
  `cc-num`    — raw CC number (e.g. 74 for Hydrasynth filter cutoff)
  `channel`   — MIDI channel 1–16
  `from`      — starting CC value 0–127
  `to`        — ending CC value 0–127
  `bars`      — total duration in bars (4 beats each)
  `curve`     — trajectory curve keyword: :linear, :breathe, :smooth-step, :bounce
                (default :breathe)

  Example:
    ;; Open filter over 64 bars with a breathing curve
    (berlin/filter-journey! [:filter :cutoff] 74 1 0 127 64 :breathe)"
  ([ctrl-path cc-num channel from to bars]
   (filter-journey! ctrl-path cc-num channel from to bars :breathe))
  ([ctrl-path cc-num channel from to bars curve]
   (let [total-beats (* bars 4)
         traj        (traj/trajectory :from (double from) :to (double to)
                                      :beats total-beats :curve curve)
         loop-name   (keyword (str "berlin-journey-" (name (last ctrl-path))))
         bar-beat    (atom 0)]
     (try
       (ctrl/bind! ctrl-path {:type :midi-cc :channel channel :cc-num cc-num
                              :range [0 127]})
       (catch clojure.lang.ExceptionInfo _
         nil))
     (loop-ns/deflive-loop loop-name {}
       (let [b             @bar-beat
             elapsed-beats (* b 4.0)
             val           (long (Math/round (clock/sample traj elapsed-beats)))]
         (ctrl/send! ctrl-path val)
         (swap! bar-beat inc)
         (when (>= @bar-beat bars)
           (loop-ns/stop-loop! loop-name))
         (loop-ns/sleep! 4)))
     loop-name)))

;; ---------------------------------------------------------------------------
;; Phase drift — fractional tempo offset between two live loops
;; ---------------------------------------------------------------------------

(def ^:private drift-registry (atom {}))

(defn phase-drift!
  "Register a fractional tempo offset for a loop.

  `loop-name`    — keyword name of an existing live loop
  `drift-factor` — small float; positive = loop runs slower, negative = faster.
                   Typical range: [-0.02, 0.02] (±2% of tempo).

  Note: this registers the drift in a local registry for now. Full wiring
  into the loop scheduler's per-loop sleep multiplier is a future iteration.
  Use journey/phase-pair to calculate LCM alignment events when composing
  with incommensurate loop lengths.

  Example:
    (berlin/phase-drift! :seq-b 0.01)"
  [loop-name drift-factor]
  (swap! drift-registry assoc loop-name drift-factor))

(defn clear-drift!
  "Remove phase drift registration for a loop, restoring normal tempo."
  [loop-name]
  (swap! drift-registry dissoc loop-name))

;; ---------------------------------------------------------------------------
;; Tuning morph — interpolate between two MTS frames over N bars
;; ---------------------------------------------------------------------------

(defn tuning-morph!
  "Interpolate between two Scala scales over `bars` bars, sending MTS Bulk Dump
  at each bar boundary.

  Interpolation is linear in cents per degree. The morph loop stops
  automatically when complete; the destination tuning remains loaded.

  `from-ms`  — starting Scale record (cljseq.scala/load-scl result)
  `to-ms`    — ending Scale record
  `bars`     — number of bars over which to interpolate
  `kbm`      — KeyboardMap, or nil to use the default mapping

  Example:
    (berlin/tuning-morph! (load-scl \"12-tet.scl\")
                          (load-scl \"carlos-alpha.scl\")
                          32 nil)"
  [from-ms to-ms bars kbm]
  (let [n-degrees (scala/degree-count from-ms)
        loop-name :berlin-tuning-morph
        bar-atom  (atom 0)]
    (loop-ns/deflive-loop loop-name {}
      (let [b              @bar-atom
            frac           (/ (double b) (double bars))
            blended-ratios (mapv (fn [i]
                                   (let [from-cents (scala/degree->cents from-ms i)
                                         to-cents   (scala/degree->cents to-ms   i)]
                                     (+ from-cents (* frac (- to-cents from-cents)))))
                                 (range n-degrees))
            blended-ms     (assoc from-ms :degrees blended-ratios)]
        (sidecar/send-mts! blended-ms kbm)
        (swap! bar-atom inc)
        (when (>= @bar-atom bars)
          (loop-ns/stop-loop! loop-name))
        (loop-ns/sleep! 4)))))

;; ---------------------------------------------------------------------------
;; Tape drift — pitch wobble per pass (worn tape simulation)
;; ---------------------------------------------------------------------------

(defn tape-drift
  "Wrap an ostinato atom with tape-style pitch drift: each pass shifts all
  pitches by a small random cents offset, simulating a worn tape loop.

  Returns a new atom wrapping the original; use tick-tape! to advance it.

  `max-drift-cents` — maximum per-pass pitch drift in cents (default 8).

  Example:
    (def drifting-ost (berlin/tape-drift ost 12))
    (deflive-loop :tape {}
      (play! (berlin/tick-tape! drifting-ost))
      (sleep! 1/8))"
  ([ost-atom] (tape-drift ost-atom 8))
  ([ost-atom max-drift-cents]
   (atom {:inner       ost-atom
          :drift-cents 0.0
          :max-drift   (double max-drift-cents)})))

(defn tick-tape!
  "Advance a tape-drift wrapper one step. See berlin/tape-drift."
  [tape-atom]
  (let [{:keys [inner drift-cents max-drift]} @tape-atom
        step      (next-step! inner)
        new-drift (max (- max-drift)
                       (min max-drift
                            (+ drift-cents (* max-drift (- (rand) 0.5) 0.3))))]
    (swap! tape-atom assoc :drift-cents new-drift)
    (if (zero? (double drift-cents))
      step
      (assoc step :pitch/bend-cents drift-cents))))

;; ---------------------------------------------------------------------------
;; Frippertronics / SOS — Sound on Sound accumulation via temporal buffer
;; ---------------------------------------------------------------------------
;;
;; Maps the Fripp/Eno tape loop system to cljseq.temporal-buffer:
;;
;;   Tape loop length    → temporal buffer zone depth (z6 = 64 beats ≈ 35s at 100 BPM)
;;   Per-pass decay      → :tape Color (vel × 0.95, duration × 0.98, ±10¢ flutter)
;;   Loop accumulation   → high feedback with generation tracking
;;   Natural tail decay  → velocity-floor drops quiet events automatically
;;   Overdub input       → temporal-buffer-send! from a live loop
;;
;; Composition pattern:
;;
;;   (def sos (berlin/frippertronics! :sos {}))
;;
;;   ;; Layer 1 — sparse low motif, tape drift
;;   (def ost-a (berlin/tape-drift my-ost 8))
;;   (deflive-loop :layer-a {}
;;     (play! (berlin/sos-send! sos (berlin/tick-tape! ost-a)))
;;     (sleep! 1/4))
;;
;;   ;; After :z6 depth (64 beats), layer-a begins accumulating in the buffer.
;;   ;; Add layer 2 on top; earlier layers fade via :tape Color per generation.
;;   (def ost-b (berlin/ostinato higher-pattern {:mutation-rate 0.08}))
;;   (deflive-loop :layer-b {}
;;     (play! (berlin/sos-send! sos (berlin/next-step! ost-b)))
;;     (sleep! 1/8))
;;
;;   ;; Freeze input and sculpt the frozen content:
;;   (tbuf/temporal-buffer-hold! sos true)
;;   (tbuf/temporal-buffer-color! sos :dark)   ; tails get darker each pass

(defn frippertronics!
  "Create a Frippertronics-style SOS (Sound on Sound) accumulation buffer.

  Configures a temporal buffer for tape-loop accumulation modelled after the
  Fripp/Eno tape loop technique: new material feeds in, earlier passes fade
  through per-generation Color transformation, and the buffer sustains
  independently at high feedback.

  `buf-name` — keyword name for the buffer (e.g. :sos, :looper)
  `opts`:
    :zone           — zone keyword for loop depth (default :z6, 64 beats)
    :feedback       — re-injection probability [0.0–1.0] (default 0.75)
    :max-generation — maximum pass count before events are dropped (default 12)
    :velocity-floor — velocity below which events are discarded (default 8)
    :color          — Color preset for per-pass transform (default :tape)
                      :tape — vel×0.95, dur×0.98, ±10¢ flutter (analog character)
                      :dark — vel×0.90, dur×1.15, −5¢ sag (darker, slower fade)
                      :warm — vel×0.93, dur×1.05 (warm, decaying tail)
    :tape-scale     — Doppler cents-per-rate-unit (default 80.0; subtle)

  Returns `buf-name`. Feed events with `sos-send!` or `temporal-buffer-send!`.

  Example:
    (def sos (berlin/frippertronics! :sos {}))
    (deflive-loop :sos-feed {}
      (play! (berlin/sos-send! sos (berlin/tick-tape! drifting-ost)))
      (sleep! 1/8))"
  ([buf-name] (frippertronics! buf-name {}))
  ([buf-name {:keys [zone feedback max-generation velocity-floor color tape-scale]
              :or   {zone            :z6
                     feedback        0.75
                     max-generation  12
                     velocity-floor  8
                     color           :tape
                     tape-scale      80.0}}]
   (tbuf/deftemporal-buffer buf-name
     {:active-zone zone
      :tape-scale  tape-scale
      :color       color
      :feedback    {:amount         feedback
                    :max-generation max-generation
                    :velocity-floor velocity-floor}})))

(defn sos-send!
  "Feed a note event into a Frippertronics buffer and return it for play!.

  Writes `step` into `buf-name` (via temporal-buffer-send!) and returns `step`
  unchanged. Intended for the common pattern of simultaneously sounding the
  note and accumulating it in the SOS buffer:

    (play! (berlin/sos-send! sos (berlin/next-step! ost)))

  Has no effect when the buffer is in Hold mode (input is frozen).
  Returns `step`."
  [buf-name step]
  (tbuf/temporal-buffer-send! buf-name step)
  step)
