(ns jolt-nrepl-mcp.server-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt-nrepl-mcp.client :as client]
            [jolt-nrepl-mcp.client-test :as ct]
            [jolt-nrepl-mcp.server :as s]))

(defn call [state tool args]
  (let [{:keys [content isError]} (s/call-tool state tool args)]
    {:text (:text (first content)) :error? isError}))

(deftest lists-the-tools
  (is (= #{"nrepl_eval" "nrepl_load_file" "nrepl_start" "nrepl_stop" "nrepl_connect"
           "nrepl_status" "nrepl_interrupt" "nrepl_disconnect" "clj_check" "clj_replace"}
         (set (map :name s/tool-definitions)))))

(deftest truncate-keeps-head-and-tail
  (is (= "abc" (s/truncate "abc" 10)))
  (let [t (s/truncate (apply str (repeat 1000 "x")) 100)]
    (is (< (count t) 160))
    (is (re-find #"900 chars elided" t))))

(deftest clj-check-diagnoses-and-repairs
  (let [st (client/create-state)]
    (testing "code"
      (is (= {:text "ok: reads as Clojure" :error? false} (call st "clj_check" {"code" "(inc 1)"})))
      (let [{:keys [text error?]} (call st "clj_check" {"code" "(inc (dec 1"})]
        (is (false? error?))
        (is (str/includes? text "(inc (dec 1))"))
        (is (str/includes? text "auto-closed 2")))
      (let [{:keys [text error?]} (call st "clj_check" {"code" "(f [x)"})]
        (is (true? error?))
        (is (re-find #"line 1, col 6" text))))
    (testing "file, fixed in place"
      (let [dir (str (fs/create-temp-dir {:prefix "jnm-check"}))
            f (str dir "/a.clj")]
        (try
          (spit f "(ns a)\n(defn f [x]\n  (let [y 1\n    (+ x y)))\n")
          (let [{:keys [text error?]} (call st "clj_check" {"file" f})]
            (is (false? error?))
            (is (re-find #"repairable" text))
            (is (= "(ns a)\n(defn f [x]\n  (let [y 1\n    (+ x y)))\n" (slurp f)) "no write without fix"))
          (let [{:keys [text]} (call st "clj_check" {"file" f "fix" true})]
            (is (re-find #"wrote" text))
            (is (= "(ns a)\n(defn f [x]\n  (let [y 1]\n    (+ x y)))\n" (slurp f))))
          (finally (fs/delete-tree dir {:force true})))))))

(deftest clj-replace-edits-structurally
  (let [st (client/create-state)
        dir (str (fs/create-temp-dir {:prefix "jnm-replace"}))
        f (str dir "/a.clj")]
    (try
      (spit f "(ns a)\n\n(defn f [x]\n  (+ x\n     1))\n")
      (let [{:keys [text error?]} (call st "clj_replace" {"file" f "old" "(+ x 1)" "new" "(+ x 2)"})]
        (is (false? error?))
        (is (re-find #"replaced 1 form at line 4, col 3" text))
        (is (= "(ns a)\n\n(defn f [x]\n  (+ x 2))\n" (slurp f))))
      (let [{:keys [text error?]} (call st "clj_replace" {"file" f "old" "(nope)" "new" "(x)"})]
        (is (true? error?))
        (is (re-find #"no form reads equal" text)))
      (finally (fs/delete-tree dir {:force true})))))

(deftest eval-text-is-compact
  (let [{:keys [root] :as srv} (ct/start-server! false)
        st (client/create-state)]
    (try
      (let [{:keys [text error?]} (call st "nrepl_eval" {"cwd" root "code" "(println \"hi\") (+ 1 2"})]
        (is (false? error?))
        (is (= (str "repaired: auto-closed 1 unclosed delimiter(s) at a trailing truncation: appended `)`."
                    " If that placement is wrong, resend the corrected text.\nout:\nhi\n=> 3\nns: user")
               text)))
      (let [{:keys [text error?]} (call st "nrepl_eval" {"cwd" root "code" "(/ 1 0)"})]
        (is (true? error?))
        (is (re-find #"(?s)err:\n.*Divide by zero" text))
        (is (re-find #"ex: class java.lang.ArithmeticException" text)))
      (let [{:keys [text]} (call st "nrepl_eval" {"cwd" root "code" "(apply str (repeat 500 \\a))"
                                                 "maxOutputChars" 100})]
        (is (re-find #"chars elided" text)))
      (testing "clj_replace can reload the file it edited"
        (let [f (str root "/src/demo/r.clj")]
          (spit f "(ns demo.r)\n(defn v [] 1)\n")
          (let [{:keys [text error?]} (call st "clj_replace" {"cwd" root "file" "src/demo/r.clj"
                                                              "old" "(defn v [] 1)" "new" "(defn v [] 2)"
                                                              "load" true})]
            (is (false? error?) text)
            (is (re-find #"loaded" text)))
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
        (is (= 10 (count (get-in (req 2 "tools/list" nil) [:result :tools]))))
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
