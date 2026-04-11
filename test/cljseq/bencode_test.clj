; SPDX-License-Identifier: EPL-2.0
(ns cljseq.bencode-test
  (:require [clojure.test   :refer [deftest is testing]]
            [cljseq.bencode :as bc]))

;; ---------------------------------------------------------------------------
;; Round-trip helpers
;; ---------------------------------------------------------------------------

(defn- rt [v]
  (bc/decode (bc/encode v)))

;; ---------------------------------------------------------------------------
;; Integers
;; ---------------------------------------------------------------------------

(deftest encode-integer
  (testing "positive integer"
    (is (= "i42e" (String. (bc/encode 42) "UTF-8"))))
  (testing "zero"
    (is (= "i0e" (String. (bc/encode 0) "UTF-8"))))
  (testing "negative integer"
    (is (= "i-7e" (String. (bc/encode -7) "UTF-8")))))

(deftest roundtrip-integer
  (is (= 0   (rt 0)))
  (is (= 1   (rt 1)))
  (is (= -99 (rt -99)))
  (is (= 1000000 (rt 1000000))))

;; ---------------------------------------------------------------------------
;; Strings
;; ---------------------------------------------------------------------------

(deftest encode-string
  (testing "empty string"
    (is (= "0:" (String. (bc/encode "") "UTF-8"))))
  (testing "simple string"
    (is (= "4:spam" (String. (bc/encode "spam") "UTF-8"))))
  (testing "string with colon"
    (is (= "5:ab:cd" (String. (bc/encode "ab:cd") "UTF-8")))))

(deftest roundtrip-string
  (is (= ""       (rt "")))
  (is (= "hello"  (rt "hello")))
  (is (= "a:b:c"  (rt "a:b:c")))
  (is (= "i42e"   (rt "i42e"))))   ; bencode syntax inside a string is just data

(deftest keyword-encodes-as-string
  (is (= "2:op" (String. (bc/encode :op) "UTF-8"))))

;; ---------------------------------------------------------------------------
;; Lists
;; ---------------------------------------------------------------------------

(deftest encode-list
  (testing "empty list"
    (is (= "le" (String. (bc/encode []) "UTF-8"))))
  (testing "list of strings"
    (is (= "l4:spam3:fooe" (String. (bc/encode ["spam" "foo"]) "UTF-8"))))
  (testing "list of ints"
    (is (= "li1ei2ei3ee" (String. (bc/encode [1 2 3]) "UTF-8")))))

(deftest roundtrip-list
  (is (= []          (rt [])))
  (is (= ["a" "b"]   (rt ["a" "b"])))
  (is (= [1 2 3]     (rt [1 2 3])))
  (is (= ["done"]    (rt ["done"]))))

;; ---------------------------------------------------------------------------
;; Dicts
;; ---------------------------------------------------------------------------

(deftest encode-dict-sorted
  ;; bencode requires dict keys to be sorted
  (let [encoded (String. (bc/encode {:b "2" :a "1"}) "UTF-8")]
    (is (.startsWith encoded "d"))
    (is (.endsWith encoded "e"))
    ;; 'a' must come before 'b'
    (is (< (.indexOf encoded "1:a") (.indexOf encoded "1:b")))))

(deftest roundtrip-dict
  (is (= {}                    (rt {})))
  (is (= {:foo "bar"}          (rt {:foo "bar"})))
  (is (= {:op "eval" :id "1"}  (rt {:op "eval" :id "1"})))
  (is (= {:status ["done"]}    (rt {:status ["done"]}))))

;; ---------------------------------------------------------------------------
;; nREPL message shapes
;; ---------------------------------------------------------------------------

(deftest nrepl-eval-request
  (let [msg {:op "eval" :code "(+ 1 2)" :id "req-1" :session "abc"}
        decoded (rt msg)]
    (is (= "eval"    (:op decoded)))
    (is (= "(+ 1 2)" (:code decoded)))
    (is (= "req-1"   (:id decoded)))
    (is (= "abc"     (:session decoded)))))

(deftest nrepl-eval-response
  (let [resp {:id "req-1" :value "3" :status ["done"]}
        decoded (rt resp)]
    (is (= "req-1"   (:id decoded)))
    (is (= "3"       (:value decoded)))
    (is (= ["done"]  (:status decoded)))))

(deftest nrepl-error-response
  (let [resp {:id "r" :ex "class clojure.lang.ExceptionInfo" :status ["eval-error" "done"]}
        decoded (rt resp)]
    (is (= "class clojure.lang.ExceptionInfo" (:ex decoded)))
    (is (= ["eval-error" "done"] (:status decoded)))))

;; ---------------------------------------------------------------------------
;; Nested structures
;; ---------------------------------------------------------------------------

(deftest nested-list-in-dict
  (let [v {:keys ["a" "b" "c"]}]
    (is (= v (rt v)))))

(deftest deeply-nested
  (let [v {:outer {:inner "value"}}]
    (is (= v (rt v)))))

;; ---------------------------------------------------------------------------
;; decode-stream
;; ---------------------------------------------------------------------------

(deftest decode-stream-reads-single-value
  (let [bs  (bc/encode {:op "eval" :id "x"})
        in  (java.io.ByteArrayInputStream. bs)
        val (bc/decode-stream in)]
    (is (= "eval" (:op val)))
    (is (= "x"    (:id val)))))

(deftest decode-stream-eof-returns-nil
  (let [in (java.io.ByteArrayInputStream. (byte-array 0))]
    (is (nil? (bc/decode-stream in)))))

(deftest decode-stream-multiple-frames
  ;; Two frames concatenated in one stream
  (let [f1  (bc/encode {:id "1" :value "a"})
        f2  (bc/encode {:id "2" :value "b"})
        buf (byte-array (+ (alength f1) (alength f2)))]
    (System/arraycopy f1 0 buf 0 (alength f1))
    (System/arraycopy f2 0 buf (alength f1) (alength f2))
    (let [in (java.io.ByteArrayInputStream. buf)]
      (is (= "a" (:value (bc/decode-stream in))))
      (is (= "b" (:value (bc/decode-stream in))))
      (is (nil? (bc/decode-stream in))))))
