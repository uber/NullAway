// An init script to override a build configuration to use a snapshot version of NullAway
gradle.lifecycle.beforeProject {
  repositories {
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
  }

  configurations.configureEach {
    resolutionStrategy {
      eachDependency {
        if (requested.group == "com.uber.nullaway") {
          useVersion("+")
        }
      }
      cacheChangingModulesFor(0, "seconds")
      cacheDynamicVersionsFor(0, "seconds")
    }
  }

  // NullAway runs inside javac, so compiling every source set (main, test, and extras such as
  // multi-release or test-fixture sources) checks every source set the project runs NullAway on,
  // without running tests, Javadoc, or linters.
  tasks.register("compileAllJava") {
    dependsOn(tasks.withType<JavaCompile>())
  }
}
