package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class GenericDiamondTests extends NullAwayTestsBase {

  @Test
  public void issue1451() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {
                static Foo<@Nullable Void> make() {
                  throw new RuntimeException();
                }
              }
              static class Bar<T extends @Nullable Object> {
                Bar(Foo<T> foo) {
                }
              }
              void test() {
                Bar<@Nullable Void> b = new Bar<>(Foo.make());
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
