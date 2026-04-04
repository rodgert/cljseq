; SPDX-License-Identifier: EPL-2.0
(ns cljseq.learn
  "MIDI Learn — interactive device map authoring tool.

  Guides the user through tapping/wiggling physical controls while cljseq
  listens on the sidecar MIDI input port. The result is a device EDN map
  written to (dirs/devices-dir) — loadable immediately by load-device-map.

  ## Workflow

    ;; 1. Start a sidecar with MIDI input monitoring enabled
    (start-sidecar! :midi-port 0 :midi-in-port 1)

    ;; 2. Open a learn session for the device being mapped
    (start-learn-session! :lm-drum
      {:device/name         \"Behringer LM DRUM\"
       :device/manufacturer \"Behringer\"
       :device/type         :drum-machine
       :midi/channel        10})

    ;; 3a. Learn drum pad → note assignments (tap each pad in order)
    (learn-notes! :lm-drum [:kick :snare :hihat-closed :hihat-open
                             :tom1 :tom2 :clap :rimshot])

    ;; 3b. Learn CC parameters for a synthesizer (wiggle each control)
    (learn-cc! :my-synth [:filter1 :cutoff])
    (learn-sequence! :my-synth [[:filter1 :cutoff]
                                 [:filter1 :resonance]
                                 [:filter2 :cutoff]])

    ;; 4. Check progress mid-session
    (learn-session-state :lm-drum)

    ;; 5. Export to (dirs/devices-dir)/lm-drum.edn
    (export-device-map! :lm-drum)

    ;; or to a specific path:
    (export-device-map! :lm-drum \"resources/devices/lm-drum.edn\")

  ## Cancellation / timeout

    (cancel-learn!)     ; stop waiting; keep already-captured entries
    (learning?)         ; true while waiting for a control touch

  ## Testing without hardware

    (learn/-inject-message! {:status 0x99 :b1 36 :b2 100})  ; fake note-on ch 10
    (learn/-inject-message! {:status 0xB9 :b1 74 :b2 64})   ; fake CC ch 10

  ## Exported EDN schema (subset shown)

    {:device/id    :lm-drum
     :device/name  \"Behringer LM DRUM\"
     :device/type  :drum-machine
     :device/role  :target
     :midi/channel 10
     :midi/notes   [{:note 36 :path [:kick]  :label \"kick\"}  ...]
     :midi/cc      [{:cc 74  :path [:filter1 :cutoff] :range [0 127]} ...]}

  Key design decisions: R&R §32 (device map authoring), dirs §32."
  (:require [clojure.java.io  :as io]
            [clojure.pprint   :as pprint]
            [clojure.string   :as str]
            [cljseq.dirs      :as dirs]
            [cljseq.sidecar   :as sidecar])
  (:import  [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ---------------------------------------------------------------------------
;; Session registry
;; {session-id {:meta {} :notes [] :ccs []}}
;; Sessions persist until exported or explicitly cleared.
;; ---------------------------------------------------------------------------

(defonce ^:private sessions (atom {}))

;; ---------------------------------------------------------------------------
;; Per-capture message queue
;; The sidecar watcher and -inject-message! both push here.
;; The runner thread drains it.
;; ---------------------------------------------------------------------------

(defonce ^:private ^LinkedBlockingQueue msg-queue (LinkedBlockingQueue.))

;; ---------------------------------------------------------------------------
;; Active learn state — at most one capture sequence runs at a time
;; ---------------------------------------------------------------------------

(defonce ^:private learn-state
  (atom {:status     :idle   ; :idle | :waiting
         :cancel?    (atom false)
         :runner     nil}))

;; ---------------------------------------------------------------------------
;; MIDI message decoding
;; Sidecar format: {:time-ns N :status N :b1 N :b2 N}
;; status = command-nibble | (channel - 1)
;; ---------------------------------------------------------------------------

(defn- msg-command  [msg] (bit-and (:status msg) 0xF0))
(defn- msg-channel  [msg] (inc (bit-and (:status msg) 0x0F)))
(defn- msg-note     [msg] (:b1 msg))
(defn- msg-cc-num   [msg] (:b1 msg))

(defn- note-on?
  "True for Note On messages with velocity > 0. Excludes note-off disguised
  as note-on with velocity 0."
  [msg]
  (and (= 0x90 (msg-command msg))
       (> (int (:b2 msg)) 0)))

(defn- cc?
  [msg]
  (= 0xB0 (msg-command msg)))

;; ---------------------------------------------------------------------------
;; Sidecar watcher — feeds msg-queue while learning is active
;; ---------------------------------------------------------------------------

(defn- install-sidecar-watcher! []
  (add-watch sidecar/midi-in-messages ::learn-capture
             (fn [_ _ old new]
               (when (> (count new) (count old))
                 (let [new-msgs (subvec new (count old))]
                   (doseq [msg new-msgs]
                     (.offer msg-queue msg)))))))

(defn- remove-sidecar-watcher! []
  (remove-watch sidecar/midi-in-messages ::learn-capture))

;; ---------------------------------------------------------------------------
;; Capture runner helpers
;; ---------------------------------------------------------------------------

(defn- capture-prompt [type name]
  (case type
    :note (str "tap " (clojure.core/name name) " →")
    :cc   (str "wiggle control for " (pr-str name) " →")))

(defn- capture-confirm [type name result]
  (case type
    :note (format "%-20s note %-3d  ch %d"
                  (str (clojure.core/name name) ":") (:note result) (:channel result))
    :cc   (format "%-24s CC %-3d  ch %d"
                  (str (pr-str name) ":") (:cc result) (:channel result))))

(defn- poll-matching!
  "Block on msg-queue until a message matching `pred` arrives, cancel? becomes
  true, or `timeout-ms` elapses. Returns the message or nil."
  [pred cancel? timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))]
        (cond
          @cancel?          nil
          (neg? remaining)  nil
          :else
          (if-let [msg (.poll msg-queue (min remaining 100) TimeUnit/MILLISECONDS)]
            (if (pred msg)
              msg
              (recur))            ; wrong type — discard and keep waiting
            (recur)))))))

(defn- capture-one!
  "Wait for one matching MIDI message for `item` and record it in `session-id`.
  Returns true if captured, false on timeout or cancel."
  [session-id item cancel? timeout-ms]
  (let [{:keys [name type]} item
        pred (case type :note note-on? :cc cc?)]
    (println (str "[learn] " (capture-prompt type name)))
    (if-let [msg (poll-matching! pred cancel? timeout-ms)]
      (let [ch     (msg-channel msg)
            result (case type
                     :note {:type :note :channel ch :note   (msg-note msg)}
                     :cc   {:type :cc   :channel ch :cc     (msg-cc-num msg)})]
        (println (str "[learn] " (capture-confirm type name result)))
        (swap! sessions update-in [session-id (if (= type :note) :notes :ccs)]
               conj (assoc result :name name))
        true)
      (do
        (println (str "[learn] timeout waiting for " (pr-str name)))
        false))))

(defn- run-capture-sequence!
  "Runner thread body: captures each item in `queue` for `session-id`."
  [session-id queue cancel? timeout-ms]
  (fn []
    (try
      (doseq [item queue]
        (when-not @cancel?
          (when-not (capture-one! session-id item cancel? timeout-ms)
            (reset! cancel? true))))  ; timeout on any item halts the sequence
      (finally
        (remove-sidecar-watcher!)
        (.clear msg-queue)
        (swap! learn-state assoc :status :idle)
        (when (not @cancel?)
          (println (str "[learn] session " (pr-str session-id) " complete. "
                        "Call (export-device-map! " (pr-str session-id) ") to save.")))))))

;; ---------------------------------------------------------------------------
;; Internal: start a capture sequence
;; ---------------------------------------------------------------------------

(defn- start-capture! [session-id queue timeout-ms]
  (when (= :waiting (:status @learn-state))
    (throw (ex-info "A learn capture is already running. Call (cancel-learn!) first."
                    {})))
  (let [cancel? (atom false)]
    (.clear msg-queue)
    (install-sidecar-watcher!)
    (let [runner (Thread. (run-capture-sequence! session-id queue cancel? timeout-ms))]
      (swap! learn-state assoc :status :waiting :cancel? cancel? :runner runner)
      (.setDaemon runner true)
      (.setName  runner "cljseq-learn-runner")
      (.start    runner))))

;; ---------------------------------------------------------------------------
;; Public API — session lifecycle
;; ---------------------------------------------------------------------------

(defn start-learn-session!
  "Open a new device-map authoring session.

  `session-id` — keyword identifying this session (also becomes :device/id
                 in the exported map)
  `meta`       — device metadata map. Recognized keys:
                   :device/name         — human-readable name (required for export)
                   :device/manufacturer — manufacturer string
                   :device/type         — :synthesizer | :drum-machine | :controller
                   :device/role         — :target | :controller (default :target)
                   :midi/channel        — primary MIDI channel (default 1)

  Re-opening the same session-id preserves previously captured entries.

  Example:
    (start-learn-session! :lm-drum
      {:device/name  \"Behringer LM DRUM\"
       :device/type  :drum-machine
       :midi/channel 10})"
  [session-id meta]
  (swap! sessions update session-id
         (fn [existing]
           (-> (or existing {:notes [] :ccs []})
               (assoc :meta meta))))
  (println (str "[learn] session " (pr-str session-id) " ready. "
                "Call (learn-notes! ...) or (learn-cc! ...) to begin."))
  session-id)

(defn cancel-learn!
  "Stop the current capture sequence. Already-captured entries are preserved.
  No-op if no capture is running."
  []
  (when-let [c (:cancel? @learn-state)]
    (reset! c true))
  nil)

(defn learning?
  "True while a capture sequence is actively waiting for MIDI input."
  []
  (= :waiting (:status @learn-state)))

(defn learn-session-state
  "Return the current state of `session-id`: metadata and captured entries.

  Keys in the returned map:
    :meta   — device metadata from start-learn-session!
    :notes  — vec of {:name kw :channel N :note N} (from learn-notes!)
    :ccs    — vec of {:name path :channel N :cc N}  (from learn-cc!/learn-sequence!)

  Returns nil if the session has not been started."
  [session-id]
  (clojure.core/get @sessions session-id))

(defn clear-session!
  "Discard all captured data for `session-id`. Does not affect running captures."
  [session-id]
  (swap! sessions dissoc session-id)
  nil)

;; ---------------------------------------------------------------------------
;; Public API — capture
;; ---------------------------------------------------------------------------

(defn learn-notes!
  "Learn note-on assignments for a list of named voices/pads.

  Waits for one Note On message per name, in order. Each message captures the
  note number and channel. Intended for drum machines, samplers, and other
  instruments where pads transmit distinct note numbers.

  `session-id` — an open learn session
  `names`      — seq of keywords naming each pad/voice in tap order
  `opts`
    :timeout-ms  — per-item timeout in milliseconds (default 8000)

  Example:
    (learn-notes! :lm-drum [:kick :snare :hihat-closed :hihat-open])"
  [session-id names & {:keys [timeout-ms] :or {timeout-ms 8000}}]
  (when-not (clojure.core/get @sessions session-id)
    (throw (ex-info (str "No session for " (pr-str session-id)
                         ". Call (start-learn-session! ...) first.")
                    {:session-id session-id})))
  (let [queue (mapv #(hash-map :name % :type :note) names)]
    (println (str "[learn] learning " (count queue) " note assignment(s) for "
                  (pr-str session-id) " — tap each pad when prompted."))
    (start-capture! session-id queue timeout-ms)))

(defn learn-cc!
  "Learn the CC assignment for a single ctrl path.

  Waits for one Control Change message. Captures CC number and channel.

  `session-id` — an open learn session
  `path`       — ctrl path vector, e.g. [:filter1 :cutoff]
  `opts`
    :timeout-ms  — timeout in milliseconds (default 8000)

  Example:
    (learn-cc! :my-synth [:filter1 :cutoff])"
  [session-id path & {:keys [timeout-ms] :or {timeout-ms 8000}}]
  (when-not (clojure.core/get @sessions session-id)
    (throw (ex-info (str "No session for " (pr-str session-id)
                         ". Call (start-learn-session! ...) first.")
                    {:session-id session-id})))
  (start-capture! session-id [{:name path :type :cc}] timeout-ms))

(defn learn-sequence!
  "Learn CC assignments for a sequence of ctrl paths.

  Wiggle each control in order when prompted. One CC message is captured
  per path.

  `session-id` — an open learn session
  `paths`      — seq of ctrl path vectors
  `opts`
    :timeout-ms — per-item timeout in milliseconds (default 8000)

  Example:
    (learn-sequence! :my-synth [[:filter1 :cutoff]
                                 [:filter1 :resonance]
                                 [:filter2 :cutoff]])"
  [session-id paths & {:keys [timeout-ms] :or {timeout-ms 8000}}]
  (when-not (clojure.core/get @sessions session-id)
    (throw (ex-info (str "No session for " (pr-str session-id)
                         ". Call (start-learn-session! ...) first.")
                    {:session-id session-id})))
  (let [queue (mapv #(hash-map :name % :type :cc) paths)]
    (println (str "[learn] learning " (count queue) " CC assignment(s) for "
                  (pr-str session-id) " — wiggle each control when prompted."))
    (start-capture! session-id queue timeout-ms)))

;; ---------------------------------------------------------------------------
;; Public API — export
;; ---------------------------------------------------------------------------

(defn- notes->edn [notes]
  (mapv (fn [{:keys [name note channel]}]
          {:note    note
           :path    [name]
           :label   (clojure.core/name name)
           :channel channel})
        notes))

(defn- ccs->edn [ccs]
  (mapv (fn [{:keys [name cc channel]}]
          {:cc      cc
           :path    name        ; already a path vector
           :range   [0 127]
           :channel channel})
        ccs))

(defn- session->edn [session-id]
  (let [{:keys [meta notes ccs]} (clojure.core/get @sessions session-id)
        channel (or (:midi/channel meta)
                    (:channel (first (concat notes ccs)))
                    1)]
    (cond-> {:device/id           session-id
             :device/name         (or (:device/name meta) (name session-id))
             :device/manufacturer (or (:device/manufacturer meta) "Unknown")
             :device/type         (or (:device/type meta) :synthesizer)
             :device/role         (or (:device/role meta) :target)
             :device/connectivity [:usb-midi :din-midi]
             :midi/channel        channel}
      (seq notes) (assoc :midi/notes (notes->edn notes))
      (seq ccs)   (assoc :midi/cc    (ccs->edn ccs)))))

(defn export-device-map!
  "Write the learned device map for `session-id` to an EDN file.

  `session-id`  — an open session with at least one captured entry
  `output-path` — (optional) absolute or relative path to write.
                  Default: (dirs/devices-dir)/<session-name>.edn

  The file is written in a format compatible with load-device-map and
  defdevice. If written to (dirs/devices-dir) it will be found automatically
  by resolve-device-resource, shadowing any built-in map of the same name.

  Returns the path written.

  Example:
    (export-device-map! :lm-drum)
    (export-device-map! :lm-drum \"resources/devices/lm-drum.edn\")"
  ([session-id]
   (export-device-map! session-id
                       (str (dirs/devices-dir) "/" (name session-id) ".edn")))
  ([session-id output-path]
   (when-not (clojure.core/get @sessions session-id)
     (throw (ex-info (str "No session for " (pr-str session-id)) {})))
   (let [edn-map (session->edn session-id)
         content (with-out-str
                   (println (str ";; cljseq device map — " (:device/name edn-map)))
                   (println (str ";; Generated by cljseq.learn"))
                   (println (str ";; SPDX-License-Identifier: EPL-2.0"))
                   (println)
                   (pprint/pprint edn-map))]
     (io/make-parents output-path)
     (spit output-path content)
     (println (str "[learn] exported " (pr-str session-id) " → " output-path))
     output-path)))

;; ---------------------------------------------------------------------------
;; Test hook
;; ---------------------------------------------------------------------------

(defn -inject-message!
  "Inject a raw MIDI message map into the learn capture queue.

  Used for testing without physical hardware. The message format matches the
  sidecar's MidiIn payload: {:status N :b1 N :b2 N} (time-ns optional).

  `status` byte encodes command nibble and channel-1:
    Note On  ch N: (bit-or 0x90 (dec N))  e.g. 0x99 = Note On ch 10
    CC       ch N: (bit-or 0xB0 (dec N))  e.g. 0xB9 = CC ch 10

  Example:
    (learn/-inject-message! {:status 0x99 :b1 36 :b2 100})  ; kick, ch 10
    (learn/-inject-message! {:status 0xB1 :b1 74 :b2 64})   ; CC 74 ch 2"
  [msg]
  (.offer msg-queue (merge {:time-ns 0} msg)))
