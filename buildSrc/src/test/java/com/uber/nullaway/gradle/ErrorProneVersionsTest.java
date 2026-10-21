/*
 * Copyright (C) 2026. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.nullaway.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.file.FileCollection;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pinned configuration resolves every {@code com.google.errorprone} module to the pinned version
 * and every other module to the version requested.
 *
 * <p>Each test resolves a synthetic Gradle project against a Maven repository written here. The
 * repository holds {@code error_prone_core}, {@code error_prone_annotations}, and {@code
 * org.example:lib} at versions 1 and 2, and {@code org.example:checker} at version 1, which
 * depends on {@code error_prone_annotations} 2.
 */
class ErrorProneVersionsTest {

  @TempDir Path directory;

  private Project project() throws IOException {
    Path repository = directory.resolve("repository");
    publish(repository, "com.google.errorprone", "error_prone_core", "1", "");
    publish(repository, "com.google.errorprone", "error_prone_core", "2", "");
    publish(repository, "com.google.errorprone", "error_prone_annotations", "1", "");
    publish(repository, "com.google.errorprone", "error_prone_annotations", "2", "");
    publish(repository, "org.example", "lib", "1", "");
    publish(repository, "org.example", "lib", "2", "");
    publish(
        repository,
        "org.example",
        "checker",
        "1",
        "<dependency><groupId>com.google.errorprone</groupId>"
            + "<artifactId>error_prone_annotations</artifactId><version>2</version></dependency>");
    Project project =
        ProjectBuilder.builder().withProjectDir(directory.resolve("project").toFile()).build();
    project.getRepositories().maven(maven -> maven.setUrl(repository.toUri()));
    return project;
  }

  private static void publish(
      Path repository, String group, String artifact, String version, String dependencies)
      throws IOException {
    Path dir = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(artifact + "-" + version + ".pom"),
        "<project><modelVersion>4.0.0</modelVersion>"
            + ("<groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>")
            + ("<version>" + version + "</version>")
            + ("<dependencies>" + dependencies + "</dependencies></project>"));
    Files.write(dir.resolve(artifact + "-" + version + ".jar"), new byte[0]);
  }

  /**
   * Adds a Java runtime variant to {@code project} that publishes {@code file} for Java release
   * {@code jvm}.
   */
  private static void publishVariant(Project project, String name, int jvm, String file) {
    project
        .getConfigurations()
        .consumable(
            name,
            configuration -> {
              configuration
                  .getAttributes()
                  .attribute(
                      Usage.USAGE_ATTRIBUTE,
                      project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME))
                  .attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, jvm);
              configuration.getOutgoing().artifact(project.file(file));
            });
  }

  /** Names a file under the project directory by its relative path, and a jar by its name. */
  private List<String> names(Project project, FileCollection files) {
    Path projectDir = project.getProjectDir().toPath();
    return files.getFiles().stream()
        .map(File::toPath)
        .map(
            file ->
                file.startsWith(projectDir)
                    ? projectDir.relativize(file).toString().replace(File.separatorChar, '/')
                    : file.getFileName().toString())
        .toList();
  }

  @Test
  void pinMovesADeclaredErrorProneModuleAndLeavesOtherGroupsAlone() throws IOException {
    Project project = project();
    Configuration configuration = project.getConfigurations().create("pinned");
    project.getDependencies().add("pinned", "com.google.errorprone:error_prone_core:2");
    project.getDependencies().add("pinned", "org.example:lib:2");

    ErrorProneVersions.pin(configuration, "1");

    assertEquals(List.of("error_prone_core-1.jar", "lib-2.jar"), names(project, configuration));
  }

  @Test
  void pinMovesAnErrorProneModuleThatAnotherModuleRequests() throws IOException {
    Project project = project();
    Configuration configuration = project.getConfigurations().create("pinned");
    project.getDependencies().add("pinned", "org.example:checker:1");

    ErrorProneVersions.pin(configuration, "1");

    assertEquals(
        List.of("checker-1.jar", "error_prone_annotations-1.jar"), names(project, configuration));
  }

  /**
   * The sibling project publishes a variant for Java 8 and one for a Java release no JVM runs yet.
   * A configuration that carries the attributes of {@code testRuntimeClasspath} requests the
   * running JVM's version and selects the Java 8 variant; one without them selects the newer.
   */
  @Test
  void testRuntimeClasspathHoldsTheTestDependenciesWithErrorPronePinned() throws IOException {
    Project project = project();
    project.getPluginManager().apply("java");
    Project sibling = ProjectBuilder.builder().withParent(project).withName("sibling").build();
    publishVariant(sibling, "runtimeElements", 8, "sibling.jar");
    publishVariant(sibling, "futureRuntimeElements", 1000, "sibling-future.jar");
    project.getDependencies().add("testImplementation", "com.google.errorprone:error_prone_core:2");
    project.getDependencies().add("testImplementation", project.project(":sibling"));
    project.getDependencies().add("testRuntimeOnly", "org.example:lib:2");

    FileCollection classpath =
        ErrorProneVersions.testRuntimeClasspath(project, "errorProneOneTestRuntimeClasspath", "1");

    assertEquals(
        List.of(
            "build/classes/java/test",
            "build/resources/test",
            "build/classes/java/main",
            "build/resources/main",
            "error_prone_core-1.jar",
            "sibling/sibling.jar",
            "lib-2.jar"),
        names(project, classpath));
  }

  /**
   * The test task keeps running on the version the build declares. The pinned classpath is
   * resolved first, so the configuration behind it exists when the other one resolves.
   */
  @Test
  void testRuntimeClasspathLeavesTheTestTaskClasspathUnpinned() throws IOException {
    Project project = project();
    project.getPluginManager().apply("java");
    project.getDependencies().add("testImplementation", "com.google.errorprone:error_prone_core:2");

    ErrorProneVersions.testRuntimeClasspath(project, "errorProneOneTestRuntimeClasspath", "1")
        .getFiles();

    assertEquals(
        List.of("error_prone_core-2.jar"),
        names(project, project.getConfigurations().getByName("testRuntimeClasspath")));
  }
}
