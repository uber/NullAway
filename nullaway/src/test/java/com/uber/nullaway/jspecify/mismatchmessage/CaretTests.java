package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A caret marks, in both printed types, the smallest node that differs in nullness, and no node the
 * target accepts as it is.
 */
public class CaretTests extends MismatchMessageTestBase {

  @Test
  public void onlyTheInnermostDifferingNodeIsMarkedInsideAWildcardBound() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    List<? extends List<@Nullable String>>
                                          ^^^^^^^^^^^^^^^^
            required: List<? extends List<String>>
                                          ^^^^^^
            path: List type argument E -> wildcard upper bound -> List type argument E
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
        incompatible nullability: found @Nullable Object, required Object
            found:    Map<? extends String, ? extends @Nullable Object>
                                                      ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, ? extends Object>
                                                      ^^^^^^
            path: Map type argument V -> wildcard upper bound
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
        incompatible nullability: found @Nullable String, required String
            found:    Map<? extends String, @Nullable String>
                                            ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, String>
                                            ^^^^^^
            path: Map type argument V
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
        incompatible nullability: found @Nullable String, required String
            found:    Three<? extends String, String, @Nullable String>
                                                      ^^^^^^^^^^^^^^^^
            required: Three<? extends Object, String, String>
                                                      ^^^^^^
            path: Three type argument C
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
        incompatible nullability: found @Nullable String, required String
            found:    Three<?, String, @Nullable String>
                                       ^^^^^^^^^^^^^^^^
            required: Three<? extends @Nullable Object, String, String>
                                                                ^^^^^^
            path: Three type argument C
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
        incompatible nullability: 2 mismatches between source and target types
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
        incompatible nullability: found @Nullable String, required String
            found:    List<@Nullable String> []
                           ^^^^^^^^^^^^^^^^
            required: List<String> []
                           ^^^^^^
            path: array element -> List type argument E
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
        incompatible nullability: found @Nullable String, required String
            found:    List<@Nullable String []>
                           ^^^^^^^^^^^^^^^^
            required: List<String []>
                           ^^^^^^
            path: List type argument E -> array element
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
        incompatible nullability: found String @Nullable [], required String []
            found:    List<String @Nullable []>
                           ^^^^^^^^^^^^^^^^^^^
            required: List<String []>
                           ^^^^^^^^^
            path: List type argument E
        """,
        """
        static void take(List<String[]> l) {}
        void test(List<String @Nullable []> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }
}
