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
./jdk-javac-plugin/regenerate-jspecify-jdk.sh 
```

The script fetches a shallow checkout of the JSpecify JDK at the pinned
`JDK_REVISION` in the script. To reuse an existing clean checkout at that
revision, add `--jdk-dir /path/to/jdk`. You can pass `--boot-jdk /path/to/boot-jdk`
to give a path to the boot JDK to use to bootstrap the build;
without it, JDK configure searches for a compatible installed JDK.
Configuration, build output, and JSON files go into a fresh temporary directory.
Failed runs retain that directory and their logs; use `--keep-temp` to retain it
after successful runs as well. Use `--verbose` to stream command output while
also saving it to the logs.  If all succeeds, the
`nullaway/src/main/resources/jspecify-jdk.astubx` file will be overwritten with
the new version.
