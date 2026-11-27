package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * Where the source is compared as a supertype of another class, the {@code path:} line names the
 * position in that supertype. A caret goes under the node of the type the reader wrote where the
 * substitution carried that node into the view. Where the node at fault is not in the written type,
 * the message prints the view on a {@code found as:} line and draws the source carets under it.
 */
public class ViewedSourceTests extends MismatchMessageTestBase {

  @Test
  public void aCarriedOverNodeInsideAWildcardBoundIsMarkedOnTheWrittenSource() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Sub<? extends List<@Nullable String>>
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
  public void aCarriedOverNodeInsideAnArrayElementIsMarkedOnTheWrittenSource() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Sub<@Nullable String []>
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
  public void aCarriedOverCaptureIsMarkedOnTheWrittenSourceAndKeepsItsProvenance() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Sub<capture of ?>
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
  public void aNodeFromTheSubclassDeclarationIsMarkedOnTheViewedSource() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Sub
            found as: Base<List<@Nullable String>>
                                ^^^^^^^^^^^^^^^^
            required: Base<List<String>>
                                ^^^^^^
            path: Base type argument T -> List type argument E
        """,
        """
        static class Base<T extends @Nullable Object> {}
        static class Sub extends Base<List<@Nullable String>> {}
        static void take(Base<List<String>> b) {}
        void test(Sub s) {
          // BUG: Diagnostic matches: MESSAGE
          take(s);
        }
        """);
  }

  @Test
  public void aWildcardFromTheSubclassDeclarationGetsANoteThatStatesItsBoundOnly() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Sub
            found as: Base<List<?>>
                                ^
            required: Base<? extends List<? extends Object>>
                                                    ^^^^^^
            path: Base type argument B -> wildcard upper bound -> List type argument E -> wildcard upper bound
            note: the source wildcard has an implicit @Nullable Object upper bound
        """,
        """
        static class Base<B> {}
        static class Sub extends Base<List<?>> {}
        static void take(Base<? extends List<? extends Object>> b) {}
        void test(Sub s) {
          // BUG: Diagnostic matches: MESSAGE
          take(s);
        }
        """);
  }

  @Test
  public void aPathAcrossAViewReachesIntoWhateverTheSubclassPassed() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Indirect<?>
                               ^
            required: Base<? extends List<? extends Object>>
                                                    ^^^^^^
            path: Base type argument B -> wildcard upper bound -> List type argument E -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter X of Indirect
            did you mean Indirect<? extends Object>?
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
  public void aSubclassOfTheSameSimpleNameIsMarkedOnTheWrittenSource() {
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
