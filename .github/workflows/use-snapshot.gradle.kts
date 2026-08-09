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
}
