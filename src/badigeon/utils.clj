(ns badigeon.utils
  (:require [clojure.tools.deps.alpha.util.maven :as maven]
            [clojure.java.io :as io])
  (:import [java.nio.file Paths Path StandardCopyOption]
           [java.util.jar JarEntry JarOutputStream]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:const version "1.2")

(def ^"[Ljava.nio.file.StandardCopyOption;" copy-options
  (into-array StandardCopyOption
              [StandardCopyOption/COPY_ATTRIBUTES
               StandardCopyOption/REPLACE_EXISTING]))

(def ^"[Ljava.nio.file.StandardCopyOption;" copy-options-no-replace
  (into-array StandardCopyOption [StandardCopyOption/COPY_ATTRIBUTES]))

(defn ^Path make-path
  "Returns a java.nio.file.Path constructed from the provided String(s)."
  [path & paths]
  (Paths/get (str path) (into-array String (map str paths))))

(defn relativize-path [^Path root-path ^Path path]
  (if (= root-path path)
    (if-let [parent-path (.getParent path)]
      (.normalize (.relativize parent-path path))
      path)
    (.normalize (.relativize root-path path))))

(defn make-out-path [artifact-id {:keys [:mvn/version classifier extension]}]
  (let [classifier (when classifier (str "-" (name classifier)))
        version (when version (str "-" version))
        extension (when extension (str "." extension))]
    (make-path
     (System/getProperty "user.dir")
     "target"
     (str artifact-id version classifier extension))))

(defn artifact-with-default-extension [{:keys [file-path] :as artifact}]
  (cond (contains? artifact :extension)
        artifact
        (.endsWith (str file-path) ".jar")
        (assoc artifact :extension "jar")
        (= (str file-path) "pom.xml")
        (assoc artifact :extension "pom")
        (.endsWith (str file-path) ".jar.asc")
        (assoc artifact :extension "jar.asc")
        (= (str file-path) "pom.xml.asc")
        (assoc artifact :extension "pom.asc")
        :else artifact))

(defn with-standard-repos [repos]
  (merge maven/standard-repos repos))

(defn check-for-unstable-deps [pred dependencies]
  (doseq [[lib coords] dependencies]
    (when (pred coords)
      (throw (ex-info (str "Release versions may not depend upon unstable version."
                           "\nFreeze snapshots/local dependencies to dated versions or set the "
                           "\"allow-unstable-deps?\" option.")
                      {:lib lib
                       :coords coords})))))

(defn snapshot-dep? [{:keys [:mvn/version]}]
  (and version (re-find #"SNAPSHOT" version)))

(defn local-dep? [{:keys [:local/root]}]
  root)

(defn jar-entry->jar-file-path [jar-entry]
  (when jar-entry
    (let [jar-entry (str jar-entry)
          jar-file-path (if (.startsWith jar-entry "jar:")
                          (.substring jar-entry (count "jar:"))
                          jar-entry)
          jar-file-path (if (.startsWith jar-file-path "file:")
                          (.substring jar-file-path (count "file:"))
                          jar-file-path)
          entry-split-index (.lastIndexOf jar-file-path "!")
          entry-split-index (if (= -1 entry-split-index)
                              (count jar-file-path)
                              entry-split-index)
          jar-file-path (.substring jar-file-path 0 entry-split-index)]
      jar-file-path)))

(defn put-zip-entry!
  [^ZipOutputStream zip-out ^Path root-path ^Path path]
  (when (not (.equals root-path path))
    (let [f (.toFile path)
          relative-path (str (relativize-path root-path path))
          relative-path (.replace relative-path (System/getProperty "file.separator") "/")
          relative-path (if (.isDirectory f)
                          (str relative-path "/")
                          (str relative-path))
          ^ZipEntry entry (if (instance? JarOutputStream zip-out)
                            (JarEntry. relative-path)
                            (ZipEntry. relative-path))]
      (.setTime entry (.lastModified f))
      (.putNextEntry zip-out entry)
      (when-not (.isDirectory f)
        (io/copy f zip-out))
      (.closeEntry zip-out))))
