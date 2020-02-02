(ns badigeon.uberjar
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as deps-reader]
            [badigeon.bundle :as bundle]
            [badigeon.utils :as utils]
            [clojure.java.io :as io])
  (:import [java.nio.file Path Files
            FileVisitor FileVisitOption FileVisitResult
            FileSystemLoopException NoSuchFileException]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar Manifest JarFile JarEntry]
           [java.util EnumSet]
           [java.io FileInputStream FileOutputStream]
           [java.util.regex Pattern]))

(def ^{:dynamic true :private true} *resource-paths* nil)
(def ^{:dynamic true :private true} *resource-conflict-paths* nil)

(defn- find-resource-conflicts-jar [path]
  (let [jar-file (JarFile. (str path))
        entries (enumeration-seq (.entries jar-file))]
    (doseq [^JarEntry entry entries
            :when (not (.isDirectory entry))]
      (let [entry-path-str (.getName entry)]
        (when (contains? *resource-paths* entry-path-str)
          (if (contains? *resource-conflict-paths* entry-path-str)
            (set! *resource-conflict-paths* (update *resource-conflict-paths* entry-path-str conj jar-file))
            (set! *resource-conflict-paths* (assoc *resource-conflict-paths* entry-path-str #{(get *resource-paths* entry-path-str) jar-file}))))
        (set! *resource-paths* (assoc *resource-paths* entry-path-str jar-file))))))

(defn- make-find-resource-conflicts-directory-file-visitor [^Path root-path]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (let [resource-path (str (.relativize root-path path))]
        (when (contains? *resource-paths* resource-path)
          (if (contains? *resource-conflict-paths* resource-path)
            (set! *resource-conflict-paths* (update *resource-conflict-paths* resource-path conj path))
            (set! *resource-conflict-paths* (assoc *resource-conflict-paths* resource-path #{(get *resource-paths* resource-path) path}))))
        (set! *resource-paths* (assoc *resource-paths* resource-path path))
        FileVisitResult/CONTINUE))
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(defn- find-resource-conflicts-directory [path]
  (let [path (if (string? path)
               (utils/make-path path)
               path)]
    (Files/walkFileTree path
                        (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                        Integer/MAX_VALUE
                        (make-find-resource-conflicts-directory-file-visitor path))))

(defn- find-resource-conflicts-paths [paths]
  (doseq [path paths]
    (let [f (io/file path)]
      (when (.exists f)
        (cond (and (not (.isDirectory f)) (.endsWith (str path) ".jar"))
              (find-resource-conflicts-jar path)
              (.isDirectory f)
              (find-resource-conflicts-directory path))))))

(defn- find-resource-conflicts*
  ([]
   (find-resource-conflicts* nil))
  ([{:keys [deps-map aliases]}]
   (let [deps-map (or deps-map (deps-reader/slurp-deps "deps.edn"))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         args-map (deps/combine-aliases deps-map aliases)
         resolved-deps (deps/resolve-deps deps-map args-map)]
     (binding [*resource-paths* {}
               *resource-conflict-paths* {}]
       (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
         (find-resource-conflicts-paths paths))
       (let [extra-paths (reduce (partial #'bundle/extra-paths-reducer (:aliases deps-map))
                                 [] aliases)
             all-paths (-> extra-paths
                           (concat (:paths deps-map))
                           distinct)]
         (find-resource-conflicts-paths all-paths))
       *resource-conflict-paths*))))

(defn find-resource-conflicts
  "Return the paths of all the resource conflicts (multiple resources with the same path) found on the classpath.
  - deps-map: A map with the same format than a deps.edn map. The dependencies resolved from this map are searched for conflicts. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving the project resources and its dependencies."
  ([]
   (find-resource-conflicts nil))
  ([{:keys [deps-map aliases] :as params}]
   (->> (find-resource-conflicts* params)
        keys
        (into #{}))))

(defn- copy-file [from to]
  (let [^Path to (if (string? to)
                   (utils/make-path to)
                   to)
        ^Path from (if (string? from)
                     (utils/make-path from)
                     from)]
    (Files/copy from to utils/copy-options)))

(defn- copy-jar [path ^Path to]
  (let [jar-file (JarFile. (str path))
        entries (enumeration-seq (.entries jar-file))]
    (doseq [^JarEntry entry entries
            :when (not (.isDirectory entry))]
      (let [entry-path (.getName entry)]
        (when-not (contains? *resource-conflict-paths* entry-path)
          (let [f-path (.resolve to entry-path)
                file (.toFile f-path)]
            (Files/createDirectories (.getParent f-path) (make-array FileAttribute 0))
            (io/copy (.getInputStream jar-file entry) file)
            (.setLastModified file (.getTime entry))))))))

(defn- make-directory-file-visitor [^Path root-path ^Path to]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      (bundle/post-visit-directory root-path to dir exception))
    (preVisitDirectory [_ dir attrs]
      (bundle/pre-visit-directory root-path to dir attrs))
    (visitFile [_ path attrs]
      (let [resource-path (.relativize root-path path)]
        (when-not (contains? *resource-conflict-paths* (str resource-path))
          (let [new-file (.resolve to resource-path)]
            (copy-file path new-file))))
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (bundle/visit-file-failed file exception))))

(defn- copy-directory [from to-directory]
  (let [to-directory (if (string? to-directory)
                       (utils/make-path to-directory)
                       to-directory)
        from (if (string? from)
               (utils/make-path from)
               from)]
    (Files/walkFileTree from
                        (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                        Integer/MAX_VALUE
                        (make-directory-file-visitor from to-directory))))

(defn- copy-dependency [{:keys [paths]} ^Path out-path]
  (doseq [path paths]
    (let [f (io/file path)]
      (when (.exists f)
        (cond (and (not (.isDirectory f)) (.endsWith (str path) ".jar"))
              (copy-jar path out-path)
              (.isDirectory f)
              (copy-directory path out-path))))))

(defn make-out-path
  "Build a path using a library name and its version number."
  [lib version]
  (bundle/make-out-path lib version))

(defn bundle
  "Creates a directory that contains all the resources from all the dependencies resolved from \"deps-map\". Resource conflicts (multiple resources with the same path) are not copied to the output directory. Use the \"badigeon.uberjar/find-resource-conflicts\" function to list resource conflicts. By default, an exception is thrown when the project dependends on a local dependency or a SNAPSHOT version of a dependency.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.
  - excluded-libs: A set of lib symbols to be excluded from the produced directory. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - warn-on-resource-conflicts?. A boolean. When set to true and resource conflicts are found, then a warning is printed to *err*."
  ([out-path]
   (bundle out-path nil))
  ([out-path {:keys [deps-map
                     aliases
                     excluded-libs
                     allow-unstable-deps?
                     warn-on-resource-conflicts?]
              :or {warn-on-resource-conflicts? true}}]
   (let [deps-map (or deps-map (deps-reader/slurp-deps "deps.edn"))
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
                                                              :aliases aliases})]
       (when (and warn-on-resource-conflicts? (seq resource-conflict-paths))
         (binding [*out* *err*]
           (println (str "Warning: Resource conflicts found: "
                         (pr-str (keys resource-conflict-paths))))))
       (binding [*resource-conflict-paths* resource-conflict-paths]
         (doseq [[lib coords] resolved-deps]
           (when-not (contains? excluded-libs lib)
             (copy-dependency coords out-path)))
         (let [extra-paths (reduce (partial #'bundle/extra-paths-reducer (:aliases deps-map))
                                   [] aliases)
               all-paths (-> extra-paths
                             (concat (:paths deps-map))
                             distinct)]
           (copy-dependency {:paths all-paths} out-path))))
     out-path)))

(defn walk-directory
  "Recursively visit all the files of a directory. For each visited file, the function f is called with two parameters: The path of the directory being recursively visited and the path of the file being visited, relative to the directory."
  [directory f]
  (bundle/walk-directory directory f))

;; Resource conflicts merging

(defn- resource->input-stream [path resource]
  (if (instance? JarFile resource)
    (let [jar-entry (.getJarEntry resource path)]
      (.getInputStream resource jar-entry))
    (FileInputStream. (.toFile resource))))

(defn- merger-reducer
  ([{:keys [read merge]} path resource]
   (with-open [resource-in (resource->input-stream path resource)]
     (read resource-in)))
  ([{:keys [merge] :as merger} path acc resource]
   (let [r (merger-reducer merger path resource)]
     (merge acc r))))

(defn- merge-with-merger [{:keys [read merge write] :as merger} ^Path out-path path resources]
  {:pre [(and read merge write)]}
  (let [out-path (.resolve out-path path)
        init-val (merger-reducer merger path (first resources))
        resources (rest resources)
        merged-resource (reduce (partial merger-reducer merger path) init-val resources)]
    (with-open [file-out (FileOutputStream. (.toFile out-path))]
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
  "Merge all the resource conflicts (multiple resources with the same path) found on the classpath.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies resolved from this map are searched for conflicts. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.
  - resource-mergers: A map which keys are strings or regexps and values are maps called \"mergers\". \"Mergers\" are used to merge the resources which path matches one of the keys of \"resource-mergers\". \"Mergers\" must be maps containing three keys: :read, :merge, and :write. Default to \"badigeon.uberjar/default-resource-mergers\""
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
  
  (find-resource-conflicts {:deps-map (deps-reader/slurp-deps "deps.edn") :aliases [:doc]})
  
  (let [out-path (make-out-path 'badigeon utils/version)]
    (badigeon.clean/clean out-path)
    (bundle out-path
            {:deps-map (deps-reader/slurp-deps "deps.edn")
             :excluded-libs #{'org.clojure/clojure}
             :allow-unstable-deps? true
             :warn-on-resource-conflicts? true}))

  (walk-directory (make-out-path 'badigeon/badigeon utils/version)
                  (fn [d p] (prn p)))
  )
