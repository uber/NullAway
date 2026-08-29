package com.uber.nullaway.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.errorprone.scanner.ScannerSupplier;
import com.uber.nullaway.NullAway;
import org.junit.Assume;
import org.junit.Test;

public class ReversingCompilationTestHelperTest {

  private static final String PERMUTE_SOURCES = "nullaway.permute.sources";

  @Test
  public void aDiagnosticThatMovesWithTheOrderFailsTheReversedRun() {
    Assume.assumeTrue("the reversed compilation is off", Boolean.getBoolean(PERMUTE_SOURCES));
    ReversingCompilationTestHelper helper = duplicateClassMarkedInSecondFile();
    AssertionError error = assertThrows(AssertionError.class, helper::doTest);
    assertThat(error).hasMessageThat().contains("fails with them reversed");
    assertThat(error).hasCauseThat().hasMessageThat().contains("/A.java");
  }

  @Test
  public void aDiagnosticThatMovesWithTheOrderPassesWhenTheCheckIsOff() {
    String previous = System.getProperty(PERMUTE_SOURCES);
    System.setProperty(PERMUTE_SOURCES, "false");
    try {
      duplicateClassMarkedInSecondFile().doTest();
    } finally {
      if (previous == null) {
        System.clearProperty(PERMUTE_SOURCES);
      } else {
        System.setProperty(PERMUTE_SOURCES, previous);
      }
    }
  }

  @Test
  public void aDiagnosticThatStaysInItsFilePassesBothRuns() {
    Assume.assumeTrue("the reversed compilation is off", Boolean.getBoolean(PERMUTE_SOURCES));
    newHelper()
        .addSourceLines(
            "A.java",
            """
            package test;
            class A {}
            """)
        .addSourceLines(
            "B.java",
            """
            package test;
            class B {
              // BUG: Diagnostic contains: cannot find symbol
              Object o = undeclared;
            }
            """)
        .doTest();
  }

  @Test
  public void aSecondDoTestIsRefused() {
    ReversingCompilationTestHelper helper =
        newHelper()
            .addSourceLines(
                "A.java",
                """
                package test;
                class A {}
                """);
    helper.doTest();
    assertThrows(IllegalStateException.class, helper::doTest);
  }

  /**
   * Returns a helper over two files that both declare {@code test.Dup}. javac reports {@code
   * duplicate class} against whichever declaration it reads second, so the marker in {@code B.java}
   * holds in the given order and not in the reversed one.
   */
  private ReversingCompilationTestHelper duplicateClassMarkedInSecondFile() {
    return newHelper()
        .addSourceLines(
            "A.java",
            """
            package test;
            class Dup {}
            """)
        .addSourceLines(
            "B.java",
            """
            package test;
            // BUG: Diagnostic contains: duplicate class
            class Dup {}
            """);
  }

  /**
   * Built from a {@link ScannerSupplier}, so a marker matches a javac diagnostic that does not name
   * a check.
   */
  private ReversingCompilationTestHelper newHelper() {
    return ReversingCompilationTestHelper.newInstance(
            ScannerSupplier.fromBugCheckerClasses(NullAway.class), getClass())
        .setArgs("-XepOpt:NullAway:AnnotatedPackages=test");
  }
}
