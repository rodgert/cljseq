; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ctrl
  "cljseq control tree.

  The control tree is a persistent map within the shared system-state atom.
  Nodes hold a typed value and optional physical bindings (MIDI CC, OSC, etc.).

  ## set! vs send! — the core distinction

  There are two write functions and the choice is intentional:

    ctrl/set!   — YOU ARE RECORDING a value. Use when the value arrives from
                  outside (MIDI input, OSC, peer sync, UI, config) or when
                  updating internal state that has no hardware target.
                  Never dispatches to physical outputs. Safe to call freely.

    ctrl/send!  — YOU ARE DRIVING hardware. Use when Clojure code is the
                  source of truth and you want the value to reach the bound
                  device (MIDI CC, NRPN). Dispatches to all physical bindings.

  The distinction prevents feedback loops: if MIDI arrives on CC 74 and you
  record it with set!, it updates the tree without echoing back to hardware.
  If your LFO calls send!, it drives CC 74 out. Same path, opposite direction.

  A third variant exists for timing-critical dispatch:

    ctrl/send-at! time-ns path value — like send! but schedules the outgoing
                  hardware messages at an explicit nanosecond timestamp, for
                  alignment with note-on events already in the sidecar queue.

  ## Node lifecycle
    (ctrl/defnode! [:filter/cutoff] :type :float :meta {:range [0.0 1.0]} :value 0.5)
    (ctrl/bind!    [:filter/cutoff] {:type :midi-cc :channel 1 :cc-num 74} :priority 20)
    (ctrl/set!     [:filter/cutoff] 0.3)    ; record a value — no hardware dispatch
    (ctrl/send!    [:filter/cutoff] 0.3)    ; drive hardware — atom + MIDI CC out
    (ctrl/get      [:filter/cutoff])        ; => 0.3

  ## Safety net
    (ctrl/checkpoint! :known-good)          ; snapshot tree
    ;; ... live editing ...
    (ctrl/panic!)                           ; revert to :known-good immediately

  ## Undo
    (ctrl/undo!)                            ; revert last structural change
    (ctrl/undo! :now)                       ; explicit immediate (same in Phase 1)

  ## Node types (Q8)
    :int      integer; optional :range [lo hi]
    :float    floating-point; optional :range [lo hi]
    :bool     boolean
    :keyword  arbitrary keyword
    :enum     keyword from declared set; :values [k1 k2 ...]
    :data     opaque Clojure value (default)

  ## Binding priorities (Q9) — lower number = higher priority
    0   Clojure code (ctrl/send! from code always wins)
    10  Ableton Link
    20  MIDI
    30  OSC

  Key design decisions: Q4, Q8, Q9, Q10, Q47, Q48."
  (:refer-clojure :exclude [get])
  (:require [cljseq.sidecar :as sidecar]))

;; ---------------------------------------------------------------------------
;; System reference — registered by cljseq.core/start! (avoids circular dep)
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

;; ---------------------------------------------------------------------------
;; Diagnostics
;;
;; ^:dynamic so tests can capture or suppress the warning without forking stderr.
;; ---------------------------------------------------------------------------

(def ^:dynamic *dispatch-warn-fn*
  "Called when a physical binding (MIDI CC, NRPN) cannot be dispatched because
  the sidecar is not connected. Default: print to *err*.
  Override in tests: (binding [ctrl/*dispatch-warn-fn* (fn [& _])] ...)"
  (fn [path binding-type]
    (binding [*out* *err*]
      (println (str "[ctrl] send! — " (pr-str path)
                    " has a " binding-type " binding but sidecar is not connected."
                    " Start the sidecar with (sidecar/start-sidecar!) first.")))))

;; ---------------------------------------------------------------------------
;; Watcher registry
;;
;; {path {watch-key fn}} — keyed by watch-key so callers can remove them.
;; Watchers fire synchronously (in the writer's thread) after every send! or
;; set! that touches their path, AFTER MIDI dispatch completes.
;; Exceptions in watchers are printed and swallowed so they can't kill the
;; caller (e.g. a mod-route! runner thread).
;; ---------------------------------------------------------------------------

(defonce ^:private watchers (atom {}))

;; Global watchers — fire on every set!/send! regardless of path.
;; Stored separately from per-path watchers.
(defonce ^:private global-watchers (atom {}))

(defn -register-system!
  "Wire ctrl to the shared system-state atom. Called by cljseq.core/start!."
  [ref]
  (reset! system-ref ref))

;; ---------------------------------------------------------------------------
;; CtrlNode record (Q8)
;; ---------------------------------------------------------------------------

(defrecord CtrlNode
  [value      ; current value (any Clojure type)
   type       ; :int :float :bool :keyword :enum :data
   node-meta  ; metadata map — :range [lo hi], :values [...], etc.
   bindings]) ; vector of binding maps, sorted by :priority ascending

(defn- make-node
  "Create a new CtrlNode with type :data and no bindings."
  ([]        (->CtrlNode nil  :data {} []))
  ([v]       (->CtrlNode v    :data {} []))
  ([v t]     (->CtrlNode v    t     {} []))
  ([v t m]   (->CtrlNode v    t     m  [])))

;; ---------------------------------------------------------------------------
;; Internal path helpers
;; ---------------------------------------------------------------------------

(defn- tree-path
  "Prepend :tree to a user path vector for assoc-in navigation."
  [path]
  (into [:tree] path))

(defn- get-node [state path]
  (get-in state (tree-path path)))

(defn- put-node [state path node]
  (assoc-in state (tree-path path) node))

;; ---------------------------------------------------------------------------
;; Undo stack helpers (Q47)
;; ---------------------------------------------------------------------------

(def ^:private default-undo-depth 50)

(defn- push-undo
  "Return updated state with the current :tree and :serial pushed onto the
  undo stack, bounded at the configured depth (falls back to default-undo-depth)."
  [state]
  (let [depth (or (clojure.core/get-in state [:config :ctrl/undo-stack-depth])
                  default-undo-depth)]
    (update state :undo-stack
            (fn [stack]
              (let [entry     {:tree   (:tree state)
                               :serial (:serial state)}
                    new-stack (conj stack entry)]
                (if (> (count new-stack) depth)
                  (subvec new-stack 1)
                  new-stack))))))

(defn- fire-watchers!
  "Fire all watchers registered on `path` with `value`, then all global watchers.
  Called internally after every send-at! and set!."
  [path value]
  (doseq [[_ f] (clojure.core/get @watchers path)]
    (try
      (f path value)
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[ctrl] watcher error on " (pr-str path)
                        ": " (.getMessage e)))))))
  (doseq [[_ f] @global-watchers]
    (try (f path value)
         (catch Exception e
           (binding [*out* *err*]
             (println (str "[ctrl] global watcher error: " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Core read/write API
;; ---------------------------------------------------------------------------

(defn get
  "Read the current value at `path` in the control tree.
  Returns nil if the path does not exist or has not been set.

  Example:
    (ctrl/get [:filter/cutoff])    ; => 0.5
    (ctrl/get [:loops :bass :vel]) ; => 64"
  [path]
  (some-> (get-node @@system-ref path) :value))

(defn node-info
  "Return the full CtrlNode map at `path`, or nil if absent.
  Useful for inspecting type, meta, and bindings."
  [path]
  (get-node @@system-ref path))

(defn set!
  "Record `value` at `path` in the control tree. Never dispatches to hardware.

  Use set! when the value originates outside Clojure — incoming MIDI CC,
  incoming OSC, peer sync, UI writes, config updates. Also use it for any
  internal state that has no hardware binding. Because set! never echoes to
  hardware, it is safe to call from MIDI/OSC input handlers without creating
  feedback loops.

  Not an undo target by default; pass :undoable true to push to the undo stack.
  Creates a :data node if the path does not exist; preserves existing type and bindings.

  Use send! when Clojure is the source and you want the value to reach bound
  hardware. Use send-at! when you need the hardware message at a specific time.

  Examples:
    (ctrl/set! [:filter/cutoff] 0.7)           ; record value, no hardware dispatch
    (ctrl/set! [:chord] :C-major :undoable true)
    ;; In a MIDI input handler — safe: will not echo back to hardware:
    (ctrl/set! [:cc/74] incoming-value)"
  [path value & {:keys [undoable]}]
  (swap! @system-ref
         (fn [s]
           (let [node (or (get-node s path) (make-node))
                 s'   (if undoable (push-undo s) s)]
             (put-node s' path (assoc node :value value)))))
  (fire-watchers! path value)
  nil)

(defn send-at!
  "Drive `value` to `path` with explicit nanosecond timing.

  Identical to send! but schedules the outgoing hardware messages at `time-ns`
  (nanoseconds since epoch) rather than at the current wall-clock time. Use
  this to align a CC or NRPN with a note-on that has already been placed in
  the sidecar queue — for example, per-step mod routing in core/play!.

  Like send!, this is for Clojure-originated values going to hardware. Use
  set! for values arriving from hardware or from external sources.

  Binding types dispatched and value scaling are identical to send!:
    :midi-cc   — single CC message; value scaled to [0,127] via binding :range.
    :midi-nrpn — NRPN parameter (CC 99/98/6/38); supports 7-bit, 14-bit, :raw.

  Example:
    ;; Schedule a filter sweep to arrive at the same time as the note-on:
    (ctrl/send-at! on-ns [:filter/cutoff] 0.7)"
  [time-ns path value]
  (swap! @system-ref
         (fn [s]
           (let [node (or (get-node s path) (make-node))]
             (put-node s path (assoc node :value value)))))
  (let [node (get-node @@system-ref path)]
    (doseq [binding (:bindings node)]
      (case (:type binding)
        :midi-cc
        (let [ch      (int (or (:channel binding) 1))
              cc-num  (int (:cc-num binding))
              [lo hi] (or (:range binding) [0 127])
              lo      (double lo)
              hi      (double hi)
              raw     (double value)
              pct     (/ (- raw lo) (- hi lo))
              scaled  (long (Math/round (* pct 127.0)))]
          (if (sidecar/connected?)
            (sidecar/send-cc! time-ns ch cc-num (max 0 (min 127 scaled)))
            (*dispatch-warn-fn* path :midi-cc)))

        :midi-nrpn
        (let [ch        (int (or (:channel binding) 1))
              nrpn      (int (:nrpn binding))
              bits      (int (or (:bits binding) 14))
              max-val   (if (= 14 bits) 16383 127)
              ;; :raw true → bypass range scaling; use value directly
              clamped   (if (:raw binding)
                          (max 0 (min max-val (long value)))
                          (let [[lo hi] (or (:range binding) [0 max-val])
                                pct     (/ (- (double value) (double lo))
                                           (- (double hi)   (double lo)))]
                            (max 0 (min max-val (long (Math/round (* pct (double max-val))))))))
              param-msb (bit-and (bit-shift-right nrpn 7) 0x7F)
              param-lsb (bit-and nrpn 0x7F)
              ;; Always use full 14-bit NRPN wire encoding: CC6=MSB, CC38=LSB.
              ;; Wire value = CC6*128 + CC38 — this is what the Hydrasynth (and the
              ;; MIDI NRPN standard) expects.  :bits only controls the user-facing
              ;; value range (7 → [0,127], 14 → [0,16383]); it does NOT switch to a
              ;; "CC6-only" encoding.  For a 7-bit param with value 5: CC6=0, CC38=5
              ;; (wire value = 0*128+5 = 5 ✓), not CC6=5 (wire value = 5*128 = 640 ✗).
              data-msb  (bit-and (bit-shift-right clamped 7) 0x7F)
              data-lsb  (bit-and clamped 0x7F)]
          (if (sidecar/connected?)
            ;; NRPN sequence must arrive in order: CC99 → CC98 → CC6 → CC38.
            ;; The scheduler min-heap does not preserve insertion order for equal
            ;; timestamps, so we add 1ns offsets to guarantee ordering.
            (do
              (sidecar/send-cc! time-ns       ch 99 param-msb)
              (sidecar/send-cc! (+ time-ns 1) ch 98 param-lsb)
              (sidecar/send-cc! (+ time-ns 2) ch  6 data-msb)
              (sidecar/send-cc! (+ time-ns 3) ch 38 data-lsb))
            (*dispatch-warn-fn* path :midi-nrpn)))

        ;; Other binding types: log and skip
        (binding [*out* *err*]
          (println (str "[ctrl] send! binding type " (:type binding)
                        " at " (pr-str path) " not yet dispatched"))))))
  (fire-watchers! path value)
  nil)

(defn send!
  "Drive `value` to `path`: update the control tree and dispatch to all physical
  bindings at the current wall-clock time.

  Use send! when Clojure is the source of the value and you want it to reach
  bound hardware — LFOs, trajectories, live REPL tweaks, mod routing. Do NOT
  use send! in MIDI/OSC input handlers; use set! there to avoid feedback loops.

  Use send-at! when you need the hardware message aligned with an already-
  scheduled note-on (e.g. per-step mod routing in core/play!).

  Binding types dispatched:

    :midi-cc   — single CC message; value scaled to [0,127] via binding :range.
                 Example: (ctrl/send! [:filter/cutoff] 0.7) ; CC 74 = 89

    :midi-nrpn — NRPN parameter; emits CC 99/98 (parameter select) then CC 6/38
                 (data entry). Supports 7-bit and 14-bit resolution controlled
                 by :bits in the binding (default 14). Value scaled from binding
                 :range to [0,127] or [0,16383]. Add :raw true to the binding to
                 skip scaling (useful for compound-addressed parameters).
                 Example: (ctrl/send! [:portamento] 1000) ; NRPN 1, 14-bit
                 Example: (ctrl/send! [:ribbon/mode] 1)   ; :raw true, direct value

    others     — logged; dispatch deferred to future sprint."
  [path value]
  (send-at! (* (System/currentTimeMillis) 1000000) path value))

(defn send-raw-nrpn!
  "Fire a raw NRPN directly to the sidecar, bypassing the control tree.

  `channel`  — MIDI channel (1–16)
  `nrpn-num` — NRPN parameter number (0–16383); split into CC99/CC98
  `value`    — data value; 14-bit [0–16383] by default (see :bits option)

  Options:
    :bits — 7 for 7-bit value range [0,127]; 14 for 14-bit range [0,16383] (default).
            Both always send CC99/CC98/CC6/CC38 using standard 14-bit wire encoding
            (wire value = CC6*128 + CC38).  :bits only controls value clamping.

  Useful for compound-addressed Hydrasynth parameters (ribbon sub-params,
  ARP sub-params) where CC6 encodes a sub-parameter rather than data MSB.
  In that case pack the value as (bit-or (bit-shift-left sub-param 7) data).

  Does nothing if the sidecar is not connected.

  Examples:
    ;; Ribbon mode sub-param 0=off, NRPN group 0x41 (65):
    (ctrl/send-raw-nrpn! 1 (bit-or (bit-shift-left 65 7) 0) 1)

    ;; Portamento NRPN 1, 14-bit value 500:
    (ctrl/send-raw-nrpn! 1 1 500)"
  ([channel nrpn-num value]
   (send-raw-nrpn! channel nrpn-num value 14))
  ([channel nrpn-num value bits]
   (when (sidecar/connected?)
     (let [now-ns    (* (System/currentTimeMillis) 1000000)
           ch        (int channel)
           nrpn      (int nrpn-num)
           bits      (int bits)
           max-val   (if (= 14 bits) 16383 127)
           clamped   (max 0 (min max-val (long value)))
           param-msb (bit-and (bit-shift-right nrpn 7) 0x7F)
           param-lsb (bit-and nrpn 0x7F)
           ;; Always use full 14-bit encoding — see send-at! comment.
           data-msb  (bit-and (bit-shift-right clamped 7) 0x7F)
           data-lsb  (bit-and clamped 0x7F)]
       (sidecar/send-cc! now-ns       ch 99 param-msb)
       (sidecar/send-cc! (+ now-ns 1) ch 98 param-lsb)
       (sidecar/send-cc! (+ now-ns 2) ch  6 data-msb)
       (sidecar/send-cc! (+ now-ns 3) ch 38 data-lsb)))))

;; ---------------------------------------------------------------------------
;; Node declaration (Q8)
;; ---------------------------------------------------------------------------

(defn defnode!
  "Declare a typed control tree node at `path`.

  Updates the node's type and metadata without changing its current value
  (or sets an initial value if :value is provided and the node is new).
  If the node already exists its bindings are preserved.

  Options:
    :type   — #{:int :float :bool :keyword :enum :data} (default :data)
    :meta   — metadata map; for :int/:float use {:range [lo hi]};
              for :enum use {:values [:a :b :c]}
    :value  — initial value (ignored if the node already exists and has a value)

  This is a structural change; it increments the tree serial number.

  Example:
    (ctrl/defnode! [:filter/cutoff]
                   :type :float :meta {:range [0.0 1.0]} :value 0.5)"
  [path & {:keys [type node-meta value] :or {type :data node-meta {}}}]
  (swap! @system-ref
         (fn [s]
           (let [existing  (get-node s path)
                 cur-value (if (and (some? value) (nil? (some-> existing :value)))
                             value
                             (some-> existing :value))
                 node      (->CtrlNode cur-value type node-meta
                                       (or (some-> existing :bindings) []))]
             (-> (put-node s path node)
                 (update :serial inc)))))
  nil)

;; ---------------------------------------------------------------------------
;; Binding management (Q9)
;; ---------------------------------------------------------------------------

(defn bind!
  "Register a physical binding on the control tree node at `path`.

  Raises if the node already has a binding with the same priority (Q9 fail-fast).
  Bindings are stored sorted by priority (ascending; lower = higher priority).

  The `source` map must include at minimum :type. Recognized binding types:
    :midi-cc — requires :channel (1–16) and :cc-num (0–127);
               optional :range [lo hi] (default [0 127]) for value scaling.

  Options:
    :priority — integer (default 20 for MIDI); lower = higher priority.

  This is a structural change; increments the tree serial number.

  Examples:
    (ctrl/bind! [:filter/cutoff] {:type :midi-cc :channel 1 :cc-num 74})
    (ctrl/bind! [:filter/cutoff] {:type :midi-cc :channel 1 :cc-num 74}
                :priority 20)
    (ctrl/bind! [:global/bpm] link-source :priority 10)"
  [path source & {:keys [priority] :or {priority 20}}]
  (let [binding (assoc source :priority priority)]
    (swap! @system-ref
           (fn [s]
             (let [node     (or (get-node s path) (make-node))
                   existing (:bindings node)
                   conflict (first (filter #(= priority (:priority %)) existing))]
               (when conflict
                 (throw (ex-info
                         (str "ctrl/bind!: path " (pr-str path)
                              " already has a binding at priority " priority
                              "; use a different priority or call unbind! first")
                         {:path path :existing-binding conflict :new-binding binding})))
               (let [new-bindings (vec (sort-by :priority (conj existing binding)))]
                 (-> (put-node s path (assoc node :bindings new-bindings))
                     (update :serial inc)))))))
  nil)

(defn unbind!
  "Remove bindings from the node at `path`.

  If `priority` is a number, removes the binding at that priority.
  If `priority` is :all, removes all bindings.

  This is a structural change; increments the tree serial number.

  Example:
    (ctrl/unbind! [:filter/cutoff] 20)   ; remove MIDI binding
    (ctrl/unbind! [:filter/cutoff] :all) ; remove all bindings"
  [path priority]
  (swap! @system-ref
         (fn [s]
           (let [node (or (get-node s path) (make-node))]
             (-> (put-node s path
                           (assoc node :bindings
                                  (if (= :all priority)
                                    []
                                    (vec (remove #(= priority (:priority %))
                                                 (:bindings node))))))
                 (update :serial inc)))))
  nil)

(defn- walk-nodes
  "Walk nested tree map `m`, returning [[path node] ...] for every CtrlNode leaf."
  [m prefix]
  (reduce-kv
    (fn [acc k v]
      (let [p (conj prefix k)]
        (cond
          (instance? CtrlNode v) (conj acc [p v])
          (map? v)               (into acc (walk-nodes v p))
          :else                  acc)))
    []
    m))

(defn bindings-by-type
  "Return [[path binding] ...] for every binding of `binding-type` in the ctrl tree.

  Walks all nodes in the tree regardless of depth. Returns an empty sequence
  if the system is not started or no bindings of that type exist.

  Used by cljseq.midi-in to locate :midi-device-input bindings on each message.

  Example:
    (ctrl/bindings-by-type :midi-device-input)
    ;; => [[ [:filter/cutoff] {:type :midi-device-input :device :arturia/keystep ...} ] ...]"
  [binding-type]
  (when-let [s @system-ref]
    (let [tree (:tree @s)]
      (for [[path node] (walk-nodes tree [])
            binding     (:bindings node)
            :when       (= binding-type (:type binding))]
        [path binding]))))

(defn all-nodes
  "Return a seq of {:path [...] :value v :type kw :node-meta m} maps for every ctrl node.

  Walks the entire tree; useful for serialising the tree to external consumers
  (e.g. the control-plane HTTP server).  Returns an empty seq if the system is
  not started.

  Example:
    (ctrl/all-nodes)
    ;; => ({:path [:filter/cutoff] :value 0.5 :type :float :node-meta {:range [0.0 1.0]}} ...)"
  []
  (if-let [s @system-ref]
    (map (fn [[path node]]
           {:path      path
            :value     (:value node)
            :type      (:type node)
            :node-meta (or (:node-meta node) {})})
         (walk-nodes (:tree @s) []))
    []))

;; ---------------------------------------------------------------------------
;; Watcher API
;; ---------------------------------------------------------------------------

(defn watch!
  "Register a callback fired synchronously after every send! or set! that
  writes to `path`.

  `watch-key` — any value; identifies this watcher for later removal.
                Use a namespaced keyword to avoid collisions, e.g. ::my-ns/key.
  `f`         — (fn [path value]) called after MIDI dispatch completes.

  Re-registering the same path+watch-key replaces the previous callback.
  Exceptions thrown by `f` are printed to stderr and swallowed.

  Example:
    (ctrl/watch! [:arc/tension] ::my-listener
                 (fn [path v] (println path \"changed to\" v)))"
  [path watch-key f]
  (swap! watchers assoc-in [path watch-key] f)
  nil)

(defn unwatch!
  "Remove the watcher identified by `watch-key` from `path`.
  No-op if the key is not registered."
  [path watch-key]
  (swap! watchers update path dissoc watch-key)
  nil)

(defn unwatch-all!
  "Remove all watchers registered on `path`."
  [path]
  (swap! watchers dissoc path)
  nil)

(defn watch-global!
  "Register `f` to be called with [path value] on every ctrl-tree change,
  regardless of which path was written.

  `watch-key` identifies this watcher for later removal via `unwatch-global!`.
  Use a namespaced keyword to avoid collisions, e.g. ::server/ws-broadcast.
  Re-registering the same key replaces the previous callback.
  Exceptions thrown by `f` are printed to stderr and swallowed.

  Example:
    (ctrl/watch-global! ::ws/broadcast (fn [path v] (broadcast! path v)))"
  [watch-key f]
  (swap! global-watchers assoc watch-key f)
  nil)

(defn unwatch-global!
  "Remove the global watcher identified by `watch-key`.
  No-op if the key is not registered."
  [watch-key]
  (swap! global-watchers dissoc watch-key)
  nil)

;; ---------------------------------------------------------------------------
;; Checkpoints and panic (Q47)
;; ---------------------------------------------------------------------------

(defn checkpoint!
  "Save a named snapshot of the current control tree.

  The snapshot is stored in :checkpoints under `name`. Subsequent `panic!`
  calls with this name (or with no name, if this is the most recent checkpoint)
  will restore the tree to this state.

  Checkpoints are not themselves undo targets — panic! pushes to the undo stack
  before reverting, so `undo!` after `panic!` undoes the revert.

  Example:
    (ctrl/checkpoint! :known-good)
    (ctrl/checkpoint! :pre-breakdown)"
  [name]
  (swap! @system-ref
         (fn [s]
           (-> s
               (assoc-in [:checkpoints name]
                         {:tree     (:tree s)
                          :serial   (:serial s)
                          :saved-at (System/currentTimeMillis)})
               (assoc :last-checkpoint name))))
  nil)

(defn panic!
  "Revert the control tree to a saved checkpoint immediately.

  (panic!)          — revert to the most recently defined checkpoint
  (panic! :name)    — revert to the named checkpoint

  Before reverting, the current tree is pushed onto the undo stack so that
  `undo!` after `panic!` can restore the pre-panic state.

  Example:
    (ctrl/checkpoint! :known-good)
    ;; ... things go wrong ...
    (ctrl/panic!)           ; restore :known-good"
  ([]
   (let [name (:last-checkpoint @@system-ref)]
     (if name
       (panic! name)
       (binding [*out* *err*]
         (println "[ctrl] panic!: no checkpoint saved; call (ctrl/checkpoint! name) first")))))
  ([name]
   (let [cp (get-in @@system-ref [:checkpoints name])]
     (if-not cp
       (binding [*out* *err*]
         (println (str "[ctrl] panic!: no checkpoint named " (pr-str name))))
       (do
         (swap! @system-ref
                (fn [s]
                  (-> (push-undo s)
                      (assoc :tree   (:tree cp)
                             :serial (:serial cp)))))
         (binding [*out* *err*]
           (println (str "[ctrl] reverted to checkpoint " (pr-str name)))))))))

;; ---------------------------------------------------------------------------
;; Undo (Q47)
;; ---------------------------------------------------------------------------

(defn undo!
  "Revert the last structural change pushed to the undo stack.

  Phase 1: always immediate. Beat-aware boundary semantics (Q47 §undo-timing)
  are deferred — a future sprint will queue the revert at the next loop boundary
  when called without :now.

  (undo!)      — revert last structural change
  (undo! :now) — explicit immediate revert (same as above in Phase 1)

  Returns true if a change was reverted, false if the undo stack is empty."
  ([] (undo! :now))
  ([_timing]
   (let [empty? (empty? (:undo-stack @@system-ref))]
     (if empty?
       (do (binding [*out* *err*]
             (println "[ctrl] undo!: nothing to undo"))
           false)
       (do (swap! @system-ref
                  (fn [s]
                    (let [entry (peek (:undo-stack s))]
                      (-> s
                          (assoc :tree   (:tree entry)
                                 :serial (:serial entry))
                          (update :undo-stack pop)))))
           true)))))
