; SPDX-License-Identifier: EPL-2.0
(ns cljseq.looper
  "Protocol and registry for looper/tape devices.

  `LooperDevice` defines the common vocabulary shared across loop-based and
  tape-delay devices. It covers only operations that are unambiguously present
  in all three supported devices (Aeros, EHX 95000, Strymon Volante). The rich
  device-specific vocabulary lives in the device namespaces:

    cljseq.aeros     — part transitions, RPO state machine, song select
    cljseq.ehx95000  — DUB feedback trajectories, OCT/half-speed, punch, SPP
    cljseq.volante   — head matrix routing, Mechanics/Wear, preset system, SOS

  ## Protocol

  Implementations receive a normalized `level` ∈ [0.0, 1.0] for `set-track-level!`
  and map it to the device's native scale internally — Aeros uses a non-linear dB
  scale, the 95000 uses linear 0–127, the Volante uses 0–127 per head.

  `select-scene!` semantics are intentionally loose: Aeros transitions to a part,
  the 95000 loads a loop, the Volante loads a preset. All produce a scene change.

  ## Registry

  Devices registered with `register-looper!` can be addressed collectively:

    (register-looper! :aeros   aeros-instance)
    (register-looper! :volante volante-instance)

    (start-all-loopers!)          ; transport-start! on all registered devices
    (stop-all-loopers!)           ; transport-stop! on all registered devices
    (set-all-levels! 1 0.75)      ; set track 1 to 75% on every device

  This lets conductors and mod routes address the looper layer without knowing
  which specific devices are connected at session time.

  ## Example

    (require '[cljseq.looper :as looper])
    (require '[cljseq.aeros  :as aeros])

    (def my-aeros (aeros/connect! {:channel 1}))
    (looper/register-looper! :aeros my-aeros)

    ;; Drive all looper track-1 levels from an arc trajectory
    (mod-route! [:arc/density]
                some-trajectory
                (fn [v] (looper/set-all-levels! 1 v)))

    ;; Conductor section that starts all loopers
    (defconductor! :session-start
      [{:bars 0
        :on-start (fn [] (looper/start-all-loopers!))}
       {:bars 32 :gesture :buildup}])")

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol LooperDevice
  "Common operations for loop-based and tape-delay devices.

  All implementations must handle the case where the device is not connected
  gracefully (log and no-op rather than throw)."

  (transport-start!
    [device]
    "Start transport / begin playback.
    Aeros: MIDI Start (0xFA). 95000: MIDI Start. Volante: not applicable (SOS only).")

  (transport-stop!
    [device]
    "Stop transport / halt playback.
    Aeros: CC:43 value 0. 95000: MIDI Stop. Volante: not applicable.")

  (select-scene!
    [device n]
    "Change to scene/loop/preset N (0-based).
    Aeros: transitions to song part N+1 (parts are 1-indexed). The transition
    timing follows the device's Change Part behavior setting.
    95000: loads loop N directly (PC message).
    Volante: loads preset N (PC message, bank 0).")

  (set-track-level!
    [device track-idx level]
    "Set the output level of track `track-idx` (0-based) to `level` ∈ [0.0, 1.0].
    Each implementation maps the normalized value to its native CC scale.
    Aeros: CC 21–26 (non-linear dB: 0→-inf, 0.89→0dB, 1.0→+6dB).
    95000: CC 20–25 (linear 0–127).
    Volante: CC 25–28 per head (linear 0–127).")

  (mute-track!
    [device track-idx]
    "Mute track `track-idx` (0-based).
    Aeros: CC:38 value 11–16 (EOL timing per device setting).
    95000: CC3/PC 120–125 (toggle — implementation must track state).
    Volante: CC 21–24 value 0 (head playback switch off).")

  (unmute-track!
    [device track-idx]
    "Unmute track `track-idx` (0-based).
    Aeros: CC:38 value 21–26 (EOL timing per device setting).
    95000: CC3/PC 120–125 (toggle — implementation must track state).
    Volante: CC 21–24 value 127 (head playback switch on)."))

;; ---------------------------------------------------------------------------
;; Device registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn register-looper!
  "Register a LooperDevice implementation under `name-kw`.
  Replaces any existing registration for that name.

  Example:
    (register-looper! :aeros my-aeros-instance)"
  [name-kw device]
  {:pre [(satisfies? LooperDevice device)]}
  (swap! registry assoc name-kw device)
  nil)

(defn unregister-looper!
  "Remove a registered looper by name. No-op if not registered."
  [name-kw]
  (swap! registry dissoc name-kw)
  nil)

(defn registered-loopers
  "Return a map of {name-kw → LooperDevice} for all registered devices."
  []
  @registry)

(defn all-loopers
  "Return a seq of all registered LooperDevice implementations."
  []
  (vals @registry))

;; ---------------------------------------------------------------------------
;; Collective operations
;; ---------------------------------------------------------------------------

(defn start-all-loopers!
  "Call transport-start! on every registered looper. Returns nil."
  []
  (doseq [d (all-loopers)]
    (transport-start! d))
  nil)

(defn stop-all-loopers!
  "Call transport-stop! on every registered looper. Returns nil."
  []
  (doseq [d (all-loopers)]
    (transport-stop! d))
  nil)

(defn set-all-levels!
  "Set track `track-idx` (0-based) to normalized `level` ∈ [0.0, 1.0]
  on every registered looper.

  Useful as a mod-route target:
    (mod-route! [:arc/density] traj (fn [v] (looper/set-all-levels! 0 v)))"
  [track-idx level]
  (doseq [d (all-loopers)]
    (set-track-level! d track-idx level))
  nil)

(defn mute-all-tracks!
  "Mute track `track-idx` on every registered looper."
  [track-idx]
  (doseq [d (all-loopers)]
    (mute-track! d track-idx))
  nil)

(defn unmute-all-tracks!
  "Unmute track `track-idx` on every registered looper."
  [track-idx]
  (doseq [d (all-loopers)]
    (unmute-track! d track-idx))
  nil)

;; ---------------------------------------------------------------------------
;; Level scaling helpers (used by device implementations)
;; ---------------------------------------------------------------------------

(defn normalized->midi-linear
  "Map normalized level ∈ [0.0, 1.0] to MIDI CC range [0, 127]."
  ^long [level]
  (max 0 (min 127 (Math/round (* (double level) 127.0)))))

(defn normalized->aeros-fader
  "Map normalized level ∈ [0.0, 1.0] to Aeros fader CC value [0, 127].

  Aeros fader scale: 0 = -inf dBFS, 113 = 0 dB, 127 = +6 dB.
  We map:
    0.0  → 0   (-inf)
    0.89 → 113 (0 dB reference — unity gain)
    1.0  → 127 (+6 dB)"
  ^long [level]
  (let [v (double level)]
    (cond
      (<= v 0.0)  0
      (>= v 1.0)  127
      ;; Linear interpolation in two segments:
      ;; [0.0, 0.89] → [0, 113]  (below unity)
      ;; [0.89, 1.0] → [113, 127] (above unity)
      (<= v 0.89) (Math/round (* v (/ 113.0 0.89)))
      :else       (Math/round (+ 113.0 (* (- v 0.89) (/ 14.0 0.11)))))))
