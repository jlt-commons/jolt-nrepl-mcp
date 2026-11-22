(ns jolt-nrepl-mcp.jvm-test
  "Against a real JVM Clojure nREPL server (nrepl/nrepl), when the `clojure`
  CLI is on PATH; skipped otherwise. A JVM session binds the print vars per
  session, so it catches print caps that only reach the root."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt-nrepl-mcp.client :as c]
            [jolt-nrepl-mcp.client-test :refer [wait-for-port stop-server!]]))

(def ^:dynamic *jvm* nil)

(defn start-jvm-server! []
  (let [root (str (fs/create-temp-dir {:prefix "jnm-jvm"}))
        cfg (str (fs/create-temp-dir {:prefix "jnm-jvm-cfg"}))]
    (fs/create-dirs (str root "/src/demo"))
    (spit (str root "/deps.edn")
          "{:paths [\"src\"] :deps {org.clojure/clojure {:mvn/version \"1.12.3\"} nrepl/nrepl {:mvn/version \"1.3.1\"}}}")
    (spit (str root "/src/demo/jvm.clj")
          "(ns demo.jvm (:require [clojure.test :refer [deftest is]]))\n(defn greet \"Say hi.\" [n] (str \"hi \" n))\n(deftest greets (is (= \"hi x\" (greet \"x\"))))\n")
    ;; a small heap, so a print cap that doesn't hold fails fast instead of
    ;; eating memory; CLJ_CONFIG keeps the CLI out of the user's config dir
    (let [proc (p/process ["clojure" "-J-Xmx256m" "-M" "-m" "nrepl.cmdline"]
                          {:dir root :out :string :err :string :shutdown p/destroy-tree
                           :extra-env {"CLJ_CONFIG" cfg}})]
      (try
        (wait-for-port root)
        (catch Throwable e
          (p/destroy-tree proc)
          (fs/delete-tree root {:force true})
          (fs/delete-tree cfg {:force true})
          (throw e)))
      {:root root :proc proc :cfg cfg})))

(use-fixtures :once
  (fn [t]
    (if-not (fs/which "clojure")
      (println "jvm-test: no clojure CLI on PATH, skipping")
      (let [srv (start-jvm-server!)]
        (try
          (binding [*jvm* srv] (t))
          (finally
            (stop-server! srv)
            (fs/delete-tree (:cfg srv) {:force true})))))))

(deftest jvm-session-printing-is-capped
  (when *jvm*
    (let [st (c/create-state) cwd (:root *jvm*)]
      (try
        (let [r (c/eval! st {:cwd cwd :timeout-ms 20000 :code "(range)"})]
          (is (false? (:error? r)))
          (is (str/ends-with? (str (:value r)) "...)")))
        (testing "the cap is the session's, so it holds on the next eval"
          (is (= (str c/default-print-length) (:value (c/eval! st {:cwd cwd :code "*print-length*"})))))
        (is (= {:length c/default-print-length :level c/default-print-level} (:print (c/status st))))
        (finally (c/disconnect! st))))))

(deftest jvm-lookup-and-tests-work
  (when *jvm*
    (let [st (c/create-state) cwd (:root *jvm*)]
      (try
        (c/eval! st {:cwd cwd :code "(require 'demo.jvm) (in-ns 'demo.jvm)"})
        (is (= "Say hi." (:doc (c/lookup! st {:cwd cwd :sym "demo.jvm/greet"}))))
        (let [{:keys [counts]} (c/test! st {:cwd cwd :ns ["demo.jvm"]})]
          (is (= 1 (:test counts)))
          (is (= 0 (+ (:fail counts) (:error counts)))))
        (testing "the session's ns survives the lookup and the test run"
          (is (= "\"demo.jvm\"" (:value (c/eval! st {:cwd cwd :code "(clojure.core/str clojure.core/*ns*)"})))))
        (finally (c/disconnect! st))))))
