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

# Pull requests

Pull requests are squash-merged: the description becomes the body of the single commit that lands on the target branch,
so write it as the commit message for all the changes in the PR.  Headlines are imperative and in sentence case, with no
Conventional Commits prefix and no trailing period, e.g. `Preserve nested nullness in enhanced for var types`; the squash
merge appends the PR number.
