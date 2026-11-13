(ns jolt-nrepl-mcp.server
  "The MCP server: tool definitions, compact text rendering, and stdio loop."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [jolt-nrepl-mcp.client :as client]
            [jolt-nrepl-mcp.discover :as discover]
            [jolt-nrepl-mcp.forms :as forms]
            [jolt-nrepl-mcp.mcp :as mcp]))

(def ^:private default-max-output 20000)

(def ^:private cwd-prop
  {:type "string"
   :description "Absolute path inside the project. The server's .nrepl-port is found here or in a parent. Defaults to the last project used, else the MCP process directory."})

(def ^:private timeout-prop
  {:type "integer" :minimum 1 :maximum client/max-timeout-ms
   :description (str "Default " client/default-timeout-ms ". On timeout the eval is interrupted when the server supports it; it is never replayed.")})

(def tool-definitions
  [{:name "nrepl_eval"
    :description "Evaluate Clojure in the project's persistent nREPL session (connects lazily, and starts a server for a deps.edn project that has none). Use it first: reproduce a bug and try a fix here before editing files. Each top-level form is evaluated in turn and shown with its own stdout, stderr and value; the first error stops the rest. A dropped trailing paren is repaired and reported; any other syntax problem is refused with line/col before anything runs. The ns carries over between evals. Printing is capped (*print-length*, *print-level*), so an infinite seq returns."
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "One or more Clojure forms"}
                               :ns {:type "string" :description "Evaluate in this namespace instead of the current one"}
                               :cwd cwd-prop
                               :timeoutMs timeout-prop
                               :pprint {:type "boolean" :description "Pretty-print values (large maps and nested data)"}
                               :maxOutputChars {:type "integer" :minimum 200
                                                :description (str "Per-field output cap, default " default-max-output)}}
                  :required ["code"]}}
   {:name "nrepl_lookup"
    :description "A var's arglists, docstring and file:line, resolved in a namespace (aliases work). Use before calling an API you are unsure of, instead of doc/source snippets."
    :inputSchema {:type "object"
                  :properties {:sym {:type "string" :description "Symbol, e.g. str/join or my.ns/f"}
                               :ns {:type "string" :description "Resolve in this namespace (default: the current one)"}
                               :cwd cwd-prop}
                  :required ["sym"]}}
   {:name "nrepl_complete"
    :description "Vars and namespaces starting with a prefix, from the running image."
    :inputSchema {:type "object"
                  :properties {:prefix {:type "string" :description "e.g. clojure.string/jo, str/, redu"}
                               :ns {:type "string" :description "Complete in this namespace (default: the current one)"}
                               :cwd cwd-prop}
                  :required ["prefix"]}}
   {:name "nrepl_test"
    :description "Run clojure.test in the session and get counts plus each failure and error (var, testing context, expected, actual, line). Test namespaces are reloaded first. Give ns, or vars for single tests."
    :inputSchema {:type "object"
                  :properties {:ns {:type "array" :items {:type "string"} :description "Test namespaces, e.g. [\"my.app-test\"]"}
                               :vars {:type "array" :items {:type "string"} :description "Qualified test vars, e.g. [\"my.app-test/parses\"]"}
                               :reload {:type "boolean" :description "Reload the namespaces first, default true"}
                               :cwd cwd-prop
                               :timeoutMs timeout-prop
                               :maxOutputChars {:type "integer" :minimum 200}}}}
   {:name "nrepl_load_file"
    :description "Load (reload) a project source file into the session. The file's syntax is checked first and a broken file is refused with line/col."
    :inputSchema {:type "object"
                  :properties {:file {:type "string" :description "Path relative to the project root (or absolute inside it)"}
                               :cwd cwd-prop
                               :timeoutMs timeout-prop}
                  :required ["file"]}}
   {:name "nrepl_start"
    :description "Start `jolt nrepl-server` for the project (nearest deps.edn at or above cwd) in the background and connect. Reuses a server that is already running."
    :inputSchema {:type "object"
                  :properties {:cwd cwd-prop
                               :command {:type "string" :description "Launcher to run instead of `jolt`, e.g. a clone's bin/jolt"}
                               :waitMs {:type "integer" :minimum 1000 :description "How long to wait for the port, default 120000"}}}}
   {:name "nrepl_stop"
    :description "Stop an nREPL server that nrepl_start started for this project."
    :inputSchema {:type "object" :properties {:cwd cwd-prop}}}
   {:name "nrepl_connect"
    :description "Connect explicitly (switch projects, or a host/port without .nrepl-port). Usually unnecessary: eval connects lazily."
    :inputSchema {:type "object"
                  :properties {:cwd cwd-prop
                               :host {:type "string" :default "127.0.0.1"}
                               :port {:type "integer" :minimum 1 :maximum 65535}}}}
   {:name "nrepl_status"
    :description "Connection, project root, current ns, busy state, print caps and server ops. scan=true also lists the nREPL servers running on this machine (port, pid, directory): use it when .nrepl-port is missing or stale."
    :inputSchema {:type "object" :properties {:scan {:type "boolean"}}}}
   {:name "nrepl_interrupt"
    :description "Interrupt the running eval (needs the server's interrupt op)."
    :inputSchema {:type "object" :properties {}}}
   {:name "nrepl_disconnect"
    :description "Close the session and connection. Also the escape hatch for a stuck eval."
    :inputSchema {:type "object" :properties {}}}])

;; ---------------------------------------------------------------------------
;; rendering

(defn truncate [s max]
  (let [s (str s) n (count s)]
    (if (<= n max)
      s
      (let [head (quot (* 7 max) 10)
            tail (- max head)]
        (str (subs s 0 head) "\n…[" (- n max) " chars elided]…\n" (subs s (- n tail)))))))

(defn pretty
  "`value` (printed by the server) pretty-printed when it is a map or nested
  data, or as it is when it doesn't read here (#object, records, ...)."
  [value]
  (try
    (let [v (binding [*read-eval* false
                      *default-data-reader-fn* (fn [tag _] (throw (ex-info "tagged" {:tag tag})))]
              (read-string value))]
      ;; a flat seq of scalars only gets longer, one item per line
      (if (or (map? v) (and (coll? v) (some coll? v)))
        (str/trimr (binding [*print-length* nil *print-level* nil] (with-out-str (pprint/pprint v))))
        value))
    (catch Throwable _ value)))

(defn- result-lines [{:keys [out err value ex root-ex]} {:keys [max pprint?]}]
  (cond-> []
    (seq out) (conj (str "out:\n" (str/trimr (truncate out max))))
    (seq err) (conj (str "err:\n" (str/trimr (truncate err max))))
    (some? value) (conj (str "=> " (truncate (if pprint? (pretty value) value) max)))
    ex (conj (str "ex: " ex (when root-ex (str " (root: " root-ex ")"))))))

(defn- eval-text
  ([r max] (eval-text r max {}))
  ([{:keys [repair forms values ns ex error? interrupted? timed-out? skipped] :as r} max opts]
   (let [opts (assoc opts :max max)]
     (str/join "\n"
               (cond-> []
                 repair (conj (str "repaired: " repair))
                 forms (into (mapcat (fn [f] (into [(str "-- " (forms/label (:code f)))] (result-lines f opts))) forms))
                 (not forms) (into (result-lines r opts))
                 (and (not forms) values (> (count values) 1)) (conj (str "values: " (count values)))
                 skipped (conj (str "skipped: " skipped " form(s) after the error"))
                 timed-out? (conj (str "timed out; interrupted: " (boolean interrupted?) ". " client/timeout-hint))
                 (and error? (not ex) (not timed-out?)) (conj "error")
                 ns (conj (str "ns: " ns)))))))

(defn- lookup-text [{:keys [ns name arglists doc file line]}]
  (str/join "\n" (cond-> [(str ns "/" name)]
                   arglists (conj arglists)
                   doc (conj doc)
                   file (conj (str file (when line (str ":" line)))))))

(defn- test-text [{:keys [counts problems out error]} max]
  (if error
    (str "error: " (truncate error max))
    (let [{:keys [test pass fail error]} counts]
      (str/join "\n"
                (concat
                 [(str test " tests, " pass " passed, " fail " failed, " error " errors")]
                 (for [{:keys [type var context message expected actual line]} problems]
                   (str/join "\n"
                             (cond-> [(str (if (= :fail type) "FAIL " "ERROR ") var (when line (str " (line " line ")")))]
                               context (conj (str "  in: " context))
                               message (conj (str "  " message))
                               (and expected (not= "nil" expected)) (conj (str "  expected: " (truncate expected max)))
                               actual (conj (str "  actual: " (truncate actual max))))))
                 (when (seq out) [(str "out:\n" (str/trimr (truncate out max)))]))))))

(defn- scan-text [servers root]
  (if (map? servers)
    (:error servers)
    (str "servers:"
         (if (empty? servers)
           " none found"
           (apply str (for [{:keys [port pid command dir ops]} servers]
                        (str "\n  port " port " pid " pid " " command " " dir
                             (when (= dir root) " (this project)")
                             (when (some #{"interrupt"} ops) " [interrupt]"))))))))

(defn- arg [args k]
  (let [v (get args (name k) (get args k))]
    (if (and (string? v) (str/blank? v) (not= k :new)) nil v)))

(defn- resolve-file [state cwd file]
  (cond
    (fs/absolute? file) file
    cwd (str (fs/path cwd file))
    (:root @state) (str (fs/path (:root @state) file))
    :else (str (fs/path (client/default-dir) file))))

;; ---------------------------------------------------------------------------
;; tools

(defn- project-dir [cwd]
  (let [dir (client/canonical-dir cwd)]
    (or (loop [d (fs/path dir)]
          (cond (nil? d) nil
                (fs/exists? (fs/path d "deps.edn")) (str d)
                :else (recur (fs/parent d))))
        dir)))

(defn- reachable? [state cwd]
  (try (client/connect! state {:cwd cwd}) true (catch Throwable _ false)))

(defn- start [state args]
  (let [root (project-dir (arg args :cwd))
        port-file (str root "/.nrepl-port")
        wait-ms (or (arg args :waitMs) 120000)]
    (if (and (fs/exists? port-file) (reachable? state root))
      (mcp/tool-result (str "already running for " root " on port " (:port (client/status state))))
      (do
        (fs/delete-if-exists port-file)
        (fs/create-dirs (str root "/.jolt"))
        (let [cmd (or (arg args :command) "jolt")
              pid (-> (p/shell {:dir root :out :string}
                               "sh" "-c" (str "nohup " cmd " nrepl-server 0 >> .jolt/nrepl.log 2>&1 < /dev/null & echo $!"))
                      :out str/trim)
              deadline (+ (System/currentTimeMillis) wait-ms)]
          (spit (str root "/.jolt/nrepl.pid") pid)
          (loop []
            (cond
              (and (fs/exists? port-file) (reachable? state root))
              (mcp/tool-result (str "started nREPL for " root " on port " (:port (client/status state))
                                    " (pid " pid ", log .jolt/nrepl.log)"))

              (< deadline (System/currentTimeMillis))
              (mcp/tool-result (str "nREPL did not come up within " wait-ms "ms. Log tail:\n"
                                    (let [log (str root "/.jolt/nrepl.log")]
                                      (when (fs/exists? log)
                                        (truncate (slurp log) 3000))))
                               :error? true)

              :else (do (Thread/sleep 100) (recur)))))))))

(defn- stop [state args]
  (let [root (project-dir (arg args :cwd))
        pid-file (str root "/.jolt/nrepl.pid")]
    (if-not (fs/exists? pid-file)
      (mcp/tool-result (str "no server started by nrepl_start in " root) :error? true)
      (let [pid (str/trim (slurp pid-file))]
        (when (= root (:root @state)) (client/disconnect! state))
        (p/shell {:continue true :out :string :err :string} "kill" pid)
        (fs/delete-if-exists pid-file)
        (fs/delete-if-exists (str root "/.nrepl-port"))
        (mcp/tool-result (str "stopped nREPL (pid " pid ") for " root))))))

(defn- exception-result [e]
  (let [{:keys [type] :as data} (ex-data e)]
    (mcp/tool-result (str (when type (str (name type) ": ")) (ex-message e))
                     :error? true)))

(defn- autostart? []
  (not= "0" (System/getenv "JOLT_NREPL_MCP_AUTOSTART")))

(defn- with-server
  "Run `f`; when it fails because the project has no running server (no
  .nrepl-port, or a stale one) and the project has a deps.edn, start one and
  run `f` again. Returns [started-message-or-nil result]."
  [state cwd f]
  (try
    [nil (f)]
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [type]} (ex-data e)
            root (when (and (#{:missing-port :connection-refused} type) (autostart?))
                   (try (project-dir cwd) (catch Throwable _ nil)))]
        (if (and root (fs/exists? (fs/path root "deps.edn")))
          (let [r (start state {"cwd" root})]
            (if (:isError r)
              (throw e)
              [(:text (first (:content r))) (f)]))
          (throw e))))))

(defn- with-started [started {:keys [content] :as result}]
  (if started
    (assoc-in result [:content 0 :text] (str started "\n" (:text (first content))))
    result))

(defn- strings [v] (cond (nil? v) nil (string? v) [v] :else (vec v)))

(defn call-tool [state tool args]
  (try
    (let [cwd (arg args :cwd)
          max (or (arg args :maxOutputChars) default-max-output)
          run (fn [f render error?]
                (let [[started r] (with-server state cwd f)]
                  (with-started started (mcp/tool-result (render r) :error? (error? r)))))]
      (case tool
        "nrepl_eval" (run #(client/eval! state {:cwd cwd :code (arg args :code) :ns (arg args :ns)
                                                :timeout-ms (arg args :timeoutMs)})
                          #(eval-text % max {:pprint? (true? (arg args :pprint))})
                          :error?)
        "nrepl_load_file" (run #(client/load-file! state {:cwd cwd :file (arg args :file)
                                                          :timeout-ms (arg args :timeoutMs)})
                               #(eval-text % max) :error?)
        "nrepl_lookup" (let [sym (arg args :sym)]
                         (run #(client/lookup! state {:cwd cwd :sym sym :ns (arg args :ns)})
                              #(if % (lookup-text %) (str sym " not found (no var by that name in "
                                                          (or (arg args :ns) "the current ns") ")"))
                              nil?))
        "nrepl_complete" (run #(client/complete! state {:cwd cwd :prefix (arg args :prefix) :ns (arg args :ns)})
                              #(if (seq %)
                                 (str/join "\n" (map (fn [{:keys [candidate type]}]
                                                       (str candidate (when type (str " (" type ")"))))
                                                     (take 100 %)))
                                 "no completions")
                              (constantly false))
        "nrepl_test" (run #(client/test! state {:cwd cwd :ns (strings (arg args :ns)) :vars (strings (arg args :vars))
                                                :reload? (not (false? (arg args :reload)))
                                                :timeout-ms (arg args :timeoutMs)})
                          #(test-text % max)
                          #(boolean (or (:error %) (pos? (get-in % [:counts :fail] 0))
                                        (pos? (get-in % [:counts :error] 0)))))
        "nrepl_start" (start state args)
        "nrepl_stop" (stop state args)
        "nrepl_connect" (let [s (client/connect! state {:cwd cwd :host (arg args :host) :port (arg args :port)})]
                          (mcp/tool-result (str "connected to " (:host s) ":" (:port s) " for " (:root s)
                                                "; interrupt: " (:interrupt? s))))
        "nrepl_status" (let [st (client/status state)]
                         (mcp/tool-result (str (pr-str st)
                                               (when (true? (arg args :scan))
                                                 (str "\n" (scan-text (discover/scan) (:root st)))))))
        "nrepl_interrupt" (let [r (client/interrupt! state)]
                            (mcp/tool-result (if (:interrupted? r) "interrupt sent" (str "not interrupted: " (:reason r)))
                                             :error? (not (:interrupted? r))))
        "nrepl_disconnect" (mcp/tool-result (if (:disconnected? (client/disconnect! state))
                                              "disconnected" "was not connected"))))
    (catch clojure.lang.ExceptionInfo e (exception-result e))))

;; ---------------------------------------------------------------------------
;; stdio

(defn run-stdio [state]
  (let [write-lock (Object.)
        pending (atom [])]
    (loop []
      (when-let [line (read-line)]
        (when-not (str/blank? line)
          (let [task (future
                       (try
                         (when-let [resp (mcp/handle-message line tool-definitions
                                                             #(call-tool state %1 %2))]
                           (locking write-lock
                             (println (mcp/write-str resp))
                             (flush)))
                         (catch Throwable e
                           (binding [*out* *err*] (println "jolt-nrepl-mcp:" (ex-message e))))))]
            (swap! pending #(conj (vec (remove realized? %)) task))))
        (recur)))
    (doseq [t @pending] (deref t 5000 nil))))

(defn -main [& _]
  (let [state (client/create-state)]
    (try
      (run-stdio state)
      (finally
        (client/disconnect! state)
        (shutdown-agents)
        (System/exit 0)))))
