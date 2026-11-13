(ns jolt-nrepl-mcp.discover
  "Find nREPL servers running on this machine: listening sockets from lsof,
  each process's working directory, kept only when the port answers an nREPL
  describe. Ported from clojure-mcp's nrepl_ports tool (EPL-2.0), with the
  project dir taken from the process instead of an eval."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [jolt-nrepl-mcp.client :as client]))

(def ^:private candidate-command #"(?i)jolt|java|clojure|babashka|^bb$|nrepl")

(defn parse-lsof
  "Listening TCP sockets from `lsof -nP -iTCP -sTCP:LISTEN` output:
  [{:command :pid :port}], one per pid and port."
  [out]
  (->> (str/split-lines (or out ""))
       (keep (fn [line]
               (let [[command pid] (str/split (str/trim line) #"\s+")]
                 (when-let [[_ port] (re-find #"TCP\s+(?:\*|[\d.]+|\[[0-9a-fA-F:]*\]):(\d+)\s+\(LISTEN\)" line)]
                   (when-let [pid (parse-long (or pid ""))]
                     {:command command :pid pid :port (parse-long port)})))))
       distinct
       vec))

(defn- sh [& args]
  (try (let [{:keys [exit out]} (apply p/shell {:continue true :out :string :err :string} args)]
         (when (zero? exit) out))
       (catch Throwable _ nil)))

(defn- process-cwd [pid]
  (some->> (sh "lsof" "-a" "-p" (str pid) "-d" "cwd" "-Fn")
           str/split-lines
           (some #(when (str/starts-with? % "n") (subs % 1)))))

(defn scan
  "Running nREPL servers: [{:port :pid :command :dir :ops}], or
  {:error ..} when lsof is not available."
  []
  (if-let [out (sh "lsof" "-nP" "-iTCP" "-sTCP:LISTEN")]
    (->> (parse-lsof out)
         (filter #(re-find candidate-command (:command %)))
         (pmap (fn [{:keys [pid port] :as s}]
                 (when-let [{:keys [ops]} (client/probe "127.0.0.1" port)]
                   (assoc s :dir (process-cwd pid) :ops ops))))
         (remove nil?)
         (sort-by :port)
         vec)
    {:error "lsof is not available, so running servers can't be listed"}))
