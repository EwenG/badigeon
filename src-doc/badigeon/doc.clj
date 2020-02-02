(ns badigeon.doc
  (:require [badigeon.clean :as clean]
            [badigeon.classpath :as classpath]
            [badigeon.javac :as javac]
            [badigeon.compile :as compile]
            [badigeon.jar :as jar]
            [badigeon.pom :as pom]
            [badigeon.install :as install]
            [badigeon.prompt :as prompt]
            [badigeon.sign :as sign]
            [badigeon.deploy :as deploy]
            [badigeon.bundle :as bundle]
            [badigeon.uberjar :as uberjar]
            [badigeon.jlink :as jlink]
            [badigeon.zip :as zip]
            [badigeon.war :as war]
            [badigeon.exec :as exec]
            [badigeon.utils :as utils]))

(def header "# API\n\n")
(def template
  (str
   "## `%s`\n\n"
   "Arglists: `%s`\n\n"
   "%s"))

(defn escape-chars [^String s]
  (let [sb (StringBuilder.)]
    (dotimes [n (count s)]
      (let [c (.charAt s n)]
        (case c
          \* (do (.append sb \\ ) (.append sb \*))
          (.append sb c))))
    (str sb)))

(def vars [#'clean/clean #'classpath/make-classpath #'javac/javac
           #'compile/compile #'compile/extract-classes-from-dependencies
           #'jar/jar #'jar/make-manifest
           #'pom/sync-pom #'install/install #'prompt/prompt #'prompt/prompt-password #'sign/sign
           #'deploy/deploy
           #'bundle/make-out-path #'bundle/bundle
           #'bundle/extract-native-dependencies #'bundle/extract-native-dependencies-from-file
           #'bundle/walk-directory
           #'bundle/bin-script
           #'uberjar/make-out-path #'uberjar/bundle
           #'uberjar/find-resource-conflicts #'uberjar/merge-resource-conflicts
           #'uberjar/walk-directory
           #'jlink/jlink #'zip/zip
           #'war/make-out-path #'war/war-exploded #'war/war #'exec/exec
           #'utils/make-path])

(defn var-sym [^clojure.lang.Var v]
  (symbol (str (.-ns v)) (str (.-sym v))))

(defn arglists-remove-default-vals [arg]
  (cond (map? arg)
        (into (or (empty arg) []) (map arglists-remove-default-vals) (dissoc arg :or))
        (coll? arg)
        (into (or (empty arg) []) (map arglists-remove-default-vals) arg)
        :else arg))

(defn doc-entry [template v-sym v-arglists doc]
  (str (format template
               (pr-str v-sym)
               (binding [*print-length* nil
                         *print-level* nil]
                 (pr-str v-arglists))
               doc) "\n\n"))

(defn v->doc [v]
  (let [{:keys [arglists doc]} (meta v)
        arglists (map arglists-remove-default-vals arglists)]
    (doc-entry template (var-sym v) arglists doc)))

(defn gen-doc [vars]
  (escape-chars (apply str header (map v->doc vars))))

(defn -main []
  (spit "API.md" (gen-doc vars)))

(comment
  (-main)
  )
