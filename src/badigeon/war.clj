(ns badigeon.war
  (:require [clojure.data.xml :as xml]
            [clojure.tools.deps :as deps]
            [clojure.tools.deps.util.maven :as maven]
            [badigeon.utils :as utils]
            [badigeon.bundle :as bundle]
            [badigeon.jar :as jar]
            [badigeon.compile :as compile]
            [badigeon.zip :as zip]
            [clojure.java.io :as io])
  (:import [java.nio.file Path Paths Files]
           [java.nio.file.attribute FileAttribute]))

(def web-app-attrs
  "Attributes for the web-app element, indexed by the servlet version."
  {"2.4" {:xmlns     "http://java.sun.com/xml/ns/j2ee"
          :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
          :xsi:schemaLocation (str "http://java.sun.com/xml/ns/j2ee "
                                   "http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd")
          :version "2.4"}
   "2.5" {:xmlns     "http://java.sun.com/xml/ns/javaee"
          :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
          :xsi:schemaLocation (str "http://java.sun.com/xml/ns/javaee "
                                   "http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd")
          :version "2.5"}
   "3.0" {:xmlns     "http://java.sun.com/xml/ns/javaee"
          :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
          :xsi:schemaLocation (str "http://java.sun.com/xml/ns/javaee "
                                   "http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd")
          :version "3.0"}})

(defn make-web-xml [{:keys [servlet-version listener-class
                            servlet-name servlet-class
                            url-pattern]
                     :or {servlet-version "2.5"
                          url-pattern "/*"}}]
  (xml/sexp-as-element
   `[:web-app ~(get web-app-attrs servlet-version {})
     ~@(when listener-class
         [[:listener
           [:listener-class listener-class]]])
     [:servlet
      [:servlet-name ~servlet-name]
      [:servlet-class ~servlet-class]]
     [:servlet-mapping
      [:servlet-name ~servlet-name]
      [:url-pattern ~url-pattern]]]))

(defn make-out-path
  "Build a path using a library name and its version number."
  [lib version]
  (bundle/make-out-path lib version))

(defn war-exploded
  "Creates an exploded war directory. The produced war can be run on legacy java servers such as Tomcat. This function AOT compiles the provided servlet-namespace. The servlet-namespace must contain a :gen-class directive implementing an HttpServlet.
  - out-path: The path of the output directory.
  - servlet-namespace: A symbol naming a namespace. This namespace must contain a :gen-class directive implementing an HttpServlet.
  - compiler-options: A map with the same format than clojure.core/*compiler-options*. The compiler-options are used when compiling the servlet-namespace and, when provided, the listener-namespace.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving dependencies.
  - excluded-libs: A set of lib symbols to be excluded from the produced bundle. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - warn-on-resource-conflicts?. A collection of strings or regexps matched against the names of the conflicting resources. Matching resources are excluded from the warnings. Alternatively, this option can be set to false to disable warnings completly. Default to \"default-warn-on-resource-conflicts?\"
  - manifest: A map of additionel entries to the war manifest. Values of the manifest map can be maps to represent manifest sections. By default, the war manifest contains the \"Created-by\", \"Built-By\" and \"Build-Jdk\" entries.
  - servlet-version: The version of the servlet spec that we claim to conform to. Attributes corresponding to this version will be added to the web-app element of the web.xml. If not specified, defaults to 2.5.
  - servlet-name: The name of the servlet (in web.xml). Defaults to the servlet-namespace name.
  - servlet-class: The servlet class name. Default to the munged servlet-namespace name.
  - url-pattern: The url pattern of the servlet mapping (in web.xml). Defaults to \"/*\".
  - listener-namespace: A symbol naming a namespace. This namespace must contain a :gen-class directive implementing a ServletContextListener.
  - listener-class: Class used for servlet init/destroy functions. Called listener because underneath it uses a ServletContextListener."
  ([out-path servlet-namespace]
   (war-exploded servlet-namespace nil))
  ([out-path servlet-namespace
    {:keys [compiler-options

            deps-map
            aliases
            excluded-libs
            allow-unstable-deps?
            warn-on-resource-conflicts?

            manifest

            servlet-version
            servlet-name
            servlet-class
            url-pattern

            listener-namespace
            listener-class]
     :or {warn-on-resource-conflicts? bundle/default-warn-on-resource-conflicts-exclusions
          servlet-class (namespace-munge servlet-namespace)
          servlet-name (str servlet-namespace)
          servlet-version "2.5"
          url-pattern "/*"}
     :as opts}]
   (let [deps-map (or deps-map (deps/slurp-deps (io/file "deps.edn")))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         args-map (deps/combine-aliases deps-map aliases)
         resolved-deps (deps/resolve-deps deps-map args-map)
         ^Path out-path (if (string? out-path)
                          (utils/make-path out-path)
                          out-path)
         ^Path libs-path (utils/make-path "WEB-INF/lib")
         opts (assoc opts
                     :servlet-class servlet-class
                     :servlet-name servlet-name
                     :servlet-version servlet-version
                     :url-pattern url-pattern
                     :listener-class (or listener-class
                                         (when listener-namespace
                                           (namespace-munge listener-namespace))))]
     (when-not allow-unstable-deps?
       (utils/check-for-unstable-deps #(or (utils/snapshot-dep? %) (utils/local-dep? %))
                                      resolved-deps))
     (Files/createDirectories (.resolve out-path libs-path) (make-array FileAttribute 0))
     (Files/createDirectories (.getParent (.resolve out-path "META-INF/MANIFEST.MF"))
                              (make-array FileAttribute 0))
     (let [the-manifest (jar/make-manifest nil manifest)]
       (spit (str (.resolve out-path "META-INF/MANIFEST.MF")) the-manifest))
     (let [web-xml (make-web-xml opts)]
       (spit (str (.resolve out-path "WEB-INF/web.xml")) (xml/indent-str web-xml)))
     (compile/compile servlet-namespace
                      {:compile-path (str (.resolve out-path "WEB-INF/classes"))
                       :compiler-options compiler-options})
     (when listener-namespace
       (compile/compile listener-namespace
                        {:compile-path (str (.resolve out-path "WEB-INF/classes"))
                         :compiler-options compiler-options}))
     (let [resource-conflict-paths (#'bundle/find-resource-conflicts* {:deps-map deps-map
                                                                       :aliases aliases})
           resource-conflict-paths-warnings (reduce-kv (partial
                                                        #'bundle/resource-conflicts-reducer
                                                        warn-on-resource-conflicts?)
                                                       {} resource-conflict-paths)]
       (when (seq resource-conflict-paths-warnings)
         (binding [*out* *err*]
           (println (str "Warning: Resource conflicts found: "
                         (pr-str (keys resource-conflict-paths-warnings))))))
       (let [copied-paths (volatile! #{})]
         (doseq [[lib coords] resolved-deps]
           (when-not (contains? excluded-libs lib)
             (#'bundle/copy-dep-dependency
              copied-paths resource-conflict-paths
              lib coords out-path libs-path)))))
     out-path)))

(defn war
  "Use the badigeon.war/war-exploded function to create an exploded war directory and zip the result into a .war file."
  ([out-path servlet-namespace]
   (war out-path servlet-namespace nil))
  ([out-path servlet-namespace {:keys [compiler-options

                                       deps-map
                                       aliases
                                       excluded-libs
                                       allow-unstable-deps?

                                       manifest

                                       servlet-version
                                       servlet-name
                                       servlet-class
                                       url-pattern

                                       listener-namespace
                                       listener-class]
                                :as opts}]
   (let [out-path (war-exploded out-path servlet-namespace opts)
         war-out-path (.resolveSibling
                       ^Path out-path
                       (str (.getFileName ^Path out-path) ".war"))]
     (zip/zip out-path war-out-path))))

(comment
  (let [out-path (make-out-path 'badigeon utils/version)]
    (badigeon.clean/clean out-path)
    (war out-path
         'badigeon.main
         {:allow-unstable-deps? true
          :deps-map (deps/slurp-deps (io/file "deps.edn"))}))
  )
