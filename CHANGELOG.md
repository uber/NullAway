Changelog
=========
Version 0.5.6
-------------
* Add coverage measurement through coveralls. (#224) 
* Fix empty comment added when AutoFixSuppressionComment is not set. (#225)
* Make JarInfer generated jars fully deterministic by removing timestamps. (#227)

Version 0.5.5
-------------
* Allow for custom Error URLS (#220)
* Fix crash with native methods invoked from initializer (#222)

Version 0.5.4
-------------
* Add AutoFixSuppressionComment flag. (#213)
* [JarInfer] Write to/load from separate astubx model jars (#214)
* Update readme and tooling versions (#217)
* Update to Error Prone 2.3.1 and centralize Java compiler flags (#218)
* [JarInfer] Handler for @Nullable return value annotations (#216)

Version 0.5.3
-------------
* JarInfer: Third-party bytecode analysis (MVP version) (#199)
* Handle @NotNull in hasNonNullAnnotation. (#204)
* Handler for separate Android models jar (#206)
* fix: zip entry size error (#207)
* Small test for restrictive annotations and generics. (#209)
* Create android-jarinfer-models-sdk28 and fix release scripts. (#210)
* JarInfer checks for null tested parameters #211

Note: This is the first release to include jar-infer-cli, jar-infer-lib, and
android-jarinfer-models-sdk28 artifacts

Version 0.5.2
-------------
* Fix NPE in Thrift handler on complex receiver expressions (#195)
* Add ExcludedFieldAnnotations unit tests. (#192) 
* Various crash fixes (#196)
* Fix @NonNull argument detection in RestrictiveAnnotationHandler. (#198)

Version 0.5.1
-------------
* Various fixes for AcknowledgeRestrictiveAnnotations (#194)

Version 0.5.0
-------------
* Breaking change: Warn when castToNonNull method is not passed @NonNull (#191)
* Add -XepOpt:NullAway:AcknowledgeRestrictiveAnnotations config flag. (#189)
  - WARNING: This feature is broken in this release, fixed on 0.5.1
* Add support for LEFT_SHIFT and RIGHT_SHIFT (#188)
* Remove a suppression from a test that doesn't need it. (#183)
* Support Objects.isNull (#179)

Version 0.4.7
-------------
* Clean up some unnecessary state (#168)
* Properly read type use annotations when code is present as a class file (#172)
* Fix NPE inside NullAway when initializer methods use try-with-resources (#177)

Version 0.4.6
-------------
* Fix a couple of Thrift issues (#164)
* Don't report initialization warnings on fields for @ExternalInit classes with 
  no initializer methods (#166)

Version 0.4.5
-------------
* Fix bug with handling Thrift `TBase.isSet()` calls (#161)

Version 0.4.4
-------------
* add UnannotatedClasses option (#160)

Version 0.4.3
-------------
* properly handle compound assignments (#157)
* handle unboxing of array index expression (#158)

Version 0.4.2
-------------
* Upgrade Checker Framework dependency to upstream version 2.5.0 (#150)
* Don't crash on field initialization inside an enum (#146)
* Properly find super constructor for anonymous classes (#147)
* Add a Handler for supporting isSetXXXX() methods in Thrift-generated code (#148)
* Use `@SuppressWarnings` as autofix in a couple more places (#149)

Version 0.4.1
-------------
* Initial RxNullabilityPropagator support for method
  references. (#141)

Version 0.4.0
-------------
* Support for checking uses of method references (#139, #140).  Note
  that this may lead to new NullAway warnings being reported for code
  that previously passed.
* Add support for `Observable.doOnNext` to RxNullabilityPropagator
  (#137)

Version 0.3.7
-------------
* Small bug fix in `@Contract` support (#136)

Version 0.3.6
-------------
* Support for a subset of JetBrains `@Contract` annotations (#129)
* Built-in support for JUnit 4/5 assertNotNull, Objects.requireNonNull
* Fix crash when using try-with-resource with an empty try block. (#135)

Version 0.3.5
-------------
* Support for treating `@Generated`-annotated classes as unannotated (#127) 

Version 0.3.4
-------------
* Support for classes with external initialization (#124)

Version 0.3.3
-------------
* Made dependence on Guava explicit (#120)
* Significantly improved handling of try/finally (#123)

Version 0.3.2
-------------
* Just fixed a Gradle configuration problem

Version 0.3.1 (never made it to Maven Central)
-------------
* Bug fixes (#107, #108, #110, #112)

Version 0.3.0
-------------
* Update library models to require full method signatures rather than
  just method names (#90).  This is an API-breaking change; if you've
  written your own library models, they will need to be updated.
* Support @BeforeEach and @BeforeAll as initializer annotations, and
  @Inject and @LazyInit as excluded field annotations. (#81)
* Support Checker Framework's @NullableDecl annotation (#84)
* Add models for java.util.Deque methods (#86)
* Add model for WebView.getUrl() (#91)

Version 0.2.2
-------------
* minor fixes (#69, #71)

Version 0.2.1
-------------
* Fix bug with accesses of fields from unannotated packages (#67)
* Add models for ArrayDeque (#68)

Version 0.2.0
-------------
* New feature: NullAway now does some checking that `@NonNull` fields
  are not used before the are initialized (#58, #63).  Updating to
  0.2.0 may cause "read before initialized" problems to be detected in
  code that was NullAway-clean before.
* Model `Throwable.getMessage()` as returning `@Nullable`, matching
  the spec.  This may also cause new warnings in code that was
  previously NullAway-clean.

Version 0.1.8
-------------
* Make NullAway's Error Prone dependence compileOnly (#50).  This could help reduce size of annotation processor paths, speeding build times.
* Handle AND, OR, XOR expressions getting autoboxed (#55)
* Handle @Nullable type use annotations (#56)

Version 0.1.7
-------------
* -XepOpt:NullAway:ExcludedClasses accepts package prefixes. (#38)
* Handle unary minus and unary plus (#40)
* Handle prefix increment / decrement (#43)
* add check for unannotated packages when excluding a class (#46)

Version 0.1.6
-------------

* We now check static fields and initializer blocks (#34)
* Fix for lambdas where the functional interface method had `void` return type (#37)

Version 0.1.5
-------------
* Add finer grained suppressions and auto-fixes (#31).  You can
  suppress initialization errors specifically now with
  `@SuppressWarnings("NullAway.Init")` 
* Fix performance issue with lambdas (#29) 
* Add lambda support to the RxNullabilityPropagator handler. (#12)

Version 0.1.4
-------------
* Another lambda fix (#23)

Version 0.1.3
-------------
* Fixes for lambdas (#13, #17)

Version 0.1.2
-------------

* Downgrade Checker Framework due to crash (#7)
* More modeling of Rx operators (#8)

Version 0.1.1
-------------

* Update Checker Framework dependence to pick up bug fix (#4)


Version 0.1.0
-------------

* Initial release
