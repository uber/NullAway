package com.uber.nullaway;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Fails when the test harness stops running {@link JSpecifyUnrecognizedAnnotationLocation} over the
 * sources a test compiles.
 *
 * <p>{@link NullAwayTestsBase#makeTestHelperWithArgs} runs that check alongside NullAway and raises
 * it to {@code WARN}, since it reports nothing at its default severity. A test source therefore
 * cannot quietly annotate a location JSpecify gives no meaning to. Nothing else asserts that
 * wiring, and a test whose sources lost the check would still pass.
 *
 * <p>If this fails, the helper no longer supplies both checks, no longer raises the check, or
 * {@code @SuppressWarnings} no longer silences it. A test source that annotates such a location on
 * purpose carries that suppression.
 */
@RunWith(JUnit4.class)
public class UnrecognizedAnnotationLocationInTestSourcesTest extends NullAwayTestsBase {

  @Test
  public void checkRunsOverTestSources() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              @Nullable int count = 0;
            }
            """)
        .doTest();
  }

  @Test
  public void suppressingSilencesTheCheck() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            @SuppressWarnings("JSpecifyUnrecognizedAnnotationLocation")
            class Test {
              @Nullable int count = 0;
            }
            """)
        .doTest();
  }
}
