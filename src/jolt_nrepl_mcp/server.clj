(ns jolt-nrepl-mcp.server
  "The MCP server: tool definitions, compact text rendering, and stdio loop."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [jolt-nrepl-mcp.client :as client]
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
    :description "Evaluate Clojure in the project's persistent nREPL session (connects lazily). Use it first: reproduce a bug and try a fix here before editing files. Returns stdout, stderr, the last value and the current ns. A dropped trailing paren is repaired and reported; any other syntax problem is refused with line/col before anything runs. The ns from the previous eval carries over."
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "One or more Clojure forms"}
                               :ns {:type "string" :description "Evaluate in this namespace instead of the current one"}
                               :cwd cwd-prop
                               :timeoutMs timeout-prop
                               :maxOutputChars {:type "integer" :minimum 200
                                                :description (str "Per-field output cap, default " default-max-output)}}
                  :required ["code"]}}
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
    :description "Connection, project root, current ns, busy state and server ops."
    :inputSchema {:type "object" :properties {}}}
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

(defn- eval-text [{:keys [repair out err value values ns ex root-ex error? interrupted? timed-out?]} max]
  (str/join "\n"
            (cond-> []
              repair (conj (str "repaired: " repair))
              (seq out) (conj (str "out:\n" (str/trimr (truncate out max))))
              (seq err) (conj (str "err:\n" (str/trimr (truncate err max))))
              (and values (> (count values) 1)) (conj (str "values: " (count values)))
              (some? value) (conj (str "=> " (truncate value max)))
              ex (conj (str "ex: " ex (when root-ex (str " (root: " root-ex ")"))))
              timed-out? (conj (str "timed out; interrupted: " (boolean interrupted?)))
              (and error? (not ex) (not timed-out?)) (conj "error")
              ns (conj (str "ns: " ns)))))

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

(defn call-tool [state tool args]
  (try
    (let [cwd (arg args :cwd)
          max (or (arg args :maxOutputChars) default-max-output)]
      (case tool
        "nrepl_eval" (let [r (client/eval! state {:cwd cwd :code (arg args :code) :ns (arg args :ns)
                                                  :timeout-ms (arg args :timeoutMs)})]
                       (mcp/tool-result (eval-text r max) :error? (:error? r)))
        "nrepl_load_file" (let [r (client/load-file! state {:cwd cwd :file (arg args :file)
                                                            :timeout-ms (arg args :timeoutMs)})]
                            (mcp/tool-result (eval-text r max) :error? (:error? r)))
        "nrepl_start" (start state args)
        "nrepl_stop" (stop state args)
        "nrepl_connect" (let [s (client/connect! state {:cwd cwd :host (arg args :host) :port (arg args :port)})]
                          (mcp/tool-result (str "connected to " (:host s) ":" (:port s) " for " (:root s)
                                                "; interrupt: " (:interrupt? s))))
        "nrepl_status" (mcp/tool-result (pr-str (client/status state)))
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
