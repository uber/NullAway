package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Regression coverage for lower annotations at javac's inferred common intersection shape. */
public class IntersectionLowerBoundInferenceTests extends NullAwayTestsBase {

  @Test
  public void arrayLowerConflictsInBothOrders() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              /** Returns one of two values of an inferred common type. */
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              void test(ArrayList<@Nullable String>[] a, LinkedList<String>[] b) {
                // BUG: Diagnostic contains: incompatible types
                List<String>[] forward = pick(a, b);
                // BUG: Diagnostic contains: incompatible types
                List<String>[] reverse = pick(b, a);
                // BUG: Diagnostic contains: incompatible types
                pick(a, b)[0].get(0).length();
                // BUG: Diagnostic contains: incompatible types
                pick(b, a)[0].get(0).length();
                // Invariance is still a conflict even with a nullable-element target.
                // BUG: Diagnostic contains: incompatible types
                List<@Nullable String>[] nullableForward = pick(a, b);
                // BUG: Diagnostic contains: incompatible types
                List<@Nullable String>[] nullableReverse = pick(b, a);
              }
              void multidimensional(ArrayList<@Nullable String>[][] a, LinkedList<String>[][] b) {
                // BUG: Diagnostic contains: incompatible types
                pick(a, b)[0][0].get(0).length();
                // BUG: Diagnostic contains: incompatible types
                pick(b, a)[0][0].get(0).length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonArrayLowerConflictsInBothOrders() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              /** Returns one of two values of an inferred common type. */
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              void test(ArrayList<@Nullable String> a, LinkedList<String> b) {
                // BUG: Diagnostic contains: incompatible types
                List<String> forward = pick(a, b);
                // BUG: Diagnostic contains: incompatible types
                List<String> reverse = pick(b, a);
                // BUG: Diagnostic contains: incompatible types
                pick(a, b).get(0).length();
                // BUG: Diagnostic contains: incompatible types
                pick(b, a).get(0).length();
                // BUG: Diagnostic contains: incompatible types
                List<@Nullable String> nullableForward = pick(a, b);
                // BUG: Diagnostic contains: incompatible types
                List<@Nullable String> nullableReverse = pick(b, a);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void interfaceOnlyIntersectionChecksEveryComponentInBothOrders() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface First<T extends @Nullable Object> { T first(); }
              interface Second<T extends @Nullable Object> { T second(); }
              abstract static class A<T extends @Nullable Object> implements First<String>, Second<T> {}
              abstract static class B<T extends @Nullable Object> implements First<String>, Second<T> {}
              /** Returns one of two values of an inferred common type. */
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              void conflict(A<@Nullable String> a, B<String> b) {
                // The agreeing First component must not hide the conflicting Second component.
                // BUG: Diagnostic contains: arguments 'b' (B<String>) and 'a' (A<@Nullable String>) have conflicting nullability
                pick(a, b).second().length();
                // BUG: Diagnostic contains: arguments 'a' (A<@Nullable String>) and 'b' (B<String>) have conflicting nullability
                pick(b, a).second().length();
              }
              void agrees(A<String> a, B<String> b) {
                First<String> firstForward = pick(a, b);
                First<String> firstReverse = pick(b, a);
                Second<String> secondForward = pick(a, b);
                Second<String> secondReverse = pick(b, a);
                pick(a, b).first().length();
                pick(b, a).second().length();
              }
              void nullableAgrees(A<@Nullable String> a, B<@Nullable String> b) {
                Second<@Nullable String> forward = pick(a, b);
                Second<@Nullable String> reverse = pick(b, a);
                pick(a, b).first().length();
                pick(b, a).first().length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(a, b).second().length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(b, a).second().length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableScalarAndArrayIntersectionRootsInBothOrders() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface First<T extends @Nullable Object> { T first(); }
              interface Second<T extends @Nullable Object> { T second(); }
              abstract static class A<T extends @Nullable Object> implements First<T>, Second<T> {}
              abstract static class B<T extends @Nullable Object> implements First<T>, Second<T> {}
              /** Returns one of two values of an inferred common type. */
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              /** Keeps the occurrence's nullable return override independent of inference. */
              static <X extends @Nullable Object> @Nullable X maybePick(X a, X b) { return null; }
              static void acceptsNullable(@Nullable First<String> value) {}
              static void acceptsNonNull(First<String> value) {}
              void scalar(@Nullable A<String> a, B<String> b) {
                acceptsNullable(pick(a, b));
                acceptsNullable(pick(b, a));
                // BUG: Diagnostic contains: @Nullable
                acceptsNonNull(pick(a, b));
                // BUG: Diagnostic contains: @Nullable
                acceptsNonNull(pick(b, a));
                // BUG: Diagnostic contains: dereferenced expression
                pick(a, b).first().length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(b, a).second().length();
                var forward = pick(a, b);
                var reverse = pick(b, a);
                if (forward != null) { forward.first().length(); }
                if (reverse != null) { reverse.second().length(); }
              }
              void bothNullable(@Nullable A<String> a, @Nullable B<String> b) {
                acceptsNullable(pick(a, b));
                acceptsNullable(pick(b, a));
                // BUG: Diagnostic contains: dereferenced expression
                pick(a, b).first().length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(b, a).second().length();
              }
              void returnOverride(A<String> a, B<String> b) {
                acceptsNullable(maybePick(a, b));
                acceptsNullable(maybePick(b, a));
                // BUG: Diagnostic contains: dereferenced expression
                maybePick(a, b).first().length();
                // BUG: Diagnostic contains: dereferenced expression
                maybePick(b, a).second().length();
                // The nullable occurrence must not contaminate inference at a different call.
                pick(a, b).first().length();
                pick(b, a).second().length();
              }
              void arrayComponents(@Nullable A<String>[] a, B<String>[] b) {
                @Nullable First<String>[] forward = pick(a, b);
                @Nullable First<String>[] reverse = pick(b, a);
                @Nullable Second<String>[] secondForward = pick(a, b);
                @Nullable Second<String>[] secondReverse = pick(b, a);
                // BUG: Diagnostic contains: incompatible types
                First<String>[] invalidForward = pick(a, b);
                // BUG: Diagnostic contains: incompatible types
                First<String>[] invalidReverse = pick(b, a);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void agreeingLowerAnnotationsInBothOrders() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              /** Returns one of two values of an inferred common type. */
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              void nonNull(ArrayList<String> a, LinkedList<String> b,
                  ArrayList<String>[] aa, LinkedList<String>[] bb) {
                List<String> forward = pick(a, b);
                List<String> reverse = pick(b, a);
                List<String>[] arrayForward = pick(aa, bb);
                List<String>[] arrayReverse = pick(bb, aa);
                pick(a, b).get(0).length();
                pick(b, a).get(0).length();
                pick(aa, bb)[0].get(0).length();
                pick(bb, aa)[0].get(0).length();
              }
              void nullable(ArrayList<@Nullable String> a, LinkedList<@Nullable String> b,
                  ArrayList<@Nullable String>[] aa, LinkedList<@Nullable String>[] bb) {
                List<@Nullable String> forward = pick(a, b);
                List<@Nullable String> reverse = pick(b, a);
                List<@Nullable String>[] arrayForward = pick(aa, bb);
                List<@Nullable String>[] arrayReverse = pick(bb, aa);
                // BUG: Diagnostic contains: incompatible types
                List<String> invalidForward = pick(a, b);
                // BUG: Diagnostic contains: incompatible types
                List<String> invalidReverse = pick(b, a);
                // BUG: Diagnostic contains: incompatible types
                List<String>[] invalidArrayForward = pick(aa, bb);
                // BUG: Diagnostic contains: incompatible types
                List<String>[] invalidArrayReverse = pick(bb, aa);
                // BUG: Diagnostic contains: dereferenced expression
                pick(a, b).get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(b, a).get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(aa, bb)[0].get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(bb, aa)[0].get(0).length();
              }
              static void acceptsNullableList(@Nullable List<String> value) {}
              void nullableRoots(@Nullable ArrayList<String> a, LinkedList<String> b,
                  @Nullable ArrayList<String>[] aa, LinkedList<String>[] bb) {
                acceptsNullableList(pick(a, b));
                acceptsNullableList(pick(b, a));
                // BUG: Diagnostic contains: dereferenced expression
                pick(a, b).get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                pick(b, a).get(0).length();
                @Nullable List<String>[] arrayForward = pick(aa, bb);
                @Nullable List<String>[] arrayReverse = pick(bb, aa);
                // BUG: Diagnostic contains: incompatible types
                List<String>[] invalidArrayForward = pick(aa, bb);
                // BUG: Diagnostic contains: incompatible types
                List<String>[] invalidArrayReverse = pick(bb, aa);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void arrayCovarianceAndNominalWideningRemainAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              /** Returns one of two values of an inferred common type. */
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              void covariant(Number[] numbers, @Nullable Integer[] integers,
                  String[][] strings, @Nullable String[][] nullableElements,
                  String[] @Nullable [] nullableArrays) {
                @Nullable Number[] forward = pick(numbers, integers);
                @Nullable Number[] reverse = pick(integers, numbers);
                @Nullable String[] @Nullable [] dimensionsForward = pick(nullableElements, nullableArrays);
                @Nullable String[] @Nullable [] dimensionsReverse = pick(nullableArrays, nullableElements);
                @Nullable String[][] elementsForward = pick(strings, nullableElements);
                @Nullable String[][] elementsReverse = pick(nullableElements, strings);
              }
              void related(ArrayList<String> subtype, List<String> supertype,
                  ArrayList<String>[] subtypes, List<String>[] supertypes) {
                List<String> forward = pick(subtype, supertype);
                List<String> reverse = pick(supertype, subtype);
                List<String>[] arrayForward = pick(subtypes, supertypes);
                List<String>[] arrayReverse = pick(supertypes, subtypes);
              }
              void widened(ArrayList<String> strings, LinkedList<Integer> integers) {
                List<? extends Object> forward = pick(strings, integers);
                List<? extends Object> reverse = pick(integers, strings);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void sameNominalInvariantConflictsRemainRejectedInBothOrders() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              /** Returns one of two values of an inferred common type. */
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              void test(List<@Nullable String> a, List<String> b,
                  List<@Nullable String>[] aa, List<String>[] bb) {
                // BUG: Diagnostic contains: List<String> cannot be converted to List<@Nullable String>
                pick(a, b);
                // BUG: Diagnostic contains: List<@Nullable String> cannot be converted to List<String>
                pick(b, a);
                // BUG: Diagnostic contains: incompatible types
                pick(aa, bb);
                // BUG: Diagnostic contains: incompatible types
                pick(bb, aa);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constructorIntersectionDeclarationBoundsSurviveAnonymousAttribution() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface First<T extends @Nullable Object> {}
              interface Second<T extends @Nullable Object> {}
              abstract static class Both implements First<@Nullable String>, Second<String> {}
              static class InterfaceBounds {
                <U extends @Nullable First<@Nullable String> & @Nullable Second<String>>
                    InterfaceBounds(U value) {}
              }
              static class ClassBounds {
                <U extends @Nullable Object & @Nullable First<@Nullable String> & @Nullable Second<String>>
                    ClassBounds(U value) {}
              }
              static class MixedBounds {
                <U extends @Nullable First<@Nullable String> & Second<String>>
                    MixedBounds(U value) {}
              }
              void ordinaryFirst(Both value) {
                new InterfaceBounds(null);
                new InterfaceBounds(null) {};
                new InterfaceBounds(value);
                new InterfaceBounds(value) {};
                new ClassBounds(null);
                new ClassBounds(null) {};
                new ClassBounds(value);
                new ClassBounds(value) {};
                // One nullable bound does not relax the other non-null component.
                // BUG: Diagnostic contains: @Nullable
                new MixedBounds(null);
                // BUG: Diagnostic contains: @Nullable
                new MixedBounds(null) {};
              }
              void anonymousFirst(Both value) {
                new InterfaceBounds(null) {};
                new InterfaceBounds(null);
                new ClassBounds(null) {};
                new ClassBounds(null);
                new InterfaceBounds(value) {};
                new InterfaceBounds(value);
                new ClassBounds(value) {};
                new ClassBounds(value);
                // BUG: Diagnostic contains: @Nullable
                new MixedBounds(null) {};
                // BUG: Diagnostic contains: @Nullable
                new MixedBounds(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constructorNestedDeclarationAnnotationsDoNotRelaxTheBoundRoot() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Elements {
                <U extends List<@Nullable String>> Elements(U value) {}
              }
              static class Arrays {
                <U extends List<@Nullable String[]>> Arrays(U value) {}
              }
              static class Indexed {
                <A extends @Nullable Object, U extends List<@Nullable String>>
                    Indexed(A optional, U value) {}
              }
              void ordinaryFirst(List<@Nullable String> elements, List<@Nullable String[]> arrays) {
                new Elements(elements);
                new Elements(elements) {};
                new Arrays(arrays);
                new Arrays(arrays) {};
                new Indexed(null, elements);
                new Indexed(null, elements) {};
                // Each parameter_index has its own root and nested annotation positions.
                // BUG: Diagnostic contains: @Nullable
                new Indexed(null, null);
                // BUG: Diagnostic contains: @Nullable
                new Indexed(null, null) {};
                // TYPE_ARGUMENT and ARRAY paths must not be applied to the List root.
                // BUG: Diagnostic contains: @Nullable
                new Elements(null);
                // BUG: Diagnostic contains: @Nullable
                new Elements(null) {};
                // BUG: Diagnostic contains: @Nullable
                new Arrays(null);
                // BUG: Diagnostic contains: @Nullable
                new Arrays(null) {};
              }
              void anonymousFirst(List<@Nullable String> elements, List<@Nullable String[]> arrays) {
                new Elements(elements) {};
                new Elements(elements);
                new Arrays(arrays) {};
                new Arrays(arrays);
                new Indexed(null, elements) {};
                new Indexed(null, elements);
                // BUG: Diagnostic contains: @Nullable
                new Indexed(null, null) {};
                // BUG: Diagnostic contains: @Nullable
                new Indexed(null, null);
                // BUG: Diagnostic contains: @Nullable
                new Elements(null) {};
                // BUG: Diagnostic contains: @Nullable
                new Elements(null);
              }
              void invariantConflicts(List<String> elements, List<String[]> arrays) {
                // BUG: Diagnostic contains: incompatible types
                new Elements(elements);
                // BUG: Diagnostic contains: incompatible types
                new Elements(elements) {};
                // BUG: Diagnostic contains: incompatible types
                new Arrays(arrays);
                // BUG: Diagnostic contains: incompatible types
                new Arrays(arrays) {};
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constructorQualifiedAndWildcardDeclarationPathsStayDistinct() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    JSpecifyJavacConfig.HANDLE_WILDCARD_GENERICS_FLAG)))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Outer<T extends @Nullable Object> {
                class Inner<S extends @Nullable Object> {}
              }
              static class OuterArgument {
                <U extends Outer<@Nullable String>.Inner<String>> OuterArgument(U value) {}
              }
              static class InnerArgument {
                <U extends Outer<String>.Inner<@Nullable String>> InnerArgument(U value) {}
              }
              static class InnerRoot {
                <U extends Outer<String>.@Nullable Inner<String>> InnerRoot(U value) {}
              }
              static class WildcardBound {
                <U extends List<? extends @Nullable String>> WildcardBound(U value) {}
              }
              void ordinaryFirst(Outer<@Nullable String>.Inner<String> outer,
                  Outer<String>.Inner<@Nullable String> inner, List<@Nullable String> elements) {
                new OuterArgument(outer);
                new OuterArgument(outer) {};
                new InnerArgument(inner);
                new InnerArgument(inner) {};
                new InnerRoot(null);
                new InnerRoot(null) {};
                new WildcardBound(elements);
                new WildcardBound(elements) {};
                // A WILDCARD-bound annotation does not annotate the surrounding List.
                // BUG: Diagnostic contains: @Nullable
                new WildcardBound(null);
                // BUG: Diagnostic contains: @Nullable
                new WildcardBound(null) {};
              }
              void anonymousFirst(Outer<@Nullable String>.Inner<String> outer,
                  Outer<String>.Inner<@Nullable String> inner, List<@Nullable String> elements) {
                new OuterArgument(outer) {};
                new OuterArgument(outer);
                new InnerArgument(inner) {};
                new InnerArgument(inner);
                new InnerRoot(null) {};
                new InnerRoot(null);
                new WildcardBound(elements) {};
                new WildcardBound(elements);
                // An outer or inner argument annotation does not annotate the Inner root.
                // BUG: Diagnostic contains: @Nullable
                new OuterArgument(null) {};
                // BUG: Diagnostic contains: @Nullable
                new OuterArgument(null);
                // BUG: Diagnostic contains: @Nullable
                new InnerArgument(null) {};
                // BUG: Diagnostic contains: @Nullable
                new InnerArgument(null);
              }
            }
            """)
        .doTest();
  }

  /** Creates a helper with the same JSpecify inference configuration as the other regressions. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
