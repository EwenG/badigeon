(ns badigeon.clean
  (:import [java.nio.file Paths FileVisitor Files FileVisitResult FileVisitOption]))

(defn same-directory? [path1 path2]
  (let [normalized-path1 (-> path1 (.toAbsolutePath) (.normalize))
        normalized-path2 (-> path2 (.toAbsolutePath) (.normalize))]
    (= (str normalized-path2) (str normalized-path1))))

(defn is-parent-path? [path1 path2]
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

(defn delete-recursively [dir]
  (when (.exists (.toFile dir))
    (Files/walkFileTree dir (make-file-visitor))))

(defn sanity-check [path allow-outside-target?]
  (let [root-path (Paths/get (System/getProperty "user.dir") (make-array String 0))
        target-path (.resolve root-path "target")]
    (when (not (is-parent-path? root-path path))
      (throw (IllegalArgumentException. "Cannot delete a directory outside of project root")))
    (when (and
           (not allow-outside-target?)
           (not (same-directory? target-path path))
           (not (is-parent-path? target-path path)))
      (throw (IllegalArgumentException. "Cannot delete a directory outside of target-directory. Consider setting the \"allow-outside-target?\" option if you really want to delete this directory.")))))

(defn clean [target-directories & {:keys [allow-outside-target?]}]
  (if (coll? target-directories)
    (doseq [dir target-directories]
      (let [path (Paths/get dir (make-array String 0))]
        (sanity-check path allow-outside-target?)
        (delete-recursively path)))
    (let [path (Paths/get target-directories (make-array String 0))]
      (sanity-check path allow-outside-target?)
      (delete-recursively path))))

(comment
  (clean "target")
  )

