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