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

Code coverage
-------------

`./gradlew :<module>:test` prints which of the lines your branch changed the run did not execute, and which changed branches it took only one way. The report is advisory: it fails the build only when `-PdiffBase` names a ref that does not resolve, with `diffBase <ref> does not resolve to a commit`. It covers only the tests the run executed, so run the module's whole suite before you read an uncovered line as a missing test.

The changed lines are the ones changed since the merge base of `HEAD` and the base ref. The base ref is `-PdiffBase=<ref>` when you set it. Otherwise it is the branch's upstream, or `origin/master` when the upstream does not exist or is the branch's own pushed copy, as in a fork. For a stacked pull request, set `-PdiffBase` to the parent branch or commit so that only the current layer counts.

To acknowledge a line you decided not to test, end it with `// diff-coverage: ignore -- <reason>`. The line still counts as unexecuted in the totals, but it moves from the list of work to a list of acknowledged lines. The marker has to follow `//`, `/*`, or `*` on the line it acknowledges.

The console shows at most 10 files, 10 rows per file, and 50 rows in total. `<module>/build/reports/diff-coverage/test.txt` holds the whole report, and the JaCoCo XML is in `<module>/build/reports/jacoco/test/jacocoTestReport.xml`.

| Property | Default | Effect |
| --- | --- | --- |
| `-PdiffBase=<ref>` | upstream, or `origin/master` | The ref whose merge base with `HEAD` decides which lines count as changed, for diff coverage and for `pitestDiff` |
| `-PdiffCoverageMaxFiles=<n>` | `10` | Files the console describes |
| `-PdiffCoverageMaxLinesPerFile=<n>` | `10` | Rows per file |
| `-PdiffCoverageMaxLines=<n>` | `50` | Rows in the whole block |
| `-PdiffCoverageShowAll` | off | Prints the whole report to the console |
| `-PdiffCoverageEnabled=false` | on locally, off on CI | Runs the `test` task without JaCoCo, about 20% faster, and skips the report |
| `-PenableJacoco=true` | unset | Instruments every test task, including `testJdk*`, for `./gradlew codeCoverageReport`; with any value set, the diff coverage report runs only as `./gradlew :<module>:diffCoverage` |

On CI, where the `CI` environment variable is `true`, tests run without JaCoCo unless the job sets `-PenableJacoco=true`.

Mutation testing
----------------

A test that executes a changed line can still pass whatever the line does. `./gradlew :<module>:pitestDiff` runs [PIT](https://pitest.org/) over the lines your branch changed in the module's main sources: it changes one instruction at a time on those lines, runs the tests that reach it, and prints each mutant no test detected, with the source line. The changed lines are the ones diff coverage counts, from the same `-PdiffBase`, and `<module>/build/diff/changed-lines.txt` lists them. Mutants on other lines are dropped before any test runs against them, and when no mutant is left, PIT skips the run that records which test reaches which line.

The task is not part of `test` or `check`, and it never fails the build because of a surviving mutant. Its cost is one run of the module's tests to record which test reaches which line, plus a run of the reaching tests for each mutant. On ten cores, PIT took 49 seconds for a change with 59 mutants on its changed lines and 2.5 minutes for one with 208, about 20 seconds of each in that first run; compiling adds the usual build time. The task is skipped when the branch changed no line in the module's main sources. PIT runs the tests on the JDK the `test` task uses. It does not read that task's test filter, so a test that fails in PIT's first run, such as one the filter excludes, is silently left out of the analysis; `./gradlew :<module>:test` still reports a test that is broken.

A surviving mutant is a question, not a defect. It marks behavior the tests do not pin down, or an equivalent change that no caller can observe, or a defensive branch no test can reach. PIT generates no mutant on a line that calls `Verify` or `Preconditions`, since removing such a call changes no diagnostic; this also leaves a condition written inside such a call untested.

The console lists each live mutant under its file and line, with the line's source text and up to three of the tests that reach the line; a test for the mutant usually belongs beside them. The report ends with how to read a live mutant. It shows at most 10 files, 20 lines per file, and 50 lines in total. `<module>/build/reports/pitest-diff/survivors.txt` holds the whole report, `index.html` the PIT HTML report, and `pit.log` everything PIT printed, including a warning that the JUnit 5 plugin is missing: the tests run on JUnit 4 and use JUnit 5 only for assertions, so the warning does not apply. When PIT fails, the task prints the end of `pit.log`.

| Property | Default | Effect |
| --- | --- | --- |
| `-PdiffBase=<ref>` | upstream, or `origin/master` | The ref whose merge base with `HEAD` decides which lines are mutated |
| `-PpitestTargetTests=<glob>` | `*` | Tests allowed to kill a mutant. Narrowing it shortens the run, and a mutant that survives it may still be killed by a test outside it |
| `-PpitestMutators=<group>` | `DEFAULTS` | PIT mutator group: `DEFAULTS`, `STRONGER`, or `ALL` |
| `-PpitestThreads=<n>` | half the cores | Mutants analyzed in parallel |
| `-PpitestMaxFiles=<n>` | `10` | Files the console describes |
| `-PpitestMaxLinesPerFile=<n>` | `20` | Lines per file |
| `-PpitestMaxLines=<n>` | `50` | Lines in the whole console block |
| `-PpitestShowAll` | off | Prints the whole report to the console |

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
