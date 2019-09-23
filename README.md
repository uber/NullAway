## NullAway: Fast Annotation-Based Null Checking for Java [![Build Status](https://travis-ci.com/uber/NullAway.svg?branch=master)](https://travis-ci.com/uber/NullAway) [![Coverage Status](https://coveralls.io/repos/github/uber/NullAway/badge.svg?branch=master)](https://coveralls.io/github/uber/NullAway?branch=master)

NullAway is a tool to help eliminate `NullPointerException`s (NPEs) in your Java code.  To use NullAway, first add `@Nullable` annotations in your code wherever a field, method parameter, or return value may be `null`.  Given these annotations, NullAway performs a series of type-based, local checks to ensure that any pointer that gets dereferenced in your code cannot be `null`.  NullAway is similar to the type-based nullability checking in the Kotlin and Swift languages, and the [Checker Framework](https://checkerframework.org/) and [Eradicate](http://fbinfer.com/docs/eradicate.html) null checkers for Java.

NullAway is *fast*.  It is built as a plugin to [Error Prone](http://errorprone.info/) and can run on every single build of your code.  In our measurements, the build-time overhead of running NullAway is usually less than 10%.  NullAway is also *practical*: it does not prevent all possible NPEs in your code, but it catches most of the NPEs we have observed in production while imposing a reasonable annotation burden, giving a great "bang for your buck."  At Uber, we combine NullAway with [RAVE](https://github.com/uber-common/rave) to obtain thorough protection against NPEs in our Android apps.

## Installation

### Overview

NullAway requires that you build your code with [Error Prone](http://errorprone.info), version 2.1.1 or higher.  See the [Error Prone documentation](http://errorprone.info/docs/installation) for instructions on getting started with Error Prone and integration with your build system.  The instructions below assume you are using Gradle; see [the docs](https://github.com/uber/NullAway/wiki/Configuration#other-build-systems) for discussion of other build systems.

### Gradle

#### Java (non-Android)

To integrate NullAway into your non-Android Java project, add the following to your `build.gradle` file:

```gradle
plugins {
  // we assume you are already using the Java plugin
  id "net.ltgt.errorprone" version "0.6"
}

dependencies {
  annotationProcessor "com.uber.nullaway:nullaway:0.7.8"

  // Optional, some source of nullability annotations.
  // Not required on Android if you use the support 
  // library nullability annotations.
  compileOnly "com.google.code.findbugs:jsr305:3.0.2"

  errorprone "com.google.errorprone:error_prone_core:2.3.2"
  errorproneJavac "com.google.errorprone:javac:9+181-r4173-1"
}

import net.ltgt.gradle.errorprone.CheckSeverity

tasks.withType(JavaCompile) {
  // remove the if condition if you want to run NullAway on test code
  if (!name.toLowerCase().contains("test")) {
    options.errorprone {
      check("NullAway", CheckSeverity.ERROR)
      option("NullAway:AnnotatedPackages", "com.uber")
    }
  }
}
```

Let's walk through this script step by step.  The `plugins` section pulls in the [Gradle Error Prone plugin](https://github.com/tbroyer/gradle-errorprone-plugin) for Error Prone integration. If you are using the older `apply plugin` syntax instead of a `plugins` block, the following is equivalent:
```gradle
buildscript {
  repositories {
    gradlePluginPortal()
  }
  dependencies {
    classpath "net.ltgt.gradle:gradle-errorprone-plugin:0.6"
  }
}

apply plugin: 'net.ltgt.errorprone'
```

In `dependencies`, the `annotationProcessor` line loads NullAway, and the `compileOnly` line loads a [JSR 305](https://jcp.org/en/jsr/detail?id=305) library which provides a suitable `@Nullable` annotation (`javax.annotation.Nullable`).  NullAway allows for any `@Nullable` annotation to be used, so, e.g., `@Nullable` from the Android Support Library or JetBrains annotations is also fine. The `errorprone` line ensures that a compatible version of Error Prone is used, and the `errorproneJavac` line is needed for JDK 8 compatibility.

Finally, in the `tasks.withType(JavaCompile)` section, we pass some configuration options to NullAway.  First `check("NullAway", CheckSeverity.ERROR)` sets NullAway issues to the error level (it's equivalent to the `-Xep:NullAway:ERROR` standard Error Prone argument); by default NullAway emits warnings.  Then, `option("NullAway:AnnotatedPackages", "com.uber")` (equivalent to the `-XepOpt:NullAway:AnnotatedPackages=com.uber` standard Error Prone argument), tells NullAway that source code in packages under the `com.uber` namespace should be checked for null dereferences and proper usage of `@Nullable` annotations, and that class files in these packages should be assumed to have correct usage of `@Nullable` (see [the docs](https://github.com/uber/NullAway/wiki/Configuration) for more detail).  NullAway requires at least the `AnnotatedPackages` configuration argument to run, in order to distinguish between annotated and unannotated code.  See [the configuration docs](https://github.com/uber/NullAway/wiki/Configuration) for other useful configuration options.

We recommend addressing all the issues that Error Prone reports, particularly those reported as errors (rather than warnings).  But, if you'd like to try out NullAway without running other Error Prone checks, you can use `options.errorprone.disableAllChecks` (equivalent to passing `"-XepDisableAllChecks"` to the compiler, before the NullAway-specific arguments).

Snapshots of the development version are available in [Sonatype's snapshots repository][snapshots].

#### Android

The configuration for an Android project is very similar to the Java case, with one key difference: The `com.google.code.findbugs:jsr305:3.0.2` dependency can be removed; you can use the `android.support.annotation.Nullable` annotation from the Android Support library.

```gradle
dependencies {
  annotationProcessor "com.uber.nullaway:nullaway:0.7.8"
}
```
A complete Android `build.gradle` example is [here](https://gist.github.com/msridhar/6cacd429567f1d1ad9a278e06809601c).  Also see our [sample app](https://github.com/uber/NullAway/blob/master/sample-app/).

#### Annotation Processors / Generated Code

Some annotation processors like [Dagger](https://google.github.io/dagger/) and [AutoValue](https://github.com/google/auto/tree/master/value) generate code into the same package namespace as your own code.  This can cause problems when setting NullAway to the `ERROR` level as suggested above, since errors in this generated code will block the build.  Currently the best solution to this problem is to completely disable Error Prone on generated code, using the `-XepExcludedPaths` option added in Error Prone 2.13 (documented [here](http://errorprone.info/docs/flags), use `options.errorprone.excludedPaths=` in Gradle).  To use, figure out which directory contains the generated code, and add that directory to the excluded path regex.

**Note for Dagger users**: Dagger versions older than 2.12 can have bad interactions with NullAway; see [here](https://github.com/uber/NullAway/issues/48#issuecomment-340018409).  Please update to Dagger 2.12 to fix the problem.

## Code Example

Let's see how NullAway works on a simple code example:
```java
static void log(Object x) {
    System.out.println(x.toString());
}
static void foo() {
    log(null);
}
```
This code is buggy: when `foo()` is called, the subsequent call to `log()` will fail with an NPE.  You can see this error in the NullAway sample app by running:
```bash
cp sample/src/main/java/com/uber/mylib/MyClass.java.buggy sample/src/main/java/com/uber/mylib/MyClass.java
./gradlew build
```

By default, NullAway assumes every method parameter, return value, and field is _non-null_, i.e., it can never be assigned a `null` value.  In the above code, the `x` parameter of `log()` is assumed to be non-null.  So, NullAway reports the following error:
```
warning: [NullAway] passing @Nullable parameter 'null' where @NonNull is required
    log(null);
        ^
```
We can fix this error by allowing `null` to be passed to `log()`, with a `@Nullable` annotation:
```java
static void log(@Nullable Object x) {
    System.out.println(x.toString());
}
```
With this annotation, NullAway points out the possible null dereference:
```
warning: [NullAway] dereferenced expression x is @Nullable
    System.out.println(x.toString());
                        ^
```
We can fix this warning by adding a null check:
```java
static void log(@Nullable Object x) {
    if (x != null) {
        System.out.println(x.toString());
    }
}
```
With this change, all the NullAway warnings are fixed.

For more details on NullAway's checks, error messages, and limitations, see [our detailed guide](https://github.com/uber/NullAway/wiki).

## Support

Please feel free to [open a GitHub issue](https://github.com/uber/NullAway/issues) if you have any questions on how to use NullAway.  Or, you can [join the NullAway Discord server](https://discord.gg/QH2F779) and ask us a question there.

## Contributors

We'd love for you to contribute to NullAway!  Please note that once
you create a pull request, you will be asked to sign our [Uber Contributor License Agreement](https://docs.google.com/a/uber.com/forms/d/1pAwS_-dA1KhPlfxzYLBqK6rsSWwRwH95OCCZrcsY5rk/viewform).

## License

NullAway is licensed under the MIT license.  See the LICENSE.txt file for more information.

 [snapshots]: https://oss.sonatype.org/content/repositories/snapshots/com/uber/nullaway/
