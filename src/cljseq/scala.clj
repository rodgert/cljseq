; SPDX-License-Identifier: EPL-2.0
(ns cljseq.scala
  "Microtonal scale support via the Scala (.scl) file format.

  The Scala format (.scl) is a standard for describing microtonal and
  alternative tuning systems. Thousands of scales are freely available
  in the Scala Scale Archive (https://huygens-fokker.org/scala/).

  ## Loading scales

    (def ms (scala/load-scl \"31edo.scl\"))       ; search scales-dir then classpath
    (def ms (scala/load-scl \"/path/to/my.scl\")) ; explicit path
    (def ms (scala/parse-scl scl-string))          ; parse raw text

  ## Inspection

    (scala/degree-count ms)     ; number of pitch classes per period
    (scala/period-cents ms)     ; period in cents (usually 1200.0)
    (scala/degree->cents ms 7)  ; cents above root for scale degree 7
    (scala/all-degrees ms)      ; all degrees as cents vector

  ## Playing microtonal notes with cljseq

  `degree->step` returns a step map with :pitch/midi and :pitch/bend-cents
  suitable for passing directly to `play!`:

    (def ms (scala/load-scl \"31edo.scl\"))
    (def root 60)   ; MIDI note C4

    ;; Play the 7th scale degree
    (play! (merge (scala/degree->step ms root 7) {:dur/beats 1/4}))

    ;; In a live loop
    (deflive-loop :melody {}
      (doseq [d [0 3 5 7 10 12]]
        (play! (merge (scala/degree->step ms root d) {:dur/beats 1/8})))
      (sleep! 3))

  The :pitch/bend-cents value is always in [-50.0, +50.0] cents — the
  deviation from the nearest MIDI semitone. The sidecar sends a pitch
  bend message before the note-on and resets it after note-off.

  ## Pitch bend range calibration

  core/*bend-range-cents* controls how many cents equal a full-scale
  pitch bend. Default is 200.0 (±2 semitones, the MIDI standard default).
  Match this to your synth's pitch bend range setting:

    (binding [core/*bend-range-cents* 1200.0]  ; ±1 octave
      (play! (scala/degree->step ms root 7) 1/4))

  ## Keyboard mapping (.kbm)

  KBM files map MIDI key numbers to scale degrees, enabling a scale to be
  played from a physical keyboard with arbitrary layouts (e.g. diatonic on
  white keys, unmapped black keys):

    (def kbm (scala/load-kbm \"whitekeys-a440.kbm\"))
    (def ms  (scala/load-scl \"pythagorean.scl\"))

    ;; Retune an incoming MIDI note using both files:
    (scala/midi->step kbm ms 62)   ; D key → Pythagorean major second
    ;; => {:pitch/midi 62 :pitch/bend-cents 3.91}

    ;; Returns nil for unmapped keys (x entries) — caller may silence or pass through:
    (scala/midi->step kbm ms 61)   ; C# unmapped → nil

  ## MTS Bulk Dump (MIDI Tuning Standard)

  `scale->mts-bytes` generates a 408-byte MTS Bulk Dump SysEx message that
  retunes all 128 MIDI keys simultaneously. Send it via `cljseq.sidecar/send-mts!`
  to retune any MTS-capable synth (including the Hydrasynth Explorer).

    (def ms  (scala/load-scl \"31edo.scl\"))
    (def kbm (scala/load-kbm \"whitekeys.kbm\"))

    ;; Retune the whole keyboard in one message:
    (sidecar/send-mts! ms kbm)

    ;; Or generate the raw bytes (e.g. for inspection or custom transport):
    (scala/scale->mts-bytes ms kbm)

  Key design decision: MicrotonalScale is independent of the 12-TET Scale type.
  Use Scale for diatonic/modal work; use MicrotonalScale for tuning systems
  that don't fit the 12-semitone grid."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [cljseq.dirs     :as dirs]))

;; ---------------------------------------------------------------------------
;; MicrotonalScale record
;; ---------------------------------------------------------------------------

(defrecord MicrotonalScale [description period-cents degrees])
;; description  — string from SCL description line
;; period-cents — period in cents (last entry in SCL file, usually 1200.0)
;; degrees      — vector of cents offsets from root, length = degree-count
;;                degrees[0] = 0.0 (implicit unison, not listed in SCL file)
;;                degrees[n] = nth scale step in cents
;;                Does NOT include the period itself (that is period-cents)

;; ---------------------------------------------------------------------------
;; Pitch value parsing
;; ---------------------------------------------------------------------------

(defn- ratio->cents
  "Convert a ratio string 'a/b' or integer string 'n' to cents."
  ^double [^String s]
  (if (str/includes? s "/")
    (let [[a b] (str/split s #"/")
          num   (Double/parseDouble (str/trim a))
          den   (Double/parseDouble (str/trim b))]
      (* 1200.0 (/ (Math/log (/ num den)) (Math/log 2.0))))
    (* 1200.0 (/ (Math/log (Double/parseDouble s)) (Math/log 2.0)))))

(defn- parse-pitch-value
  "Parse one SCL pitch entry to cents.
  Entries with '.' are already in cents; others are ratios."
  ^double [^String raw]
  (let [s (str/trim raw)]
    (if (str/includes? s ".")
      (Double/parseDouble s)
      (ratio->cents s))))

;; ---------------------------------------------------------------------------
;; SCL parser
;; ---------------------------------------------------------------------------

(defn parse-scl
  "Parse a Scala (.scl) file from a string.

  Returns a MicrotonalScale record, or throws ex-info on malformed input.

  SCL format:
    Lines beginning with '!' are comments and are ignored.
    Line 1 (non-comment): scale description (any text)
    Line 2 (non-comment): number of notes N (integer)
    Lines 3..N+2: pitch values — either cents (e.g. 386.314) or
                  ratios (e.g. 3/2 or 2). The last entry is the period
                  (usually 1200.0 cents = 2/1 octave)."
  [^String text]
  (let [lines   (->> (str/split-lines text)
                     (remove #(str/starts-with? (str/trim %) "!"))
                     (map str/trim)
                     (remove empty?))
        _       (when (< (count lines) 2)
                  (throw (ex-info "SCL parse error: missing description or note count"
                                  {:lines-found (count lines)})))
        desc    (first lines)
        n       (try (Integer/parseInt (second lines))
                     (catch NumberFormatException _
                       (throw (ex-info "SCL parse error: note count must be an integer"
                                       {:value (second lines)}))))
        _       (when (< n 1)
                  (throw (ex-info "SCL parse error: note count must be positive" {:n n})))
        entries (->> (drop 2 lines)
                     (take n)
                     (mapv parse-pitch-value))
        _       (when (< (count entries) n)
                  (throw (ex-info "SCL parse error: fewer pitch values than note count"
                                  {:expected n :found (count entries)})))
        period  (last entries)
        degrees (into [0.0] (butlast entries))]
    (->MicrotonalScale desc period degrees)))

(defn load-scl
  "Load a Scala (.scl) file by name or path.

  `name-or-path` — filename (searched in scales-dir then classpath resources/scales/)
                   or an absolute path to a .scl file.

  Returns a MicrotonalScale record.

  Example:
    (scala/load-scl \"31edo.scl\")
    (scala/load-scl \"/Users/me/scales/carlos-alpha.scl\")"
  [name-or-path]
  (let [file (io/file name-or-path)]
    (if (.isAbsolute file)
      (if (.exists file)
        (parse-scl (slurp file))
        (throw (ex-info "SCL file not found" {:path name-or-path})))
      ;; Relative: search scales-dir then classpath
      (if-let [url (dirs/resolve-scala-resource name-or-path)]
        (parse-scl (slurp url))
        (throw (ex-info "SCL file not found in scales-dir or classpath"
                        {:name name-or-path
                         :searched [(dirs/scales-dir) "classpath resources/scales/"]}))))))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn degree-count
  "Return the number of pitch classes per period (not counting the period itself).
  For a 7-note diatonic scale, this is 7."
  [^MicrotonalScale ms]
  (count (:degrees ms)))

(defn period-cents
  "Return the period of the scale in cents (usually 1200.0)."
  ^double [^MicrotonalScale ms]
  (double (:period-cents ms)))

(defn all-degrees
  "Return the degrees vector: all pitch offsets in cents from root [0.0 ... last].
  Length = (degree-count ms)."
  [^MicrotonalScale ms]
  (:degrees ms))

;; ---------------------------------------------------------------------------
;; Pitch arithmetic
;; ---------------------------------------------------------------------------

(defn degree->cents
  "Return the cents offset above root for scale degree `d`.

  Handles degrees beyond one period and negative degrees:
    degree 0              → 0.0 (root)
    degree (degree-count) → period-cents (one period up)
    degree -1             → last step minus period (one period below root)"
  ^double [^MicrotonalScale ms d]
  (let [degs   (:degrees ms)
        n      (count degs)
        octave (Math/floorDiv (int d) n)
        step   (Math/floorMod (int d) n)]
    (+ (double (nth degs step))
       (* (double octave) (double (:period-cents ms))))))

;; ---------------------------------------------------------------------------
;; Integration with play! — step map generation
;; ---------------------------------------------------------------------------

(defn degree->note
  "Return a map {:midi N :bend-cents C} for playing scale degree `d`
  with the root at MIDI note `root-midi`.

  :midi       — nearest MIDI note number (0–127)
  :bend-cents — cent deviation from that MIDI note, always in [-50.0, +50.0]

  The :pitch/bend-cents value is always within ±50 cents because we round
  to the nearest semitone. Use with core/*bend-range-cents* ≥ 50.0 (default 200.0).

  Example:
    (scala/degree->note ms 60 7)
    ;; => {:midi 67 :bend-cents -1.955}  ; Pythagorean 5th, slightly below 12-TET G"
  [^MicrotonalScale ms root-midi d]
  (let [cents    (degree->cents ms d)
        nearest  (long (Math/round (/ cents 100.0)))
        bend     (- cents (* (double nearest) 100.0))
        midi     (max 0 (min 127 (+ (long root-midi) nearest)))]
    {:midi midi :bend-cents bend}))

(defn degree->step
  "Return a step map {:pitch/midi N :pitch/bend-cents C} for scale degree `d`.

  The returned map is suitable for passing directly to play! — merge it with
  any additional step map keys you need:

    (play! (merge (scala/degree->step ms root d) {:dur/beats 1/4}))

  When :bend-cents is 0.0 (e.g. for 12-TET), :pitch/bend-cents is omitted
  from the returned map so that play! skips the pitch bend message entirely."
  [^MicrotonalScale ms root-midi d]
  (let [{:keys [midi bend-cents]} (degree->note ms root-midi d)]
    (cond-> {:pitch/midi (long midi)}
      (not (zero? bend-cents))
      (assoc :pitch/bend-cents bend-cents))))

;; ---------------------------------------------------------------------------
;; Convenience: interval between two degrees in cents
;; ---------------------------------------------------------------------------

(defn interval-cents
  "Return the interval in cents between degree `from` and degree `to`."
  ^double [^MicrotonalScale ms from to]
  (- (degree->cents ms to) (degree->cents ms from)))

;; ---------------------------------------------------------------------------
;; KeyboardMap record (.kbm)
;; ---------------------------------------------------------------------------

(defrecord KeyboardMap
  [map-size        ; int — number of keys per map cycle (0 = identity/linear)
   first-midi      ; int — first MIDI key to retune
   last-midi       ; int — last MIDI key to retune
   middle-note     ; int — MIDI note that maps to reference-freq at degree 0
   reference-note  ; int — MIDI note that sounds at reference-freq
   reference-freq  ; double — reference frequency in Hz (usually 440.0)
   formal-octave   ; int — scale degree that is one formal octave above degree 0
   keys])          ; vector of degree-index (int) or :x (unmapped)

;; ---------------------------------------------------------------------------
;; KBM parser
;; ---------------------------------------------------------------------------

(defn- parse-kbm-int [s field]
  (try (Integer/parseInt (str/trim s))
       (catch NumberFormatException _
         (throw (ex-info "KBM parse error: expected integer"
                         {:field field :value s})))))

(defn- parse-kbm-double [s field]
  (try (Double/parseDouble (str/trim s))
       (catch NumberFormatException _
         (throw (ex-info "KBM parse error: expected number"
                         {:field field :value s})))))

(defn- parse-key-entry
  "Parse a single key-map entry: an integer degree or 'x' (unmapped)."
  [s]
  (let [t (str/trim s)]
    (if (= "x" (str/lower-case t))
      :x
      (try (Integer/parseInt t)
           (catch NumberFormatException _
             (throw (ex-info "KBM parse error: key entry must be integer or 'x'"
                             {:value t})))))))

(defn parse-kbm
  "Parse a Scala keyboard map (.kbm) file from a string.

  Returns a KeyboardMap record, or throws ex-info on malformed input.

  KBM format (lines beginning with '!' are comments):
    Line 1: map size (keys per period; 0 = identity mapping)
    Line 2: first MIDI key to retune
    Line 3: last MIDI key to retune
    Line 4: middle note — MIDI key that maps to scale degree 0
    Line 5: reference note — MIDI key that sounds at reference-freq
    Line 6: reference frequency in Hz
    Line 7: scale degree that is one formal octave (period repetition)
    Lines 8+: key mapping entries — one integer degree or 'x' per line"
  [^String text]
  (let [lines (->> (str/split-lines text)
                   (remove #(str/starts-with? (str/trim %) "!"))
                   (map str/trim)
                   (remove empty?))
        _     (when (< (count lines) 7)
                (throw (ex-info "KBM parse error: fewer than 7 header fields"
                                {:lines-found (count lines)})))
        [l1 l2 l3 l4 l5 l6 l7 & rest-lines] lines
        map-size       (parse-kbm-int    l1 :map-size)
        first-midi     (parse-kbm-int    l2 :first-midi)
        last-midi      (parse-kbm-int    l3 :last-midi)
        middle-note    (parse-kbm-int    l4 :middle-note)
        reference-note (parse-kbm-int    l5 :reference-note)
        reference-freq (parse-kbm-double l6 :reference-freq)
        formal-octave  (parse-kbm-int    l7 :formal-octave)
        keys           (if (zero? map-size)
                         []
                         (let [entries (mapv parse-key-entry (take map-size rest-lines))]
                           (when (< (count entries) map-size)
                             (throw (ex-info "KBM parse error: fewer key entries than map-size"
                                             {:expected map-size :found (count entries)})))
                           entries))]
    (->KeyboardMap map-size first-midi last-midi middle-note
                   reference-note reference-freq formal-octave keys)))

(defn load-kbm
  "Load a Scala keyboard map (.kbm) file by name or path.

  `name-or-path` — filename (searched in scales-dir then classpath resources/scales/)
                   or an absolute path to a .kbm file.

  Returns a KeyboardMap record.

  Example:
    (scala/load-kbm \"whitekeys-a440.kbm\")
    (scala/load-kbm \"/Users/me/scales/my-layout.kbm\")"
  [name-or-path]
  (let [file (io/file name-or-path)]
    (if (.isAbsolute file)
      (if (.exists file)
        (parse-kbm (slurp file))
        (throw (ex-info "KBM file not found" {:path name-or-path})))
      (if-let [url (dirs/resolve-scala-resource name-or-path)]
        (parse-kbm (slurp url))
        (throw (ex-info "KBM file not found in scales-dir or classpath"
                        {:name name-or-path
                         :searched [(dirs/scales-dir) "classpath resources/scales/"]}))))))

;; ---------------------------------------------------------------------------
;; KBM + SCL combined pitch lookup
;; ---------------------------------------------------------------------------

(defn midi->note
  "Retune MIDI key `midi-note` using keyboard map `kbm` and scale `ms`.

  Returns {:midi N :bend-cents C} where N is the nearest MIDI note and C is
  the pitch-bend deviation in cents, or nil if `midi-note` is outside the
  kbm's [first-midi, last-midi] range or maps to an unmapped ('x') key.

  When map-size is 0, delegates to `degree->note` treating the MIDI offset
  from middle-note as a scale degree (identity / linear mapping).

  The :bend-cents is always within [-50.0, +50.0] cents."
  [^KeyboardMap kbm ^MicrotonalScale ms midi-note]
  (let [midi   (int midi-note)
        first  (int (:first-midi kbm))
        last   (int (:last-midi  kbm))]
    (when (and (>= midi first) (<= midi last))
      (let [sz (int (:map-size kbm))]
        (if (zero? sz)
          ;; Identity mapping: treat offset from middle-note as degree
          (degree->note ms (:middle-note kbm) (- midi (:middle-note kbm)))
          ;; Normal mapping
          (let [offset  (- midi (int (:middle-note kbm)))
                octave  (Math/floorDiv offset sz)
                key-pos (Math/floorMod offset sz)
                degree  (nth (:keys kbm) key-pos)]
            (when (not= :x degree)
              (let [base-cents  (double (nth (:degrees ms) (int degree)))
                    total-cents (+ base-cents (* (double octave)
                                                 (double (:period-cents ms))))
                    nearest     (long (Math/round (/ total-cents 100.0)))
                    bend        (- total-cents (* (double nearest) 100.0))
                    out-midi    (max 0 (min 127 (+ (int (:middle-note kbm)) nearest)))]
                {:midi out-midi :bend-cents bend}))))))))

(defn midi->step
  "Retune MIDI key `midi-note` using keyboard map `kbm` and scale `ms`.

  Returns a step map {:pitch/midi N :pitch/bend-cents C} suitable for play!,
  or nil if the key is out of range or unmapped.

  :pitch/bend-cents is omitted when bend is exactly 0.0 (e.g. 12-TET layouts).

  Example:
    (def kbm (scala/load-kbm \"whitekeys-a440.kbm\"))
    (def ms  (scala/load-scl  \"pythagorean.scl\"))
    (play! (merge (scala/midi->step kbm ms 60) {:dur/beats 1/4}))"
  [^KeyboardMap kbm ^MicrotonalScale ms midi-note]
  (when-let [{:keys [midi bend-cents]} (midi->note kbm ms midi-note)]
    (cond-> {:pitch/midi (long midi)}
      (not (zero? bend-cents))
      (assoc :pitch/bend-cents bend-cents))))

;; ---------------------------------------------------------------------------
;; MTS Bulk Dump — MIDI Tuning Standard (MTS)
;; ---------------------------------------------------------------------------

(defn- encode-mts-note
  "Encode MIDI key `k` as a 3-byte MTS note entry [xx yy zz].

  If `result` is nil (key unmapped or out of KBM range), the key maps to
  itself with no detuning (12-TET identity). Otherwise the retuned MIDI
  note and bend-cents from midi->note are encoded as:

    xx = nearest semitone (0–127)
    fine = bend_cents / 100 * 16384, rounded to [0, 16383]
    yy = (fine >> 7) & 0x7F
    zz = fine & 0x7F

  Negative bend-cents (key is flat of nearest semitone) are handled by
  decrementing xx by 1 and reflecting the deviation into the positive range."
  [k result]
  (let [xx   (int (if result (:midi result) k))
        bend (double (if result (:bend-cents result 0.0) 0.0))
        [xx' adj] (if (neg? bend)
                    [(max 0 (dec xx)) (+ bend 100.0)]
                    [xx bend])
        fine (max 0 (min 16383 (int (Math/round (* (/ adj 100.0) 16384.0)))))
        yy   (bit-and (bit-shift-right fine 7) 0x7F)
        zz   (bit-and fine 0x7F)]
    [(unchecked-byte xx') (unchecked-byte yy) (unchecked-byte zz)]))

(def ^:private mts-identity-kbm
  "Identity KBM for scale->mts-bytes when no KBM is provided.
  map-size=0 maps each MIDI key linearly from middle-note=60 as origin."
  (->KeyboardMap 0 0 127 60 69 440.0 12 []))

(defn scale->mts-bytes
  "Generate a 408-byte MTS Bulk Dump SysEx byte array for `ms`.

  Encodes all 128 MIDI keys using `kbm` to map keys to scale degrees.
  When `kbm` is nil an identity map (middle-note=60, linear degrees) is used.
  Unmapped keys (:x KBM entries or out-of-range) retain their 12-TET tuning.

  MTS Bulk Dump format (MIDI Tuning Standard, Non-Realtime Universal SysEx):
    F0 7E 7F 08 01 <prog> <name×16> <128 × [xx yy zz]> <checksum> F7
    Total: 408 bytes.

  The checksum is the XOR of all bytes after F0 and before the checksum
  byte itself, masked to 7 bits.

  The returned byte array can be passed directly to sidecar/send-sysex!
  or sidecar/send-mts!.

  Example:
    (def ms (scala/load-scl \"31edo.scl\"))
    (sidecar/send-mts! ms)

    (def kbm (scala/load-kbm \"whitekeys.kbm\"))
    (sidecar/send-mts! ms kbm)"
  ([ms]    (scale->mts-bytes ms nil))
  ([ms kbm]
   (let [effective-kbm (or kbm mts-identity-kbm)
         name-str      (str (:description ms ""))
         name-bytes    (mapv (fn [i]
                               (unchecked-byte
                                 (if (< i (count name-str))
                                   (int (.charAt name-str i))
                                   0x20)))
                             (range 16))
         note-entries  (mapcat (fn [k]
                                 (encode-mts-note k (midi->note effective-kbm ms k)))
                               (range 128))
         body          (concat [0x7E 0x7F 0x08 0x01 0x00]
                               name-bytes
                               note-entries)
         checksum      (bit-and
                         (reduce (fn [acc b]
                                   (bit-xor acc (bit-and (int b) 0xFF)))
                                 0 body)
                         0x7F)
         all-bytes     (concat [0xF0] body [checksum 0xF7])
         arr           (byte-array (count all-bytes))]
     (doseq [[i b] (map-indexed vector all-bytes)]
       (aset arr i (unchecked-byte b)))
     arr)))
