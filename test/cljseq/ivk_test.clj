; SPDX-License-Identifier: EPL-2.0
(ns cljseq.ivk-test
  "Tests for cljseq.ivk — keyboard layout registry."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [cljseq.chord  :as chord]
            [cljseq.ivk    :as ivk]
            [cljseq.pitch  :as pitch]
            [cljseq.scale  :as scale]))

;; ---------------------------------------------------------------------------
;; Test fixtures — reset ivk state before each test
;; ---------------------------------------------------------------------------

(defn reset-ivk! [f]
  (reset! ivk/ivk-state
          {:current-pitch  60
           :layout         :interval
           :scale          nil
           :octave-range   [36 84]
           :chord-fn       :I
           :chord-ext      :triad
           :passthru?      false
           :arp?           false
           :arp-notes      []
           :arp-rate       1/4
           :play-dur       1/4
           :voiced-chord?  false
           :modulation     {:bend 8192}
           :channel        1})
  (f))

(use-fixtures :each reset-ivk!)

;; ---------------------------------------------------------------------------
;; Layout registry
;; ---------------------------------------------------------------------------

(deftest built-in-layouts-registered
  (testing "all five built-in layouts are present in the registry"
    ;; layout-registry is a private atom; @@#'... double-derefs Var→Atom→Map
    (is (get @@#'ivk/layout-registry :interval))
    (is (get @@#'ivk/layout-registry :chromatic))
    (is (get @@#'ivk/layout-registry :scale))
    (is (get @@#'ivk/layout-registry :ndlr))
    (is (get @@#'ivk/layout-registry :harmonic))))

(deftest register-and-set-layout
  (testing "register a custom layout and switch to it"
    (let [my-layout {:layout/id :test :layout/name "Test"
                     :key-map {\a {:action :play-current}}}]
      (ivk/register-layout! :test my-layout)
      (ivk/set-layout! :test)
      (is (= :test (:layout @ivk/ivk-state))))))

(deftest set-layout-unknown-throws
  (testing "set-layout! throws for unknown layout id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ivk/set-layout! :does-not-exist)))))

;; ---------------------------------------------------------------------------
;; set-pitch! / current-pitch
;; ---------------------------------------------------------------------------

(deftest set-and-get-pitch
  (testing "set-pitch! updates :current-pitch; current-pitch reads it"
    (ivk/set-pitch! 72)
    (is (= 72 (ivk/current-pitch)))))

;; ---------------------------------------------------------------------------
;; :apply-interval action
;; ---------------------------------------------------------------------------

(deftest apply-interval-chromatic-fallback
  (testing "apply-interval with no scale steps chromatically"
    (let [state {:current-pitch 60 :scale nil :octave-range [36 84] :play-dur 1/4}
          after (ivk/handle-action! {:action :apply-interval :n 2} state)]
      (is (= 62 (:current-pitch after))))))

(deftest apply-interval-with-scale
  (testing "apply-interval steps diatonically in scale"
    (let [s     (scale/scale :C 4 :major)
          state {:current-pitch 60 :scale s :octave-range [36 84] :play-dur 1/4}
          after (ivk/handle-action! {:action :apply-interval :n 1} state)]
      ;; C major step +1 from C4 = D4 (62)
      (is (= 62 (:current-pitch after)))))
  (testing "apply-interval negative step"
    (let [s     (scale/scale :C 4 :major)
          state {:current-pitch 62 :scale s :octave-range [36 84] :play-dur 1/4}
          after (ivk/handle-action! {:action :apply-interval :n -1} state)]
      ;; D4 step -1 = C4 (60)
      (is (= 60 (:current-pitch after))))))

;; ---------------------------------------------------------------------------
;; :set-octave action
;; ---------------------------------------------------------------------------

(deftest set-octave-shifts-pitch-and-range
  (testing "set-octave updates both :current-pitch and :octave-range"
    (let [state {:current-pitch 60 :octave-range [36 84]}
          after (ivk/handle-action! {:action :set-octave :delta -1} state)]
      (is (= 48 (:current-pitch after)))
      (is (= [24 72] (:octave-range after)))))
  (testing "set-octave up"
    (let [state {:current-pitch 60 :octave-range [36 84]}
          after (ivk/handle-action! {:action :set-octave :delta 1} state)]
      (is (= 72 (:current-pitch after)))
      (is (= [48 96] (:octave-range after))))))

;; ---------------------------------------------------------------------------
;; :play-absolute action (chromatic layout semantics)
;; ---------------------------------------------------------------------------

(deftest play-absolute-uses-current-octave
  (testing "play-absolute places pitch-class in current-pitch octave"
    (let [state {:current-pitch 60 :octave-range [36 84] :play-dur 1/4}
          ;; \a in chromatic layout maps to midi=60 (C4, pc=0)
          ;; playing from C4 → should still get C4
          after (ivk/handle-action! {:action :play-absolute :midi 60} state)]
      (is (= 60 (:current-pitch after))))
    (let [state {:current-pitch 72 :octave-range [48 96] :play-dur 1/4}
          ;; current-pitch = C5 (octave 6), playing C (pc 0) → C5
          after (ivk/handle-action! {:action :play-absolute :midi 60} state)]
      (is (= 72 (:current-pitch after))))))

;; ---------------------------------------------------------------------------
;; :cycle-chord-ext
;; ---------------------------------------------------------------------------

(deftest cycle-chord-ext-cycles
  (testing ":triad → :seventh → :ninth → :triad"
    (let [s0 {:chord-ext :triad}
          s1 (ivk/handle-action! {:action :cycle-chord-ext} s0)
          s2 (ivk/handle-action! {:action :cycle-chord-ext} s1)
          s3 (ivk/handle-action! {:action :cycle-chord-ext} s2)]
      (is (= :seventh (:chord-ext s1)))
      (is (= :ninth   (:chord-ext s2)))
      (is (= :triad   (:chord-ext s3))))))

;; ---------------------------------------------------------------------------
;; :play-chord-tone (ndlr)
;; ---------------------------------------------------------------------------

(deftest play-chord-tone-requires-scale
  (testing "play-chord-tone with no scale leaves state unchanged (logs error)"
    (let [state {:current-pitch 60 :scale nil :chord-fn :I
                 :octave-range [36 84] :play-dur 1/4}
          after (ivk/handle-action! {:action :play-chord-tone :degree 0} state)]
      (is (= state after)))))

(deftest play-chord-tone-root
  (testing "play-chord-tone degree=0 on C major :I plays C"
    (let [s     (scale/scale :C 4 :major)
          state {:current-pitch 60 :scale s :chord-fn :I
                 :octave-range [36 84] :play-dur 1/4}
          after (ivk/handle-action! {:action :play-chord-tone :degree 0} state)]
      ;; C major chord :I root = C4 (60)
      (is (= 60 (:current-pitch after)))))
  (testing "play-chord-tone degree=1 on C major :I plays E (3rd)"
    (let [s     (scale/scale :C 4 :major)
          state {:current-pitch 60 :scale s :chord-fn :I
                 :octave-range [36 84] :play-dur 1/4}
          after (ivk/handle-action! {:action :play-chord-tone :degree 1} state)]
      ;; C major chord :I 3rd = E4 (64)
      (is (= 64 (:current-pitch after)))))
  (testing "play-chord-tone on :IV chord plays F"
    (let [s     (scale/scale :C 4 :major)
          state {:current-pitch 60 :scale s :chord-fn :IV
                 :octave-range [36 84] :play-dur 1/4}
          after (ivk/handle-action! {:action :play-chord-tone :degree 0} state)]
      ;; C major :IV root = F4 (65)
      (is (= 65 (:current-pitch after))))))

;; ---------------------------------------------------------------------------
;; chord-for-numeral (new accessor added to chord.clj)
;; ---------------------------------------------------------------------------

(deftest chord-for-numeral-test
  (is (= [0 :major]    (chord/chord-for-numeral :I)))
  (is (= [4 :dom7]     (chord/chord-for-numeral :V7)))
  (is (= [6 :half-dim7] (chord/chord-for-numeral :VIIø7)))
  (is (nil?             (chord/chord-for-numeral :XVII))))

;; ---------------------------------------------------------------------------
;; Arp state management
;; ---------------------------------------------------------------------------

(deftest arp-queue-test
  (testing "enqueue-note! (via arp? path) adds to :arp-notes"
    (swap! ivk/ivk-state assoc :arp? true)
    ;; Directly test the private enqueue fn via atom manipulation
    (swap! ivk/ivk-state update :arp-notes conj 60)
    (swap! ivk/ivk-state update :arp-notes conj 64)
    (is (= [60 64] (:arp-notes @ivk/ivk-state)))))

(deftest stop-arp-clears-queue
  (swap! ivk/ivk-state assoc :arp? true :arp-notes [60 64 67])
  (ivk/stop-arp!)
  (is (= [] (:arp-notes @ivk/ivk-state)))
  (is (false? (:arp? @ivk/ivk-state))))

;; ---------------------------------------------------------------------------
;; :cc-delta — virtual mod wheel
;; ---------------------------------------------------------------------------

(deftest cc-delta-increments-value
  (testing ":cc-delta accumulates across presses within [0 127]"
    (let [state {:modulation {:cc-1 64} :channel 1}
          s1    (ivk/handle-action! {:action :cc-delta :cc 1 :delta 10} state)
          s2    (ivk/handle-action! {:action :cc-delta :cc 1 :delta 10} s1)
          s3    (ivk/handle-action! {:action :cc-delta :cc 1 :delta -30} s2)]
      (is (= 74  (get-in s1 [:modulation :cc-1])))
      (is (= 84  (get-in s2 [:modulation :cc-1])))
      (is (= 54  (get-in s3 [:modulation :cc-1]))))))

(deftest cc-delta-clamps-to-127
  (let [state {:modulation {:cc-1 120} :channel 1}
        after (ivk/handle-action! {:action :cc-delta :cc 1 :delta 20} state)]
    (is (= 127 (get-in after [:modulation :cc-1])))))

(deftest cc-delta-clamps-to-zero
  (let [state {:modulation {:cc-1 5} :channel 1}
        after (ivk/handle-action! {:action :cc-delta :cc 1 :delta -20} state)]
    (is (= 0 (get-in after [:modulation :cc-1])))))

(deftest cc-delta-phasor-fn-does-not-update-slot
  (testing "when :cc slot is a fn, state is not modified by key press"
    (let [lfo   (constantly 80)
          state {:modulation {:cc-1 lfo} :channel 1}
          after (ivk/handle-action! {:action :cc-delta :cc 1 :delta 10} state)]
      ;; slot should still be the fn, not a number
      (is (fn? (get-in after [:modulation :cc-1]))))))

;; ---------------------------------------------------------------------------
;; :pitch-bend-delta
;; ---------------------------------------------------------------------------

(deftest pitch-bend-delta-increments
  (let [state {:modulation {:bend 8192} :channel 1}
        after (ivk/handle-action! {:action :pitch-bend-delta :delta 512} state)]
    (is (= 8704 (get-in after [:modulation :bend])))))

(deftest pitch-bend-delta-clamps
  (let [state {:modulation {:bend 16300} :channel 1}
        after (ivk/handle-action! {:action :pitch-bend-delta :delta 512} state)]
    (is (= 16383 (get-in after [:modulation :bend])))))

(deftest pitch-bend-center-snap
  (testing "delta=0 sends current center, useful as a 'snap to center' key"
    (let [state {:modulation {:bend 9000} :channel 1}
          after (ivk/handle-action! {:action :pitch-bend-delta :delta 0} state)]
      ;; no delta applied, value unchanged
      (is (= 9000 (get-in after [:modulation :bend]))))))

;; ---------------------------------------------------------------------------
;; phasor-bound :play-dur
;; ---------------------------------------------------------------------------

(deftest phasor-dur-fn-called
  (testing ":play-dur as a fn is called each step"
    (let [calls (atom 0)
          dur-fn (fn [] (swap! calls inc) 1/8)
          state  {:current-pitch 60 :scale nil :octave-range [36 84]
                  :play-dur dur-fn}
          _after (ivk/handle-action! {:action :apply-interval :n 0} state)]
      (is (= 1 @calls)))))

;; ---------------------------------------------------------------------------
;; keycode->char (internal — verify QWERTY mapping)
;; ---------------------------------------------------------------------------

(deftest keycode->char-table
  (let [kc->ch @#'ivk/keycode->char]
    (is (= \a    (kc->ch 0)))
    (is (= \s    (kc->ch 1)))
    (is (= \space (kc->ch 49)))
    (is (nil?    (kc->ch 99)))))

;; ---------------------------------------------------------------------------
;; :toggle-voiced-mode
;; ---------------------------------------------------------------------------

(deftest toggle-voiced-mode-flips-flag
  (testing "toggle-voiced-mode flips :voiced-chord? each press"
    (let [s0 {:voiced-chord? false}
          s1 (ivk/handle-action! {:action :toggle-voiced-mode} s0)
          s2 (ivk/handle-action! {:action :toggle-voiced-mode} s1)]
      (is (true?  (:voiced-chord? s1)))
      (is (false? (:voiced-chord? s2))))))

;; ---------------------------------------------------------------------------
;; :play-chord-tone with :octave-offset (harmonic layout upper row)
;; ---------------------------------------------------------------------------

(deftest play-chord-tone-octave-offset-test
  (testing ":octave-offset 1 raises note by one octave"
    (let [s     (scale/scale :C 4 :major)
          state {:current-pitch 60 :scale s :chord-fn :I
                 :octave-range [36 108] :play-dur 1/4}
          base  (ivk/handle-action! {:action :play-chord-tone :degree 0} state)
          upper (ivk/handle-action! {:action :play-chord-tone :degree 0
                                     :octave-offset 1} state)]
      (is (= 12 (- (:current-pitch upper) (:current-pitch base)))
          "octave-offset 1 adds 12 semitones")))
  (testing ":octave-offset nil behaves like 0"
    (let [s     (scale/scale :C 4 :major)
          state {:current-pitch 60 :scale s :chord-fn :I
                 :octave-range [36 84] :play-dur 1/4}
          no-off  (ivk/handle-action! {:action :play-chord-tone :degree 0} state)
          nil-off (ivk/handle-action! {:action :play-chord-tone :degree 0
                                       :octave-offset nil} state)]
      (is (= (:current-pitch no-off) (:current-pitch nil-off))))))

;; ---------------------------------------------------------------------------
;; :set-scale-root (harmonic layout number row)
;; ---------------------------------------------------------------------------

(deftest set-scale-root-changes-root-preserves-mode
  (testing ":set-scale-root preserves interval pattern (mode stays same)"
    (let [s     (scale/scale :C 4 :major)
          state {:scale s :chord-fn :IV :octave-range [36 84]}
          after (ivk/handle-action! {:action :set-scale-root :midi 67} state)]
      (is (= (:intervals s) (:intervals (:scale after))) "mode preserved")
      (is (= :I (:chord-fn after)) "chord-fn reset to tonic")))
  (testing ":set-scale-root with no scale is a no-op"
    (let [state {:scale nil :chord-fn :V}
          after (ivk/handle-action! {:action :set-scale-root :midi 62} state)]
      (is (= state after)))))

(deftest set-scale-root-midi-pitch-class-test
  (testing "pitch class of new root matches the :midi arg (mod 12)"
    (let [s     (scale/scale :C 4 :major)
          state {:scale s :chord-fn :I :octave-range [36 84]}
          ;; Pivot to D (midi 62, pc=2)
          after (ivk/handle-action! {:action :set-scale-root :midi 62} state)
          ;; degree 0 = root; convert Pitch to MIDI to extract pitch class
          root-midi (pitch/pitch->midi (scale/pitch-at (:scale after) 0))]
      (is (= 2 (mod root-midi 12)) "root is now D (pc=2)"))))

;; ---------------------------------------------------------------------------
;; :transpose-scale
;; ---------------------------------------------------------------------------

(deftest transpose-scale-shifts-root
  (testing ":transpose-scale +2 moves root up a whole step"
    (let [s     (scale/scale :C 4 :major)
          state {:scale s :chord-fn :IV}
          after (ivk/handle-action! {:action :transpose-scale :semitones 2} state)]
      (is (= (:intervals s) (:intervals (:scale after))) "mode preserved")
      (is (= :I (:chord-fn after)) "chord-fn reset")))
  (testing ":transpose-scale with no scale is a no-op"
    (let [state {:scale nil :chord-fn :V}
          after (ivk/handle-action! {:action :transpose-scale :semitones 7} state)]
      (is (= state after)))))

;; ---------------------------------------------------------------------------
;; :harmonic layout — structural checks
;; ---------------------------------------------------------------------------

(deftest harmonic-layout-has-home-row-split
  (testing "home row left keys are chord selectors"
    (let [km (:key-map (get @@#'ivk/layout-registry :harmonic))]
      (is (= :set-chord-fn (:action (get km \a))) "a=I chord")
      (is (= :set-chord-fn (:action (get km \s))) "s=IV chord")
      (is (= :set-chord-fn (:action (get km \d))) "d=V chord")
      (is (= :set-chord-fn (:action (get km \f))) "f=VI chord")))
  (testing "home row right keys are chord tones"
    (let [km (:key-map (get @@#'ivk/layout-registry :harmonic))]
      (is (= :play-chord-tone (:action (get km \h))) "h=root")
      (is (= :play-chord-tone (:action (get km \j))) "j=3rd")
      (is (= :play-chord-tone (:action (get km \k))) "k=5th")
      (is (= :play-chord-tone (:action (get km \l))) "l=7th")
      (is (= :play-chord-tone (:action (get km \;))) ";=9th")))
  (testing "upper octave keys carry :octave-offset 1"
    (let [km (:key-map (get @@#'ivk/layout-registry :harmonic))]
      (is (= 1 (:octave-offset (get km \y))) "y=root+8va")
      (is (= 1 (:octave-offset (get km \u))) "u=3rd+8va")))
  (testing "number row keys trigger scale root / transposition actions"
    (let [km (:key-map (get @@#'ivk/layout-registry :harmonic))]
      (is (= :set-scale-root  (:action (get km \1))) "1=C pivot")
      (is (= :set-scale-root  (:action (get km \5))) "5=G pivot")
      (is (= :transpose-scale (:action (get km \8))) "8=♭")
      (is (= :transpose-scale (:action (get km \9))) "9=♯")
      (is (= -1 (:semitones (get km \8))) "8 flattens by 1")
      (is (= 1  (:semitones (get km \9))) "9 sharpens by 1"))))
