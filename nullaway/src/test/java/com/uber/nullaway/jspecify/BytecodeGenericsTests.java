package com.uber.nullaway.jspecify;

import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.scanner.ScannerSupplier;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

public class BytecodeGenericsTests extends NullAwayTestsBase {

  @Test
  public void unboundedWildcardWithNonNullFormalBoundAfterTypeInspection() {
    CompilationTestHelper.newInstance(
            ScannerSupplier.fromBugCheckerClasses(TypeInspectionChecker.class, NullAway.class),
            getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.SeparatelyCompiledQueue;
            class Test {
              static long producerIndex(SeparatelyCompiledQueue<?> queue) {
                return SeparatelyCompiledQueue.producerIndex(queue);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void capturedUnboundedWildcardAfterBackingWildcardTypeInspection() {
    CompilationTestHelper.newInstance(
            ScannerSupplier.fromBugCheckerClasses(TypeInspectionChecker.class, NullAway.class),
            getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.generics.SeparatelyCompiledQueue;
            class Test {
              static <T> T identity(T value) {
                return value;
              }
              static long producerIndex(SeparatelyCompiledQueue<?> queue) {
                // identity() returns an already captured form of queue's wildcard.
                return SeparatelyCompiledQueue.producerIndex(identity(queue));
              }
            }
            """)
        .doTest();
  }

  /**
   * Performs the type inspection that exposes javac's mutable wildcard bound to NullAway.
   *
   * <p>This is the relevant behavior reduced from Error Prone's {@code PreferTestParameter}
   * checker. Calling {@link com.sun.tools.javac.code.Types#unboxedTypeOrType(Type)} searches the
   * parameter type's supertypes and can recontextualize a shared wildcard's {@code bound} field.
   */
  @BugPattern(summary = "Inspects single-parameter method types", severity = SUGGESTION)
  public static final class TypeInspectionChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      if (tree.getParameters().size() == 1) {
        Type parameterType = ASTHelpers.getType(tree.getParameters().get(0));
        if (parameterType != null) {
          state.getTypes().unboxedTypeOrType(parameterType);
        }
      }
      return NO_MATCH;
    }
  }

  @Test
  public void basicTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.NonNullTypeParam;
            import com.uber.lib.generics.NullableTypeParam;
            class Test {
              // BUG: Diagnostic contains: Generic type parameter
              static void testBadNonNull(NonNullTypeParam<@Nullable String> t1) {
                // BUG: Diagnostic contains: Generic type parameter
                NonNullTypeParam<@Nullable String> t2 = null;
                NullableTypeParam<@Nullable String> t3 = null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void multipleTypeParametersInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.MixedTypeParam;
            class Test {
              static class PartiallyInvalidSubclass
                  // BUG: Diagnostic contains: Generic type parameter
                  extends MixedTypeParam<@Nullable String, String, String, @Nullable String> {}
              static class ValidSubclass1
                  extends MixedTypeParam<String, @Nullable String, @Nullable String, String> {}
              static class PartiallyInvalidSubclass2
                  extends MixedTypeParam<
                      String,
                      String,
                      String,
                      // BUG: Diagnostic contains: Generic type parameter
                      @Nullable String> {}
              static class ValidSubclass2 extends MixedTypeParam<String, String, String, String> {}
            }
            """)
        .doTest();
  }

  @Test
  public void genericsChecksForAssignments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.NullableTypeParam;
            class Test {
              static void testPositive(NullableTypeParam<@Nullable String> t1) {
                // BUG: Diagnostic contains: incompatible types: NullableTypeParam<@Nullable String>
                NullableTypeParam<String> t2 = t1;
              }
              static void testNegative(NullableTypeParam<@Nullable String> t1) {
                NullableTypeParam<@Nullable String> t2 = t1;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericsChecksForFieldAssignments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.NullableTypeParam;
            class Test {
              static void testPositive(NullableTypeParam<String> t1) {
                // BUG: Diagnostic contains: incompatible types: NullableTypeParam<String>
                NullableTypeParam.staticField = t1;
                // BUG: Diagnostic contains: incompatible types: NullableTypeParam<@Nullable String>
                NullableTypeParam<String> t2 = NullableTypeParam.staticField;
              }
              static void testNegative(NullableTypeParam<@Nullable String> t1) {
                NullableTypeParam.staticField = t1;
                NullableTypeParam<@Nullable String> t2 = NullableTypeParam.staticField;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericsChecksForParamPassingAndReturns() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.NullableTypeParam;
            import com.uber.lib.generics.GenericTypeArgMethods;
            class Test {
              static void testPositive(NullableTypeParam<String> t1) {
                // BUG: Diagnostic contains: incompatible types: NullableTypeParam<String>
                GenericTypeArgMethods.nullableTypeParamArg(t1);
                // BUG: Diagnostic contains: incompatible types: NullableTypeParam<@Nullable String>
                NullableTypeParam<String> t2 = GenericTypeArgMethods.nullableTypeParamReturn();
              }
              static void testNegative(NullableTypeParam<@Nullable String> t1) {
                GenericTypeArgMethods.nullableTypeParamArg(t1);
                NullableTypeParam<@Nullable String> t2 = GenericTypeArgMethods.nullableTypeParamReturn();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideParameterType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.Fn;
            class Test {
             static class TestFunc1 implements Fn<@Nullable String, String> {
              @Override
              // BUG: Diagnostic contains: parameter s is
              public String apply(String s) {
               return s;
              }
             }
             static class TestFunc2 implements Fn<@Nullable String, String> {
              @Override
              public String apply(@Nullable String s) {
               return "hi";
              }
             }
             static class TestFunc3 implements Fn<String, String> {
              @Override
              public String apply(String s) {
               return "hi";
              }
             }
             static class TestFunc4 implements Fn<String, String> {
              // this override is legal, we should get no error
              @Override
              public String apply(@Nullable String s) {
               return "hi";
              }
             }
             static void useTestFunc(String s) {
                Fn<@Nullable String, String> f1 = new TestFunc2();
                // should get no error here
                f1.apply(null);
                Fn<String, String> f2 = new TestFunc3();
                // BUG: Diagnostic contains: passing @Nullable parameter
                f2.apply(null);
             }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideReturnTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.Fn;
            class Test {
             static class TestFunc1 implements Fn<String, @Nullable String> {
              @Override
              public @Nullable String apply(String s) {
               return s;
              }
             }
             static class TestFunc2 implements Fn<String, @Nullable String> {
              @Override
              public String apply(String s) {
               return s;
              }
             }
             static class TestFunc3 implements Fn<String, String> {
              @Override
              // BUG: Diagnostic contains: method returns @Nullable, but superclass
              public @Nullable String apply(String s) {
               return s;
              }
             }
             static class TestFunc4 implements Fn<@Nullable String, String> {
              @Override
              // BUG: Diagnostic contains: method returns @Nullable, but superclass
              public @Nullable String apply(String s) {
               return s;
              }
             }
             static void useTestFunc(String s) {
                Fn<String, @Nullable String> f1 = new TestFunc1();
                String t1 = f1.apply(s);
                // BUG: Diagnostic contains: dereferenced expression
                t1.hashCode();
                TestFunc2 f2 = new TestFunc2();
                String t2 = f2.apply(s);
                // There should not be an error here
                t2.hashCode();
                Fn<String, @Nullable String> f3 = new TestFunc2();
                String t3 = f3.apply(s);
                // BUG: Diagnostic contains: dereferenced expression
                t3.hashCode();
                // BUG: Diagnostic contains: dereferenced expression
                f3.apply(s).hashCode();
             }
            }
            """)
        .doTest();
  }

  @Test
  public void callMethodTakingJavaUtilFunction() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import com.uber.lib.generics.JavaUtilFunctionMethods;
            class Test {
              static void testNegative() {
                JavaUtilFunctionMethods.withFunction(s -> { return null; });
              }
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
