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
           [java.util EnumSet]))

(def ^{:dynamic true :private true} *resource-paths* nil)
(def ^{:dynamic true :private true} *resource-conflict-paths* nil)

(defn- find-resource-conflicts-jar [path]
  (let [jar-file (JarFile. (str path))
        entries (enumeration-seq (.entries jar-file))]
    (doseq [^JarEntry entry entries
            :when (not (.isDirectory entry))]
      (let [entry-path-str (.getName entry)]
        (when (contains? *resource-conflict-paths* entry-path-str)
          (set! *resource-conflict-paths* (conj *resource-conflict-paths* entry-path-str)))
        (set! *resource-paths* (conj *resource-paths* entry-path-str))))))

(defn- make-find-resource-conflicts-directory-file-visitor [^Path root-path]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (let [resource-path (str (.relativize root-path path))]
        (when (contains? *resource-paths* resource-path)
          (set! *resource-conflict-paths* (conj *resource-conflict-paths* resource-path)))
        (set! *resource-paths* (conj *resource-paths* resource-path))
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

(defn find-resource-conflicts
  "Return the paths of all the resource conflicts (multiple resources with the same path) found on the classpath.
  - deps-map: A map with the same format than a deps.edn map. The dependencies resolved from this map are searched for conflicts. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository."
  ([]
   (find-resource-conflicts nil))
  ([{:keys [deps-map]}]
   (let [deps-map (or deps-map (deps-reader/slurp-deps "deps.edn"))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         resolved-deps (deps/resolve-deps deps-map nil)]
     (binding [*resource-paths* #{}
               *resource-conflict-paths* #{}]
       (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
         (find-resource-conflicts-paths paths))
       (find-resource-conflicts-paths (:paths deps-map))
       *resource-conflict-paths*))))

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
          (let [f-path (.resolve to entry-path)]
            (Files/createDirectories (.getParent f-path) (make-array FileAttribute 0))
            (io/copy (.getInputStream jar-file entry) (.toFile f-path))))))))

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

(defn bundle
  "Creates a directory that contains all the resources from all the dependencies resolved from \"deps-map\". Resource conflicts (multiple resources with the same path) are not copied to the output directory. Use the \"badigeon.uberjar/find-resource-conflicts\" function to list resource conflicts. By default, an exception is thrown when the project dependends on a local dependency or a SNAPSHOT version of a dependency.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - excluded-libs: A set of lib symbols to be excluded from the produced directory. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - warn-on-resource-conflicts?. A boolean. When set to true and resource conflicts are found, then a warning is printed to *err*."
  ([out-path]
   (bundle out-path nil))
  ([out-path {:keys [deps-map
                     excluded-libs
                     allow-unstable-deps?
                     warn-on-resource-conflicts?]
              :or {warn-on-resource-conflicts? true}}]
   (let [deps-map (or deps-map (deps-reader/slurp-deps "deps.edn"))
         deps-map (update deps-map :mvn/repos utils/with-standard-repos)
         resolved-deps (deps/resolve-deps deps-map nil)
         ^Path out-path (if (string? out-path)
                          (utils/make-path out-path)
                          out-path)]
     (when-not allow-unstable-deps?
       (utils/check-for-unstable-deps
        #(or (utils/snapshot-dep? %) (utils/local-dep? %))
        resolved-deps))
     (Files/createDirectories out-path (make-array FileAttribute 0))
     (let [resource-conflict-paths (find-resource-conflicts deps-map)]
       (when (and warn-on-resource-conflicts? (seq resource-conflict-paths))
         (binding [*out* *err*]
           (prn (str "Warning: Resource conflicts found: "
                     (pr-str resource-conflict-paths)))))
       (binding [*resource-conflict-paths* resource-conflict-paths]
         (doseq [[lib coords] resolved-deps]
           (when-not (contains? excluded-libs lib)
             (copy-dependency coords out-path)))
         (copy-dependency {:paths (:paths deps-map)} out-path)))
     out-path)))

(comment
  (find-resource-conflicts {:deps-map (deps-reader/slurp-deps "deps.edn")})
  
  (let [out-path (utils/make-out-path 'badigeon {:mvn/version utils/version :classifier "rrr"})]
    (badigeon.clean/clean out-path)
    (bundle out-path
            {:deps-map (deps-reader/slurp-deps "deps.edn")
             :excluded-libs #{'org.clojure/clojure}
             :allow-unstable-deps true
             :warn-on-resource-conflicts? false}))
  )
