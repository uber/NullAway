package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A {@code note:} line says where the source takes a nullness its printed form does not show: the
 * bound an unbounded or lower-bounded wildcard inherits, the type parameter a captured wildcard
 * instantiates, or the declared bound of a type variable written without nullness. A source that
 * prints its own nullness gets no note.
 */
public class NoteTests extends MismatchMessageTestBase {

  // A List<?> passed to Collection.addAll, as issue #1822 reported it. The message used to print
  // the
  // two types and that List<?> is a subtype of Collection<?>, which read as a contradiction.
  @Test
  public void anUnboundedWildcardNamesTheTypeParameterItsBoundIsInheritedFrom() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    List<?>
                           ^
            required: Collection<? extends Object>
                                           ^^^^^^
            path: Collection type argument E -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter E of List
            did you mean List<? extends Object>?
        """,
        """
        Collection<Object> copy(List<?> list) {
          List<Object> result = new ArrayList<>();
          // BUG: Diagnostic matches: MESSAGE
          result.addAll(list);
          return result;
        }
        Collection<Object> copyNonNull(List<? extends Object> list) {
          List<Object> result = new ArrayList<>();
          result.addAll(list);
          return result;
        }
        static void takeNullable(Collection<? extends @Nullable Object> c) {}
        void nullableTarget(List<?> list) {
          takeNullable(list);
        }
        """);
  }

  @Test
  public void aSuperBoundedWildcardNamesTheTypeParameterItsUpperBoundIsInheritedFrom() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    List<? super String>
                           ^^^^^^^^^^^^^^
            required: Collection<? extends Object>
                                           ^^^^^^
            path: Collection type argument E -> wildcard upper bound
            note: the source ? super String has no explicit upper bound, so its upper bound is inherited from
                  type parameter E of List
        """,
        """
        static void take(Collection<? extends Object> c) {}
        void test(List<? super String> list) {
          // BUG: Diagnostic matches: MESSAGE
          take(list);
        }
        """);
  }

  @Test
  public void anExplicitlyWrittenNullableBoundDrawsNoInheritedBoundNote() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    List<? extends @Nullable String>
                                     ^^^^^^^^^^^^^^^^
            required: List<? extends String>
                                     ^^^^^^
            path: List type argument E -> wildcard upper bound
        """,
        """
        static void take(List<? extends String> l) {}
        void test(List<? extends @Nullable String> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void aTypeVariableWithANullableBoundIsReportedBesideAMismatchingSibling() {
    assertMessage(
        """
        incompatible nullability: 2 mismatches between source and target types
            found:    Pair<T, @Nullable String>
                           ^  ^^^^^^^^^^^^^^^^
                           1  2
            required: Pair<? extends Object, String>
                                     ^^^^^^  ^^^^^^
                                     1       2
         \s
            1. path: Pair type argument A -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source T has no nullness of its own, so it takes the nullness of its declared upper
                     bound
         \s
            2. path: Pair type argument B
               found:    @Nullable String
               required: String
        """,
        """
        static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
        static void take(Pair<? extends Object, String> p) {}
        static <T extends @Nullable Object> void test(Pair<T, @Nullable String> p) {
          // BUG: Diagnostic matches: MESSAGE
          take(p);
        }
        """);
  }

  @Test
  public void noInheritedBoundNoteWhereTheUseStatesItsOwnNullness() {
    assertMessage(
        """
        incompatible nullability: found @Nullable T, required Object
            found:    Box<@Nullable T>
                          ^^^^^^^^^^^
            required: Box<? extends Object>
                                    ^^^^^^
            path: Box type argument T -> wildcard upper bound
        """,
        """
        static class Box<T extends @Nullable Object> {}
        static void takeNonNull(Box<? extends Object> b) {}
        static <T> void theBoundAloneIsAccepted(Box<T> b) {
          takeNonNull(b);
        }
        static <T> void test(Box<@Nullable T> b) {
          // BUG: Diagnostic matches: MESSAGE
          takeNonNull(b);
        }
        """);
  }
}
