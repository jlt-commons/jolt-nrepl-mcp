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
            [jolt-nrepl-mcp.forms :as forms]
            [jolt-lsp.syntax :as syntax]
            ;; java.net.Socket's provider: loaded on demand in a dev run, but a
            ;; built binary only has what is required
            [jolt.socket])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def default-timeout-ms 120000)
(def max-timeout-ms 600000)
(def ^:private handshake-timeout-ms 10000)
(def ^:private interrupt-grace-ms 1500)

;; Root values set for *print-length*/*print-level* on connect, when the
;; server has none, so printing an infinite seq returns instead of hanging
;; until the timeout (clojure-mcp caps printing with nREPL's print quota,
;; which jolt's servers do not implement). Override with
;; JOLT_NREPL_MCP_PRINT_LENGTH / JOLT_NREPL_MCP_PRINT_LEVEL; 0 leaves it unset.
(def timeout-hint
  (str "Usually an infinite loop, a blocking call (deref, a queue, a socket), or an eval "
       "that needs a larger timeoutMs."))

(def default-print-length 200)
(def default-print-level 50)

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
  (let [{:keys [conn root host port session ops ns busy? print]} @state]
    (cond-> {:connected? (boolean (and conn (not @(:closed? conn))))
             :busy? (boolean busy?)
             :root root :host host :port port :session session :ns ns
             :interrupt? (contains? ops "interrupt")
             :ops (vec (sort ops))}
      print (assoc :print print))))

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

(declare exec!)

(defn- env-limit [var default]
  (let [v (System/getenv var)]
    (if (str/blank? v) default (let [n (parse-long (str/trim v))] (when (and n (pos? n)) n)))))

(defn- cap-printing!
  "Give *print-length*/*print-level* root values when the server has none, and
  record what they are. A server where this fails is left as it is."
  [state]
  (let [len (env-limit "JOLT_NREPL_MCP_PRINT_LENGTH" default-print-length)
        lvl (env-limit "JOLT_NREPL_MCP_PRINT_LEVEL" default-print-level)
        code (str "(do"
                  (when len (str " (when (nil? *print-length*) (alter-var-root #'*print-length* (constantly " len ")))"))
                  (when lvl (str " (when (nil? *print-level*) (alter-var-root #'*print-level* (constantly " lvl ")))"))
                  " (pr-str {:length *print-length* :level *print-level*}))")]
    (try
      (let [{:keys [value error?]} (exec! state {:op "eval" :code code :ns "user"}
                                          handshake-timeout-ms {:track-ns? false})]
        (when-not error?
          (swap! state assoc :print (read-string (read-string value)))))
      (catch Throwable _ nil))))

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
          (cap-printing! state)
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
                                    (str "No .nrepl-port in " dir " or any parent. Start a server with nrepl_start, or list running ones with nrepl_status scan=true.")
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
  (let [{:keys [root host port]} @state
        live? (:connected? (status state))]
    (if (and (nil? cwd) root)
      ;; no cwd: the project in use, reconnecting to it if the link dropped
      (when-not live?
        (connect-inner! state {:root root :host (or host "127.0.0.1")
                               :port (if (fs/exists? (str root "/.nrepl-port")) (read-port root) port)}))
      (let [dir (canonical-dir cwd)]
        (when-not (and live? root (or (= dir root) (str/starts-with? dir (str root "/"))))
          (let [root' (or (find-root dir)
                          (fail :missing-port
                                (str "No .nrepl-port in " dir " or any parent. Start a server with nrepl_start, or list running ones with nrepl_status scan=true.")
                                {:cwd dir}))]
            (connect-inner! state {:root root' :host (or host "127.0.0.1")
                                   :port (read-port root')})))))))

(defn- with-connection
  "Run `f` holding the client, connected to the project for `cwd`."
  [state cwd f]
  (acquire! state)
  (try
    (ensure-connected! state cwd)
    (f)
    (finally (release! state))))

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
  "Send one op and collect its result. `track-ns?` false keeps the
  session's namespace where it is (internal evals: lookup, test runs)."
  [state msg timeout-ms & [{:keys [track-ns?] :or {track-ns? true}}]]
  (let [{:keys [conn session ns]} @state
        msg (cond-> msg
              session (assoc :session session)
              (and ns (not (:ns msg)) (= "eval" (:op msg))) (assoc :ns ns))
        eval? (= "eval" (:op msg))
        {:keys [msgs complete? closed? id queue]}
        (request conn msg timeout-ms #(swap! state assoc :active-id %))
        finish (fn [msgs extra]
                 (forget! conn id)
                 (let [{:keys [values out err status ns ex root-ex]} (combine msgs)
                       error? (boolean (some #{"error" "eval-error" "interrupted"} status))]
                   ;; a load-file is a load: its ns form does not switch the
                   ;; session, and a server that got no :ns reports its own
                   ;; default, which must not become the session's
                   (when (and ns (not error?) eval? track-ns?) (swap! state assoc :ns ns))
                   (cond-> (merge {:value (last values)
                                   :out out :err err
                                   :ns (if eval? (or ns (:ns @state)) (:ns @state))
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
                         "The form may still be running server-side and was NOT replayed. "
                         timeout-hint)
                    {:interrupted? interrupted? :certain? false :timeout-ms timeout-ms})))))))

(defn- timeout [t]
  (let [t (or t default-timeout-ms)]
    (when-not (and (integer? t) (<= 1 t max-timeout-ms))
      (fail :invalid-timeout (str "timeoutMs must be an integer in 1.." max-timeout-ms)))
    t))

(defn- readable-elsewhere?
  "A diagnosis that only means this process's reader can't read it."
  [problem]
  (= :does-not-read (:reason problem)))

(defn- eval-forms!
  "Send each top-level form as its own eval, so each gets its own output and
  value; the first error stops the rest. Code that can't be split here goes
  as one eval. The totals (:value, :out, :err, ...) read as one eval's would."
  [state code ns timeout-ms]
  (let [fs (forms/split code)]
    (if (< (count fs) 2)
      (exec! state (cond-> {:op "eval" :code code} ns (assoc :ns ns)) timeout-ms)
      (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
        (loop [[f & more] fs, done []]
          (let [left (max 1 (- deadline (System/currentTimeMillis)))
                r (try
                    (exec! state (cond-> {:op "eval" :code f} (and ns (empty? done)) (assoc :ns ns)) left)
                    (catch clojure.lang.ExceptionInfo e
                      (if (seq done)
                        (throw (ex-info (str (ex-message e) " " (count done) " earlier form(s) completed.")
                                        (assoc (ex-data e) :completed (count done))))
                        (throw e))))
                done (conj done (assoc r :code f))]
            (if (and (seq more) (not (:error? r)))
              (recur more done)
              (let [last-r (peek done)]
                (cond-> (assoc last-r
                               :forms (mapv #(dissoc % :status) done)
                               :out (apply str (map :out done))
                               :err (apply str (map :err done)))
                  true (dissoc :code)
                  (seq more) (assoc :skipped (count more)))))))))))

(defn eval!
  "Evaluate `code`. Syntax is vetted first: a trailing truncation is repaired
  and reported in :repair; anything else is refused with a line and column
  before it reaches the server."
  [state {:keys [cwd code ns timeout-ms]}]
  (when-not (and (string? code) (not (str/blank? code)))
    (fail :invalid-code "code must be a nonempty string"))
  (let [timeout-ms (timeout timeout-ms)
        {:keys [problem note] :as v} (syntax/vet code {:whole? true})
        vetted (if problem code (:code v))]
    ;; Delimiters balance but it doesn't read HERE: ::alias/kw, a tagged
    ;; literal or reader feature this process lacks. The server's reader, in
    ;; the right namespace, is the one that counts.
    (when (and problem (not (readable-elsewhere? problem)))
      (fail :syntax (str "Not evaluated — " note) {:note note :problem problem}))
    (with-connection state cwd
      #(cond-> (eval-forms! state vetted ns timeout-ms)
         (and note (not problem)) (assoc :repair note)))))

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
    (with-connection state cwd
      (fn []
        (let [path (project-file (:root @state) file)
              text (slurp path)]
          (when-let [d (let [d (syntax/diagnose text)] (when-not (readable-elsewhere? d) d))]
            (fail :syntax (str "Not loaded — " path ": " (syntax/explain d)
                               " jolt-lsp's jolt_check with fix=true repairs a trailing truncation.")
                  {:note (syntax/explain d) :problem d :file path}))
          (exec! state {:op "load-file" :file text :file-path path
                        :file-name (str (fs/file-name path))}
                 timeout-ms))))))

;; ---------------------------------------------------------------------------
;; lookup, completion, tests
;;
;; Structured answers to what an agent otherwise asks with doc/apropos/run-tests
;; snippets (clojure-mcp loads a clj-mcp.repl-tools namespace of helpers for
;; the same purpose). The nREPL ops are used when the server has them; the
;; fallbacks are internal evals that leave the session's namespace alone.

(defn- op!
  "Send a non-eval op and return its raw responses."
  [state msg timeout-ms]
  (let [{:keys [conn session]} @state
        {:keys [msgs complete? id]} (request conn (cond-> msg session (assoc :session session)) timeout-ms)]
    (forget! conn id)
    (when-not complete?
      (fail :timeout (str (:op msg) " got no answer within " timeout-ms "ms")))
    msgs))

(defn- internal-eval
  "Eval `code`, which returns (pr-str x), and read x back."
  [state code timeout-ms]
  (let [{:keys [value error? err ex]} (exec! state {:op "eval" :code code :ns "user"}
                                             timeout-ms {:track-ns? false})]
    (if error?
      {::error (str/trim (str (or (not-empty err) ex "eval failed")))}
      (read-string (read-string value)))))

(def ^:private unbounded "(binding [*print-length* nil *print-level* nil] (pr-str %s))")

(defn- unbounded-pr [form] (format unbounded form))

(defn lookup!
  "Metadata for the var `sym` resolves to in `ns` (default: the session's):
  {:name :ns :doc :arglists :file :line :column}, or nil."
  [state {:keys [cwd sym ns timeout-ms]}]
  (when-not (and (string? sym) (not (str/blank? sym)))
    (fail :invalid-sym "sym must be a nonempty symbol name"))
  (with-connection state cwd
    (fn []
      (let [ns (or ns (:ns @state) "user")
            t (timeout timeout-ms)]
        (if (contains? (:ops @state) "lookup")
          (let [info (some #(get % "info") (op! state {:op "lookup" :sym sym :ns ns} t))]
            (when (seq info)
              (cond-> {:name (get info "name") :ns (get info "ns")}
                (get info "doc") (assoc :doc (get info "doc"))
                (get info "arglists-str") (assoc :arglists (get info "arglists-str"))
                (get info "file") (assoc :file (get info "file"))
                (get info "line") (assoc :line (get info "line"))
                (get info "column") (assoc :column (get info "column")))))
          (let [r (internal-eval
                   state
                   (str "(let [v (try (ns-resolve (or (find-ns (symbol " (pr-str ns) ")) (find-ns 'user))"
                        " (symbol " (pr-str sym) ")) (catch Throwable _ nil))"
                        " m (when (var? v) (meta v))]"
                        (unbounded-pr
                         (str "(when m (into {} (remove (comp nil? val)"
                              " {:name (str (:name m)) :ns (str (:ns m)) :doc (:doc m)"
                              " :arglists (some-> (:arglists m) pr-str) :file (some-> (:file m) str)"
                              " :line (:line m) :column (:column m)})))"))
                        ")")
                   t)]
            (when-not (::error r) r)))))))

(defn complete!
  "Completion candidates for `prefix` in `ns`: [{:candidate :ns :type} ...]."
  [state {:keys [cwd prefix ns timeout-ms]}]
  (when-not (and (string? prefix) (not (str/blank? prefix)))
    (fail :invalid-prefix "prefix must be a nonempty string"))
  (with-connection state cwd
    (fn []
      (let [op (some (:ops @state) ["completions" "complete"])]
        (when-not op
          (fail :unsupported "the server has no completions op"))
        (let [msgs (op! state {:op op :prefix prefix :ns (or ns (:ns @state) "user")}
                        (timeout timeout-ms))]
          (->> (mapcat #(get % "completions") msgs)
               (mapv (fn [c] (into {} (keep (fn [k] (when-let [v (get c (name k))] [k v])))
                                   [:candidate :ns :type])))))))))

(defn- test-code
  "Run clojure.test on namespaces or vars with `report` rebound to collect
  results; returns (pr-str {:counts .. :problems [..]})."
  [nss vars]
  (str "(let [counts (atom {:test 0 :pass 0 :fail 0 :error 0}) problems (atom []) cur (atom nil)"
       " shown (fn [x] (if (instance? Throwable x)"
       "   (str (clojure.string/replace (str (class x)) #\"^class \" \"\") \": \" (ex-message x)"
       "        (when-let [d (ex-data x)] (str \" \" (pr-str d))))"
       "   (pr-str x)))]"
       " (binding [clojure.test/report"
       "   (fn [m] (case (:type m)"
       "     :begin-test-var (do (swap! counts update :test inc)"
       "                         (reset! cur (let [mm (meta (:var m))] (symbol (str (:ns mm)) (str (:name mm))))))"
       "     (:pass :fail :error)"
       "     (do (swap! counts update (:type m) inc)"
       "         (when-not (= :pass (:type m))"
       "           (swap! problems conj"
       "             (into {} (remove (comp nil? val)"
       "               {:type (:type m) :var @cur :file (:file m) :line (:line m) :message (:message m)"
       "                :context (first clojure.test/*testing-contexts*)"
       "                :expected (pr-str (:expected m)) :actual (shown (:actual m))})))))"
       "     nil))]"
       (if (seq vars)
         (str " (let [vs (map (juxt identity resolve) '[" (str/join " " vars) "])]"
              "   (doseq [[s v] vs :when (nil? v)]"
              "     (swap! counts update :error inc)"
              "     (swap! problems conj {:type :error :var s :message \"no such var\"}))"
              "   (clojure.test/test-vars (keep second vs))))")
         (str " (clojure.test/run-tests " (str/join " " (map #(str "'" %) nss)) "))"))
       (unbounded-pr "{:counts @counts :problems @problems}")
       ")"))

(defn- symbol-name? [s] (and (string? s) (re-matches #"[^\s()\[\]{}\"'`~@^;,\\]+" s)))

(defn test!
  "Run clojure.test on the namespaces `ns` (reloaded first) or the vars `vars`
  (fully qualified), with results as data:
  {:counts {:test :pass :fail :error} :problems [{:type :var :line :message
  :context :expected :actual}] :out \"..\"} or {:error \"..\"}."
  [state {:keys [cwd ns vars reload? timeout-ms] :or {reload? true}}]
  (let [nss (vec ns) vars (vec vars)]
    (when-not (or (seq nss) (seq vars))
      (fail :invalid-tests "pass ns (namespaces) or vars (qualified var names)"))
    (when-not (every? symbol-name? (concat nss vars))
      (fail :invalid-tests "ns and vars must be symbol names"))
    (when-not (every? #(str/includes? % "/") vars)
      (fail :invalid-tests "vars must be fully qualified: my.ns-test/the-test"))
    (with-connection state cwd
      (fn []
        (let [t (timeout timeout-ms)
              loads (distinct (concat nss (map #(first (str/split % #"/" 2)) vars)))
              req (exec! state {:op "eval" :ns "user" :code
                                (str "(require 'clojure.test 'clojure.string "
                                     (str/join " " (map #(str "'" %) loads))
                                     (when reload? " :reload") ")")}
                         t {:track-ns? false})]
          (if (:error? req)
            {:error (str/trim (str (not-empty (:err req)) (:ex req)))}
            (let [{:keys [value error? err ex out]}
                  (exec! state {:op "eval" :ns "user"
                                :code (test-code nss vars)}
                         t {:track-ns? false})]
              (if error?
                {:error (str/trim (str (not-empty err) ex)) :out out}
                (cond-> (read-string (read-string value))
                  (seq out) (assoc :out out))))))))))

(defn interrupt! [state]
  (cond
    (not (:connected? (status state))) {:interrupted? false :reason "not connected"}
    (not (contains? (:ops @state) "interrupt"))
    {:interrupted? false
     :reason "server has no interrupt op; add nrepl.middleware/default-middleware or use nrepl_disconnect"}
    (nil? (:active-id @state)) {:interrupted? false :reason "no active eval"}
    :else {:interrupted? (boolean (interrupt-active! state))}))

(defn probe
  "Whether an nREPL server answers `describe` at host:port; its ops, or nil."
  [host port]
  (try
    (let [conn (open-conn host port)]
      (try
        (let [{:keys [msgs complete?]} (request conn {:op "describe"} 1500)]
          (when complete?
            {:ops (vec (sort (set (mapcat #(keys (get % "ops")) msgs))))}))
        (finally (close-conn conn))))
    (catch Throwable _ nil)))
