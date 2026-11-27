package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A caret marks, in both printed types, the smallest node that differs in nullness, and no node the
 * target accepts as it is. Differences below one printed node, such as a {@code ?} whose implicit
 * bound holds two of them, share its caret, which is numbered for each.
 */
public class CaretTests extends MismatchMessageTestBase {

  @Test
  public void onlyTheInnermostDifferingNodeIsMarkedInsideAWildcardBound() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard upper bound -> List type argument E: found @Nullable String, required String
            found:    List<? extends List<@Nullable String>>
                                          ^^^^^^^^^^^^^^^^
            required: List<? extends List<String>>
                                          ^^^^^^
        """,
        """
        static void take(List<? extends List<String>> l) {}
        void test(List<? extends List<@Nullable String>> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void caretSkipsATypeArgumentTheTargetAccepts() {
    assertMessage(
        """
        incompatible nullability in Map type argument V -> wildcard upper bound: found @Nullable Object, required Object
            found:    Map<? extends String, ? extends @Nullable Object>
                                                      ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, ? extends Object>
                                                      ^^^^^^
        """,
        """
        static void take(Map<? extends Object, ? extends Object> m) {}
        void accepted(Map<? extends String, ? extends Object> m) {
          take(m);
        }
        void test(Map<? extends String, ? extends @Nullable Object> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void caretNamesTheConcreteArgumentWhenEveryWildcardIsAccepted() {
    assertMessage(
        """
        incompatible nullability in Map type argument V: found @Nullable String, required String
            found:    Map<? extends String, @Nullable String>
                                            ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, String>
                                            ^^^^^^
        """,
        """
        static void take(Map<? extends Object, String> m) {}
        void accepted(Map<? extends String, String> m) {
          take(m);
        }
        void test(Map<? extends String, @Nullable String> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void caretMarksTheFailingArgumentWhereTheTargetRepeatsAType() {
    assertMessage(
        """
        incompatible nullability in Three type argument C: found @Nullable String, required String
            found:    Three<? extends String, String, @Nullable String>
                                                      ^^^^^^^^^^^^^^^^
            required: Three<? extends Object, String, String>
                                                      ^^^^^^
        """,
        """
        static class Three<A, B, C extends @Nullable Object> {}
        static void take(Three<? extends Object, String, String> t) {}
        void test(Three<? extends String, String, @Nullable String> t) {
          // BUG: Diagnostic matches: MESSAGE
          take(t);
        }
        """);
  }

  @Test
  public void onlyTheFailingArgumentIsNamedBesideAWildcardTheTargetAccepts() {
    assertMessage(
        """
        incompatible nullability in Three type argument C: found @Nullable String, required String
            found:    Three<?, String, @Nullable String>
                                       ^^^^^^^^^^^^^^^^
            required: Three<? extends @Nullable Object, String, String>
                                                                ^^^^^^
        """,
        """
        static class Three<A, B, C extends @Nullable Object> {}
        static void take(Three<? extends @Nullable Object, String, String> t) {}
        void test(Three<?, String, @Nullable String> t) {
          // BUG: Diagnostic matches: MESSAGE
          take(t);
        }
        """);
  }

  @Test
  public void bothPositionsAreMarkedWhereOneInstanceFillsTwoTypeArguments() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound and 1 other position: found @Nullable Object, required Object
            found:    Map<capture of ?, capture of ?>
                          ^^^^^^^^^^^^  ^^^^^^^^^^^^
                          1             2
            required: Map<? extends Object, ? extends Object>
                                    ^^^^^^            ^^^^^^
                                    1                 2
         \s
            1. path: Map type argument K -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source wildcard is the type argument for type parameter E of List
         \s
            2. path: Map type argument V -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source wildcard is the type argument for type parameter E of List
        """,
        """
        static <T extends @Nullable Object> Map<T, T> duplicate(List<T> x) {
          throw new RuntimeException();
        }
        static void take(Map<? extends Object, ? extends Object> m) {}
        void test(List<?> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(duplicate(l));
        }
        """);
  }

  @Test
  public void aMismatchInsideAnArrayElementTypeArgumentIsMarked() {
    assertMessage(
        """
        incompatible nullability in array element -> List type argument E: found @Nullable String, required String
            found:    List<@Nullable String> []
                           ^^^^^^^^^^^^^^^^
            required: List<String> []
                           ^^^^^^
        """,
        """
        static void take(List<String>[] l) {}
        void test(List<@Nullable String>[] l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void aNullableArrayComponentTypeIsMarkedInsideATypeArgument() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> array element: found @Nullable String, required String
            found:    List<@Nullable String []>
                           ^^^^^^^^^^^^^^^^
            required: List<String []>
                           ^^^^^^
        """,
        """
        static void take(List<String[]> l) {}
        void test(List<@Nullable String[]> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void aNullableArrayTypeIsMarkedInsideATypeArgument() {
    assertMessage(
        """
        incompatible nullability in List type argument E: found String @Nullable [], required String []
            found:    List<String @Nullable []>
                           ^^^^^^^^^^^^^^^^^^^
            required: List<String []>
                           ^^^^^^^^^
        """,
        """
        static void take(List<String[]> l) {}
        void test(List<String @Nullable []> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void twoMismatchesUnderOneWildcardShareItsCaret() {
    assertMessage(
        """
        incompatible nullability in Foo type argument T -> wildcard upper bound -> Map type argument K and 1 other position: found @Nullable String, required String
            found:    Foo<?>
                          ^
                          1,2
            required: Foo<? extends Map<String, String>>
                                        ^^^^^^  ^^^^^^
                                        1       2
         \s
            1. path: Foo type argument T -> wildcard upper bound -> Map type argument K
               found:    @Nullable String
               required: String
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
         \s
            2. path: Foo type argument T -> wildcard upper bound -> Map type argument V
               found:    @Nullable String
               required: String
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
        """,
        """
        static class Foo<T extends Map<@Nullable String, @Nullable String>> {}
        static void take(Foo<? extends Map<String, String>> f) {}
        void test(Foo<?> f) {
          // BUG: Diagnostic matches: MESSAGE
          take(f);
        }
        """);
  }

  @Test
  public void twoMismatchesUnderOneNestedWildcardShareItsCaret() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard upper bound -> Foo type argument T -> wildcard upper bound -> Map type argument K and 1 other position: found @Nullable String, required String
            found:    List<Foo<?>>
                               ^
                               1,2
            required: List<? extends Foo<? extends Map<String, String>>>
                                                       ^^^^^^  ^^^^^^
                                                       1       2
         \s
            1. path: List type argument E -> wildcard upper bound -> Foo type argument T -> wildcard upper bound -> Map type argument K
               found:    @Nullable String
               required: String
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
         \s
            2. path: List type argument E -> wildcard upper bound -> Foo type argument T -> wildcard upper bound -> Map type argument V
               found:    @Nullable String
               required: String
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
        """,
        """
        static class Foo<T extends Map<@Nullable String, @Nullable String>> {}
        static void take(List<? extends Foo<? extends Map<String, String>>> f) {}
        void test(List<Foo<?>> f) {
          // BUG: Diagnostic matches: MESSAGE
          take(f);
        }
        """);
  }
}
