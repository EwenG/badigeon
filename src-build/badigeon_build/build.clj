(ns badigeon-build-build
  (:require [badigeon.jar :as jar]
            [badigeon.install :as install]))

(defn -main []
  (jar/jar 'badigeon/badigeon {:mvn/version badigeon.utils/version}
           {:paths ["src"]}))
