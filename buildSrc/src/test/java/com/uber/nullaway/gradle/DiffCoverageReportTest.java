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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.nullaway.gradle.DiffCoverageReport.Limits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The changed lines of a module's own sources are split by whether the run covered them, and
 * rendered as the text the build prints.
 *
 * <p>A source root is repository-relative, so a path under it names a file of this module and a
 * path outside every root belongs to another module.
 */
class DiffCoverageReportTest {

  private static final String ROOT = "nullaway/src/main/java";
  private static final String PACKAGE = ROOT + "/com/uber/nullaway/";
  private static final String SAMPLE = PACKAGE + "Sample.java";
  private static final String OTHER = PACKAGE + "Other.java";
  private static final List<String> ROOTS = List.of(ROOT);
  private static final String SCOPE = "against master (5c1f60370), from :nullaway:test";

  /** The marker a source line carries to acknowledge that nothing covers it. */
  private static final String IGNORE = "// diff-coverage: ignore -- defensive safeguard";

  @TempDir Path directory;

  /** Returns one changed file with the given line numbers, as {@code measure} takes them. */
  private static Map<String, NavigableSet<Integer>> changed(String path, Integer... numbers) {
    Map<String, NavigableSet<Integer>> changed = new LinkedHashMap<>();
    changed.put(path, new TreeSet<>(Arrays.asList(numbers)));
    return changed;
  }

  /**
   * Returns a JaCoCo {@code <line>} element for Sample.java.
   *
   * @param coveredInstructions above zero the line counts as executed
   * @param coveredBranches branches the run took
   * @param missedBranches branches it did not
   */
  private static String line(
      int number, int coveredInstructions, int coveredBranches, int missedBranches) {
    return String.format(
        "<line nr=\"%d\" mi=\"%d\" ci=\"%d\" mb=\"%d\" cb=\"%d\"/>",
        number, coveredInstructions > 0 ? 0 : 4, coveredInstructions, missedBranches,
        coveredBranches);
  }

  /** Reads a report holding the given {@code <line>} elements for Sample.java. */
  private JacocoLines report(String lines) throws IOException {
    Path file = directory.resolve("jacocoTestReport.xml");
    Files.writeString(
        file,
        "<report name=\"test\"><package name=\"com/uber/nullaway\">"
            + "<sourcefile name=\"Sample.java\">"
            + lines
            + "</sourcefile></package></report>");
    return JacocoLines.read(List.of(file.toFile()));
  }

  /**
   * Measures against the working tree written here, in which a file nobody wrote has no text.
   *
   * <p>A test that says nothing about the quoted source therefore gets rows of counters alone,
   * which is also what a report of a file the task cannot read looks like.
   */
  private DiffCoverageReport measure(
      Map<String, NavigableSet<Integer>> changed, List<String> sourceRoots, JacocoLines lines) {
    return DiffCoverageReport.measure(
        changed, sourceRoots, lines, SourceLines.under(directory.toFile()));
  }

  /** Writes one source file of this module, whose first line is line 1. */
  private void writeSource(String name, String... lines) throws IOException {
    Path file = directory.resolve(PACKAGE + name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, String.join("\n", lines) + "\n");
  }

  /**
   * Measures one changed line per named file, each with the given counters.
   *
   * @param counters per file, in the order the names are given: covered instructions, covered
   *     branches, missed branches, repeated once per changed line
   */
  private DiffCoverageReport measureFiles(List<String> names, List<int[]> counters)
      throws IOException {
    StringBuilder xml = new StringBuilder("<report name=\"test\">");
    Map<String, NavigableSet<Integer>> changed = new LinkedHashMap<>();
    for (int index = 0; index < names.size(); index++) {
      String name = names.get(index);
      int[] file = counters.get(index);
      xml.append("<package name=\"com/uber/nullaway\"><sourcefile name=\"")
          .append(name)
          .append("\">");
      NavigableSet<Integer> numbers = new TreeSet<>();
      for (int number = 1; number <= file.length / 3; number++) {
        int at = (number - 1) * 3;
        xml.append(line(number, file[at], file[at + 1], file[at + 2]));
        numbers.add(number);
      }
      xml.append("</sourcefile></package>");
      changed.put(PACKAGE + name, numbers);
    }
    xml.append("</report>");
    Path report = directory.resolve("multi.xml");
    Files.writeString(report, xml.toString());
    return measure(changed, ROOTS, JacocoLines.read(List.of(report.toFile())));
  }

  /** Returns the counters of a file with executed lines followed by uncovered ones, no branches. */
  private static int[] someExecuted(int executed, int uncovered) {
    int[] counters = new int[(executed + uncovered) * 3];
    for (int line = 0; line < executed; line++) {
      counters[line * 3] = 4;
    }
    return counters;
  }

  /** Returns the file names of the measured paths, in the order the report puts them. */
  private static List<String> names(DiffCoverageReport report) {
    return report.measuredPaths().stream().map(path -> path.substring(path.lastIndexOf('/') + 1))
        .toList();
  }

  /**
   * Returns the one rendered line with the given prefix, failing where there is not exactly one.
   */
  private static String onlyLineStartingWith(String rendered, String prefix) {
    List<String> matching = rendered.lines().filter(line -> line.startsWith(prefix)).toList();
    assertEquals(
        1, matching.size(), () -> "lines starting with \"" + prefix + "\" in:\n" + rendered);
    return matching.get(0);
  }

  /** Returns the rows the block spends on uncovered lines, whichever file they belong to. */
  private static List<String> rows(String rendered) {
    return rendered.lines().filter(line -> line.startsWith("      ")).toList();
  }

  /** Returns the paths of the files the block describes under the main list. */
  private static List<String> describedPaths(String rendered) {
    return rendered.lines().filter(line -> line.startsWith("  " + ROOT)).map(String::strip)
        .toList();
  }

  @Nested
  class SplittingTheChangedLines {

    @Test
    void anExecutedLineCountsTowardsBothTheExecutedAndTheExecutableTotal() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 4, 0, 0)));
      assertAll(
          () -> assertEquals(1, result.executedLines(), "executedLines"),
          () -> assertEquals(1, result.executableLines(), "executableLines"),
          () -> assertEquals(0, result.uncoveredLines(), "uncoveredLines"));
    }

    @Test
    void anUnexecutedLineCountsTowardsTheExecutableTotalAlone() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 0, 0, 0)));
      assertAll(
          () -> assertEquals(0, result.executedLines(), "executedLines"),
          () -> assertEquals(1, result.executableLines(), "executableLines"),
          () -> assertEquals(1, result.uncoveredLines(), "uncoveredLines"));
    }

    @Test
    void aChangedLineTheReportOmitsCountsTowardsNeitherTotal() throws IOException {
      DiffCoverageReport result =
          measure(changed(SAMPLE, 10, 11), ROOTS, report(line(10, 4, 0, 0)));
      assertEquals(1, result.executableLines());
    }

    @Test
    void aLineWithABranchTakenOneWayCountsAsUncoveredThoughItRan() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 4, 1, 1)));
      assertAll(
          () -> assertEquals(1, result.executedLines(), "executedLines"),
          () -> assertEquals(1, result.uncoveredLines(), "uncoveredLines"));
    }

    @Test
    void aLineWithEveryBranchTakenIsNotCountedAsUncovered() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 4, 2, 0)));
      assertEquals(0, result.uncoveredLines());
    }

    @Test
    void onlyTheFilesTheCountsCameFromAreNamedAsMeasured() throws IOException {
      Map<String, NavigableSet<Integer>> changed = changed(SAMPLE, 10);
      changed.put(OTHER, new TreeSet<>(List.of(10)));
      DiffCoverageReport result = measure(changed, ROOTS, report(line(10, 0, 0, 0)));
      assertEquals(List.of(SAMPLE), result.measuredPaths());
    }
  }

  @Nested
  class FilesThatCannotBeMeasured {

    /** A file no report covers is the one thing that fails a threshold whatever the coverage. */
    @Test
    void aFileUnderARootThatNoReportMentionsIsNamedAsMissingFromTheReport() throws IOException {
      DiffCoverageReport result = measure(changed(OTHER, 10), ROOTS, report(""));
      assertEquals(List.of(OTHER), result.unmeasuredPaths());
    }

    /**
     * A comment-only change contributes to neither side of the share, so it has to be named
     * without joining the files that fail the threshold for want of a report.
     */
    @Test
    void aFileWhoseChangedLinesEmitNoBytecodeIsNamedSeparately() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(99, 4, 0, 0)));
      assertAll(
          () -> assertEquals(List.of(), result.unmeasuredPaths(), "unmeasuredPaths"),
          () ->
              assertLinesMatch(
                  List.of(
                      ">> the header >>",
                      "  Changed files with no executable changed lines:",
                      "    " + SAMPLE,
                      ">> the total >>"),
                  result.render(SCOPE, false, List.of(), Limits.NONE).lines().toList()));
    }

    @Test
    void aFileOfAnotherModuleIsMeasuredNowhereAndFailsNoThreshold() throws IOException {
      DiffCoverageReport result =
          measure(
              changed("other/src/main/java/com/uber/nullaway/Sample.java", 10),
              ROOTS,
              report(line(10, 0, 0, 0)));
      assertAll(
          () -> assertEquals(List.of(), result.unmeasuredPaths(), "unmeasuredPaths"),
          () -> assertEquals(0, result.executableLines(), "executableLines"));
    }

    /**
     * :nullaway compiles a second source root, the shared sources of
     * library-model/library-model-generator, so a file of either root is a file of this module.
     */
    @Test
    void aFileUnderASecondSourceRootIsMeasuredLikeOneUnderTheFirst() throws IOException {
      String shared = "library-model/library-model-generator/src/shared/java";
      DiffCoverageReport result =
          measure(
              changed(shared + "/com/uber/nullaway/Sample.java", 10),
              List.of(ROOT, shared),
              report(line(10, 4, 0, 0)));
      assertEquals(List.of(shared + "/com/uber/nullaway/Sample.java"), result.measuredPaths());
    }

    /**
     * Where one root is a prefix of another, the longer root has to win, or every file of the
     * nested root is keyed with the extra directories still in its package path.
     */
    @Test
    void aFileUnderTwoNestedRootsIsKeyedFromTheLongerOneWhicheverComesFirst() throws IOException {
      DiffCoverageReport result =
          measure(changed(SAMPLE, 10), List.of("nullaway/src", ROOT), report(line(10, 4, 0, 0)));
      assertEquals(List.of(SAMPLE), result.measuredPaths());
    }

    /**
     * A root built from a {@code java.nio.file.Path} carries the platform separator, while the
     * changed paths come from a git diff and always use {@code /}.
     */
    @Test
    void aRootSpelledWithBackslashesStillMatchesAGitPath() throws IOException {
      DiffCoverageReport result =
          measure(changed(SAMPLE, 10), List.of("nullaway\\src\\main\\java"),
              report(line(10, 4, 0, 0)));
      assertEquals(List.of(SAMPLE), result.measuredPaths());
    }

    /** The test tree of this module shares its prefix and is still not a main source root. */
    @Test
    void aTestSourceOfThisModuleIsMeasuredNowhereAndFailsNoThreshold() throws IOException {
      DiffCoverageReport result =
          measure(
              changed("nullaway/src/test/java/com/uber/nullaway/SampleTest.java", 10),
              ROOTS,
              report(line(10, 0, 0, 0)));
      assertAll(
          () -> assertEquals(List.of(), result.unmeasuredPaths(), "unmeasuredPaths"),
          () -> assertEquals(0, result.executableLines(), "executableLines"));
    }
  }

  @Nested
  class QuotingTheSource {

    @Test
    void anUncoveredLineIsQuotedFromTheWorkingTreeWithItsIndentationStripped() throws IOException {
      writeSource("Sample.java", "class Sample {", "    return null;");
      DiffCoverageReport result = measure(changed(SAMPLE, 2), ROOTS, report(line(2, 0, 0, 0)));
      assertEquals(
          "      2: not executed: return null;",
          onlyLineStartingWith(result.render(SCOPE, false, List.of(), Limits.NONE), "      2: "));
    }

    /** A generated line can run to thousands of characters and would wrap the whole block. */
    @Test
    void aLineLongerThanTheQuoteWidthIsCutAndMarked() throws IOException {
      writeSource("Sample.java", "x".repeat(130));
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 0)));
      assertEquals(
          "      1: not executed: " + "x".repeat(100) + "...",
          onlyLineStartingWith(result.render(SCOPE, false, List.of(), Limits.NONE), "      1: "));
    }

    @Test
    void aLineExactlyAtTheQuoteWidthIsQuotedWhole() throws IOException {
      writeSource("Sample.java", "x".repeat(100));
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 0)));
      assertEquals(
          "      1: not executed: " + "x".repeat(100),
          onlyLineStartingWith(result.render(SCOPE, false, List.of(), Limits.NONE), "      1: "));
    }

    /**
     * The counters are the report's subject, so a file that was deleted or that this JVM cannot
     * decode is still measured; only the quote goes.
     */
    @Test
    void aLineWithNoSourceToQuoteIsReportedWithItsCountersAlone() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 7), ROOTS, report(line(7, 0, 0, 0)));
      assertEquals(
          "      7: not executed",
          onlyLineStartingWith(result.render(SCOPE, false, List.of(), Limits.NONE), "      7: "));
    }
  }

  @Nested
  class AcknowledgingAnUncoveredLine {

    @Test
    void aLineMarkedInACommentIsListedUnderTheAcknowledgedHeadingRatherThanAsWork()
        throws IOException {
      writeSource("Sample.java", "throw new AssertionError(); " + IGNORE);
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 0)));
      assertLinesMatch(
          List.of(
              "Diff coverage " + SCOPE,
              "",
              "  Acknowledged uncovered changed code:",
              "    " + SAMPLE,
              "      1: not executed: throw new AssertionError(); " + IGNORE,
              "",
              "Total: 0/1 changed lines executed.",
              "Uncovered changed code: 1 line (1 acknowledged)"),
          result.render(SCOPE, false, List.of(), Limits.NONE).lines().toList());
    }

    /** The marker takes the line out of the work, not out of the measurement. */
    @Test
    void anAcknowledgedLineStillCountsAsUnexecutedInTheTotal() throws IOException {
      writeSource("Sample.java", "throw new AssertionError(); " + IGNORE, "return 1;");
      DiffCoverageReport result =
          measure(changed(SAMPLE, 1, 2), ROOTS, report(line(1, 0, 0, 0) + line(2, 4, 0, 0)));
      assertAll(
          () -> assertEquals(1, result.executedLines(), "executedLines"),
          () -> assertEquals(2, result.executableLines(), "executableLines"),
          () -> assertEquals(1, result.acknowledgedLines(), "acknowledgedLines"));
    }

    @Test
    void aMarkerInABlockCommentAcknowledgesItsLineToo() throws IOException {
      writeSource("Sample.java", "throw new AssertionError(); /* diff-coverage: ignore */");
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 0)));
      assertEquals(1, result.acknowledgedLines());
    }

    /** The marker follows a comment opener, so a line that only names it acknowledges nothing. */
    @Test
    void aMarkerWithNoCommentOpenerLeavesTheLineInTheWork() throws IOException {
      writeSource("Sample.java", "String marker = \"diff-coverage: ignore\";");
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 0)));
      String rendered = result.render(SCOPE, false, List.of(), Limits.NONE);
      assertAll(
          () -> assertEquals(0, result.acknowledgedLines(), "acknowledgedLines"),
          () ->
              assertEquals(
                  List.of("      1: not executed: String marker = \"diff-coverage: ignore\";"),
                  rows(rendered),
                  "rows"),
          () -> assertFalse(rendered.contains("Acknowledged"), rendered));
    }

    /** A hyphenated word after the marker is another marker, not this one. */
    @Test
    void aMarkerWhoseWordOnlyStartsWithIgnoreDoesNotAcknowledge() throws IOException {
      writeSource("Sample.java", "throw new AssertionError(); // diff-coverage: ignore-until-1.2");
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 0)));
      assertEquals(0, result.acknowledgedLines());
    }

    @Test
    void aCoveredLineCarryingTheMarkerIsNotCountedAsAcknowledged() throws IOException {
      writeSource("Sample.java", "return 1; " + IGNORE);
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 4, 0, 0)));
      assertAll(
          () -> assertEquals(0, result.acknowledgedLines(), "acknowledgedLines"),
          () -> assertEquals(0, result.uncoveredLines(), "uncoveredLines"));
    }

    /**
     * Counted as fully covered, a file whose remaining work is only acknowledged would report that
     * every changed line of it ran.
     */
    @Test
    void aFileWhoseUncoveredLinesAreAllAcknowledgedIsNotCountedAsFullyCovered() throws IOException {
      writeSource("Sample.java", "throw new AssertionError(); " + IGNORE);
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 0)));
      String rendered = result.render(SCOPE, false, List.of(), Limits.NONE);
      assertFalse(rendered.contains("fully covered"), rendered);
    }

    @Test
    void aFileWithBothKindsOfUncoveredLineIsDescribedInBothLists() throws IOException {
      writeSource("Sample.java", "throw new AssertionError(); " + IGNORE, "return 1;");
      DiffCoverageReport result =
          measure(changed(SAMPLE, 1, 2), ROOTS, report(line(1, 0, 0, 0) + line(2, 0, 0, 0)));
      assertLinesMatch(
          List.of(
              "Diff coverage " + SCOPE,
              "  ordered by uncovered changed lines, then uncovered branches",
              "",
              "  " + SAMPLE,
              "    0/2 changed lines executed",
              "    uncovered changed code:",
              "      2: not executed: return 1;",
              "",
              "  Acknowledged uncovered changed code:",
              "    " + SAMPLE,
              "      1: not executed: throw new AssertionError(); " + IGNORE,
              "",
              "Total: 0/2 changed lines executed.",
              "Uncovered changed code: 2 lines (1 acknowledged)"),
          result.render(SCOPE, false, List.of(), Limits.NONE).lines().toList());
    }

    /**
     * The work is printed first, so a block whose budget the work spends says how many lines are
     * acknowledged and lists none of them.
     */
    @Test
    void theWorkListSpendsTheBudgetBeforeTheAcknowledgedListSeesIt() throws IOException {
      writeSource("Sample.java", "a();", "b();", "c(); " + IGNORE);
      DiffCoverageReport result =
          measure(
              changed(SAMPLE, 1, 2, 3),
              ROOTS,
              report(line(1, 0, 0, 0) + line(2, 0, 0, 0) + line(3, 0, 0, 0)));
      String rendered = result.render(SCOPE, false, List.of(), new Limits(4, 10, 2));
      assertAll(
          () ->
              assertEquals(
                  List.of("      1: not executed: a();", "      2: not executed: b();"),
                  rows(rendered),
                  "rows"),
          () -> assertFalse(rendered.contains("Acknowledged"), rendered),
          () ->
              assertEquals(
                  "Uncovered changed code: 3 lines (1 acknowledged)",
                  onlyLineStartingWith(rendered, "Uncovered changed code: ")));
    }

    @Test
    void theAcknowledgedFilesPastTheFileCapAreReplacedByTheirCount() throws IOException {
      writeSource("First.java", "a(); " + IGNORE);
      writeSource("Second.java", "b(); " + IGNORE);
      String rendered =
          measureFiles(
                  List.of("First.java", "Second.java"),
                  List.of(someExecuted(0, 1), someExecuted(0, 1)))
              .render(SCOPE, false, List.of(), new Limits(1, 10, 50));
      assertLinesMatch(
          List.of(
              ">> the header >>",
              "  Acknowledged uncovered changed code:",
              "    " + PACKAGE + "First.java",
              "      1: not executed: a(); " + IGNORE,
              "    2 files in total",
              ">> the totals >>"),
          rendered.lines().toList());
    }

    @Test
    void theAcknowledgedListIsCutByThePerFileCapLikeTheWorkIs() throws IOException {
      writeSource("Sample.java", "a(); " + IGNORE, "b(); " + IGNORE, "c(); " + IGNORE);
      DiffCoverageReport result =
          measure(
              changed(SAMPLE, 1, 2, 3),
              ROOTS,
              report(line(1, 0, 0, 0) + line(2, 0, 0, 0) + line(3, 0, 0, 0)));
      assertEquals(
          List.of("      1: not executed: a(); " + IGNORE, "      2 more acknowledged lines"),
          rows(result.render(SCOPE, false, List.of(), new Limits(4, 1, 50))));
    }
  }

  @Nested
  class Rendering {

    @Test
    void aMeasuredFileRendersItsCountsAndOneRowPerUncoveredLine() throws IOException {
      writeSource("Sample.java", "if (a) {", "  return 1;", "if (b) {");
      DiffCoverageReport result =
          measure(
              changed(SAMPLE, 1, 2, 3),
              ROOTS,
              report(line(1, 4, 1, 1) + line(2, 0, 0, 0) + line(3, 0, 0, 2)));
      assertLinesMatch(
          List.of(
              "Diff coverage " + SCOPE,
              "  ordered by uncovered changed lines, then uncovered branches",
              "",
              "  " + SAMPLE,
              "    1/3 changed lines executed, 1/4 branches covered",
              "    uncovered changed code:",
              "      1: 1 of 2 branches covered: if (a) {",
              "      2: not executed: return 1;",
              "      3: not executed; 0 of 2 branches covered: if (b) {",
              "",
              "Total: 1/3 changed lines executed, 1/4 branches covered.",
              "Uncovered changed code: 3 lines"),
          result.render(SCOPE, false, List.of(), Limits.NONE).lines().toList());
    }

    /**
     * Reported once as unexecuted and again as partly taken, the line reads as two pieces of work,
     * and every count that adds the rows up is inflated by it.
     */
    @Test
    void aLineNeitherExecutedNorFullyBranchedTakesOneRowCarryingBothCounts() throws IOException {
      writeSource("Sample.java", "if (sourceType == null) {");
      DiffCoverageReport result = measure(changed(SAMPLE, 1), ROOTS, report(line(1, 0, 0, 2)));
      String rendered = result.render(SCOPE, false, List.of(), Limits.NONE);
      assertAll(
          () ->
              assertEquals(
                  List.of(
                      "      1: not executed; 0 of 2 branches covered: if (sourceType == null) {"),
                  rows(rendered),
                  "rows"),
          () ->
              assertEquals(
                  "Uncovered changed code: 1 line",
                  onlyLineStartingWith(rendered, "Uncovered changed code: ")));
    }

    /**
     * A comment-only change to this module's sources is still a change the reader may be checking
     * on, so the block names the file and says the total is not a coverage figure.
     */
    @Test
    void aChangeOfNonExecutableLinesRendersThatInsteadOfAZeroTotal() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(99, 4, 0, 0)));
      assertLinesMatch(
          List.of(
              "Diff coverage " + SCOPE,
              "",
              "  Changed files with no executable changed lines:",
              "    " + SAMPLE,
              "",
              "Total: no changed executable lines under this module's source roots."),
          result.render(SCOPE, false, List.of(), Limits.NONE).lines().toList());
    }

    /**
     * The conventions plugin gives every module the task, so a whole-build run would otherwise
     * print a block per module saying that a change in one of them touched none of the others.
     */
    @Test
    void aChangeThatReachesNoneOfThisModulesSourcesRendersNothingAtAll() throws IOException {
      DiffCoverageReport result = measure(Map.of(), ROOTS, report(""));
      assertEquals("", result.render(SCOPE, false, List.of(), Limits.NONE));
    }

    @Test
    void aChangeOfAnotherModuleAloneRendersNothingAtAll() throws IOException {
      DiffCoverageReport result =
          measure(
              changed("other/src/main/java/com/uber/nullaway/Sample.java", 10),
              ROOTS,
              report(line(10, 0, 0, 0)));
      assertEquals("", result.render(SCOPE, false, List.of(), Limits.NONE));
    }

    @Test
    void anEditAfterTheRunIsAnnouncedBeforeTheCountsItInvalidates() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 0, 0, 0)));
      assertLinesMatch(
          List.of(
              "Diff coverage " + SCOPE,
              "  ordered by uncovered changed lines, then uncovered branches",
              "",
              ">> the warning >>",
              "    " + SAMPLE,
              "  Re-run the tests before trusting the numbers below.",
              ">> the counts >>"),
          result.render(SCOPE, false, List.of(SAMPLE), Limits.NONE).lines().toList());
    }

    @Test
    void aFileNoReportMentionsAndOneWithNoBytecodeGetDifferentHeadings() throws IOException {
      Map<String, NavigableSet<Integer>> changed = changed(SAMPLE, 10);
      changed.put(OTHER, new TreeSet<>(List.of(10)));
      DiffCoverageReport result = measure(changed, ROOTS, report(line(99, 4, 0, 0)));
      assertLinesMatch(
          List.of(
              "Diff coverage " + SCOPE,
              "",
              "  No report read here covers these files, so they were probably not part of the"
                  + " run:",
              "    " + OTHER,
              "",
              "  Changed files with no executable changed lines:",
              "    " + SAMPLE,
              "",
              "Total: no changed executable lines under this module's source roots."),
          result.render(SCOPE, false, List.of(), Limits.NONE).lines().toList());
    }

    /** A change whose lines branch nowhere gets no branch pair, rather than a 0/0 one. */
    @Test
    void aChangeWithNoBranchAtAllReportsLinesAlone() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 4, 0, 0)));
      assertEquals(
          "Total: 1/1 changed lines executed.",
          onlyLineStartingWith(result.render(SCOPE, false, List.of(), Limits.NONE), "Total: "));
    }

    /**
     * A branch nothing took on a line something ran is the case line counts cannot report: the
     * line reads as executed, and only the branch pair says the condition was tested one way.
     */
    @Test
    void everyLineExecutedWithABranchUntakenStillReportsTheBranchPair() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 4, 1, 1)));
      assertEquals(
          "Total: 1/1 changed lines executed, 1/2 branches covered.",
          onlyLineStartingWith(result.render(SCOPE, false, List.of(), Limits.NONE), "Total: "));
    }

    @Test
    void aFullyTakenBranchCountsTowardsBothSidesOfThePair() throws IOException {
      DiffCoverageReport result = measure(changed(SAMPLE, 10), ROOTS, report(line(10, 4, 2, 0)));
      assertEquals(
          "Total: 1/1 changed lines executed, 2/2 branches covered.",
          onlyLineStartingWith(result.render(SCOPE, false, List.of(), Limits.NONE), "Total: "));
    }

    @Test
    void theBranchPairOfTheTotalSumsTheFiles() throws IOException {
      String rendered =
          measureFiles(
                  List.of("A.java", "B.java"), List.of(new int[] {4, 1, 1}, new int[] {4, 1, 3}))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertEquals(
          "Total: 2/2 changed lines executed, 2/6 branches covered.",
          onlyLineStartingWith(rendered, "Total: "));
    }

    /**
     * The executed share reports a line whose condition went one way as covered, so it is not the
     * count of what is left to do.
     */
    @Test
    void theUncoveredTotalCountsTheRowsRatherThanTheUnexecutedLines() throws IOException {
      String rendered =
          measureFiles(
                  List.of("A.java", "B.java"), List.of(new int[] {4, 1, 1}, new int[] {0, 0, 0}))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertAll(
          () ->
              assertEquals(
                  "Total: 1/2 changed lines executed, 1/2 branches covered.",
                  onlyLineStartingWith(rendered, "Total: ")),
          () ->
              assertEquals(
                  "Uncovered changed code: 2 lines",
                  onlyLineStartingWith(rendered, "Uncovered changed code: ")));
    }

    @Test
    void aChangeWithEveryLineCoveredReportsNoUncoveredCode() throws IOException {
      String rendered =
          measureFiles(List.of("A.java"), List.of(someExecuted(2, 0)))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertEquals(
          "Uncovered changed code: 0 lines",
          onlyLineStartingWith(rendered, "Uncovered changed code: "));
    }

    @Test
    void theOrderingNoteAppearsWhereMoreThanOneFileIsDescribed() throws IOException {
      String rendered =
          measureFiles(
                  List.of("A.java", "B.java"), List.of(new int[] {0, 0, 0}, new int[] {0, 0, 0}))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertEquals(
          "  ordered by uncovered changed lines, then uncovered branches",
          onlyLineStartingWith(rendered, "  ordered "));
    }

    /** A subheading that comes and goes between two runs reads as a different code path. */
    @Test
    void theOrderingNoteAppearsForASingleFileToo() throws IOException {
      String rendered =
          measureFiles(List.of("A.java"), List.of(new int[] {0, 0, 0}))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertEquals(
          "  ordered by uncovered changed lines, then uncovered branches",
          onlyLineStartingWith(rendered, "  ordered "));
    }

    @Test
    void theOrderingNoteIsLeftOutWhereNoFileOwesATest() throws IOException {
      String rendered =
          measureFiles(List.of("A.java"), List.of(someExecuted(1, 0)))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertFalse(rendered.contains("ordered by"), rendered);
    }

    @Test
    void aFilteredRunSaysWhatItsNumbersLeaveOut() throws IOException {
      String rendered =
          measureFiles(List.of("A.java"), List.of(new int[] {0, 0, 0}))
              .render(SCOPE, true, List.of(), Limits.NONE);
      assertEquals(
          "  a filtered run leaves the rest of the change looking unexecuted",
          onlyLineStartingWith(rendered, "  a filtered run"));
    }

    @Test
    void anUnfilteredRunSaysNothingAboutAFilter() throws IOException {
      String rendered =
          measureFiles(List.of("A.java"), List.of(new int[] {0, 0, 0}))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertFalse(rendered.contains("filtered run"), rendered);
    }

    @Test
    void theFullyCoveredCountCarriesTheLinesAndBranchesBehindIt() throws IOException {
      String rendered =
          measureFiles(
                  List.of("Owing.java", "Covered.java"),
                  List.of(someExecuted(0, 1), new int[] {4, 2, 0, 4, 0, 0}))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertEquals(
          "  1 changed file is fully covered (2 lines, 2 branches)",
          onlyLineStartingWith(rendered, "  1 changed file"));
    }

    /** A file whose lines branch nowhere gets no branch count, as elsewhere in the block. */
    @Test
    void theFullyCoveredCountLeavesOutBranchesWhereThereAreNone() throws IOException {
      String rendered =
          measureFiles(
                  List.of("Owing.java", "Covered.java"),
                  List.of(someExecuted(0, 1), someExecuted(2, 0)))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertEquals(
          "  1 changed file is fully covered (2 lines)",
          onlyLineStartingWith(rendered, "  1 changed file"));
    }
  }

  @Nested
  class TheLineCaps {

    /** Returns a file of count unexecuted lines numbered from one. */
    private DiffCoverageReport unexecutedLines(int count) throws IOException {
      StringBuilder xml = new StringBuilder();
      NavigableSet<Integer> numbers = new TreeSet<>();
      for (int number = 1; number <= count; number++) {
        xml.append(line(number, 0, 0, 0));
        numbers.add(number);
      }
      return measure(Map.of(SAMPLE, numbers), ROOTS, report(xml.toString()));
    }

    @Test
    void linesUpToThePerFileCapAreAllListedWithNoTotal() throws IOException {
      String rendered = unexecutedLines(3).render(SCOPE, false, List.of(), new Limits(4, 3, 50));
      assertEquals(
          List.of("      1: not executed", "      2: not executed", "      3: not executed"),
          rows(rendered));
    }

    @Test
    void theLinesPastThePerFileCapAreReplacedByTheirCount() throws IOException {
      String rendered = unexecutedLines(5).render(SCOPE, false, List.of(), new Limits(4, 3, 50));
      assertEquals(
          List.of(
              "      1: not executed",
              "      2: not executed",
              "      3: not executed",
              "      2 more affected lines"),
          rows(rendered));
    }

    /**
     * Without a cap over the whole block, a change spread across many files spends one screen per
     * file however small each file's list is.
     */
    @Test
    void theTotalCapStopsTheRowsPartWayThroughASecondFile() throws IOException {
      String rendered =
          measureFiles(
                  List.of("A.java", "B.java"),
                  List.of(someExecuted(0, 3), someExecuted(0, 3)))
              .render(SCOPE, false, List.of(), new Limits(4, 10, 4));
      assertEquals(
          List.of(
              "      1: not executed",
              "      2: not executed",
              "      3: not executed",
              "      1: not executed",
              "      2 more affected lines"),
          rows(rendered));
    }

    /** A file the budget leaves no row for is not worth its heading, so it joins the total. */
    @Test
    void aFileTheTotalCapLeavesNoRowForIsCountedRatherThanDescribed() throws IOException {
      String rendered =
          measureFiles(
                  List.of("A.java", "B.java"),
                  List.of(someExecuted(0, 2), someExecuted(0, 2)))
              .render(SCOPE, false, List.of(), new Limits(4, 10, 2));
      assertAll(
          () ->
              assertEquals(
                  List.of(PACKAGE + "A.java"), describedPaths(rendered), "files described"),
          () ->
              assertEquals(
                  "  2 files owing a test in total",
                  onlyLineStartingWith(rendered, "  2 files")));
    }

    /** A limit of zero is a limit; only a negative one names no number of rows to print. */
    @Test
    void aLimitOfZeroPrintsTheFileAndNoneOfItsRows() throws IOException {
      String rendered = unexecutedLines(3).render(SCOPE, false, List.of(), new Limits(4, 0, 50));
      assertEquals(List.of("      3 more affected lines"), rows(rendered));
    }

    /**
     * A build property can spell a negative limit, and cutting the rows to it would throw an index
     * out of the list rather than saying what is wrong.
     */
    @Test
    void aNegativeLimitIsRefusedWithAMessageNamingIt() {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> new Limits(4, -1, 50));
      assertEquals(
          "a diff coverage limit cannot be negative: files=4, linesPerFile=-1, lines=50",
          thrown.getMessage());
    }

    @Test
    void showingEverythingSpendsNoCapAtAll() throws IOException {
      String rendered = unexecutedLines(40).render(SCOPE, false, List.of(), Limits.NONE);
      assertAll(
          () -> assertEquals(40, rows(rendered).size(), "rows"),
          () -> assertFalse(rendered.contains("more affected lines"), rendered));
    }
  }

  @Nested
  class Ordering {

    @Test
    void theFileWithMoreUncoveredChangedLinesComesFirst() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("Few.java", "Many.java"),
              List.of(new int[] {0, 0, 0}, new int[] {0, 0, 0, 0, 0, 0}));
      assertEquals(List.of("Many.java", "Few.java"), names(result));
    }

    /**
     * Ordering by share would put HalfCovered.java first at 50% against WellCovered.java's 80%,
     * and send the reader to the file with one line left to test rather than the one with two.
     */
    @Test
    void moreUncoveredLinesBeatAWorseShareOfThem() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("HalfCovered.java", "WellCovered.java"),
              List.of(someExecuted(1, 1), someExecuted(8, 2)));
      assertEquals(List.of("WellCovered.java", "HalfCovered.java"), names(result));
    }

    /**
     * A single uncovered line outranks nine untaken branches, since the two are different things
     * and any coefficient joining them into one score would be invented.
     */
    @Test
    void oneUncoveredLineOutranksAnyNumberOfUntakenBranches() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("OneLine.java", "NineBranches.java"),
              List.of(new int[] {0, 0, 0}, new int[] {4, 1, 9}));
      assertEquals(List.of("OneLine.java", "NineBranches.java"), names(result));
    }

    @Test
    void filesTyingOnUncoveredLinesGoByMissedBranchOutcomes() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("OneMissed.java", "ThreeMissed.java"),
              List.of(new int[] {4, 1, 1}, new int[] {4, 1, 3}));
      assertEquals(List.of("ThreeMissed.java", "OneMissed.java"), names(result));
    }

    /** A line reported as 0 of 4 branches covered owes more than one reported as 1 of 2. */
    @Test
    void missedBranchesCountOutcomesRatherThanLinesCarryingThem() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("TwoLinesOneMissedEach.java", "OneLineFourMissed.java"),
              List.of(new int[] {4, 1, 1, 4, 1, 1}, new int[] {4, 0, 4}));
      assertEquals(
          List.of("OneLineFourMissed.java", "TwoLinesOneMissedEach.java"), names(result));
    }

    @Test
    void aFullyCoveredFileComesAfterOneWithAnUntakenBranch() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("Covered.java", "Branch.java"),
              List.of(new int[] {4, 2, 0}, new int[] {4, 1, 1}));
      assertEquals(List.of("Branch.java", "Covered.java"), names(result));
    }

    /**
     * Between files owing the same, the larger change is the one worth reading first. The names
     * are chosen so that ordering by path alone would put them the other way round.
     */
    @Test
    void filesOwingTheSameGoByHowMuchTheyChanged() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("Alpha.java", "Beta.java"),
              List.of(someExecuted(1, 1), someExecuted(3, 1)));
      assertEquals(List.of("Beta.java", "Alpha.java"), names(result));
    }

    /** Ordering by path keeps two runs of the same change printing the same block. */
    @Test
    void filesAlikeOnEveryCountGoByPath() throws IOException {
      DiffCoverageReport result =
          measureFiles(
              List.of("Beta.java", "Alpha.java"),
              List.of(new int[] {0, 0, 0}, new int[] {0, 0, 0}));
      assertEquals(List.of("Alpha.java", "Beta.java"), names(result));
    }

    /** Ordered by uncovered lines alone, the acknowledged file would be read first. */
    @Test
    void aFileWhoseUncoveredLinesAreAcknowledgedComesAfterOneOwingASingleTest()
        throws IOException {
      writeSource("Acknowledged.java", "a(); " + IGNORE, "b(); " + IGNORE);
      DiffCoverageReport result =
          measureFiles(
              List.of("Acknowledged.java", "Owing.java"),
              List.of(someExecuted(0, 2), someExecuted(0, 1)));
      assertEquals(List.of("Owing.java", "Acknowledged.java"), names(result));
    }
  }

  @Nested
  class FilesOwingNothing {

    /**
     * Silence would leave a fully covered file looking like one the run never measured, which is
     * the confusion the separate headings elsewhere in the block exist to prevent.
     */
    @Test
    void aFullyCoveredFileIsCountedRatherThanDescribed() throws IOException {
      String rendered =
          measureFiles(
                  List.of("Owing.java", "Covered.java"),
                  List.of(someExecuted(0, 1), someExecuted(1, 0)))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertAll(
          () ->
              assertEquals(
                  List.of(PACKAGE + "Owing.java"), describedPaths(rendered), "files described"),
          () ->
              assertEquals(
                  "  1 changed file is fully covered (1 line)",
                  onlyLineStartingWith(rendered, "  1 changed file")));
    }

    @Test
    void severalFullyCoveredFilesAreCountedInOneLine() throws IOException {
      String rendered =
          measureFiles(
                  List.of("Owing.java", "First.java", "Second.java"),
                  List.of(someExecuted(0, 1), someExecuted(1, 0), someExecuted(1, 0)))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertEquals(
          "  2 changed files are fully covered (2 lines)",
          onlyLineStartingWith(rendered, "  2 changed files"));
    }

    /** A file whose lines all ran still owes a test where a branch went one way. */
    @Test
    void aFileOwingOnlyABranchIsStillDescribed() throws IOException {
      String rendered =
          measureFiles(List.of("Branch.java"), List.of(new int[] {4, 1, 1}))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertAll(
          () ->
              assertEquals(
                  List.of(PACKAGE + "Branch.java"), describedPaths(rendered), "files described"),
          () -> assertFalse(rendered.contains("fully covered"), rendered));
    }

    @Test
    void aRunWithNoFullyCoveredFileSaysNothingAboutThem() throws IOException {
      String rendered =
          measureFiles(List.of("Owing.java"), List.of(someExecuted(0, 1)))
              .render(SCOPE, false, List.of(), Limits.NONE);
      assertFalse(rendered.contains("fully covered"), rendered);
    }

    /**
     * The cap is a budget for the files worth reading, so a covered one must not spend any of it.
     */
    @Test
    void aFullyCoveredFileDoesNotSpendTheFileCap() throws IOException {
      String rendered =
          measureFiles(
                  List.of("Covered.java", "OwingA.java", "OwingB.java"),
                  List.of(someExecuted(1, 0), someExecuted(0, 1), someExecuted(0, 1)))
              .render(SCOPE, false, List.of(), new Limits(2, 10, 50));
      assertAll(
          () ->
              assertEquals(
                  List.of(PACKAGE + "OwingA.java", PACKAGE + "OwingB.java"),
                  describedPaths(rendered),
                  "files described"),
          () -> assertFalse(rendered.contains("owing a test in total"), rendered));
    }
  }

  @Nested
  class TheFileCap {

    @Test
    void filesUpToTheCapAreAllDescribedWithNoTotal() throws IOException {
      String rendered =
          measureFiles(
                  List.of("A.java", "B.java"),
                  List.of(new int[] {0, 0, 0}, new int[] {0, 0, 0}))
              .render(SCOPE, false, List.of(), new Limits(2, 10, 50));
      assertAll(
          () -> assertEquals(2, describedPaths(rendered).size(), "files described"),
          () -> assertFalse(rendered.contains("in total"), rendered));
    }

    /** The files dropped are the ones with least to say, since the list is ordered by work left. */
    @Test
    void onePastTheCapIsReplacedByTheFileTotalAndTheLeastNeedyFileGoes() throws IOException {
      String rendered =
          measureFiles(
                  List.of("Small.java", "Large.java", "Medium.java"),
                  List.of(new int[] {0, 0, 0}, new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0},
                      new int[] {0, 0, 0, 0, 0, 0}))
              .render(SCOPE, false, List.of(), new Limits(2, 10, 50));
      assertAll(
          () ->
              assertEquals(
                  List.of(PACKAGE + "Large.java", PACKAGE + "Medium.java"),
                  describedPaths(rendered),
                  "files described"),
          () ->
              assertEquals(
                  "  3 files owing a test in total",
                  onlyLineStartingWith(rendered, "  3 files")),
          () ->
              assertEquals(
                  "Total: 0/6 changed lines executed.",
                  onlyLineStartingWith(rendered, "Total: ")));
    }
  }
}
