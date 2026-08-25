package com.uber.nullaway.jspecify;

import static com.google.common.truth.Truth.assertThat;

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
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Test;

/** Tests the locations and counts of generic inference failure diagnostics. */
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
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both @NonNull and @Nullable
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
            .getTask(
                null,
                FileManagers.testFileManager(),
                diagnosticCollector,
                args,
                null,
                List.of(unrelatedSource, callerSource))
            .call();

    assertThat(compilationSucceeded).isTrue();
    List<Diagnostic<? extends JavaFileObject>> inferenceFailureDiagnostics =
        diagnosticCollector.getDiagnostics().stream()
            .filter(
                diagnostic ->
                    diagnostic
                        .getMessage(Locale.ROOT)
                        .contains("inference failure: type variable T"))
            .toList();
    assertThat(inferenceFailureDiagnostics).hasSize(1);
    Diagnostic<? extends JavaFileObject> inferenceFailure = inferenceFailureDiagnostics.get(0);
    assertThat(inferenceFailure.getSource().getName()).endsWith("File2.java");
    assertThat(inferenceFailure.getLineNumber()).isEqualTo(7L);
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
