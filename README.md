## NullAway: Fast Annotation-Based Null Checking for Java [![Build Status](https://travis-ci.org/uber/NullAway.svg?branch=master)](https://travis-ci.org/uber/NullAway)

NullAway is a tool to help eliminate `NullPointerException`s (NPEs) in your Java code.  To use NullAway, first add `@Nullable` annotations in your code wherever a field, method parameter, or return value may be `null`.  Given these annotations, NullAway performs a series of type-based, local checks to ensure that any pointer that gets dereferenced in your code cannot be `null`.  NullAway is similar to the type-based nullability checking in the Kotlin and Swift languages, and the [Checker Framework](https://checkerframework.org/) and [Eradicate](http://fbinfer.com/docs/eradicate.html) null checkers for Java.

NullAway is *fast*.  It is built as a plugin to Error Prone and can run on every single build of your code.  In our measurements, the build-time overhead of running NullAway is usually less than 10%.  NullAway is also *practical*: it does not prevent all possible NPEs in your code, but it catches most of the NPEs we have observed in production while imposing a reasonable annotation burden, giving a great "bang for your buck."  At Uber, we combine NullAway with [RAVE](https://github.com/uber-common/rave) to obtain thorough protection against NPEs in our Android apps.

## Installation

### Overview

NullAway requires that you build your code with [Error Prone](http://errorprone.info), version 2.1.1 or higher.  See the [Error Prone documentation](http://errorprone.info/docs/installation) for instructions on getting started with Error Prone and integration with your build system.  The instructions below assume you are using the Gradle build system; integration with other systems should require similar steps.

### Gradle
To integrate NullAway into your project add the following to your `build.gradle` file:

```
buildscript {
  repositories {
    maven {
      url "https://plugins.gradle.org/m2/"
    }
  }
  dependencies {
    classpath "net.ltgt.gradle:gradle-errorprone-plugin:0.0.11"
    classpath "net.ltgt.gradle:gradle-apt-plugin:0.11"
  }
}

apply plugin: "java"
apply plugin: "net.ltgt.errorprone"
apply plugin: "net.ltgt.apt"

configurations.errorprone {
  resolutionStrategy.force "com.google.errorprone:error_prone_core:2.1.1"
}

dependencies {
    apt "com.uber.nullaway:nullaway:0.1.0"

    compile "com.google.code.findbugs:jsr305:3.0.2"
}

compileJava {
    options.compilerArgs += ["-Xep:NullAway:ERROR", "-XepOpt:NullAway:AnnotatedPackages=com.uber"]
}
```

Let's walk through this script step by step.  The `buildscript` section of the script pulls in the [Gradle Error Prone plugin](https://github.com/tbroyer/gradle-errorprone-plugin) for Error Prone integration, and the [Gradle APT plugin](https://github.com/tbroyer/gradle-apt-plugin) to ease specification of annotation processor dependencies for a build.  We need the latter since Error Prone loads plugin checkers from the annotation processor path.  Note that the Gradle APT plugin is appropriate for Java projects; for Android projects, use an `annotationProcessor` dependence with the [Android Gradle plugin](https://developer.android.com/studio/releases/gradle-plugin.html).  The `apply plugin` lines load the relevant plugins.  The `configurations.errorprone` section forces our desired Error Prone version.

In `dependencies`, the `apt` line loads NullAway, and the `compile` line loads a [JSR 305](https://jcp.org/en/jsr/detail?id=305) library which provides a suitable `@Nullable` annotation (`javax.annotation.Nullable`).  NullAway allows for any `@Nullable` annotation to be used, so, e.g., `@Nullable` from the Android support or IntelliJ annotations is also fine.

Finally, in the `compileJava` section, we pass some configuration options to NullAway as compiler arguments.  The first argument `-Xep:NullAway:ERROR` is a standard Error Prone argument that sets NullAway issues to the error level; by default NullAway emits warnings.  The second argument, `-XepOpt:NullAway:AnnotatedPackages=com.uber`, tells NullAway that source code in packages under the `com.uber` namespace should be checked for null dereferences and proper usage of `@Nullable` annotations, and that class files in these packages should be assumed to have correct usage of `@Nullable` (see [the docs](https://github.com/uber/NullAway/wiki/Configuration) for more detail).  NullAway requires at least the `AnnotatedPackages` configuration argument to run, in order to distinguish between annotated and unannotated code.  See [the configuration docs](https://github.com/uber/NullAway/wiki/Configuration) for other useful configuration options.

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
```
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

## License

NullAway is licensed under the MIT license.  See the LICENSE.txt file for more information.
