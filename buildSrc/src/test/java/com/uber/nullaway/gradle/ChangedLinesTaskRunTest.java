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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The changed-lines file names the base ref it was measured against in its header, which the
 * pitestDiff report prints, and the lines changed under the module's source root.
 *
 * <p>The task runs in a synthetic Gradle project against a repository built here, with the system
 * and global git configuration switched off.
 */
class ChangedLinesTaskRunTest {

  @TempDir Path directory;

  private Path write() throws IOException {
    Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
    ChangedLinesTask task =
        project.getTasks().register("changedLines", ChangedLinesTask.class).get();
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
    task.getBaseRef().set("HEAD");
    Path output = directory.resolve("build/diff/changed-lines.txt");
    task.getOutputFile().set(output.toFile());
    task.write();
    return output;
  }

  @Test
  void theFileNamesTheBaseAndTheLinesChangedSinceIt() throws Exception {
    run("init", "--initial-branch=master", ".");
    Path source = directory.resolve("src/main/java/com/x/A.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "a1\na2\na3\n");
    run("add", "-A");
    run("-c", "user.email=t@example.com", "-c", "user.name=T", "commit", "-m", "seed");
    String head = revParse();
    Files.writeString(source, "a1\nchanged\na3\n");

    Path file = write();

    assertEquals(
        "against HEAD (" + head.substring(0, 9) + ")", ChangedLinesFile.readHeader(file));
    assertEquals(
        Map.of("com/x/A.java", new TreeSet<>(List.of(2))), ChangedLinesFile.read(file));
  }

  private String revParse() throws Exception {
    Process process =
        new ProcessBuilder("git", "rev-parse", "HEAD").directory(directory.toFile()).start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8).trim();
    assertEquals(0, process.waitFor(), "git rev-parse HEAD");
    return output;
  }

  private void run(String... arguments) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    String noConfig = directory.resolve("no-git-config").toString();
    builder.environment().put("GIT_CONFIG_GLOBAL", noConfig);
    builder.environment().put("GIT_CONFIG_SYSTEM", noConfig);
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    Process process = builder.start();
    String output;
    try (InputStream stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), UTF_8);
    }
    assertEquals(0, process.waitFor(), () -> String.join(" ", command) + ": " + output);
  }
}
