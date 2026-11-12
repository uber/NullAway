package com.uber.nullaway.jspecify;

import static com.google.common.truth.Truth.assertThat;

import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.FileObjects;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
   * diagnostic. Both annotation checks in {@code ConstraintSolverImpl.inferenceVariableForUse} are
   * reached, by an explicit {@code @Nullable} on a use and an explicit {@code @NonNull} on a
   * parameter. The call with an explicit type witness reaches neither, because a witness skips
   * inference; it is here as one of the issue's shapes, and it guards against such a call acquiring
   * an inference failure of its own. A shape whose line expects nothing needs no help, since {@code
   * CompilationTestHelper} rejects any diagnostic there.
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

  @Test
  public void methodReferenceInferenceFailuresAreReportedOnce() {
    assertThat(
            compileAndReportDiagnostics(
                List.of(methodReferenceSource()),
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true"))
        .containsExactly(
            "Test.java:29: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:30: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:31: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:32: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:34: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:37: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:40: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:43: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:45: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:46: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:47: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:48: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:49: [NullAway] parameter extra of referenced method is @NonNull, but"
                + " parameter in functional interface method"
                + " com.uber.Test.UnmarkedFn.apply(List<@Nullable String>,@Nullable Object) is"
                + " @Nullable");
  }

  /**
   * Checks that method-reference inference failures preserve independent parameter and return
   * compatibility errors. Asserting the complete diagnostic set also catches duplicate warnings,
   * including a warning at the reference when inference failed at the enclosing generic call.
   */
  @Test
  public void methodReferenceInferenceFailuresPreserveIndependentCompatibilityErrors() {
    JavaFileObject source =
        FileObjects.forSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.*;
            @NullMarked class Test {
              // Setup: functional interfaces and methods used by the warning checks below.
              interface Fn<A extends @Nullable Object, R extends @Nullable Object> { R apply(A a); }
              interface BiFn<A extends @Nullable Object, B extends @Nullable Object> { void apply(A a, B b); }
              static <T> @Nullable String nul(T t) { return null; }
              static <T extends @Nullable Object> @Nullable List<T> wrap(T t) { return null; }
              static <T> T nonNullId(T value) { return value; }
              static <T> void consume(T value, String extra) {}
              static <U> void pair(Fn<String, String> f, U u) {}
              static <U> void cachedTop(Fn<@Nullable String, @Nullable String> f, U w) {}
              static class Receiver<C> {
                <T> void consume(C value, List<T> values) {}
              }

              // Warning checks: each method below is a case whose diagnostics are asserted exactly.
              void unrelatedArgumentFailure() {
                pair(Test::nul, null); // 3 warnings: U inference failure, nullable argument, nullable return.
              }
              void unrelatedArgumentControl() {
                pair(Test::nul, "x"); // 1 warning: nullable return.
              }
              void nestedReturnFailure() {
                Fn<@Nullable String, List<String>> f = Test::wrap; // 2 warnings: T inference failure, nullable return.
              }
              void nestedReturnControl() {
                Fn<String, List<String>> f = Test::wrap; // 1 warning: nullable return.
              }
              void cachedTopLevelFailure() {
                cachedTop( // 1 warning: T inference failure, reported at the enclosing call.
                    Test::nonNullId, // 0 warnings: the duplicate parameter mismatch is suppressed.
                    "");
              }
              void independentParameterFailure() {
                BiFn<@Nullable String, @Nullable String> f = Test::consume; // 2 warnings: T inference failure, extra parameter mismatch.
              }
              void classTypeVariableFailure(Receiver<String> receiver) {
                BiFn<@Nullable String, List<@Nullable String>> f = receiver::consume; // 2 warnings: T inference failure, value parameter mismatch.
              }

              static <T> String takes(List<String> l) { return ""; }
              static <U> void pairList(Fn<List<@Nullable String>, String> f, U u) {}
              void unrelatedArgumentNestedParameterFailure() {
                pairList(Test::takes, null); // 3 warnings: U inference failure, nullable argument, nested parameter mismatch.
              }
              void unrelatedArgumentNestedParameterControl() {
                pairList(Test::takes, "x"); // 1 warning: nested parameter mismatch.
              }

              static <T> void both(T t, List<String> l) {}
              static <T> void bothNullableElements(T t, List<@Nullable String> l) {}
              static void accept(BiFn<@Nullable String, List<@Nullable String>> f) {}
              void independentNestedParameterArgumentFailure() {
                accept(Test::both); // 2 warnings: T inference failure, nested parameter mismatch.
              }
              void independentNestedParameterArgumentControl() {
                accept(Test::bothNullableElements); // 1 warning: T inference failure.
              }
              void independentNestedParameterAssignmentFailure() {
                BiFn<@Nullable String, List<@Nullable String>> f = Test::both; // 2 warnings: T inference failure, nested parameter mismatch.
              }
              void independentNestedParameterAssignmentControl() {
                BiFn<@Nullable String, List<@Nullable String>> f = Test::bothNullableElements; // 1 warning: T inference failure.
              }

              static <T> List<@Nullable String> nestedResult(T t) { return List.of(); }
              static <T> List<String> nonNullNestedResult(T t) { return List.of(); }
              void independentNestedReturnFailure() {
                Fn<@Nullable String, List<String>> f = Test::nestedResult; // 2 warnings: T inference failure, nested return mismatch.
              }
              void independentNestedReturnControl() {
                Fn<@Nullable String, List<String>> f = Test::nonNullNestedResult; // 1 warning: T inference failure.
              }

              static <T> List<@Nullable T> nullableElements(T t) { return List.of(); }
              void dependentNestedReturnFailure() {
                Fn<@Nullable String, List<String>> f = Test::nullableElements; // 1 warning: T inference failure, duplicate nested return mismatch suppressed.
              }

              static <T> @Nullable T maybe(T value) { return null; }
              void explicitlyNullableTypeVariableReturnFailure() {
                Fn<@Nullable String, String> f = Test::maybe; // 2 warnings: T inference failure, explicit nullable return.
              }
            }
            """);

    assertThat(compileAndReportDiagnostics(List.of(source)))
        .containsExactly(
            "Test.java:20: [NullAway] inference failure: type variable U is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:20: [NullAway] passing @Nullable parameter 'null' where @NonNull is required",
            "Test.java:20: [NullAway] referenced method returns @Nullable, but functional interface"
                + " method com.uber.Test.Fn.apply(String) returns @NonNull",
            "Test.java:23: [NullAway] referenced method returns @Nullable, but functional interface"
                + " method com.uber.Test.Fn.apply(String) returns @NonNull",
            "Test.java:26: [NullAway] inference failure: type variable T constrained to be both"
                + " @NonNull and @Nullable",
            "Test.java:26: [NullAway] referenced method returns @Nullable, but functional interface"
                + " method com.uber.Test.Fn.apply(@Nullable String) returns @NonNull",
            "Test.java:29: [NullAway] referenced method returns @Nullable, but functional interface"
                + " method com.uber.Test.Fn.apply(String) returns @NonNull",
            "Test.java:32: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:37: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:37: [NullAway] parameter extra of referenced method is @NonNull, but"
                + " parameter in functional interface method"
                + " com.uber.Test.BiFn.apply(@Nullable String,@Nullable String) is @Nullable",
            "Test.java:40: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:40: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method"
                + " com.uber.Test.BiFn.apply(@Nullable String,List<@Nullable String>) is @Nullable",
            "Test.java:46: [NullAway] inference failure: type variable U is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:46: [NullAway] passing @Nullable parameter 'null' where @NonNull is required",
            "Test.java:46: [NullAway] parameter type of referenced method is List<String>, but"
                + " parameter in functional interface method has type List<@Nullable String>, which"
                + " has mismatched type parameter nullability",
            "Test.java:49: [NullAway] parameter type of referenced method is List<String>, but"
                + " parameter in functional interface method has type List<@Nullable String>, which"
                + " has mismatched type parameter nullability",
            "Test.java:56: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:56: [NullAway] parameter type of referenced method is List<String>, but"
                + " parameter in functional interface method has type List<@Nullable String>, which"
                + " has mismatched type parameter nullability",
            "Test.java:59: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:62: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:62: [NullAway] parameter type of referenced method is List<String>, but"
                + " parameter in functional interface method has type List<@Nullable String>, which"
                + " has mismatched type parameter nullability",
            "Test.java:65: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:71: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:71: [NullAway] referenced method returns List<@Nullable String>, but functional"
                + " interface method returns List<String>, which has mismatched type parameter"
                + " nullability",
            "Test.java:74: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:79: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:84: [NullAway] inference failure: type variable T is constrained to be"
                + " @Nullable, but its upper bound requires it to be @NonNull",
            "Test.java:84: [NullAway] referenced method returns @Nullable, but functional interface"
                + " method com.uber.Test.Fn.apply(@Nullable String) returns @NonNull");
  }

  @Test
  public void methodReferenceCompatibilityErrorsRemainWhenInferenceWarningsAreDisabled() {
    assertThat(
            compileAndReportDiagnostics(
                List.of(methodReferenceSource()),
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=false"))
        .containsExactly(
            "Test.java:29: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:30: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:31: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:32: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:34: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:37: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:40: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:43: [NullAway] parameter value of referenced method is @NonNull, but"
                + " parameter in functional interface method com.uber.Test.Fn.apply(@Nullable"
                + " String) is @Nullable",
            "Test.java:45: [NullAway] parameter type of referenced method is List<T>, but parameter"
                + " in functional interface method has type List<@Nullable String>, which has"
                + " mismatched type parameter nullability",
            "Test.java:46: [NullAway] parameter type of referenced method is List<T>, but parameter"
                + " in functional interface method has type List<@Nullable String>, which has"
                + " mismatched type parameter nullability",
            "Test.java:47: [NullAway] parameter type of referenced method is List<T>, but parameter"
                + " in functional interface method has type List<@Nullable String>, which has"
                + " mismatched type parameter nullability",
            "Test.java:48: [NullAway] parameter type of referenced method is List<T>, but parameter"
                + " in functional interface method has type List<@Nullable String>, which has"
                + " mismatched type parameter nullability",
            "Test.java:49: [NullAway] parameter extra of referenced method is @NonNull, but"
                + " parameter in functional interface method"
                + " com.uber.Test.UnmarkedFn.apply(List<@Nullable String>,@Nullable Object) is"
                + " @Nullable");
  }

  /**
   * Returns method references with incompatible inferred nullability in each target context, with
   * nullable-bound and unmarked-target controls. The generic cached call runs joint inference
   * before the reference itself is visited.
   */
  private static JavaFileObject methodReferenceSource() {
    return FileObjects.forSourceLines(
        "Test.java",
        """
        package com.uber;
        import java.util.List;
        import java.util.function.Supplier;
        import org.jspecify.annotations.*;
        @NullMarked class Test {
          // Functional interfaces for testing method references
          interface Fn<A extends @Nullable Object, R extends @Nullable Object> { R apply(A a); }
          interface MarkedFn<T extends @Nullable Object> { void apply(T value); }
          @NullUnmarked interface UnmarkedFn {
            void apply(List<@Nullable String> values, @Nullable Object extra);
          }

          // Methods used for references
          static <T> T nonNullId(T value) { return value; }
          static <T extends @Nullable Object> T nullableId(T value) { return value; }
          static <T> void nonNullElements(List<T> values) {}
          static <T> void nonNullExtra(List<T> values, Object extra) {}

          // Methods to which we pass references
          static <T extends @Nullable Object> void gen(Fn<T, T> function) {}
          static void accept(Fn<@Nullable String, @Nullable String> function) {}
          static void acceptNested(MarkedFn<List<@Nullable String>> function) {}
          // The U type variable forces inference for a call to this function before a method reference passed
          // as the first argument is visited, so the reference's inference failure is already cached when it
          // is visited.
          static <U> void cached(MarkedFn<List<@Nullable String>> function, U witness) {}

          // Cases to check we get exactly one error or none
          void explicitGenericArgument() { Test.<@Nullable String>gen(Test::nonNullId); } // Error expected.
          void nonGenericArgument() { accept(Test::nonNullId); } // Error expected.
          void local() { Fn<@Nullable String, @Nullable String> function = Test::nonNullId; } // Error expected.
          Fn<@Nullable String, @Nullable String> field = Test::nonNullId; // Error expected.
          Fn<@Nullable String, @Nullable String> conditional(boolean flag) {
            return flag ? Test::nonNullId : value -> value; // Error expected.
          }
          Fn<@Nullable String, @Nullable String> switchArm(int selector) {
            return switch (selector) { default -> Test::nonNullId; }; // Error expected.
          }
          Supplier<Fn<@Nullable String, @Nullable String>> lambdaReturn() {
            return () -> Test::nonNullId; // Error expected.
          }
          Supplier<Fn<@Nullable String, @Nullable String>> lambdaBlockReturn() {
            return () -> { return Test::nonNullId; }; // Error expected.
          }
          void nestedLocal() { MarkedFn<List<@Nullable String>> function = Test::nonNullElements; } // Error expected.
          void nestedArgument() { acceptNested(Test::nonNullElements); } // Error expected.
          MarkedFn<List<@Nullable String>> nestedReturn() { return Test::nonNullElements; } // Error expected.
          void cachedNested() { cached(Test::nonNullElements, ""); } // Error expected.
          void unmarkedTarget() { UnmarkedFn function = Test::nonNullExtra; } // Error expected.
          void nullableBoundControl() { accept(Test::nullableId); } // No error expected.
          void nonNullNestedControl() { MarkedFn<List<String>> function = Test::nonNullElements; } // No error expected.
        }
        """);
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

  /**
   * Compiles sources in JSpecify mode using the shared diagnostic helper.
   *
   * @param sources the compilation units to compile
   * @param additionalArgs compiler options overriding the default JSpecify configuration
   * @return the rendered diagnostics, in the order javac emitted them
   */
  private List<String> compileAndReportDiagnostics(
      List<JavaFileObject> sources, String... additionalArgs) {
    List<String> args =
        new ArrayList<>(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber")));
    args.addAll(Arrays.asList(additionalArgs));
    return compileAndReportDiagnostics(sources, args);
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
