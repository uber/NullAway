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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The counters of a JaCoCo XML report are read per source line, and several reports merge by taking
 * the larger of each counter.
 *
 * <p>The attribute names are JaCoCo's: {@code ci} and {@code mi} count covered and missed
 * instructions, {@code cb} and {@code mb} covered and missed branches.
 */
class JacocoLinesTest {

  private static final String KEY = "com/uber/nullaway/Sample.java";

  @TempDir Path directory;

  /** Writes a report holding the given {@code <line>} elements for Sample.java. */
  private File report(String name, String lines) throws IOException {
    File file = directory.resolve(name).toFile();
    Files.writeString(
        file.toPath(),
        "<report name=\"test\"><package name=\"com/uber/nullaway\">"
            + "<sourcefile name=\"Sample.java\">"
            + lines
            + "</sourcefile></package></report>");
    return file;
  }

  private JacocoLines read(String lines) throws IOException {
    return JacocoLines.read(List.of(report("jacocoTestReport.xml", lines)));
  }

  /** Returns the counters of line 10, failing by name rather than by NPE where it is absent. */
  private JacocoLines.Counters counters(String lines) throws IOException {
    JacocoLines.Counters counters = read(lines).get(KEY, 10);
    assertNotNull(counters, () -> "no counters for line 10 in " + lines);
    return counters;
  }

  @Nested
  class OneReport {

    @Test
    void aLineWithACoveredInstructionCountsAsExecuted() throws IOException {
      assertTrue(
          counters("<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>")
              .isExecuted());
    }

    @Test
    void aLineWithNoCoveredInstructionCountsAsNotExecuted() throws IOException {
      assertFalse(
          counters("<line nr=\"10\" mi=\"4\" ci=\"0\" mb=\"0\" cb=\"0\"/>")
              .isExecuted());
    }

    @Test
    void aLineWithAMissedBranchHasAnUntakenBranch() throws IOException {
      assertTrue(
          counters("<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"1\" cb=\"1\"/>")
              .hasUntakenBranch());
    }

    @Test
    void aLineWithNoMissedBranchHasNoUntakenBranch() throws IOException {
      assertFalse(
          counters("<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"2\"/>")
              .hasUntakenBranch());
    }

    @Test
    void aLineWithNoBranchAtAllHasNoUntakenBranch() throws IOException {
      assertFalse(
          counters("<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>")
              .hasUntakenBranch());
    }

    @Test
    void theBranchTotalIsTheCoveredAndMissedBranchesTogether() throws IOException {
      JacocoLines.Counters counters =
          counters("<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"3\" cb=\"1\"/>");
      assertAll(
          () -> assertEquals(1, counters.coveredBranches, "coveredBranches"),
          () -> assertEquals(4, counters.totalBranches, "totalBranches"));
    }

    @Test
    void anAbsentCounterAttributeReadsAsZero() throws IOException {
      JacocoLines.Counters counters = counters("<line nr=\"10\" ci=\"4\"/>");
      assertAll(
          () -> assertEquals(0, counters.coveredBranches, "coveredBranches"),
          () -> assertEquals(0, counters.totalBranches, "totalBranches"));
    }

    @Test
    void aLineTheReportDoesNotMentionHasNoCounters() throws IOException {
      assertNull(read("<line nr=\"10\" mi=\"0\" ci=\"4\"/>").get(KEY, 11));
    }

    @Test
    void aSourceFileTheReportDoesNotMentionIsNotCovered() throws IOException {
      assertFalse(read("").covers("com/uber/nullaway/Other.java"));
    }

    @Test
    void aSourceFileTheReportMentionsIsCovered() throws IOException {
      assertTrue(read("").covers(KEY));
    }
  }

  @Nested
  class SeveralReports {

    @Test
    void aLineExecutedByEitherReportCountsAsExecuted() throws IOException {
      JacocoLines merged =
          JacocoLines.read(
              List.of(
                  report("first.xml", "<line nr=\"10\" mi=\"4\" ci=\"0\" mb=\"0\" cb=\"0\"/>"),
                  report("second.xml", "<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>")));
      assertTrue(merged.get(KEY, 10).isExecuted());
    }

    /**
     * JaCoCo numbers no branch, so two reports each covering one of two branches cannot be told
     * from two reports covering the same branch. Reporting the line as partly taken asks for a test
     * that may already exist; adding the counters up would hide one that does not.
     */
    @Test
    void aBranchOneReportMissedStaysMissedRatherThanAddingUpToTheTotal() throws IOException {
      String line = "<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"1\" cb=\"1\"/>";
      JacocoLines merged =
          JacocoLines.read(List.of(report("first.xml", line), report("second.xml", line)));
      JacocoLines.Counters counters = merged.get(KEY, 10);
      assertAll(
          () -> assertEquals(1, counters.coveredBranches, "coveredBranches"),
          () -> assertEquals(2, counters.totalBranches, "totalBranches"),
          () -> assertTrue(counters.hasUntakenBranch(), "hasUntakenBranch"));
    }

    /** A report compiled from fewer branches must not shrink the total the other one saw. */
    @Test
    void theLargerBranchTotalOfTheTwoReportsWins() throws IOException {
      JacocoLines merged =
          JacocoLines.read(
              List.of(
                  report("first.xml", "<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"1\" cb=\"1\"/>"),
                  report("second.xml", "<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"3\" cb=\"1\"/>")));
      assertEquals(4, merged.get(KEY, 10).totalBranches);
    }

    @Test
    void aLineOnlyOneReportMentionsKeepsThatReportsCounters() throws IOException {
      JacocoLines merged =
          JacocoLines.read(
              List.of(
                  report("first.xml", "<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>"),
                  report("second.xml", "<line nr=\"20\" mi=\"4\" ci=\"0\" mb=\"0\" cb=\"0\"/>")));
      assertAll(
          () -> assertTrue(merged.get(KEY, 10).isExecuted(), "line 10"),
          () -> assertFalse(merged.get(KEY, 20).isExecuted(), "line 20"));
    }
  }

  @Nested
  class MalformedInput {

    /**
     * JaCoCo declares {@code report.dtd} as a relative system id and writes no such file beside the
     * report, so a parser that resolves it fails on every report this build produces.
     */
    @Test
    void aReportDeclaringTheJacocoDoctypeIsReadWithoutResolvingIt() throws IOException {
      File file = directory.resolve("doctyped.xml").toFile();
      Files.writeString(
          file.toPath(),
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">"
              + "<report name=\"test\"><package name=\"com/uber/nullaway\">"
              + "<sourcefile name=\"Sample.java\">"
              + "<line nr=\"10\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>"
              + "</sourcefile></package></report>");
      assertTrue(JacocoLines.read(List.of(file)).get(KEY, 10).isExecuted());
    }

    @Test
    void aReportThatIsNotWellFormedNamesItsFile() throws IOException {
      File file = directory.resolve("truncated.xml").toFile();
      Files.writeString(file.toPath(), "<report><package name=\"p\">");
      IOException thrown = assertThrows(IOException.class, () -> JacocoLines.read(List.of(file)));
      assertTrue(
          thrown.getMessage().contains(file.getName()),
          () -> "message does not name the file: " + thrown.getMessage());
    }
  }
}
