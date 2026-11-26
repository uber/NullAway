package com.uber.nullaway;

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneJavaCompiler;
import com.google.errorprone.FileManagers;
import com.google.errorprone.scanner.ScannerSupplier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public abstract class NullAwayTestsBase {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  protected CompilationTestHelper defaultCompilationHelper;

  @SuppressWarnings("CheckReturnValue")
  @Before
  public void setup() {
    defaultCompilationHelper = makeTestHelperWithArgs(defaultArgs());
  }

  /** Returns the javac arguments {@link #setup} builds {@link #defaultCompilationHelper} from. */
  protected List<String> defaultArgs() {
    return List.of(
        "-d",
        temporaryFolder.getRoot().getAbsolutePath(),
        "-XepOpt:NullAway:KnownInitializers="
            + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases.Super.doInit,"
            + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases"
            + ".SuperInterface.doInit2",
        "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex",
        // A regexp value for UnannotatedSubPackages, so the tests cover regexp support
        "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
        "-XepOpt:NullAway:ExcludedClasses="
            + "com.uber.nullaway.testdata.Shape_Stuff,"
            + "com.uber.nullaway.testdata.excluded",
        "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.nullaway.testdata.TestAnnot",
        "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull",
        "-XepOpt:NullAway:ExternalInitAnnotations=com.uber.ExternalInit",
        "-XepOpt:NullAway:ExcludedFieldAnnotations=com.uber.ExternalFieldInit",
        "-XDaddTypeAnnotationsToSymbol=true");
  }

  /**
   * Creates a new {@link CompilationTestHelper} with a list of javac arguments. As of Error Prone
   * 2.5.1, {@link CompilationTestHelper#setArgs(List)} can only be invoked once per object. So,
   * this method must be used to create a test helper when a different set of javac arguments is
   * required than those used for {@link #defaultCompilationHelper}.
   *
   * <p>The helper runs {@link JSpecifyUnrecognizedAnnotationLocation} alongside NullAway, so a test
   * whose source annotates a location JSpecify does not recognize fails. A source that does so on
   * purpose carries {@code @SuppressWarnings("JSpecifyUnrecognizedAnnotationLocation")} on the
   * annotated declaration or on one enclosing it.
   *
   * <p>The helper is built from a {@link ScannerSupplier} rather than from NullAway alone, so Error
   * Prone does not require a diagnostic naming NullAway on a line marked {@code // BUG: Diagnostic
   * contains:}. Any diagnostic on that line containing the marker text satisfies the marker.
   *
   * @param args the javac arguments
   * @return the test helper
   */
  protected CompilationTestHelper makeTestHelperWithArgs(List<String> args) {
    return makeTestHelperWithArgs(getClass(), args);
  }

  /**
   * Creates a {@link CompilationTestHelper} that runs NullAway and {@link
   * JSpecifyUnrecognizedAnnotationLocation} together over the sources a test compiles. The location
   * check runs at {@code WARN}, since it reports nothing at its default severity.
   *
   * <p>A test class that builds its own NullAway helper calls this rather than {@link
   * CompilationTestHelper#newInstance}, so that its sources are checked too. {@link
   * UnrecognizedAnnotationLocationInTestSourcesTest} covers only {@link #defaultCompilationHelper},
   * not a helper built some other way.
   *
   * @param testClass the test class, used to resolve source paths
   * @param args the javac arguments
   */
  public static CompilationTestHelper makeTestHelperWithArgs(
      Class<?> testClass, List<String> args) {
    return CompilationTestHelper.newInstance(
            ScannerSupplier.fromBugCheckerClasses(
                NullAway.class, JSpecifyUnrecognizedAnnotationLocation.class),
            testClass)
        .setArgs(
            ImmutableList.<String>builder()
                .addAll(args)
                .add("-Xep:JSpecifyUnrecognizedAnnotationLocation:WARN")
                .build());
  }

  /**
   * Compiles {@code sources} with NullAway and reports every diagnostic that names a position in
   * them, as {@code <file>:<line>: <message>}. A caller asserts the returned list, whole or
   * narrowed to the diagnostics its own name covers, so one that appears, disappears, moves to
   * another line, or changes its wording fails the test. Severity is not part of the filter, since
   * a check reported below warning level is still a diagnostic about the source.
   *
   * <p>A diagnostic is reported only when it names both a file and a line, which is what a reader
   * of the source can locate. That leaves out javac's {@code [options]} warnings, which name no
   * file, and its summary notes such as "uses unchecked or unsafe operations", which name a file at
   * {@link Diagnostic#NOPOS}. Dropping those loses no error, because compilation has to succeed
   * anyway: NullAway reports at warning severity here, so a compile error means the test source
   * itself is broken.
   *
   * @param sources the compilation units to compile
   * @param args the javac arguments
   * @return the rendered diagnostics, in the order javac emitted them
   */
  protected List<String> compileAndReportDiagnostics(
      List<JavaFileObject> sources, List<String> args) {
    DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
    args = new ArrayList<>(args);
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
        .map(NullAwayTestsBase::render)
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
}
