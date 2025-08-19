// An init script to override a build configuration to use a snapshot version of NullAway
allprojects {
  repositories {
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
  }
}

gradle.projectsLoaded {
  rootProject.allprojects {
    configurations.all {
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
}
