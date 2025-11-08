package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

/**
 * Tests for preserving environment nullness facts for lambdas passed to nullness preserving methods
 * (methods annotated with @NullnessPreserving). Variables captured in such lambdas should not be
 * treated as automatically nullable; instead, the facts at the call site should be visible in the
 * lambda body.
 */
public class NullnessPreservingTests extends NullAwayTestsBase {

  @Test
  public void methodLambdaPreservesFacts() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:CheckNullnessPreserving=true",
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "com/example/library/PureLibrary.java",
            "package com.example.library;",
            "import com.uber.nullaway.annotations.NullnessPreserving;",
            "import java.util.function.Consumer;",
            "public class PureLibrary {",
            "  @NullnessPreserving",
            "  public static <T> void withConsumer(T t, Consumer<T> consumer) {",
            "    consumer.accept(t);",
            "  }",
            "}")
        .addSourceLines(
            "com/uber/Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.example.library.PureLibrary;",
            "public class Test {",
            "  private @Nullable Object f;",
            "  public void test1() {",
            "    if (this.f == null) {",
            "      throw new IllegalArgumentException();",
            "    }",
            "    // f is known non-null after the check; that fact should be visible in the lambda",
            "    PureLibrary.withConsumer(\"x\", v -> System.out.println(this.f.toString()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void methodLambdaDoesNotPreserveFacts() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:CheckNullnessPreserving=true",
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "com/example/library/OrdinaryLibrary.java",
            "package com.example.library;",
            "import java.util.function.Consumer;",
            "public class OrdinaryLibrary {",
            "  public static <T> void withConsumer(T t, Consumer<T> consumer) {",
            "    consumer.accept(t);",
            "  }",
            "}")
        .addSourceLines(
            "com/uber/Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.example.library.OrdinaryLibrary;",
            "public class Test {",
            "  private @Nullable Object f;",
            "  public void test1() {",
            "    if (this.f == null) {",
            "      throw new IllegalArgumentException();",
            "    }",
            "    // Without nullness preserving annotation, we should not propagate the non-null fact into the lambda",
            "    // BUG: Diagnostic contains: dereferenced expression this.f is @Nullable",
            "    OrdinaryLibrary.withConsumer(\"x\", v -> System.out.println(this.f.toString()));",
            "  }",
            "}")
        .doTest();
  }
}
