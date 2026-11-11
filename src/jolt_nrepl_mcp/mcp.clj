(ns jolt-nrepl-mcp.mcp
  "Minimal MCP (JSON-RPC 2.0 over stdio) message handling. Adapted from the
  kimi-nrepl-plugin."
  (:require [clojure.data.json :as json]
            [jolt-nrepl-mcp.protocol :as protocol]))

(def server-info {:name "jolt-nrepl" :version "0.3.0"})

(def instructions
  "What the agent is told about this server in its system prompt, every
  session. Kept short: it is paid for whether or not the tools are used."
  (str "Persistent nREPL session for Clojure/Jolt projects. When working on Clojure or Jolt code, "
       "use these tools instead of `jolt -e`/`clj` in a shell: vars, loaded namespaces and the "
       "current ns persist, so each step is one eval.\n"
       "REPL first, then edit. Before you edit a file to fix a bug or write a function:\n"
       "1. nrepl_start (pass the project dir as `cwd`) if there is no .nrepl-port.\n"
       "2. Reproduce in nrepl_eval: require the ns, call the failing function or test, and look at "
       "the actual inputs and outputs. Capture values with an inline (def x* x) in a REPL-only "
       "redefinition, not println; after an error inspect *e and (ex-data *e).\n"
       "3. Try the fix by redefining the function in nrepl_eval and re-running the failing case.\n"
       "4. Only then edit the file, nrepl_load_file it, and re-run "
       "the tests in the session: (clojure.test/run-tests 'my.ns-test).\n"
       "For structural edits and syntax repair use the jolt-lsp MCP server (jolt_replace_form, "
       "jolt_check). A dropped trailing paren is auto-closed and reported; other "
       "syntax errors are refused with line/col. Load the jolt-nrepl skill for the full workflow."))
(defn tool-result
  "A text tool result."
  [text & {:keys [error?]}]
  (protocol/text-result text error?))

(defn- handle-parsed [message tool-definitions call-tool]
  (let [{:keys [call] :as r} (protocol/route message tool-definitions server-info instructions)]
    (if call
      (try
        (protocol/reply message (call-tool (:name call) (:arguments call)))
        (catch Throwable e
          (protocol/reply message (tool-result (str "Tool error: " (ex-message e)) :error? true))))
      (:reply r))))

(defn handle-message [message tool-definitions call-tool]
  (if (string? message)
    (let [parsed (try (json/read-str message) (catch Throwable e e))]
      (if (instance? Throwable parsed)
        (protocol/error-reply nil -32700 (str "Parse error: " (ex-message parsed)))
        (handle-parsed parsed tool-definitions call-tool)))
    (handle-parsed message tool-definitions call-tool)))

(defn write-str [v] (json/write-str v :escape-unicode false))
