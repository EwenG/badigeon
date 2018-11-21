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