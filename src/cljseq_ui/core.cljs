; SPDX-License-Identifier: EPL-2.0
(ns cljseq-ui.core
  "cljseq browser control surface.

  Connects to the cljseq HTTP server via WebSocket (/ws) for live ctrl-tree
  updates. The ctrl-tree panel groups nodes by their first path segment, with
  each group collapsible. Interactive controls (sliders, toggles, enum buttons)
  write back via WebSocket. BPM has +/− nudge and click-to-edit.

  A live-loop monitor polls GET /loops every 2 s and shows running loops with
  tick counts. The changes log (newest-first) occupies the rest of the right
  panel.

  Entry point: init! — called by shadow-cljs on load and hot-reload."
  (:require [reagent.core :as r]
            [reagent.dom  :as rdom]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; State atoms
;; ---------------------------------------------------------------------------

;; {path-str {:value v :type t :meta m :path-arr [...]}}
(defonce ctrl-state       (r/atom (sorted-map)))
(defonce bpm-state        (r/atom nil))
(defonce ws-status        (r/atom :connecting))  ; :connecting :open :closed
(defonce log-entries      (r/atom []))            ; newest-first
(defonce loops-state      (r/atom []))            ; [{:name :running? :ticks}]
(defonce collapsed-groups (r/atom #{}))           ; set of group-name strings

(def ^:private max-log 60)

;; ---------------------------------------------------------------------------
;; WebSocket
;; ---------------------------------------------------------------------------

(defonce ^:private ws-ref (atom nil))

(defn- ws-url []
  (let [loc   js/window.location
        proto (if (= "https:" (.-protocol loc)) "wss:" "ws:")]
    (str proto "//" (.-host loc) "/ws")))

(defn- send-ctrl!
  "Write a ctrl value back to the server via WebSocket.
  Uses the stored :path-arr to preserve namespaced-keyword paths."
  [path-str value]
  (when-let [node (get @ctrl-state path-str)]
    (when-let [ws @ws-ref]
      (when (= 1 (.-readyState ws))
        (.send ws (js/JSON.stringify
                    #js {"path"  (clj->js (:path-arr node))
                         "value" value}))))))

(defn- handle-message! [evt]
  (when-let [data (try (js->clj (.parse js/JSON (.-data evt)))
                       (catch :default _ nil))]
    (let [path-arr (get data "path")
          value    (get data "value")
          path-str (str/join "/" path-arr)]
      (if (= path-str "bpm")
        (reset! bpm-state value)
        (swap! ctrl-state update path-str
               (fn [existing]
                 (assoc (or existing {:type "data" :meta {} :path-arr path-arr})
                        :value    value
                        :path-arr path-arr))))
      (swap! log-entries
             (fn [log]
               (vec (take max-log (conj log {:path path-str :value value}))))))))

(declare connect!)

(defn connect! []
  (when-let [old @ws-ref] (.close old))
  (reset! ws-status :connecting)
  (let [ws (js/WebSocket. (ws-url))]
    (set! (.-onopen ws)    (fn [_] (reset! ws-status :open)))
    (set! (.-onclose ws)   (fn [_]
                              (reset! ws-status :closed)
                              (reset! ws-ref nil)
                              (js/setTimeout connect! 3000)))
    (set! (.-onerror ws)   (fn [_] (reset! ws-status :closed)))
    (set! (.-onmessage ws) handle-message!)
    (reset! ws-ref ws)))

;; ---------------------------------------------------------------------------
;; REST fetches
;; ---------------------------------------------------------------------------

(defn- fetch-ctrl! []
  (-> (js/fetch "/ctrl")
      (.then #(.json %))
      (.then (fn [data]
               (doseq [node (js->clj data)]
                 (let [path-arr (get node "path")
                       path-str (str/join "/" path-arr)
                       value    (get node "value")
                       type-s   (get node "type" "data")
                       meta     (get node "meta" {})]
                   (if (= path-str "bpm")
                     (reset! bpm-state value)
                     (swap! ctrl-state assoc path-str
                            {:value    value
                             :type     type-s
                             :meta     meta
                             :path-arr path-arr}))))))))

(defn- fetch-loops! []
  (-> (js/fetch "/loops")
      (.then #(.json %))
      (.then (fn [data]
               (reset! loops-state (js->clj data))))))

(defn- set-bpm! [bpm]
  (-> (js/fetch "/bpm"
                #js {:method  "PUT"
                     :headers #js {"Content-Type" "application/json"}
                     :body    (js/JSON.stringify #js {"bpm" bpm})})
      (.then #(.json %))
      (.then (fn [_] (reset! bpm-state (double bpm))))))

;; ---------------------------------------------------------------------------
;; Grouping helpers
;; ---------------------------------------------------------------------------

(defn- group-key [path-str]
  (let [slash (.indexOf path-str "/")]
    (if (neg? slash) "—" (.substring path-str 0 slash))))

(defn- group-nodes
  "Partition ctrl-state entries into a sorted map of group → [[path node] ...]."
  [ctrl-map]
  (let [grouped (group-by (fn [[ps _]] (group-key ps)) ctrl-map)]
    (into (sorted-map) grouped)))

;; ---------------------------------------------------------------------------
;; UI components
;; ---------------------------------------------------------------------------

(defn- connection-badge []
  (let [s @ws-status]
    [:span.badge {:class (name s)}
     (case s :open "LIVE" :connecting "..." :closed "OFFLINE")]))

(defn- bpm-display
  "BPM widget with +/− nudge and click-to-edit. Local state via form-2."
  []
  (let [editing? (r/atom false)
        draft    (r/atom nil)]
    (fn []
      [:div.bpm-display
       (if @editing?
         [:<>
          [:input.bpm-input
           {:type        "number"
            :value       (or @draft @bpm-state 120)
            :auto-focus  true
            :on-change   #(reset! draft (js/parseInt (.. % -target -value)))
            :on-key-down (fn [e]
                           (case (.-key e)
                             "Enter"  (do (when-let [b @draft] (set-bpm! b))
                                          (reset! editing? false)
                                          (reset! draft nil))
                             "Escape" (do (reset! editing? false)
                                          (reset! draft nil))
                             nil))
            :on-blur     #(reset! editing? false)}]
          [:span.bpm-label "BPM"]]
         [:<>
          [:button.bpm-nudge {:on-click #(set-bpm! (dec (or @bpm-state 120)))} "−"]
          [:span.bpm-value
           {:on-click #(do (reset! draft @bpm-state) (reset! editing? true))
            :title    "Click to edit BPM"}
           (or @bpm-state "—")]
          [:button.bpm-nudge {:on-click #(set-bpm! (inc (or @bpm-state 120)))} "+"]
          [:span.bpm-label "BPM"]])])))

(defn- ctrl-row [path-str {:keys [value type meta]}]
  (let [[lo hi] (get meta "range" (if (= type "float") [0.0 1.0] [0 127]))
        step     (if (= type "float") 0.001 1)
        vals     (get meta "values")]
    [:tr
     [:td.path (let [slash (.indexOf path-str "/")]
                 (if (neg? slash) path-str (.substring path-str (inc slash))))]
     [:td.ctrl
      (cond
        (or (= type "float") (= type "int"))
        [:div.slider-row
         [:input {:type     "range"
                  :min      lo :max hi :step step
                  :value    (or value lo)
                  :on-input (fn [e]
                               (let [raw (js/parseFloat (.. e -target -value))
                                     v   (if (= type "int") (int raw) raw)]
                                 (send-ctrl! path-str v)))}]
         [:span.slider-value
          (if (= type "float")
            (.toFixed (js/Number (or value 0)) 3)
            (str (or value 0)))]]

        (= type "bool")
        [:button.toggle {:class    (if value "on" "off")
                         :on-click #(send-ctrl! path-str (not value))}
         (if value "ON" "OFF")]

        (and (= type "enum") (seq vals))
        [:div.enum-group
         (for [v vals]
           ^{:key v}
           [:button.enum-btn {:class    (when (= v (str value)) "active")
                              :on-click #(send-ctrl! path-str v)}
            v])]

        :else
        [:span.value-readonly (pr-str value)])]]))

(defn- ctrl-group [group-name entries]
  (let [collapsed? (contains? @collapsed-groups group-name)]
    [:div.group
     [:div.group-header
      {:on-click #(swap! collapsed-groups
                         (if collapsed? disj conj)
                         group-name)}
      [:span.group-chevron (if collapsed? "▸" "▾")]
      [:span.group-name group-name]
      [:span.group-count (str "(" (count entries) ")")]]
     (when-not collapsed?
       [:table
        [:tbody
         (for [[path-str node] entries]
           ^{:key path-str}
           [ctrl-row path-str node])]])]))

(defn- ctrl-panel []
  (let [groups (group-nodes @ctrl-state)]
    [:div.ctrl-tree
     [:h3 "ctrl tree"]
     [:div.scroll
      (for [[group-name entries] groups]
        ^{:key group-name}
        [ctrl-group group-name entries])]]))

(defn- loop-monitor []
  (let [loops @loops-state]
    [:div.loop-monitor
     [:h3 (str "loops (" (count loops) ")")]
     [:div.loop-list
      (if (empty? loops)
        [:div.loop-empty "no loops running"]
        (for [lp loops]
          (let [lname    (get lp "name")
                running? (get lp "running?")
                ticks    (get lp "ticks" 0)]
            ^{:key lname}
            [:div.loop-entry {:class (if running? "running" "stopped")}
             [:span.loop-dot {:class (if running? "running" "stopped")}]
             [:span.loop-name lname]
             [:span.loop-ticks (str ticks)]])))]]))

(defn- log-panel []
  (let [entries @log-entries]
    [:div.log
     [:h3 (str "changes (" (count entries) ")")]
     [:div.scroll
      (for [{:keys [path value]} entries]
        ^{:key (str path value (js/Date.now))}
        [:div.log-entry
         [:span.path path]
         [:span.value (pr-str value)]])]]))

(defn- app []
  [:div.container
   [:header
    [:h1 "cljseq"]
    [connection-badge]
    [bpm-display]]
   [:div.panels
    [ctrl-panel]
    [:div.right-panel
     [loop-monitor]
     [log-panel]]]])

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defonce ^:private loop-poll-handle (atom nil))

(defn init! []
  (fetch-ctrl!)
  (connect!)
  (fetch-loops!)
  (when-let [h @loop-poll-handle] (js/clearInterval h))
  (reset! loop-poll-handle (js/setInterval fetch-loops! 2000)))
