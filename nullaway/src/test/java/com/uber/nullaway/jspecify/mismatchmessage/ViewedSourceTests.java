package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * Where the check views the source as a supertype of its own class, the one the target names, the
 * {@code path:} line names the position in that supertype. A caret goes under the node of the type
 * the reader wrote where the substitution carried that node into the view. Where the source itself
 * is viewed as a supertype and the node at fault is not in the written type, the message prints the
 * view on a {@code source viewed as:} line and draws the source carets under it.
 */
public class ViewedSourceTests extends MismatchMessageTestBase {

  @Test
  public void aCarriedOverNodeInsideAWildcardBoundIsMarkedOnTheWrittenSource() {
    assertMessage(
        """
        incompatible nullability in Base type argument T -> wildcard upper bound -> List type argument E: source has @Nullable String where target requires String
            source: Sub<? extends List<@Nullable String>>
                                       ^^^^^^^^^^^^^^^^
            target: Base<? extends List<String>>
                                        ^^^^^^
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
        incompatible nullability in Base type argument T -> array element: source has @Nullable String where target requires String
            source: Sub<@Nullable String []>
                        ^^^^^^^^^^^^^^^^
            target: Base<String []>
                         ^^^^^^
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
        incompatible nullability in Base type argument T -> wildcard upper bound: source has @Nullable Object where target requires Object
            source: Sub<capture of ?>
                        ^^^^^^^^^^^^
            target: Base<? extends Object>
                                   ^^^^^^
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
        incompatible nullability in Base type argument T -> List type argument E: source has @Nullable String where target requires String
            source:           Sub
            source viewed as: Base<List<@Nullable String>>
                                        ^^^^^^^^^^^^^^^^
            target:           Base<List<String>>
                                        ^^^^^^
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
        incompatible nullability in Base type argument B -> wildcard upper bound -> List type argument E -> wildcard upper bound: source has @Nullable Object where target requires Object
            source:           Sub
            source viewed as: Base<List<?>>
                                        ^
            target:           Base<? extends List<? extends Object>>
                                                            ^^^^^^
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
        incompatible nullability in Base type argument B -> wildcard upper bound -> List type argument E -> wildcard upper bound: source has @Nullable Object where target requires Object
            source: Indirect<?>
                             ^
            target: Base<? extends List<? extends Object>>
                                                  ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter X of Indirect
            fix: declare i as Indirect<? extends Object>
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
                incompatible nullability in Result type argument T: source has @Nullable String where target requires String
                    source: Result<@Nullable String>
                                   ^^^^^^^^^^^^^^^^
                    target: Result<String>
                                   ^^^^^^
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

  @Test
  public void aReorderingSubclassIsMarkedAtTheArgumentItPassesOn() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard upper bound -> Pair type argument A: source has @Nullable String where target requires String
            source: List<Swap<String, @Nullable String>>
                                      ^^^^^^^^^^^^^^^^
            target: List<? extends Pair<String, String>>
                                        ^^^^^^
        """,
        """
        static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
        static class Swap<X extends @Nullable Object, Y extends @Nullable Object> extends Pair<Y, X> {}
        static void take(List<? extends Pair<String, String>> l) {}
        void test(List<Swap<String, @Nullable String>> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void aReorderingSubclassInsideAnArrayIsMarkedAtTheArgumentItPassesOn() {
    assertMessage(
        """
        incompatible nullability in array element -> Pair type argument A: source has @Nullable String where target requires String
            source: Swap<String, @Nullable String> []
                                 ^^^^^^^^^^^^^^^^
            target: Pair<String, String> []
                         ^^^^^^
        """,
        """
        static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
        static class Swap<X extends @Nullable Object, Y extends @Nullable Object> extends Pair<Y, X> {}
        static void take(Pair<String, String>[] a) {}
        void test(Swap<String, @Nullable String>[] a) {
          // BUG: Diagnostic matches: MESSAGE
          take(a);
        }
        """);
  }

  @Test
  public void aReorderingSubclassTwoLevelsDownIsMarkedAtTheArgumentItPassesOn() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard upper bound -> List type argument E -> wildcard upper bound -> Pair type argument A: source has @Nullable String where target requires String
            source: List<List<Swap<String, @Nullable String>>>
                                           ^^^^^^^^^^^^^^^^
            target: List<? extends List<? extends Pair<String, String>>>
                                                       ^^^^^^
        """,
        """
        static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
        static class Swap<X extends @Nullable Object, Y extends @Nullable Object> extends Pair<Y, X> {}
        static void take(List<? extends List<? extends Pair<String, String>>> l) {}
        void test(List<List<Swap<String, @Nullable String>>> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void anEnclosingTypeArgumentOfASubclassIsMarkedOnTheWrittenSource() {
    assertMessage(
        """
        incompatible nullability in enclosing type -> Outer type argument T: source has @Nullable String where target requires String
            source: Outer<@Nullable String>.SubInner
                          ^^^^^^^^^^^^^^^^
            target: Outer<String>.Inner
                          ^^^^^^
        """,
        """
        static class Outer<T extends @Nullable Object> {
          class Inner {}
          class SubInner extends Inner {}
        }
        static void take(Outer<String>.Inner i) {}
        void test(Outer<@Nullable String>.SubInner s) {
          // BUG: Diagnostic matches: MESSAGE
          take(s);
        }
        """);
  }
}
