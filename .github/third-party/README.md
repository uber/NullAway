# Patches for third-party projects built in CI

The `compile-other-projects` job in [continuous-integration.yml](../workflows/continuous-integration.yml) builds
caffeine, spring-framework, junit-framework, spring-boot, reactor-core, and spring-batch with the NullAway snapshot.
Each project builds at the commit in the `sha` field of its matrix entry, with the patches in
`.github/third-party/<project>/` applied in file name order, by running the command in the `build` field.

A patch lets a NullAway pull request pass while the fix it needs waits for review upstream. When a new or stricter check
reports a false positive in one of these projects, fix the code upstream and add the same change here as a patch.

## Adding a patch

1. Open the pull request in the third-party project.
2. Save it as a patch named after the upstream pull request number:

   ```bash
   mkdir -p .github/third-party/<project>
   gh pr diff <number> --repo <owner>/<repo> > .github/third-party/<project>/<number>-<short-name>.patch
   ```

   `<project>` is the `project` field of the matrix entry, for example `spring-framework`.
3. Check that the patch applies to the pinned commit:

   ```bash
   rm -rf /tmp/<project>
   git init /tmp/<project>
   git -C /tmp/<project> fetch --depth 1 <url> <sha>
   git -C /tmp/<project> checkout --detach FETCH_HEAD
   .github/third-party/apply-patches.sh .github/third-party/<project> /tmp/<project>
   ```

4. Commit the patch in the NullAway pull request that needs it.

Patches apply in lexicographic order of their file names, each against the code the earlier ones left. Two patches
that touch the same lines conflict in either order; generate the later one on top of the earlier, or merge them into one
file.

## How a project picks up the snapshot

The job first publishes NullAway to the local Maven repository with `publishToMavenLocal`, then runs the `build`
command in the project checkout. The command sees two things:

- `use-snapshot.gradle.kts`, copied into the checkout. Passed to Gradle with `--init-script`, it replaces the version of
  every `com.uber.nullaway` dependency with the newest one the project's repositories offer. The init script adds the
  local Maven repository, and the snapshot there sorts above every release. It also
  registers a `compileAllJava` task in every project that runs all `JavaCompile` tasks: main, test, and extra source
  sets such as multi-release or test-fixture sources.
- `NULLAWAY_VERSION`, set to `VERSION_NAME` from NullAway's `gradle.properties`, for example `0.14.4-SNAPSHOT`. A Maven
  build passes it to the property its `pom.xml` reads the NullAway version from, such as
  `-Dnullaway.version="$NULLAWAY_VERSION"`.

NullAway runs inside `javac`, so compiling the main and test sources is enough. Prefer a command that stops there:
`compileAllJava` for Gradle, `test-compile` for Maven, with plugins that run before compilation, such as a formatter
check, skipped by their own property.

## Adding a project

A project fits when it already runs NullAway in its build, preferably with `NullAway:JSpecifyMode=true`, and when its
build passes with the snapshot at the commit you pin.

1. Add a matrix entry with `project`, `url`, `branch`, `sha`, and `build`, in that order. The Renovate rule that moves
   `sha` matches the first four fields as consecutive lines, so `build` goes last.
2. For a Maven project, find the property that holds the NullAway version in its `pom.xml` and set it from
   `NULLAWAY_VERSION` in `build`. Pass `-nsu` too, so that Maven takes the snapshot this job published rather than a newer
   one from a snapshot repository the `pom.xml` declares.
3. Build the project locally the way CI does, as described below, and add the project to the list at the top of this
   page.

## Building a project locally

1. Publish NullAway with `ORG_GRADLE_PROJECT_RELEASE_SIGNING_ENABLED=false ./gradlew publishToMavenLocal`.
2. Check out the project at the pinned commit, as in step 3 of [Adding a patch](#adding-a-patch), and copy
   `.github/workflows/use-snapshot.gradle.kts` into the checkout.
3. Set `NULLAWAY_VERSION` to `VERSION_NAME` from `gradle.properties`, and run the `build` command of the matrix entry in
   the checkout.

The job installs JDK 26 and JDK 25 and builds with JDK 25 by default. Caffeine's command sets `JAVA_VERSION=26` to match
its ErrorProne CI configuration. Caffeine's default Java 11 target selects JUnit 5; the Java 26 target selects JUnit 6
and enables NullAway checking of JUnit's annotated API, exposing additional generic-inference diagnostics. reactor-core
also needs JDK 8, 11, 17, and 21 toolchains to configure its build; Gradle downloads them through the foojay toolchain
resolver its settings apply.

## What CI does with each patch

| Patch state | CI | What to do |
| --- | --- | --- |
| Applies | Applies it and builds | Nothing |
| Upstream already contains it | Skips it and annotates the file with `Upstream already contains this patch; delete it` | Delete the file |
| Neither applies nor is upstream | Fails with `The patch neither applies nor is already upstream` | Delete the file if upstream merged a different fix; otherwise rebase the upstream pull request and save the patch again |

The last two states appear when the pinned commit moves, so they show up in the pull request that moves it.

## Moving the pinned commit

Renovate opens a weekly pull request that sets each `sha` to the tip of the branch in the `branch` field. To build
against a newer commit sooner, edit `sha` in the NullAway pull request that needs it.
