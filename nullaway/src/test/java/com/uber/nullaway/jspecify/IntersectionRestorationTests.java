package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Collections;
import org.junit.Test;

/** Diagnostic tests for explicit wildcard bounds with additional intersection components. */
public class IntersectionRestorationTests extends NullAwayTestsBase {

  @Test
  public void explicitWildcardComponentWithAdditionalFormalBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface A<E extends @Nullable Object> { E a(); }
              interface B<E extends @Nullable Object> { E b(); }
              interface Holder<E extends @Nullable Object,
                  T extends A<@Nullable E> & B<E>> {
                T get();
              }
              interface Factory<E extends @Nullable Object> {
                Holder<E, ? extends A<@Nullable E>> fromA();
                Holder<E, ? extends B<E>> fromB();
              }

              void acceptsMatchingNullableComponent(Factory<String> factory) {
                A<@Nullable String> result = factory.fromA().get();
              }

              void rejectsNonNullMatchingComponent(Factory<String> factory) {
                // BUG: Diagnostic contains: incompatible types
                A<String> result = factory.fromA().get();
              }

              void acceptsOtherNonNullComponent(Factory<String> factory) {
                factory.fromA().get().b().length();
              }

              void rejectsWideningOtherInvariantComponent(Factory<String> factory) {
                // BUG: Diagnostic contains: incompatible types
                B<@Nullable String> result = factory.fromA().get();
              }

              void acceptsMatchingNonNullComponent(Factory<String> factory) {
                factory.fromB().get().b().length();
              }

              void rejectsWideningMatchingInvariantComponent(Factory<String> factory) {
                // BUG: Diagnostic contains: incompatible types
                B<@Nullable String> result = factory.fromB().get();
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
