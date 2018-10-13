(ns badigeon.classpath
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as deps-reader]
            [badigeon.utils :as utils]))

(defn make-classpath
  "Builds a classpath by using the provided deps spec or, by default, the deps.edn file of the current project. Returns the built classpath as a string.
  - deps-map: A map with the same format than a deps.edn map. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - aliases: Alias keywords used while building the classpath."
  ([]
   (make-classpath nil))
  ([{:keys [deps-map aliases]}]
   (let [deps-map (or deps-map (deps-reader/slurp-deps "deps.edn"))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         args-map (deps/combine-aliases deps-map aliases)]
     (-> (deps/resolve-deps deps-map args-map)
         (deps/make-classpath (:paths deps-map) args-map)))))

(comment
  (let [deps-map {:paths ["src"]
                  :deps {:org.clojure/clojure #:mvn{:version "1.9.0"}
                         #_#_:org.clojure/tools.deps.alpha #:mvn{:version "0.5.442"}}
                  :aliases {:doc {:extra-paths ["src-doc"]}}}]
    (make-classpath {:deps-map deps-map #_#_:alias-keywords [:doc]}))
  )
