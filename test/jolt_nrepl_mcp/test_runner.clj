(ns jolt-nrepl-mcp.test-runner
  (:require [clojure.test :as t]
            jolt-nrepl-mcp.bencode-test
            jolt-nrepl-mcp.client-test
            jolt-nrepl-mcp.forms-test
            jolt-nrepl-mcp.mcp-test
            jolt-nrepl-mcp.server-test))

(def test-namespaces
  '[jolt-nrepl-mcp.bencode-test jolt-nrepl-mcp.client-test jolt-nrepl-mcp.forms-test jolt-nrepl-mcp.mcp-test jolt-nrepl-mcp.server-test])

(defn -main [& args]
  (let [nss (if (seq args) (map symbol args) test-namespaces)
        {:keys [fail error]} (apply t/run-tests nss)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
