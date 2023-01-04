/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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

package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
public class NullAwayCoreTests extends NullAwayTestsBase {

  @Test
  public void coreNullabilityPositiveCases() {
    defaultCompilationHelper.addSourceFile("NullAwayPositiveCases.java").doTest();
  }

  @Test
  public void nullabilityAnonymousClass() {
    defaultCompilationHelper.addSourceFile("NullAwayAnonymousClass.java").doTest();
  }

  @Test
  public void coreNullabilityNegativeCases() {
    defaultCompilationHelper
        .addSourceFile("NullAwayNegativeCases.java")
        .addSourceFile("OtherStuff.java")
        .addSourceFile("TestAnnot.java")
        .addSourceFile("unannotated/UnannotatedClass.java")
        .doTest();
  }

  @Test
  public void assertSupportPositiveCases() {
    defaultCompilationHelper.addSourceFile("CheckAssertSupportPositiveCases.java").doTest();
  }

  @Test
  public void assertSupportNegativeCases() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AssertsEnabled=true"))
        .addSourceFile("CheckAssertSupportNegativeCases.java")
        .doTest();
  }

  @Test
  public void testGenericAnonymousInner() {
    defaultCompilationHelper
        .addSourceLines(
            "GenericSuper.java",
            "package com.uber;",
            "class GenericSuper<T> {",
            "  T x;",
            "  GenericSuper(T y) {",
            "    this.x = y;",
            "  }",
            "}")
        .addSourceLines(
            "AnonSub.java",
            "package com.uber;",
            "import java.util.List;",
            "import javax.annotation.Nullable;",
            "class AnonSub {",
            "  static GenericSuper<List<String>> makeSuper(List<String> list) {",
            "    return new GenericSuper<List<String>>(list) {};",
            "  }",
            "  static GenericSuper<List<String>> makeSuperBad(@Nullable List<String> list) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'list' where @NonNull",
            "    return new GenericSuper<List<String>>(list) {};",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void erasedIterator() {
    // just checking for crash
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.*;",
            "class Test {",
            "  static class Foo implements Iterable {",
            "    public Iterator iterator() {",
            "      return new Iterator() {",
            "        @Override",
            "        public boolean hasNext() {",
            "          return false;",
            "        }",
            "        @Override",
            "        public Iterator next() {",
            "          throw new NoSuchElementException();",
            "        }",
            "      };",
            "    }",
            "  }",
            "  static void testErasedIterator(Foo foo) {",
            "    for (Object x : foo) {",
            "      x.hashCode();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void compoundAssignment() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void assignments() {",
            "    String x = null; x += \"hello\";",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    Integer y = null; y += 3;",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    boolean b = false; Boolean c = null; b |= c;",
            "  }",
            "  static Integer returnCompound() {",
            "    Integer z = 7;",
            "    return (z += 10);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayIndexUnbox() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void indexUnbox() {",
            "    Integer x = null; int[] fizz = { 0, 1 };",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    int y = fizz[x];",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void cfNullableArrayField() {
    defaultCompilationHelper
        .addSourceLines(
            "CFNullable.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "import java.util.List;",
            "abstract class CFNullable<E> {",
            "  List<E> @Nullable [] table;",
            "}")
        .doTest();
  }

  @Test
  public void supportSwitchExpression() {
    defaultCompilationHelper
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "enum Level {",
            " HIGH, MEDIUM, LOW }",
            "class TestPositive {",
            "   void foo(@Nullable Integer s) {",
            "    // BUG: Diagnostic contains: switch expression s is @Nullable",
            "    switch(s) {",
            "      case 5: break;",
            "    }",
            "    String x = null;",
            "    // BUG: Diagnostic contains: switch expression x is @Nullable",
            "    switch(x) {",
            "      default: break;",
            "    }",
            "    Level level = null;",
            "    // BUG: Diagnostic contains: switch expression level is @Nullable",
            "    switch (level) {",
            "      default: break; }",
            "    }",
            "}")
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class TestNegative {",
            "   void foo(Integer s, short y) {",
            "    switch(s) {",
            "      case 5: break;",
            "    }",
            "    String x = \"irrelevant\";",
            "    switch(x) {",
            "      default: break;",
            "    }",
            "    switch(y) {",
            "      default: break;",
            "    }",
            "    Level level = Level.HIGH;",
            "    switch (level) {",
            "      default: break;",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testCastToNonNull() {
    defaultCompilationHelper
        .addSourceFile("Util.java")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(@Nullable Object o) {",
            "    return castToNonNull(o);",
            "  }",
            "  Object test2(Object o) {",
            "    // BUG: Diagnostic contains: passing known @NonNull parameter 'o' to CastToNonNullMethod",
            "    return castToNonNull(o);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testCastToNonNullExtraArgsWarning() {
    defaultCompilationHelper
        .addSourceFile("Util.java")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(Object o) {",
            "    // BUG: Diagnostic contains: passing known @NonNull parameter 'o' to CastToNonNullMethod",
            "    return castToNonNull(o, \"o should be @Nullable but never actually null\");",
            "  }",
            "  Object test2(Object o) {",
            "    // BUG: Diagnostic contains: passing known @NonNull parameter 'o' to CastToNonNullMethod",
            "    return castToNonNull(\"o should be @Nullable but never actually null\", o, 0);",
            "  }",
            "  Object test3(@Nullable Object o) {",
            "    // Expected use of cast",
            "    return castToNonNull(o, \"o should be @Nullable but never actually null\");",
            "  }",
            "  Object test4(@Nullable Object o) {",
            "    // Expected use of cast",
            "    return castToNonNull(o, \"o should be @Nullable but never actually null\");",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testReadStaticInConstructor() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  // BUG: Diagnostic contains: @NonNull static field o not initialized",
            "  static Object o;",
            "  Object f, g;",
            "  public Test() {",
            "    f = new String(\"hi\");",
            "    o = new Object();",
            "    g = o;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void customErrorURL() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ErrorURL=http://mydomain.com/nullaway"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void foo() {",
            "    // BUG: Diagnostic contains: mydomain.com",
            "    Object x = null; x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void defaultURL() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void foo() {",
            "    // BUG: Diagnostic contains: t.uber.com/nullaway",
            "    Object x = null; x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void invokeNativeFromInitializer() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  Object f;",
            "  private native void foo();",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field f",
            "  Test() {",
            "    foo();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testCapturingScopes() {
    defaultCompilationHelper.addSourceFile("CapturingScopes.java").doTest();
  }

  @Test
  public void testEnhancedFor() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.util.List;",
            "public class Test {",
            "  public void testEnhancedFor(@Nullable List<String> l) {",
            "    // BUG: Diagnostic contains: enhanced-for expression l is @Nullable",
            "    for (String x: l) {}",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testMapWithCustomPut() { // See https://github.com/uber/NullAway/issues/389
    defaultCompilationHelper
        .addSourceLines(
            "Item.java",
            "package com.uber.lib.unannotated.collections;",
            "public class Item<K,V> {",
            " public final K key;",
            " public final V value;",
            " public Item(K k, V v) {",
            "  this.key = k;",
            "  this.value = v;",
            " }",
            "}")
        .addSourceLines(
            "MapLike.java",
            "package com.uber.lib.unannotated.collections;",
            "import java.util.HashMap;",
            "// Too much work to implement java.util.Map from scratch",
            "public class MapLike<K,V> extends HashMap<K,V> {",
            " public MapLike() {",
            "   super();",
            " }",
            " public void put(Item<K,V> item) {",
            "   put(item.key, item.value);",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.collections.Item;",
            "import com.uber.lib.unannotated.collections.MapLike;",
            "public class Test {",
            " public static MapLike test_389(@Nullable Item<String, String> item) {",
            "  MapLike<String, String> map = new MapLike<String, String>();",
            "  if (item != null) {", // Required to trigger dataflow analysis
            "    map.put(item);",
            "  }",
            "  return map;",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void derefNullableTernary() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "public class Test {",
            "  public void derefTernary(boolean b) {",
            "    Object o1 = null, o2 = new Object();",
            "    // BUG: Diagnostic contains: dereferenced expression (b ? o1 : o2) is @Nullable",
            "    (b ? o1 : o2).toString();",
            "    // BUG: Diagnostic contains: dereferenced expression (b ? o2 : o1) is @Nullable",
            "    (b ? o2 : o1).toString();",
            "    // This case is safe",
            "    (b ? o2 : o2).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testCustomNullableAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CustomNullableAnnotations=qual.Null"))
        .addSourceLines("qual/Null.java", "package qual;", "public @interface Null {", "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import qual.Null;",
            "class Test {",
            "   @Null Object foo;", // No error, should detect @Null
            "   @Null Object baz(){",
            "     bar(foo);",
            "     return null;", // No error, should detect @Null
            "   }",
            "   String bar(@Null Object item){",
            "     // BUG: Diagnostic contains: dereferenced expression item is @Nullable",
            "     return item.toString();",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testCustomNonnullAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedClasses=com.uber.Other",
                "-XepOpt:NullAway:CustomNonnullAnnotations=qual.NoNull",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines("qual/NoNull.java", "package qual;", "public @interface NoNull {", "}")
        .addSourceLines(
            "Other.java",
            "package com.uber;",
            "import qual.NoNull;",
            "public class Other {",
            "   void bar(@NoNull Object item) { }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "   Other other = new Other();",
            "   void foo(){",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "     other.bar(null);",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapGetChainWithCast() {
    defaultCompilationHelper
        .addSourceLines(
            "Constants.java",
            "package com.uber;",
            "public class Constants {",
            "   public static final String KEY_1 = \"key1\";",
            "   public static final String KEY_2 = \"key2\";",
            "   public static final String KEY_3 = \"key3\";",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "class Test {",
            "   boolean withoutCast(Map<String, Map<String, Map<String, Object>>> topLevelMap){",
            "     return topLevelMap.get(Constants.KEY_1) == null ",
            "       || topLevelMap.get(Constants.KEY_1).get(Constants.KEY_2) == null",
            "       || topLevelMap.get(Constants.KEY_1).get(Constants.KEY_2).get(Constants.KEY_3) == null;",
            "   }",
            "   boolean withCast(Map<String, Object> topLevelMap){",
            "     return topLevelMap.get(Constants.KEY_1) == null ",
            "       || ((Map<String,Object>) topLevelMap.get(Constants.KEY_1)).get(Constants.KEY_2) == null",
            "       || ((Map<String,Object>) ",
            "              ((Map<String,Object>) topLevelMap.get(Constants.KEY_1)).get(Constants.KEY_2))",
            "                .get(Constants.KEY_3) == null;",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapPutAndPutIfAbsent() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "class Test {",
            "   Object testPut(String key, Object o, Map<String, Object> m){",
            "     m.put(key, o);",
            "     return m.get(key);",
            "   }",
            "   Object testPutIfAbsent(String key, Object o, Map<String, Object> m){",
            "     m.putIfAbsent(key, o);",
            "     return m.get(key);",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapComputeIfAbsent() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import java.util.function.Function;",
            // Need JSpecify (vs javax) for annotating generics
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "   Object testComputeIfAbsent(String key, Function<String, Object> f, Map<String, Object> m){",
            "     m.computeIfAbsent(key, f);",
            "     return m.get(key);",
            "   }",
            "   Object testComputeIfAbsentLambda(String key, Map<String, Object> m){",
            "     m.computeIfAbsent(key, k -> k);",
            "     return m.get(key);",
            "   }",
            "   Object testComputeIfAbsentNull(String key, Function<String, @Nullable Object> f, Map<String, Object> m){",
            "     m.computeIfAbsent(key, f);",
            "     // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "     return m.get(key);",
            "   }",
            "   // ToDo: should error somewhere, but doesn't, due to limited checking of generics",
            "   Object testComputeIfAbsentNullLambda(String key, Map<String, Object> m){",
            "     m.computeIfAbsent(key, k -> null);",
            "     return m.get(key);",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapWithMapGetKey() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import java.util.function.Function;",
            "class Test {",
            "   String testMapWithMapGetKey(Map<String,String> m1, Map<String,String> m2) {",
            "     if (m1.containsKey(\"s1\")) {",
            "       if (m2.containsKey(m1.get(\"s1\"))) {",
            "         return m2.get(m1.get(\"s1\")).toString();",
            "       }",
            "     }",
            "     return \"no\";",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void tryFinallySupport() {
    defaultCompilationHelper.addSourceFile("NullAwayTryFinallyCases.java").doTest();
  }

  @Test
  public void tryWithResourcesSupport() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.io.BufferedReader;",
            "import java.io.FileReader;",
            "import java.io.IOException;",
            "class Test {",
            "  String foo(String path, @Nullable String s, @Nullable Object o) throws IOException {",
            "    try (BufferedReader br = new BufferedReader(new FileReader(path))) {",
            "      // Code inside try-resource gets analyzed",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      o.toString();",
            "      s = br.readLine();",
            "      return s;",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void tryWithResourcesSupportInit() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.io.BufferedReader;",
            "import java.io.FileReader;",
            "import java.io.IOException;",
            "class Test {",
            "  private String path;",
            "  private String f;",
            "  Test(String p) throws IOException {",
            "    path = p;",
            "    try (BufferedReader br = new BufferedReader(new FileReader(path))) {",
            "      f = br.readLine();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void tryFinallySupportInit() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.io.BufferedReader;",
            "import java.io.FileReader;",
            "import java.io.IOException;",
            "class Test {",
            "  private String path;",
            "  private String f;",
            "  Test(String p) throws IOException {",
            "    path = p;",
            "    try {",
            "      BufferedReader br = new BufferedReader(new FileReader(path));",
            "      f = br.readLine();",
            "    } finally {",
            "      f = \"DEFAULT\";",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableOnJavaLangVoid() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Void foo1() {",
            "    // temporarily, we treat a Void return type as if it was @Nullable Void",
            "    return null;",
            "  }",
            "  @Nullable Void foo2() {",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableOnJavaLangVoidWithCast() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  // Currently unhandled. In fact, it *should* produce an error. This entire test case",
            "  // needs to be rethought once we properly support generics, such that it works on T v",
            "  // when T == @Nullable Void, but not when T == Void. Without generics, though, this is the",
            "  // best we can do.",
            "  @SuppressWarnings(\"NullAway\")",
            "  private Void v = (Void)null;",
            "  Void foo1() {",
            "    // temporarily, we treat a Void return type as if it was @Nullable Void",
            "    return (Void)null;",
            "  }",
            "  // Temporarily, we treat any Void formal as if it were @Nullable Void",
            "  void consumeVoid(Void v) {",
            "  }",
            "  @Nullable Void foo2() {",
            "    consumeVoid(null); // See comment on consumeVoid for why this is allowed",
            "    consumeVoid((Void)null);",
            "    return (Void)null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void staticCallZeroArgsNullCheck() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable static Object nullableReturn() { return new Object(); }",
            "  void foo() {",
            "    if (nullableReturn() != null) {",
            "      nullableReturn().toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    nullableReturn().toString();",
            "  }",
            "  void foo2() {",
            "    if (Test.nullableReturn() != null) {",
            "      nullableReturn().toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    Test.nullableReturn().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void primitiveCasts() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "    static void foo(int i) { }",
            "    static void m() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = (int) i;",
            "        // this is fine",
            "        int i3 = (int) Integer.valueOf(3);",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i4 = ((int) i) + 1;",
            "        // BUG: Diagnostic contains: unboxing",
            "        foo((int) i);",
            "        // try another type",
            "        Double d = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        double d2 = (double) d;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void unboxingInBinaryTrees() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "    static void m1() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = i + j;",
            "    }",
            "    static void m2() {",
            "        Integer i = null;",
            "        // this is fine",
            "        String s = i + \"hi\";",
            "    }",
            "    static void m3() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i3 = i - j;",
            "    }",
            "    static void m4() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i4 = i * j;",
            "    }",
            "    static void m5() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i5 = i << 2;",
            "    }",
            "    static void m6() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        boolean b1 = i <= j;",
            "    }",
            "    static void m7() {",
            "        Boolean x = null;",
            "        Boolean y = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        boolean b2 = x && y;",
            "    }",
            "    static void m8() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // this is fine",
            "        boolean b = i == j;",
            "    }",
            "    static void m9() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        boolean b = i != 0;",
            "    }",
            "    static void m10() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int j = 3 - i;",
            "    }",
            "    static void m11() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = i",
            "          +",
            "          // BUG: Diagnostic contains: unboxing",
            "          j;",
            "    }",
            "    static void m12() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = i",
            "          +",
            "          // no error here, due to previous unbox of i",
            "          i;",
            "    }",
            "    static void m13() {",
            "        int[] arr = null;",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: dereferenced",
            "        int i2 = arr[",
            "          // BUG: Diagnostic contains: unboxing",
            "          i];",
            "    }",
            "    static void primitiveArgs(int x, int y) {}",
            "    static void m14() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        primitiveArgs(",
            "          // BUG: Diagnostic contains: unboxing",
            "          i,",
            "          // BUG: Diagnostic contains: unboxing",
            "          j);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void primitiveCastsRememberNullChecks() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class Test {",
            "    static void foo(int i) { }",
            "    static void m1(@Nullable Integer i) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i1 = (int) i;",
            "    }",
            "    static void m2(@Nullable Integer i) {",
            "        if (i != null) {",
            "            // this is fine",
            "            int i2 = (int) i;",
            "        }",
            "    }",
            "    static void m3(@Nullable Integer i) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i3 = (int) i;",
            "    }",
            "    static void m4(@Nullable Integer i) {",
            "        Preconditions.checkNotNull(i);",
            "        // this is fine",
            "        int i4 = (int) i;",
            "    }",
            "    static private void consumeInt(int i) { }",
            "    static void m5(@Nullable Integer i) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        consumeInt((int) i);",
            "    }",
            "    static void m6(@Nullable Integer i) {",
            "        Preconditions.checkNotNull(i);",
            "        // this is fine",
            "        consumeInt((int) i);",
            "    }",
            "    static void m7(@Nullable Object o) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        consumeInt((int) o);",
            "    }",
            "    static void m8(@Nullable Object o) {",
            "        Preconditions.checkNotNull(o);",
            "        // this is fine",
            "        consumeInt((int) o);",
            "    }",
            "    static void m9(Map<String,Object> m) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        consumeInt((int) m.get(\"foo\"));",
            "    }",
            "    static void m10(Map<String,Object> m) {",
            "        if(m.get(\"bar\") != null) {",
            "            // this is fine",
            "            consumeInt((int) m.get(\"bar\"));",
            "        }",
            "    }",
            "    static void m11(Map<String,Object> m) {",
            "        Preconditions.checkNotNull(m.get(\"bar\"));",
            "        // this is fine",
            "        consumeInt((int) m.get(\"bar\"));",
            "    }",
            "}")
        .doTest();
  }
}
