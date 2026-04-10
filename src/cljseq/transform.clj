; SPDX-License-Identifier: EPL-2.0
(ns cljseq.transform
  "Note transformer protocol and built-in transformers.

  Transformers apply per-event processing in live loops — they sit between
  note generation and `play!`. Each transformer implements `ITransformer`
  and returns a seq of `{:event map :delay-beats number}` results. Multiple
  results produce multiple sounds (harmonies, echoes, arpeggios). An empty
  seq silently drops the event.

  ## Quick start

    ;; Single transformer
    (def my-echo (echo {:repeats 3 :decay 0.7 :delay-beats 1/4}))

    (deflive-loop :melody {}
      (when-let [note (pick-step ctx profile)]
        (play-transformed! my-echo note))
      (sleep! 1))

    ;; Chain transformers (processed left to right; output of each feeds the next)
    (def my-chain
      (compose-xf
        (velocity-curve {:breakpoints [[0 0] [64 90] [127 127]]})
        (harmonize {:intervals [7 12]})
        (echo {:repeats 2 :decay 0.6 :delay-beats 1/4})))

    (deflive-loop :melody {}
      (when-let [note (pick-step ctx profile)]
        (play-transformed! my-chain note))
      (sleep! 1))

  ## Built-in transformers

    velocity-curve  — piecewise linear velocity mapping
    quantize        — snap pitch to nearest scale degree with forgiveness
    harmonize       — add chromatic interval copies (reads *harmony-ctx* by default)
    echo            — decay repeat chain
    note-repeat     — evenly-spaced (or Euclidean) rhythmic copies
    strum           — spread chord tones over time
    dribble         — bouncing-ball timing (gravity physics)
    latch           — toggle or velocity-threshold gate

  ## Composition

  `compose-xf` chains transformers left-to-right. Each transformer is applied
  to every event in the current result set; delay-beats accumulate correctly
  so an echo of a strum plays the full strummed chord N times.

  ## Delayed events

  Events with delay-beats > 0 are played by a daemon thread that sleeps the
  appropriate number of milliseconds then calls play!. Thread startup latency
  is typically < 1 ms, acceptable for musical purposes. A shared scheduler
  is planned for a future sprint."
  (:require [cljseq.core  :as core]
            [cljseq.loop  :as loop-ns]
            [cljseq.pitch :as pitch-ns]
            [cljseq.scale :as scale-ns]))

;; ---------------------------------------------------------------------------
;; ITransformer protocol
;; ---------------------------------------------------------------------------

(defprotocol ITransformer
  (transform [xf event]
    "Apply this transformer to `event`.

    Returns a seq of {:event map :delay-beats number} maps.
      - Empty seq    → event is silently dropped.
      - delay-beats  → 0 = play immediately; > 0 = play after that many beats.

    Transformers may return multiple results (harmonies, echoes). All results
    from a composed chain are collected; delay-beats accumulate through the chain."))

;; ---------------------------------------------------------------------------
;; Core machinery
;; ---------------------------------------------------------------------------

(defn- beats->delay-ms
  "Convert `beats` to milliseconds at the current BPM."
  ^long [beats]
  (let [bpm (double (or (core/get-bpm) 120))]
    (long (* (double beats) (/ 60000.0 bpm)))))

(defn play-transformed!
  "Apply transformer `xf` to `event` and play all resulting notes.

  Events with delay-beats = 0 are played immediately via core/play!.
  Events with delay-beats > 0 are scheduled on a daemon thread that
  sleeps the appropriate milliseconds before calling play!.

  Returns nil. Safe to call with nil event (no-op).

  Example:
    (play-transformed! my-echo {:pitch/midi 60 :dur/beats 1/4 :mod/velocity 80})"
  [xf event]
  (when (and xf event)
    (doseq [{event' :event db :delay-beats} (transform xf event)
            :when event']
      (if (zero? (double db))
        (core/play! event')
        (doto (Thread.
                (fn []
                  (try
                    (Thread/sleep (beats->delay-ms db))
                    (core/play! event')
                    (catch InterruptedException _ nil)
                    (catch Exception _ nil))))
          (.setDaemon true)
          (.start)))))
  nil)

(defn compose-xf
  "Chain transformers `xfs` left to right.

  The output events from each transformer feed into the next. Delay-beats
  accumulate across the chain, so an echo of a strum correctly delays the
  full strummed pattern.

  Example:
    (compose-xf (velocity-curve ...) (harmonize ...) (echo ...))"
  [& xfs]
  (reify ITransformer
    (transform [_ event]
      (reduce
        (fn [results xf]
          (mapcat (fn [{event' :event db :delay-beats}]
                    (map (fn [r] (update r :delay-beats + db))
                         (transform xf event')))
                  results))
        [{:event event :delay-beats 0}]
        xfs))))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- lerp
  "Linear interpolate from a to b by t ∈ [0,1]."
  ^double [^double a ^double b ^double t]
  (+ a (* t (- b a))))

(defn- breakpoint-apply
  "Evaluate a piecewise linear curve at `x` defined by [[x0 y0] [x1 y1] ...].
  Clamps to the first/last y value outside the breakpoint range."
  [breakpoints x]
  (let [sorted (sort-by first breakpoints)
        lo     (first sorted)
        hi     (last sorted)]
    (cond
      (<= (double x) (double (first lo)))  (double (second lo))
      (>= (double x) (double (first hi)))  (double (second hi))
      :else
      (let [[x0 y0] (last  (filter #(<= (double (first %)) (double x)) sorted))
            [x1 y1] (first (filter #(>  (double (first %)) (double x)) sorted))
            t       (/ (- (double x) (double x0)) (- (double x1) (double x0)))]
        (lerp (double y0) (double y1) t)))))

(defn- snap-to-scale
  "Return the nearest scale MIDI pitch to `midi` within `scale`.
  Returns the snapped value, or `midi` if scale is nil.
  Forgiveness is the maximum allowed chromatic deviation; nil = no limit."
  [scale midi forgiveness]
  (if-not scale
    midi
    (let [pitches (scale-ns/scale-pitches scale)
          midis   (map (fn [p]
                         (try (pitch-ns/pitch->midi p) (catch Exception _ nil)))
                       pitches)
          valid   (filter some? midis)
          ;; Expand to adjacent octaves so we can find the closest match
          octave-midis (mapcat (fn [m] [(- m 12) m (+ m 12)]) valid)
          in-range (filter #(<= 0 % 127) octave-midis)
          nearest  (when (seq in-range)
                     (apply min-key #(Math/abs (- (int %) (int midi))) in-range))
          diff     (when nearest (Math/abs (- (int nearest) (int midi))))]
      (if (or (nil? nearest)
              (and forgiveness (> diff (int forgiveness))))
        nil
        nearest))))

(defn- bjorklund
  "Bjorklund / Euclidean rhythm: distribute `pulses` as evenly as possible
  in `steps` slots. Returns a boolean vector of length `steps`."
  [pulses steps]
  (if (or (zero? pulses) (>= pulses steps))
    (vec (repeat steps (> pulses 0)))
    (let [groups     (vec (concat (repeat pulses [true])
                                  (repeat (- steps pulses) [false])))
          remainder  (mod steps pulses)]
      (loop [pattern groups]
        (let [n        (count pattern)
              non-last (subvec pattern 0 (- n (mod n pulses)))
              last-part (subvec pattern (- n (mod n pulses)))
              rem-len  (count last-part)]
          (if (or (<= rem-len 1) (= rem-len (count non-last)))
            (vec (apply concat pattern))
            (let [half1  (subvec pattern 0 rem-len)
                  half2  (subvec pattern rem-len)
                  merged (mapv into half1 (take (count half1) (cycle half2)))
                  tail   (drop (count half1) half2)]
              (recur (into merged tail)))))))))

(defn- euclidean-positions
  "Return beat positions (from 0) for a Euclidean rhythm with `pulses` spread
  over `window-beats`."
  [pulses window-beats]
  (let [steps (max pulses 8)
        pattern (bjorklund pulses steps)
        step-size (/ (double window-beats) steps)]
    (->> (map-indexed (fn [i active?] (when active? (* i step-size))) pattern)
         (filter some?)
         (vec))))

;; ---------------------------------------------------------------------------
;; VelocityCurve — piecewise linear velocity mapping
;; ---------------------------------------------------------------------------

(defrecord VelocityCurve [breakpoints]
  ITransformer
  (transform [_ event]
    (if-let [vel (:mod/velocity event)]
      (let [mapped (-> (breakpoint-apply breakpoints (double vel))
                       (Math/round)
                       long
                       (max 1)
                       (min 127))]
        [{:event (assoc event :mod/velocity mapped) :delay-beats 0}])
      [{:event event :delay-beats 0}])))

(defn velocity-curve
  "Piecewise linear velocity transformer.

  Maps incoming :mod/velocity through a breakpoint curve. Values outside the
  breakpoint range are clamped to the first/last output value.

  Options:
    :breakpoints — vector of [in out] pairs, sorted by `in` (default identity).
                   e.g. [[0 0] [64 90] [127 127]] raises mid-range velocities.

  Example:
    (velocity-curve {:breakpoints [[0 0] [64 90] [127 127]]})"
  [& {:keys [breakpoints] :or {breakpoints [[0 0] [127 127]]}}]
  (->VelocityCurve breakpoints))

;; ---------------------------------------------------------------------------
;; Quantize — snap pitch to scale with forgiveness threshold
;; ---------------------------------------------------------------------------

(defrecord Quantize [scale forgiveness]
  ITransformer
  (transform [_ event]
    (if-let [midi (:pitch/midi event)]
      (let [snapped (snap-to-scale scale midi forgiveness)]
        (if (nil? snapped)
          []                                              ; out of forgiveness → drop
          [{:event (assoc event :pitch/midi snapped) :delay-beats 0}]))
      [{:event event :delay-beats 0}])))

(defn quantize
  "Snap pitch to the nearest degree of `scale`.

  Options:
    :scale       — Scale record to quantize to (required; nil = pass-through)
    :forgiveness — max chromatic deviation (semitones) before dropping the note;
                   nil = always snap regardless of distance (default nil)

  When the pitch is within forgiveness of a scale degree, it snaps to that degree.
  When outside forgiveness, the event is dropped (empty result).

  Example:
    (quantize {:scale (scale/scale :D 3 :dorian) :forgiveness 2})"
  [& {:keys [scale forgiveness]}]
  (->Quantize scale forgiveness))

;; ---------------------------------------------------------------------------
;; Harmonize — add chromatic interval copies
;;
;; snap? = true  → harmony notes are snapped to the nearest scale degree.
;;   scale-override = nil   → use *harmony-ctx* scale (if present)
;;   scale-override = Scale → use that scale
;; snap? = false → raw chromatic intervals, no scale snapping
;; ---------------------------------------------------------------------------

(defrecord Harmonize [intervals snap? scale-override include-original?]
  ITransformer
  (transform [_ event]
    (when-let [midi (:pitch/midi event)]
      (let [scale    (when snap?
                       (or scale-override (:harmony/key loop-ns/*harmony-ctx*)))
            base     (if include-original? [{:event event :delay-beats 0}] [])
            harmonics
            (keep (fn [interval]
                    (let [harm-midi (+ (int midi) (int interval))]
                      (when (<= 0 harm-midi 127)
                        (let [snapped (if scale
                                        (snap-to-scale scale harm-midi nil)
                                        harm-midi)]
                          (when snapped
                            {:event (assoc event :pitch/midi snapped)
                             :delay-beats 0})))))
                  intervals)]
        (concat base harmonics)))))

(defn harmonize
  "Add chromatic harmony notes at `intervals` semitones above/below the input pitch.

  By default reads :harmony/key from *harmony-ctx* and snaps harmony notes to the
  nearest scale degree. Pass :snap? false to use raw chromatic intervals.

  Options:
    :intervals         — vector of semitone offsets (positive = up, negative = down)
                         e.g. [7] = perfect fifth, [7 12] = fifth + octave,
                              [-5 7] = fourth below + fifth above
    :key               — explicit Scale record; overrides *harmony-ctx* for snapping
    :snap?             — snap harmony notes to scale? (default true)
    :include-original? — include the original event in output? (default true)

  Example:
    (harmonize {:intervals [7 12]})
    (harmonize {:intervals [-5 7] :key (scale/scale :A 3 :minor)})
    (harmonize {:intervals [4 7] :snap? false})  ; raw chromatic, no snapping"
  [& {:keys [intervals key snap? include-original?]
      :or   {intervals [7] snap? true include-original? true}}]
  (->Harmonize intervals snap? key include-original?))

;; ---------------------------------------------------------------------------
;; Echo — decay repeat chain
;; ---------------------------------------------------------------------------

(defrecord Echo [repeats decay delay-beats pitch-step]
  ITransformer
  (transform [_ event]
    (loop [results     [{:event event :delay-beats 0}]
           n            1
           vel          (double (:mod/velocity event 80))
           midi         (int (:pitch/midi event 60))]
      (if (> n repeats)
        results
        (let [new-vel  (-> (* vel (double decay)) (Math/round) long (max 1) (min 127))
              new-midi (when pitch-step
                         (-> (+ midi (int pitch-step)) (max 0) (min 127)))
              ev'      (cond-> (assoc event :mod/velocity new-vel)
                         new-midi (assoc :pitch/midi new-midi))]
          (recur (conj results {:event ev' :delay-beats (* n (double delay-beats))})
                 (inc n)
                 (double new-vel)
                 (or new-midi midi)))))))

(defn echo
  "Decay repeat chain — plays the original note plus N echoes.

  Options:
    :repeats      — number of echo repeats (default 3)
    :decay        — velocity multiplier per repeat, (0, 1] (default 0.7)
    :delay-beats  — beat gap between repeats (default 1/4)
    :pitch-step   — semitone shift per repeat; nil = same pitch (default nil)
                    e.g. 1 = ascend a semitone each repeat, -2 = descend a whole step

  Example:
    (echo {:repeats 3 :decay 0.7 :delay-beats 1/4})
    (echo {:repeats 4 :decay 0.8 :delay-beats 1/8 :pitch-step 2})"
  [& {:keys [repeats decay delay-beats pitch-step]
      :or   {repeats 3 decay 0.7 delay-beats 1/4}}]
  (->Echo repeats (double decay) (double delay-beats) pitch-step))

;; ---------------------------------------------------------------------------
;; NoteRepeat — rhythmic copies within a window
;; ---------------------------------------------------------------------------

(defrecord NoteRepeat [n-copies window-beats euclidean?]
  ITransformer
  (transform [_ event]
    (let [positions (if euclidean?
                      (euclidean-positions n-copies window-beats)
                      (map #(* % (/ (double window-beats) n-copies))
                           (range n-copies)))]
      (map (fn [pos] {:event event :delay-beats pos}) positions))))

(defn note-repeat
  "Rhythmically distribute `n-copies` of the event within `window-beats`.

  Options:
    :n-copies     — number of copies to generate (default 4)
    :window-beats — rhythmic window to fill (default 1)
    :euclidean?   — distribute using Bjorklund/Euclidean algorithm (default false)
                    Euclidean spreads the copies as evenly as possible, avoiding
                    strict grid placement. Useful for polyrhythmic feels.

  Example:
    (note-repeat {:n-copies 4 :window-beats 1})
    (note-repeat {:n-copies 3 :window-beats 2 :euclidean? true})"
  [& {:keys [n-copies window-beats euclidean?]
      :or   {n-copies 4 window-beats 1 euclidean? false}}]
  (->NoteRepeat n-copies (double window-beats) euclidean?))

;; ---------------------------------------------------------------------------
;; Strum — spread chord tones over time
;;
;; Reads :chord/notes (vector of MIDI values) from the event. Falls back to
;; the single :pitch/midi value if :chord/notes is absent.
;; ---------------------------------------------------------------------------

(defrecord Strum [direction rate-beats]
  ITransformer
  (transform [_ event]
    (let [notes  (or (:chord/notes event) [(:pitch/midi event)])
          notes  (filterv some? notes)
          ordered (case direction
                    :down (reverse notes)
                    :random (shuffle notes)
                    notes)]  ; default :up
      (if (empty? ordered)
        []
        (map-indexed
          (fn [i midi]
            {:event (-> (dissoc event :chord/notes)
                        (assoc :pitch/midi midi))
             :delay-beats (* i (double rate-beats))})
          ordered)))))

(defn strum
  "Spread chord tones across time, one note per `rate-beats`.

  Input event should have :chord/notes — a vector of MIDI values. If absent,
  the single :pitch/midi value is used (no-op strum, useful for testing chains).

  Options:
    :direction  — :up (default), :down, or :random
    :rate-beats — delay between consecutive notes (default 1/32)

  Example:
    ;; Strum a C major chord upward
    (play-transformed!
      (strum {:direction :up :rate-beats 1/32})
      {:chord/notes [60 64 67 72] :dur/beats 1 :mod/velocity 90})"
  [& {:keys [direction rate-beats]
      :or   {direction :up rate-beats 1/32}}]
  (->Strum direction (double rate-beats)))

;; ---------------------------------------------------------------------------
;; Dribble — bouncing-ball gravity timing
;;
;; Repeats the event with exponentially decreasing inter-onset intervals,
;; like a ball bouncing to rest. Each bounce is shorter than the previous by
;; factor `restitution` (coefficient of restitution).
;;
;; timing: t_n = sum_{k=0}^{n-1} initial-delay * restitution^k
;; ---------------------------------------------------------------------------

(defrecord Dribble [bounces initial-delay restitution]
  ITransformer
  (transform [_ event]
    (loop [results    [{:event event :delay-beats 0}]
           n           1
           cumulative  (double initial-delay)]
      (if (> n bounces)
        results
        (recur (conj results {:event event :delay-beats cumulative})
               (inc n)
               (+ cumulative (* (double initial-delay)
                                (Math/pow (double restitution) n))))))))

(defn dribble
  "Bouncing-ball gravity timing — repeats the event with exponentially
  shrinking inter-onset gaps.

  Options:
    :bounces       — number of bounces after the initial note (default 4)
    :initial-delay — beat gap before the first bounce (default 1/4)
    :restitution   — coefficient of restitution [0,1]; 0 = one bounce, 1 = uniform
                     (default 0.6)

  Example:
    (dribble {:bounces 5 :initial-delay 1/2 :restitution 0.65})
    ;; Plays the note then 5 bounces, each gap 65% of the previous"
  [& {:keys [bounces initial-delay restitution]
      :or   {bounces 4 initial-delay 1/4 restitution 0.6}}]
  (->Dribble bounces (double initial-delay) (double restitution)))

;; ---------------------------------------------------------------------------
;; Latch — toggle or velocity-threshold gate
;; ---------------------------------------------------------------------------

(defrecord Latch [mode threshold state-atom]
  ITransformer
  (transform [_ event]
    (case mode
      :toggle
      (let [was-open? @state-atom]
        (swap! state-atom not)
        (if was-open? [{:event event :delay-beats 0}] []))

      :velocity
      (if (>= (long (:mod/velocity event 0)) (long threshold))
        [{:event event :delay-beats 0}]
        [])

      ;; Default: pass-through
      [{:event event :delay-beats 0}])))

(defn latch
  "Toggle or velocity-threshold gate.

  :mode :toggle   — alternates between open and closed on each event.
                    First call: open (event passes). Second: closed (dropped). Etc.
  :mode :velocity — passes events only when :mod/velocity ≥ :threshold.

  Options:
    :mode       — :toggle or :velocity (default :toggle)
    :threshold  — velocity threshold for :velocity mode (default 64)
    :initial    — initial state for :toggle mode — true = open (default true)

  Example:
    (latch {:mode :toggle})         ; every other note
    (latch {:mode :velocity :threshold 80}) ; only accented notes"
  [& {:keys [mode threshold initial]
      :or   {mode :toggle threshold 64 initial true}}]
  (->Latch mode (long threshold) (atom initial)))
