(ns badigeon.clean
  (:require [badigeon.utils :as utils])
  (:import [java.nio.file Path Paths FileVisitor Files FileVisitResult FileVisitOption]))

(defn same-directory? [^Path path1 ^Path path2]
  (let [normalized-path1 (-> path1 (.toAbsolutePath) (.normalize))
        normalized-path2 (-> path2 (.toAbsolutePath) (.normalize))]
    (= (str normalized-path2) (str normalized-path1))))

(defn is-parent-path? [^Path path1 ^Path path2]
  (let [normalized-path1 (-> path1 (.toAbsolutePath) (.normalize))
        normalized-path2 (-> path2 (.toAbsolutePath) (.normalize))]
    (and (.startsWith (str normalized-path2) (str normalized-path1))
         (not= (str normalized-path2) (str normalized-path1)))))

(defn- make-file-visitor []
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      (if (nil? exception)
        (do
          (Files/delete dir)
          FileVisitResult/CONTINUE)
        (throw exception)))
    (visitFile [_ path attrs]
      (Files/delete path)
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (throw exception))))

(defn delete-recursively [^Path dir]
  (when (.exists (.toFile dir))
    (Files/walkFileTree dir (make-file-visitor))))

(defn sanity-check [path allow-outside-target?]
  (let [root-path (utils/make-path (System/getProperty "user.dir"))
        target-path (.resolve root-path "target")]
    (when (not (is-parent-path? root-path path))
      (throw (IllegalArgumentException. "Cannot delete a directory outside of project root")))
    (when (and
           (not allow-outside-target?)
           (not (same-directory? target-path path))
           (not (is-parent-path? target-path path)))
      (throw (IllegalArgumentException. "Cannot delete a directory outside of target-directory. Consider setting the \"allow-outside-target?\" option if you really want to delete this directory.")))))

(defn clean
  "Delete the target-directory. The directory to delete must not be outside of project root. By default, the directory to delete must either be the directory named \"target\" or must be inside the directory named \"target\". Setting the \"allow-outside-target?\" parameter to true makes deleting directories outside \"target\" possible."
  ([target-directory]
   (clean target-directory nil))
  ([target-directory {:keys [allow-outside-target?]}]
   (let [path (if (string? target-directory)
                (utils/make-path target-directory)
                target-directory)]
     (sanity-check path allow-outside-target?)
     (delete-recursively path))))

(comment
  (clean "target")
  )

;; We do not forbid file overwriting in compile/javac/jar/bundle because
;; compile -> does not work with an existing file which is not a directory
;;         -> can only overwrite .class files
;; jar -> can only overwrite a .jar file
;; javac/bundle -> quite similar to compile
