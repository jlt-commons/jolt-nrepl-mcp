(ns jolt-nrepl-mcp.bencode-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt-nrepl-mcp.bencode :as b]))

(deftest round-trips
  (doseq [v [42 -7 "hi" "" "λ→😀" ["a" 1 ["b"]] {"op" "eval" "code" "(+ 1 2)" "n" 3}]]
    (let [[out next] (b/decode (b/encode v) 0)]
      (is (= v out))
      (is (= (count (b/encode v)) next)))))

(deftest keywords-and-nil-encode-as-strings
  (is (= {"op" "eval" "x" ""} (first (b/decode (b/encode {:op :eval :x nil}) 0)))))

(deftest byte-lengths-not-char-lengths
  (testing "a 4-byte codepoint is length 4 on the wire"
    (is (= "4:" (subs (b/encode "😀") 0 2)))))

(deftest incomplete-input-returns-nil
  (let [whole (b/encode {"value" "abcdef" "status" ["done"]})]
    (doseq [k (range (count whole))]
      (is (nil? (b/decode (subs whole 0 k) 0)) (str "prefix " k)))))
