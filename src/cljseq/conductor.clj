; SPDX-License-Identifier: EPL-2.0
(ns cljseq.conductor
  "Conductor — sequences arc-type gestures over musical time.

  A conductor is a named entity that runs a sequence of sections, each of which
  routes trajectories to arc/device paths and sleeps for a specified duration.
  The runner uses the same drift-free `loop/sleep!` infrastructure as live loops.

  ## Section map fields

    :name      — keyword label for state reporting (optional)
    :bars      — section duration in bars; 1 bar = 4 beats (default 0 = instant)
    :gesture   — how to produce trajectories for this section:
                   keyword  — built-in: :buildup :breakdown :groove-lock
                                        :wind-down :drop :silence
                   fn       — (fn [section-map start-beat] → {path → traj})
                   map      — {path → traj} used directly
                   nil      — no trajectories (use :on-start for side effects)
    :curve     — curve shape passed to built-in gestures (default :s-curve)
    :from      — start level for built-in gestures (default 0.1)
    :to        — end level for built-in gestures (default 1.0)
    :on-start  — (fn []) called before trajectories are routed (optional)
    :on-end    — (fn []) called after the section sleep (optional)

  ## Built-in gestures

    :buildup    — S-curve rise of tension/density/register/momentum
    :breakdown  — reverse-exp collapse of density/texture/momentum
    :groove-lock — cosine ease of tension while momentum/density hold
    :wind-down  — cosine fade of all arcs to near-zero
    :drop       — near-instant snap: tension→0, density→1, momentum→1
    :silence    — fast cosine collapse of all arcs to 0

  ## Example

    (defconductor! :trance-drop
      [{:name :buildup      :bars 32 :gesture :buildup    :curve :s-curve}
       {:name :anticipation :bars 1  :gesture :silence}
       {:name :drop         :bars 0  :gesture :drop}
       {:name :groove-lock  :bars 16 :gesture :groove-lock}
       {:name :wind-down    :bars 8  :gesture :wind-down}])

    (fire! :trance-drop)
    (conductor-state :trance-drop)   ; → {:status :running :section :buildup}
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

(def ^:private builtin-gestures
  {:buildup     mk-buildup
   :breakdown   mk-breakdown
   :groove-lock mk-groove-lock
   :wind-down   mk-wind-down
   :drop        mk-drop
   :silence     mk-silence})

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

(defn- conductor-runner [name-kw sections running?]
  (fn []
    (binding [loop-ns/*virtual-time* (loop-ns/-start-beat)]
      (try
        (doseq [section sections]
          (when @running?
            ;; Track current section
            (swap! registry assoc-in [name-kw :section] (:name section))
            ;; on-start callback
            (run-callback! (str (pr-str name-kw) " :on-start") (:on-start section))
            ;; Route trajectories
            (let [start (loop-ns/now)
                  trajs (resolve-gesture section start)]
              (doseq [[path tr] trajs]
                (mod/mod-route! path tr)))
            ;; Sleep for section duration (0 bars = instant, no sleep)
            (let [beats (* (double (get section :bars 0)) 4.0)]
              (when (pos? beats)
                (loop-ns/sleep! beats)))
            ;; on-end callback
            (run-callback! (str (pr-str name-kw) " :on-end") (:on-end section))))
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

  Re-registering the same name replaces the section list. If the conductor
  is currently running, the running instance is unaffected; a subsequent
  `fire!` will use the new sections.

  Example:
    (defconductor! :drop-sequence
      [{:name :buildup  :bars 32 :gesture :buildup :curve :s-curve}
       {:name :drop     :bars 0  :gesture :drop}
       {:name :groove   :bars 16 :gesture :groove-lock}])"
  [name-kw sections]
  (swap! registry update name-kw
         (fn [existing]
           (assoc (or existing {})
                  :sections sections
                  :status   (or (:status existing) :idle))))
  name-kw)

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
    (let [running?  (atom true)
          sections  (:sections entry)
          thread    (Thread. (conductor-runner name-kw sections running?))]
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

(defn stop-all-conductors!
  "Abort all running conductors. Called by cljseq.core/stop!."
  []
  (doseq [name-kw (conductor-names)]
    (abort! name-kw))
  nil)
