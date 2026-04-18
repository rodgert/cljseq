; SPDX-License-Identifier: EPL-2.0
(ns cljseq.supervisor
  "Lightweight process supervisor for live performance reliability.

  Watches registered services (SC, sidecar, loop threads), emits lifecycle
  events on status changes, optionally auto-restarts failed services, and
  restores last-known state on recovery.

  Live loops can declare service dependencies via `:pause-on-down` in their
  opts map.  When a dependency is `:down`, the loop sleeps for one bar and
  retries — maintaining virtual time continuity without playing into silence.
  On recovery the loop re-enters aligned to the next `:resume-on-bar` boundary
  (default 4 beats), so it snaps back cleanly at a musically sensible point.

  ## Lifecycle events

    :up            — service transitioned from :down/:unknown to :up
    :down          — service transitioned from :up/:unknown to :down
    :restore-failed — restore-fn threw after a recovery

  ## Quick start

    ;; Register SC (health check + synthdef restore on reconnect)
    (supervisor/register-sc!)

    ;; Register sidecar (health check + auto-restart with saved opts)
    (supervisor/register-sidecar!)

    ;; Watch loops for dead threads and emit events
    (supervisor/start-watchdog! :monitor-loops? true)

    ;; Pause :voice-a while SC is down, resume on 4-beat boundary
    (deflive-loop :voice-a {:pause-on-down [:sc]}
      (play! ...)
      (sleep! 3))

    ;; React to a service going down
    (supervisor/on-event! :sc :down ::my-handler
      (fn [_] (println \"SC went down — pausing arc\")))

  ## Service registration

    (supervisor/register! :my-service
      :check-fn    #(thing/connected?)
      :restart-fn  #(thing/reconnect!)
      :restore-fn  #(thing/reload-state!))"
  (:require [cljseq.loop    :as loop-ns]
            [cljseq.core    :as core]
            [cljseq.sc      :as sc]
            [cljseq.sidecar :as sidecar]))

;; ---------------------------------------------------------------------------
;; Event bus
;; ---------------------------------------------------------------------------

(defonce event-handlers
  ;; Shape: {service-name {event-type {handler-key handler-fn}}}
  (atom {}))

(defn on-event!
  "Register handler-fn to be called when service-name emits event-type.
  handler-key identifies the registration for later removal.
  Returns handler-key.

  event-type: :up, :down, :restore-failed"
  [service-name event-type handler-key handler-fn]
  (swap! event-handlers assoc-in [service-name event-type handler-key] handler-fn)
  handler-key)

(defn off-event!
  "Deregister a handler previously registered with on-event!."
  [service-name event-type handler-key]
  (swap! event-handlers update-in [service-name event-type] dissoc handler-key)
  nil)

(defn- emit!
  "Emit an event to all registered handlers. Swallows handler exceptions."
  [service-name event-type payload]
  (doseq [[_ f] (get-in @event-handlers [service-name event-type] {})]
    (try (f payload)
         (catch Throwable e
           (binding [*out* *err*]
             (println (str "[supervisor] handler error for "
                           (name service-name) "/" (name event-type)
                           " (" (.getSimpleName (class e)) "): "
                           (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Service registry
;; ---------------------------------------------------------------------------

(defonce services
  ;; Shape: {service-name {:check-fn :restart-fn :restore-fn}}
  (atom {}))

(defonce service-state
  ;; Shape: {service-name {:status :up/:down/:unknown
  ;;                       :last-check-ms 0
  ;;                       :consecutive-failures 0}}
  (atom {}))

(defn register!
  "Register a supervised service.

  Options:
    :check-fn   — (fn []) → truthy if healthy.  Called every watchdog tick.
    :restart-fn — (fn []) → called when service goes :down.  Should attempt
                  reconnect.  May throw — the watchdog catches and logs.
    :restore-fn — (fn []) → called after a successful recovery to restore
                  last-known state (e.g. re-send synthdefs to SC).

  Call register! before start-watchdog! so the first check has a clean baseline."
  [service-name & {:keys [check-fn restart-fn restore-fn]}]
  (swap! services assoc service-name
         {:check-fn check-fn :restart-fn restart-fn :restore-fn restore-fn})
  (swap! service-state assoc service-name
         {:status :unknown :last-check-ms 0 :consecutive-failures 0})
  service-name)

(defn deregister!
  "Remove a service from supervision."
  [service-name]
  (swap! services dissoc service-name)
  (swap! service-state dissoc service-name)
  nil)

(defn service-status
  "Return the current known status of a service: :up, :down, or :unknown."
  [service-name]
  (get-in @service-state [service-name :status] :unknown))

(defn any-down?
  "Return truthy if any of the named services are currently :down.
  Used by the deflive-loop :pause-on-down machinery."
  [service-names]
  (some #(= :down (service-status %)) service-names))

(defn all-statuses
  "Return a map of service-name → status for all registered services."
  []
  (reduce-kv (fn [m k v] (assoc m k (:status v))) {} @service-state))

;; ---------------------------------------------------------------------------
;; Convenience registrations for built-in services
;; ---------------------------------------------------------------------------

(defn register-sc!
  "Register the SC server as a supervised service.
  Health check: sc/sc-connected?
  Restore: re-sends all synthdefs that were previously loaded.
  Restart: if sc/start-sc! was used to launch sclang, the supervisor will call
  sc/sc-restart! automatically when the health check fails — no terminal
  intervention required for audio device reconnects or sclang crashes.
  Pass :restart-fn to override (e.g. when SC is managed externally)."
  [& {:keys [restart-fn]}]
  (register! :sc
             :check-fn    sc/sc-connected?
             :restart-fn  (or restart-fn sc/sc-restart!)
             :restore-fn  sc/resend-sent-synthdefs!))

(defn register-sidecar!
  "Register the sidecar process as a supervised service.
  Health check: sidecar/connected?
  Restart: calls sidecar/restart-sidecar! using the opts from the last start.
  Restore: no-op — sidecar is stateless from the JVM's perspective."
  []
  (register! :sidecar
             :check-fn    sidecar/connected?
             :restart-fn  sidecar/restart-sidecar!))

;; ---------------------------------------------------------------------------
;; Health-check and state-transition logic
;; ---------------------------------------------------------------------------

(defn- log [& args]
  (binding [*out* *err*]
    (println (apply str "[supervisor] " args))))

(defn- try-restart! [service-name restart-fn]
  (try
    (restart-fn)
    (log (name service-name) " restart attempted")
    true
    (catch Throwable e
      (log (name service-name) " restart failed ("
           (.getSimpleName (class e)) "): " (.getMessage e))
      false)))

(defn- try-restore! [service-name restore-fn]
  (try
    (restore-fn)
    (log (name service-name) " state restored")
    true
    (catch Throwable e
      (log (name service-name) " restore failed ("
           (.getSimpleName (class e)) "): " (.getMessage e))
      (emit! service-name :restore-failed
             {:service service-name :error (.getMessage e)})
      false)))

(defn- transition-up! [service-name restore-fn now-ms extra-payload]
  (swap! service-state update service-name
         assoc :status :up :consecutive-failures 0)
  (when restore-fn (try-restore! service-name restore-fn))
  (emit! service-name :up (merge {:service service-name :at now-ms} extra-payload))
  (log (name service-name) " UP"))

(defn- transition-down! [service-name now-ms]
  (swap! service-state update service-name
         assoc :status :down :consecutive-failures 1)
  (emit! service-name :down {:service service-name :at now-ms})
  (log (name service-name) " DOWN"))

(defn- check-service! [service-name]
  (when-let [{:keys [check-fn restart-fn restore-fn]} (get @services service-name)]
    (let [now-ms   (System/currentTimeMillis)
          prev     (get @service-state service-name)
          healthy? (try (boolean (check-fn)) (catch Throwable _ false))]
      (swap! service-state update service-name assoc :last-check-ms now-ms)
      (cond
        ;; Healthy — was not :up
        (and healthy? (not= :up (:status prev)))
        (transition-up! service-name restore-fn now-ms {})

        ;; Unhealthy — was not :down
        (and (not healthy?) (not= :down (:status prev)))
        (do (transition-down! service-name now-ms)
            (when restart-fn
              (when (try-restart! service-name restart-fn)
                ;; Re-check immediately after restart attempt
                (let [recovered? (try (boolean (check-fn)) (catch Throwable _ false))]
                  (when recovered?
                    (transition-up! service-name restore-fn
                                    (System/currentTimeMillis) {:restarted true}))))))

        ;; Unhealthy — already :down
        (and (not healthy?) (= :down (:status prev)))
        (swap! service-state update service-name update :consecutive-failures inc)

        ;; Otherwise: status unchanged — no transition
        :else nil))))

;; ---------------------------------------------------------------------------
;; Loop thread monitoring
;; ---------------------------------------------------------------------------

(defn- check-loops! []
  (when-let [sref (loop-ns/-system-ref)]
    (when-let [sys @sref]
      (doseq [[loop-name entry] (:loops @sys)]
        (when (and @(:running? entry)
                   (when-let [t ^Thread (:thread entry)]
                     (not (.isAlive t))))
          (emit! :loops :down {:loop loop-name :at (System/currentTimeMillis)})
          (log "loop " (name loop-name) " thread DIED"))))))

;; ---------------------------------------------------------------------------
;; Watchdog thread
;; ---------------------------------------------------------------------------

(defonce watchdog-atom (atom nil))

(defn running?
  "Return true if the supervisor watchdog is running."
  []
  (boolean (some-> @watchdog-atom :running? deref)))

(defn- watchdog-sleep-ms
  "Compute the watchdog sleep duration in ms.
  When interval-ms is explicit, use it directly.
  Otherwise derive from BPM: bars × beats-per-bar × (60000 / bpm), clamped to
  at least 100 ms so a zero/nil BPM (before start!) is safe.
  beats-per-bar nil means: read from core/get-beats-per-bar each tick."
  [interval-ms bars beats-per-bar]
  (if interval-ms
    interval-ms
    (let [bpm  (or (core/get-bpm) 120)
          bpb  (or beats-per-bar (core/get-beats-per-bar))]
      (max 100 (long (* bars bpb (/ 60000.0 bpm)))))))

(defn start-watchdog!
  "Start the supervisor watchdog thread.

  Options:
    :interval-ms    — fixed check frequency in ms.  When omitted the interval
                      is re-derived from BPM each tick: bars × beats-per-bar ×
                      (60 000 / bpm).  This means a tempo change (set-bpm!) is
                      automatically reflected in the next watchdog sleep.
    :bars           — how many bars constitute one scan period (default 1).
    :beats-per-bar  — beats per bar for the BPM→ms conversion.  When omitted,
                      reads core/get-beats-per-bar on each tick, so
                      (core/set-beats-per-bar! 3) at runtime is automatically
                      reflected without restarting the watchdog.
    :monitor-loops? — whether to check loop thread liveness (default true).

  Wires the pause-check into deflive-loop so that :pause-on-down opts work.
  Call after register! so services have a baseline state before first check."
  [& {:keys [interval-ms bars beats-per-bar monitor-loops?]
      :or   {bars 1 monitor-loops? true}}]
  (when (running?)
    (throw (ex-info "Supervisor watchdog already running; call stop-watchdog! first" {})))
  (loop-ns/-register-pause-check! any-down?)
  (let [running?# (atom true)
        t         (Thread.
                   (fn []
                     (while @running?#
                       (try
                         (doseq [sname (keys @services)]
                           (check-service! sname))
                         (when monitor-loops?
                           (check-loops!))
                         (catch Throwable e
                           (log "watchdog tick error (" (.getSimpleName (class e))
                                "): " (.getMessage e))))
                       (try (Thread/sleep (long (watchdog-sleep-ms interval-ms bars beats-per-bar)))
                            (catch InterruptedException _)))))]
    (.setDaemon t true)
    (.setName t "cljseq-supervisor-watchdog")
    (reset! watchdog-atom {:thread t :running? running?#
                           :interval-ms interval-ms
                           :bars bars :beats-per-bar beats-per-bar})
    (.start t)
    :started))

(defn stop-watchdog!
  "Stop the supervisor watchdog and remove the pause-check hook from deflive-loop."
  []
  (when-let [{:keys [^Thread thread running?]} @watchdog-atom]
    (reset! running? false)
    (.interrupt thread)
    (reset! watchdog-atom nil))
  (loop-ns/-register-pause-check! nil)
  :stopped)
