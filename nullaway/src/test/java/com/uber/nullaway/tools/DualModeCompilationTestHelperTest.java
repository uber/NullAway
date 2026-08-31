/*
 * Copyright (c) 2026 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.uber.nullaway.NullAway;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Checks that {@link TestMode#BYTECODE} does what the suites relying on it assume.
 *
 * <p>Every other test reaches bytecode mode through {@link com.uber.nullaway.NullAwayTestsBase},
 * where a snippet the mode cannot handle is skipped through a JUnit assumption. That is right for a
 * snippet, and wrong as the only outcome the suite can produce: were the class-file path to break
 * for a reason no snippet controls, the suite would report skips and stay green. These tests fail
 * instead, so a break in the mode itself is a red build.
 *
 * <p>Each snippet below gives one file a NullAway error with no marker. That file is the
 * discriminator: it must end up compiled to a class file, since analyzing it reports a diagnostic
 * on a line carrying no marker and fails the run.
 */
@RunWith(JUnit4.class)
public class DualModeCompilationTestHelperTest {

  /**
   * Returns a helper over a two-file snippet whose dependency holds a NullAway error with no
   * marker, and whose analyzed file holds one with a marker.
   *
   * <p>The snippet passes in bytecode mode only if all three of these hold: {@code Dep.java} was
   * compiled by plain javac and so was never analyzed, since its unmarked error would otherwise
   * fail the run; the class file reached NullAway on the classpath, since {@code Dep} would
   * otherwise be an unresolved symbol; and {@code @Nullable} survived into the class file, since
   * the marked diagnostic would otherwise not be reported. It fails in source mode, on the unmarked
   * error.
   *
   * @param testMode the mode to run the snippet in
   * @return the test helper
   */
  private DualModeCompilationTestHelper snippetSplitByItsMarkers(TestMode testMode) {
    return DualModeCompilationTestHelper.newInstance(NullAway.class, getClass(), testMode)
        .setArgs(List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Dep.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Dep {
              @Nullable public Object get() { return null; }
              public void unmarkedError(@Nullable Object o) { o.toString(); }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            class Test {
              void f(Dep d) {
                // BUG: Diagnostic contains: dereferenced expression
                d.get().toString();
              }
            }
            """);
  }

  /**
   * Runs a snippet, turning the assumption that skips an unsplittable snippet into a failure.
   *
   * @param helper the test helper
   */
  private static void runExpectingNoSkip(DualModeCompilationTestHelper helper) {
    try {
      helper.doTest();
    } catch (AssumptionViolatedException e) {
      throw new AssertionError("bytecode mode skipped a snippet that must split", e);
    }
  }

  @Test
  public void bytecodeModeAnalyzesOnlyTheMarkedFile() {
    runExpectingNoSkip(snippetSplitByItsMarkers(TestMode.BYTECODE));
  }

  @Test
  public void sourceModeAnalyzesEveryFile() {
    DualModeCompilationTestHelper helper = snippetSplitByItsMarkers(TestMode.SOURCE);
    assertThrows(AssertionError.class, helper::doTest);
  }

  /**
   * Returns a helper carrying the annotated-packages flag and the first source file of a snippet,
   * to which the caller adds the rest.
   *
   * @param testMode the mode to run the snippet in
   * @param path the path of the source file
   * @param lines the content of the source file
   * @return the test helper
   */
  private DualModeCompilationTestHelper snippetStartingWith(
      TestMode testMode, String path, String... lines) {
    return DualModeCompilationTestHelper.newInstance(NullAway.class, getClass(), testMode)
        .setArgs(List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(path, lines);
  }

  @Test
  public void bytecodeModeSplitsASnippetWithNoMarkerAtItsLastFile() {
    runExpectingNoSkip(
        snippetStartingWith(
                TestMode.BYTECODE,
                "Dep.java",
                """
                package com.uber;
                import javax.annotation.Nullable;
                public class Dep {
                  public void unmarkedError(@Nullable Object o) { o.toString(); }
                }
                """)
            .addSourceLines(
                "Test.java",
                """
                package com.uber;
                class Test {
                  void f(Dep d) { d.unmarkedError(new Object()); }
                }
                """));
  }

  @Test
  public void bytecodeModeFallsBackToAnalyzingTheFirstFile() {
    runExpectingNoSkip(
        snippetStartingWith(
                TestMode.BYTECODE,
                "Test.java",
                """
                package com.uber;
                class Test {
                  void f(Dep d) { d.get(); }
                }
                """)
            .addSourceLines(
                "Dep.java",
                """
                package com.uber;
                import javax.annotation.Nullable;
                public class Dep {
                  public Object get() { return new Object(); }
                  public void unmarkedError(@Nullable Object o) { o.toString(); }
                }
                """));
  }

  @Test
  public void bytecodeModeShortensTheDependencySetUntilItCompiles() {
    // NeedsMarked precedes Standalone, so no prefix of the unmarked files compiles and only
    // dropping the file javac reported on reaches a split.
    runExpectingNoSkip(
        snippetStartingWith(
                TestMode.BYTECODE,
                "Marked.java",
                """
                package com.uber;
                import javax.annotation.Nullable;
                public class Marked {
                  @Nullable public Object get() { return null; }
                  void f() {
                    // BUG: Diagnostic contains: dereferenced expression
                    get().toString();
                  }
                }
                """)
            .addSourceLines(
                "NeedsMarked.java",
                """
                package com.uber;
                public class NeedsMarked {
                  void f(Marked m) { m.get(); }
                }
                """)
            .addSourceLines(
                "Standalone.java",
                """
                package com.uber;
                import javax.annotation.Nullable;
                public class Standalone {
                  public void unmarkedError(@Nullable Object o) { o.toString(); }
                }
                """));
  }

  @Test
  public void bytecodeModeKeepsTheLargestDependencySetThatCompiles() {
    // Nothing is marked, so the candidates are {A, B} and {B, C}. A refers to C, so the first
    // shrinks to {B} while the second compiles whole, and only the second puts C on the classpath.
    runExpectingNoSkip(
        snippetStartingWith(
                TestMode.BYTECODE,
                "A.java",
                """
                package com.uber;
                class A {
                  void f(C c) { c.ok(); }
                }
                """)
            .addSourceLines(
                "B.java",
                """
                package com.uber;
                public class B {
                  public void ok() {}
                }
                """)
            .addSourceLines(
                "C.java",
                """
                package com.uber;
                import javax.annotation.Nullable;
                public class C {
                  public void ok() {}
                  public void unmarkedError(@Nullable Object o) { o.toString(); }
                }
                """));
  }

  /** The spellings javac accepts for the classpath option, each with the value {@code custom}. */
  private static final List<List<String>> CLASSPATH_SPELLINGS =
      List.of(
          List.of("-classpath", "custom"),
          List.of("-cp", "custom"),
          List.of("--class-path", "custom"),
          List.of("--class-path=custom"));

  /**
   * Returns the value of the single classpath option in a javac command line.
   *
   * @param options the javac arguments
   * @return the classpath
   */
  private static String onlyClasspath(List<String> options) {
    List<String> values = new ArrayList<>();
    for (int i = 0; i < options.size(); i++) {
      String option = options.get(i);
      if (option.startsWith("--class-path=")) {
        values.add(option.substring("--class-path=".length()));
      } else if (List.of("-classpath", "-cp", "--class-path").contains(option)) {
        values.add(options.get(++i));
      }
    }
    assertEquals("javac takes the last classpath option, so exactly one may be passed", 1, values.size());
    return values.get(0);
  }

  @Test
  public void aDeclaredClasspathReachesBothCompilations() {
    Path classes = Paths.get("classes");
    for (List<String> spelling : CLASSPATH_SPELLINGS) {
      List<String> args = new ArrayList<>(List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber"));
      args.addAll(spelling);
      DualModeCompilationTestHelper helper =
          DualModeCompilationTestHelper.newInstance(NullAway.class, getClass(), TestMode.BYTECODE)
              .setArgs(args);
      assertEquals(
          "sources becoming class files are compiled against what the test declared: " + spelling,
          "custom",
          onlyClasspath(helper.javacOptions(classes)));
      assertEquals(
          "analyzed sources see the class files ahead of what the test declared: " + spelling,
          classes + File.pathSeparator + "custom",
          onlyClasspath(helper.argsWithClasspath(classes)));
    }
  }

  @Test
  public void withoutADeclaredClasspathBothCompilationsUseTheRuntimeClasspath() {
    Path classes = Paths.get("classes");
    DualModeCompilationTestHelper helper =
        DualModeCompilationTestHelper.newInstance(NullAway.class, getClass(), TestMode.BYTECODE)
            .setArgs(List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber"));
    String runtimeClasspath = System.getProperty("java.class.path");
    assertEquals(runtimeClasspath, onlyClasspath(helper.javacOptions(classes)));
    assertEquals(
        classes + File.pathSeparator + runtimeClasspath,
        onlyClasspath(helper.argsWithClasspath(classes)));
  }

  @Test
  public void bytecodeModeSkipsASnippetItCannotSplit() {
    DualModeCompilationTestHelper helper =
        DualModeCompilationTestHelper.newInstance(NullAway.class, getClass(), TestMode.BYTECODE)
            .setArgs(List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
            .addSourceLines(
                "Test.java",
                """
                package com.uber;
                import javax.annotation.Nullable;
                class Test {
                  void f(@Nullable Object o) {
                    // BUG: Diagnostic contains: dereferenced expression
                    o.toString();
                  }
                }
                """);
    assertThrows(AssumptionViolatedException.class, helper::doTest);
  }
}
