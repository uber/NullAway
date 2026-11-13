Contributing to NullAway
=======================

Uber welcomes contributions of all kinds and sizes. This includes everything from simple bug reports to large features.

Workflow
--------

We love GitHub issues!

For small feature requests, an issue first proposing it for discussion or demo implementation in a PR suffice.

For big features, please open an issue so that we can agree on the direction, and hopefully avoid investing a lot of time on a feature that might need reworking.

Small pull requests for things like typos, bug fixes, etc are always welcome.

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

`./gradlew :<module>:pitestDiff -Pcribro.pitestTargetTests=<test classes>` runs [PIT](https://pitest.org/) over the lines your branch changed in the module's main sources and prints each mutant no test detected. It is not part of `test` or `check`, and a surviving mutant never fails the build. PIT runs only the tests of the module whose code it mutates. The tests in `jdk-recent-unit-tests`, `guava-recent-unit-tests`, `jar-infer/nullaway-integration-test`, and `jdk-annotations/jdk-integration-test` exercise `nullaway/src/main`, and the tests of `jar-infer/jar-infer-lib` and `jdk-annotations/astubx-generator` exercise `StubxWriter` in `library-model/library-model-generator`; none of them runs under the `pitestDiff` of the module whose code they test. PIT generates no mutant on a line that calls `Verify` or `Preconditions`, so a condition written inside such a call stays untested.

`-PenableJacoco=true` also instruments the `testJdk*` tasks, for `./gradlew codeCoverageReport`. On CI, the Linux job sets it and the macOS and Windows jobs set `-Pcribro.diffCoverageEnabled=false`, so their tests run without JaCoCo.

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
