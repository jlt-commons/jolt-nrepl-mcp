(ns jolt-nrepl-mcp.replace
  "Structural find-and-replace for Clojure source.

  Matches by what the text READS as, not by its characters, so a snippet
  matches regardless of whitespace, line breaks, or indentation, while
  everything outside the matched form keeps its formatting byte for byte.
  The idea comes from clj-native-agent's clj-replace (which used rewrite-clj);
  this is a dependency-free lexer plus `read-string`, so it runs on jolt.

  Strings, comments and char literals are skipped, so text that merely LOOKS
  like the form there is never touched."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [jolt-nrepl-mcp.syntax :as syntax]))

(def ^:private openers #{\( \[ \{})
(def ^:private closers #{\) \] \}})
(def ^:private prefix-chars #{\' \` \~ \@ \# \^})

(defn- token-end
  "Index just past the atom token that starts at `i`."
  [s n i]
  (loop [j (if (= \\ (nth s i)) (+ i 2) (inc i))]
    (if (or (>= j n)
            (let [c (nth s j)]
              (or (Character/isWhitespace c) (= c \,) (openers c) (closers c)
                  (= c \") (= c \;))))
      (min j n)
      (recur (inc j)))))

(defn- string-end [s n i]
  (loop [j (inc i)]
    (cond
      (>= j n) n
      (= (nth s j) \\) (recur (+ j 2))
      (= (nth s j) \") (inc j)
      :else (recur (inc j)))))

(defn- prefix-start
  "Start of the run of reader-macro prefix characters (`'`, `#`, `@`, `~` …)
  immediately before index `i`."
  [s i]
  (loop [j i]
    (if (and (pos? j) (prefix-chars (nth s (dec j))))
      (recur (dec j))
      j)))

(defn spans
  "Every candidate form in `s` as [start end): each delimited collection, each
  string, each atom, plus — when reader-macro prefixes precede one — the same
  form with its prefix (`'x`, `#{…}`, `#(…)`, `@a`)."
  [s]
  (let [n (count s)
        emit (fn [acc start end]
               (let [p (prefix-start s start)]
                 (cond-> (conj acc [start end])
                   (< p start) (conj [p end]))))]
    (loop [i 0, stack [], acc []]
      (if (>= i n)
        acc
        (let [c (nth s i)]
          (cond
            (= c \;) (let [nl (str/index-of s "\n" i)]
                       (recur (if nl (inc nl) n) stack acc))
            (= c \") (let [e (string-end s n i)]
                       (recur e stack (emit acc i e)))
            (openers c) (recur (inc i) (conj stack i) acc)
            (closers c) (if-let [start (peek stack)]
                          (recur (inc i) (pop stack) (emit acc start (inc i)))
                          (recur (inc i) stack acc))
            ;; prefixes are picked up by `emit` once the form they precede ends
            ;; (a regex literal is a string with a # prefix)
            (or (Character/isWhitespace c) (= c \,) (prefix-chars c))
            (recur (inc i) stack acc)
            :else (let [e (token-end s n i)]
                    (recur e stack (emit acc i e)))))))))

(defn- gensym-name? [sym]
  (let [nm (name sym)]
    (or (str/ends-with? nm "#")
        (str/ends-with? nm "__auto__")
        (re-matches #"p\d*__\d+" nm))))

(defn normalize
  "A form with reader-generated names numbered by first appearance, and regex
  literals compared by pattern, so two readings of the same text are `=`."
  [form]
  (let [seen (atom {})]
    (walk/prewalk
     (fn [x]
       (cond
         (and (symbol? x) (gensym-name? x))
         (or (get @seen x)
             (let [g (symbol (str "G__" (count @seen)))]
               (swap! seen assoc x g)
               g))
         (instance? java.util.regex.Pattern x) [::regex (str x)]
         :else x))
     form)))

(defn- read-one
  "The single form `text` reads as, or ::none when it is not exactly one form."
  [text]
  (try
    (let [forms (read-string (str "[" text "\n]"))]
      (if (= 1 (count forms)) (normalize (first forms)) ::none))
    (catch Throwable _ ::none)))

(defn find-forms
  "Every place in `s` whose form reads equal to `old`, in file order, as
  {:start :end :line :col :text}."
  [s old]
  (let [target (read-one old)]
    (when-not (= ::none target)
      (->> (spans s)
           (distinct)
           (sort)
           (keep (fn [[start end]]
                   (let [text (subs s start end)]
                     (when (= target (read-one text))
                       (merge {:start start :end end :text text}
                              (syntax/line-col s start))))))
           ;; a prefixed and a bare span can both read equal only if the prefix
           ;; is a no-op; keep the outermost.
           (reduce (fn [acc m]
                     (if (some #(and (<= (:start %) (:start m)) (>= (:end %) (:end m))) acc)
                       acc
                       (conj acc m)))
                   [])))))

(defn- reindent
  "Shift continuation lines of `new` to the match column when they were
  written relative to column 1. Code inside a form starting at column `col`
  is never indented less than `col` spaces, so a continuation line left of
  that means the text was written as if at the margin."
  [new col]
  (let [[first-line & more] (str/split new #"\n" -1)
        indents (keep #(when-not (str/blank? %) (count (re-find #"^ *" %))) more)
        shift (dec col)]
    (if (and (pos? shift) (seq indents) (< (apply min indents) col))
      (str/join "\n" (cons first-line
                           (map #(if (str/blank? %) % (str (apply str (repeat shift \space)) %)) more)))
      new)))

(defn replace-form
  "Replace the form(s) in `s` that read equal to `old` with the text `new`.

  Options: :occurrence n (1-based) picks one of several matches; :all? replaces
  every match. Returns {:content s' :matches [...]} or {:error kw ...} with
  :no-match, :ambiguous (with :matches), :invalid-old, :invalid-new, or
  :broken-result (the edited file no longer reads, with the diagnosis)."
  [s old new {:keys [occurrence all?]}]
  (cond
    (= ::none (read-one old))
    {:error :invalid-old :message "old is not exactly one readable Clojure form"
     :diagnosis (syntax/diagnose old)}

    (and (not (str/blank? new)) (syntax/diagnose new))
    {:error :invalid-new :message "new does not read as Clojure" :diagnosis (syntax/diagnose new)}

    :else
    (let [matches (find-forms s old)
          chosen (cond
                   (empty? matches) nil
                   all? matches
                   occurrence (when (<= 1 occurrence (count matches))
                                [(nth matches (dec occurrence))])
                   (= 1 (count matches)) matches)]
      (cond
        (empty? matches) {:error :no-match :message "no form reads equal to old"}
        (nil? chosen) {:error :ambiguous :message (str (count matches) " forms match; pass occurrence or all")
                       :matches (mapv #(dissoc % :start :end) matches)}
        :else
        (let [content (reduce (fn [acc {:keys [start end col]}]
                                (str (subs acc 0 start) (reindent (str/trim new) col) (subs acc end)))
                              s
                              (sort-by :start > chosen))]
          (if-let [d (syntax/diagnose content)]
            {:error :broken-result :message "the edited file would not read" :diagnosis d}
            {:content content :matches (mapv #(dissoc % :start :end) chosen)}))))))
