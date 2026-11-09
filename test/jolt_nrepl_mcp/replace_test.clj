(ns jolt-nrepl-mcp.replace-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt-nrepl-mcp.replace :as r]))

(def src
  "(ns demo)

(defn update-user
  [db id attrs]
  (merge-user db
              id   attrs))

(defn other []
  (str \"(merge-user db id attrs)\") ; (merge-user db id attrs)
  #(inc %))
")

(deftest matches-structure-not-text
  (let [{:keys [content matches]} (r/replace-form src "(merge-user db id attrs)" "(save! db id attrs)" {})]
    (is (= 1 (count matches)) "strings and comments are not code")
    (is (= {:line 5 :col 3} (select-keys (first matches) [:line :col])))
    (is (str/includes? content "  (save! db id attrs))\n"))
    (is (str/includes? content "(str \"(merge-user db id attrs)\") ; (merge-user db id attrs)")
        "the rest of the file keeps its formatting")))

(deftest no-match-and-ambiguous-are-errors
  (is (= :no-match (:error (r/replace-form src "(nope)" "(x)" {}))))
  (let [s "(f (g 1))\n(h (g 1))\n"
        res (r/replace-form s "(g 1)" "(g 2)" {})]
    (is (= :ambiguous (:error res)))
    (is (= [{:line 1 :col 4} {:line 2 :col 4}]
           (map #(select-keys % [:line :col]) (:matches res))))
    (testing "an occurrence picks one"
      (is (= "(f (g 1))\n(h (g 2))\n" (:content (r/replace-form s "(g 1)" "(g 2)" {:occurrence 2})))))
    (testing ":all replaces every one"
      (is (= "(f (g 2))\n(h (g 2))\n" (:content (r/replace-form s "(g 1)" "(g 2)" {:all? true})))))))

(deftest reader-macros-and-gensyms-compare-equal
  (is (= 1 (count (:matches (r/replace-form src "#(inc %)" "#(dec %)" {})))))
  (is (str/includes? (:content (r/replace-form src "#(inc   %)" "#(dec %)" {})) "#(dec %)"))
  (is (= "(def x '[a b])" (:content (r/replace-form "(def x '[a  b])" "'[a b]" "'[a b]" {}))))
  (is (= "(re-find #\"y\" s)" (:content (r/replace-form "(re-find #\"x\" s)" "#\"x\"" "#\"y\"" {})))))

(deftest atoms-can-be-replaced
  (is (= "(+ y 1)" (:content (r/replace-form "(+ x 1)" "x" "y" {})))))

(deftest multi-line-replacement-is-reindented-to-the-match-column
  (let [s "(defn f []\n  (let [a 1]\n    a))\n"
        res (r/replace-form s "(let [a 1] a)" "(let [a 2]\n  (inc a))" {})]
    (is (= "(defn f []\n  (let [a 2]\n    (inc a)))\n" (:content res))))
  (testing "text already written at the absolute indentation is left alone"
    (let [s "(defn f []\n  (let [a 1]\n    a))\n"
          res (r/replace-form s "(let [a 1] a)" "(let [a 2]\n    (inc a))" {})]
      (is (= "(defn f []\n  (let [a 2]\n    (inc a)))\n" (:content res))))))

(deftest refuses-unreadable-input-and-output
  (is (= :invalid-old (:error (r/replace-form src "(merge-user" "(x)" {}))))
  (is (= :invalid-new (:error (r/replace-form src "(merge-user db id attrs)" "(x" {}))))
  (testing "an empty replacement deletes the form"
    (is (= "(f )" (:content (r/replace-form "(f (g))" "(g)" "" {}))))))
