package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Test;

public class InitializationTests extends NullAwayTestsBase {
  /**
   * Adds the shared {@code Util} source used by read-before-initialization tests.
   *
   * @param helper compilation helper to configure
   * @return the same helper, with the shared utility source added
   */
  private CompilationTestHelper addReadBeforeInitUtil(CompilationTestHelper helper) {
    String utilSource =
        """
        package com.uber.nullaway.testdata;

        import javax.annotation.Nullable;

        public class Util {

          public static <T> T castToNonNull(@Nullable T x) {
            if (x == null) {
              throw new RuntimeException();
            }
            return x;
          }

          public static <T> T castToNonNull(@Nullable T x, String msg) {
            if (x == null) {
              throw new RuntimeException(msg);
            }
            return x;
          }

          public static <T> T castToNonNull(String msg, @Nullable T x, int counter) {
            // counter is needed to distinguish this method from the previous one when T == String
            if (x == null) {
              throw new RuntimeException(msg);
            }
            return x;
          }

          public static <T> T id(T x) {
            return x;
          }
        }
        """;
    return helper.addSourceLines("Util.java", utilSource);
  }

  @Test
  public void initFieldPositiveCases() {
    defaultCompilationHelper
        .addSourceLines(
            "CheckFieldInitPositiveCases.java",
            """
            package com.uber.nullaway.testdata;

            import com.uber.nullaway.annotations.Initializer;
            import javax.annotation.Nullable;

            /** Created by msridhar on 3/7/17. */
            public class CheckFieldInitPositiveCases {

              static class T1 {

                Object f;

                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field 'f' (line 11) is
                // initialized
                T1() {}
              }

              static class T2 {

                Object f, g;

                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull fields 'f' (line 20),
                // 'g' (line 20) are
                // initialized
                T2() {}
              }

              static class T3 {

                // BUG: Diagnostic contains: @NonNull field 'CheckFieldInitPositiveCases$T3.f' not initialized
                Object f;
              }

              static class T4 {

                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                Object f = null;

                @Nullable
                static Object returnNull() {
                  return null;
                }

                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                Object g = returnNull();
              }

              static class T5 {

                Object f;

                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field 'f' (line 50) is
                // initialized
                T5(boolean b) {
                  if (b) {
                    this.f = new Object();
                  }
                }
              }

              static class T6 {

                Object f;

                T6() {
                  // to test detection of this() call
                  this(false);
                }

                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field 'f' (line 63) is
                // initialized
                T6(boolean b) {}
              }

              static class T7 {

                Object f;
                Object g;

                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field 'f' (line 77) is
                // initialized
                T7(boolean b) {
                  if (b) {
                    init();
                  }
                  g = new Object();
                }

                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field 'g' (line 78)
                // is
                // initialized
                T7() {
                  init();
                  init2();
                }

                private void init() {
                  f = new Object();
                }

                public void init2() {
                  g = new Object();
                }
              }

              static class T8 {

                Object f;

                @Initializer
                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field 'f' (line 108)
                // is
                // initialized
                public void init() {}
              }

              static class T9 {

                // BUG: Diagnostic contains: @NonNull static field 'CheckFieldInitPositiveCases$T9.f' not
                // initialized
                static Object f;

                static {
                }
              }

              static class T10 {

                // BUG: Diagnostic contains: @NonNull static field 'CheckFieldInitPositiveCases$T10.f' not
                // initialized
                static Object f;

                static {
                }

                @Initializer
                static void init() {}
              }

              static class T11 {

                // BUG: Diagnostic contains: @NonNull static field 'CheckFieldInitPositiveCases$T11.f' not
                // initialized
                static Object f;

                static {
                }

                @Initializer
                static void init(Object f) {
                  f = new Object(); // Wrong f
                }
              }

              public Object getT12() {
                return (new Object() {
                  /*T12*/
                  // BUG: Diagnostic contains: .f' not initialized
                  private Object f;

                  public Object getF() {
                    return f;
                  }

                  public void setF(Object f) {
                    this.f = f;
                  }
                });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void initFieldNegativeCases() {
    defaultCompilationHelper
        .addSourceLines(
            "CheckFieldInitNegativeCases.java",
            """
            package com.uber.nullaway.testdata;

            import com.google.errorprone.annotations.concurrent.LazyInit;
            import com.uber.nullaway.annotations.Initializer;
            import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
            import org.junit.Before;
            import org.junit.BeforeClass;
            import org.junit.jupiter.api.BeforeAll;
            import org.junit.jupiter.api.BeforeEach;

            /** Created by msridhar on 3/8/17. */
            public class CheckFieldInitNegativeCases {

              class T1 {

                boolean boolField;

                Object f = new Object();

                Object g;

                Object h;

                Object k;

                @jakarta.inject.Inject Object m;

                @javax.inject.Inject Object n;

                @LazyInit Object lazy;

                T1(Object h, Object k, boolean b) {
                  g = new Object();
                  this.h = h;
                  if (b) {
                    this.k = k;
                  } else {
                    this.k = new Object();
                  }
                }
              }

              class T2 {

                Object f;

                T2() {}

                @Initializer
                void init() {
                  this.f = new Object();
                }
              }

              class T3 {

                Object f, g;

                T3() {}

                @Initializer
                void init1() {
                  this.f = new Object();
                }

                @Initializer
                void init2() {
                  this.g = new Object();
                }
              }

              class T4 {

                Object f;

                T4() {
                  init();
                }

                private void init() {
                  f = new Object();
                }
              }

              class T5 {

                Object f;
                Object g;

                @Initializer
                public void init1() {
                  init();
                  init2();
                }

                private void init() {
                  f = new Object();
                }

                public final void init2() {
                  g = new Object();
                }
              }

              static class T6 {

                Object f;
                static Object g;

                T6() {}

                @Before
                void init1() {
                  this.f = new Object();
                }

                @BeforeClass
                static void init2() {
                  T6.g = new Object();
                }
              }

              static class T7 {

                Object f;
                static Object g;

                T7() {}

                @BeforeEach
                void init1() {
                  this.f = new Object();
                }

                @BeforeAll
                static void init2() {
                  T7.g = new Object();
                }
              }

              final class T8 {

                Object f;

                @Initializer
                public void init1() {
                  init();
                }

                public void init() {
                  f = new Object();
                }
              }

              final class T9 {

                Object f;

                public T9() {
                  init();
                }

                public void init() {
                  f = new Object();
                }
              }

              abstract class Super {

                // to test known initializer methods
                abstract void doInit();
              }

              interface SuperInterface {

                // to test known initializer methods
                void doInit2();
              }

              class Sub extends Super implements SuperInterface {

                Object anotherField;
                Object yetAnotherField;

                @Override
                void doInit() {
                  anotherField = new Object();
                }

                @Override
                public void doInit2() {
                  yetAnotherField = new Object();
                }
              }

              static class StaticInitializerBlock {
                static Object f;

                static {
                  f = new Object();
                }
              }

              static class StaticInitializerBlockMultiple {
                static Object f;

                static {
                  assert true; // Do nothing
                }

                static {
                  f = new Object();
                }

                static {
                  assert true; // Do nothing
                }
              }

              static class StaticInitializer {
                static Object f;

                @Initializer
                static void init() {
                  f = new Object();
                }
              }

              static class StaticInitializerExplicitClass {
                static Object f;

                @Initializer
                static void init(Object f) {
                  StaticInitializerExplicitClass.f = new Object();
                }
              }

              static class InstInitBlock {

                Object f;

                {
                  f = new Object();
                }
              }

              static class SuppressWarningsA {

                Object f; // Should be an error, but we are suppressing

                @SuppressWarnings("NullAway")
                SuppressWarningsA() {}
              }

              static class SuppressWarningsB {

                Object f; // Should be an error, but we are suppressing

                @SuppressWarnings("NullAway.Init")
                SuppressWarningsB() {}
              }

              static class SuppressWarningsC {

                @SuppressWarnings("NullAway.Init")
                static Object f; // Should be an error, but we are suppressing

                static {
                  assert true; // Do nothing
                }
              }

              static class SuppressWarningsD {

                @SuppressWarnings("NullAway.Init")
                static Object f; // Should be an error, but we are suppressing

                static {
                  assert true; // Do nothing
                }

                @Initializer
                static void init() {}
              }

              public class SuppressWarningsE {

                @SuppressWarnings("NullAway.Init")
                private Object f;

                SuppressWarningsE(final Object f) {
                  this.setF(f);
                }

                @SuppressWarnings("NullAway.Init")
                protected SuppressWarningsE() {}

                public void setF(final Object f) {
                  this.f = f;
                }
              }

              static class MonotonicNonNullUsage {

                @MonotonicNonNull Object f;

                @com.uber.nullaway.annotations.MonotonicNonNull Object g;

                MonotonicNonNullUsage() {}
              }

              @SuppressWarnings("NullAway.Init")
              public Object getSuppressWarningsF() {
                return (new Object() {
                  /*SuppressWarningsF*/
                  private Object f;

                  public Object getF() {
                    return f;
                  }

                  public void setF(Object f) {
                    this.f = f;
                  }
                });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void readBeforeInitPositiveCases() {
    addReadBeforeInitUtil(defaultCompilationHelper)
        .addSourceLines(
            "ReadBeforeInitPositiveCases.java",
            """
            package com.uber.nullaway.testdata;

            import com.uber.nullaway.annotations.Initializer;

            public class ReadBeforeInitPositiveCases {

              class T1 {

                Object f;
                Object g;

                T1() {
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  System.out.println(f.toString());
                  f = new Object();
                  // BUG: Diagnostic contains: read of @NonNull field 'g' before
                  System.out.println(g.toString());
                  g = new Object();
                }

                T1(boolean b) {
                  if (b) {
                    f = new Object();
                  }
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  System.out.println(f.toString());
                  f = new Object();
                  g = new Object();
                }
              }

              static class T2 {

                Object f;
                Object f2;

                {
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  System.out.println(f.toString());
                }

                // BUG: Diagnostic contains: read of @NonNull field 'f2' before
                Object g = f2;

                // BUG: Diagnostic contains: read of @NonNull field 'f2' before
                Object h = str(f2);

                T2() {
                  f = "hi";
                  f2 = "byte";
                }

                static String str(Object o) {
                  return o.toString();
                }
              }

              static class StaticStuff {

                static Object f;
                static Object f2;

                static {
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  System.out.println(f.toString());
                }

                // BUG: Diagnostic contains: read of @NonNull field 'f2' before
                static Object g = f2;

                static {
                  f = "hi";
                  f2 = "byte";
                }
              }

              class InvokePrivate {

                Object f;
                Object g;

                InvokePrivate() {
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  f.toString();
                  initF();
                  initG();
                  g.toString();
                }

                private void initF() {
                  f = "boo";
                }

                private void initG() {
                  g = "boo";
                }
              }

              static class StoreInLocal {

                Object f;

                StoreInLocal() {
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  Object x = this.f;
                  x.toString();
                  this.f = new Object();
                }
              }

              static class NestedWrite {

                NestedWrite foo;
                Object baz;

                NestedWrite() {
                  // BUG: Diagnostic contains: read of @NonNull field 'foo' before
                  this.foo.baz = new Object();
                  this.foo = new NestedWrite();
                  this.baz = new Object();
                }
              }

              static class SingleInitializer {

                Object f;

                @Initializer
                public void init() {
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  f.toString();
                  f = new Object();
                }
              }

              static class SingleInitializer2 {

                Object f;
                Object g;

                SingleInitializer2() {
                  f = new Object();
                  g = new Object();
                }

                SingleInitializer2(boolean b) {
                  if (b) {
                    f = new Object();
                  }
                  g = new Object();
                }

                @Initializer
                public void init() {
                  g.toString();
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  f.toString();
                }
              }

              static class SingleStaticInitializer {

                static Object f;
                static Object g;

                @Initializer
                static void init() {
                  g.toString();
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  f.toString();
                }

                static {
                  g = new Object();
                }
              }

              static class StaticCallTest {

                Object f;

                @Initializer
                void init() {
                  // BUG: Diagnostic contains: read of @NonNull field 'f' before
                  f = Util.id(f);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void readBeforeInitNegativeCases() {
    addReadBeforeInitUtil(defaultCompilationHelper)
        .addSourceLines(
            "ReadBeforeInitNegativeCases.java",
            """
            package com.uber.nullaway.testdata;

            import static com.uber.nullaway.testdata.Util.castToNonNull;

            import com.uber.nullaway.annotations.Initializer;
            import javax.annotation.Nullable;

            public class ReadBeforeInitNegativeCases {

              class T1 {

                Object f;
                Object g;

                T1() {
                  f = new Object();
                  g = new Object();
                  System.out.println(f.toString());
                  System.out.println(g.toString());
                }

                T1(boolean b) {
                  if (b) {
                    f = new Object();
                  } else {
                    f = "hello";
                  }
                  System.out.println(f.toString());
                  if (b) {
                    g = "hello";
                    System.out.println(g.toString());
                  }
                  g = "goodbye";
                }
              }

              class T2 {

                Object f = new Object();

                T2() {
                  System.out.println(f.toString());
                }
              }

              class T3 {

                Object f;

                T3() {
                  System.out.println(f.toString());
                }

                {
                  f = new Object();
                }
              }

              static class StaticStuff {

                static Object f;

                static void foo() {
                  System.out.println(f.toString());
                  System.out.println(g.toString());
                }

                static Object g = "fizz";

                static {
                  f = new Object();
                }

                static Object h;

                static {
                  h = "hello";
                  h.toString();
                }
              }

              class AnonymousInner {

                Runnable r1, r2;
                Object f;

                AnonymousInner() {
                  r1 =
                      new Runnable() {
                        @Override
                        public void run() {
                          System.out.println(f.toString());
                        }
                      };
                  r2 = () -> System.out.println(f.toString());
                  // false negative that we miss
                  r2.run();
                  f = new Object();
                }
              }

              class InvokePrivate {

                Object f;

                InvokePrivate() {
                  initF();
                  f.toString();
                }

                private void initF() {
                  f = "boo";
                }
              }

              static class ReadSuppressedStaticFromConstructor {

                @SuppressWarnings("NullAway.Init")
                static Object foo;

                ReadSuppressedStaticFromConstructor() {
                  foo.toString();
                }
              }

              static class NestedWrite {

                NestedWrite foo;
                Object baz;

                NestedWrite() {
                  this.foo = new NestedWrite();
                  this.foo.toString();
                  this.baz = new Object();
                  this.baz.hashCode();
                }

                NestedWrite(NestedWrite other) {
                  // safe, as other has already been initialized
                  other.foo.baz = new Object();
                  this.foo = new NestedWrite();
                  this.baz = new Object();
                }
              }

              static class SingleInitializer {

                Object f;

                SingleInitializer() {
                  f = new Object();
                }

                @Initializer
                public void init() {
                  f.toString();
                }
              }

              static class SingleInitializer2 {

                Object f;
                Object g;

                SingleInitializer2() {
                  f = new Object();
                  g = new Object();
                }

                SingleInitializer2(boolean b) {
                  if (b) {
                    f = new Object();
                  } else {
                    f = "hi";
                  }
                  g = new Object();
                }

                @Initializer
                public void init() {
                  g.toString();
                  f.toString();
                }
              }

              static class SingleStaticInitializer {

                static Object f;
                static Object g;

                @Initializer
                static void init() {
                  g.toString();
                  f.toString();
                }

                static {
                  f = new Object();
                  g = new Object();
                }
              }

              static class CompareToNullInInit {

                Object f;

                @Initializer
                void init() {
                  if (f == null) {
                    f = new Object();
                  }
                }
              }

              static class CompareToNullInInit2 {

                Object f;

                @Initializer
                void init() {
                  if (null == f) {
                    f = new Object();
                  }
                }
              }

              static class CompareToNullInInit3 {

                Object f;

                @Initializer
                void init() {
                  if (!(f != null)) {
                    f = new Object();
                  }
                }
              }

              static class CastToNonNullTest {

                Object castF;
                Object castG;

                @Initializer
                void init(@Nullable Object o) {
                  if (o != null) {
                    castF = castToNonNull(castF);
                    castG = castToNonNull(castG);
                    return;
                  }
                  castF = "hi";
                  castG = "bye";
                }
              }

              // https://github.com/uber/NullAway/issues/347
              static class ReadInsideAssert {

                Object f;

                public ReadInsideAssert(Object o) {
                  this.f = o;
                  if (this.f.toString() != "") throw new Error();
                  assert this.f.toString() != "";
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void externalInitSupport() {
    defaultCompilationHelper
        .addSourceLines(
            "ExternalInit.java",
            """
            package com.uber;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface ExternalInit {}
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            @ExternalInit
            class Test {
              Object f;
            // no error here due to external init
              public Test() {}
              // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field
              public Test(int x) {}
            }
            """)
        .addSourceLines(
            "Test2.java",
            """
            package com.uber;
            @ExternalInit
            class Test2 {
            // no error here due to external init
              Object f;
            }
            """)
        .addSourceLines(
            "Test3.java",
            """
            package com.uber;
            @ExternalInit
            class Test3 {
              Object f;
              // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field
              public Test3(int x) {}
            }
            """)
        .doTest();
  }

  @Test
  public void externalInitSupportConstructors() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ExternalInitAnnotations=com.uber.ExternalInitConstructor"))
        .addSourceLines(
            "ExternalInitConstructor.java",
            """
            package com.uber;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
            public @interface ExternalInitConstructor {}
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            class Test {
              Object f;
            // no error here due to external init
              @ExternalInitConstructor
              public Test() {}
              // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field
              public Test(int x) {}
              public Test(Object o) { this.f = o; }
            }
            """)
        .addSourceLines(
            "Test2.java",
            """
            package com.uber;
            class Test2 {
              // BUG: Diagnostic contains: @NonNull field 'f' not initialized
              Object f;
            // must be on a constructor!
              @ExternalInitConstructor
              public void init() {}
            }
            """)
        .addSourceLines(
            "Test3.java",
            """
            package com.uber;
            class Test3 {
              Object f;
            // Must be zero-args constructor!
              @ExternalInitConstructor
              // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field
              public Test3(int x) {}
            }
            """)
        .doTest();
  }

  @Test
  public void externalInitSupportFields() {
    defaultCompilationHelper
        .addSourceLines(
            "ExternalFieldInit.java",
            """
            package com.uber;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface ExternalFieldInit {}
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            class Test {
              @ExternalFieldInit Object f;
            // no error here due to external init
              public Test() {}
            // no error here due to external init
              public Test(int x) {}
            }
            """)
        .addSourceLines(
            "Test2.java",
            """
            package com.uber;
            class Test2 {
            // no error here due to external init
              @ExternalFieldInit Object f;
            }
            """)
        .addSourceLines(
            "Test3.java",
            """
            package com.uber;
            class Test3 {
              @ExternalFieldInit Object f;
            // no error here due to external init
              @ExternalFieldInit // See GitHub#184
              public Test3() {}
            // no error here due to external init
              @ExternalFieldInit // See GitHub#184
              public Test3(int x) {}
            }
            """)
        .doTest();
  }

  @Test
  public void testEnumInit() {
    defaultCompilationHelper
        .addSourceLines(
            "SomeEnum.java",
            """
            package com.uber;
            import java.util.Random;
            enum SomeEnum {
              FOO, BAR;
              final Object o;
              final Object p;
              private SomeEnum() {
                this.o = new Object();
                this.p = new Object();
                this.o.equals(this.p);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void postConstructAnnotation() {
    defaultCompilationHelper
        .addSourceLines(
            "Test1.java",
            """
            package com.uber;
            import javax.annotation.PostConstruct;
            class Test1 {
              Object f;
              @PostConstruct
              public void init() {
                this.f = new Object();
              }
              public Test1() {}
            }
            """)
        .addSourceLines(
            "Test2.java",
            """
            package com.uber;
            import jakarta.annotation.PostConstruct;
            class Test2 {
              Object f;
              @PostConstruct
              public void init() {
                this.f = new Object();
              }
              public Test2() {}
            }
            """)
        .doTest();
  }
}
