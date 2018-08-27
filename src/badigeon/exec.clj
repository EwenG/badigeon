(ns badigeon.exec
  (:require [clojure.java.io :as io]))

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

(defn exec
  "Synchronously executes the specified command in a separate process. Prints the process output using \"clojure.core/print\". Throws an exception when the process exit code is not 0.
  - command: The command to be executed.
  - proc-args: A collection of command arguments. Default to no argument.
  - error-msg: The error message of the exception thrown upon error. Default to \"Process execution error\"."
  ([command]
   (exec command nil))
  ([command {:keys [proc-args error-msg] :or {error-msg "Process execution error"}}]
   (let [proc-env (as-env-strings (get-english-env))
         proc-command-args (into [command] proc-args)
         proc (.exec (Runtime/getRuntime)
                     ^"[Ljava.lang.String;" (into-array String proc-command-args)
                     ^"[Ljava.lang.String;" proc-env)]
     (.addShutdownHook (Runtime/getRuntime)
                       (Thread. (fn [] (.destroy proc))))
     (with-open [proc-out (.getInputStream proc)
                 proc-err (.getErrorStream proc)]
       (let [exit-code (.waitFor proc)]
         (print (slurp (io/reader proc-out)))
         (print (slurp (io/reader proc-err)))
         (when (not= exit-code 0)
           (throw (ex-info error-msg
                           {:exit-code exit-code
                            :command command
                            :proc-args proc-args}))))))))

(comment
  (exec "scss" {:proc-args ["-h"]})
  (exec "scss" {:proc-args ["-yyyy"] :error-msg "scss error"})
  )
