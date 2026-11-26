# Javac plugin for generating JSpecify JDK astubx file

This module and others contain logic to generate an astubx file from the
[annotated JSpecify JDK](https://github.com/jspecify/jdk).  The generation works
in two stages:

1. This module provides a javac plugin that gets injected into the build of the
JSpecify JDK.  It generates `.json` files capturing the nullability annotations
in the JDK.
2. We have a separate astubx generator (main entrypoint:
`com.uber.nullaway.jdkannotations.AstubxGeneratorCLI`) that turns the `.json`
files into an `.astubx` file.  

NullAway does not bundle an astubx file generated this way: it reads the
JSpecify JDK models from the class files of a
[jspecify-jdk-stubs](https://github.com/vlsi/jspecify-jdk-stubs) jar, whose
release is set by `jspecifyJdkStubs` in `gradle/libs.versions.toml`.
