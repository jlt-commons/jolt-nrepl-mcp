(ns jolt-nrepl-mcp.bencode
  "bencode for the nREPL wire. Wire text is held as latin1 strings (one char
  per byte) so lengths are byte lengths; string values are UTF-8 decoded at
  the boundary. Adapted from jolt-lang/nrepl's nrepl.bencode."
  (:require [clojure.string :as str]))

(defn ->wire [s] (String. (.getBytes (str s) "UTF-8") "ISO-8859-1"))
(defn wire-> [s] (String. (.getBytes s "ISO-8859-1") "UTF-8"))

(defn encode
  "Encode `v` (integer, string, keyword, nil, map, sequential) as a latin1
  bencode string."
  [v]
  (cond
    (integer? v) (str "i" v "e")
    (or (string? v) (keyword? v) (nil? v))
    (let [w (->wire (if (keyword? v) (name v) (or v "")))] (str (count w) ":" w))
    (map? v) (str "d" (str/join (mapcat (fn [[k x]] [(encode (name k)) (encode x)])
                                        (sort-by (comp name key) v)))
                  "e")
    (sequential? v) (str "l" (str/join (map encode v)) "e")
    :else (encode (str v))))

(defn decode
  "Decode one value from latin1 string `s` at index `i`: [value next-index],
  or nil when `s` does not yet hold a complete value."
  [s i]
  (let [n (count s)]
    (when (< i n)
      (let [c (nth s i)]
        (case c
          \i (when-let [e (str/index-of s "e" i)]
               [(parse-long (subs s (inc i) e)) (inc e)])
          \l (loop [j (inc i) acc []]
               (cond (>= j n) nil
                     (= (nth s j) \e) [acc (inc j)]
                     :else (when-let [[v nj] (decode s j)] (recur nj (conj acc v)))))
          \d (loop [j (inc i) acc {}]
               (cond (>= j n) nil
                     (= (nth s j) \e) [acc (inc j)]
                     :else (when-let [[k kj] (decode s j)]
                             (when-let [[v vj] (decode s kj)]
                               (recur vj (assoc acc k v))))))
          (when-let [colon (str/index-of s ":" i)]
            (let [len (parse-long (subs s i colon))
                  start (inc colon)
                  end (+ start len)]
              (when (and len (<= end n))
                [(wire-> (subs s start end)) end]))))))))
