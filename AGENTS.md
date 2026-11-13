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

When you write `CompilationTestHelper` tests, assume Error Prone reports every mismatched line at once rather than
stopping at the first.  Put the cases of one rule, those that report an error and those that do not, in one source file,
with one method per case named for what it checks.

# Code coverage and mutation testing

The cribro plugin, applied in `settings.gradle`, adds diff coverage to `test` and a `pitestDiff` task to every Java
project.  Before you act on an uncovered line or run `pitestDiff` for the first time, run `./gradlew -q cribro`: it
prints the commands, properties, and how to act on each report, from the plugin version the build uses.

`./gradlew :nullaway:test` ends with the changed lines and branches the run did not cover.  A run filtered with
`--tests` reflects only the tests it ran, so run the module's full suite before treating uncovered code as a missing
test.  For stacked PRs, set `-Pcribro.diffBase=<ref>` to the parent PR or commit so only the current layer is measured;
the same property sets the base for mutation testing.

If an uncovered line is intentionally unreachable or not worth testing, acknowledge the reviewed exception with
`// diff-coverage: ignore -- <reason>`; it remains counted as uncovered but is removed from the actionable list.

After you add or change tests for a change to `<module>/src/main`, run
`./gradlew :<module>:pitestDiff -Pcribro.pitestTargetTests=<test classes>` and act on the mutants it reports; it is not
part of `test`.  PIT runs only the tests of the module whose code it mutates, so for a change to `nullaway/src/main`
name test classes under `nullaway/src/test`.

# Third-party projects in CI

When a `Build <project> with snapshot` CI job fails, read `.github/third-party/README.md`.

# Changelog

Our `CHANGELOG.md` file should be formatted as follows:

* Link to PRs just via their number, e.g. `#1234`, not a full GitHub URL
* Don't credit `@msridhar` in changelog entries, but credit all other contributors
* Under maintenance, list sub-bullets with a `-` rather than a `*`

# Performance

NullAway must not repeat work on the same input once per lookup, nesting level, or link of a chain when its cost grows
with the code it checks or the types that code uses, such as a method's dataflow, an inference over a call chain, or a
walk of a bound graph (#1327, #1896, #2093 still open); compute it once and pass it down, or cache it.
`DataFlow.CfgParams` keys a dataflow run on the path to the code being analyzed: by leaf for constructors, initializer
blocks, and field initializers (#2079), and by `TreePath` identity for other methods, lambdas, and anonymous-class
methods (on purpose for lambdas, see its Javadoc). So a new `TreePath` whose leaf is such a method or lambda reruns it,
even when appended to the scanner's path, and so does a path rebuilt with `TreePath.getPath(CompilationUnitTree, Tree)`.
A path to an expression reuses the run keyed on the path `NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer`
returns for it, so it reruns only when that path is a new key. Some existing sites need the rerun and some do not;
#2137 lists those measured so far. Flag a diff that adds a rerun on the same input or reaches a redundant site more
often. Bounds can form cycles (`S extends Settings<? extends S>`, #1760), and inference can return to a `var`
initializer from a later use of the local (#2090), so recursion along bounds or through inference stops at, or cannot
reach, a question already in progress. A fix for repeated work gets a test that fails without it: a timeout for
exponential growth, a count of the repeated work otherwise; a fix for a cycle gets a test that overflows the stack
without it. A new cache or cycle guard also needs a test that shows it hits, because javac and capture conversion create
new `Type` objects for the same type and `TreePath` has no `equals`.

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
