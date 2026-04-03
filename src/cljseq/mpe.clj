; SPDX-License-Identifier: EPL-2.0
(ns cljseq.mpe
  "MPE (MIDI Polyphonic Expression) voice allocator and per-note dispatch.

  Implements the MPE lower zone protocol:
    - Master channel: 1 (zone-wide CCs, pitch bend, program change)
    - Note channels: 2–9 (round-robin allocation, 8 voice polyphony)

  Per-note expression keys in step maps:
    :pitch/bend    — pitch offset in semitones (float; 0.0 = no bend)
    :midi/pressure — channel pressure 0–127 (y-axis on MPE controller)
    :mod/slide     — CC74 value 0–127 (z-axis; commonly timbre/slide)

  ## Quick start
    (mpe/enable-mpe!)
    (play! {:pitch/midi 60 :dur/beats 1
            :pitch/bend 0.5 :midi/pressure 80})

  ## Pitch bend range
    *mpe-bend-range* controls how many semitones equal a full-scale pitch bend.
    Default is 48 (±48 semitones, per MPE spec recommendation). Override:
      (binding [mpe/*mpe-bend-range* 24] ...)
    Match this to the Hydrasynth's per-patch NRPN 3FH/41H setting.

  ## Channel allocation
    Round-robin across channels 2–9. No LRU — if all 8 channels are active
    the oldest note on the wrapped channel gets a late note-off, which is
    acceptable for typical performance use. LRU can be added in a later phase.

  Wire format additions (sidecar.clj):
    0x04 MSG-PITCH-BEND   — [int64 ns][ch][lsb][msb][0]
    0x05 MSG-CHAN-PRESSURE — [int64 ns][ch][0][pressure][0]
  C++ sidecar handling for 0x04/0x05 is tracked in doc/handoff-sprint23-cpp.md."
  (:require [cljseq.clock   :as clock]
            [cljseq.sidecar :as sidecar]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *mpe-bend-range*
  "Maximum pitch bend range in semitones (one direction).
  Default 48 matches the MPE spec recommendation for full-range expression.
  Set to match the Hydrasynth per-patch pitch bend range (NRPN 3FH/41H)."
  48)

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private mpe-state
  (atom {:enabled? false
         :rr-idx   0}))   ; round-robin index 0–7 → channels 2–9

;; ---------------------------------------------------------------------------
;; Enable / disable
;; ---------------------------------------------------------------------------

(defn enable-mpe!
  "Enable MPE mode. Step maps with :pitch/bend, :midi/pressure, or :mod/slide
  will be routed through the MPE voice allocator in core/play!."
  []
  (swap! mpe-state assoc :enabled? true)
  nil)

(defn disable-mpe!
  "Disable MPE mode. All notes revert to standard single-channel dispatch."
  []
  (swap! mpe-state assoc :enabled? false)
  nil)

(defn mpe-enabled?
  "Return true if MPE mode is active."
  []
  (boolean (:enabled? @mpe-state)))

;; ---------------------------------------------------------------------------
;; Channel allocation (round-robin, channels 2–9)
;; ---------------------------------------------------------------------------

(defn- next-channel!
  "Atomically advance the round-robin index and return the next note channel (2–9)."
  []
  (let [[old _] (swap-vals! mpe-state update :rr-idx #(mod (inc %) 8))]
    (+ 2 (:rr-idx old))))

;; ---------------------------------------------------------------------------
;; Pitch bend conversion
;; ---------------------------------------------------------------------------

(defn semitones->bend14
  "Convert a semitone offset to a 14-bit MIDI pitch bend value.

  semitones — float pitch offset (positive = up, negative = down).
              Clamped to ±*mpe-bend-range*.
  Returns integer in [0, 16383]; center (no bend) = 8192.

  Examples (with *mpe-bend-range* = 48):
    (semitones->bend14  0.0)  => 8192
    (semitones->bend14 48.0)  => 16383
    (semitones->bend14 -48.0) => 0
    (semitones->bend14  1.0)  => 8363  (≈1/48 of 8191)"
  [semitones]
  (let [range-st  (double *mpe-bend-range*)
        clamped   (max (- range-st) (min range-st (double semitones)))
        frac      (/ clamped range-st)   ; -1.0 to +1.0
        ;; MIDI pitch bend: center=8192, max-positive=16383, max-negative=0
        ;; Positive direction uses 8191 steps (8192→16383); negative uses 8192 (8192→0)
        steps     (if (>= frac 0.0) 8191.0 8192.0)
        raw       (+ 8192 (Math/round (* frac steps)))]
    (max 0 (min 16383 (long raw)))))

;; ---------------------------------------------------------------------------
;; MPE step detection
;; ---------------------------------------------------------------------------

(defn mpe-step?
  "Return true if the step map contains any per-note MPE expression keys.
  Used by core/play! to route through play-mpe! when MPE is enabled."
  [step]
  (and (map? step)
       (or (contains? step :pitch/bend)
           (contains? step :midi/pressure)
           (contains? step :mod/slide))))

;; ---------------------------------------------------------------------------
;; MPE note dispatch
;; ---------------------------------------------------------------------------

(defn play-mpe!
  "Dispatch a step map as a full MPE note event.

  Allocates a note channel (2–9), sends pre-note pitch bend and/or channel
  pressure, then note-on, optional CC74 slide, note-off, and a pitch-bend
  reset to center after the note ends.

  step — step map with keys:
    :pitch/midi    — MIDI note number (required)
    :dur/beats     — duration in beats (default 1/4)
    :mod/velocity  — MIDI velocity 0–127 (default 64)
    :pitch/bend    — semitones offset (float; optional)
    :midi/pressure — channel pressure 0–127 (optional)
    :mod/slide     — CC74 0–127 (optional)
  now-beat — current virtual beat position (double)
  tl       — timeline map {:bpm n :beat0-epoch-ms t :beat0-beat b}

  No-op (returns nil) if the sidecar is not connected."
  [step now-beat tl]
  (when (sidecar/connected?)
    (let [midi     (long (:pitch/midi step))
          beats    (double (:dur/beats step 1/4))
          velocity (long (:mod/velocity step 64))
          bend     (:pitch/bend step)
          pressure (:midi/pressure step)
          slide    (:mod/slide step)
          ch       (next-channel!)
          on-ns    (clock/beat->epoch-ns (double now-beat) tl)
          off-ns   (clock/beat->epoch-ns (+ (double now-beat) beats) tl)]
      ;; Pre-note pitch bend on the allocated note channel
      (when bend
        (sidecar/send-pitch-bend! on-ns ch (semitones->bend14 bend)))
      ;; Per-note channel pressure
      (when pressure
        (sidecar/send-channel-pressure! on-ns ch (long pressure)))
      ;; Note-on
      (sidecar/send-note-on! on-ns ch midi velocity)
      ;; CC74 slide / timbre
      (when slide
        (sidecar/send-cc! on-ns ch 74 (long slide)))
      ;; Note-off
      (sidecar/send-note-off! off-ns ch midi)
      ;; Reset pitch bend to center after note ends (clean-up for next note on this channel)
      (sidecar/send-pitch-bend! off-ns ch 8192)
      ch)))
