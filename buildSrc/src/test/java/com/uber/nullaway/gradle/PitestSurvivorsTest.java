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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The report prints each live mutant under its file and line, with the line's source text and the
 * tests PIT recorded as reaching it, and counts every other mutant as killed.
 */
class PitestSurvivorsTest {

  /** What a report with live mutants ends with, before the paths of the full report. */
  private static final String LIVE_FOOTER =
      "\n\n"
          + "For each live mutant, decide which case it is:\n"
          + "  - the tests miss behavior the change defines: add or strengthen a test,"
          + " next to the tests named for its line\n"
          + "  - no caller can observe the difference, so the mutant is equivalent:"
          + " say so in the pull request\n"
          + "  - you cannot tell: report it as unresolved\n"
          + "\n"
          + "PIT generates no mutant on a line that calls Verify or Preconditions.";

  @TempDir Path repository;

  /**
   * Returns the mutants.tsv line for a mutant in method {@code m} of the given class of package
   * {@code com.x}, covered by the given tests of class {@code com.x.T}.
   */
  private static String mutation(
      String status, String className, String file, int line, String description, String... tests) {
    StringBuilder out =
        new StringBuilder(
            status + "\tcom.x." + className + "\tm\t" + file + "\t" + line + "\t" + description
                + " for com/x/" + className + "::m");
    for (String test : tests) {
      out.append("\tcom.x.").append(test).append("(com.x.T)");
    }
    return out.toString();
  }

  /** Writes the mutants to mutants.tsv and returns the path. */
  private Path mutantsFile(String... mutations) throws IOException {
    Path file = repository.resolve("mutants.tsv");
    Files.writeString(file, String.join("\n", mutations) + "\n");
    return file;
  }

  /** Reads a run whose sources are com/x/A.java and com/x/B.java, each with five lines. */
  private PitestSurvivors run(String... mutations) throws IOException {
    Path sources = repository.resolve("src");
    Files.createDirectories(sources.resolve("com/x"));
    Files.writeString(sources.resolve("com/x/A.java"), "a1\n  a2\na3\na4\na5\n");
    Files.writeString(sources.resolve("com/x/B.java"), "b1\nb2\nb3\nb4\nb5\n");
    return PitestSurvivors.read(
        "against origin/master (abc), all tests",
        mutantsFile(mutations).toFile(),
        repository.toFile(),
        List.of(new File("absent"), sources.toFile()));
  }

  @Test
  void liveMutantsAreGroupedByFileAndLineWithTheTestsThatReachThem() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  4 mutants: 1 killed, 2 survived, 1 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    2: a2\n"
            + "        survived: negated conditional, in m\n"
            + "        survived: removed call, in m\n"
            + "        reached by 1 test:\n"
            + "          T.t1\n"
            + "    4: a4\n"
            + "        no test reaches it: replaced return value with null, in m\n"
            + "\n"
            + "Live mutants on 2 lines in 1 file." + LIVE_FOOTER,
        run(
                mutation("NO_COVERAGE", "A", "A.java", 4, "replaced return value with null"),
                mutation("SURVIVED", "A", "A.java", 2, "negated conditional", "T.t1"),
                mutation("KILLED", "A", "A.java", 3, "removed call", "T.t2"),
                mutation("SURVIVED", "A", "A.java", 2, "removed call", "T.t1"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void aTimeoutCountsAsKilledAndANonViableMutantIsCountedApart() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants: 2 killed, 0 survived, 0 not reached by any test, 1 NON_VIABLE",
        run(
                mutation("KILLED", "A", "A.java", 1, "removed call"),
                mutation("TIMED_OUT", "A", "A.java", 2, "negated conditional"),
                mutation("NON_VIABLE", "A", "A.java", 3, "negated conditional"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void aRunInWhichNoTestReachedAnyLineSaysWhyThatCanHappen() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  1 mutant: 0 killed, 0 survived, 1 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "No test reached any of the changed lines. Either no test of this module exercises this"
            + " code, or the tests that do failed under PIT, which leaves them out silently; the"
            + " \"Mutation testing\" section of CONTRIBUTING.md lists the known cases.\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        no test reaches it: removed call, in m\n"
            + "\n"
            + "Live mutants on 1 line in 1 file."
            + LIVE_FOOTER,
        run(mutation("NO_COVERAGE", "A", "A.java", 1, "removed call"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void theFileWithMoreLiveMutantsComesFirst() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants: 0 killed, 3 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/B.java\n"
            + "    1: b1\n"
            + "        survived: removed call, in m\n"
            + "    2: b2\n"
            + "        survived: removed call, in m\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        survived: removed call, in m\n"
            + "\n"
            + "Live mutants on 3 lines in 2 files." + LIVE_FOOTER,
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 2, "removed call"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void theTotalLineLimitEndsTheReportAfterTheLinesItAllows() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants: 0 killed, 3 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/B.java\n"
            + "    1: b1\n"
            + "        survived: removed call, in m\n"
            + "    1 more line\n"
            + "\n"
            + "  1 more file\n"
            + "\n"
            + "Live mutants on 3 lines in 2 files." + LIVE_FOOTER,
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call"),
                mutation("SURVIVED", "B", "B.java", 2, "removed call"))
            .render(new DiffCoverageReport.Limits(10, 10, 1), List.of()));
  }

  @Test
  void theReportFileHoldsWhatTheLimitsCut() throws IOException {
    PitestSurvivors report =
        run(
            mutation("SURVIVED", "A", "A.java", 1, "removed call"),
            mutation("SURVIVED", "B", "B.java", 1, "removed call"));
    Path file = repository.resolve("build/survivors.txt");

    report.writeAndRender(repository.toFile(), file.toFile(), 1, 1, 1, false, List.of());

    assertEquals(
        report.render(DiffCoverageReport.Limits.NONE, List.of()) + "\n", Files.readString(file));
  }

  @Test
  void showAllPrintsTheWholeReport() throws IOException {
    PitestSurvivors report =
        run(
            mutation("SURVIVED", "A", "A.java", 1, "removed call"),
            mutation("SURVIVED", "B", "B.java", 1, "removed call"));

    assertEquals(
        report.render(DiffCoverageReport.Limits.NONE, List.of("build/survivors.txt")),
        report.writeAndRender(
            repository.toFile(),
            repository.resolve("build/survivors.txt").toFile(),
            1,
            1,
            1,
            true,
            List.of()));
  }

  @Test
  void theTestsNamedFirstAreTheOnesReachingTheMostLiveMutants() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  2 mutants: 0 killed, 2 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        survived: removed call, in m\n"
            + "        reached by 5 tests, among them:\n"
            + "          T.d\n"
            + "          T.e\n"
            + "          T.a\n"
            + "\n"
            + "  src/com/x/B.java\n"
            + "    1: b1\n"
            + "        survived: removed call, in m\n"
            + "        reached by 2 tests:\n"
            + "          T.d\n"
            + "          T.e\n"
            + "\n"
            + "Live mutants on 2 lines in 2 files." + LIVE_FOOTER,
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call", "T.a", "T.b", "T.c", "T.d", "T.e"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call", "T.d", "T.e"))
            .render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void theLimitsCutLinesAndFilesAndTheReportNamesTheFullOne() throws IOException {
    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  3 mutants: 0 killed, 3 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: a1\n"
            + "        survived: removed call, in m\n"
            + "    1 more line\n"
            + "\n"
            + "  1 more file\n"
            + "\n"
            + "Live mutants on 3 lines in 2 files." + LIVE_FOOTER + "\n"
            + "\n"
            + "Full report:\n"
            + "  build/survivors.txt",
        run(
                mutation("SURVIVED", "A", "A.java", 1, "removed call"),
                mutation("SURVIVED", "A", "A.java", 5, "removed call"),
                mutation("SURVIVED", "B", "B.java", 1, "removed call"))
            .render(new DiffCoverageReport.Limits(1, 1, 50), List.of("build/survivors.txt")));
  }

  @Test
  void aRunThatWroteNoReportSaysNoMutantWasAnalyzed() throws IOException {
    Path build = repository.resolve("build");
    Files.createDirectories(build);
    Files.writeString(build.resolve("pit.log"), "");
    PitestSurvivors report =
        PitestSurvivors.read(
            "against origin/master (abc), all tests",
            build.resolve("mutants.tsv").toFile(),
            repository.toFile(),
            List.of(repository.toFile()));

    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  no mutant: PIT generated none on the changed lines\n"
            + "\n"
            + "PIT generates no mutant on a line that calls Verify or Preconditions.\n"
            + "\n"
            + "Full report:\n"
            + "  build/survivors.txt\n"
            + "  build/pit.log",
        report.writeAndRender(
            repository.toFile(),
            build.resolve("survivors.txt").toFile(),
            10,
            10,
            50,
            false,
            List.of("build/index.html", "build/pit.log")));
  }

  /** The report quotes no text for a line it cannot decode, and still reports the mutant. */
  @Test
  void aSourceFileThatIsNotUtf8LeavesTheLineUnquoted() throws IOException {
    Path sources = repository.resolve("src");
    Files.createDirectories(sources.resolve("com/x"));
    Files.write(sources.resolve("com/x/A.java"), new byte[] {(byte) 0xff, '\n'});
    PitestSurvivors report =
        PitestSurvivors.read(
            "against origin/master (abc), all tests",
            mutantsFile(mutation("SURVIVED", "A", "A.java", 1, "removed call")).toFile(),
            repository.toFile(),
            List.of(sources.toFile()));

    assertEquals(
        "Mutation testing of changed lines against origin/master (abc), all tests\n"
            + "  1 mutant: 0 killed, 1 survived, 0 not reached by any test\n"
            + "  ordered by live mutants per file, then by line\n"
            + "\n"
            + "  src/com/x/A.java\n"
            + "    1: \n"
            + "        survived: removed call, in m\n"
            + "\n"
            + "Live mutants on 1 line in 1 file."
            + LIVE_FOOTER,
        report.render(DiffCoverageReport.Limits.NONE, List.of()));
  }

  @Test
  void aLambdaIsNamedByTheMethodThatHoldsIt() {
    assertEquals("a lambda in check", PitestSurvivors.readableMethod("lambda$check$0"));
  }

  @Test
  void aConstructorIsNamedAsSuch() {
    assertEquals("a constructor", PitestSurvivors.readableMethod("<init>"));
  }
}
