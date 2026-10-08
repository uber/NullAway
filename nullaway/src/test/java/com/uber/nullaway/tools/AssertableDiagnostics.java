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
package com.uber.nullaway.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Checks that every diagnostic a test compilation reports lands where the test framework looks for
 * it.
 *
 * <p>{@link com.google.errorprone.CompilationTestHelper} asserts line by line over the sources it
 * was given: a line with a {@code // BUG: Diagnostic contains:} comment must carry a matching
 * diagnostic, and a line without one must carry none. A diagnostic that names a line past the end
 * of its file, or a file that was not compiled as a source, falls outside that loop, so no test can
 * assert on it and no test fails when it appears.
 *
 * <p>Misattribution is not hypothetical here: <a
 * href="https://github.com/uber/NullAway/issues/1725">#1725</a> and <a
 * href="https://github.com/uber/NullAway/issues/1733">#1733</a> both reported a diagnostic against
 * the wrong compilation unit, and {@code GenericInferenceErrorReportingTests} pads its first source
 * with twenty comment lines so that a misattributed diagnostic lands on a line that exists.
 */
public final class AssertableDiagnostics {

  /** Temporary directory names the test file manager invents, dropped from a message. */
  private static final Pattern TEMPORARY_DIRECTORY =
      Pattern.compile("/[^/]*junit[^/]*/", Pattern.CASE_INSENSITIVE);

  private AssertableDiagnostics() {}

  /**
   * Throws when a diagnostic names a line no source has.
   *
   * @param sources the compilation units the test handed to the compiler
   * @param diagnostics every diagnostic the compilation reported
   * @throws AssertionError if any diagnostic names a file outside {@code sources}, or a line past
   *     the end of the file it names
   */
  public static void checkEveryDiagnosticIsAssertable(
      Iterable<? extends JavaFileObject> sources,
      List<? extends Diagnostic<? extends JavaFileObject>> diagnostics) {
    Map<URI, Integer> lineCounts = new HashMap<>();
    for (JavaFileObject source : sources) {
      lineCounts.put(source.toUri(), lineCount(source));
    }
    List<String> unassertable = new ArrayList<>();
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
      String reason = whyUnassertable(diagnostic, lineCounts);
      if (reason != null) {
        unassertable.add(
            shortName(diagnostic.getSource())
                + ":"
                + diagnostic.getLineNumber()
                + " ("
                + reason
                + ") "
                + diagnostic.getMessage(Locale.getDefault()).replaceAll("\\s+", " "));
      }
    }
    if (!unassertable.isEmpty()) {
      throw new AssertionError(
          "No line of any compiled source can assert on these diagnostics, so no test would fail if"
              + " they changed:\n  "
              + String.join("\n  ", unassertable));
    }
  }

  /** Returns why {@code diagnostic} is out of reach of the line-by-line assertions, or null. */
  private static @org.jspecify.annotations.Nullable String whyUnassertable(
      Diagnostic<? extends JavaFileObject> diagnostic, Map<URI, Integer> lineCounts) {
    // javac reports its summary notes ("uses unchecked or unsafe operations") against a file at
    // NOPOS. No line-based assertion can reach those, and none is meant to.
    if (diagnostic.getSource() == null || diagnostic.getLineNumber() == Diagnostic.NOPOS) {
      return null;
    }
    Integer lines = lineCounts.get(diagnostic.getSource().toUri());
    if (lines == null) {
      return "names a file that was not compiled as a source";
    }
    if (diagnostic.getLineNumber() > lines) {
      return "names line " + diagnostic.getLineNumber() + " of a " + lines + "-line file";
    }
    return null;
  }

  /** Returns the number of lines in {@code source}, counting a final line without a newline. */
  private static int lineCount(JavaFileObject source) {
    try {
      CharSequence content = source.getCharContent(false);
      if (content.length() == 0) {
        return 0;
      }
      int lines = 1;
      for (int i = 0; i < content.length() - 1; i++) {
        if (content.charAt(i) == '\n') {
          lines++;
        }
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns a file name for a message, without the temporary directory the test manager invents.
   */
  private static String shortName(JavaFileObject source) {
    String path = source.toUri().getPath();
    return path == null ? source.getName() : TEMPORARY_DIRECTORY.matcher(path).replaceAll("/");
  }
}
