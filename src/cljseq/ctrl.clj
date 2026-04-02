; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ctrl
  "cljseq control tree.

  The control tree is a persistent map within the shared system-state atom.
  Nodes hold a typed value and optional physical bindings (MIDI CC, OSC, etc.).

  ## Node lifecycle
    (ctrl/defnode! [:filter/cutoff] :type :float :meta {:range [0.0 1.0]} :value 0.5)
    (ctrl/bind!    [:filter/cutoff] {:type :midi-cc :channel 1 :cc-num 74} :priority 20)
    (ctrl/set!     [:filter/cutoff] 0.3)    ; logical: atom only
    (ctrl/send!    [:filter/cutoff] 0.3)    ; physical: atom + MIDI CC dispatch
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
    0   Clojure code (ctrl/set! always wins)
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
  undo stack, bounded at default-undo-depth."
  [state]
  (update state :undo-stack
          (fn [stack]
            (let [entry    {:tree   (:tree state)
                            :serial (:serial state)}
                  new-stack (conj stack entry)]
              (if (> (count new-stack) default-undo-depth)
                (subvec new-stack 1)
                new-stack)))))

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
  "Logical write: update `path` to `value` in the control tree.

  Does NOT dispatch to physical outputs (MIDI CC, OSC). Use `send!` for that.
  Not an undo target by default; pass :undoable true to push to the undo stack.
  Creates a :data node if the path does not exist; preserves existing type and bindings.

  Examples:
    (ctrl/set! [:filter/cutoff] 0.7)
    (ctrl/set! [:chord] :C-major :undoable true)"
  [path value & {:keys [undoable]}]
  (swap! @system-ref
         (fn [s]
           (let [node (or (get-node s path) (make-node))
                 s'   (if undoable (push-undo s) s)]
             (put-node s' path (assoc node :value value)))))
  nil)

(defn send!
  "Physical write: update `path` to `value` and dispatch to all physical bindings.

  Binding types dispatched:

    :midi-cc   — single CC message; value scaled to [0,127] via binding :range.
                 Example: (ctrl/send! [:filter/cutoff] 0.7) ; CC 74 = 89

    :midi-nrpn — NRPN parameter; emits CC 99/98 (parameter select) then CC 6/38
                 (data entry). Supports 7-bit (CC6 only) and 14-bit (CC6 + CC38)
                 resolution controlled by :bits in the binding (default 14).
                 Value is scaled from binding :range to [0,127] or [0,16383].
                 Example: (ctrl/send! [:portamento] 1000) ; NRPN 1, 14-bit

    others     — logged; dispatch deferred to future sprint."
  [path value]
  (swap! @system-ref
         (fn [s]
           (let [node (or (get-node s path) (make-node))]
             (put-node s path (assoc node :value value)))))
  (let [node   (get-node @@system-ref path)
        now-ns (* (System/currentTimeMillis) 1000000)]
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
          (when (sidecar/connected?)
            (sidecar/send-cc! now-ns ch cc-num (max 0 (min 127 scaled)))))

        :midi-nrpn
        (let [ch        (int (or (:channel binding) 1))
              nrpn      (int (:nrpn binding))
              bits      (int (or (:bits binding) 14))
              max-val   (if (= 14 bits) 16383 127)
              [lo hi]   (or (:range binding) [0 max-val])
              lo        (double lo)
              hi        (double hi)
              raw       (double value)
              pct       (/ (- raw lo) (- hi lo))
              clamped   (max 0 (min max-val (long (Math/round (* pct (double max-val))))))
              param-msb (bit-and (bit-shift-right nrpn 7) 0x7F)
              param-lsb (bit-and nrpn 0x7F)
              ;; 14-bit: CC6 = value >> 7, CC38 = value & 0x7F
              ;; 7-bit:  CC6 = value (0–127), CC38 omitted
              data-msb  (if (= 14 bits)
                          (bit-and (bit-shift-right clamped 7) 0x7F)
                          clamped)
              data-lsb  (bit-and clamped 0x7F)]
          (when (sidecar/connected?)
            (sidecar/send-cc! now-ns ch 99 param-msb)
            (sidecar/send-cc! now-ns ch 98 param-lsb)
            (sidecar/send-cc! now-ns ch  6 data-msb)
            (when (= 14 bits)
              (sidecar/send-cc! now-ns ch 38 data-lsb))))

        ;; Other binding types: log and skip
        (binding [*out* *err*]
          (println (str "[ctrl] send! binding type " (:type binding)
                        " at " (pr-str path) " not yet dispatched"))))))
  nil)

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
