package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A {@code did you mean} line offers the source type rewritten with explicit wildcard upper bounds.
 * It appears only where the rewritten type repairs every difference and prints without losing a
 * type-use annotation.
 */
public class SourceRepairTests extends MismatchMessageTestBase {

  @Test
  public void suggestionRewritesEveryMismatchingWildcard() {
    assertMessage(
        """
        incompatible nullability: 2 mismatches between source and target types
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
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter K of Map
         \s
            2. path: Map type argument V -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter V of Map
         \s
            did you mean Map<? extends Object, ? extends Object>?
        """,
        """
        static void take(Map<? extends Object, ? extends Object> m) {}
        void suggestedFormIsAccepted(Map<? extends Object, ? extends Object> m) {
          take(m);
        }
        void test(Map<?, ?> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void suggestionLeavesAWildcardTheTargetAllowsToBeNullable() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Map<?, ?>
                          ^
            required: Map<? extends Object, ? extends @Nullable Object>
                                    ^^^^^^
            path: Map type argument K -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter K of Map
            did you mean Map<? extends Object, ?>?
        """,
        """
        static void take(Map<? extends Object, ? extends @Nullable Object> m) {}
        void suggestedFormIsAccepted(Map<? extends Object, ?> m) {
          take(m);
        }
        void test(Map<?, ?> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void suggestionSurvivesAMismatchInTheHarmlessDirection() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Box<?, ?>
                          ^
            required: Box<? extends Object, ? extends @Nullable Object>
                                    ^^^^^^
            path: Box type argument T -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter T of Box
            did you mean Box<? extends Object, ?>?
        """,
        """
        static class Box<T extends @Nullable Object, U> {}
        static void take(Box<? extends Object, ? extends @Nullable Object> b) {}
        void test(Box<?, ?> b) {
          // BUG: Diagnostic matches: MESSAGE
          take(b);
        }
        void suggestedFormIsAccepted(Box<? extends Object, ?> b) {
          take(b);
        }
        """);
  }

  @Test
  public void suggestionSurvivesASuperBoundedArgumentTheTargetAlsoDeclaresSuper() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Map<?, ? super String>
                          ^
            required: Map<? extends Object, ? super String>
                                    ^^^^^^
            path: Map type argument K -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter K of Map
            did you mean Map<? extends Object, ? super String>?
        """,
        """
        static void take(Map<? extends Object, ? super String> m) {}
        void suggestedFormIsAccepted(Map<? extends Object, ? super String> m) {
          take(m);
        }
        void test(Map<?, ? super String> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """);
  }

  @Test
  public void unboundedWildcardSuggestionInsideAnArrayElement() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    List<?> []
                           ^
            required: Collection<? extends Object> []
                                           ^^^^^^
            path: array element -> Collection type argument E -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter E of List
            did you mean List<? extends Object> []?
        """,
        """
        static void takeArray(Collection<? extends Object>[] c) {}
        void suggestedFormIsAccepted(List<? extends Object>[] lists) {
          takeArray(lists);
        }
        void test(List<?>[] lists) {
          // BUG: Diagnostic matches: MESSAGE
          takeArray(lists);
        }
        """);
  }

  @Test
  public void unboundedWildcardSuggestionInsideANestedTypeArgument() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    List<List<?>>
                                ^
            required: Collection<? extends List<? extends Object>>
                                                          ^^^^^^
            path: Collection type argument E -> wildcard upper bound -> List type argument E
                  -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter E of List
            did you mean List<List<? extends Object>>?
        """,
        """
        static void takeNested(Collection<? extends List<? extends Object>> c) {}
        void suggestedFormIsAccepted(List<List<? extends Object>> lists) {
          takeNested(lists);
        }
        void test(List<List<?>> lists) {
          // BUG: Diagnostic matches: MESSAGE
          takeNested(lists);
        }
        """);
  }

  @Test
  public void unboundedWildcardSuggestionInAnEnclosingTypeArgument() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Outer<?>.Inner
                            ^
            required: Outer<? extends Object>.Inner
                                      ^^^^^^
            path: enclosing type -> Outer type argument T -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter T of Outer
            did you mean Outer<? extends Object>.Inner?
        """,
        """
        static class Outer<T extends @Nullable Object> {
          class Inner {}
        }
        static void take(Outer<? extends Object>.Inner inner) {}
        void suggestedFormIsAccepted(Outer<? extends Object>.Inner inner) {
          take(inner);
        }
        void test(Outer<?>.Inner inner) {
          // BUG: Diagnostic matches: MESSAGE
          take(inner);
        }
        """);
  }

  @Test
  public void theSourceRepairIsStillOfferedWhereTheRequiredTypeIsNotEditable() {
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
        static void take(Collection<? extends Object> c) {}
        void test(List<?> l) {
          // BUG: Diagnostic matches: MESSAGE
          take(l);
        }
        """);
  }

  @Test
  public void noSuggestionWhenAnExplicitBoundMismatchRemains() {
    assertMessage(
        """
        incompatible nullability: 2 mismatches between source and target types
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
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter K of Map
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
  public void noSuggestionWhenAConcreteTypeArgumentAlsoMismatches() {
    assertMessage(
        """
        incompatible nullability: 2 mismatches between source and target types
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
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter K of Map
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
  public void noSuggestionWhenOneMismatchCannotBeRewritten() {
    assertMessage(
        """
        incompatible nullability: 2 mismatches between source and target types
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
               note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                     parameter K of Map
         \s
            2. path: Map type argument V -> wildcard upper bound
               found:    @Nullable Object
               required: Object
               note: the source ? super String has no explicit upper bound, so its upper bound is inherited
                     from type parameter V of Map
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
  public void noSuggestionWhenBoundsDifferBelowTheTopLevel() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Holder<?>
                             ^
            required: Holder<? extends List<Object>>
                                            ^^^^^^
            path: Holder type argument E -> wildcard upper bound -> List type argument E
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
  public void noSuggestionWhenTheRewrittenTypeHoldsACapture() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Map<List<?>, capture of ?>
                               ^
            required: Map<? extends List<? extends Object>, ?>
                                                   ^^^^^^
            path: Map type argument K -> wildcard upper bound -> List type argument E -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter E of List
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

  @Test
  public void aSourceWithNoOtherTypeUseAnnotationGetsTheRepair() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Map<?, String>
                          ^
            required: Map<? extends Object, String>
                                    ^^^^^^
            path: Map type argument K -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter K of Map
            did you mean Map<? extends Object, String>?
        """,
        mapWithValuesOfType("String"));
  }

  // The printer drops @Tainted from both printed types, so the message differs from the one in
  // aSourceWithNoOtherTypeUseAnnotationGetsTheRepair only by the missing repair line.
  @Test
  public void noSourceRepairWhereItsPrintedFormWouldDropAnnotations() {
    assertMessage(
        """
        incompatible nullability: found @Nullable Object, required Object
            found:    Map<?, String>
                          ^
            required: Map<? extends Object, String>
                                    ^^^^^^
            path: Map type argument K -> wildcard upper bound
            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                  parameter K of Map
        """,
        mapWithValuesOfType("@Tainted String"));
  }

  /**
   * Returns the members of a class that passes {@code Map<?, valueType>} where {@code Map<? extends
   * Object, String>} is required. The key wildcard is the one mismatch; {@code valueType} may carry
   * the type-use annotation {@code @Tainted}, which the class declares.
   */
  private static String mapWithValuesOfType(String valueType) {
    return """
        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
        @interface Tainted {}
        static void take(Map<? extends Object, String> m) {}
        void test(Map<?, %s> m) {
          // BUG: Diagnostic matches: MESSAGE
          take(m);
        }
        """
        .formatted(valueType);
  }
}
