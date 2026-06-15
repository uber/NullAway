package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

public class ConditionalExprTests extends NullAwayTestsBase {

  @Test
  public void wildcard() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              interface ClassLike<T extends @Nullable Object> {
                String name();
              }
              static String guardedNullableWildcard(@Nullable ClassLike<?> maybeClass) {
                return (maybeClass != null ? maybeClass : fallback()).name();
              }
              static ClassLike<?> fallback() {
                throw new RuntimeException();
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelperWithInferenceFailureWarning() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true")));
  }
}
