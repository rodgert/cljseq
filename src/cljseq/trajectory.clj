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
    :smooth-step — smoother S-curve; Perlin 6t⁵-15t⁴+10t³ (zero 2nd deriv at endpoints)
    :cosine      — smooth ease-in/out; (1-cos(π·t))/2
    :breathe     — rise and fall; sin(π·t) — peaks at midpoint, returns to `from`
    :bounce      — settle with overshoot; Penner bounce-out easing, 3 decaying bounces
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

    (traj/buildup        :bars 32)  ; tension+density+register S-curve rise
    (traj/trance-buildup :bars 32)  ; long trance-style build: slow tension, dramatic register
    (traj/breakdown      :bars 8)   ; density+texture collapse
    (traj/anticipation   :bars 2)   ; pre-drop silence: everything collapses, tension holds
    (traj/groove-lock    :bars 16)  ; hold momentum, ease tension
    (traj/wind-down      :bars 8)   ; coordinated fade to silence
    (traj/swell          :bars 8)   ; rise-and-fall (uses :breathe curve)
    (traj/tension-peak   :bars 2)   ; sharp spike then instant drop (fill/accent)

  ## Utilities

    ;; Blend two arc maps — overlapping paths weighted-average their samples
    (traj/arc-merge (traj/buildup :bars 8) (traj/swell :bars 8) :weight 0.7)

    ;; Retime all Trajectory values in an arc map to a new bar count
    (traj/time-warp (traj/buildup :bars 16) 8 :start (now))

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

(defn- curve-breathe
  "sin(π·t) — rises from 0 to 1 at the midpoint, returns to 0 at t=1.
  Used to shape swells: a Trajectory with :breathe peaks at `to` at the midpoint
  and returns to `from` at completion. The mod runner auto-stops at completion
  (next-edge → ##Inf), leaving the parameter at its starting value."
  ^double [^double t]
  (Math/sin (* Math/PI t)))

(defn- curve-smooth-step
  "Ken Perlin's smootherstep: 6t⁵ - 15t⁴ + 10t³.
  Zero first AND second derivative at t=0 and t=1 — smoother than :s-curve
  (cubic Hermite), with no perceived 'kink' at the endpoints."
  ^double [^double t]
  (* t t t (+ 10.0 (* t (- (* 6.0 t) 15.0)))))

(defn- curve-bounce
  "Robert Penner's bounce-out easing — settles at 1.0 with three decaying
  overshoots. Starts at 0.0 and ends at 1.0 like all other curves.

  The bounce moves toward the target (1.0) and overshoots it twice before
  settling, creating a springy feel. Useful for snappy arc entries and
  attention-grabbing accents."
  ^double [^double t]
  (let [n1 7.5625
        d1 2.75]
    (cond
      (< t (/ 1.0 d1))
      (* n1 t t)
      (< t (/ 2.0 d1))
      (let [t' (- t (/ 1.5 d1))]
        (+ (* n1 t' t') 0.75))
      (< t (/ 2.5 d1))
      (let [t' (- t (/ 2.25 d1))]
        (+ (* n1 t' t') 0.9375))
      :else
      (let [t' (- t (/ 2.625 d1))]
        (+ (* n1 t' t') 0.984375)))))

(def ^:private curve-fns
  {:linear      curve-linear
   :exponential curve-exponential
   :reverse-exp curve-reverse-exp
   :s-curve     curve-s-curve
   :smooth-step curve-smooth-step
   :cosine      curve-cosine
   :breathe     curve-breathe
   :bounce      curve-bounce
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
    :curve       — one of :linear :exponential :reverse-exp :s-curve :smooth-step
                   :cosine :breathe :step  (default :linear)
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

(defn swell
  "Return a trajectory map for a swell — a rise-and-fall gesture that peaks
  at the midpoint and returns to the background level at completion.

  Uses the :breathe curve (sin(π·t)): the Trajectory peaks at `peak-level`
  halfway through, then decays back to `from` at the end. Because :breathe
  ends at 0, the arc nodes are left at `from` when the mod runner auto-stops
  — no additional cleanup needed.

  Options:
    :bars        — duration in bars (default 8)
    :start       — start beat (default 0.0)
    :from        — background level at start and end (default 0.2)
    :peak-level  — peak value at the midpoint (default 1.0)

  Example:
    (mod-route! [:arc/tension]
                (first (vals (traj/swell :bars 4 :start (now) :peak-level 0.9))))"
  [& {:keys [bars start from peak-level]
      :or   {bars 8 start 0.0 from 0.2 peak-level 1.0}}]
  (let [beats (* (double bars) 4.0)
        mk    #(trajectory :from from :to peak-level :beats beats
                           :curve :breathe :start start)]
    {[:arc/tension]  (mk)
     [:arc/density]  (mk)
     [:arc/register] (trajectory :from from :to (* (double peak-level) 0.8)
                                 :beats beats :curve :breathe :start start)}))

(defn tension-peak
  "Return a trajectory map for a tension peak — a sharp spike in tension
  followed by an immediate collapse. Designed for fills and accents before
  a drop or section change.

  The spike uses :exponential (fast rise) for tension and a corresponding
  :reverse-exp rise in density. Both snap back at completion (0-bar drop)
  via :step.

  Options:
    :bars   — rise duration in bars (default 2); the collapse is instant
    :start  — start beat (default 0.0)
    :peak   — peak tension level (default 1.0)"
  [& {:keys [bars start peak]
      :or   {bars 2 start 0.0 peak 1.0}}]
  (let [beats (* (double bars) 4.0)]
    {[:arc/tension]  (trajectory :from 0.3 :to peak
                                 :beats beats :curve :exponential :start start)
     [:arc/density]  (trajectory :from 0.5 :to 0.9
                                 :beats beats :curve :reverse-exp :start start)
     [:arc/momentum] (trajectory :from 0.8 :to 0.3
                                 :beats beats :curve :linear :start start)}))

(defn trance-buildup
  "Return a trajectory map for a trance-style buildup — longer, more dramatic
  than `buildup`. Register peaks sharply, tension rises very slowly then
  accelerates, density builds early and plateaus, momentum snaps on at bar N-2.

  Options:
    :bars    — buildup duration in bars (default 32)
    :start   — start beat (default 0.0)
    :from    — start level for tension and density (default 0.05)

  Typical usage:
    (trance-buildup :bars 32 :start (now))"
  [& {:keys [bars start from]
      :or   {bars 32 start 0.0 from 0.05}}]
  (let [beats      (* (double bars) 4.0)
        snap-beats (- beats 8.0)]  ; momentum snap 2 bars before end
    {[:arc/tension]  (trajectory :from from :to 1.0
                                 :beats beats :curve :exponential :start start)
     [:arc/density]  (trajectory :from from :to 0.9
                                 :beats beats :curve :reverse-exp :start start)
     [:arc/register] (trajectory :from 0.2 :to 1.0
                                 :beats beats :curve :s-curve :start start)
     [:arc/momentum] (trajectory :from 0.0 :to 0.95
                                 :beats (max 1.0 snap-beats) :curve :step :start start)}))

(defn anticipation
  "Return a trajectory map for an anticipation moment — the brief silence
  just before a drop. Density, texture, and momentum collapse to near-zero
  while tension holds at maximum (unresolved pull toward the drop).

  Options:
    :bars    — duration in bars (default 2; typically 1–4)
    :start   — start beat (default 0.0)

  Anticipation is typically followed immediately by a :drop section in the
  conductor, or by a manual fire of the drop gesture."
  [& {:keys [bars start]
      :or   {bars 2 start 0.0}}]
  (let [beats (* (double bars) 4.0)
        mk    #(trajectory :from %1 :to %2 :beats beats :curve :cosine :start start)]
    {[:arc/tension]  (mk 0.8 1.0)    ; tension climbs to max (unresolved)
     [:arc/density]  (mk 0.8 0.02)   ; density collapses
     [:arc/texture]  (mk 0.8 0.0)    ; texture to silence
     [:arc/momentum] (mk 0.9 0.05)})) ; momentum drains

;; ---------------------------------------------------------------------------
;; Arc map utilities
;; ---------------------------------------------------------------------------

(defrecord BlendedTrajectory [a b weight]
  clock/ITemporalValue
  (sample [_ beat]
    (let [w (double weight)]
      (+ (* w       (clock/sample a beat))
         (* (- 1.0 w) (clock/sample b beat)))))
  (next-edge [_ beat]
    (min (clock/next-edge a beat)
         (clock/next-edge b beat))))

(defn arc-merge
  "Blend two arc maps into one.

  For paths present in both maps the result samples a weighted average:
    value = weight·a + (1-weight)·b

  Paths present in only one map pass through unchanged.

  Options:
    :weight — how much of `arc-a` to use (default 0.5; 1.0 = pure arc-a)

  Example — 70% buildup, 30% swell on shared paths:
    (arc-merge (buildup :bars 8) (swell :bars 8) :weight 0.7)"
  [arc-a arc-b & {:keys [weight] :or {weight 0.5}}]
  (merge-with #(->BlendedTrajectory %1 %2 weight) arc-a arc-b))

(defn time-warp
  "Retime all Trajectory values in `arc-map` to span `new-bars` bars.

  Replaces the `:beats` and optionally `:start-beat` of every Trajectory
  in the map while preserving the original curve shape, from, and to values.
  Non-Trajectory ITemporalValues (BlendedTrajectory, OneShot, etc.) are
  passed through unchanged.

  Options:
    :start — new start beat for all trajectories; if omitted each trajectory
             keeps its original start-beat

  Example:
    ;; Take a 16-bar buildup template and squeeze it into 8 bars
    (time-warp (buildup :bars 16 :start 0.0) 8 :start (now))"
  [arc-map new-bars & {:keys [start]}]
  (let [new-beats (* (double new-bars) 4.0)]
    (reduce-kv
      (fn [m path tr]
        (assoc m path
               (if (instance? Trajectory tr)
                 (->Trajectory (:from tr) (:to tr) new-beats (:curve-fn tr)
                               (if (some? start) (double start) (double (:start-beat tr))))
                 tr)))
      {}
      arc-map)))
