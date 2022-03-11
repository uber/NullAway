package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
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
    defaultCompilationHelper =
        makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:KnownInitializers="
                    + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases.Super.doInit,"
                    + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases"
                    + ".SuperInterface.doInit2",
                "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex",
                // We give the following in Regexp format to test that support
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                "-XepOpt:NullAway:ExcludedClasses="
                    + "com.uber.nullaway.testdata.Shape_Stuff,"
                    + "com.uber.nullaway.testdata.excluded",
                "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.nullaway.testdata.TestAnnot",
                "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull",
                "-XepOpt:NullAway:ExternalInitAnnotations=com.uber.ExternalInit",
                "-XepOpt:NullAway:ExcludedFieldAnnotations=com.uber.ExternalFieldInit"));
  }

  /**
   * Creates a new {@link CompilationTestHelper} with a list of javac arguments. As of Error Prone
   * 2.5.1, {@link CompilationTestHelper#setArgs(List)} can only be invoked once per object. So,
   * this method must be used to create a test helper when a different set of javac arguments is
   * required than those used for {@link #defaultCompilationHelper}.
   *
   * @param args the javac arguments
   * @return the test helper
   */
  protected CompilationTestHelper makeTestHelperWithArgs(List<String> args) {
    return CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
  }
}
