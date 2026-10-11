package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Regression coverage for wildcard-shaped lowers and sibling diamond inference ownership. */
public class InferenceLowerBoundRegressionTests extends NullAwayTestsBase {

  @Test
  public void collectionWildcardShapeAcceptsConcreteAndWildcardLowersInBothOrders() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.List;
            import java.util.Set;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T> T pick(T a, T b) { return a; }
              void test(List<? extends @Nullable String> nullable, Set<String> nonNull,
                  List<? extends String> nonNullList, Set<@Nullable String> nullableSet) {
                Collection<? extends @Nullable String> forward = pick(nullable, nonNull);
                Collection<? extends @Nullable String> reverse = pick(nonNull, nullable);
                Collection<? extends @Nullable String> concreteForward = pick(nonNullList, nullableSet);
                Collection<? extends @Nullable String> concreteReverse = pick(nullableSet, nonNullList);
                Collection<? extends String> cleanForward = pick(nonNullList, nonNull);
                Collection<? extends String> cleanReverse = pick(nonNull, nonNullList);
                cleanForward.iterator().next().length();
                cleanReverse.iterator().next().length();
                // BUG: Diagnostic contains: dereferenced expression
                forward.iterator().next().length();
                // BUG: Diagnostic contains: dereferenced expression
                reverse.iterator().next().length();
                // BUG: Diagnostic contains: dereferenced expression
                concreteForward.iterator().next().length();
                // BUG: Diagnostic contains: dereferenced expression
                concreteReverse.iterator().next().length();
                // BUG: Diagnostic contains: incompatible types
                Collection<? extends String> invalidForward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                Collection<? extends String> invalidReverse = pick(nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void extendsBoundsMergeCovariantlyInBothOrders() {
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
              static <T> T pick(T a, T b) { return a; }
              void test(ArrayList<? extends @Nullable String> nullable,
                  LinkedList<? extends String> nonNull,
                  ArrayList<? extends String> clean) {
                List<? extends @Nullable String> forward = pick(nullable, nonNull);
                List<? extends @Nullable String> reverse = pick(nonNull, nullable);
                List<? extends String> cleanForward = pick(clean, nonNull);
                List<? extends String> cleanReverse = pick(nonNull, clean);
                cleanForward.get(0).length();
                cleanReverse.get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                forward.get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                reverse.get(0).length();
                // BUG: Diagnostic contains: incompatible types
                List<? extends String> invalidForward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                List<? extends String> invalidReverse = pick(nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void superBoundsMergeContravariantlyInBothOrders() {
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
              static <T> T pick(T a, T b) { return a; }
              void test(ArrayList<? super @Nullable String> nullable,
                  LinkedList<? super String> nonNull,
                  LinkedList<? super @Nullable String> alsoNullable) {
                List<? super String> forward = pick(nullable, nonNull);
                List<? super String> reverse = pick(nonNull, nullable);
                forward.add("x");
                reverse.add("x");
                // BUG: Diagnostic contains: passing @Nullable parameter
                forward.add(null);
                // BUG: Diagnostic contains: passing @Nullable parameter
                reverse.add(null);
                List<? super @Nullable String> cleanForward = pick(nullable, alsoNullable);
                List<? super @Nullable String> cleanReverse = pick(alsoNullable, nullable);
                cleanForward.add(null);
                cleanReverse.add(null);
                // BUG: Diagnostic contains: incompatible types
                List<? super @Nullable String> invalidForward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                List<? super @Nullable String> invalidReverse = pick(nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedWildcardBoundsRetainVarianceAndConcreteSlotsRetainInvariance() {
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
              static <T> T pick(T a, T b) { return a; }
              void nested(ArrayList<? extends List<? extends @Nullable String>> nullable,
                  LinkedList<? extends List<? extends String>> nonNull) {
                List<? extends List<? extends @Nullable String>> forward = pick(nullable, nonNull);
                List<? extends List<? extends @Nullable String>> reverse = pick(nonNull, nullable);
                // BUG: Diagnostic contains: dereferenced expression
                forward.get(0).get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                reverse.get(0).get(0).length();
                // BUG: Diagnostic contains: incompatible types
                List<? extends List<? extends String>> invalidForward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                List<? extends List<? extends String>> invalidReverse = pick(nonNull, nullable);
              }
              void invariantInsideWildcard(ArrayList<? extends List<@Nullable String>> nullable,
                  LinkedList<? extends List<String>> nonNull) {
                // BUG: Diagnostic contains: incompatible types
                List<? extends List<@Nullable String>> forward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                List<? extends List<@Nullable String>> reverse = pick(nonNull, nullable);
              }
              void invariantOutsideWildcard(
                  ArrayList<List<? extends @Nullable String>> nullable,
                  LinkedList<List<? extends String>> nonNull) {
                // BUG: Diagnostic contains: incompatible types
                List<List<? extends @Nullable String>> forward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                List<List<? extends @Nullable String>> reverse = pick(nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void siblingDiamondSlotsAreConstrainedBeforeTheyDefault() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T> T pick(T a, T b) { return a; }
              static <T> T id(T value) { return value; }
              void test(List<@Nullable String> ln, List<String> clean) {
                List<@Nullable String> b = pick(ln, new ArrayList<>());
                List<@Nullable String> reverse = pick(new ArrayList<>(), ln);
                List<@Nullable String> nestedForward = pick(id(ln), new ArrayList<>());
                List<@Nullable String> nestedReverse = pick(new ArrayList<>(), id(ln));
                List<@Nullable String> twoNested = pick(id(ln), id(new ArrayList<>()));
                List<@Nullable String> twoNestedReverse = pick(id(new ArrayList<>()), id(ln));
                List<String> cleanForward = pick(clean, new ArrayList<>());
                List<String> cleanReverse = pick(new ArrayList<>(), clean);
                cleanForward.get(0).length();
                cleanReverse.get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                b.get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                reverse.get(0).length();
                // BUG: Diagnostic contains: incompatible types
                List<String> invalidForward = pick(ln, new ArrayList<>());
                // BUG: Diagnostic contains: incompatible types
                List<String> invalidReverse = pick(new ArrayList<>(), ln);
                // BUG: Diagnostic contains: incompatible types
                List<@Nullable String> explicitForward = pick(ln, new ArrayList<String>());
                // BUG: Diagnostic contains: incompatible types
                List<@Nullable String> explicitReverse = pick(new ArrayList<String>(), ln);
              }
              void nestedSlots(List<List<@Nullable String>> lists) {
                List<List<@Nullable String>> forward = pick(lists, new ArrayList<>());
                List<List<@Nullable String>> reverse = pick(new ArrayList<>(), lists);
                // BUG: Diagnostic contains: dereferenced expression
                forward.get(0).get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                reverse.get(0).get(0).length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void threeLowersMergeWildcardsAndConstrainDiamondsInEveryPosition() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import java.util.Vector;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T> T pick(T a, T b, T c) { return a; }
              void extendsBounds(ArrayList<? extends @Nullable String> a,
                  LinkedList<? extends String> b, Vector<? extends String> c) {
                List<? extends @Nullable String> abc = pick(a, b, c);
                List<? extends @Nullable String> acb = pick(a, c, b);
                List<? extends @Nullable String> bac = pick(b, a, c);
                List<? extends @Nullable String> bca = pick(b, c, a);
                List<? extends @Nullable String> cab = pick(c, a, b);
                List<? extends @Nullable String> cba = pick(c, b, a);
                // BUG: Diagnostic contains: incompatible types
                List<? extends String> invalid = pick(b, c, a);
              }
              void superBounds(ArrayList<? super @Nullable String> a,
                  LinkedList<? super @Nullable String> b, Vector<? super String> c) {
                List<? super String> abc = pick(a, b, c);
                List<? super String> acb = pick(a, c, b);
                List<? super String> bac = pick(b, a, c);
                List<? super String> bca = pick(b, c, a);
                List<? super String> cab = pick(c, a, b);
                List<? super String> cba = pick(c, b, a);
                // BUG: Diagnostic contains: incompatible types
                List<? super @Nullable String> invalid = pick(a, b, c);
              }
              void diamond(List<@Nullable String> ln) {
                List<@Nullable String> first = pick(ln, new ArrayList<>(), new LinkedList<>());
                List<@Nullable String> middle = pick(new ArrayList<>(), ln, new LinkedList<>());
                List<@Nullable String> last = pick(new ArrayList<>(), new LinkedList<>(), ln);
                // BUG: Diagnostic contains: incompatible types
                List<String> invalid = pick(new ArrayList<>(), new LinkedList<>(), ln);
              }
              void thirdInvariantConflict(ArrayList<? extends List<@Nullable String>> a,
                  LinkedList<? extends List<@Nullable String>> b,
                  Vector<? extends List<String>> c) {
                // BUG: Diagnostic contains: incompatible types
                List<? extends List<@Nullable String>> forward = pick(a, b, c);
                // BUG: Diagnostic contains: incompatible types
                List<? extends List<@Nullable String>> reverse = pick(c, b, a);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void superBoundMeetsRecurseThroughNestedWildcardsAndArrayDimensions() {
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
              static <T> T pick(T a, T b) { return a; }
              void arrays(ArrayList<? super @Nullable String[][]> elements,
                  LinkedList<? super String[] @Nullable []> arrays) {
                List<? super String[][]> forward = pick(elements, arrays);
                List<? super String[][]> reverse = pick(arrays, elements);
                forward.add(new String[0][0]);
                reverse.add(new String[0][0]);
                // BUG: Diagnostic contains: incompatible types
                List<? super @Nullable String[][]> invalidForward = pick(elements, arrays);
                // BUG: Diagnostic contains: incompatible types
                List<? super String[] @Nullable []> invalidReverse = pick(arrays, elements);
              }
              void nestedExtends(ArrayList<? super List<? extends @Nullable String>> nullable,
                  LinkedList<? super List<? extends String>> nonNull) {
                List<? super List<? extends String>> forward = pick(nullable, nonNull);
                List<? super List<? extends String>> reverse = pick(nonNull, nullable);
                // BUG: Diagnostic contains: incompatible types
                List<? super List<? extends @Nullable String>> invalidForward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                List<? super List<? extends @Nullable String>> invalidReverse = pick(nonNull, nullable);
              }
              void nestedSuper(ArrayList<? super List<? super @Nullable String>> nullable,
                  LinkedList<? super List<? super String>> nonNull) {
                List<? super List<? super @Nullable String>> forward = pick(nullable, nonNull);
                List<? super List<? super @Nullable String>> reverse = pick(nonNull, nullable);
                // BUG: Diagnostic contains: incompatible types
                List<? super List<? super String>> invalidForward = pick(nullable, nonNull);
                // BUG: Diagnostic contains: incompatible types
                List<? super List<? super String>> invalidReverse = pick(nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  /** Creates a helper for nullness-marked inference with wildcard containment enabled. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
