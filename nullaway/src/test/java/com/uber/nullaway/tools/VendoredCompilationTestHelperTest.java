package com.uber.nullaway.tools;

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.errorprone.CompilationTestHelper;
import java.net.URL;
import org.junit.Test;

/**
 * Guards the classpath order the vendored {@link CompilationTestHelper} depends on.
 *
 * <p>The checks that copy adds are invisible when an {@code error_prone_test_helpers} jar comes
 * first, and a test suite that stops checking something looks exactly like one that has nothing to
 * report. Tasks that deliberately prepend an Error Prone jar, such as {@code testErrorProneOldest}
 * and {@code testJdk17}, skip this test rather than fail it.
 */
public class VendoredCompilationTestHelperTest {

  @Test
  public void theVendoredHelperIsTheOneOnTheClasspath() {
    URL location = CompilationTestHelper.class.getProtectionDomain().getCodeSource().getLocation();
    if (location.getPath().endsWith(".jar")) {
      // A task that pins an older Error Prone; see the class comment.
      return;
    }
    assertWithMessage(
            "com.google.errorprone.CompilationTestHelper was loaded from %s, which is neither the"
                + " vendored copy in this source set nor an Error Prone jar",
            location)
        .that(location.getPath())
        .contains("classes");
  }
}
