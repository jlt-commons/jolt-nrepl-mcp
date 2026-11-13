(ns jolt-nrepl-mcp.client-test
  "Against real `jolt nrepl-server` processes: the built-in handler, and one
  with jolt-lang/nrepl's default middleware (sessions + interrupt)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt-nrepl-mcp.client :as c]))

(def nrepl-dep
  "jolt-lang/nrepl {:git/url \"https://github.com/jolt-lang/nrepl\" :git/tag \"v0.4.3\" :git/sha \"8ada4beeb100a391045ec2d93eb566b476fb46f7\"}")

(defn wait-for-port [root]
  (let [port-file (str root "/.nrepl-port")
        deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (cond
        (fs/exists? port-file) (parse-long (str/trim (slurp port-file)))
        (< deadline (System/currentTimeMillis)) (throw (ex-info "nREPL server did not write its port" {}))
        :else (do (Thread/sleep 50) (recur))))))

(defn start-server! [rich?]
  (let [root (str (fs/create-temp-dir {:prefix (if rich? "jnm-rich" "jnm-base")}))]
    (fs/create-dirs (str root "/src/demo"))
    (spit (str root "/deps.edn")
          (if rich?
            (str "{:paths [\"src\"] :deps {" nrepl-dep "} :nrepl/middleware [nrepl.middleware/default-middleware]}")
            "{:paths [\"src\"]}"))
    (let [proc (p/process ["jolt" "nrepl-server" "0"]
                          {:dir root :out :string :err :string :shutdown p/destroy-tree})]
      (wait-for-port root)
      {:root root :proc proc})))

(defn stop-server! [{:keys [root proc]}]
  (p/destroy-tree proc)
  ;; wait for it to exit: a server removes its .nrepl-port on the way out,
  ;; which races delete-tree's walk
  (deref proc 10000 nil)
  (try (fs/delete-tree root {:force true})
       (catch java.nio.file.NoSuchFileException _
         (fs/delete-tree root {:force true}))))

(def ^:dynamic *base* nil)
(def ^:dynamic *rich* nil)

(use-fixtures :once
  (fn [t]
    (let [base (start-server! false)
          rich (start-server! true)]
      (try
        (binding [*base* base *rich* rich] (t))
        (finally
          (stop-server! base)
          (stop-server! rich))))))

(defn thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest evaluates-with-output-and-value
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (let [r (c/eval! st {:cwd cwd :code "(println \"hi\") (+ 1 2)"})]
        (is (= "3" (:value r)))
        (is (= "hi\n" (:out r)))
        (is (false? (:error? r))))
      (testing "stderr and unicode"
        (let [r (c/eval! st {:cwd cwd :code "(binding [*out* *err*] (println \"λ😀\")) \"λ😀\""})]
          (is (= "\"λ😀\"" (:value r)))
          (is (= "λ😀\n" (:err r)))))
      (finally (c/disconnect! st)))))

(deftest namespace-persists-across-evals-even-without-sessions
  ;; jolt's built-in handler does not keep *ns* per session; the client
  ;; carries the last namespace forward on every eval.
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (c/eval! st {:cwd cwd :code "(ns demo.scratch) (def answer 42)"})
      (is (= "demo.scratch" (:ns (c/eval! st {:cwd cwd :code "answer"}))))
      (is (= "42" (:value (c/eval! st {:cwd cwd :code "answer"}))))
      (testing "an explicit ns wins"
        (is (= "\"clojure.string\"" (:value (c/eval! st {:cwd cwd :ns "clojure.string" :code "(str *ns*)"})))))
      (finally (c/disconnect! st)))))

(deftest errors-are-results-not-exceptions
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (let [r (c/eval! st {:cwd cwd :code "(/ 1 0)"})]
        (is (true? (:error? r)))
        (is (re-find #"Divide by zero" (:err r)))
        (is (re-find #"ArithmeticException" (str (:ex r)))))
      (finally (c/disconnect! st)))))

(deftest truncated-code-is-repaired-and-reported
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (let [r (c/eval! st {:cwd cwd :code "(+ 1 (* 2 3"})]
        (is (= "7" (:value r)))
        (is (re-find #"auto-closed 2" (:repair r))))
      (testing "unrepairable code never reaches the server, and says where"
        (let [d (thrown-data #(c/eval! st {:cwd cwd :code "(defn f [] 1)) (g)"}))]
          (is (= :syntax (:type d)))
          (is (re-find #"line 1, col 14" (:note d)))))
      (finally (c/disconnect! st)))))

(deftest finds-nrepl-port-in-a-parent-directory
  (let [st (c/create-state)
        sub (str (:root *base*) "/src/demo")]
    (try
      (is (= "1" (:value (c/eval! st {:cwd sub :code "1"}))))
      (is (= (str (fs/real-path (:root *base*))) (:root (c/status st))))
      (finally (c/disconnect! st)))))

(deftest load-file-loads-and-refuses-broken-files-with-a-position
  (let [st (c/create-state) cwd (:root *base*)
        good (str cwd "/src/demo/good.clj")
        bad (str cwd "/src/demo/bad.clj")]
    (try
      (spit good "(ns demo.good)\n(defn twice [x] (* 2 x))\n")
      (spit bad "(ns demo.bad)\n(defn f [x]\n  (let [y 1\n        )]\n    x))\n")
      (is (false? (:error? (c/load-file! st {:cwd cwd :file "src/demo/good.clj"}))))
      (is (= "10" (:value (c/eval! st {:cwd cwd :code "(demo.good/twice 5)"}))))
      (testing "a load does not move the session's namespace"
        (c/eval! st {:cwd cwd :code "(ns demo.here)"})
        (c/load-file! st {:cwd cwd :file "src/demo/good.clj"})
        (is (= "\"demo.here\"" (:value (c/eval! st {:cwd cwd :code "(str *ns*)"})))))
      (let [d (thrown-data #(c/load-file! st {:cwd cwd :file "src/demo/bad.clj"}))]
        (is (= :syntax (:type d)))
        (is (re-find #"line 4, col 9" (:note d))))
      (is (= :path-escape (:type (thrown-data #(c/load-file! st {:cwd cwd :file "../../etc/hosts"})))))
      (finally (c/disconnect! st)))))

(deftest timeout-without-interrupt-drops-the-connection-and-never-replays
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (c/eval! st {:cwd cwd :code "(def attempts (atom 0))"})
      (let [d (thrown-data #(c/eval! st {:cwd cwd :timeout-ms 100
                                         :code "(do (swap! attempts inc) (Thread/sleep 600) 99)"}))]
        (is (= :timeout (:type d)))
        (is (false? (:certain? d))))
      (is (false? (:connected? (c/status st))))
      (Thread/sleep 700)
      (is (= "1" (:value (c/eval! st {:cwd cwd :code "@attempts"}))))
      (finally (c/disconnect! st)))))

(deftest rich-middleware-interrupts-on-timeout-and-keeps-the-session
  (let [st (c/create-state) cwd (:root *rich*)]
    (try
      (is (:interrupt? (c/connect! st {:cwd cwd})))
      (c/eval! st {:cwd cwd :code "(def kept 7)"})
      (let [r (c/eval! st {:cwd cwd :timeout-ms 300 :code "(loop [] (recur))"})]
        (is (true? (:error? r)))
        (is (:interrupted? r)))
      (is (:connected? (c/status st)))
      (is (= "7" (:value (c/eval! st {:cwd cwd :code "kept"}))))
      (finally (c/disconnect! st)))))

(deftest explicit-interrupt-and-busy
  (let [st (c/create-state) cwd (:root *rich*)]
    (try
      (c/connect! st {:cwd cwd})
      (let [task (future (c/eval! st {:cwd cwd :code "(loop [] (recur))"}))]
        (loop [i 0] (when (and (< i 200) (not (:busy? (c/status st)))) (Thread/sleep 10) (recur (inc i))))
        (is (= :busy (:type (thrown-data #(c/eval! st {:cwd cwd :code "1"})))))
        (is (:interrupted? (c/interrupt! st)))
        (let [r (deref task 5000 ::timeout)]
          (is (not= ::timeout r))
          (is (true? (:error? r)))))
      (is (= "2" (:value (c/eval! st {:cwd cwd :code "(inc 1)"}))))
      (finally (c/disconnect! st)))))

(deftest missing-port-file-is-a-clear-error
  (let [st (c/create-state)
        dir (str (fs/create-temp-dir {:prefix "jnm-empty"}))]
    (try
      (is (= :missing-port (:type (thrown-data #(c/eval! st {:cwd dir :code "1"})))))
      (finally (fs/delete-tree dir {:force true})))))

;; --- per-form eval, print caps, lookup, completion, tests -------------------

(deftest each-form-gets-its-own-output-and-value
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (let [r (c/eval! st {:cwd cwd :code "(println \"a\") 1 (println \"b\") (+ 1 1)"})]
        (is (= ["nil" "1" "nil" "2"] (mapv :value (:forms r))))
        (is (= ["a\n" "" "b\n" ""] (mapv :out (:forms r))))
        (is (= ["(println \"a\")" "1" "(println \"b\")" "(+ 1 1)"] (mapv :code (:forms r))))
        (testing "the totals stay as they were"
          (is (= "2" (:value r)))
          (is (= "a\nb\n" (:out r)))))
      (testing "a namespace switch carries into the next form"
        (let [r (c/eval! st {:cwd cwd :code "(ns demo.per-form) (str *ns*)"})]
          (is (= "\"demo.per-form\"" (:value r)))
          (is (= "demo.per-form" (:ns r))))
        (c/eval! st {:cwd cwd :code "(ns user)"}))
      (testing "the first error stops the rest"
        (c/eval! st {:cwd cwd :code "(def z 1)"})
        (let [r (c/eval! st {:cwd cwd :code "(def z 2) (/ 1 0) (def z 3)"})]
          (is (true? (:error? r)))
          (is (= 1 (:skipped r)))
          (is (= 2 (count (:forms r))))
          (is (= "2" (:value (c/eval! st {:cwd cwd :code "z"}))))))
      (testing "code that can't be split here is sent whole"
        (let [r (c/eval! st {:cwd cwd :code "(require '[clojure.string :as s]) ::s/x"})]
          (is (nil? (:forms r)))
          (is (= ":clojure.string/x" (:value r)))))
      (finally (c/disconnect! st)))))

(deftest omitted-cwd-means-the-connected-project
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (c/eval! st {:cwd cwd :code "(def here 1)"})
      (is (= "1" (:value (c/eval! st {:code "here"}))))
      (is (= (str (fs/real-path cwd)) (:root (c/status st))))
      (testing "and after a dropped connection, the last project"
        (c/disconnect! st)
        (c/connect! st {:cwd cwd})
        (#'c/drop-conn! st)
        (is (= "1" (:value (c/eval! st {:code "here"})))))
      (finally (c/disconnect! st)))))

(deftest printing-is-capped-so-infinite-seqs-return
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (let [r (c/eval! st {:cwd cwd :timeout-ms 10000 :code "(range)"})]
        (is (false? (:error? r)))
        (is (str/ends-with? (:value r) "...)")))
      (is (= {:length c/default-print-length :level c/default-print-level} (:print (c/status st))))
      (finally (c/disconnect! st)))))

(deftest timeouts-say-what-probably-happened
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (is (re-find #"infinite loop" (ex-message (try (c/eval! st {:cwd cwd :timeout-ms 100 :code "(Thread/sleep 400)"})
                                                     (catch clojure.lang.ExceptionInfo e e)))))
      (finally (c/disconnect! st)))))

(deftest looks-up-vars-with-or-without-the-middleware
  (doseq [srv [*base* *rich*]]
    (let [st (c/create-state) cwd (:root srv)]
      (try
        (let [i (c/lookup! st {:cwd cwd :sym "clojure.string/join"})]
          (is (= "clojure.string" (:ns i)))
          (is (= "join" (:name i)))
          (is (= "([coll] [separator coll])" (:arglists i)))
          (is (re-find #"separated by" (:doc i)))
          (is (integer? (:line i))))
        (testing "aliases resolve in the given ns"
          (c/eval! st {:cwd cwd :code "(ns demo.lk (:require [clojure.string :as s]))"})
          (is (= "join" (:name (c/lookup! st {:cwd cwd :sym "s/join" :ns "demo.lk"})))))
        (is (nil? (c/lookup! st {:cwd cwd :sym "no.such/thing"})))
        (testing "a lookup does not move the session's namespace"
          (is (= "demo.lk" (:ns (c/status st)))))
        (finally (c/disconnect! st))))))

(deftest completes-prefixes
  (let [st (c/create-state) cwd (:root *base*)]
    (try
      (is (some #{{:candidate "clojure.string/join" :ns "clojure.string"}}
                (c/complete! st {:cwd cwd :prefix "clojure.string/jo"})))
      (is (some #(= "reduce" (:candidate %)) (c/complete! st {:cwd cwd :prefix "redu"})))
      (finally (c/disconnect! st)))))

(deftest runs-tests-and-returns-structured-results
  (let [st (c/create-state) cwd (:root *base*)]
    (spit (str cwd "/src/demo/t_test.clj")
          (str "(ns demo.t-test (:require [clojure.test :refer [deftest is testing]]))\n"
               "(deftest ok (is (= 1 1)))\n"
               "(deftest bad (testing \"ctx\" (is (= 1 2) \"msg\")))\n"
               "(deftest boom (throw (ex-info \"kaboom\" {:k 1})))\n"))
    (try
      (let [{:keys [counts problems]} (c/test! st {:cwd cwd :ns ["demo.t-test"]})]
        (is (= {:test 3 :pass 1 :fail 1 :error 1} counts))
        (let [fail (first (filter #(= :fail (:type %)) problems))]
          (is (= 'demo.t-test/bad (:var fail)))
          (is (= "ctx" (:context fail)))
          (is (= "msg" (:message fail)))
          (is (= "(= 1 2)" (:expected fail)))
          (is (= 3 (:line fail))))
        (let [err (first (filter #(= :error (:type %)) problems))]
          (is (= 'demo.t-test/boom (:var err)))
          (is (re-find #"kaboom" (:actual err)))
          (is (re-find #"\{:k 1\}" (:actual err)))))
      (testing "selected vars only"
        (is (= {:test 1 :pass 1 :fail 0 :error 0}
               (:counts (c/test! st {:cwd cwd :vars ["demo.t-test/ok"]})))))
      (testing "a namespace that does not load is an error result"
        (is (re-find #"not.there" (str (:error (c/test! st {:cwd cwd :ns ["not.there"]}))))))
      (finally (c/disconnect! st)))))

(deftest alias-keywords-are-not-refused-as-syntax-errors
  ;; they read only in the namespace that has the alias, which this process
  ;; is not: the server decides
  (let [st (c/create-state) cwd (:root *base*)
        f (str cwd "/src/demo/kw.clj")]
    (try
      (spit f "(ns demo.kw (:require [clojure.string :as s]))\n(def k ::s/x)\n")
      (is (false? (:error? (c/load-file! st {:cwd cwd :file "src/demo/kw.clj"}))))
      (is (= ":clojure.string/x" (:value (c/eval! st {:cwd cwd :ns "demo.kw" :code "::s/x"}))))
      (testing "the server still reports real reader errors"
        (is (true? (:error? (c/eval! st {:cwd cwd :ns "demo.kw" :code "::nope/x"})))))
      (finally (c/disconnect! st)))))
