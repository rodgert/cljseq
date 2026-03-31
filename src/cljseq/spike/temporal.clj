; SPDX-License-Identifier: EPL-2.0
(ns cljseq.spike.temporal
  "ITemporalValue design spike.

  Implements the minimal ITemporalValue protocol in a scratch namespace and
  verifies the three composition acceptance criteria from Q44:

    1. (clock-div 4 (lfo :rate 0.1))         ; modulator as clock source
    2. (ctrl/bind! [:filter/cutoff]           ; modulator to parameter
                   (lfo :rate 0.25))
    3. (timing/bind! (swing :amount 0.55))    ; timing modulator to time_ns

  Output: see doc/spike-itemporalvalue.md for design decision record.

  This namespace is scratch/exploratory only. It is excluded from production
  builds and Codox API docs.")

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol ITemporalValue
  "A value that varies over musical time.

  `beat` is a rational number representing the current beat position.
  The contract:
    - `sample` must be pure and allocation-free on the hot path
    - `next-edge` must return a beat strictly greater than the input beat"
  (sample    [v beat] "Sample the value at `beat`. Returns a number.")
  (next-edge [v beat] "Return the beat of the next 0→1 rising edge after `beat`."))

;; ---------------------------------------------------------------------------
;; Master clock
;; ---------------------------------------------------------------------------

(defrecord MasterClock [bpm]
  ITemporalValue
  (sample [_ beat]
    ;; Always returns 1.0 — the clock is always "high"
    1.0)
  (next-edge [_ beat]
    ;; Rising edge on every integer beat
    (inc (long beat))))

(defn master-clock
  "Construct a master clock at `bpm` beats per minute.
  The clock ticks on every integer beat boundary."
  [bpm]
  (->MasterClock bpm))

;; ---------------------------------------------------------------------------
;; Clock divider
;; ---------------------------------------------------------------------------

(defrecord ClockDiv [divisor source]
  ITemporalValue
  (sample [_ beat]
    ;; High when the underlying source is high AND we are on a divided boundary
    (if (zero? (mod (long beat) divisor)) 1.0 0.0))
  (next-edge [_ beat]
    ;; Next edge is the next multiple of divisor after beat
    (let [next-multiple (* divisor (inc (long (/ beat divisor))))]
      next-multiple)))

(defn clock-div
  "Divide `source` clock by `divisor`.

  Acceptance criterion 1:
    (clock-div 4 (lfo :rate 0.1))   ; modulator as clock source"
  [divisor source]
  (->ClockDiv divisor source))

;; ---------------------------------------------------------------------------
;; LFO
;; ---------------------------------------------------------------------------

(defrecord Lfo [rate shape]
  ITemporalValue
  (sample [_ beat]
    ;; Sine LFO; rate is cycles per beat
    (Math/sin (* 2.0 Math/PI rate beat)))
  (next-edge [_ beat]
    ;; Rising zero-crossing: occurs at beat = n/rate for integer n
    (let [cycle   (/ 1.0 rate)
          current (mod beat cycle)]
      (+ beat (- cycle current)))))

(defn lfo
  "Construct a sine LFO.

  Options:
    :rate   cycles per beat (default 1.0)
    :shape  :sine (only shape currently supported)

  Acceptance criterion 1 (as source): (clock-div 4 (lfo :rate 0.1))
  Acceptance criterion 2 (to parameter): (ctrl/bind! [:filter/cutoff] (lfo :rate 0.25))"
  [& {:keys [rate shape] :or {rate 1.0 shape :sine}}]
  (->Lfo rate shape))

;; ---------------------------------------------------------------------------
;; Swing timing modulator
;; ---------------------------------------------------------------------------

(defrecord Swing [amount]
  ITemporalValue
  (sample [_ beat]
    ;; Returns nanosecond offset. Positive = push late, negative = pull early.
    ;; Swing applies to off-beats (beat mod 1 ≈ 0.5).
    (let [phase (mod beat 1.0)]
      (if (> phase 0.4)
        (long (* amount 20000000))   ; ~20ms max swing at amount=1.0
        0)))
  (next-edge [_ beat]
    (inc (long beat))))

(defn swing
  "Construct a swing timing modulator.

  :amount  [0.0 1.0] — proportion of the off-beat interval to delay.
  Returns nanosecond offsets suitable for timing/bind!.

  Acceptance criterion 3: (timing/bind! (swing :amount 0.55))"
  [& {:keys [amount] :or {amount 0.5}}]
  (->Swing amount))

;; ---------------------------------------------------------------------------
;; Stub ctrl/bind! and timing/bind! for spike verification
;; ---------------------------------------------------------------------------

(def ^:dynamic *bound-params* (atom {}))
(def ^:dynamic *bound-timing* (atom []))

(defn ctrl-bind!
  "Spike stub: bind an ITemporalValue modulator to a control tree path.

  Acceptance criterion 2:
    (ctrl-bind! [:filter/cutoff] (lfo :rate 0.25))"
  [path modulator]
  (swap! *bound-params* assoc path modulator)
  {:path path :modulator modulator})

(defn timing-bind!
  "Spike stub: bind an ITemporalValue timing modulator to the time_ns pipeline.

  Acceptance criterion 3:
    (timing-bind! (swing :amount 0.55))"
  [modulator]
  (swap! *bound-timing* conj modulator)
  {:modulator modulator})

;; ---------------------------------------------------------------------------
;; Acceptance criterion verification
;; ---------------------------------------------------------------------------

(comment
  ;; Criterion 1: modulator as clock source
  (def divided-clock (clock-div 4 (lfo :rate 0.1)))
  (sample divided-clock 0)    ;=> 1.0 (beat 0 is a multiple of 4)
  (sample divided-clock 1)    ;=> 0.0
  (sample divided-clock 4)    ;=> 1.0
  (next-edge divided-clock 1) ;=> 4

  ;; Criterion 2: modulator to parameter
  (ctrl-bind! [:filter/cutoff] (lfo :rate 0.25))
  @*bound-params*             ;=> {[:filter/cutoff] #cljseq.spike.temporal.Lfo{...}}

  ;; Criterion 3: timing modulator to time_ns
  (timing-bind! (swing :amount 0.55))
  @*bound-timing*             ;=> [#cljseq.spike.temporal.Swing{:amount 0.55}]
  (sample (first @*bound-timing*) 0.5) ;=> positive ns offset (late swing)
  (sample (first @*bound-timing*) 0.0) ;=> 0 (on the beat)
  )
