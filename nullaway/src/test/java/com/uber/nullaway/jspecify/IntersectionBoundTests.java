package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/**
 * A type variable whose upper bound is an intersection type includes {@code null} only when every
 * element of the intersection is annotated {@code @Nullable}. JSpecify assigns a nullness operator
 * to each element separately, so one non-null element makes the whole bound null-exclusive.
 *
 * <p>The bounds these tests read from bytecode are declared in {@code
 * com.uber.lib.generics.IntersectionTypeParam}, {@code com.uber.lib.generics.InnerClassBound}, and
 * {@code com.uber.lib.generics.NestedNullableInBound}. Before JDK 22, javac drops the annotations
 * on such a bound (<a href="https://bugs.openjdk.org/browse/JDK-8225377">JDK-8225377</a>), and
 * NullAway falls back on the raw type attributes of the class file, which number the bounds of an
 * intersection separately.
 *
 * <p>These tests never run without that bug's workaround: {@code JSpecifyJavacConfig} passes {@code
 * -XDaddTypeAnnotationsToSymbol=true}, so the annotation mirrors answer on every JDK the suite runs
 * on. The fallback can only turn a non-null verdict into a nullable one, so what the bytecode tests
 * pin is that it adds nothing it should not; a fallback that stopped supplying nullability
 * altogether fails none of them.
 */
public class IntersectionBoundTests extends NullAwayTestsBase {

  @Test
  public void allNullableIntersectionBoundAcceptsNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Box<T extends @Nullable Object & @Nullable Serializable> {}
              static void test(Box<@Nullable String> b) {}
            }
            """)
        .doTest();
  }

  @Test
  public void nonNullFirstElementRefusesNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Box<T extends Object & @Nullable Serializable> {}
              // BUG: Diagnostic contains: type variable T of type com.uber.Test.Box does not have a @Nullable upper bound
              static void test(Box<@Nullable String> b) {}
            }
            """)
        .doTest();
  }

  @Test
  public void nonNullLaterElementRefusesNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Box<T extends @Nullable Object & Serializable> {}
              // BUG: Diagnostic contains: type variable T of type com.uber.Test.Box does not have a @Nullable upper bound
              static void test(Box<@Nullable String> b) {}
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionBoundWithNoNullableElementRefusesNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Box<T extends Serializable & CharSequence> {}
              // BUG: Diagnostic contains: type variable T of type com.uber.Test.Box does not have a @Nullable upper bound
              static void test(Box<@Nullable String> b) {}
            }
            """)
        .doTest();
  }

  @Test
  public void allNullableIntersectionBoundAcceptsNullableTypeArgumentOnGenericMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Object & @Nullable Serializable> void box(T t) {}
              static void test() {
                Test.<@Nullable String>box(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionBoundWithNonNullElementRefusesNullableTypeArgumentOnGenericMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Object & Serializable> void box(T t) {}
              static void test() {
                // BUG: Diagnostic contains: method <T>box(T)'s type variable T is not @Nullable
                Test.<@Nullable String>box(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void allNullableInterfaceOnlyBoundAcceptsNullableTypeArgumentOnGenericMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Serializable & @Nullable CharSequence> void box(T t) {}
              static void test() {
                Test.<@Nullable String>box(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void interfaceOnlyBoundWithNonNullElementRefusesNullableTypeArgumentOnGenericMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Serializable & CharSequence> void box(T t) {}
              static void test() {
                // BUG: Diagnostic contains: method <T>box(T)'s type variable T is not @Nullable
                Test.<@Nullable String>box(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void readThroughWildcardWithAllNullableIntersectionBoundIsNullable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Box<T extends @Nullable Object & @Nullable Serializable> {
                T get() { throw new RuntimeException(); }
              }
              static void test(Box<?> b) {
                // BUG: Diagnostic contains: dereferenced expression 'b.get()' is @Nullable
                b.get().toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void readThroughWildcardWithNonNullElementInBoundIsNonNull() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Box<T extends @Nullable Object & Serializable> {
                T get() { throw new RuntimeException(); }
              }
              static void test(Box<?> b) {
                b.get().toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideNarrowingIntersectionBoundIsRefused() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <T extends @Nullable Object & @Nullable Serializable> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T extends @Nullable Object & Serializable> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideWideningIntersectionBoundIsRefused() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <T extends @Nullable Object & Serializable> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable Object & @Nullable Serializable> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideRepeatingIntersectionBoundIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.io.Serializable;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <T extends @Nullable Object & @Nullable Serializable> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                public <T extends @Nullable Object & @Nullable Serializable> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void allNullableIntersectionBoundFromBytecodeAcceptsNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.IntersectionTypeParam;
            import org.jspecify.annotations.Nullable;
            class Test {
              static void test(
                  IntersectionTypeParam<
                      // E1's class bound and interface bound are both @Nullable.
                      @Nullable String,
                      String,
                      String,
                      // E4 has no class bound, and both of its interface bounds are @Nullable.
                      @Nullable String,
                      String,
                      // E6 has three elements, all @Nullable.
                      @Nullable String,
                      String,
                      String> p) {}
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionBoundFromBytecodeWithNonNullElementRefusesNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.IntersectionTypeParam;
            import org.jspecify.annotations.Nullable;
            class Test {
              static void test(
                  IntersectionTypeParam<
                      String,
                      // E2's class bound is @Nullable and its interface bound is not.
                      // BUG: Diagnostic contains: type variable E2 of type com.uber.lib.generics.IntersectionTypeParam
                      @Nullable String,
                      // E3's interface bound is @Nullable and its class bound is not.
                      // BUG: Diagnostic contains: type variable E3 of type com.uber.lib.generics.IntersectionTypeParam
                      @Nullable String,
                      String,
                      // E5 has no class bound, and one of its interface bounds is not @Nullable.
                      // BUG: Diagnostic contains: type variable E5 of type com.uber.lib.generics.IntersectionTypeParam
                      @Nullable String,
                      String,
                      // E7 has no @Nullable element at all.
                      // BUG: Diagnostic contains: type variable E7 of type com.uber.lib.generics.IntersectionTypeParam
                      @Nullable String,
                      // E8 has three elements, and one of them is not @Nullable.
                      // BUG: Diagnostic contains: type variable E8 of type com.uber.lib.generics.IntersectionTypeParam
                      @Nullable String> p) {}
            }
            """)
        .doTest();
  }

  @Test
  public void innerClassBoundFromBytecodeAcceptsNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.InnerClassBound;
            import org.jspecify.annotations.Nullable;
            class Test {
              static void test(InnerClassBound.Box<InnerClassBound.@Nullable Inner> p) {}
            }
            """)
        .doTest();
  }

  @Test
  public void annotationOnEnclosingTypeOfBoundFromBytecodeRefusesNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.InnerClassBound;
            import org.jspecify.annotations.Nullable;
            class Test {
              // BUG: Diagnostic contains: type variable T of type com.uber.lib.generics.InnerClassBound.OuterAnnotatedBox
              static void test(InnerClassBound.OuterAnnotatedBox<InnerClassBound.@Nullable Inner> p) {}
            }
            """)
        .doTest();
  }

  @Test
  public void
      annotationOnEnclosingTypeOfIntersectionElementFromBytecodeRefusesNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.InnerClassBound;
            import com.uber.lib.generics.InnerClassBound.OuterAnnotatedIntersectionBox;
            import org.jspecify.annotations.Nullable;
            class Test {
              // BUG: Diagnostic contains: type variable T of type com.uber.lib.generics.InnerClassBound.OuterAnnotatedIntersectionBox
              static void test(OuterAnnotatedIntersectionBox<InnerClassBound.@Nullable Inner> p) {}
            }
            """)
        .doTest();
  }

  @Test
  public void boundWithNullableTypeArgumentFromBytecodeRefusesNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.NestedNullableInBound;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Test {
              interface Strings extends List<@Nullable String> {}
              // BUG: Diagnostic contains: type variable T of type com.uber.lib.generics.NestedNullableInBound
              static void test(NestedNullableInBound<@Nullable Strings> p) {}
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
