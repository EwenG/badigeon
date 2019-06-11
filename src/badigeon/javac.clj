(ns badigeon.javac
  (:require [badigeon.classpath :as classpath]
            [badigeon.utils :as utils])
  (:import [javax.tools ToolProvider JavaCompiler]
           [java.nio.file Path Paths Files FileVisitor FileVisitResult
            FileVisitOption FileSystemLoopException NoSuchFileException]
           [java.util EnumSet]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.io ByteArrayOutputStream]))

(defn- make-file-visitor [compiler source-dir compile-dir visitor-fn]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (visitor-fn compiler source-dir compile-dir path attrs)
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(defn is-java-file? [path ^BasicFileAttributes attrs]
  (and (.isRegularFile attrs) (.endsWith (str path) ".java")))

(def ^{:dynamic true
       :private true}
  *java-paths* nil)

(defn- visit-path [compiler source-dir compile-dir path attrs]
  (when (is-java-file? path attrs)
    (Files/createDirectories compile-dir (make-array FileAttribute 0))
    (set! *java-paths* (conj! *java-paths* path))))

(defn- javac-command [classpath compile-path paths opts]
  (into `[~@(when classpath ["-cp" classpath]) ~@opts "-d" ~(str compile-path)]
        (map str paths)))

(defn- javac* [^JavaCompiler compiler source-dir compile-dir opts]
  (let [source-dir (utils/make-path source-dir)
        compile-dir (utils/make-path compile-dir)
        provided-classpath? (some #(= "-cp" %) opts)
        the-classpath (when-not provided-classpath? (classpath/make-classpath))]
    (binding [*java-paths* (transient [])]
      (Files/walkFileTree source-dir
                          (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                          Integer/MAX_VALUE
                          (make-file-visitor compiler source-dir compile-dir visit-path))
      (let [java-paths (persistent! *java-paths*)]
        (when (seq java-paths)
          ;; This starts Clojure agents. We might need to call shutdown-agents after this
          (let [javac-command (javac-command the-classpath compile-dir java-paths opts)]
            (let [compiler-out (ByteArrayOutputStream.)
                  compiler-err (ByteArrayOutputStream.)]
              (.run compiler nil compiler-out compiler-err (into-array String javac-command))
              (print (str compiler-out))
              (print (str compiler-err)))))))))

(defn javac
  "Compiles java source files found in the \"source-dir\" directory. Note that the badigeon.javac/javac functions triggers the start of Clojure agents. You might want to call clojure.core/shutdown-agents to close the agent thread pools.
  - source-dir: The path of a directory containing java source files.
  - compile-path: The path to the directory where .class file are emitted.
  - javac-options: A vector of the options to be used when invoking the javac command. Default to using a \"-cp\" argument computed using the project deps.edn file (without merging the system-level and user-level deps.edn maps) and a \"-d\" argument equal to the compile-path."
  ([source-dir]
   (javac source-dir nil))
  ([source-dir {:keys [compile-path javac-options]
                :or {compile-path "target/classes"}}]
   (let [compile-path (if (instance? Path compile-path)
                        (str compile-path)
                        compile-path)
         compiler (ToolProvider/getSystemJavaCompiler)]
     (when (nil? compiler)
       (throw (ex-info "Java compiler not found" {})))
     (javac* compiler source-dir compile-path javac-options))))

(comment
  (javac "src-java"
         {:compile-path "target/classes"
          :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]})
  )
