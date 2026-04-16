; SPDX-License-Identifier: EPL-2.0
(ns cljseq.journey
  "Journey composition conductor — Phaedra/Rubycon structural arc support.

  A 'journey' composition in the Berlin School tradition is not a sequence of
  sections but a trajectory field: multiple independent parameters moving on
  different arcs at different rates, with the structural arc emerging from their
  interaction rather than from deliberate section markers.

  This namespace provides:

    1. A global bar counter — the shared timeline that all loops can query
    2. A conductor loop — fires trajectory transitions at scheduled bar positions
    3. Phase-pair analysis — LCM and alignment events for incommensurate loop pairs
    4. Humanise — micro-timing and velocity variance for analogue sequencer feel
    5. Phaedra arc — a canonical four-movement timeline scaffold

  ## The Phaedra form — four movements as parameter thresholds

  Movement 1 (EMERGENCE):   generative field at low probability; drone; filter closed
  Movement 2 (CRYSTALLISE): ostinato condenses; pattern stabilises; filter opening
  Movement 3 (DEVELOPMENT): random-walk drift; multiple voices; texture deepening
  Movement 4 (DISSOLUTION): pattern fragments; strip-down; filter closes; silence

  ## Quick start

    (require '[cljseq.journey :as journey])

    ;; Start the global bar counter (ticks every 4 beats)
    (journey/start-bar-counter!)

    ;; Define a journey timeline
    (journey/start-journey!
      [[0   #(my-emergence!)]
       [32  #(my-crystallise!)]
       [64  #(my-development!)]
       [128 #(my-dissolution!)]])

    ;; Query phase alignment between two incommensurate loops
    (journey/phase-pair 12 17)
    ;; => {:lcm 204 :alignment-beats [204 408 612 816 1020]
    ;;     :drift-rate 0.0294 :at-bpm {:bpm 100 :minutes 2.04}}"
  (:require [cljseq.core :as core]
            [cljseq.loop :as loop-ns]))

;; ---------------------------------------------------------------------------
;; 1. Global bar counter
;; ---------------------------------------------------------------------------

(defonce ^:private bar-counter (atom {:bar 0 :running false}))

(defn current-bar
  "Return the current global bar number.

  Starts at 0 when start-bar-counter! is called. All journey loops and
  conductor transitions should query this to locate themselves in the arc."
  []
  (:bar @bar-counter))

(defn start-bar-counter!
  "Start the global bar counter. Increments by 1 every `beats-per-bar` beats.

  The counter is the shared timeline that conductor loops and trajectory triggers
  query. Reset the count with reset-bar-counter! without stopping; stop with
  stop-bar-counter!.

  `beats-per-bar` — number of beats per bar.  When omitted, defaults to the
  value set via (core/set-beats-per-bar!) or the :beats-per-bar arg to
  (core/start!), which itself defaults to 4.  Pass explicitly to override for
  this counter only (e.g. 3 for waltz time) without changing the global setting.

  Example:
    (journey/start-bar-counter!)       ; uses core/get-beats-per-bar (default 4)
    (journey/start-bar-counter! 3)     ; 3/4 for this counter only"
  ([] (start-bar-counter! (core/get-beats-per-bar)))
  ([beats-per-bar]
   (swap! bar-counter assoc :running true :bar 0)
   (loop-ns/deflive-loop :journey/bar-counter {}
     (when (:running @bar-counter)
       (swap! bar-counter update :bar inc))
     (loop-ns/sleep! beats-per-bar))))

(defn reset-bar-counter!
  "Reset the bar counter to 0 without stopping it."
  []
  (swap! bar-counter assoc :bar 0))

(defn stop-bar-counter!
  "Stop the bar counter loop."
  []
  (swap! bar-counter assoc :running false)
  (loop-ns/stop-loop! :journey/bar-counter))

;; ---------------------------------------------------------------------------
;; 2. Journey conductor
;; ---------------------------------------------------------------------------

(defn start-journey!
  "Start a journey conductor that fires transition functions at scheduled bars.

  `timeline` — vector of [bar-number transition-fn] pairs. Each transition-fn
               is called with no arguments exactly once when that bar is reached
               or passed.

  The conductor polls bar position every 4 beats. Transitions at or before
  the current bar that have not yet fired are dispatched in bar order.

  `opts`:
    :name   — conductor loop name (default :journey/conductor)
    :on-end — fn called with no args after the last transition fires

  Returns the loop name keyword.

  Example:
    (journey/start-journey!
      [[0   #(begin-emergence!)]
       [32  #(open-filter!)]
       [64  #(crystallise-ostinato!)]
       [128 #(begin-dissolution!)]]
      {:on-end #(println \"Journey complete\")})"
  ([timeline] (start-journey! timeline {}))
  ([timeline {:keys [name on-end] :or {name :journey/conductor}}]
   (let [remaining (atom (vec (sort-by first timeline)))]
     (loop-ns/deflive-loop name {}
       (let [bar (current-bar)
             due (take-while (fn [[t _]] (<= t bar)) @remaining)]
         (doseq [[_ f] due]
           (try (f)
                (catch Exception e
                  (println "Journey transition error:" (.getMessage e)))))
         (swap! remaining (fn [r] (vec (drop (count due) r))))
         (when (and (empty? @remaining) on-end)
           (on-end)
           (loop-ns/stop-loop! name)))
       (loop-ns/sleep! 4))
     name)))

(defn stop-journey!
  "Stop the journey conductor loop.

  With no arguments stops the default :journey/conductor loop; pass a name
  to stop a named conductor started with the :name option."
  ([] (stop-journey! :journey/conductor))
  ([name] (loop-ns/stop-loop! name)))

;; ---------------------------------------------------------------------------
;; 3. Phase-pair analysis — incommensurate loop relationships
;; ---------------------------------------------------------------------------

(defn- gcd [a b]
  (if (zero? b) a (recur b (mod a b))))

(defn phase-pair
  "Analyse the phase relationship between two loops of different lengths.

  Returns a map:
    :lcm             — beats before the patterns exactly re-align
    :alignment-beats — first N future alignment beat positions
    :drift-rate      — fractional phase change per beat
    :at-bpm          — map of :bpm and :minutes for one full alignment cycle

  `length-a`, `length-b` — loop lengths in beats (integers or ratios).

  `opts`:
    :bpm           — tempo for minute calculation (default 100)
    :n-alignments  — how many alignment positions to list (default 5)

  Classic Rubycon-style pairs:
    (phase-pair 12 17)   ; LCM 204  ≈ 2.0 min at 100 BPM
    (phase-pair 16 23)   ; LCM 368  ≈ 3.7 min
    (phase-pair 12 19)   ; LCM 228  ≈ 2.3 min
    (phase-pair 17 23)   ; LCM 391  ≈ 3.9 min

  Example:
    (journey/phase-pair 12 17)
    ;; => {:lcm 204
    ;;     :alignment-beats [204 408 612 816 1020]
    ;;     :drift-rate 0.02941...
    ;;     :at-bpm {:bpm 100 :minutes 2.04}}"
  ([length-a length-b] (phase-pair length-a length-b {}))
  ([length-a length-b {:keys [bpm n-alignments] :or {bpm 100 n-alignments 5}}]
   (let [lcm-val (/ (* length-a length-b) (gcd length-a length-b))
         shorter (min length-a length-b)
         drift   (/ (Math/abs (- (/ 1.0 length-a) (/ 1.0 length-b)))
                    (/ 1.0 shorter))]
     {:lcm             lcm-val
      :alignment-beats (mapv #(* % lcm-val) (range 1 (inc n-alignments)))
      :drift-rate      drift
      :at-bpm          {:bpm     bpm
                        :minutes (double (/ (* lcm-val (/ 60.0 bpm)) 60.0))}})))

;; ---------------------------------------------------------------------------
;; 4. Humanise — analogue sequencer micro-timing and velocity variance
;; ---------------------------------------------------------------------------

(defn humanise
  "Apply micro-timing and velocity variance to a step map — analogue sequencer feel.

  Classic hardware step sequencers (Moog 960, ARP 1613) have inherent CV timing
  imperfections that give patterns a subtle human quality distinct from MIDI
  grid quantisation. This models that as stochastic offsets on timing and velocity.

  Returns a new step map with :velocity adjusted and :timing/offset-ms set when
  a timing offset is applied. The :timing/offset-ms key is consumed by the
  play! tuning pipeline.

  `step` — step map with :pitch/midi, :dur/beats, :velocity keys.
  `opts`:
    :timing-variance-ms  — max timing offset in milliseconds (default 6)
    :velocity-variance   — max velocity offset ±N (default 8)
    :timing-bias         — directional bias: :late, :early, or :none (default :none)

  Example:
    (deflive-loop :ost-humanised {}
      (play! (journey/humanise step {:timing-variance-ms 8 :velocity-variance 10}))
      (sleep! 1/4))"
  ([step] (humanise step {}))
  ([step {:keys [timing-variance-ms velocity-variance timing-bias]
          :or   {timing-variance-ms 6 velocity-variance 8 timing-bias :none}}]
   (let [t-raw     (* timing-variance-ms (- (rand) 0.5) 2.0)
         t-offset  (case timing-bias
                     :late  (Math/abs t-raw)
                     :early (- (Math/abs t-raw))
                     t-raw)
         v-delta   (long (Math/round (* velocity-variance (- (rand) 0.5) 2.0)))
         new-vel   (max 1 (min 127 (+ (:velocity step 64) v-delta)))]
     (cond-> (assoc step :velocity new-vel)
       (not (zero? t-offset))
       (assoc :timing/offset-ms t-offset)))))

;; ---------------------------------------------------------------------------
;; 5. Phaedra arc — canonical four-movement timeline scaffold
;; ---------------------------------------------------------------------------

(defn phaedra-arc
  "Return a Phaedra-style timeline scaffold for a ~20-minute journey at 100 BPM.

  Returns a map:
    :timeline    — vector of [bar :keyword] pairs; replace :keyword with your fns
    :movements   — named bar ranges {:emergence [0 64] ...}
    :phase-pairs — suggested incommensurate loop length pairs for Movement 3

  The timeline contains canonical boundary events. Replace the keyword placeholders
  with transition functions before passing to start-journey!.

  Example:
    (let [arc   (journey/phaedra-arc)
          tl    (:timeline arc)
          tl+fn (mapv (fn [[bar kw]]
                        [bar (get my-transitions kw #(println kw))])
                      tl)]
      (journey/start-journey! tl+fn))"
  []
  {:timeline
   [[0   :begin-emergence]
    [32  :open-filter-1]
    [48  :crystallise]
    [64  :enter-development]
    [80  :deepen-filter]
    [128 :begin-drift]
    [192 :echo-throw-1]
    [208 :echo-throw-2]
    [240 :begin-dissolution]
    [272 :close-filter]
    [304 :fade-texture]
    [320 :silence]]

   :movements
   {:emergence   [0   64]
    :crystallise [48  80]
    :development [64  240]
    :dissolution [240 320]}

   :phase-pairs
   [{:a 12 :b 17 :comment "LCM 204 ≈ 2.0 min — tightest Rubycon feel"}
    {:a 16 :b 23 :comment "LCM 368 ≈ 3.7 min — slower drift, spacious"}
    {:a 12 :b 19 :comment "LCM 228 ≈ 2.3 min — asymmetric feel"}
    {:a 17 :b 23 :comment "LCM 391 ≈ 3.9 min — near-Rubycon, wide orbit"}]})
