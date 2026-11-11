(ns jolt-nrepl-mcp.bencode
  "bencode for the nREPL wire. Wire text is held as latin1 strings (one char
  per byte) so lengths are byte lengths; string values are UTF-8 decoded at
  the boundary. Adapted from jolt-lang/nrepl's nrepl.bencode.

  Pure Clojure, no host interop, and every loop is a reduce over the input:
  the writ spec (test/jolt_nrepl_mcp/spec/bencode_spec.clj) checks it."
  (:require [clojure.string :as str]))

(defn- utf8-bytes
  "The UTF-8 bytes of one code point."
  [cp]
  (cond
    (< cp 0x80) [cp]
    (< cp 0x800) [(bit-or 0xC0 (bit-shift-right cp 6))
                  (bit-or 0x80 (bit-and cp 0x3F))]
    (< cp 0x10000) [(bit-or 0xE0 (bit-shift-right cp 12))
                    (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                    (bit-or 0x80 (bit-and cp 0x3F))]
    :else [(bit-or 0xF0 (bit-shift-right cp 18))
           (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F))
           (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
           (bit-or 0x80 (bit-and cp 0x3F))]))

(defn ->wire
  "Text -> its UTF-8 bytes, one latin1 char per byte."
  [s]
  (apply str (map char (mapcat #(utf8-bytes (int %)) (str s)))))

(defn- lead
  "Start a code point at byte b: [code-points continuation-bytes-left
  partial-cp]."
  [out b]
  (cond
    (< b 0x80) [(conj out b) 0 0]
    (= 0xC0 (bit-and b 0xE0)) [out 1 (bit-and b 0x1F)]
    (= 0xE0 (bit-and b 0xF0)) [out 2 (bit-and b 0x0F)]
    (= 0xF0 (bit-and b 0xF8)) [out 3 (bit-and b 0x07)]
    :else [(conj out 0xFFFD) 0 0]))

(defn- utf8-step
  "Fold one byte into [code-points continuation-bytes-left partial-cp]. A
  malformed sequence decodes to U+FFFD, as the JVM's decoder does."
  [[out need cp] b]
  (cond
    (zero? need) (lead out b)
    (= 0x80 (bit-and b 0xC0))
    (let [cp (bit-or (bit-shift-left cp 6) (bit-and b 0x3F))]
      (if (= need 1) [(conj out cp) 0 0] [out (dec need) cp]))
    ;; a lead byte where a continuation belonged: replace, then start over
    :else (lead (conj out 0xFFFD) b)))

(defn wire->
  "latin1 wire text (one char per byte) -> the text its UTF-8 bytes spell."
  [s]
  (let [[out need] (reduce utf8-step [[] 0 0] (map int s))
        out (if (pos? need) (conj out 0xFFFD) out)]
    (apply str (map char out))))

(defn- branch? [x]
  (or (map? x) (sequential? x)))

(defn- children
  "A collection's contents framed by its bencode open and close markers;
  map keys sorted by name."
  [x]
  (if (map? x)
    (concat [::dict]
            (mapcat (fn [[k v]] [(name k) v]) (sort-by (comp name key) x))
            [::end])
    (concat [::list] x [::end])))

(defn- encode-string [s]
  (let [w (->wire s)] (str (count w) ":" w)))

(defn- encode-leaf [v]
  (cond
    (= ::dict v) "d"
    (= ::list v) "l"
    (= ::end v) "e"
    (integer? v) (str "i" v "e")
    (nil? v) "0:"
    (keyword? v) (encode-string (name v))
    :else (encode-string (str v))))

(defn encode
  "Encode `v` (integer, string, keyword, nil, map, sequential) as a latin1
  bencode string. A walk of the value in order, each collection framed by
  its markers, with every leaf encoded on its own."
  [v]
  (apply str (map encode-leaf (remove branch? (tree-seq branch? children v)))))

(defn- emit
  "Hand a decoded value to the innermost open container, or finish."
  [st v pos]
  (let [stack (:stack st)]
    (if (empty? stack)
      (reduced (assoc st :result [v pos]))
      (let [top (peek stack)
            top (cond
                  (= :list (:kind top)) (update top :acc conj v)
                  (contains? top :key) (-> top (update :acc assoc (:key top) v) (dissoc :key))
                  :else (assoc top :key v))]
        (assoc st :stack (conj (pop stack) top) :pos pos)))))

(defn- step
  "Consume the token at the cursor when the reduce reaches it."
  [s n st j]
  (if (not= j (:pos st))
    st
    (let [c (nth s j)]
      (case c
        \i (if-let [e (str/index-of s "e" j)]
             (emit st (parse-long (subs s (inc j) e)) (inc e))
             (reduced st))
        \l (assoc st :stack (conj (:stack st) {:kind :list :acc []}) :pos (inc j))
        \d (assoc st :stack (conj (:stack st) {:kind :dict :acc {}}) :pos (inc j))
        \e (if (empty? (:stack st))
             (reduced st)
             (emit (assoc st :stack (pop (:stack st)))
                   (:acc (peek (:stack st)))
                   (inc j)))
        (let [colon (str/index-of s ":" j)
              len (when colon (parse-long (subs s j colon)))
              end (when len (+ colon 1 len))]
          (if (and end (<= end n))
            (emit st (wire-> (subs s (inc colon) end)) end)
            (reduced st)))))))

(defn decode
  "Decode one value from latin1 string `s` at index `i`: [value next-index],
  or nil when `s` does not yet hold a complete value."
  [s i]
  (let [n (count s)]
    (when (< i n)
      (:result (reduce #(step s n %1 %2)
                       {:stack [] :pos i :result nil}
                       (range i n))))))
