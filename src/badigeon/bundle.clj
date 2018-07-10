(ns badigeon.bundle
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as deps-reader]
            [clojure.tools.deps.alpha.extensions :as extensions]
            [clojure.java.io :as io]
            [badigeon.utils :as utils]
            [badigeon.clean :as clean]
            [badigeon.pom :as pom]
            [clojure.tools.deps.alpha.util.maven :as maven])
  (:import [java.nio.file Path Paths Files
            FileVisitor FileVisitOption FileVisitResult
            FileSystemLoopException NoSuchFileException FileAlreadyExistsException
            StandardCopyOption LinkOption]
           [java.nio.file.attribute FileAttribute]
           [java.io FileOutputStream]
           [java.util.zip ZipFile ZipOutputStream]
           [java.util.jar JarFile]
           [java.util EnumSet]))

(def ^:dynamic *out-path* nil)
(def ^:dynamic *copied-paths* nil)

(defn make-directory-file-visitor [root-path to]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      (when-not exception
        (let [new-dir (.resolve to (.relativize root-path dir))]
          (Files/setLastModifiedTime new-dir
                                     (Files/getLastModifiedTime
                                      dir (make-array LinkOption 0)))))
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      (let [new-dir (.resolve to (.relativize root-path dir))]
        (try
          (Files/copy dir new-dir (into-array
                                   StandardCopyOption [StandardCopyOption/COPY_ATTRIBUTES]))
          (catch FileAlreadyExistsException e
            ;; ignore
            nil))
        FileVisitResult/CONTINUE))
    (visitFile [_ path attrs]
      (let [new-file (.resolve to (.relativize root-path path))
            relative-path (when (some? *out-path*) (.relativize *out-path* new-file))]
        (when (and (some? *copied-paths*) (get *copied-paths* relative-path))
          (throw (ex-info "Duplicate path" {:from (str path)
                                            :to (str to)
                                            :relative-path (str relative-path)})))
        (when (some? *copied-paths*)
          (set! *copied-paths* (conj *copied-paths* relative-path)))
        (Files/copy path new-file (into-array
                                   StandardCopyOption [StandardCopyOption/COPY_ATTRIBUTES
                                                       StandardCopyOption/REPLACE_EXISTING]))
        FileVisitResult/CONTINUE))
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(def ^:const native-extensions #{".so" ".dylib" ".dll" ".a" ".lib"})

(defn do-extract-native-dependencies
  ([native-prefix coords out-path]
   (do-extract-native-dependencies native-prefix coords out-path
                                   (Paths/get "lib" (make-array 0))))
  ([native-prefix {:keys [paths]} out-path native-path]
   (let [native-prefix (if (instance? Path native-prefix)
                         native-prefix
                         (Paths/get (str native-prefix) (make-array String 0)))
         native-path (.resolve out-path native-path)]
     (doseq [path paths]
       (let [f (io/file path)]
         (when (and (.exists f) (not (.isDirectory f))
                    (.endsWith path ".jar"))
           (let [jar-file (JarFile. path)
                 entries (enumeration-seq (.entries jar-file))]
             (doseq [entry entries]
               (let [entry-path (.getName entry)]
                 (when (and (some #(.endsWith entry-path %) native-extensions)
                            (.startsWith entry-path (str native-prefix)))
                   (let [entry-path (.relativize native-prefix (Paths/get (.getName entry) (make-array String 0)))
                         f-path (.resolve native-path entry-path)]
                     (Files/createDirectories (.getParent f-path) (make-array FileAttribute 0))
                     (io/copy (.getInputStream jar-file entry) (.toFile f-path)))))))))))))

(defn copy-directory [from to-directory]
  (let [to-directory (if (string? to-directory)
                       (Paths/get to-directory (make-array String 0))
                       to-directory)
        from (if (string? from)
               (Paths/get from (make-array String 0))
               from)]
    (Files/walkFileTree from
                        (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                        Integer/MAX_VALUE
                        (make-directory-file-visitor from to-directory))))

(defn copy-file [from to]
  (let [to (if (string? to)
             (Paths/get to (make-array String 0))
             to)
        from (if (string? from)
               (Paths/get from (make-array String 0))
               from)
        relative-path (when (some? *out-path*) (.relativize *out-path* to))]
    (if (and (some? *copied-paths*)
             (get *copied-paths* relative-path))
      (throw (ex-info "Duplicate path" {:from (str from)
                                        :to (str to)
                                        :relative-path (str relative-path)}))
      (do
        (when (some? *out-path*)
          (set! *copied-paths* (conj *copied-paths* relative-path)))
        (Files/copy
         from to (into-array StandardCopyOption [StandardCopyOption/COPY_ATTRIBUTES
                                                 StandardCopyOption/REPLACE_EXISTING]))))))

(defn copy-dependency
  ([coords out-path]
   (copy-dependency coords out-path (Paths/get "lib" (make-array String 0))))
  ([{:keys [paths]} out-path libs-path]
   (doseq [path paths]
     (let [f (io/file path)]
       (when (.exists f)
         (if (and (not (.isDirectory f)) (.endsWith (str path) ".jar"))
           (let [to (-> out-path (.resolve libs-path) (.resolve (.getName f)))]
             (copy-file path to))
           (copy-directory path out-path)))))))

(defn with-standard-repos [repos]
  (merge maven/standard-repos repos))

(defn snapshot-dep? [{:keys [:mvn/version]}]
  (and version (re-find #"SNAPSHOT" version)))

(defn local-dep? [{:keys [:local/root]}]
  root)

(defn check-for-unstable-deps [pred dependencies]
  (doseq [[lib  coords] dependencies]
    (when (pred coords)
      (throw (ex-info (str "Release versions may not depend upon unstable version."
                           "\nFreeze snapshots/local dependencies to dated versions or set the "
                           "\"allow-unstable-deps?\" option.")
                      {:lib lib
                       :coords coords})))))

;; excluded-libs excludes a lib but not its dependencies
(defn bundle
  ([out-path deps-map]
   (bundle out-path deps-map nil))
  ([out-path deps-map {:keys [excluded-libs
                              allow-unstable-deps?
                              libs-path]}]
   (let [deps-map (update deps-map :mvn/repos with-standard-repos)
         resolved-deps (deps/resolve-deps deps-map nil)
         out-path (if (string? out-path)
                    (Paths/get out-path (make-array String 0))
                    out-path)
         libs-path (if libs-path
                     (if (string? libs-path)
                       (Paths/get libs-path (make-array String 0))
                       libs-path)
                     (Paths/get "lib" (make-array String 0)))]
     (when-not allow-unstable-deps?
       (check-for-unstable-deps #(or (snapshot-dep? %) (local-dep? %)) resolved-deps))
     (Files/createDirectories (.resolve out-path libs-path) (make-array FileAttribute 0))
     (binding [*out-path* out-path
               *copied-paths* #{}]
       (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
         (when-not (contains? excluded-libs lib)
           (copy-dependency coords out-path libs-path))))
     out-path)))

(defn extract-native-dependencies
  ([out-path deps-map]
   (extract-native-dependencies out-path deps-map nil))
  ([out-path deps-map  {:keys [allow-unstable-deps?
                               native-path
                               natives-prefixes]}]
   (let [deps-map (update deps-map :mvn/repos with-standard-repos)
         resolved-deps (deps/resolve-deps deps-map nil)
         out-path (if (string? out-path)
                    (Paths/get out-path (make-array String 0))
                    out-path)
         native-path (if native-path
                       (if (string? native-path)
                         (Paths/get native-path (make-array String 0))
                         native-path)
                       (Paths/get "lib" (make-array String 0)))]
     (when-not allow-unstable-deps?
       (check-for-unstable-deps #(snapshot-dep? %) resolved-deps))
     (Files/createDirectories (.resolve out-path native-path) (make-array FileAttribute 0))
     (doseq [[lib {:keys [paths] :as coords}] resolved-deps]
       (when (contains? natives-prefixes lib)
         (do-extract-native-dependencies (get natives-prefixes lib) coords out-path native-path)))
     out-path)))

(defn make-out-path [lib maven-coords]
  (let [artifact-id (name lib)]
    (utils/make-out-path artifact-id (dissoc maven-coords :extension))))

(comment
  (require '[leiningen.uberjar])
  
  (utils/make-out-path "badigeon" {:mvn/version utils/version :classifier "rrr"})

  (let [out-path (make-out-path 'badigeon/badigeon {:mvn/version utils/version})
        deps {:deps '{badigeon/badigeon {:local/root "/home/ewen/clojure/badigeon"}}}]
    (bundle out-path deps {:allow-unstable-deps? true
                           :libs-path "libs/gg"
                           :excluded-libs #{'badigeon/badigeon 'badigeon-deps/badigeon-deps2
                                            'com.googlecode.javaewah/JavaEWAH}})
    (extract-native-dependencies out-path deps {:allow-unstable-deps? true
                                                :natives-prefixes {'net.java.dev.jna/jna "com"}}))
  )

;; net/java/dev/jna/jna/4.1.0/jna-4.1.0.jar
