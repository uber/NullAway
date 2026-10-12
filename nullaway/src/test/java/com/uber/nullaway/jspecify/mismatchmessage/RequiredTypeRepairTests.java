package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * A {@code consider changing the required type to:} line offers the required type with the nullness
 * of the source. It appears where the diagnostic points at the declaration that writes the required
 * type, a local variable declaration, and not at a call, an assignment, or a return, whose required
 * type is written somewhere else. It is also withheld where printing it would drop a type-use
 * annotation.
 */
public class RequiredTypeRepairTests extends MismatchMessageTestBase {

  @Test
  public void suggestsTheRequiredTypeAtAVariableDeclaration() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Map<? extends String, @Nullable String>
                                            ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, String>
                                            ^^^^^^
            path: Map type argument V
            consider changing the required type to:
              Map<? extends Object, @Nullable String>
        """,
        mapWithNullableValuesReaching(
            "Map<? extends Object, String> declared = m; return declared;"));
  }

  @Test
  public void noSuggestedRequiredTypeAtACallSite() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Map<? extends String, @Nullable String>
                                            ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, String>
                                            ^^^^^^
            path: Map type argument V
        """,
        mapWithNullableValuesReaching("take(m); return existing;"));
  }

  @Test
  public void noSuggestedRequiredTypeAtAnAssignmentToAnExistingVariable() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Map<? extends String, @Nullable String>
                                            ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, String>
                                            ^^^^^^
            path: Map type argument V
        """,
        mapWithNullableValuesReaching("existing = m; return existing;"));
  }

  @Test
  public void noSuggestedRequiredTypeAtAReturnStatement() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Map<? extends String, @Nullable String>
                                            ^^^^^^^^^^^^^^^^
            required: Map<? extends Object, String>
                                            ^^^^^^
            path: Map type argument V
        """,
        mapWithNullableValuesReaching("return m;"));
  }

  @Test
  public void theSuggestedRequiredTypeIsTheDeclaredTypeNotAnInstantiatedSupertype() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Sub<@Nullable String>
            found as: List<@Nullable String>
                           ^^^^^^^^^^^^^^^^
            required: List<String>
                           ^^^^^^
            path: List type argument E
            consider changing the required type to:
              List<@Nullable String>
        """,
        """
        static class Sub<T extends @Nullable Object> extends ArrayList<T> {}
        void test(Sub<@Nullable String> s) {
          // BUG: Diagnostic matches: MESSAGE
          List<String> l = s;
          l.hashCode();
        }
        """);
  }

  @Test
  public void noSuggestedRequiredTypeWhereItsPrintedFormWouldDropAnnotations() {
    assertMessage(
        """
        incompatible nullability: found @Nullable String, required String
            found:    Map<String, @Nullable String>
                                  ^^^^^^^^^^^^^^^^
            required: Map<String, String>
                                  ^^^^^^
            path: Map type argument V
        """,
        mapDeclaredWithKeysOfType("@Tainted String"));
  }

  /**
   * Returns the members of a class whose method {@code test} holds a {@code Map<? extends
   * String, @Nullable String> m} and runs {@code statements} on the marked line, where {@code Map<?
   * extends Object, String>} is required. The local {@code existing} and the method {@code take}
   * hold and accept that required type, and the method {@code repairedTypeIsAccepted} shows that
   * the repaired type accepts {@code m}.
   */
  private static String mapWithNullableValuesReaching(String statements) {
    return """
        static void take(Map<? extends Object, String> m) {}
        static Map<? extends Object, String> existingMap() {
          throw new RuntimeException();
        }
        void repairedTypeIsAccepted(Map<? extends String, @Nullable String> m) {
          Map<? extends Object, @Nullable String> repaired = m;
        }
        Map<? extends Object, String> test(Map<? extends String, @Nullable String> m) {
          Map<? extends Object, String> existing = existingMap();
          // BUG: Diagnostic matches: MESSAGE
          %s
        }
        """
        .formatted(statements);
  }

  /**
   * Returns the members of a class that declares a {@code Map<keyType, String>} initialized from a
   * {@code Map<keyType, @Nullable String>}. {@code keyType} may carry the type-use annotation
   * {@code @Tainted}, which the class declares.
   */
  private static String mapDeclaredWithKeysOfType(String keyType) {
    return """
        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
        @interface Tainted {}
        void test(Map<%1$s, @Nullable String> m) {
          // BUG: Diagnostic matches: MESSAGE
          Map<%1$s, String> declared = m;
          declared.hashCode();
        }
        """
        .formatted(keyType);
  }
}
