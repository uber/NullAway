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

To regenerate the file and run the main NullAway tests and self-check, run:

```bash
./jdk-javac-plugin/regenerate-jspecify-jdk.sh --boot-jdk /path/to/boot-jdk
```

The script fetches a shallow checkout of the JSpecify JDK at the pinned
`JDK_REVISION` in the script. To reuse an existing clean checkout at that
revision, add `--jdk-dir /path/to/jdk`. The boot JDK argument is optional;
without it, JDK configure searches for a compatible installed JDK.
Configuration, build output, and JSON files go into a fresh temporary directory.
Failed runs retain that directory and their logs; use `--keep-temp` to retain it
after successful runs as well. Use `--verbose` to stream command output while
also saving it to the logs.

The equivalent manual steps are:

1. Build this module and the `astubx-generator-cli` module:
`./gradlew :jdk-javac-plugin:build :jdk-annotations:astubx-generator-cli:build`
2. Clone the [JSpecify JDK](https://github.com/jspecify/jdk), and check out the
`JDK_REVISION` pinned in `regenerate-jspecify-jdk.sh`.
3. In the JDK repo, configure with a compatible boot JDK. For example:

```bash
bash configure \
    --with-boot-jdk=/Library/Java/JavaVirtualMachines/zulu-27.jdk/Contents/Home
```

Adjust the boot JDK path for your machine. The javac server can remain enabled.
4. Create `build/<configuration>/custom-spec.gmk` in the JDK repo containing:

```make
include /path/to/NullAway/jdk-javac-plugin/jdk-build-hooks/custom-spec.gmk
```

Adjust the NullAway path and use the configuration directory created by
`configure` (e.g., `macosx-aarch64-server-release`). The external hooks inject
the plugin and `-XDaccessInternalAPI=true` without modifying JDK source files.
They use a processor path to load the plugin and, where needed, the JDK's
`depend` plugin.

In the JDK repo, run `make clean && make jdk`. JSON files go to `/tmp` by
default. To avoid stale files, use a fresh directory and pass
`NULLAWAY_JDK_JSON_DIR=/path/to/fresh-directory` to `make jdk`.
5. Run the `AstubxGeneratorCLI` main method (e.g., you can run it from within
IntelliJ).  It takes two arguments.  The first is the directory containing the
json files, and the second is where the output astubx file should be placed.
The output file will be named `output.astubx`.
6. Copy the `output.astubx` file from the previous step to
`nullaway/src/main/resources/jspecify-jdk.astubx` under the NullAway repo.
