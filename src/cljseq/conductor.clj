; SPDX-License-Identifier: EPL-2.0
(ns cljseq.conductor
  "Conductor — sequences arc-type gestures over musical time.

  A conductor is a named entity that runs a sequence of sections, each of which
  routes trajectories to arc/device paths and sleeps for a specified duration.
  The runner uses the same drift-free `loop/sleep!` infrastructure as live loops.

  ## Section map fields

    :name      — keyword label for state reporting and cue! targeting (optional)
    :bars      — section duration in bars; 1 bar = 4 beats (default 0 = instant)
    :repeat    — number of times to run this section (default 1)
    :when      — (fn []) predicate evaluated before each invocation; if false the
                 section is skipped entirely (no gesture, no sleep, no callbacks).
                 Evaluated per repetition when :repeat > 1.
    :gesture   — how to produce trajectories for this section:
                   keyword  — built-in: :buildup :trance-buildup :breakdown
                                        :anticipation :groove-lock :wind-down
                                        :drop :silence :swell :tension-peak
                   fn       — (fn [section-map start-beat] → {path → traj})
                   map      — {path → traj} used directly
                   nil      — no trajectories (use :on-start for side effects)
    :curve     — curve shape passed to built-in gestures (default :s-curve)
    :from      — start level for built-in gestures (default 0.1)
    :to        — end level for built-in gestures (default 1.0)
    :on-start  — (fn []) called before trajectories are routed, per repetition
    :on-end    — (fn []) called after the section sleep, per repetition

  ## Conductor-level options (passed as third arg to defconductor!)

    :on-transition — (fn [from-name to-name]) called once per section entry,
                     before :on-start and before the :when check.
                     `from-name` is nil for the very first section.

  ## Built-in gestures

    :buildup        — S-curve rise of tension/density/register/momentum
    :trance-buildup — slow-exponential tension + dramatic register, momentum step-snap
    :breakdown      — reverse-exp collapse of density/texture/momentum
    :anticipation   — pre-drop silence: density/texture collapse, tension climbs
    :groove-lock    — cosine ease of tension while momentum/density hold
    :wind-down      — cosine fade of all arcs to near-zero
    :drop           — near-instant snap: tension→0, density→1, momentum→1
    :silence        — fast cosine collapse of all arcs to 0
    :swell          — breathe-curve rise-and-fall
    :tension-peak   — sharp exponential spike in tension

  ## Example

    (defconductor! :trance-drop
      [{:name :buildup      :bars 32 :gesture :buildup    :curve :s-curve}
       {:name :anticipation :bars 1  :gesture :anticipation}
       {:name :drop         :bars 0  :gesture :drop}
       {:name :groove-lock  :bars 16 :gesture :groove-lock}
       {:name :wind-down    :bars 8  :gesture :wind-down}]
      {:on-transition (fn [from to]
                        (println \"transition\" from \"→\" to))})

    (fire! :trance-drop)
    (conductor-state :trance-drop)   ; → {:status :running :section :buildup}
    (cue! :trance-drop :wind-down)   ; jump to :wind-down at next boundary
    (abort! :trance-drop)

  Key design decisions: R&R §31 (conductor), Track B conductor layer."
  (:require [cljseq.loop       :as loop-ns]
            [cljseq.mod        :as mod]
            [cljseq.trajectory :as traj])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; Registry: {name {:sections [...] :status :idle/:running/:done/:aborted/:error
;;                  :section  current-section-name-or-nil
;;                  :running? atom
;;                  :thread   Thread}}
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

;; ---------------------------------------------------------------------------
;; Built-in gesture fns
;; Each takes (section-map start-beat) → {path → trajectory}
;; ---------------------------------------------------------------------------

(defn- mk-buildup [section start]
  (traj/buildup :bars  (:bars section 16)
                :start start
                :curve (get section :curve :s-curve)
                :from  (get section :from 0.1)
                :to    (get section :to   1.0)))

(defn- mk-breakdown [section start]
  (traj/breakdown :bars  (:bars section 8)
                  :start start
                  :curve (get section :curve :reverse-exp)))

(defn- mk-groove-lock [section start]
  (traj/groove-lock :bars       (:bars section 16)
                    :start      start
                    :tension-to (get section :tension-to 0.4)))

(defn- mk-wind-down [section start]
  (traj/wind-down :bars  (:bars section 8)
                  :start start))

(defn- mk-drop [_section start]
  ;; Near-instant snap: very short trajectory so the mod runner fires within
  ;; one sample period (~0.125 beats) and auto-stops.
  (let [snap #(traj/trajectory :from %1 :to %2 :beats 0.05 :curve :linear :start start)]
    {[:arc/tension]  (snap 1.0 0.0)
     [:arc/density]  (snap 0.0 1.0)
     [:arc/momentum] (snap 0.0 1.0)}))

(defn- mk-silence [_section start]
  (let [mk #(traj/trajectory :from 1.0 :to 0.0 :beats 2 :curve :cosine :start start)]
    {[:arc/tension]  (mk)
     [:arc/density]  (mk)
     [:arc/momentum] (mk)
     [:arc/texture]  (mk)}))

(defn- mk-swell [section start]
  (traj/swell :bars       (:bars section 8)
              :start      start
              :from       (get section :from 0.2)
              :peak-level (get section :to   1.0)))

(defn- mk-tension-peak [section start]
  (traj/tension-peak :bars  (:bars section 2)
                     :start start
                     :peak  (get section :to 1.0)))

(defn- mk-trance-buildup [section start]
  (traj/trance-buildup :bars  (:bars section 32)
                       :start start
                       :from  (get section :from 0.05)))

(defn- mk-anticipation [section start]
  (traj/anticipation :bars  (:bars section 2)
                     :start start))

(def ^:private builtin-gestures
  {:buildup        mk-buildup
   :trance-buildup mk-trance-buildup
   :breakdown      mk-breakdown
   :anticipation   mk-anticipation
   :groove-lock    mk-groove-lock
   :wind-down      mk-wind-down
   :drop           mk-drop
   :silence        mk-silence
   :swell          mk-swell
   :tension-peak   mk-tension-peak})

;; ---------------------------------------------------------------------------
;; Gesture resolution
;; ---------------------------------------------------------------------------

(defn- resolve-gesture
  "Return a {path → trajectory} map for `section` starting at `start-beat`."
  [section start-beat]
  (let [g (:gesture section)]
    (cond
      (nil? g)      {}
      (keyword? g)  (if-let [f (clojure.core/get builtin-gestures g)]
                      (f section start-beat)
                      (throw (ex-info
                               (str "Unknown conductor gesture: " (pr-str g)
                                    ". Built-ins: " (keys builtin-gestures))
                               {:gesture g})))
      (fn? g)       (g section start-beat)
      (map? g)      g
      :else         {})))

;; ---------------------------------------------------------------------------
;; Safe callback runner
;; ---------------------------------------------------------------------------

(defn- run-callback! [label f]
  (when f
    (try (f)
         (catch Exception e
           (binding [*out* *err*]
             (println (str "[conductor] " label " error: " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Runner thread body
;; ---------------------------------------------------------------------------

(defn- section-active?
  "Return true if `section` should run — either no :when predicate, or the
  predicate returns a truthy value. Exceptions in :when are treated as true
  (run the section) and logged."
  [section label]
  (if-let [pred (:when section)]
    (try (boolean (pred))
         (catch Exception e
           (binding [*out* *err*]
             (println (str "[conductor] " label " :when error: " (.getMessage e))))
           true))
    true))

(defn- find-section-idx
  "Return the index of the first section with :name equal to `target`, or nil."
  [sections target]
  (first (keep-indexed (fn [i s] (when (= (:name s) target) i)) sections)))

(defn- run-section! [name-kw section running?]
  (when (and @running?
             (section-active? section (str (pr-str name-kw) " " (pr-str (:name section)))))
    (swap! registry assoc-in [name-kw :section] (:name section))
    (run-callback! (str (pr-str name-kw) " :on-start") (:on-start section))
    (let [start (loop-ns/now)
          trajs (resolve-gesture section start)]
      (doseq [[path tr] trajs]
        (mod/mod-route! path tr)))
    (let [beats (* (double (get section :bars 0)) 4.0)]
      (when (pos? beats)
        (loop-ns/sleep! beats)))
    (run-callback! (str (pr-str name-kw) " :on-end") (:on-end section))))

(defn- conductor-runner [name-kw sections running? on-transition]
  (fn []
    (binding [loop-ns/*virtual-time* (loop-ns/-start-beat)]
      (try
        (loop [idx       0
               prev-name nil]
          (when (and @running? (< idx (count sections)))
            ;; Check for a pending cue — consume it atomically and resolve index
            (let [cue-name (let [c (get-in @registry [name-kw :cue])]
                             (when c
                               (swap! registry assoc-in [name-kw :cue] nil)
                               c))
                  actual-idx (if cue-name
                               (or (find-section-idx sections cue-name)
                                   (do (binding [*out* *err*]
                                         (println (str "[conductor] cue! target "
                                                       (pr-str cue-name)
                                                       " not found in "
                                                       (pr-str name-kw)
                                                       "; continuing in order")))
                                       idx))
                               idx)
                  section    (nth sections actual-idx)
                  sect-name  (:name section)]
              ;; Fire on-transition before this section (nil from on first entry)
              (when on-transition
                (run-callback! (str (pr-str name-kw) " :on-transition")
                               #(on-transition prev-name sect-name)))
              ;; Run section (with repetitions)
              (let [reps (max 1 (int (get section :repeat 1)))]
                (dotimes [_ reps]
                  (run-section! name-kw section running?)))
              (recur (inc actual-idx) sect-name))))
        ;; Normal completion
        (swap! registry update name-kw assoc :status :done :section nil)
        (catch Exception e
          (binding [*out* *err*]
            (println (str "[conductor] " (pr-str name-kw) " error: " (.getMessage e))))
          (swap! registry update name-kw assoc :status :error :section nil))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn defconductor!
  "Register a named conductor with a sequence of section maps.

  `name-kw`  — keyword identifying this conductor
  `sections` — vector of section maps (see ns docstring for fields)
  `opts`     — optional map of conductor-level options:
                 :on-transition — (fn [from-name to-name]) fired before each
                                  section; from-name is nil for the first section

  Re-registering the same name replaces the section list and options. If the
  conductor is currently running, the running instance is unaffected; a
  subsequent `fire!` will use the new sections and options.

  Example:
    (defconductor! :drop-sequence
      [{:name :buildup  :bars 32 :gesture :buildup}
       {:name :drop     :bars 0  :gesture :drop}
       {:name :groove   :bars 16 :gesture :groove-lock}]
      {:on-transition (fn [from to] (println from \"→\" to))})"
  ([name-kw sections]
   (defconductor! name-kw sections nil))
  ([name-kw sections opts]
   (swap! registry update name-kw
          (fn [existing]
            (assoc (or existing {})
                   :sections      sections
                   :on-transition (:on-transition opts)
                   :status        (or (:status existing) :idle))))
   name-kw))

(defn fire!
  "Start the named conductor running asynchronously.

  Returns immediately. The conductor advances through its sections in order,
  routing trajectories and sleeping via loop/sleep!.

  If the conductor is already running, logs a warning and does nothing — use
  `abort!` first if you want to restart.

  Example:
    (fire! :drop-sequence)"
  [name-kw]
  (let [entry (clojure.core/get @registry name-kw)]
    (when-not entry
      (throw (ex-info (str "conductor not registered: " (pr-str name-kw)
                           ". Call defconductor! first.")
                      {:name name-kw})))
    (when (= :running (:status entry))
      (binding [*out* *err*]
        (println (str "[conductor] " (pr-str name-kw) " is already running. "
                      "Call (abort! " (pr-str name-kw) ") first.")))
      (throw (ex-info (str "conductor " (pr-str name-kw) " already running")
                      {:name name-kw})))
    (let [running?       (atom true)
          sections       (:sections entry)
          on-transition  (:on-transition entry)
          thread         (Thread. (conductor-runner name-kw sections running? on-transition))]
      (swap! registry update name-kw assoc
             :status   :running
             :section  nil
             :running? running?
             :thread   thread)
      (.setDaemon thread true)
      (.setName thread (str "cljseq-conductor-" (name name-kw)))
      (.start thread)))
  name-kw)

(defn abort!
  "Stop a running conductor at the next section boundary.

  The current section's sleep completes before the conductor halts — it does
  not interrupt mid-sleep. Unparks the runner thread immediately so any
  `sleep!` within a 0-bar section resolves without waiting.

  No-op if the conductor is not running.

  Example:
    (abort! :drop-sequence)"
  [name-kw]
  (when-let [entry (clojure.core/get @registry name-kw)]
    (when-let [running? (:running? entry)]
      (reset! running? false))
    (when-let [^Thread t (:thread entry)]
      (LockSupport/unpark t))
    (swap! registry update name-kw assoc :status :aborted :section nil))
  nil)

(defn conductor-state
  "Return the current state map for `name-kw`.

  Keys:
    :status  — :idle | :running | :done | :aborted | :error
    :section — current section :name keyword, or nil when not running

  Returns nil if the conductor has not been registered.

  Example:
    (conductor-state :drop-sequence)
    ;; => {:status :running :section :buildup}"
  [name-kw]
  (when-let [e (clojure.core/get @registry name-kw)]
    (select-keys e [:status :section])))

(defn conductor-names
  "Return a seq of all registered conductor names."
  []
  (keys @registry))

(defn cue!
  "Jump to the section named `section-name-kw` at the next section boundary.

  The current section finishes normally (including its sleep and :on-end
  callback), then the runner jumps to the cued section rather than advancing
  to the next one in sequence. `on-transition` is called with the cued
  section as the destination.

  If the cue target name does not match any section, a warning is logged and
  the conductor continues in order.

  No-op if the conductor is not currently running.

  Example:
    ;; Skip directly to the outro while the buildup is still sleeping
    (cue! :trance-drop :wind-down)"
  [conductor-name-kw section-name-kw]
  (when (= :running (:status (clojure.core/get @registry conductor-name-kw)))
    (swap! registry assoc-in [conductor-name-kw :cue] section-name-kw))
  nil)

(defn stop-all-conductors!
  "Abort all running conductors. Called by cljseq.core/stop!."
  []
  (doseq [name-kw (conductor-names)]
    (abort! name-kw))
  nil)
