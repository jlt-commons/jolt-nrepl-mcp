(ns jolt-nrepl-mcp.forms
  "Split eval input into top-level forms, keeping each form's source text.

  jolt's nREPL servers answer a multi-form eval with every form's output and
  only the last value, so the client sends forms one at a time to give the
  agent a value per form (the per-form output layout follows clojure-mcp's
  eval tool, EPL-2.0)."
  (:require [clojure.string :as str]))

(defn- strip-leading-comments
  "read+string's text includes whitespace and line comments before the form."
  [s]
  (loop [s (str/triml s)]
    (if (str/starts-with? s ";")
      (let [nl (str/index-of s "\n")]
        (if nl (recur (str/triml (subs s nl))) ""))
      s)))

(defn split
  "The top-level forms of `code` as source strings, or nil when it can't be
  split here (an alias-qualified keyword like ::s/x only reads in its own ns,
  an unknown reader feature, ...). nil means: send it whole."
  [code]
  (try
    (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. code))]
      (binding [*default-data-reader-fn* (fn [_tag v] v)
                *read-eval* false]
        (loop [acc []]
          (let [[form text] (read+string {:eof ::eof :read-cond :preserve} r)]
            (if (= ::eof form)
              (not-empty acc)
              (recur (conj acc (strip-leading-comments text))))))))
    (catch Throwable _ nil)))

(defn label
  "A one-line label for a form: its first line, cut to `max` chars."
  ([text] (label text 60))
  ([text max]
   (let [line (first (str/split-lines text))
         more? (or (< (count line) (count text)) (< max (count line)))
         line (if (< max (count line)) (subs line 0 max) line)]
     (str line (when more? " …")))))
