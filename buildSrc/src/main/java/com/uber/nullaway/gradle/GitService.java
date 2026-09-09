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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.process.ExecOperations;

/**
 * Runs git in one repository and remembers what each command printed.
 *
 * <p>Every module of a build asks the same questions of the same repository: which ref the diff is
 * taken against, which lines it changed, which files are untracked. Running git once per module
 * repeats work that cannot answer differently, and a build service is the one object a build has
 * that outlives a task and is shared across projects, so the answers are cached here.
 *
 * <p>A command runs at most once per build. The working tree can change while a build runs, and a
 * cached answer therefore describes the tree as it stood when the first module asked. That is the
 * behavior this cache is for: a report that names different changed lines in two modules of one
 * build would be read as a difference between the modules.
 *
 * <p>Instances are shared between tasks that may run in parallel, so the cache is concurrent and
 * git may run on several threads at once.
 */
public abstract class GitService implements BuildService<GitService.Parameters> {

  /** The repository every command of this service runs in. */
  public interface Parameters extends BuildServiceParameters {
    DirectoryProperty getRepositoryRoot();
  }

  /** What one git command printed, kept so that a repeat of it prints the same thing. */
  private static final class Output {
    /** The trimmed standard output, or null where git reported failure. */
    final String value;

    /** The trimmed standard error, which is what a failure has to report. */
    final String error;

    Output(String value, String error) {
      this.value = value;
      this.error = error;
    }
  }

  private final Map<List<String>, Output> outputs = new ConcurrentHashMap<>();

  @Inject
  protected abstract ExecOperations getExecOperations();

  /** Returns the repository the commands run in, which is also where its files are read from. */
  public File repositoryRoot() {
    return getParameters().getRepositoryRoot().get().getAsFile();
  }

  /**
   * Returns the trimmed output of a git command.
   *
   * @throws GradleException where git reported failure, since empty output means the command
   *     succeeded and found nothing, and the two must not read alike
   */
  public String output(String... arguments) {
    Output result = run(arguments);
    if (result.value == null) {
      throw new GradleException(
          "git " + String.join(" ", arguments) + " failed: " + result.error);
    }
    return result.value;
  }

  /** Returns the trimmed output of a git command, or null where git reported failure. */
  public String outputOrNull(String... arguments) {
    return run(arguments).value;
  }

  private Output run(String... arguments) {
    return outputs.computeIfAbsent(List.of(arguments), this::execute);
  }

  private Output execute(List<String> arguments) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(arguments);
    int exitCode =
        getExecOperations()
            .exec(
                spec -> {
                  spec.setWorkingDir(repositoryRoot());
                  spec.setCommandLine(command);
                  spec.setStandardOutput(output);
                  spec.setErrorOutput(error);
                  spec.setIgnoreExitValue(true);
                })
            .getExitValue();
    String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
    return new Output(
        exitCode == 0 ? text : null,
        new String(error.toByteArray(), StandardCharsets.UTF_8).trim());
  }
}
