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

`./gradlew :<module>:test` prints which of the lines your branch changed the run did not execute, and which changed branches it took only one way. The report is advisory: it fails the build only when `-PdiffCoverageBase` names a ref that does not resolve, with `diffCoverageBase <ref> does not resolve to a commit`. It covers only the tests the run executed, so run the module's whole suite before you read an uncovered line as a missing test.

The changed lines are the ones changed since the merge base of `HEAD` and the base ref. The base ref is `-PdiffCoverageBase=<ref>` when you set it. Otherwise it is the branch's upstream, or `origin/master` when the upstream does not exist or is the branch's own pushed copy, as in a fork. For a stacked pull request, set `-PdiffCoverageBase` to the parent branch or commit so that only the current layer counts.

To acknowledge a line you decided not to test, end it with `// diff-coverage: ignore -- <reason>`. The line still counts as unexecuted in the totals, but it moves from the list of work to a list of acknowledged lines. The marker has to follow `//`, `/*`, or `*` on the line it acknowledges.

The console shows at most 10 files, 10 rows per file, and 50 rows in total. `<module>/build/reports/diff-coverage/test.txt` holds the whole report, and the JaCoCo XML is in `<module>/build/reports/jacoco/test/jacocoTestReport.xml`.

| Property | Default | Effect |
| --- | --- | --- |
| `-PdiffCoverageBase=<ref>` | upstream, or `origin/master` | The ref whose merge base with `HEAD` decides which lines count as changed |
| `-PdiffCoverageMaxFiles=<n>` | `10` | Files the console describes |
| `-PdiffCoverageMaxLinesPerFile=<n>` | `10` | Rows per file |
| `-PdiffCoverageMaxLines=<n>` | `50` | Rows in the whole block |
| `-PdiffCoverageShowAll` | off | Prints the whole report to the console |
| `-PdiffCoverageEnabled=false` | on locally, off on CI | Runs the `test` task without JaCoCo, about 20% faster, and skips the report |
| `-PenableJacoco=true` | unset | Instruments every test task, including `testJdk*`, for `./gradlew codeCoverageReport`; with any value set, the diff coverage report runs only as `./gradlew :<module>:diffCoverage` |

On CI, where the `CI` environment variable is `true`, tests run without JaCoCo unless the job sets `-PenableJacoco=true`.

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
