; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ivk
  "cljseq.ivk — extensible keyboard layout registry.

  The computer keyboard becomes a live-performance control surface.
  Each layout maps QWERTY keys to musical actions; actions dispatch through
  an open multimethod so new layouts never require touching ivk core.

  Layouts are pure Clojure data:

    {:layout/id   :my-layout
     :layout/name \"My layout\"
     :key-map     {\\a {:action :play-absolute :midi 60}
                   \\s {:action :apply-interval :n 1}
                   ...}}

  Built-in layouts
  ─────────────────
  :interval  — Samchillian/Misha-style diatonic interval steps
  :chromatic — Bitwig-style piano layout (home row = white keys)
  :scale     — absolute diatonic scale degrees
  :ndlr      — NDLR-inspired chord surface; left hand = chord functions,
                right hand = chord tones; updates *harmony-ctx* globally

  Arp mode
  ─────────
  Any layout can be combined with arp mode. When :arp? true in ivk-state,
  key presses do not play immediately — they queue into :arp-notes. The arp
  engine cycles through that queue at the current Link tempo according to
  :arp-rate (default 1/4 beat). Use start-arp! / stop-arp! to control the
  arp clock independently of key input.

  Sidecar integration
  ────────────────────
  Keyboard events arrive as 0x21 KbdEvent frames (added in ivk sprint).
  Call start-kbd! to register the push handler and optionally restart the
  sidecar with --kbd flag.

  Public API
  ──────────
  start-kbd!          — register 0x21 handler; optionally (re)start sidecar
  stop-kbd!           — deregister handler; clear arp
  register-layout!    — add a layout to the registry
  set-layout!         — switch to a named layout
  set-pitch!          — set :current-pitch (e.g. from T-1 passthru)
  current-pitch       — return current :current-pitch MIDI integer
  start-arp!          — start arp engine cycling :arp-notes
  stop-arp!           — stop arp engine
  ivk-state           — atom holding full ivk state (for inspection)"
  (:require [cljseq.chord   :as chord]
            [cljseq.core    :as core]
            [cljseq.loop    :as loop-ns]
            [cljseq.pitch   :as pitch]
            [cljseq.scale   :as scale]
            [cljseq.sidecar :as sidecar])
  (:import  [java.nio ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(def ivk-state
  "Atom holding all keyboard layout state.

  Keys:
    :current-pitch  — MIDI note number tracking position in pitch space (60 = C4)
    :layout         — active layout keyword
    :scale          — Scale record from *harmony-ctx* or explicit override
    :octave-range   — [lo hi] MIDI clamp window for output (default [36 84])
    :chord-fn       — active chord function for :ndlr layout (default :I)
    :chord-ext      — chord extension depth: :triad :seventh :ninth (cycles via \\[)
    :passthru?      — when true, incoming T-1 Note Ons update :current-pitch
    :arp?           — when true, key presses queue into :arp-notes instead of playing
    :arp-notes      — vector of MIDI note numbers queued for arp playback
    :arp-rate       — arp step duration in beats (default 1/4)
    :play-dur       — note duration in beats for direct key presses (default 1/4);
                      may be set to a phasor-fn (fn [] beats) for modulated duration
    :modulation     — map of controller values, each a static number OR a (fn [] value):
                        {:cc-1   64      ; mod wheel position 0–127
                         :cc-74  0       ; filter cutoff
                         :bend   8192    ; pitch bend 0–16383 (center = 8192)}
                      Key pair actions (:cc-delta / :pitch-bend-delta) update these
                      and send to sidecar immediately. Bind a phasor fn for auto-sweep:
                        (swap! ivk-state assoc-in [:modulation :cc-1] #(mod-lfo))
    :channel        — MIDI channel for CC and pitch bend sends (default 1)
    :voiced-chord?  — when true, chord-function keys (:set-chord-fn) also play
                      the root note of the new chord. Default false (silent switch)."
  (atom {:current-pitch  60
         :layout         :interval
         :scale          nil
         :octave-range   [36 84]
         :chord-fn       :I
         :chord-ext      :triad
         :passthru?      false
         :arp?           false
         :arp-notes      []
         :arp-rate       1/4
         :play-dur       1/4
         :modulation     {:bend 8192}
         :channel        1
         :voiced-chord?  false}))

;; ---------------------------------------------------------------------------
;; Layout registry
;; ---------------------------------------------------------------------------

(def ^:private layout-registry
  "Atom holding the map of layout-id → layout map."
  (atom {}))

(defn register-layout!
  "Add or replace a layout in the registry.
  `id`     — keyword identifying the layout (e.g. :interval)
  `layout` — map with at least :layout/id and :key-map keys."
  [id layout]
  (swap! layout-registry assoc id layout))

(defn set-layout!
  "Switch to the named layout. Throws if not registered."
  [id]
  (when-not (get @layout-registry id)
    (throw (ex-info (str "ivk: layout not registered: " id) {:id id})))
  (swap! ivk-state assoc :layout id)
  id)

;; ---------------------------------------------------------------------------
;; Pitch helpers
;; ---------------------------------------------------------------------------

(defn- clamp-midi
  "Clamp midi note n to the :octave-range in state."
  [n [lo hi]]
  (max lo (min hi n)))

(defn- midi->pitch-safe
  "Convert MIDI integer to Pitch, defaulting to C4 on error."
  [midi]
  (try (pitch/midi->pitch (int midi))
       (catch Exception _ (pitch/midi->pitch 60))))

(defn- resolve-dur
  "Resolve :play-dur from state: static value returned as-is; fn called for
  phasor-bound duration (e.g. a cljseq.mod/lfo or custom (fn [] beats))."
  [state]
  (let [d (:play-dur state 1/4)]
    (if (fn? d) (d) d)))

;; ---------------------------------------------------------------------------
;; Action dispatch multimethod
;; ---------------------------------------------------------------------------

(defmulti handle-action!
  "Dispatch a key action. Returns updated ivk state map.

  `action-map` — the :key-map value for the pressed key
  `state`      — current @ivk-state map

  New layouts add defmethod entries dispatching on (:action action-map).
  The return value replaces the state atom's contents."
  (fn [action-map _state] (:action action-map)))

(defmethod handle-action! :default [action-map state]
  (binding [*out* *err*]
    (println "[ivk] unknown action:" (:action action-map)))
  state)

;; ---------------------------------------------------------------------------
;; :apply-interval — Samchillian-style diatonic step
;; ---------------------------------------------------------------------------

(defmethod handle-action! :apply-interval [{:keys [n]} state]
  (let [s        (:scale state)
        midi     (:current-pitch state)
        new-midi (if (and s (not (zero? n)))
                   (let [p     (midi->pitch-safe midi)
                         new-p (scale/next-pitch s p n)]
                     (pitch/pitch->midi new-p))
                   (+ midi n))  ; fallback: chromatic steps when no scale
        clamped  (clamp-midi new-midi (:octave-range state))]
    (core/play! {:pitch/midi clamped :dur/beats (resolve-dur state)})
    (assoc state :current-pitch clamped)))

;; ---------------------------------------------------------------------------
;; :play-current — play current pitch without movement (repeat)
;; ---------------------------------------------------------------------------

(defmethod handle-action! :play-current [_ state]
  (let [midi (:current-pitch state)]
    (core/play! {:pitch/midi midi :dur/beats (resolve-dur state)})
    state))

;; ---------------------------------------------------------------------------
;; :play-absolute — direct MIDI note (pitch-class placed in current octave)
;;
;; The layout stores canonical MIDI numbers (C4-based). play-absolute extracts
;; the pitch class (0–11) and places it in the same octave as :current-pitch.
;; This means \a always plays C in whatever octave you are currently in.
;; ---------------------------------------------------------------------------

(defmethod handle-action! :play-absolute [{:keys [midi]} state]
  (let [pc          (mod midi 12)
        curr-octave (quot (:current-pitch state) 12)
        out-midi    (+ pc (* 12 curr-octave))
        clamped     (clamp-midi out-midi (:octave-range state))]
    (core/play! {:pitch/midi clamped :dur/beats (resolve-dur state)})
    (assoc state :current-pitch clamped)))

;; ---------------------------------------------------------------------------
;; :set-octave — shift current-pitch and octave clamp window up or down
;; ---------------------------------------------------------------------------

(defmethod handle-action! :set-octave [{:keys [delta]} state]
  (let [shift (* 12 delta)]
    (-> state
        (update :current-pitch + shift)
        (update :octave-range (fn [[lo hi]] [(+ lo shift) (+ hi shift)])))))

;; ---------------------------------------------------------------------------
;; :cycle-chord-ext — cycle :chord-ext through triad → seventh → ninth
;; ---------------------------------------------------------------------------

(def ^:private ext-cycle {:triad :seventh :seventh :ninth :ninth :triad})

(defmethod handle-action! :cycle-chord-ext [_ state]
  (update state :chord-ext ext-cycle))

;; ---------------------------------------------------------------------------
;; :toggle-voiced-mode — toggle whether chord-function keys emit a root note
;; ---------------------------------------------------------------------------

(defmethod handle-action! :toggle-voiced-mode [_ state]
  (update state :voiced-chord? not))

;; ---------------------------------------------------------------------------
;; :set-scale-root — absolute root pivot (TheoryBoard-style pad)
;;
;; Changes the scale root to a specific pitch class while preserving the
;; interval pattern (mode stays the same). Resets :chord-fn to :I so the
;; tonic of the new key is immediately the home chord.
;;
;; Action map: {:action :set-scale-root :midi <pitch-class-midi>}
;;   :midi — MIDI note number whose pitch class (0–11) is used as the new root.
;;           The octave is preserved from the current scale root, so the root
;;           stays in a sensible register.
;;
;; Example in a layout:
;;   \1  {:action :set-scale-root :midi 60}   ; → C (any octave)
;;   \5  {:action :set-scale-root :midi 67}   ; → G
;; ---------------------------------------------------------------------------

(defmethod handle-action! :set-scale-root [{:keys [midi]} state]
  (if-let [s (:scale state)]
    (let [pc        (mod midi 12)
          root-midi (pitch/pitch->midi (:root s))
          oct       (quot root-midi 12)
          new-midi  (+ pc (* 12 oct))
          new-root  (pitch/midi->pitch new-midi)
          new-scale (scale/make-scale new-root (:intervals s))]
      (assoc state :scale new-scale :chord-fn :I))
    state))

;; ---------------------------------------------------------------------------
;; :transpose-scale — shift scale root by semitones (chromatic pivot)
;;
;; Moves the entire harmonic field up or down by the given number of semitones.
;; Resets :chord-fn to :I (the new tonic).
;;
;; Action map: {:action :transpose-scale :semitones <n>}
;;
;; Example in a layout:
;;   \8  {:action :transpose-scale :semitones -1}  ; ♭ — flatten root
;;   \9  {:action :transpose-scale :semitones  1}  ; ♯ — sharpen root
;; ---------------------------------------------------------------------------

(defmethod handle-action! :transpose-scale [{:keys [semitones]} state]
  (if-let [s (:scale state)]
    (assoc state :scale (scale/transpose-scale s semitones) :chord-fn :I)
    state))

;; ---------------------------------------------------------------------------
;; :cc-delta — virtual mod wheel / continuous controller via key pair
;;
;; Action map: {:action :cc-delta :cc <cc-num> :delta <±n>}
;;   cc    — MIDI CC number (1 = mod wheel, 74 = filter, 11 = expression, etc.)
;;   delta — amount to add per key press (negative = decrement)
;;
;; The CC value is stored in state under :modulation keyword :cc/<N> so it
;; persists across key presses. The slot may also hold a (fn [] value) for
;; phasor-driven automatic modulation — in that case key presses are ignored
;; and the phasor value is sent directly.
;;
;; Examples in a layout:
;;   \[  {:action :cc-delta :cc 1  :delta -10}   ; mod wheel down   → :cc-1 in :modulation
;;   \]  {:action :cc-delta :cc 1  :delta  10}   ; mod wheel up
;;   \-  {:action :cc-delta :cc 74 :delta  -5}   ; filter down      → :cc-74
;;   \=  {:action :cc-delta :cc 74 :delta   5}   ; filter up
;; ---------------------------------------------------------------------------

(defn- resolve-mod-value
  "Resolve a modulation slot value: fn? → call it, number → return as-is."
  [v]
  (if (fn? v) (v) v))

(defmethod handle-action! :cc-delta [{:keys [cc delta]} state]
  (let [k       (keyword (str "cc-" cc))
        slot    (get-in state [:modulation k] 64)
        value   (if (fn? slot)
                  (resolve-mod-value slot)          ; phasor-driven: send current value
                  (-> (+ slot delta)                ; key-driven: increment and clamp
                      (max 0)
                      (min 127)))
        ch      (:channel state 1)
        time-ns (* (System/currentTimeMillis) 1000000)]
    (sidecar/send-cc! time-ns ch cc (int value))
    (if (fn? slot)
      state  ; don't update phasor slot
      (assoc-in state [:modulation k] value))))

;; ---------------------------------------------------------------------------
;; :pitch-bend-delta — virtual pitch bend wheel via key pair
;;
;; Action map: {:action :pitch-bend-delta :delta <±n>}
;;   delta — 14-bit steps to add per press (center = 8192; range 0–16383)
;;           typical: ±512 = ~1 semitone, ±4096 = max
;;
;; Like :cc-delta, the :bend slot in :modulation may hold a phasor fn for
;; automatic vibrato/pitch sweep without key input.
;;
;; Examples:
;;   \,  {:action :pitch-bend-delta :delta  -512}  ; bend down ~1 semi
;;   \.  {:action :pitch-bend-delta :delta   512}  ; bend up ~1 semi
;;   \0  {:action :pitch-bend-delta :delta     0}  ; snap to center
;; ---------------------------------------------------------------------------

(defmethod handle-action! :pitch-bend-delta [{:keys [delta]} state]
  (let [slot  (get-in state [:modulation :bend] 8192)
        value (if (fn? slot)
                (resolve-mod-value slot)
                (-> (+ slot delta) (max 0) (min 16383)))
        ch    (:channel state 1)
        time-ns (* (System/currentTimeMillis) 1000000)]
    (sidecar/send-pitch-bend! time-ns ch (int value))
    (if (fn? slot)
      state
      (assoc-in state [:modulation :bend] value))))

;; ---------------------------------------------------------------------------
;; :set-chord-fn — change active chord function (for :ndlr layout)
;;   :scope :global also updates *harmony-ctx* chord function globally
;; ---------------------------------------------------------------------------

(defmethod handle-action! :set-chord-fn [{:keys [roman scope]} state]
  (when (= scope :global)
    ;; alter-var-root updates the root binding so all live loops pick it up
    (alter-var-root #'loop-ns/*harmony-ctx*
                    (fn [ctx]
                      (when ctx
                        (assoc ctx :chord-fn roman)))))
  ;; When voiced-chord? is set, emit the root of the new chord immediately
  ;; so the chord change is audible without waiting for a melody keypress.
  (when (:voiced-chord? state)
    (let [s (:scale state)
          [scale-deg quality] (or (chord/chord-for-numeral roman) [0 :major])]
      (when s
        (let [c     (chord/chord-at-degree s scale-deg quality)
              midis (chord/chord->midis c)
              root  (first midis)
              out   (clamp-midi root (:octave-range state))]
          (core/play! {:pitch/midi out :dur/beats (resolve-dur state)})))))
  (assoc state :chord-fn roman))

;; ---------------------------------------------------------------------------
;; :play-chord-tone — play the nth chord tone of the current chord function
;; ---------------------------------------------------------------------------

(defmethod handle-action! :play-chord-tone [{:keys [degree octave-offset]} state]
  (let [s        (:scale state)
        chord-fn (:chord-fn state :I)
        [scale-deg quality] (or (chord/chord-for-numeral chord-fn)
                                [0 :major])]
    (if-not s
      (do (binding [*out* *err*]
            (println "[ivk] :play-chord-tone requires an active :scale in ivk-state"))
          state)
      (let [c     (chord/chord-at-degree s scale-deg quality)
            midis (chord/chord->midis c)
            base  (nth midis degree (first midis))
            note  (+ base (* 12 (or octave-offset 0)))
            out   (clamp-midi note (:octave-range state))]
        (core/play! {:pitch/midi out :dur/beats (resolve-dur state)})
        (assoc state :current-pitch out)))))

;; ---------------------------------------------------------------------------
;; Arp mode helpers
;; ---------------------------------------------------------------------------

(defn- enqueue-note!
  "Add note to :arp-notes queue instead of playing immediately."
  [state midi]
  (update state :arp-notes conj midi))

(def ^:private arp-future (atom nil))

(defn start-arp!
  "Start the arp engine. Cycles through :arp-notes at :arp-rate beats.

  Uses a background thread that reads the live :arp-notes queue each step,
  so notes added while the arp is running are picked up immediately.
  Timing is via loop-ns/sleep! so it respects virtual time inside live-loops."
  []
  (when-not @arp-future
    (reset! arp-future
            (future
              (try
                (loop [i 0]
                  (let [st    @ivk-state
                        notes (:arp-notes st)]
                    (when (seq notes)
                      (let [note (nth notes (mod i (count notes)))
                            rate (:arp-rate st 1/4)]
                        (core/play! {:pitch/midi note :dur/beats rate})
                        (loop-ns/sleep! rate)))
                    (when (:arp? @ivk-state)
                      (recur (inc i)))))
                (catch InterruptedException _)
                (catch Exception e
                  (binding [*out* *err*]
                    (println "[ivk] arp error:" e)))))))
  nil)

(defn stop-arp!
  "Stop the arp engine and clear the note queue."
  []
  (swap! ivk-state assoc :arp? false :arp-notes [])
  (when-let [f @arp-future]
    (future-cancel f)
    (reset! arp-future nil)))

;; ---------------------------------------------------------------------------
;; Key event handler
;; ---------------------------------------------------------------------------

(defn- decode-kbd-payload
  "Decode a 3-byte KbdEvent payload into a map."
  [^bytes payload]
  {:keycode    (bit-and (aget payload 0) 0xFF)
   :modifiers  (bit-and (aget payload 1) 0xFF)
   :event-type (bit-and (aget payload 2) 0xFF)})

(defn- keycode->char
  "Convert macOS virtual keycode to a Java char (US QWERTY layout).
  Returns nil for keycodes we don't recognise."
  [keycode]
  ;; macOS US QWERTY keycode table (kVK_* constants from Carbon Events.h)
  (case (int keycode)
    0   \a    1   \s    2   \d    3   \f    4   \h
    5   \g    6   \z    7   \x    8   \c    9   \v
    11  \b    12  \q    13  \w    14  \e    15  \r
    16  \y    17  \t    18  \1    19  \2    20  \3
    21  \4    22  \6    23  \5    24  \=    25  \9
    26  \7    27  \-    28  \8    29  \0    30  \]
    31  \o    32  \u    33  \[    34  \i    35  \p
    37  \l    38  \j    39  \'    40  \k    41  \;
    42  \\    43  \,    44  \/    45  \n    46  \m
    47  \.    49  \space
    nil))

(defn- on-kbd-event
  "Push handler for 0x21 KbdEvent frames from the sidecar."
  [^bytes payload]
  (when (>= (alength payload) 3)
    (let [{:keys [keycode event-type]} (decode-kbd-payload payload)]
      ;; Only handle keydown (0) and keyrepeat (2); ignore keyup (1)
      (when (or (zero? event-type) (== event-type 2))
        (when-let [ch (keycode->char keycode)]
          (let [st     @ivk-state
                layout (get @layout-registry (:layout st))
                action (get-in layout [:key-map ch])]
            (when action
              (swap! ivk-state
                     (fn [s]
                       (if (:arp? s)
                         ;; In arp mode: queue note, don't dispatch action
                         (if-let [midi (:pitch/midi action)]
                           (enqueue-note! s midi)
                           (handle-action! action s))
                         (handle-action! action s)))))))))))

;; ---------------------------------------------------------------------------
;; Public lifecycle API
;; ---------------------------------------------------------------------------

(defn set-pitch!
  "Set :current-pitch to midi. Used by T-1 passthru."
  [midi]
  (swap! ivk-state assoc :current-pitch (int midi)))

(defn current-pitch
  "Return the current :current-pitch MIDI integer."
  []
  (:current-pitch @ivk-state))

(defn- harmony-ctx->scale
  "Extract a Scale record from *harmony-ctx* (Scale record or ImprovisationContext)."
  [ctx]
  (cond
    (nil? ctx)                                nil
    (instance? cljseq.scale.Scale ctx)        ctx
    (map? ctx)                                (:harmony/key ctx)
    :else                                     nil))

(defn start-kbd!
  "Register the 0x21 KbdEvent push handler.

  Initialises :scale from *harmony-ctx* if not already set.
  Does NOT restart the sidecar — assumes it was started with --kbd."
  []
  (when-let [s (harmony-ctx->scale loop-ns/*harmony-ctx*)]
    (swap! ivk-state assoc :scale s))
  (sidecar/register-push-handler! 0x21 on-kbd-event)
  (println "[ivk] keyboard input active, layout:" (:layout @ivk-state)))

(defn stop-kbd!
  "Deregister the keyboard push handler and stop any active arp."
  []
  (stop-arp!)
  (sidecar/register-push-handler! 0x21 nil)
  (println "[ivk] keyboard input stopped"))

;; ---------------------------------------------------------------------------
;; Layout cheatsheet renderer
;; ---------------------------------------------------------------------------

(def ^:private note-names
  ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn- action->label
  "Return a short human-readable label (≤6 chars) for a key action map."
  [action-map]
  (when action-map
    (case (:action action-map)
      :apply-interval     (let [n (:n action-map 0)]
                            (if (pos? n) (str "+" n) (str n)))
      :play-current       "•"
      :play-absolute      (let [m  (:midi action-map 60)
                                pc (mod m 12)
                                oc (- (quot m 12) 1)]
                            (str (nth note-names pc) oc))
      :set-octave         (if (pos? (:delta action-map 0)) "oct↑" "oct↓")
      :cycle-chord-ext    "ext+"
      :set-chord-fn       (name (:roman action-map "?"))
      :play-chord-tone    (let [degree (:degree action-map 0)
                                labels ["root" "3rd" "5th" "7th" "9th" "11th" "13th"]
                                base   (nth labels degree "?")
                                off    (:octave-offset action-map)]
                            (if (and off (pos? off)) (str base "↑") base))
      :cc-delta           (str "cc" (:cc action-map "?")
                               (if (pos? (:delta action-map 0)) "+" "−"))
      :pitch-bend-delta   (let [d (:delta action-map 0)]
                            (if (zero? d) "bend•" (if (pos? d) "bend+" "bend−")))
      :toggle-voiced-mode "vcx"
      :set-scale-root     (str "→" (nth note-names (mod (:midi action-map 0) 12)))
      :transpose-scale    (if (pos? (:semitones action-map 0)) "♯" "♭")
      "?")))

(def ^:private kbd-rows
  "QWERTY rows as character vectors, ordered top to bottom."
  [[\1 \2 \3 \4 \5 \6 \7 \8 \9 \0 \- \=]
   [\q \w \e \r \t \y \u \i \o \p \[ \]]
   [\a \s \d \f \g \h \j \k \l \; \']
   [\z \x \c \v \b \n \m \, \. \/]])

(defn render-layout
  "Render a layout as an ASCII keyboard cheatsheet and print it.

  `layout-or-id` — a layout map (with :key-map) or a registered layout keyword.

  Each QWERTY key is labelled with a short description of its assigned action.
  Unbound keys show · (middle dot). The layout name is printed as a header.

  Examples:
    (render-layout :harmonic)
    (render-layout :interval)
    (render-layout my-custom-layout)"
  [layout-or-id]
  (let [layout (if (keyword? layout-or-id)
                 (or (get @layout-registry layout-or-id)
                     (throw (ex-info "ivk: layout not registered"
                                     {:id layout-or-id})))
                 layout-or-id)
        km      (:key-map layout {})
        cell-w  6   ; fixed cell width (chars)
        pad     (fn [s] (let [n (- cell-w (count s))]
                          (str s (apply str (repeat n " ")))))
        render-row (fn [row]
                     (let [keys-line   (apply str (map #(pad (str %)) row))
                           labels-line (apply str (map #(pad (or (action->label (get km %)) "·")) row))]
                       (str keys-line "\n" labels-line)))
        sep     (apply str (repeat (* cell-w (count (first kbd-rows))) "─"))
        title   (str (:layout/name layout (:layout/id layout "Layout")))]
    (println title)
    (println sep)
    (doseq [row kbd-rows]
      (println (render-row row))
      (println))
    (println sep))
  nil)

;; ---------------------------------------------------------------------------
;; Built-in layouts
;; ---------------------------------------------------------------------------

;; :interval — Samchillian/Misha-inspired diatonic interval steps
;; Home row: a=-4 s=-3 d=-2 f=-1 [space=repeat] j=+1 k=+2 l=+3 ;=+4
;; Lower:    z=-8 x=-5              m=+5  ,=+8
;; Octave:   [=down  ]=up
(register-layout! :interval
  {:layout/id   :interval
   :layout/name "Interval (Samchillian-style diatonic steps)"
   :key-map
   {\a     {:action :apply-interval :n -4}
    \s     {:action :apply-interval :n -3}
    \d     {:action :apply-interval :n -2}
    \f     {:action :apply-interval :n -1}
    \space {:action :play-current}
    \j     {:action :apply-interval :n  1}
    \k     {:action :apply-interval :n  2}
    \l     {:action :apply-interval :n  3}
    \;     {:action :apply-interval :n  4}
    \z     {:action :apply-interval :n -8}
    \x     {:action :apply-interval :n -5}
    \m     {:action :apply-interval :n  5}
    \,     {:action :apply-interval :n  8}
    \[     {:action :set-octave :delta -1}
    \]     {:action :set-octave :delta  1}}})

;; :chromatic — Bitwig-style piano layout
;; Upper row (black keys):  q=C# w=D# e=. r=F# t=G# y=A# u=. (one octave)
;; Home row  (white keys):  a=C  s=D  d=E  f=F  g=G  h=A  j=B  k=C'
;; z=octave down, x=octave up
(register-layout! :chromatic
  {:layout/id   :chromatic
   :layout/name "Chromatic (Bitwig-style piano)"
   :key-map
   ;; Upper row — black keys (relative to current octave base)
   {\q  {:action :play-absolute :midi 61}   ; C#4
    \w  {:action :play-absolute :midi 63}   ; D#4
    \r  {:action :play-absolute :midi 66}   ; F#4
    \t  {:action :play-absolute :midi 68}   ; G#4
    \y  {:action :play-absolute :midi 70}   ; A#4
    ;; Home row — white keys
    \a  {:action :play-absolute :midi 60}   ; C4
    \s  {:action :play-absolute :midi 62}   ; D4
    \d  {:action :play-absolute :midi 64}   ; E4
    \f  {:action :play-absolute :midi 65}   ; F4
    \g  {:action :play-absolute :midi 67}   ; G4
    \h  {:action :play-absolute :midi 69}   ; A4
    \j  {:action :play-absolute :midi 71}   ; B4
    \k  {:action :play-absolute :midi 72}   ; C5
    ;; Octave shift
    \z  {:action :set-octave :delta -1}
    \x  {:action :set-octave :delta  1}}})

;; :scale — absolute diatonic scale degrees (always in key)
;; a=1 s=2 d=3 f=4 g=5 h=6 j=7 k=8(octave)
;; z=octave down, x=octave up
(register-layout! :scale
  {:layout/id   :scale
   :layout/name "Scale (absolute diatonic degrees)"
   :key-map
   {\a  {:action :apply-interval :n  0}   ; degree 0 (root)
    \s  {:action :apply-interval :n  1}   ; degree 1
    \d  {:action :apply-interval :n  2}
    \f  {:action :apply-interval :n  3}
    \g  {:action :apply-interval :n  4}
    \h  {:action :apply-interval :n  5}
    \j  {:action :apply-interval :n  6}
    \k  {:action :apply-interval :n  7}   ; octave above root
    \z  {:action :set-octave :delta -1}
    \x  {:action :set-octave :delta  1}}})

;; :harmonic — TheoryBoard-inspired compact layout
;;
;; The home row is split: left hand (asdf) selects the harmonic context
;; (primary diatonic chords), right hand (hjkl;) plays melody over it.
;; Both hands stay on the home row for 80% of playing — ergonomic for a
;; touch typist.
;;
;; Home row — left: primary chords (I IV V VI = the axis of awesome)
;;   a=I   s=IV   d=V   f=VI(vi)
;;   g     = pivot: root of current chord (seam key between the two hands)
;; Home row — right: chord tones of the active chord
;;   h=root  j=3rd  k=5th  l=7th  ;=9th
;;
;; Top row — left: secondary diatonic chords (reach deliberately)
;;   q=II(ii)  w=III(iii)  e=VII(vii°)
;; Top row — right: same chord tones, one octave up
;;   y=root↑  u=3rd↑  i=5th↑  o=7th↑  p=9th↑
;;
;; Bottom row:
;;   z=octave-down  x=octave-up  c=toggle-voiced-mode
;;
;; Number row — TheoryBoard pivot pads (absolute scale root, same mode):
;;   1=C  2=D  3=E  4=F  5=G  6=A  7=B
;;   8=♭ (flatten root -1 semi)   9=♯ (sharpen root +1 semi)
(register-layout! :harmonic
  {:layout/id   :harmonic
   :layout/name "Harmonic (TheoryBoard-style left=chord right=melody)"
   :key-map
   ;; Home row left — primary chord functions
   {\a  {:action :set-chord-fn :roman :I}
    \s  {:action :set-chord-fn :roman :IV}
    \d  {:action :set-chord-fn :roman :V}
    \f  {:action :set-chord-fn :roman :VI}
    ;; g = seam/pivot: root of current chord without moving :chord-fn
    \g  {:action :play-chord-tone :degree 0}
    ;; Home row right — chord tones (melody)
    \h  {:action :play-chord-tone :degree 0}   ; root
    \j  {:action :play-chord-tone :degree 1}   ; 3rd
    \k  {:action :play-chord-tone :degree 2}   ; 5th
    \l  {:action :play-chord-tone :degree 3}   ; 7th
    \;  {:action :play-chord-tone :degree 4}   ; 9th
    ;; Top row left — secondary diatonic chords (deliberate reach)
    \q  {:action :set-chord-fn :roman :II}
    \w  {:action :set-chord-fn :roman :III}
    \e  {:action :set-chord-fn :roman :VII}
    ;; Top row right — upper octave melody
    \y  {:action :play-chord-tone :degree 0 :octave-offset 1}
    \u  {:action :play-chord-tone :degree 1 :octave-offset 1}
    \i  {:action :play-chord-tone :degree 2 :octave-offset 1}
    \o  {:action :play-chord-tone :degree 3 :octave-offset 1}
    \p  {:action :play-chord-tone :degree 4 :octave-offset 1}
    ;; Bottom row — octave and mode controls
    \z  {:action :set-octave :delta -1}
    \x  {:action :set-octave :delta  1}
    \c  {:action :toggle-voiced-mode}
    ;; Number row — pivot pads (absolute scale root, mode preserved)
    \1  {:action :set-scale-root :midi 60}   ; → C
    \2  {:action :set-scale-root :midi 62}   ; → D
    \3  {:action :set-scale-root :midi 64}   ; → E
    \4  {:action :set-scale-root :midi 65}   ; → F
    \5  {:action :set-scale-root :midi 67}   ; → G
    \6  {:action :set-scale-root :midi 69}   ; → A
    \7  {:action :set-scale-root :midi 71}   ; → B
    \8  {:action :transpose-scale :semitones -1}   ; ♭
    \9  {:action :transpose-scale :semitones  1}}}) ; ♯

;; :ndlr — NDLR-inspired chord surface
;;
;; Left hand — chord function (updates *harmony-ctx* globally):
;;   a=:I  s=:ii  d=:IV  f=:V  g=:vi
;;   q=:iii  w=:IVmaj7  e=:V7  r=:VIIø7
;;
;; Right hand — chord tones (degree 0..6 = root..13th):
;;   j=root  k=3rd  l=5th  ;=7th  h=9th  u=11th  i=13th
;;
;; Extensions / octave:
;;   z=octave-down  x=octave-up  [=cycle-chord-ext  space=hold-root
(register-layout! :ndlr
  {:layout/id   :ndlr
   :layout/name "NDLR (chord surface — left=functions, right=tones)"
   :key-map
   ;; Left hand — chord functions (global harmony conductor)
   {\a  {:action :set-chord-fn :roman :I     :scope :global}
    \s  {:action :set-chord-fn :roman :ii    :scope :global}
    \d  {:action :set-chord-fn :roman :IV    :scope :global}
    \f  {:action :set-chord-fn :roman :V     :scope :global}
    \g  {:action :set-chord-fn :roman :vi    :scope :global}
    ;; Upper row — 7th chord variants
    \q  {:action :set-chord-fn :roman :iii   :scope :global}
    \w  {:action :set-chord-fn :roman :IVmaj7 :scope :global}
    \e  {:action :set-chord-fn :roman :V7    :scope :global}
    \r  {:action :set-chord-fn :roman :VIIø7 :scope :global}
    ;; Right hand — chord tones
    \j  {:action :play-chord-tone :degree 0}  ; root
    \k  {:action :play-chord-tone :degree 1}  ; 3rd
    \l  {:action :play-chord-tone :degree 2}  ; 5th
    \;  {:action :play-chord-tone :degree 3}  ; 7th
    \h  {:action :play-chord-tone :degree 4}  ; 9th
    \u  {:action :play-chord-tone :degree 5}  ; 11th
    \i  {:action :play-chord-tone :degree 6}  ; 13th
    ;; Octave / extension
    \z  {:action :set-octave :delta -1}
    \x  {:action :set-octave :delta  1}
    \[  {:action :cycle-chord-ext}
    \space {:action :play-chord-tone :degree 0}}}) ; hold root
