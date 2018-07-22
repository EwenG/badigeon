(ns badigeon.utils
  (:import [java.nio.file Paths Path]))

(def ^:const version "0.0.1-SNAPSHOT")

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
