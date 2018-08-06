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

    java -jar <path-to-jar-infer-cli-tool> -i <in_path> -o <out_path> [-p <pkg_name>] [-vdh]
     -i,--input-file <in_path>     path to target jar/aar file
     -o,--output-file <out_path>   path to processed jar/aar file
     -p,--package <pkg_name>       qualified package name
     -v,--verbose                  set verbosity
     -d,--debug                    print debug information
     -h,--help                     print usage information

