(ns badigeon.pom
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.data.xml.tree :as tree]
            [clojure.data.xml.event :as event])
  (:import [java.io File Reader ByteArrayOutputStream]
           [java.nio.file Paths]
           [clojure.data.xml.node Element]
           [java.util Properties]))

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(defn- to-dep
  [[lib {:keys [mvn/version classifier exclusions] :as coord}]]
  (if version
    (cond->
        [::pom/dependency
         [::pom/groupId (or (namespace lib) (name lib))]
         [::pom/artifactId (name lib)]
         [::pom/version version]]

      classifier
      (conj [::pom/classifier classifier])

      (seq exclusions)
      (conj [::pom/exclusions
             (map (fn [excl]
                    [::pom/exclusion
                     [::pom/groupId (namespace excl)]
                     [::pom/artifactId (name excl)]])
                  exclusions)]))))

(defn- gen-deps
  [deps]
  [::pom/dependencies
   (map to-dep deps)])

(defn- to-repo
  [[name repo]]
  [::pom/repository
   [::pom/id name]
   [::pom/url (:url repo)]])

(defn- gen-repos
  [repos]
  [::pom/repositories
   (map to-repo repos)])

(defn gen-pom [group-id artifact-id version deps repos]
  (xml/sexp-as-element
   [::pom/project
    {:xmlns "http://maven.apache.org/POM/4.0.0"
     (keyword "xmlns:xsi") "http://www.w3.org/2001/XMLSchema-instance"
     (keyword "xsi:schemaLocation") "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"}
    [::pom/modelVersion "4.0.0"]
    (when group-id [::pom/groupId group-id])
    (when artifact-id [::pom/artifactId artifact-id])
    (when version [::pom/version version])
    (when (seq deps) (gen-deps deps))
    (when (seq repos) (gen-repos repos))]))

(defn- parse-xml
  [^Reader rdr]
  (let [roots (tree/seq-tree
               event/event-element event/event-exit? event/event-node
               (xml/event-seq rdr {:include-node? #{:element :characters :comment}}))]
    (first (filter #(instance? Element %) (first roots)))))

(defn- make-xml-element
  [{:keys [tag attrs] :as node} children]
  (with-meta
    (apply xml/element tag attrs children)
    (meta node)))

(defn- xml-update
  [root tag-path replace-node]
  (let [z (zip/zipper xml/element? :content make-xml-element root)]
    (zip/root
     (loop [[tag & more-tags :as tags] tag-path, parent z, child (zip/down z)]
       (if child
         (if (= tag (:tag (zip/node child)))
           (if (seq more-tags)
             (recur more-tags child (zip/down child))
             (zip/edit child (constantly replace-node)))
           (recur tags parent (zip/right child)))
         (zip/append-child parent replace-node))))))

(defn- replace-project-infos [pom group-id artifact-id version]
  (cond-> pom
    group-id (xml-update [::pom/groupId]
                         (xml/sexp-as-element [::pom/groupId group-id]))
    artifact-id (xml-update [::pom/artifactId]
                            (xml/sexp-as-element [::pom/artifactId artifact-id]))
    version (xml-update [::pom/version] (xml/sexp-as-element [::pom/version version]))))

(defn- replace-deps
  [pom deps]
  (xml-update pom [::pom/dependencies] (xml/sexp-as-element (gen-deps deps))))

(defn- replace-repos
  [pom repos]
  (if (seq repos)
    (xml-update pom [::pom/repositories] (xml/sexp-as-element (gen-repos repos)))
    pom))

(defn sync-pom [lib {:keys [:mvn/version]} {:keys [deps :mvn/repos]}]
  "Creates or updates a pom.xml file at the root of the project. lib is a symbol naming the library the pom.xml file refers to. The groupId attribute of the pom.xml file is the namespace of the symbol \"lib\" if lib is a namespaced symbol, or if its name is an unqualified symbol. The artifactId attribute of the pom.xml file is the name of the \"lib\" symbol. The pom.xml version, dependencies, and repositories attributes are updated using the version, deps and repos parameters."
  (let [root-path (Paths/get (System/getProperty "user.dir") (make-array String 0))
        artifact-id (name lib)
        group-id (or (namespace lib) artifact-id)
        pom-path (.resolve root-path "pom.xml")
        pom-file (.toFile pom-path)
        pom (if (.exists pom-file)
              (with-open [rdr (io/reader pom-file)]
                (-> rdr
                    parse-xml
                    (replace-project-infos group-id artifact-id version)
                    (replace-deps deps)
                    (replace-repos repos)))
              (gen-pom group-id artifact-id version deps repos))]
    (spit pom-file (xml/indent-str pom))))

(defn make-pom-properties [lib {:keys [:mvn/version]}]
  (let [baos (ByteArrayOutputStream.)
        artifact-id (name lib)
        group-id (or (namespace lib) artifact-id)
        properties (Properties.)]
    (.setProperty properties "groupId" group-id)
    (.setProperty properties "artifactId" artifact-id)
    (when version (.setProperty properties "version" version))
    (.store properties baos "Badigeon")
    (.toByteArray baos)))

(comment
  (sync-pom 'badigeon/badigeon
            '{:mvn/version "0.0.1-SNAPSHOT"}
            '{:deps {org.clojure/clojure {:mvn/version "1.9.0"}
                     badigeon-deps/badigeon-deps
                     {:local/root "badigeon-deps"}}
              :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                          "clojars" {:url "https://repo.clojars.org/"}}})

  (make-pom-properties 'badigeong/badigeon '{:mvn/version "0.0.1-SNAPSHOT"})
  )

