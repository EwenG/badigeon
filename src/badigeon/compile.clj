(ns badigeon.compile
  (:require [badigeon.classpath :as classpath]
            [clojure.java.io :as io]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as deps-reader]
            [badigeon.utils :as utils])
  (:refer-clojure :exclude [compile])
  (:import [java.nio.file Path Paths Files]
           [java.nio.file.attribute FileAttribute]
           [java.io File]
           [java.net URL URI URLClassLoader]
           [java.util.jar JarFile JarEntry]))

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
                        Object [(into-array String ["--eval" in-script])]))))
         compile-exception (atom nil)
         ]
     (.setUncaughtExceptionHandler 
       t (reify Thread$UncaughtExceptionHandler
           (^void uncaughtException  [_ ^Thread t ^Throwable e] (reset! compile-exception e))))
     (.start t)
     (.join t)
     (.close classloader)
     (when-let [exception @compile-exception]
       (throw (ex-info "Exception during compilation" {} exception)))
     )))

(defn- extract-classes-from-dependency [path ^Path out-path]
  (let [^Path path (if (string? path)
                     (Paths/get path (make-array String 0))
                     path)
        f (.toFile path)]
    (when (and (.exists f) (not (.isDirectory f))
               (.endsWith (str path) ".jar"))
      (let [jar-file (JarFile. (str path))
            entries (enumeration-seq (.entries jar-file))]
        (doseq [^JarEntry entry entries]
          (let [entry-path (str (.getName entry))]
            (when (.endsWith entry-path ".class")
              (let [f-path (.resolve out-path entry-path)]
                (Files/createDirectories (.getParent f-path) (make-array FileAttribute 0))
                (io/copy (.getInputStream jar-file entry) (.toFile f-path))))))))))

(defn extract-classes-from-dependencies
  "Extract classes from jar dependencies. By default, classes are extracted the \"target/classes\" folder. This function can be used to circumvent the fact that badigeon.compile/compile does not compile dependencies that are already AOT, such as Clojure itself.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies with a jar format resolved from this map are searched for native dependencies. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - excluded-libs: A set of lib symbols to be excluded from the produced bundle. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false."
  ([]
   (extract-classes-from-dependencies nil))
  ([{:keys [out-path
            deps-map
            excluded-libs
            allow-unstable-deps?] :as opts}]
   (let [out-path (or out-path "target/classes")
         out-path (if (string? out-path)
                    (Paths/get out-path (make-array String 0))
                    out-path)
         deps-map (or deps-map (deps-reader/slurp-deps "deps.edn"))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         resolved-deps (deps/resolve-deps deps-map nil)]
     (when-not allow-unstable-deps?
       (utils/check-for-unstable-deps #(or (utils/snapshot-dep? %) (utils/local-dep? %)) resolved-deps))
     (Files/createDirectories out-path (make-array FileAttribute 0))
     (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
       (when-not (contains? excluded-libs lib)
         (doseq [path paths]
           (extract-classes-from-dependency path out-path))))
     out-path)))

(comment
  
  (compile '[badigeon.main] {:compile-path "target/classes"
                             :compiler-options {:elide-meta [:doc :file :line :added]}})

  (extract-classes-from-dependencies
   {:deps-map (assoc (deps-reader/slurp-deps "deps.edn") :deps '{org.clojure/clojure {:mvn/version "1.9.0"}})
    :excluded-libs #{'org.clojure/clojure}})
  
  )

;; Cleaning non project classes: https://dev.clojure.org/jira/browse/CLJ-322

;; Cleaning non project classes is not supported by badigeon because:
;; Most of the time, libraries should be shipped without AOT. In the rare case when a library must be shipped AOT (let's say we don't want to ship the sources), directories can be removed programmatically, between build tasks. Shipping an application with AOT is a more common use case. In this case, AOT compiling dependencies is not an issue.

;; Compiling is done in a separate classloader because
;; - clojure.core/compile recursively compiles a namespace and its dependencies, unless the dependencies are already loaded. :reload-all does not help. Removing the AOT compiled files and recompiling results in a strange result: Source files are not reloaded, no .class file is produced. Using a separate classloader simulates a :reload-all for compile. 
