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

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Pins the Error Prone version a configuration resolves to.
 *
 * <p>NullAway compiles against the oldest Error Prone it supports, so a reference to an API that
 * version lacks fails compilation. Its tests run on several Error Prone versions, and each test
 * classpath resolves to exactly one of them, so a class that only a newer release has cannot load
 * in a test that runs on an older one.
 */
public final class ErrorProneVersions {
  private static final String ERROR_PRONE_GROUP = "com.google.errorprone";

  private ErrorProneVersions() {}

  /**
   * Resolves every {@code com.google.errorprone} module in {@code configuration} to {@code
   * version}, whatever version its dependents request.
   */
  public static void pin(Configuration configuration, String version) {
    configuration
        .getResolutionStrategy()
        .eachDependency(
            details -> {
              if (ERROR_PRONE_GROUP.equals(details.getRequested().getGroup())) {
                details.useVersion(version);
                details.because("each classpath holds a single Error Prone version");
              }
            });
  }

  /**
   * Returns the {@code test} source set's runtime classpath with every Error Prone module pinned to
   * {@code version}, for a test task that runs the {@code test} task's tests on that version.
   *
   * <p>The classpath holds the outputs of the {@code main} and {@code test} source sets and a new
   * resolvable configuration named {@code configurationName}. That configuration declares the same
   * dependencies and attributes as {@code testRuntimeClasspath}; Error Prone, and the libraries it
   * brings in, resolve for {@code version}.
   */
  public static FileCollection testRuntimeClasspath(
      Project project, String configurationName, String version) {
    ConfigurationContainer configurations = project.getConfigurations();
    Configuration testRuntimeClasspath = configurations.getByName("testRuntimeClasspath");
    NamedDomainObjectProvider<ResolvableConfiguration> dependencies =
        configurations.resolvable(
            configurationName,
            configuration -> {
              configuration.extendsFrom(
                  configurations.getByName("testImplementation"),
                  configurations.getByName("testRuntimeOnly"));
              configuration.getAttributes().addAllLater(testRuntimeClasspath.getAttributes());
              pin(configuration, version);
            });
    SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
    return project.files(
        sourceSets.getByName("test").getOutput(),
        sourceSets.getByName("main").getOutput(),
        dependencies);
  }
}
