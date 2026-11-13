(ns jolt-nrepl-mcp.forms-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt-nrepl-mcp.forms :as forms]))

(deftest splits-top-level-forms-keeping-their-text
  (is (= ["(println \"a\")" "1" "'d"] (forms/split "(println \"a\") 1 ; note\n 'd")))
  (is (= ["#?(:clj 1 :cljs 2)" "#foo/bar {:a 1}" "`(b ~c)" "@d" "#(inc %)" "#\"x+\""]
         (forms/split "#?(:clj 1 :cljs 2) #foo/bar {:a 1} `(b ~c) @d #(inc %) #\"x+\"")))
  (testing "a discarded form stays with the next one"
    (is (= ["#_ (x) 3"] (forms/split "#_ (x) 3"))))
  (testing "nil means send it whole"
    (is (nil? (forms/split "::s/foo 1")))
    (is (nil? (forms/split "   ")))
    (is (nil? (forms/split "; only a comment")))))

(deftest labels-are-one-short-line
  (is (= "(+ 1 2)" (forms/label "(+ 1 2)")))
  (is (= "(defn f [x] …" (forms/label "(defn f [x]\n  x)")))
  (is (= "(aaaaa …" (forms/label "(aaaaaaaaaa)" 6))))
