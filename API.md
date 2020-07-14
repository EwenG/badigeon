# API

## `badigeon.clean/clean`

Arglists: `([target-directory] [target-directory {:keys [allow-outside-target?]}])`

Delete the target-directory. The directory to delete must not be outside of project root. By default, the directory to delete must either be the directory named "target" or must be inside the directory named "target". Setting the "allow-outside-target?" parameter to true makes deleting directories outside "target" possible.

## `badigeon.classpath/make-classpath`

Arglists: `([] [{:keys [deps-map aliases]}])`

Builds a classpath by using the provided deps spec or, by default, the deps.edn file of the current project. Returns the built classpath as a string.
  - deps-map: A map with the same format than a deps.edn map. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - aliases: Alias keywords used while building the classpath.

## `badigeon.javac/javac`

Arglists: `([source-dir] [source-dir {:keys [compile-path javac-options]}])`

Compiles java source files found in the "source-dir" directory. Note that the badigeon.javac/javac functions triggers the start of Clojure agents. You might want to call clojure.core/shutdown-agents to close the agent thread pools.
  - source-dir: The path of a directory containing java source files.
  - compile-path: The path to the directory where .class file are emitted.
  - javac-options: A vector of the options to be used when invoking the javac command. Default to using a "-cp" argument computed using the project deps.edn file (without merging the system-level and user-level deps.edn maps) and a "-d" argument equal to the compile-path.

## `badigeon.compile/compile`

Arglists: `([namespaces] [namespaces {:keys [compile-path compiler-options classpath], :as options}])`

AOT compile one or several Clojure namespace(s). Dependencies of the compiled namespaces are
  always AOT compiled too, unless they come under an already AOT compiled form. Namespaces are
  loaded while beeing compiled so beware of side effects.
  - namespaces: A symbol or a collection of symbols naming one or several Clojure namespaces.
  - compile-path: The path to the directory where .class files are emitted. Default to "target/classes".
  - compiler-options: A map with the same format than clojure.core/\*compiler-options\*.
  - classpath: The classpath used while AOT compiling. Defaults to a classpath string computed using the deps.edn file of the current project, without merging the system-level and user-level deps.edn maps.

## `badigeon.compile/extract-classes-from-dependencies`

Arglists: `([] [{:keys [out-path deps-map aliases excluded-libs allow-unstable-deps?], :as opts}])`

Extract classes from jar dependencies. By default, classes are extracted to the "target/classes" folder. This function can be used to circumvent the fact that badigeon.compile/compile does not compile dependencies that are already AOT, such as Clojure itself.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies with a jar format resolved from this map are searched for ".class" files. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving dependencies.
  - excluded-libs: A set of lib symbols to be excluded from the produced bundle. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.

## `badigeon.jar/jar`

Arglists: `([lib maven-coords] [lib maven-coords {:keys [out-path main manifest paths deps :mvn/repos exclusion-predicate inclusion-path allow-all-dependencies?], :as options}])`

Bundles project resources into a jar file. This function also generates maven description files. By default, this function ensures that all the project dependencies are maven based.
  - lib: A symbol naming the library.
  - maven-coords: A map with the same format than tools.deps maven coordinates.
  - out-path: The path of the produced jar file. When not provided, a default out-path is generated from the lib and maven coordinates.
  - main: A namespace to be added to the "Main" entry to the jar manifest. Default to nil.
  - manifest: A map of additional entries to the jar manifest. Values of the manifest map can be maps to represent manifest sections. By default, the jar manifest contains the "Created-by", "Built-By" and "Build-Jdk" entries.
  - paths: A vector of the paths containing the resources to be bundled into the jar. Default to the paths of the deps.edn file.
  - deps: The dependencies of the project. deps have the same format than the :deps entry of a tools.deps map. Dependencies are copied to the pom.xml file produced while generating the jar file. Default to the deps.edn dependencies of the project (excluding the system-level and user-level deps.edn dependencies).
  - mvn/repos: Repositories to be copied to the pom.xml file produced while generating the jar. Must have same format than the :mvn/repos entry of deps.edn. Default to nil.
  - exclusion-predicate: A predicate to exclude files that would otherwise been added to the jar. The predicate takes two parameters: the path of the directory being visited (among the :paths of the project) and the path of the file being visited under this directory. The file being visited is added to the jar when the exclusion predicate returns a falsy value. It is excluded from the jar otherwise. Default to a predicate that excludes dotfiles and emacs backup files.
  - inclusion-path: A predicate to add files to the jar that would otherwise not have been added to it. Can be used to add any file of the project to the jar - not only those under the project :paths. The predicate takes two arguments: the path of the root directory of the project and the file being visited under this directory. The file being visited is added to the jar under the path returned by this function. It is not added to the jar when this function returns a falsy value. Default to a predicate that add the pom.xml, deps.edn, and any file at the root of the project directory starting with "license" or "readme" (case incensitive) under the "META-INF" folder of the jar.
  - allow-all-dependencies?: A boolean that can be set to true to allow any types of dependency, such as local or git dependencies. Default to false, in which case only maven dependencies are allowed - an exception is thrown when this is not the case. When set to true, the jar is produced even in the presence of non-maven dependencies, but only maven dependencies are added to the jar.

## `badigeon.jar/make-manifest`

Arglists: `([main] [main manifest-overrides])`

Return the content of a MANIFEST.MF file as a string.
  - main: A namespace to be added to the "Main" entry to the manifest. Default to nil.
  - manifest-overrides: A map of additionel entries to the manifest. Values of the manifest map can be maps to represent manifest sections. By default, the manifest contains the "Created-by", "Built-By" and "Build-Jdk" entries.

## `badigeon.pom/sync-pom`

Arglists: `([lib {:keys [:mvn/version]} {:keys [deps :mvn/repos]}])`

Creates or updates a pom.xml file at the root of the project. lib is a symbol naming the library the pom.xml file refers to. The groupId attribute of the pom.xml file is the namespace of the symbol "lib" if lib is a namespaced symbol, or if its name is an unqualified symbol. The artifactId attribute of the pom.xml file is the name of the "lib" symbol. The pom.xml version, dependencies, and repositories attributes are updated using the version, deps and repos parameters.

## `badigeon.install/install`

Arglists: `([lib maven-coords file-path pom-file-path] [lib maven-coords file-path pom-file-path {:keys [local-repo]}])`

Install a jar file into the local maven repository.
  - lib: A symbol naming the library to install. The groupId of the installed library is the namespace of the symbol "lib" if lib is a namespaced symbol, or its name if lib is an unqualified symbol. The artifactId of the installed symbol is the name of the "lib" symbol.
  - maven-coords: A map representing the maven coordinates of the library, under the same format than the one used by tools.deps.
  - file-path: The path to the jar to be installed.
  - pom-file-path: The path to the pom.xml file to be installed.
  - local-repo: The path to the local maven repository where the library is to be installed. Default to ~/.m2/repository .

## `badigeon.prompt/prompt`

Arglists: `([prompt])`

Read a string from \*in\*, prompting with string "prompt".

## `badigeon.prompt/prompt-password`

Arglists: `([prompt])`

Read a string for the process standard input without echoing, prompting with string "prompt". Note that the process standard input may be different than \*in\* when using a socket REPL for example.

## `badigeon.sign/sign`

Arglists: `([artifacts] [artifacts {:keys [command gpg-key], :as opts}])`

Sign a collection of artifacts using the "gpg" command.
  - artifacts: A collections of artifacts. Each artifact must be a map with a :file-path key and an optional :extension key. :file-path is the path to th file to be signed. :extension is the artifact packaging type. :extension is optional and defaults to "jar" for jar files and "pom" for pom files.
  - command: The command used to sign the artifact. Default to "gpg".
  - gpg-key: The private key to be used. Default to the first private key found.
  Returns the artifacts representing the signatures of the input artifacts conjoined to the input artifacts.

## `badigeon.deploy/deploy`

Arglists: `([lib version artifacts repository] [lib version artifacts repository {:keys [credentials allow-unsigned?]}])`

Deploys a collection of artifacts to a remote repository. When deploying non-snapshot versions of artifacts, artifacts must be signed, unless the "allow-unsigned?" parameter is set to true.
  - lib: A symbol naming the library to be deployed.
  - version: The version of the library to be deployed.
  - artifacts: The collection of artifacts to be deployed. Each artifact must be a map with a :file-path and an optional :extension key. :extension defaults to "jar" for jar file and "pom" for pom files. Artifacts representing a signature must also have a :badigeon/signature? key set to true.
  - repository: A map with an :id and a :url key representing the remote repository where the artifacts are to be deployed. The :id is used to find credentials in the settings.xml file when authenticating to the repository.
  - credentials: When authenticating to a repository, the credentials are searched in the maven settings.xml file, using the repository :id, unless the "credentials" parameter is used. credentials must be a map with the following optional keys: :username, :password, :private-key, :passphrase
  - allow-unsigned?: When set to true, allow deploying non-snapshot versions of unsigned artifacts. Default to false.

## `badigeon.bundle/make-out-path`

Arglists: `([lib version])`

Build a path using a library name and its version number.

## `badigeon.bundle/bundle`

Arglists: `([out-path] [out-path {:keys [deps-map aliases excluded-libs allow-unstable-deps? libs-path]}])`

Creates a standalone bundle of the project resources and its dependencies. By default jar dependencies are copied in a "lib" folder, under the ouput directory. Other dependencies (local and git) are copied by copying their :paths content to the root of the output directory. By default, an exception is thrown when the project dependends on a local dependency or a SNAPSHOT version of a dependency.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.
  - excluded-libs: A set of lib symbols to be excluded from the produced bundle. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - libs-path: The path of the folder where dependencies are copied, relative to the output folder. Default to "lib".

## `badigeon.bundle/extract-native-dependencies`

Arglists: `([out-path] [out-path {:keys [deps-map aliases allow-unstable-deps? native-path native-prefixes native-extensions], :as opts}])`

Extract native dependencies (.so, .dylib, .dll, .a, .lib, .scx files) from jar dependencies. By default native dependencies are extracted to a "lib" folder under the output directory.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies with a jar format resolved from this map are searched for native dependencies. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - native-path: The path of the folder where native dependencies are extracted, relative to the output folder. Default to "lib".
  - native-prefixes: A map from libs (symbol) to a path prefix (string). Libs with a specified native-prefix are searched for native dependencies under the path of the native prefix only. The native-prefix is excluded from the output path of the native dependency.
  - native-extensions: A collection of native extension regexp. Files which name match one of these regexps are considered a native dependency. Default to badigeon.bundle/native-extensions.

## `badigeon.bundle/extract-native-dependencies-from-file`

Arglists: `([out-path file-path] [out-path file-path {:keys [native-path native-prefix native-extensions], :as opts}])`

Extract native dependencies (.so, .dylib, .dll, .a, .lib, .scx files) from a jar file. By default native dependencies are extracted to a "lib" folder under the output directory.
  - out-path: The path of the output directory.
  - file-path: The path of jar file from which the native dependencies are extracted.
  - native-path: The path of the folder where native dependencies are extracted, relative to the output folder. Default to "lib".
  - native-prefix: A path prefix (string). The file is searched for native dependencies under the path of the native prefix only. The native-prefix is excluded from the output path of the native dependency.
  - native-extensions: A collection of native extension regexp. Files which name match one of these regexps are considered a native dependency. Default to badigeon.bundle/native-extensions.

## `badigeon.bundle/walk-directory`

Arglists: `([directory f])`

Recursively visit all the files of a directory. For each visited file, the function f is called with two parameters: The path of the directory being recursively visited and the path of the file being visited, relative to the directory.

## `badigeon.bundle/bin-script`

Arglists: `([out-path main] [out-path main {:keys [os-type script-path script-header command classpath jvm-opts args]}])`

Write a start script for the bundle under "out-path", using the "main" parameter as the CLojure namespace defining the -main method entry point.
  - os-type: Either the badigeon.bundle.windows-like constant or the badigeon.bundle.posix-like constant, depending on the wanted script type. Default to badigeon.bundle.posix-like .
  - script-path: The output path of the script, relative to the "out-path" parameter. Default to bin/run.sh or bin/run.bat, depending on the os-type .
  - script-header: A string prefixed to the script. Default to "#!/bin/sh
" or "@echo off
", depending on the os-type.
  - command: The command run by the script. Default to "java" or "runtime/bin/java" if the "runtime" folder contains a custom JRE created with jlink.
  - classpath: The classpath argument used when executing the command. Default a classpath containing the root folder and the lib directory.
  - jvm-opts: A vector of jvm arguments used when executing the command. Default to the empty vector.
  - args: A vector of arguments provided to the program. Default to the empty vector.

## `badigeon.uberjar/make-out-path`

Arglists: `([lib version])`

Build a path using a library name and its version number.

## `badigeon.uberjar/bundle`

Arglists: `([out-path] [out-path {:keys [deps-map aliases excluded-libs allow-unstable-deps? warn-on-resource-conflicts?]}])`

Creates a directory that contains all the resources from all the dependencies resolved from "deps-map". Resource conflicts (multiple resources with the same path) are not copied to the output directory. ".class" files are an exception, they are always copied to the ouput directory. Use the "badigeon.uberjar/find-resource-conflicts" function to list resource conflicts. By default, an exception is thrown when the project depends on a local dependency or a SNAPSHOT version of a dependency.
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repositories.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.
  - excluded-libs: A set of lib symbols to be excluded from the produced directory. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - warn-on-resource-conflicts?. A boolean. When set to true and resource conflicts are found, then a warning is printed to \*err\*.

## `badigeon.uberjar/find-resource-conflicts`

Arglists: `([] [{:keys [deps-map aliases], :as params}])`

Return the paths of all the resource conflicts (multiple resources with the same path) found on the classpath.
  - deps-map: A map with the same format than a deps.edn map. The dependencies resolved from this map are searched for conflicts. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.

## `badigeon.uberjar/merge-resource-conflicts`

Arglists: `([out-path] [out-path {:keys [deps-map aliases resource-mergers]}])`

Merge the resource conflicts (multiple resources with the same path) found on the classpath and handled by the provided "resource-mergers".
  - out-path: The path of the output directory.
  - deps-map: A map with the same format than a deps.edn map. The dependencies resolved from this map are searched for conflicts. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving the project resources and its dependencies.
  - resource-mergers: A map which keys are strings or regexps and values are maps called "mergers". "Mergers" are used to merge the resources which path matches one of the keys of "resource-mergers". "Mergers" must be maps containing three keys: :read, :merge, and :write. Default to "badigeon.uberjar/default-resource-mergers".

## `badigeon.uberjar/walk-directory`

Arglists: `([directory f])`

Recursively visit all the files of a directory. For each visited file, the function f is called with two parameters: The path of the directory being recursively visited and the path of the file being visited, relative to the directory.

## `badigeon.jlink/jlink`

Arglists: `([out-path] [out-path {:keys [jlink-path module-path modules jlink-options]}])`

Creates a custom JRE using the jlink command. To be run, this function requires a JDK >= version 9. 
  - out-path: The output folder of the custom JRE (this often is the same out-path than the one provided to the Badigeon "bundle" function). By default the JRE is output in the out-path/runtime directory.
  - jlink-path: The folder where the custom JRE is output, relative to "out-path". Default to "runtime".
  - module-path: The path where the java module are searched for. Default to "JAVA_HOME/jmods".
  - modules: A vector of modules to be used when creating the custom JRE. Default to ["java.base"]
  - jlink-options: The options used when executing the jlink command. Default to ["--strip-debug" "--no-man-pages" "--no-header-files" "--compress=2"]

## `badigeon.zip/zip`

Arglists: `([directory-path] [directory-path out-path])`

Zip a directory. By default, outputs the zipped directory to a file with the same name than "directory-path" but with a .zip extension. The directory to be zipped is often the directory created by the Badigeon "bundle" function.

## `badigeon.war/make-out-path`

Arglists: `([lib version])`

Build a path using a library name and its version number.

## `badigeon.war/war-exploded`

Arglists: `([out-path servlet-namespace] [out-path servlet-namespace {:keys [compiler-options deps-map aliases excluded-libs allow-unstable-deps? manifest servlet-version servlet-name servlet-class url-pattern listener-namespace listener-class], :as opts}])`

Creates an exploded war directory. The produced war can be run on legacy java servers such as Tomcat. This function AOT compiles the provided servlet-namespace. The servlet-namespace must contain a :gen-class directive implementing an HttpServlet.
  - out-path: The path of the output directory.
  - servlet-namespace: A symbol naming a namespace. This namespace must contain a :gen-class directive implementing an HttpServlet.
  - compiler-options: A map with the same format than clojure.core/\*compiler-options\*. The compiler-options are used when compiling the servlet-namespace and, when provided, the listener-namespace.
  - deps-map: A map with the same format than a deps.edn map. The dependencies of the project are resolved from this map in order to be copied to the output directory. Default to the deps.edn map of the project (without merging the system-level and user-level deps.edn maps), with the addition of the maven central and clojars repository.
  - aliases: Alias keywords used while resolving dependencies.
  - excluded-libs: A set of lib symbols to be excluded from the produced bundle. Only the lib is excluded and not its dependencies.
  - allow-unstable-deps?: A boolean. When set to true, the project can depend on local dependencies or a SNAPSHOT version of a dependency. Default to false.
  - manifest: A map of additionel entries to the war manifest. Values of the manifest map can be maps to represent manifest sections. By default, the war manifest contains the "Created-by", "Built-By" and "Build-Jdk" entries.
  - servlet-version: The version of the servlet spec that we claim to conform to. Attributes corresponding to this version will be added to the web-app element of the web.xml. If not specified, defaults to 2.5.
  - servlet-name: The name of the servlet (in web.xml). Defaults to the servlet-namespace name.
  - servlet-class: The servlet class name. Default to the munged servlet-namespace name.
  - url-pattern: The url pattern of the servlet mapping (in web.xml). Defaults to "/\*".
  - listener-namespace: A symbol naming a namespace. This namespace must contain a :gen-class directive implementing a ServletContextListener.
  - listener-class: Class used for servlet init/destroy functions. Called listener because underneath it uses a ServletContextListener.

## `badigeon.war/war`

Arglists: `([out-path servlet-namespace] [out-path servlet-namespace {:keys [compiler-options deps-map aliases excluded-libs allow-unstable-deps? manifest servlet-version servlet-name servlet-class url-pattern listener-namespace listener-class], :as opts}])`

Use the badigeon.war/war-exploded function to create an exploded war directory and zip the result into a .war file.

## `badigeon.exec/exec`

Arglists: `([command] [command {:keys [proc-args error-msg]}])`

Synchronously executes the specified command in a separate process. Prints the process output using "clojure.core/print". Throws an exception when the process exit code is not 0.
  - command: The command to be executed.
  - proc-args: A collection of command arguments. Default to no argument.
  - error-msg: The error message of the exception thrown upon error. Default to "Process execution error".

## `badigeon.utils/make-path`

Arglists: `([path & paths])`

Returns a java.nio.file.Path constructed from the provided String(s).

