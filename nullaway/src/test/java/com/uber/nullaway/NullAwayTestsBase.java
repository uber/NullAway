package com.uber.nullaway;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.scanner.ScannerSupplier;
import java.util.List;
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
}
