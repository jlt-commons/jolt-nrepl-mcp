(ns jolt-nrepl-mcp.spec.protocol-spec
  "MCP routing: a JSON-RPC 2.0 message in, a reply or a tool to call out. A
  notification (no id) gets no reply; a malformed request, an unknown method
  and an unknown tool each get their error code; initialize negotiates the
  protocol version."
  (:require [writ.spec :refer [spec ann graph law refine]]))

(spec jolt-nrepl-mcp.protocol {:require :tested})

(ann text-result [Any Bool -> Any])
(ann reply [Any Any -> Any])
(ann error-reply [Any Int String -> Any])
(ann route [Any (List Any) Any String -> Decision])

;; what route decides: a reply to send (nil for none), or a tool to call
(refine Decision [d {:reply (Opt Any) :call (Opt {:name String :arguments Any})}] true)

(graph routing
  {:start :message
   :states {:message Any
            :decision Decision}
   :edges {:message {[route (List Any) Any String] #{:decision}}}
   :final [:decision]})

(def tools [{:name "echo"}])

(def info {:name "test"})

(law text-result-shape
  (forall [s String, e Bool]
    (= {:content [{:type "text" :text s}] :isError e} (text-result s e))))

(law reply-carries-the-id
  (forall [id Int, r Any]
    (= {:jsonrpc "2.0" :id id :result r} (reply {"id" id} r))))

(law notifications-get-no-reply
  (forall [r Any]
    (nil? (reply {"jsonrpc" "2.0" "method" "x"} r))))

(law initialize-echoes-a-supported-version
  (= "2025-03-26"
     (get-in (route (merge {"jsonrpc" "2.0" "id" 1} {"method" "initialize" "params" {"protocolVersion" "2025-03-26"}}) tools info "use it")
             [:reply :result :protocolVersion])))

(law initialize-falls-back-to-the-newest
  (forall [v String]
    (=> (not (contains? #{"2024-11-05" "2025-03-26" "2025-06-18"} v))
        (= "2025-06-18"
           (get-in (route (merge {"jsonrpc" "2.0" "id" 1} {"method" "initialize" "params" {"protocolVersion" v}}) tools info "use it")
                   [:reply :result :protocolVersion])))))

(law initialized-is-silent
  (= {:reply nil} (route {"jsonrpc" "2.0" "method" "notifications/initialized"} tools info "use it")))

(law unknown-method-is-32601
  (= -32601 (get-in (route {"jsonrpc" "2.0" "id" 1 "method" "nope"} tools info "use it")
                    [:reply :error :code])))

(law invalid-request-is-32600
  (forall [id Int]
    (= -32600 (get-in (route {"jsonrpc" "1.0" "id" id "method" "ping"} tools info "use it")
                      [:reply :error :code]))))

(law unknown-tool-is-32602
  (= -32602 (get-in (route (merge {"jsonrpc" "2.0" "id" 1} {"method" "tools/call" "params" {"name" "nope"}}) tools info "use it")
                    [:reply :error :code])))

(law tools-call-names-the-tool
  (forall [args (Map String String)]
    (= {:call {:name "echo" :arguments args}}
       (route (merge {"jsonrpc" "2.0" "id" 1} {"method" "tools/call" "params" {"name" "echo" "arguments" args}}) tools info "use it"))))

(law tools-list-lists-them
  (= tools (get-in (route (merge {"jsonrpc" "2.0" "id" 1} {"method" "tools/list"}) tools info "use it") [:reply :result :tools])))

(law errors-to-notifications-are-dropped
  (= {:reply nil}
     (route {"jsonrpc" "2.0" "method" "tools/call" "params" {"name" "nope"}} tools info "use it")))

(law error-reply-shape
  (forall [id Int, code Int, s String]
    (= {:jsonrpc "2.0" :id id :error {:code code :message s}} (error-reply id code s))))

(law ping-replies-empty
  (= {} (get-in (route (merge {"jsonrpc" "2.0" "id" 1} {"method" "ping"}) tools info "use it")
                [:reply :result])))

(law not-a-map-is-32600
  (forall [x Int]
    (= -32600 (get-in (route x tools info "use it") [:reply :error :code]))))

(law initialize-params-must-be-an-object
  (and (= -32602 (get-in (route {"jsonrpc" "2.0" "id" 1 "method" "initialize" "params" [1]}
                                tools info "use it")
                         [:reply :error :code]))
       (= {:reply nil} (route {"jsonrpc" "2.0" "method" "initialize" "params" [1]}
                              tools info "use it"))))

(law tools-call-needs-params-and-a-name
  (and (= -32602 (get-in (route {"jsonrpc" "2.0" "id" 1 "method" "tools/call"} tools info "use it")
                         [:reply :error :code]))
       (= -32602 (get-in (route {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" 3}}
                                tools info "use it")
                         [:reply :error :code]))))

(law missing-arguments-are-empty
  (= {:call {:name "echo" :arguments {}}}
     (route {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "echo"}}
            tools info "use it")))

(law a-tool-notification-still-calls-the-tool
  (= {:call {:name "echo" :arguments {}}}
     (route {"jsonrpc" "2.0" "method" "tools/call" "params" {"name" "echo"}} tools info "use it")))

(law unknown-notifications-are-silent
  (= {:reply nil} (route {"jsonrpc" "2.0" "method" "whatever"} tools info "use it")))

(law initialize-sends-the-server-info-and-instructions
  (forall [n String, text String]
    (let [r (route {"jsonrpc" "2.0" "id" 1 "method" "initialize"} tools {:name n} text)]
      (and (= {:name n} (get-in r [:reply :result :serverInfo]))
           (= text (get-in r [:reply :result :instructions]))))))
