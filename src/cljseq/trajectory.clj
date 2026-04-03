; SPDX-License-Identifier: EPL-2.0
(ns cljseq.trajectory
  "Trajectory primitives — time-bounded, curve-shaped transitions.

  A `Trajectory` is an `ITemporalValue` that moves a value from `from` to
  `to` over `beats` beats starting at `start-beat`, following a named curve.
  Once complete it clamps at `to` and `next-edge` returns ##Inf, so mod-route!
  runners auto-stop — identical behaviour to a `cljseq.mod/OneShot`.

  ## Curve shapes

    :linear      — straight ramp; t is used directly
    :exponential — slow start, fast end; t² (concave up)
    :reverse-exp — fast start, slow end; 1-(1-t)² (concave down / decay)
    :s-curve     — slow-fast-slow; cubic Hermite 3t²-2t³
    :cosine      — smooth ease-in/out; (1-cos(π·t))/2
    :step        — holds at `from`, snaps to `to` at beat start+beats

  ## Usage

    (require '[cljseq.trajectory :as traj]
             '[cljseq.mod        :as mod]
             '[cljseq.loop       :refer [now]])

    ;; 32-bar trance buildup — filter cutoff 0→1
    (mod-route! [:filter1/cutoff]
                (traj/trajectory :from 0.0 :to 1.0 :beats 128 :curve :s-curve
                                 :start (now)))

    ;; Snap tension to max at the drop
    (traj/trajectory :from 0.8 :to 1.0 :beats 1 :curve :step :start (now))

  ## Named high-level gestures (return {path → trajectory} maps)

    (traj/buildup  :bars 32 :bpm 128)   ; tension+density+register S-curve
    (traj/breakdown :bars 8)             ; density+texture collapse
    (traj/groove-lock :bars 16)          ; hold momentum, ease tension

  Key design decisions: R&R §31 (trajectory primitive), Track C EDM vocabulary."
  (:require [cljseq.clock :as clock]))

;; ---------------------------------------------------------------------------
;; Curve functions
;;
;; All take t ∈ [0.0, 1.0] and return a value in [0.0, 1.0].
;; ---------------------------------------------------------------------------

(defn- curve-linear      ^double [^double t] t)
(defn- curve-exponential ^double [^double t] (* t t))
(defn- curve-reverse-exp ^double [^double t] (let [u (- 1.0 t)] (- 1.0 (* u u))))
(defn- curve-s-curve     ^double [^double t] (* t t (- 3.0 (* 2.0 t))))
(defn- curve-cosine      ^double [^double t] (* 0.5 (- 1.0 (Math/cos (* Math/PI t)))))
(defn- curve-step        ^double [^double t] (if (< t 1.0) 0.0 1.0))

(def ^:private curve-fns
  {:linear      curve-linear
   :exponential curve-exponential
   :reverse-exp curve-reverse-exp
   :s-curve     curve-s-curve
   :cosine      curve-cosine
   :step        curve-step})

;; ---------------------------------------------------------------------------
;; Trajectory record
;; ---------------------------------------------------------------------------

(defrecord Trajectory [from to beats curve-fn start-beat]
  clock/ITemporalValue
  (sample [_ beat]
    (let [from       (double from)
          to         (double to)
          beats      (double beats)
          start-beat (double start-beat)
          elapsed    (- (double beat) start-beat)
          t          (if (<= beats 0.0) 1.0
                         (max 0.0 (min 1.0 (/ elapsed beats))))
          curved     (curve-fn t)]
      (+ from (* curved (- to from)))))
  (next-edge [_ beat]
    (let [end-beat (+ (double start-beat) (double beats))]
      (if (>= (double beat) end-beat)
        ##Inf
        ;; Sample once per 1/8 beat — fine enough for smooth automation,
        ;; cheap enough for the runner thread.
        (+ (double beat) 0.125)))))

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn trajectory
  "Create a Trajectory — a time-bounded, curve-shaped ITemporalValue.

  Options:
    :from        — start value (default 0.0)
    :to          — end value   (default 1.0)
    :beats       — duration in beats (required)
    :curve       — one of :linear :exponential :reverse-exp :s-curve :cosine
                   :step  (default :linear)
    :start       — start beat position (default 0.0; use (now) in a live loop)

  The trajectory clamps at `to` once `start + beats` is reached. When routed
  via mod-route! the runner auto-stops (next-edge returns ##Inf at completion).

  Example:
    (trajectory :from 0.0 :to 1.0 :beats 32 :curve :s-curve :start (now))"
  [& {:keys [from to beats curve start]
      :or   {from 0.0 to 1.0 curve :linear start 0.0}}]
  {:pre [(some? beats)
         (contains? curve-fns curve)]}
  (->Trajectory (double from)
                (double to)
                (double beats)
                (get curve-fns curve)
                (double start)))

;; ---------------------------------------------------------------------------
;; Convenience: apply-curve
;;
;; Sample a curve fn directly by name — useful for testing and for building
;; higher-level gestures without constructing a full Trajectory record.
;; ---------------------------------------------------------------------------

(defn apply-curve
  "Apply named curve to t ∈ [0.0, 1.0]. Returns a double in [0.0, 1.0].

  Curves: :linear :exponential :reverse-exp :s-curve :cosine :step"
  ^double [curve ^double t]
  {:pre [(contains? curve-fns curve)]}
  ((get curve-fns curve) t))

;; ---------------------------------------------------------------------------
;; Named high-level gestures
;;
;; Return a map of ctrl-path → Trajectory so the caller can route them all:
;;
;;   (let [trajs (buildup :bars 32 :start (now))]
;;     (doseq [[path traj] trajs]
;;       (mod/mod-route! path traj)))
;; ---------------------------------------------------------------------------

(defn buildup
  "Return a trajectory map for a buildup gesture — coordinated rise of
  tension, density, register, and momentum.

  Options:
    :bars    — buildup duration in bars (1 bar = 4 beats; default 16)
    :start   — start beat (default 0.0; use (now) in a live loop)
    :curve   — curve shape (default :s-curve)
    :from    — start level for all arcs (default 0.1)
    :to      — end level for all arcs (default 1.0)"
  [& {:keys [bars start curve from to]
      :or   {bars 16 start 0.0 curve :s-curve from 0.1 to 1.0}}]
  (let [beats (* (double bars) 4.0)
        mk    #(trajectory :from from :to to :beats beats :curve curve :start start)]
    {[:arc/tension]  (mk)
     [:arc/density]  (mk)
     [:arc/register] (mk)
     [:arc/momentum] (trajectory :from from :to 0.95
                                 :beats beats :curve :step :start start)}))

(defn breakdown
  "Return a trajectory map for a breakdown — simultaneous collapse of
  density, texture, and momentum to near-zero.

  Options:
    :bars    — duration in bars (default 8)
    :start   — start beat (default 0.0)
    :curve   — curve shape (default :reverse-exp)"
  [& {:keys [bars start curve]
      :or   {bars 8 start 0.0 curve :reverse-exp}}]
  (let [beats (* (double bars) 4.0)
        mk    #(trajectory :from 1.0 :to 0.05 :beats beats :curve curve :start start)]
    {[:arc/density]  (mk)
     [:arc/texture]  (mk)
     [:arc/momentum] (trajectory :from 1.0 :to 0.1
                                 :beats beats :curve :linear :start start)}))

(defn groove-lock
  "Return a trajectory map for a post-drop groove-lock — holds momentum and
  density high while tension eases toward a resting level.

  Options:
    :bars    — duration in bars (default 16)
    :start   — start beat (default 0.0)
    :tension-to — target tension level (default 0.4)"
  [& {:keys [bars start tension-to]
      :or   {bars 16 start 0.0 tension-to 0.4}}]
  (let [beats (* (double bars) 4.0)]
    {[:arc/tension]  (trajectory :from 1.0 :to tension-to
                                 :beats beats :curve :cosine :start start)
     [:arc/momentum] (trajectory :from 1.0 :to 0.9
                                 :beats beats :curve :linear :start start)
     [:arc/density]  (trajectory :from 1.0 :to 0.85
                                 :beats beats :curve :linear :start start)}))

(defn wind-down
  "Return a trajectory map for a section wind-down — coordinated decrease
  across all arc types toward silence.

  Options:
    :bars    — duration in bars (default 8)
    :start   — start beat (default 0.0)"
  [& {:keys [bars start]
      :or   {bars 8 start 0.0}}]
  (let [beats (* (double bars) 4.0)
        mk    #(trajectory :from %1 :to %2 :beats beats :curve :cosine :start start)]
    {[:arc/tension]  (mk 0.4 0.0)
     [:arc/density]  (mk 0.85 0.0)
     [:arc/momentum] (mk 0.9  0.1)
     [:arc/texture]  (mk 0.85 0.0)
     [:arc/register] (mk 0.5  0.2)}))
