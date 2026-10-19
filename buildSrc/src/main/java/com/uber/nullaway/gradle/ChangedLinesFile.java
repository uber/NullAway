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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads and writes the changed lines of one module as a text file.
 *
 * <p>Each line of the file names one source file and its changed lines, separated by a tab, as in
 * {@code com/uber/nullaway/NullAway.java<TAB>12-14,40}. The path is relative to the source root, which is
 * the form a class file records through its package and its {@code SourceFile} attribute, so a
 * tool that sees only bytecode can match a mutant to the file. Lines starting with {@code #} are
 * comments.
 *
 * <p>This class is loaded both by the build and by the PIT process, so it depends on nothing but
 * the JDK.
 */
public final class ChangedLinesFile {

  /**
   * The system property naming the file for the PIT interceptor in {@code
   * com.uber.nullaway.gradle.pitest}, set on the JVM that runs PIT.
   *
   * <p>It is declared here rather than beside the interceptor because the build reads it too, and
   * the build cannot load a class that implements a PIT interface.
   */
  public static final String PITEST_PROPERTY = "nullaway.pitest.changedLines";

  private ChangedLinesFile() {}

  /**
   * Writes the changed lines to a file, replacing whatever it held.
   *
   * @param header comment lines written before the entries, without the leading {@code #}
   * @param lines the changed line numbers, keyed by path relative to the source root; a path with
   *     no line is left out
   */
  public static void write(Path file, List<String> header, Map<String, NavigableSet<Integer>> lines)
      throws IOException {
    StringBuilder text = new StringBuilder();
    for (String comment : header) {
      text.append("# ").append(comment).append('\n');
    }
    for (Map.Entry<String, NavigableSet<Integer>> entry : lines.entrySet()) {
      // A file with no changed line has no entry, so every entry the reader parses names a line.
      if (entry.getValue().isEmpty()) {
        continue;
      }
      text.append(entry.getKey()).append('\t').append(ranges(entry.getValue())).append('\n');
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  /** Returns the first comment line of the file without its {@code #}, or null where it has none. */
  public static String readHeader(Path file) throws IOException {
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.startsWith("# ")) {
        return line.substring(2);
      }
    }
    return null;
  }

  /** Returns the changed line numbers the file holds, keyed by path relative to the source root. */
  public static Map<String, NavigableSet<Integer>> read(Path file) throws IOException {
    Map<String, NavigableSet<Integer>> lines = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int tab = line.lastIndexOf('\t');
      if (tab < 0) {
        throw new IOException("No tab in " + file + " line: " + line);
      }
      NavigableSet<Integer> numbers = new TreeSet<>();
      for (String range : line.substring(tab + 1).split(",")) {
        int dash = range.indexOf('-');
        int first = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
        int last = dash < 0 ? first : Integer.parseInt(range.substring(dash + 1));
        for (int number = first; number <= last; number++) {
          numbers.add(number);
        }
      }
      lines.put(line.substring(0, tab), numbers);
    }
    return lines;
  }

  /**
   * Returns the PIT {@code targetClasses} patterns for every class a changed file can compile to.
   *
   * <p>A source file compiles to its own top-level class, the classes nested in it, and any other
   * top-level class it declares, which carries a name of its own. So each changed file contributes
   * every class of its package, and the interceptor keeps only the mutants whose class records the
   * changed file as its source. {@code package-info.java} and {@code module-info.java} compile to no
   * class PIT mutates and contribute nothing.
   *
   * <p>A pattern starting with {@code ~} is a regular expression to PIT. A glob would not do, since
   * PIT's {@code *} also matches subpackages. PIT escapes every {@code +} in the expression, so the
   * pattern repeats a character class instead of using {@code +}.
   *
   * @param lines the changed line numbers, keyed by path relative to the source root
   */
  public static List<String> targetClasses(Map<String, NavigableSet<Integer>> lines) {
    Set<String> patterns = new LinkedHashSet<>();
    for (String path : lines.keySet()) {
      if (!path.endsWith(".java")
          || path.endsWith("package-info.java")
          || path.endsWith("module-info.java")) {
        continue;
      }
      int slash = path.lastIndexOf('/');
      String packagePrefix = slash < 0 ? "" : path.substring(0, slash).replace('/', '.') + ".";
      patterns.add(
          "~" + (packagePrefix.isEmpty() ? "" : Pattern.quote(packagePrefix)) + "[^.][^.]*");
    }
    return new ArrayList<>(patterns);
  }

  /** Returns the numbers as comma-separated ranges, such as {@code 3-5,9}. */
  static String ranges(NavigableSet<Integer> numbers) {
    List<String> ranges = new ArrayList<>();
    Integer first = null;
    Integer last = null;
    for (int number : numbers) {
      if (last != null && number == last + 1) {
        last = number;
        continue;
      }
      if (first != null) {
        ranges.add(first.equals(last) ? first.toString() : first + "-" + last);
      }
      first = number;
      last = number;
    }
    if (first != null) {
      ranges.add(first.equals(last) ? first.toString() : first + "-" + last);
    }
    return String.join(",", ranges);
  }
}
