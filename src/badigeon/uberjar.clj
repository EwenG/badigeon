(ns badigeon.uberjar
  (:require [clojure.tools.deps.alpha :as deps]
            [badigeon.bundle :as bundle]
            [badigeon.utils :as utils]
            [clojure.java.io :as io])
  (:import [java.nio.file Path Files
            FileVisitor FileVisitOption FileVisitResult
            FileSystemLoopException NoSuchFileException]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar JarFile JarEntry]
           [java.util EnumSet]
           [java.io FileInputStream FileOutputStream]
           [java.util.regex Pattern]))

(defn- find-resource-conflicts-jar [resource-paths resource-conflict-paths path]
  (let [jar-file (JarFile. (str path))
        entries (enumeration-seq (.entries jar-file))]
    (doseq [^JarEntry entry entries
            :when (not (.isDirectory entry))]
      (let [entry-path-str (.getName entry)]
        (when-let [previous-jar-file (get @resource-paths entry-path-str)]
          (let [all-root-paths (-> (get @resource-conflict-paths entry-path-str)
                                   (conj previous-jar-file jar-file)
                                   set)]
            (when (> (count all-root-paths) 1)
              (vswap! resource-conflict-paths assoc
                      entry-path-str all-root-paths))))
        (vswap! resource-paths assoc entry-path-str jar-file)))))

(defn- find-resource-conflicts-paths [resource-paths resource-conflict-paths paths]
  (doseq [path paths]
    (let [f (io/file path)]
      (when (.exists f)
        (cond (and (not (.isDirectory f)) (.endsWith (str path) ".jar"))
              (find-resource-conflicts-jar resource-paths resource-conflict-paths path)
              (.isDirectory f)
              (#'bundle/find-resource-conflicts-directory resource-paths resource-conflict-paths path))))))

(defn- find-resource-conflicts*
  ([]
   (find-resource-conflicts* nil))
  ([{:keys [deps-map aliases]}]
   (let [deps-map (or deps-map (deps/slurp-deps (io/file "deps.edn")))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         args-map (deps/combine-aliases deps-map aliases)
         resolved-deps (deps/resolve-deps deps-map args-map)]
     (let [resource-paths (volatile! {})
           resource-conflict-paths (volatile! {})]
       (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
         (find-resource-conflicts-paths resource-paths resource-conflict-paths paths))
       (let [extra-paths (reduce (partial #'bundle/extra-paths-reducer (:aliases deps-map))
                                 [] aliases)
             all-paths (-> extra-paths
                           (concat (:paths deps-map))
                           distinct)]
         (find-resource-conflicts-paths resource-paths resource-conflict-paths all-paths))
       @resource-conflict-paths))))

(defn- resource-root-path->string [v]
  (if (instance? JarFile v)
    (.getName ^JarFile v)
    (str v)))

(defn- find-resource-conflicts-reducer [res-conflicts k v]
  (assoc res-conflicts k (into #{} (map resource-root-path->string) v)))

(defn find-resource-conflicts
  "Return the paths of all the resource conflicts (multiple resources with the same path) found on the classpath.
  - deps-map: A map with the same format than a deps.edn map. The dependencies resolved from this map are searched for conflicts. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving the project resources and its dependencies."
  ([]
   (find-resource-conflicts nil))
  ([{:keys [deps-map aliases] :as params}]
   (let [res-conflicts (find-resource-conflicts* params)]
     (reduce-kv find-resource-conflicts-reducer {} res-conflicts))))

(defn- copy-jar [resource-conflict-paths path ^Path to]
  (let [jar-file (JarFile. (str path))
        entries (enumeration-seq (.entries jar-file))]
    (doseq [^JarEntry entry entries
            :when (not (.isDirectory entry))]
      (let [entry-path (.getName entry)]
        (when-not (contains? resource-conflict-paths entry-path)
          (let [f-path (.resolve to entry-path)
                file (.toFile f-path)]
            (Files/createDirectories (.getParent f-path) (make-array FileAttribute 0))
            (io/copy (.getInputStream jar-file entry) file)
            (.setLastModified file (.getTime entry))))))))

(defn- make-directory-file-visitor [resource-conflict-paths ^Path root-path ^Path to]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      (bundle/post-visit-directory root-path to dir exception))
    (preVisitDirectory [_ dir attrs]
      (bundle/pre-visit-directory root-path to dir attrs))
    (visitFile [_ path attrs]
      (let [resource-path (.relativize root-path path)]
        (when-not (contains? resource-conflict-paths (str resource-path))
          (let [new-file (.resolve to resource-path)]
            (#'bundle/copy-file path new-file)))
        FileVisitResult/CONTINUE))
    (visitFileFailed [_ file exception]
      (bundle/visit-file-failed file exception))))

(defn- copy-directory [resource-conflict-paths from to-directory]
  (let [to-directory (if (string? to-directory)
                       (utils/make-path to-directory)
                       to-directory)
        from (if (string? from)
               (utils/make-path from)
               from)]
    (Files/walkFileTree from
                        (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                        Integer/MAX_VALUE
                        (make-directory-file-visitor resource-conflict-paths from to-directory))))

(defn- copy-dependency [resource-conflict-paths {:keys [paths]} ^Path out-path]
  (doseq [path paths]
    (let [f (io/file path)]
      (when (.exists f)
        (cond (and (not (.isDirectory f)) (.endsWith (str path) ".jar"))
              (copy-jar resource-conflict-paths path out-path)
              (.isDirectory f)
              (copy-directory resource-conflict-paths path out-path))))))

(defn make-out-path
  "Build a path using a library name and its version number."
  [lib version]
  (bundle/make-out-path lib version))

(defn- resource-conflicts-remove-classes-reducer [resource-conflicts k v]
  (if (and (.endsWith (str k) ".class")
           (not= (str k) "module-info.class"))
    resource-conflicts
    (assoc resource-conflicts k v)))

(def default-warn-on-resource-conflicts-exclusions ["META-INF/MANIFEST.MF" #"module-info.class$"
                                                    #".*\.DS_Store$" #".*/\.DS_Store$" ])

(comment
  (re-matches #".*\.DS_Store$" "dd.DS_Store")
  )

(defn bundle
  "Creates a directory that contains all the resources from all the dependencies resolved from \"deps-map\". Resource conflicts (multiple resources with the same path) are not copied to the output directory. \".class\" files are an exception, they are always copied to the ouput directory. Use the \"badigeon.uberjar/find-resource-conflicts\" function to list resource conflicts. By default, an exception is thrown when the project depends on a local dependency or a SNAPSHOT version of a dependency.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.
  - excluded-libs: A set of lib symbols to be excluded from the produced directory. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - warn-on-resource-conflicts?. A collection of strings or regexps matched against the names of the conflicting resources. Matching resources are excluded from the warnings. Alternatively, this option can be set to false to disable warnings completly. Default to \"default-warn-on-resource-conflicts?\""
  ([out-path]
   (bundle out-path nil))
  ([out-path {:keys [deps-map
                     aliases
                     excluded-libs
                     allow-unstable-deps?
                     warn-on-resource-conflicts?]
              :or {warn-on-resource-conflicts? default-warn-on-resource-conflicts-exclusions}}]
   (let [deps-map (or deps-map (deps/slurp-deps (io/file "deps.edn")))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         args-map (deps/combine-aliases deps-map aliases)
         resolved-deps (deps/resolve-deps deps-map args-map)
         ^Path out-path (if (string? out-path)
                          (utils/make-path out-path)
                          out-path)]
     (when-not allow-unstable-deps?
       (utils/check-for-unstable-deps
        #(or (utils/snapshot-dep? %) (utils/local-dep? %))
        resolved-deps))
     (Files/createDirectories out-path (make-array FileAttribute 0))
     (let [resource-conflict-paths (find-resource-conflicts* {:deps-map deps-map
                                                              :aliases aliases})
           resource-conflict-paths-warnings (reduce-kv (partial
                                                        #'bundle/resource-conflicts-reducer
                                                        warn-on-resource-conflicts?)
                                                       {} resource-conflict-paths)
           resource-conflict-paths (reduce-kv resource-conflicts-remove-classes-reducer
                                              {} resource-conflict-paths)]
       (when (seq resource-conflict-paths-warnings)
         (binding [*out* *err*]
           (println (str "Warning: Resource conflicts found: "
                         (pr-str (keys resource-conflict-paths-warnings))))))
       (doseq [[lib coords] resolved-deps]
         (when-not (contains? excluded-libs lib)
           (copy-dependency resource-conflict-paths coords out-path)))
       (let [extra-paths (reduce (partial #'bundle/extra-paths-reducer (:aliases deps-map))
                                 [] aliases)
             all-paths (-> extra-paths
                           (concat (:paths deps-map))
                           distinct)]
         (copy-dependency resource-conflict-paths {:paths all-paths} out-path)))
     out-path)))

(defn walk-directory
  "Recursively visit all the files of a directory. For each visited file, the function f is called with two parameters: The path of the directory being recursively visited and the path of the file being visited, relative to the directory."
  [directory f]
  (bundle/walk-directory directory f))

;; Resource conflicts merging

(defn- ^java.io.InputStream resource->input-stream [path resource]
  (if (instance? JarFile resource)
    (let [jar-entry (.getJarEntry ^JarFile resource path)]
      (.getInputStream ^JarFile resource jar-entry))
    (FileInputStream. (.toFile ^Path resource))))

(defn- merger-reducer
  ([{:keys [read merge]} path resource]
   (with-open [resource-in (resource->input-stream path resource)]
     (read resource-in)))
  ([{:keys [merge] :as merger} path acc resource]
   (let [r (merger-reducer merger path resource)]
     (merge acc r))))

(defn- merge-with-merger [{:keys [read merge write] :as merger}
                          ^Path out-path ^String path resources]
  {:pre [(and read merge write)]}
  (let [out-path (.resolve out-path path)
        init-val (merger-reducer merger path (first resources))
        resources (rest resources)
        merged-resource (reduce (partial merger-reducer merger path) init-val resources)
        out-file (.toFile out-path)]
    (.mkdirs (.getParentFile out-file))
    (with-open [file-out (FileOutputStream. out-file)]
      (write file-out merged-resource))))

(def default-resource-mergers
  {"data_readers.clj" {:read (comp read-string slurp)
                       :merge merge
                       :write #(spit %1 (pr-str %2))}
   #"META-INF/services/.*" {:read slurp
                            :merge #(str %1 "\n" %2)
                            :write spit}})

;; We don't set the last modified date of the out file while merging since
;; this is not a copy of a single input file
(defn merge-resource-conflicts
  "Merge the resource conflicts (multiple resources with the same path) found on the classpath and handled by the provided \"resource-mergers\".
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies resolved from this map are searched for conflicts. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.
  - resource-mergers: A map which keys are strings or regexps and values are maps called \"mergers\". \"Mergers\" are used to merge the resources which path matches one of the keys of \"resource-mergers\". \"Mergers\" must be maps containing three keys: :read, :merge, and :write. Default to \"badigeon.uberjar/default-resource-mergers\"."
  ([out-path]
   (merge-resource-conflicts out-path nil))
  ([out-path {:keys [deps-map aliases resource-mergers]
              :or {resource-mergers default-resource-mergers}}]
   (let [^Path out-path (if (string? out-path)
                          (utils/make-path out-path)
                          out-path)
         resource-conflicts-paths (find-resource-conflicts* {:deps-map deps-map
                                                             :aliases aliases})]
     (doseq [[path resources] resource-conflicts-paths]
       (loop [resource-mergers (seq resource-mergers)]
         (when-let [[merger-path-or-regex merger] (first resource-mergers)]
           (if (or
                (and (string? merger-path-or-regex) (= merger-path-or-regex path))
                (and (instance? Pattern merger-path-or-regex) (re-matches merger-path-or-regex path)))
             (if (map? merger)
               (merge-with-merger merger out-path path resources)
               (merger out-path path resources))
             (recur (rest resource-mergers)))))))))

(comment
  (merge-resource-conflicts (make-out-path 'badigeon utils/version))
  
  (find-resource-conflicts
   {:deps-map (deps/slurp-deps (io/file "deps.edn")) :aliases [#_:doc]})
  
  (let [out-path (make-out-path 'badigeon utils/version)]
    (badigeon.clean/clean out-path)
    (bundle out-path
            {:deps-map (deps/slurp-deps (io/file "deps.edn"))
             :excluded-libs #{'org.clojure/clojure}
             :allow-unstable-deps? true
             :warn-on-resource-conflicts? true}))

  (walk-directory (make-out-path 'badigeon/badigeon utils/version)
                  (fn [d p] (prn p)))

  (let [out-path (make-out-path 'badigeon utils/version)]
    (badigeon.clean/clean "target")
    (bundle out-path {:allow-unstable-deps? true
                      #_#_:warn-on-resource-conflicts? [#".*"]}))
  )
