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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run that measures nothing says so rather than reporting a change as fully covered.
 *
 * <p>These are the two outcomes a caller cannot tell apart from the counts: a base ref that does
 * not resolve and a report that was never written both leave no line changed and no line executed,
 * which is what a fully covered change looks like. The task is driven through a synthetic Gradle
 * project rather than a build, so the decision is exercised rather than asserted about.
 *
 * <p>Every git command runs against a repository built here, with the system and global
 * configuration switched off, so that a setting on the machine running the tests decides nothing.
 */
class DiffCoverageTaskRunTest {

  /** Where the task writes the report whole, relative to the repository built here. */
  private static final Path FULL_REPORT = Path.of("build", "reports", "diff-coverage", "test.txt");

  @TempDir Path directory;

  private DiffCoverageTask task(Path... reports) {
    Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    DiffCoverageTask task =
        project.getTasks().register("diffCoverage", DiffCoverageTask.class).get();
    task.getGit()
        .set(
            project
                .getGradle()
                .getSharedServices()
                .registerIfAbsent(
                    "git",
                    GitService.class,
                    service ->
                        service.getParameters().getRepositoryRoot().set(directory.toFile())));
    task.getSourceRoots().set(List.of("src/main/java"));
    task.getScopeDescription().set("a test");
    task.getFiltered().set(false);
    task.getMaxLinesPerFile().set(10);
    task.getMaxLinesTotal().set(50);
    task.getMaxFiles().set(4);
    task.getShowAll().set(false);
    task.getOutputFile().set(directory.resolve(FULL_REPORT).toFile());
    for (Path report : reports) {
      task.getReportFiles().from(report.toFile());
    }
    return task;
  }

  /** Creates a repository with no commit, so that no ref of any kind resolves in it. */
  private void emptyRepository() throws Exception {
    run("init", "--initial-branch=master", ".");
    run("config", "user.email", "test@example.com");
    run("config", "user.name", "Test");
  }

  /** Creates a repository whose only commit is on master. */
  private void repositoryWithOneCommit() throws Exception {
    emptyRepository();
    Files.writeString(directory.resolve("README.md"), "seed\n");
    run("add", "-A");
    run("commit", "-m", "seed");
  }

  /** Writes a JaCoCo report that mentions one source file and nothing else. */
  private Path reportCovering(String sourceFile) throws IOException {
    Path report = directory.resolve("report.xml");
    Files.writeString(
        report,
        "<report name=\"test\"><package name=\"\"><sourcefile name=\""
            + sourceFile
            + "\"><line nr=\"1\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>"
            + "</sourcefile></package></report>");
    return report;
  }

  private String revParse(String ref) throws Exception {
    Process process =
        new ProcessBuilder("git", "rev-parse", ref).directory(directory.toFile()).start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8).trim();
    assertEquals(0, process.waitFor(), () -> "git rev-parse " + ref);
    return output;
  }

  private void run(String... arguments) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    // A path git can read and will not find, rather than /dev/null, which names a file of its own
    // on Windows and would leave the configuration of the machine deciding what these tests see.
    String noConfig = directory.resolve("no-git-config").toString();
    environment.put("GIT_CONFIG_GLOBAL", noConfig);
    environment.put("GIT_CONFIG_SYSTEM", noConfig);
    environment.put("GIT_CONFIG_NOSYSTEM", "1");
    Process process = builder.start();
    // Read before waiting: nothing else drains the pipe, so a command that filled it would hang,
    // and what git printed is the only account of why it failed.
    String output;
    try (InputStream stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), UTF_8);
    }
    assertEquals(
        0,
        process.waitFor(),
        () -> "git " + String.join(" ", arguments) + " printed " + output);
  }

  @Nested
  class WithNoBaseRef {

    /** A repository with no commit resolves neither an upstream branch nor origin/master. */
    @Test
    void aRunThatResolvedNoBaseSaysSoRatherThanReportingNoChangedLine() throws Exception {
      emptyRepository();
      assertEquals(DiffCoverageTask.NO_BASE, task().describeRun());
    }
  }

  @Nested
  class WithNoReport {

    /** The module has to be one the change reached, or it would rightly say nothing at all. */
    @Test
    void aRunThatFoundNoReportSaysSoRatherThanReportingNoExecutedLine() throws Exception {
      repositoryWithOneCommit();
      Path source = directory.resolve("src/main/java/A.java");
      Files.createDirectories(source.getParent());
      Files.writeString(source, "class A {}\n");
      DiffCoverageTask task = task(directory.resolve("absent.xml"));
      task.getBaseRef().set("master");

      assertEquals(DiffCoverageTask.NO_REPORT, task.describeRun());
    }
  }

  @Nested
  class WithAnUnreadableUntrackedFile {

    /**
     * An untracked file has no diff, so the task reads it whole. One this JVM cannot decode is not
     * worth failing a build whose tests just passed, so it is named and left out of the counts.
     */
    @Test
    void aFileThisJvmCannotDecodeIsNamedInTheReport() throws Exception {
      repositoryWithOneCommit();
      undecodableSource();
      DiffCoverageTask task = task(reportCovering("Other.java"));
      task.getBaseRef().set("master");
      assertEquals(
          "Diff coverage: cannot read the untracked file src/main/java/Bad.java: "
              + "java.nio.charset.MalformedInputException: Input length = 1",
          task.describeRun().lines().findFirst().orElseThrow());
    }

    /**
     * Left in the counts, the file would be reported as changed code no report covers, which is a
     * measurement it never received.
     */
    @Test
    void aFileThisJvmCannotDecodeIsLeftOutOfTheCounts() throws Exception {
      repositoryWithOneCommit();
      undecodableSource();
      DiffCoverageTask task = task(reportCovering("Other.java"));
      task.getBaseRef().set("master");

      String printed = task.describeRun();

      assertFalse(
          printed.contains("covers these files"),
          () -> "the block listing files no report covers: " + printed);
    }

    private void undecodableSource() throws IOException {
      Path source = directory.resolve("src/main/java/Bad.java");
      Files.createDirectories(source.getParent());
      Files.write(source, new byte[] {(byte) 0xC3, (byte) 0x28, '\n'});
    }
  }

  @Nested
  class WithABaseThatDoesNotResolve {

    @Test
    void anExplicitRefThatDoesNotResolveFailsTheTask() throws Exception {
      repositoryWithOneCommit();
      DiffCoverageTask task = task();
      task.getBaseRef().set("no/such/ref");
      GradleException thrown = assertThrows(GradleException.class, task::describeRun);
      assertEquals(
          "diffCoverageBase no/such/ref does not resolve to a commit", thrown.getMessage());
    }
  }

  @Nested
  class WithAChangedFileNoReportCovers {

    /**
     * Such a file was never measured, and a report that left it out silently would read as one
     * where every changed line of it ran.
     */
    @Test
    void aFileTheReportNeverMentionsIsNamedRatherThanLeftOut() throws Exception {
      repositoryWithOneCommit();
      Path source = directory.resolve("src/main/java/A.java");
      Files.createDirectories(source.getParent());
      Files.writeString(source, "class A {}\n");
      Path report = directory.resolve("report.xml");
      Files.writeString(
          report,
          "<report name=\"test\"><package name=\"\">"
              + "<sourcefile name=\"Other.java\"/></package></report>");
      DiffCoverageTask task = task(report);
      task.getBaseRef().set("master");

      String printed = task.describeRun();

      assertTrue(
          printed.contains(
              "No report read here covers these files, so they were probably not part of the "
                  + "run:\n    src/main/java/A.java"),
          () -> "the block naming the unmeasured file: " + printed);
    }
  }

  @Nested
  class WithAModuleTheChangeNeverReached {

    /**
     * A module with no test produces no report at all, so without this every whole-build run would
     * carry one give-up line per such module, and one more for every module the change missed.
     */
    @Test
    void aModuleWithNoChangedSourceSaysNothingEvenWithNoReport() throws Exception {
      repositoryWithOneCommit();
      Path elsewhere = directory.resolve("other/src/main/java/A.java");
      Files.createDirectories(elsewhere.getParent());
      Files.writeString(elsewhere, "class A {}\n");
      DiffCoverageTask task = task(directory.resolve("absent.xml"));
      task.getBaseRef().set("master");
      assertEquals("", task.describeRun());
    }

    /** A module the change did reach still reports that its coverage could not be read. */
    @Test
    void aModuleWithAChangedSourceStillReportsAMissingReport() throws Exception {
      repositoryWithOneCommit();
      Path source = directory.resolve("src/main/java/A.java");
      Files.createDirectories(source.getParent());
      Files.writeString(source, "class A {}\n");
      DiffCoverageTask task = task(directory.resolve("absent.xml"));
      task.getBaseRef().set("master");
      assertEquals(DiffCoverageTask.NO_REPORT, task.describeRun());
    }
  }

  @Nested
  class TheScopeLine {

    /**
     * Between an upstream branch and origin/master the reader cannot otherwise tell which base the
     * counts were taken against, which is the whole reason the resolved ref is carried back.
     */
    @Test
    void theHeaderNamesTheRefThatResolvedAndItsMergeBase() throws Exception {
      repositoryWithOneCommit();
      Path source = directory.resolve("src/main/java/A.java");
      Files.createDirectories(source.getParent());
      Files.writeString(source, "class A {}\n");
      DiffCoverageTask task = task(reportCovering("A.java"));
      task.getBaseRef().set("master");
      String head = revParse("HEAD").substring(0, 9);
      assertEquals(
          "Diff coverage against master (" + head + "), from a test",
          task.describeRun().lines().findFirst().orElseThrow());
    }
  }

  @Nested
  class WithNoCommonHistory {

    /**
     * A ref can resolve and share no commit with HEAD, as in a shallow clone or after an orphan
     * branch. That is a base this run does not have, not a reason to fail a build whose tests
     * passed, so the message is the one for a base that could not be found.
     */
    @Test
    void aBaseWithNoMergeBaseIsTreatedAsNoBaseAtAll() throws Exception {
      repositoryWithOneCommit();
      run("checkout", "-q", "--orphan", "unrelated");
      run("rm", "-q", "-f", "README.md");
      Files.writeString(directory.resolve("OTHER.md"), "other\n");
      run("add", "-A");
      run("commit", "-m", "unrelated");
      DiffCoverageTask task = task();
      task.getBaseRef().set("master");

      assertEquals(DiffCoverageTask.NO_BASE, task.describeRun());
    }
  }

  @Nested
  class TheFullReport {

    /**
     * Builds a task over a repository holding one untracked source of count uncovered lines, whose
     * text is {@code line1();} downwards, and the report that measures it.
     */
    private DiffCoverageTask taskWithUncoveredLines(int count) throws Exception {
      repositoryWithOneCommit();
      StringBuilder source = new StringBuilder();
      StringBuilder xml =
          new StringBuilder(
              "<report name=\"test\"><package name=\"com/uber\">"
                  + "<sourcefile name=\"A.java\">");
      for (int number = 1; number <= count; number++) {
        source.append("line").append(number).append("();\n");
        xml.append("<line nr=\"")
            .append(number)
            .append("\" mi=\"4\" ci=\"0\" mb=\"0\" cb=\"0\"/>");
      }
      xml.append("</sourcefile></package></report>");
      Path file = directory.resolve("src/main/java/com/uber/A.java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, source.toString());
      Path report = directory.resolve("report.xml");
      Files.writeString(report, xml.toString());
      DiffCoverageTask task = task(report);
      task.getBaseRef().set("master");
      return task;
    }

    /**
     * The console block is a summary, so it has to leave the reader somewhere to read the rest and
     * somewhere to read the run it was measured from.
     */
    @Test
    void theConsoleEndsWithThePathOfTheReportItWroteAndOfTheJacocoXml() throws Exception {
      List<String> printed = taskWithUncoveredLines(2).describeRun().lines().toList();
      int paths = printed.indexOf("Full diff coverage report:");
      assertEquals(
          List.of(
              "Full diff coverage report:",
              "  " + FULL_REPORT,
              "Full JaCoCo report:",
              "  report.xml"),
          printed.subList(Math.max(paths, 0), printed.size()),
          () -> "the paths at the end of:\n" + String.join("\n", printed));
    }

    @Test
    void theFileHoldsTheLinesTheConsoleCapLeftOut() throws Exception {
      DiffCoverageTask task = taskWithUncoveredLines(3);
      task.getMaxLinesTotal().set(1);
      String console = task.describeRun();
      String written = Files.readString(directory.resolve(FULL_REPORT));
      assertAll(
          () -> assertFalse(console.contains("\n      3: not executed"), console),
          () -> assertTrue(written.contains("\n      3: not executed: line3();"), written));
    }

    /** The file is the reader's copy of the report, so it names the run it was measured from. */
    @Test
    void theFileEndsWithThePathOfTheJacocoXml() throws Exception {
      taskWithUncoveredLines(2).describeRun();
      String written = Files.readString(directory.resolve(FULL_REPORT));
      assertTrue(written.endsWith("Full JaCoCo report:\n  report.xml\n"), written);
    }

    /** Several reports are read where a module has more than one test task instrumented. */
    @Test
    void aSecondJacocoReportIsNamedUnderThePluralHeading() throws Exception {
      DiffCoverageTask task = taskWithUncoveredLines(1);
      Path second = directory.resolve("other.xml");
      Files.writeString(second, "<report name=\"other\"/>");
      task.getReportFiles().from(second.toFile());
      List<String> printed = task.describeRun().lines().toList();
      assertAll(
          () ->
              assertEquals(
                  "Full JaCoCo reports:",
                  printed.get(printed.size() - 3),
                  () -> String.join("\n", printed)),
          () ->
              assertEquals(
                  List.of("  other.xml", "  report.xml"),
                  printed.subList(printed.size() - 2, printed.size()).stream().sorted().toList(),
                  () -> String.join("\n", printed)));
    }

    /**
     * A path that names no file sends the reader to a stale report or to none, so the console says
     * what happened where it would otherwise carry the path.
     */
    @Test
    void aReportThatCannotBeWrittenIsSaidInPlaceOfItsPath() throws Exception {
      DiffCoverageTask task = taskWithUncoveredLines(1);
      // The JaCoCo report is a regular file, so no directory of that name can hold the report.
      task.getOutputFile().set(directory.resolve("report.xml").resolve("test.txt").toFile());
      String printed = task.describeRun();
      assertTrue(
          printed.contains(
              "Full diff coverage report: cannot write "
                  + Path.of("report.xml", "test.txt")
                  + ": "),
          printed);
    }

    /**
     * The path is printed only where the task wrote the file, and a reader who kept the path from
     * an earlier run would otherwise read that run's report as this one's.
     */
    @Test
    void aRunThatMeasuresNothingLeavesNoReportFromAnEarlierRunBehind() throws Exception {
      DiffCoverageTask task = taskWithUncoveredLines(2);
      task.describeRun();
      Files.delete(directory.resolve("src/main/java/com/uber/A.java"));
      task.describeRun();
      assertFalse(
          Files.exists(directory.resolve(FULL_REPORT)),
          () -> "the report of the earlier run is still at " + FULL_REPORT);
    }

    @Test
    void showingEverythingPrintsTheRowsTheCapWouldHaveDropped() throws Exception {
      DiffCoverageTask task = taskWithUncoveredLines(3);
      task.getMaxLinesTotal().set(1);
      task.getShowAll().set(true);
      String console = task.describeRun();
      assertTrue(console.contains("      3: not executed: line3();"), console);
    }
  }
}
