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
      }
    }
  }
}