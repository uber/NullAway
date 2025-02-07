package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import java.util.Arrays;
import java.util.List;
import org.junit.Ignore;
import org.junit.Test;

public class GenericsTests extends NullAwayTestsBase {

  @Test
  public void basicTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  // BUG: Diagnostic contains: Generic type parameter",
            "  static void testBadNonNull(NonNullTypeParam<@Nullable String> t1) {",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    NonNullTypeParam<@Nullable String> t2 = null;",
            "    NullableTypeParam<@Nullable String> t3 = null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void constructorTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  static void testOkNonNull(NonNullTypeParam<String> t) {",
            "    NonNullTypeParam<String> t2 = new NonNullTypeParam<String>();",
            "  }",
            "  static void testBadNonNull(NonNullTypeParam<String> t) {",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    NonNullTypeParam<String> t2 = new NonNullTypeParam<@Nullable String>();",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    testBadNonNull(new NonNullTypeParam<@Nullable String>());",
            "    testBadNonNull(",
            "        // BUG: Diagnostic contains: Cannot pass parameter of type NonNullTypeParam<@Nullable String>",
            "        new NonNullTypeParam<",
            "            // BUG: Diagnostic contains: Generic type parameter",
            "            @Nullable String>());",
            "  }",
            "  static void testOkNullable(NullableTypeParam<String> t1, NullableTypeParam<@Nullable String> t2) {",
            "    NullableTypeParam<String> t3 = new NullableTypeParam<String>();",
            "    NullableTypeParam<@Nullable String> t4 = new NullableTypeParam<@Nullable String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void multipleTypeParametersInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class MixedTypeParam<E1, E2 extends @Nullable Object, E3 extends @Nullable Object, E4> {}",
            "  static class PartiallyInvalidSubclass",
            "      // BUG: Diagnostic contains: Generic type parameter",
            "      extends MixedTypeParam<@Nullable String, String, String, @Nullable String> {}",
            "  static class ValidSubclass1",
            "      extends MixedTypeParam<String, @Nullable String, @Nullable String, String> {}",
            "  static class PartiallyInvalidSubclass2",
            "      extends MixedTypeParam<",
            "          String,",
            "          String,",
            "          String,",
            "          // BUG: Diagnostic contains: Generic type parameter",
            "          @Nullable String> {}",
            "  static class ValidSubclass2 extends MixedTypeParam<String, String, String, String> {}",
            "}")
        .doTest();
  }

  @Test
  public void subClassTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  static class SuperClassForValidSubclass {",
            "    static class ValidSubclass extends NullableTypeParam<@Nullable String> {}",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    static class InvalidSubclass extends NonNullTypeParam<@Nullable String> {}",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void interfaceImplementationTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static interface NonNullTypeParamInterface<E> {}",
            "  static interface NullableTypeParamInterface<E extends @Nullable Object> {}",
            "  static class InvalidInterfaceImplementation",
            "      // BUG: Diagnostic contains: Generic type parameter",
            "      implements NonNullTypeParamInterface<@Nullable String> {}",
            "  static class ValidInterfaceImplementation implements NullableTypeParamInterface<String> {}",
            "}")
        .doTest();
  }

  @Test
  public void nestedTypeParams() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  // BUG: Diagnostic contains: Generic type parameter",
            "  static void testBadNonNull(NullableTypeParam<NonNullTypeParam<@Nullable String>> t) {",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    NullableTypeParam<NonNullTypeParam<NonNullTypeParam<@Nullable String>>> t2 = null;",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    t2 = new NullableTypeParam<NonNullTypeParam<NonNullTypeParam<@Nullable String>>>();",
            "    // this is fine",
            "    NullableTypeParam<NonNullTypeParam<NullableTypeParam<@Nullable String>>> t3 = null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void returnTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  // BUG: Diagnostic contains: Generic type parameter",
            "  static NonNullTypeParam<@Nullable String> testBadNonNull() {",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    return new NonNullTypeParam<@Nullable String>();",
            "  }",
            "  static NullableTypeParam<@Nullable String> testOKNull() {",
            "    return new NullableTypeParam<@Nullable String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testOKNewClassInstantiationForOtherAnnotations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import lombok.NonNull;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static class DifferentAnnotTypeParam1<E extends @NonNull Object> {}",
            "  static class DifferentAnnotTypeParam2<@NonNull E> {}",
            "  static void testOKOtherAnnotation(NonNullTypeParam<String> t) {",
            "    // should not show error for annotation other than @Nullable",
            "    testOKOtherAnnotation(new NonNullTypeParam<@NonNull String>());",
            "    DifferentAnnotTypeParam1<String> t1 = new DifferentAnnotTypeParam1<String>();",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    DifferentAnnotTypeParam2<String> t2 = new DifferentAnnotTypeParam2<@Nullable String>();",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    DifferentAnnotTypeParam1<String> t3 = new DifferentAnnotTypeParam1<@Nullable String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedGenericTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class Wrapper<P extends @Nullable Object> {",
            "    abstract class Fn<R extends @Nullable Object> {",
            "      abstract R apply(P p);",
            "    }",
            "  }",
            "  static void param(@Nullable Wrapper<String>.Fn<String> p) {}",
            "  static void positiveParam() {",
            "    Wrapper<@Nullable String>.Fn<String> x = null;",
            "    // BUG: Diagnostic contains: Cannot pass parameter of type Test.Wrapper<@Nullable String>.Fn<String>",
            "    param(x);",
            "  }",
            "  static void positiveAssign() {",
            "    Wrapper<@Nullable String>.Fn<String> p1 = null;",
            "    // BUG: Diagnostic contains: Cannot assign from type Test.Wrapper<@Nullable String>.Fn<String> to type Test.Wrapper<String>.Fn<String>",
            "    Wrapper<String>.Fn<String> p2 = p1;",
            "  }",
            "  static @Nullable Wrapper<String>.Fn<String> positiveReturn() {",
            "    Wrapper<@Nullable String>.Fn<String> p1 = null;",
            "    // BUG: Diagnostic contains: Cannot return expression of type Test.Wrapper<@Nullable String>.Fn<String>",
            "    return p1;",
            "  }",
            "  static void negativeParam() {",
            "    Wrapper<String>.Fn<String> x = null;",
            "    param(x);",
            "  }",
            "  static void negativeAssign() {",
            "    Wrapper<@Nullable String>.Fn<String> p1 = null;",
            "    Wrapper<@Nullable String>.Fn<String> p2 = p1;",
            "  }",
            "  static @Nullable Wrapper<@Nullable String>.Fn<String> negativeReturn() {",
            "    Wrapper<@Nullable String>.Fn<String> p1 = null;",
            "    return p1;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void downcastInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static void instOf(Object o) {",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    Object p = (NonNullTypeParam<@Nullable String>) o;",
            "  }",
            "}")
        .doTest();
  }

  /** check that we don't report errors on invalid instantiations in unannotated code */
  @Test
  public void instantiationInUnannotatedCode() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.other;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static void instOf(Object o) {",
            "    Object p = (NonNullTypeParam<@Nullable String>) o;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericsChecksForAssignments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  static void testPositive(NullableTypeParam<@Nullable String> t1) {",
            "    // BUG: Diagnostic contains: Cannot assign from type NullableTypeParam<@Nullable String>",
            "    NullableTypeParam<String> t2 = t1;",
            "  }",
            "  static void testNegative(NullableTypeParam<@Nullable String> t1) {",
            "    NullableTypeParam<@Nullable String> t2 = t1;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericsChecksForAssignmentsWithNonJSpecifyAnnotations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "class Test {",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  static void testNoWarningForMismatch(NullableTypeParam<@Nullable String> t1) {",
            "    // we still get an error here as we are not forcing use of JSpecify's @Nullable",
            "    // BUG: Diagnostic contains: Cannot assign from type NullableTypeParam<@Nullable String>",
            "    NullableTypeParam<String> t2 = t1;",
            "  }",
            "  static void testNegative(NullableTypeParam<@Nullable String> t1) {",
            "    NullableTypeParam<@Nullable String> t2 = t1;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedChecksForAssignmentsMultipleArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class SampleClass<E extends @Nullable Object> {}",
            "  static class SampleClassMultipleArguments<E1 extends @Nullable Object, E2> {}",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type SampleClassMultipleArguments<SampleClass<SampleClass<String>>",
            "    SampleClassMultipleArguments<SampleClass<SampleClass<@Nullable String>>, String> t1 =",
            "        new SampleClassMultipleArguments<SampleClass<SampleClass<String>>, String>();",
            "  }",
            "  static void testNegative() {",
            "    SampleClassMultipleArguments<SampleClass<SampleClass<@Nullable String>>, String> t1 =",
            "        new SampleClassMultipleArguments<SampleClass<SampleClass<@Nullable String>>, String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void superTypeAssignmentChecksSingleInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object> {}",
            "  class FnImpl implements Fn<@Nullable String, @Nullable String> {}",
            "  void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type Test.FnImpl",
            "    Fn<@Nullable String, String> f = new FnImpl();",
            "  }",
            "  void testNegative() {",
            "    Fn<@Nullable String, @Nullable String> f = new FnImpl();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void superTypeAssignmentChecksMultipleInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn1<P1 extends @Nullable Object, P2 extends @Nullable Object> {}",
            "  interface Fn2<P extends @Nullable Object> {}",
            "  class FnImpl implements Fn1<@Nullable String, @Nullable String>, Fn2<String> {}",
            "  void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    Fn2<@Nullable String> f = new FnImpl();",
            "  }",
            "  void testNegative() {",
            "    Fn2<String> f = new FnImpl();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void superTypeAssignmentChecksMultipleLevelInheritance() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class SuperClassC<P1 extends @Nullable Object> {}",
            "  class SuperClassB<P extends @Nullable Object> extends SuperClassC<P> {}",
            "  class SubClassA<P extends @Nullable Object> extends SuperClassB<P> {}",
            "  class FnImpl1 extends SubClassA<String> {}",
            "  class FnImpl2 extends SubClassA<@Nullable String> {}",
            "  void testPositive() {",
            "    SuperClassC<@Nullable String> f;",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    f = new FnImpl1();",
            "  }",
            "  void testNegative() {",
            "    SuperClassC<@Nullable String> f;",
            "    // No error",
            "    f = new FnImpl2();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void subtypeWithParameters() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class D<P extends @Nullable Object> {}",
            "  class B<P extends @Nullable Object> extends D<P> {}",
            "  void testPositive(B<@Nullable String> b) {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    D<String> f1 = new B<@Nullable String>();",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    D<String> f2 = b;",
            "  }",
            "  void testNegative(B<String> b) {",
            "    D<String> f1 = new B<String>();",
            "    D<String> f2 = b;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void fancierSubtypeWithParameters() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class Super<A extends @Nullable Object, B> {}",
            "  class Sub<C, D extends @Nullable Object> extends Super<D, C> {}",
            "  void testNegative() {",
            "    // valid assignment",
            "    Super<@Nullable String, String> s = new Sub<String, @Nullable String>();",
            "  }",
            "  void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    Super<@Nullable String, String> s2 = new Sub<@Nullable String, String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedVariableDeclarationChecks() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class D<P extends @Nullable Object> {}",
            "  class B<P extends @Nullable Object> extends D<P> {}",
            "  class C<P extends @Nullable Object> {}",
            "  class A<T extends C<P>, P extends @Nullable Object> {}",
            "  void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    D<C<String>> f1 = new B<C<@Nullable String>>();",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    A<C<String>, String> f2 = new A<C<String>, @Nullable String>();",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    D<C<String>> f3 = new B<@Nullable C<String>>();",
            "  }",
            "  void testNegative() {",
            "    D<C<@Nullable String>> f1 = new B<C<@Nullable String>>();",
            "    A<C<String>, String> f2 = new A<C<String>, String>();",
            "    D<@Nullable C<String>> f3 = new B<@Nullable C<String>>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForMethodReferenceInAnAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    String function(T1 o);",
            "  }",
            "  static String foo(Object o) {",
            "    return o.toString();",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: parameter o of referenced method is @NonNull",
            "    A<@Nullable Object> p = Test::foo;",
            "  }",
            "  static void testNegative() {",
            "    A<Object> p = Test::foo;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForMethodReferenceForClassFieldAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    T1 function(Object o);",
            "  }",
            "  static @Nullable String foo(Object o) {",
            "    return o.toString();",
            "  }",
            "  // BUG: Diagnostic contains: referenced method returns @Nullable",
            "  A<String> positiveField = Test::foo;",
            "  A<@Nullable String> negativeField = Test::foo;",
            "}")
        .doTest();
  }

  @Test
  public void testForMethodReferenceReturnTypeInAnAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    T1 function(Object o);",
            "  }",
            "  static @Nullable String foo(Object o) {",
            "    return o.toString();",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: referenced method returns @Nullable",
            "    A<String> p = Test::foo;",
            "  }",
            "  static void testNegative() {",
            "    A<@Nullable String> p = Test::foo;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForMethodReferenceWhenReturned() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    T1 function(Object o);",
            "  }",
            "  static @Nullable String foo(Object o) {",
            "    return o.toString();",
            "  }",
            "  static A<String> testPositive() {",
            "    // BUG: Diagnostic contains: referenced method returns @Nullable",
            "    return Test::foo;",
            "  }",
            "  static A<@Nullable String> testNegative() {",
            "    return Test::foo;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForMethodReferenceAsMethodParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    T1 function(Object o);",
            "  }",
            "  static @Nullable String foo(Object o) {",
            "    return o.toString();",
            "  }",
            "  static void fooPositive(A<String> a) {",
            "  }",
            "  static void fooNegative(A<@Nullable String> a) {",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: referenced method returns @Nullable",
            "    fooPositive(Test::foo);",
            "  }",
            "  static void testNegative() {",
            "    fooNegative(Test::foo);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForLambdasInAnAssignmentWithSingleParam() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    String function(T1 o);",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: dereferenced expression o is @Nullable",
            "    A<@Nullable Object> p = o -> o.toString();",
            "  }",
            "  static void testNegative() {",
            "    A<Object> p = o -> o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForLambdasInAnAssignmentWithMultipleParams() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object,T2 extends @Nullable Object> {",
            "    String function(T1 o1,T2 o2);",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: dereferenced expression o1 is @Nullable",
            "    A<@Nullable Object,Object> p = (o1,o2) -> o1.toString();",
            "  }",
            "  static void testNegative() {",
            "    A<@Nullable Object,Object> p = (o1,o2) -> o2.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForLambdasInAnAssignmentWithoutJSpecifyMode() {
    makeHelperWithoutJSpecifyMode()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    String function(T1 o);",
            "  }",
            "  static void testPositive() {",
            "    // Using outside JSpecify Mode So we don't get a bug here",
            "    A<@Nullable Object> p = o -> o.toString();",
            "  }",
            "  static void testNegative() {",
            "    A<Object> p = o -> o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForLambdaReturnTypeInAnAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    T1 function(Object o);",
            "  }",
            "  static void testPositive1() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "    A<String> p = x -> null;",
            "  }",
            "  static void testPositive2() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "    A<String> p = x -> { return null; };",
            "  }",
            "  static void testNegative1() {",
            "    A<@Nullable String> p = x -> null;",
            "  }",
            "  static void testNegative2() {",
            "    A<@Nullable String> p = x -> { return null; };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForDiamondInAnAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface A<T1 extends @Nullable Object> {",
            "    String function(T1 o);",
            "  }",
            "  static class B<T1> implements A<T1> {",
            "    public String function(T1 o) {",
            "      return o.toString();",
            "    }",
            "  }",
            "  static void testPositive() {",
            "    // TODO: we should report an error here, since B's type parameter",
            "    // cannot be @Nullable; we do not catch this yet",
            "    A<@Nullable Object> p = new B<>();",
            "  }",
            "  static void testNegative() {",
            "    A<Object> p = new B<>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericFunctionReturnTypeNewClassTree() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static A<String> testPositive1() {",
            "   // BUG: Diagnostic contains: Cannot return expression of type A<@Nullable String>",
            "   return new A<@Nullable String>();",
            "  }",
            "  static A<@Nullable String> testPositive2() {",
            "   // BUG: Diagnostic contains: mismatched nullability of type parameters",
            "   return new A<String>();",
            "  }",
            "  static A<@Nullable String> testNegative() {",
            "   return new A<@Nullable String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericFunctionReturnTypeNormalTree() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static A<String> testPositive(A<@Nullable String> a) {",
            "   // BUG: Diagnostic contains: mismatched nullability of type parameters",
            "   return a;",
            "  }",
            "  static A<@Nullable String> testNegative(A<@Nullable String> a) {",
            "   return a;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericFunctionReturnTypeMultipleReturnStatementsIfElseBlock() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static A<String> testPositive(A<@Nullable String> a, int num) {",
            "   if (num % 2 == 0) {",
            "    // BUG: Diagnostic contains: mismatched nullability of type parameters",
            "     return a;",
            "    } else {",
            "     return new A<String>();",
            "    }",
            "  }",
            "  static A<String> testNegative(A<String> a, int num) {",
            "    return a;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericsChecksForTernaryOperator() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "static class A<T extends @Nullable Object> { }",
            "  static A<String> testPositive(A<String> a, boolean t) {",
            "    // BUG: Diagnostic contains: Conditional expression must have type A<@Nullable String>",
            "    A<@Nullable String> t1 = t ? new A<String>() : new A<@Nullable String>();",
            "    // BUG: Diagnostic contains: Conditional expression must have type",
            "    return t ? new A<@Nullable String>() : new A<@Nullable String>();",
            "  }",
            "  static void testPositiveTernaryMethodArgument(boolean t) {",
            "    // BUG: Diagnostic contains: Conditional expression must have type",
            "    A<String> a = testPositive(t ? new A<String>() : new A<@Nullable String>(), t);",
            "  }",
            "  static A<@Nullable String> testNegative(boolean t) {",
            "    A<@Nullable String> t1 = t ? new A<@Nullable String>() : new A<@Nullable String>();",
            "    return t ? new A<@Nullable String>() : new A<@Nullable String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ternaryOperatorComplexSubtyping() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> {}",
            "  static class B<T extends @Nullable Object> extends A<T> {}",
            "  static class C<T extends @Nullable Object> extends A<T> {}",
            "  static void testPositive(boolean t) {",
            "    // BUG: Diagnostic contains: Conditional expression must have type",
            "    A<@Nullable String> t1 = t ? new B<@Nullable String>() : new C<String>();",
            "    // BUG: Diagnostic contains: Conditional expression must have type",
            "    A<@Nullable String> t2 = t ? new C<String>() : new B<@Nullable String>();",
            "    // BUG: Diagnostic contains:Conditional expression must have type",
            "    A<@Nullable String> t3 = t ? new B<String>() : new C<@Nullable String>();",
            "    // BUG: Diagnostic contains: Conditional expression must have type",
            "    A<String> t4 = t ? new B<@Nullable String>() : new C<@Nullable String>();",
            "  }",
            "  static void testNegative(boolean t) {",
            "    A<@Nullable String> t1 = t ? new B<@Nullable String>() : new C<@Nullable String>();",
            "    A<@Nullable String> t2 = t ? new C<@Nullable String>() : new B<@Nullable String>();",
            "    A<String> t3 = t ? new C<String>() : new B<String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedTernary() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> {}",
            "  static class B<T extends @Nullable Object> extends A<T> {}",
            "  static class C<T extends @Nullable Object> extends A<T> {}",
            "  static void testPositive(boolean t) {",
            "    A<@Nullable String> t1 = t ? new C<@Nullable String>() :",
            "        // BUG: Diagnostic contains: Conditional expression must have type",
            "        (t ? new B<@Nullable String>() : new A<String>());",
            "  }",
            "  static void testNegative(boolean t) {",
            "    A<@Nullable String> t1 = t ? new C<@Nullable String>() :",
            "        (t ? new B<@Nullable String>() : new A<@Nullable String>());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ternaryMismatchedAssignmentContext() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "static class A<T extends @Nullable Object> { }",
            "  static void testPositive(boolean t) {",
            "    // we get two errors here, one for each sub-expression; perhaps ideally we would report",
            "    // just one error (that the ternary operator has type A<String> but the assignment LHS",
            "    // has type A<@Nullable String>), but implementing that check in general is",
            "    // a bit tricky",
            "    A<@Nullable String> t1 = t",
            "        // BUG: Diagnostic contains: Conditional expression must have type",
            "        ? new A<String>()",
            "        // BUG: Diagnostic contains: Conditional expression must have type",
            "        : new A<String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void parameterPassing() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "static class A<T extends @Nullable Object> { }",
            "  static A<String> sampleMethod1(A<A<String>> a1, A<String> a2) {",
            "     return a2;",
            "  }",
            "  static A<String> sampleMethod2(A<A<@Nullable String>> a1, A<String> a2) {",
            "     return a2;",
            "  }",
            "  static void sampleMethod3(A<@Nullable String> a1) {",
            "  }",
            "  static void testPositive1(A<A<@Nullable String>> a1, A<String> a2) {",
            "    // BUG: Diagnostic contains: Cannot pass parameter of type A<A<@Nullable String>>",
            "    A<String> a = sampleMethod1(a1, a2);",
            "  }",
            "  static void testPositive2(A<A<String>> a1, A<String> a2) {",
            "    // BUG: Diagnostic contains: Cannot pass parameter of type",
            "    A<String> a = sampleMethod2(a1, a2);",
            "  }",
            "  static void testPositive3(A<String> a1) {",
            "    // BUG: Diagnostic contains: Cannot pass parameter of type",
            "    sampleMethod3(a1);",
            "  }",
            "  static void testNegative(A<A<String>> a1, A<String> a2) {",
            "    A<String> a = sampleMethod1(a1, a2);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void parameterPassingInstanceMethods() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> {",
            "    void foo(A<T> a) {}",
            "    void bar(A<T> a) { foo(a); this.foo(a); }",
            "  }",
            "  static void test(A<@Nullable String> p, A<String> q) {",
            "    // BUG: Diagnostic contains: Cannot pass parameter of type",
            "    p.foo(q);",
            "    // this one is fine",
            "    p.foo(p);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void varargsParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static A<@Nullable String> sampleMethodWithVarArgs(A<String>... args) {",
            "     return new A<@Nullable String>();",
            "  }",
            "  static void testPositive(A<@Nullable String> a1, A<String> a2) {",
            "     // BUG: Diagnostic contains: Cannot pass parameter of type",
            "     A<@Nullable String> b = sampleMethodWithVarArgs(a1);",
            "     // BUG: Diagnostic contains: Cannot pass parameter of type",
            "     A<@Nullable String> b2 = sampleMethodWithVarArgs(a2, a1);",
            "  }",
            "  static void testNegative(A<String> a1, A<String> a2) {",
            "     A<@Nullable String> b = sampleMethodWithVarArgs(a1);",
            "     A<@Nullable String> b2 = sampleMethodWithVarArgs(a2, a1);",
            "  }",
            "}")
        .doTest();
  }

  /**
   * Currently this test is solely to ensure NullAway does not crash in the presence of raw types.
   * Further study of the JSpecify documents is needed to determine whether any errors should be
   * reported for these cases.
   */
  @Test
  public void rawTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> {}",
            "  static class NullableTypeParam<E extends @Nullable Object> {}",
            "  static void rawLocals() {",
            "    NonNullTypeParam<String> t1 = new NonNullTypeParam();",
            "    NullableTypeParam<@Nullable String> t2 = new NullableTypeParam();",
            "    NonNullTypeParam t3 = new NonNullTypeParam<String>();",
            "    NullableTypeParam t4 = new NullableTypeParam<@Nullable String>();",
            "    NonNullTypeParam t5 = new NonNullTypeParam();",
            "    NullableTypeParam t6 = new NullableTypeParam();",
            "  }",
            "  static void rawConditionalExpression(boolean b, NullableTypeParam<@Nullable String> p) {",
            "    NullableTypeParam<@Nullable String> t = b ? new NullableTypeParam() : p;",
            "  }",
            "  static void doNothing(NullableTypeParam<@Nullable String> p) { }",
            "  static void rawParameterPassing() { doNothing(new NullableTypeParam()); }",
            "  static NullableTypeParam<@Nullable String> rawReturn() {",
            "    return new NullableTypeParam();",
            "}",
            "}")
        .doTest();
  }

  @Test
  public void nestedGenericTypeAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    A<A<@Nullable String>[]> var1 = new A<A<String>[]>();",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    A<A<String>[]> var2 = new A<A<@Nullable String>[]>();",
            "  }",
            "  static void testNegative() {",
            "    A<A<@Nullable String>[]> var1 = new A<A<@Nullable String>[]>();",
            "    A<A<String>[]> var2 = new A<A<String>[]>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedGenericTypeAssignment2() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    A<A<String>[]> var2 = new A<A<@Nullable String>[]>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericPrimitiveArrayTypeAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: Cannot assign from type A<int[]>",
            "    A<int @Nullable[]> x = new A<int[]>();",
            "  }",
            "  static void testNegative() {",
            "    A<int @Nullable[]> x = new A<int @Nullable[]>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedGenericTypeVariables() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static class B<T> {",
            "    void foo() {",
            "      A<A<T>[]> x = new A<A<T>[]>();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedGenericWildcardTypeVariables() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static class B<T> {",
            "    void foo() {",
            "      A<A<? extends String>[]> x = new A<A<? extends String>[]>();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overrideReturnTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object> {",
            "   R apply(P p);",
            "  }",
            " static class TestFunc1 implements Fn<String, @Nullable String> {",
            "  @Override",
            "  public @Nullable String apply(String s) {",
            "   return s;",
            "  }",
            " }",
            " static class TestFunc2 implements Fn<String, @Nullable String> {",
            "  @Override",
            "  public String apply(String s) {",
            "   return s;",
            "  }",
            " }",
            " static class TestFunc3 implements Fn<String, String> {",
            "  @Override",
            "  // BUG: Diagnostic contains: method returns @Nullable, but superclass",
            "  public @Nullable String apply(String s) {",
            "   return s;",
            "  }",
            " }",
            " static class TestFunc4 implements Fn<@Nullable String, String> {",
            "  @Override",
            "  // BUG: Diagnostic contains: method returns @Nullable, but superclass",
            "  public @Nullable String apply(String s) {",
            "   return s;",
            "  }",
            " }",
            " static void useTestFunc(String s) {",
            "    Fn<String, @Nullable String> f1 = new TestFunc1();",
            "    String t1 = f1.apply(s);",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    t1.hashCode();",
            "    TestFunc2 f2 = new TestFunc2();",
            "    String t2 = f2.apply(s);",
            "    // There should not be an error here",
            "    t2.hashCode();",
            "    Fn<String, @Nullable String> f3 = new TestFunc2();",
            "    String t3 = f3.apply(s);",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    t3.hashCode();",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    f3.apply(s).hashCode();",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void overrideWithNullCheck() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object> {",
            "   R apply(P p);",
            "  }",
            " static class TestFunc1 implements Fn<String, @Nullable String> {",
            "  @Override",
            "  public @Nullable String apply(String s) {",
            "   return s;",
            "  }",
            " }",
            " static void useTestFuncWithCast() {",
            "    Fn<String, @Nullable String> f1 = new TestFunc1();",
            "    if (f1.apply(\"hello\") != null) {",
            "      String t1 = f1.apply(\"hello\");",
            "      // no error here due to null check",
            "      t1.hashCode();",
            "    }",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void overrideParameterType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object> {",
            "   R apply(P p);",
            "  }",
            " static class TestFunc1 implements Fn<@Nullable String, String> {",
            "  @Override",
            "  // BUG: Diagnostic contains: parameter s is",
            "  public String apply(String s) {",
            "   return s;",
            "  }",
            " }",
            " static class TestFunc2 implements Fn<@Nullable String, String> {",
            "  @Override",
            "  public String apply(@Nullable String s) {",
            "   return \"hi\";",
            "  }",
            " }",
            " static class TestFunc3 implements Fn<String, String> {",
            "  @Override",
            "  public String apply(String s) {",
            "   return \"hi\";",
            "  }",
            " }",
            " static class TestFunc4 implements Fn<String, String> {",
            "  // this override is legal, we should get no error",
            "  @Override",
            "  public String apply(@Nullable String s) {",
            "   return \"hi\";",
            "  }",
            " }",
            " static void useTestFunc(String s) {",
            "    Fn<@Nullable String, String> f1 = new TestFunc2();",
            "    // should get no error here",
            "    f1.apply(null);",
            "    Fn<String, String> f2 = new TestFunc3();",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    f2.apply(null);",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void overrideExplicitlyTypedAnonymousClass() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object> {",
            "    R apply(P p);",
            "  }",
            "  static abstract class FnClass<P extends @Nullable Object, R extends @Nullable Object> {",
            "    abstract R apply(P p);",
            "  }",
            "  static void anonymousClasses() {",
            "    Fn<@Nullable String, String> fn1 = new Fn<@Nullable String, String>() {",
            "      // BUG: Diagnostic contains: parameter s is @NonNull, but parameter in superclass method",
            "      public String apply(String s) { return s; }",
            "    };",
            "    FnClass<String, String> fn2 = new FnClass<String, String>() {",
            "      // BUG: Diagnostic contains: method returns @Nullable, but superclass method",
            "      public @Nullable String apply(String s) { return null; }",
            "    };",
            "    Fn<String, @Nullable String> fn3 = new Fn<String, @Nullable String>() {",
            "      public @Nullable String apply(String s) { return null; }",
            "    };",
            "    FnClass<@Nullable String, String> fn4 = new FnClass<@Nullable String, String>() {",
            "      public String apply(@Nullable String s) { return \"hello\"; }",
            "    };",
            "  }",
            "  static void anonymousClassesFullName() {",
            "    Test.Fn<@Nullable String, String> fn1 = new Test.Fn<@Nullable String, String>() {",
            "      // BUG: Diagnostic contains: parameter s is @NonNull, but parameter in superclass method",
            "      public String apply(String s) { return s; }",
            "    };",
            "    Test.FnClass<String, String> fn2 = new Test.FnClass<String, String>() {",
            "      // BUG: Diagnostic contains: method returns @Nullable, but superclass method",
            "      public @Nullable String apply(String s) { return null; }",
            "    };",
            "    Test.Fn<String, @Nullable String> fn3 = new Test.Fn<String, @Nullable String>() {",
            "      public @Nullable String apply(String s) { return null; }",
            "    };",
            "    Test.FnClass<@Nullable String, String> fn4 = new Test.FnClass<@Nullable String, String>() {",
            "      public String apply(@Nullable String s) { return \"hello\"; }",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/836")
  @Test
  public void overrideAnonymousNestedClass() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class Wrapper<P extends @Nullable Object> {",
            "    abstract class Fn<R extends @Nullable Object> {",
            "      abstract R apply(P p);",
            "    }",
            "  }",
            "  void anonymousNestedClasses() {",
            "    Wrapper<@Nullable String>.Fn<String> fn1 = (this.new Wrapper<@Nullable String>()).new Fn<String>() {",
            "      // BUG: Diagnostic contains: parameter s is @NonNull, but parameter in superclass method",
            "      public String apply(String s) { return s; }",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void otherTypeUseNullableAnnotation() {
    makeHelper()
        .addSourceLines(
            "Nullable.java",
            "package com.other;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.RetentionPolicy;",
            "import java.lang.annotation.Target;",
            "@Target(ElementType.TYPE_USE)",
            "@Retention(RetentionPolicy.CLASS)",
            "public @interface Nullable {}")
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.jspecify.annotations.NullMarked;",
            "import com.other.Nullable;",
            "@NullMarked",
            "class Foo {",
            "    static abstract class MyClass<T extends @Nullable Object>  {",
            "        abstract T doThing(T value);",
            "    }",
            "    static void repro() {",
            "        new MyClass<@Nullable Object>() {",
            "            @Override",
            "            @Nullable Object doThing(@Nullable Object value) {",
            "                return value;",
            "            }",
            "        }.doThing(null);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void nullableVoidGenericsLambda() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface TestInterface<T extends @Nullable Object> {",
            "    T test();",
            "  }",
            "  public TestInterface<@Nullable Void> getTest() {",
            "    return () -> { return null; };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void explicitlyTypedAnonymousClassAsReceiver() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object> {",
            "    R apply(P p);",
            "  }",
            "  static abstract class FnClass<P extends @Nullable Object, R extends @Nullable Object> {",
            "    abstract R apply(P p);",
            "  }",
            "  static void anonymousClasses() {",
            "    String s1 = (new Fn<String, @Nullable String>() {",
            "      public @Nullable String apply(String s) { return null; }",
            "    }).apply(\"hi\");",
            "    // BUG: Diagnostic contains: dereferenced expression s1",
            "    s1.hashCode();",
            "    String s2 = (new FnClass<String, @Nullable String>() {",
            "      public @Nullable String apply(String s) { return null; }",
            "    }).apply(\"hi\");",
            "    // BUG: Diagnostic contains: dereferenced expression s2",
            "    s2.hashCode();",
            "    (new Fn<String, String>() {",
            "      public String apply(String s) { return \"hi\"; }",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    }).apply(null);",
            "    (new FnClass<String, String>() {",
            "      public String apply(String s) { return \"hi\"; }",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    }).apply(null);",
            "    (new Fn<@Nullable String, String>() {",
            "      public String apply(@Nullable String s) { return \"hi\"; }",
            "    }).apply(null);",
            "    (new FnClass<@Nullable String, String>() {",
            "      public String apply(@Nullable String s) { return \"hi\"; }",
            "    }).apply(null);",
            "  }",
            "}")
        .doTest();
  }

  /** Diamond anonymous classes are not supported yet; tests are for future reference */
  @Test
  public void overrideDiamondAnonymousClass() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object> {",
            "    R apply(P p);",
            "  }",
            "  static abstract class FnClass<P extends @Nullable Object, R extends @Nullable Object> {",
            "    abstract R apply(P p);",
            "  }",
            "  static void anonymousClasses() {",
            "    Fn<@Nullable String, String> fn1 = new Fn<>() {",
            "      // TODO: should report a bug here",
            "      public String apply(String s) { return s; }",
            "    };",
            "    FnClass<@Nullable String, String> fn2 = new FnClass<>() {",
            "      // TODO: should report a bug here",
            "      public String apply(String s) { return s; }",
            "    };",
            "    Fn<String, @Nullable String> fn3 = new Fn<>() {",
            "      // TODO: this is a false positive",
            "      // BUG: Diagnostic contains: method returns @Nullable, but superclass method",
            "      public @Nullable String apply(String s) { return null; }",
            "    };",
            "    FnClass<String, @Nullable String> fn4 = new FnClass<>() {",
            "      // TODO: this is a false positive",
            "      // BUG: Diagnostic contains: method returns @Nullable, but superclass method",
            "      public @Nullable String apply(String s) { return null; }",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableGenericTypeVariableReturnType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            " interface Fn<P extends @Nullable Object, R> {",
            "   @Nullable R apply(P p);",
            "  }",
            " static class TestFunc implements Fn<String, String> {",
            "  @Override",
            "  //This override is fine and is handled by the current code",
            "  public @Nullable String apply(String s) {",
            "   return s;",
            "  }",
            " }",
            " static void useTestFunc(String s) {",
            "  Fn<String, String> f = new TestFunc();",
            "  String t = f.apply(s);",
            "  // BUG: Diagnostic contains: dereferenced expression",
            "  t.hashCode();",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void overrideWithNestedTypeParametersInReturnType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            " class P<T1 extends @Nullable Object, T2 extends @Nullable Object>{}",
            " interface Fn<T3 extends P<T4, T4>, T4 extends @Nullable Object> {",
            "  T3 apply();",
            " }",
            " class TestFunc1 implements Fn<P<@Nullable String, String>, @Nullable String> {",
            " @Override",
            "  // BUG: Diagnostic contains: Method returns Test.P<@Nullable String, @Nullable String>, but overridden method",
            " public P<@Nullable String, @Nullable String> apply() {",
            "   return new P<@Nullable String, @Nullable String>();",
            "  }",
            " }",
            " class TestFunc2 implements Fn<P<@Nullable String, @Nullable String>, @Nullable String> {",
            "   @Override",
            "   // BUG: Diagnostic contains: Method returns Test.P<@Nullable String, String>, but overridden method returns",
            "   public P<@Nullable String, String> apply() {",
            "     return new P<@Nullable String, String>();",
            "   }",
            " }",
            " class TestFunc3 implements Fn<P<@Nullable String, @Nullable String>, @Nullable String> {",
            "   @Override",
            "   public P<@Nullable String, @Nullable String> apply() {",
            "     return new P<@Nullable String, @Nullable String>();",
            "   }",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void overrideWithNestedTypeParametersInParameterType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class P<T1 extends @Nullable Object, T2 extends @Nullable Object>{}",
            " interface Fn<T extends P<R, R>, R extends @Nullable Object> {",
            "  String apply(T t, String s);",
            " }",
            " class TestFunc implements Fn<P<String, String>, String> {",
            " @Override",
            "  // BUG: Diagnostic contains: Parameter has type Test.P<@Nullable String, String>, but overridden method has parameter type Test.P<String, String>",
            "  public String apply(P<@Nullable String, String> p, String s) {",
            "    return s;",
            "  }",
            " }",
            " class TestFunc2 implements Fn<P<String, String>, String> {",
            " @Override",
            "  public String apply(P<String, String> p, String s) {",
            "    return s;",
            "  }",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void interactionWithContracts() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "class Test {",
            "  interface Fn1<P extends @Nullable Object, R extends @Nullable Object> {",
            "    R apply(P p);",
            "  }",
            "  static class TestFunc1 implements Fn1<@Nullable String, @Nullable String> {",
            "    @Override",
            "    @Contract(\"!null -> !null\")",
            "    public @Nullable String apply(@Nullable String s) {",
            "      return s;",
            "    }",
            "  }",
            "  interface Fn2<P extends @Nullable Object, R extends @Nullable Object> {",
            "    @Contract(\"!null -> !null\")",
            "    R apply(P p);",
            "  }",
            "  static class TestFunc2 implements Fn2<@Nullable String, @Nullable String> {",
            "    @Override",
            "    public @Nullable String apply(@Nullable String s) {",
            "      return s;",
            "    }",
            "  }",
            "  static class TestFunc2_Bad implements Fn2<@Nullable String, @Nullable String> {",
            "    @Override",
            "    public @Nullable String apply(@Nullable String s) {",
            "      // False negative: with contract checking enabled, this should be rejected",
            "      // See https://github.com/uber/NullAway/issues/803",
            "      return null;",
            "    }",
            "  }",
            "  static void testMethod() {",
            "    // No error due to @Contract",
            "    (new TestFunc1()).apply(\"hello\").hashCode();",
            "    Fn1<@Nullable String, @Nullable String> fn1 = new TestFunc1();",
            "    // BUG: Diagnostic contains: dereferenced expression fn1.apply(\"hello\")",
            "    fn1.apply(\"hello\").hashCode();",
            "    // BUG: Diagnostic contains: dereferenced expression (new TestFunc2())",
            "    (new TestFunc2()).apply(\"hello\").hashCode();",
            "    Fn2<@Nullable String, @Nullable String> fn2 = new TestFunc2();",
            "    // No error due to @Contract",
            "    fn2.apply(\"hello\").hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForStaticMethodCallAsAParam() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T> {",
            "   public static <T> A<T> returnA(){",
            "     return new A<T>();",
            "   }",
            "   public static <T> A<T> returnAWithParam(Object o){",
            "     return new A<T>();",
            "   }",
            "  }",
            "  static void func(A<Object> a){",
            "  }",
            "  static void testNegative() {",
            "   func(A.returnA());",
            "  }",
            "  static void testNegative2() {",
            "   func(A.returnAWithParam(new Object()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForDiamondOperatorReturnedAsAMethodCaller() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class B<T>{",
            "   String build(){return \"x\";}",
            "  }",
            "  static String testNegative() {",
            "   return new B<>().build();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForNullRhsTypeWhenReturnedForGenericType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T extends @Nullable Object> { }",
            "  static A<String> testPositive() {",
            "   // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "   return null;",
            "  }",
            "  static @Nullable A<String> testNegative() {",
            "   return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForNullTypeRhsTypeForArrayType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "class Test {",
            "  static void testNegative() {",
            "   List<String> a = new ArrayList<String>();",
            "   Object[] o = a != null ? a.toArray() : null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overrideWithRawType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Foo<T> {}",
            "  interface Bar<T> {",
            "    void add(Foo<T> foo);",
            "    @Nullable Foo<T> get();",
            "  }",
            "  static class Baz<T> implements Bar<T> {",
            "    @SuppressWarnings(\"rawtypes\")",
            "    @Override",
            "    public void add(Foo foo) {}",
            "    @SuppressWarnings(\"rawtypes\")",
            "    @Override",
            "    public @Nullable Foo get() { return null; }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testForNullableUpperBoundsInLibModel() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.function.Function;",
            "class Test {",
            "  static String foo(@Nullable String a) {",
            "    if(a!=null){ ",
            "     return a.replace(\"a\",\"\");",
            "    }else{",
            "     return \"\";",
            "    }",
            "  }",
            "  static void testPositiveMethodRef() {",
            "   Function<String,@Nullable String> removeA = Test::foo;",
            "   // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "   removeA.apply(null);",
            "  }",
            "  static void testNegativeMethodRef() {",
            "   Function<@Nullable String,@Nullable String> removeA = Test::foo;",
            "   removeA.apply(null);",
            "  }",
            "  static void testPositiveLambda() {",
            "   Function<String,@Nullable String> removeA = a -> a.replace(\"a\",\"\");",
            "   // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "   removeA.apply(null);",
            "  }",
            "  static void testNegative() {",
            "   Function<String,@Nullable String> removeA = a -> a.replace(\"a\",\"\");",
            "  }",
            "  static @Nullable String foo2(@Nullable String b) {",
            "    return null;",
            "  }",
            "  static Function<String,String> testPositiveReturn() {",
            "    // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method",
            "    return Test::foo2;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void passAnnotatedLambdaOrMethodRefToUnannotatedCode() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.NullUnmarked;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static interface Fn<S extends @Nullable Object, T extends @Nullable Object> {",
            "    T apply(S s);",
            "  }",
            "  @NullUnmarked",
            "  static class C1 {",
            "    static void foo(Fn<String, String> f) {}",
            "  }",
            "  @NullMarked",
            "  static class C2 {",
            "    static void m1() {",
            "      // no error here since C1 is @NullUnmarked",
            "      C1.foo(s -> null);",
            "    }",
            "    static @Nullable String baz(String s) { return null; }",
            "    static void m2() {",
            "      // no error here since C1 is @NullUnmarked",
            "      C1.foo(C2::baz);",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testRawTypeReceiverCast() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static class A<T> {",
            "    void foo(T n) {}",
            "  }",
            "  static class B {",
            "    static <T> void bar(A<T> a) {",
            "      ((A) a).foo(new Object());",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void pseudoAssignmentWithRawDeclaredTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class A<T> {",
            "    void foo(T n) {}",
            "  }",
            "  static class B {",
            "    static void bar(A a) {}",
            "    static void m1(A<?> a) {",
            "      bar(a);",
            "    }",
            "    static A m2(A<?> a) {",
            "      return a;",
            "    }",
            "    @Nullable A<?> field;",
            "    void m3(A<?> a) {",
            "      field = a;",
            "      A local = a;",
            "      local = a;",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testUseOfUnannotatedCode() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.NullUnmarked;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  @NullUnmarked",
            "  static class NullUnmarkedClass<T> {",
            "  }",
            "  @NullMarked",
            "  static class MarkedClass {",
            "    static void testInstantiation() {",
            "      // NullUnmarkedClass is marked @NullUnmarked, so we get no error",
            "      // even though the type variable does not have a @Nullable upper bound",
            "      new NullUnmarkedClass<@Nullable String>();",
            "    }",
            "    static void testAssignment() {",
            "      NullUnmarkedClass<@Nullable Integer> var = null;",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void boxInteger() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static void testAssign(int i) {",
            "    // should not do any check here due to primitive type",
            "    Integer I = i;",
            "  }",
            "  static Integer testReturn(int i) {",
            "    // should not do any check here due to primitive type",
            "    return i;",
            "  }",
            "  static void takeInteger(Integer I) {}",
            "  static void testCall(int i) {",
            "    // should not do any check here due to primitive type",
            "    takeInteger(i);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void issue1008() {
    // testing for no crash
    makeHelper()
        .addSourceLines(
            "EnumCombinations.java",
            "package com.uber;",
            "public class EnumCombinations {",
            "    public static void combinations(Class<? extends Enum<?>> first, Class<? extends Enum<?>>... others) {",
            "    }",
            "    public static void args(Class<? extends Enum<?>> first, Class<? extends Enum<?>>... others) {",
            "        combinations(first, others);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void intersectionTypeFromConditionalExprInStringConcat() {
    makeHelper()
        .addSourceLines(
            "ServiceExtraInfo.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "public class ServiceExtraInfo {",
            "    private java.util.@Nullable List<Object> relativeServices;",
            "    private String getStr() {",
            "        return (relativeServices == null ? \"relativeServices == null\" : relativeServices.size()) + \"\";",
            "    }",
            "    private String getStr2(boolean b, java.util.List<Object> l) {",
            "        return (b ? (b ? l.size() : \"hello\") : 3) + \"\";",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void intersectionTypeInvalidAssign() {
    String[] source;
    // javac behavior differs between versions before and after 23, so we have two versions of the
    // test source code
    if (Runtime.version().feature() >= 23) {
      source =
          new String[] {
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.io.Serializable;",
            "public class Test {",
            "  interface A<T extends @Nullable Object> {}",
            "  static class B implements A<@Nullable String>, Serializable {}",
            "  static class C implements A<String>, Serializable {}",
            "  static void test1(Object o) {",
            "    var x = (A<String> & Serializable) o;",
            "    // BUG: Diagnostic contains: Cannot assign from type B to type A<String> & Serializable",
            "    x = new B();",
            "    // ok",
            "    x = new C();",
            "  }",
            "  static void test2(Object o) {",
            "    var x = (A<@Nullable String> & Serializable) o;",
            "    x = new B();",
            "    // BUG: Diagnostic contains: Cannot assign from type C to type A<@Nullable String> & Serializable",
            "    x = new C();",
            "  }",
            "}"
          };
    } else {
      // Before JDK 23, javac does not compute types with annotations for cast expressions, so the
      // test assertions do not work as expected.
      source =
          new String[] {
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.io.Serializable;",
            "public class Test {",
            "  interface A<T extends @Nullable Object> {}",
            "  static class B implements A<@Nullable String>, Serializable {}",
            "  static class C implements A<String>, Serializable {}",
            "  static void test1(Object o) {",
            "    var x = (A<String> & Serializable) o;",
            "    // BUG: Diagnostic contains: Cannot assign from type B to type A<String> & Serializable",
            "    x = new B();",
            "    // ok",
            "    x = new C();",
            "  }",
            "  static void test2(Object o) {",
            "    var x = (A<@Nullable String> & Serializable) o;",
            // TODO: should _not_ be an error, see https://github.com/uber/NullAway/issues/1022
            "    // BUG: Diagnostic contains: Cannot assign from type B to type A<String> & Serializable",
            "    x = new B();",
            // TODO: _should_ be an error, see https://github.com/uber/NullAway/issues/1022
            "    x = new C();",
            "  }",
            "}"
          };
    }
    makeHelper().addSourceLines("Test.java", source).doTest();
  }

  @Test
  public void issue1014() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.function.Function;",
            "class Test<R> {",
            "    public interface PropertyFunction<",
            "            T extends @Nullable Object, R extends @Nullable Object, E extends Exception>",
            "            extends Function<T, R> {",
            "        R _apply(T t) throws E;",
            "    }",
            "    @Nullable PropertyFunction<? super R, ? extends String, ? super Exception> stringFunc;",
            "    public void propertyString() {",
            "        var f = stringFunc;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1019() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "",
            "public class Test {",
            "    public static class StringList extends ArrayList {",
            "    }",
            "    @SuppressWarnings(\"unchecked\")",
            "    public static List<String> convert(final StringList stringList) {",
            "        return stringList;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1082() {
    makeHelper()
        .addSourceLines(
            "Main.java",
            "package com.uber;",
            "import java.util.Optional;",
            "public class Main {",
            "  public interface Factory<T> {",
            "    T create();",
            "  }",
            "  public interface Expiry<K, V> {}",
            "  static class Config<K, V> {",
            "    Config<K, V> setFactory(Optional<Factory<? extends Expiry<K, V>>> factory) {",
            "      return this;",
            "    }",
            "  }",
            "  static void caller(Config config) {",
            "    // checking that we don't crash",
            "    config.setFactory(Optional.<Object>empty());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void issue1093() {
    makeHelper()
        .addSourceLines(
            "Main.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "public class Main {",
            "    interface CacheLoader<V extends @Nullable Object> {",
            "    }",
            "    enum Loader implements CacheLoader<@Nullable Integer> {",
            "        NULL;",
            "        Loader() {}",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1127() {
    makeHelper()
        .addSourceLines(
            "Main.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "public class Main {",
            "  void arrayAssign(boolean b, @Nullable String @Nullable [] vals) {",
            "    @Nullable String[] arr = (b ? vals : null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedGenericField() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.unannotated;",
            "import org.jspecify.annotations.*;",
            "import java.util.function.Function;",
            "public class Test {",
            "  static Function<@Nullable String, @Nullable String> foo;",
            "  @NullMarked",
            "  static class Inner {",
            "    static @Nullable Function<@Nullable String, @Nullable String> bar;",
            "    void bar(Function<String,String> f) {",
            "      // no error since foo is in @NullUnmarked code",
            "      foo = f;",
            "      // BUG: Diagnostic contains: Cannot assign from type Function<String, String>",
            "      bar = f;",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void issue1126() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.function.Supplier;",
            "public class Test {",
            "    static class K<T extends @Nullable Object> {}",
            "    void foo(K<@Nullable Object> k) {",
            "        K<? extends @Nullable Object> k2 = k;",
            "        Supplier<? extends @Nullable Object> s = () -> null;",
            "    }",
            "}")
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "import java.util.HashMap;",
            "import java.util.Map;",
            "import org.jspecify.annotations.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class Test2 {",
            "    @Contract(\"null -> true\")",
            "    public static boolean isEmpty(@Nullable Map<?, ? extends @Nullable Object> map) {",
            "        return (map == null || map.isEmpty());",
            "    }",
            "    static void foo() {",
            "        Map<String, @Nullable Object> variables = new HashMap<>();",
            "        if (isEmpty(variables)) { /* do nothing */ }",
            "        variables.toString();",
            "    }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }

  private CompilationTestHelper makeHelperWithoutJSpecifyMode() {
    return makeTestHelperWithArgs(List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber"));
  }
}
