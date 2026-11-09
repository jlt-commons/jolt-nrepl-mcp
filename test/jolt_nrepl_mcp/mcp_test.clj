(ns jolt-nrepl-mcp.mcp-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt-nrepl-mcp.mcp :as sut]))

(def tools
  [{:name "nrepl_eval"
    :description "Evaluate code"
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}}
                  :required ["code"]}}])

(defn handle [message]
  (sut/handle-message message tools (fn [_ _] (sut/tool-result "ok"))))

(deftest initialize-negotiates-protocol-and-identifies-server
  (let [response (handle {:jsonrpc "2.0" :id 1 :method "initialize"
                          :params {:protocolVersion "2025-03-26"}})]
    (is (= 1 (:id response)))
    (is (= "2025-03-26" (get-in response [:result :protocolVersion])))
    (is (map? (get-in response [:result :capabilities :tools])))
    (is (= "jolt-nrepl" (get-in response [:result :serverInfo :name])))
    (testing "instructions tell the agent when to reach for the tools"
      (is (re-find #"nrepl_eval" (get-in response [:result :instructions])))
      (is (re-find #"(?i)before (you )?edit" (get-in response [:result :instructions]))
          "the REPL comes first: reproduce and try the fix before editing files")))
  (is (= "2025-06-18"
         (get-in (handle {:jsonrpc "2.0" :id "a" :method "initialize"
                          :params {:protocolVersion "2099-01-01"}})
                 [:result :protocolVersion]))))

(deftest notifications-ping-and-tool-list-follow-json-rpc
  (is (nil? (handle {:jsonrpc "2.0" :method "notifications/initialized"})))
  (is (= {} (get-in (handle {:jsonrpc "2.0" :id 2 :method "ping"}) [:result])))
  (is (= tools (get-in (handle {:jsonrpc "2.0" :id "tools" :method "tools/list"}) [:result :tools]))))

(deftest tool-call-forwards-name-and-arguments
  (let [seen (atom nil)
        args {"code" "(+ 1 2)"}
        response (sut/handle-message
                  {:jsonrpc "2.0" :id 3 :method "tools/call"
                   :params {:name "nrepl_eval" :arguments args}}
                  tools
                  (fn [name actual]
                    (reset! seen [name actual])
                    (sut/tool-result "3")))]
    (is (= ["nrepl_eval" args] @seen))
    (is (= "3" (get-in response [:result :content 0 :text])))
    (is (false? (get-in response [:result :isError])))))

(deftest malformed-and-invalid-requests-return-json-rpc-errors
  (let [parse-error (handle "{not-json")
        invalid (handle {:jsonrpc "2.0" :id 4})
        unknown (handle {:jsonrpc "2.0" :id "unknown" :method "no/such"})]
    (is (= -32700 (get-in parse-error [:error :code])))
    (is (nil? (:id parse-error)))
    (is (= -32600 (get-in invalid [:error :code])))
    (is (= -32601 (get-in unknown [:error :code])))
    (is (= "unknown" (:id unknown)))))

(deftest valid-json-invalid-shapes-are-not-parse-errors
  (let [scalar (handle "42")
        bad-initialize (handle {:jsonrpc "2.0" :id "init" :method "initialize" :params "bad"})
        bad-call (handle {:jsonrpc "2.0" :id "call" :method "tools/call" :params "bad"})]
    (is (= -32600 (get-in scalar [:error :code])))
    (is (= -32602 (get-in bad-initialize [:error :code])))
    (is (= "init" (:id bad-initialize)))
    (is (= -32602 (get-in bad-call [:error :code])))
    (is (= "call" (:id bad-call)))))

(deftest tool-exceptions-are-tool-results
  (let [response (sut/handle-message
                  {:jsonrpc "2.0" :id 5 :method "tools/call"
                   :params {:name "nrepl_eval" :arguments {}}}
                  tools
                  (fn [_ _] (throw (ex-info "boom" {}))))]
    (is (true? (get-in response [:result :isError])))
    (is (re-find #"boom" (get-in response [:result :content 0 :text])))))

(deftest unicode-survives-raw-json-decoding
  (let [seen (atom nil)]
    (sut/handle-message
     "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"nrepl_eval\",\"arguments\":{\"code\":\"😀\"}}}"
     tools
     (fn [_ args] (reset! seen args) (sut/tool-result "ok")))
    (is (= "😀" (get @seen "code")))
    (reset! seen nil)
    (sut/handle-message
     "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"nrepl_eval\",\"arguments\":{\"code\":\"\\ud83d\\ude00\"}}}"
     tools
     (fn [_ args] (reset! seen args) (sut/tool-result "ok")))
    (is (= "😀" (get @seen "code")))))
