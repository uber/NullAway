package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
@SuppressWarnings("deprecation")
public class AndroidTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @SuppressWarnings("CheckReturnValue")
  @Before
  public void setup() {
    compilationHelper =
        NullAwayTestsBase.makeTestHelperWithArgs(
            getClass(),
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex"));
  }

  // Core Fragment tests

  @Test
  public void coreFragmentSuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/core/Fragment.java")
        .addSourceFile("testdata/android-success/CoreFragment.java")
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnAttachError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/core/Fragment.java")
        .addSourceFile("testdata/android-error/CoreFragmentWithoutOnAttach.java")
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnCreateError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/core/Fragment.java")
        .addSourceFile("testdata/android-error/CoreFragmentWithoutOnCreate.java")
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnCreateViewError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/core/Fragment.java")
        .addSourceFile("testdata/android-error/CoreFragmentWithoutOnCreateView.java")
        .doTest();
  }

  // AndroidX Library Fragment
  @Test
  public void androidxFragmentSuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/androidx/Fragment.java")
        .addSourceFile("testdata/android-success/AndroidxFragment.java")
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnAttachError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/androidx/Fragment.java")
        .addSourceFile("testdata/android-error/AndroidxFragmentWithoutOnAttach.java")
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnCreateError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/androidx/Fragment.java")
        .addSourceFile("testdata/android-error/AndroidxFragmentWithoutOnCreate.java")
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnCreateViewError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/androidx/Fragment.java")
        .addSourceFile("testdata/android-error/AndroidxFragmentWithoutOnCreateView.java")
        .doTest();
  }

  // Android support library Fragment

  @Test
  public void supportLibFragmentSuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/supportlib/Fragment.java")
        .addSourceFile("testdata/android-success/SupportLibraryFragment.java")
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnAttachError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/supportlib/Fragment.java")
        .addSourceFile("testdata/android-error/SupportLibraryFragmentWithoutOnAttach.java")
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnCreateError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/supportlib/Fragment.java")
        .addSourceFile("testdata/android-error/SupportLibraryFragmentWithoutOnCreate.java")
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnCreateViewError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/supportlib/Fragment.java")
        .addSourceFile("testdata/android-error/SupportLibraryFragmentWithoutOnCreateView.java")
        .doTest();
  }

  // Core Activity

  @Test
  public void coreActivitySuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/core/Activity.java")
        .addSourceFile("testdata/android-success/CoreActivity.java")
        .doTest();
  }

  // Support Library Activity

  @Test
  public void supportLibActivitySuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/supportlib/ActivityCompat.java")
        .addSourceFile("testdata/android-success/SupportLibActivityCompat.java")
        .doTest();
  }

  // AndroidX Library Activity

  @Test
  public void androidxActivitySuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceFile("testdata/androidstubs/androidx/ActivityCompat.java")
        .addSourceFile("testdata/android-success/AndroidxActivityCompat.java")
        .doTest();
  }

  /** Adds the Android stub sources that all the tests in this class share. */
  @SuppressWarnings("CheckReturnValue")
  private void initializeAndroidCoreClasses() {
    compilationHelper
        .addSourceFile("testdata/androidstubs/core/Context.java")
        .addSourceFile("testdata/androidstubs/core/Bundle.java")
        .addSourceFile("testdata/androidstubs/core/LayoutInflater.java")
        .addSourceFile("testdata/androidstubs/core/PersistableBundle.java")
        .addSourceFile("testdata/androidstubs/core/View.java")
        .addSourceFile("testdata/androidstubs/core/ViewGroup.java");
  }
}
