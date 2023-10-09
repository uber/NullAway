(For testing only) Publishing an unsigned LOCAL build
=====================================================
By default, we set `RELEASE_SIGNING_ENABLED=true` in `gradle.properties`, which means
published builds must be signed unless they are for a `SNAPSHOT` version.  To publish
a non-`SNAPSHOT` build locally without signing (e.g., a `LOCAL` version), use the
following command:

```bash
ORG_GRADLE_PROJECT_RELEASE_SIGNING_ENABLED=false ./gradlew publishToMavenLocal
```

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
 3. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
 4. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
 5. `./gradlew clean publish`
 6. Update the `gradle.properties` to the next SNAPSHOT version.
 7. `git commit -am "Prepare next development version."`
 8. `git push && git push --tags`
 9. Visit [Sonatype Nexus](https://oss.sonatype.org/) and promote the artifact.
 10. Go to [this page](https://github.com/uber/NullAway/releases/new) to create a new release on GitHub, using the release notes from `CHANGELOG.md`.
