# Patches for third-party projects built in CI

The `compile-other-projects` job in [continuous-integration.yml](../workflows/continuous-integration.yml) builds
caffeine, spring-framework, junit-framework, and spring-boot with the NullAway snapshot. Each project builds at the
commit in the `sha` field of its matrix entry, with the patches in `.github/third-party/<project>/` applied in file name
order.

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

To build the project locally the way CI does, publish NullAway with
`ORG_GRADLE_PROJECT_RELEASE_SIGNING_ENABLED=false ./gradlew publishToMavenLocal`, copy
`.github/workflows/use-snapshot.gradle.kts` into the checkout, and run
`./gradlew --init-script use-snapshot.gradle.kts assemble` there. For Caffeine, install JDK 26 and use
`JAVA_VERSION=26 ./gradlew --init-script use-snapshot.gradle.kts assemble`
to match its ErrorProne CI configuration. Caffeine's default Java 11 target selects JUnit 5; the Java 26 target selects
JUnit 6 and enables NullAway checking of JUnit's annotated API, exposing additional generic-inference diagnostics.

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
