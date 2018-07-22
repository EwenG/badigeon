(ns badigeon.zip
  (:require [badigeon.utils :as utils])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.nio.file Path Paths
            Files FileVisitor FileVisitResult FileSystemLoopException
            FileVisitOption NoSuchFileException]
           [java.io BufferedOutputStream FileOutputStream]
           [java.util EnumSet]))

(defn make-file-visitor [^Path root-path ^ZipOutputStream zip-out]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (.putNextEntry zip-out (ZipEntry. (str (utils/relativize-path root-path path))))
      (Files/copy path zip-out)
      (.closeEntry zip-out)
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(defn zip [directory-path out-path]
  "Zip a directory. Outputs the zipped directory to \"out-path\". The directory to be zipped is often the directory created by the Badigeon \"bundle\" function."
  (let [directory-path (if (string? directory-path)
                         (Paths/get directory-path (make-array String 0))
                         directory-path)
        out-path (if (string? out-path)
                   (Paths/get out-path (make-array String 0))
                   out-path)]
    (with-open [zip-out (-> (.toFile ^Path out-path)
                            (FileOutputStream.)
                            (BufferedOutputStream.)
                            (ZipOutputStream.))]
      (Files/walkFileTree directory-path
                          (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                          Integer/MAX_VALUE
                          (make-file-visitor directory-path zip-out))
      (str out-path))))


