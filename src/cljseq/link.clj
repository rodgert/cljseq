; SPDX-License-Identifier: EPL-2.0
(ns cljseq.link
  "cljseq Ableton Link integration.

  Coordinates with the C++ sidecar's LinkEngine via the IPC protocol.
  When Link is active, cljseq.loop/sleep! uses the Link timeline instead of
  the local BPM formula, providing beat-accurate sync with Link peers.

  ## Quick start
    (require '[cljseq.link :as link])
    (link/enable!)              ; join/create a Link session
    (link/bpm)                  ; => session BPM (from most recent push)
    (link/peers)                ; => number of connected peers
    (link/disable!)             ; leave the session

  ## How it works
  The sidecar pushes a LinkState frame (IPC 0x80) whenever tempo, peer count,
  or transport state changes. This namespace registers a push handler at load
  time. Each push updates :link-timeline in system-state — a map with the same
  shape as :timeline but anchored to the Link session.

  cljseq.loop/sleep! checks (link/active?) and, when true, uses :link-timeline
  to compute its wall-clock deadline. BPM changes from other Link peers take
  effect on the next sleep! call with no explicit broadcast (Q60-equivalent).

  Key design decisions: Q7 (Link integration), §15 of R&R doc."
  (:require [cljseq.sidecar :as sidecar])
  (:import  [java.nio ByteBuffer ByteOrder]
            [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state reference (injected by cljseq.core/start!)
;; ---------------------------------------------------------------------------

(def ^:private system-ref (atom nil))

(defn -register-system!
  "Called by cljseq.core/start! to inject the system-state atom.
  Not part of the public API."
  [state-atom]
  (reset! system-ref state-atom))

;; ---------------------------------------------------------------------------
;; LinkState push handler — registered with sidecar at namespace load time
;; ---------------------------------------------------------------------------

(defn- parse-link-state
  "Parse the 37-byte 0x80 payload into a Clojure map.

  Wire layout (LE):
    [double bpm(8)][double anchor_beat(8)][int64 anchor_us(8)]
    [uint32 peers(4)][uint8 playing(1)][uint8 reserved×3(3)]"
  [^bytes payload]
  (when (>= (alength payload) 37)
    (let [buf (ByteBuffer/wrap payload)]
      (.order buf ByteOrder/LITTLE_ENDIAN)
      {:bpm         (.getDouble buf)
       :anchor-beat (.getDouble buf)
       :anchor-us   (.getLong buf)
       :peers       (Integer/toUnsignedLong (.getInt buf))
       :playing     (pos? (bit-and (.get buf) 0xFF))})))

(defn- link-state->timeline
  "Convert a parsed LinkState into a :timeline-shaped map for use by
  cljseq.clock/beat->epoch-ms.

  Link's anchor-us is µs; the timeline expects epoch-ms."
  [{:keys [bpm anchor-beat anchor-us]}]
  {:bpm            bpm
   :beat0-beat     anchor-beat
   :beat0-epoch-ms (/ anchor-us 1000.0)})

(defn- on-link-state-push
  "Handle a 0x80 LinkState push from the sidecar.
  Updates :link-state and :link-timeline in system-state and unparks
  all sleeping loop threads so they recompute their deadline (same
  mechanism as Q60)."
  [^bytes payload]
  (when-let [ls (parse-link-state payload)]
    (when-let [s @system-ref]
      (swap! s (fn [state]
                 (-> state
                     (assoc :link-state    ls)
                     (assoc :link-timeline (link-state->timeline ls)))))
      ;; Unpark sleeping loop threads — they will recompute their wall-clock
      ;; deadline against the updated :link-timeline on next park-until-beat!
      (doseq [[_ entry] (:loops @s)]
        (when-let [^Thread t (:thread entry)]
          (LockSupport/unpark t))))))

;; Register push handler at namespace load time. The handler survives
;; stop!/start! cycles; sidecar/register-push-handler! just updates a map.
(sidecar/register-push-handler! 0x80 on-link-state-push)

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn active?
  "Return true if a Link session is currently active."
  []
  (boolean (when-let [s @system-ref] (:link-timeline @s))))

(defn link-timeline
  "Return the current Link timeline map (same shape as :timeline), or nil
  when Link is inactive. Used by cljseq.loop/sleep! to compute beat→epoch-ms."
  []
  (when-let [s @system-ref] (:link-timeline @s)))

(defn link-state
  "Return the most recently received raw LinkState map, or nil."
  []
  (when-let [s @system-ref] (:link-state @s)))

(defn bpm
  "Return the current session BPM as reported by Link, or nil when inactive."
  []
  (:bpm (link-state)))

(defn peers
  "Return the number of connected Link peers, or nil when inactive."
  []
  (:peers (link-state)))

(defn playing?
  "Return the Link transport state (true = playing), or nil when inactive."
  []
  (:playing (link-state)))

(defn enable!
  "Join or create a Link session.

  Options:
    :quantum — phrase length in beats (default 4). All peers that share the
               same quantum are guaranteed to be in phase with each other.

  Triggers an immediate 0x80 LinkState push from the sidecar, which populates
  :link-timeline and activates the Link timing path in sleep!."
  [& {:keys [quantum] :or {quantum 4}}]
  (when-not (sidecar/connected?)
    (throw (ex-info "sidecar not connected; call (start!) first" {})))
  (sidecar/send-link-enable! :quantum quantum))

(defn disable!
  "Leave the Link session. :link-timeline is cleared from system-state
  and sleep! reverts to the local BPM formula."
  []
  (when (sidecar/connected?)
    (sidecar/send-link-disable!))
  (when-let [s @system-ref]
    (swap! s dissoc :link-state :link-timeline)))

(defn set-bpm!
  "Propose a tempo change to the Link session. All peers will converge on
  the new BPM within one or two network round-trips."
  [bpm]
  (when (sidecar/connected?)
    (sidecar/send-link-set-bpm! (double bpm))))

;; ---------------------------------------------------------------------------
;; Quantum alignment helpers (used by deflive-loop when Link is active)
;; ---------------------------------------------------------------------------

(defn next-quantum-beat
  "Return the beat number of the next quantum boundary strictly after `beat`.

  Used by deflive-loop to delay loop start until the next bar boundary,
  ensuring the loop joins the session in phase (§15.4)."
  [beat quantum]
  (let [q (double quantum)
        b (double beat)]
    (* q (Math/ceil (/ b q)))))
