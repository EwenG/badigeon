(ns badigeon.jar
  (:require [clojure.tools.deps.alpha.reader :as deps-reader]
            [clojure.tools.deps.alpha.gen.pom :as deps-gen-pom]
            [badigeon.pom :as pom]
            [badigeon.utils :as utils]
            [clojure.string :as string]
            [clojure.java.io :as io])
  (:import [java.nio.file Path Paths Files]
           [java.util EnumSet]
           [java.util.jar Manifest JarEntry JarOutputStream]
           [java.nio.file FileVisitor FileVisitResult FileSystemLoopException
            FileVisitOption NoSuchFileException]
           [java.io BufferedOutputStream FileOutputStream ByteArrayInputStream]))

(defn relativize-path [parent-path path]
  (.normalize (.relativize parent-path path)))

(def ^:private default-manifest
  {"Created-By" (str "Badigeon " utils/version)
   "Built-By" (System/getProperty "user.name")
   "Build-Jdk" (System/getProperty "java.version")})

(defn- place-sections-last
  "Places sections at the end of the manifest seq, as specified by the
  Manifest spec. Retains ordering otherwise (if mf is ordered)."
  [mf]
  (sort-by val (fn [v1 v2]
                 (and (not (coll? v1)) (coll? v2)))
           (seq mf)))

(declare ^:private format-manifest-entry)

(defn- format-manifest-entry-section [k v]
  (->> (map format-manifest-entry v)
       (cons (str "\nName: " (name k) "\n"))
       (string/join)))

(defn- format-manifest-entry [[k v]]
  (if (coll? v)
    (format-manifest-entry-section k v)
    (->> (str (name k) ": " v)
         (partition-all 70)  ;; Manifest spec says lines <= 72 chars
         (map (partial apply str))
         (string/join "\n ")  ;; Manifest spec says join with "\n "
         (format "%s\n"))))

(defn make-manifest [main manifest-overrides]
  (let [manifest-overrides (into {} manifest-overrides)
        manifest (if main
                   (assoc default-manifest "Main-Class" (munge (str main)))
                   default-manifest)]
    (->> (merge manifest manifest-overrides)
         place-sections-last
         (map format-manifest-entry)
         (cons "Manifest-Version: 1.0\n")
         (string/join ""))))

(defn dotfiles-pred [root-path path]
  (.startsWith (str (.getFileName path)) "."))

(defn emacs-backups-pred [root-path path]
  (let [file-name (str (.getFileName path))]
    (or (.endsWith file-name "~") (.startsWith file-name "#"))))

(defn default-exclusion-predicate [root-path path]
  (or (dotfiles-pred root-path path)
      (emacs-backups-pred root-path path)))

(defn pom-path [group-id project-name root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^pom.xml$" (str (relativize-path root-files path))))
    (format "META-INF/maven/%s/%s/pom.xml" group-id project-name)))

(defn deps-path [group-id project-name root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^deps.edn$" (str (relativize-path root-files path))))
    (format "META-INF/badigeon/%s/%s/deps.edn" group-id project-name)))

(defn readme-path [group-id project-name root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^(?i)readme(.*)$" (str (relativize-path root-files path))))
    (format "META-INF/badigeon/%s/%s/%s"
            group-id project-name (.getFileName path))))

(defn license-path [group-id project-name root-files path]
  (when (and
         (not (.isDirectory (.toFile path)))
         (re-matches #"^(?i)license(.*)$" (str (relativize-path root-files path))))
    (format "META-INF/badigeon/%s/%s/%s"
            group-id project-name (.getFileName path))))

(defn default-inclusion-path [group-id project-name root-files path]
  (or (pom-path group-id project-name root-files path)
      (deps-path group-id project-name root-files path)
      (readme-path group-id project-name root-files path)
      (license-path group-id project-name root-files path)))

(def ^:dynamic *jar-paths* nil)

(defn- put-jar-entry!
  "Adds a jar entry to the Jar output stream."
  [jar-out file path]
  (.putNextEntry jar-out (doto (JarEntry. (str path))
                           (.setTime (.lastModified file))))
  (when-not (.isDirectory file)
    (io/copy file jar-out)))

(defn path-file-visitor [jar-out exclusion-predicate root-path path attrs]
  (let [file-name (.getFileName path)]
    (when (and (not= root-path path)
               (not (exclusion-predicate root-path path)))
      (let [f (.toFile path)
            relative-path (relativize-path root-path path)]
        (if (and (not (.isDirectory f)) (get *jar-paths* relative-path))
          (throw (IllegalArgumentException. (format "Duplicate path: %s" (str relative-path))))
          (do
            (set! *jar-paths* (conj! *jar-paths* relative-path))
            (put-jar-entry! jar-out f relative-path)))))))

(defn inclusion-path-visitor [jar-out pred root-path path attrs]
  (when-let [file-name (pred root-path path)]
    (let [f (.toFile path)]
      (if (and (not (.isDirectory f)) (get *jar-paths* file-name))
        (throw (IllegalArgumentException. (format "Duplicate path: %s" (str file-name))))
        (let [bytes (.getBytes (slurp (str path)))]
          (set! *jar-paths* (conj! *jar-paths* file-name))
          (.putNextEntry jar-out (JarEntry. file-name))
          (io/copy (ByteArrayInputStream. bytes) jar-out))))))

(defn- make-file-visitor [jar-out pred root-path visitor-fn]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (visitor-fn jar-out pred root-path path attrs)
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (cond (instance? FileSystemLoopException exception)
            FileVisitResult/SKIP_SUBTREE
            (instance? NoSuchFileException exception)
            FileVisitResult/SKIP_SUBTREE
            :else (throw exception)))))

(defn make-out-path [project-name version]
  (let [version (when version (str "-" version))]
    (Paths/get (System/getProperty "user.dir")
               (into-array String ["target" (str project-name version ".jar")]))))

(defn jar [{:keys [version] :as project-map}
           & {:keys [out-path main manifest-entries exclusion-predicate inclusion-path]
              :or {exclusion-predicate default-exclusion-predicate}}]
  (let [root-path (Paths/get (System/getProperty "user.dir") (make-array String 0))
        default-project-name (pom/default-project-name root-path)
        project-map (merge {:group-id default-project-name
                            :name default-project-name}
                           project-map)
        inclusion-path (or inclusion-path
                           (partial default-inclusion-path
                                    (:group-id project-map) (:name project-map)))
        out-path (or out-path (make-out-path (:name project-map) version))
        out-path (if (string? out-path)
                   (Paths/get out-path (make-array String 0))
                   out-path)
        out-path (if (and (instance? Path out-path) (not (.isAbsolute out-path)))
                   (.resolve root-path out-path)
                   out-path)
        {:keys [config-files]} (deps-reader/clojure-env)
        deps-map (dissoc (deps-reader/read-deps config-files) :aliases)
        manifest (-> (make-manifest main manifest-entries)
                     (.getBytes)
                     (ByteArrayInputStream.)
                     (Manifest.))]
    (pom/sync-pom project-map)
    (deps-gen-pom/sync-pom deps-map (.toFile root-path))
    (.mkdirs (.toFile (.getParent out-path)))
    (with-open [jar-out (-> (.toFile out-path)
                            (FileOutputStream.)
                            (BufferedOutputStream.)
                            (JarOutputStream. manifest))]
      (binding [*jar-paths* (transient #{})]
        (Files/walkFileTree root-path
                            (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                            Integer/MAX_VALUE
                            (make-file-visitor
                             jar-out inclusion-path root-path
                             inclusion-path-visitor))
        (doseq [path (:paths deps-map)]
          (Files/walkFileTree (.resolve root-path path)
                              (EnumSet/of FileVisitOption/FOLLOW_LINKS)
                              Integer/MAX_VALUE
                              (make-file-visitor
                               jar-out exclusion-predicate
                               (.resolve root-path path)
                               path-file-visitor)))))))

(comment
  ;; rm -r META-INF/ src/ && unzip badigeon-0.0.1-SNAPSHOT.jar

  (defn inclusion-path [group-id project-name root-files path]
    (license-path group-id project-name root-files path))

  (defn exclusion-predicate [root-path path]
    (prn root-path path)
    true)
  
  (jar {:group-id "badigeongi2"
        :name "badigeonn2"
        :version "0.0.2-SNAPSHOT"}
       :manifest-entries {"Built-By" "ewen2"
                          "Project-awesome-level" "super-great"
                          :my-section-1 [["MyKey1" "MyValue1"] ["MyKey2" "MyValue2"]]
                          :my-section-2 {"MyKey3" "MyValue3" "MyKey4" "MyValue4"}}
       :inclusion-path (partial inclusion-path "badigeongi2" "badigeonn2")
       :exclusion-predicate exclusion-predicate)
  
  (instance? Path (Paths/get "/home" (into-array String ["ewen"])))
  
  (make-out-path "badigeon" "0.0.1" "SNAPSHOT")

  (.resolve (Paths/get (System/getProperty "user.dir") (make-array String 0))
            (Paths/get "target" (make-array String 0)))
  
  (make-manifest 'clojure.main
                 {"Built-by" "ewen2"
                  "Project-awesome-level" "super-great"
                  :my-section-1 [["MyKey1" "MyValue1"] ["MyKey2" "MyValue2"]]
                  :my-section-2 {"MyKey3" "MyValue3" "MyKey4" "MyValue4"}})

  (deps-reader/read-deps (:config-files (deps-reader/clojure-env)))
  )

(comment
  (require '[leiningen.jar])

  (in-ns 'leiningen.jar)
  (#'leiningen.jar/jar pp 'badigeon.tt1/main)

  (pom/make-pom pp)

  (filespecs pp)

  (print (second (manifest-entries nil {:my-section-1 [["MyKey1" "MyValue1"] ["MyKey2" "MyValue2"]]
                                        :my-section-2 {"MyKey3" "MyValue3" "MyKey4" "MyValue4"}})))
  )

(comment
  (in-ns 'leiningen.jar)
  (def pp {:description "A library providing an API to build and package Clojure projects"
           :compile-path "/home/ewen/clojure/badigeon/target/classes"
           :deploy-repositories
           [["clojars"
             {:url "https://clojars.org/repo/"
              :password :gpg
              :username :gpg}]]
           :group "badigeon"
           :license {:name "Eclipse Public License"
                     :url "http://www.eclipse.org/legal/epl-v10.html"}
           :resource-paths '("/home/ewen/clojure/badigeon/dev-resources"
                             "/home/ewen/clojure/badigeon/resources")
           #_:uberjar-merge-with #_{"META-INF/plexus/components.xml"
                                    leiningen.uberjar/components-merger
                                    "data_readers.clj" leiningen.uberjar/clj-map-merger
                                    #"META-INF/services/.*"
                                    [clojure.core/slurp
                                     (fn*
                                      [p1__3347__3349__auto__
                                       p2__3348__3350__auto__]
                                      (clojure.core/str
                                       p1__3347__3349__auto__
                                       "\n"
                                       p2__3348__3350__auto__))
                                     clojure.core/spit]}
           :name "badigeon"
           #_:checkout-deps-shares
           #_[:source-paths
              :test-paths
              :resource-paths
              :compile-path
              #'leiningen.core.classpath/checkout-deps-paths]
           :source-paths '("/home/ewen/clojure/badigeon/src")
           :eval-in :subprocess
           :repositories [["central"
                           {:url "https://repo1.maven.org/maven2/"
                            :snapshots false}]
                          ["clojars"
                           {:url "https://repo.clojars.org/"}]]
           :test-paths '("/home/ewen/clojure/badigeon/test")
           :target-path "/home/ewen/clojure/badigeon/target"
           :prep-tasks ["javac" "compile"]
           :native-path "/home/ewen/clojure/badigeon/target/native"
           :offline? false
           :root "/home/ewen/clojure/badigeon"
           #_:pedantic? #_ranges
           :clean-targets [:target-path]
           :plugins []
           :url "https://github.com/EwenG/replique"
           :plugin-repositories [["central"
                                  {:url "https://repo1.maven.org/maven2/"
                                   :snapshots false}]
                                 ["clojars"
                                  {:url "https://repo.clojars.org/"}]]
           :aliases {"downgrade" "upgrade"}
           :version "0.0.1"
           :jar-exclusions [#"^\."]
           #_:global-vars #_{*print-length* 20
                             *print-level* 10}
           :uberjar-exclusions [#"(?i)^META-INF/[^/]*\.(SF|RSA|DSA)$"]
           :jvm-opts ["-XX:-OmitStackTraceInFastThrow"
                      "-XX:+TieredCompilation"
                      "-XX:TieredStopAtLevel=1"]
           :dependencies '([org.clojure/clojure
                            "1.8.0"]
                           [org.clojure/tools.deps.alpha
                            "0.5.435"]
                           [leiningen/leiningen
                            "2.8.1"]
                           [org.clojure/tools.nrepl
                            "0.2.12"
                            :exclusions
                            ([org.clojure/clojure])]
                           [clojure-complete/clojure-complete
                            "0.2.4" :exclusions
                            ([org.clojure/clojure])])
           :release-tasks [["vcs"
                            "assert-committed"]
                           ["change" "version"
                            "leiningen.release/bump-version"
                            "release"]
                           ["vcs" "commit"]
                           ["vcs" "tag"]
                           ["deploy"]
                           ["change" "version"
                            "leiningen.release/bump-version"]
                           ["vcs" "commit"]
                           ["vcs" "push"]]
           #_:test-selectors #_{:default (constantly true)}})
  )
