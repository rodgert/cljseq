; SPDX-License-Identifier: EPL-2.0
(ns cljseq.runtime-test
  (:require [clojure.test    :refer [deftest is testing use-fixtures]]
            [cljseq.runtime  :as runtime]))

;; ---------------------------------------------------------------------------
;; Fixture — reset runtime state between tests
;; ---------------------------------------------------------------------------

(defn with-clean-runtime [f]
  (reset! @#'runtime/state {})
  (reset! @#'runtime/global-watchers {})
  (f)
  (reset! @#'runtime/state {})
  (reset! @#'runtime/global-watchers {})
  ;; Remove any atom-level watchers added during the test
  (remove-watch @#'runtime/state ::test))

(use-fixtures :each with-clean-runtime)

;; ---------------------------------------------------------------------------
;; set! / get
;; ---------------------------------------------------------------------------

(deftest set-and-get-test
  (runtime/set! [:sc :status] :running)
  (is (= :running (runtime/get [:sc :status]))))

(deftest set-nested-path-test
  (runtime/set! [:sc :errors] [{:kind :stuck-note}])
  (is (= [{:kind :stuck-note}] (runtime/get [:sc :errors]))))

(deftest get-missing-path-returns-nil-test
  (is (nil? (runtime/get [:no :such :path]))))

;; ---------------------------------------------------------------------------
;; clear!
;; ---------------------------------------------------------------------------

(deftest clear-removes-key-test
  (runtime/set! [:sc :status] :running)
  (runtime/clear! [:sc :status])
  (is (nil? (runtime/get [:sc :status]))))

(deftest clear-does-not-disturb-siblings-test
  (runtime/set! [:sc :status] :running)
  (runtime/set! [:sc :sclang-pid] 1234)
  (runtime/clear! [:sc :status])
  (is (nil?   (runtime/get [:sc :status])))
  (is (= 1234 (runtime/get [:sc :sclang-pid]))))

;; ---------------------------------------------------------------------------
;; conj!
;; ---------------------------------------------------------------------------

(deftest conj-appends-to-ring-test
  (runtime/conj! [:sc :errors] {:kind :stuck-note})
  (runtime/conj! [:sc :errors] {:kind :timeout})
  (is (= 2 (count (runtime/get [:sc :errors]))))
  (is (= :stuck-note (:kind (first  (runtime/get [:sc :errors])))))
  (is (= :timeout    (:kind (second (runtime/get [:sc :errors]))))))

(deftest conj-respects-limit-test
  (dotimes [i 5]
    (runtime/conj! [:sc :errors] {:n i} 3))
  (let [errs (runtime/get [:sc :errors])]
    (is (= 3 (count errs)))
    (is (= [2 3 4] (mapv :n errs)))))

(deftest conj-default-limit-100-test
  (dotimes [_ 150]
    (runtime/conj! [:sc :errors] {:x 1}))
  (is (= 100 (count (runtime/get [:sc :errors])))))

;; ---------------------------------------------------------------------------
;; snapshot
;; ---------------------------------------------------------------------------

(deftest snapshot-returns-full-state-test
  (runtime/set! [:sc :status] :running)
  (runtime/set! [:midi :status] :connected)
  (let [snap (runtime/snapshot)]
    (is (= :running   (get-in snap [:sc :status])))
    (is (= :connected (get-in snap [:midi :status])))))

;; ---------------------------------------------------------------------------
;; watch-global! / unwatch-global!
;; ---------------------------------------------------------------------------

(deftest global-watcher-fires-on-set-test
  (let [calls (atom [])]
    (runtime/watch-global! ::test (fn [path val] (swap! calls conj [path val])))
    (runtime/set! [:sc :status] :running)
    (is (= [[[:sc :status] :running]] @calls))))

(deftest global-watcher-fires-on-conj-test
  (let [calls (atom [])]
    (runtime/watch-global! ::test (fn [path val] (swap! calls conj [path val])))
    (runtime/conj! [:sc :errors] {:kind :timeout})
    (is (= 1 (count @calls)))
    (is (= [:sc :errors] (first (first @calls))))))

(deftest unwatch-global-stops-notifications-test
  (let [calls (atom [])]
    (runtime/watch-global! ::test (fn [path val] (swap! calls conj [path val])))
    (runtime/unwatch-global! ::test)
    (runtime/set! [:sc :status] :running)
    (is (empty? @calls))))

;; ---------------------------------------------------------------------------
;; watch! / unwatch! (path-scoped)
;; ---------------------------------------------------------------------------

(deftest path-watch-fires-on-change-test
  (let [calls (atom [])]
    (runtime/watch! [:sc :status] ::test
      (fn [path old new] (swap! calls conj {:path path :old old :new new})))
    (runtime/set! [:sc :status] :running)
    (is (= 1 (count @calls)))
    (is (= {:path [:sc :status] :old nil :new :running} (first @calls)))))

(deftest path-watch-does-not-fire-on-sibling-change-test
  (let [calls (atom [])]
    (runtime/watch! [:sc :status] ::test
      (fn [_ _ _] (swap! calls conj :fired)))
    (runtime/set! [:midi :status] :connected)
    (is (empty? @calls))))

(deftest path-watch-does-not-fire-when-value-unchanged-test
  (runtime/set! [:sc :status] :running)
  (let [calls (atom [])]
    (runtime/watch! [:sc :status] ::test
      (fn [_ _ _] (swap! calls conj :fired)))
    (runtime/set! [:sc :status] :running)
    (is (empty? @calls))))

(deftest unwatch-stops-path-watch-test
  (let [calls (atom [])]
    (runtime/watch! [:sc :status] ::test
      (fn [_ _ _] (swap! calls conj :fired)))
    (runtime/unwatch! ::test)
    (runtime/set! [:sc :status] :running)
    (is (empty? @calls))))
