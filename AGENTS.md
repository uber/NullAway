### Testing

To run only the tests for the main NullAway module, run `./gradlew :nullaway:test`.  Run _only_ these tests unless you 
are specifically asked to run a test in a different module.  If you want to run a single 
test class or method within that module, you can use the `--tests` flag. For example, to run all tests in the 
`com.uber.nullaway.NullAwayTest` class, you would run:

```
./gradlew :nullaway:test --tests "com.uber.nullaway.NullAwayTest"
```