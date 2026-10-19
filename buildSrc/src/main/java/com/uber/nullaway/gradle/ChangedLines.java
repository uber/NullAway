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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.gradle.api.GradleException;

/**
 * The lines a branch changed, and the base ref they were measured against.
 *
 * <p>Diff coverage and diff mutation testing answer different questions about the same set of
 * lines, so both read it from here: a change to how the base is chosen moves both reports at once,
 * and {@code -PdiffBase} means the same thing to each of them.
 *
 * @param ref the ref the diff was taken against, as the caller or git named it
 * @param commit the merge base of that ref and HEAD
 * @param lines the changed lines of every Java file, keyed by repository-relative path
 * @param unreadable a line naming each untracked file this JVM could not read
 */
record ChangedLines(
    String ref, String commit, Map<String, NavigableSet<Integer>> lines, List<String> unreadable) {

  /** The Gradle property that names the base ref for every diff-based report. */
  static final String BASE_PROPERTY = "diffBase";

  static final String NO_BASE_HINT =
      "Pass -P" + BASE_PROPERTY + "=<ref>, or set an upstream branch, or fetch origin/master.";

  private static final String[] UPSTREAM_NAME = {"rev-parse", "--abbrev-ref", "@{upstream}"};

  private static final String[] BRANCH_NAME = {"rev-parse", "--abbrev-ref", "HEAD"};

  /**
   * Returns the lines changed since the merge base of HEAD and the base ref, or null where no
   * base resolves.
   *
   * <p>Taking the merge base rather than the ref itself keeps commits that landed on the ref since
   * the branch point out of the diff, so the result covers this branch's own work. With no
   * explicit ref, the upstream branch is tried, then {@code origin/master}.
   *
   * @param explicit the ref the caller named, or null
   * @throws GradleException where the explicit ref does not resolve, because diffing against
   *     something else would report the wrong lines as changed
   */
  static ChangedLines measure(GitService git, String explicit) {
    if (explicit != null && !resolves(git, explicit)) {
      throw new GradleException(BASE_PROPERTY + " " + explicit + " does not resolve to a commit");
    }
    String upstream = git.outputOrNull(UPSTREAM_NAME);
    String branch = git.outputOrNull(BRANCH_NAME);
    for (String candidate : baseCandidates(explicit, upstream, branch)) {
      if (!resolves(git, candidate)) {
        continue;
      }
      // A ref can resolve and still share no commit with HEAD, as in a shallow clone or after an
      // orphan branch, and merge-base then fails. That is a base this run does not have, not a
      // reason to fail a build whose tests passed.
      String mergeBase = git.outputOrNull("merge-base", candidate, "HEAD");
      if (mergeBase != null) {
        List<String> unreadable = new ArrayList<>();
        return new ChangedLines(
            candidate, mergeBase, changedLines(git, mergeBase, unreadable), unreadable);
      }
    }
    return null;
  }

  /** Returns the merge base abbreviated the way the reports print it. */
  String shortCommit() {
    return commit.substring(0, Math.min(9, commit.length()));
  }

  /**
   * Returns the refs to try as the base, in order.
   *
   * <p>An explicit ref stands alone: falling through from the ref the caller named to another one
   * would report a different set of lines as changed, under a base the caller did not choose.
   *
   * <p>An upstream that is this branch's own pushed copy is left out. In a fork workflow the branch
   * tracks the fork, so after the first push its upstream is the branch itself, every line it
   * changed is already in the base, and the report says nothing was changed rather than that it
   * could not find a base.
   *
   * @param explicit the ref the caller named, or null
   * @param upstreamRef the upstream branch as git names it, such as {@code origin/develop}, or null
   * @param currentBranch the checked-out branch, or null on a detached HEAD
   */
  static List<String> baseCandidates(String explicit, String upstreamRef, String currentBranch) {
    if (explicit != null) {
      return List.of(explicit);
    }
    if (upstreamRef == null || isOwnPushedBranch(upstreamRef, currentBranch)) {
      return List.of("origin/master");
    }
    return List.of(upstreamRef, "origin/master");
  }

  /**
   * Returns whether the upstream ref is the current branch pushed to a remote.
   *
   * <p>The comparison drops the remote name, which is the first segment, so that a branch of its
   * own named after the tail of another one is not mistaken for it.
   */
  static boolean isOwnPushedBranch(String upstreamRef, String currentBranch) {
    if (currentBranch == null) {
      return false;
    }
    int remoteEnd = upstreamRef.indexOf('/');
    String branch = remoteEnd < 0 ? upstreamRef : upstreamRef.substring(remoteEnd + 1);
    return branch.equals(currentBranch);
  }

  /**
   * Returns the git arguments the diff is taken with.
   *
   * <p>{@code --unified=0} is what makes a hunk header name only changed lines, and the prefixes
   * are pinned because {@code diff.noprefix} and {@code diff.mnemonicPrefix} are personal settings
   * that would otherwise change the {@code a/} and {@code b/} that {@link UnifiedDiff} strips.
   *
   * <p>{@code core.quotePath} is off because it wraps a path holding a non-ASCII byte in quotes
   * and escapes the byte, as in {@code "b/src/caf\303\251.java"}. Such a {@code +++} line starts
   * with a quotation mark rather than with the prefix, so it is no file header, and every hunk of
   * that file leaves the report with nothing to show that it happened.
   */
  static List<String> diffCommand(String base) {
    return List.of(
        "-c",
        "core.quotePath=false",
        "diff",
        "--unified=0",
        "--no-color",
        "--src-prefix=a/",
        "--dst-prefix=b/",
        base,
        "--",
        "*.java");
  }

  /** Returns whether the ref names a commit of this repository. */
  private static boolean resolves(GitService git, String ref) {
    return git.outputOrNull("rev-parse", "--verify", "--quiet", ref + "^{commit}") != null;
  }

  /**
   * Returns the changed lines of every Java file, keyed by repository-relative path.
   *
   * <p>Two sources are merged: the post-image lines of the diff between base and the working tree,
   * which covers committed, staged and unstaged changes alike, and every line of an untracked
   * file, which no diff mentions at all.
   *
   * @param unreadable collects a line naming each untracked file this JVM cannot decode
   */
  private static Map<String, NavigableSet<Integer>> changedLines(
      GitService git, String base, List<String> unreadable) {
    String diff = git.output(diffCommand(base).toArray(new String[0]));
    Map<String, NavigableSet<Integer>> changed = UnifiedDiff.parse(diff);
    // core.quotePath is off here for the reason diffCommand gives: a quoted path names no file
    // this JVM can open, and the file would be reported as unreadable rather than measured.
    String untracked =
        git.output(
            "-c",
            "core.quotePath=false",
            "ls-files",
            "--others",
            "--exclude-standard",
            "--",
            "*.java");
    if (untracked.isEmpty()) {
      return changed;
    }
    for (String path : untracked.split("\n")) {
      if (path.isEmpty()) {
        continue;
      }
      // An untracked file has no diff, so every line in it counts as changed.
      NavigableSet<Integer> lines = new TreeSet<>();
      try {
        int count = Files.readAllLines(git.repositoryRoot().toPath().resolve(path)).size();
        for (int number = 1; number <= count; number++) {
          lines.add(number);
        }
      } catch (IOException e) {
        // A broken symlink or a file this JVM cannot decode is not worth failing a build whose
        // tests passed, so it is named and left out of the counts.
        unreadable.add("cannot read the untracked file " + path + ": " + e);
        continue;
      }
      changed.put(path, lines);
    }
    return changed;
  }
}
