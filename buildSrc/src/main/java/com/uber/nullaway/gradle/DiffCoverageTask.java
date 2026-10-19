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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Prints which of the lines this branch changed the preceding test run executed.
 *
 * <p>The task intersects two sets: the lines a git diff marks as changed, and the lines a JaCoCo
 * report records as executed. It reads no coverage data from the base commit, so nothing has to be
 * recorded before the run or stored in the repository; the base is a git ref, used only to decide
 * which lines count as changed.
 *
 * <p>The output goes to the {@code QUIET} log level, which {@code --quiet} still shows, because the
 * point of the task is to reach whoever is reading the test output. What the printed block leaves
 * out is in the file {@link #getOutputFile()} names, which the block ends by naming.
 */
@UntrackedTask(
    because = "the working tree and the branch decide the result, and Gradle tracks neither")
public abstract class DiffCoverageTask extends DefaultTask {

  static final String NO_BASE =
      "Diff coverage: no base ref resolved, so no line counts as changed. "
          + ChangedLines.NO_BASE_HINT;

  static final String NO_REPORT =
      "Diff coverage: no JaCoCo report was produced, so no line counts as executed.";

  /** The JaCoCo XML reports to read. */
  @InputFiles
  public abstract ConfigurableFileCollection getReportFiles();

  /** Source directories of the module, relative to the repository root. */
  @Internal
  public abstract ListProperty<String> getSourceRoots();

  /**
   * The ref whose merge base with HEAD the diff is taken from, as {@link ChangedLines#measure}
   * resolves it.
   */
  @Internal
  public abstract Property<String> getBaseRef();

  /** What the run covered, printed so that a filtered run is not read as missing coverage. */
  @Internal
  public abstract Property<String> getScopeDescription();

  /** How many uncovered lines to print for one file, past which the file states its total. */
  @Internal
  public abstract Property<Integer> getMaxLinesPerFile();

  /** How many uncovered lines to print over the block's lists together. */
  @Internal
  public abstract Property<Integer> getMaxLinesTotal();

  /** Whether the run was narrowed to some of the tests, which the report has to say. */
  @Internal
  public abstract Property<Boolean> getFiltered();

  /** How many files to describe over the block's lists together, most work left first. */
  @Internal
  public abstract Property<Integer> getMaxFiles();

  /** Whether to print the whole report, spending no limit on the console output. */
  @Internal
  public abstract Property<Boolean> getShowAll();

  /**
   * The file the whole report is written to, whatever the console limits leave out.
   *
   * <p>The task prints its path, so that a reader who needs the rest reads a report rather than
   * the JaCoCo XML.
   */
  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  /**
   * The name the build registers {@link GitService} under.
   *
   * <p>The registration and this reference share the constant because a reference that names an
   * unregistered service fails only in a build that runs the task, which no test here does.
   */
  public static final String GIT_SERVICE = "nullaway.git";

  /** Runs the git commands, and names the repository the diff and the sources are read from. */
  @ServiceReference(GIT_SERVICE)
  public abstract Property<GitService> getGit();

  @TaskAction
  public void report() throws IOException {
    String text = describeRun();
    if (!text.isEmpty()) {
      getLogger().quiet(text);
    }
  }

  /**
   * Returns the text this run prints, which is empty where the change reached none of the module.
   *
   * <p>Returning the text rather than logging it is what lets a test read the report: the scope
   * line naming the ref that resolved, the message of a run that measured nothing, and the note
   * about a file that could not be read are all outcomes a caller can only otherwise infer.
   *
   * <p>The file {@link #getOutputFile()} names is rewritten here, and is deleted where this run
   * measured nothing, so that it holds this run's report or no report at all.
   */
  String describeRun() throws IOException {
    // The file belongs to this task, so a run that measures nothing must not leave the last run's
    // report where a reader would take it for this one. A path that names no regular file is left
    // to the write below, which reports what is wrong with it.
    Path output = getOutputFile().get().getAsFile().toPath();
    if (Files.isRegularFile(output)) {
      Files.delete(output);
    }
    GitService git = getGit().get();
    File repository = git.repositoryRoot();
    ChangedLines diff = ChangedLines.measure(git, getBaseRef().getOrNull());
    if (diff == null) {
      return NO_BASE;
    }
    List<String> unreadable = new ArrayList<>();
    for (String note : diff.unreadable()) {
      unreadable.add("Diff coverage: " + note);
    }
    Map<String, NavigableSet<Integer>> changed = diff.lines();

    // A module the change never reached has nothing to say, and saying it anyway would put a line
    // in every whole-build run for every module with no test and for every module the change
    // missed. Decided before the report, since a module with no test produces none.
    if (!DiffCoverageReport.reaches(getSourceRoots().get(), changed.keySet())) {
      return String.join("\n", unreadable);
    }
    List<File> reports = new ArrayList<>(getReportFiles().getFiles());
    reports.removeIf(file -> !file.isFile());
    if (reports.isEmpty()) {
      return NO_REPORT;
    }
    DiffCoverageReport report =
        DiffCoverageReport.measure(
            changed,
            getSourceRoots().get(),
            JacocoLines.read(reports),
            SourceLines.under(repository));
    String scope =
        "against " + diff.ref() + " (" + diff.shortCommit() + "), from "
            + getScopeDescription().get();
    List<String> stale = staleSources(repository, report.measuredPaths(), reports);
    String full =
        report.render(scope, getFiltered().get(), stale, DiffCoverageReport.Limits.NONE);
    String console =
        getShowAll().get()
            ? full
            : report.render(
                scope,
                getFiltered().get(),
                stale,
                new DiffCoverageReport.Limits(
                    getMaxFiles().get(), getMaxLinesPerFile().get(), getMaxLinesTotal().get()));
    String text = console.isEmpty() ? console : withReportPaths(repository, reports, console, full);
    if (unreadable.isEmpty()) {
      return text;
    }
    // The silence rule is for a module the change never reached. A file the task could not read is
    // a change it did reach and could not measure, so it is said either way.
    String notes = String.join("\n", unreadable);
    return text.isEmpty() ? notes : notes + "\n" + text;
  }

  /**
   * Writes the whole report to a file and returns the text to print, ending in the paths of both.
   *
   * <p>The console block is a summary of work, so the reader is left with somewhere to read the
   * rest: the file holds every uncovered line, and the JaCoCo XML holds the run it was measured
   * from. A file that cannot be written is reported in place of its path, since a path that names
   * no file sends the reader to a stale report or to none.
   */
  private String withReportPaths(
      File repository, List<File> reports, String console, String full) {
    StringBuilder jacoco =
        new StringBuilder(reports.size() == 1 ? "Full JaCoCo report:" : "Full JaCoCo reports:");
    for (File report : reports) {
      jacoco.append("\n  ").append(relativize(repository, report));
    }
    File output = getOutputFile().get().getAsFile();
    String diffCoverage;
    try {
      Files.createDirectories(output.toPath().getParent());
      Files.writeString(output.toPath(), full + "\n\n" + jacoco + "\n");
      diffCoverage = "Full diff coverage report:\n  " + relativize(repository, output);
    } catch (IOException e) {
      diffCoverage =
          "Full diff coverage report: cannot write " + relativize(repository, output) + ": " + e;
    }
    return console + "\n\n" + diffCoverage + "\n" + jacoco;
  }

  /** Returns the path as the reader would type it, which is relative to the repository. */
  private static String relativize(File repository, File file) {
    Path root = repository.toPath().toAbsolutePath().normalize();
    Path target = file.toPath().toAbsolutePath().normalize();
    return target.startsWith(root) ? root.relativize(target).toString() : target.toString();
  }

  /**
   * Returns the files among measuredPaths that were edited after the newest report was written.
   *
   * <p>JaCoCo numbers lines against the sources that were compiled, so an edit after the run shifts
   * every line below it and the report stops naming the code it was recorded for. Only the files
   * the counts came from are checked; an edit elsewhere moves no line number in this report.
   */
  static List<String> staleSources(
      File repository, List<String> measuredPaths, List<File> reports) {
    long newest = reports.stream().mapToLong(File::lastModified).max().orElse(Long.MAX_VALUE);
    List<String> stale = new ArrayList<>();
    for (String path : measuredPaths) {
      File source = new File(repository, path);
      if (source.isFile() && source.lastModified() > newest) {
        stale.add(path);
      }
    }
    return stale;
  }

}
