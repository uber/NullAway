package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Collections;
import org.junit.Test;

/**
 * Source-compilation regressions for shared wildcard-bound graphs, with and without a cyclic leaf.
 * The generous timeouts guard catastrophic regressions, not throughput.
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
