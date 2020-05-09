(ns badigeon-build-build
  (:require [badigeon.jar :as jar]
            [badigeon.install :as install]
            [badigeon.utils :as utils]
            [badigeon.sign :as sign]
            [badigeon.deploy :as deploy]
            [badigeon.prompt :as prompt]))

(defn -main []
  (let [jar-path (jar/jar 'badigeon/badigeon {:mvn/version badigeon.utils/version}
                          {:paths ["src"]})
        artifacts [{:file-path jar-path}
                   {:file-path "pom.xml"}]
        artifacts (sign/sign artifacts)
        password (prompt/prompt-password "Password: ")]
    (deploy/deploy
     'badigeon/badigeon badigeon.utils/version
     artifacts
     {:id "clojars"
      :url "https://repo.clojars.org/"}
     {:credentials {:username "ewen" :password password}})))

