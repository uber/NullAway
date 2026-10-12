package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * Where the source is compared as a supertype of its own class, the message prints that view on a
 * {@code found as:} line, draws the source carets under it, and keeps the {@code path:} and {@code
 * note:} lines in the coordinates of the view.
 */
public class ViewedSourceTests extends MismatchMessageTestBase {

  @Test
  public void aMismatchInsideAWildcardBoundIsMarkedOnTheViewedSource() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Sub<? extends List<@Nullable String>>
            found as: Base<? extends List<@Nullable String>>
                                          ^^^^^^^^^^^^^^^^
            required: Base<? extends List<String>>
                                          ^^^^^^
            path: Base type argument T -> wildcard upper bound -> List type argument E
        """,
        """
        static class Base<T extends @Nullable Object> {}
        static class Sub<T extends @Nullable Object> extends Base<T> {}
        static void take(Base<? extends List<String>> b) {}
        void test(Sub<? extends List<@Nullable String>> s) {
          // BUG: Diagnostic matches: MESSAGE
          take(s);
        }
        """);
  }

  @Test
  public void aMismatchInsideAnArrayElementIsMarkedOnTheViewedSource() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Sub<@Nullable String []>
            found as: Base<@Nullable String []>
                           ^^^^^^^^^^^^^^^^
            required: Base<String []>
                           ^^^^^^
            path: Base type argument T -> array element
        """,
        """
        static class Base<T extends @Nullable Object> {}
        static class Sub<T extends @Nullable Object> extends Base<T> {}
        static void take(Base<String[]> b) {}
        void test(Sub<@Nullable String[]> s) {
          // BUG: Diagnostic matches: MESSAGE
          take(s);
        }
        """);
  }

  @Test
  public void aCapturedWildcardIsMarkedOnTheViewedSourceAndKeepsItsProvenance() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Sub<capture of ?>
            found as: Base<capture of ?>
                           ^^^^^^^^^^^^
            required: Base<? extends Object>
                                     ^^^^^^
            path: Base type argument T -> wildcard upper bound
            note: the source wildcard is the type argument for type parameter T of Sub
        """,
        """
        static class Base<T extends @Nullable Object> {}
        static class Sub<T extends @Nullable Object> extends Base<T> {}
        static void take(Base<? extends Object> b) {}
        static Sub<?> make() {
          throw new RuntimeException();
        }
        void test() {
          // BUG: Diagnostic matches: MESSAGE
          take(make());
        }
        """);
  }

  @Test
  public void aPathAcrossAViewReachesIntoWhateverTheSubclassPassed() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Indirect<?>
            found as: Base<List<?>>
                                ^
            required: Base<? extends List<? extends Object>>
                                                    ^^^^^^
            path: Base type argument B -> wildcard upper bound -> List type argument E -> wildcard upper bound
            note: the source wildcard has an implicit @Nullable Object upper bound
        """,
        """
        static class Base<B> {}
        static class Indirect<X extends @Nullable Object> extends Base<List<X>> {}
        static void take(Base<? extends List<? extends Object>> b) {}
        void test(Indirect<?> i) {
          // BUG: Diagnostic matches: MESSAGE
          take(i);
        }
        """);
  }

  // b.Result extends a.Result, so the call reaches NullAway and both sides print as
  // "Result".
  @Test
  public void simpleNamesAloneDoNotShowThatTheTwoTypesComeFromDifferentPackages() {
    makeHelper()
        .addSourceLines(
            "a/Result.java",
            """
            package a;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Result<T extends @Nullable Object> {}
            """)
        .addSourceLines(
            "b/Result.java",
            """
            package b;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Result<T extends @Nullable Object> extends a.Result<T> {}
            """)
        .expectErrorMessage(
            "MESSAGE",
            messageIs(
                """
                incompatible nullability: found @Nullable String, required String
                    found:    Result<@Nullable String>
                    found as: Result<@Nullable String>
                                     ^^^^^^^^^^^^^^^^
                    required: Result<String>
                                     ^^^^^^
                    path: Result type argument T
                """))
        .addSourceLines(
            "Test.java",
            nullMarkedClass(
                """
                static void take(a.Result<String> r) {}
                void test(b.Result<@Nullable String> r) {
                  // BUG: Diagnostic matches: MESSAGE
                  take(r);
                }
                """))
        .doTest();
  }
}
