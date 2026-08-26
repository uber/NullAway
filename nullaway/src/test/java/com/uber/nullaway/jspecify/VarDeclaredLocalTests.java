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
                // BUG: Diagnostic contains: dereferenced expression 'foo1.get()' is @Nullable
                foo1.get().hashCode();
                var foo2 = make(new Object());
                foo2.get().hashCode();
                var foo3 = make(null);
                // BUG: Diagnostic contains: dereferenced expression 'foo3.get()' is @Nullable
                foo3.get().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void varLocalDoesNotProvideTargetTypeForGenericMethodInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<T extends @Nullable Object> {
                T get();
              }
              static <U extends @Nullable Object> Box<U> box(U u) {
                throw new RuntimeException();
              }
              void test() {
                var inferredFromInitializer = box(null);
                // BUG: Diagnostic contains: dereferenced expression 'inferredFromInitializer.get()' is @Nullable
                inferredFromInitializer.get().hashCode();
                // BUG: Diagnostic contains: inference failure
                Box<String> explicitNonNullTarget = box(null);
                Box<@Nullable String> explicitNullableTarget = box(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void varLocalReassigned() {
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
              static <U extends @Nullable Object> Foo<Foo<U>> makeNested(Foo<U> f) {
                throw new RuntimeException();
              }
              void testPositive() {
                var foo = make(new Object());
                // BUG: Diagnostic contains: inference failure
                foo = make(null);
              }
              void testPositive2(Foo<@Nullable Object> f1, Foo<Object> f2) {
                var foo = makeNested(f1);
                // BUG: Diagnostic contains: inference failure
                foo = makeNested(f2);
              }
              void testNegative() {
                var foo = make(null);
                // no warning here since NullAway infers the type argument to be @Nullable Object
                // based on the constraint from the assignment context, and it's legal to pass
                // new Object() as a parameter
                foo = make(new Object());
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
                  // BUG: Diagnostic contains: dereferenced expression 'foo1.get()' is @Nullable
                  foo1.get().hashCode();
                }
                for (var foo1 = make(new Object()); foo1 == null; ) {
                  foo1.get().hashCode();
                }
                for (var foo1 = make(null); foo1 == null; ) {
                  // BUG: Diagnostic contains: dereferenced expression 'foo1.get()' is @Nullable
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
                  // BUG: Diagnostic contains: dereferenced expression 'foo.get()' is @Nullable
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
                    // BUG: Diagnostic contains: dereferenced expression 'foo.get()' is @Nullable
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
                  // BUG: Diagnostic contains: dereferenced expression 'x' is @Nullable
                  x.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitlyTypedEnhancedForLoop() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.List;
            @NullMarked
            class Test {
              void test(
                  List<@Nullable String> nullableList,
                  List<String> nonNullList,
                  List<? extends @Nullable String> nullableWildcardList) {
                for (String value : nullableList) {
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.hashCode();
                }
                for (String value : nonNullList) {
                  value.hashCode();
                }
                for (String value : nullableWildcardList) {
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.hashCode();
                }
              }
              <T extends @Nullable Object> void testNullableTypeVariableUpperBound(
                  List<T> nullableTypeVariableList,
                  List<? extends T> nullableWildcardTypeVariableList) {
                for (T value : nullableTypeVariableList) {
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.hashCode();
                }
                for (T value : nullableWildcardTypeVariableList) {
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.hashCode();
                }
              }
              <T> void testNonNullTypeVariableUpperBound(
                  List<T> nonNullTypeVariableList,
                  List<? extends T> nonNullWildcardTypeVariableList) {
                for (T value : nonNullTypeVariableList) {
                  value.hashCode();
                }
                for (T value : nonNullWildcardTypeVariableList) {
                  value.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void enhancedForLoopMapEntryPreservesValueTypeNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.LinkedHashMap;
            import java.util.concurrent.CompletableFuture;
            @NullMarked
            class Test {
              static <K, V> void put(
                  LinkedHashMap<K, @Nullable V> map, K key, @Nullable V value) {
                map.put(key, value);
              }
              static <K, V> void setValue(
                  LinkedHashMap<K, @Nullable V> map, @Nullable V value) {
                for (var entry : map.entrySet()) {
                  entry.setValue(value);
                }
              }
              static <K, V> void setNestedValue(
                  LinkedHashMap<K, CompletableFuture<@Nullable V>> map) {
                for (var entry : map.entrySet()) {
                  entry.getValue().obtrudeValue(null);
                }
              }
              static <K, V> void rejectNullableValue(
                  LinkedHashMap<K, V> map, @Nullable V value) {
                for (var entry : map.entrySet()) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'value' where @NonNull is required
                  entry.setValue(value);
                }
              }
              static <K, V> void rejectNestedNullableValue(
                  LinkedHashMap<K, CompletableFuture<V>> map) {
                for (var entry : map.entrySet()) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                  entry.getValue().obtrudeValue(null);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void enhancedForLoopOverArrayPreservesElementTypeNullness() {
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
              void test(Foo<@Nullable String>[] nullableArray, Foo<String>[] nonNullArray) {
                for (var foo : nullableArray) {
                  // BUG: Diagnostic contains: dereferenced expression 'foo.get()' is @Nullable
                  foo.get().hashCode();
                }
                for (var foo : nonNullArray) {
                  foo.get().hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
