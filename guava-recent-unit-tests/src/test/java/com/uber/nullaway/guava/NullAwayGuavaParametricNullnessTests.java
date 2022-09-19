/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayGuavaParametricNullnessTests {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  @Before
  public void setup() {
    defaultCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.google.common",
                    "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated"));
  }

  @Test
  public void testFutureCallbackParametricNullness() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.util.concurrent.FutureCallback;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "    public static <T> FutureCallback<T> wrapFutureCallback(FutureCallback<T> futureCallback) {",
            "        return new FutureCallback<T>() {",
            "            @Override",
            "            public void onSuccess(@Nullable T result) {",
            "                futureCallback.onSuccess(result);",
            "            }",
            "            @Override",
            "            public void onFailure(Throwable throwable) {",
            "                futureCallback.onFailure(throwable);",
            "            }",
            "        };",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void testIterableParametricNullness() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.collect.ImmutableList;",
            "import com.google.common.collect.Iterables;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "    public static String test1() {",
            "        // BUG: Diagnostic contains: returning @Nullable expression",
            "        return Iterables.getFirst(ImmutableList.<String>of(), null);",
            "    }",
            "    public static @Nullable String test2() {",
            "        return Iterables.getFirst(ImmutableList.<String>of(), null);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void testCloserParametricNullness() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.io.Closer;",
            "import java.io.Closeable;",
            "import java.io.FileInputStream;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "    public static FileInputStream test1(Closer closer, FileInputStream fis1) {",
            "        // safe: non-null arg to non-null return",
            "        return closer.register(fis1);",
            "    }",
            "    @Nullable",
            "    public static FileInputStream test2(Closer closer, @Nullable FileInputStream fis2) {",
            "        // safe: nullable arg to nullable return",
            "        return closer.register(fis2);",
            "    }",
            "    public static FileInputStream test3(Closer closer, @Nullable FileInputStream fis3) {",
            "        // BUG: Diagnostic contains: returning @Nullable expression",
            "        return closer.register(fis3);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void testFunctionMethodOverride() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.base.Function;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "    public static void testFunctionOverride() {",
            "        Function<Object, Object> f = new Function<Object, Object>() {",
            "            @Override",
            "            public Object apply(Object input) {",
            "                return input;",
            "             }",
            "        };",
            "    }",
            "    public static void testFunctionOverrideNullableReturn() {",
            "        Function<Object, Object> f = new Function<Object, Object>() {",
            "            @Override",
            "            @Nullable",
            "            // BUG: Diagnostic contains: method returns @Nullable, but superclass method",
            "            public Object apply(Object input) {",
            "                return null;",
            "             }",
            "        };",
            "    }",
            "}")
        .doTest();
  }
}
