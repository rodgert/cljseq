; SPDX-License-Identifier: EPL-2.0
(ns cljseq.fractal
  "cljseq fractal sequencer — self-similar branching sequence generation.

  Inspired by the Qu-Bit Electronix Bloom sequencer (see doc/attribution.md).
  Generates sequences that exhibit self-similar (fractal) branching structure
  via deterministic transformations of a hand-programmed trunk sequence.
  Variability comes from navigating different paths through the branch tree
  and from probabilistic mutation.

  ## Quick start

    (deffractal melody
      :trunk  [{:pitch/midi 60 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
               {:pitch/midi 64 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
               {:pitch/midi 67 :dur/beats 1/2 :gate/on? true :gate/len 0.8}
               {:pitch/midi 65 :dur/beats 1/4 :gate/on? true :gate/len 0.5}]
      :transforms [:reverse :inverse :mutate]
      :scale  (random/weighted-scale :major)
      :root   60)

    ;; In a live-loop:
    (let [step (next-step! melody)]
      (play! :midi/synth (:pitch/midi step) :dur (:dur/beats step))
      (sleep! (:dur/beats step)))

  Key design decisions:
    Q39 — freeze!/thaw! for multi-step trunk edits
    Q41 — unified step map: plain map, namespace-qualified keys
    Q43 — path supports integer, keyword, or explicit vector
    §24 — Fractal Sequence Architecture (Bloom-Inspired)"
  (:require [cljseq.ctrl   :as ctrl]
            [cljseq.random :as random]
            [cljseq.seq    :as sq]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register a fractal context atom under the given name keyword. Used by deffractal macro."
  [k ctx-atom]
  (swap! registry assoc k ctx-atom))

;; ---------------------------------------------------------------------------
;; Transformation algebra (§24.4)
;; ---------------------------------------------------------------------------

(defn reverse-seq
  "Reverse the step order of a sequence."
  [steps]
  (vec (reverse steps)))

(defn inverse-seq
  "Mirror each pitch around root-midi: new = root + (root - old).
  Steps without :pitch/midi pass through unchanged."
  [steps root-midi]
  (let [root (int root-midi)]
    (mapv (fn [step]
            (if-let [p (:pitch/midi step)]
              (assoc step :pitch/midi (max 0 (min 127 (+ root (- root (int p))))))
              step))
          steps)))

(defn transpose-seq
  "Transpose all :pitch/midi values by semitones (chromatic). Clamps to [0,127]."
  [steps semitones]
  (let [s (int semitones)]
    (mapv (fn [step]
            (if-let [p (:pitch/midi step)]
              (assoc step :pitch/midi (max 0 (min 127 (+ (int p) s))))
              step))
          steps)))

(defn mutate-seq
  "Randomly mutate pitches and gate lengths.

  Options:
    :note-prob  — probability of pitch mutation per step [0.0-1.0] (default 0.25)
    :gate-prob  — probability of gate-len mutation per step [0.0-1.0] (default 0.1)
    :scale      — weighted-scale map from cljseq.random (nil = chromatic ±3 semitone walk)
    :root       — root MIDI note for scale-aware mutation (default 60)"
  [steps & {:keys [note-prob gate-prob scale root]
            :or   {note-prob 0.25 gate-prob 0.1 root 60}}]
  (let [np (double note-prob)
        gp (double gate-prob)
        r  (int root)]
    (mapv (fn [step]
            (cond-> step
              (and (:pitch/midi step) (< (rand) np))
              (assoc :pitch/midi
                     (if scale
                       (let [midi (+ r (rand-int 24))]
                         (random/progressive-quantize midi scale 0.75))
                       (max 0 (min 127 (+ (int (:pitch/midi step))
                                          (- (rand-int 7) 3))))))
              (and (contains? step :gate/len) (< (rand) gp))
              (assoc :gate/len
                     (max 0.05 (min 1.0 (+ (double (:gate/len step 0.5))
                                           (* 0.4 (- (rand) 0.5))))))))
          steps)))

(defn randomize-seq
  "Generate a fresh random sequence of `len` steps.

  Options:
    :scale   — weighted-scale map (nil = chromatic random in [root, root+24))
    :root    — root MIDI note (default 60)
    :dur     — step duration in beats (default 1/4)"
  [len & {:keys [scale root dur]
          :or   {root 60 dur 1/4}}]
  (let [r         (int root)
        intervals (if scale (:intervals scale) (range 12))]
    (vec (repeatedly len
                     (fn []
                       (let [iv   (rand-nth intervals)
                             oct  (* 12 (rand-nth [0 1]))
                             midi (max 0 (min 127 (+ r iv oct)))]
                         {:pitch/midi midi
                          :dur/beats  dur
                          :gate/on?   true
                          :gate/len   0.5}))))))

(defn resize-seq
  "Resize `steps` to `target-len` using `algorithm`:
    :spread — interleave silent steps between each original step
    :stretch — repeat each step proportionally
    :clone  — cycle the whole sequence (default)"
  [steps target-len algorithm]
  (let [n (count steps)]
    (if (zero? n)
      (vec (repeat target-len {:dur/beats 1/4 :gate/on? false :gate/len 0.0}))
      (case algorithm
        :spread
        (let [silent {:pitch/midi (:pitch/midi (first steps))
                      :dur/beats  (:dur/beats (first steps) 1/4)
                      :gate/on?   false
                      :gate/len   0.0}]
          (vec (take target-len (interleave steps (repeat silent)))))
        :stretch
        (let [factor (max 1 (int (Math/ceil (/ target-len n))))]
          (vec (take target-len (mapcat #(repeat factor %) steps))))
        (vec (take target-len (cycle steps)))))))

(defn rotate-seq
  "Shift the start point of `steps` by `offset` positions."
  [steps offset]
  (let [n   (count steps)]
    (if (zero? n)
      steps
      (let [off (mod (int offset) n)]
        (vec (concat (drop off steps) (take off steps)))))))

;; ---------------------------------------------------------------------------
;; Playback order (§24.7)
;; ---------------------------------------------------------------------------

(defmulti step-indices
  "Return a vector of step indices for playing `len` steps in `order` mode.
  `opts` is a map of mode-specific options (e.g. {:page-len 4})."
  (fn [order _len _opts] order))

(defmethod step-indices :forward   [_ len _] (vec (range len)))
(defmethod step-indices :reverse   [_ len _] (vec (range (dec len) -1 -1)))
(defmethod step-indices :pendulum  [_ len _]
  (if (<= len 1)
    [0]
    (vec (concat (range len) (range (- len 2) 0 -1)))))
(defmethod step-indices :random    [_ len _] (vec (shuffle (range len))))
(defmethod step-indices :converge  [_ len _]
  (let [half (quot len 2)]
    (vec (mapcat (fn [i] [(nth (range len) i)
                          (nth (range len) (- len 1 i))])
                 (range half)))))
(defmethod step-indices :diverge   [_ len _]
  (let [c (quot len 2)]
    (vec (mapcat (fn [d]
                   (let [lo (- c d) hi (+ c d)]
                     (cond
                       (< lo 0) [hi]
                       (>= hi len) [lo]
                       (= lo hi) [lo]
                       :else [lo hi])))
                 (range (inc c))))))
(defmethod step-indices :page-jump [_ len {:keys [page-len] :or {page-len 4}}]
  (let [pages (partition-all page-len (range len))]
    (vec (mapcat identity (shuffle pages)))))
(defmethod step-indices :default   [_ len _] (vec (range len)))

;; ---------------------------------------------------------------------------
;; Branch tree (§24.2)
;; ---------------------------------------------------------------------------

(defn- apply-transform
  "Apply a single transform keyword to a seq of steps."
  [steps transform {:keys [scale root mutate-prob transpose-semitones]
                    :or   {root 60 mutate-prob 0.25 transpose-semitones 12}}]
  (case transform
    :reverse   (reverse-seq steps)
    :inverse   (inverse-seq steps root)
    :transpose (transpose-seq steps transpose-semitones)
    :mutate    (mutate-seq steps
                           :note-prob mutate-prob
                           :scale     scale
                           :root      root)
    :randomize (randomize-seq (count steps)
                              :scale scale
                              :root  root)
    steps))

(defn build-tree
  "Build a branch tree from a trunk sequence.
  Returns a vector `[trunk branch1 branch2 ...]`.
  Each branch is produced by applying successive transforms to the previous one.

  Options:
    :transforms        — vector of transform keywords (default [])
    :scale             — weighted-scale map (for :mutate/:randomize/:inverse)
    :root              — root MIDI note (default 60)
    :mutate-prob       — mutation probability for :mutate transform (default 0.25)
    :transpose-semitones — semitones for :transpose transform (default 12)"
  [trunk & {:keys [transforms] :or {transforms []} :as opts}]
  (reduce (fn [tree transform]
            (conj tree (apply-transform (last tree) transform opts)))
          [(vec trunk)]
          transforms))

;; ---------------------------------------------------------------------------
;; Path navigation (§24.3, Q43)
;; ---------------------------------------------------------------------------

(defn make-path
  "Resolve a path spec to a vector of branch indices.

  Supported forms:
    [0 2 1]      — explicit vector of branch indices (used directly)
    0            — integer: 0 = forward, 1 = reverse through all branches
    :leftmost    — forward through all branches [0 1 ... n-1]
    :rightmost   — trunk + branches in reverse [0 n-1 n-2 ... 1]
    :random      — shuffled branch order
    :depth-first — trunk, then alternate: trunk + branch i for each i"
  [path-spec n-branches]
  (let [n (int n-branches)]
    (cond
      (vector? path-spec)      path-spec
      (= :leftmost  path-spec) (vec (range n))
      (= :rightmost path-spec) (vec (cons 0 (range (dec n) 0 -1)))
      (= :random    path-spec) (vec (shuffle (range n)))
      (= :depth-first path-spec)
      (vec (mapcat #(vector 0 %) (range 1 n)))
      (integer? path-spec)
      (case (int path-spec)
        0 (vec (range n))
        1 (vec (cons 0 (range (dec n) 0 -1)))
        (vec (range n)))
      :else (vec (range n)))))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-fractal-context
  "Build a fractal context map from raw keyword options."
  [opts]
  (let [{:keys [trunk transforms scale root mutate-prob
                transpose-semitones path order]
         :or   {trunk [] transforms [] root 60
                mutate-prob 0.25 transpose-semitones 12
                path 0 order :forward}} opts
        tree        (build-tree trunk
                                :transforms          transforms
                                :scale               scale
                                :root                root
                                :mutate-prob         mutate-prob
                                :transpose-semitones transpose-semitones)
        path-idxs   (make-path path (count tree))
        first-br    (nth tree (first path-idxs) [])
        step-order  (if (seq first-br)
                      (vec (step-indices order (count first-br) {}))
                      [])]
    {:trunk       (vec trunk)
     :tree        tree
     :path-idxs   path-idxs
     :branch-pos  0
     :step-order  step-order
     :step-pos    0
     :last-step   nil
     :frozen?     false
     :order       order
     :opts        opts}))

;; ---------------------------------------------------------------------------
;; deffractal macro (§24.8)
;; ---------------------------------------------------------------------------

(defmacro deffractal
  "Define a named fractal sequence context and register it in the ctrl tree.

  Creates a var bound to an atom holding the context, and registers it at
  [:fractal <name>] in the ctrl tree.

  Parameters:
    :trunk            — vector of step maps (base sequence)
    :transforms       — vector of transform keywords applied sequentially:
                        :reverse :inverse :transpose :mutate :randomize
    :scale            — weighted-scale map from cljseq.random/weighted-scale
    :root             — root MIDI note for inverse/transpose/mutate (default 60)
    :mutate-prob      — per-step mutation probability (default 0.25)
    :transpose-semitones — semitone shift for :transpose (default 12, = one octave)
    :path             — path spec: 0=forward, 1=reverse, :random, or explicit vector
    :order            — playback order within each branch:
                        :forward :reverse :pendulum :random :converge :diverge
                        :page-jump (default :forward)

  Dispatch operations:
    (next-step! ctx-atom)       — advance and return next step map
    (current-branch ctx-atom)   — which branch index is currently playing
    (fractal-path! ctx-atom p)  — switch path at next branch boundary
    (freeze! ctx-atom)          — pin state; disable reactive regeneration
    (thaw! ctx-atom)            — re-enable regeneration after freeze!
    (mutate! ctx-atom)          — reseed random mutations in all branches
    (reseed! ctx-atom)          — randomize trunk and regenerate all branches

  Example:
    (deffractal melody
      :trunk  [{:pitch/midi 60 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
               {:pitch/midi 64 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
               {:pitch/midi 67 :dur/beats 1/2 :gate/on? true :gate/len 0.8}
               {:pitch/midi 65 :dur/beats 1/4 :gate/on? true :gate/len 0.5}]
      :transforms [:reverse :inverse :mutate]
      :scale  (cljseq.random/weighted-scale :major)
      :root   60)"
  [gen-name & opts]
  (let [opts-map (apply hash-map opts)]
    `(do
       (def ~gen-name (atom (make-fractal-context ~opts-map)))
       (register! ~(keyword (name gen-name)) ~gen-name)
       (ctrl/defnode! [:fractal ~(keyword (name gen-name))]
                      :type :data :value @~gen-name)
       ~gen-name)))

;; ---------------------------------------------------------------------------
;; Draw / navigation
;; ---------------------------------------------------------------------------

(defn next-step!
  "Advance the fractal context and return the next step map.

  Advances the step position within the current branch. When the branch is
  exhausted, advances to the next branch in the path and pre-computes its
  step order. Wraps the path when all branches are consumed.

  Returns the step map, or nil if the trunk is empty."
  [ctx-atom]
  (:last-step
   (swap! ctx-atom
          (fn [{:keys [tree path-idxs branch-pos step-order step-pos order] :as state}]
            (if (empty? step-order)
              state
              (let [branch    (nth tree (nth path-idxs branch-pos) [])
                    step-idx  (nth step-order step-pos)
                    step      (nth branch step-idx nil)
                    next-sp   (inc step-pos)
                    [new-bp new-so new-sp]
                    (if (>= next-sp (count step-order))
                      (let [next-bp (mod (inc branch-pos) (count path-idxs))
                            next-br (nth tree (nth path-idxs next-bp) [])
                            next-so (if (seq next-br)
                                      (vec (step-indices order (count next-br) {}))
                                      [])]
                        [next-bp next-so 0])
                      [branch-pos step-order next-sp])]
                (assoc state
                       :last-step   step
                       :branch-pos  new-bp
                       :step-order  new-so
                       :step-pos    new-sp)))))))

(defn current-branch
  "Return the branch index currently playing."
  [ctx-atom]
  (let [{:keys [path-idxs branch-pos]} @ctx-atom]
    (nth path-idxs branch-pos 0)))

(defn fractal-path!
  "Switch the active path at the next branch boundary.
  path-spec: 0=forward, 1=reverse, :random, :leftmost, :rightmost, or explicit vector."
  [ctx-atom path-spec]
  (swap! ctx-atom
         (fn [{:keys [tree order] :as state}]
           (let [new-idxs  (make-path path-spec (count tree))
                 first-br  (nth tree (first new-idxs) [])
                 new-order (if (seq first-br)
                             (vec (step-indices order (count first-br) {}))
                             [])]
             (assoc state
                    :path-idxs  new-idxs
                    :branch-pos 0
                    :step-order new-order
                    :step-pos   0))))
  nil)

(defn freeze!
  "Pin the current path/state; stop reactive branch regeneration (Q39)."
  [ctx-atom]
  (swap! ctx-atom assoc :frozen? true)
  nil)

(defn thaw!
  "Re-enable reactive regeneration after freeze!."
  [ctx-atom]
  (swap! ctx-atom assoc :frozen? false)
  nil)

(defn mutate!
  "Reseed random mutations — regenerates only :mutate branches.
  Preserves the current path and playback position."
  [ctx-atom]
  (swap! ctx-atom
         (fn [{:keys [opts branch-pos step-pos order] :as state}]
           (when-not (:frozen? state)
             (let [new-ctx (make-fractal-context opts)
                   new-br  (nth (:tree new-ctx) (nth (:path-idxs new-ctx) 0) [])
                   _ nil]
               (assoc state
                      :tree (:tree new-ctx))))))
  nil)

(defn reseed!
  "Randomize the trunk and regenerate all branches."
  [ctx-atom]
  (swap! ctx-atom
         (fn [{:keys [opts frozen?] :as state}]
           (if frozen?
             state
             (let [{:keys [scale root trunk]} opts
                   new-trunk (if scale
                               (randomize-seq (count trunk)
                                              :scale scale
                                              :root  (or root 60))
                               trunk)
                   new-opts  (assoc opts :trunk new-trunk)
                   new-ctx   (make-fractal-context new-opts)]
               (merge state new-ctx {:opts new-opts})))))
  nil)

;; ---------------------------------------------------------------------------
;; FractalSeq — IStepSequencer wrapper
;; ---------------------------------------------------------------------------

(defrecord FractalSeq [ctx-atom vel])
;; ctx-atom — fractal context atom (from make-fractal-context / deffractal)
;; vel      — default velocity 0–127

(defn make-fractal-seq
  "Wrap a fractal context atom as an IStepSequencer.

  `ctx-atom` — fractal context atom from make-fractal-context or deffractal.

  Options:
    :vel — default velocity 0–127 (default 100)

  Returns a FractalSeq. Use with run-step! from cljseq.seq (infinite source —
  seq-cycle-length returns nil; caller drives the loop via deflive-loop).

  Example:
    (deffractal melody {:trunk [...] :transforms [...]})
    (deflive-loop :frac {}
      (run-step! (make-fractal-seq melody)))"
  [ctx-atom & {:keys [vel] :or {vel 100}}]
  (->FractalSeq ctx-atom (long vel)))

(extend-protocol sq/IStepSequencer
  FractalSeq
  (next-event [fs]
    (let [step  (next-step! (:ctx-atom fs))
          beats (double (:dur/beats step 1/4))]
      (if (or (nil? step) (not (:gate/on? step true)))
        {:event nil :beats beats}
        {:event (assoc step :mod/velocity (:vel fs)) :beats beats})))
  (seq-cycle-length [_] nil))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn fractal-names
  "Return a seq of registered fractal context names."
  []
  (or (keys @registry) '()))

;; ---------------------------------------------------------------------------
;; Ornamentation — scale-aware step expansion (§24.5)
;; ---------------------------------------------------------------------------

(def ^:private chromatic-intervals [0 1 2 3 4 5 6 7 8 9 10 11])

(defn- scale-notes-in-range
  "Sorted vec of all MIDI note numbers in the scale rooted at `root` within [lo hi]."
  [intervals root lo hi]
  (let [r (int root)]
    (vec (sort (for [oct (range -2 11)
                     iv  intervals
                     :let [n (+ (* 12 oct) r iv)]
                     :when (<= (int lo) n (int hi))]
                 n)))))

(defn- degree-above
  "First scale note strictly above `midi`."
  [midi intervals root]
  (let [notes (scale-notes-in-range intervals root 0 127)]
    (first (filter #(> % (int midi)) notes))))

(defn- degree-below
  "First scale note strictly below `midi`."
  [midi intervals root]
  (let [notes (scale-notes-in-range intervals root 0 127)]
    (last (filter #(< % (int midi)) notes))))

(defn- n-degrees-toward
  "Up to `n` consecutive scale degrees from `from-midi` toward `to-midi`.
  Returns a vec of MIDI notes; may be shorter than n if range exhausted."
  [from-midi to-midi n intervals root]
  (let [notes (scale-notes-in-range intervals root 0 127)
        up?   (> (int to-midi) (int from-midi))
        pool  (if up?
                (filter #(> % (int from-midi)) notes)
                (reverse (filter #(< % (int from-midi)) notes)))]
    (vec (take n pool))))

(defn- n-degrees-away
  "Up to `n` consecutive scale degrees from `from-midi` away from `to-midi`."
  [from-midi to-midi n intervals root]
  (n-degrees-toward from-midi (+ (int from-midi) (- (int from-midi) (int to-midi)))
                    n intervals root))

(defn- sub-step
  "Build a sub-step map inheriting gate-len from parent."
  [pitch dur parent]
  {:pitch/midi (max 0 (min 127 (int pitch)))
   :dur/beats  dur
   :gate/on?   true
   :gate/len   (:gate/len parent 0.5)})

(defn- rest-step
  "Build a silent sub-step."
  [dur parent]
  {:pitch/midi (:pitch/midi parent 60)
   :dur/beats  dur
   :gate/on?   false
   :gate/len   0.0})

(defn ornament
  "Expand `step` into a vec of sub-step maps according to `ornament-type`.

  `context` map:
    :scale     — weighted-scale map from cljseq.random (nil = chromatic)
    :root      — root MIDI note for scale navigation (default 60)
    :next-step — following step map (for :anticipation, :run-toward, etc.)
    :prev-step — preceding step map (for :suspension)

  Sub-step durations sum to the parent step's :dur/beats. If :scale is nil,
  degree-navigation falls back to chromatic semitones.

  Ornament types:
    Two-event:   :anticipation :suspension :syncopation :octave-up :fifth-up
                 :half-turn-toward :half-turn-away
    Three-event: :mordent-up :mordent-down
    Four-event:  :run-toward :run-away :turn :arp-toward :arp-away
    Eight-event: :trill"
  [step ornament-type {:keys [scale root next-step prev-step]
                        :or   {root 60}}]
  (let [d    (:dur/beats step 1/4)
        p    (int (:pitch/midi step 60))
        ivs  (if scale (:intervals scale) chromatic-intervals)
        r    (int root)
        abv  (or (degree-above p ivs r) (+ p 2))
        blw  (or (degree-below p ivs r) (- p 2))
        np   (int (:pitch/midi next-step p))
        pp   (int (:pitch/midi prev-step p))]
    (case ornament-type
      ;; --- Two-event ornaments ---
      :anticipation
      [(sub-step np (/ d 4) step)
       (sub-step p  (* d 3/4) step)]

      :suspension
      [(sub-step pp (/ d 4) step)
       (sub-step p  (* d 3/4) step)]

      :syncopation
      [(rest-step (/ d 4) step)
       (sub-step p (* d 3/4) step)]

      :octave-up
      [(sub-step p        (/ d 2) step)
       (sub-step (+ p 12) (/ d 2) step)]

      :fifth-up
      (let [fifth (or (degree-above p ivs r)
                      (+ p 7))]
        [(sub-step p     (/ d 2) step)
         (sub-step fifth (/ d 2) step)])

      :half-turn-toward
      (let [beyond (n-degrees-toward p np 2 ivs r)
            grace  (or (second beyond) (first beyond) abv)]
        [(sub-step grace (/ d 4) step)
         (sub-step p     (* d 3/4) step)])

      :half-turn-away
      (let [away (n-degrees-away p np 1 ivs r)
            grace (or (first away) blw)]
        [(sub-step grace (/ d 4) step)
         (sub-step p     (* d 3/4) step)])

      ;; --- Three-event ornaments ---
      :mordent-up
      [(sub-step p   (/ d 4) step)
       (sub-step abv (/ d 4) step)
       (sub-step p   (/ d 2) step)]

      :mordent-down
      [(sub-step p   (/ d 4) step)
       (sub-step blw (/ d 4) step)
       (sub-step p   (/ d 2) step)]

      ;; --- Four-event ornaments ---
      :run-toward
      (let [run (n-degrees-toward p np 4 ivs r)
            run (if (< (count run) 4)
                  (vec (take 4 (cycle (conj run np))))
                  run)]
        (mapv #(sub-step % (/ d 4) step) run))

      :run-away
      (let [run (n-degrees-away p np 4 ivs r)
            run (if (< (count run) 4)
                  (vec (take 4 (cycle (conj run blw))))
                  run)]
        (mapv #(sub-step % (/ d 4) step) run))

      :turn
      [(sub-step p   (/ d 4) step)
       (sub-step abv (/ d 4) step)
       (sub-step blw (/ d 4) step)
       (sub-step p   (/ d 4) step)]

      :arp-toward
      (let [run (n-degrees-toward p np 3 ivs r)
            run (conj (vec run) np)]
        (mapv #(sub-step % (/ d 4) step) (take 4 (cycle run))))

      :arp-away
      (let [run (n-degrees-away p np 3 ivs r)
            run (if (seq run) run [blw])]
        (mapv #(sub-step % (/ d 4) step) (take 4 (cycle run))))

      ;; --- Eight-event ornament ---
      :trill
      (vec (mapcat (fn [i] [(sub-step (if (even? i) p abv) (/ d 8) step)])
                   (range 8)))

      ;; Default: return step unchanged as single sub-step
      [(sub-step p d step)])))

(defn ornament-seq
  "Apply ornaments probabilistically to a sequence of steps.

  For each step, with probability `prob`, replaces it with an ornamented
  expansion using a randomly selected ornament type from `types`. Sub-steps
  replace the original step in the output seq. Steps without :pitch/midi
  are passed through unchanged.

  Options:
    :scale     — weighted-scale map (nil = chromatic)
    :root      — root MIDI note (default 60)
    :prob      — probability a step is ornamented [0.0-1.0] (default 0.3)
    :types     — seq of ornament type keywords to choose from
                 (default [:mordent-up :mordent-down :anticipation :turn])
    :max-steps — :two, :four, or :eight — cap ornament expansion size
                 (default :eight = no cap)"
  [steps & {:keys [scale root prob types max-steps]
            :or   {root 60 prob 0.3
                   types [:mordent-up :mordent-down :anticipation :turn]
                   max-steps :eight}}]
  (let [allowed (case max-steps
                  :two  #{:anticipation :suspension :syncopation :octave-up
                          :fifth-up :half-turn-toward :half-turn-away}
                  :four #{:anticipation :suspension :syncopation :octave-up
                          :fifth-up :half-turn-toward :half-turn-away
                          :run-toward :run-away :turn :arp-toward :arp-away
                          :mordent-up :mordent-down}
                  nil)
        eligible (if allowed (filter allowed types) types)
        n (count steps)]
    (vec (mapcat (fn [i]
                   (let [step (nth steps i)]
                     (if (and (:pitch/midi step) (< (rand) (double prob)) (seq eligible))
                       (let [t    (rand-nth eligible)
                             ctx  {:scale     scale
                                   :root      root
                                   :next-step (when (< (inc i) n) (nth steps (inc i)))
                                   :prev-step (when (pos? i) (nth steps (dec i)))}]
                         (ornament step t ctx))
                       [step])))
                 (range n)))))
