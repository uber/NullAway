package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * Where the search for differing positions finds none, as for a difference in a lower bound, the
 * message falls back to the {@code incompatible types:} form, with no carets and no {@code path:}
 * line. A raw source has no type arguments to compare and draws no message at all.
 */
public class FallbackMessageTests extends MismatchMessageTestBase {

  @Test
  public void aLowerBoundDifferenceFallsBackToThePlainIncompatibleTypesMessage() {
    assertMessage(
        """
        incompatible types: List<? super String> cannot be converted to List<? super @Nullable String>
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
  public void theFallbackMessageStatesTheSubtypeRelationBetweenTheTwoTypes() {
    assertMessage(
        """
        incompatible types: Sub<? super String> cannot be converted to Base<? super @Nullable String> (Sub<? super String> is a subtype of Base<? super String>)
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
  public void aRawSourceTypeIsAcceptedWithNoMismatchToReport() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            nullMarkedClass(
                """
                @SuppressWarnings("rawtypes")
                void test(List raw) {
                  List<String> typed = raw;
                  typed.hashCode();
                }
                """))
        .doTest();
  }
}
