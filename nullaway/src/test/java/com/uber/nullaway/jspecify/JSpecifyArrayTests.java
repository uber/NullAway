/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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

package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import java.util.Arrays;
import org.junit.Test;

public class JSpecifyArrayTests extends NullAwayTestsBase {

  @Test
  public void arrayTopLevelAnnotationDereference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static Integer @Nullable [] fizz = {1};",
            "  static void foo() {",
            "    // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "    int bar = fizz.length;",
            "  }",
            "  static void bar() {",
            "    // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "    int bar = fizz[0];",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayTopLevelAnnotationAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  Object foo = new Object();",
            "  void m( Integer @Nullable [] bar) {",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      foo = bar;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayContentsAnnotationDereference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static Object foo = new Object();",
            "  static void foo() {",
            "      // BUG: Diagnostic contains: dereferenced expression fizz[0] is @Nullable",
            "      int bar = fizz[0].length();",
            "      // OK: valid dereference since only elements of the array can be null",
            "      foo = fizz.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayContentsAnnotationAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  Object fizz = new Object();",
            "  void m( @Nullable Integer [] foo) {",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      fizz = foo[0];",
            "      // OK: valid assignment since only elements can be null",
            "      fizz = foo;",
            "  }",
            "}")
        .doTest();
  }

  /**
   * Currently in JSpecify mode, JSpecify syntax only applies to type-use annotations. Declaration
   * annotations preserve their existing behavior, with annotations being treated on the top-level
   * type. We will very likely revisit this design in the future.
   */
  @Test
  public void arrayDeclarationAnnotation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static Object o1 = new Object();",
            "  static void foo() {",
            "      // This should not report an error while using JSpecify type-use annotation",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      o1 = fizz;",
            "      // This should not report an error while using JSpecify type-use annotation",
            "      // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "      o1 = fizz.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayContentsAndTopLevelAnnotation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String @Nullable [] fizz = {\"1\"};",
            "  static Object foo = new Object();",
            "  static void foo() {",
            "     if (fizz != null) {",
            "        String s = fizz[0];",
            "        // BUG: Diagnostic contains: dereferenced expression s is @Nullable",
            "        int l1 = s.length();",
            "        if (s != null){",
            "           // OK: handled by null check",
            "           int l2 = s.length();",
            "        }",
            "     }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableAssignmentNonnullArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static String [] foo = new String[10];",
            "  static void foo() {",
            "    // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents",
            "    foo[1] = null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableAssignmentNullableArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String [] foo = new String[10];",
            "  static void foo() {",
            "    // OK: since array elements are @Nullable",
            "    foo[1] = null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableAssignmentLocalArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static void foo() {",
            "    String [] nonNullArray = new String[10];",
            "    @Nullable String [] nullableArray = new String[10];",
            "    // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents",
            "    nonNullArray[1] = null;",
            "    // OK: since array elements are @Nullable",
            "    nullableArray[1] = null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableAssignmentParameterArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static void fizz(String[] nonNullArray, @Nullable String[] nullableArray) {",
            "    // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents",
            "    nonNullArray[1] = null;",
            "    // OK: since array elements are @Nullable",
            "    nullableArray[1] = null;",
            "  }",
            "  public static void main(String[] args) {",
            "    String[] foo = new String[10];",
            "    @Nullable String[] bar = new String[10];",
            "    fizz(foo, bar);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arraySubtyping() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static void test(@Nullable Integer[] nullableIntArr, Integer[] nonnullIntArr) {",
            "    // legal",
            "    Integer[] x1 = nonnullIntArr;",
            "    // legal",
            "    @Nullable Integer[] x2 = nullableIntArr;",
            "    // legal (covariant array subtypes)",
            "    x2 = nonnullIntArr;",
            "    // BUG: Diagnostic contains: Cannot assign from type @Nullable Integer[] to type Integer[]",
            "    x1 = nullableIntArr;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arraySubtypingWithNewExpression() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static void test() {",
            "    // legal",
            "    Integer[] x1 = new Integer[0];",
            "    // legal (covariant array subtypes)",
            "    @Nullable Integer[] x2 = new Integer[0];",
            "    // legal",
            "    x2 = new @Nullable Integer[0];",
            "    // BUG: Diagnostic contains: Cannot assign from type @Nullable Integer[] to type Integer[]",
            "    x1 = new @Nullable Integer[0];",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arraysAndGenerics() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.List;",
            "class Test {",
            "  void foo(List<@Nullable Integer[]> l) {}",
            "  void testPositive(List<Integer[]> p) {",
            "    // BUG: Diagnostic contains: Cannot pass parameter of type List<Integer[]>",
            "    foo(p);",
            "  }",
            "  void testNegative(List<@Nullable Integer[]> p) {",
            "    foo(p);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericArraysReturnedAndPassed() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class Foo<T> {}",
            "  static class Bar<T> {",
            "    Foo<T>[] getFoosPositive() {",
            "      @Nullable Foo<T>[] result = new Foo[0];",
            "      // BUG: Diagnostic contains: Cannot return expression of type @Nullable Foo<T>[] from method",
            "      return result;",
            "    }",
            "    Foo<T>[] getFoosNegative() {",
            "      Foo<T>[] result = new Foo[0];",
            "      return result;",
            "    }",
            "    void takeFoos(Foo<T>[] foos) {}",
            "    void callTakeFoosPositive(@Nullable Foo<T>[] p) {",
            "      // BUG: Diagnostic contains: Cannot pass parameter of type @Nullable Foo<T>[]",
            "      takeFoos(p);",
            "    }",
            "    void callTakeFoosNegative(Foo<T>[] p) {",
            "      takeFoos(p);",
            "    }",
            "    void takeFoosVarargs(Foo<T>[]... foos) {}",
            "    void callTakeFoosVarargsPositive(@Nullable Foo<T>[] p, Foo<T>[] p2) {",
            "      // Under the hood, a @Nullable Foo<T>[][] is passed, which is not a subtype",
            "      // of the formal parameter type Foo<T>[][]",
            "      // BUG: Diagnostic contains: Cannot pass parameter of type @Nullable Foo<T>[]",
            "      takeFoosVarargs(p);",
            "      // BUG: Diagnostic contains: Cannot pass parameter of type @Nullable Foo<T>[]",
            "      takeFoosVarargs(p2, p);",
            "    }",
            "    void callTakeFoosVarargsNegative(Foo<T>[] p) {",
            "      takeFoosVarargs(p);",
            "    }",
            "    void takeNullableFoosVarargs(@Nullable Foo<T>[]... foos) {}",
            "    void callTakeNullableFoosVarargsNegative(@Nullable Foo<T>[] p1, Foo<T>[] p2) {",
            "      takeNullableFoosVarargs(p1);",
            "      takeNullableFoosVarargs(p2);",
            "      takeNullableFoosVarargs(p1, p2);",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overridesReturnType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.List;",
            "class Test {",
            "  class Super {",
            "    @Nullable Integer[] foo() { return new @Nullable Integer[0]; }",
            "    Integer[] bar() { return new Integer[0]; }",
            "  }",
            "  class Sub extends Super {",
            "    @Override",
            "    Integer[] foo() { return new Integer[0]; }",
            "    @Override",
            "    // BUG: Diagnostic contains: Method returns @Nullable Integer[], but overridden method returns Integer[]",
            "    @Nullable Integer[] bar() { return new @Nullable Integer[0]; }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overridesParameterType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.List;",
            "class Test {",
            "  class Super {",
            "    void foo(@Nullable Integer[] p) { }",
            "    void bar(Integer[] p) { }",
            "  }",
            "  class Sub extends Super {",
            "    @Override",
            "    // BUG: Diagnostic contains: Parameter has type Integer[], but overridden method has parameter type @Nullable Integer[]",
            "    void foo(Integer[] p) { }",
            "    @Override",
            "    void bar(@Nullable Integer[] p) { }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ternaryOperator() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static Integer[] testPositive(Integer[] p, boolean t) {",
            "    // BUG: Diagnostic contains: Conditional expression must have type Integer[]",
            "    Integer[] t1 = t ? new Integer[0] : new @Nullable Integer[0];",
            "    // BUG: Diagnostic contains: Conditional expression must have type",
            "    return t ? new @Nullable Integer[0] : new @Nullable Integer[0];",
            "  }",
            "  static void testPositiveTernaryMethodArgument(boolean t) {",
            "    // BUG: Diagnostic contains: Conditional expression must have type",
            "    Integer[] a = testPositive(t ? new Integer[0] : new @Nullable Integer[0], t);",
            "  }",
            "  static @Nullable Integer[] testNegative(boolean n) {",
            "    @Nullable Integer[] t1 = n ? new @Nullable Integer[0] : new @Nullable Integer[0];",
            "    @Nullable Integer[] t2 = n ? new Integer[0] : new @Nullable Integer[0];",
            "    return n ? new @Nullable Integer[0] : new @Nullable Integer[0];",
            "  }",
            "  static void testNegativeTernaryMethodArgument(boolean t) {",
            "    Integer[] a = testPositive(t ? new Integer[0] : new Integer[0], t);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void fieldAccessIndexArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static final int i = 0;",
            "  static void foo() {",
            "  if (fizz[i]!=null) { ",
            "   fizz[i].toString();",
            "}",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[i] is @Nullable",
            "   fizz[i].toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void constantIndexArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static void foo() {",
            "  if (fizz[0]!=null) { ",
            "    // OK: constant integer indexes are handled by dataflow",
            "   fizz[0].toString();",
            "}",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[0] is @Nullable",
            "   fizz[0].toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void localVariableIndexArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String[] fizz = {\"1\"};",
            "  static void foo() {",
            "    int index = 1;",
            "    if (fizz[index] != null) {",
            "    // OK: local variable indexes are handled by dataflow",
            "      fizz[index].toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[index] is @Nullable",
            "    fizz[index].toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void loopVariableIndex() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String[] fizz = {\"1\"};",
            "  static void foo() {",
            "    for (int i = 0; i < fizz.length; i++) {",
            "      if (fizz[i] != null) {",
            "        fizz[i].toString();",
            "      }",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void forEachLoop() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String[] fizz = {\"1\"};",
            "  static void foo() {",
            "    for (String s : fizz) {",
            "      if (s != null) {",
            "        s.toString();",
            "      }",
            "      // BUG: Diagnostic contains: dereferenced expression s is @Nullable",
            "      s.toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void methodInvocationIndexArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String[] fizz = {\"1\"};",
            "  static int getIndex() {",
            "    return 0;",
            "  }",
            "  static final Integer i = 0;",
            "  static void foo() {",
            "    if (fizz[getIndex()] != null) {",
            "    // index methods aren't handled by dataflow",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[getIndex()] is @Nullable",
            "      fizz[getIndex()].toString();",
            "    }",
            "    if (fizz[i] != null) {",
            "    // wrapper class indexes aren't handled by dataflow",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[i] is @Nullable",
            "      fizz[i].toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[getIndex()] is @Nullable",
            "    fizz[getIndex()].toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arithmeticIndexArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String[] fizz = {\"1\", null};",
            "  static void foo() {",
            "    int i = 0;",
            "    if (fizz[i+1] != null) {",
            "    // index expressions aren't handled by dataflow",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[i+1] is @Nullable",
            "      fizz[i+1].toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression fizz[i+1] is @Nullable",
            "    fizz[i+1].toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayMethodInvocationIndex() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String[] getArray() { return new String[] {\"1\", null}; }",
            "  static void foo() {",
            "    if (getArray()[0] != null) {",
            "    // array resulting from method invocation isn't handled by dataflow",
            "    // BUG: Diagnostic contains: dereferenced expression getArray()[0] is @Nullable",
            "      getArray()[0].toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression getArray()[0] is @Nullable",
            "    getArray()[0].toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void mismatchedIndexUse() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String[] fizz = {\"1\", null};",
            "  static void foo() {",
            "    int i = 0;",
            "    if (fizz[i] != null) {",
            "      // BUG: Diagnostic contains: dereferenced expression fizz[i+1] is @Nullable",
            "      fizz[i+1].toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseAndDeclarationAnnotationOnArray() {
    makeHelper()
        .addSourceLines(
            "Nullable.java",
            "package com.uber;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})",
            "public @interface Nullable {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  @Nullable Object[] foo1 = null;",
            "  static String @Nullable[] foo2 = null;",
            "  static void bar() {",
            "      if (foo2 !=null){",
            "           // annotation is treated as declaration",
            "           String bar = foo2[0].toString(); ",
            "      }",
            "      // BUG: Diagnostic contains: dereferenced expression foo2 is @Nullable",
            "      String bar = foo2[0].toString(); ",
            "  }",
            "  static @Nullable String @Nullable [] foo3 = null;",
            "  static void fizz() {",
            "      if (foo3 !=null){",
            "           // annotation is also applied to the elements",
            "      // BUG: Diagnostic contains: dereferenced expression foo3[0] is @Nullable",
            "           String bar = foo3[0].toString(); ",
            "      }",
            "      // BUG: Diagnostic contains: dereferenced expression foo3 is @Nullable",
            "      String bar = foo3[0].toString(); ",
            "  }",
            "  @Nullable Object [][] foo4 = null;",
            "  Object @Nullable [][] foo5 = null;",
            "  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "  Object [] @Nullable [] foo6 = null;",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
