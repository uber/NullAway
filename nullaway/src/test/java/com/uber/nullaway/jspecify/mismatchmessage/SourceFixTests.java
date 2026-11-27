package com.uber.nullaway.jspecify.mismatchmessage;

import java.util.Map;
import org.junit.Test;

/**
 * A {@code fix:} line that names the variable the source reads offers the source type rewritten
 * with explicit wildcard upper bounds. It appears only where the rewritten type removes every
 * difference and prints without losing a type-use annotation. {@link FixTests} checks the edit the
 * fix makes.
 */
public class SourceFixTests extends MismatchMessageTestBase {

  @Test
  public void theFixRewritesEveryMismatchingWildcard() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound and 1 other position: found @Nullable Object, required Object
            found:    Map<?, ?>
                          ^  ^
                          1  2
            required: Map<? extends Object, ? extends Object>
                                    ^^^^^^            ^^^^^^
                                    1                 2
         \s
            1. path: Map type argument K -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
         \s
            2. path: Map type argument V -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter V of Map
         \s
            fix: declare m as Map<? extends Object, ? extends Object>
        """,
        """
        static void take(Map<? extends Object, ? extends Object> m) {}
        void fixedFormIsAccepted(Map<? extends Object, ? extends Object> m) {
          take(m);
        }
        void test(Map<?, ?> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void theFixLeavesAWildcardTheTargetAllowsToBeNullable() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound: found @Nullable Object, required Object
            found:    Map<?, ?>
                          ^
            required: Map<? extends Object, ? extends @Nullable Object>
                                    ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
            fix: declare m as Map<? extends Object, ?>
        """,
        """
        static void take(Map<? extends Object, ? extends @Nullable Object> m) {}
        void fixedFormIsAccepted(Map<? extends Object, ?> m) {
          take(m);
        }
        void test(Map<?, ?> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void theFixSurvivesAMismatchInTheHarmlessDirection() {
    assertMessage(
        """
        incompatible nullability in Box type argument T -> wildcard upper bound: found @Nullable Object, required Object
            found:    Box<?, ?>
                          ^
            required: Box<? extends Object, ? extends @Nullable Object>
                                    ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Box
            fix: declare b as Box<? extends Object, ?>
        """,
        """
        static class Box<T extends @Nullable Object, U> {}
        static void take(Box<? extends Object, ? extends @Nullable Object> b) {}
        void test(Box<?, ?> b) {
          // BUG: Diagnostic matches: MESSAGE
          take(b);
        }
        void fixedFormIsAccepted(Box<? extends Object, ?> b) {
          take(b);
        }
        """);
  }

  @Test
  public void theFixSurvivesASuperBoundedArgumentTheTargetAlsoDeclaresSuper() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound: found @Nullable Object, required Object
            found:    Map<?, ? super String>
                          ^
            required: Map<? extends Object, ? super String>
                                    ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
            fix: declare m as Map<? extends Object, ? super String>
        """,
        """
        static void take(Map<? extends Object, ? super String> m) {}
        void fixedFormIsAccepted(Map<? extends Object, ? super String> m) {
          take(m);
        }
        void test(Map<?, ? super String> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void theFixRewritesAWildcardInsideAnArrayElement() {
    assertMessage(
        """
        incompatible nullability in array element -> Collection type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<?> []
                           ^
            required: Collection<? extends Object> []
                                           ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare lists as List<? extends Object> []
        """,
        """
        static void takeArray(Collection<? extends Object>[] c) {}
        void fixedFormIsAccepted(List<? extends Object>[] lists) {
          takeArray(lists);
        }
        void test(List<?>[] lists) {
          // BUG: Diagnostic matches: MESSAGE
          takeArray(lists);
        }
        """);
  }

  @Test
  public void theFixRewritesAWildcardInsideANestedTypeArgument() {
    assertMessage(
        """
        incompatible nullability in Collection type argument E -> wildcard upper bound -> List type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<List<?>>
                                ^
            required: Collection<? extends List<? extends Object>>
                                                          ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare lists as List<List<? extends Object>>
        """,
        """
        static void takeNested(Collection<? extends List<? extends Object>> c) {}
        void fixedFormIsAccepted(List<List<? extends Object>> lists) {
          takeNested(lists);
        }
        void test(List<List<?>> lists) {
          // BUG: Diagnostic matches: MESSAGE
          takeNested(lists);
        }
        """);
  }

  @Test
  public void theFixRewritesAWildcardInAnEnclosingTypeArgument() {
    assertMessage(
        """
        incompatible nullability in enclosing type -> Outer type argument T -> wildcard upper bound: found @Nullable Object, required Object
            found:    Outer<?>.Inner
                            ^
            required: Outer<? extends Object>.Inner
                                      ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter T of Outer
            fix: declare inner as Outer<? extends Object>.Inner
        """,
        """
        static class Outer<T extends @Nullable Object> {
          class Inner {}
        }
        static void take(Outer<? extends Object>.Inner inner) {}
        void fixedFormIsAccepted(Outer<? extends Object>.Inner inner) {
          take(inner);
        }
        void test(Outer<?>.Inner inner) {
          // BUG: Diagnostic matches: MESSAGE
          take(inner);
        }
        """);
  }

  @Test
  public void theSourceFixIsStillOfferedWhereTheRequiredTypeIsNotEditable() {
    assertMessage(
        """
        incompatible nullability in Collection type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<?>
                           ^
            required: Collection<? extends Object>
                                           ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare l as List<? extends Object>
        """,
        """
        static void take(Collection<? extends Object> c) {}
        void test(List<?> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void noFixWhenAnExplicitBoundMismatchRemains() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound and 1 other position: found @Nullable Object, required Object
            found:    Map<?, ? extends @Nullable Object>
                          ^            ^^^^^^^^^^^^^^^^
                          1            2
            required: Map<? extends Object, ? extends Object>
                                    ^^^^^^            ^^^^^^
                                    1                 2
         \s
            1. path: Map type argument K -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
         \s
            2. path: Map type argument V -> wildcard upper bound
               found:    @Nullable Object
               required: Object
        """,
        """
        static void take(Map<? extends Object, ? extends Object> m) {}
        void test(Map<?, ? extends @Nullable Object> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void noFixWhenAConcreteTypeArgumentAlsoMismatches() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound and 1 other position: found @Nullable Object, required Object
            found:    Map<?, @Nullable String>
                          ^  ^^^^^^^^^^^^^^^^
                          1  2
            required: Map<? extends Object, String>
                                    ^^^^^^  ^^^^^^
                                    1       2
         \s
            1. path: Map type argument K -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
         \s
            2. path: Map type argument V
               found:    @Nullable String
               required: String
        """,
        """
        static void take(Map<? extends Object, String> m) {}
        void test(Map<?, @Nullable String> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void noFixWhenOneMismatchCannotBeRewritten() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound and 1 other position: found @Nullable Object, required Object
            found:    Map<?, ? super String>
                          ^  ^^^^^^^^^^^^^^
                          1  2
            required: Map<? extends Object, ? extends Object>
                                    ^^^^^^            ^^^^^^
                                    1                 2
         \s
            1. path: Map type argument K -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
         \s
            2. path: Map type argument V -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? super String has no explicit upper bound, so its upper bound is inherited from type parameter V of Map
        """,
        """
        static void take(Map<? extends Object, ? extends Object> m) {}
        void test(Map<?, ? super String> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void noFixWhenBoundsDifferBelowTheTopLevel() {
    assertMessage(
        """
        incompatible nullability in Holder type argument E -> wildcard upper bound -> List type argument E: found @Nullable Object, required Object
            found:    Holder<?>
                             ^
            required: Holder<? extends List<Object>>
                                            ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of Holder
        """,
        """
        static class Holder<E extends List<@Nullable Object>> {}
        static void take(Holder<? extends List<Object>> h) {}
        void test(Holder<?> h) {
          // BUG: Diagnostic matches: MESSAGE
          take(h);
        }
        """);
  }

  @Test
  public void noFixWhenTheRewrittenTypeHoldsACapture() {
    assertMessage(
        """
        incompatible nullability in Map type argument K -> wildcard upper bound -> List type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    Map<List<?>, capture of ?>
                               ^
            required: Map<? extends List<? extends Object>, ?>
                                                   ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
        """,
        """
        static <T extends @Nullable Object> Map<List<?>, T> make(List<T> l) {
          throw new RuntimeException();
        }
        static void take(Map<? extends List<? extends Object>, ?> m) {}
        void test(List<?> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(make(l));
        }
        """);
  }

  // The printer drops @Tainted from the printed types, so TAINTED differs from PLAIN only by the
  // missing fix line.
  @Test
  public void theFixIsWithheldWhereItsPrintedFormWouldDropAnAnnotation() {
    assertMessages(
        Map.of(
            "PLAIN",
            """
            incompatible nullability in Map type argument K -> wildcard upper bound: found @Nullable Object, required Object
                found:    Map<?, String>
                              ^
                required: Map<? extends Object, String>
                                        ^^^^^^
                note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
                fix: declare plain as Map<? extends Object, String>
            """,
            "TAINTED",
            """
            incompatible nullability in Map type argument K -> wildcard upper bound: found @Nullable Object, required Object
                found:    Map<?, String>
                              ^
                required: Map<? extends Object, String>
                                        ^^^^^^
                note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
            """,
            "TAINTED_ARRAY_ELEMENT",
            """
            incompatible nullability in Map type argument K -> wildcard upper bound: found @Nullable Object, required Object
                found:    Map<?, String []>
                              ^
                required: Map<? extends Object, String []>
                                        ^^^^^^
                note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
            """),
        """
        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
        @interface Tainted {}
        static void take(Map<? extends Object, String> m) {}
        static void takeArrays(Map<? extends Object, @Tainted String[]> m) {}
        void plain(Map<?, String> plain) {
          // BUG: Diagnostic matches: PLAIN
          take(plain);
        }
        void tainted(Map<?, @Tainted String> tainted) {
          // BUG: Diagnostic matches: TAINTED
          take(tainted);
        }
        void taintedArrayElement(Map<?, @Tainted String[]> m) {
          // BUG: Diagnostic matches: TAINTED_ARRAY_ELEMENT
          takeArrays(m);
        }
        """);
  }

  @Test
  public void theFixNamesTheFieldItRewrites() {
    assertMessage(
        """
        incompatible nullability in Collection type argument E -> wildcard upper bound: found @Nullable Object, required Object
            found:    List<?>
                           ^
            required: Collection<? extends Object>
                                           ^^^^^^
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
            fix: declare field as List<? extends Object>
        """,
        """
        List<?> field = new ArrayList<>();
        void test(List<Object> result) {
          // BUG: Diagnostic matches: MESSAGE
          result.addAll(field);
        }
        """);
  }
}
