Changelog
=========
Version 0.10.22
---------------
IMPORTANT: The support for JDK 8 is deprecated in this release and will be removed in
    an upcoming release.

* Fix bug with implicit equals() methods in interfaces (#898)
* Fix crash with raw types in overrides in JSpecify mode (#899)
* Docs fix: Update instructions for Android and our sample app (#900)

Version 0.10.21
---------------
IMPORTANT: This release fixes a crash when running against <2.24.0 release of
    Error Prone (see #894) introduced in NullAway v0.10.20 and another crash related to
    Checker Framework (see #895) introduced in NullAway v0.10.19.

* Fix backwards-incompatible calls to ASTHelpers.hasDirectAnnotationWithSimpleName (#894)
* Downgrade to Checker Framework 3.40.0 (#895)

Version 0.10.20
---------------
* Fix JSpecify support on JDK 21 (#869)
* Build / CI tooling upgrades for NullAway itself:
  - Update to WALA 1.6.3 (#887)
  - Update to Error Prone 2.24.1 (#888)

Version 0.10.19
---------------
* Update to Checker Framework 3.41.0 (#873)
* Extend library models to mark fields as nullable (#878)
  - Main use case is NullAwayAnnotator
* Fix jarinfer cli output determinism (#884)
* Add support for AssertJ as() and describedAs() in AssertionHandler (#885)
* Support for JSpecify's 0.3.0 annotation [experimental]
  - JSpecify: In generics code, get rid of checks for ClassType (#863)
* Update some dependencies (#883)

Version 0.10.18
---------------
* Fix assertion check for structure of enhanced-for loop over a Map keySet (#868)

Version 0.10.17
---------------
* Fix bug with computing direct type use annotations on parameters (#864)
* Model Apache Flink's RichFunction.open as an @Initializer method (#862)
* Support for JSpecify's 0.3.0 annotation [experimental]
  - JSpecify: adding com.google.common to annotated packages in build.gradle (#857)
  - JSpecify: handling the return of a diamond operator anonymous object method caller (#858)
  - Create com.uber.nullaway.generics package (#855)
  - Clarifications and small fixes for checking JSpecify @Nullable annotation (#859)
  - Apply minor cleanups suggested by IntelliJ in generics code (#860)

Version 0.10.16
---------------
NOTE: Maven Central signing key rotated for this release following a revocation.

* Minor cleanup in AccessPathElement (#851)
* Support for JSpecify's 0.3.0 annotation [experimental]
  - JSpecify: handle return types of method references in Java Generics (#847)
  - JSpecify: handle Nullability for lambda expression parameters for Generic Types (#852)
  - JSpecify: Modify Array Type Use Annotation Syntax (#850)
  - JSpecify: handle Nullability for return types of lambda expressions for Generic Types (#854)
* Build / CI tooling for NullAway itself:
  - Update to Gradle 8.4 and Error Prone 2.23.0 (#849)

Version 0.10.15
---------------
* [IMPORTANT] Update minimum Error Prone version and Guava version (#843)
  NullAway now requires Error Prone 2.10.0 or later
* Add Spring mock/testing annotations to excluded field annotation list (#757)
* Update to Checker Framework 3.39.0 (#839) [Support for JDK 21 constructs]
* Support for JSpecify's 0.3.0 annotation [experimental]
  - Properly check generic method overriding in explicitly-typed anonymous classes (#808)
  - JSpecify: handle incorrect method parameter nullability for method reference (#845)
  - JSpecify: initial handling of generic enclosing types for inner classes (#837)
* Build / CI tooling for NullAway itself:
  - Update Gradle and a couple of plugin versions (#832)
  - Run recent JDK tests on JDK 21 (#834)
  - Fix which JDKs are installed on CI (#835)
  - Update to Error Prone 2.22.0 (#833)
  - Ignore code coverage for method executed non-deterministically in tests (#838 and #844)
  - Build NullAway with JSpecify mode enabled (#841)

Version 0.10.14
---------------
IMPORTANT: This version introduces EXPERIMENTAL JDK21 support.
* Bump Checker Framework dependency to 3.38.0 (#819)
  - Note: Not just an internal implementation change. Needed to support JDK 21!
* Treat parameter of generated Record.equals() methods as @Nullable (#825)
* Build / CI tooling for NullAway itself:
  - Fixes Codecov Report Expired error (#821)
  - Updated Readme.md with Codecov link (#823)
  - Remove ASM-related hack in build config (#824)
  - Run tests on JDK 21 (#826)

Version 0.10.13
---------------
* Allow library models to define custom stream classes (#807)
* Avoid suggesting castToNonNull fixes in certain cases (#799)
* Ensure castToNonNull insertion/removal suggested fixes do not remove comments (#815)
* Support for JSpecify's 0.3.0 annotation [experimental]
  - Generics checks for method overriding (#755)
  - Make GenericsChecks methods static (#805)
  - Add visitors for handling different types in generic type invariance check (#806)
* Build / CI tooling for NullAway itself:
  - Bump versions for some dependencies (#800)
  - Update to WALA 1.6.2 (#798)
  - Update to Error Prone 2.21.1 (#797)
  - Enable contract checking when building NullAway (#802)
  - Bump Error Prone Gradle Plugin version (#804)
  - Modify JMH Benchmark Workflow For Shellcheck (#813)
  - Bump gradle maven publish plugin from 0.21.0 to 0.25.3 (#810)
  - Use Spotless to enforce consistent formatting for Gradle build scripts (#809)
  - Remove unnecessary compile dependence for jar-infer-cli (#816)
  - Added Codecov to CI Pipeline (#820)

Version 0.10.12
---------------
Note: This is the first release built with Java 11. In particular, running
    JarInfer now requires a JDK 11 JVM. NullAway is still capable of analyzing JDK 8
    source/target projects, and should be compatible with the Error Prone JDK 9 javac
    just as the release before, but a JDK 11 javac is recommended.
* Update to WALA 1.6.1 and remove ability to build on JDK 8 (#777)
* Fix compatibility issue when building on JDK 17 but running on JDK 8 (#779)
* Fix JDK compatibility issue in LombokHandler (#795)
* Improve auto-fixing of unnecessary castToNonNull calls (#796)
* Support for JSpecify's 0.3.0 annotation [experimental]
  - JSpecify: avoid crashes when encountering raw types (#792)
  - Fix off-by-one error in JSpecify checking of parameter passing (#793)
* Build / CI tooling for NullAway itself:
  - Fix Publish Snapshot CI job (#774)
  - Add step to create release on GitHub (#775)
  - Build the Android sample app on JDK 17 (#776)
  - Update to Error Prone 2.20.0 (#772)
  - Add tasks to run JDK 8 tests on JDK 11+ (#778)
  - Switch to Spotless for formatting Java code (#780)
  - Added GCP JMH Benchmark Workflow (#770)
  - Set concurrency for JMH benchmarking workflow (#784)
  - Disable daemon when running benchmarks (#786)
  - Update to Gradle 8.2.1 (#781)

Version 0.10.11
---------------
* NULL_LITERAL expressions may always be null (#749)
* Fix error in Lombok generated code for @Nullable @Builder.Default (#765)
* Support for specific libraries/APIs:
  - Added support for Apache Validate (#769)
  - Introduce FluentFutureHandler as a workaround for Guava FluentFuture (#771)
* Internal code refactorings:
  - [Refactor] Pass resolved Symbols into Handler methods (#729)
  - Prepare for Nullable ASTHelpers.getSymbol (#733)
  - Refactor: streamline mayBeNullExpr flow (#753)
  - Refactor LibraryModelsHandler.onOverrideMayBeNullExpr (#754)
  - Refactor simple onOverrideMayBeNullExpr handlers (#747)
* Support for JSpecify's 0.3.0 annotation [experimental]
  - JSpecify generics checks for conditional expressions (#739)
  - Generics checks for parameter passing (#746)
  - Clearer printing of types in errors related to generics (#758)
* NullAwayInfer/Annotator data serialization support [experimental]
  - Update path serialization for class files (#752)
* Build / CI tooling for NullAway itself:
  - Update to Gradle 8.0.2 (#743)
  - Fix CI on Windows (#759)
  - Upgrade to Error Prone 2.19.1 (#763)
  - Upgrade maven publish plugin to 0.21.0 (#773)

Version 0.10.10
---------------
* Add command line option to skip specific library models. (#741)
* Support for specific libraries/APIs:
  - Model Map.getOrDefault (#724)
  - Model Class.cast (#731)
  - Model Class.isInstance (#732)
* Internal code refactorings:
  - Refactor code to use Map.getOrDefault where possible (#727)
  - Break loops when result can no longer change (#728)
* Support for JSpecify's 0.3.0 annotation [experimental]
  - JSpecify: initial checks for generic type compatibility at assignments (#715)
  - Add JSpecify checking for return statements (#734)
* NullAwayInfer/Annotator data serialization support [experimental]
  - Refactoring in symbol serialization (#736)
  - Refactoring tabSeparatedToString logic to prepare for serialization version 3 (#738)
  - Update method serialization to exclude type use annotations and type arguments (#735)
* Docs fix: -XepExcludedPaths was added in 2.1.3, not 2.13 (#744)

Version 0.10.9
--------------
* Add support for external init annotations in constructors (#725)
* Ignore incompatibly annotated var args from Kotlin code. (#721)
* Support for specific libraries/APIs:
  - Add Throwable.getCause and getLocalizedMessage() library models (#717)
  - Support more test assertions in OptionalEmptinessHandler (#718)
  - Support isInstanceOf(...) as implying non-null in assertion libraries (#726)
* [Refactor] Avoid redundant Map lookups (#722)
* Build / CI tooling for NullAway itself:
  - Update to Error Prone 2.18.0 (#707)

Version 0.10.8
--------------
* Don't do checks for type casts and parameterized trees in unannotated code (#712)
* Add an initial `nullaway:nullaway-annotations` artifact. (#709)
  - Contains only an implementation of `@Initializer` for now.
* NullAwayInfer/Annotator data serialization support [experimental]
  - Update region selection for initialization errors. (#713)
  - Update path serialization for reported errors and fixes. (#714)
* Build / CI tooling for NullAway itself:
  - Turn up various Error Prone checks (#710)

Version 0.10.7
--------------
(Bug fix release)
* Resolve regression for type annotations directly on inner types. (#706)

Version 0.10.6
--------------
* Handle BITWISE_COMPLEMENT operator (#696)
* Add support for AssertJ (#698)
* Fix logic for @Nullable annotation on type parameter (#702)
* Preserve nullness checks in final fields when propagating nullness into inner contexts (#703)
* NullAwayInfer/Annotator data serialization support [experimental]
  - Add source offset and path to reported errors in error serialization. (#704)
* Build / CI tooling for NullAway itself:
  - [Jspecify] Update test dep to final JSpecify 0.3.0 release (#700)
     = Intermediate PRs: 0.3.0-alpha-3 (#692), 0.3-alpha2 (#691)
  - Update to Gradle 7.6 (#690)


Version 0.10.5
--------------
* Report more unboxing errors in a single compilation (#686)
* Remove AccessPath.getAccessPathForNodeNoMapGet (#687)
* NullAwayInfer/Annotator data serialization support [experimental]
  - Fix Serialization: Split field initialization region into smaller regions (#658)
  - Add serialization format version to fix serialization output (#688)
  - Fix serialization field region computation bug fix (#689)
* EXPERIMENTAL support for JSpecify's 0.3.0 annotations
  - [Jspecify] Update tests to JSpecify 0.3.0-alpha-1 (#673)
  - [Jspecify] Add checks for proper JSpecify generic type instantiations (#680)
  - (Note: Annotation support for generics is not complete/useful just yet)

Version 0.10.4
--------------
(Bug fix release)
* Fix LibraryModels recording of dataflow nullness for Map APs (#685)
* Proper checking of unboxing in binary trees (#684)
* Build / CI tooling for NullAway itself:
  - Bump dependency versions in GitHub Actions config (#683)

Version 0.10.3
--------------
* Report an error when casting @Nullable expression to primitive type (#663)
* Fix an NPE in the optional emptiness handler (#678)
* Add support for boolean constraints (about nullness) in Contract annotations (#669)
* Support for specific libraries/APIs:
  - PreconditionsHandler reflects Guava Preconditions exception types (#668)
  - Handle Guava Verify functions (#682)
* Dependency Updates:
  - checkerframework 3.26.0 (#671)
* Build / CI tooling for NullAway itself:
  - Build and test against Error Prone 2.15.0 (#665)
  - Bump Error Prone and EP plugin to 2.16 (#675)

Version 0.10.2
--------------
* Make AbstractConfig collection fields explicity Immutable (#601)
* NullAwayInfer/Annotator data serialization support [experimental]
  - Fix crash in fixserialization when ClassSymbol.sourcefile is null (#656)

Version 0.10.1
--------------
This is a bug-fixing release for a crash introduced in 0.10.1 on type.class
(for primitive type = boolean/int/void/etc.).
* Fix crash when querying null-markedness of primitive.class expressions (#654)
* Fix for querying for generated code w/ primitive.class expressions. (#655)

Version 0.10.0
--------------
* Switch parameter overriding handler to use Nullness[] (#648) [performance opt!]
* EXPERIMENTAL support for JSpecify's 0.3.0 @NullMarked and @NullUnmarked semantics
  - [JSpecify] Support @NullMarked on methods. (#644)
  - [JSpecify] Support @NullUnmarked. (#651)
  - Allow AcknowledgeRestrictiveAnnotations to work on fields (#652)
* Dependency Updates:
  - Update to WALA 1.5.8 (#650)
* Build / CI tooling for NullAway itself:
  - Update to Gradle 7.5.1 (#647)
  - Add Gradle versions plugin and update some "safe" dependencies (#649)

Version 0.9.10
--------------
* Improved support for library models on annotated code:
  - Make library models override annotations by default. (#636)
  - Generalize handler APIs for argument nullability on (un-)annotated code (#639)
    - [Follow-up] Optimizations for parameter nullness handler / overriding (#646)
  - Generalize handler APIs for return nullability on (un-)annotated code (#641)
* Support for specific libraries/APIs:
  - Add library model for Guava's Closer.register (#632)
  - Support for Map.computeIfAbsent(...) (#640)
* NullAwayInfer/Annotator data serialization support [experimental]
  - Augment error serializarion info (#643)
* Dependency Updates:
  - Update to Checker Framework 3.24.0 (#631)
* Fix javadoc and CONTRIBUTING.md typos (#642)

Version 0.9.9
-------------
* Fix handling of empty contract arguments (#616)
* Fix inconsistent treament of generated code in RestrictiveAnnotationHandler (#618)
* Allow Library Models to override annotations. (#624)
* Allow tracking field accesses outside the this instance and static fields (#625)
* Add Guava 31+ support by treating @ParametricNullness as @nullable (#629)
* Refactoring:
  - Clean up: Remove method parameter protection analysis (#622)
  - Clean up: Remove nullable annotation configuration in fix serialization. (#621)
* Build / CI tooling for NullAway itself:
  - Add a microbenchmark for type inference / dataflow (#617)

Version 0.9.8
-------------
* Fix false positive involving type parameter @Nullable annotations (#609)
* Add config option to register custom @Generated annotations. (#600)
* Treat Void formal arguments as @Nullable (#613)
* Generalize support for castToNonNull methods using library models (#614)
* Support for specific libraries/APIs:
  - Support for Preconditions.checkArgument (#608)
  - Model for com.google.api.client.util.Strings.isNullOrEmpty (#605)
* Refactoring:
  - Cleanups to AccessPath representation and implementation (#603)
  - Clean-up: Remove unused fix suggestion code. (#615)
* Dependency Updates:
  - Update to Checker Framework 3.22.2 (#610)
* Build / CI tooling for NullAway itself:
  - Add NullAway 0.9.7 as a JMH benchmark (#602)
  - Update to Error Prone 2.14.0 (#606)

Version 0.9.7
-------------
* Allow zero-argument static method calls to be the root of an access path (#596)
* Support for specific libraries/APIs
  - Add support for Optional.isEmpty() (#590)
  - Model System.console() as returning @nullable (#591)
* JDK 17+ support improvements
  - Add a test of binding patterns (#583)
* JSpecify support:
  - Move JSpecify tests to correct package (#587)
* NullAwayInfer/Annotator data serialization support [experimental]
  - Fixes line breaks and tabs in serializing errors. (#584)
  - Using flatNames for LocalType/anon. classes in fix serialization (#592)
  - Fixes to computing class and method info for error serialization (#599)
* Dependency updates
  - [JarInfer] Update Apache Commons IO dependency. (#582)
  - Update to Checker Framework 3.21.3 (#564)
* Build / CI tooling for NullAway itself:
  - NullAway now builds with NullAway (#560)
  - Switch to using gradle-build-action (#581)
  - Compile and test against Error Prone 2.12.0 (#585)
  - Enabled a few more EP checks on our code (#586)
    (Note: the `Void` related portion of this changes was reverted)
  - Update to Gradle 7.4.2 (#589)
  - Update to Error Prone 2.13.1 and latest Lombok (#588)

Version 0.9.6
-------------
* Initial support for JSpecify's @NullMarked annotation (#493)
  - Fix bug in handling of TreatGeneratedAsUnannotated (#580)
    (Note: this bug is not in any released NullAway version, but was temporarily
     introduced to the main/master branch by #493)
* Improved tracking of map nullness
  - Improve nullness tracking of map calls in the presence of type casts (#537)
  - Reason about iterating over a map's key set using an enhanced for loop (#554)
  - Reason about key set iteration for subtypes of Map (#559)
  - Add support for Map.putIfAbsent. (#568)
* Add support for data serialization for Nullaway data for UCR's NullAwayAnnotator
  - Serialization of Type Change Suggestions for Type Violations (#517)
  - Measurement of Method protection against nullability of arguments (#575)
  - Enhanced Serialization Test Infrastructure (#579)
  - Field initialization serialization (#576)
* Build / CI tooling for NullAway itself:
  - Enable parallel builds (#549) (#555)
  - Add dependence from coveralls task to codeCoverageReport (#552)
  - Switch to temurin on CI (#553)
  - Separating NullAwayTests into smaller files (#550)
  - Require braces for all conditionals and loops (#556)
  - Enable build cache (#562)
  - Fix JarInfer integration test on Java 11 (#529)
  - Get Android sample apps building on JDK 11 (#531)
  - Limit metaspace size (#563)
  - Update CI jobs (#565)
  - Set epApiVersion for jacoco coverage reporting (#566)
  - Compile and test against Error Prone 2.11.0 (#567)
  - Fix EP version for jacoco coverage step (#571)
  - Update to latest Google Java Format (#572)

Version 0.9.5
-------------
* JDK17 support improvements:
  - Fix crash with switch expression as a lambda body (#543, follow up: #545)
  - Better fix for crash on member selects inside module-info.java (#544)
* Bump Guava dependency to 24.1.1 (#536)
* Build / CI tooling for NullAway itself:
  - Bump AutoValue and AutoService versions (#538)
  - Add task to run NullAway on itself (#542)
  - Add test case for unsound map reassignment handling (#541)

Version 0.9.4
-------------
* Fix crash with fully-qualified names in module-info.java import (#534)

Version 0.9.3
-------------
IMPORTANT: This version introduces EXPERIMENTAL JDK17 support.
  There is a known crash on lambdas with switch expressions as body
  (see #524). Best current workaround is to
  `@SuppressWarnings("NullAway")` on the enclosing method
* Improve reporting of multiple parameter errors on a single method call (#503)
* Support compile-time constant field args in method Access Paths (#504)
* Add basic library support for grpc Metadata through GrpcHandler (#505)
* Fix soundness bug with dereference of ternary expressions (#516)
* Add support for switch expressions (#520) [JDK 17]
* Allow setting custom Nullable Annotation via Error Prone CLI flags (#522)
* Add JarInfer models for Android SDK 31 (Android 12) (#532)
* Build / CI tooling for NullAway itself:
  - Prevent JMH tests from running on pre-v11 JDKs (#492)
  - Bump to Error Prone 2.8.1 (#494), 2.9.0 (#497), and 2.10.0 (#507)
  - Docs: Fix a broken link in README.md (#495)
  - Update to Gradle 7.2 (#496), 7.3.1 (#509), and 7.3.3 (#530)
  - Add Autodispose benchmark (#498)
  - Bump jmh plugin to 0.6.6 (#500)
  - Bump to Checker dataflow 3.20.0 (#510)
  - CI tests for JDK 17 (#512)
  - Some fixes to GitHub Actions config (#514)
  - Make jar-infer-lib tests pass on JDK 11 (#523)
  - Extra tests for all DummyOptionsConfig's methods (#525)
  - Pull jmh Gradle plugin version to top level (#526)
  - Add tests for JDK 16+ records (#527)
  - Support for Coveralls on multiple modules (#521)
  - Changes to avoid re-running Gradle tasks unnecessarily (#528)

Version 0.9.2
-------------
* Allow specifying custom names for Contract annotations (#476)
* Use shaded Checker Framework dataflow artifact made for NullAway (#485)
* Bump Checker dataflow to 3.16.0 (#490)
* Library Models:
  - Add library model for java.nio.file.Path.getParent() (#464)
  - Default models support for Spring's Autowired (#477)
  - Models for `Objects.requireNonNull()` with `Supplier` (#483)
* Build / CI tooling for NullAway itself:
  - Small Gradle build cleanup (#469)
  - Allow Error Prone API version to be configured via a property (#470)
  - Also test NullAway on Error Prone 2.6.0 (#471)
  - Check our code with Error Prone 2.6.0 (#472) [temporary, see below]
  - Check code with Error Prone 2.7.1 (#480)
  - Update to Gradle 7.0.2 (#481) then 7.1 (#486)
  - Add a jmh module for benchmarking (#487, #489)
  - Test on CI with Error Prone 2.8.0 (#491)

Version 0.9.1
--------------
* Add baseline support for (Java 15) records (#377)
* Multiple build tooling fixed:
  - Update Gradle to 6.8.3 (#451)
  - Gradle: switch to java-library plugin where possible (#455)
  - Switch from mvn-push script to gradle-maven-publish-plugin (#457)
  - Fix publication of fat jar for jar-infer-cli. (#461)
* Add JarInfer models for Android 11 (SDK 30) (#460)

Version 0.9.0
--------------
* IMPORTANT: Error Prone minimum version moved to 2.4.0 (#447)
  - This allows compatibility with Error Prone 2.5.1 by
    moving to updated APIs.
  - Remove Checker Framework shadow config from nullaway module (#449)
* `@Contract` annotations are now checked (#312) (#428) (#450)
* Add support for @RequiresNonnull/@EnsuresNonnull annotations (#423)
* [Fix] Handle WideningConversionNode in Map key specifiers (#415)
* [Fix] Try to handle lombok.Builder without crashing. (#414)
* [Fix] Ignore library models return nullability on first-party code (#446)
* Update to Checker Dataflow dependency to 3.6.0 (#416)
* Library Models:
  - Add library model for TextView.getLayout() (#418)
  - Add library model for Service.onStartCommand (#419)
  - Models for common Spring/Spark/Apache utility classes (#436)
  - Add support for jakarta.inject-api (#439)
* Build / CI tooling for NullAway itself:
  - Update to Gradle 6.6.1 (#420)
  - Switch CI to GitHub Actions (#440) (#442) (#450)

Version 0.8.0
--------------
* Improve suppression of subcheckers, using full AST path (#392)
* Support null implies false library models (#394)
* Make `@ChecksForNull` an alias for `@Nullable` (#397)
* Fix: android-jar.py's exit code. (#399)
* Upgrade Error Prone dependencies to 2.4.0. (#400)
  - And fix detected issues (#403, #404)
* Allow library models of the form null param -> null return (#407)
* Make excluded class annotations work on nested classes (#412)
* Improved Map handling: Strings and integers. (#413)
* Also `-SNAPSHOT` builds are being published correctly again (#409)
* New android-jarinfer-models-sdk29 artifact for Android 10

Version 0.7.10
--------------
* Add Java 8 streams nullness-propagation support (#371)
* Give line numbers for uninitialized fields when reporting error on an initializer (#380)
* Include outer$inner class name when reporting field init errors (#375)
* Update to Gradle 6.1.1 (#381)
* Add @MonotonicNonNull as lazy initialization annotation. (#383)
* Add default library model for CompilationUnitTree.getPackageName() (#384)
* Improve matching of native Map methods (#390)
  - Fixes an IndexOutOfBoundsException checker crash

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
