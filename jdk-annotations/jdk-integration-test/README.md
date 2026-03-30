These integration tests for the javac plugin work as follows.  We have two versions of code used as a "library" for
input, in the sibling `test-annotated` and `test-unannotated` modules.  For the `test-annotated` code, we generate an
astubx using the javac plugin (to generate JSON) and the astubx generator (to generate astubx from the JSON).  Then,
when building the jar for the `test-unannotated` code, we include the generated astubx in the jar file.  This module
depends only on the `test-unannotated` module, so the tests are run against the unannotated code, but with the astubx
available on the classpath.  This allows us to test that the javac plugin is correctly generating the astubx and that
the astubx is correctly being loaded and used by NullAway.
