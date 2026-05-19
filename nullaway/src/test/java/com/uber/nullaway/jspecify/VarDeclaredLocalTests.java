package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class VarDeclaredLocalTests extends NullAwayTestsBase {

  @Test
  public void genericInferenceForVarLocal() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<T extends @Nullable Object> {
                T get();
              }
              static <U extends @Nullable Object> Foo<U> make(U u) {
                throw new RuntimeException();
              }
              void test() {
                var foo1 = make(null);
                // BUG: Diagnostic contains: dereferenced expression foo1.get() is @Nullable
                foo1.get().hashCode();
                var foo2 = make(new Object());
                foo2.get().hashCode();
                var foo3 = make(null);
                // BUG: Diagnostic contains: dereferenced expression foo3.get() is @Nullable
                foo3.get().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void varInTryWithResources() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.*;
            import java.util.stream.*;
            @NullMarked
            class Test {
              void test(Iterator<@Nullable Object> iterator) {
                // just testing that we don't crash here
                try (var stream = StreamSupport.stream(
                  Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)) {
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void sameNameVarInLoop() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<T extends @Nullable Object> {
                T get();
              }
              static <U extends @Nullable Object> Foo<U> make(U u) {
                throw new RuntimeException();
              }
              void test() {
                for (var foo1 = make(null); foo1 == null; ) {
                  // BUG: Diagnostic contains: dereferenced expression foo1.get() is @Nullable
                  foo1.get().hashCode();
                }
                for (var foo1 = make(new Object()); foo1 == null; ) {
                  foo1.get().hashCode();
                }
                for (var foo1 = make(null); foo1 == null; ) {
                  // BUG: Diagnostic contains: dereferenced expression foo1.get() is @Nullable
                  foo1.get().hashCode();
                }
              }
            }""")
        .doTest();
  }

  @Test
  public void varGenericInferenceFromDataflowInLoop() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<T extends @Nullable Object> {
                T get();
              }
              static <U extends @Nullable Object> Foo<U> make(U u) {
                throw new RuntimeException();
              }
              void test() {
                String s = "hello";
                while (true) {
                  var foo = make(s);
                  // BUG: Diagnostic contains: dereferenced expression foo.get() is @Nullable
                  foo.get().hashCode();
                  s = null;
                }
              }
            }""")
        .doTest();
  }

  @Test
  public void genericInferenceForVarLocalWithDuplicateNameInAnonymousClass() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<T extends @Nullable Object> {
                T get();
              }
              interface Runner {
                void run();
              }
              static <U extends @Nullable Object> Foo<U> make(U u) {
                throw new RuntimeException();
              }
              void test() {
                var foo = make(new Object());
                new Runner() {
                  @Override
                  public void run() {
                    var foo = make(null);
                    // BUG: Diagnostic contains: dereferenced expression foo.get() is @Nullable
                    foo.get().hashCode();
                  }
                };
                foo.get().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void enhancedForLoop() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.*;
            @NullMarked
            class Test {
              interface Foo<T extends @Nullable Object> {
                T get();
              }
              void test(List<Foo<@Nullable String>> l) {
                for (var foo : l) {
                  var x = foo.get();
                  // TODO we should be reporting a warning here consistently
                  // commented out since we only report a warning on JDK 27+
                  // x.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList(
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true")));
  }
}
