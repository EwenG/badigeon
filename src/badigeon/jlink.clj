(ns badigeon.jlink
  (:require [badigeon.utils :as utils]
            [clojure.string :as string])
  (:import [java.util Optional]
           [java.util.spi ToolProvider]
           [java.io ByteArrayOutputStream PrintStream File]
           [java.nio.file Paths Path]))

(defmacro interface-static-call
  [sym argtypes]
  `(let [m# (.getMethod ~(symbol (namespace sym))
                        ~(name sym)
                        (into-array Class ~argtypes))]
     (fn [& args#]
       (.invoke m# nil (to-array args#)))))

(defn- jlink-command [java-home out-path module-path modules jlink-options]
  (into ["--module-path" module-path
         "--add-modules" modules
         "--output" (str out-path)]
        jlink-options))

(defn jlink
  "Creates a custom JRE using the jlink command. To be run, this function requires a JDK >= version 9. 
  - out-path: The output folder of the custom JRE (this often is the same out-path than the one provided to the Badigeon \"bundle\" function). By default the JRE is output in the out-path/runtime directory.
  - jlink-path: The folder where the custom JRE is output, relative to \"out-path\". Default to \"runtime\".
  - module-path: The path where the java module are searched for. Default to \"JAVA_HOME/jmods\".
  - modules: A vector of modules to be used when creating the custom JRE. Default to [\"java.base\"]
  - jlink-options: The options used when executing the jlink command. Default to [\"--strip-debug\" \"--no-man-pages\" \"--no-header-files\" \"--compress=2\"]"
  ([out-path]
   (jlink out-path nil))
  ([out-path {:keys [jlink-path module-path modules jlink-options]
              :or {jlink-path (utils/make-path "runtime")
                   modules ["java.base"]
                   jlink-options ["--strip-debug" "--no-man-pages"
                                  "--no-header-files" "--compress=2"]}}]
   (let [java-home (System/getProperty "java.home")
         ^Path out-path (if (string? out-path)
                          (utils/make-path out-path)
                          out-path)
         ^Path jlink-path (if (string? jlink-path)
                            (utils/make-path jlink-path)
                            jlink-path)
         jlink-path (.resolve out-path jlink-path)
         module-path (or module-path (str java-home File/separator "jmods"))
         modules (string/join "," modules)
         maybe-jlink ((interface-static-call ToolProvider/findFirst [java.lang.String]) "jlink")
         jlink (.orElse ^Optional maybe-jlink nil)]
     (when (nil? jlink)
       (throw (ex-info "JLink tool not found" {})))
     (let [jlink-out (ByteArrayOutputStream.)
           jlink-err (ByteArrayOutputStream.)]
       (.run ^ToolProvider jlink (PrintStream. jlink-out) (PrintStream. jlink-err)
             ^"[Ljava.lang.String;" (into-array
                                     String
                                     (jlink-command java-home jlink-path module-path modules
                                                    jlink-options)))
       (print (str jlink-out))
       (print (str jlink-err))))))


(comment
  (let [out-path (badigeon.bundle/make-out-path 'badigeon/badigeon {:mvn/version utils/version})]
    (badigeon.clean/clean (.resolve out-path "runtime"))
    (jlink out-path))
  
  )
