(ns badigeon.install
  (:require [clojure.tools.deps.alpha.util.maven :as maven]
            [clojure.java.io :as io])
  (:import [org.eclipse.aether.installation InstallRequest]))

(defn install
  "Install a jar file into the local maven repository.
  - lib is a symbol naming the library to install. The groupId of the installed library is the namespace of the symbol \"lib\" if lib is a namespaced symbol, or its name if lib is an unqualified symbol. The artifactId of the installed symbol is the name of the \"lib\" symbol.
  - maven-coords is a map representing the maven coordinates of the library, under the same format than the one used by tools.deps.
  - file-path is the path to the jar to be installed.
  - pom-file-path is the path to the pom.xml file to be installed.
  - local-repo is the path to the local maven repository where the library is to be installed. Default to ~/.m2/repository ."
  ([lib maven-coords file-path pom-file-path]
   (install lib maven-coords file-path pom-file-path nil))
  ([lib maven-coords file-path pom-file-path {:keys [local-repo]}]
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


