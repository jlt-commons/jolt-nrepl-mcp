(ns jolt-nrepl-mcp.server-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt-nrepl-mcp.client :as client]
            [jolt-nrepl-mcp.client-test :as ct]
            [jolt-nrepl-mcp.discover :as discover]
            [jolt-nrepl-mcp.server :as s]))

(defn call [state tool args]
  (let [{:keys [content isError]} (s/call-tool state tool args)]
    {:text (:text (first content)) :error? isError}))

(deftest lists-the-tools
  (is (= #{"nrepl_eval" "nrepl_load_file" "nrepl_start" "nrepl_stop" "nrepl_connect"
           "nrepl_status" "nrepl_interrupt" "nrepl_disconnect"
           "nrepl_lookup" "nrepl_complete" "nrepl_test"}
         (set (map :name s/tool-definitions)))))

(deftest truncate-keeps-head-and-tail
  (is (= "abc" (s/truncate "abc" 10)))
  (let [t (s/truncate (apply str (repeat 1000 "x")) 100)]
    (is (< (count t) 160))
    (is (re-find #"900 chars elided" t))))

(deftest eval-text-is-compact
  (let [{:keys [root] :as srv} (ct/start-server! false)
        st (client/create-state)]
    (try
      (let [{:keys [text error?]} (call st "nrepl_eval" {"cwd" root "code" "(println \"hi\") (+ 1 2"})]
        (is (false? error?))
        (is (= (str "repaired: auto-closed 1 unclosed delimiter(s) at a trailing truncation: appended `)`."
                    " If that placement is wrong, resend the corrected text.\n"
                    "-- (println \"hi\")\nout:\nhi\n=> nil\n-- (+ 1 2)\n=> 3\nns: user")
               text)))
      (let [{:keys [text error?]} (call st "nrepl_eval" {"cwd" root "code" "(/ 1 0)"})]
        (is (true? error?))
        (is (re-find #"(?s)err:\n.*Divide by zero" text))
        (is (re-find #"ex: class java.lang.ArithmeticException" text)))
      (let [{:keys [text]} (call st "nrepl_eval" {"cwd" root "code" "(apply str (repeat 500 \\a))"
                                                 "maxOutputChars" 100})]
        (is (re-find #"chars elided" text)))
      (testing "nrepl_load_file reloads an edited file"
        (let [f (str root "/src/demo/r.clj")]
          (spit f "(ns demo.r)\n(defn v [] 1)\n")
          (is (false? (:error? (call st "nrepl_load_file" {"cwd" root "file" "src/demo/r.clj"}))))
          (spit f "(ns demo.r)\n(defn v [] 2)\n")
          (let [{:keys [text error?]} (call st "nrepl_load_file" {"cwd" root "file" "src/demo/r.clj"})]
            (is (false? error?) text))
          (is (str/includes? (:text (call st "nrepl_eval" {"cwd" root "code" "(demo.r/v)"})) "=> 2"))))
      (finally
        (client/disconnect! st)
        (ct/stop-server! srv)))))

(deftest start-connects-and-stop-kills
  (let [root (str (fs/create-temp-dir {:prefix "jnm-start"}))
        st (client/create-state)]
    (try
      (spit (str root "/deps.edn") "{:paths [\"src\"]}")
      (fs/create-dirs (str root "/src"))
      (let [{:keys [text error?]} (call st "nrepl_start" {"cwd" (str root "/src")})]
        (is (false? error?) text)
        (is (re-find #"started" text)))
      (is (str/includes? (:text (call st "nrepl_eval" {"cwd" root "code" "(+ 40 2)"})) "=> 42"))
      (is (re-find #"already running" (:text (call st "nrepl_start" {"cwd" root}))))
      (let [{:keys [text error?]} (call st "nrepl_stop" {"cwd" root})]
        (is (false? error?) text)
        (is (re-find #"stopped" text)))
      (is (not (fs/exists? (str root "/.nrepl-port"))))
      (finally
        (client/disconnect! st)
        (when (fs/exists? (str root "/.jolt/nrepl.pid"))
          (p/shell {:continue true} "kill" (str/trim (slurp (str root "/.jolt/nrepl.pid")))))
        (fs/delete-tree root {:force true})))))

;; --- the real stdio process -------------------------------------------------

(defn- send-json [w msg] (.write w (str (json/write-str msg) "\n")) (.flush w))

(defn- read-json [r]
  (let [line (deref (future (.readLine r)) 20000 ::timeout)]
    (when (= ::timeout line) (throw (ex-info "timed out reading MCP response" {})))
    (json/read-str line :key-fn keyword)))

(deftest stdio-server-end-to-end
  (let [{:keys [root] :as srv} (ct/start-server! true)
        mcp (p/process ["jolt" "-m" "jolt-nrepl-mcp.server"]
                       {:dir (str (fs/cwd)) :out :pipe :err :inherit :shutdown p/destroy-tree})]
    (try
      (let [w (io/writer (:in mcp)) r (io/reader (:out mcp))
            req (fn [id method params]
                  (send-json w (cond-> {:jsonrpc "2.0" :id id :method method} params (assoc :params params)))
                  (read-json r))
            call (fn [id tool args] (req id "tools/call" {:name tool :arguments args}))]
        (is (= "2025-06-18" (get-in (req 1 "initialize" {:protocolVersion "2025-06-18"}) [:result :protocolVersion])))
        (send-json w {:jsonrpc "2.0" :method "notifications/initialized"})
        (is (= 11 (count (get-in (req 2 "tools/list" nil) [:result :tools]))))
        (is (str/includes? (get-in (call 3 "nrepl_eval" {:cwd root :code "(str \"😀\" (+ 1 2))"})
                                   [:result :content 0 :text])
                           "=> \"😀3\""))
        (testing "interrupt runs while an eval is in flight"
          (send-json w {:jsonrpc "2.0" :id "spin" :method "tools/call"
                        :params {:name "nrepl_eval" :arguments {:cwd root :code "(loop [] (recur))"}}})
          (Thread/sleep 300)
          (send-json w {:jsonrpc "2.0" :id "int" :method "tools/call"
                        :params {:name "nrepl_interrupt" :arguments {}}})
          (let [rs [(read-json r) (read-json r)]]
            (is (= #{"spin" "int"} (set (map :id rs))))
            (is (true? (get-in (first (filter #(= "spin" (:id %)) rs)) [:result :isError]))))
          (is (str/includes? (get-in (call 4 "nrepl_eval" {:cwd root :code "42"}) [:result :content 0 :text])
                             "=> 42")))
        (.close w)
        (is (not= ::timeout (deref mcp 10000 ::timeout))))
      (finally
        (p/destroy-tree mcp)
        (ct/stop-server! srv)))))

;; --- per-form rendering, pprint, lookup/complete/test, auto-start, scan -----

(deftest each-form-is-rendered-with-its-own-output-and-value
  (let [{:keys [root] :as srv} (ct/start-server! false)
        st (client/create-state)]
    (try
      (is (= (str "-- (println \"a\")\nout:\na\n=> nil\n"
                  "-- (+ 1 2)\n=> 3\n"
                  "ns: user")
             (:text (call st "nrepl_eval" {"cwd" root "code" "(println \"a\") (+ 1 2)"}))))
      (let [{:keys [text error?]} (call st "nrepl_eval" {"cwd" root "code" "(def q 1) (/ 1 0) (def q 2)"})]
        (is (true? error?))
        (is (re-find #"(?s)-- \(/ 1 0\)\nerr:\n.*Divide by zero.*\nex: .*ArithmeticException" text))
        (is (re-find #"skipped: 1 form\(s\) after the error" text)))
      (testing "pprint"
        (is (re-find #"=> \{:a \"x+\",\n :b"
                     (:text (call st "nrepl_eval" {"cwd" root "pprint" true
                                                   "code" "(into (sorted-map) (zipmap [:a :b :c :d :e :f :g :h] (repeat (apply str (repeat 12 \"x\")))))"}))))
        (testing "a value that doesn't read is shown as it is"
          (is (re-find #"=> #object" (:text (call st "nrepl_eval" {"cwd" root "pprint" true "code" "(atom 1)"}))))))
      (testing "lookup, complete, test"
        (is (re-find #"(?s)clojure.string/join\n\(\[coll\] \[separator coll\]\)\n.*separated by.*\nclojure/string.clj:50"
                     (:text (call st "nrepl_lookup" {"cwd" root "sym" "clojure.string/join"}))))
        (let [{:keys [text error?]} (call st "nrepl_lookup" {"cwd" root "sym" "nope/nothing"})]
          (is (true? error?))
          (is (re-find #"not found" text)))
        (is (re-find #"clojure.string/join" (:text (call st "nrepl_complete" {"cwd" root "prefix" "clojure.string/jo"}))))
        (spit (str root "/src/demo/s_test.clj")
              "(ns demo.s-test (:require [clojure.test :refer [deftest is]]))\n(deftest good (is (= 1 1)))\n(deftest nope (is (= 1 2)))\n")
        (let [{:keys [text error?]} (call st "nrepl_test" {"cwd" root "ns" "demo.s-test"})]
          (is (true? error?))
          (is (re-find #"2 tests, 1 passed, 1 failed, 0 errors" text))
          (is (re-find #"FAIL demo.s-test/nope" text))
          (is (re-find #"expected: \(= 1 2\)" text)))
        (let [{:keys [text error?]} (call st "nrepl_test" {"cwd" root "vars" ["demo.s-test/good"]})]
          (is (false? error?))
          (is (re-find #"1 tests, 1 passed" text))))
      (testing "status can list running servers"
        (let [port (:port (client/status st))
              {:keys [text]} (call st "nrepl_status" {"scan" true})]
          (is (re-find (re-pattern (str "port " port)) text))))
      (finally
        (client/disconnect! st)
        (ct/stop-server! srv)))))

(deftest eval-starts-a-server-when-the-project-has-none
  (let [root (str (fs/create-temp-dir {:prefix "jnm-auto"}))
        st (client/create-state)]
    (try
      (spit (str root "/deps.edn") "{:paths [\"src\"]}")
      (let [{:keys [text error?]} (call st "nrepl_eval" {"cwd" root "code" "(+ 1 2)"})]
        (is (false? error?) text)
        (is (re-find #"started nREPL" text))
        (is (re-find #"=> 3" text)))
      (finally
        (call st "nrepl_stop" {"cwd" root})
        (fs/delete-tree root {:force true}))))
  (testing "no deps.edn, no start"
    (let [dir (str (fs/create-temp-dir {:prefix "jnm-noproj"}))
          st (client/create-state)]
      (try
        (let [{:keys [text error?]} (call st "nrepl_eval" {"cwd" dir "code" "1"})]
          (is (true? error?))
          (is (re-find #"missing-port" text)))
        (finally (fs/delete-tree dir {:force true}))))))

(deftest parses-lsof-listeners
  (is (= [{:command "jolt" :pid 78627 :port 58762}
          {:command "java" :pid 5 :port 7888}
          {:command "Joplin" :pid 6 :port 2658}]
         (discover/parse-lsof
          (str "COMMAND     PID    USER   FD   TYPE  DEVICE SIZE/OFF NODE NAME\n"
               "jolt      78627 me    3u  IPv4 0x51      0t0  TCP 127.0.0.1:58762 (LISTEN)\n"
               "java          5 me   10u  IPv6 0x1e      0t0  TCP *:7888 (LISTEN)\n"
               "java          5 me   11u  IPv4 0x1f      0t0  TCP *:7888 (LISTEN)\n"
               "Joplin        6 me   31u  IPv6 0x2a      0t0  TCP [::1]:2658 (LISTEN)\n")))))

(deftest pretty-leaves-flat-seqs-and-unreadable-values-alone
  (is (= "(1 2 3)" (s/pretty "(1 2 3)")))
  (is (= "#object[Foo]" (s/pretty "#object[Foo]")))
  (is (= "{:a [1 2]}" (s/pretty "{:a [1 2]}"))))
