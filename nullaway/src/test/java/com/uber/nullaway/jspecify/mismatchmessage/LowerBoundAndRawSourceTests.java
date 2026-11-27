package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A difference in a {@code ? super} bound is located like any other, and the target side reads as
 * {@code a supertype of} the bound the target states. A raw source has no type arguments to compare
 * and draws no message at all, and neither does an array of a raw target type.
 */
public class LowerBoundAndRawSourceTests extends MismatchMessageTestBase {

  @Test
  public void aLowerBoundDifferenceIsMarkedInBothTypes() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard lower bound: source has String where target requires a supertype of @Nullable String
            source: List<? super String>
                                 ^^^^^^
            target: List<? super @Nullable String>
                                 ^^^^^^^^^^^^^^^^
        """,
        """
        static void takeNonNullLower(List<? super String> l) {}
        static void takeNullableLower(List<? super @Nullable String> l) {}
        void nullableSourceLowerBound(List<? super @Nullable String> l) {
          takeNonNullLower(l);
        }
        void nonNullSourceLowerBound(List<? super String> l) {
          // BUG: Diagnostic matches: MESSAGE
          takeNullableLower(l);
        }
        """);
  }

  @Test
  public void aLowerBoundDifferenceIsMarkedAcrossASupertypeView() {
    assertMessage(
        """
        incompatible nullability in Base type argument T -> wildcard lower bound: source has String where target requires a supertype of @Nullable String
            source: Sub<? super String>
                                ^^^^^^
            target: Base<? super @Nullable String>
                                 ^^^^^^^^^^^^^^^^
        """,
        """
        static class Base<T extends @Nullable Object> {}
        static class Sub<T extends @Nullable Object> extends Base<T> {}
        static void take(Base<? super @Nullable String> b) {}
        void test(Sub<? super String> s) {
          // BUG: Diagnostic matches: MESSAGE
          take(s);
        }
        """);
  }

  @Test
  public void aRawTypeOnEitherSideIsAcceptedWithNoMismatchToReport() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            nullMarkedClass(
                """
                @SuppressWarnings("rawtypes")
                void rawSource(List raw) {
                  List<String> typed = raw;
                  typed.hashCode();
                }
                @SuppressWarnings("rawtypes")
                void rawArrayTarget(List<@Nullable String>[] typed) {
                  List[] raw = typed;
                  raw.hashCode();
                }
                """))
        .doTest();
  }
}
