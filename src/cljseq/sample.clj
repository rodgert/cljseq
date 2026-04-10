; SPDX-License-Identifier: EPL-2.0
(ns cljseq.sample
  "Sample player — SC buffer management and playback.

  Wraps SC's Buffer/PlayBuf/TGrains machinery with the cljseq naming and
  dispatch conventions. Integrates with play! via the :sample step-map key.

  ## Basic workflow

    (require '[cljseq.sc     :as sc])
    (require '[cljseq.sample :as smp])

    (sc/connect-sc!)

    ;; Register and load a sample
    (smp/defbuffer! ::kick \"/samples/kick.wav\")
    (smp/load-sample! ::kick)

    ;; Play it directly
    (smp/sample! ::kick)
    (smp/sample! ::kick :rate 0.5 :amp 0.6)   ; slower, quieter

    ;; Or via play! in a live loop (same dispatch as :synth)
    (deflive-loop :drums {}
      (play! {:sample ::kick :mod/velocity 100})
      (sleep! 1))

  ## Granular playback (via :granular-cloud patch)

    (smp/defbuffer! ::texture \"/samples/texture.wav\")
    (smp/load-sample! ::texture)

    (def inst (sc/instantiate-patch! :granular-cloud))
    (sc/set-patch-param! inst :buf (smp/buffer-id ::texture))
    (sc/set-patch-param! inst :density 12)
    (sc/set-patch-param! inst :pos 0.3)

    ;; Automate grain density with a trajectory
    (sc/apply-trajectory! (get-in inst [:nodes :grains]) :density
      (traj/trajectory :from 4 :to 32 :beats 16 :curve :s-curve :start (now)))

  ## Buffer deduplication

  load-sample! is idempotent by path — multiple names can share one SC buffer:

    (smp/defbuffer! ::kick-dry  \"/samples/kick.wav\")
    (smp/defbuffer! ::kick-copy \"/samples/kick.wav\")
    (smp/load-sample! ::kick-dry)
    (smp/load-sample! ::kick-copy)
    ;; Both share the same SC buffer ID"
  (:require [cljseq.core :as core]
            [cljseq.sc   :as sc]))

;; ---------------------------------------------------------------------------
;; Buffer registry
;; ---------------------------------------------------------------------------

(defonce ^:private buffer-registry
  (atom {:by-name {}      ; {buffer-key {:path str :id int|nil}}
         :by-path {}}))   ; {path buffer-id} — deduplication index

(defn defbuffer!
  "Register a named sample buffer with its file path.

  Does not allocate an SC buffer — call load-sample! to load it into SC.
  Idempotent: re-registering replaces the previous path.

  Example:
    (smp/defbuffer! ::kick \"/samples/kick.wav\")
    (smp/defbuffer! :snare \"/samples/snare.wav\")"
  [buffer-name path]
  (when-not (keyword? buffer-name)
    (throw (ex-info "defbuffer!: name must be a keyword" {:name buffer-name})))
  (swap! buffer-registry assoc-in [:by-name buffer-name] {:path path :id nil})
  buffer-name)

(defn buffer-id
  "Return the SC buffer ID for a named buffer, or nil if not yet loaded."
  [buffer-name]
  (get-in @buffer-registry [:by-name buffer-name :id]))

(defn buffer-path
  "Return the file path registered for buffer-name, or nil."
  [buffer-name]
  (get-in @buffer-registry [:by-name buffer-name :path]))

(defn sample-names
  "Return all registered buffer names."
  []
  (keys (:by-name @buffer-registry)))

(defn- record-loaded! [buffer-name path buf-id]
  (swap! buffer-registry
         #(-> %
              (assoc-in [:by-name buffer-name :id]   buf-id)
              (assoc-in [:by-name buffer-name :path] path)
              (assoc-in [:by-path path]               buf-id))))

;; ---------------------------------------------------------------------------
;; Loading
;; ---------------------------------------------------------------------------

(defn load-sample!
  "Allocate an SC buffer and load a sample from disk.

  1-arity: uses the path registered with defbuffer!
  2-arity: loads from path directly (also registers under buffer-name)

  Idempotent by path — if the same file has already been loaded (under any
  name), returns the cached buffer ID without sending another /b_allocRead.

  Returns the SC buffer ID (integer).

  Example:
    (smp/defbuffer! ::kick \"/samples/kick.wav\")
    (smp/load-sample! ::kick)                    ; => 0
    (smp/load-sample! ::kick2 \"/samples/kick.wav\") ; => 0 (cached)"
  ([buffer-name]
   (let [path (buffer-path buffer-name)]
     (when-not path
       (throw (ex-info "load-sample!: buffer not registered — call defbuffer! first"
                       {:name buffer-name})))
     (load-sample! buffer-name path)))
  ([buffer-name path]
   (if-let [cached (get-in @buffer-registry [:by-path path])]
     (do (record-loaded! buffer-name path cached)
         cached)
     (let [buf-id (sc/sc-buffer-alloc! path)]
       (record-loaded! buffer-name path buf-id)
       buf-id))))

(defn unload-sample!
  "Free the SC buffer for buffer-name and remove it from the registry."
  [buffer-name]
  (when-let [buf-id (buffer-id buffer-name)]
    (sc/sc-buffer-free! buf-id)
    (let [path (buffer-path buffer-name)]
      (swap! buffer-registry
             #(-> %
                  (assoc-in [:by-name buffer-name :id] nil)
                  (update :by-path dissoc path)))))
  nil)

;; ---------------------------------------------------------------------------
;; Playback
;; ---------------------------------------------------------------------------

(defn sample!
  "Fire a one-shot sample playback on SC. Returns the node ID.

  `buffer-name` must have been loaded with load-sample!.

  Options:
    :rate — playback rate (default 1.0; 2.0 = octave up; negative = reverse)
    :amp  — amplitude 0–1 (default 0.8)
    :pan  — stereo position -1 to 1 (default 0.0)

  Example:
    (smp/sample! ::kick)
    (smp/sample! ::snare :rate 0.8 :amp 0.6 :pan -0.3)"
  [buffer-name & {:keys [rate amp pan] :or {rate 1.0 amp 0.8 pan 0.0}}]
  (let [buf-id (buffer-id buffer-name)]
    (when-not buf-id
      (throw (ex-info "sample!: buffer not loaded — call load-sample! first"
                      {:name buffer-name})))
    (sc/sc-play! {:synth :buf-player
                  :buf   (double buf-id)
                  :rate  (double rate)
                  :amp   (double amp)
                  :pan   (double pan)
                  :dur-ms 30000})))   ; long enough for any sample; PlayBuf doneAction:2 frees it

(defn loop-sample!
  "Start a looping buffer playback on SC. Returns the node ID.
  Call free-synth! or kill-synth! on the returned node-id to stop.

  Example:
    (def loop-node (smp/loop-sample! ::texture :rate 0.95))
    (sc/free-synth! loop-node)"
  [buffer-name & {:keys [rate amp pan] :or {rate 1.0 amp 0.8 pan 0.0}}]
  (let [buf-id (buffer-id buffer-name)]
    (when-not buf-id
      (throw (ex-info "loop-sample!: buffer not loaded" {:name buffer-name})))
    (sc/sc-synth! :buf-looper {:buf  (double buf-id)
                                :rate (double rate)
                                :amp  (double amp)
                                :pan  (double pan)})))

;; ---------------------------------------------------------------------------
;; play! integration — :sample dispatch
;; ---------------------------------------------------------------------------

(defn- sample-play-dispatch!
  "Handle a :sample step map routed from core/play!.
  Signature matches what core/play! passes: [note dur bpm]."
  [note _dur _bpm]
  (when (sc/sc-connected?)
    (let [buf-key (:sample note)
          buf-id  (buffer-id buf-key)]
      (when buf-id
        (sc/sc-play! {:synth  :buf-player
                      :buf    (double buf-id)
                      :rate   (double (or (:sample/rate note) 1.0))
                      :amp    (double (or (:mod/velocity note)
                                         (* (:mod/velocity note 64) (/ 1.0 127))))
                      :pan    (double (or (:pan note) 0.0))
                      :dur-ms 30000})))))

(core/register-sample-dispatch! sample-play-dispatch!)

;; ---------------------------------------------------------------------------
;; :granular-cloud patch wiring (convenience)
;; ---------------------------------------------------------------------------

(defn granular-cloud!
  "Instantiate the :granular-cloud patch and wire buffer-name as the grain source.

  Returns the patch instance. Use sc/set-patch-param! to control in real time.

  Example:
    (def cloud (smp/granular-cloud! ::texture :density 10 :pos 0.4))
    (sc/set-patch-param! cloud :density 20)
    (sc/free-patch! cloud)"
  [buffer-name & {:keys [density pos rate dur room mix]
                  :or   {density 8 pos 0.5 rate 1.0 dur 0.12 room 0.7 mix 0.4}}]
  (let [buf-id (buffer-id buffer-name)]
    (when-not buf-id
      (throw (ex-info "granular-cloud!: buffer not loaded" {:name buffer-name})))
    (let [inst (sc/instantiate-patch! :granular-cloud)]
      (sc/set-patch-param! inst :buf     (double buf-id))
      (sc/set-patch-param! inst :density (double density))
      (sc/set-patch-param! inst :pos     (double pos))
      (sc/set-patch-param! inst :rate    (double rate))
      (sc/set-patch-param! inst :dur     (double dur))
      (sc/set-patch-param! inst :room    (double room))
      (sc/set-patch-param! inst :mix     (double mix))
      inst)))
