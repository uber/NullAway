package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A {@code path:} line names the position of a difference in words: one step per type argument,
 * wildcard bound, array element, or enclosing type, each type argument named by the type parameter
 * its declaration declares.
 */
public class PathTests extends MismatchMessageTestBase {

  @Test
  public void pathNamesTheTypeParameterTheDeclarationDeclared() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Result<String, @Nullable String>
                                     ^^^^^^^^^^^^^^^^
            required: Result<String, String>
                                     ^^^^^^
            path: Result type argument Failure
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
        incompatible nullability: found @Nullable String, required String
            found:    List<Collection<List<@Nullable String>>>
                                           ^^^^^^^^^^^^^^^^
            required: List<Collection<List<String>>>
                                           ^^^^^^
            path: List type argument E -> Collection type argument E -> List type argument E
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
        incompatible nullability: found @Nullable Integer, required Integer
            found:    Outer<String>.Inner<@Nullable Integer>
                                          ^^^^^^^^^^^^^^^^^
            required: Outer<String>.Inner<Integer>
                                          ^^^^^^^
            path: Inner type argument U
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
        incompatible nullability: 2 mismatches between source and target types
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
        incompatible nullability: 2 mismatches between source and target types
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
            2. path: Map type argument V -> List type argument E -> wildcard upper bound
                     -> Collection type argument E
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
}
