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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Compares the diagnostics of a multi-file test against the diagnostics of the same test with its
 * compilation units in the opposite order.
 *
 * <p>The order in which a build system hands source files to javac is not part of the program, so
 * the report must not depend on it. NullAway has depended on it before: <a
 * href="https://github.com/uber/NullAway/issues/1725">#1725</a> and <a
 * href="https://github.com/uber/NullAway/issues/1733">#1733</a> both reported a diagnostic against
 * whichever compilation unit happened to create the dataflow analysis rather than against the file
 * holding the call.
 *
 * <p>Reversing the list is enough to catch that class of defect and costs one extra compilation per
 * multi-file test, where checking every permutation would cost <i>n!</i>.
 *
 * <p>No test writes anything for this: the check applies to every test that hands the compiler more
 * than one file, which is 214 of them today.
 *
 * <p>Off by default. Turn it on with {@code ./gradlew :nullaway:test -PpermuteSources=true}; CI
 * turns it on for some rows of its build matrix.
 */
public final class SourceOrder {

  /** The documentation link Error Prone appends to every NullAway message. */
  private static final Pattern SEE_LINK = Pattern.compile("\\s*\\(see http\\S*\\s*\\)\\s*$");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private SourceOrder() {}

  /** Returns whether the check runs in this JVM. */
  public static boolean isEnabled() {
    return Boolean.getBoolean("nullaway.permute.sources");
  }

  /**
   * Renders diagnostics as sorted {@code <file>:<line>: <message>} entries, so that two runs can be
   * compared without depending on the order they came out in.
   *
   * @param diagnostics the diagnostics of one compilation
   * @return one entry per diagnostic that names a line, sorted
   */
  public static List<String> render(
      List<? extends Diagnostic<? extends JavaFileObject>> diagnostics) {
    List<String> rendered = new ArrayList<>();
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
      if (diagnostic.getSource() == null || diagnostic.getLineNumber() == Diagnostic.NOPOS) {
        continue;
      }
      String message = SEE_LINK.matcher(diagnostic.getMessage(Locale.getDefault())).replaceAll("");
      rendered.add(
          fileName(diagnostic.getSource())
              + ":"
              + diagnostic.getLineNumber()
              + ": "
              + WHITESPACE.matcher(message).replaceAll(" ").trim());
    }
    rendered.sort(String::compareTo);
    return rendered;
  }

  /**
   * Throws when the two runs disagree.
   *
   * @param asGiven diagnostics of the compilation with the sources in the order the test gave them
   * @param reversed diagnostics of the compilation with the sources reversed
   * @throws AssertionError if the two differ
   */
  public static void compare(List<String> asGiven, List<String> reversed) {
    if (asGiven.equals(reversed)) {
      return;
    }
    List<String> onlyAsGiven = new ArrayList<>(asGiven);
    onlyAsGiven.removeAll(reversed);
    List<String> onlyReversed = new ArrayList<>(reversed);
    onlyReversed.removeAll(asGiven);
    throw new AssertionError(
        "Reversing the order of the compilation units changed the diagnostics.\n"
            + "Only in the order the test gave:\n  "
            + String.join("\n  ", onlyAsGiven)
            + "\nOnly in the reversed order:\n  "
            + String.join("\n  ", onlyReversed));
  }

  /** Returns the last path segment of {@code source}, which is what a test names it. */
  private static String fileName(JavaFileObject source) {
    String path = source.toUri().getPath();
    if (path == null) {
      return source.getName();
    }
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
