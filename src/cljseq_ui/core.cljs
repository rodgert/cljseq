; SPDX-License-Identifier: EPL-2.0
(ns cljseq-ui.core
  "cljseq browser control surface.

  Connects to the cljseq HTTP server via WebSocket (/ws) and presents a live,
  interactive view of the ctrl tree. Float/int nodes render as sliders; bool
  nodes as toggles; enum nodes as button groups; opaque :data nodes as
  read-only text. All writes are sent back to the server via WebSocket.

  BPM is displayed prominently in the header with +/− nudge buttons and
  click-to-edit support (Enter to commit, Escape or blur to cancel).

  Entry point: init! — called by shadow-cljs on load and hot-reload."
  (:require [reagent.core :as r]
            [reagent.dom  :as rdom]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; State atoms
;; ---------------------------------------------------------------------------

;; {path-str {:value v :type t :meta m :path-arr [...]}}
(defonce ctrl-state  (r/atom (sorted-map)))
(defonce bpm-state   (r/atom nil))
(defonce ws-status   (r/atom :connecting))   ; :connecting :open :closed
(defonce log-entries (r/atom []))             ; newest-first

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
  "Send a ctrl-tree write to the server via the open WebSocket.
  `path-str` must be a key in ctrl-state (so we can recover the original
  path array without re-splitting on '/' which breaks namespaced keywords)."
  [path-str value]
  (when-let [node (get @ctrl-state path-str)]
    (when-let [ws @ws-ref]
      (when (= 1 (.-readyState ws))   ; WebSocket.OPEN = 1
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
        ;; Keep existing type/meta if present; update value and path-arr.
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
  (when-let [old @ws-ref]
    (.close old))
  (reset! ws-status :connecting)
  (let [ws (js/WebSocket. (ws-url))]
    (set! (.-onopen ws)
          (fn [_] (reset! ws-status :open)))
    (set! (.-onclose ws)
          (fn [_]
            (reset! ws-status :closed)
            (reset! ws-ref nil)
            (js/setTimeout connect! 3000)))
    (set! (.-onerror ws)
          (fn [_] (reset! ws-status :closed)))
    (set! (.-onmessage ws) handle-message!)
    (reset! ws-ref ws)))

;; ---------------------------------------------------------------------------
;; Initial ctrl-tree fetch (REST snapshot populates type + meta)
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

;; ---------------------------------------------------------------------------
;; BPM REST write (bypasses ctrl-tree; hits core/set-bpm! directly)
;; ---------------------------------------------------------------------------

(defn- set-bpm! [bpm]
  (-> (js/fetch "/bpm"
                #js {:method  "PUT"
                     :headers #js {"Content-Type" "application/json"}
                     :body    (js/JSON.stringify #js {"bpm" bpm})})
      (.then #(.json %))
      (.then (fn [_] (reset! bpm-state (double bpm))))))

;; ---------------------------------------------------------------------------
;; UI components
;; ---------------------------------------------------------------------------

(defn- connection-badge []
  (let [s @ws-status]
    [:span.badge {:class (name s)}
     (case s
       :open       "LIVE"
       :connecting "..."
       :closed     "OFFLINE")]))

(defn- bpm-display
  "BPM header widget with +/− nudge and click-to-edit.
  Form-2 component so editing state is local."
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
          [:button.bpm-nudge
           {:on-click #(set-bpm! (dec (or @bpm-state 120)))} "−"]
          [:span.bpm-value
           {:on-click #(do (reset! draft @bpm-state)
                           (reset! editing? true))
            :title    "Click to edit BPM"}
           (or @bpm-state "—")]
          [:button.bpm-nudge
           {:on-click #(set-bpm! (inc (or @bpm-state 120)))} "+"]
          [:span.bpm-label "BPM"]])])))

(defn- ctrl-row
  "Render one ctrl-tree row as a typed interactive control."
  [path-str {:keys [value type meta]}]
  (let [[lo hi] (get meta "range" (if (= type "float") [0.0 1.0] [0 127]))
        step     (if (= type "float") 0.001 1)
        vals     (get meta "values")]
    [:tr
     [:td.path path-str]
     [:td.ctrl
      (cond
        ;; Float or int — range slider
        (or (= type "float") (= type "int"))
        [:div.slider-row
         [:input
          {:type     "range"
           :min      lo
           :max      hi
           :step     step
           :value    (or value lo)
           :on-input (fn [e]
                       (let [raw (js/parseFloat (.. e -target -value))
                             v   (if (= type "int") (int raw) raw)]
                         (send-ctrl! path-str v)))}]
         [:span.slider-value
          (if (= type "float")
            (.toFixed (js/Number (or value 0)) 3)
            (str (or value 0)))]]

        ;; Bool — toggle button
        (= type "bool")
        [:button.toggle
         {:class    (if value "on" "off")
          :on-click #(send-ctrl! path-str (not value))}
         (if value "ON" "OFF")]

        ;; Enum — button group
        (and (= type "enum") (seq vals))
        [:div.enum-group
         (for [v vals]
           ^{:key v}
           [:button.enum-btn
            {:class    (when (= v (str value)) "active")
             :on-click #(send-ctrl! path-str v)}
            v])]

        ;; Opaque :data or unknown — read-only
        :else
        [:span.value-readonly (pr-str value)])]]))

(defn- ctrl-panel []
  (let [nodes @ctrl-state]
    [:div.ctrl-tree
     [:h3 "ctrl tree"]
     [:div.scroll
      [:table
       [:tbody
        (for [[path-str node] nodes]
          ^{:key path-str}
          [ctrl-row path-str node])]]]]))

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
    [log-panel]]])

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn init! []
  (fetch-ctrl!)
  (connect!)
  (rdom/render [app] (.getElementById js/document "app")))
