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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** How much of a change a test run executed, file by file. */
final class DiffCoverageReport {

  /** Characters of a source line the report quotes, past which the line is cut. */
  private static final int QUOTE_WIDTH = 100;

  /**
   * The comment that takes a line out of the list of work, such as {@code // diff-coverage: ignore
   * -- defensive safeguard}.
   *
   * <p>The marker acknowledges the line it sits on, and has to follow a comment opener on it, so
   * that the reason is readable where the uncovered line is. Nothing here parses Java, so a marker
   * written with its opener inside a string literal counts as well.
   */
  private static final Pattern IGNORE_MARKER =
      Pattern.compile("(?://|/\\*|\\*)\\s*diff-coverage:\\s*ignore(?![\\w-])");

  /** One changed line the run left uncovered, with the source text the report quotes for it. */
  record AffectedLine(
      int number, boolean executed, int coveredBranches, int totalBranches, String source) {

    /** Returns how many branch outcomes of this line nothing took. */
    int missedBranches() {
      return totalBranches - coveredBranches;
    }

    /**
     * Returns the row the report prints, such as {@code 194: not executed; 0 of 2 branches
     * covered: return null;}.
     *
     * <p>A line the run neither reached nor took a branch of is one line of work, so both counters
     * share a row rather than repeating the line under two headings.
     */
    String describe() {
      StringBuilder row = new StringBuilder().append(number).append(": ");
      if (!executed) {
        row.append("not executed");
      }
      if (missedBranches() > 0) {
        if (!executed) {
          row.append("; ");
        }
        row.append(coveredBranches).append(" of ").append(totalBranches).append(" branches covered");
      }
      if (!source.isEmpty()) {
        row.append(": ").append(source);
      }
      return row.toString();
    }
  }

  /** One file's changed lines, split by whether the run covered them. */
  static final class FileCoverage {
    final String path;

    /** Changed lines of this file that emit bytecode, executed or not. */
    int executableLines;

    /** Changed lines of this file the run executed. */
    int executedLines;

    /** The uncovered lines this file still owes a test, in ascending order. */
    final List<AffectedLine> affected = new ArrayList<>();

    /** The uncovered lines a comment in the source acknowledges, in ascending order. */
    final List<AffectedLine> acknowledged = new ArrayList<>();

    int coveredBranches;
    int totalBranches;

    FileCoverage(String path) {
      this.path = path;
    }

    /** Returns whether this file still owes a test, which is what makes it worth describing. */
    boolean needsAttention() {
      return !affected.isEmpty();
    }

    /** Returns how many of the lines this file owes a test nothing ran. */
    int uncoveredChangedLines() {
      return (int) affected.stream().filter(line -> !line.executed()).count();
    }

    /**
     * Returns how many branch outcomes of the lines this file owes a test nothing took.
     *
     * <p>Outcomes rather than lines, so that a line reported as {@code 0 of 4 branches covered}
     * outweighs one reported as {@code 1 of 2}.
     */
    int missedChangedBranches() {
      return affected.stream().mapToInt(AffectedLine::missedBranches).sum();
    }
  }

  /** How much of the report to print, past which the reader is sent to the full report. */
  static final class Limits {

    /** Prints the report whole, as the file the task writes holds it. */
    static final Limits NONE =
        new Limits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    final int files;
    final int linesPerFile;
    final int lines;

    /**
     * @param files how many files to describe, over the block's lists together
     * @param linesPerFile how many rows to spend on one file
     * @param lines how many rows to spend over the block's lists together
     * @throws IllegalArgumentException where a limit is negative, which a build property can
     *     spell and which would otherwise surface as an index out of the rows it cuts
     */
    Limits(int files, int linesPerFile, int lines) {
      if (files < 0 || linesPerFile < 0 || lines < 0) {
        throw new IllegalArgumentException(
            "a diff coverage limit cannot be negative: files="
                + files
                + ", linesPerFile="
                + linesPerFile
                + ", lines="
                + lines);
      }
      this.files = files;
      this.linesPerFile = linesPerFile;
      this.lines = lines;
    }
  }

  /**
   * What one rendering has left to spend, which the lists take from in the order they are printed.
   *
   * <p>One budget over the whole block rather than one per list, so that the block a reader takes
   * in at a glance is the limit they set, and the work is described before the lines somebody has
   * already decided about.
   */
  private static final class Budget {
    int files;
    int rows;

    Budget(Limits limits) {
      this.files = limits.files;
      this.rows = limits.lines;
    }

    boolean isSpent() {
      return files == 0 || rows == 0;
    }
  }

  /** The total printed where the change reached no line of this module that emits bytecode. */
  static final String NO_EXECUTABLE_LINES =
      "Total: no changed executable lines under this module's source roots.";

  final List<FileCoverage> measured = new ArrayList<>();

  /** Changed files under a source root that no report read here mentions. */
  final List<String> notInAnyReport = new ArrayList<>();

  /** Changed files whose changed lines emit no bytecode, such as comments and declarations. */
  final List<String> nothingExecutable = new ArrayList<>();

  /**
   * Orders files by how much testing they still owe, most first.
   *
   * <p>An acknowledged line counts towards neither key: it is measured like any other line, and a
   * file whose remaining work is acknowledged is not where the reader should start.
   *
   * <p>The keys stay separate rather than summing into one score, because a line and a branch
   * outcome are different things and any coefficient joining them would be invented. A file with
   * one uncovered line therefore outranks a file with nine untaken branches and no uncovered line.
   *
   * <p>The count is absolute rather than a share, because the question is where the most work is
   * left, not which file reads worst: 90 of 100 lines executed leaves ten lines to test, and 1 of 2
   * leaves one, however much worse the second looks as a percentage.
   *
   * <p>Each key is reversed on its own. Reversing a chain reverses every key before it as well, so
   * {@code comparingInt(a).reversed().thenComparingInt(b).reversed()} orders by a ascending.
   */
  private static final Comparator<FileCoverage> BY_WORK_LEFT =
      Comparator.comparingInt(FileCoverage::uncoveredChangedLines)
          .reversed()
          .thenComparing(Comparator.comparingInt(FileCoverage::missedChangedBranches).reversed())
          .thenComparing(Comparator.comparingInt((FileCoverage file) -> file.executableLines)
              .reversed())
          .thenComparing(file -> file.path);

  private DiffCoverageReport() {}

  /**
   * Measures the changed lines of the files that sit under one of the source roots.
   *
   * <p>A changed file outside every root belongs to another module and is left out entirely, so
   * that a module's report says nothing about code it was never compiled from. A file under a root
   * that no report mentions is reported, since the reader has to decide whether the module was part
   * of the run or the file simply contributes no class.
   *
   * @param sourceRoots repository-relative source directories, such as {@code
   *     nullaway/src/main/java}
   * @param sources the working tree the uncovered lines are quoted from
   */
  static DiffCoverageReport measure(
      Map<String, NavigableSet<Integer>> changed,
      List<String> sourceRoots,
      JacocoLines lines,
      SourceLines sources) {
    DiffCoverageReport report = new DiffCoverageReport();
    for (Map.Entry<String, NavigableSet<Integer>> entry : new TreeMap<>(changed).entrySet()) {
      String path = entry.getKey();
      String key = sourceFileKey(path, sourceRoots);
      if (key == null) {
        continue;
      }
      if (!lines.covers(key)) {
        report.notInAnyReport.add(path);
        continue;
      }
      FileCoverage file = new FileCoverage(path);
      for (int number : entry.getValue()) {
        JacocoLines.Counters counters = lines.get(key, number);
        if (counters == null) {
          continue;
        }
        file.executableLines++;
        if (counters.isExecuted()) {
          file.executedLines++;
        }
        file.coveredBranches += counters.coveredBranches;
        file.totalBranches += counters.totalBranches;
        if (counters.isExecuted() && !counters.hasUntakenBranch()) {
          continue;
        }
        String source = sources.get(path, number);
        AffectedLine affected =
            new AffectedLine(
                number,
                counters.isExecuted(),
                counters.coveredBranches,
                counters.totalBranches,
                quote(source));
        (IGNORE_MARKER.matcher(source).find() ? file.acknowledged : file.affected).add(affected);
      }
      if (file.executableLines > 0) {
        report.measured.add(file);
      } else {
        report.nothingExecutable.add(path);
      }
    }
    report.measured.sort(BY_WORK_LEFT);
    return report;
  }

  /** Returns the source line as the report quotes it: stripped, and cut to a width that fits. */
  private static String quote(String source) {
    String stripped = source.strip();
    return stripped.length() <= QUOTE_WIDTH
        ? stripped
        : stripped.substring(0, QUOTE_WIDTH) + "...";
  }

  /** Returns the paths whose changed lines the counts came from. */
  List<String> measuredPaths() {
    return measured.stream().map(file -> file.path).collect(Collectors.toList());
  }

  /**
   * Returns whether the change reached none of this module's sources.
   *
   * <p>The conventions plugin gives every module the task, so a whole-build run would otherwise
   * print a block per module saying that a change in one of them touched none of the others.
   */
  boolean isSilent() {
    return measured.isEmpty() && notInAnyReport.isEmpty() && nothingExecutable.isEmpty();
  }

  int coveredBranches() {
    return measured.stream().mapToInt(file -> file.coveredBranches).sum();
  }

  int totalBranches() {
    return measured.stream().mapToInt(file -> file.totalBranches).sum();
  }

  int executedLines() {
    return measured.stream().mapToInt(file -> file.executedLines).sum();
  }

  int executableLines() {
    return measured.stream().mapToInt(file -> file.executableLines).sum();
  }

  /** Returns how many changed lines the run left unexecuted or took a branch of only one way. */
  int uncoveredLines() {
    return measured.stream()
        .mapToInt(file -> file.affected.size() + file.acknowledged.size())
        .sum();
  }

  /** Returns how many of the uncovered lines a comment in the source acknowledges. */
  int acknowledgedLines() {
    return measured.stream().mapToInt(file -> file.acknowledged.size()).sum();
  }

  /**
   * Returns the report as the text the task prints.
   *
   * <p>Only the files that still owe a test are described, so that the block reads as the work
   * left rather than as an inventory of the change. The fully covered ones are counted in one line,
   * which is what tells the reader they were measured at all.
   *
   * <p>Every limit trades completeness for a block a reader takes in at a glance, and a truncated
   * list states its own total so that the reader knows what was left out without counting what was
   * shown. The task writes the same report under {@link Limits#NONE} to a file and names it.
   *
   * @param scope what the run covered, such as the test task and its filter
   * @param filtered whether the run was narrowed to some of the tests
   * @param staleSources measured files edited after the report was written, whose line numbers the
   *     report therefore no longer names
   * @param limits how much of the report to print
   */
  String render(String scope, boolean filtered, List<String> staleSources, Limits limits) {
    if (isSilent()) {
      return "";
    }
    StringBuilder out = new StringBuilder("Diff coverage ").append(scope);
    if (filtered) {
      // The header names the filter, and a reader still quotes the total as the branch's coverage.
      out.append("\n  a filtered run leaves the rest of the change looking unexecuted");
    }
    List<FileCoverage> owing = measured.stream().filter(FileCoverage::needsAttention).toList();
    if (!owing.isEmpty()) {
      // Printed for one file too: a subheading that comes and goes between two runs reads as a
      // different code path rather than as a list with nothing to order.
      out.append("\n  ordered by uncovered changed lines, then uncovered branches");
    }
    if (!staleSources.isEmpty()) {
      out.append("\n\n  WARNING: edited after the report was written, so its line numbers no ")
          .append("longer match the source:");
      for (String path : staleSources) {
        out.append("\n    ").append(path);
      }
      out.append("\n  Re-run the tests before trusting the numbers below.");
    }
    Budget budget = new Budget(limits);
    appendOwing(out, owing, budget, limits.linesPerFile);
    appendFullyCovered(out);
    appendAcknowledged(out, budget, limits.linesPerFile);
    appendPaths(
        out,
        notInAnyReport,
        "No report read here covers these files, so they were probably not part of the run:");
    appendPaths(out, nothingExecutable, "Changed files with no executable changed lines:");
    out.append("\n\n");
    if (executableLines() == 0) {
      out.append(NO_EXECUTABLE_LINES);
      return out.toString();
    }
    out.append("Total: ")
        .append(executedLines())
        .append('/')
        .append(executableLines())
        .append(" changed lines executed");
    appendBranches(out, coveredBranches(), totalBranches());
    out.append('.');
    // Every uncovered line counts here, printed or not: the executed share alone reports a line
    // whose condition went one way as covered.
    out.append("\nUncovered changed code: ")
        .append(uncoveredLines())
        .append(uncoveredLines() == 1 ? " line" : " lines");
    if (acknowledgedLines() > 0) {
      out.append(" (").append(acknowledgedLines()).append(" acknowledged)");
    }
    return out.toString();
  }

  /** Appends a block per file owing a test, until the budget stops the list. */
  private void appendOwing(
      StringBuilder out, List<FileCoverage> owing, Budget budget, int linesPerFile) {
    int described = 0;
    for (FileCoverage file : owing) {
      if (budget.isSpent()) {
        break;
      }
      out.append("\n\n  ").append(file.path);
      out.append("\n    ")
          .append(file.executedLines)
          .append('/')
          .append(file.executableLines)
          .append(" changed lines executed");
      appendBranches(out, file.coveredBranches, file.totalBranches);
      out.append("\n    uncovered changed code:");
      budget.rows -=
          appendRows(
              out, file.affected, Math.min(linesPerFile, budget.rows), " more affected lines");
      budget.files--;
      described++;
    }
    if (described < owing.size()) {
      out.append("\n\n  ")
          .append(owing.size())
          .append(owing.size() == 1 ? " file owing" : " files owing")
          .append(" a test in total");
    }
  }

  /**
   * Appends the count of the files with nothing left to test, and their line and branch totals.
   *
   * <p>A file with nothing left to test is not work, and describing it would push a file that is
   * work out of the list. Counting it keeps "measured and fine" apart from "never measured", which
   * is the distinction the whole report exists to make.
   */
  private void appendFullyCovered(StringBuilder out) {
    List<FileCoverage> covered =
        measured.stream()
            .filter(file -> file.affected.isEmpty() && file.acknowledged.isEmpty())
            .toList();
    if (covered.isEmpty()) {
      return;
    }
    int lines = covered.stream().mapToInt(file -> file.executableLines).sum();
    int branches = covered.stream().mapToInt(file -> file.totalBranches).sum();
    out.append("\n\n  ")
        .append(covered.size())
        .append(covered.size() == 1 ? " changed file is" : " changed files are")
        .append(" fully covered (")
        .append(lines)
        .append(lines == 1 ? " line" : " lines");
    if (branches > 0) {
      out.append(", ").append(branches).append(branches == 1 ? " branch" : " branches");
    }
    out.append(')');
  }

  /**
   * Appends the uncovered lines a comment in the source acknowledges, under their own heading.
   *
   * <p>They are listed away from the work so that a reader looking for the next test skips them,
   * and listed at all so that an acknowledgement can be reviewed rather than only counted. What
   * the work list left of the budget is what they are listed with, and the count in the total
   * reports them whether or not any row here does.
   */
  private void appendAcknowledged(StringBuilder out, Budget budget, int linesPerFile) {
    List<FileCoverage> files =
        measured.stream().filter(file -> !file.acknowledged.isEmpty()).toList();
    if (files.isEmpty() || budget.isSpent()) {
      return;
    }
    out.append("\n\n  Acknowledged uncovered changed code:");
    int described = 0;
    for (FileCoverage file : files) {
      if (budget.isSpent()) {
        break;
      }
      out.append("\n    ").append(file.path);
      budget.rows -=
          appendRows(
              out,
              file.acknowledged,
              Math.min(linesPerFile, budget.rows),
              " more acknowledged lines");
      budget.files--;
      described++;
    }
    if (described < files.size()) {
      out.append("\n    ").append(files.size()).append(" files in total");
    }
  }

  /**
   * Appends up to allowed rows, then a row stating how many were left out.
   *
   * @return how many rows were printed, which is what the caller takes off its budget
   */
  private static int appendRows(
      StringBuilder out, List<AffectedLine> rows, int allowed, String more) {
    int shown = Math.min(allowed, rows.size());
    for (AffectedLine row : rows.subList(0, shown)) {
      out.append("\n      ").append(row.describe());
    }
    if (shown < rows.size()) {
      out.append("\n      ").append(rows.size() - shown).append(more);
    }
    return shown;
  }

  /**
   * Appends the branch pair, or nothing where the changed lines branch nowhere.
   *
   * <p>Line counts alone report a line as executed once any path through it ran, so a changed
   * condition tested one way reads as covered; the branch pair is what says otherwise.
   */
  private static void appendBranches(StringBuilder out, int covered, int total) {
    if (total > 0) {
      out.append(", ").append(covered).append('/').append(total).append(" branches covered");
    }
  }

  private static void appendPaths(StringBuilder out, List<String> paths, String heading) {
    if (paths.isEmpty()) {
      return;
    }
    out.append("\n\n  ").append(heading);
    for (String path : paths) {
      out.append("\n    ").append(path);
    }
  }

  /** Returns whether any of the paths sits under one of the source roots. */
  static boolean reaches(List<String> sourceRoots, Collection<String> paths) {
    return paths.stream().anyMatch(path -> sourceFileKey(path, sourceRoots) != null);
  }

  /**
   * Returns the path with its source root stripped, or null where no root contains it.
   *
   * <p>The longest matching root wins, since a root nested inside another would otherwise leave the
   * outer root's extra directories in the key and match no package JaCoCo reports.
   *
   * <p>A root's separators are normalised first. The paths come from a git diff and always use
   * {@code /}, while a root built from a {@code java.nio.file.Path} uses the platform separator, so
   * on Windows no root would match and every changed file would leave the report.
   */
  private static String sourceFileKey(String path, List<String> sourceRoots) {
    String longest = null;
    for (String sourceRoot : sourceRoots) {
      String root = sourceRoot.replace('\\', '/');
      String prefix = root.endsWith("/") ? root : root + "/";
      if (path.startsWith(prefix) && (longest == null || prefix.length() > longest.length())) {
        longest = prefix;
      }
    }
    return longest == null ? null : path.substring(longest.length());
  }
}
