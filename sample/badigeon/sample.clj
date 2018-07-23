(ns badigeon.sample
  (:require [badigeon.clean :as clean]
            [badigeon.javac :as javac]
            [badigeon.compile :as compile]
            [badigeon.jar :as jar]
            [badigeon.install :as install]))

(defn -main []
  ;; Delete the target directory
  (clean/clean "target"
               {;; By default, Badigeon does not allow deleting forlders outside the target directory,
                ;; unless :allow-outside-target? is true
                :allow-outside-target? true})

  ;; Compile java sources under the src-java directory
  (javac/javac "src-java" {;; Emit class files to the target/classes directory
                           :compile-path "target/classes"
                           ;; Additional options used by the javac command
                           :compiler-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]})

  ;; AOT compiles the badigeon.main namespace. Badigeon AOT compiles Clojure sources in a fresh classloader, using the clojure.core/compile function. As a consequence, all the namespaces and their dependencies always get recompiled, unlike with the clojure.core/compile function. Beware of side effects triggered while loading the compiled namespaces.
  (compile/compile '[badigeon.main]
                   {;; Emit class files to the target/classes directory
                    :compile-path "target/classes"
                    ;; Compiler options used by the clojure.core/compile function
                    :compiler-options {:disable-locals-clearing false
                                       :elide-meta [:doc :file :line :added]
                                       :direct-linking true}})

  ;; Package the project into a jar file
  (jar/jar 'badigeon/badigeon {:mvn/version "0.0.1-SNAPSHOT"}
           {;; The jar file produced.
            :out-path "target/badigeon-0.0.1-SNAPSHOT.jar"
            ;; Adds a \"Main\" entry to the jar manifest with the value \"badigeon.main\"
            :main 'badigeon.main
            ;; Additional key/value pairs to add to the jar manifest. If a value is a collection, a manifest section is built for it.
            :manifest {"Project-awesome-level" "super-great"
                       :my-section-1 [["MyKey1" "MyValue1"] ["MyKey2" "MyValue2"]]
                       :my-section-2 {"MyKey3" "MyValue3" "MyKey4" "MyValue4"}}
            ;; By default Badigeon add entries for all files in the directory listed in the :paths section of the deps.edn file. This can be overridden here.
            :paths ["src" "target/classes"]
            ;; The dependencies to be added to the \"dependencies\" section of the pom.xml file. When not specified, defaults to the :deps entry of the deps.edn file, without merging the user-level and system-level deps.edn files
            :deps '{org.clojure/clojure {:mvn/version "1.9.0"}}
            ;; The repositories to be added to the \"repositories\" section of the pom.xml file. When not specified, default to nil - even if the deps.edn files contains a :mvn/repos entry.
            :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
            ;; A predicate used to excludes files from beeing added to the jar. The predicate is a function of two parameters: The path of the directory beeing visited (among the :paths of the project) and the path of the file beeing visited under this directory.
            :exclusion-predicate badigeon.jar/default-exclusion-predicate
            ;; A function to add files to the jar that would otherwise not have been added to it. The function must take two parameters: the path of the root directory of the project and the file being visited under this directory. When the function returns a falsy value, the file is not added to the jar. Otherwise the function must return a string which represents the path within the jar where the file is copied. 
            :inclusion-path (partial badigeon.jar/default-inclusion-path "badigeon" "badigeon")
            ;; By default git and local dependencies are not allowed. Set allow-all-dependencies? to true to allow them 
            :allow-all-dependencies? true})

  ;; Install the previously created jar file into the local maven repository
  (install/install 'badigeon/badigeon {:mvn/version "0.0.1-SNAPSHOT"}
                   ;; The jar file to be installed
                   "target/badigeon-0.0.1-SNAPSHOT.jar"
                   ;; The pom.xml file to be installed. THis file is generted when creating the jar with the badigeon.jar/jar function
                   "pom.xml"
                   {:local-repo (str (System/getProperty "user.home") "/.m2/repository")})
  )

(comment
  
  )
