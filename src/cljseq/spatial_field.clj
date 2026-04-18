; SPDX-License-Identifier: EPL-2.0
(ns cljseq.spatial-field
  "Physics particle field — spatial generation, modulation, and routing.

  A Spatial Field is a 2D bounded manifold (N-gon room) where particles
  travel under configurable physics. Their positions drive musical output:

    :generation — collisions fire note events (Ricochet/Dribble analogue)
    :modulation — particle positions continuously update ctrl tree values
    :routing    — particle zone determines target device/channel for events

  ## Quick start

    (require '[cljseq.user :refer :all])
    (session!)

    ;; Ricochet: launch a particle on each play!, collisions spawn notes
    (defspatial-field! :my-room
      {:mode :generation :sides 4.7 :ball-speed 1.0 :ball-damping 0.85
       :mappings {:x :pan :y :pitch}})
    (start-field! :my-room)

    ;; Use as ITransformer — composes with other transformers
    (deflive-loop :bouncer {}
      (play-transformed! {:pitch/midi 60 :dur/beats 1/4}
                         (field-transformer :my-room))
      (sleep! 2))

    ;; Slow ergodic pan — modulation mode
    (defspatial-field! :pan-drift
      {:mode :modulation :sides 5.3 :ball-speed 0.1 :ball-damping 1.0
       :particles 1 :mappings {:x :pan :y :reverb-send}})
    (start-field! :pan-drift)

    (stop-field! :my-room)

  ## Physics

    :forces {:gravity   {:direction :down :strength 0.3}
             :attractor {:position [0.5 0.5] :strength 0.5 :falloff :inverse-square}
             :repeller  {:position [0.0 0.0] :strength 0.8}
             :damping   0.98       ; velocity multiplier per step
             :wind      {:direction :right :strength 0.1}}

  ## Axis mappings

    :mappings {:x :pan          ; X position → stereo pan
               :y :pitch        ; Y position → pitch offset
               :r :velocity     ; radial distance → velocity
               :speed :cc-74}   ; speed → MIDI CC 74

  ## Built-in presets

    (start-field! :ricochet)    ; classic ball-in-box, generation
    (start-field! :drift)       ; slow ergodic pan, modulation
    (start-field! :orbit)       ; center-attractor sweep, modulation
    (start-field! :pinball)     ; dense heptagon, generation
    (start-field! :dribble)     ; gravity bounce 1D, generation"
  (:require [cljseq.core      :as core]
            [cljseq.ctrl      :as ctrl]
            [cljseq.transform :as xf]))

;; ---------------------------------------------------------------------------
;; N-gon geometry
;; ---------------------------------------------------------------------------

(defn- ngon-vertices
  "Generate vertices of a regular N-gon (decimal N supported) centred at
  [cx cy] with radius r. Vertices start at the top (π/2) and go clockwise.

  For decimal N (e.g. 4.7), angular spacing is 2π/N between consecutive
  vertices, producing an asymmetric polygon: the closing wall spans the gap
  between the last vertex and the first — its length and angle differ from
  the regular walls, encoding the fractional asymmetry."
  [n & {:keys [cx cy r] :or {cx 0.5 cy 0.5 r 0.45}}]
  (let [n-int (int (Math/ceil n))
        step  (/ (* 2.0 Math/PI) n)]
    (mapv (fn [k]
            (let [angle (- (/ Math/PI 2.0) (* k step))]
              [(+ cx (* r (Math/cos angle)))
               (+ cy (* r (Math/sin angle)))]))
          (range n-int))))

(defn- wall-normal
  "Compute the inward-pointing unit normal for a wall from a to b.
  'Inward' is the direction toward the centroid [cx cy]."
  [[ax ay] [bx by] cx cy]
  (let [dx  (- bx ax)
        dy  (- by ay)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))
        ;; Perpendicular: rotate 90° — two candidates
        nx1 (/ (- dy) len)
        ny1 (/ dx len)
        ;; Pick the one pointing toward the centroid
        mx  (/ (+ ax bx) 2.0)
        my  (/ (+ ay by) 2.0)
        dot (+ (* (- cx mx) nx1) (* (- cy my) ny1))]
    (if (>= dot 0.0) [nx1 ny1] [(- nx1) (- ny1)])))

(defn ngon-walls
  "Build the wall list for an N-gon room.
  Each wall: {:a [x1 y1] :b [x2 y2] :normal [nx ny] :type type}

  Options:
    :cx :cy  — centre (default 0.5)
    :r       — radius (default 0.45)
    :type    — boundary type for all walls (default :reflect)
    :wall-types — per-wall type vector; overrides :type"
  [n & {:keys [cx cy r type wall-types]
        :or   {cx 0.5 cy 0.5 r 0.45 type :reflect}}]
  (let [verts (ngon-vertices n :cx cx :cy cy :r r)
        nv    (count verts)]
    (mapv (fn [i]
            (let [a  (verts i)
                  b  (verts (mod (inc i) nv))
                  wt (if wall-types (nth wall-types i type) type)]
              {:a      a
               :b      b
               :normal (wall-normal a b cx cy)
               :type   wt}))
          (range nv))))

;; ---------------------------------------------------------------------------
;; Ray–wall intersection
;; ---------------------------------------------------------------------------

(defn- ray-wall-t
  "Return the t value (time to collision) for a ray from `pos` in direction
  `vel` hitting wall segment a→b. Returns ##Inf if no valid intersection
  (parallel, behind, or outside segment)."
  [[px py] [vx vy] [ax ay] [bx by]]
  (let [dx (- bx ax)
        dy (- by ay)
        ;; det = dx*vy - dy*vx
        det (- (* dx vy) (* dy vx))]
    (if (< (Math/abs ^double det) 1e-10)
      ##Inf   ; parallel
      (let [;; t = ((px-ax)*dy - (py-ay)*dx) / det
            t (/ (- (* (- px ax) dy) (* (- py ay) dx)) det)
            ;; s = ((px-ax)*vy - (py-ay)*vx) / det
            s (/ (- (* (- px ax) vy) (* (- py ay) vx)) det)]
        (if (and (> t 1e-6) (<= 0.0 s 1.0))
          t
          ##Inf)))))

(defn find-next-collision
  "Find the nearest wall collision for a particle.
  Returns {:t time-to-collision :wall-idx index :wall wall-map}
  or {:t ##Inf} if no collision found within the walls."
  [particle walls]
  (let [{[px py] :position [vx vy] :velocity} particle]
    (reduce (fn [best [idx wall]]
              (let [t (ray-wall-t [px py] [vx vy]
                                  (:a wall) (:b wall))]
                (if (< t (:t best))
                  {:t t :wall-idx idx :wall wall}
                  best)))
            {:t ##Inf}
            (map-indexed vector walls))))

;; ---------------------------------------------------------------------------
;; Reflection and boundary handling
;; ---------------------------------------------------------------------------

(defn- reflect-velocity
  "Reflect velocity vector off a wall with the given inward unit normal."
  [[vx vy] [nx ny]]
  (let [dot (* 2.0 (+ (* vx nx) (* vy ny)))]
    [(- vx (* dot nx))
     (- vy (* dot ny))]))

(defn- speed
  [[vx vy]]
  (Math/sqrt (+ (* vx vx) (* vy vy))))

;; ---------------------------------------------------------------------------
;; Forces
;; ---------------------------------------------------------------------------

(defn- apply-gravity
  [[vx vy] {:keys [direction strength] :or {direction :down strength 0.3}} dt]
  (case direction
    :down  [vx (- vy (* strength dt))]
    :up    [vx (+ vy (* strength dt))]
    :left  [(- vx (* strength dt)) vy]
    :right [(+ vx (* strength dt)) vy]
    [vx vy]))

(defn- apply-attractor
  [vel [px py] {:keys [position strength falloff] :or {strength 0.5 falloff :inverse-square}} dt attract?]
  (let [[tx ty] position
        dx  (- tx px)
        dy  (- ty py)
        d2  (+ (* dx dx) (* dy dy))
        d   (Math/sqrt (max d2 1e-6))
        mag (case falloff
              :inverse-square (* strength (/ 1.0 (max d2 0.01)) dt)
              :linear         (* strength (/ 1.0 (max d 0.01)) dt)
              (* strength dt))
        sign (if attract? 1.0 -1.0)]
    [(+ (first vel)  (* sign (/ dx d) mag))
     (+ (second vel) (* sign (/ dy d) mag))]))

(defn- apply-wind
  [[vx vy] {:keys [direction strength] :or {direction :right strength 0.1}} dt]
  (case direction
    :right [(+ vx (* strength dt)) vy]
    :left  [(- vx (* strength dt)) vy]
    :up    [vx (+ vy (* strength dt))]
    :down  [vx (- vy (* strength dt))]
    [vx vy]))

(defn apply-forces
  "Apply all configured forces to a particle, returning updated velocity."
  [particle forces dt]
  (let [{:keys [position velocity]} particle
        vel velocity]
    (cond-> vel
      (:gravity forces)
      (apply-gravity (:gravity forces) dt)

      (:wind forces)
      (apply-wind (:wind forces) dt)

      (:attractor forces)
      (apply-attractor position (:attractor forces) dt true)

      (:repeller forces)
      (apply-attractor position (:repeller forces) dt false)

      (:damping forces)
      (#(let [d (:damping forces)]
          [(* (first %) d) (* (second %) d)])))))

;; ---------------------------------------------------------------------------
;; Particle step
;; ---------------------------------------------------------------------------

(defn step-particle
  "Advance particle by dt seconds, handling collisions.
  Returns {:particle updated-particle :events [{:wall wall :position pos}...]}"
  [particle walls forces dt]
  (let [vel   (apply-forces particle forces dt)
        part' (assoc particle :velocity vel)]
    (if (< (speed vel) 1e-6)
      {:particle part' :events []}
      (let [{:keys [t wall]} (find-next-collision part' walls)]
        (if (>= t dt)
          ;; No collision in this step — advance freely
          {:particle (-> part'
                         (update :position (fn [[px py]]
                                             [(+ px (* (first vel) dt))
                                              (+ py (* (second vel) dt))]))
                         (update :age inc))
           :events []}
          ;; Collision within step — advance to collision, handle, recurse
          (let [hit-pos   [(+ (get-in part' [:position 0]) (* (first vel) t))
                            (+ (get-in part' [:position 1]) (* (second vel) t))]
                wall-type (:type wall)
                new-vel   (case wall-type
                            :reflect  (reflect-velocity (:velocity part') (:normal wall))
                            :portal   (reflect-velocity (:velocity part') (:normal wall))
                            :generate (reflect-velocity (:velocity part') (:normal wall))
                            :absorb   [0.0 0.0]
                            (reflect-velocity (:velocity part') (:normal wall)))
                part''    (assoc part' :position hit-pos :velocity new-vel)
                remaining (- dt t)
                event     {:wall wall :position hit-pos :particle part''}]
            (if (or (= wall-type :absorb) (<= remaining 1e-7))
              {:particle part'' :events [event]}
              (let [result (step-particle part'' walls forces remaining)]
                {:particle (:particle result)
                 :events   (into [event] (:events result))}))))))))


;; ---------------------------------------------------------------------------
;; Axis mappings — particle state → musical parameter value
;; ---------------------------------------------------------------------------

(defn particle-axes
  "Compute all mappable axis values for a particle."
  [{[px py] :position [vx vy] :velocity :as p}]
  (let [cx    0.5
        cy    0.5
        dx    (- px cx)
        dy    (- py cy)
        r     (Math/sqrt (+ (* dx dx) (* dy dy)))
        theta (Math/atan2 dy dx)
        spd   (speed (:velocity p))]
    {:x px :y py :r r :θ theta :vx vx :vy vy :speed spd :age (:age p)}))

(defn- map-range
  "Linearly map v from [in-lo in-hi] to [out-lo out-hi]."
  [v in-lo in-hi out-lo out-hi]
  (let [t (/ (- v in-lo) (- in-hi in-lo))]
    (+ out-lo (* t (- out-hi out-lo)))))

(defn- curve-fn [curve]
  (case curve
    :linear  identity
    :exponential (fn [t] (* t t))
    :logarithmic (fn [t] (Math/sqrt (max 0.0 t)))
    :s-curve (fn [t] (* t t (- 3.0 (* 2.0 t))))
    identity))

(defn apply-mapping
  "Apply a single axis mapping to a particle state, returning [target value].
  Mapping: {:from axis-kw :to target-kw :range [lo hi] :curve :linear}"
  [axes {:keys [from to range curve] :or {curve :linear}}]
  (let [raw   (get axes from 0.0)
        ;; Default input ranges per axis
        in-lo (case from :r 0.0 :speed 0.0 :age 0.0 -1.0)
        in-hi (case from :r 0.5 :speed 3.0 :age 1000.0 1.0)
        cfn   (curve-fn curve)
        t     (cfn (max 0.0 (min 1.0 (map-range raw in-lo in-hi 0.0 1.0))))
        [out-lo out-hi] (or range [0.0 1.0])
        val   (+ out-lo (* t (- out-hi out-lo)))]
    [to val]))

(defn map-particle
  "Apply all mappings to a particle, returning a map of {target value}.
  mappings: {:x :pan :y :pitch} or
            {:x {:to :pan :range [-1 1]} :y {:to :pitch :range [-12 12]}}"
  [particle mappings]
  (let [axes (particle-axes particle)]
    (into {}
          (map (fn [[from-or-kw to-or-spec]]
                 (let [spec (if (keyword? to-or-spec)
                              {:from from-or-kw :to to-or-spec}
                              (assoc to-or-spec :from from-or-kw))]
                   (apply-mapping axes spec)))
               mappings))))

;; ---------------------------------------------------------------------------
;; VBAP quad output
;; ---------------------------------------------------------------------------

(defn quad-gains
  "Compute [FL FR RL RR] amplitude gains for position [x y] ∈ [0,1]².
  Uses vector-based amplitude panning (VBAP) with constant power.
  Position [0.5 0.5] → equal gains; [0 0.5] → full left; [0.5 1] → full front."
  [[x y]]
  (let [;; Normalize to [-1,1]
        xn (- (* 2.0 x) 1.0)
        yn (- (* 2.0 y) 1.0)
        ;; front-back split
        front (Math/sqrt (max 0.0 (* 0.5 (+ 1.0 yn))))
        rear  (Math/sqrt (max 0.0 (* 0.5 (- 1.0 yn))))
        ;; left-right split
        left  (Math/sqrt (max 0.0 (* 0.5 (- 1.0 xn))))
        right (Math/sqrt (max 0.0 (* 0.5 (+ 1.0 xn))))]
    [(* front left)
     (* front right)
     (* rear  left)
     (* rear  right)]))

;; ---------------------------------------------------------------------------
;; Zone routing (Routing mode)
;; ---------------------------------------------------------------------------

(defn- particle-zone
  "Return the zone keyword for a particle position given a zone map.
  Zones: {:top-left kw :top-right kw :bottom-left kw :bottom-right kw}"
  [[px py] zones]
  (let [left?   (< px 0.5)
        top?    (>= py 0.5)]
    (cond
      (and left? top?)    (:top-left zones)
      (and (not left?) top?)   (:top-right zones)
      (and left? (not top?))   (:bottom-left zones)
      :else                    (:bottom-right zones))))

;; ---------------------------------------------------------------------------
;; Field registry
;; ---------------------------------------------------------------------------

(defonce ^:private field-registry (atom {}))

(defn- build-walls [cfg]
  (let [n         (double (:sides cfg 4.0))
        wall-types (:wall-types cfg)]
    (ngon-walls n :type (get cfg :wall-type :reflect)
                  :wall-types wall-types)))

(defn defspatial-field!
  "Register a named spatial field definition.

  Required:
    :mode    — :generation :modulation :routing

  Options:
    :sides        — room sides (default 4.0; decimals create asymmetry)
    :ball-speed   — initial particle speed (default 1.0)
    :ball-damping — velocity damping per collision (default 0.95)
    :particles    — particle count for :modulation mode (default 1)
    :min-velocity — despawn threshold for :generation (default 0.05)
    :forces       — force map {:gravity {:direction :down :strength 0.3} ...}
    :mappings     — axis→target map {:x :pan :y :pitch}
    :zones        — zone→target map for :routing mode
    :wall-types   — per-wall boundary type vector
    :wall-type    — default boundary type for all walls (default :reflect)

  Example:
    (defspatial-field! :my-room
      {:mode :generation :sides 4.7 :ball-speed 1.0 :ball-damping 0.85
       :mappings {:x :pan :y :pitch}})"
  [field-name cfg]
  (let [walls (build-walls cfg)
        field {:name         field-name
               :mode         (:mode cfg :generation)
               :sides        (:sides cfg 4.0)
               :walls        walls
               :forces       (:forces cfg {})
               :mappings     (:mappings cfg {})
               :zones        (:zones cfg {})
               :config       {:ball-speed   (:ball-speed cfg 1.0)
                              :ball-damping (:ball-damping cfg 0.95)
                              :particles    (:particles cfg 1)
                              :min-velocity (:min-velocity cfg 0.05)}
               :state        :stopped
               :thread       nil
               :particles    (atom [])}]
    (swap! field-registry assoc field-name field)
    field-name))

(defn get-field
  "Return the field map for `field-name`, or nil."
  [field-name]
  (get @field-registry field-name))

(defn field-names
  "Return a sorted seq of registered field names."
  []
  (sort (keys @field-registry)))

;; ---------------------------------------------------------------------------
;; Initial particle factory
;; ---------------------------------------------------------------------------

(defn- make-particle
  "Create a particle with a random launch angle and the configured speed."
  [speed & {:keys [position] :or {position [0.5 0.5]}}]
  (let [angle (rand (* 2.0 Math/PI))]
    {:position position
     :velocity [(* speed (Math/cos angle))
                (* speed (Math/sin angle))]
     :mass     1.0
     :charge   1.0
     :age      0}))

;; ---------------------------------------------------------------------------
;; Event dispatch from collision
;; ---------------------------------------------------------------------------

(defn- collision-event
  "Build a play! step map from a collision event, applying field mappings."
  [field collision triggering-event]
  (let [particle  (:particle collision)
        mapped    (map-particle particle (:mappings field))
        base      (or triggering-event {:pitch/midi 60 :dur/beats 1/4})
        ;; Apply mapped parameters onto the base event
        pitch-off (get mapped :pitch 0.0)
        pan-val   (get mapped :pan nil)
        vel-val   (get mapped :velocity nil)
        midi      (+ (int (:pitch/midi base 60)) (int pitch-off))
        velocity  (or (when vel-val (max 0 (min 127 (int (* vel-val 127.0)))))
                      (int (:mod/velocity base 64)))]
    (cond-> (assoc base :pitch/midi midi :mod/velocity velocity)
      pan-val (assoc :pitch/pan pan-val))))

;; ---------------------------------------------------------------------------
;; Modulation mode — background tick thread
;; ---------------------------------------------------------------------------

(defn- modulation-loop
  "Run the modulation loop for a field, updating ctrl tree with particle state."
  [field-name tick-ms]
  (let [field (get @field-registry field-name)]
    (when (and field (= :running (:state field)))
      (let [walls   (:walls field)
            forces  (:forces field)
            dt      (/ tick-ms 1000.0)
            parts   @(:particles field)]
        ;; Step all particles
        (let [stepped (mapv #(:particle (step-particle % walls forces dt)) parts)]
          (reset! (:particles field) stepped)
          ;; Publish state to ctrl tree
          (let [state {:particles (mapv #(select-keys % [:position :velocity :age]) stepped)
                       :mapped    (mapv #(map-particle % (:mappings field)) stepped)}]
            (try (ctrl/set! [:spatial field-name :state] state)
                 (catch Exception _))))))))

(defn- start-modulation-thread
  [field-name]
  (let [tick-ms 50]
    (doto (Thread.
            (fn []
              (try
                (loop []
                  (when (= :running (:state (get @field-registry field-name)))
                    (modulation-loop field-name tick-ms)
                    (Thread/sleep tick-ms)
                    (recur)))
                (catch InterruptedException _)
                (catch Exception e
                  (println (str "[spatial-field] " field-name " modulation error: "
                                (.getMessage e)))))))
      (.setDaemon true)
      (.start))))

;; ---------------------------------------------------------------------------
;; Generation mode — particle lifecycle thread
;; ---------------------------------------------------------------------------

(defn- launch-particle
  "Launch a particle into a field and drive it until it stops or despawns.
  Fires core/play! at each :generate wall collision."
  [field-name particle triggering-event]
  (let [tick-ms 16]
    (doto (Thread.
            (fn []
              (try
                (loop [p particle]
                  (let [field    (get @field-registry field-name)
                        min-v    (get-in field [:config :min-velocity])
                        walls    (:walls field)
                        forces   (:forces field)
                        dt       (/ tick-ms 1000.0)]
                    (when (and field
                               (= :running (:state field))
                               (> (speed (:velocity p)) (or min-v 0.05)))
                      (let [{:keys [particle events]}
                            (step-particle p walls forces dt)]
                        ;; Fire events for :generate walls
                        (doseq [ev events
                                :when (= :generate (get-in ev [:wall :type]))]
                          (core/play! (collision-event field ev triggering-event)))
                        (Thread/sleep tick-ms)
                        (recur particle)))))
                (catch InterruptedException _)
                (catch Exception e
                  (println (str "[spatial-field] " field-name " particle error: "
                                (.getMessage e)))))))
      (.setDaemon true)
      (.start))))

;; ---------------------------------------------------------------------------
;; Field lifecycle
;; ---------------------------------------------------------------------------

(defn start-field!
  "Start a registered spatial field. For :modulation mode, begins ticking
  particle positions to the ctrl tree. For :generation mode, the field
  becomes ready to receive launch events via play-through-field! or
  field-transformer.

  Returns nil."
  [field-name]
  (let [field (get @field-registry field-name)]
    (when-not field
      (throw (ex-info "start-field!: field not found" {:name field-name})))
    ;; Initialise particles for modulation mode
    (when (= :generation (:mode field))
      (reset! (:particles field) []))
    (when (= :modulation (:mode field))
      (let [n (get-in field [:config :particles] 1)
            spd (get-in field [:config :ball-speed] 1.0)]
        (reset! (:particles field)
                (vec (repeatedly n #(make-particle spd))))))
    ;; Mark running
    (swap! field-registry assoc-in [field-name :state] :running)
    ;; Start background thread for modulation
    (when (= :modulation (:mode field))
      (let [t (start-modulation-thread field-name)]
        (swap! field-registry assoc-in [field-name :thread] t)))
    nil))

(defn stop-field!
  "Stop a running spatial field. Interrupts background threads.
  Returns nil."
  [field-name]
  (when-let [field (get @field-registry field-name)]
    (swap! field-registry assoc-in [field-name :state] :stopped)
    (when-let [^Thread t (:thread field)]
      (.interrupt t))
    (swap! field-registry assoc-in [field-name :thread] nil)
    (reset! (:particles field) []))
  nil)

(defn stop-all-fields!
  "Stop all running spatial fields."
  []
  (doseq [name (field-names)]
    (stop-field! name)))

(defn field-state
  "Return the current runtime state of a field: :running or :stopped."
  [field-name]
  (:state (get @field-registry field-name)))

;; ---------------------------------------------------------------------------
;; ITransformer implementation — generation mode
;; ---------------------------------------------------------------------------

(defrecord SpatialFieldTransformer [field-name]
  xf/ITransformer
  (transform [_ event]
    ;; Launch a particle and return the triggering event immediately
    ;; with delay-beats=0; collisions generate notes asynchronously.
    (let [field (get @field-registry field-name)]
      (when (and field (= :running (:state field)))
        (let [spd (get-in field [:config :ball-speed] 1.0)
              p   (make-particle spd)]
          (launch-particle field-name p event)))
    ;; Return the original event so the live loop still hears it
    [{:event event :delay-beats 0}])))

(defn field-transformer
  "Return an ITransformer that, when an event passes through it, launches a
  particle into the named field. Use with play-transformed! or compose-xf.

  The triggering event is played immediately; collisions with :generate walls
  fire additional notes asynchronously.

  Example:
    (play-transformed! {:pitch/midi 60 :dur/beats 1/4}
                       (field-transformer :ricochet))"
  [field-name]
  (->SpatialFieldTransformer field-name))

;; ---------------------------------------------------------------------------
;; Direct play helper
;; ---------------------------------------------------------------------------

(defn play-through-field!
  "Launch a particle into a generation-mode field and play the triggering event.
  Convenience wrapper around play-transformed!.

  Example:
    (play-through-field! :ricochet {:pitch/midi 60 :dur/beats 1/4})"
  [field-name event]
  (let [field (get @field-registry field-name)]
    (when (and field (= :running (:state field)))
      (let [spd (get-in field [:config :ball-speed] 1.0)
            p   (make-particle spd)]
        (launch-particle field-name p event)
        (core/play! event)))))

;; ---------------------------------------------------------------------------
;; Named presets
;; ---------------------------------------------------------------------------

(defn- register-presets! []
  ;; :ricochet — classic ball-in-box, generation, square room
  (defspatial-field! :ricochet
    {:mode         :generation
     :sides        4.0
     :ball-speed   1.0
     :ball-damping 0.85
     :min-velocity 0.05
     :forces       {:damping 0.99}
     :wall-type    :generate
     :mappings     {:x :pan :y :pitch}})

  ;; :dribble — gravity bounce 1D (ball falls and bounces)
  (defspatial-field! :dribble
    {:mode         :generation
     :sides        4.0
     :ball-speed   0.5
     :ball-damping 0.8
     :min-velocity 0.02
     :forces       {:gravity {:direction :down :strength 0.5}
                    :damping 0.85}
     :wall-type    :generate
     :mappings     {:y :velocity :x :pan}})

  ;; :drift — slow ergodic pan sweep, modulation, irregular pentagon
  (defspatial-field! :drift
    {:mode         :modulation
     :sides        5.3
     :ball-speed   0.1
     :ball-damping 1.0
     :particles    1
     :forces       {:damping 1.0}
     :wall-type    :reflect
     :mappings     {:x :pan :y :reverb-send}})

  ;; :orbit — center attractor, circular sweep
  (defspatial-field! :orbit
    {:mode         :modulation
     :sides        4.0
     :ball-speed   0.8
     :ball-damping 1.0
     :particles    1
     :forces       {:attractor {:position [0.5 0.5] :strength 0.3 :falloff :inverse-square}
                    :damping   1.0}
     :wall-type    :reflect
     :mappings     {:r :reverb-send :θ :pan}})

  ;; :pinball — dense heptagon, generation
  (defspatial-field! :pinball
    {:mode         :generation
     :sides        7.0
     :ball-speed   2.0
     :ball-damping 0.9
     :min-velocity 0.1
     :forces       {:damping 0.98}
     :wall-type    :generate
     :mappings     {:x :pan :y :pitch}})

  ;; :gravity-well — converging density, generation
  (defspatial-field! :gravity-well
    {:mode         :generation
     :sides        4.0
     :ball-speed   1.2
     :ball-damping 0.92
     :min-velocity 0.05
     :forces       {:attractor {:position [0.5 0.5] :strength 0.8 :falloff :inverse-square}
                    :damping   0.97}
     :wall-type    :generate
     :mappings     {:r :velocity :x :pan}})

  ;; :portal-room — hexagon with portal walls (particle wraps through walls)
  (defspatial-field! :portal-room
    {:mode         :generation
     :sides        6.0
     :ball-speed   1.0
     :ball-damping 1.0
     :min-velocity 0.05
     :forces       {:damping 1.0}
     :wall-type    :portal
     :mappings     {:x :pan :y :pitch}})

  ;; :instrument-quad — four-quadrant routing, square room
  (defspatial-field! :instrument-quad
    {:mode         :modulation
     :sides        4.0
     :ball-speed   0.3
     :ball-damping 1.0
     :particles    1
     :forces       {:damping 1.0}
     :wall-type    :reflect
     :mappings     {:x :pan}
     :zones        {:top-left    :instrument-a
                    :top-right   :instrument-b
                    :bottom-left :instrument-c
                    :bottom-right :instrument-d}}))

;; Register presets at load time
(register-presets!)

;; ---------------------------------------------------------------------------
;; system registration
;; ---------------------------------------------------------------------------

(defn -register-system!
  "Called by cljseq.core/start! — no-op for spatial-field (presets are
  registered at load time; user fields survive restarts)."
  [_system-state]
  nil)
