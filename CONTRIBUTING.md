Contributing to NullAway
=======================

Uber welcomes contributions of all kinds and sizes. This includes everything from simple bug reports to large features.

Workflow
--------

We love GitHub issues!

For small feature requests, an issue first proposing it for discussion or demo implementation in a PR suffice.

For big features, please open an issue so that we can agree on the direction, and hopefully avoid investing a lot of time on a feature that might need reworking.

Small pull requests for things like typos, bug fixes, etc are always welcome.

Reporting bugs
--------------

Before filing, search [existing issues, including closed ones](https://github.com/uber/NullAway/issues?q=is%3Aissue). The web UI provides a [bug report form](https://github.com/uber/NullAway/issues/new?template=bug_report.yml). Issues created through `gh` or an API do not automatically inherit the form's required fields or labels, so include these sections in the issue body:


* `### Kind of problem`: false positive, false negative, crash, or other. Before reporting a false negative, confirm the affected code is in scope for your configuration (`AnnotatedPackages` or `OnlyNullMarked`/`@NullMarked`). Check [Deliberate unsoundness](https://github.com/uber/NullAway/wiki/Deliberate-Unsoundness) for errors intentionally not reported and [Error messages](https://github.com/uber/NullAway/wiki/Error-Messages) for diagnostic explanations.
* `### Minimal reproducer`: a small Java sample or test case with imports and nullness annotations, or a small reproducer repository with the command to run it. Compile the sample again after the last edit. Name any third-party library needed and its version. If you cannot reduce the code, include the method around the reported line and say so. If the problem does not depend on code, give the build command that shows it.
* `### Actual behavior`: diagnostics or a stack trace as text, not screenshots. For a false negative, write "no error reported"; for other problems, describe what happens.
* `### Expected behavior`: which diagnostics should or should not appear, and on which expressions, for example "no error on `x.foo()`". For a crash, write "no crash". Link the relevant JSpecify specification or NullAway documentation section when applicable.
* `### NullAway configuration`: compiler options, especially JSpecifyMode, AnnotatedPackages or OnlyNullMarked, custom nullness annotations, and library models if used. See [Configuration](https://github.com/uber/NullAway/wiki/Configuration) for the options. Remove secrets and unrelated build configuration; internal package names may be replaced.
* `### NullAway version`: the version of `com.uber.nullaway:nullaway`, or the commit if using an unreleased build.
* `### Error Prone version`: the version of `com.google.errorprone:error_prone_core` used by your build, not the Gradle or Maven plugin version.
* `### JDK version`: the JDK version used to compile the affected code, and the Java source or release level if different. See [Compatibility](https://github.com/uber/NullAway/wiki/Compatibility) for supported JDK and Error Prone versions.


Put Java samples in fenced `java` blocks, and configuration and compiler output in fenced `text` blocks. Unlike the web form's fields configured with `render: text`, CLI/API submissions need those fences supplied explicitly. Put inline code and annotations such as `@Nullable` in backticks to prevent markup loss and unintended mentions.

Optional sections match the remaining form fields:

* `### Build tool and plugin versions`: relevant plugin versions, such as the Gradle Error Prone or NullAway plugin, and whether this is an Android build.
* `### Reproduces with the latest NullAway release`: "Yes", "No, not tried yet", or "No, I cannot upgrade".
* `### Additional context`: anything the reproducer does not show, related issues, relevant dependencies, annotation processors, workarounds, or the last working NullAway version if this is a regression.

If you have permission, explicitly apply the `bug` label, for example with `gh issue create --label bug`.

Requesting features
-------------------

> [!NOTE]
> Before filing, search [existing issues, including closed ones](https://github.com/uber/NullAway/issues?q=is%3Aissue). Before requesting support for an annotation or a JSpecify feature, check [Supported annotations](https://github.com/uber/NullAway/wiki/Supported-Annotations) and [JSpecify support](https://github.com/uber/NullAway/wiki/JSpecify-Support).

The web UI provides a [feature request form](https://github.com/uber/NullAway/issues/new?template=feature_request.yml). For requests filed through `gh` or an API, include these required sections:

* `### Problem or use case`: what you are trying to do and what NullAway does today that prevents it. If an existing option did not help, say what happened.
* `### Example and expected behavior`: a small Java example with nullness annotations, what NullAway reports today, and what it should report instead. Use a fenced `java` block, and name the NullAway version and whether JSpecifyMode is on. For build integrations, include example configuration instead.

Optionally add `### Proposed solution` for implementation ideas, `### Current workarounds` for existing options, library models, suppressions, or other workarounds and why they are insufficient, and `### Additional context` for relevant links, specifications, dependencies, or related issues. Put inline code and annotations in backticks, and configuration in fenced `text` blocks. If you have permission, explicitly apply the `enhancement` label, for example with `gh issue create --label enhancement`.

DOs and DON'Ts
--------------

* DO format your code using Google Java Format.  An appropriate Git pre-commit hook is automatically installed the first time you run `./gradlew`.
* DO include tests when adding new features. When fixing bugs, start with adding a test that highlights how the current behavior is broken.
* DO keep the discussions focused. When a new or related topic comes up it's often better to create new issue than to side track the discussion.

* DON'T submit PRs that alter licensing related files or headers. If you believe there's a problem with them, file an issue and we'll be happy to discuss it.

Code coverage and mutation testing
----------------------------------

The build applies [cribro](https://github.com/cribro-dev/cribro) in `settings.gradle`. `./gradlew -q cribro` prints how to use it in this build: the commands, the `-Pcribro.*` properties and their defaults, and how to read each report.

`./gradlew :<module>:test` prints which of the lines your branch changed the run did not execute, and which changed branches it took only one way. The report covers only the tests the run executed, so run the module's whole suite before you read an uncovered line as a missing test. For a stacked pull request, set `-Pcribro.diffBase` to the parent branch or commit so that only the current layer counts. To acknowledge a line you decided not to test, end it with `// diff-coverage: ignore -- <reason>`. `-Pcribro.diffCoverageEnabled=false` runs `test` without JaCoCo and skips the report.

`./gradlew :<module>:pitestDiff -Pcribro.pitestTargetTests=<test classes>` runs [PIT](https://pitest.org/) over the lines your branch changed in the module's main sources and prints each mutant no test detected. It is not part of `test` or `check`, and a surviving mutant never fails the build. PIT runs only the tests of the module whose code it mutates. The tests in `jdk-recent-unit-tests`, `guava-recent-unit-tests`, `jar-infer/nullaway-integration-test`, and `jdk-annotations/jdk-integration-test` exercise `nullaway/src/main`, and the tests of `jar-infer/jar-infer-lib` and `jdk-annotations/astubx-generator` exercise `StubxWriter` in `library-model/library-model-generator`; none of them runs under the `pitestDiff` of the module whose code they test. PIT mutates a line that calls `Verify` or `Preconditions` like any other, so a surviving mutant that removes such a call marks a check no test makes fail.

`-PenableJacoco=true` also instruments the `Test` tasks other than `test`, such as `testJdk*`, for `./gradlew codeCoverageReport`; the report gets data from `test` only while diff coverage is on. On CI, the Linux job sets it and the macOS and Windows jobs set `-Pcribro.diffCoverageEnabled=false`, so their tests run without JaCoCo.

Third-party projects in CI
--------------------------

CI builds caffeine, spring-framework, junit-framework, spring-boot, reactor-core, and spring-batch with your NullAway changes. If a new check reports a false positive in one of them, fix it upstream and add the fix as a patch, as described in [.github/third-party/README.md](.github/third-party/README.md).

Guiding Principles
------------------

* We allow anyone to participate in our projects. Tasks can be carried out by anyone that demonstrates the capability to complete them
* Always be respectful of one another. Assume the best in others and act with empathy at all times
* Collaborate closely with individuals maintaining the project or experienced users. Getting ideas out in the open and seeing a proposal before it's a pull request helps reduce redundancy and ensures we're all connected to the decision making process

AI usage
--------

We allow for AI-generated code in PRs, but, we require that contributors have manually reviewed and understood
all the changes.  Always end PR descriptions for AI-assisted changes, including drafts, with
`Assisted-by: <tool> (<model-id>)` (not `Co-Authored-By:`).  If you are a first-time contributor, and you used
AI tools as part of generating your PR, please also disclose in the PR description which AI tools were used and
how they were used, and attest that you have read and understood all the changes in your PR.  Here are two example
disclosures (taken from the [JabRef policy](https://github.com/JabRef/jabref/blob/main/AI_USAGE_POLICY.md)):

<!-- first example -->
> I used Claude to help debug a test failure. I reviewed the suggested fix, tested it locally, and verified it solves the issue without side effects.

<!-- second example -->
> I used ChatGPT to help me understand an error message and suggest debugging steps. I implemented the fix myself after verifying it.
