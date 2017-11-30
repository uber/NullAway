Changelog
=========

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
