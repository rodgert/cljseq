; SPDX-License-Identifier: EPL-2.0
(ns cljseq.dirs-test
  "Unit tests for cljseq.dirs — layout selection, path resolution,
  environment variable overrides, and device resource search order."
  (:require [clojure.java.io  :as io]
            [clojure.string   :as str]
            [clojure.test     :refer [deftest is testing use-fixtures]]
            [cljseq.dirs      :as dirs]))

;; ---------------------------------------------------------------------------
;; Fixture — restore the active layout after each test
;; ---------------------------------------------------------------------------

(defn restore-layout [f]
  (let [original-id (dirs/active-layout-id)
        original    (case original-id
                      :xdg         (dirs/xdg-layout)
                      :macos-native (dirs/macos-native-layout)
                      (dirs/xdg-layout))]
    (try (f)
         (finally (dirs/set-layout! original)))))

(use-fixtures :each restore-layout)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- home [] (System/getProperty "user.home"))

(defn- test-layout
  "Return a layout map that uses a fixed temp-style prefix for all dirs.
  Avoids touching the real user home in path assertions."
  [prefix]
  {:id     :test
   :data   (constantly (str prefix "/data"))
   :config (constantly (str prefix "/config"))
   :cache  (constantly (str prefix "/cache"))})

;; ---------------------------------------------------------------------------
;; Layout constructors
;; ---------------------------------------------------------------------------

(deftest xdg-layout-id-test
  (testing "xdg-layout has :id :xdg"
    (is (= :xdg (:id (dirs/xdg-layout))))))

(deftest macos-native-layout-id-test
  (testing "macos-native-layout has :id :macos-native"
    (is (= :macos-native (:id (dirs/macos-native-layout))))))

(deftest xdg-layout-data-dir-default-test
  (testing "xdg-layout data dir uses ~/.local/share when XDG_DATA_HOME unset"
    (with-redefs [cljseq.dirs/read-env (fn [k] (when (= k "CLJSEQ_LAYOUT") nil))]
      (let [layout (dirs/xdg-layout)
            result ((:data layout))]
        (is (str/starts-with? result (str (home) "/.local/share")))
        (is (str/ends-with? result "/cljseq"))))))

(deftest xdg-layout-respects-xdg-data-home-test
  (testing "xdg-layout uses XDG_DATA_HOME when set"
    (with-redefs [cljseq.dirs/read-env (fn [k]
                                         (case k
                                           "XDG_DATA_HOME" "/custom/data"
                                           nil))]
      (let [layout (dirs/xdg-layout)]
        (is (= "/custom/data/cljseq" ((:data layout))))))))

(deftest xdg-layout-config-dir-test
  (testing "xdg-layout config dir uses ~/.config by default"
    (with-redefs [cljseq.dirs/read-env (constantly nil)]
      (let [layout (dirs/xdg-layout)]
        (is (str/starts-with? ((:config layout)) (str (home) "/.config")))))))

(deftest xdg-layout-cache-dir-test
  (testing "xdg-layout cache dir uses ~/.cache by default"
    (with-redefs [cljseq.dirs/read-env (constantly nil)]
      (let [layout (dirs/xdg-layout)]
        (is (str/starts-with? ((:cache layout)) (str (home) "/.cache")))))))

(deftest macos-native-layout-paths-test
  (testing "macos-native-layout data dir uses ~/Library/Application Support"
    (let [layout (dirs/macos-native-layout)]
      (is (str/includes? ((:data layout)) "Library/Application Support/cljseq"))))
  (testing "macos-native-layout cache dir uses ~/Library/Caches"
    (let [layout (dirs/macos-native-layout)]
      (is (str/includes? ((:cache layout)) "Library/Caches/cljseq")))))

;; ---------------------------------------------------------------------------
;; set-layout! / active-layout-id
;; ---------------------------------------------------------------------------

(deftest set-layout-keyword-xdg-test
  (testing "set-layout! :xdg switches to XDG layout"
    (dirs/set-layout! :xdg)
    (is (= :xdg (dirs/active-layout-id)))))

(deftest set-layout-keyword-macos-native-test
  (testing "set-layout! :macos-native switches to macOS native layout"
    (dirs/set-layout! :macos-native)
    (is (= :macos-native (dirs/active-layout-id)))))

(deftest set-layout-returns-id-test
  (testing "set-layout! returns the layout :id"
    (is (= :xdg (dirs/set-layout! :xdg)))
    (is (= :macos-native (dirs/set-layout! :macos-native)))))

(deftest set-layout-custom-map-test
  (testing "set-layout! accepts a custom layout map"
    (dirs/set-layout! (test-layout "/tmp/cljseq-test"))
    (is (= :test (dirs/active-layout-id)))))

;; ---------------------------------------------------------------------------
;; user-data-dir / user-config-dir / user-cache-dir
;; ---------------------------------------------------------------------------

(deftest user-data-dir-uses-layout-test
  (testing "user-data-dir returns layout data path"
    (dirs/set-layout! (test-layout "/tmp/test-data"))
    (with-redefs [cljseq.dirs/read-env (constantly nil)]
      (is (= "/tmp/test-data/data" (dirs/user-data-dir))))))

(deftest user-data-dir-env-override-test
  (testing "CLJSEQ_DATA_DIR overrides the active layout"
    (dirs/set-layout! (test-layout "/tmp/test-data"))
    (with-redefs [cljseq.dirs/read-env (fn [k]
                                         (when (= k "CLJSEQ_DATA_DIR")
                                           "/override/data"))]
      (is (= "/override/data" (dirs/user-data-dir))))))

(deftest user-config-dir-env-override-test
  (testing "CLJSEQ_CONFIG_DIR overrides the active layout"
    (dirs/set-layout! (test-layout "/tmp/test-cfg"))
    (with-redefs [cljseq.dirs/read-env (fn [k]
                                         (when (= k "CLJSEQ_CONFIG_DIR")
                                           "/override/config"))]
      (is (= "/override/config" (dirs/user-config-dir))))))

(deftest user-cache-dir-env-override-test
  (testing "CLJSEQ_CACHE_DIR overrides the active layout"
    (dirs/set-layout! (test-layout "/tmp/test-cache"))
    (with-redefs [cljseq.dirs/read-env (fn [k]
                                         (when (= k "CLJSEQ_CACHE_DIR")
                                           "/override/cache"))]
      (is (= "/override/cache" (dirs/user-cache-dir))))))

(deftest env-overrides-take-priority-over-xdg-vars-test
  (testing "CLJSEQ_DATA_DIR takes priority over XDG_DATA_HOME"
    (dirs/set-layout! :xdg)
    (with-redefs [cljseq.dirs/read-env (fn [k]
                                         (case k
                                           "CLJSEQ_DATA_DIR" "/cljseq-override"
                                           "XDG_DATA_HOME"   "/xdg-home"
                                           nil))]
      (is (= "/cljseq-override" (dirs/user-data-dir))))))

;; ---------------------------------------------------------------------------
;; Subdirectory helpers — structure checks (avoid real filesystem writes)
;; ---------------------------------------------------------------------------

(deftest devices-dir-under-data-dir-test
  (testing "devices-dir is a subdirectory of user-data-dir"
    (dirs/set-layout! (test-layout (System/getProperty "java.io.tmpdir")))
    (with-redefs [cljseq.dirs/read-env (constantly nil)]
      (let [data    (dirs/user-data-dir)
            devices (dirs/devices-dir)]
        (is (str/starts-with? devices data))
        (is (str/ends-with? devices "/devices"))))))

(deftest corpora-dir-under-data-dir-test
  (testing "corpora-dir is a subdirectory of user-data-dir"
    (dirs/set-layout! (test-layout (System/getProperty "java.io.tmpdir")))
    (with-redefs [cljseq.dirs/read-env (constantly nil)]
      (let [data    (dirs/user-data-dir)
            corpora (dirs/corpora-dir)]
        (is (str/starts-with? corpora data))
        (is (str/ends-with? corpora "/corpora"))))))

(deftest sessions-dir-under-data-dir-test
  (testing "sessions-dir is a subdirectory of user-data-dir"
    (dirs/set-layout! (test-layout (System/getProperty "java.io.tmpdir")))
    (with-redefs [cljseq.dirs/read-env (constantly nil)]
      (let [data     (dirs/user-data-dir)
            sessions (dirs/sessions-dir)]
        (is (str/starts-with? sessions data))
        (is (str/ends-with? sessions "/sessions"))))))

;; ---------------------------------------------------------------------------
;; resolve-device-resource — search order
;; ---------------------------------------------------------------------------

(deftest resolve-device-classpath-test
  (testing "resolve-device-resource finds built-in device maps on classpath"
    ;; hydrasynth-explorer.edn ships with the product — must always be found
    (let [url (dirs/resolve-device-resource "hydrasynth-explorer.edn")]
      (is (some? url) "hydrasynth-explorer.edn found on classpath")
      (is (instance? java.net.URL url)))))

(deftest resolve-device-unknown-returns-nil-test
  (testing "resolve-device-resource returns nil for unknown device"
    (let [url (dirs/resolve-device-resource "no-such-device-xyz.edn")]
      (is (nil? url)))))

(deftest resolve-device-user-shadows-classpath-test
  (testing "user devices-dir shadows classpath for same filename"
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/cljseq-dirs-test-" (System/currentTimeMillis))
          tmp-file (io/file tmp-dir "hydrasynth-explorer.edn")]
      (try
        (.mkdirs (io/file tmp-dir))
        (spit tmp-file ";; user override\n{:device/id :test}")
        ;; Point devices-dir to our temp dir
        (with-redefs [cljseq.dirs/devices-dir (constantly tmp-dir)]
          (let [url (dirs/resolve-device-resource "hydrasynth-explorer.edn")]
            (is (some? url))
            ;; The resolved URL should serve our override content, not the bundled map
            (is (str/includes? (slurp url) "user override")
                "user file content returned, not classpath bundled map")))
        (finally
          (.delete tmp-file)
          (.delete (io/file tmp-dir)))))))
