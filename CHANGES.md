# 1.3

- Fix duplicate entries exception when building a jar from resources paths that contains multiple directories with the same name
- tools.deps 0.9.857

# 1.2
Bad build.

# 1.1

- Update to tools.deps.alpha version 0.9.763
- Add the directories found on the classpath as jar entries when creating jars
- Add directories as zip entries when creating zips
- Fix a potential exception when using badigeon.uberjar/merge-resource-conficts 

# 1.0

- Bugfix: Fix the path of jar entries copied from resources when building on windows

# 0.0.13

- Bugfix: Copy local dependencies when using badigeon.bundle/bundle 

# 0.0.12

- Update to tools.deps.alpha version 0.8.677

# 0.0.11

- The badigeon.uberjar/bundle function now always copies ".class" files to the output directory, even in case of conflicts.
- The badigeon.uberjar/find-resource-conflicts function now returns a map from resource path to a collection of directories/jar files that contain the resource. 
- Add the badigeon.uberjar/merge-resource-conflicts function
- Bug fix: Resource conficts in jar file were not always detected
- badigeon.bundle/bundle now automatically resolves dependencies conflicts when multiple dependencies have the same artifact-id and version number
- Document the badigeon.war/make-out-path function
- badigeon.war/make-out-path now correctly handles the version number to generate the out path
- Update to tools.deps.alpha version 0.8.633

# 0.0.10

- Update to tools.deps.alpha version 0.8.578
- Add support to pass in aliases to relevant functions

# 0.0.9

- Add the badigeon.uberjar/walk-directory function
- Add the badigeon.uberjar/make-out-path function
- Add the badigeon.bundle/walk-directory function
- Update to tools.deps.alpha version 0.7.541

# 0.0.8

- Copy the last modification time of files included in the zip archive created with badigeon.zip/zip. This fixes potential issues when .classes files were added to the zip.
- Copy the last modification time of files included in a jar archive using the "inclusion-path" option of badigeon.jar/jar. This fixes potential issues when .classes files were added to the jar using the "inclusion-path" option.
- Document badigeon.jar/make-manifest and implement a single arity version of it
- Add the badigeon.uberjar/find-resource-conflicts function
- Add the badigeon.uberjar/bundle function
- Exceptions thrown while compiling using badigeon.compile/compile are now thrown instead of just being printed.
- Fix badigeon.zip/zip when building from windows.
- Fix extracting native dependencies when building from windows.
- Add the badigeon.compile/extract-classes-from-dependencies function. This function can be used to extract classes from already AOT dependencies, such as the Clojure dependency.
- Add the badigeon.bundle/extract-native-dependencies-from-file function. This function extracts native dependencies from a single file.
- Update to tools.deps.alpha version 0.7.516

# 0.0.7

- Add a ":native-extensions" option to badigeon.bundle/extract-native-dependencies
- badigeon.bundle/extract-native-dependencies now recognizes more native extensions by default (https://github.com/EwenG/badigeon/pull/5)
- Update to tools.deps.alpha version 0.6.496

# 0.0.6

- Update to tools.deps.alpha version 0.6.480. Warning: The classifier key of dependencies is not supported anymore by tools.deps.alpha 0.6.480.

# 0.0.5

- Document the fact that AOT compiled dependencies do not get AOT compiled again by badigeon.compile/compile
- Make badigeon.compile/compile to accept java nio Paths
- Fix badigeon for clojure version < 1.9.0

# 0.0.4

- Update the tools.deps dependency to 0.5.452
- The badigeon.compile/compile function now takes an optional :classpath argument
- Add the possibility to compute a classpath string using a tools.deps dependency map and optional aliases. 

# 0.0.3

- Add the possibility to execute shell commands in a separate process.

# 0.0.2

- Add the possibility to package the project as a war file.