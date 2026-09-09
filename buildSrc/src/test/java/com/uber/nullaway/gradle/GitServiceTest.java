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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One git command runs once a build, and a failing one is told apart from an empty result.
 *
 * <p>The cache is what makes the service worth registering: twelve modules ask the same questions
 * of one repository, and a second run of a command can only repeat its answer. It is asserted by
 * changing the repository between two identical calls, since a call that ran again would report the
 * new state.
 *
 * <p>Every command runs against a repository built here. The fixture that builds it switches the
 * system and global configuration off for its own processes, so that a setting on the machine
 * running the tests decides nothing about what the repository holds.
 */
class GitServiceTest {

  @TempDir Path directory;

  private GitService service() {
    Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    return project
        .getGradle()
        .getSharedServices()
        .registerIfAbsent(
            "git",
            GitService.class,
            service -> service.getParameters().getRepositoryRoot().set(directory.toFile()))
        .get();
  }

  @Test
  void aRepeatedCommandKeepsTheAnswerOfItsFirstRun() throws Exception {
    repositoryWithOneCommit();
    GitService git = service();
    String first = git.output("rev-parse", "HEAD");

    commit("second");

    assertEquals(first, git.output("rev-parse", "HEAD"), "rev-parse HEAD after a second commit");
    assertNotEquals(first, head(), "the commit HEAD names after the second commit");
  }

  @Test
  void aCommandIsKeyedByItsWholeArgumentList() throws Exception {
    repositoryWithOneCommit();
    GitService git = service();
    git.output("rev-parse", "HEAD");

    commit("second");

    assertEquals(head(), git.output("rev-parse", "master"), "rev-parse master");
  }

  @Test
  void aFailingCommandComesBackAsNullRatherThanAsEmptyOutput() throws Exception {
    repositoryWithOneCommit();

    assertNull(
        service().outputOrNull("rev-parse", "--verify", "--quiet", "absent^{commit}"),
        "a ref that does not resolve");
  }

  @Test
  void aFailingCommandReportsWhatGitPrinted() throws Exception {
    repositoryWithOneCommit();
    GitService git = service();

    GradleException thrown =
        assertThrows(GradleException.class, () -> git.output("rev-parse", "absent"));

    String prefix = "git rev-parse absent failed: ";
    assertTrue(
        thrown.getMessage().startsWith(prefix),
        () -> "the command that failed: " + thrown.getMessage());
    assertNotEquals(
        "",
        thrown.getMessage().substring(prefix.length()),
        () -> "what git printed about the ref: " + thrown.getMessage());
  }

  @Test
  void aFailedCommandKeepsFailingForTheRestOfTheBuild() throws Exception {
    repositoryWithOneCommit();
    GitService git = service();
    String[] resolveAbsent = {"rev-parse", "--verify", "--quiet", "absent^{commit}"};
    git.outputOrNull(resolveAbsent);

    run("branch", "absent");

    assertNull(git.outputOrNull(resolveAbsent), "the ref the branch now names");
  }

  @Test
  void aCommandThatFoundNothingComesBackAsEmptyOutput() throws Exception {
    repositoryWithOneCommit();

    assertEquals(
        "",
        service().output("ls-files", "--others", "--exclude-standard", "--", "*.java"),
        "an untracked Java file");
  }

  /** Creates a repository whose only commit is on master. */
  private void repositoryWithOneCommit() throws Exception {
    run("init", "--initial-branch=master", ".");
    run("config", "user.email", "test@example.com");
    run("config", "user.name", "Test");
    commit("seed");
  }

  /** Adds a file named after the message and commits everything in the tree. */
  private void commit(String message) throws Exception {
    Files.writeString(directory.resolve(message + ".txt"), message + "\n");
    run("add", "-A");
    run("commit", "-m", message);
  }

  /** Returns the commit HEAD names now, read outside the service so no cache stands between. */
  private String head() throws Exception {
    return run("rev-parse", "HEAD").trim();
  }

  private String run(String... arguments) throws IOException, InterruptedException {
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
    assertEquals(0, process.waitFor(), () -> "git " + String.join(" ", arguments) + ": " + output);
    return output;
  }
}
