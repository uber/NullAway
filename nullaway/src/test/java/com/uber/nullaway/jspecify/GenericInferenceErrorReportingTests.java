package com.uber.nullaway.jspecify;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.FileObjects;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.Test;

/**
 * Tests the diagnostics NullAway reports for generic inference: which ones, how many, and where.
 */
public class GenericInferenceErrorReportingTests extends NullAwayTestsBase {

  /**
   * Tests that the error message we get at a call to Arrays.copyOf does not erroneously suggest we
   * are trying to infer the nullability of a type variable from the caller method
   */
  @Test
  public void issue1821DoNotTreatCallerTypeVariableAsAnInferenceVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Arrays;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              public static <E> E @Nullable [] copyOfConditionalExpression(E @Nullable [] original) {
                return original == null
                    ? null
                    // NOTE: this does not typecheck because the JSpecify JDK model of Arrays.copyOf annotates its
                    // return type as @Nullable T[] (where T is copyOf's type variable), which is incompatible with the
                    // return type E @Nullable [] of the enclosing method.  Arrays.copyOf returns an explicit @Nullable T[]
                    // since it will pad the array with `null` whenever the passed new length is greater
                    // than original.length (the example below is safe, but NullAway / JSpecify do not reason
                    // about integer constraints)
                    // BUG: Diagnostic contains: Conditional expression must have type E @Nullable [] but the sub-expression has type @Nullable E []
                    : Arrays.copyOf(original, original.length);
              }
              public static <E> E @Nullable [] copyOfDirectReturn(E [] original) {
                // NOTE: this does not typecheck because the JSpecify JDK model of Arrays.copyOf annotates its
                // return type as @Nullable T[] (where T is copyOf's type variable), which is incompatible with the
                // return type E @Nullable [] of the enclosing method.  Arrays.copyOf returns an explicit @Nullable T[]
                // since it will pad the array with `null` whenever the passed new length is greater
                // than original.length (the example below is safe, but NullAway / JSpecify do not reason
                // about integer constraints)
                // BUG: Diagnostic contains: incompatible types: @Nullable E [] cannot be converted to E @Nullable []
                return Arrays.copyOf(original, original.length);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedScalarFailurePointsAtTheConstrainedCall() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <X extends @Nullable Object> X id(X value) { return value; }
              static <U extends @Nullable Object> List<U> one(U value) { throw new RuntimeException(); }
              static void takeList(List<String> values) {}
              void test(@Nullable String value) {
                takeList(id(
                    id(id(id(id(id(
                        one(value)
                    )))))
                ));
              }
            }
            """);

    assertThat(
            compileAndReportDiagnostics(List.of(callerSource)).stream()
                .filter(diagnostic -> diagnostic.contains("inference failure:"))
                .toList())
        .containsExactly(
            "Caller.java:13: [NullAway] inference failure: type variable U constrained to be both"
                + " @NonNull and @Nullable");
  }

  @Test
  public void distinctLowerConflictReportsSourceArgumentsOnceInEitherOrder() {
    checkDistinctLowerConflict(false);
    checkDistinctLowerConflict(true);
  }

  /** Checks one source-level conflict at the later argument, without result or bound cascades. */
  private void checkDistinctLowerConflict(boolean reverse) {
    String first = reverse ? "b" : "a";
    String second = reverse ? "a" : "b";
    String firstType = reverse ? "LinkedList<String>" : "ArrayList<@Nullable String>";
    String secondType = reverse ? "ArrayList<@Nullable String>" : "LinkedList<String>";
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <X extends @Nullable Object> X pick(X a, X b) { return a; }
              void test(ArrayList<@Nullable String> a, LinkedList<String> b) {
                List<String> result = pick(
                    %s,
                    %s);
              }
            }
            """
                .formatted(first, second));
    assertThat(compileAndReportDiagnostics(List.of(source)))
        .containsExactly(
            "Caller.java:13: [NullAway] incompatible types: arguments '"
                + second
                + "' ("
                + secondType
                + ") and '"
                + first
                + "' ("
                + firstType
                + ") have conflicting nullability for type variable X");
  }

  @Test
  public void threeDistinctLowerSourcesReportOneSourceLevelConflict() {
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import java.util.Vector;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <X extends @Nullable Object> X pick3(X a, X b, X c) { return a; }
              void test(ArrayList<String> a, LinkedList<String> b, Vector<@Nullable String> c) {
                List<String> result = pick3(
                    a,
                    b,
                    c);
              }
            }
            """);
    assertThat(compileAndReportDiagnostics(List.of(source)))
        .containsExactly(
            "Caller.java:15: [NullAway] incompatible types: arguments 'c' (Vector<@Nullable String>)"
                + " and 'a' (ArrayList<String>) have conflicting nullability for type variable X");
  }

  @Test
  public void lowerProvenanceDistinguishesAnnotationVariantsOfTheSameClass() {
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <X extends @Nullable Object> X pick3(X a, X b, X c) { return a; }
              void test(ArrayList<String> a, ArrayList<@Nullable String> b, LinkedList<String> c) {
                List<String> result = pick3(
                    a,
                    b,
                    c);
              }
            }
            """);
    assertThat(compileAndReportDiagnostics(List.of(source)))
        .containsExactly(
            "Caller.java:14: [NullAway] incompatible types: arguments 'c' (LinkedList<String>)"
                + " and 'b' (ArrayList<@Nullable String>) have conflicting nullability for type"
                + " variable X");
  }

  @Test
  public void lowerConflictDoesNotHideFixedAnnotationsOnArrayResultDimensions() {
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <X extends @Nullable Object> X[] @Nullable [] pickArrays(X a, X b) {
                throw new RuntimeException();
              }
              void test(ArrayList<@Nullable String> a, LinkedList<String> b) {
                List<String>[][] result = pickArrays(a, b);
              }
            }
            """);
    List<String> diagnostics = compileAndReportDiagnostics(List.of(source));
    assertThat(diagnostics).hasSize(2);
    assertThat(diagnostics.stream().filter(message -> message.contains("arguments 'b'")).toList())
        .hasSize(1);
    assertThat(
            diagnostics.stream()
                .filter(message -> message.contains("cannot be converted"))
                .toList())
        .hasSize(1);
  }

  @Test
  public void lowerConflictDoesNotHideDefaultNonNullArrayDimensionContracts() {
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <X extends @Nullable Object> void mixed(X a, X b, X[][] other) {}
              void test(ArrayList<@Nullable String> a, LinkedList<String> b,
                  List<String>[] @Nullable [] other) {
                mixed(a, b, other);
              }
            }
            """);
    List<String> diagnostics = compileAndReportDiagnostics(List.of(source));
    assertThat(diagnostics).hasSize(2);
    assertThat(diagnostics.stream().filter(message -> message.contains("arguments 'b'")).toList())
        .hasSize(1);
    assertThat(
            diagnostics.stream()
                .filter(message -> message.contains("cannot be converted"))
                .toList())
        .hasSize(1);
  }

  @Test
  public void lowerConflictDoesNotHideAnIndependentParameterMismatch() {
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <X extends @Nullable Object> void mixed(X a, X b, List<String> other) {}
              void test(ArrayList<@Nullable String> a, LinkedList<String> b,
                  List<@Nullable String> other) {
                mixed(
                    a,
                    b,
                    other);
              }
            }
            """);
    assertThat(compileAndReportDiagnostics(List.of(source)))
        .containsExactly(
            "Caller.java:14: [NullAway] incompatible types: arguments 'b' (LinkedList<String>)"
                + " and 'a' (ArrayList<@Nullable String>) have conflicting nullability for type"
                + " variable X",
            "Caller.java:15: [NullAway] incompatible types: List<@Nullable String> cannot be"
                + " converted to List<String>");
  }

  /**
   * Source compiled before each call site, padded so a misattributed diagnostic lands on an
   * existing line and cannot be dropped by the test framework.
   */
  private static final String[] PADDED_FIRST_COMPILATION_UNIT = {
    """
    package com.uber;
    // padding so that a misattributed diagnostic lands on an existing line (1)
    // padding so that a misattributed diagnostic lands on an existing line (2)
    // padding so that a misattributed diagnostic lands on an existing line (3)
    // padding so that a misattributed diagnostic lands on an existing line (4)
    // padding so that a misattributed diagnostic lands on an existing line (5)
    // padding so that a misattributed diagnostic lands on an existing line (6)
    // padding so that a misattributed diagnostic lands on an existing line (7)
    // padding so that a misattributed diagnostic lands on an existing line (8)
    // padding so that a misattributed diagnostic lands on an existing line (9)
    // padding so that a misattributed diagnostic lands on an existing line (10)
    // padding so that a misattributed diagnostic lands on an existing line (11)
    // padding so that a misattributed diagnostic lands on an existing line (12)
    // padding so that a misattributed diagnostic lands on an existing line (13)
    // padding so that a misattributed diagnostic lands on an existing line (14)
    // padding so that a misattributed diagnostic lands on an existing line (15)
    // padding so that a misattributed diagnostic lands on an existing line (16)
    // padding so that a misattributed diagnostic lands on an existing line (17)
    // padding so that a misattributed diagnostic lands on an existing line (18)
    // padding so that a misattributed diagnostic lands on an existing line (19)
    // padding so that a misattributed diagnostic lands on an existing line (20)
    class Unrelated {}
    """
  };

  /**
   * NullAway must report an inference failure in the file holding the call, not in the compilation
   * unit that happened to create the dataflow analysis; see
   * https://github.com/uber/NullAway/issues/1725.
   */
  @Test
  public void inferenceFailureReportedInFileWithCall() {
    makeHelper()
        .addSourceLines("Unrelated.java", PADDED_FIRST_COMPILATION_UNIT)
        .addSourceLines(
            "Caller.java",
            """
            package com.uber;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Caller {
              <T> T call(Supplier<T> s) {
                return s.get();
              }
              void f() {
                call(() -> { return ""; });
                // BUG: Diagnostic contains: inference failure: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
                call(() -> { return null; });
              }
            }
            """)
        .doTest();
  }

  /**
   * For this failure, only dataflow raises the diagnostic; no other pass reports it, so a
   * misattributed diagnostic would be the whole NullAway output of the build. See
   * https://github.com/uber/NullAway/issues/1733.
   */
  @Test
  public void inferenceFailureFromDataflowReportedInFileWithCall() {
    makeHelper()
        .addSourceLines("Unrelated.java", PADDED_FIRST_COMPILATION_UNIT)
        .addSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              interface Visitor<R extends @Nullable Object> {
                R visit(Node n);
              }
              interface Node {
                <R extends @Nullable Object> R accept(Visitor<R> v);
              }
              static class NullableVoidVisitor implements Visitor<@Nullable Void> {
                // the override narrows the return type to @NonNull Void, so R cannot be inferred
                @Override
                public Void visit(Node n) {
                  // BUG: Diagnostic contains: inference failure: type variable R constrained to be both @NonNull and @Nullable
                  return n.accept(this);
                }
              }
            }
            """)
        .doTest();
  }

  /**
   * Same as {@link #inferenceFailureFromDataflowReportedInFileWithCall()}, for the transfer
   * function of the contract-checking analysis, which is a second instance carrying its own {@link
   * com.google.errorprone.VisitorState} and needs the same update.
   */
  @Test
  public void inferenceFailureFromContractDataflowReportedInFileWithCall() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines("Unrelated.java", PADDED_FIRST_COMPILATION_UNIT)
        .addSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jetbrains.annotations.Contract;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              interface Visitor<R extends @Nullable Object> {
                R visit(Node n);
              }
              interface Node {
                <R extends @Nullable Object> R accept(Visitor<R> v);
              }
              static class NullableVoidVisitor implements Visitor<@Nullable Void> {
                @Override
                public Void visit(Node n) {
                  // BUG: Diagnostic contains: inference failure: type variable R constrained to be both @NonNull and @Nullable
                  return n.accept(this);
                }
              }
              // the contract makes NullAway run its contract-checking dataflow over this method
              @Contract("_, _ -> !null")
              static Void pick(Node n, NullableVoidVisitor v) {
                // BUG: Diagnostic contains: inference failure: type variable R constrained to be both @NonNull and @Nullable
                return n.accept(v);
              }
            }
            """)
        .doTest();
  }

  /** Generic inference failures should be reported once and attributed to the correct file. */
  @Test
  public void inferenceFailureReportedOnceInCorrectFile() {
    JavaFileObject unrelatedSource =
        FileObjects.forSourceLines(
            "File1.java",
            """
            package com.uber;
            class File1 {}
            """);
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "File2.java",
            """
            package com.uber;
            @org.jspecify.annotations.NullMarked
            class File2 {
              <T> T call(java.util.function.Supplier<T> s) { return s.get(); }
              void f() {
                call(() -> { return ""; });
                call(() -> { return null; });
              }
            }
            """);
    assertThat(
            compileAndReportDiagnostics(List.of(unrelatedSource, callerSource)).stream()
                .filter(diagnostic -> diagnostic.contains("inference failure: type variable T"))
                .toList())
        .containsExactly(
            "File2.java:7: [NullAway] inference failure: type variable T is constrained to be @Nullable, "
                + "but its upper bound requires it to be @NonNull");
  }

  @Test
  public void scalarInferenceFailureInsideImplicitLambdaReportedOnce() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
                R apply(P value);
              }
              static <P extends @Nullable Object, R extends @Nullable Object> R map(
                  P value, Mapper<P, R> mapper) {
                throw new UnsupportedOperationException();
              }
              static <T> T nonNullId(T value) { return value; }
              static void test() {
                map("value", x -> nonNullId(null));
              }
            }
            """);

    List<String> diagnostics = compileAndReportDiagnostics(List.of(callerSource));
    assertThat(
            diagnostics.stream()
                .filter(diagnostic -> diagnostic.contains("inference failure:"))
                .toList())
        .hasSize(1);
    assertThat(
            diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.contains("passing @Nullable parameter")))
        .isTrue();
  }

  @Test
  public void scalarInferenceFailureInsideImplicitLambdaSuppressed() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
                R apply(P value);
              }
              static <P extends @Nullable Object, R extends @Nullable Object> R map(
                  P value, Mapper<P, R> mapper) {
                throw new UnsupportedOperationException();
              }
              static <T> T nonNullId(T value) { return value; }
              @SuppressWarnings("NullAway")
              static void test() {
                map("value", x -> nonNullId(null));
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(callerSource))).isEmpty();
  }

  /**
   * Fails when an explicit {@code @Nullable} annotation on a type-variable use constrains the
   * underlying inference variable, which makes NullAway report one mismatch twice: as an inference
   * failure and again as the ordinary compatibility diagnostic. NullAway must report the
   * compatibility diagnostic alone; see https://github.com/uber/NullAway/issues/1730.
   *
   * <p>This has to assert the whole set of diagnostics, so it cannot live in {@link
   * GenericMethodTests}: {@code CompilationTestHelper} accepts a line whose {@code // BUG:
   * Diagnostic contains:} comment matches one diagnostic even when the line carries another. The
   * source below therefore carries every shape from that issue whose line still expects a
   * diagnostic. Both annotation checks in {@code
   * ConstraintSolverImpl.treatAsTypeVariableForInference} are reached, by an explicit
   * {@code @Nullable} on a use and an explicit {@code @NonNull} on a parameter. The call with an
   * explicit type witness reaches neither, because a witness skips inference; it is here as one of
   * the issue's shapes, and it guards against such a call acquiring an inference failure of its
   * own. A shape whose line expects nothing needs no help, since {@code CompilationTestHelper}
   * rejects any diagnostic there.
   */
  @Test
  public void annotatedTypeVariableUseIsNotAnInferenceFailure() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static <T extends @Nullable Object> @Nullable T id(T value) {
                return value;
              }
              static <T extends @Nullable Object> T nonNullParamId(@NonNull T value) {
                return value;
              }
              static String nonNullTarget() {
                return id("");
              }
              static String witness() {
                return Caller.<String>id("");
              }
              static String nullableArgument(@Nullable String value) {
                return id(value);
              }
              static void nonNullParameter(@Nullable String value) {
                nonNullParamId(value);
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(callerSource)))
        .containsExactly(
            "Caller.java:14: [NullAway] returning @Nullable expression from method with @NonNull"
                + " return type",
            "Caller.java:17: [NullAway] returning @Nullable expression from method with @NonNull"
                + " return type",
            "Caller.java:20: [NullAway] returning @Nullable expression from method with @NonNull"
                + " return type",
            "Caller.java:23: [NullAway] passing @Nullable parameter 'value' where @NonNull is"
                + " required");
  }

  /** Nested declaration-bound violations are reported once as ordinary incompatibilities. */
  @Test
  public void nestedDeclarationBoundViolationReportedOnce() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static class Box<T extends @Nullable Object> {}
              static class Bad extends Box<@Nullable String> {}
              static <T extends Box<String>> T boundedId(T value) { return value; }
              static void test(Box<@Nullable String> direct, Bad bad) {
                boundedId(direct);
                boundedId(bad);
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(callerSource)))
        .containsExactly(
            "Caller.java:10: [NullAway] incompatible types: Box<@Nullable String> cannot be"
                + " converted to Box<String>",
            "Caller.java:11: [NullAway] incompatible types: Bad cannot be converted to Box<String>"
                + " (Bad is a subtype of Box<@Nullable String>)");
  }

  /**
   * Characterizes a pre-existing false-positive limitation for mixed invariant lower bounds. The
   * unconstrained type variable T could be inferred as Object, making both calls valid. The current
   * incompatibilities are not correct full inference; retain their exact diagnostics here without
   * treating them as desired behavior or allowing additional inference-failure diagnostics.
   */
  @Test
  public void knownLimitationMixedInvariantLowerBoundsReportFalsePositiveIncompatibilities() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> void consume(T first, T second) {}
              static void test(Box<@Nullable String> nullable, Box<String> nonNull) {
                consume(nullable, nonNull);
                consume(nonNull, nullable);
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(callerSource)))
        .containsExactly(
            "Caller.java:9: [NullAway] incompatible types: Box<String> cannot be converted to"
                + " Box<@Nullable String>",
            "Caller.java:10: [NullAway] incompatible types: Box<@Nullable String> cannot be"
                + " converted to Box<String>");
  }

  /**
   * Fails when {@link #compileAndReportDiagnostics} reports a diagnostic that names no line in the
   * compiled source. javac emits a summary note against a file rather than against no file at all,
   * so a filter that only asks for a source lets the note through, and the expected set of any test
   * whose source triggers one has to carry it. The source below compiles without a NullAway
   * diagnostic and produces two such notes.
   */
  @Test
  public void summaryNotesAreNotReported() {
    JavaFileObject uncheckedSource =
        FileObjects.forSourceLines(
            "Unchecked.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.List;
            @org.jspecify.annotations.NullMarked
            class Unchecked {
              @SuppressWarnings("rawtypes")
              static List raw() {
                return new ArrayList();
              }
              static void use() {
                List<String> typed = raw();
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(uncheckedSource))).isEmpty();
  }

  @Test
  public void compilerErrorsAreNotReportedAsInferenceDiagnostics() {
    JavaFileObject invalidSource =
        FileObjects.forSourceLines("Invalid.java", "class Invalid { MissingType value; }");
    List<JavaFileObject> sources = List.of(invalidSource);
    assertThrows(AssertionError.class, () -> compileAndCollectJSpecifyDiagnostics(sources));
  }

  /**
   * Compiles {@code sources} with NullAway in JSpecify mode and reports every diagnostic that names
   * a position in them, as {@code <file>:<line>: <message>}. A caller asserts the returned list,
   * whole or narrowed to the diagnostics its own name covers, so one that appears, disappears,
   * moves to another line, or changes its wording fails the test. Severity is not part of the
   * filter, since a check reported below warning level is still a diagnostic about the source.
   *
   * <p>A diagnostic is reported only when it names both a file and a line, which is what a reader
   * of the source can locate. That leaves out javac's {@code [options]} warnings, which name no
   * file, and its summary notes such as "uses unchecked or unsafe operations", which name a file at
   * {@link Diagnostic#NOPOS}. Dropping those loses no error, because compilation has to succeed
   * anyway: NullAway reports at warning severity here, so a compile error means the test source
   * itself is broken.
   *
   * @param sources the compilation units to compile
   * @return the rendered diagnostics, in the order javac emitted them
   */
  private List<String> compileAndReportDiagnostics(List<JavaFileObject> sources) {
    return compileAndCollectJSpecifyDiagnostics(sources).stream()
        .map(GenericInferenceErrorReportingTests::render)
        .toList();
  }

  /**
   * Renders {@code diagnostic} as {@code <file>:<line>: <message>}, on one line. The directory the
   * test file manager invented is dropped, as is the "see" line Error Prone appends to every
   * NullAway diagnostic, so the result holds what a reader of the source would recognize. Both
   * separators end a directory here: {@link javax.tools.FileObject#getName()} promises only a
   * user-friendly name, and a file manager is free to hand back a platform path.
   *
   * @param diagnostic the diagnostic to render
   * @return the rendered diagnostic
   */
  private static String render(Diagnostic<? extends JavaFileObject> diagnostic) {
    String path = diagnostic.getSource().getName();
    String fileName = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    String message =
        Arrays.stream(diagnostic.getMessage(Locale.ROOT).split("\\R"))
            .map(String::trim)
            .filter(line -> !line.startsWith("(see "))
            .collect(Collectors.joining(" "));
    return fileName + ":" + diagnostic.getLineNumber() + ": " + message;
  }

  /** Each independent nested bound violation gets exactly one diagnostic on its own call line. */
  @Test
  public void independentNestedDeclarationBoundViolationsReportedOnceOnDistinctLines() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static class Box<E extends @Nullable Object> {}
              static class Bad extends Box<@Nullable String> {}
              static class Good extends Box<String> {}
              static <T extends Box<String>> T boundedId(T value) { return value; }
              static <A extends @Nullable Object, B extends @Nullable Object> void pair(
                  A first, B second) {}
              static void test(Bad firstBad, Bad secondBad, Good good) {
                pair(
                    boundedId(firstBad),
                    boundedId(secondBad));
                pair(boundedId(good), boundedId(good));
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(callerSource)))
        .containsExactly(
            "Caller.java:14: [NullAway] incompatible types: Bad cannot be converted to Box<String>"
                + " (Bad is a subtype of Box<@Nullable String>)",
            "Caller.java:15: [NullAway] incompatible types: Bad cannot be converted to Box<String>"
                + " (Bad is a subtype of Box<@Nullable String>)");
  }

  @Test
  public void nestedDeclarationBoundViolationInsideLazyInferenceLocallySuppressed() {
    JavaFileObject callerSource =
        FileObjects.forSourceLines(
            "Caller.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Caller {
              static class Box<T extends @Nullable Object> {}
              static class Bad extends Box<@Nullable String> {}
              interface Factory<R extends @Nullable Object> {
                R get();
              }
              static <R extends @Nullable Object> R supply(Factory<R> factory) {
                return factory.get();
              }
              static <T extends Box<String>> T boundedId(T value) { return value; }
              @NullMarked
              static void test(Bad bad) {
                supply(() -> {
                  @SuppressWarnings("NullAway") var result = boundedId(bad);
                  return result;
                });
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(callerSource))).isEmpty();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
