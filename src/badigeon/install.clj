(ns badigeon.install
  (:require [clojure.tools.deps.alpha.util.maven :as maven]
            [clojure.java.io :as io])
  (:import [org.eclipse.aether.installation InstallRequest]))

(defn install
  ([lib maven-coords file-path pom-file-path]
   (install lib maven-coords file-path pom-file-path nil))
  ([lib maven-coords file-path pom-file-path local-repo]
   (let [local-repo (or local-repo maven/default-local-repo)
         system (maven/make-system)
         session (maven/make-session system local-repo)
         artifact (maven/coord->artifact lib maven-coords)
         artifact (.setFile artifact (io/file file-path))
         pom-artifact (maven/coord->artifact lib (-> maven-coords
                                                     (assoc :extension "pom")
                                                     (dissoc :classifier)))
         pom-artifact (.setFile pom-artifact (io/file pom-file-path))]
     (.install system session (-> (InstallRequest.)
                                  (.addArtifact artifact)
                                  (.addArtifact pom-artifact))))))

(comment
  (install 'badigeon/badigeon
           {:mvn/version badigeon.utils/version
            :classifier "cl"}
           "target/badigeong-0.0.1-SNAPSHOT-cl.jar"
           "pom.xml")
  )


