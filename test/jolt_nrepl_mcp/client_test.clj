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
  (fs/delete-tree root {:force true}))

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
