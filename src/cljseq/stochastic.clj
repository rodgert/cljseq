; SPDX-License-Identifier: EPL-2.0
(ns cljseq.stochastic
  "cljseq stochastic generative sequencer.

  Inspired by Mutable Instruments' Marbles Eurorack module (see doc/attribution.md).
  Provides non-deterministic, probabilistic pattern generation with controllable
  bias — the 'when' (T section / rhythm) and 'what' (X section / pitch) are
  independently configurable.

  ## Quick start

    ;; X section: pitch values
    (def melody (stochastic-sequence :spread 0.5 :bias 0.5 :range [48 72]))
    (melody)   ;=> 62  (random MIDI note each call)

    ;; T section: rhythm gates
    (def rhythm (stochastic-rhythm :mode :complementary-bernoulli :bias 0.6))
    (rhythm)   ;=> true or false (fires or not)

    ;; DEJA VU memory: 0=pure random, 1=locked loop
    (def dv (atom 0.0))
    (def looping (stochastic-sequence :spread 0.4 :deja-vu dv :loop-len 8))
    (reset! dv 0.8)   ;=> crystallize the loop

    ;; All-in-one: named stochastic context
    (defstochastic my-gen
      :x-spread 0.5 :x-bias 0.5 :x-range [48 72] :x-loop-len 8
      :t-mode :complementary-bernoulli :t-bias 0.5 :t-loop-len 8
      :channels 2)

    (next-x! my-gen 0)   ;=> MIDI note value for channel 0
    (next-t! my-gen 1)   ;=> true/false gate for channel 1

  Key design decisions: Q34 (defonce ring buffer), Q37 (correlated channels,
  lerp perturbation), Q38 (auto-register stochastic context), §23."
  (:require [cljseq.ctrl   :as ctrl]
            [cljseq.random :as random]))

;; ---------------------------------------------------------------------------
;; Registry (Q38: auto-registration)
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register a stochastic context under the given name keyword. Used by defstochastic macro."
  [k ctx]
  (swap! registry assoc k ctx))

;; ---------------------------------------------------------------------------
;; DEJA VU ring buffer (§23.6)
;; ---------------------------------------------------------------------------

(defn make-deja-vu-source
  "Wrap `gen-fn` with DEJA VU ring-buffer memory.

  `gen-fn`      — zero-arg function that generates fresh values
  `loop-len`    — number of positions in the memory ring [1-32]
  `deja-vu-atom` — atom holding the deja-vu parameter [0.0-1.0]
                   0.0 = pure random; 1.0 = locked loop

  Returns a zero-arg function that:
  - With probability `@deja-vu-atom`: replays the buffered value at the
    current ring position
  - Otherwise: calls `gen-fn`, writes the result into the ring, advances

  The buffer is pre-filled with initial draws on construction so it is
  non-empty from the first call. Consistent with Q34 (defonce persistence)."
  [gen-fn loop-len deja-vu-atom]
  (let [n   (max 1 (int loop-len))
        buf (atom (vec (repeatedly n gen-fn)))
        pos (atom 0)]
    (fn []
      (let [dv  (double @deja-vu-atom)
            idx (mod @pos n)
            val (if (< (rand) dv)
                  (nth @buf idx)          ; replay from ring
                  (let [v (gen-fn)]
                    (swap! buf assoc idx v)  ; write fresh into ring
                    v))]
        (swap! pos inc)
        val))))

;; ---------------------------------------------------------------------------
;; X section: stochastic value sequence (§23.4)
;; ---------------------------------------------------------------------------

(defn stochastic-sequence
  "Create a callable pitch/value generator with Marbles-style parameters.

  Returns a zero-arg function; each call draws the next value.

  Options:
    :spread   — distribution width [0=constant, 0.5=Gaussian, 1=uniform] (default 0.5)
    :bias     — center of distribution [0.0-1.0] (default 0.5)
    :steps    — quantization depth [0=raw, 1=root-only] (default 0.5)
    :scale    — weighted-scale map from (random/weighted-scale ...) or nil
    :range    — [lo hi] MIDI note range (default [48 84])
    :deja-vu  — atom holding deja-vu parameter [0.0-1.0] (default (atom 0.0))
    :loop-len — ring buffer length for deja-vu (default 8)

  Example:
    (def mel (stochastic-sequence :spread 0.4 :bias 0.6 :range [48 72]
                                   :scale (random/weighted-scale :dorian)))
    (mel)   ;=> MIDI note"
  [& {:keys [spread bias steps scale range deja-vu loop-len]
      :or   {spread 0.5 bias 0.5 steps 0.5 range [48 84] loop-len 8}}]
  (let [deja-vu-atom (or deja-vu (atom 0.0))
        [lo hi]      range
        lo           (double lo)
        hi           (double hi)
        gen-fn       (fn []
                       (let [raw  (random/sample-distribution spread bias)
                             midi (long (Math/round (+ lo (* raw (- hi lo)))))]
                         (if (and scale (> (double steps) 0.5))
                           (random/progressive-quantize midi scale steps)
                           (int (max lo (min hi midi))))))]
    (make-deja-vu-source gen-fn loop-len deja-vu-atom)))

;; ---------------------------------------------------------------------------
;; T section: stochastic rhythm (§23.3)
;; ---------------------------------------------------------------------------

(defn stochastic-rhythm
  "Create a callable gate generator.

  Returns a zero-arg function; each call returns true (fires) or false (silent).

  Options:
    :mode     — gate generation mode (default :independent-bernoulli)
                :complementary-bernoulli — coin flip determines which of two channels fires
                :independent-bernoulli   — independent Bernoulli draw per call
                :three-states            — weighted choice: channel-A / channel-B / silence
    :bias     — probability parameter [0.0-1.0] (default 0.5)
    :deja-vu  — atom holding deja-vu parameter [0.0-1.0] (default (atom 0.0))
    :loop-len — ring buffer length (default 8)

  Note: for :complementary-bernoulli mode, channel correlation is enforced when
  used via defstochastic/next-t! (which shares a tick atom across channels).
  Standalone stochastic-rhythm is independent-Bernoulli regardless of mode.

  Example:
    (def gates (stochastic-rhythm :mode :independent-bernoulli :bias 0.6))
    (gates)   ;=> true (60% of the time)"
  [& {:keys [mode bias deja-vu loop-len]
      :or   {mode :independent-bernoulli bias 0.5 loop-len 8}}]
  (let [deja-vu-atom (or deja-vu (atom 0.0))
        b            (double bias)
        gen-fn       (case mode
                       :complementary-bernoulli #(< (rand) b)
                       :independent-bernoulli   #(< (rand) b)
                       :three-states            (fn []
                                                  (let [r (rand)]
                                                    (< r (* b 2/3))))
                       #(< (rand) b))]
    (make-deja-vu-source gen-fn loop-len deja-vu-atom)))

;; ---------------------------------------------------------------------------
;; T-section tick: correlated multi-channel generation (Q37)
;;
;; For complementary-bernoulli: channels share a single coin flip per tick.
;; Channel 0 generates; channels 1+ read the cached result from the same tick.
;; ---------------------------------------------------------------------------

(defn- make-t-channels
  "Return a vector of N per-channel T-source fns for the given mode.

  For :complementary-bernoulli, a shared coin flip is used: channel 0 fires
  when the coin is heads, channel 1 fires when it is tails, all other channels
  are independent. The tick is computed atomically on the first call and cached
  for subsequent channels within the same logical step.

  For other modes, each channel is an independent Bernoulli draw."
  [mode n-channels bias deja-vu-atom loop-len]
  (case mode
    :complementary-bernoulli
    (let [shared-tick (atom {:id -1 :values nil})
          tick-id     (atom 0)]
      (mapv (fn [ch]
              (let [gen-fn (fn []
                             (if (zero? ch)
                               ;; Channel 0: generate a new tick
                               (let [coin (< (rand) (double bias))
                                     vals (-> (vec (repeat n-channels false))
                                              (assoc 0 coin)
                                              (assoc (min 1 (dec n-channels)) (not coin)))]
                                 (reset! shared-tick {:id (swap! tick-id inc) :values vals})
                                 coin)
                               ;; Channel 1+: read from cache (may be stale if called out-of-order)
                               (boolean (get (:values @shared-tick) ch false))))]
                (make-deja-vu-source gen-fn loop-len deja-vu-atom)))
            (range n-channels)))

    ;; All other modes: independent per channel
    (mapv (fn [_]
            (make-deja-vu-source
              #(< (rand) (double bias))
              loop-len deja-vu-atom))
          (range n-channels))))

;; ---------------------------------------------------------------------------
;; defstochastic macro (§23.8, Q38)
;; ---------------------------------------------------------------------------

(defn make-stochastic-context
  "Build a stochastic context map from raw keyword options."
  [opts]
  (let [{:keys [channels
                x-spread x-bias x-steps x-scale x-range x-deja-vu x-loop-len
                t-mode   t-bias          t-deja-vu t-loop-len]
         :or   {channels  2
                x-spread  0.5  x-bias 0.5 x-steps 0.5 x-range [48 84] x-loop-len 8
                t-mode    :independent-bernoulli
                t-bias    0.5                                  t-loop-len 8}} opts
        x-dv  (or x-deja-vu (atom 0.0))
        t-dv  (or t-deja-vu (atom 0.0))
        x-srcs (mapv (fn [_]
                       (stochastic-sequence
                         :spread   x-spread
                         :bias     x-bias
                         :steps    x-steps
                         :scale    x-scale
                         :range    x-range
                         :deja-vu  x-dv
                         :loop-len x-loop-len))
                     (range channels))
        t-srcs (make-t-channels t-mode channels t-bias t-dv t-loop-len)]
    {:channels channels
     :x-sources x-srcs
     :t-sources t-srcs
     :x-deja-vu x-dv
     :t-deja-vu t-dv
     :opts      opts}))

(defmacro defstochastic
  "Define a named stochastic context and register it in the ctrl tree.

  Creates a var bound to the context map and registers it at
  [:stochastic <name>] in the ctrl tree (Q38 auto-registration).

  Named context survives live-loop redefinitions if declared with defonce (Q34).

  Parameters:
    :channels   — number of correlated X/T output channels (default 2)
    ;; X section (pitch/value)
    :x-spread   — distribution width [0=constant, 1=uniform] (default 0.5)
    :x-bias     — distribution center [0.0-1.0] (default 0.5)
    :x-steps    — quantization depth [0.5=raw, 1=root-only] (default 0.5)
    :x-scale    — weighted-scale map or nil
    :x-range    — [lo hi] MIDI note range (default [48 84])
    :x-deja-vu  — atom for deja-vu parameter (default fresh (atom 0.0))
    :x-loop-len — ring buffer length (default 8)
    ;; T section (rhythm/gate)
    :t-mode     — :complementary-bernoulli, :independent-bernoulli, :three-states
    :t-bias     — gate probability (default 0.5)
    :t-deja-vu  — atom for deja-vu parameter (default fresh (atom 0.0))
    :t-loop-len — ring buffer length (default 8)

  Example:
    (defstochastic my-gen
      :x-spread 0.4 :x-bias 0.6 :x-range [48 72]
      :x-scale  (random/weighted-scale :dorian)
      :t-mode   :complementary-bernoulli :t-bias 0.55
      :channels 2)"
  [gen-name & opts]
  (let [opts-map (apply hash-map opts)]
    `(do
       (def ~gen-name (make-stochastic-context ~opts-map))
       (register! ~(keyword (name gen-name)) ~gen-name)
       (ctrl/defnode! [:stochastic ~(keyword (name gen-name))]
                      :type :data :value ~gen-name)
       ~gen-name)))

;; ---------------------------------------------------------------------------
;; Draw functions
;; ---------------------------------------------------------------------------

(defn next-x!
  "Draw the next X-section (pitch/value) for channel `ch`.

  `gen` — stochastic context from defstochastic
  `ch`  — channel index (0-based)

  Returns an integer MIDI note value."
  [gen ch]
  ((nth (:x-sources gen) ch)))

(defn next-t!
  "Draw the next T-section (rhythm/gate) for channel `ch`.

  `gen` — stochastic context from defstochastic
  `ch`  — channel index (0-based)

  Returns true (fires) or false (silent).

  For :complementary-bernoulli mode, call channels in order 0, 1, ... per step
  to ensure correct mutual-exclusion correlation."
  [gen ch]
  ((nth (:t-sources gen) ch)))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn stochastic-names
  "Return a seq of registered stochastic context names."
  []
  (or (keys @registry) '()))
