(ns badigeon.utils
  (:require [clojure.tools.deps.alpha.util.maven :as maven])
  (:import [java.nio.file Paths Path]))

(def ^:const version "0.0.7")

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
    (Paths/get (System/getProperty "user.dir")
               (into-array String ["target" (str artifact-id version classifier extension)]))))


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
