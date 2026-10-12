package com.uber.nullaway.jspecify;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneJavaCompiler;
import com.google.errorprone.FileManagers;
import com.google.errorprone.FileObjects;
import com.google.errorprone.scanner.ScannerSupplier;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
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

  @Test
  public void ordinaryReferenceConflictsRetainBothSourcesInEitherOrder() {
    for (boolean reverse : List.of(false, true)) {
      String first = reverse ? "Test::clean" : "Test::nullable";
      String second = reverse ? "Test::nullable" : "Test::clean";
      List<String> diagnostics =
          compileDiagnostics(
              """
              package com.uber;
              import java.util.ArrayList;
              import java.util.LinkedList;
              import java.util.List;
              import java.util.function.Supplier;
              import org.jspecify.annotations.NullMarked;
              import org.jspecify.annotations.Nullable;
              @NullMarked
              class Test {
                static ArrayList<@Nullable String> nullable() { throw new RuntimeException(); }
                static LinkedList<String> clean() { throw new RuntimeException(); }
                static <T> T from(Supplier<? extends T> a, Supplier<? extends T> b) { return a.get(); }
                void test() {
                  List<String> result = from(
                      %s,
                      %s);
                }
              }
              """
                  .formatted(first, second));
      List<String> conflicts =
          diagnostics.stream()
              .filter(message -> message.contains("have conflicting nullability"))
              .toList();
      assertThat(conflicts).hasSize(1);
      assertThat(conflicts.get(0)).startsWith("16:");
      assertThat(conflicts.get(0)).contains("arguments '" + second + "'");
      assertThat(conflicts.get(0)).contains("and '" + first + "'");
      assertThat(conflicts.get(0)).contains("ArrayList<@Nullable String>");
      assertThat(conflicts.get(0)).contains("LinkedList<String>");
      assertThat(conflicts.get(0)).doesNotContain("lower source");
    }
  }

  @Test
  public void independentOuterConflictSurvivesUnavailableSupportAlternative() {
    for (boolean suppliers : List.of(false, true)) {
      for (boolean reverse : List.of(false, true)) {
        String firstInner = reverse ? "Test::clean" : "Test::nullable";
        String secondInner = reverse ? "Test::nullable" : "Test::clean";
        String nullableSource = suppliers ? "Test::nullable" : "nullable()";
        String cleanSource = suppliers ? "Test::clean" : "clean()";
        String firstOuter = reverse ? cleanSource : nullableSource;
        String secondOuter = reverse ? nullableSource : cleanSource;
        List<String> diagnostics =
            compileDiagnostics(
                """
                package com.uber;
                import java.util.ArrayList;
                import java.util.LinkedList;
                import java.util.List;
                import java.util.function.Supplier;
                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;
                @NullMarked
                class Test {
                  static ArrayList<@Nullable String> nullable() { throw new RuntimeException(); }
                  static LinkedList<String> clean() { throw new RuntimeException(); }
                  static <T> T from(Supplier<? extends T> a, Supplier<? extends T> b) { return a.get(); }
                  static <U> U from3(U a, Supplier<? extends U> b, Supplier<? extends U> c) { return a; }
                  static <U> U pick3(U a, U b, U c) { return a; }
                  void test() {
                    List<String> result = %s(
                        from(%s, %s),
                        %s,
                        %s);
                  }
                }
                """
                    .formatted(
                        suppliers ? "from3" : "pick3",
                        firstInner,
                        secondInner,
                        firstOuter,
                        secondOuter));
        List<String> conflicts =
            diagnostics.stream()
                .filter(message -> message.contains("have conflicting nullability"))
                .toList();
        assertThat(conflicts).hasSize(2);
        List<String> inner =
            conflicts.stream().filter(message -> message.contains("for type variable T")).toList();
        List<String> outer =
            conflicts.stream().filter(message -> message.contains("for type variable U")).toList();
        assertThat(inner).hasSize(1);
        assertThat(outer).hasSize(1);
        assertThat(inner.get(0)).startsWith("17:");
        assertThat(inner.get(0)).contains("arguments '" + secondInner + "'");
        assertThat(inner.get(0)).contains("and '" + firstInner + "'");
        assertThat(outer.get(0)).startsWith("19:");
        assertThat(outer.get(0)).contains("arguments '" + secondOuter + "'");
        assertThat(outer.get(0)).contains("and '" + firstOuter + "'");
        assertThat(outer.get(0)).contains("ArrayList<@Nullable String>");
        assertThat(outer.get(0)).contains("LinkedList<String>");
        assertThat(outer.get(0)).doesNotContain("from(");
        assertThat(outer.get(0)).doesNotContain("lower source");
      }
    }
  }

  @Test
  public void failedNestedResultDoesNotPublishTransitiveConflict() {
    List<String> diagnostics =
        compileDiagnostics(
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static ArrayList<@Nullable String> nullable() { throw new RuntimeException(); }
              static LinkedList<String> clean() { throw new RuntimeException(); }
              static <T> T from(Supplier<? extends T> a, Supplier<? extends T> b) { return a.get(); }
              static <R> R identity(R input) { return input; }
              void test() {
                List<String> nested = identity(from(Test::nullable, Test::clean));
                List<String> independent = from(Test::nullable, Test::clean);
              }
            }
            """);
    List<String> conflicts =
        diagnostics.stream()
            .filter(message -> message.contains("have conflicting nullability"))
            .toList();
    assertThat(conflicts).hasSize(2);
    for (String conflict : conflicts) {
      assertThat(conflict).contains("arguments 'Test::clean' (LinkedList<String>)");
      assertThat(conflict).contains("and 'Test::nullable' (ArrayList<@Nullable String>)");
      assertThat(conflict).contains("for type variable T");
    }
    assertThat(conflicts.stream().filter(message -> message.startsWith("15:")).toList()).hasSize(1);
    assertThat(conflicts.stream().filter(message -> message.startsWith("16:")).toList()).hasSize(1);
  }

  @Test
  public void ownedLambdaConflictsDoNotHideIndependentFinalChecks() {
    List<String> diagnostics =
        compileDiagnostics(
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import java.util.function.Function;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static ArrayList<@Nullable String> nullable() { throw new RuntimeException(); }
              static LinkedList<String> clean() { throw new RuntimeException(); }
              static <T> T from(Supplier<? extends T> a, Supplier<? extends T> b) { return a.get(); }
              static <A, R> R map(A value, Function<A, R> function) { return function.apply(value); }
              static <T> T id(T value) { return value; }
              void test() {
                List<String> result = map("input", value -> {
                  var copy = id(value);
                  return from(Test::nullable, Test::clean);
                });
                List<String> independent = id(nullable());
              }
            }
            """);
    List<String> conflicts =
        diagnostics.stream()
            .filter(message -> message.contains("have conflicting nullability"))
            .toList();
    assertThat(conflicts).hasSize(1);
    assertThat(conflicts.get(0)).contains("Test::nullable");
    assertThat(conflicts.get(0)).contains("Test::clean");
    assertThat(
            diagnostics.stream()
                .filter(
                    message -> message.startsWith("21:") && message.contains("incompatible types"))
                .toList())
        .hasSize(1);
  }

  @Test
  public void provisionalLambdaReferenceConflictsDoNotHideFinalChecks() {
    List<String> diagnostics =
        compileDiagnostics(
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import java.util.function.Function;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static ArrayList<@Nullable String> nullable() { throw new RuntimeException(); }
              static LinkedList<String> clean() { throw new RuntimeException(); }
              static <T> T from(Supplier<? extends T> a, Supplier<? extends T> b) { return a.get(); }
              static <A, R> R map(A value, Function<A, R> function) { return function.apply(value); }
              static <T> T id(T value) { return value; }
              void test() {
                List<String> result = map("input", value -> from(
                    Test::nullable,
                    Test::clean));
                List<String> independent = id(nullable());
              }
            }
            """);
    // The nested call participates in provisional lambda inference and is checked again against
    // the final descriptor. Neither the provisional pass nor diagnostic dedup may hide that check.
    List<String> conflicts =
        diagnostics.stream()
            .filter(message -> message.contains("have conflicting nullability"))
            .toList();
    assertThat(conflicts).hasSize(1);
    assertThat(conflicts.get(0)).startsWith("19:");
    assertThat(conflicts.get(0)).contains("arguments 'Test::clean' (LinkedList<String>)");
    assertThat(conflicts.get(0)).contains("and 'Test::nullable' (ArrayList<@Nullable String>)");
    assertThat(
            diagnostics.stream()
                .filter(
                    message -> message.startsWith("20:") && message.contains("incompatible types"))
                .toList())
        .hasSize(1);
  }

  /**
   * Compiles a frontend fixture and retains positioned diagnostics, including ordinary reference
   * checks alongside the source-conflict diagnostic. NullAway runs at warning severity, so compiler
   * errors still fail the test rather than being mistaken for inference diagnostics.
   */
  private List<String> compileDiagnostics(String source) {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<String> args =
        new ArrayList<>(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber")));
    args.add("-proc:none");
    ErrorProneJavaCompiler compiler =
        new ErrorProneJavaCompiler(ScannerSupplier.fromBugCheckerClasses(NullAway.class));
    boolean success =
        compiler
            .getTask(
                null,
                FileManagers.testFileManager(),
                diagnostics,
                args,
                null,
                List.of(FileObjects.forSourceLines("Test.java", source)))
            .call();
    assertWithMessage("compilation failed: %s", diagnostics.getDiagnostics())
        .that(success)
        .isTrue();
    return diagnostics.getDiagnostics().stream()
        .filter(
            diagnostic ->
                diagnostic.getSource() != null && diagnostic.getLineNumber() != Diagnostic.NOPOS)
        .map(diagnostic -> diagnostic.getLineNumber() + ":" + diagnostic.getMessage(Locale.ROOT))
        .toList();
  }

  /** Creates a helper for nullness-marked inference with wildcard containment enabled. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
