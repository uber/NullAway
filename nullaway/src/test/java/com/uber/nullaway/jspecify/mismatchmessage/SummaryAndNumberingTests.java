package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * The first line of a generic nullability message names the position of the first difference, how
 * many other positions differ, and the two types at the first one, so that it stands alone where a
 * tool shows only that line. Where there are several, each gets a numbered entry, five at most, and
 * a line after them counts the rest. A type argument that the source states outright has to match
 * exactly, and the first line says so where the source is the less nullable side.
 */
public class SummaryAndNumberingTests extends MismatchMessageTestBase {

  @Test
  public void listsAtMostFiveMismatchesAndSaysHowManyMoreThereAre() {
    assertMessage(
        """
        incompatible nullability in Args type argument A -> wildcard upper bound and 5 other positions: source has @Nullable Object where target requires Object
            source: Args<?, ?, ?, ?, ?, ?>
                         ^  ^  ^  ^  ^
                         1  2  3  4  5
            target: Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
                                   ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^
                                   1                 2                 3                 4                 5
         \s
            1. path: Args type argument A -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter A of Args
         \s
            2. path: Args type argument B -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter B of Args
         \s
            3. path: Args type argument C -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter C of Args
         \s
            4. path: Args type argument D -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter D of Args
         \s
            5. path: Args type argument E -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of Args
         \s
            and 1 more, not listed
         \s
            fix: declare args as Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
        """,
        """
        static class Args<A extends @Nullable Object, B extends @Nullable Object, C extends @Nullable Object, D extends @Nullable Object, E extends @Nullable Object, F extends @Nullable Object> {}
        static void take(Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object> args) {}
        void test(Args<?, ?, ?, ?, ?, ?> args) {
          // BUG: Diagnostic matches: MESSAGE
          take(args);
        }
        """);
  }

  @Test
  public void listsFiveMismatchesWithoutSayingThereAreMore() {
    assertMessage(
        """
        incompatible nullability in Args type argument A -> wildcard upper bound and 4 other positions: source has @Nullable Object where target requires Object
            source: Args<?, ?, ?, ?, ?>
                         ^  ^  ^  ^  ^
                         1  2  3  4  5
            target: Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
                                   ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^
                                   1                 2                 3                 4                 5
         \s
            1. path: Args type argument A -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter A of Args
         \s
            2. path: Args type argument B -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter B of Args
         \s
            3. path: Args type argument C -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter C of Args
         \s
            4. path: Args type argument D -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter D of Args
         \s
            5. path: Args type argument E -> wildcard upper bound
               source: @Nullable Object
               target: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of Args
         \s
            fix: declare args as Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
        """,
        """
        static class Args<A extends @Nullable Object, B extends @Nullable Object, C extends @Nullable Object, D extends @Nullable Object, E extends @Nullable Object> {}
        static void take(Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object> args) {}
        void test(Args<?, ?, ?, ?, ?> args) {
          // BUG: Diagnostic matches: MESSAGE
          take(args);
        }
        """);
  }

  @Test
  public void adjacentTypeArgumentsThatBothMismatchAreNumberedSideBySide() {
    assertMessage(
        """
        incompatible nullability in Map type argument K and 1 other position: source has @Nullable String where target requires String
            source: Map<@Nullable String, @Nullable String>
                        ^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^
                        1                 2
            target: Map<String, String>
                        ^^^^^^  ^^^^^^
                        1       2
         \s
            1. path: Map type argument K
               source: @Nullable String
               target: String
         \s
            2. path: Map type argument V
               source: @Nullable String
               target: String
        """,
        """
        static void take(Map<String, String> m) {}
        void test(Map<@Nullable String, @Nullable String> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void invariantTypeArgumentIsReportedWhenTheSourceIsLessNullableThanTheTarget() {
    assertMessage(
        """
        incompatible nullability in List type argument E: a type argument must match exactly; source has String where target requires @Nullable String
            source: List<String>
                         ^^^^^^
            target: List<@Nullable String>
                         ^^^^^^^^^^^^^^^^
        """,
        """
        void test(List<String> source) {
          // BUG: Diagnostic matches: MESSAGE
          List<@Nullable String> target = source;
          target.hashCode();
        }
        """);
  }
}
