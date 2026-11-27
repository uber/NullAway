package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A {@code note:} line says where the source takes a nullness its printed form does not show: the
 * bound an unbounded or lower-bounded wildcard inherits, or the type parameter a captured wildcard
 * instantiates. A source that prints its own nullness gets no note.
 */
public class NoteTests extends MismatchMessageTestBase {

  // A List<?> passed to Collection.addAll, as issue #1822 reported it. The message used to print
  // the two types and that List<?> is a subtype of Collection<?>, which read as a contradiction.
  @Test
  public void anUnboundedWildcardNamesTheTypeParameterItsBoundIsInheritedFrom() {
    assertMessage(
        """
        incompatible nullability in Collection type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<?>
                           ^
            required: Collection<? extends Object>
                                           ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare list as List<? extends Object>
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
        incompatible nullability in Collection type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<? super String>
                           ^^^^^^^^^^^^^^
            required: Collection<? extends Object>
                                           ^^^^^^
            note: the source ? super String has no explicit upper bound, so its upper bound is inherited from type parameter E of List
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
        incompatible nullability in List type argument E -> wildcard upper bound: found @Nullable String, required String
            found:    List<? extends @Nullable String>
                                     ^^^^^^^^^^^^^^^^
            required: List<? extends String>
                                     ^^^^^^
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
  public void noInheritedBoundNoteWhereTheUseStatesItsOwnNullness() {
    assertMessage(
        """
        incompatible nullability in Box type argument T -> wildcard upper bound: found @Nullable T, required Object
            found:    Box<@Nullable T>
                          ^^^^^^^^^^^
            required: Box<? extends Object>
                                    ^^^^^^
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

  @Test
  public void aWildcardWhoseImplicitBoundHoldsTheMismatchNamesItsTypeParameter() {
    assertMessage(
        """
        incompatible nullability in Foo type argument T -> wildcard upper bound -> List type argument E: found @Nullable String, required String
            found:    Foo<?>
                          ^
            required: Foo<? extends List<String>>
                                         ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
        """,
        """
        static class Foo<T extends List<@Nullable String>> {}
        static void take(Foo<? extends List<String>> f) {}
        void test(Foo<?> f) {
          // BUG: Diagnostic matches: MESSAGE
          take(f);
        }
        """);
  }

  @Test
  public void aNestedWildcardWhoseImplicitBoundHoldsTheMismatchNamesItsTypeParameter() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard upper bound -> Foo type argument T -> wildcard upper bound -> List type argument E: found @Nullable String, required String
            found:    List<Foo<?>>
                               ^
            required: List<? extends Foo<? extends List<String>>>
                                                        ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Foo
        """,
        """
        static class Foo<T extends List<@Nullable String>> {}
        static void take(List<? extends Foo<? extends List<String>>> f) {}
        void test(List<Foo<?>> f) {
          // BUG: Diagnostic matches: MESSAGE
          take(f);
        }
        """);
  }

  @Test
  public void aWildcardInsideAWildcardBoundNamesItsTypeParameter() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard upper bound -> List type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<? extends List<?>>
                                          ^
            required: List<? extends List<? extends Object>>
                                                    ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare l as List<? extends List<? extends Object>>
        """,
        """
        static void take(List<? extends List<? extends Object>> l) {}
        void test(List<? extends List<?>> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void aWildcardInsideAnArrayInsideAWildcardBoundNamesItsTypeParameter() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> wildcard upper bound -> array element -> List type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<? extends List<?> []>
                                          ^
            required: List<? extends List<? extends Object> []>
                                                    ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare l as List<? extends List<? extends Object> []>
        """,
        """
        static void take(List<? extends List<? extends Object>[]> l) {}
        void test(List<? extends List<?>[]> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }
}
