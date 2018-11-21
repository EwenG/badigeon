(ns badigeon.compile
  (:require [badigeon.classpath :as classpath]
            [clojure.java.io :as io])
  (:refer-clojure :exclude [compile])
  (:import [java.nio.file Path Paths Files]
           [java.nio.file.attribute FileAttribute]
           [java.io File]
           [java.net URL URI URLClassLoader]))

(defn do-compile->string [namespaces options]
  (format
   "(let [namespaces (quote %s)
          {:keys [compile-path compiler-options] :as options} (quote %s)
          namespaces (if (coll? namespaces)
                       namespaces
                       [namespaces])
          compile-path (or compile-path \"target/classes\")
          compile-path (if (string? compile-path)
                         (java.nio.file.Paths/get compile-path (make-array String 0))
                         compile-path)]
      (binding [*compile-path* (str compile-path)
                *compiler-options* (or compiler-options *compiler-options*)]
        (doseq [namespace namespaces]
          (clojure.core/compile namespace)))
      (clojure.core/shutdown-agents))"
   namespaces options))

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

(defn compile
  "AOT compile one or several Clojure namespace(s). Dependencies of the compiled namespaces are
  always AOT compiled too, unless they come under an already AOT compiled form. Namespaces are
  loaded while beeing compiled so beware of side effects.
  - namespaces: A symbol or a collection of symbols naming one or several Clojure namespaces.
  - compile-path: The path to the directory where .class files are emitted. Default to \"target/classes\".
  - compiler-options: A map with the same format than clojure.core/*compiler-options*.
  - classpath: The classpath used while AOT compiling. Defaults to a classpath string computed using the deps.edn file of the current project, without merging the system-level and user-level deps.edn maps."
  ([namespaces]
   (compile namespaces nil))
  ([namespaces {:keys [compile-path compiler-options classpath] :as options}]
   (let [compile-path (or compile-path "target/classes")
         compile-path (if (string? compile-path)
                        (Paths/get compile-path (make-array String 0))
                        compile-path)
         options (assoc options :compile-path (str compile-path))
         ;; We must ensure early that the compile-path exists otherwise the Clojure Compiler has issues compiling classes / loading classes. I'm not sure why exactly
         _ (Files/createDirectories compile-path (make-array FileAttribute 0))
         classpath (or classpath (classpath/make-classpath))
         classpath-urls (->> classpath classpath->paths paths->urls (into-array URL))
         classloader (URLClassLoader. classpath-urls
                                      (.getParent (ClassLoader/getSystemClassLoader)))
         main-class (.loadClass classloader "clojure.main")
         main-method (.getMethod
                      main-class "main"
                      (into-array Class [(Class/forName "[Ljava.lang.String;")]))
         ;; Eval the AOT compile script as a string, otherwise we would need a main method and a way
         ;; to add the main method namespace to the classpath
         in-script (do-compile->string (pr-str namespaces) (pr-str options))
         t (Thread. (fn []
                      (.setContextClassLoader (Thread/currentThread) classloader)
                      (.invoke
                       main-method
                       nil
                       (into-array
                        Object [(into-array String ["--eval" in-script])]))))]
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
