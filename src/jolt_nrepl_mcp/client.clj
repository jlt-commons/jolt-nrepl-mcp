(ns jolt-nrepl-mcp.client
  "A small nREPL client over a plain socket.

  One reader thread decodes responses and routes them by message id to a
  per-request queue, so a late response to a timed-out request can never be
  mistaken for the answer to the next one. One request at a time; a second
  while one is active is refused as :busy rather than queued behind it.

  The client tracks the namespace each eval ends in and sends it with the next
  one. jolt's built-in handler does not keep *ns* per session, and an agent
  that ran (ns foo) expects to still be in foo."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [jolt-nrepl-mcp.bencode :as bencode]
            [jolt-nrepl-mcp.syntax :as syntax])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def default-timeout-ms 120000)
(def max-timeout-ms 600000)
(def ^:private handshake-timeout-ms 10000)
(def ^:private interrupt-grace-ms 1500)

(defn- fail [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

;; ---------------------------------------------------------------------------
;; transport

(defn- reader-loop [{:keys [in pending closed?]}]
  (let [buf (byte-array 65536)]
    (loop [acc ""]
      (when-not @closed?
        (let [n (try (.read in buf)
                     (catch java.net.SocketTimeoutException _ 0)
                     (catch Throwable _ -1))]
          (if (neg? n)
            (do (reset! closed? true)
                (doseq [[_ q] @pending] (.put q ::closed)))
            (let [acc (if (pos? n) (str acc (String. buf 0 n "ISO-8859-1")) acc)
                  acc (loop [acc acc]
                        (if-let [[msg next] (bencode/decode acc 0)]
                          (do (when-let [q (get @pending (get msg "id"))]
                                (.put q msg))
                              (recur (subs acc next)))
                          acc))]
              (recur acc))))))))

(defn- open-conn [host port]
  (let [sock (try (java.net.Socket. host port)
                  (catch Throwable e
                    (fail :connection-refused
                          (str "Cannot connect to nREPL at " host ":" port
                               " (" (ex-message e) "). Is the server running? A stale .nrepl-port"
                               " survives a killed server; start a new one with nrepl_start.")
                          {:host host :port port})))
        _ (.setSoTimeout sock 250)
        conn {:sock sock
              :in (.getInputStream sock)
              :out (.getOutputStream sock)
              :lock (Object.)
              :pending (atom {})
              :closed? (atom false)}]
    (assoc conn :reader (future (reader-loop conn)))))

(defn- close-conn [{:keys [sock closed?]}]
  (when closed? (reset! closed? true))
  (when sock (try (.close sock) (catch Throwable _ nil))))

(defn- send! [{:keys [out lock closed?]} msg]
  (when @closed? (fail :connection-closed "nREPL connection is closed"))
  (let [bytes (.getBytes (bencode/encode msg) "ISO-8859-1")]
    (locking lock
      (.write out bytes)
      (.flush out))))

(def ^:private counter (atom 0))
(defn- next-id [] (str "mcp-" (swap! counter inc)))

(defn- done? [msg] (some #{"done"} (get msg "status")))

(defn- collect
  "Collect responses for `id` until one says done or `deadline` passes.
  Returns {:msgs [...] :complete? bool :closed? bool}."
  [q deadline msgs]
  (loop [msgs msgs]
    (let [left (- deadline (System/currentTimeMillis))
          m (when (pos? left) (.poll q left TimeUnit/MILLISECONDS))]
      (cond
        (nil? m) {:msgs msgs :complete? false}
        (= ::closed m) {:msgs msgs :complete? false :closed? true}
        (done? m) {:msgs (conj msgs m) :complete? true}
        :else (recur (conj msgs m))))))

(defn- request
  "Send `msg` and wait up to `timeout-ms` for its done. `on-start` receives
  the id before anything is sent."
  [conn msg timeout-ms & [on-start]]
  (let [id (next-id)
        q (LinkedBlockingQueue.)]
    (swap! (:pending conn) assoc id q)
    (when on-start (on-start id))
    (try
      (send! conn (assoc msg :id id))
      (assoc (collect q (+ (System/currentTimeMillis) timeout-ms) []) :id id :queue q)
      (catch Throwable e
        (swap! (:pending conn) dissoc id)
        (throw e)))))

(defn- forget! [conn id] (swap! (:pending conn) dissoc id))

(defn- combine [msgs]
  (reduce (fn [m r]
            (reduce-kv (fn [m k v]
                         (case k
                           "value" (update m :values conj v)
                           "out" (update m :out str v)
                           "err" (update m :err str v)
                           "status" (update m :status into v)
                           "ns" (assoc m :ns v)
                           "ex" (assoc m :ex v)
                           "root-ex" (assoc m :root-ex v)
                           m))
                       m r))
          {:values [] :out "" :err "" :status #{}}
          msgs))

;; ---------------------------------------------------------------------------
;; state

(defn create-state [] (atom {:busy? false}))

(defn status [state]
  (let [{:keys [conn root host port session ops ns busy?]} @state]
    {:connected? (boolean (and conn (not @(:closed? conn))))
     :busy? (boolean busy?)
     :root root :host host :port port :session session :ns ns
     :interrupt? (contains? ops "interrupt")
     :ops (vec (sort ops))}))

(defn- drop-conn! [state]
  (let [{:keys [conn session]} @state]
    (swap! state dissoc :conn :session :ops)
    (when conn
      (when (and session (not @(:closed? conn)))
        (try (send! conn {:op "close" :session session}) (catch Throwable _ nil)))
      (close-conn conn))))

(defn disconnect! [state]
  (let [was (:connected? (status state))]
    (drop-conn! state)
    (swap! state dissoc :root :host :port :ns)
    {:disconnected? was}))

(defn- acquire! [state]
  (locking state
    (when (:busy? @state)
      (fail :busy "An eval is already running. Wait, call nrepl_interrupt, or nrepl_disconnect."))
    (swap! state assoc :busy? true)))

(defn- release! [state]
  (swap! state assoc :busy? false :active-id nil))

;; ---------------------------------------------------------------------------
;; project discovery

(defn default-dir
  "Where the agent is working when it names no cwd. The launcher runs the
  server from its own directory and exports the caller's as
  JOLT_NREPL_MCP_CWD."
  []
  (some #(when-not (str/blank? %) %)
        [(System/getenv "JOLT_NREPL_MCP_CWD")
         (System/getenv "CLAUDE_PROJECT_DIR")
         (System/getProperty "user.dir")]))

(defn canonical-dir [cwd]
  (let [cwd (or cwd (default-dir))]
    (when-not (and (string? cwd) (fs/absolute? cwd))
      (fail :invalid-cwd "cwd must be an absolute directory" {:cwd cwd}))
    (when-not (fs/directory? cwd)
      (fail :invalid-cwd "cwd is not a directory" {:cwd cwd}))
    (str (fs/real-path cwd))))

(defn find-root
  "The nearest directory at or above `dir` holding a .nrepl-port, or nil."
  [dir]
  (loop [d (fs/path dir)]
    (cond
      (nil? d) nil
      (fs/exists? (fs/path d ".nrepl-port")) (str d)
      :else (recur (fs/parent d)))))

(defn- read-port [root]
  (let [p (parse-long (str/trim (slurp (str root "/.nrepl-port"))))]
    (when-not (and p (<= 1 p 65535))
      (fail :invalid-port (str "Unreadable .nrepl-port in " root)))
    p))

;; ---------------------------------------------------------------------------
;; connect

(defn- connect-inner! [state {:keys [root host port]}]
  (drop-conn! state)
  (let [conn (open-conn host port)
        deadline-ms handshake-timeout-ms]
    (try
      (let [clone (request conn {:op "clone"} deadline-ms)
            desc (request conn {:op "describe"} deadline-ms)]
        (forget! conn (:id clone))
        (forget! conn (:id desc))
        (when-not (and (:complete? clone) (:complete? desc))
          (fail :connection-timeout "nREPL handshake did not complete"))
        (let [session (some #(get % "new-session") (:msgs clone))
              ops (set (mapcat #(keys (get % "ops")) (:msgs desc)))
              same-project? (= root (:root @state))]
          (swap! state merge {:conn conn :root root :host host :port port
                              :session session :ops ops}
                 ;; jolt 0.8.25's built-in server starts every eval in
                 ;; clojure.core (jolt-lang/jolt#1564); an nREPL session is
                 ;; expected to start in user. Harmless on fixed servers.
                 (when-not same-project? {:ns "user"}))
          (status state)))
      (catch Throwable e
        (close-conn conn)
        (throw e)))))

(defn connect!
  "Connect to the nREPL server for the project at or above `cwd`, or to an
  explicit host/port."
  [state {:keys [cwd host port]}]
  (let [dir (canonical-dir cwd)
        root (if port dir (or (find-root dir)
                              (fail :missing-port
                                    (str "No .nrepl-port in " dir " or any parent. Start a server with nrepl_start.")
                                    {:cwd dir})))
        port (or port (read-port root))]
    (acquire! state)
    (try
      (connect-inner! state {:root root :host (or host "127.0.0.1") :port port})
      (finally (release! state)))))

(defn- ensure-connected!
  "Reuse the live connection when `cwd` is inside its project; otherwise
  (re)connect to the project that owns `cwd`."
  [state cwd]
  (let [dir (canonical-dir cwd)
        {:keys [root]} @state
        live? (:connected? (status state))]
    (when-not (and live? root (or (= dir root) (str/starts-with? dir (str root "/"))))
      (let [root' (or (find-root dir)
                      (when (and root (not live?) (nil? cwd)) root)
                      (fail :missing-port
                            (str "No .nrepl-port in " dir " or any parent. Start a server with nrepl_start.")
                            {:cwd dir}))]
        (connect-inner! state {:root root' :host (or (:host @state) "127.0.0.1")
                               :port (read-port root')})))))

;; ---------------------------------------------------------------------------
;; eval

(defn- interrupt-active! [state]
  (let [{:keys [conn session active-id ops]} @state]
    (when (and conn active-id (contains? ops "interrupt"))
      (try (send! conn (cond-> {:op "interrupt" :interrupt-id active-id}
                         session (assoc :session session)))
           true
           (catch Throwable _ false)))))

(defn- exec!
  [state msg timeout-ms]
  (let [{:keys [conn session ns]} @state
        msg (cond-> msg
              session (assoc :session session)
              (and ns (not (:ns msg)) (= "eval" (:op msg))) (assoc :ns ns))
        {:keys [msgs complete? closed? id queue]}
        (request conn msg timeout-ms #(swap! state assoc :active-id %))
        finish (fn [msgs extra]
                 (forget! conn id)
                 (let [{:keys [values out err status ns ex root-ex]} (combine msgs)
                       error? (boolean (some #{"error" "eval-error" "interrupted"} status))]
                   (when (and ns (not error?)) (swap! state assoc :ns ns))
                   (cond-> (merge {:value (last values)
                                   :out out :err err
                                   :ns (or ns (:ns @state))
                                   :status (vec (sort status))
                                   :error? error?}
                                  extra)
                     (> (count values) 1) (assoc :values values)
                     ex (assoc :ex ex)
                     (and root-ex (not= root-ex ex)) (assoc :root-ex root-ex))))]
    (cond
      complete? (finish msgs {})

      closed?
      (do (drop-conn! state)
          (fail :connection-closed "nREPL closed the connection before the eval finished"))

      :else
      (let [interrupted? (interrupt-active! state)
            {more :msgs ok? :complete?} (when interrupted?
                                          (collect queue (+ (System/currentTimeMillis) interrupt-grace-ms) msgs))]
        (if ok?
          (finish more {:timed-out? true :interrupted? true})
          (do (forget! conn id)
              (drop-conn! state)
              (fail :timeout
                    (str "Eval timed out after " timeout-ms "ms. "
                         (if interrupted?
                           "Interrupt was sent but not confirmed; "
                           "The server has no interrupt op; ")
                         "the connection was dropped so late output cannot leak into the next eval. "
                         "The form may still be running server-side and was NOT replayed.")
                    {:interrupted? interrupted? :certain? false :timeout-ms timeout-ms})))))))

(defn- timeout [t]
  (let [t (or t default-timeout-ms)]
    (when-not (and (integer? t) (<= 1 t max-timeout-ms))
      (fail :invalid-timeout (str "timeoutMs must be an integer in 1.." max-timeout-ms)))
    t))

(defn eval!
  "Evaluate `code`. Syntax is vetted first: a trailing truncation is repaired
  and reported in :repair; anything else is refused with a line and column
  before it reaches the server."
  [state {:keys [cwd code ns timeout-ms]}]
  (when-not (and (string? code) (not (str/blank? code)))
    (fail :invalid-code "code must be a nonempty string"))
  (let [timeout-ms (timeout timeout-ms)
        {vetted :code :keys [problem note]} (syntax/vet code {:whole? true})]
    (when problem
      (fail :syntax (str "Not evaluated — " note) {:note note :problem problem}))
    (acquire! state)
    (try
      (ensure-connected! state cwd)
      (cond-> (exec! state (cond-> {:op "eval" :code vetted} ns (assoc :ns ns)) timeout-ms)
        note (assoc :repair note))
      (finally (release! state)))))

(defn- project-file [root file]
  (when-not (and (string? file) (not (str/blank? file)))
    (fail :invalid-file "file must be a nonempty path"))
  (let [path (fs/normalize (fs/path root file))
        inside? #(str/starts-with? (str %) (str root "/"))]
    (when-not (or (inside? path) (fs/exists? path))
      (fail :path-escape "file is outside the project root" {:file file}))
    (when-not (fs/exists? path)
      (fail :missing-file (str "No such file: " file) {:file file}))
    (let [real (fs/real-path path)]
      (when-not (str/starts-with? (str real) (str root "/"))
        (fail :path-escape "file is outside the project root" {:file file}))
      (when-not (fs/regular-file? real)
        (fail :invalid-file "not a regular file" {:file file}))
      (str real))))

(defn load-file!
  "Load a project file into the session. The file is diagnosed first, so a
  broken file is refused with a line and column instead of the reader's guess."
  [state {:keys [cwd file timeout-ms]}]
  (let [timeout-ms (timeout timeout-ms)]
    (acquire! state)
    (try
      (ensure-connected! state cwd)
      (let [path (project-file (:root @state) file)
            text (slurp path)]
        (when-let [d (syntax/diagnose text)]
          (fail :syntax (str "Not loaded — " path ": " (syntax/explain d)
                             " Run clj_check with fix to repair a trailing truncation.")
                {:note (syntax/explain d) :problem d :file path}))
        (exec! state {:op "load-file" :file text :file-path path
                     :file-name (str (fs/file-name path))}
              timeout-ms))
      (finally (release! state)))))

(defn interrupt! [state]
  (cond
    (not (:connected? (status state))) {:interrupted? false :reason "not connected"}
    (not (contains? (:ops @state) "interrupt"))
    {:interrupted? false
     :reason "server has no interrupt op; add nrepl.middleware/default-middleware or use nrepl_disconnect"}
    (nil? (:active-id @state)) {:interrupted? false :reason "no active eval"}
    :else {:interrupted? (boolean (interrupt-active! state))}))
