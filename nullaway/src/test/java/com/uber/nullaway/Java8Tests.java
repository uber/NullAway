package com.uber.nullaway;

import org.junit.Test;

public class Java8Tests extends NullAwayTestsBase {
  @Test
  public void java8PositiveCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayJava8PositiveCases.java",
            """
            package com.uber.nullaway.testdata;

            import io.reactivex.functions.BiFunction;
            import io.reactivex.functions.Function;
            import javax.annotation.Nonnull;
            import javax.annotation.Nullable;

            public class NullAwayJava8PositiveCases {

              @FunctionalInterface
              interface RetNonNullFunction {

                Object getVal();
              }

              public static void testRetNonNull() {
                RetNonNullFunction p =
                    () -> {
                      // BUG: Diagnostic contains: returning @Nullable expression from method with
                      return null;
                    };
                p.getVal();
              }

              @FunctionalInterface
              interface NullableParamFunction<T, U> {

                U takeVal(@Nullable T x);
              }

              @FunctionalInterface
              interface NonNullParamFunction {

                String takeVal(Object x);
              }

              static void testNullableParam() {
                // BUG: Diagnostic contains: dereferenced expression x is @Nullable
                NullableParamFunction n = (x) -> x.toString();
                // BUG: Diagnostic contains: parameter x is @NonNull, but parameter in functional interface
                NullableParamFunction n2 = (Object x) -> x.toString();
                // BUG: Diagnostic contains: dereferenced expression x is @Nullable
                NonNullParamFunction n3 = (@Nullable Object x) -> x.toString();
                // BUG: Diagnostic contains: parameter x is @NonNull, but parameter in functional interface
                NullableParamFunction n4 = (@Nonnull Object x) -> x.toString();
              }

              static void testAnnoatedThirdParty() {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
                Function<String, Object> f1 = (x) -> null; // io.reactivex.(Bi)Function is anotated
                Function<String, Object> f2 =
                    (x) -> {
                      // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull
                      return null;
                    };
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
                BiFunction<String, String, Object> f3 = (x, y) -> null;
              }

              ////////////////////////
              // method references  //
              ////////////////////////

              interface Function<T, R> {
                R apply(T t);
              }

              static <R, T> R map(T t, Function<T, R> fun) {
                return fun.apply(t);
              }

              static <T, U> U applyTakeVal(NullableParamFunction<T, U> nn) {
                return nn.takeVal(null);
              }

              @Nullable
              static Object returnNull(String t) {
                return null;
              }

              static String derefParam(Object o) {
                return o.toString();
              }

              static void testRefsToStaticMethods() {
                String ex = "hi";
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional
                map(ex, NullAwayJava8PositiveCases::returnNull);
                // BUG: Diagnostic contains: parameter o of referenced method is @NonNull, but
                applyTakeVal(NullAwayJava8PositiveCases::derefParam);
              }

              @FunctionalInterface
              interface NullableSecondParamFunction<T> {

                String takeVal(T x, @Nullable Object y);
              }

              static <T> String applyDoubleTakeVal(NullableSecondParamFunction<T> ns, T firstParam) {
                return ns.takeVal(firstParam, null);
              }

              static class MethodContainer {

                @Nullable
                Object returnNull(String t) {
                  return null;
                }

                @Nullable
                String returnNullWithNullableParam(@Nullable Object t) {
                  return null;
                }

                String derefSecondParam(Object w, Object z) {
                  return z.toString();
                }

                String derefParam(Object p) {
                  return p.toString();
                }

                String makeStr() {
                  return "buzz";
                }

                void testRefsToInstanceMethods() {
                  String ex = "bye";
                  MethodContainer m = new MethodContainer();
                  // BUG: Diagnostic contains: referenced method returns @Nullable, but functional
                  map(ex, m::returnNull);
                  // BUG: Diagnostic contains: parameter z of referenced method is @NonNull, but
                  applyDoubleTakeVal(m::derefSecondParam, new Object());
                  // BUG: Diagnostic contains: parameter p of referenced method is @NonNull, but parameter in
                  applyDoubleTakeVal(MethodContainer::derefParam, m);
                  // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface
                  applyDoubleTakeVal(MethodContainer::returnNullWithNullableParam, m);
                  // BUG: Diagnostic contains: unbound instance method reference cannot be used
                  applyTakeVal(MethodContainer::makeStr);
                }
              }

              static class MethodContainerSub extends MethodContainer {
                @Override
                String derefSecondParam(Object w, @Nullable Object z) {
                  return "" + ((z != null) ? z.hashCode() : 10);
                }

                void testSuperRef() {
                  // BUG: Diagnostic contains: parameter z of referenced method is @NonNull, but parameter
                  applyDoubleTakeVal(super::derefSecondParam, new Object());
                }
              }

              static class ConstructorRefs {

                public ConstructorRefs(Object p) {}

                class Inner {

                  public Inner(Object q) {}
                }

                void testConstRefs() {
                  // BUG: Diagnostic contains: parameter p of referenced method is @NonNull, but parameter
                  applyTakeVal(ConstructorRefs::new);
                  // BUG: Diagnostic contains: parameter q of referenced method is @NonNull, but parameter
                  applyTakeVal(Inner::new);
                }
              }
            }

            """)
        .doTest();
  }

  @Test
  public void java8NegativeCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayJava8NegativeCases.java",
            """
            package com.uber.nullaway.testdata;

            import java.util.Collections;
            import java.util.Comparator;
            import java.util.List;
            import java.util.function.BiFunction;
            import javax.annotation.Nullable;

            public class NullAwayJava8NegativeCases {

              @FunctionalInterface
              interface RetNullableFunction {

                @Nullable
                Object getVal();
              }

              public static void testLambda() {
                RetNullableFunction p =
                    () -> {
                      return null;
                    };
                p.getVal();
              }

              @FunctionalInterface
              interface NonNullParamFunction {

                String takeVal(Object x);
              }

              @FunctionalInterface
              interface NullableParamFunction<T, U> {

                U takeVal(@Nullable T x);
              }

              static void testNonNullParam() {
                NonNullParamFunction n = (x) -> x.toString();
                NonNullParamFunction n2 = (@Nullable Object x) -> (x == null) ? "null" : x.toString();
                NullableParamFunction n3 = (@Nullable Object x) -> (x == null) ? "null" : x.toString();
                NullableParamFunction n4 = (x) -> (x == null) ? "null" : x.toString();
              }

              static void testBuiltIn() {
                java.util.function.Function<String, String> foo = (x) -> x.toString();
                BiFunction<String, Object, String> bar = (x, y) -> x.toString() + y.toString();
                java.util.function.Function<String, Object> foo2 = (x) -> null; // java.util.Function is
                // unnanotated
                java.util.function.Function<String, Object> foo3 =
                    (x) -> {
                      return null;
                    };
              }

              static class Size {
                public Size(int h, int w) {
                  this.height = h;
                  this.width = w;
                }

                public final int height;
                public final int width;
              }

              static void testSort(List<Integer> intList, List<Size> sizeList) {
                Collections.sort(
                    intList,
                    (a, b) -> {
                      return (b - a);
                    });
                Collections.sort(
                    intList,
                    (a, b) -> {
                      return a;
                    });
                Collections.sort(
                    sizeList,
                    (a, b) -> {
                      int aPixels = a.height * a.width;
                      int bPixels = b.height * b.width;
                      if (bPixels < aPixels) {
                        return -1;
                      }
                      if (bPixels > aPixels) {
                        return 1;
                      }
                      return 0;
                    });
              }

              static Comparator<Integer> testLambdaExpressionsAreNotNull() {
                return (a, b) -> {
                  return (b - a);
                };
              }

              @FunctionalInterface
              interface VoidFunction {

                void doSomething();
              }

              static void wrapDoSomething() {
                RetNullableFunction r = () -> null;
                VoidFunction v = () -> r.getVal();
                v.doSomething();
              }

              ////////////////////////
              // method references  //
              ////////////////////////

              interface MyFunction<T, R> {
                R apply(T t);
              }

              static <R, T> R map(T t, MyFunction<T, R> fun) {
                return fun.apply(t);
              }

              static <T, U> U applyTakeVal(NullableParamFunction<T, U> nn) {
                return nn.takeVal(null);
              }

              static Object returnNonNull(String t) {
                return new Object();
              }

              static String derefParam(@Nullable Object o) {
                return o != null ? o.toString() : "";
              }

              static void testRefsToStaticMethods() {
                String ex = "hi";
                map(ex, NullAwayJava8NegativeCases::returnNonNull);
                applyTakeVal(NullAwayJava8NegativeCases::derefParam);
              }

              @FunctionalInterface
              interface NullableSecondParamFunction<T> {

                String takeVal(T x, @Nullable Object y);
              }

              static <T> String applyDoubleTakeVal(NullableSecondParamFunction<T> ns, T firstParam) {
                return ns.takeVal(firstParam, null);
              }

              static class MethodContainer {

                Object returnNonNull(String t) {
                  return new Object();
                }

                String returnNonNullWithNullableParam(@Nullable Object t) {
                  return "";
                }

                String derefSecondParam(Object w, @Nullable Object z) {
                  return z != null ? z.toString() : w.toString();
                }

                String derefSecondParam2(Object w, Object z) {
                  return z.toString();
                }

                String derefParam(@Nullable Object p) {
                  return (p != null) ? p.toString() : "";
                }

                String makeStr() {
                  return "buzz";
                }

                void testRefsToInstanceMethods() {
                  String ex = "bye";
                  MethodContainer m = new MethodContainer();
                  map(ex, m::returnNonNull);
                  applyDoubleTakeVal(m::derefSecondParam, new Object());
                  applyDoubleTakeVal(MethodContainer::derefParam, m);
                  applyDoubleTakeVal(MethodContainer::returnNonNullWithNullableParam, m);
                  map(this, MethodContainer::makeStr);
                }
              }

              static class MethodContainerSub extends MethodContainer {
                @Override
                String derefSecondParam2(Object w, @Nullable Object z) {
                  return "" + ((z != null) ? z.hashCode() : 10);
                }

                void testOverrideWithMethodRef() {
                  applyDoubleTakeVal(this::derefSecondParam2, new Object());
                }
              }

              static class ConstructorRefs {

                public ConstructorRefs(@Nullable Object p) {}

                class Inner {

                  public Inner(@Nullable Object q) {}
                }

                void testConstRefs() {
                  applyTakeVal(ConstructorRefs::new);
                  applyTakeVal(Inner::new);
                }
              }
            }

            """)
        .doTest();
  }

  @Test
  public void functionalMethodSuperInterface() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwaySuperFunctionalInterface.java",
            """
            package com.uber.nullaway.testdata;

            public class NullAwaySuperFunctionalInterface {

              public void passLambda() {
                runLambda1(() -> {});
                runLambda2(() -> {});
              }

              private void runLambda1(F1 f) {
                f.call();
              }

              private void runLambda2(F2 f) {
                f.call();
              }

              @FunctionalInterface
              private static interface F2 extends M0, F1 {}

              private static interface M0 {}

              @FunctionalInterface
              private static interface F1 extends F0 {}

              @FunctionalInterface
              private static interface F0 {
                void call();
              }
            }

            """)
        .doTest();
  }

  @Test
  public void functionalMethodOverrideSuperInterface() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayOverrideFunctionalInterfaces.java",
            """
            package com.uber.nullaway.testdata;

            import java.util.function.ToIntFunction;

            public class NullAwayOverrideFunctionalInterfaces {

              public void test() {
                call(str -> 42);
              }

              private int call(ObjToInt<String> f) {
                return f.call("The answer to life the universe and everything");
              }

              @FunctionalInterface
              private static interface ObjToInt<T> extends ObjToIntE<T, RuntimeException>, ToIntFunction<T> {
                @Override
                default int applyAsInt(T t) {
                  return call(t);
                }
              }

              @FunctionalInterface
              private static interface ObjToIntE<T, E extends Exception> {
                int call(T t) throws E;
              }
            }

            """)
        .doTest();
  }

  @Test
  public void methodReferenceOnNullableVariable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import java.util.Arrays;
            import java.util.stream.Collectors;
            class Test {
              public static boolean testPositive(@Nullable String text, java.util.Set<String> s) {
                // BUG: Diagnostic contains: dereferenced expression text is @Nullable
                return s.stream().anyMatch(text::contains);
              }
              public static String[] testNegative(Object[] arr) {
                // also tests we don't crash when the qualifier expression of a method reference is a type
                return Arrays.stream(arr).map(Object::toString).toArray(String[]::new);
              }
              public static <T> boolean testNegativeWithTypeVariable(T[] arr) {
                return Arrays.stream(arr).map(T::toString).collect(Collectors.toList()).isEmpty();
              }
            }
            """)
        .doTest();
  }

  /** test that we can properly read an explicit type-use annotation on a lambda parameter */
  @Test
  public void testNullableLambdaParamTypeUse() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
                @FunctionalInterface
                interface NullableParamFunctionTypeUse<T, U> {
                  U takeVal(@Nullable T x);
                }
                static void testParamTypeUse() {
                  NullableParamFunctionTypeUse n3 = (@Nullable Object x) -> (x == null) ? "null" : x.toString();
                  NullableParamFunctionTypeUse n4 = (x) -> (x == null) ? "null" : x.toString();
                }
            }
            """)
        .doTest();
  }
}
