package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class SuppressionTests extends NullAwayTestsBase {

  @Test
  public void additionalSuppressionNamesTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SuppressionNameAliases=Foo,Bar"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              @SuppressWarnings("Foo")
              void foo(@Nullable Object o) {
                o.getClass();
              }
              @SuppressWarnings("Bar")
              void bar(@Nullable Object o) {
                o.getClass();
              }
              @SuppressWarnings("Baz")
              void baz(@Nullable Object o) {
                // BUG: Diagnostic contains: dereferenced expression 'o' is @Nullable
                o.getClass();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wrongOverrideParamSuppressionOnParameter() {
    defaultCompilationHelper
        .addSourceLines(
            "TestInterface.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface TestInterface {
              void foo(@Nullable Object param);
            }
            """)
        .addSourceLines(
            "TestImpl.java",
            """
            package com.uber;
            public class TestImpl implements TestInterface {
              @Override
              public void foo(@SuppressWarnings("NullAway") Object param) {}
            }
            """)
        .doTest();
  }

  @Test
  public void wrongOverrideParamSuppressionOnMethodReferenceUsage() {
    defaultCompilationHelper
        .addSourceLines(
            "TestInterface.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface TestInterface {
              void foo(@Nullable Object param);
            }
            """)
        .addSourceLines(
            "TestImpl.java",
            """
            package com.uber;
            public class TestImpl {
              public void doFoo(Object param) {}
            }
            """)
        .addSourceLines(
            "TestDriver.java",
            """
            package com.uber;
            public class TestDriver {
              public int bar(TestInterface param) {
                return 0;
              }
              public void doTest(TestImpl impl) {
                @SuppressWarnings("NullAway")
                int res = bar(impl::doFoo);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wrongOverrideParamSuppressionOnLambda() {
    defaultCompilationHelper
        .addSourceLines(
            "TestInterface.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface TestInterface {
              void foo(@Nullable Object param);
            }
            """)
        .addSourceLines(
            "TestDriver.java",
            """
            package com.uber;
            public class TestDriver {
              public int bar(TestInterface param) {
                return 0;
              }
              public void doTest() {
                int res = bar((@SuppressWarnings("NullAway") Object param) -> {});
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wrongOverrideParamNoSuppression() {
    defaultCompilationHelper
        .addSourceLines(
            "TestInterface.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface TestInterface {
              void foo(@Nullable Object param);
            }
            """)
        .addSourceLines(
            "TestImpl.java",
            """
            package com.uber;
            public class TestImpl implements TestInterface {
              @Override
              // BUG: Diagnostic contains: parameter param is @NonNull, but parameter in superclass method
              public void foo(Object param) {}
            }
            """)
        .doTest();
  }

  @Test
  public void wrongOverrideParamOnMethodReferenceUsageNoSuppression() {
    defaultCompilationHelper
        .addSourceLines(
            "TestInterface.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface TestInterface {
              void foo(@Nullable Object param);
            }
            """)
        .addSourceLines(
            "TestImpl.java",
            """
            package com.uber;
            public class TestImpl {
              public void doFoo(Object param) {}
            }
            """)
        .addSourceLines(
            "TestDriver.java",
            """
            package com.uber;
            public class TestDriver {
              public int bar(TestInterface param) {
                return 0;
              }
              public void doTest(TestImpl impl) {
                // BUG: Diagnostic contains: parameter param of referenced method is @NonNull, but parameter in functional interface method
                int res = bar(impl::doFoo);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wrongOverrideParamOnLambdaNoSuppression() {
    defaultCompilationHelper
        .addSourceLines(
            "TestInterface.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface TestInterface {
              void foo(@Nullable Object param);
            }
            """)
        .addSourceLines(
            "TestDriver.java",
            """
            package com.uber;
            public class TestDriver {
              public int bar(TestInterface param) {
                return 0;
              }
              public void doTest() {
                // BUG: Diagnostic contains: parameter param is @NonNull, but parameter in functional interface method
                int res = bar((Object param) -> {});
              }
            }
            """)
        .doTest();
  }

  /**
   * Test showing a trick for asserting that all {@code @Nullable} levels of a nested access path
   * are in fact non-null using a warning suppression.
   */
  @Test
  public void suppressNestedInRequireNonNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import java.util.Objects;
            class Test {
              static class Foo {
                @Nullable Foo next;
                @Nullable Foo getNext() { return next; }
              }

              static void testFields(Foo f) {
                @SuppressWarnings("NullAway")
                var unused = Objects.requireNonNull(f.next.next.next);
                f.next.hashCode();
                f.next.next.hashCode();
                f.next.next.next.hashCode();
                // one too many
                // BUG: Diagnostic contains: dereferenced expression 'f.next.next.next.next' is @Nullable
                f.next.next.next.next.hashCode();
              }

              static void testCalls(Foo f) {
                @SuppressWarnings("NullAway")
                var unused = Objects.requireNonNull(f.getNext().getNext().getNext());
                f.getNext().hashCode();
                f.getNext().getNext().hashCode();
                f.getNext().getNext().getNext().hashCode();
                // one too many
                // BUG: Diagnostic contains: dereferenced expression 'f.getNext().getNext().getNext().getNext()' is @Nullable
                f.getNext().getNext().getNext().getNext().hashCode();
              }
            }
            """)
        .doTest();
  }
}
