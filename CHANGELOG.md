Changelog
=========

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
