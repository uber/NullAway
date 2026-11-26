package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Collections;
import org.junit.Test;

/** Source regressions for explicit non-null components of computed intersection bounds. */
public class IntersectionBoundNarrowingTests extends NullAwayTestsBase {

  @Test
  public void oneExplicitNonNullComponentPreventsNullableDeclarationFallback() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface A {}
              interface B {}
              interface Result<T extends @Nullable A & @Nullable B> {}
              interface FirstNonNull<T extends @NonNull A & B> {
                Result<T> result();
              }
              interface LastNonNull<T extends A & @NonNull B> {
                Result<T> result();
              }
              interface AllNullable<T extends @Nullable A & @Nullable B> {
                Result<T> result();
              }

              // Member substitution carries the source capture into Result without generic-call
              // inference annotating every component. Result's nullable declaration is fallback
              // information, not permission to widen an explicitly non-null computed bound.
              Result<? extends A> acceptsExplicitNonNullFirstComponent(FirstNonNull<?> source) {
                return source.result();
              }

              Result<? extends B> acceptsExplicitNonNullLastComponent(LastNonNull<?> source) {
                return source.result();
              }

              Result<? extends @Nullable A> acceptsAllNullableFirstComponent(AllNullable<?> source) {
                return source.result();
              }

              Result<? extends @Nullable B> acceptsAllNullableLastComponent(AllNullable<?> source) {
                return source.result();
              }

              Result<? extends A> rejectsAllNullableFirstComponent(AllNullable<?> source) {
                // BUG: Diagnostic contains: incompatible types
                return source.result();
              }

              Result<? extends B> rejectsAllNullableLastComponent(AllNullable<?> source) {
                // BUG: Diagnostic contains: incompatible types
                return source.result();
              }
            }
            """)
        .doTest();
  }

  /** Creates a NullAway compilation helper with wildcard-aware JSpecify checking. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Collections.singletonList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
