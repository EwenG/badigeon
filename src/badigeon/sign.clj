(ns badigeon.sign
  (:require [clojure.java.io :as io]
            [badigeon.utils :as utils]
            [badigeon.exec :as exec])
  (:import [java.nio.file Path]))

(defn signing-args [file gpg-key]
  (let [key-spec (when gpg-key
                   ["--default-key" gpg-key])]
    `["--yes" "-ab" ~@key-spec "--" ~file]))

(defn sign-one
  "Sign a single artifact. The artifact must be a map with a :file-path key and an optional :extension key. :file-path is the path to th file to be signed. :extension is the artifact packaging type. :extension is optional and defaults to \"jar\" for jar files and \"pom\" for pom files.
  Returns an artifact representing the signature of the input artifact."
  ([artifact]
   (sign-one artifact nil))
  ([artifact {:keys [command gpg-key] :or {command "gpg"}}]
   (let [{:keys [file-path extension]} (utils/artifact-with-default-extension artifact)
         file-path (str file-path)]
     (exec/exec command {:proc-args (signing-args file-path gpg-key)
                         :error-msg "Error while signing"})
     `{:file-path ~(str file-path ".asc")
       :badigeon/signature? true
       ~@(when extension [:extension (str extension ".asc")])
       ~@nil})))

(defn sign
  "Sign a collection of artifacts using the \"gpg\" command.
  - artifacts: A collections of artifacts. Each artifact must be a map with a :file-path key and an optional :extension key. :file-path is the path to th file to be signed. :extension is the artifact packaging type. :extension is optional and defaults to \"jar\" for jar files and \"pom\" for pom files.
  - command: The command used to sign the artifact. Default to \"gpg\".
  - gpg-key: The private key to be used. Default to the first private key found.
  Returns the artifacts representing the signatures of the input artifacts conjoined to the input artifacts."
  ([artifacts]
   (sign artifacts nil))
  ([artifacts {:keys [command gpg-key] :as opts}]
   (reduce #(conj %1 (sign-one %2 opts)) artifacts artifacts)))

