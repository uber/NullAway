package com.uber.nullaway;

import org.junit.Test;

public class AccessPathsTests extends NullAwayTestsBase {

  @Test
  public void testConstantsInAccessPathsNegative() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface NullableContainer<K, V> {
             @Nullable public V get(K k);
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Test {
              public void testSingleStringCheck(NullableContainer<String, Object> c) {
                if (c.get("KEY_STR") != null) {
                  c.get("KEY_STR").toString(); // is safe
                }
              }
              public void testSingleIntCheck(NullableContainer<Integer, Object> c) {
                if (c.get(42) != null) {
                  c.get(42).toString(); // is safe
                }
              }
              public void testMultipleChecks(NullableContainer<String, NullableContainer<Integer, Object>> c) {
                if (c.get("KEY_STR") != null && c.get("KEY_STR").get(42) != null) {
                  c.get("KEY_STR").get(42).toString(); // is safe
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testConstantsInAccessPathsPositive() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface NullableContainer<K, V> {
             @Nullable public V get(K k);
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Test {
              public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {
                if (c.get("KEY_STR") != null && c.get("KEY_STR").get(0) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'c.get("KEY_STR").get(42)' is @Nullable
                  c.get("KEY_STR").get(42).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constantArgumentToAPrimitiveParameterMatchesByTheWidenedValue() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Test {
              static final byte BYTE_ONE = 1;
              static final byte BYTE_UNO = 1;
              static final short SHORT_ONE = 1;
              @Nullable Object b(byte x) { return null; }
              @Nullable Object s(short x) { return null; }
              @Nullable Object i(int x) { return null; }
              @Nullable Object l(long x) { return null; }
              @Nullable Object f(float x) { return null; }
              @Nullable Object d(double x) { return null; }
              void floatWidensToADifferentDouble() {
                // 0.1f widens to 0.10000000149011612, a different double than 0.1d
                if (d(0.1d) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'd(0.1f)' is @Nullable
                  d(0.1f).toString();
                }
              }
              void intWidensToAnEqualLong() {
                if (l(0) != null) {
                  l(0L).toString();
                }
              }
              void intWidensToAnEqualFloat() {
                if (f(1) != null) {
                  f(1.0f).toString();
                }
              }
              void intWidensToAnEqualDouble() {
                if (d(1) != null) {
                  d(1.0).toString();
                }
              }
              void charWidensToItsCodeAsInt() {
                if (i('a') != null) {
                  i(97).toString();
                }
              }
              void byteConstantWidensToAnEqualShortConstant() {
                if (s(BYTE_ONE) != null) {
                  s(SHORT_ONE).toString();
                }
              }
              void byteConstantsWithEqualValues() {
                if (b(BYTE_ONE) != null) {
                  b(BYTE_UNO).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constantArgumentToAnObjectParameterMatchesByTheBoxedClassAndValue() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Test {
              @Nullable Object o(Object x) { return null; }
              void integerAndLongWithEqualValues() {
                if (o(42) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'o(42L)' is @Nullable
                  o(42L).toString();
                }
              }
              void integerAndStringWithEqualText() {
                if (o(42) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'o("42")' is @Nullable
                  o("42").toString();
                }
              }
              void integerAndCharacterWithEqualCode() {
                if (o(97) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'o('a')' is @Nullable
                  o('a').toString();
                }
              }
              void longValueOfAnIntAndALongLiteral() {
                if (o(42L) != null) {
                  o(Long.valueOf(42)).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void onlyAWrapperValueOfOfItsOwnPrimitiveIsUnwrappedAsBoxing() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Test {
              @Nullable Object o(Object x) { return null; }
              void integerValueOfAnInt() {
                if (o(42) != null) {
                  o(Integer.valueOf(42)).toString();
                }
              }
              void stringValueOfAnInt() {
                if (o(42) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'o(String.valueOf(42))' is @Nullable
                  o(String.valueOf(42)).toString();
                }
              }
              void integerValueOfAString() {
                if (o("42") != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'o(Integer.valueOf("42"))' is @Nullable
                  o(Integer.valueOf("42")).toString();
                }
              }
              void integerToString() {
                if (o(42) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'o(Integer.toString(42))' is @Nullable
                  o(Integer.toString(42)).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testVariablesInAccessPathsNegative() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface NullableContainer<K, V> {
             @Nullable public V get(K k);
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Test {
              private static final int INT_KEY = 42; // Guaranteed constant!
              // Guaranteed constant after class loading
              private static final int INT_KEY_HC = "teststr".hashCode();
              public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {
                if (c.get("KEY_STR") != null && c.get("KEY_STR").get(INT_KEY) != null) {
                  c.get("KEY_STR").get(INT_KEY).toString();
                  c.get("KEY_STR").get(Test.INT_KEY).toString();
                  c.get("KEY_STR").get(42).toString(); // Extra magic!
                }
                if (c.get("KEY_STR") != null && c.get("KEY_STR").get(INT_KEY_HC) != null) {
                  c.get("KEY_STR").get(INT_KEY_HC).toString();
                  c.get("KEY_STR").get(Test.INT_KEY_HC).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testVariablesInAccessPathsPositive() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public interface NullableContainer<K, V> {
             @Nullable public V get(K k);
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Test {
              private Integer intKey = 42; // No guarantee it's a constant
              public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {
                if (c.get("KEY_STR") != null && c.get("KEY_STR").get(this.intKey) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'c.get("KEY_STR").get
                  c.get("KEY_STR").get(this.intKey).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testField() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Foo {
             @Nullable public Object o;
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import javax.annotation.Nullable;
            public class Test {
              public String testFieldCheck(Foo foo) {
                if (foo.o == null) {
                  foo.o = new Object();
                }
                return foo.o.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testArrayListField() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import java.util.List;
            import javax.annotation.Nullable;
            public class Foo {
             @Nullable public List<Object> list;
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import javax.annotation.Nullable;
            public class Test {
              public Object testFieldCheck(Foo foo) {
                if (foo.list == null) {
                  foo.list = new ArrayList<Object>();
                }
                return foo.list.get(0);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testFieldWithoutValidAccessPath() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Foo {
             @Nullable public Object o;
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import javax.annotation.Nullable;
            public class Test {
              public String testFieldCheck(Foo foo) {
                if (foo.o == null) {
                  (new Foo()).o = new Object();
                }
                // BUG: Diagnostic contains: dereferenced expression 'foo.o' is @Nullable
                return foo.o.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testFieldWithoutValidAccessPathLongChain() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Foo {
             public Foo nonNull;
             public Foo() {
               nonNull = this;
             }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import javax.annotation.Nullable;
            public class Test {
              public String testFieldCheck(Foo foo) {
                // Just checking that NullAway doesn't crash on a long but ultimately rootless access path
                return (new Foo()).nonNull.nonNull.toString().toLowerCase();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testFinalFieldNullabilityPropagatesToInnerContexts() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Foo {
             @Nullable public final Bar bar;
             @Nullable public Bar mutableBar;
             public Foo(@Nullable Bar bar) {
               this.bar = bar;
               this.mutableBar = bar;
             }
            }
            """)
        .addSourceLines(
            "Bar.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Bar {
             @Nullable public final Foo foo;
             @Nullable public Foo mutableFoo;
             public Bar(@Nullable Foo foo) {
               this.foo = foo;
               this.mutableFoo = foo;
             }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.base.Preconditions;
            import java.util.ArrayList;
            import java.util.function.Predicate;
            import java.util.function.Function;
            import javax.annotation.Nullable;
            public class Test {
              @Nullable private final Foo foo;
              @Nullable private Foo mutableFoo;
              public Test(@Nullable Foo foo) {
                this.foo = foo;
                this.mutableFoo = foo;
              }
              public Predicate<String> testReadFinalFromLambdaNoCheck() {
                // BUG: Diagnostic contains: dereferenced expression 'foo' is @Nullable
                return (s -> foo.toString().equals(s));
              }
              public Predicate<String> testReadFinalFromLambdaAfterCheck() {
                Preconditions.checkNotNull(foo);
                // safe!
                return (s -> foo.toString().equals(s));
              }
              public Predicate<String> testReadMutableFromLambdaAfterCheck() {
                Preconditions.checkNotNull(mutableFoo);
                // BUG: Diagnostic contains: dereferenced expression 'mutableFoo' is @Nullable
                return (s -> mutableFoo.toString().equals(s));
              }
              public Function<String, Predicate<String>> testReadFinalFromLambdaAfterCheckDeepContext() {
                Preconditions.checkNotNull(foo);
                // safe!
                return (s1 -> (s2 -> foo.toString().equals(s1 + s2)));
              }
              public Predicate<String> testReadFinalFromLambdaAfterCheckDeepAP() {
                Preconditions.checkNotNull(foo);
                Preconditions.checkNotNull(foo.bar);
                Preconditions.checkNotNull(foo.bar.foo);
                // safe!
                return (s -> foo.bar.foo.toString().equals(s));
              }
              public Predicate<String> testReadFinalFromLambdaAfterCheckDeepAPIncomplete() {
                Preconditions.checkNotNull(foo);
                Preconditions.checkNotNull(foo.bar);
                // BUG: Diagnostic contains: dereferenced expression 'foo.bar.foo' is @Nullable
                return (s -> foo.bar.foo.toString().equals(s));
              }
              public Predicate<String> testReadMutableFromLambdaAfterCheckDeepAP1() {
                Preconditions.checkNotNull(foo);
                Preconditions.checkNotNull(foo.mutableBar);
                Preconditions.checkNotNull(foo.mutableBar.foo);
                // BUG: Diagnostic contains: dereferenced expression 'foo.mutableBar' is @Nullable
                return (s -> foo.mutableBar.foo.toString().equals(s));
              }
              public Predicate<String> testReadMutableFromLambdaAfterCheckDeepAP2() {
                Preconditions.checkNotNull(foo);
                Preconditions.checkNotNull(foo.bar);
                Preconditions.checkNotNull(foo.bar.mutableFoo);
                // BUG: Diagnostic contains: dereferenced expression 'foo.bar.mutableFoo' is @Nullable
                return (s -> foo.bar.mutableFoo.toString().equals(s));
              }
              public boolean testReadFinalFromLambdaAfterCheckLocalClass(String s) {
                Preconditions.checkNotNull(foo);
                // safe!
                class Inner {
                   public Inner() { }
                   public boolean doTest(String s) { return foo.toString().equals(s); }
                }
                return (new Inner()).doTest(s);
              }
              public boolean testReadFinalFromLambdaCheckBeforeUseLocalClass(String s) {
                class Inner {
                   public Inner() { }
                   // At the time of declaring this, foo is not known to be non-null!
                   public boolean doTest(String s)  {
                     // BUG: Diagnostic contains: dereferenced expression 'foo' is @Nullable
                     return foo.toString().equals(s);
                   }
                }
                // Technically safe, but hard to reason about: needs to be aware of *when* doTest() can be
                // called which is generally _beyond_ NullAway.
                Preconditions.checkNotNull(foo);
                return (new Inner()).doTest(s);
              }
              public boolean testReadMutableFromLambdaAfterCheckLocalClass(String s) {
                Preconditions.checkNotNull(mutableFoo);
                class Inner {
                   public Inner() { }
                   public boolean doTest(String s) {
                     // BUG: Diagnostic contains: dereferenced expression 'mutableFoo' is @Nullable
                     return mutableFoo.toString().equals(s);
                   }
                   public boolean doTestSafe(String s) {
                     Preconditions.checkNotNull(mutableFoo);
                     return mutableFoo.toString().equals(s);
                   }
                }
                if (s.length() % 2 == 0) {
                  return (new Inner()).doTest(s);
                } else {
                   // safe!
                  return (new Inner()).doTestSafe(s);
                }
              }
              public boolean testReadFinalFromLambdaAfterCheckLocalClassWithNameCollision(String s) {
                Preconditions.checkNotNull(foo);
                class Inner {
                   @Nullable private Foo foo;
                   public Inner() { this.foo = null; }
                   public boolean doTest(String s)  {
                     // BUG: Diagnostic contains: dereferenced expression 'foo' is @Nullable
                     return foo.toString().equals(s);
                   }
                   public boolean doTestSafe(String s)  {
                     // TODO: Technically safe, but it would need recognizing Test.this.[...] as the same AP as
                     //       that from the closure.
                     // BUG: Diagnostic contains: dereferenced expression 'Test.this.foo' is @Nullable
                     return Test.this.foo.toString().equals(s);
                   }
                }
                if (s.length() % 2 == 0) {
                  return (new Inner()).doTest(s);
                } else {
                  return (new Inner()).doTestSafe(s);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testAccessUsingExplicitThis() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            public class Foo {
              @Nullable public Object bar;
              public class Nested {
                @Nullable public Object bar;
                public void testNegative1() {
                  if (Foo.this.bar != null) {
                    Foo.this.bar.toString();
                  }
                }
                public void testNegative2() {
                  // Foo.this can never be null
                  Foo.this.toString();
                }
                public void testPositive() {
                  if (Foo.this.bar != null) {
                    // BUG: Diagnostic contains: dereferenced expression 'this.bar' is @Nullable
                    this.bar.toString();
                  }
                  if (this.bar != null) {
                    // BUG: Diagnostic contains: dereferenced expression 'Foo.this.bar' is @Nullable
                    Foo.this.bar.toString();
                  }
                }
              }
              public void testUnhandled1() {
                if (bar != null) {
                  // This is safe but we don't currently handle it
                  // BUG: Diagnostic contains: dereferenced expression 'Foo.this.bar' is @Nullable
                  Foo.this.bar.toString();
                }
              }
              public void testUnhandled2() {
                if (Foo.this.bar != null) {
                  // This is safe but we don't currently handle it
                  // BUG: Diagnostic contains: dereferenced expression 'bar' is @Nullable
                  bar.toString();
                }
              }
              public void testNegative1() {
                if (bar != null) {
                  // This is safe and handled
                  this.bar.toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapKeysFromValueOf() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import java.util.HashMap;
            import java.util.Map;
            public class Foo {
              private final Map<Integer, Object> map = new HashMap<>();
              private final Map<Long, Object> longMap = new HashMap<>();
              static Integer valueOf(int i) { return 0; }
              static Integer valueOf(int i, int j) { return i+j; }
              public void putThenGetIntegerValueOf() {
                map.put(Integer.valueOf(10), new Object());
                map.get(Integer.valueOf(10)).toString();
              }
              public void putThenGetLongValueOf() {
                longMap.put(Long.valueOf(10), new Object());
                longMap.get(Long.valueOf(10)).toString();
              }
              public void putThenGetFooValueOf() {
                map.put(valueOf(10), new Object());
                // Unknown valueOf method so we report a warning
                // BUG: Diagnostic contains: dereferenced expression 'map.get(valueOf(10))' is @Nullable
                map.get(valueOf(10)).toString();
                map.put(valueOf(10,20), new Object());
                // Unknown valueOf method so we report a warning
                // BUG: Diagnostic contains: dereferenced expression 'map.get(valueOf(10,20))' is @Nullable
                map.get(valueOf(10,20)).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapKeyFromIntegerValueOfStaticImport() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import java.util.HashMap;
            import java.util.Map;
            import static java.lang.Integer.valueOf;
            public class Foo {
              private final Map<Integer, Object> map = new HashMap<>();
              public void putThenGet() {
                map.put(valueOf(10), new Object());
                map.get(valueOf(10)).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constantMapKeyMatchesOnlyTheSameBoxedClassAndValue() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              void integerAndLongKeys(Map<Object, Object> m) {
                if (m.get(42) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(42L)' is @Nullable
                  m.get(42L).toString();
                }
              }
              void longValueOfAnIntAndALongLiteral(Map<Object, Object> m) {
                if (m.get(Long.valueOf(42)) != null) {
                  m.get(42L).toString();
                }
              }
              void stringAndIntegerValueOfTheString(Map<Object, Object> m) {
                if (m.get("42") != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(Integer.valueOf("42"))' is @Nullable
                  m.get(Integer.valueOf("42")).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void boxedVariableMapKeyMatchesOnlyTheSameWrapper() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              void intKeyAndLongValueOfIt(Map<Object, Object> m, int i) {
                if (m.get(i) != null) {
                  // m.get(i) looks up an Integer and m.get(Long.valueOf(i)) a Long
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(Long.valueOf(i))' is @Nullable
                  m.get(Long.valueOf(i)).toString();
                }
              }
              void intKeyAndIntegerValueOfIt(Map<Object, Object> m, int i) {
                if (m.get(i) != null) {
                  m.get(Integer.valueOf(i)).toString();
                }
              }
              void longValueOfAnIntKeyAndItself(Map<Object, Object> m, int i) {
                if (m.get(Long.valueOf(i)) != null) {
                  m.get(Long.valueOf(i)).toString();
                }
              }
              void longKeyAndLongValueOfIt(Map<Object, Object> m, long l) {
                if (m.get(l) != null) {
                  m.get(Long.valueOf(l)).toString();
                }
              }
              void putThenGetWithAnIntKey(Map<Object, Object> m, int i) {
                m.put(i, new Object());
                m.get(i).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapContainsKeyMakesMapRemoveSafe() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              void testMapRemoveAfterContainsKey(Map<String, Object> map) {
                if (map.containsKey("key")) {
                  map.remove("key").toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapContainsKeyDifferentKeyDoesNotMakeMapRemoveSafe() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              void testMapRemoveAfterContainsKeyDifferentKey(Map<String, Object> map) {
                if (map.containsKey("key1")) {
                  // BUG: Diagnostic contains: dereferenced expression 'map.remove("key2")' is @Nullable
                  map.remove("key2").toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapRemoveConsumesContainsKeyFact() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              void testConsecutiveRemoves(Map<String, Object> map, String key) {
                if (map.containsKey(key)) {
                  map.remove(key).toString();
                  // BUG: Diagnostic contains: dereferenced expression 'map.remove(key)' is @Nullable
                  map.remove(key).toString();
                }
              }
              void testGetAfterRemove(Map<String, Object> map, String key) {
                if (map.containsKey(key)) {
                  map.remove(key).toString();
                  // BUG: Diagnostic contains: dereferenced expression 'map.get(key)' is @Nullable
                  map.get(key).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapRemoveResultCheckDoesNotRefineMapEntry() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              void testMapRemoveResultCheck(Map<String, Object> map, String key) {
                if (map.remove(key) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'map.remove(key)' is @Nullable
                  map.remove(key).toString();
                  // BUG: Diagnostic contains: dereferenced expression 'map.get(key)' is @Nullable
                  map.get(key).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullCheckOfReferenceCastRefinesItsOperand() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String castToOwnType(@Nullable Object o) {
                if ((Object) o != null) {
                  return o.toString();
                }
                return "";
              }
              String downcast(@Nullable Object o) {
                if ((String) o != null) {
                  return o.toString();
                }
                return "";
              }
              String equalsNullBranch(@Nullable Object o) {
                if ((Object) o == null) {
                  // BUG: Diagnostic contains: dereferenced expression 'o' is @Nullable
                  return o.toString();
                }
                return "";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void checkNotNullOfReferenceCastRefinesItsOperand() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import com.google.common.base.Preconditions;
            class Test {
              int checkedVariable(@Nullable Integer i) {
                Preconditions.checkNotNull((Integer) i);
                return i;
              }
              int uncheckedVariable(@Nullable Integer i, @Nullable Integer j) {
                Preconditions.checkNotNull((Integer) j);
                // BUG: Diagnostic contains: unboxing of a @Nullable expression 'i'
                return i;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapKeyUnderReferenceCastIsTheSameKey() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              String referenceCastKey(Map<String, Object> m, String k) {
                if (m.get((String) k) != null) {
                  return m.get(k).toString();
                }
                return "";
              }
              String primitiveCastKey(Map<Object, Object> m, long l) {
                // m.get(l) looks up a Long and m.get((int) l) an Integer, so they are different keys
                if (m.get(l) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get((int) l)' is @Nullable
                  return m.get((int) l).toString();
                }
                return "";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullCheckOfCastMapGetRefinesTheSameGet() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            class Test {
              String conditional(Map<String, Object> m) {
                return (String) m.get("k") == null ? "" : (String) m.get("k");
              }
              String ifNotNull(Map<String, Object> m) {
                if ((String) m.get("k") != null) {
                  return m.get("k").toString();
                }
                return "";
              }
              String otherKey(Map<String, Object> m) {
                if ((String) m.get("k") != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get("j")' is @Nullable
                  return m.get("j").toString();
                }
                return "";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapKeyLiteralUnderCastIsTheSameKey() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.HashMap;
            import java.util.Map;
            class Test {
              String stringLiteral(Map<String, Object> m) {
                if (m.get("k") != null) {
                  return m.get((String) ("k")).toString();
                }
                return "";
              }
              String valueOfKey() {
                Map<Integer, Object> m = new HashMap<>();
                m.put(Integer.valueOf(10), new Object());
                return m.get((Integer) (Integer.valueOf(10))).toString();
              }
              String containsKeyThenRemove(Map<String, Object> m) {
                if (m.containsKey("k")) {
                  return m.remove((String) ("k")).toString();
                }
                return "";
              }
              String intLiteralUnderIntCast(Map<Integer, Object> m) {
                if (m.get(42) != null) {
                  return m.get((int) (42)).toString();
                }
                return "";
              }
              String intVariableUnderIntCast(Map<Integer, Object> m, int key) {
                if (m.get(key) != null) {
                  return m.get((int) (key)).toString();
                }
                return "";
              }
              String otherStringLiteralUnderReferenceCast(Map<String, Object> m) {
                if (m.get("k") != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get((String) ("j"))' is @Nullable
                  return m.get((String) ("j")).toString();
                }
                return "";
              }
              String intLiteralUnderLongCast(Map<Object, Object> m) {
                // m.get(42) looks up an Integer and m.get((long) (42)) a Long, so they are different keys
                if (m.get(42) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get((long) (42))' is @Nullable
                  return m.get((long) (42)).toString();
                }
                return "";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constantArgumentUnderCastIsTheSameArgument() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            import javax.annotation.Nullable;
            class Test {
              static final String K = "k";
              @Nullable Object get(int i) { return null; }
              @Nullable Object apply(String s) { return null; }
              @Nullable Object getBoxed(Integer i) { return null; }
              @Nullable Object getLong(long l) { return null; }
              String intLiteralUnderIntCast() {
                if (get(0) != null) {
                  return get((int) (0)).toString();
                }
                return "";
              }
              String boxedIntLiteralUnderIntCast() {
                if (getBoxed(0) != null) {
                  return getBoxed((int) (0)).toString();
                }
                return "";
              }
              String widenedIntLiteralUnderIntCast() {
                if (getLong(0) != null) {
                  return getLong((int) (0)).toString();
                }
                return "";
              }
              String stringLiteralUnderReferenceCast() {
                if (apply("hello") != null) {
                  return apply((String) ("hello")).toString();
                }
                return "";
              }
              String constantFieldUnderReferenceCast(Map<String, Map<String, Object>> m) {
                if (m.get(K) != null && m.get(K).get(K) != null) {
                  return m.get((String) (K)).get(K).toString();
                }
                return "";
              }
              String otherConstantUnderIntCast() {
                if (get(0) != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'get((int) (1))' is @Nullable
                  return get((int) (1)).toString();
                }
                return "";
              }
            }
            """)
        .doTest();
  }
}
