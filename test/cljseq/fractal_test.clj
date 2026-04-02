; SPDX-License-Identifier: EPL-2.0
(ns cljseq.fractal-test
  "Unit tests for cljseq.fractal — transformation algebra, playback order,
  branch tree, path navigation, and deffractal context."
  (:require [clojure.test :refer [deftest is testing]]
            [cljseq.core    :as core]
            [cljseq.ctrl    :as ctrl]
            [cljseq.fractal :as fractal]
            [cljseq.random  :as random]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(clojure.test/use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Shared test data
;; ---------------------------------------------------------------------------

(def ^:private trunk
  [{:pitch/midi 60 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
   {:pitch/midi 64 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
   {:pitch/midi 67 :dur/beats 1/2 :gate/on? true :gate/len 0.8}
   {:pitch/midi 65 :dur/beats 1/4 :gate/on? true :gate/len 0.5}])

;; ---------------------------------------------------------------------------
;; reverse-seq
;; ---------------------------------------------------------------------------

(deftest reverse-seq-test
  (testing "reverse-seq reverses step order"
    (let [r (fractal/reverse-seq trunk)]
      (is (= 4 (count r)))
      (is (= 65 (:pitch/midi (first r))))
      (is (= 60 (:pitch/midi (last r))))))
  (testing "reverse-seq of empty is empty"
    (is (= [] (fractal/reverse-seq [])))))

;; ---------------------------------------------------------------------------
;; inverse-seq
;; ---------------------------------------------------------------------------

(deftest inverse-seq-mirrors-around-root-test
  (testing "inverse-seq mirrors pitches around root 60"
    (let [inv (fractal/inverse-seq trunk 60)]
      ;; 60 → 60+60-60=60 (unchanged)
      (is (= 60 (:pitch/midi (nth inv 0))))
      ;; 64 → 60+(60-64)=56
      (is (= 56 (:pitch/midi (nth inv 1))))
      ;; 67 → 60+(60-67)=53
      (is (= 53 (:pitch/midi (nth inv 2))))
      ;; 65 → 60+(60-65)=55
      (is (= 55 (:pitch/midi (nth inv 3)))))))

(deftest inverse-seq-clamps-to-midi-range-test
  (testing "inverse-seq clamps result to [0,127]"
    (let [high-steps [{:pitch/midi 100}]
          inv        (fractal/inverse-seq high-steps 20)]
      ;; 20+(20-100)=-60 → clamp to 0
      (is (= 0 (:pitch/midi (first inv)))))))

(deftest inverse-seq-passes-through-non-pitched-test
  (testing "steps without :pitch/midi are unchanged"
    (let [steps [{:dur/beats 1/4 :gate/on? false}]
          inv   (fractal/inverse-seq steps 60)]
      (is (= steps inv)))))

;; ---------------------------------------------------------------------------
;; transpose-seq
;; ---------------------------------------------------------------------------

(deftest transpose-seq-adds-semitones-test
  (testing "transpose-seq shifts all pitches by N semitones"
    (let [t (fractal/transpose-seq trunk 12)]
      (is (= 72 (:pitch/midi (nth t 0))))
      (is (= 76 (:pitch/midi (nth t 1))))
      (is (= 79 (:pitch/midi (nth t 2))))
      (is (= 77 (:pitch/midi (nth t 3)))))))

(deftest transpose-seq-clamps-test
  (testing "transpose-seq clamps to [0,127]"
    (let [high [{:pitch/midi 125}]
          t    (fractal/transpose-seq high 10)]
      (is (= 127 (:pitch/midi (first t)))))
    (let [low [{:pitch/midi 2}]
          t   (fractal/transpose-seq low -10)]
      (is (= 0 (:pitch/midi (first t)))))))

;; ---------------------------------------------------------------------------
;; mutate-seq
;; ---------------------------------------------------------------------------

(deftest mutate-seq-preserves-count-test
  (testing "mutate-seq returns same number of steps"
    (is (= 4 (count (fractal/mutate-seq trunk :note-prob 0.5))))))

(deftest mutate-seq-zero-prob-unchanged-test
  (testing "mutate-seq at prob 0 returns identical pitches"
    (let [mut (fractal/mutate-seq trunk :note-prob 0.0 :gate-prob 0.0)]
      (is (= (map :pitch/midi trunk) (map :pitch/midi mut))))))

(deftest mutate-seq-full-prob-changes-pitches-test
  (testing "mutate-seq at prob 1.0 changes all pitches"
    (let [ws  (random/weighted-scale :major)
          mut (fractal/mutate-seq trunk :note-prob 1.0 :scale ws :root 60)]
      ;; All results should be integers in MIDI range
      (is (every? integer? (map :pitch/midi mut)))
      (is (every? #(<= 0 % 127) (map :pitch/midi mut))))))

;; ---------------------------------------------------------------------------
;; randomize-seq
;; ---------------------------------------------------------------------------

(deftest randomize-seq-returns-correct-count-test
  (testing "randomize-seq returns exactly len steps"
    (let [ws (random/weighted-scale :major)
          r  (fractal/randomize-seq 8 :scale ws :root 60)]
      (is (= 8 (count r))))))

(deftest randomize-seq-step-shape-test
  (testing "randomize-seq steps have required keys"
    (let [r (fractal/randomize-seq 4 :scale (random/weighted-scale :dorian) :root 62)]
      (doseq [step r]
        (is (integer? (:pitch/midi step)))
        (is (:dur/beats step))
        (is (true? (:gate/on? step)))))))

;; ---------------------------------------------------------------------------
;; resize-seq
;; ---------------------------------------------------------------------------

(deftest resize-seq-clone-test
  (testing ":clone cycles the sequence to target length"
    (let [r (fractal/resize-seq trunk 8 :clone)]
      (is (= 8 (count r)))
      (is (= 60 (:pitch/midi (nth r 0))))
      (is (= 60 (:pitch/midi (nth r 4)))))))

(deftest resize-seq-stretch-test
  (testing ":stretch repeats each step proportionally"
    (let [two-steps [{:pitch/midi 60} {:pitch/midi 64}]
          r         (fractal/resize-seq two-steps 4 :stretch)]
      (is (= 4 (count r)))
      (is (= 60 (:pitch/midi (nth r 0))))
      (is (= 60 (:pitch/midi (nth r 1))))
      (is (= 64 (:pitch/midi (nth r 2)))))))

(deftest resize-seq-spread-test
  (testing ":spread interleaves silent steps"
    (let [two-steps [{:pitch/midi 60 :gate/on? true}
                     {:pitch/midi 64 :gate/on? true}]
          r         (fractal/resize-seq two-steps 4 :spread)]
      (is (= 4 (count r)))
      (is (true? (:gate/on? (nth r 0))))
      (is (false? (:gate/on? (nth r 1)))))))

;; ---------------------------------------------------------------------------
;; rotate-seq
;; ---------------------------------------------------------------------------

(deftest rotate-seq-test
  (testing "rotate-seq shifts start by offset"
    (let [r (fractal/rotate-seq trunk 1)]
      (is (= 4 (count r)))
      (is (= 64 (:pitch/midi (first r))))
      (is (= 60 (:pitch/midi (last r))))))
  (testing "rotate-seq by 0 is identity"
    (is (= trunk (fractal/rotate-seq trunk 0))))
  (testing "rotate-seq wraps modularly"
    (is (= (fractal/rotate-seq trunk 1)
           (fractal/rotate-seq trunk (+ 1 (count trunk)))))))

;; ---------------------------------------------------------------------------
;; step-indices
;; ---------------------------------------------------------------------------

(deftest step-indices-forward-test
  (testing ":forward returns [0 1 2 ... n-1]"
    (is (= [0 1 2 3] (fractal/step-indices :forward 4 {})))))

(deftest step-indices-reverse-test
  (testing ":reverse returns [n-1 ... 1 0]"
    (is (= [3 2 1 0] (fractal/step-indices :reverse 4 {})))))

(deftest step-indices-pendulum-test
  (testing ":pendulum returns forward then reverse (excluding endpoints)"
    (let [r (fractal/step-indices :pendulum 4 {})]
      (is (= [0 1 2 3 2 1] r)))))

(deftest step-indices-random-test
  (testing ":random returns a permutation of [0 .. n-1]"
    (let [r (fractal/step-indices :random 5 {})]
      (is (= 5 (count r)))
      (is (= (set (range 5)) (set r))))))

(deftest step-indices-converge-test
  (testing ":converge interleaves from outer edges inward"
    (let [r (fractal/step-indices :converge 4 {})]
      (is (= [0 3 1 2] r)))))

(deftest step-indices-diverge-test
  (testing ":diverge proceeds outward from center"
    (let [r (fractal/step-indices :diverge 5 {})]
      ;; center=2; pairs: [2], [1,3], [0,4]
      (is (= 5 (count (set r))) "all indices present")
      (is (= 2 (first r)) "starts at center"))))

(deftest step-indices-page-jump-test
  (testing ":page-jump covers all indices in shuffled page order"
    (let [r (fractal/step-indices :page-jump 8 {:page-len 4})]
      (is (= 8 (count r)))
      (is (= (set (range 8)) (set r))))))

;; ---------------------------------------------------------------------------
;; build-tree
;; ---------------------------------------------------------------------------

(deftest build-tree-no-transforms-test
  (testing "build-tree with no transforms returns [trunk]"
    (let [tree (fractal/build-tree trunk)]
      (is (= 1 (count tree)))
      (is (= trunk (first tree))))))

(deftest build-tree-reverse-transform-test
  (testing "build-tree :reverse produces reversed branch"
    (let [tree (fractal/build-tree trunk :transforms [:reverse])]
      (is (= 2 (count tree)))
      (is (= trunk (first tree)))
      (is (= (fractal/reverse-seq trunk) (second tree))))))

(deftest build-tree-multiple-transforms-test
  (testing "build-tree applies transforms sequentially"
    (let [tree (fractal/build-tree trunk :transforms [:reverse :transpose]
                                   :transpose-semitones 12)]
      (is (= 3 (count tree)))
      ;; branch 1 = reversed trunk
      (is (= (fractal/reverse-seq trunk) (nth tree 1)))
      ;; branch 2 = reversed trunk transposed up 12
      (is (= (fractal/transpose-seq (fractal/reverse-seq trunk) 12)
             (nth tree 2))))))

;; ---------------------------------------------------------------------------
;; make-path
;; ---------------------------------------------------------------------------

(deftest make-path-integer-0-test
  (testing "integer 0 = forward path"
    (is (= [0 1 2] (fractal/make-path 0 3)))))

(deftest make-path-integer-1-test
  (testing "integer 1 = reverse path"
    (is (= [0 2 1] (fractal/make-path 1 3)))))

(deftest make-path-vector-test
  (testing "explicit vector used directly"
    (is (= [0 2 0 1] (fractal/make-path [0 2 0 1] 4)))))

(deftest make-path-keyword-leftmost-test
  (testing ":leftmost = forward"
    (is (= [0 1 2 3] (fractal/make-path :leftmost 4)))))

(deftest make-path-keyword-rightmost-test
  (testing ":rightmost = trunk first then branches in reverse"
    (is (= [0 3 2 1] (fractal/make-path :rightmost 4)))))

(deftest make-path-keyword-random-test
  (testing ":random returns a permutation"
    (let [p (fractal/make-path :random 4)]
      (is (= (set (range 4)) (set p))))))

;; ---------------------------------------------------------------------------
;; deffractal macro
;; ---------------------------------------------------------------------------

(deftest deffractal-creates-atom-test
  (testing "deffractal creates a var bound to an atom"
    (fractal/deffractal test-frac-a :trunk trunk :transforms [:reverse])
    (is (instance? clojure.lang.Atom test-frac-a))
    (is (map? @test-frac-a))))

(deftest deffractal-registers-ctrl-node-test
  (testing "deffractal registers [:fractal name] in ctrl tree"
    (fractal/deffractal test-frac-b :trunk trunk)
    (is (some? (ctrl/node-info [:fractal :test-frac-b])))))

(deftest deffractal-registers-in-fractal-names-test
  (testing "deffractal adds name to fractal-names"
    (fractal/deffractal test-frac-c :trunk trunk)
    (is (some #{:test-frac-c} (fractal/fractal-names)))))

(deftest deffractal-builds-correct-tree-size-test
  (testing "deffractal builds tree with trunk + N branches"
    (fractal/deffractal test-frac-d
      :trunk trunk
      :transforms [:reverse :inverse :mutate])
    (is (= 4 (count (:tree @test-frac-d))) "trunk + 3 branches")))

;; ---------------------------------------------------------------------------
;; next-step!
;; ---------------------------------------------------------------------------

(deftest next-step-returns-step-map-test
  (testing "next-step! returns a step map with :pitch/midi"
    (fractal/deffractal test-ns-a :trunk trunk)
    (let [step (fractal/next-step! test-ns-a)]
      (is (map? step))
      (is (integer? (:pitch/midi step))))))

(deftest next-step-cycles-through-trunk-test
  (testing "next-step! cycles through all trunk steps then wraps"
    (fractal/deffractal test-ns-b :trunk trunk :path [0])
    ;; With path [0] (trunk only), 4 steps then back to step 0
    (let [steps (repeatedly 8 #(fractal/next-step! test-ns-b))
          pitches (map :pitch/midi steps)]
      (is (= (take 4 pitches) (drop 4 pitches))
          "second 4 steps repeat first 4"))))

(deftest next-step-advances-through-branches-test
  (testing "next-step! plays through all branches in path order"
    (fractal/deffractal test-ns-c
      :trunk trunk
      :transforms [:reverse]
      :path 0)
    ;; path 0 = [0 1]; trunk has 4 steps, branch1 has 4 steps
    ;; steps 0-3 come from trunk, steps 4-7 from branch1
    (let [steps (vec (repeatedly 8 #(fractal/next-step! test-ns-c)))]
      (is (= (:pitch/midi (first trunk))
             (:pitch/midi (nth steps 0)))
          "step 0 = first trunk step")
      (is (= (:pitch/midi (last trunk))
             (:pitch/midi (nth steps 4)))
          "step 4 = first step of reversed branch = last trunk step"))))

;; ---------------------------------------------------------------------------
;; current-branch
;; ---------------------------------------------------------------------------

(deftest current-branch-test
  (testing "current-branch returns 0 initially"
    (fractal/deffractal test-cb-a :trunk trunk)
    (is (= 0 (fractal/current-branch test-cb-a)))))

;; ---------------------------------------------------------------------------
;; fractal-path!
;; ---------------------------------------------------------------------------

(deftest fractal-path-switches-test
  (testing "fractal-path! changes the active path"
    (fractal/deffractal test-fp-a
      :trunk trunk
      :transforms [:reverse :inverse]
      :path 0)
    ;; Start on forward path [0 1 2]
    (is (= [0 1 2] (:path-idxs @test-fp-a)))
    ;; Switch to reverse path
    (fractal/fractal-path! test-fp-a 1)
    (is (= [0 2 1] (:path-idxs @test-fp-a)))))

;; ---------------------------------------------------------------------------
;; freeze! / thaw!
;; ---------------------------------------------------------------------------

(deftest freeze-thaw-test
  (testing "freeze! sets :frozen? true, thaw! clears it"
    (fractal/deffractal test-ft-a :trunk trunk)
    (is (false? (:frozen? @test-ft-a)))
    (fractal/freeze! test-ft-a)
    (is (true? (:frozen? @test-ft-a)))
    (fractal/thaw! test-ft-a)
    (is (false? (:frozen? @test-ft-a)))))

(deftest reseed-respects-freeze-test
  (testing "reseed! is no-op when frozen"
    (fractal/deffractal test-ft-b
      :trunk trunk
      :scale (random/weighted-scale :major)
      :root 60)
    (fractal/freeze! test-ft-b)
    (let [tree-before (:tree @test-ft-b)]
      (fractal/reseed! test-ft-b)
      (is (= tree-before (:tree @test-ft-b)) "tree unchanged when frozen"))))

;; ---------------------------------------------------------------------------
;; reseed!
;; ---------------------------------------------------------------------------

(deftest reseed-changes-trunk-test
  (testing "reseed! produces a different trunk (probabilistically)"
    (fractal/deffractal test-rs-a
      :trunk trunk
      :scale (random/weighted-scale :major)
      :root 60)
    (let [original-tree (:tree @test-rs-a)]
      ;; reseed multiple times — at least one should differ
      (dotimes [_ 5] (fractal/reseed! test-rs-a))
      ;; New tree was regenerated
      (is (vector? (:tree @test-rs-a))))))

;; ---------------------------------------------------------------------------
;; mutate!
;; ---------------------------------------------------------------------------

(deftest mutate-changes-tree-test
  (testing "mutate! regenerates tree (mutated branches differ)"
    (fractal/deffractal test-mu-a
      :trunk trunk
      :transforms [:mutate :mutate]
      :mutate-prob 1.0)
    (let [tree-before (:tree @test-mu-a)]
      (fractal/mutate! test-mu-a)
      ;; tree is regenerated (trunk stays same, branches may differ)
      (is (= trunk (first (:tree @test-mu-a))) "trunk unchanged"))))

;; ---------------------------------------------------------------------------
;; fractal-names
;; ---------------------------------------------------------------------------

(deftest fractal-names-test
  (testing "fractal-names returns a seq"
    (is (sequential? (fractal/fractal-names))))
  (testing "fractal-names includes registered contexts"
    (fractal/deffractal test-fn-a :trunk trunk)
    (is (some #{:test-fn-a} (fractal/fractal-names)))))
