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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the post-image line numbers out of a unified diff. */
final class UnifiedDiff {

  private static final Pattern HUNK_HEADER =
      Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");

  private UnifiedDiff() {}

  /**
   * Returns the lines each file gained or had rewritten, keyed by repository-relative path.
   *
   * <p>The diff has to be produced with {@code --unified=0} and the default {@code a/} and {@code
   * b/} prefixes, which {@code diff.noprefix} and {@code diff.mnemonicPrefix} would otherwise
   * change. A hunk that only deletes lines contributes none, since the deleted lines are in no file
   * a coverage report can mention.
   *
   * <p>A file header counts as one only in the header section git opens with {@code diff --git},
   * and only as the {@code +++} directly under its {@code ---}. Content carries the same shapes: an
   * added line whose text starts with {@code ++} reaches the diff as {@code +++ …}, and a deleted
   * line reading {@code -- a/x} beside an added {@code ++ b/y} reaches it as a header pair. Reading
   * either as a header opens a file named after that text and takes every later hunk of the real
   * file with it, which drops those lines from the report with nothing to show that it happened.
   */
  static Map<String, NavigableSet<Integer>> parse(String diff) {
    Map<String, NavigableSet<Integer>> changed = new LinkedHashMap<>();
    NavigableSet<Integer> current = null;
    boolean inHeaderSection = false;
    boolean afterSourceHeader = false;
    for (String line : diff.split("\n", -1)) {
      if (line.startsWith("diff --git ")) {
        inHeaderSection = true;
        afterSourceHeader = false;
        current = null;
      } else if (inHeaderSection && isHeader(line, "--- ", "a/")) {
        afterSourceHeader = true;
      } else if (afterSourceHeader && isHeader(line, "+++ ", "b/")) {
        String target = line.substring(4).trim();
        current =
            target.equals("/dev/null")
                ? null
                : changed.computeIfAbsent(target.substring(2), unused -> new TreeSet<>());
        inHeaderSection = false;
        afterSourceHeader = false;
      } else if (current != null && line.startsWith("@@")) {
        Matcher matcher = HUNK_HEADER.matcher(line);
        if (matcher.matches()) {
          int start = Integer.parseInt(matcher.group(1));
          int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
          for (int number = start; number < start + count; number++) {
            current.add(number);
          }
        }
        inHeaderSection = false;
        afterSourceHeader = false;
      } else {
        afterSourceHeader = false;
      }
    }
    changed.values().removeIf(NavigableSet::isEmpty);
    return changed;
  }

  /** Returns whether the line is a file header naming a path under prefix, or /dev/null. */
  private static boolean isHeader(String line, String marker, String prefix) {
    if (!line.startsWith(marker)) {
      return false;
    }
    String target = line.substring(marker.length()).trim();
    return target.equals("/dev/null") || target.startsWith(prefix);
  }
}
