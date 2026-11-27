package com.uber.nullaway.jspecify.mismatchmessage;

import java.util.Map;
import org.junit.Test;

/**
 * A {@code fix:} line that names the variable a declaration declares offers the target type with
 * the nullness of the source. It appears where the diagnostic points at the declaration that writes
 * the target type, a local variable declaration, and not at a call, an assignment, or a return,
 * whose target type is written somewhere else. It is also withheld where printing it would drop a
 * type-use annotation. {@link FixTests} checks the edit the fix makes.
 */
public class TargetTypeFixTests extends MismatchMessageTestBase {

  private static final String MAP_VALUES_MESSAGE =
      """
      incompatible nullability in Map type argument V: source has @Nullable String where target requires String
          source: Map<? extends String, @Nullable String>
                                        ^^^^^^^^^^^^^^^^
          target: Map<? extends Object, String>
                                        ^^^^^^
      """;

  @Test
  public void theFixIsOfferedOnlyWhereTheDiagnosticPointsAtTheTargetTypeDeclaration() {
    assertMessages(
        Map.of(
            "DECLARATION",
            MAP_VALUES_MESSAGE
                + "    fix: declare declared as Map<? extends Object, @Nullable String>\n",
            "CALL",
            MAP_VALUES_MESSAGE,
            "ASSIGNMENT",
            MAP_VALUES_MESSAGE,
            "RETURN",
            MAP_VALUES_MESSAGE),
        """
        static void take(Map<? extends Object, String> m) {}
        void fixedTypeIsAccepted(Map<? extends String, @Nullable String> m) {
          Map<? extends Object, @Nullable String> fixed = m;
        }
        void declaration(Map<? extends String, @Nullable String> m) {
          // BUG: Diagnostic matches: DECLARATION
          Map<? extends Object, String> declared = m;
          declared.hashCode();
        }
        void call(Map<? extends String, @Nullable String> m) {
          // BUG: Diagnostic matches: CALL
          take(m);
        }
        void assignment(Map<? extends String, @Nullable String> m, Map<? extends Object, String> existing) {
          // BUG: Diagnostic matches: ASSIGNMENT
          existing = m;
          existing.hashCode();
        }
        Map<? extends Object, String> returned(Map<? extends String, @Nullable String> m) {
          // BUG: Diagnostic matches: RETURN
          return m;
        }
        """);
  }

  @Test
  public void theFixIsTheDeclaredTypeNotAnInstantiatedSupertype() {
    assertMessage(
        """
        incompatible nullability in List type argument E: source has @Nullable String where target requires String
            source: Sub<@Nullable String>
                        ^^^^^^^^^^^^^^^^
            target: List<String>
                         ^^^^^^
            fix: declare l as List<@Nullable String>
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
  public void theFixReplacesTheNodeAtEveryKindOfStep() {
    assertMessages(
        Map.of(
            "WILDCARD_BOUND",
            """
            incompatible nullability in Collection type argument E -> wildcard upper bound: source has @Nullable Object where target requires Object
                source: List<?>
                             ^
                target: Collection<? extends Object>
                                             ^^^^^^
                note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter E of List
                fix: declare list as List<? extends Object>
                fix: declare c as Collection<? extends @Nullable Object>
            """,
            "ARRAY_ELEMENT",
            """
            incompatible nullability in array element -> List type argument E: source has @Nullable String where target requires String
                source: List<@Nullable String> []
                             ^^^^^^^^^^^^^^^^
                target: List<String> []
                             ^^^^^^
                fix: declare d as List<@Nullable String> []
            """,
            "ENCLOSING_TYPE",
            """
            incompatible nullability in enclosing type -> Outer type argument T: source has @Nullable String where target requires String
                source: Outer<@Nullable String>.Inner
                              ^^^^^^^^^^^^^^^^
                target: Outer<String>.Inner
                              ^^^^^^
                fix: declare d as Outer<@Nullable String>.Inner
            """),
        """
        static class Outer<T extends @Nullable Object> {
          class Inner {}
        }
        void wildcardBound(List<?> list) {
          // BUG: Diagnostic matches: WILDCARD_BOUND
          Collection<? extends Object> c = list;
          c.hashCode();
        }
        void arrayElement(List<@Nullable String>[] src) {
          // BUG: Diagnostic matches: ARRAY_ELEMENT
          List<String>[] d = src;
          d.hashCode();
        }
        void enclosingType(Outer<@Nullable String>.Inner src) {
          // BUG: Diagnostic matches: ENCLOSING_TYPE
          Outer<String>.Inner d = src;
          d.hashCode();
        }
        """);
  }

  // The printer drops @Tainted from the printed types, so TAINTED and TAINTED_BOUND differ from
  // PLAIN and a message with a fix only by the missing fix line.
  @Test
  public void theFixIsWithheldWhereItsPrintedFormWouldDropAnAnnotation() {
    assertMessages(
        Map.of(
            "PLAIN",
            """
            incompatible nullability in Map type argument V: source has @Nullable String where target requires String
                source: Map<String, @Nullable String>
                                    ^^^^^^^^^^^^^^^^
                target: Map<String, String>
                                    ^^^^^^
                fix: declare plain as Map<String, @Nullable String>
            """,
            "TAINTED",
            """
            incompatible nullability in Map type argument V: source has @Nullable String where target requires String
                source: Map<String, @Nullable String>
                                    ^^^^^^^^^^^^^^^^
                target: Map<String, String>
                                    ^^^^^^
            """,
            "TAINTED_BOUND",
            """
            incompatible nullability in Map type argument V: source has @Nullable String where target requires String
                source: Map<? extends String, @Nullable String>
                                              ^^^^^^^^^^^^^^^^
                target: Map<? extends String, String>
                                              ^^^^^^
            """,
            "TYPE_VARIABLE",
            """
            incompatible nullability in Map type argument V: source has @Nullable String where target requires String
                source: Map<T, @Nullable String>
                               ^^^^^^^^^^^^^^^^
                target: Map<T, String>
                               ^^^^^^
                fix: declare d as Map<T, @Nullable String>
            """),
        """
        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
        @interface Tainted {}
        void plain(Map<String, @Nullable String> m) {
          // BUG: Diagnostic matches: PLAIN
          Map<String, String> plain = m;
          plain.hashCode();
        }
        void tainted(Map<@Tainted String, @Nullable String> m) {
          // BUG: Diagnostic matches: TAINTED
          Map<@Tainted String, String> d = m;
          d.hashCode();
        }
        void taintedBound(Map<? extends @Tainted String, @Nullable String> m) {
          // BUG: Diagnostic matches: TAINTED_BOUND
          Map<? extends @Tainted String, String> d = m;
          d.hashCode();
        }
        static <T> void typeVariable(Map<T, @Nullable String> m) {
          // BUG: Diagnostic matches: TYPE_VARIABLE
          Map<T, String> d = m;
          d.hashCode();
        }
        """);
  }
}
