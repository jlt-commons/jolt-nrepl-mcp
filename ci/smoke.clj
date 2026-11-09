;; Drive a built jolt-nrepl-mcp binary over stdio from a scratch project
;; outside the repo, so a binary that still needs the source tree fails here.
;; Exercises the protocol, a real nREPL server (nrepl_start needs `jolt` on
;; PATH), eval, syntax repair, structural replace with reload, and stop.
;; Usage: jolt ci/smoke.clj path/to/jolt-nrepl-mcp
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def bin (str (fs/absolutize (first *command-line-args*))))
(def proj (str (fs/create-temp-dir {:prefix "jnm-smoke"})))
(spit (str proj "/deps.edn") "{:paths [\"src\"]}")
(fs/create-dirs (str proj "/src/smoke"))
(spit (str proj "/src/smoke/core.clj") "(ns smoke.core)\n\n(defn greet [n]\n  (str \"hi \"\n       n))\n")

(def proc (p/process [bin] {:dir proj :out :pipe :err :inherit}))
(def w (io/writer (:in proc)))
(def r (io/reader (:out proc)))
(def failures (atom 0))

(defn call [id method params]
  (.write w (str (json/write-str {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
  (.flush w)
  (let [line (deref (future (.readLine r)) 180000 ::timeout)]
    (when (or (= ::timeout line) (nil? line))
      (println "FAIL: no response to" method) (System/exit 1))
    (json/read-str line :key-fn keyword)))

(defn tool [id name args]
  (let [res (:result (call id "tools/call" {:name name :arguments args}))]
    (println (str "== " name ": " (str/replace (get-in res [:content 0 :text]) "\n" " | ")))
    res))

(defn check [what ok?]
  (when-not ok? (swap! failures inc) (println "FAIL:" what)))

(try
  (check "initialize" (= "2025-06-18" (get-in (call 1 "initialize" {:protocolVersion "2025-06-18"})
                                               [:result :protocolVersion])))
  (check "tools/list" (= 10 (count (get-in (call 2 "tools/list" {}) [:result :tools]))))
  (check "nrepl_start" (not (:isError (tool 3 "nrepl_start" {:cwd proj}))))
  (let [res (tool 4 "nrepl_eval" {:code "(println \"out\") (+ 40 (inc 1"})]
    (check "eval value" (str/includes? (get-in res [:content 0 :text]) "=> 42"))
    (check "eval repair" (str/includes? (get-in res [:content 0 :text]) "auto-closed 2")))
  (check "eval refuses mid-text imbalance"
         (:isError (tool 5 "nrepl_eval" {:code "(defn f [] 1)) (g)"})))
  (check "load_file" (not (:isError (tool 6 "nrepl_load_file" {:file "src/smoke/core.clj"}))))
  (check "clj_replace + load"
         (not (:isError (tool 7 "clj_replace" {:file "src/smoke/core.clj" :old "(str \"hi \" n)"
                                               :new "(str \"hello \" n)" :load true}))))
  (let [text (get-in (tool 8 "nrepl_eval" {:code "(smoke.core/greet \"ci\")"}) [:content 0 :text])]
    (check "replaced code runs" (str/includes? text "hello ci"))
    (check "loads leave the session in user" (str/includes? text "ns: user")))
  (check "nrepl_stop" (not (:isError (tool 9 "nrepl_stop" {:cwd proj}))))
  (.close w)
  (check "clean exit" (= 0 (:exit (deref proc 10000 {:exit :timeout}))))
  (finally
    (p/destroy-tree proc)
    (fs/delete-tree proj {:force true})))

(if (zero? @failures)
  (println "smoke: ok")
  (do (println "smoke:" @failures "failure(s)") (System/exit 1)))
