NullAway jar-infer
=======

### Introduction

This extends NullAway to support Nullability inference on external libraries.

### Requirements

  * Java 8
  * The [Gradle](https://gradle.org/) build tool
  * The [WALA](http://wala.sourceforge.net/wiki/index.php/Main_Page) analysis framework

### Installation

Clone the repository, and then:

    cd jar-infer/tests
    gradle build
    cd ..
    gradle build

This will pull in the required WALA jars and build the analysis code.

### Usage

    cd jar-infer
    gradle run -PmainClass="com.uber.nullaway.jarinfer.definitelyDerefedParamsDriver"

