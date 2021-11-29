/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.thirdpartylibs;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
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
public class NullAwayGrpcTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper ioGrpcCompilationTestHelper;

  @SuppressWarnings("CheckReturnValue")
  @Before
  public void setup() {
    ioGrpcCompilationTestHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    // IMPORTANT:
                    // These tests use the fact that io.grpc.Metadata.get(...) is annotated
                    // @Nullable. Without the flag above (or a corresponding library model),
                    // the gRPC libraries themseves would need to be part of  AnnotatedPackages
                    // for the true positives in the tests below to manifest. Using the
                    // default optimistic-nullness assumptions for third-party code, results
                    // in assuming that all calls to Metadata.get(...) return non-null.
                    "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"));
  }

  @Test
  public void ioGrpcMetadataAsMapTest() {
    ioGrpcCompilationTestHelper
        .addSourceLines(
            "Keys.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public final class Keys {",
            "  static final Metadata.Key<String> KEY_1 = Metadata.Key.of(\"KEY1\", Metadata.ASCII_STRING_MARSHALLER);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public class Test {",
            "  public void takeNonNullString(String s) { }",
            "  public void safeDeref(Metadata headers) {",
            "   if (headers.containsKey(Keys.KEY_1)) {",
            "     takeNonNullString(headers.get(Keys.KEY_1));",
            "   }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ioGrpcMetadataAsMapPossitiveTest() {
    ioGrpcCompilationTestHelper
        .addSourceLines(
            "Keys.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public final class Keys {",
            "  static final Metadata.Key<String> KEY_1 = Metadata.Key.of(\"KEY1\", Metadata.ASCII_STRING_MARSHALLER);",
            "  static final Metadata.Key<String> KEY_2 = Metadata.Key.of(\"KEY2\", Metadata.ASCII_STRING_MARSHALLER);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public class Test {",
            "  public void takeNonNullString(String s) { }",
            "  public void safeDeref(Metadata headers) {",
            "   if (headers.containsKey(Keys.KEY_1)) {",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'headers.get(Keys.KEY_2)'",
            "     takeNonNullString(headers.get(Keys.KEY_2));", // Expect error: KEY_2 != KEY_1
            "   }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ioGrpcMetadataAsAccessPathTest() {
    ioGrpcCompilationTestHelper
        .addSourceLines(
            "Keys.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public final class Keys {",
            "  static final Metadata.Key<String> KEY_1 = Metadata.Key.of(\"KEY1\", Metadata.ASCII_STRING_MARSHALLER);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public class Test {",
            "  public void takeNonNullString(String s) { }",
            "  public void safeDeref(Metadata headers) {",
            "   if (headers.get(Keys.KEY_1) != null) {",
            "     takeNonNullString(headers.get(Keys.KEY_1));",
            "   }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ioGrpcMetadataAsAccessPathPositiveTest() {
    ioGrpcCompilationTestHelper
        .addSourceLines(
            "Keys.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public final class Keys {",
            "  static final Metadata.Key<String> KEY_1 = Metadata.Key.of(\"KEY1\", Metadata.ASCII_STRING_MARSHALLER);",
            "  static final Metadata.Key<String> KEY_2 = Metadata.Key.of(\"KEY2\", Metadata.ASCII_STRING_MARSHALLER);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import io.grpc.Metadata;",
            "public class Test {",
            "  public void takeNonNullString(String s) { }",
            "  public void safeDeref(Metadata headers) {",
            "   if (headers.get(Keys.KEY_1) != null) {",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'headers.get(Keys.KEY_2)'",
            "     takeNonNullString(headers.get(Keys.KEY_2));", // Expect error: KEY_2 != KEY_1
            "   }",
            "  }",
            "}")
        .doTest();
  }
}
