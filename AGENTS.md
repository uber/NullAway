# Testing

To run only the tests for the main NullAway module, run `./gradlew :nullaway:test`. Run _only_ these tests unless you
are specifically asked to run a test in a different module. If you want to run a single
test class or method within that module, you can use the `--tests` flag. For example, to run all tests in the
`com.uber.nullaway.NullAwayTest` class, you would run:

```bash
./gradlew :nullaway:test --tests "com.uber.nullaway.NullAwayTest"
```

Do _not_ try to run multiple Gradle build commands in parallel; it is not supported and often leads to a failure.

# Changelog

Our `CHANGELOG.md` file should be formatted as follows:

* Link to PRs just via their number, e.g. `#1234`, not a full GitHub URL
* Don't credit `@msridhar` in changelog entries, but credit all other contributors
* Under maintenance, list sub-bullets with a `-` rather than a `*`

# Coding style

Do not add top-level `@Nullable` annotations on local variables.  NullAway infers the nullability of local variables and
ignores these explicit annotations.

Whenever you add a non-trivial method, add Javadoc, even if it's a private method.

You do _not_ need to run `./gradlew spotlessJavaCheck` to check formatting.  We have a pre-commit hook that
automatically formats code before it is committed.
