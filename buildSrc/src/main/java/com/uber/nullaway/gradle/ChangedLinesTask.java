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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Writes the lines this branch changed in one module's main sources to a {@link ChangedLinesFile}.
 *
 * <p>The file holds only files under the module's source roots, with paths relative to the root
 * that contains them, so it is empty for a module the change did not reach. Untracked Java files
 * count as changed in full.
 */
@UntrackedTask(
    because = "the working tree and the branch decide the result, and Gradle tracks neither")
public abstract class ChangedLinesTask extends DefaultTask {

  /** Source directories of the module, relative to the repository root. */
  @Internal
  public abstract ListProperty<String> getSourceRoots();

  /** The ref whose merge base with HEAD the diff is taken from; see {@link ChangedLines#measure}. */
  @Internal
  public abstract Property<String> getBaseRef();

  /** The file the changed lines are written to. */
  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  /** Runs the git commands, and names the repository the diff is read from. */
  @ServiceReference(DiffCoverageTask.GIT_SERVICE)
  public abstract Property<GitService> getGit();

  @TaskAction
  public void write() throws IOException {
    ChangedLines diff = ChangedLines.measure(getGit().get(), getBaseRef().getOrNull());
    if (diff == null) {
      getLogger().warn("No base ref resolved, so no line counts as changed. {}",
          ChangedLines.NO_BASE_HINT);
      ChangedLinesFile.write(
          getOutputFile().get().getAsFile().toPath(), List.of("no base ref resolved"), Map.of());
      return;
    }
    for (String note : diff.unreadable()) {
      getLogger().warn(note);
    }
    ChangedLinesFile.write(
        getOutputFile().get().getAsFile().toPath(),
        List.of("against " + diff.ref() + " (" + diff.shortCommit() + ")"),
        underSourceRoots(diff.lines(), getSourceRoots().get()));
  }

  /**
   * Returns the entries whose path lies under one of the source roots, keyed by the path relative
   * to that root.
   */
  static Map<String, NavigableSet<Integer>> underSourceRoots(
      Map<String, NavigableSet<Integer>> changed, List<String> sourceRoots) {
    Map<String, NavigableSet<Integer>> relative = new TreeMap<>();
    for (Map.Entry<String, NavigableSet<Integer>> entry : changed.entrySet()) {
      for (String root : sourceRoots) {
        String prefix = root.endsWith("/") ? root : root + "/";
        if (entry.getKey().startsWith(prefix)) {
          relative.put(entry.getKey().substring(prefix.length()), entry.getValue());
          break;
        }
      }
    }
    return relative;
  }
}
