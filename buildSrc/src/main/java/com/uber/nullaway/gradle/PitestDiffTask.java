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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Runs PIT over the lines a {@link ChangedLinesTask} listed and prints the mutants no test detected.
 *
 * <p>PIT runs in a JVM of its own, with the jar of buildSrc on its classpath so that it finds the
 * changed-lines interceptor and the mutants listener. Everything PIT prints goes to {@code pit.log}
 * in the report directory, which the console names only when PIT fails, together with the end of
 * the log; otherwise the console gets the report of {@link PitestSurvivors}.
 */
@UntrackedTask(because = "the working tree and the branch decide the result, and Gradle tracks neither")
public abstract class PitestDiffTask extends DefaultTask {

  private static final String MAIN_CLASS =
      "org.pitest.mutationtest.commandline.MutationCoverageReport";

  /** How many lines of {@code pit.log} a failure prints. */
  private static final int LOG_TAIL = 30;

  /** PIT and the jar of buildSrc. */
  @Classpath
  public abstract ConfigurableFileCollection getPitestClasspath();

  /** The JVM PIT runs in, which is the one the module's tests run in. */
  @Nested
  public abstract Property<JavaLauncher> getJavaLauncher();

  /** The {@link ChangedLinesFile} that names the lines to mutate. */
  @InputFile
  public abstract RegularFileProperty getChangedLinesFile();

  /** The classpath the tests run with, the main classes included. */
  @Classpath
  public abstract ConfigurableFileCollection getTestRuntimeClasspath();

  /** The main classes, the only code PIT mutates; they are on the test runtime classpath too. */
  @Internal
  public abstract ConfigurableFileCollection getMainClassesDirs();

  /** The main source directories, where the report finds the text of a mutated line. */
  @Internal
  public abstract ConfigurableFileCollection getMainSourceDirs();

  /** The JVM arguments of the test task, as {@link PitestJvmArgs} joins them. */
  @Input
  public abstract Property<String> getTestJvmArgs();

  /** A PIT glob for the tests allowed to kill a mutant; unset, every test is. */
  @Input
  @Optional
  public abstract Property<String> getTargetTests();

  /** The PIT mutator group. */
  @Input
  public abstract Property<String> getMutators();

  /** How many mutants PIT analyzes at once. */
  @Input
  public abstract Property<Integer> getThreads();

  /** How many files the console report describes. */
  @Input
  public abstract Property<Integer> getMaxFiles();

  /** How many lines of one file the console report describes. */
  @Input
  public abstract Property<Integer> getMaxLinesPerFile();

  /** How many lines the console report describes in all. */
  @Input
  public abstract Property<Integer> getMaxLines();

  /** Whether the console prints the whole report. */
  @Input
  public abstract Property<Boolean> getShowAll();

  /** Where PIT writes its reports and the task writes {@code pit.log} and {@code survivors.txt}. */
  @OutputDirectory
  public abstract DirectoryProperty getReportDir();

  /** The directory the printed paths are relative to. */
  @Internal
  public abstract DirectoryProperty getRepositoryRoot();

  @Inject
  protected abstract ExecOperations getExecOperations();

  @Inject
  protected abstract FileSystemOperations getFileSystemOperations();

  /** Returns whether the changed lines include a class of the main sources to mutate. */
  public boolean hasTargetClasses() throws IOException {
    return !ChangedLinesFile.targetClasses(
            ChangedLinesFile.read(getChangedLinesFile().get().getAsFile().toPath()))
        .isEmpty();
  }

  @TaskAction
  public void run() throws IOException {
    Path changedLines = getChangedLinesFile().get().getAsFile().toPath();
    File reportDir = getReportDir().get().getAsFile();
    // PIT writes no report when no mutant is left on the changed lines, so the directory is emptied
    // first: a report found after the run is this run's.
    getFileSystemOperations().delete(spec -> spec.delete(reportDir));
    Files.createDirectories(reportDir.toPath());
    File log = new File(reportDir, "pit.log");
    ExecResult result;
    try (OutputStream out = new FileOutputStream(log)) {
      List<String> arguments =
          arguments(ChangedLinesFile.targetClasses(ChangedLinesFile.read(changedLines)), reportDir);
      result =
          getExecOperations()
              .javaexec(
                  spec -> {
                    spec.setExecutable(
                        getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
                    spec.setClasspath(getPitestClasspath());
                    spec.getMainClass().set(MAIN_CLASS);
                    spec.systemProperty(
                        ChangedLinesFile.PITEST_PROPERTY, changedLines.toAbsolutePath().toString());
                    spec.setArgs(arguments);
                    spec.setStandardOutput(out);
                    spec.setErrorOutput(out);
                    spec.setIgnoreExitValue(true);
                  });
    }
    File root = getRepositoryRoot().get().getAsFile();
    if (result.getExitValue() != 0) {
      List<String> lines = Files.readAllLines(log.toPath());
      throw new GradleException(
          "PIT failed with exit code "
              + result.getExitValue()
              + ". The end of "
              + DiffCoverageTask.relativize(root, log)
              + ":\n"
              + String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL), lines.size())));
    }
    String header = ChangedLinesFile.readHeader(changedLines);
    String tests =
        getTargetTests().isPresent() ? "tests matching " + getTargetTests().get() : "all tests";
    PitestSurvivors report =
        PitestSurvivors.read(
            (header == null ? "against an unknown base" : header) + ", " + tests,
            new File(reportDir, MutantsFile.NAME),
            root,
            List.copyOf(getMainSourceDirs().getFiles()));
    getLogger()
        .quiet(
            report.writeAndRender(
                root,
                new File(reportDir, "survivors.txt"),
                getMaxFiles().get(),
                getMaxLinesPerFile().get(),
                getMaxLines().get(),
                getShowAll().get(),
                List.of(DiffCoverageTask.relativize(root, new File(reportDir, "index.html")))));
  }

  /** Returns the PIT command line for the given classes. */
  private List<String> arguments(List<String> targetClasses, File reportDir) {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("reportDir", reportDir.getAbsolutePath());
    options.put("targetClasses", String.join(",", targetClasses));
    options.put("targetTests", getTargetTests().getOrElse("*"));
    // Only the main output is mutated, so a glob cannot reach a test class of the same name.
    options.put("mutableCodePaths", paths(getMainClassesDirs()));
    options.put("sourceDirs", paths(getMainSourceDirs()));
    options.put("classPath", paths(getTestRuntimeClasspath()));
    options.put("jvmArgs", getTestJvmArgs().get());
    options.put("mutators", getMutators().get());
    // PIT's own default list, plus the invariant checks: removing a Verify or Preconditions call
    // changes no diagnostic, so every such mutant would survive a passing suite. PIT drops every
    // mutant on a line that makes such a call, not only the call's own.
    options.put(
        "avoidCallsTo",
        String.join(
            ",",
            "java.util.logging",
            "org.apache.log4j",
            "org.apache.logging.log4j",
            "org.slf4j",
            "org.apache.commons.logging",
            "org.jboss.logging",
            "com.google.common.base.Verify",
            "com.google.common.base.Preconditions"));
    options.put("threads", getThreads().get().toString());
    options.put("outputFormats", "HTML," + MutantsFile.LISTENER_FORMAT);
    options.put("timestampedReports", "false");
    // A changed line can be a comment or a declaration with no instruction to mutate.
    options.put("failWhenNoMutations", "false");
    // A test case here compiles a Java source file, so PIT's default 4-second constant is too tight.
    options.put("timeoutConst", "20000");
    // PIT runs every test, including those the test task's filter excludes, and refuses to start
    // when one fails before any mutation. A test failing there is left out instead.
    options.put("skipFailingTests", "true");
    List<String> arguments = new ArrayList<>();
    options.forEach(
        (name, value) -> {
          arguments.add("--" + name);
          arguments.add(value);
        });
    return arguments;
  }

  private static String paths(ConfigurableFileCollection files) {
    return files.getFiles().stream().map(File::getAbsolutePath).collect(Collectors.joining(","));
  }
}
