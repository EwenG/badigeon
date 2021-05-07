# Badigeon

Badigeon is a build library based on tools.deps. Badigeon can be used to:

- Compile java sources
- AOT compile Clojure sources
- Package a project as a jar file
- Install jar files to the local maven repository
- Install jar files to remote maven repository
- Sign jar files
- Package a project into a standalone bundle with a start script
- Package a project as an uberjar file
- Produce a custom JRE runtime using jlink
- Package a project as a war file
- Execute shell commands in a separate process

# Release information

Latest release: 1.6

[deps.edn](https://clojure.org/guides/deps_and_cli) dependency information:

```clojure
badigeon/badigeon {:mvn/version "1.6"}
```

# API

[API usage](https://github.com/EwenG/badigeon/blob/master/API.md)

[Sample file](https://github.com/EwenG/badigeon/blob/master/sample/badigeon/sample.clj)


# Example projects

[badigeon-hello-world](https://github.com/EwenG/badigeon-hello-world)

---

## License

Copyright 2019 Ewen Grosjean.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
