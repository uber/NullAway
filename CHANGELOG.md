Changelog
=========

Version 0.7.9
-------------
* Multiple dependency upgrades
  - Gradle to 5.6.2. (#362)
  - WALA to 1.5.4 (#337)
  - Checker Dataflow to 3.0.0 (#369)
* Added OPTIONAL_CONTENT synthetic field to track Optional  emptiness (#364)
  - With this, `-XepOpt:NullAway:CheckOptionalEmptiness` should be 
    ready for use.
* Handle Nullchk operator (#368)

Version 0.7.8
-------------
* Added NullAway.Optional suppression (#359) 
* [JarInfer] Ignore non-public classes when inferring annotations. (#360) 

Version 0.7.7
-------------
* [Optionals] Support Optional isPresent call in assertThat (#349)
* Preconditions checkNotNull support, added missing cases. (#355)
* [JarInfer] Use Android Nullable/NonNull annotations for AARs (not javax) (#357)

Version 0.7.6
-------------
* Library models for guava's AsyncFunction (#328)
* Annotate StringUtils.isBlank() of org.apache.commons (lang & lang3) (#330)
* Adding support for Aar-to-aar transformation (#334)
* Add support for @RecentlyNullable and @RecentlyNonNull (#335)
* Update to Gradle 5.5.1 (#336)
* Don't compute frames on bytecode writting in JarInfer (#338)
* Use exact jar output path when possible in JarInfer (#339)
* Avoid adding redundant annotations during bytecode rewriting in JarInfer (#341)
* Handle cases when there are no annotations on methods or parameters in JarInfer (#342)
* Fix #333 Nullaway init suppression issue (#343)
* Add option to JarInfer to deal with signed jars (#345)
* Fix #344 onActivityCreated known initializer (#346)
* Skip read-before-init analysis for assert statements (#348)

Version 0.7.5
------------
* Allow models to override @nullable on third-party functional interfaces (#326)
  - Defines Guava's Function and Predicate as @NonNull->@NonNull
    by default.

Version 0.7.4
-------------
* Add support for Jar to Jar transformation to JarInfer (#316)
* Refactor the driver and annotation summary type in JarInfer (#317)
* Minor refactor and cleanup in JarInfer-lib (#319)
* Different approach for param analysis (#320)
* Fix @NullableDecl support (#324) 
* Treat methods of final classes as final for initialization. (#325)

Version 0.7.3
-------------
* Optional support for assertThat(...).isNotNull() statements (#304)
* Fix NPE in AccessPathElement.toString() (#306)
* Add tests for optional emptiness support with Rx (#308)
* Support for assertThat in JUnit and Hamcrest. (#310)
* Add support for CoreMatchers and core.IsNull in hamcrest. (#311)
* Make class-level caches for InferredJARModelsHandler instance fields. (#315)

Version 0.7.2
-------------
* Install GJF hook using a gradle task, rather than a gradlew hack (#298). 
* Nullable switch expression support (#300).
* Upgrade to Error Prone 2.3.3 (#295).
Update Gradle, Error Prone plugin, and Android Gradle Plugin (#294).
Add support for UNSIGNED_RIGHT_SHIFT (#303).

Version 0.7.1
--------------
* Remove warning about @nullable var args (#296).

Version 0.7.0
--------------
* Added Optional emptiness handler (#278).
  `-XepOpt:NullAway:CheckOptionalEmptiness=true` to enable (experimental) support for `Optional` emptiness.
* Improved (partial but sound-er) varargs support (#291).
* Refactor for ErrorMessage class use (#284).
* Custom path to Optional class for Optional emptiness handler (#288).
* Add support for methods taking literal constant args in Access Paths. (#285).

Version 0.6.6
---------------
This only adds a minor library fix supporting Guava's Preconditions.checkNotNull with an error message 
argument (#283)

Version 0.6.5
---------------
* Various fixes for generating @SuppressWarnings (#271)
* Improved error message now doesn't tell users to report NullAway config errors to Error Prone  (#273)
* Adding support for Activity and Fragment coming from the support libraries (#275)
* Library models fixes (#277)
* Add Fragment.onViewCreated as a known initializer. (#279)

Version 0.6.4
---------------
* Initial support for JDK 11 (#263).  Core NullAway should be working, but JarInfer does not yet work.
* Disable JarInfer handler by default (#261).  `-XepOpt:NullAway:JarInferEnabled=true` is now required to enable the JarInfer handler.
* Add models for Apache StringUtils isEmpty methods (#264)
* Optimize library model lookups to reduce overhead (#265)

Version 0.6.3
-------------
* Fix handling of enhanced for loops (#256)

Version 0.6.2
-------------
* Handle lambda override with AcknowledgeRestrictiveAnnotations (#255)
* Handle interaction between AcknowledgeRestrictiveAnnotations and TreatGeneratedAsUnannotated (#254)

Version 0.6.1
-------------
* Enable excluded class annotations to (mostly) work on inner classes (#239)
* Assertion of not equal to null updates the access path (#240) 
* Update Gradle examples in README (#244)
* Change how jarinfer finds astubx model jars. (#243)
* Update to Error Prone 2.3.2 (#242)
* Update net.ltgt.errorprone to 0.6, and build updates ((#248)
* Restrictive annotated method overriding (#249) 
   Note: This can require significant annotation changes if 
   `-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true` is set.
   Not a new minor version, since that option is false by default.
* Fix error on checking the initTree2PrevFieldInit cache. (#252) 
* Add support for renamed android.support packages in models. (#253)

Version 0.6.0
-------------
* Add support for marking library parameters as explicitly @Nullable (#228)
* De-genericize NullnessStore (#231)
* Bump Checker Framework to 2.5.5 (#233)
* Pass nullability info on enclosing locals into dataflow analysis for 
  lambdas and anonymous / local classes (#235)

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
