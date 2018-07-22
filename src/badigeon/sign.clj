(ns badigeon.sign
  (:require [clojure.java.io :as io]
            [badigeon.utils :as utils])
  (:import [java.nio.file Path]))

;; Taken from leiningen

(defn- get-english-env
  "Returns env vars as a map with clojure keywords and LANGUAGE set to 'en'"
  []
  (let [env (System/getenv)]
    (assoc (zipmap (map keyword (keys env)) (vals env))
           :LANGUAGE "en")))

(defn- as-env-strings
  [env]
  (into-array String (map (fn [[k v]] (str (name k) "=" v)) env)))

(defn signing-args [file gpg-key]
  (let [key-spec (when gpg-key
                   ["--default-key" gpg-key])]
    `["--yes" "-ab" ~@key-spec "--" ~file]))

(defn sign
  ([artifact]
   (sign artifact nil))
  ([artifact {:keys [gpg-key command] :or {command "gpg"}}]
   (let [{:keys [file-path extension]} (utils/artifact-with-default-extension artifact)
         file-path (str file-path)
         command (or command "gpg")
         proc-env (as-env-strings (get-english-env))
         proc-args (into [command] (signing-args file-path gpg-key))
         proc (.exec (Runtime/getRuntime) (into-array String proc-args) proc-env)]
     (.addShutdownHook (Runtime/getRuntime)
                       (Thread. (fn [] (.destroy proc))))
     (with-open [proc-out (.getInputStream proc)
                 proc-err (.getErrorStream proc)]
       (let [exit-code (.waitFor proc)]
         (print (slurp (io/reader proc-out)))
         (print (slurp (io/reader proc-err)))
         (when (not= exit-code 0)
           (throw (ex-info "Error while signing"
                           {:exit-code exit-code
                            :file-path file-path
                            :command command
                            :proc-args proc-args})))))
     `{:file-path ~(str file-path ".asc")
       ~@(when extension [:extension (str extension ".asc")])
       ~@nil})))

