(ns jolt-nrepl-mcp.mcp
  "Minimal MCP (JSON-RPC 2.0 over stdio) message handling. Adapted from the
  kimi-nrepl-plugin."
  (:require [clojure.data.json :as json]))

(def server-info {:name "jolt-nrepl" :version "0.2.0"})
(def ^:private newest-protocol-version "2025-06-18")
(def ^:private supported-protocol-versions #{"2024-11-05" "2025-03-26" "2025-06-18"})

(defn tool-result
  "A text tool result. Text only: the agent reads text, and a duplicate
  structured copy would double the tokens of every result."
  [text & {:keys [error?]}]
  {:content [{:type "text" :text (str text)}]
   :isError (boolean error?)})

(defn- value [m k] (if (contains? m k) (get m k) (get m (name k))))
(defn- has-key? [m k] (or (contains? m k) (contains? m (name k))))

(defn- response [message result]
  (when (has-key? message :id)
    {:jsonrpc "2.0" :id (value message :id) :result result}))

(defn- error-response [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- call-requested-tool [message tool-definitions call-tool]
  (let [params (value message :params)]
    (if-not (map? params)
      (error-response (value message :id) -32602 "tools/call requires params")
      (let [tool-name (value params :name)
            arguments (or (value params :arguments) {})]
        (cond
          (not (string? tool-name))
          (error-response (value message :id) -32602 "tools/call requires a tool name")

          (not-any? #(= tool-name (value % :name)) tool-definitions)
          (error-response (value message :id) -32602 (str "Unknown tool: " tool-name))

          :else
          (try
            (response message (call-tool tool-name arguments))
            (catch Throwable e
              (response message (tool-result (str "Tool error: " (ex-message e)) :error? true)))))))))

(defn- handle-parsed [message tool-definitions call-tool]
  (if (or (not (map? message))
          (not= "2.0" (value message :jsonrpc))
          (not (string? (value message :method))))
    (error-response (when (map? message) (value message :id)) -32600 "Invalid JSON-RPC request")
    (let [method (value message :method)]
      (case method
        "notifications/initialized" nil
        "initialize"
        (let [params (value message :params)]
          (if-not (or (nil? params) (map? params))
            (when (has-key? message :id)
              (error-response (value message :id) -32602 "initialize params must be an object"))
            (let [requested (value params :protocolVersion)]
              (response message
                        {:protocolVersion (if (supported-protocol-versions requested)
                                            requested
                                            newest-protocol-version)
                         :capabilities {:tools {:listChanged false}}
                         :serverInfo server-info}))))
        "ping" (response message {})
        "tools/list" (response message {:tools tool-definitions})
        "tools/call" (let [r (call-requested-tool message tool-definitions call-tool)]
                       (when (has-key? message :id) r))
        (when (has-key? message :id)
          (error-response (value message :id) -32601 (str "Method not found: " method)))))))

(defn handle-message [message tool-definitions call-tool]
  (if (string? message)
    (let [parsed (try (json/read-str message) (catch Throwable e e))]
      (if (instance? Throwable parsed)
        (error-response nil -32700 (str "Parse error: " (ex-message parsed)))
        (handle-parsed parsed tool-definitions call-tool)))
    (handle-parsed message tool-definitions call-tool)))

(defn write-str [v] (json/write-str v :escape-unicode false))
