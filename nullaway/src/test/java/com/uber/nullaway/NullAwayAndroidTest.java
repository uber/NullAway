package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import com.sun.tools.javac.main.Main;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
@SuppressWarnings("CheckTestExtendsBaseClass")
public class NullAwayAndroidTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @Before
  public void setup() {
    compilationHelper = CompilationTestHelper.newInstance(NullAway.class, getClass());
    compilationHelper.setArgs(
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

  // Core Fragment tests

  @Test
  public void coreFragmentSuccess() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-success/CoreFragment.java")
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void coreFragmentMissingOnAttachError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/CoreFragmentWithoutOnAttach.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void coreFragmentMissingOnCreateError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/CoreFragmentWithoutOnCreate.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void coreFragmentMissingOnCreateViewError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/CoreFragmentWithoutOnCreateView.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  // AndroidX Library Fragment
  @Test
  public void androidxFragmentSuccess() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/androidx/Fragment.java")
        .addSourceFile("android-success/AndroidxFragment.java")
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void androidxFragmentMissingOnAttachError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/AndroidxFragmentWithoutOnAttach.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void androidxFragmentMissingOnCreateError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/AndroidxFragmentWithoutOnCreate.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void androidxFragmentMissingOnCreateViewError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/AndroidxFragmentWithoutOnCreateView.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  // Android support library Fragment

  @Test
  public void supportLibFragmentSuccess() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/supportlib/Fragment.java")
        .addSourceFile("android-success/SupportLibraryFragment.java")
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void supportLibFragmentMissingOnAttachError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/SupportLibraryFragmentWithoutOnAttach.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void supportLibFragmentMissingOnCreateError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/SupportLibraryFragmentWithoutOnCreate.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  @Test(expected = AssertionError.class)
  public void supportLibFragmentMissingOnCreateViewError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/SupportLibraryFragmentWithoutOnCreateView.java")
        .expectResult(Main.Result.ERROR)
        .doTest();
  }

  // Core Activity

  @Test
  public void coreActivitySuccess() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Activity.java")
        .addSourceFile("android-success/CoreActivity.java")
        .doTest();
  }

  // Support Library Activity

  @Test
  public void supportLibActivitySuccess() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/supportlib/ActivityCompat.java")
        .addSourceFile("android-success/SupportLibActivityCompat.java")
        .doTest();
  }

  // AndroidX Library Activity

  @Test
  public void androidxActivitySuccess() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/androidx/ActivityCompat.java")
        .addSourceFile("android-success/AndroidxActivityCompat.java")
        .doTest();
  }

  /** Initialises the default android classes that are commonly used. */
  private void initialiseAndroidCoreClasses() {
    compilationHelper
        .addSourceFile("androidstubs/annotations/Nullable.java")
        .addSourceFile("androidstubs/annotations/NonNull.java")
        .addSourceFile("androidstubs/core/Context.java")
        .addSourceFile("androidstubs/core/Bundle.java")
        .addSourceFile("androidstubs/core/LayoutInflater.java")
        .addSourceFile("androidstubs/core/PersistableBundle.java")
        .addSourceFile("androidstubs/core/View.java")
        .addSourceFile("androidstubs/core/ViewGroup.java");
  }
}
