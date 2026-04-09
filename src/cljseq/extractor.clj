; SPDX-License-Identifier: EPL-2.0
(ns cljseq.extractor
  "cljseq Threshold Extractor — GTE-inspired continuous-value → event converter.

  Watches a source function `f(beat) → [0.0, 1.0]` and fires note or CC events
  whenever the value crosses a threshold boundary. The rhythm emerges from the
  motion of the source curve, not from a pattern or random process.

  ## Core concept

  A threshold comb of N-1 evenly-spaced boundaries divides the source range into
  N channels. Crossing a boundary fires an `:on-cross` event; the active channel
  (1..N) indicates which zone the source is currently in.

  ## Space parameter

  `space` controls what fraction of [0.0, 1.0] the comb spans.
  `space-center` sets the midpoint of the comb (default 0.5).

    comb-low  = max(0, center - space/2)
    comb-high = min(1, center + space/2)

  With space=1.0 the comb covers the full range. With space=0.5 the comb is a
  tight window around the center — the extractor becomes hypersensitive to small
  movements in that region.

  ## Hysteresis

  To prevent rapid re-triggering when the source hovers near a threshold, a
  hysteresis band prevents a channel change from being recognized until the source
  has moved past the threshold by at least `hysteresis` × inter-threshold spacing.
  Default: 0.01.

  ## Phase 1 scope

  - Uniform threshold placement (N equal zones)
  - Continuous polling mode (no clock gating — see Phase 2)
  - `:on-cross` event output: note or CC
  - `:direction` filter: :both, :rising, :falling
  - Hysteresis (configurable, default 0.01)
  - Source: any fn `f(beat) → [0.0, 1.0]`

  Hardware reference: MakeNoise GTE (Gestural Time Extractor), released 2026-03-26.
  See doc/design-threshold-extractor.md for full design rationale."
  (:require [cljseq.loop    :as loop-ns]
            [cljseq.clock   :as clock]
            [cljseq.sidecar :as sidecar])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state injection (same pattern as cljseq.temporal-buffer)
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

(defn -register-system!
  "Called by cljseq.core/start! to inject the system-state atom.
  Not part of the public API."
  [state-atom]
  (reset! system-ref state-atom))

(defn- current-beat
  "Return current beat position from the system timeline, or 0.0."
  ^double []
  (if-let [s @system-ref]
    (if-let [tl (:timeline @s)]
      (clock/epoch-ms->beat (System/currentTimeMillis) tl)
      0.0)
    0.0))

(defn- timeline
  "Return the current timeline map, or nil."
  []
  (when-let [s @system-ref]
    (:timeline @s)))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private registry (atom {}))

;; ---------------------------------------------------------------------------
;; Thread helpers (mirrors temporal-buffer pattern)
;; ---------------------------------------------------------------------------

(defn- start-thread! [thread-name f]
  (doto (Thread. ^Runnable f thread-name)
    (.setDaemon true)
    (.start)))

(defn- stop-thread! [running? ^Thread t]
  (reset! running? false)
  (when t (LockSupport/unpark t)))

;; ---------------------------------------------------------------------------
;; Threshold arithmetic
;; ---------------------------------------------------------------------------

(defn compute-thresholds
  "Return a vector of (n-channels − 1) threshold values uniformly spaced
  within [comb-low, comb-high].

  With n-channels=4, space=1.0, space-center=0.5:
    comb-low=0.0, comb-high=1.0 → thresholds [0.25 0.5 0.75]

  With n-channels=4, space=0.5, space-center=0.5:
    comb-low=0.25, comb-high=0.75 → thresholds [0.3333 0.5 0.6667]"
  [^long n-channels ^double space ^double space-center]
  (let [half      (* space 0.5)
        comb-low  (max 0.0 (- space-center half))
        comb-high (min 1.0 (+ space-center half))
        span      (- comb-high comb-low)
        step      (/ span n-channels)]
    (mapv (fn [^long i] (+ comb-low (* step i)))
          (range 1 n-channels))))

(defn value->channel
  "Return the 1-indexed channel (1..N) that `v` falls into, given `thresholds`
  (a sorted ascending vector of N-1 threshold values).

  Channel 1 is below the first threshold; channel N is above the last threshold."
  ^long [^double v thresholds]
  ;; A value at exactly a threshold has just crossed it → higher channel (>=)
  (inc (int (count (filter #(>= v ^double %) thresholds)))))

(defn hysteresis-ok?
  "Return true if the source value `v` has moved far enough past the boundary
  between `last-ch` and `new-ch` to be considered a confirmed crossing.

  The dead-zone half-width is `hyst` × inter-threshold spacing. If the source is
  within the dead-zone it is treated as still in `last-ch`.

  When last-ch == new-ch, no crossing has occurred — returns false.
  When the thresholds vector is empty (n-channels=1), always returns false."
  [v thresholds last-ch new-ch hyst]
  (let [v        (double v)
        last-ch  (long last-ch)
        new-ch   (long new-ch)
        hyst     (double hyst)
        n-thresh (count thresholds)]
    (cond
      (= last-ch new-ch) false
      (zero? n-thresh)   false
      :else
      (let [;; Boundary index (0-based) is (min last-ch new-ch) - 1
            boundary-idx (dec (min last-ch new-ch))
            thresh       (if (and (>= boundary-idx 0) (< boundary-idx n-thresh))
                           (double (nth thresholds boundary-idx))
                           0.5)
            ;; Inter-threshold spacing — use first gap when >1 threshold
            spacing      (if (> n-thresh 1)
                           (- (double (nth thresholds 1))
                              (double (nth thresholds 0)))
                           (max 0.001 thresh))
            dead-zone    (* hyst (max 0.001 (Math/abs spacing)))]
        (if (> new-ch last-ch)
          ;; Rising: v must be > threshold + dead-zone
          (> v (+ thresh dead-zone))
          ;; Falling: v must be < threshold - dead-zone
          (< v (- thresh dead-zone)))))))

;; ---------------------------------------------------------------------------
;; Event emission
;; ---------------------------------------------------------------------------

(defn- emit-on-cross!
  "Dispatch on `:type` in `cfg` and fire the appropriate event.
  Falls back to stdout when the sidecar is not connected.

  `ch`       — 1-indexed active channel after crossing
  `prev-ch`  — prior channel
  `dir`      — :rising or :falling
  `beat`     — beat at which the crossing was detected"
  [ch prev-ch dir beat cfg]
  (let [ch      (long ch)
        prev-ch (long prev-ch)
        beat    (double beat)]
  (case (:type cfg :note)

    :note
    (let [midi-ch  (int (or (:midi-channel cfg) 1))
          note     (int (or (:pitch cfg) 60))
          velocity (int (max 0 (min 127 (or (:velocity cfg) 80))))
          dur      (double (or (:duration cfg) 0.1))]
      (if (and (sidecar/connected?) (timeline))
        (let [tl     (timeline)
              on-ns  (clock/beat->epoch-ns beat tl)
              off-ns (clock/beat->epoch-ns (+ beat dur) tl)]
          (sidecar/send-note-on!  on-ns  midi-ch note velocity)
          (sidecar/send-note-off! off-ns midi-ch note))
        (println (format "[extractor] cross ch=%d→%d %s beat=%.3f note=%d vel=%d"
                         prev-ch ch (name dir) beat note velocity))))

    :cc
    (let [midi-ch (int (or (:midi-channel cfg) 1))
          cc-num  (int (or (:cc cfg) 48))
          value   (int (max 0 (min 127 (or (:value cfg) 127))))]
      (if (and (sidecar/connected?) (timeline))
        (let [tl     (timeline)
              now-ns (clock/beat->epoch-ns beat tl)]
          (sidecar/send-cc! now-ns midi-ch cc-num value))
        (println (format "[extractor] cross ch=%d→%d %s beat=%.3f cc=%d val=%d"
                         prev-ch ch (name dir) beat cc-num value))))

    :fn
    (when-let [f (:f cfg)]
      (f ch prev-ch dir beat))

    nil)))

;; ---------------------------------------------------------------------------
;; Direction filter
;; ---------------------------------------------------------------------------

(defn- direction-allowed?
  "Return true if `crossing-dir` (:rising or :falling) is permitted by `direction-filter`."
  [crossing-dir direction-filter]
  (case direction-filter
    :both    true
    :rising  (= crossing-dir :rising)
    :falling (= crossing-dir :falling)
    true))

;; ---------------------------------------------------------------------------
;; Poll cycle
;; ---------------------------------------------------------------------------

(defn- poll-once!
  "Sample the source at `beat`, detect threshold crossings, emit events.
  Returns updated [last-channel last-value] pair."
  [beat source-fn thresholds on-cross direction-filter hysteresis last-ch last-v]
  (let [beat      (double beat)
        hysteresis (double hysteresis)
        last-ch   (long last-ch)
        last-v    (double last-v)
        v         (double (source-fn beat))
        new-ch (long (value->channel v thresholds))
        dir    (if (> v last-v) :rising :falling)]
    (cond
      ;; First sample (last-ch=0): initialize channel, no crossing event
      (zero? last-ch) [new-ch v]
      ;; Confirmed crossing: emit event and advance channel
      (and (not= new-ch last-ch)
           (hysteresis-ok? v thresholds last-ch new-ch hysteresis)
           (direction-allowed? dir direction-filter))
      (do
        (emit-on-cross! new-ch last-ch dir beat on-cross)
        [new-ch v])
      ;; No confirmed crossing — keep channel, update value
      :else [last-ch v])))

;; ---------------------------------------------------------------------------
;; Polling daemon runner
;; ---------------------------------------------------------------------------

(defn- extractor-runner
  "Daemon loop: polls the source at `poll-interval` beats, detects crossings,
  fires events. Runs until `running?` is false."
  [ext-name ^double poll-interval running?]
  (loop [last-ch 0
         last-v  -1.0]
    (when @running?
      (let [entry (get @registry ext-name)]
        (when entry
          (let [state      @(:state-atom entry)
                source-fn  (:source-fn state)
                thresholds (:thresholds state)
                on-cross   (:on-cross state)
                dir-filter (:direction state :both)
                hyst       (double (:hysteresis state 0.01))
                beat       (current-beat)
                [new-ch new-v] (if source-fn
                                 (poll-once! beat source-fn thresholds on-cross
                                             dir-filter hyst last-ch last-v)
                                 [last-ch last-v])
                next-beat  (+ beat poll-interval)]
            (loop-ns/-park-until-beat! next-beat)
            (recur new-ch new-v)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(declare extractor-stop!)

(defn defthreshold-extractor
  "Register and start a named threshold extractor.

  `ext-name` — keyword identifier for this extractor (e.g. :tension-rhythm)
  `opts`      — configuration map:

  Required:
    :source-fn  — fn `f(beat) → [0.0, 1.0]`; sampled at each poll tick
    :on-cross   — what to fire on each threshold crossing:
                  {:type     :note            ; or :cc, :fn
                   :pitch    60               ; MIDI note number (for :note)
                   :velocity 80
                   :duration 0.1              ; in beats (for :note)
                   :midi-channel 1
                   ;; for :cc —
                   :cc       48
                   :value    127
                   ;; for :fn —
                   :f        (fn [ch prev-ch direction beat] ...)}

  Optional:
    :channels      — number of equal zones (default 8)
    :space         — fraction of [0,1] the comb spans (default 1.0)
    :space-center  — center of the comb (default 0.5)
    :direction     — :both | :rising | :falling (default :both)
    :hysteresis    — dead-zone fraction of inter-threshold spacing (default 0.01)
    :poll-interval — beats between polls (default 1/32)

  Re-evaluating with the same name stops the prior instance and starts fresh.

  Returns `ext-name`."
  ([ext-name]
   (defthreshold-extractor ext-name {}))
  ([ext-name opts]
   (when (get @registry ext-name)
     (extractor-stop! ext-name))
   (let [n-channels    (long (or (:channels opts) 8))
         space         (double (or (:space opts) 1.0))
         space-center  (double (or (:space-center opts) 0.5))
         direction     (or (:direction opts) :both)
         hysteresis    (double (or (:hysteresis opts) 0.01))
         poll-interval (double (or (:poll-interval opts) (/ 1.0 32.0)))
         thresholds    (compute-thresholds n-channels space space-center)
         state-atom    (atom {:source-fn    (:source-fn opts)
                              :thresholds   thresholds
                              :n-channels   n-channels
                              :space        space
                              :space-center space-center
                              :direction    direction
                              :hysteresis   hysteresis
                              :on-cross     (:on-cross opts)})
         running?      (atom true)
         entry         {:opts       opts
                        :state-atom state-atom
                        :running?   running?
                        :thread     nil}]
     (swap! registry assoc ext-name entry)
     (let [t (start-thread! (str "cljseq-extractor-" (name ext-name))
                            #(extractor-runner ext-name poll-interval running?))]
       (swap! registry update ext-name assoc :thread t))
     ext-name)))

(defn extractor-stop!
  "Stop the polling thread for `ext-name` and remove it from the registry.
  Safe to call if the extractor is not running — returns nil."
  [ext-name]
  (when-let [entry (get @registry ext-name)]
    (stop-thread! (:running? entry) (:thread entry))
    (swap! registry dissoc ext-name))
  nil)

(defn extractor-stop-all!
  "Stop all active threshold extractors. Called by cljseq.core/stop!."
  []
  (doseq [n (keys @registry)]
    (extractor-stop! n))
  nil)

(defn extractor-set!
  "Update a live parameter on a running extractor.

  Supported keys:
    :source-fn     — replace the source function
    :on-cross      — replace the event config
    :space         — recompute the threshold comb (n-channels preserved)
    :space-center  — recompute the threshold comb (n-channels preserved)
    :direction     — :both | :rising | :falling
    :hysteresis    — dead-zone fraction

  Returns `ext-name`, or nil if not registered."
  [ext-name k v]
  (when-let [entry (get @registry ext-name)]
    (let [state-atom (:state-atom entry)]
      (if (#{:space :space-center} k)
        (swap! state-atom (fn [s]
                            (let [nc (long (:n-channels s 8))
                                  sp (double (if (= k :space) v (:space s 1.0)))
                                  sc (double (if (= k :space-center) v (:space-center s 0.5)))]
                              (assoc s k v :thresholds (compute-thresholds nc sp sc)))))
        (swap! state-atom assoc k v)))
    ext-name))

(defn extractor-status
  "Return a snapshot of the current state for `ext-name`, or nil."
  [ext-name]
  (when-let [entry (get @registry ext-name)]
    (assoc @(:state-atom entry)
           :running? @(:running? entry))))
