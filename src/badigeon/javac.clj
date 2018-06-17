(ns badigeon.javac
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.util.maven :as deps-maven-utils]
            [clojure.tools.deps.alpha.reader :as deps-reader])
  (:import [javax.tools ToolProvider]
           [java.nio.file Path Files FileVisitor FileVisitResult
            FileVisitOption FileSystemLoopException NoSuchFileException]
           [java.util EnumSet]
           [java.nio.file Paths LinkOption]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.io ByteArrayOutputStream]))

(defn- make-file-visitor [source-dir compile-dir visitor-fn]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (visitor-fn source-dir compile-dir path #_(relativize-path *root-path* path) attrs)
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(defn is-java-file? [path attrs]
  (and (.isRegularFile attrs) (.endsWith (str path) ".java")))

(defn relativize-path [^Path root-path ^Path path]
  (if (= root-path path)
    (if-let [parent-path (.getParent path)]
      (.normalize (.relativize parent-path path))
      path)
    (.normalize (.relativize root-path path))))

(def ^{:dynamic true
       :private true}
  *java-paths* nil)

(defn- visit-path [source-dir compile-dir path attrs]
  (when (is-java-file? path attrs)
    (let [rel-source (relativize-path source-dir path)
          rel-compiled (-> (str rel-source)
                           (.replaceFirst "\\.java$" ".class")
                           (Paths/get (make-array String 0)))
          last-modified-source (.lastModifiedTime attrs)
          last-modified-compiled (try (-> (.resolve compile-dir rel-compiled)
                                          (Files/readAttributes BasicFileAttributes
                                                                (make-array LinkOption 0))
                                          (.lastModifiedTime))
                                      (catch NoSuchFileException e nil))]
      (when (or (nil? last-modified-compiled)
                (> (.compareTo last-modified-source last-modified-compiled) 0))
        (when-let [compiler (javax.tools.ToolProvider/getSystemJavaCompiler)]
          (Files/createDirectories compile-dir (make-array FileAttribute 0))
          (set! *java-paths* (conj! *java-paths* path)))))))

(defn- ensure-compiler-exists []
  (when (nil? (javax.tools.ToolProvider/getSystemJavaCompiler))
    (throw (IllegalStateException. "Java compiler not found"))))

(defn- get-classpath []
  (let [{:keys [config-files]} (deps-reader/clojure-env)
        deps-map (dissoc (deps-reader/read-deps config-files) :aliases)]
    (-> (deps/resolve-deps deps-map nil)
        (deps/make-classpath nil nil))))

(defn- javac-command [classpath compile-path paths opts]
  (into `["-cp" ~classpath ~@opts "-d" ~(str compile-path)]
        (map str paths)))

(defn- javac* [source-dir compile-dir opts]
  (let [source-dir (Paths/get source-dir (make-array String 0))
        compile-dir (Paths/get compile-dir (make-array String 0))]
    (binding [*java-paths* (transient [])]
      (Files/walkFileTree source-dir
                          (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                          Integer/MAX_VALUE
                          (make-file-visitor source-dir compile-dir visit-path))
      (let [java-paths (persistent! *java-paths*)]
        (when (seq java-paths)
          (let [javac-command (javac-command (get-classpath) compile-dir java-paths opts)]
            (when-let [compiler (javax.tools.ToolProvider/getSystemJavaCompiler)]
              (let [compiler-out (ByteArrayOutputStream.)
                    compiler-err (ByteArrayOutputStream.)]
                (.run compiler nil compiler-out compiler-err (into-array String javac-command))
                (print (str compiler-out))
                (print (str compiler-err))))))))))

(defn javac
  ([source-dirs compile-dir]
   (javac source-dirs compile-dir nil))
  ([source-dirs compile-dir opts]
   (if (sequential? source-dirs)
     (doseq [source-dir source-dirs]
       (javac* source-dir compile-dir opts))
     (javac* source-dirs compile-dir opts))))


(comment
  *compile-path*
  (javac ["src-java" "src-java2"] "target/classes")

  (javac ["src-java" "src-java2"] "target/classes"
         ["-target" "1.6" "-source" "1.6" "-Xlint:-options"])
  )
