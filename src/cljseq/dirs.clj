; SPDX-License-Identifier: EPL-2.0
(ns cljseq.dirs
  "Platform-appropriate directory resolution for cljseq.

  Provides the canonical locations for user data, config, and cache under a
  pluggable layout abstraction. The active layout can be changed at runtime via
  `set-layout!`, making it straightforward to add new layout strategies (e.g.
  macOS native ~/Library conventions) in a future sprint without modifying call
  sites.

  ## Active layout

  The default layout follows the XDG Base Directory Specification — the de
  facto standard for developer tools on Linux and macOS:

    ~/.local/share/cljseq/   — user data   (learned device maps, saved sessions)
    ~/.config/cljseq/        — user config (preferences, port assignments)
    ~/.cache/cljseq/         — cache       (m21 corpus, derived artefacts)

  XDG environment variables are respected: XDG_DATA_HOME, XDG_CONFIG_HOME,
  XDG_CACHE_HOME.

  ## Environment variable overrides

  These take priority over the active layout and are useful for CI, containers,
  and non-standard setups:

    CLJSEQ_DATA_DIR    — overrides (user-data-dir)
    CLJSEQ_CONFIG_DIR  — overrides (user-config-dir)
    CLJSEQ_CACHE_DIR   — overrides (user-cache-dir)
    CLJSEQ_LAYOUT      — selects layout at startup: 'xdg' (default) or
                         'macos-native' (reserved, not yet implemented)

  ## macOS native layout (future sprint)

  When implemented, the :macos-native layout will follow Apple HIG conventions:

    ~/Library/Application Support/cljseq/   — data + config
    ~/Library/Caches/cljseq/                — cache

  The `macos-native-layout` constructor is already defined (stubbed) so that
  `set-layout! :macos-native` becomes a one-line activation in that sprint.

  ## Resource search order for device maps

  `(resolve-device-resource name)` searches:
    1. (devices-dir)              — user-created / learned maps (writable)
    2. Classpath resources/devices/ — built-in maps (read-only, bundled)

  User maps shadow built-in maps of the same filename.

  ## Subdirectory helpers

  All subdirectory helpers call mkdirs on first access so callers never need to
  check for directory existence:

    (devices-dir)   — (user-data-dir)/devices
    (corpora-dir)   — (user-data-dir)/corpora
    (sessions-dir)  — (user-data-dir)/sessions

  Key design decisions: XDG Base Directory Specification (freedesktop.org),
  macOS HIG (future), R&R §32 (directory layout)."
  (:require [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- home [] (System/getProperty "user.home"))

(defn- read-env
  "Read an environment variable. Extracted into a function so tests can
  redirect it via with-redefs without touching System/getenv directly."
  [k]
  (System/getenv k))

(defn- ensure-dir
  "Create `path` and all parent directories if they do not exist.
  Returns `path` as a string."
  ^String [^String path]
  (.mkdirs (io/file path))
  path)

;; ---------------------------------------------------------------------------
;; Layout maps
;;
;; A layout is a plain map:
;;   {:id      keyword
;;    :data    (fn [] path-string)
;;    :config  (fn [] path-string)
;;    :cache   (fn [] path-string)}
;;
;; Using plain maps (rather than a protocol) keeps the abstraction light and
;; testable: tests can inject a custom layout map without any protocol machinery.
;; ---------------------------------------------------------------------------

(def app-name "cljseq")

(defn xdg-layout
  "XDG Base Directory Specification layout (default).
  Respects XDG_DATA_HOME, XDG_CONFIG_HOME, XDG_CACHE_HOME."
  []
  {:id     :xdg
   :data   (fn [] (str (or (read-env "XDG_DATA_HOME")
                           (str (home) "/.local/share"))
                       "/" app-name))
   :config (fn [] (str (or (read-env "XDG_CONFIG_HOME")
                           (str (home) "/.config"))
                       "/" app-name))
   :cache  (fn [] (str (or (read-env "XDG_CACHE_HOME")
                           (str (home) "/.cache"))
                       "/" app-name))})

(defn macos-native-layout
  "macOS native layout using ~/Library conventions (reserved for future sprint).

  When activated the layout will use:
    ~/Library/Application Support/cljseq/   — data and config
    ~/Library/Caches/cljseq/                — cache

  Currently produces the same paths as xdg-layout. Activate with:
    (set-layout! :macos-native)"
  []
  {:id     :macos-native
   :data   (fn [] (str (home) "/Library/Application Support/" app-name))
   :config (fn [] (str (home) "/Library/Application Support/" app-name))
   :cache  (fn [] (str (home) "/Library/Caches/" app-name))})

(defn- layout-for-env
  "Select the default layout based on the CLJSEQ_LAYOUT environment variable."
  []
  (case (read-env "CLJSEQ_LAYOUT")
    "macos-native" (macos-native-layout)
    (xdg-layout)))

(defonce ^:private active-layout (atom (layout-for-env)))

;; ---------------------------------------------------------------------------
;; Layout management
;; ---------------------------------------------------------------------------

(defn set-layout!
  "Change the active directory layout.

  `layout` — :xdg               XDG Base Directory Specification (default)
             :macos-native       macOS ~/Library conventions (future sprint)
             a layout map        from xdg-layout / macos-native-layout, or
                                 a user-supplied map with :id/:data/:config/:cache

  Returns the layout :id keyword.

  Example:
    (dirs/set-layout! :xdg)
    (dirs/set-layout! :macos-native)
    (dirs/set-layout! (assoc (dirs/xdg-layout) :data (fn [] \"/tmp/test\")))"
  [layout]
  (let [l (case layout
            :xdg          (xdg-layout)
            :macos-native (macos-native-layout)
            layout)]
    (reset! active-layout l)
    (:id l)))

(defn active-layout-id
  "Return the keyword identifying the currently active layout."
  []
  (:id @active-layout))

;; ---------------------------------------------------------------------------
;; Core directory resolution
;; CLJSEQ_*_DIR environment variables always take priority over the layout.
;; ---------------------------------------------------------------------------

(defn user-data-dir
  "Root user data directory. Override with CLJSEQ_DATA_DIR.

  Default (XDG): ~/.local/share/cljseq
  Default (macOS native): ~/Library/Application Support/cljseq"
  ^String []
  (or (read-env "CLJSEQ_DATA_DIR")
      ((:data @active-layout))))

(defn user-config-dir
  "Root user config directory. Override with CLJSEQ_CONFIG_DIR.

  Default (XDG): ~/.config/cljseq
  Default (macOS native): ~/Library/Application Support/cljseq"
  ^String []
  (or (read-env "CLJSEQ_CONFIG_DIR")
      ((:config @active-layout))))

(defn user-cache-dir
  "Root user cache directory. Override with CLJSEQ_CACHE_DIR.

  Default (XDG): ~/.cache/cljseq
  Default (macOS native): ~/Library/Caches/cljseq"
  ^String []
  (or (read-env "CLJSEQ_CACHE_DIR")
      ((:cache @active-layout))))

;; ---------------------------------------------------------------------------
;; Subdirectory helpers — created on first access
;; ---------------------------------------------------------------------------

(defn devices-dir
  "Directory for user-created and learned device maps.
  Searched before classpath resources by resolve-device-resource.
  Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/devices")))

(defn corpora-dir
  "Directory for user-imported corpora (m21 cache, etc.).
  Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/corpora")))

(defn sessions-dir
  "Directory for saved session state and conductor scripts.
  Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/sessions")))

;; ---------------------------------------------------------------------------
;; Device resource resolution
;;
;; Search order:
;;   1. (devices-dir)/<name>  — user-created / learned (writable)
;;   2. classpath resources/devices/<name>  — built-in (read-only)
;;
;; Returns a java.net.URL or nil.
;; ---------------------------------------------------------------------------

(defn scales-dir
  "Directory for Scala (.scl) and keyboard mapping (.kbm) microtonal scale files.
  Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/scales")))

(defn resolve-scala-resource
  "Resolve a Scala scale filename to a URL, searching user scales before classpath.

  Search order:
    1. (scales-dir)/<name>               — user scales (writable)
    2. classpath resources/scales/<name> — built-in bundled scales (read-only)

  Returns a java.net.URL if found, nil if not found in either location.

  Example:
    (dirs/resolve-scala-resource \"31edo.scl\")
    (dirs/resolve-scala-resource \"pythagorean.scl\")"
  [name]
  (let [user-file (io/file (scales-dir) name)]
    (if (.exists user-file)
      (.toURL user-file)
      (io/resource (str "scales/" name)))))

(defn resolve-device-resource
  "Resolve a device map filename to a URL, searching user data before classpath.

  `name` — filename, e.g. \"hydrasynth-explorer.edn\" (no directory prefix)

  Returns a java.net.URL if found, nil if not found in either location.

  Search order:
    1. (devices-dir)/<name>         — user-created or learned map
    2. classpath resources/devices/<name>  — built-in map

  Example:
    (resolve-device-resource \"hydrasynth-explorer.edn\")
    (resolve-device-resource \"lm-drum.edn\")"
  [name]
  (let [user-file (io/file (devices-dir) name)]
    (if (.exists user-file)
      (.toURL user-file)
      (io/resource (str "devices/" name)))))

(defn topology-path
  "Canonical path for the user's studio topology file.

  Default: (user-config-dir)/topology.edn
  Override: CLJSEQ_TOPOLOGY environment variable.

  The file is not created automatically. Use cljseq.topology/load-topology!
  to load it; see doc/topology-example.edn for the schema and annotations."
  ^String []
  (or (read-env "CLJSEQ_TOPOLOGY")
      (str (user-config-dir) "/topology.edn")))
