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

    gradle wrapper
    ./gradlew build

This will pull in the required WALA jars and build the analysis code.

### Usage

    ./gradlew test

