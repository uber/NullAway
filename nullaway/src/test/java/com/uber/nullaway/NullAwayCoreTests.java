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
}
