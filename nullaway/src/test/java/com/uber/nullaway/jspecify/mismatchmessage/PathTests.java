package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A {@code path:} line, and the first line of the message, name the position of a difference in
 * words: one step per type argument, wildcard bound, array element, or enclosing type, each type
 * argument named by the type parameter its declaration declares. The position is the one the check
 * rejects, so a nullable root or array is compared below its own nullness, and a captured source
 * through its upper bound.
 */
public class PathTests extends MismatchMessageTestBase {

  @Test
  public void pathNamesTheTypeParameterTheDeclarationDeclared() {
    assertMessage(
        """
        incompatible nullability in Result type argument Failure: found @Nullable String, required String
            found:    Result<String, @Nullable String>
                                     ^^^^^^^^^^^^^^^^
            required: Result<String, String>
                                     ^^^^^^
        """,
        """
        static class Result<Success extends @Nullable Object, Failure extends @Nullable Object> {}
        static void take(Result<String, String> r) {}
        void test(Result<String, @Nullable String> r) {
          // BUG: Diagnostic matches: MESSAGE
          take(r);
        }
        """);
  }

  @Test
  public void pathRepeatsTheTypeParameterNameAtEveryLevelThatSharesIt() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> Collection type argument E -> List type argument E: found @Nullable String, required String
            found:    List<Collection<List<@Nullable String>>>
                                           ^^^^^^^^^^^^^^^^
            required: List<Collection<List<String>>>
                                           ^^^^^^
        """,
        """
        static void take(List<Collection<List<String>>> l) {}
        void test(List<Collection<List<@Nullable String>>> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void pathKeepsTheEnclosingTypeApartFromTheInnerType() {
    assertMessage(
        """
        incompatible nullability in Inner type argument U: found @Nullable Integer, required Integer
            found:    Outer<String>.Inner<@Nullable Integer>
                                          ^^^^^^^^^^^^^^^^^
            required: Outer<String>.Inner<Integer>
                                          ^^^^^^^
        """,
        """
        static class Outer<T extends @Nullable Object> {
          class Inner<U extends @Nullable Object> {}
        }
        static void take(Outer<String>.Inner<Integer> i) {}
        void test(Outer<String>.Inner<@Nullable Integer> i) {
          // BUG: Diagnostic matches: MESSAGE
          take(i);
        }
        """);
  }

  @Test
  public void twoMismatchesInsideOneTypeArgumentSharePathPrefix() {
    assertMessage(
        """
        incompatible nullability in Map type argument V -> Map type argument K and 1 other position: found @Nullable String, required String
            found:    Map<String, Map<@Nullable String, @Nullable Integer>>
                                      ^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^
                                      1                 2
            required: Map<String, Map<String, Integer>>
                                      ^^^^^^  ^^^^^^^
                                      1       2
         \s
            1. path: Map type argument V -> Map type argument K
               found:    @Nullable String
               required: String
         \s
            2. path: Map type argument V -> Map type argument V
               found:    @Nullable Integer
               required: Integer
        """,
        """
        static void take(Map<String, Map<String, Integer>> m) {}
        void test(Map<String, Map<@Nullable String, @Nullable Integer>> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void mismatchesAtDifferentDepthsEachGetTheirOwnPath() {
    assertMessage(
        """
        incompatible nullability in Map type argument K and 1 other position: found @Nullable String, required String
            found:    Map<@Nullable String, List<? extends Collection<@Nullable Integer>>>
                          ^^^^^^^^^^^^^^^^                            ^^^^^^^^^^^^^^^^^
                          1                                           2
            required: Map<String, List<? extends Collection<Integer>>>
                          ^^^^^^                            ^^^^^^^
                          1                                 2
         \s
            1. path: Map type argument K
               found:    @Nullable String
               required: String
         \s
            2. path: Map type argument V -> List type argument E -> wildcard upper bound -> Collection type argument E
               found:    @Nullable Integer
               required: Integer
        """,
        """
        static void take(Map<String, List<? extends Collection<Integer>>> m) {}
        void test(
            Map<@Nullable String, List<? extends Collection<@Nullable Integer>>> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void pathReachesATypeArgumentOfAnArrayElement() {
    assertMessage(
        """
        incompatible nullability in array element -> List type argument E: a type argument must match exactly; found String, required @Nullable String
            found:    List<String> []
                           ^^^^^^
            required: @Nullable List<@Nullable String> []
                                     ^^^^^^^^^^^^^^^^
        """,
        """
        void test(List<String>[] arr) {
          // BUG: Diagnostic matches: MESSAGE
          @Nullable List<@Nullable String>[] x = arr;
        }
        """);
  }

  @Test
  public void pathDescendsIntoEveryDimensionOfANestedArray() {
    assertMessage(
        """
        incompatible nullability in array element -> array element: found @Nullable Integer, required Integer
            found:    @Nullable Integer [] []
                      ^^^^^^^^^^^^^^^^^
            required: Integer @Nullable [] []
                      ^^^^^^^
        """,
        """
        void test(@Nullable Integer[][] src) {
          // BUG: Diagnostic matches: MESSAGE
          Integer[] @Nullable [] t = src;
        }
        """);
  }

  @Test
  public void pathDescendsIntoEveryDimensionOfAThreeDimensionalArray() {
    assertMessage(
        """
        incompatible nullability in array element -> array element -> array element: found @Nullable Integer, required Integer
            found:    @Nullable Integer [] [] []
                      ^^^^^^^^^^^^^^^^^
            required: Integer @Nullable [] [] []
                      ^^^^^^^
        """,
        """
        void test(@Nullable Integer[][][] src3) {
          // BUG: Diagnostic matches: MESSAGE
          Integer[][] @Nullable [] t = src3;
        }
        """);
  }

  @Test
  public void aNullableSourceIsComparedBelowItsOwnNullness() {
    assertMessage(
        """
        incompatible nullability in Collection type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    @Nullable List<?>
                                     ^
            required: Collection<? extends Object>
                                           ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare l as @Nullable List<? extends Object>
        """,
        """
        static void take(Collection<? extends Object> c) {}
        void test(@Nullable List<?> l) {
          if (l != null) {
            // BUG: Diagnostic matches: MESSAGE
            take(l);
          }
        }
        """);
  }

  @Test
  public void aNullableTargetIsComparedBelowItsOwnNullness() {
    assertMessage(
        """
        incompatible nullability in List type argument E: found @Nullable String, required String
            found:    List<@Nullable String>
                           ^^^^^^^^^^^^^^^^
            required: @Nullable List<String>
                                     ^^^^^^
        """,
        """
        static void param(@Nullable List<String> l) {}
        void test(List<@Nullable String> x) {
          // BUG: Diagnostic matches: MESSAGE
          param(x);
        }
        """);
  }

  @Test
  public void pathNamesOnlyTheTypeArgumentTheCheckRejects() {
    assertMessage(
        """
        incompatible nullability in Pair type argument A: found @Nullable String, required String
            found:    Pair<@Nullable String, Integer []>
                           ^^^^^^^^^^^^^^^^
            required: Pair<String, ? extends @Nullable Integer []>
                           ^^^^^^
        """,
        """
        static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
        static void take(Pair<String, ? extends @Nullable Integer[]> p) {}
        void test(Pair<@Nullable String, Integer[]> p) {
          // BUG: Diagnostic matches: MESSAGE
          take(p);
        }
        """);
  }

  @Test
  public void aCapturedSourceIsComparedThroughItsUpperBound() {
    assertMessage(
        """
        incompatible nullability in List type argument E: found @Nullable String, required String
            found:    capture of ? extends List<@Nullable String>
                                                ^^^^^^^^^^^^^^^^
            required: List<String>
                           ^^^^^^
            fix: declare l as List<@Nullable String>
        """,
        """
        void test(List<? extends List<@Nullable String>> wl) {
          // BUG: Diagnostic matches: MESSAGE
          List<String> l = wl.get(0);
        }
        """);
  }

  @Test
  public void aCapturedArgumentIsComparedThroughItsUpperBound() {
    assertMessage(
        """
        incompatible nullability in List type argument E: found @Nullable String, required String
            found:    List<@Nullable String>
                           ^^^^^^^^^^^^^^^^
            required: List<String>
                           ^^^^^^
        """,
        """
        static void take(List<String> l) {}
        void test(List<? extends List<@Nullable String>> wl) {
          // BUG: Diagnostic matches: MESSAGE
          take(wl.get(0));
        }
        """);
  }

  @Test
  public void pathCrossesAWildcardLowerBound() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard lower bound -> List type argument E -> wildcard upper bound: found Object, required a supertype of @Nullable Object
            found:    List<? super List<? extends Object>>
                                                  ^^^^^^
            required: List<? super List<?>>
                                        ^
        """,
        """
        static void take(List<? super List<?>> l) {}
        void test(List<? super List<? extends Object>> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }
}
