(ns jolt-nrepl-mcp.spec-runner
  "Check the writ specs.

    jolt spec            check every spec
    jolt plan            print the state graphs, signatures and laws

  Before the target namespace exists, a check reports
  \"no implementation yet\". That is a pending spec, not a failure: the
  gate passes while every spec is either :ok or pending. A spec that
  contradicts itself, or a law that does not call the target, fails."
  (:require [clojure.string :as string]
            [writ.spec :as spec]))

(def spec-namespaces
  '[jolt-nrepl-mcp.spec.bencode-spec
    jolt-nrepl-mcp.spec.protocol-spec])

(defn- pending? [report]
  (and (not (:ok report))
       (string/includes? (str (:message report)) "no implementation yet")))

(defn- status [report]
  (cond
    (:ok report) "ok"
    (pending? report) "pending (no implementation yet)"
    :else "FAILED"))

(defn -main [& args]
  (if (= "--plan" (first args))
    (do
      (doseq [n spec-namespaces]
        (println)
        (println "===" n "===")
        (try
          (println (spec/plan n))
          (catch Throwable e
            (println "plan failed:" e)
            (System/exit 1))))
      (System/exit 0))
    (let [results
          (mapv (fn [n]
                  (let [r (try
                            (require n)
                            (spec/check n)
                            (catch Throwable e
                              {:ok false :message (str "check threw: " e)}))]
                    [n r]))
                spec-namespaces)]
      (doseq [[n r] results]
        (println (format "%-34s %s" (str n) (status r)))
        (when-not (or (:ok r) (pending? r))
          (println (:message r))))
      (System/exit (if (every? (fn [[_ r]] (or (:ok r) (pending? r))) results) 0 1)))))
