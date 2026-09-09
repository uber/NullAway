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
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
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
      "Diff coverage: no base ref resolved, so no line counts as changed. Pass -PdiffCoverageBase=<ref>, "
          + "or set an upstream branch, or fetch origin/master.";

  private static final String[] UPSTREAM_NAME = {"rev-parse", "--abbrev-ref", "@{upstream}"};

  private static final String[] BRANCH_NAME = {"rev-parse", "--abbrev-ref", "HEAD"};

  static final String NO_REPORT =
      "Diff coverage: no JaCoCo report was produced, so no line counts as executed.";

  /** The JaCoCo XML reports to read. */
  @InputFiles
  public abstract ConfigurableFileCollection getReportFiles();

  /** Source directories of the module, relative to the repository root. */
  @Internal
  public abstract ListProperty<String> getSourceRoots();

  /**
   * The ref whose merge base with HEAD the diff is taken from.
   *
   * <p>Taking the merge base rather than the ref itself keeps commits that landed on the ref since
   * the branch point out of the diff, so the report covers this branch's own work. Unset, the
   * upstream branch is tried, then {@code origin/master}. A ref that is set and does not resolve
   * fails the task rather than falling through, because diffing against something else would
   * report the wrong lines as executed.
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

  /** Fails the task where fewer than this percentage of the changed lines ran; unset by default. */
  @Internal
  public abstract Property<Double> getFailUnder();

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
   *
   * @throws GradleException where a threshold was asked for and this run cannot honour it
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
    Base base = resolveBase(git);
    if (base == null) {
      return giveUp(NO_BASE);
    }
    List<String> unreadable = new ArrayList<>();
    Map<String, NavigableSet<Integer>> changed = changedLines(git, base.commit(), unreadable);

    // A module the change never reached has nothing to say, and saying it anyway would put a line
    // in every whole-build run for every module with no test and for every module the change
    // missed. Decided before the report, since a module with no test produces none.
    if (!DiffCoverageReport.reaches(getSourceRoots().get(), changed.keySet())) {
      return String.join("\n", unreadable);
    }
    List<File> reports = new ArrayList<>(getReportFiles().getFiles());
    reports.removeIf(file -> !file.isFile());
    if (reports.isEmpty()) {
      return giveUp(NO_REPORT);
    }
    DiffCoverageReport report =
        DiffCoverageReport.measure(
            changed,
            getSourceRoots().get(),
            JacocoLines.read(reports),
            SourceLines.under(repository));
    String scope =
        "against "
            + base.ref()
            + " ("
            + base.commit().substring(0, Math.min(9, base.commit().length()))
            + "), from "
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
    failIfBelowThreshold(report, fullReportPath(repository));
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
   * Reports that no measurement was possible, and fails the task where a threshold was asked for.
   *
   * <p>A threshold is a gate, and a gate that passes because the measurement did not happen is
   * worse than no gate. Without one the message is advice, and failing the build over it would
   * stop a test run that otherwise succeeded.
   */
  private String giveUp(String reason) {
    if (getFailUnder().isPresent()) {
      throw new GradleException(reason);
    }
    return reason;
  }

  /**
   * Fails the task where the run left a changed file unmeasured or below the threshold.
   *
   * @param reportPath the report this run wrote, or null where it wrote none. A failing task
   *     prints its exception and not the block, so the message carries the one line of it a reader
   *     needs to see which lines fell short
   */
  private void failIfBelowThreshold(DiffCoverageReport report, String reportPath) {
    if (!getFailUnder().isPresent()) {
      return;
    }
    String unmeasured = unmeasuredFilesFailure(report.unmeasuredPaths());
    if (unmeasured != null) {
      throw new GradleException(naming(unmeasured, reportPath));
    }
    String failure =
        thresholdFailure(report.executedLines(), report.executableLines(), getFailUnder().get());
    if (failure != null) {
      throw new GradleException(naming(failure, reportPath));
    }
  }

  /** Returns the path of the report this run wrote, or null where it wrote none. */
  private String fullReportPath(File repository) {
    File output = getOutputFile().get().getAsFile();
    return output.isFile() ? relativize(repository, output) : null;
  }

  /** Returns the failure with the report named after it, where there is a report to name. */
  private static String naming(String failure, String reportPath) {
    return reportPath == null ? failure : failure + ". The full report is in " + reportPath;
  }

  /**
   * Returns why a gate cannot be applied to the given files, or null where there are none.
   *
   * <p>A changed file no report mentions contributes nothing to either side of the share, so a
   * change made entirely in such files would clear every threshold without being measured.
   */
  static String unmeasuredFilesFailure(List<String> unmeasuredPaths) {
    if (unmeasuredPaths.isEmpty()) {
      return null;
    }
    return "no JaCoCo report covers these changed files, so no threshold applies to them: "
        + String.join(", ", unmeasuredPaths);
  }

  /**
   * Returns why the executed share falls below required, or null where it does not.
   *
   * <p>A change with no executable line clears every threshold, since a gate on a share of nothing
   * would fail a commit that touched only comments.
   */
  static String thresholdFailure(int executedLines, int executableLines, double required) {
    if (executableLines == 0) {
      return null;
    }
    double executed = 100.0 * executedLines / executableLines;
    if (executed >= required) {
      return null;
    }
    return String.format(
        // The default locale would write 66,7% here, which no reader greps and no CI log spells
        // the same way twice across runners.
        Locale.ROOT,
        "%.1f%% of the changed lines ran, below the required %.1f%%",
        executed,
        required);
  }

  /**
   * Returns the refs to try as the base, in order.
   *
   * <p>An explicit ref stands alone: falling through from the ref the caller named to another one
   * would report a different set of lines as changed, under a base the caller did not choose.
   *
   * <p>An upstream that is this branch's own pushed copy is left out. In a fork workflow the branch
   * tracks the fork, so after the first push its upstream is the branch itself, every line it
   * changed is already in the base, and the report says nothing was changed rather than that it
   * could not find a base.
   *
   * @param explicit the ref the caller named, or null
   * @param upstreamRef the upstream branch as git names it, such as {@code origin/develop}, or null
   * @param currentBranch the checked-out branch, or null on a detached HEAD
   */
  static List<String> baseCandidates(String explicit, String upstreamRef, String currentBranch) {
    if (explicit != null) {
      return List.of(explicit);
    }
    if (upstreamRef == null || isOwnPushedBranch(upstreamRef, currentBranch)) {
      return List.of("origin/master");
    }
    return List.of(upstreamRef, "origin/master");
  }

  /**
   * Returns whether the upstream ref is the current branch pushed to a remote.
   *
   * <p>The comparison drops the remote name, which is the first segment, so that a branch of its
   * own named after the tail of another one is not mistaken for it.
   */
  static boolean isOwnPushedBranch(String upstreamRef, String currentBranch) {
    if (currentBranch == null) {
      return false;
    }
    int remoteEnd = upstreamRef.indexOf('/');
    String branch = remoteEnd < 0 ? upstreamRef : upstreamRef.substring(remoteEnd + 1);
    return branch.equals(currentBranch);
  }

  /** The ref the diff is taken against, and the merge base with HEAD it resolved to. */
  private record Base(String ref, String commit) {}

  /**
   * Returns the first candidate ref that resolves with its merge base, or null where none does.
   *
   * <p>The ref is carried back so that the report names the one that won, which {@code
   * @{upstream}} and {@code origin/master} otherwise leave the reader to guess between.
   */
  private Base resolveBase(GitService git) {
    String explicit = getBaseRef().getOrNull();
    if (explicit != null && !resolves(git, explicit)) {
      throw new GradleException(
          "diffCoverageBase " + explicit + " does not resolve to a commit");
    }
    String upstream = git.outputOrNull(UPSTREAM_NAME);
    String branch = git.outputOrNull(BRANCH_NAME);
    for (String candidate : baseCandidates(explicit, upstream, branch)) {
      if (!resolves(git, candidate)) {
        continue;
      }
      // A ref can resolve and still share no commit with HEAD, as in a shallow clone or after an
      // orphan branch, and merge-base then fails. That is a base this run does not have, not a
      // reason to fail a build whose tests passed.
      String mergeBase = git.outputOrNull("merge-base", candidate, "HEAD");
      if (mergeBase != null) {
        return new Base(candidate, mergeBase);
      }
    }
    return null;
  }

  /**
   * Returns the git arguments the diff is taken with.
   *
   * <p>{@code --unified=0} is what makes a hunk header name only changed lines, and the prefixes
   * are pinned because {@code diff.noprefix} and {@code diff.mnemonicPrefix} are personal settings
   * that would otherwise change the {@code a/} and {@code b/} that {@link UnifiedDiff} strips.
   *
   * <p>{@code core.quotePath} is off because it wraps a path holding a non-ASCII byte in quotes
   * and escapes the byte, as in {@code "b/src/caf\303\251.java"}. Such a {@code +++} line starts
   * with a quotation mark rather than with the prefix, so it is no file header, and every hunk of
   * that file leaves the report with nothing to show that it happened.
   */
  static List<String> diffCommand(String base) {
    return List.of(
        "-c",
        "core.quotePath=false",
        "diff",
        "--unified=0",
        "--no-color",
        "--src-prefix=a/",
        "--dst-prefix=b/",
        base,
        "--",
        "*.java");
  }

  /** Returns whether the ref names a commit of this repository. */
  private boolean resolves(GitService git, String ref) {
    return git.outputOrNull("rev-parse", "--verify", "--quiet", ref + "^{commit}") != null;
  }

  /**
   * Returns the changed lines of every Java file, keyed by repository-relative path.
   *
   * <p>Two sources are merged: the post-image lines of the diff between base and the working tree,
   * which covers committed, staged and unstaged changes alike, and every line of an untracked
   * file, which no diff mentions at all.
   *
   * @param unreadable collects a line naming each untracked file this JVM cannot decode
   */
  private Map<String, NavigableSet<Integer>> changedLines(
      GitService git, String base, List<String> unreadable) {
    String diff = git.output(diffCommand(base).toArray(new String[0]));
    Map<String, NavigableSet<Integer>> changed = UnifiedDiff.parse(diff);
    // core.quotePath is off here for the reason diffCommand gives: a quoted path names no file
    // this JVM can open, and the file would be reported as unreadable rather than measured.
    String untracked =
        git.output(
            "-c",
            "core.quotePath=false",
            "ls-files",
            "--others",
            "--exclude-standard",
            "--",
            "*.java");
    if (untracked.isEmpty()) {
      return changed;
    }
    for (String path : untracked.split("\n")) {
      if (path.isEmpty()) {
        continue;
      }
      // An untracked file has no diff, so every line in it counts as changed.
      NavigableSet<Integer> lines = new TreeSet<>();
      try {
        int count = Files.readAllLines(git.repositoryRoot().toPath().resolve(path)).size();
        for (int number = 1; number <= count; number++) {
          lines.add(number);
        }
      } catch (IOException e) {
        // A broken symlink or a file this JVM cannot decode is not worth failing a build whose
        // tests passed, so it is named and left out of the counts.
        unreadable.add("Diff coverage: cannot read the untracked file " + path + ": " + e);
        continue;
      }
      changed.put(path, lines);
    }
    return changed;
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
