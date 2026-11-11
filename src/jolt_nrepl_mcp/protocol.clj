(ns jolt-nrepl-mcp.protocol
  "MCP's JSON-RPC 2.0 routing as pure functions: what to answer, or which
  tool to call. jolt-nrepl-mcp.mcp does the parsing and the calling; the
  writ spec (test/jolt_nrepl_mcp/spec/protocol_spec.clj) checks this.")

(def supported-versions #{"2024-11-05" "2025-03-26" "2025-06-18"})

(def newest-version "2025-06-18")

(defn- value [m k] (if (contains? m k) (get m k) (get m (name k))))

(defn- has-key? [m k] (or (contains? m k) (contains? m (name k))))

(defn text-result
  "A text tool result. Text only: the agent reads text, and a duplicate
  structured copy would double the tokens of every result."
  [text error?]
  {:content [{:type "text" :text (str text)}]
   :isError (boolean error?)})

(defn reply
  "The response carrying `result` for `message`, or nil for a notification."
  [message result]
  (when (has-key? message :id)
    {:jsonrpc "2.0" :id (value message :id) :result result}))

(defn error-reply [id code text]
  {:jsonrpc "2.0" :id id :error {:code code :message text}})

(defn- route-call [message tool-definitions]
  (let [params (value message :params)
        id (value message :id)]
    (if-not (map? params)
      {:reply (error-reply id -32602 "tools/call requires params")}
      (let [tool-name (value params :name)]
        (cond
          (not (string? tool-name))
          {:reply (error-reply id -32602 "tools/call requires a tool name")}

          (not-any? #(= tool-name (value % :name)) tool-definitions)
          {:reply (error-reply id -32602 (str "Unknown tool: " tool-name))}

          :else
          (let [args (value params :arguments)]
            {:call {:name tool-name :arguments (if (nil? args) {} args)}}))))))

(defn route
  "What to do with a parsed message: {:reply response-or-nil}, or
  {:call {:name :arguments}} for the caller to run and answer with
  `reply`. Errors to notifications are dropped, as JSON-RPC asks."
  [message tool-definitions server-info instructions]
  (if (or (not (map? message))
          (not= "2.0" (value message :jsonrpc))
          (not (string? (value message :method))))
    {:reply (error-reply (when (map? message) (value message :id)) -32600
                         "Invalid JSON-RPC request")}
    (let [method (value message :method)
          id? (has-key? message :id)
          id (value message :id)]
      (case method
        "notifications/initialized" {:reply nil}
        "initialize"
        (let [params (value message :params)]
          (if-not (or (nil? params) (map? params))
            {:reply (when id? (error-reply id -32602 "initialize params must be an object"))}
            (let [requested (value params :protocolVersion)]
              {:reply (reply message
                             {:protocolVersion (if (contains? supported-versions requested)
                                                 requested
                                                 newest-version)
                              :capabilities {:tools {:listChanged false}}
                              :serverInfo server-info
                              :instructions instructions})})))
        "ping" {:reply (reply message {})}
        "tools/list" {:reply (reply message {:tools tool-definitions})}
        "tools/call" (let [r (route-call message tool-definitions)]
                       (if (and (:reply r) (not id?)) {:reply nil} r))
        {:reply (when id? (error-reply id -32601 (str "Method not found: " method)))}))))
