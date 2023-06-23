IMPORTANT: Make sure you are using a JDK 8 JVM by checking `java -version` before any
of the steps below.  If you run the steps below on a JDK 11+ JVM, that will break Java
8 support, as the released jars will only run on JDK 11.  We do not target Java 8 when
building on JDK 11 since Error Prone has required Java 11 since version 2.11.0.

(Recommended, but optional) Update JarInfer Android SDK Models
==============================================================

 1. Change the version in `gradle.properties` to a non-SNAPSHOT version and `./gradlew build`.
 2. Get a copy of the AOSP `framework_intermediates` for the corresponding Android version.
    2a. At Uber? http://t.uber.com/aosp_framework_intermediate
    2b. Elsewhere? You can still build the corresponding AOSP version and look for
        out/target/common/obj/JAVA_LIBRARIES/**
 3. (first time) `cp jar-infer/scripts/android-jar.conf.template jar-infer/scripts/android-jar.conf`
 4. Set the correct paths and versions in `android-jar.conf`
 5. `rm jar-infer/android-jarinfer-models-sdk28/src/main/resources/jarinfer.astubx` (for SDK 28)
 6. `python jar-infer/scripts/android-jar.py`
 7. Continue to release instructions below


Releasing
=========

 1. Change the version in `gradle.properties` to a non-SNAPSHOT version.
 2. Update the `CHANGELOG.md` for the impending release.
 3. Update the `README.md` with the new version.
 4. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
 5. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
 6. `./gradlew clean publish --no-daemon --no-parallel`
 7. Update the `gradle.properties` to the next SNAPSHOT version.
 8. `git commit -am "Prepare next development version."`
 9. `git push && git push --tags`
 10. Visit [Sonatype Nexus](https://oss.sonatype.org/) and promote the artifact.
 11. Go to [this page](https://github.com/uber/NullAway/releases/new) to create a new release on GitHub, using the release notes from `CHANGELOG.md`.
