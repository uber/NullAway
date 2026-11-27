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
        incompatible nullability in List type argument E -> wildcard upper bound -> List type argument E: source has @Nullable String where target requires String
            source: List<? extends List<@Nullable String>>
                                        ^^^^^^^^^^^^^^^^
            target: List<? extends List<String>>
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
        incompatible nullability in Map type argument V -> wildcard upper bound: source has @Nullable Object where target requires Object
            source: Map<? extends String, ? extends @Nullable Object>
                                                    ^^^^^^^^^^^^^^^^
            target: Map<? extends Object, ? extends Object>
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
        incompatible nullability in Map type argument V: source has @Nullable String where target requires String
            source: Map<? extends String, @Nullable String>
                                          ^^^^^^^^^^^^^^^^
            target: Map<? extends Object, String>
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
        incompatible nullability in Three type argument C: source has @Nullable String where target requires String
            source: Three<? extends String, String, @Nullable String>
                                                    ^^^^^^^^^^^^^^^^
            target: Three<? extends Object, String, String>
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
        incompatible nullability in Three type argument C: source has @Nullable String where target requires String
            source: Three<?, String, @Nullable String>
                                     ^^^^^^^^^^^^^^^^
            target: Three<? extends @Nullable Object, String, String>
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
        incompatible nullability in Map type argument K -> wildcard upper bound and 1 other position: source has @Nullable Object where target requires Object
            source: Map<capture of ?, capture of ?>
                        ^^^^^^^^^^^^  ^^^^^^^^^^^^
                        1             2
            target: Map<? extends Object, ? extends Object>
                                  ^^^^^^            ^^^^^^
                                  1                 2
         \s
            1. path: Map type argument K -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source wildcard is the type argument for type parameter E of List
         \s
            2. path: Map type argument V -> wildcard upper bound
               source: @Nullable Object
               target: Object
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
        incompatible nullability in array element -> List type argument E: source has @Nullable String where target requires String
            source: List<@Nullable String> []
                         ^^^^^^^^^^^^^^^^
            target: List<String> []
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
        incompatible nullability in List type argument E -> array element: source has @Nullable String where target requires String
            source: List<@Nullable String []>
                         ^^^^^^^^^^^^^^^^
            target: List<String []>
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
        incompatible nullability in List type argument E: source has String @Nullable [] where target requires String []
            source: List<String @Nullable []>
                         ^^^^^^^^^^^^^^^^^^^
            target: List<String []>
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
        incompatible nullability in Foo type argument T -> wildcard upper bound -> Map type argument K and 1 other position: source has @Nullable String where target requires String
            source: Foo<?>
                        ^
                        1,2
            target: Foo<? extends Map<String, String>>
                                      ^^^^^^  ^^^^^^
                                      1       2
         \s
            1. path: Foo type argument T -> wildcard upper bound -> Map type argument K
               source: @Nullable String
               target: String
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
         \s
            2. path: Foo type argument T -> wildcard upper bound -> Map type argument V
               source: @Nullable String
               target: String
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
        incompatible nullability in List type argument E -> wildcard upper bound -> Foo type argument T -> wildcard upper bound -> Map type argument K and 1 other position: source has @Nullable String where target requires String
            source: List<Foo<?>>
                             ^
                             1,2
            target: List<? extends Foo<? extends Map<String, String>>>
                                                     ^^^^^^  ^^^^^^
                                                     1       2
         \s
            1. path: List type argument E -> wildcard upper bound -> Foo type argument T -> wildcard upper bound -> Map type argument K
               source: @Nullable String
               target: String
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
         \s
            2. path: List type argument E -> wildcard upper bound -> Foo type argument T -> wildcard upper bound -> Map type argument V
               source: @Nullable String
               target: String
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
