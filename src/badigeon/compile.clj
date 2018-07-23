(ns badigeon.compile
  (:refer-clojure :exclude [compile])
  (:import [java.nio.file Path Paths Files]
           [java.nio.file.attribute FileAttribute]
           [java.io File]
           [java.net URL URI URLClassLoader]))

(defn- do-compile
  ([namespaces]
   (do-compile namespaces nil))
  ([namespaces {:keys [compile-path compiler-options]}]
   (let [namespaces (if (coll? namespaces)
                      namespaces
                      [namespaces])
         compile-path (or compile-path *compile-path*)
         compile-path (if (string? compile-path)
                        (Paths/get compile-path (make-array String 0))
                        compile-path)]
     (Files/createDirectories compile-path (make-array FileAttribute 0))
     (binding [*compile-path* (str compile-path)
               *compiler-options* (or compiler-options *compiler-options*)]
       (doseq [namespace namespaces]
         (clojure.core/compile namespace))))))

(defn classpath->paths [classpath]
  (when classpath
    (for [path (-> classpath
                   clojure.string/trim
                   (.split File/pathSeparator))]
      (Paths/get path (make-array String 0)))))

(defn paths->urls [paths]
  (->> paths
       (map #(.toUri ^Path %))
       (map #(.toURL ^URI %))))

(defn -main [namespaces options]
  (let [namespaces (read-string namespaces)
        options (read-string options)]
    (do-compile namespaces options)
    (clojure.core/shutdown-agents)))

(defn compile
  "AOT compile one or several Clojure namespace(s). Dependencies of the compiled namespaces are
  always AOT compiled too. Namespaces are loaded while beeing compiled so beware of side effects.
  - namespaces: A symbol or a collection of symbols naming one or several Clojure namespaces.
  - compile-path: The path to the directory where .class files are emitted.
  - compiler-options: A map with the same format than clojure.core/*compiler-options*."
  ([namespaces]
   (compile namespaces nil))
  ([namespaces {:keys [compile-path compiler-options] :as options}]
   (let [classpath (System/getProperty "java.class.path")
         classpath-urls (->> classpath classpath->paths paths->urls (into-array URL))
         classloader (URLClassLoader. classpath-urls
                                      (.getParent (ClassLoader/getSystemClassLoader)))
         main-class (.loadClass classloader "clojure.main")
         main-method (.getMethod
                      main-class "main"
                      (into-array Class [(Class/forName "[Ljava.lang.String;")]))
         t (Thread. (fn []
                      (.setContextClassLoader (Thread/currentThread) classloader)
                      (.invoke
                       main-method
                       nil
                       (into-array
                        Object [(into-array String ["--main"
                                                    "badigeon.compile"
                                                    (pr-str namespaces)
                                                    (pr-str options)])]))))]
     (.start t)
     (.join t)
     (.close classloader))))

(comment
  
  (compile '[badigeon.main] {:compile-path "target/classes"
                             :compiler-options {:elide-meta [:doc :file :line :added]}})
  
  )

;; Cleaning non project classes: https://dev.clojure.org/jira/browse/CLJ-322

;; Cleaning non project classes is not supported by badigeon because:
;; Most of the time, libraries should be shipped without AOT. In the rare case when a library must be shipped AOT (let's say we don't want to ship the sources), directories can be removed programmatically, between build tasks. Shipping an application with AOT is a more common use case. In this case, AOT compiling dependencies is not an issue.

;; Compiling is done in a separate classloader because
;; - clojure.core/compile recursively compiles a namespace and its dependencies, unless the dependencies are already loaded. :reload-all does not help. Removing the AOT compiled files and recompiling results in a strange result: Source files are not reloaded, no .class file is produced. Using a separate classloader simulates a :reload-all for compile. 
