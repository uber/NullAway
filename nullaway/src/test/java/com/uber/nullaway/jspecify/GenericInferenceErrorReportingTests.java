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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Test;

/**
 * Tests the diagnostics NullAway reports for generic inference: which ones, how many, and where.
 */
public class GenericInferenceErrorReportingTests extends NullAwayTestsBase {

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
    DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
    List<String> args =
        new ArrayList<>(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber")));
    args.add("-proc:none");
    ErrorProneJavaCompiler compiler =
        new ErrorProneJavaCompiler(ScannerSupplier.fromBugCheckerClasses(NullAway.class));

    boolean compilationSucceeded =
        compiler
            .getTask(null, FileManagers.testFileManager(), diagnosticCollector, args, null, sources)
            .call();

    assertWithMessage("compilation failed: %s", diagnosticCollector.getDiagnostics())
        .that(compilationSucceeded)
        .isTrue();
    return diagnosticCollector.getDiagnostics().stream()
        .filter(
            diagnostic ->
                diagnostic.getSource() != null && diagnostic.getLineNumber() != Diagnostic.NOPOS)
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

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
