; SPDX-License-Identifier: EPL-2.0
(ns cljseq.sample-test
  "Tests for cljseq.sample — buffer registry, load/unload, playback dispatch."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [cljseq.sample :as smp]
            [cljseq.synth  :as synth]
            [cljseq.patch  :as patch]
            [cljseq.core   :as core]))

;; ---------------------------------------------------------------------------
;; Fixture — reset buffer registry between tests
;; ---------------------------------------------------------------------------

(defn- reset-registry! [f]
  (reset! @#'smp/buffer-registry {:by-name {} :by-path {}})
  (f)
  (reset! @#'smp/buffer-registry {:by-name {} :by-path {}}))

(use-fixtures :each reset-registry!)

;; ---------------------------------------------------------------------------
;; defbuffer! / buffer-id / buffer-path / sample-names
;; ---------------------------------------------------------------------------

(deftest defbuffer-registers-test
  (testing "defbuffer! stores name and path"
    (smp/defbuffer! ::kick "/samples/kick.wav")
    (is (= "/samples/kick.wav" (smp/buffer-path ::kick)))
    (is (nil? (smp/buffer-id ::kick)))))

(deftest defbuffer-returns-name-test
  (testing "defbuffer! returns the buffer-name"
    (is (= ::snare (smp/defbuffer! ::snare "/samples/snare.wav")))))

(deftest defbuffer-idempotent-test
  (testing "re-registering replaces previous path"
    (smp/defbuffer! ::pad "/samples/pad-v1.wav")
    (smp/defbuffer! ::pad "/samples/pad-v2.wav")
    (is (= "/samples/pad-v2.wav" (smp/buffer-path ::pad)))))

(deftest defbuffer-requires-keyword-test
  (testing "defbuffer! throws when name is not a keyword"
    (is (thrown? Exception (smp/defbuffer! "not-a-kw" "/some/path.wav")))))

(deftest sample-names-test
  (testing "sample-names returns all registered names"
    (smp/defbuffer! ::a "/a.wav")
    (smp/defbuffer! ::b "/b.wav")
    (let [names (set (smp/sample-names))]
      (is (contains? names ::a))
      (is (contains? names ::b)))))

(deftest buffer-path-nil-for-unknown-test
  (testing "buffer-path returns nil for unregistered name"
    (is (nil? (smp/buffer-path ::nonexistent)))))

(deftest buffer-id-nil-before-load-test
  (testing "buffer-id is nil before load-sample!"
    (smp/defbuffer! ::unloaded "/samples/x.wav")
    (is (nil? (smp/buffer-id ::unloaded)))))

;; ---------------------------------------------------------------------------
;; load-sample! — path-based deduplication
;; ---------------------------------------------------------------------------

(deftest load-sample-calls-sc-alloc-test
  (testing "load-sample! calls sc-buffer-alloc! and records the ID"
    (let [calls (atom [])]
      (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [path] (swap! calls conj path) 42)]
        (smp/defbuffer! ::kick2 "/samples/kick.wav")
        (let [id (smp/load-sample! ::kick2)]
          (is (= 42 id))
          (is (= 42 (smp/buffer-id ::kick2)))
          (is (= ["/samples/kick.wav"] @calls)))))))

(deftest load-sample-deduplicates-by-path-test
  (testing "load-sample! reuses existing buffer when path matches"
    (let [calls (atom 0)]
      (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] (swap! calls inc) 7)]
        (smp/defbuffer! ::kick-dry  "/samples/kick.wav")
        (smp/defbuffer! ::kick-copy "/samples/kick.wav")
        (smp/load-sample! ::kick-dry)
        (smp/load-sample! ::kick-copy)
        (is (= 1 @calls) "sc-buffer-alloc! called only once")
        (is (= 7 (smp/buffer-id ::kick-dry)))
        (is (= 7 (smp/buffer-id ::kick-copy)))))))

(deftest load-sample-2-arity-test
  (testing "load-sample! 2-arity registers and loads in one call"
    (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] 99)]
      (let [id (smp/load-sample! ::direct "/samples/direct.wav")]
        (is (= 99 id))
        (is (= "/samples/direct.wav" (smp/buffer-path ::direct)))
        (is (= 99 (smp/buffer-id ::direct)))))))

(deftest load-sample-throws-when-not-registered-test
  (testing "1-arity load-sample! throws when buffer not registered"
    (is (thrown? Exception (smp/load-sample! ::ghost)))))

;; ---------------------------------------------------------------------------
;; unload-sample!
;; ---------------------------------------------------------------------------

(deftest unload-sample-frees-and-clears-test
  (testing "unload-sample! calls sc-buffer-free! and nils the id"
    (let [freed (atom nil)]
      (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] 5)
                    cljseq.sc/sc-buffer-free!   (fn [id] (reset! freed id))]
        (smp/defbuffer! ::tmp "/tmp/x.wav")
        (smp/load-sample! ::tmp)
        (smp/unload-sample! ::tmp)
        (is (= 5 @freed))
        (is (nil? (smp/buffer-id ::tmp)))))))

(deftest unload-sample-removes-by-path-index-test
  (testing "unload-sample! removes path from dedup index"
    (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] 3)
                  cljseq.sc/sc-buffer-free!   (fn [_] nil)]
      (smp/defbuffer! ::p "/dedup/x.wav")
      (smp/load-sample! ::p)
      (smp/unload-sample! ::p)
      ;; After unload, reloading should call alloc again (path dedup removed)
      (let [calls (atom 0)]
        (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] (swap! calls inc) 3)]
          (smp/load-sample! ::p "/dedup/x.wav")
          (is (= 1 @calls)))))))

(deftest unload-sample-noop-when-not-loaded-test
  (testing "unload-sample! is a no-op when buffer has no id"
    (smp/defbuffer! ::notloaded "/samples/none.wav")
    ;; Should not throw
    (is (nil? (smp/unload-sample! ::notloaded)))))

;; ---------------------------------------------------------------------------
;; sample! playback
;; ---------------------------------------------------------------------------

(deftest sample-throws-when-not-loaded-test
  (testing "sample! throws when buffer has no id"
    (smp/defbuffer! ::noid "/samples/noid.wav")
    (is (thrown? Exception (smp/sample! ::noid)))))

(deftest sample-calls-sc-play-test
  (testing "sample! calls sc/sc-play! with buf-player synth"
    (let [calls (atom [])]
      (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] 11)
                    cljseq.sc/sc-play!          (fn [m] (swap! calls conj m) 100)]
        (smp/defbuffer! ::s1 "/s1.wav")
        (smp/load-sample! ::s1)
        (smp/sample! ::s1)
        (let [args (first @calls)]
          (is (= :buf-player (:synth args)))
          (is (= 11.0 (:buf args))))))))

(deftest sample-accepts-options-test
  (testing "sample! passes :rate :amp :pan through"
    (let [calls (atom [])]
      (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] 12)
                    cljseq.sc/sc-play!          (fn [m] (swap! calls conj m) 101)]
        (smp/defbuffer! ::s2 "/s2.wav")
        (smp/load-sample! ::s2)
        (smp/sample! ::s2 :rate 0.5 :amp 0.6 :pan -0.3)
        (let [args (first @calls)]
          (is (= 0.5 (:rate args)))
          (is (= 0.6 (:amp args)))
          (is (= -0.3 (:pan args))))))))

;; ---------------------------------------------------------------------------
;; loop-sample!
;; ---------------------------------------------------------------------------

(deftest loop-sample-calls-sc-synth-test
  (testing "loop-sample! calls sc/sc-synth! with buf-looper"
    (let [calls (atom [])]
      (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] 20)
                    cljseq.sc/sc-synth!         (fn [synth args] (swap! calls conj {:synth synth :args args}) 200)]
        (smp/defbuffer! ::loop1 "/loop.wav")
        (smp/load-sample! ::loop1)
        (smp/loop-sample! ::loop1)
        (is (= :buf-looper (:synth (first @calls))))))))

(deftest loop-sample-throws-when-not-loaded-test
  (testing "loop-sample! throws when buffer has no id"
    (smp/defbuffer! ::loopnone "/none.wav")
    (is (thrown? Exception (smp/loop-sample! ::loopnone)))))

;; ---------------------------------------------------------------------------
;; granular-cloud!
;; ---------------------------------------------------------------------------

(deftest granular-cloud-throws-when-not-loaded-test
  (testing "granular-cloud! throws when buffer has no id"
    (smp/defbuffer! ::gcnone "/none.wav")
    (is (thrown? Exception (smp/granular-cloud! ::gcnone)))))

(deftest granular-cloud-instantiates-patch-test
  (testing "granular-cloud! calls instantiate-patch! with :granular-cloud"
    (let [calls (atom [])]
      (with-redefs [cljseq.sc/sc-buffer-alloc! (fn [_] 30)
                    cljseq.sc/instantiate-patch! (fn [pk] (swap! calls conj pk) {:nodes {:grains 50 :verb 51}
                                                                                  :buses {}
                                                                                  :params {}})
                    cljseq.sc/set-patch-param!   (fn [_ _ _] nil)]
        (smp/defbuffer! ::gc1 "/texture.wav")
        (smp/load-sample! ::gc1)
        (smp/granular-cloud! ::gc1)
        (is (= [:granular-cloud] @calls))))))

;; ---------------------------------------------------------------------------
;; play! dispatch — :sample key
;; ---------------------------------------------------------------------------

(deftest play-sample-dispatch-test
  (testing "play! routes :sample key to sample-play-dispatch!"
    (let [sc-calls (atom [])]
      (with-redefs [cljseq.sc/sc-connected?      (fn [] true)
                    cljseq.sc/sc-buffer-alloc!    (fn [_] 40)
                    cljseq.sc/sc-play!            (fn [m] (swap! sc-calls conj m) 300)]
        (smp/defbuffer! ::pd-test "/pd.wav")
        (smp/load-sample! ::pd-test)
        (core/play! {:sample ::pd-test} nil)
        (is (pos? (count @sc-calls)))
        (let [args (first @sc-calls)]
          (is (= :buf-player (:synth args)))
          (is (= 40.0 (:buf args))))))))

(deftest play-sample-dispatch-noop-when-disconnected-test
  (testing "sample-play-dispatch! does nothing when SC is not connected"
    (let [sc-calls (atom [])]
      (with-redefs [cljseq.sc/sc-connected? (fn [] false)
                    cljseq.sc/sc-play!      (fn [m] (swap! sc-calls conj m) nil)]
        (smp/defbuffer! ::pd-dc "/dc.wav")
        ;; No load needed — dispatch checks connected? first
        (core/play! {:sample ::pd-dc} nil)
        (is (empty? @sc-calls))))))

;; ---------------------------------------------------------------------------
;; New synths registered in synth registry
;; ---------------------------------------------------------------------------

(deftest buf-player-synth-registered-test
  (testing ":buf-player synth is registered"
    (is (some? (synth/get-synth :buf-player)))))

(deftest buf-looper-synth-registered-test
  (testing ":buf-looper synth is registered"
    (is (some? (synth/get-synth :buf-looper)))))

(deftest granular-voice-synth-registered-test
  (testing ":granular-voice synth is registered"
    (is (some? (synth/get-synth :granular-voice)))))

(deftest sine-bus-synth-registered-test
  (testing ":sine-bus synth is registered"
    (is (some? (synth/get-synth :sine-bus)))))

(deftest reverb-bus-synth-registered-test
  (testing ":reverb-bus synth is registered"
    (is (some? (synth/get-synth :reverb-bus)))))

;; ---------------------------------------------------------------------------
;; :granular-cloud patch registered
;; ---------------------------------------------------------------------------

(deftest granular-cloud-patch-registered-test
  (testing ":granular-cloud patch is registered"
    (is (some? (patch/get-patch :granular-cloud)))))

(deftest granular-cloud-patch-params-test
  (testing ":granular-cloud has expected params"
    (let [p (patch/patch-params :granular-cloud)]
      (is (= [:grains :buf]     (:buf p)))
      (is (= [:grains :density] (:density p)))
      (is (= [:grains :pos]     (:pos p)))
      (is (= [:verb   :room]    (:room p))))))

(deftest granular-cloud-patch-buses-test
  (testing ":granular-cloud declares :grain-bus"
    (let [patch (patch/get-patch :granular-cloud)
          buses (:buses patch)]
      (is (contains? buses :grain-bus))
      (is (= :audio (get-in buses [:grain-bus :rate]))))))

;; ---------------------------------------------------------------------------
;; buf-player compile-synth :sc smoke test
;; ---------------------------------------------------------------------------

(deftest buf-player-compiles-test
  (testing "buf-player SynthDef compiles to a string"
    (let [sd (synth/compile-synth :sc :buf-player)]
      (is (string? sd))
      (is (.contains sd "PlayBuf"))
      (is (or (.contains sd "buf-player") (.contains sd "buf_player"))))))

(deftest buf-looper-compiles-test
  (testing "buf-looper SynthDef compiles to a string with loop=1"
    (let [sd (synth/compile-synth :sc :buf-looper)]
      (is (string? sd))
      (is (.contains sd "PlayBuf")))))

(deftest granular-voice-compiles-test
  (testing "granular-voice SynthDef compiles to a string with TGrains"
    (let [sd (synth/compile-synth :sc :granular-voice)]
      (is (string? sd))
      (is (.contains sd "TGrains"))
      (is (.contains sd "BufFrames")))))
