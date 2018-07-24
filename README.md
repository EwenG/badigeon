# Badigeon

Badigeon is a build library based on tools.deps. Badigeon can be used to:

- Compile java sources
- AOT compile Clojure sources
- Package a project as a jar file
- Install jar files to the local maven repository
- Install jar files to remote maven repository
- Sign jar files
- Package a project into a standalone bundle with a start script
- Produce a custom JRE runtime using jlink

# Release information

Latest release: 0.0.1

[deps.edn](https://clojure.org/guides/deps_and_cli) dependency information:

```clojure
badigeon/badigeon {:git/url "git@github.com:EwenG/badigeon.git"
                   :sha "cfccc96ed1c5e42f44aebcb9236b8bf3b2795b79"
                   :tag "0.0.1"}
```

# API

[API usage](https://github.com/EwenG/badigeon/blob/master/API.md)

[Sample file](https://github.com/EwenG/badigeon/blob/master/sample/badigeon/sample.clj)

---

## License

Copyright 2018 Ewen Grosjean.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file [epl-v10.html](epl-v10.html) at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
