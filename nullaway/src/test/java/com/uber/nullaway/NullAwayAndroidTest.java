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
public class NullAwayAndroidTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @SuppressWarnings("CheckReturnValue")
  @Before
  public void setup() {
    compilationHelper = CompilationTestHelper.newInstance(NullAway.class, getClass());
    compilationHelper.setArgs(
        Arrays.asList(
            "-d",
            temporaryFolder.getRoot().getAbsolutePath(),
            "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex"));
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

  @Test
  public void coreFragmentMissingOnAttachError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/CoreFragmentWithoutOnAttach.java")
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnCreateError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/CoreFragmentWithoutOnCreate.java")
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnCreateViewError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/core/Fragment.java")
        .addSourceFile("android-error/CoreFragmentWithoutOnCreateView.java")
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

  @Test
  public void androidxFragmentMissingOnAttachError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/androidx/Fragment.java")
        .addSourceFile("android-error/AndroidxFragmentWithoutOnAttach.java")
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnCreateError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/androidx/Fragment.java")
        .addSourceFile("android-error/AndroidxFragmentWithoutOnCreate.java")
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnCreateViewError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/androidx/Fragment.java")
        .addSourceFile("android-error/AndroidxFragmentWithoutOnCreateView.java")
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

  @Test
  public void supportLibFragmentMissingOnAttachError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/supportlib/Fragment.java")
        .addSourceFile("android-error/SupportLibraryFragmentWithoutOnAttach.java")
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnCreateError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/supportlib/Fragment.java")
        .addSourceFile("android-error/SupportLibraryFragmentWithoutOnCreate.java")
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnCreateViewError() {
    initialiseAndroidCoreClasses();
    compilationHelper
        .addSourceFile("androidstubs/supportlib/Fragment.java")
        .addSourceFile("android-error/SupportLibraryFragmentWithoutOnCreateView.java")
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
  @SuppressWarnings("CheckReturnValue")
  private void initialiseAndroidCoreClasses() {
    compilationHelper
        .addSourceFile("androidstubs/core/Context.java")
        .addSourceFile("androidstubs/core/Bundle.java")
        .addSourceFile("androidstubs/core/LayoutInflater.java")
        .addSourceFile("androidstubs/core/PersistableBundle.java")
        .addSourceFile("androidstubs/core/View.java")
        .addSourceFile("androidstubs/core/ViewGroup.java");
  }
}
