package com.uber.nullaway.jspecify.mismatchmessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * The first line of a generic nullability message states the one difference between the two types,
 * or how many there are. Where there are several, each gets a numbered entry, five at most, and a
 * line after them counts the rest. A type argument that the source states outright has to match
 * exactly, and the first line says so where the source is the less nullable side.
 */
public class SummaryAndNumberingTests extends MismatchMessageTestBase {

  @Test
  public void listsAtMostFiveMismatchesAndSaysHowManyMoreThereAre() {
    assertMessage(
        """
        incompatible nullability: 6 mismatches between source and target types
            found:    Args<?, ?, ?, ?, ?, ?>
                           ^  ^  ^  ^  ^
                           1  2  3  4  5
            required: Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
                                     ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^
                                     1                 2                 3                 4                 5
         \s
            1. path: Args type argument A -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter A of Args
         \s
            2. path: Args type argument B -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter B of Args
         \s
            3. path: Args type argument C -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter C of Args
         \s
            4. path: Args type argument D -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter D of Args
         \s
            5. path: Args type argument E -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter E of Args
         \s
            and 1 more, not listed
         \s
            did you mean Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>?
        """,
        wildcardsWhereNonNullBoundsAreRequired(6));
  }

  @Test
  public void listsFiveMismatchesWithoutSayingThereAreMore() {
    assertMessage(
        """
        incompatible nullability: 5 mismatches between source and target types
            found:    Args<?, ?, ?, ?, ?>
                           ^  ^  ^  ^  ^
                           1  2  3  4  5
            required: Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
                                     ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^
                                     1                 2                 3                 4                 5
         \s
            1. path: Args type argument A -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter A of Args
         \s
            2. path: Args type argument B -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter B of Args
         \s
            3. path: Args type argument C -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter C of Args
         \s
            4. path: Args type argument D -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter D of Args
         \s
            5. path: Args type argument E -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter E of Args
         \s
            did you mean Args<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>?
        """,
        wildcardsWhereNonNullBoundsAreRequired(5));
  }

  @Test
  public void adjacentTypeArgumentsThatBothMismatchAreNumberedSideBySide() {
    assertMessage(
        """
        incompatible nullability: 2 mismatches between source and target types
            found:    Map<@Nullable String, @Nullable String>
                          ^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^
                          1                 2
            required: Map<String, String>
                          ^^^^^^  ^^^^^^
                          1       2
         \s
            1. path: Map type argument K
               found:    @Nullable String
               required: String
         \s
            2. path: Map type argument V
               found:    @Nullable String
               required: String
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
  public void invariantTypeArgumentIsReportedWhenTheSourceIsLessNullableThanRequired() {
    assertMessage(
        """
        incompatible nullability: a type argument must match exactly; found String, required @Nullable String
            found:    List<String>
                           ^^^^^^
            required: List<@Nullable String>
                           ^^^^^^^^^^^^^^^^
            path: List type argument E
        """,
        """
        void test(List<String> source) {
          // BUG: Diagnostic matches: MESSAGE
          List<@Nullable String> target = source;
          target.hashCode();
        }
        """);
  }

  /**
   * Returns the members of a class that passes {@code Args<?, ...>} where {@code Args<? extends
   * Object, ...>} is required. Every type parameter of {@code Args} has a {@code @Nullable Object}
   * bound, so each of the {@code count} wildcards is one mismatch.
   */
  private static String wildcardsWhereNonNullBoundsAreRequired(int count) {
    List<String> parameters = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      parameters.add((char) ('A' + i) + " extends @Nullable Object");
    }
    return """
        static class Args<%s> {}
        static void take(Args<%s> args) {}
        void test(Args<%s> args) {
          // BUG: Diagnostic matches: MESSAGE
          take(args);
        }
        """
        .formatted(
            String.join(", ", parameters),
            String.join(", ", Collections.nCopies(count, "? extends Object")),
            String.join(", ", Collections.nCopies(count, "?")));
  }
}
