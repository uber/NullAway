package com.uber.nullaway;

import static com.google.common.truth.Truth.assertThat;
import static com.uber.nullaway.generics.JSpecifyJavacConfig.withJSpecifyModeArgs;

import com.google.errorprone.FileObjects;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.Test;

/**
 * While an unboxing might produce a warning, the result of it is non-null and should not produce
 * additional warnings.
 */
public class PostUnboxingTests extends NullAwayTestsBase {

  @Test
  public void noExtraWarningAfterUnboxing() {
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Unboxing.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Unboxing {
              int intField;
              int[] intArray = new int[1];
              void intParam(int i) {}
              void intVararg(int... i) {}

              void f1(@Nullable Integer n) { intField = n; }
              void f2(@Nullable Integer n) { intArray[0] = n; }
              void f3(@Nullable Integer n) { intParam(n); }
              void f4(@Nullable Integer n) { intVararg(n); }
            }
            """);
    assertThat(compileAndReportDiagnostics(List.of(source), withJSpecifyModeArgs(defaultArgs())))
        .containsExactly(
            "Unboxing.java:9: [NullAway] unboxing of a @Nullable expression 'n'",
            "Unboxing.java:10: [NullAway] unboxing of a @Nullable expression 'n'",
            "Unboxing.java:11: [NullAway] unboxing of a @Nullable expression 'n'",
            "Unboxing.java:12: [NullAway] unboxing of a @Nullable expression 'n'");
  }
}
