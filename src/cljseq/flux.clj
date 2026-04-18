; SPDX-License-Identifier: EPL-2.0
(ns cljseq.flux
  "cljseq flux sequence — circular step buffer with independent read and write heads.

  Inspired by the Moog Labyrinth Eurorack sequencer (see doc/attribution.md).
  A `defflux` sequence has two independent processes operating on a shared
  circular step buffer:

    - Read head:  advances at its clock rate, emits events to an output target
    - Write head: advances at its own clock rate, writes values from a source

  An optional CORRUPT process applies probabilistic bit-level mutation to stored
  values, with a side-channel `on-corrupt!` callback for reactive secondary
  behaviour.

  ## Quick start

    (defflux :melody
      {:steps  8
       :init   60
       :read   {:clock (clock/->Phasor 1 0) :direction :forward}
       :output {:target [:flux/melody-out]}})

    ;; Inspect the buffer
    (flux/peek :melody)           ;=> [60 60 60 60 60 60 60 60]
    (flux/read-pos :melody)       ;=> 2 (after 2 beats)

    ;; Add a write head
    (defflux :evolving
      {:steps  8
       :read   {:clock (clock/->Phasor 1 0)}
       :write  {:clock  (clock/clock-div 3 (clock/->Phasor 1 0))
                :source (mod/lfo (clock/->Phasor 1/4 0) phasor/sine-uni)}
       :output {:target [:flux/out]}})

  ## Output targets
    :stdout         — println for development
    [:ctrl :path]   — ctrl/send! (compose with MIDI CC bindings)

  ## CORRUPT
    (defflux :decaying
      {:steps   8
       :corrupt {:intensity 0.2
                 :clock     (clock/clock-div 4 (clock/->Phasor 1 0))}
       :output  {:target :stdout}})

    (flux/on-corrupt! :decaying
      (fn [idx old new]
        (println (format \"corrupt step=%d %d→%d\" idx old new))))

  Key design decisions: Q53 (concurrency — vector of atoms), Q54 (scale as
  ITemporalValue), §26 Flux Sequence Architecture."
  (:refer-clojure :exclude [peek reset!])
  (:require [cljseq.clock :as clock]
            [cljseq.ctrl  :as ctrl]
            [cljseq.loop  :as loop-ns])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state (injected by cljseq.core/start!)
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

(defn -register-system!
  "Called by cljseq.core/start! to inject the system-state atom."
  [state-atom]
  (clojure.core/reset! system-ref state-atom))

(defn- current-beat
  "Return current beat position from the system timeline, or 0.0."
  ^double []
  (if-let [s @system-ref]
    (if-let [tl (:timeline @s)]
      (clock/epoch-ms->beat (System/currentTimeMillis) tl)
      0.0)
    0.0))

;; ---------------------------------------------------------------------------
;; Registry: {name entry}
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(declare stop!)

;; ---------------------------------------------------------------------------
;; Scale quantization
;; ---------------------------------------------------------------------------

(def ^:private scale-semitones
  {:chromatic   [0 1 2 3 4 5 6 7 8 9 10 11]
   :major        [0 2 4 5 7 9 11]
   :minor        [0 2 3 5 7 8 10]
   :dorian       [0 2 3 5 7 9 10]
   :phrygian     [0 1 3 5 7 8 10]
   :lydian       [0 2 4 6 7 9 11]
   :mixolydian   [0 2 4 5 7 9 10]
   :locrian      [0 1 3 5 6 8 10]
   :pentatonic   [0 2 4 7 9]
   :minor-pent   [0 3 5 7 10]})

(defn- quantize
  "Snap a MIDI note to the nearest pitch class in `scale-kw` (e.g. :major)."
  [midi-note scale-kw]
  (if-let [semitones (get scale-semitones scale-kw)]
    (let [octave (* 12 (quot (int midi-note) 12))
          pitch  (mod (int midi-note) 12)
          nearest (apply min-key #(Math/abs ^long (- pitch (long %))) semitones)]
      (+ octave (int nearest)))
    (int midi-note)))

(defn- resolve-scale
  "Sample `scale` at `beat`. Accepts nil (pass-through), a keyword, or ITemporalValue."
  [scale beat]
  (cond
    (nil? scale)     nil
    (keyword? scale) scale
    :else            (clock/sample scale beat)))

;; ---------------------------------------------------------------------------
;; CORRUPT mutation (§26.3, §26.8)
;; ---------------------------------------------------------------------------

(defn corrupt-value
  "Apply probabilistic bit-level mutation to MIDI integer `v` at `intensity`.

  Bits 0–6 are eligible for flipping. Flip probability for bit N is:
    intensity / (1 + N)
  Bit 0 (semitone LSB) is most likely to flip; bit 6 (octave-level MSB) least.
  Result is clamped to [0, 127].

  At intensity 0.0: no bits flip. At 1.0: bit 0 always flips (±1 semitone)."
  [v intensity]
  (let [flip-mask (reduce bit-or 0
                          (for [bit (range 7)
                                :when (< (rand) (/ (double intensity) (inc bit)))]
                            (bit-shift-left 1 bit)))]
    (max 0 (min 127 (bit-xor (int v) flip-mask)))))

;; ---------------------------------------------------------------------------
;; Direction advance
;; ---------------------------------------------------------------------------

(defn- advance-pos
  "Return [new-pos new-pp-dir] given pos in [0,n), direction, and current
  ping-pong direction (:forward or :backward)."
  [pos n direction pp-dir]
  (if (= 1 n)
    [0 pp-dir]
    (case direction
      :forward  [(mod (inc pos) n) pp-dir]
      :backward [(mod (+ n (dec pos)) n) pp-dir]
      :random   [(rand-int n) pp-dir]
      :ping-pong
      (let [pp-dir' (cond
                      (and (= :forward  pp-dir) (= pos (dec n))) :backward
                      (and (= :backward pp-dir) (= pos 0))       :forward
                      :else                                       pp-dir)
            delta   (if (= :forward pp-dir') 1 -1)]
        [(+ pos delta) pp-dir']))))

;; ---------------------------------------------------------------------------
;; Output dispatch
;; ---------------------------------------------------------------------------

(defn- emit!
  "Emit `value` to the output target defined in `output`."
  [value output]
  (let [target (:target output :stdout)]
    (cond
      (= :stdout target)
      (println (format "[flux] value=%d" (int value)))

      (vector? target)
      (ctrl/send! target value))))

;; ---------------------------------------------------------------------------
;; Step buffer initialisation
;; ---------------------------------------------------------------------------

(defn- make-steps
  "Return a vector of N atoms initialised from `init`.
  init may be a scalar (all steps equal) or a vector (element-per-step, cycling)."
  [n init]
  (let [vals (if (vector? init)
               (vec (take n (cycle init)))
               (vec (repeat n init)))]
    (mapv atom vals)))

;; ---------------------------------------------------------------------------
;; Runner threads
;; ---------------------------------------------------------------------------

(defn- read-runner
  "Main loop for the read head thread of `flux-name`."
  [flux-name running?]
  (loop []
    (when @running?
      (let [entry (clojure.core/get @registry flux-name)]
        (when entry
          (let [beat  (current-beat)
                clock (get-in entry [:opts :read :clock])
                next  (when clock (clock/next-edge clock beat))]
            (when (and next (not= ##Inf next))
              (loop-ns/-park-until-beat! next)
              (when @running?
                (let [entry'      (clojure.core/get @registry flux-name)
                      read-state  (:read-state entry')
                      steps       (:steps entry')
                      opts        (:opts entry')
                      beat'       (current-beat)
                      {:keys [pos frozen? pp-dir]} @read-state]
                  ;; Emit current step value
                  (when-not frozen?
                    (let [raw-val   @(steps pos)
                          scale     (resolve-scale (get opts :scale) beat')
                          out-val   (if scale (quantize raw-val scale) raw-val)]
                      (emit! out-val (:output opts)))
                    ;; Advance position
                    (let [[new-pos new-pp-dir]
                          (advance-pos pos (count steps)
                                       (get-in opts [:read :direction] :forward)
                                       pp-dir)]
                      (swap! read-state assoc :pos new-pos :pp-dir new-pp-dir)))))
              (recur))))))))

(defn- write-runner
  "Main loop for the write head thread of `flux-name`."
  [flux-name running?]
  (loop []
    (when @running?
      (let [entry (clojure.core/get @registry flux-name)]
        (when entry
          (let [beat  (current-beat)
                clock (get-in entry [:opts :write :clock])
                next  (when clock (clock/next-edge clock beat))]
            (when (and next (not= ##Inf next))
              (loop-ns/-park-until-beat! next)
              (when @running?
                (let [entry'      (clojure.core/get @registry flux-name)
                      write-state (:write-state entry')
                      steps       (:steps entry')
                      opts        (:opts entry')
                      beat'       (current-beat)
                      {:keys [pos frozen? pp-dir]} @write-state]
                  (when-not frozen?
                    (let [source    (get-in opts [:write :source])
                          new-val   (if (satisfies? clock/ITemporalValue source)
                                      (clock/sample source beat')
                                      (source))
                          transform (get-in opts [:write :transform])
                          old-val   @(steps pos)
                          final-val (if transform (transform old-val new-val) new-val)]
                      (clojure.core/reset! (steps pos) final-val))
                    (let [[new-pos new-pp-dir]
                          (advance-pos pos (count steps)
                                       (get-in opts [:write :direction] :forward)
                                       pp-dir)]
                      (swap! write-state assoc :pos new-pos :pp-dir new-pp-dir)))))
              (recur))))))))

(defn- corrupt-runner
  "Main loop for the CORRUPT process thread of `flux-name`."
  [flux-name running?]
  (loop []
    (when @running?
      (let [entry (clojure.core/get @registry flux-name)]
        (when entry
          (let [beat  (current-beat)
                clock (get-in entry [:opts :corrupt :clock])
                next  (when clock (clock/next-edge clock beat))]
            (when (and next (not= ##Inf next))
              (loop-ns/-park-until-beat! next)
              (when @running?
                (let [entry'    (clojure.core/get @registry flux-name)
                      steps     (:steps entry')
                      opts      (:opts entry')
                      listeners @(:listeners entry')
                      beat'     (current-beat)
                      intensity-src (get-in opts [:corrupt :intensity] 0.1)
                      intensity (if (satisfies? clock/ITemporalValue intensity-src)
                                  (clock/sample intensity-src beat')
                                  (double intensity-src))
                      n         (count steps)
                      idx       (rand-int n)
                      old-val   @(steps idx)
                      new-val   (corrupt-value old-val intensity)]
                  (when (not= old-val new-val)
                    (clojure.core/reset! (steps idx) new-val)
                    (doseq [cb listeners]
                      (try
                        (cb idx old-val new-val)
                        (catch Exception e
                          (binding [*out* *err*]
                            (println (str "[flux] on-corrupt! callback error: "
                                          (.getMessage e))))))))))
              (recur))))))))

;; ---------------------------------------------------------------------------
;; Thread lifecycle helpers
;; ---------------------------------------------------------------------------

(defn- start-thread!
  "Start a daemon thread running `f`, named `thread-name`. Returns the Thread."
  [thread-name f]
  (let [t (Thread. f)]
    (.setDaemon t true)
    (.setName t thread-name)
    (.start t)
    t))

(defn- stop-thread!
  "Signal `running?` atom false and unpark `thread`."
  [running? ^Thread thread]
  (clojure.core/reset! running? false)
  (when thread (LockSupport/unpark thread)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn defflux
  "Define and start a named flux sequence.

  `flux-name` — keyword (e.g. :melody)
  `opts`      — option map (see ns docstring and §26.7 for full reference)

  Option map keys:
    :steps   — integer, buffer size (default 16)
    :init    — scalar or vector, initial step values (default 60)
    :scale   — nil, scale keyword, or ITemporalValue (output quantization, Q54)
    :read    — map: {:clock ITemporalValue :direction kw :start int}
    :write   — map: {:clock ITV :source ITV-or-fn :direction kw :transform fn}
               nil to disable the write head
    :corrupt — map: {:clock ITV :intensity number-or-ITV}
               nil to disable CORRUPT
    :output  — map: {:target :stdout-or-path}

  Re-evaluating with the same name stops the prior instance and starts a new one.

  Returns `flux-name`."
  [flux-name opts]
  ;; Stop any prior instance
  (when (clojure.core/get @registry flux-name)
    (stop! flux-name))
  (let [n          (int (or (:steps opts) 16))
        init       (or (:init opts) 60)
        steps      (make-steps n init)
        read-cfg   (or (:read opts) {})
        write-cfg  (:write opts)
        corrupt-cfg (:corrupt opts)
        read-running?    (atom true)
        write-running?   (atom (some? write-cfg))
        corrupt-running? (atom (some? corrupt-cfg))
        read-state   (atom {:pos (int (or (:start read-cfg)  0)) :frozen? false :pp-dir :forward})
        write-state  (atom {:pos (int (or (:start write-cfg) 0)) :frozen? false :pp-dir :forward})
        listeners    (atom [])
        entry        {:steps            steps
                      :opts             (assoc opts
                                               :read    (merge {:direction :forward} read-cfg)
                                               :write   write-cfg
                                               :corrupt corrupt-cfg)
                      :read-state       read-state
                      :write-state      write-state
                      :listeners        listeners
                      :read-running?    read-running?
                      :write-running?   write-running?
                      :corrupt-running? corrupt-running?
                      :read-thread      nil
                      :write-thread     nil
                      :corrupt-thread   nil}]
    (swap! registry assoc flux-name entry)
    ;; Register ctrl tree node
    (ctrl/defnode! [:flux flux-name] :type :data)
    ;; Start threads
    (let [rt (start-thread! (str "cljseq-flux-read-"    (name flux-name))
                             #(read-runner    flux-name read-running?))
          wt (when write-cfg
               (start-thread! (str "cljseq-flux-write-"  (name flux-name))
                               #(write-runner  flux-name write-running?)))
          ct (when corrupt-cfg
               (start-thread! (str "cljseq-flux-corrupt-" (name flux-name))
                               #(corrupt-runner flux-name corrupt-running?)))]
      (swap! registry update flux-name assoc
             :read-thread rt :write-thread wt :corrupt-thread ct)))
  flux-name)

(defn stop!
  "Stop all threads for `flux-name` and remove it from the registry.

  The ctrl tree node [:flux flux-name] is NOT removed (preserves inspection
  state). Re-registering with defflux resets it."
  [flux-name]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (stop-thread! (:read-running?    entry) (:read-thread    entry))
    (stop-thread! (:write-running?   entry) (:write-thread   entry))
    (stop-thread! (:corrupt-running? entry) (:corrupt-thread entry))
    (swap! registry dissoc flux-name))
  nil)

(defn stop-all!
  "Stop all active flux instances. Called by cljseq.core/stop!."
  []
  (doseq [name (keys @registry)]
    (stop! name))
  nil)

(defn freeze!
  "Suspend the read head at its current position.
  The write head and CORRUPT process continue. Use unfreeze! to resume."
  [flux-name]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (swap! (:read-state entry) assoc :frozen? true))
  nil)

(defn unfreeze!
  "Resume a frozen read head from its current position."
  [flux-name]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (swap! (:read-state entry) assoc :frozen? false))
  nil)

(defn freeze-write!
  "Suspend the write head. The step buffer is no longer updated."
  [flux-name]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (swap! (:write-state entry) assoc :frozen? true))
  nil)

(defn unfreeze-write!
  "Resume a frozen write head."
  [flux-name]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (swap! (:write-state entry) assoc :frozen? false))
  nil)

(defn read-pos
  "Return the current 0-indexed read head position."
  [flux-name]
  (:pos @(:read-state (clojure.core/get @registry flux-name))))

(defn write-pos
  "Return the current 0-indexed write head position."
  [flux-name]
  (:pos @(:write-state (clojure.core/get @registry flux-name))))

(defn peek
  "Return the current step buffer contents, or a single step value.

  (flux/peek :melody)     ; => [60 62 64 65 67 65 64 62]
  (flux/peek :melody 3)   ; => 65"
  ([flux-name]
   (when-let [entry (clojure.core/get @registry flux-name)]
     (mapv deref (:steps entry))))
  ([flux-name step-idx]
   (when-let [entry (clojure.core/get @registry flux-name)]
     @(nth (:steps entry) step-idx))))

(defn set-step!
  "Write `value` directly to position `step-idx`, bypassing the write head.

  Useful for manually seeding the buffer or correcting CORRUPT mutations."
  [flux-name step-idx value]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (clojure.core/reset! (nth (:steps entry) step-idx) value))
  nil)

(defn reset!
  "Reinitialise the step buffer.

  (flux/reset! :melody)          ; reset to original :init value
  (flux/reset! :melody 60)       ; reset all steps to 60
  (flux/reset! :melody [60 62])  ; reset each step (cycles if shorter than buffer)"
  ([flux-name]
   (when-let [entry (clojure.core/get @registry flux-name)]
     (reset! flux-name (get-in entry [:opts :init] 60))))
  ([flux-name init-val]
   (when-let [entry (clojure.core/get @registry flux-name)]
     (let [steps (:steps entry)
           n     (count steps)
           vals  (if (vector? init-val)
                   (vec (take n (cycle init-val)))
                   (vec (repeat n init-val)))]
       (dotimes [i n]
         (clojure.core/reset! (steps i) (vals i)))))
   nil))

(defn on-corrupt!
  "Register `callback-fn` to be called each time CORRUPT mutates a step.

  The callback receives [step-idx old-val new-val] and is called in the
  CORRUPT thread. See §26.9 for the full callback contract.

  Example:
    (flux/on-corrupt! :melody
      (fn [idx old new]
        (ctrl/send! [:env/trig] 1)))"
  [flux-name callback-fn]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (swap! (:listeners entry) conj callback-fn))
  nil)

(defn remove-corrupt-listener!
  "Remove a previously registered on-corrupt! callback (matched by identity)."
  [flux-name callback-fn]
  (when-let [entry (clojure.core/get @registry flux-name)]
    (swap! (:listeners entry) #(vec (remove #{callback-fn} %))))
  nil)

(defn flux-names
  "Return a seq of registered flux instance names. Useful for REPL inspection."
  []
  (keys @registry))
