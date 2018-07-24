# API

## `badigeon.clean/clean`

Usage: `(badigeon.clean/clean target-directory {:keys [allow-outside-target?]})`

Args:

* target-directory - Delete the target-directory. The directory to delete must not be outside of project root. By default, the directory to delete must either be the directory named "target" or must be inside the directory named "target". This constraint can be bypassed by setting "allow-outside-target?" to true.