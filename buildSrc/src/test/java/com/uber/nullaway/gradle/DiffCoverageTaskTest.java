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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The decisions the task makes before and after the counts: which ref the diff is taken against,
 * which sources the counts can still be trusted for, and whether a threshold was met.
 *
 * <p>{@link DiffCoverageTaskRunTest} drives the task itself through a synthetic project; what is
 * here is the logic those runs carry, tested on its own.
 */
class DiffCoverageTaskTest {

  private static final String MEASURED = "nullaway/src/main/java/Sample.java";
  private static final long EARLIER = 1_000_000_000L;
  private static final long LATER = 2_000_000_000L;

  @TempDir Path directory;

  private File writeAt(String path, long modified) throws IOException {
    Path file = directory.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x\n");
    Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
    return file.toFile();
  }

  private List<String> staleAmong(String... measuredPaths) throws IOException {
    return DiffCoverageTask.staleSources(
        directory.toFile(), List.of(measuredPaths), List.of(writeAt("report.xml", EARLIER)));
  }

  @Nested
  class AgainstOneReport {

    @Test
    void aSourceEditedAfterTheReportIsStale() throws IOException {
      writeAt(MEASURED, LATER);
      assertEquals(List.of(MEASURED), staleAmong(MEASURED));
    }

    @Test
    void aSourceOlderThanTheReportIsNotStale() throws IOException {
      writeAt(MEASURED, EARLIER - 1000);
      assertEquals(List.of(), staleAmong(MEASURED));
    }

    /** A source written in the same run as the report is not an edit that moved a line. */
    @Test
    void aSourceAsOldAsTheReportIsNotStale() throws IOException {
      writeAt(MEASURED, EARLIER);
      assertEquals(List.of(), staleAmong(MEASURED));
    }

    @Test
    void aFileTheCountsDidNotComeFromIsNotChecked() throws IOException {
      writeAt("nullaway/src/test/java/SampleTest.java", LATER);
      assertEquals(List.of(), staleAmong());
    }

    @Test
    void aMeasuredPathThatNoLongerExistsIsNotStale() throws IOException {
      assertEquals(List.of(), staleAmong("nullaway/src/main/java/Deleted.java"));
    }
  }

  @Nested
  class TheBaseRef {

    @Test
    void anIntegrationUpstreamIsTriedBeforeOriginMaster() {
      assertEquals(
          List.of("origin/develop", "origin/master"),
          DiffCoverageTask.baseCandidates(null, "origin/develop", "feature/x"));
    }

    @Test
    void aBranchWithNoUpstreamFallsBackToOriginMaster() {
      assertEquals(
          List.of("origin/master"), DiffCoverageTask.baseCandidates(null, null, "feature/x"));
    }

    /**
     * In a fork workflow the branch tracks the fork, so after the first push its upstream holds
     * every line the branch changed and diffing against it would report an empty change.
     */
    @Test
    void anUpstreamThatIsThisBranchPushedToAForkIsLeftOut() {
      assertEquals(
          List.of("origin/master"),
          DiffCoverageTask.baseCandidates(null, "vs/feature/x", "feature/x"));
    }

    /**
     * Falling through from the ref the caller named to another one would report a different set of
     * lines as changed, under a base the caller did not choose.
     */
    @Test
    void anExplicitRefIsTheOnlyCandidate() {
      assertEquals(
          List.of("release-1.0"),
          DiffCoverageTask.baseCandidates("release-1.0", "origin/develop", "feature/x"));
    }
  }

  @Nested
  class RecognisingThisBranchPushed {

    @Test
    void anUpstreamNamedAfterThisBranchOnARemoteIsThisBranch() {
      assertTrue(DiffCoverageTask.isOwnPushedBranch("vs/feature/x", "feature/x"));
    }

    @Test
    void anUpstreamNamingAnotherBranchIsNotThisBranch() {
      assertFalse(DiffCoverageTask.isOwnPushedBranch("origin/develop", "feature/x"));
    }

    /** Only the remote is dropped, so a branch named after another one's tail stays its own. */
    @Test
    void anUpstreamWhoseTailMatchesButWhosePathDoesNotIsAnotherBranch() {
      assertFalse(DiffCoverageTask.isOwnPushedBranch("vs/feature/x", "x"));
    }

    @Test
    void aLocalUpstreamWithNoRemoteSegmentIsComparedWhole() {
      assertTrue(DiffCoverageTask.isOwnPushedBranch("x", "x"));
    }

    @Test
    void aDetachedHeadHasNoBranchToRecognise() {
      assertFalse(DiffCoverageTask.isOwnPushedBranch("origin/master", null));
    }
  }

  @Nested
  class GivingUp {

    /**
     * A run that measured nothing has to say so. Reusing the total a measured run prints for an
     * empty change would tell the reader that everything they changed was covered.
     */
    @ParameterizedTest
    @ValueSource(strings = {DiffCoverageTask.NO_BASE, DiffCoverageTask.NO_REPORT})
    void aMessageSayingNothingWasMeasuredIsNotTheTotalOfAMeasuredRun(String message) {
      assertFalse(
          message.contains(DiffCoverageReport.NO_EXECUTABLE_LINES),
          () -> "message reads as a measured run: " + message);
    }

    @Test
    void theTwoGiveUpMessagesNameDifferentCauses() {
      assertNotEquals(DiffCoverageTask.NO_BASE, DiffCoverageTask.NO_REPORT);
    }
  }

  @Nested
  class FilesNoReportCovers {

    /**
     * Such a file contributes nothing to either side of the share, so a change made entirely in
     * one would clear every threshold without having been measured.
     */
    @Test
    void aChangedFileNoReportCoversNamesItselfRatherThanClearingTheGate() {
      assertEquals(
          "no JaCoCo report covers these changed files, so no threshold applies to them: "
              + "a/A.java, b/B.java",
          DiffCoverageTask.unmeasuredFilesFailure(List.of("a/A.java", "b/B.java")));
    }

    @Test
    void aRunWithEveryChangedFileInTheReportAppliesTheThreshold() {
      assertNull(DiffCoverageTask.unmeasuredFilesFailure(List.of()));
    }
  }

  @Nested
  class TheThreshold {

    @Test
    void anExecutedShareBelowTheThresholdIsReportedWithBothPercentages() {
      assertEquals(
          "66.7% of the changed lines ran, below the required 90.0%",
          DiffCoverageTask.thresholdFailure(2, 3, 90.0));
    }

    @Test
    void anExecutedShareAboveTheThresholdPasses() {
      assertNull(DiffCoverageTask.thresholdFailure(3, 3, 90.0));
    }

    @Test
    void anExecutedShareExactlyAtTheThresholdPasses() {
      assertNull(DiffCoverageTask.thresholdFailure(9, 10, 90.0));
    }

    @Test
    void anExecutedShareJustBelowTheThresholdFails() {
      assertEquals(
          "89.0% of the changed lines ran, below the required 90.0%",
          DiffCoverageTask.thresholdFailure(89, 100, 90.0));
    }

    /** A commit that changed only comments clears every threshold. */
    @Test
    void aChangeWithNoExecutableLinePassesEveryThreshold() {
      assertNull(DiffCoverageTask.thresholdFailure(0, 0, 100.0));
    }

    /**
     * A locale that writes a decimal comma would print 66,7%, which nobody greps and which two CI
     * runners spell differently.
     */
    @Test
    void thePercentagesCarryADecimalPointUnderALocaleThatWritesAComma() {
      Locale original = Locale.getDefault();
      try {
        Locale.setDefault(Locale.GERMANY);
        assertEquals(
            "66.7% of the changed lines ran, below the required 90.0%",
            DiffCoverageTask.thresholdFailure(2, 3, 90.0));
      } finally {
        Locale.setDefault(original);
      }
    }
  }

  @Nested
  class AgainstSeveralReports {

    /** The newest report decides, since it is the one the counts were read from. */
    @Test
    void aSourceOlderThanTheNewestReportIsNotStale() throws IOException {
      File older = writeAt("first.xml", EARLIER);
      File newer = writeAt("second.xml", LATER);
      writeAt(MEASURED, LATER - 1000);
      assertEquals(
          List.of(),
          DiffCoverageTask.staleSources(
              directory.toFile(), List.of(MEASURED), List.of(older, newer)));
    }
  }
}
