package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Collections;
import org.junit.Test;

/**
 * Source-compilation regressions for shared wildcard-bound graphs, including leaf and back-edge
 * cycles. The generous timeouts guard catastrophic regressions, not throughput.
 */
public class SharedIntersectionTimeoutTests extends NullAwayTestsBase {

  @Test(timeout = 30_000)
  public void sharedAcyclicBoundsCompileWithinTimeout() {
    compileSharedBounds(false);
  }

  @Test(timeout = 30_000)
  public void sharedBoundsWithCyclicLeafCompileWithinTimeout() {
    compileSharedBounds(true);
  }

  @Test(timeout = 30_000)
  public void assignmentWithBackEdgeInIntersectionBounds() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Collections.singletonList("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            interface A0<T extends A5<?> & B5<?>> {}
            interface B0<T extends B5<?>> {}
            interface A1<T extends A0<?> & B0<?>> {}
            interface B1<T extends A0<?> & B0<?>> {}
            interface A2<T extends A1<?> & B1<?>> {}
            interface B2<T extends A1<?> & B1<?>> {}
            interface A3<T extends A2<?> & B2<?>> {}
            interface B3<T extends A2<?> & B2<?>> {}
            interface A4<T extends A3<?> & B3<?>> {}
            interface B4<T extends A3<?> & B3<?>> {}
            interface A5<T extends A4<?> & B4<?>> {}
            interface B5<T extends A4<?> & B4<?>> {}
            @org.jspecify.annotations.NullMarked
            abstract class Test {
              abstract A5<?> get();
              void trigger(Test receiver) {
                A5<?> value = receiver.get();
              }
            }
            """)
        .doTest();
  }

  @Test(timeout = 30_000)
  public void assignmentWithDeepBackEdgeInIntersectionBounds() {
    StringBuilder source =
        new StringBuilder(
            """
            interface A0<T extends A10<?> & B10<?>> {}
            interface B0<T extends B10<?>> {}
            """);
    for (int level = 1; level <= 10; level++) {
      for (String name : new String[] {"A", "B"}) {
        source
            .append("interface ")
            .append(name)
            .append(level)
            .append("<T extends A")
            .append(level - 1)
            .append("<?> & B")
            .append(level - 1)
            .append("<?>> {}\n");
      }
    }
    source.append(
        """
        @org.jspecify.annotations.NullMarked
        abstract class Test {
          abstract A10<?> get();
          void trigger(Test receiver) {
            A10<?> value = receiver.get();
          }
        }
        """);
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Collections.singletonList("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines("Test.java", source.toString())
        .doTest();
  }

  /**
   * Compiles an ordinary member call over thirty shared bound levels, optionally with a leaf cycle.
   */
  private void compileSharedBounds(boolean cyclicLeaf) {

    StringBuilder source =
        new StringBuilder(
            cyclicLeaf ? "interface A0<T extends A0<?>> {}\n" : "interface A0<T> {}\n");
    source.append("interface B0<T> {}\n");
    for (int level = 1; level <= 30; level++) {
      source
          .append("interface A")
          .append(level)
          .append("<T extends A")
          .append(level - 1)
          .append("<?> & B")
          .append(level - 1)
          .append("<?>> {}\n");
      source
          .append("interface B")
          .append(level)
          .append("<T extends A")
          .append(level - 1)
          .append("<?> & B")
          .append(level - 1)
          .append("<?>> {}\n");
    }
    source
        .append("@org.jspecify.annotations.NullMarked abstract class Test {\n")
        .append("  abstract A30<?> get();\n")
        .append("  void trigger(Test receiver) { receiver.get(); }\n")
        .append("}\n");
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Collections.singletonList("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines("Test.java", source.toString())
        .doTest();
  }
}
