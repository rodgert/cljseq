; SPDX-License-Identifier: EPL-2.0
(ns cljseq.schema
  "Device model schema and realization management.

  Schema changes are first-class transactions: `defdevice-model`,
  `defrealization`, and `realize!` all write to well-known
  [:cljseq/schema ...] paths in the ctrl tree, so the transaction log
  captures schema evolution alongside parameter changes.

  ## Concepts

    device model  — transport-agnostic parameter space with capability profiles
    realization   — transport binding (MIDI, CLAP, OSC) for a specific model
    realize!      — session-scope activation: declares which realization is live

  ## Quick start

    ;; 1. Declare the model — transport-agnostic parameter vocabulary
    (defdevice-model :arp2600
      {:model/name \"ARP 2600\"
       :profile/base
       {:filter {:cutoff {:type :float :range [0.0 1.0]}
                 :res    {:type :float :range [0.0 1.0]}}
        :env    {:attack  {:type :float :range [0.0 1.0]}
                 :decay   {:type :float :range [0.0 1.0]}
                 :sustain {:type :float :range [0.0 1.0]}
                 :release {:type :float :range [0.0 1.0]}}}
       :profile/extended
       {:analog {:drift {:type :float :range [0.0 1.0]}}}})

    ;; 2. Declare a realization (transport binding)
    (defrealization :grey-meanie
      {:model      :arp2600
       :satisfies  #{:arp2600/base}
       :binding    :midi
       :channel    1
       :cc-map     {[:filter :cutoff] {:cc 74 :range [0 127]}
                    [:filter :res]    {:cc 71 :range [0 127]}}})

    ;; 3. Activate for this session
    (realize! :arp2600 :grey-meanie)

    ;; 4. Address by model path — realization handles transport
    (ctrl/send! [:arp2600 :filter :cutoff] 0.6)

  ## Schema in the transaction log

  Every schema write (defdevice-model, defrealization, realize!) appears in the
  ctrl transaction log with :source/kind :schema. Session templates can replay
  schema state by projecting [:cljseq/schema ...] transactions first.

  Key design decisions: doc/design-device-model.md, Q74-Q76, Q79."
  (:require [cljseq.ctrl :as ctrl]))

;; ---------------------------------------------------------------------------
;; Schema source context
;; ---------------------------------------------------------------------------

(def ^:private schema-source
  {:source/kind :schema :source/id ::schema :source/parent nil})

;; ---------------------------------------------------------------------------
;; Schema path helpers
;; ---------------------------------------------------------------------------

(defn- model-path       [model-id]       [:cljseq/schema :device-models       model-id])
(defn- realization-path [realization-id] [:cljseq/schema :realizations         realization-id])
(defn- active-path      [model-id]       [:cljseq/schema :active-realizations  model-id])

;; ---------------------------------------------------------------------------
;; Profile introspection
;; ---------------------------------------------------------------------------

(defn- flatten-profile
  "Walk a nested profile map and return [[path-vec param-spec] ...].
  A leaf is identified by having a :type key."
  [profile]
  (letfn [(walk [prefix m]
            (if (contains? m :type)
              [[prefix m]]
              (mapcat (fn [[k v]]
                        (when (map? v)
                          (walk (conj prefix k) v)))
                      m)))]
    (walk [] profile)))

;; ---------------------------------------------------------------------------
;; defdevice-model
;; ---------------------------------------------------------------------------

(defn defdevice-model
  "Register a transport-agnostic device model.

  `model-id`  — keyword identifying this model (e.g. :arp2600)
  `model-map` — map with:
    :model/name       — human-readable name string
    :profile/base     — required parameter schema (nested {:type :range ...} map)
    :profile/extended — optional extended parameter schema

  Writes to [:cljseq/schema :device-models model-id] as a schema transaction.
  Idempotent: re-calling replaces the stored model.

  Example:
    (defdevice-model :arp2600
      {:model/name \"ARP 2600\"
       :profile/base
       {:filter {:cutoff {:type :float :range [0.0 1.0]}
                 :res    {:type :float :range [0.0 1.0]}}}})"
  [model-id model-map]
  (ctrl/with-source (assoc schema-source :source/id model-id)
    (ctrl/set! (model-path model-id)
               (assoc model-map :model/id model-id)))
  model-id)

;; ---------------------------------------------------------------------------
;; defrealization
;; ---------------------------------------------------------------------------

(defn defrealization
  "Register a transport binding for a device model.

  `realization-id`  — keyword identifying this realization (e.g. :grey-meanie)
  `realization-map` — map with:
    :model     — model-id this realization implements (required)
    :satisfies — set of capability profile keywords satisfied
                 e.g. #{:arp2600/base} or #{:arp2600/base :arp2600/extended}
    :binding   — transport type: :midi, :clap, :osc
    :channel   — MIDI channel integer (for :midi binding)
    :cc-map    — {path-vec {:cc N :range [lo hi]}} MIDI CC assignments
    :nrpn-map  — {path-vec {:nrpn N :bits 14 :range [lo hi]}} MIDI NRPN assignments
    :param-map — {path-vec param-id} for :clap binding (future sprint)

  Writes to [:cljseq/schema :realizations realization-id] as a schema transaction.

  Example:
    (defrealization :grey-meanie
      {:model     :arp2600
       :satisfies #{:arp2600/base}
       :binding   :midi
       :channel   1
       :cc-map    {[:filter :cutoff] {:cc 74 :range [0 127]}
                   [:filter :res]    {:cc 71 :range [0 127]}}})"
  [realization-id realization-map]
  (ctrl/with-source (assoc schema-source :source/id realization-id)
    (ctrl/set! (realization-path realization-id)
               (assoc realization-map :realization/id realization-id)))
  realization-id)

;; ---------------------------------------------------------------------------
;; realize! helpers
;; ---------------------------------------------------------------------------

(defn- register-model-nodes!
  "Create ctrl tree nodes for all parameters declared in model profiles."
  [model-id model-map realization-map]
  (let [satisfies   (:satisfies realization-map #{})
        model-kw    (keyword (name model-id))
        base-kw     (keyword (name model-id) "base")
        ext-kw      (keyword (name model-id) "extended")
        base     (when (contains? satisfies base-kw)
                   (:profile/base model-map {}))
        extended (when (contains? satisfies ext-kw)
                   (:profile/extended model-map {}))
        ;; Always register base profile nodes; extended only if satisfied
        all-params  (concat (flatten-profile (or (:profile/base model-map) {}))
                            (when (contains? satisfies ext-kw)
                              (flatten-profile (or (:profile/extended model-map) {}))))]
    (doseq [[param-path spec] all-params]
      (let [node-path (into [model-id] param-path)
            t         (or (:type spec) :data)
            meta      (cond-> {}
                        (:range spec)  (assoc :range (:range spec))
                        (:values spec) (assoc :values (:values spec)))]
        (ctrl/defnode! node-path :type t :node-meta meta)))))

(defn- bind-midi-realization!
  "Install MIDI CC and NRPN bindings for a :midi realization.
  Clears any pre-existing priority-20 binding before installing."
  [model-id realization-map]
  (let [channel  (int (or (:channel realization-map) 1))
        cc-map   (or (:cc-map   realization-map) {})
        nrpn-map (or (:nrpn-map realization-map) {})]
    (doseq [[param-path {:keys [cc range]}] cc-map]
      (let [node-path (into [model-id] param-path)
            r         (or range [0 127])]
        (ctrl/unbind! node-path 20)
        (ctrl/bind! node-path
                    {:type :midi-cc :channel channel :cc-num cc :range r}
                    :priority 20)))
    (doseq [[param-path {:keys [nrpn bits range]}] nrpn-map]
      (let [node-path (into [model-id] param-path)
            b         (int (or bits 14))
            max-val   (if (= 14 b) 16383 127)
            r         (or range [0 max-val])]
        (ctrl/unbind! node-path 20)
        (ctrl/bind! node-path
                    {:type :midi-nrpn :channel channel :nrpn nrpn :bits b :range r}
                    :priority 20)))))

(defn- teardown-midi-bindings!
  "Remove MIDI bindings installed by a previous realize! for model-id."
  [model-id realization-map]
  (let [cc-map   (or (:cc-map   realization-map) {})
        nrpn-map (or (:nrpn-map realization-map) {})]
    (doseq [param-path (concat (keys cc-map) (keys nrpn-map))]
      (ctrl/unbind! (into [model-id] param-path) 20))))

;; ---------------------------------------------------------------------------
;; realize!
;; ---------------------------------------------------------------------------

(defn realize!
  "Activate a realization for a model in the current session.

  Looks up model-id and realization-id from the schema ctrl tree, creates
  ctrl tree nodes for all declared parameters, and installs transport bindings
  (MIDI CC, NRPN). Any prior realization for the same model is torn down first.

  Records the active realization as a schema transaction at:
    [:cljseq/schema :active-realizations model-id]

  Throws if the model or realization is not registered, or if the realization
  declares a different model than model-id.

  Example:
    (realize! :arp2600 :grey-meanie)   ; hardware session
    (realize! :arp2600 :timewarp-vst)  ; laptop session

  After realize!, address the model via the ctrl tree:
    (ctrl/send! [:arp2600 :filter :cutoff] 0.6)"
  [model-id realization-id]
  (let [model       (ctrl/get (model-path model-id))
        realization (ctrl/get (realization-path realization-id))]
    (when-not model
      (throw (ex-info "cljseq.schema/realize!: model not registered"
                      {:model model-id
                       :registered (ctrl/child-keys [:cljseq/schema :device-models])})))
    (when-not realization
      (throw (ex-info "cljseq.schema/realize!: realization not registered"
                      {:realization realization-id
                       :registered (ctrl/child-keys [:cljseq/schema :realizations])})))
    (when (not= (:model realization) model-id)
      (throw (ex-info "cljseq.schema/realize!: realization is for a different model"
                      {:model model-id :realization-model (:model realization)})))
    ;; Tear down previous realization bindings if one is active
    (when-let [prev-id (ctrl/get (active-path model-id))]
      (when-let [prev-r (ctrl/get (realization-path prev-id))]
        (when (= :midi (:binding prev-r))
          (teardown-midi-bindings! model-id prev-r))))
    ;; Register nodes for all profile parameters
    (register-model-nodes! model-id model realization)
    ;; Install transport bindings
    (case (:binding realization)
      :midi (bind-midi-realization! model-id realization)
      nil)   ;; :clap, :osc — future sprint
    ;; Record active realization as a schema transaction
    (ctrl/with-source (assoc schema-source :source/id model-id)
      (ctrl/set! (active-path model-id) realization-id)))
  nil)

;; ---------------------------------------------------------------------------
;; Query API
;; ---------------------------------------------------------------------------

(defn get-model
  "Return the stored model map for model-id, or nil if not registered."
  [model-id]
  (ctrl/get (model-path model-id)))

(defn get-realization
  "Return the stored realization map for realization-id, or nil if not registered."
  [realization-id]
  (ctrl/get (realization-path realization-id)))

(defn active-realization
  "Return the realization-id currently active for model-id, or nil."
  [model-id]
  (ctrl/get (active-path model-id)))

(defn list-models
  "Return a seq of registered model IDs."
  []
  (ctrl/child-keys [:cljseq/schema :device-models]))

(defn list-realizations
  "Return a seq of registered realization IDs."
  []
  (ctrl/child-keys [:cljseq/schema :realizations]))

(defn satisfies-profile?
  "Return true if realization-id declares support for the given profile keyword.

  Profile keywords follow the convention <model-id>/<profile-name>:
    :arp2600/base      — base profile
    :arp2600/extended  — extended profile

  Example:
    (satisfies-profile? :grey-meanie :arp2600/base)    ; => true
    (satisfies-profile? :grey-meanie :arp2600/extended) ; => false"
  [realization-id profile-kw]
  (boolean (contains? (:satisfies (ctrl/get (realization-path realization-id)))
                      profile-kw)))
