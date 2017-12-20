Changelog
=========

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
