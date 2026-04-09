; SPDX-License-Identifier: EPL-2.0
(ns cljseq.temporal-buffer
  "Temporal Buffer — accumulating beat-timestamped event store with windowed
  playback zones.

  A Temporal Buffer continuously accumulates note events with beat timestamps.
  Multiple overlapping named zones define windows into the buffer at different
  temporal depths. A playback cursor reads from the active zone and replays
  events offset forward by one zone depth.

  This is not a delay line. It is a time-accumulation system with windowed
  access. Zone switching is pitch-free; Rate coupling (Doppler) is Phase 2.

  ## Quick start

    ;; Define a named buffer (active zone = :z2, depth 4 beats)
    (deftemporal-buffer :echo {:active-zone :z2})

    ;; From a live-loop, feed events in:
    (temporal-buffer-send! :echo {:pitch/midi 60 :dur/beats 1/4 :mod/velocity 80})

    ;; Notes repeat every 4 beats (zone :z2 depth).
    ;; Switch zone pitch-free — no Doppler, no pitch change:
    (temporal-buffer-zone! :echo :z3)    ; now repeats at 8-beat depth

    ;; Freeze input; frozen content continues to be read and replayed:
    (temporal-buffer-hold! :echo true)
    (temporal-buffer-hold! :echo false)  ; resume

    ;; Retrograde — cursor reads events in reverse order within the zone:
    (temporal-buffer-flip! :echo)

    ;; Inspect state:
    (temporal-buffer-info :echo)

  ## Named zones (Mimeophon-inspired zone topology, §4 design doc)
    :z0   0.5 beats  — sub-beat / Karplus territory (exponential rate in Phase 2)
    :z1   2.0 beats  — slapback
    :z2   4.0 beats  — phrase (4 beats)
    :z3   8.0 beats  — phrase (8 beats)
    :z4  16.0 beats  — section
    :z5  32.0 beats  — section
    :z6  64.0 beats  — long
    :z7 128.0 beats  — extended

  ## Phase scope
    Phase 1: core buffer, zones, single cursor at rate 1.0, hold, flip
    Phase 2: Rate/Doppler, clocked (pitch-free) mode, Zone 0 exponential, Skew
    Phase 3: Color, Feedback, generation tracking
    Phase 4: Halo (smear + threshold feedback injection)
    Phase 5: Hold control remapping, named presets, trajectory integration

  Design: doc/design-temporal-buffer.md (§§2–5, §11, §16 Phase 1–2)"
  (:require [cljseq.clock   :as clock]
            [cljseq.loop    :as loop-ns]
            [cljseq.sidecar :as sidecar])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state injection
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

(defn -register-system!
  "Called by cljseq.core/start! to inject the system-state atom."
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
;; Zone definitions (§4 — 8 overlapping windows)
;; ---------------------------------------------------------------------------

(def ^:private zone-table
  {:z0 {:id :z0 :depth   0.5 :label "sub-beat — Karplus territory" :rate-mode :exponential}
   :z1 {:id :z1 :depth   2.0 :label "slapback — 2 beats"           :rate-mode :linear}
   :z2 {:id :z2 :depth   4.0 :label "phrase — 4 beats"             :rate-mode :linear}
   :z3 {:id :z3 :depth   8.0 :label "phrase — 8 beats"             :rate-mode :linear}
   :z4 {:id :z4 :depth  16.0 :label "section — 16 beats"           :rate-mode :linear}
   :z5 {:id :z5 :depth  32.0 :label "section — 32 beats"           :rate-mode :linear}
   :z6 {:id :z6 :depth  64.0 :label "long — 64 beats"              :rate-mode :linear}
   :z7 {:id :z7 :depth 128.0 :label "extended — 128 beats"         :rate-mode :linear}})

(defn zone-ids
  "Return the ordered seq of zone keywords [:z0 :z1 … :z7]."
  []
  (sort (keys zone-table)))

(defn zone-info
  "Return the zone map for `zone-id`, or nil if unknown."
  [zone-id]
  (get zone-table zone-id))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(declare stop!)

;; ---------------------------------------------------------------------------
;; Event emission
;; ---------------------------------------------------------------------------

(defn- emit-event!
  "Schedule note event `ev` to play at absolute beat `play-beat`.

  Routes to sidecar if connected; falls back to stdout for Phase 0 / test use."
  [ev ^double play-beat]
  (let [midi     (int (or (:pitch/midi ev) 60))
        beats    (double (or (:dur/beats ev) 0.25))
        channel  (int (or (:midi/channel ev) 1))
        velocity (int (or (:mod/velocity ev) 64))]
    (if (and (sidecar/connected?) (timeline))
      (let [tl      (timeline)
            on-ns   (clock/beat->epoch-ns play-beat tl)
            off-ns  (clock/beat->epoch-ns (+ play-beat beats) tl)]
        (sidecar/send-note-on!  on-ns  channel midi velocity)
        (sidecar/send-note-off! off-ns channel midi))
      (println (format "[temporal-buffer] beat=%.3f midi=%d dur=%.3f ch=%d vel=%d"
                       play-beat midi beats channel velocity)))))

;; ---------------------------------------------------------------------------
;; Thread lifecycle helpers
;; ---------------------------------------------------------------------------

(defn- start-thread!
  "Start a daemon thread named `thread-name` running `f`. Returns the Thread."
  [thread-name f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.setName thread-name)
    (.start)))

(defn- stop-thread!
  "Set `running?` to false and unpark `thread`."
  [running? ^Thread thread]
  (reset! running? false)
  (when thread (LockSupport/unpark thread)))

;; ---------------------------------------------------------------------------
;; Phase 2 — Rate / Doppler helpers
;; ---------------------------------------------------------------------------

(def ^:private default-tape-scale
  "Default cents-per-unit-rate-deviation for linear zones.
  1200.0 = full tape-accurate Doppler (rate=2.0 → +1200 cents, +1 octave)."
  1200.0)

(defn- doppler-pitch-cents
  "Compute Doppler pitch offset in cents for effective `rate` in `zone`.

  Returns 0.0 in clocked mode (pitch-free Rate).

  Linear zones (z1–z7): offset = (rate - 1.0) × tape-scale
  Exponential zone (z0): offset = 1200 × log₂(rate)"
  ^double [^double rate ^double tape-scale zone clocked?]
  (if clocked?
    0.0
    (case (:rate-mode zone :linear)
      :exponential (* 1200.0 (/ (Math/log rate) (Math/log 2.0)))
      :linear      (* (- rate 1.0) tape-scale))))

(defn- shift-pitch
  "Apply `cents` pitch offset to event `ev`.

  :pitch/midi is adjusted by rounding to the nearest semitone.
  The sub-semitone remainder is stored in :pitch/cents-offset for
  downstream microtonal support (Phase 3+)."
  [ev ^double cents]
  (if (zero? cents)
    ev
    (let [base-midi (double (:pitch/midi ev 60))
          shifted   (+ base-midi (/ cents 100.0))
          new-midi  (int (Math/round shifted))
          remainder (* (- shifted new-midi) 100.0)]
      (assoc ev
             :pitch/midi         (int (max 0 (min 127 new-midi)))
             :pitch/cents-offset remainder))))

(defn- effective-cursor-rate
  "Return the effective playback rate for `cursor` (:a or :b) given
  base `rate` and the `skew` config map (or nil).

  Only :inverse skew mode is implemented in Phase 2:
    cursor A → rate + skew-amount
    cursor B → rate − skew-amount"
  ^double [^double rate skew cursor]
  (if-let [{:keys [mode amount]} skew]
    (case mode
      :inverse (case cursor
                 :a (+ rate (double amount))
                 :b (- rate (double amount)))
      rate)
    rate))

;; ---------------------------------------------------------------------------
;; Playback runner
;; ---------------------------------------------------------------------------

(defn- playback-runner
  "Daemon thread body for temporal buffer `buf-name`, cursor `cursor` (:a or :b).

  Phase 2 additions over Phase 1:
  - Effective rate = base-rate adjusted by skew for this cursor.
  - Cycle period = zone-depth / effective-rate (Rate/Doppler).
  - Events are pitch-shifted by doppler-pitch-cents before emission.
  - Clocked mode suppresses pitch shift (speed-only Rate).

  Each cycle:
  1. Determine zone depth D and effective rate R for this cursor.
  2. Compute period P = D / R.
  3. Park until next cycle boundary (cycle-start + P).
  4. Collect events written during [cycle-start, cycle-start + P).
  5. Apply Doppler pitch shift; emit in forward or retrograde order.
  6. Advance cycle-start by P."
  [buf-name running? cursor]
  (loop [cycle-start (current-beat)]
    (when @running?
      (let [entry (get @registry buf-name)]
        (when entry
          (let [state      @(:state-atom entry)
                zone-id    (:active-zone state :z3)
                zone       (get zone-table zone-id)
                depth      (double (:depth zone 8.0))
                base-rate  (double (:rate state 1.0))
                tape-scale (double (:tape-scale state default-tape-scale))
                clocked?   (boolean (:clocked? state false))
                skew       (:skew state)
                eff-rate   (max 0.01 (effective-cursor-rate base-rate skew cursor))
                period     (/ depth eff-rate)
                next-wake  (+ cycle-start period)]
            (loop-ns/-park-until-beat! next-wake)
            (when @running?
              (let [state'      @(:state-atom entry)
                    buf         @(:buffer-atom entry)
                    flip?       (:flip? state' false)
                    ;; Window: events written during [cycle-start, next-wake)
                    window      (filterv (fn [ev]
                                          (let [b (double (:beat ev 0.0))]
                                            (and (>= b cycle-start) (< b next-wake))))
                                        buf)
                    ordered     (if flip?
                                  (sort-by #(- (double (:beat % 0.0))) window)
                                  (sort-by #(double (:beat % 0.0)) window))
                    pitch-cents (doppler-pitch-cents eff-rate tape-scale zone clocked?)]
                ;; Schedule each event at (event.beat - cycle-start) + next-wake
                ;; i.e. same relative position within the zone, one period later.
                ;; Apply Doppler pitch shift before emission.
                (doseq [ev ordered]
                  (let [rel-offset (- (double (:beat ev 0.0)) cycle-start)
                        play-beat  (+ next-wake rel-offset)
                        ev'        (shift-pitch ev pitch-cents)]
                    (emit-event! ev' play-beat)))))
            (recur next-wake)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn deftemporal-buffer
  "Define and start a named temporal buffer.

  `buf-name` — keyword (e.g. :echo, :delay-8)
  `opts`     — option map:
    :active-zone — initial zone keyword (default :z3, depth 8 beats)
    :max-depth   — beats of history to retain in the buffer (default 128.0)

  Phase 2 options:
    :rate        — playback rate multiplier (default 1.0)
                   > 1.0 → faster repetition + Doppler pitch up
                   < 1.0 → slower repetition + Doppler pitch down
    :tape-scale  — cents-per-unit-rate-deviation for Doppler in linear zones
                   (default 1200.0 = full tape-accurate coupling)
    :clocked?    — when true, Rate affects speed only; pitch is suppressed
                   (default false)
    :skew        — cursor-rate divergence map, or nil (default nil)
                   {:mode :inverse :amount 0.2}
                   Starts a second cursor B at (rate − amount) alongside
                   cursor A at (rate + amount).

  Re-evaluating with the same name stops the prior instance and starts fresh.

  Returns `buf-name`."
  ([buf-name]
   (deftemporal-buffer buf-name {}))
  ([buf-name opts]
   (when (get @registry buf-name)
     (stop! buf-name))
   (let [active-zone (or (:active-zone opts) :z3)
         max-depth   (double (or (:max-depth opts) 128.0))
         rate        (double (or (:rate opts) 1.0))
         tape-scale  (double (or (:tape-scale opts) default-tape-scale))
         clocked?    (boolean (or (:clocked? opts) false))
         skew        (:skew opts)
         state-atom  (atom {:active-zone active-zone
                            :hold?       false
                            :flip?       false
                            :max-depth   max-depth
                            :rate        rate
                            :tape-scale  tape-scale
                            :clocked?    clocked?
                            :skew        skew})
         buffer-atom (atom [])
         running?    (atom true)
         entry       {:opts        opts
                      :state-atom  state-atom
                      :buffer-atom buffer-atom
                      :running?    running?
                      :thread      nil
                      :skew-thread nil}]
     (swap! registry assoc buf-name entry)
     (let [t (start-thread! (str "cljseq-tbuf-" (name buf-name))
                            #(playback-runner buf-name running? :a))]
       (swap! registry update buf-name assoc :thread t))
     (when skew
       (let [st (start-thread! (str "cljseq-tbuf-" (name buf-name) "-skew")
                               #(playback-runner buf-name running? :b))]
         (swap! registry update buf-name assoc :skew-thread st)))
     buf-name)))

(defn stop!
  "Stop the playback thread(s) for `buf-name` and remove it from the registry.

  Stops both cursor A (main thread) and cursor B (skew thread) if present.
  The buffer contents are discarded. Use `temporal-buffer-hold!` to pause
  without discarding the buffer."
  [buf-name]
  (when-let [entry (get @registry buf-name)]
    (stop-thread! (:running? entry) (:thread entry))
    ;; Skew thread shares the same running? atom; just unpark it.
    (when-let [st (:skew-thread entry)]
      (LockSupport/unpark st))
    (swap! registry dissoc buf-name))
  nil)

(defn stop-all!
  "Stop all active temporal buffers. Called by cljseq.core/stop!."
  []
  (doseq [n (keys @registry)]
    (stop! n))
  nil)

(defn temporal-buffer-send!
  "Write a note event into temporal buffer `buf-name`.

  `event` — step map with at minimum :pitch/midi. Optional keys:
    :dur/beats   — duration in beats (default 0.25)
    :midi/channel — MIDI channel 1–16 (default 1)
    :mod/velocity — velocity 0–127 (default 64)

  Has no effect when the buffer is in hold mode (input is frozen).
  Returns nil."
  [buf-name event]
  (when-let [entry (get @registry buf-name)]
    (let [state @(:state-atom entry)]
      (when-not (:hold? state false)
        (let [beat    (current-beat)
              ev      (assoc event :beat beat :generation 0 :halo-depth 0)
              max-dep (double (:max-depth state 128.0))
              prune   (fn [buf]
                        (filterv #(>= (double (:beat % 0.0)) (- beat max-dep)) buf))]
          (swap! (:buffer-atom entry) (fn [buf] (conj (prune buf) ev)))))))
  nil)

(defn temporal-buffer-zone!
  "Switch the active zone of `buf-name` to `zone-id` (pitch-free).

  Zone switching repositions the playback cursor to the new window boundary
  without changing playback speed or pitch. The current cycle completes before
  the new depth takes effect.

  Valid zone-ids: :z0 through :z7 (see zone-ids / zone-info)."
  [buf-name zone-id]
  (when-not (get zone-table zone-id)
    (throw (ex-info "temporal-buffer-zone!: unknown zone"
                    {:zone-id zone-id :valid (zone-ids)})))
  (when-let [entry (get @registry buf-name)]
    (swap! (:state-atom entry) assoc :active-zone zone-id))
  nil)

(defn temporal-buffer-hold!
  "Freeze or unfreeze the buffer input gate for `buf-name`.

  When `hold?` is true: no new events enter the buffer. Existing events
  continue to be read and replayed by the playback cursor.
  When `hold?` is false: normal input resumes.

  See §9 of design-temporal-buffer.md for Hold mode control remapping
  (targeted for Phase 5)."
  [buf-name hold?]
  (when-let [entry (get @registry buf-name)]
    (swap! (:state-atom entry) assoc :hold? (boolean hold?)))
  nil)

(defn temporal-buffer-flip!
  "Toggle retrograde (Flip) mode for `buf-name`.

  In Flip mode, the playback cursor reads events in reverse order within
  the active zone — the last note played becomes the first heard in the repeat.
  Repeated calls toggle between forward and retrograde.

  See §11 of design-temporal-buffer.md."
  [buf-name]
  (when-let [entry (get @registry buf-name)]
    (swap! (:state-atom entry) update :flip? not))
  nil)

(defn temporal-buffer-rate!
  "Set the playback rate for `buf-name`.

  rate > 1.0 — faster repetition + Doppler pitch up (tape-speed increase)
  rate = 1.0 — normal (default)
  rate < 1.0 — slower repetition + Doppler pitch down (tape-speed decrease)

  In clocked mode (`temporal-buffer-clocked! true`), pitch shift is suppressed.
  Takes effect at the next cycle boundary."
  [buf-name rate]
  (when-let [entry (get @registry buf-name)]
    (swap! (:state-atom entry) assoc :rate (double rate)))
  nil)

(defn temporal-buffer-tape-scale!
  "Set the tape-scale coefficient for Doppler pitch coupling in `buf-name`.

  tape-scale controls cents of pitch offset per unit of rate deviation from 1.0:
    pitch_cents = (rate - 1.0) × tape-scale   [linear zones z1–z7]

  Default 1200.0 — full tape-accurate Doppler (rate=2.0 → +1200 cents = +1 oct).
  Lower values produce a more subtle pitch effect at a given rate.

  Has no effect on Zone 0 (which always uses the exact 1200×log₂(rate) formula)
  or when clocked mode is enabled."
  [buf-name tape-scale]
  (when-let [entry (get @registry buf-name)]
    (swap! (:state-atom entry) assoc :tape-scale (double tape-scale)))
  nil)

(defn temporal-buffer-clocked!
  "Enable or disable clocked (pitch-free Rate) mode for `buf-name`.

  When true:  Rate controls repetition speed only — no Doppler pitch shift.
  When false: Rate couples speed and pitch (tape-Doppler, default).

  Clocked mode corresponds to an external clock driving the Rate input on a
  real tape delay — the tape speed changes but an external oscillator provides
  the pitch, so no tuning drift occurs."
  [buf-name clocked?]
  (when-let [entry (get @registry buf-name)]
    (swap! (:state-atom entry) assoc :clocked? (boolean clocked?)))
  nil)

(defn temporal-buffer-skew!
  "Set or clear the Skew configuration for `buf-name`.

  `skew-map`:
    nil — disables skew (cursor B continues running if started at creation,
          but its effective rate collapses back to the base rate)
    {:mode :inverse :amount 0.2}
          cursor A reads at (rate + amount)
          cursor B reads at (rate − amount)

  Note: the skew *thread* (cursor B) is only started when :skew is provided
  to `deftemporal-buffer`. This function updates the skew *parameters* for a
  running buffer. To add a skew cursor to a buffer that was started without
  one, use `deftemporal-buffer` to restart."
  [buf-name skew-map]
  (when-let [entry (get @registry buf-name)]
    (swap! (:state-atom entry) assoc :skew skew-map))
  nil)

(defn temporal-buffer-info
  "Return a snapshot of the state of temporal buffer `buf-name`, or nil.

  Returns:
    {:active-zone :z3
     :zone        {:id :z3 :depth 8.0 :label \"phrase — 8 beats\" ...}
     :hold?       false
     :flip?       false
     :max-depth   128.0
     :rate        1.0
     :tape-scale  1200.0
     :clocked?    false
     :skew        nil
     :event-count 42
     :oldest-beat 34.375}"
  [buf-name]
  (when-let [entry (get @registry buf-name)]
    (let [state @(:state-atom entry)
          buf   @(:buffer-atom entry)
          zone  (get zone-table (:active-zone state))]
      (merge state
             {:zone        zone
              :event-count (count buf)
              :oldest-beat (when (seq buf)
                             (apply min (map #(double (:beat % 0.0)) buf)))}))))

(defn buffer-names
  "Return a seq of registered temporal buffer names."
  []
  (keys @registry))
