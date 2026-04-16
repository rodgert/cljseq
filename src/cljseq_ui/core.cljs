; SPDX-License-Identifier: EPL-2.0
(ns cljseq-ui.core
  "cljseq browser control surface.

  Connects to the cljseq HTTP server via WebSocket (/ws) and
  displays live ctrl-tree state alongside a recent-changes log.
  Inbound ctrl writes from the browser are relayed via ctrl/set!
  on the server.

  Entry point: init! — called by shadow-cljs on load and hot-reload."
  (:require [reagent.core :as r]
            [reagent.dom  :as rdom]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; State atoms
;; ---------------------------------------------------------------------------

(defonce ctrl-state  (r/atom (sorted-map)))  ; path-str → value
(defonce bpm-state   (r/atom nil))
(defonce ws-status   (r/atom :connecting))   ; :connecting :open :closed
(defonce log-entries (r/atom []))             ; vec of {:path :value}, newest-first

(def ^:private max-log 60)

;; ---------------------------------------------------------------------------
;; WebSocket
;; ---------------------------------------------------------------------------

(defonce ^:private ws-ref (atom nil))

(defn- ws-url []
  (let [loc  js/window.location
        proto (if (= "https:" (.-protocol loc)) "wss:" "ws:")]
    (str proto "//" (.-host loc) "/ws")))

(defn- handle-message! [evt]
  (when-let [data (try (js->clj (.parse js/JSON (.-data evt)))
                       (catch :default _ nil))]
    (let [path  (get data "path")
          value (get data "value")
          path-str (str/join "/" path)]
      (if (= path-str "bpm")
        (reset! bpm-state value)
        (swap! ctrl-state assoc path-str value))
      (swap! log-entries
             (fn [log]
               (vec (take max-log (conj log {:path path-str :value value}))))))))

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
;; Initial ctrl-tree fetch (REST snapshot before WS catches up)
;; ---------------------------------------------------------------------------

(defn- fetch-ctrl! []
  (-> (js/fetch "/ctrl")
      (.then #(.json %))
      (.then (fn [data]
               (doseq [node (js->clj data)]
                 (let [path-str (str/join "/" (get node "path"))
                       value    (get node "value")]
                   (if (= path-str "bpm")
                     (reset! bpm-state value)
                     (swap! ctrl-state assoc path-str value))))))))

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

(defn- bpm-display []
  [:div.bpm-display
   [:span.bpm-value (or @bpm-state "—")]
   [:span.bpm-label "BPM"]])

(defn- ctrl-panel []
  (let [nodes @ctrl-state]
    [:div.ctrl-tree
     [:h3 "ctrl tree"]
     [:div.scroll
      [:table
       [:tbody
        (for [[path value] nodes]
          ^{:key path}
          [:tr
           [:td.path path]
           [:td.value (pr-str value)]])]]]]))

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
