; SPDX-License-Identifier: EPL-2.0
(ns cljseq.freesound
  "Freesound API integration — fetch-on-use sample discovery and caching.

  Provides a thin client over the Freesound API v2 for sample discovery,
  download, and integration with the cljseq.sample buffer registry.

  ## Setup

  Register a Freesound API client ID (free account at freesound.org):

    (freesound/set-api-key! \"your-api-key-here\")

  Or set the FREESOUND_API_KEY environment variable before starting cljseq.

  ## Fetching individual samples

    ;; Download sound ID 12345 and register it as :my/kick
    (freesound/fetch-sample! 12345 :my/kick)
    (smp/sample! :my/kick)

  ## Loading the essentials catalog

    ;; Fetch all catalog entries (skips nil-ID placeholders with a warning).
    ;; Downloaded files are cached at (dirs/user-cache-dir)/samples/.
    ;; Subsequent calls use the cache — no network request if already downloaded.
    (freesound/load-essentials!)

    ;; Then play any catalog sample by keyword:
    (smp/sample! :bd/fat)
    (smp/sample! :ambi/drone :rate 0.95)

  ## Searching Freesound

    ;; Returns a seq of {:id int :name str :license str :tags [...] :url str}
    (freesound/search-freesound \"acoustic kick drum\" {:filter \"license:\"Creative Commons 0\"\"})

    ;; Combine search + fetch:
    (let [results (freesound/search-freesound \"tabla na\" {:num-results 5})]
      (doseq [{:keys [id]} (take 3 results)]
        (println id (:name (freesound/sound-info id)))))

  ## Cache layout

    ~/.cache/cljseq/samples/{id}.{ext}   — downloaded sample files
    ~/.cache/cljseq/samples/meta/        — cached API metadata (JSON)

  ## Essentials catalog

  The bundled catalog at resources/samples/essentials.edn mirrors the
  category vocabulary of Sonic-Pi's built-in sample set. Each entry maps
  a `:category/name` keyword to a Freesound sound ID and metadata.
  Entries with `:freesound/id nil` are unfilled placeholders — use
  `search-freesound` to discover suitable replacements and update the catalog.

  Key design decisions: R&R §26 Sample Player."
  (:require [clojure.java.io :as io]
            [clojure.edn     :as edn]
            [clojure.data.json :as json]
            [cljseq.dirs     :as dirs]
            [cljseq.sample   :as smp])
  (:import [java.net HttpURLConnection URL]
           [java.io  File]))

;; ---------------------------------------------------------------------------
;; API key
;; ---------------------------------------------------------------------------

(defonce ^:private api-key-atom
  (atom (System/getenv "FREESOUND_API_KEY")))

(defn set-api-key!
  "Set the Freesound API client key.
  Overrides the FREESOUND_API_KEY environment variable.

  Get a free key at https://freesound.org/apiv2/apply/"
  [key]
  (reset! api-key-atom key))

(defn- api-key []
  (or @api-key-atom
      (throw (ex-info "Freesound API key not set. Call (freesound/set-api-key! \"...\") or set FREESOUND_API_KEY."
                      {}))))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn- http-get
  "Perform an HTTP GET to `url-str` with an Authorization: Token header.
  Returns {:status int :body string} or throws on connection failure."
  [url-str]
  (let [conn (doto ^HttpURLConnection (.openConnection (URL. url-str))
               (.setRequestMethod "GET")
               (.setRequestProperty "Authorization" (str "Token " (api-key)))
               (.setConnectTimeout 10000)
               (.setReadTimeout    30000)
               (.setInstanceFollowRedirects true))]
    (.connect conn)
    (let [status (.getResponseCode conn)
          stream (if (< status 400)
                   (.getInputStream conn)
                   (.getErrorStream conn))
          body   (slurp stream)]
      (.disconnect conn)
      {:status status :body body})))

(defn- http-download!
  "Download `url-str` to `dest-file`. Follows redirects.
  Sends Authorization header for Freesound download endpoints.
  Returns dest-file."
  [url-str ^File dest-file]
  (let [conn (doto ^HttpURLConnection (.openConnection (URL. url-str))
               (.setRequestMethod "GET")
               (.setRequestProperty "Authorization" (str "Token " (api-key)))
               (.setConnectTimeout 10000)
               (.setReadTimeout    60000)
               (.setInstanceFollowRedirects true))]
    (.connect conn)
    (let [status (.getResponseCode conn)]
      (when-not (< status 400)
        (.disconnect conn)
        (throw (ex-info "Freesound download failed"
                        {:status status :url url-str})))
      (with-open [in  (.getInputStream conn)
                  out (io/output-stream dest-file)]
        (io/copy in out))
      (.disconnect conn)
      dest-file)))

;; ---------------------------------------------------------------------------
;; Cache directory
;; ---------------------------------------------------------------------------

(defn- samples-cache-dir ^File []
  (let [d (File. (str (dirs/user-cache-dir) "/samples"))]
    (.mkdirs d)
    d))

(defn- meta-cache-dir ^File []
  (let [d (File. (str (dirs/user-cache-dir) "/samples/meta"))]
    (.mkdirs d)
    d))

(defn- cached-path
  "Return the expected File for a cached Freesound download by ID and extension."
  ^File [id ext]
  (File. (samples-cache-dir) (str id "." (name ext))))

(defn- meta-path ^File [id]
  (File. (meta-cache-dir) (str id ".json")))

;; ---------------------------------------------------------------------------
;; Freesound API calls
;; ---------------------------------------------------------------------------

(defn sound-info
  "Fetch metadata for a Freesound sound by integer ID.
  Returns a map with :id :name :license :tags :download :previews etc.
  Caches the JSON response locally to avoid repeat API calls."
  [id]
  (let [cache (meta-path id)]
    (if (.exists cache)
      (json/read-str (slurp cache) :key-fn keyword)
      (let [{:keys [status body]} (http-get (str "https://freesound.org/apiv2/sounds/" id "/"))
            _   (when-not (= 200 status)
                  (throw (ex-info "sound-info: API error"
                                  {:id id :status status :body body})))
            m   (json/read-str body :key-fn keyword)]
        (spit cache body)
        m))))

(defn search-freesound
  "Search Freesound and return a seq of result maps.

  `query`   — text search string
  `opts`    — optional map:
    :filter       — Freesound filter string (e.g. \"license:\\\"Creative Commons 0\\\"\")
    :num-results  — max results to return (default 15)
    :sort         — sort order: \"score\" \"downloads_desc\" \"created_desc\" (default \"score\")

  Returns seq of {:id int :name str :license str :tags [...] :username str}

  Example:
    (search-freesound \"kick drum\" {:filter \"license:\\\"Creative Commons 0\\\"\"})
    (search-freesound \"tabla\" {:num-results 5 :sort \"downloads_desc\"})"
  [query & [{:keys [filter num-results sort]
             :or   {num-results 15 sort "score"}}]]
  (let [params (cond-> (str "query=" (java.net.URLEncoder/encode query "UTF-8")
                             "&sort=" sort
                             "&page_size=" num-results
                             "&fields=id,name,license,tags,username")
                 filter (str "&filter=" (java.net.URLEncoder/encode filter "UTF-8")))
        {:keys [status body]} (http-get (str "https://freesound.org/apiv2/search/text/?" params))]
    (when-not (= 200 status)
      (throw (ex-info "search-freesound: API error" {:status status :body body})))
    (let [data (json/read-str body :key-fn keyword)]
      (mapv (fn [r] {:id       (:id r)
                     :name     (:name r)
                     :license  (:license r)
                     :tags     (:tags r)
                     :username (:username r)})
            (:results data)))))

;; ---------------------------------------------------------------------------
;; Fetch and register
;; ---------------------------------------------------------------------------

(defn fetch-sample!
  "Download a Freesound sound by ID and register it in the sample buffer registry.

  Downloads the original file (requires API key). The file is cached at
  (dirs/user-cache-dir)/samples/{id}.{ext} — subsequent calls return the cached file.

  `id`          — integer Freesound sound ID
  `buffer-name` — keyword to register the sample under (e.g. :my/kick)

  Returns `buffer-name` (file is registered but not loaded into SC — call
  `smp/load-sample!` to allocate an SC buffer).

  Example:
    (freesound/fetch-sample! 12345 :my/kick)
    (smp/load-sample! :my/kick)
    (smp/sample! :my/kick)"
  [id buffer-name]
  (let [info   (sound-info id)
        ext    (or (some-> (:type info) keyword) :wav)
        cached (cached-path id ext)]
    (when-not (.exists cached)
      (let [dl-url (:download info)]
        (when-not dl-url
          (throw (ex-info "fetch-sample!: no download URL in Freesound metadata"
                          {:id id :info info})))
        (http-download! dl-url cached)))
    (smp/defbuffer! buffer-name (.getAbsolutePath cached))
    buffer-name))

(defn fetch-and-load!
  "Fetch and immediately load a Freesound sample into SC.

  Combines `fetch-sample!` + `smp/load-sample!`. Requires a live SC connection.

  Returns the SC buffer ID.

  Example:
    (freesound/fetch-and-load! 12345 :my/kick)
    (smp/sample! :my/kick)"
  [id buffer-name]
  (fetch-sample! id buffer-name)
  (smp/load-sample! buffer-name))

;; ---------------------------------------------------------------------------
;; Essentials catalog
;; ---------------------------------------------------------------------------

(def ^:private essentials-catalog
  (delay
    (edn/read-string (slurp (io/resource "samples/essentials.edn")))))

(defn essentials
  "Return the full essentials catalog as a map of keyword → metadata."
  []
  @essentials-catalog)

(defn load-essentials!
  "Fetch and register all essentials catalog entries that have a Freesound ID.

  Entries with `:freesound/id nil` are logged and skipped.
  Already-cached files are not re-downloaded.

  Samples are registered in the buffer registry but NOT loaded into SC.
  Call `(smp/load-sample! kw)` on each sample before playing, or use
  `load-and-prime-essentials!` to load all into SC in one call (requires
  a live SC connection and sufficient buffer slots).

  Returns a map of {:loaded [...] :skipped [...] :errors [...]} keywords."
  []
  (let [cat @essentials-catalog
        results (reduce
                  (fn [acc [kw entry]]
                    (let [id (:freesound/id entry)]
                      (cond
                        (nil? id)
                        (do (println (str "[freesound] skip " kw " — no ID in catalog"))
                            (update acc :skipped conj kw))

                        :else
                        (try
                          (fetch-sample! id kw)
                          (println (str "[freesound] fetched " kw " (" id ")"))
                          (update acc :loaded conj kw)
                          (catch Exception e
                            (println (str "[freesound] error " kw " (" id "): " (.getMessage e)))
                            (update acc :errors conj kw))))))
                  {:loaded [] :skipped [] :errors []}
                  cat)]
    (println (str "[freesound] essentials: "
                  (count (:loaded results)) " loaded, "
                  (count (:skipped results)) " skipped (no ID), "
                  (count (:errors results)) " errors"))
    results))

(defn load-and-prime-essentials!
  "Fetch, register, AND load all essentials into SC buffers.

  Requires a live SC connection. Returns the same {:loaded :skipped :errors} map
  as load-essentials!, but :loaded entries are also loaded into SC and ready to
  play via smp/sample!."
  []
  (let [{:keys [loaded] :as results} (load-essentials!)]
    (doseq [kw loaded]
      (try
        (smp/load-sample! kw)
        (catch Exception e
          (println (str "[freesound] SC load error " kw ": " (.getMessage e))))))
    results))

(defn curate-essentials!
  "Search Freesound for each unfilled (nil-ID) catalog entry and print candidate IDs.

  For each nil-ID entry, performs a Freesound text search using the entry's :title
  and :desc fields, filtered to CC0 license. Prints the top 3 candidate IDs and names
  so you can pick the best match and fill in essentials.edn.

  Does not modify the catalog — this is a curation helper for filling in IDs."
  []
  (let [cat @essentials-catalog
        nil-entries (filter (fn [[_ v]] (nil? (:freesound/id v))) cat)]
    (println (str "Searching for " (count nil-entries) " unfilled entries..."))
    (doseq [[kw {:keys [title desc]}] nil-entries]
      (println (str "\n── " kw " — " title))
      (try
        (let [results (search-freesound (str title " " desc)
                                        {:filter "license:\"Creative Commons 0\""
                                         :num-results 3
                                         :sort "downloads_desc"})]
          (doseq [{:keys [id name username]} results]
            (println (str "  " id " — " name " (by " username ")"))))
        (catch Exception e
          (println (str "  search error: " (.getMessage e))))))))
