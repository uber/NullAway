package com.uber.nullaway;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.scanner.ScannerSupplier;
import com.uber.nullaway.tools.DualModeCompilationTestHelper;
import com.uber.nullaway.tools.SkipBytecodeTestMode;
import com.uber.nullaway.tools.TestMode;
import java.util.List;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.model.Statement;

/**
 * Base class for the test suites that check NullAway on source snippets.
 *
 * <p>Every test method runs once per {@link TestMode}. The source run is what the method would have
 * done on its own; the bytecode run happens only for a snippet that splits into files NullAway
 * analyzes and files it reads from the classpath, and is skipped through a JUnit assumption
 * otherwise, which covers most of the suite. See {@link DualModeCompilationTestHelper} for the
 * split.
 */
@RunWith(Parameterized.class)
public abstract class NullAwayTestsBase {

  /** The modes every test method of every subclass runs in. */
  @Parameterized.Parameters(name = "{0}")
  public static List<TestMode> testModes() {
    return List.of(TestMode.values());
  }

  @Parameterized.Parameter public TestMode testMode;

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** Honors {@link SkipBytecodeTestMode} on the test method and on the test class. */
  @Rule public final TestRule skipBytecodeTestMode = this::applySkipBytecodeTestMode;

  protected DualModeCompilationTestHelper defaultCompilationHelper;

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
   * Creates a new {@link DualModeCompilationTestHelper} with a list of javac arguments. As of Error
   * Prone 2.5.1, the arguments of a compilation can only be set once per object. So, this method
   * must be used to create a test helper when a different set of javac arguments is required than
   * those used for {@link #defaultCompilationHelper}.
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
  protected DualModeCompilationTestHelper makeTestHelperWithArgs(List<String> args) {
    return DualModeCompilationTestHelper.newInstance(checkers(), getClass(), testMode)
        .setArgs(checkerArgs(args));
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
    return CompilationTestHelper.newInstance(checkers(), testClass).setArgs(checkerArgs(args));
  }

  /** Returns the checks every helper built here runs: NullAway and the annotation-location check. */
  private static ScannerSupplier checkers() {
    return ScannerSupplier.fromBugCheckerClasses(
        NullAway.class, JSpecifyUnrecognizedAnnotationLocation.class);
  }

  /**
   * Returns the javac arguments of a test with the annotation-location check raised to {@code WARN}.
   *
   * @param args the javac arguments the test declared
   * @return the arguments the helper compiles with
   */
  private static ImmutableList<String> checkerArgs(List<String> args) {
    return ImmutableList.<String>builder()
        .addAll(args)
        .add("-Xep:JSpecifyUnrecognizedAnnotationLocation:WARN")
        .build();
  }

  /**
   * Wraps a test method in the assumption that skips it in {@link TestMode#BYTECODE}.
   *
   * @param base the statement that runs the test method
   * @param description the test method
   * @return the wrapped statement
   */
  private Statement applySkipBytecodeTestMode(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        SkipBytecodeTestMode skip = skipAnnotation(description);
        if (testMode == TestMode.BYTECODE && skip != null) {
          throw new AssumptionViolatedException(skip.value());
        }
        base.evaluate();
      }
    };
  }

  /**
   * Returns the {@link SkipBytecodeTestMode} annotation of a test method, falling back to the one
   * of its class.
   *
   * @param description the test method
   * @return the annotation, or null when neither the method nor its class carries one
   */
  private static SkipBytecodeTestMode skipAnnotation(Description description) {
    SkipBytecodeTestMode onMethod = description.getAnnotation(SkipBytecodeTestMode.class);
    if (onMethod != null) {
      return onMethod;
    }
    Class<?> testClass = description.getTestClass();
    return testClass == null ? null : testClass.getAnnotation(SkipBytecodeTestMode.class);
  }
}
