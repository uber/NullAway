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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real git output, taken with the arguments {@link DiffCoverageTask#diffCommand}, parses to the
 * lines the change touched.
 *
 * <p>{@link UnifiedDiffTest} pins the parser against diff text written by hand, which stays correct
 * only while git keeps emitting that text. This test runs git, so a change in what git emits
 * reaches the parser here rather than in a coverage report someone is reading.
 *
 * <p>A missing git fails these tests rather than skipping them. Every contributor and every CI job
 * has git, so a skip here would hide a broken environment instead of reporting one. The system and
 * global configuration are switched off for every command, so only what a test sets applies.
 */
class UnifiedDiffFromGitTest {

  @TempDir Path repository;

  private String base;

  private static Set<Integer> lines(int... numbers) {
    Set<Integer> set = new TreeSet<>();
    for (int number : numbers) {
      set.add(number);
    }
    return set;
  }

  @BeforeEach
  void createRepositoryWithOneCommit() throws Exception {
    run("init", "--initial-branch=master", ".");
    run("config", "user.email", "test@example.com");
    run("config", "user.name", "Test");
    write("a/B.java", "one\ntwo\nthree\n");
    commitAll();
    base = run("rev-parse", "HEAD").trim();
  }

  @Test
  void aRewrittenLineIsTheOnlyLineReported() throws Exception {
    write("a/B.java", "one\nCHANGED\nthree\n");
    assertEquals(Map.of("a/B.java", lines(2)), parseDiff());
  }

  @Test
  void anInsertedLineIsReportedAtItsPositionInTheNewFile() throws Exception {
    write("a/B.java", "one\ntwo\nINSERTED\nthree\n");
    assertEquals(Map.of("a/B.java", lines(3)), parseDiff());
  }

  @Test
  void aDeletionAloneReportsNoLine() throws Exception {
    write("a/B.java", "one\nthree\n");
    assertEquals(Map.of(), parseDiff());
  }

  @Test
  void anAddedFileReportsEveryLineOfIt() throws Exception {
    write("a/C.java", "one\ntwo\n");
    commitAll();
    assertEquals(Map.of("a/C.java", lines(1, 2)), parseDiff());
  }

  @Test
  void aDeletedFileReportsNoLine() throws Exception {
    Files.delete(repository.resolve("a/B.java"));
    commitAll();
    assertEquals(Map.of(), parseDiff());
  }

  /** See {@code UnifiedDiffTest.anAddedLineThatLooksLikeAFileHeaderStaysContentOfTheFileItIsIn}. */
  @Test
  void anAddedLineBeginningWithTwoPlusSignsDoesNotHideTheRestOfTheFile() throws Exception {
    write("a/B.java", "++ tricky\ntwo\nthree\nFOUR\n");
    assertEquals(Map.of("a/B.java", lines(1, 4)), parseDiff());
  }

  /** See {@code UnifiedDiffTest.aDeletedAndAnAddedLineFormingAHeaderPairDoNotOpenAFile}. */
  @Test
  void replacingAHeaderLookingLineWithAnotherDoesNotHideTheRestOfTheFile() throws Exception {
    write("a/B.java", "-- a/x\ntwo\nthree\n");
    commitAll();
    write("a/B.java", "++ b/y\ntwo\nthree\nFOUR\n");
    assertEquals(Map.of("a/B.java", lines(1, 4)), parseDiff());
  }

  /**
   * See {@code UnifiedDiffTest.anAddedLineWhoseTextIsADiffGitHeaderStaysContentOfTheFileItIsIn}.
   */
  @Test
  void anAddedLineReadingLikeADiffGitHeaderDoesNotHideTheRestOfTheFile() throws Exception {
    write("a/B.java", "diff --git a/x b/x\ntwo\nthree\nFOUR\n");
    assertEquals(Map.of("a/B.java", lines(1, 4)), parseDiff());
  }

  @Test
  void theDiffPrefixesSurviveARepositoryConfiguredWithoutThem() throws Exception {
    run("config", "diff.noprefix", "true");
    write("a/B.java", "one\nCHANGED\nthree\n");
    assertEquals(Map.of("a/B.java", lines(2)), parseDiff());
  }

  /**
   * With {@code core.quotePath} left on, git wraps this path in quotes and escapes the byte, and
   * the {@code +++} line then starts with a quotation mark rather than with the prefix. It is no
   * file header, so every hunk of the file leaves the report with nothing to show that it
   * happened.
   *
   * <p>The name is written and read back first, since a machine whose locale cannot hold it has
   * nothing to say about git.
   */
  @Test
  void aPathHoldingANonAsciiByteIsReadLikeAnyOther() throws Exception {
    String path = "a/Caf\u00e9.java";
    write(path, "one\ntwo\n");
    assumeTrue(Files.exists(repository.resolve(path)), () -> "this file system holds " + path);
    commitAll();
    assertEquals(Map.of(path, lines(1, 2)), parseDiff());
  }

  @Test
  void theDiffPrefixesSurviveMnemonicPrefixes() throws Exception {
    run("config", "diff.mnemonicPrefix", "true");
    write("a/B.java", "one\nCHANGED\nthree\n");
    assertEquals(Map.of("a/B.java", lines(2)), parseDiff());
  }

  private Map<String, NavigableSet<Integer>> parseDiff() throws Exception {
    List<String> arguments = new ArrayList<>(DiffCoverageTask.diffCommand(base));
    return UnifiedDiff.parse(run(arguments.toArray(new String[0])));
  }

  private void write(String path, String content) throws IOException {
    Path file = repository.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private void commitAll() throws Exception {
    run("add", "-A");
    run("commit", "-m", "change");
  }

  private String run(String... arguments) throws Exception {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
    // The tests set diff.noprefix and diff.mnemonicPrefix on the repository on purpose, so the
    // configuration of the machine running them must not decide anything else.
    Map<String, String> environment = builder.environment();
    // A path git can read and will not find, rather than /dev/null, which names a file of its own
    // on Windows and would leave the configuration of the machine deciding what these tests see.
    String noConfig = repository.resolve("no-git-config").toString();
    environment.put("GIT_CONFIG_GLOBAL", noConfig);
    environment.put("GIT_CONFIG_SYSTEM", noConfig);
    environment.put("GIT_CONFIG_NOSYSTEM", "1");
    Process process = builder.start();
    String output;
    try (InputStream stream = process.getInputStream()) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      stream.transferTo(buffer);
      output = buffer.toString(StandardCharsets.UTF_8);
    }
    int exitCode = process.waitFor();
    assertEquals(0, exitCode, () -> "git " + String.join(" ", arguments) + " printed " + output);
    return output;
  }
}
