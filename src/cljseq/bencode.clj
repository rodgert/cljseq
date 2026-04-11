; SPDX-License-Identifier: EPL-2.0
(ns cljseq.bencode
  "Minimal bencode encoder/decoder for the nREPL wire protocol.

  ## Why this is hand-rolled instead of using the nREPL library

  cljseq intentionally carries zero external dependencies beyond
  `org.clojure/clojure` and `org.clojure/data.json`. Adding the nREPL
  library (`[nrepl \"1.3.0\"]`) would be the first dev-tooling library
  in what is meant to be a lean, production-oriented library. It would
  also pull in transitive dependencies (clj-commons/pomegranate, etc.)
  that have no place in a live-performance music runtime.

  Bencode itself is intentionally simple — the whole spec is four types:

    integers   — `i42e`          (i<digits>e)
    byte strings — `4:spam`      (<length>:<bytes>)
    lists      — `l4:spami42ee`  (l<item>...e)
    dicts      — `d3:foo3:bare`  (d<key><value>...e, keys sorted)

  An encoder + decoder for these four types fits comfortably in ~80
  lines of pure Clojure. Java sockets are already used in `cljseq.peer`
  for UDP multicast; a TCP socket + bencode is a natural, consistent
  extension.

  If the nREPL protocol ever expands in a way that makes hand-rolling
  impractical, the right response is to factor this namespace out into
  a small dedicated library — not to pull nREPL's entire toolchain into
  cljseq itself.

  ## Usage

    ;; Encode a request map
    (encode {:op \"eval\" :code \"(+ 1 2)\" :id \"req-1\"})
    ;=> bytes

    ;; Decode a response frame from an InputStream
    (decode-stream in)
    ;=> {:id \"req-1\" :value \"3\" :status [\"done\"]}

  ## Wire format for nREPL messages

  nREPL messages are bencode dicts where every value is either a string
  or a list of strings. Common request keys: `:op`, `:code`, `:id`,
  `:session`. Common response keys: `:id`, `:value`, `:out`, `:err`,
  `:status`, `:session`."
  (:import [java.io InputStream OutputStream ByteArrayOutputStream]))

;; ---------------------------------------------------------------------------
;; Encoder
;; ---------------------------------------------------------------------------

(declare encode-val)

(defn- encode-int [n ^ByteArrayOutputStream out]
  (.write out (int \i))
  (.write out (.getBytes (str n) "UTF-8"))
  (.write out (int \e)))

(defn- encode-str [s ^ByteArrayOutputStream out]
  (let [bs (.getBytes ^String s "UTF-8")]
    (.write out (.getBytes (str (alength bs)) "UTF-8"))
    (.write out (int \:))
    (.write out bs)))

(defn- encode-list [coll ^ByteArrayOutputStream out]
  (.write out (int \l))
  (doseq [item coll] (encode-val item out))
  (.write out (int \e)))

(defn- encode-dict [m ^ByteArrayOutputStream out]
  (.write out (int \d))
  (doseq [[k v] (sort-by (fn [e] (name (key e))) m)]
    (encode-str (name k) out)
    (encode-val v out))
  (.write out (int \e)))

(defn- encode-val [v ^ByteArrayOutputStream out]
  (cond
    (integer? v)    (encode-int v out)
    (string? v)     (encode-str v out)
    (keyword? v)    (encode-str (name v) out)
    (sequential? v) (encode-list v out)
    (map? v)        (encode-dict v out)
    :else           (encode-str (str v) out)))

(defn encode
  "Encode `msg` (a map, or any bencode-able value) to a byte array."
  [msg]
  (let [out (ByteArrayOutputStream.)]
    (encode-val msg out)
    (.toByteArray out)))

(defn write-message
  "Encode `msg` and write it directly to `output-stream`."
  [msg ^OutputStream output-stream]
  (.write output-stream (encode msg)))

;; ---------------------------------------------------------------------------
;; Decoder
;; ---------------------------------------------------------------------------

(declare decode-val)

(defn- read-byte ^long [^InputStream in]
  (let [b (.read in)]
    (when (= b -1)
      (throw (ex-info "bencode: unexpected end of stream" {})))
    b))

(defn- read-until
  "Read bytes from `in` until `sentinel` byte, return as String."
  [^InputStream in sentinel]
  (loop [acc (StringBuilder.)]
    (let [b (read-byte in)]
      (if (= b (int sentinel))
        (str acc)
        (recur (doto acc (.append (char b))))))))

(defn- decode-int [^InputStream in]
  ;; 'i' already consumed
  (Long/parseLong (read-until in \e)))

(defn- decode-str [^InputStream in first-digit]
  ;; first digit of length already consumed
  (let [rest-len (read-until in \:)
        len      (Long/parseLong (str (char first-digit) rest-len))
        buf      (byte-array len)]
    (.readNBytes in buf 0 len)
    (String. buf "UTF-8")))

(defn- decode-list [^InputStream in]
  ;; 'l' already consumed
  (loop [acc []]
    (let [b (read-byte in)]
      (if (= b (int \e))
        acc
        (recur (conj acc (decode-val in b)))))))

(defn- decode-dict [^InputStream in]
  ;; 'd' already consumed
  (loop [acc {}]
    (let [b (read-byte in)]
      (if (= b (int \e))
        acc
        (let [k (decode-val in b)
              v (decode-val in (read-byte in))]
          (recur (assoc acc (keyword k) v)))))))

(defn- decode-val [^InputStream in first-byte]
  (cond
    (= first-byte (int \i)) (decode-int in)
    (= first-byte (int \l)) (decode-list in)
    (= first-byte (int \d)) (decode-dict in)
    (Character/isDigit (char first-byte)) (decode-str in first-byte)
    :else (throw (ex-info "bencode: unexpected byte"
                          {:byte first-byte :char (char first-byte)}))))

(defn decode
  "Decode a single bencode value from byte array `bs`.
  Returns the decoded value."
  [^bytes bs]
  (let [in (java.io.ByteArrayInputStream. bs)]
    (decode-val in (read-byte in))))

(defn decode-stream
  "Decode a single bencode value from `InputStream` `in`.
  Blocks until a complete value is available.
  Returns the decoded value, or nil on EOF."
  [^InputStream in]
  (let [b (.read in)]
    (when (not= b -1)
      (decode-val in b))))
