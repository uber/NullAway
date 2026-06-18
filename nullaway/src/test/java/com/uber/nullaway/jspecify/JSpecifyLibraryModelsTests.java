package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

public class JSpecifyLibraryModelsTests extends NullAwayTestsBase {

  @Test
  public void atomicReferenceGet() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.concurrent.atomic.AtomicReference;
            @NullMarked
            class Test {
              void testNegative() {
                AtomicReference<Integer> x = new AtomicReference<>(Integer.valueOf(3));
                x.get().hashCode();
              }
              void testPositive() {
                AtomicReference<@Nullable Integer> x = new AtomicReference<>(Integer.valueOf(3));
                // BUG: Diagnostic contains: dereferenced expression 'x.get()' is @Nullable
                x.get().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOfNullableOr() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              static Optional<String> getKey(@Nullable String key) {
                return Optional.ofNullable(key).or(() -> Optional.ofNullable(System.getenv("DEFAULT_KEY")));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOfNullableFilter() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.*;

            @NullMarked
            public record Repro(@Nullable String name) {
              public Optional<String> getNameOpt() {
                return Optional.ofNullable(name).filter(it -> true);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalExplicitNullableAnnotations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import java.util.function.Function;
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              Optional<String> ofNullableAcceptsNullable(@Nullable String value) {
                return Optional.ofNullable(value);
              }

              Optional<String> mapCanReturnNullable(Optional<String> value) {
                Function<String, @Nullable String> mapper = unused -> null;
                return value.map(mapper);
              }

              void equalsAcceptsNullable(Optional<String> value) {
                value.equals(null);
              }

              void ofRejectsNull() {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Optional.of(null);
              }

              void orElseReturnsNullable(Optional<String> value) {
                // BUG: Diagnostic contains: dereferenced expression value.orElse(null) is @Nullable
                value.orElse(null).hashCode();
              }

              void orElseGetReturnsNullable(Optional<String> value) {
                // BUG: Diagnostic contains: dereferenced expression value.orElseGet(() -> null) is @Nullable
                value.orElseGet(() -> null).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Ignore(
      "Lambda return checking does not yet use nested nullable type-use annotations from library models")
  @Test
  public void optionalMapAllowsNullableLambdaReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              Optional<String> mapCanReturnNullable(Optional<String> value) {
                return value.map(unused -> null);
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
