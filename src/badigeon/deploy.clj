(ns badigeon.deploy
  (:require [clojure.tools.deps.alpha.util.maven :as maven]
            [clojure.java.io :as io]
            [badigeon.utils :as utils])
  (:import [org.eclipse.aether.deployment DeployRequest]
           [org.eclipse.aether.repository RemoteRepository$Builder]
           [org.apache.maven.settings DefaultMavenSettingsBuilder]
           [org.apache.maven.settings.building DefaultSettingsBuilderFactory]
           [org.eclipse.aether.util.repository AuthenticationBuilder]
           [java.nio.file Path]))

(defn- set-settings-builder
  [^DefaultMavenSettingsBuilder default-builder settings-builder]
  (doto (.. default-builder getClass (getDeclaredField "settingsBuilder"))
    (.setAccessible true)
    (.set default-builder settings-builder)))

(defn- get-settings
  ^org.apache.maven.settings.Settings []
  (.buildSettings
   (doto (DefaultMavenSettingsBuilder.)
     (set-settings-builder (.newInstance (DefaultSettingsBuilderFactory.))))))

(defn remote-repo [{:keys [id url]} credentials]
  (let [repository (RemoteRepository$Builder. id "default" url)
        ^org.apache.maven.settings.Server server-setting
        (first (filter
                #(.equalsIgnoreCase id
                                    (.getId ^org.apache.maven.settings.Server %))
                (.getServers (get-settings))))
        username (or (:username credentials) (when server-setting
                                               (.getUsername server-setting)))
        password (or (:password credentials) (when server-setting
                                               (.getPassword server-setting)))
        private-key (or (:private-key credentials) (when server-setting
                                                     (.getPassword server-setting)))
        passphrase (or (:passphrase credentials) (when server-setting
                                                   (.getPassphrase server-setting)))]
    (-> repository
        (.setAuthentication (-> (AuthenticationBuilder.)
                                (.addUsername username)
                                (.addPassword password)
                                (.addPrivateKey private-key passphrase)
                                (.build)))
        (.build))))

(defn make-artifact [lib version {:keys [file-path] :as artifact}]
  (let [artifact (utils/artifact-with-default-extension artifact)]
    (-> (maven/coord->artifact lib (assoc artifact :mvn/version version))
        (.setFile (io/file (str file-path))))))

(defn deploy
  ([artifacts lib version repository]
   (deploy artifacts lib version repository nil))
  ([artifacts lib version repository {:keys [credentials]}]
   (java.lang.System/setProperty "aether.checksums.forSignature" "true")
   (let [system (maven/make-system)
         session (maven/make-session system maven/default-local-repo)
         artifacts (map (partial make-artifact lib version) artifacts)
         deploy-request (-> (DeployRequest.)
                            (.setRepository (remote-repo repository credentials)))
         deploy-request (reduce #(.addArtifact %1 %2) deploy-request artifacts)]
     (.deploy system session deploy-request))))
