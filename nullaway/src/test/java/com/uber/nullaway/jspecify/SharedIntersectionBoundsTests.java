package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Collections;
import org.junit.Test;

/** End-to-end source coverage for shared implicit wildcard bounds and recursive bounds. */
public class SharedIntersectionBoundsTests extends NullAwayTestsBase {

  @Test
  public void receiverWithTwentyLevelsOfSharedIntersectionBounds() {
    StringBuilder source =
        new StringBuilder(
            """
            import org.jspecify.annotations.NullMarked;
            @NullMarked interface A0<T> {}
            @NullMarked interface B0<T> {}
            """);
    for (int i = 1; i <= 20; i++) {
      source
          .append("@NullMarked interface A")
          .append(i)
          .append("<T extends A")
          .append(i - 1)
          .append("<?> & B")
          .append(i - 1)
          .append("<?>> { T get(); }\n");
      source
          .append("@NullMarked interface B")
          .append(i)
          .append("<T extends A")
          .append(i - 1)
          .append("<?> & B")
          .append(i - 1)
          .append("<?>> {}\n");
    }
    source.append(
        """
        @NullMarked class Test {
          A20<?> get() { throw new UnsupportedOperationException(); }
          void useReceiver() {
            var receiver = get();
            receiver.get();
          }
        }
        """);
    makeHelper().addSourceLines("Test.java", source.toString()).doTest();
  }

  @Test
  public void recursiveBoundsRetainNullableAndNonNullResults() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked class Test {
              interface Node<T extends Node<T>> { T next(); }
              interface NullableNode<T extends NullableNode<T>> { @Nullable T next(); }
              void unchangedRecursiveBound(Node<?> receiver) {
                receiver.next().next();
              }
              void nullableRecursiveResult(NullableNode<?> receiver) {
                // BUG: Diagnostic contains: dereferenced expression
                receiver.next().next();
              }
            }
            """)
        .doTest();
  }

  /** Enables the same wildcard-aware JSpecify checking as the intersection restoration tests. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Collections.singletonList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
