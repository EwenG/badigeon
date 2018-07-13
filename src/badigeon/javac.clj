(ns badigeon.javac
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as deps-reader])
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

(defn- get-classpath []
  (let [{:keys [config-files]} (deps-reader/clojure-env)
        deps-map (dissoc (deps-reader/read-deps config-files) :aliases)]
    (-> (deps/resolve-deps deps-map nil)
        (deps/make-classpath nil nil))))

(defn- javac-command [classpath compile-path paths opts]
  (into `["-cp" ~classpath ~@opts "-d" ~(str compile-path)]
        (map str paths)))

(defn- javac* [^JavaCompiler compiler source-dir compile-dir opts]
  (let [source-dir (Paths/get source-dir (make-array String 0))
        compile-dir (Paths/get compile-dir (make-array String 0))]
    (binding [*java-paths* (transient [])]
      (Files/walkFileTree source-dir
                          (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                          Integer/MAX_VALUE
                          (make-file-visitor compiler source-dir compile-dir visit-path))
      (let [java-paths (persistent! *java-paths*)]
        (when (seq java-paths)
          (let [javac-command (javac-command (get-classpath) compile-dir java-paths opts)]
            (let [compiler-out (ByteArrayOutputStream.)
                  compiler-err (ByteArrayOutputStream.)]
              (.run compiler nil compiler-out compiler-err (into-array String javac-command))
              (print (str compiler-out))
              (print (str compiler-err)))))))))

(defn javac
  "Compiles java source files found in the \"source-dirs\" directory/directories.
  source-dirs is the path of a directory or a collection of paths of directories.
  compile-path is the path to the directory where .class file are emitted.
  compiler-options is a vector of the options to be used when invoking the javac command."
  ([source-dirs]
   (javac source-dirs nil))
  ([source-dirs {:keys [compile-path compiler-options]
                 :or {compile-path "target/classes"}}]
   (let [compile-path (if (instance? Path compile-path)
                        (str compile-path)
                        compile-path)
         compiler (ToolProvider/getSystemJavaCompiler)]
     (when (nil? compiler)
       (throw (ex-info "Java compiler not found" {})))
     (if (coll? source-dirs)
       (doseq [source-dir source-dirs]
         (javac* compiler source-dir compile-path compiler-options))
       (javac* compiler source-dirs compile-path compiler-options)))))

(comment
  (javac ["src-java"]
         {:compile-path "target/classes"
          :compiler-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]})
  )
