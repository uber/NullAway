# Testing

To run only the tests for the main NullAway module, run `./gradlew :nullaway:test`. Run _only_ these tests unless you
are specifically asked to run a test in a different module. If you want to run a single
test class or method within that module, you can use the `--tests` flag. For example, to run all tests in the
`com.uber.nullaway.NullAwayTest` class, you would run:

```bash
./gradlew :nullaway:test --tests "com.uber.nullaway.NullAwayTest"
```

Do _not_ try to run multiple Gradle build commands in parallel; it is not supported and often leads to a failure.

Generally, whenever you run a test suite, it's best to also check that `./gradlew :nullaway:buildWithNullAway`
also passes; that runs NullAway checking on itself.  You don't need to run this after every targeted test run.

# Code coverage

`./gradlew :nullaway:test` reports diff coverage for changed executable lines and branches; filtered test runs only
reflect the tests actually executed, so run the module's full suite before treating uncovered code as a missing test.

By default the diff is measured against the configured upstream/base; for stacked PRs, set `-PdiffBase=<ref>` to the
parent PR or commit so only the current layer is measured.  The same property sets the base for mutation testing.

If an uncovered line is intentionally unreachable or not worth testing, acknowledge the reviewed exception with
`// diff-coverage: ignore -- <reason>`; it remains counted as uncovered but is removed from the actionable list.

# Mutation testing

Coverage shows that a test executed a changed line, not that a test would fail if the line were wrong.  After you add
or change tests for a change to `nullaway/src/main`, run `./gradlew :nullaway:pitestDiff`.  It runs PIT over the lines
changed since the base ref and prints each mutant no test detected.  It is not part of `test`.  Its cost grows with
the number of mutants on changed lines: PIT took 49 s for 59 mutants and 2.5 minutes for 208, about 20 s of each in
the coverage pass that runs the whole suite once.  To shorten a run, narrow the tests with
`-PpitestTargetTests='com.uber.nullaway.jspecify.*'`, and confirm a survivor against the full suite before acting on
it.

The console lists each live mutant under its file and line, with the tests that reach the line; a new test for the
mutant usually belongs beside them.  The whole report is in `nullaway/build/reports/pitest-diff/survivors.txt`.  Read
`pit.log` only when the task failed.  For each mutant, decide which case it is: the tests miss behavior the change
defines (add or strengthen a test), the mutant is equivalent and no caller can observe the difference (say so in the
PR), or you cannot tell (report it as unresolved).  PIT generates no mutant on a line that calls `Verify` or
`Preconditions`.

# Changelog

Our `CHANGELOG.md` file should be formatted as follows:

* Link to PRs just via their number, e.g. `#1234`, not a full GitHub URL
* Don't credit `@msridhar` in changelog entries, but credit all other contributors
* Under maintenance, list sub-bullets with a `-` rather than a `*`

# Coding style

Do not add top-level `@Nullable` annotations on local variables.  NullAway infers the nullability of local variables and
ignores these explicit annotations.

Whenever you add a non-trivial method, add Javadoc, even if it's a private method.  JUnit test methods do not require Javadoc.

You do _not_ need to run `./gradlew spotlessJavaCheck` to check formatting.  We have a pre-commit hook that
automatically formats code before it is committed.


## Commit messages

Always end commit messages, including drafts, with `Assisted-by: <tool> (<model-id>)`, never `Co-Authored-By:`.
Keep existing trailers and add yours when amending someone else's commit.

# Pull requests

Pull requests are squash-merged: the description becomes the body of the single commit that lands on the target branch,
so write it as the commit message for all the changes in the PR.  Headlines are imperative and in sentence case, with no
Conventional Commits prefix and no trailing period, e.g. `Preserve nested nullness in enhanced for var types`; the squash
merge appends the PR number.
