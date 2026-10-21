package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

/** Tests for reasoning about maps with {@code @Nullable} values in JSpecify mode. */
public class MapNullableValuesTests extends NullAwayTestsBase {

  @Test
  public void getOrDefaultCallsAndReferencesWithWildcardValues() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:HandleWildcardGenerics=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              interface Lookup {
                Object apply(Map<String, ? super String> map, String key, String defaultValue);
              }

              void call(Map<String, ? super String> map) {
                // BUG: Diagnostic contains: dereferenced expression
                map.getOrDefault("key", "fallback").hashCode();
              }

              BiFunction<String, String, Object> bound(Map<String, ? super String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }

              Lookup unbound() {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return Map::getOrDefault;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultCallsAndReferencesWithWildcardHandlingDisabled() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:HandleWildcardGenerics=false")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              interface Lookup {
                Object apply(Map<String, ? super String> map, String key, String defaultValue);
              }

              void call(Map<String, ? super String> map) {
                map.getOrDefault("key", "fallback").hashCode();
              }

              BiFunction<String, String, Object> bound(Map<String, ? super String> map) {
                return map::getOrDefault;
              }

              Lookup unbound() {
                return Map::getOrDefault;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultRuleOverridesConditionalLibraryModel() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Lookup {
                String apply(Map<String, @Nullable String> map, String key, String defaultValue);
              }

              void call(Map<String, @Nullable String> map) {
                // The test provider models a non-null default as forcing a non-null result.
                // A present key can still have a null value, so the core rule must win.
                // BUG: Diagnostic contains: dereferenced expression
                map.getOrDefault("key", "fallback").length();
              }

              BiFunction<String, String, String> bound(Map<String, @Nullable String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }

              Lookup unbound() {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return Map::getOrDefault;
              }

              BiFunction<String, String, String> nonNullValues(Map<String, String> map) {
                return map::getOrDefault;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultOnRawMapSubtype() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.HashMap;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              @SuppressWarnings({"rawtypes", "serial"})
              static class RawSubtype extends HashMap {}

              BiFunction<Object, Object, Object> reference(RawSubtype map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }

              Object call(RawSubtype map, Object key) {
                return map.getOrDefault(key, "fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapGetOrDefaultWithNullableValues() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.HashMap;
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void test(Map<String, @Nullable String> map,
                  HashMap<String, @Nullable String> hashMap, @Nullable String defaultValue) {
                // A present key can map to null even with a non-null default.
                // BUG: Diagnostic contains: dereferenced expression 'map.getOrDefault("key", "fallback")' is @Nullable
                map.getOrDefault("key", "fallback").length();
                // BUG: Diagnostic contains: dereferenced expression 'hashMap.getOrDefault("key", "fallback")' is @Nullable
                hashMap.getOrDefault("key", "fallback").length();
                // BUG: Diagnostic contains: dereferenced expression 'map.getOrDefault("key", defaultValue)' is @Nullable
                map.getOrDefault("key", defaultValue).length();
                // BUG: Diagnostic contains: dereferenced expression 'map.getOrDefault("key", null)' is @Nullable
                map.getOrDefault("key", null).length();
              }
              static String nonNull(Map<String, String> map) {
                return map.getOrDefault("key", "fallback");
              }
              static String invalid(Map<String, @Nullable String> map) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return map.getOrDefault("key", "fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultWithNonNullValuesAndNullableDefault() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void test(Map<String, String> map, @Nullable String defaultValue) {
                map.getOrDefault("key", "fallback").length();
                // BUG: Diagnostic contains: dereferenced expression 'map.getOrDefault("key", defaultValue)' is @Nullable
                map.getOrDefault("key", defaultValue).length();
                // BUG: Diagnostic contains: dereferenced expression 'map.getOrDefault("key", null)' is @Nullable
                map.getOrDefault("key", null).length();
                if (defaultValue != null) {
                  map.getOrDefault("key", defaultValue).length();
                }
              }
              void checkedResult(Map<String, @Nullable String> map) {
                if (map.getOrDefault("key", "fallback") != null) {
                  map.getOrDefault("key", "fallback").length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultWithInheritedAndInferredNullableValues() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.HashMap;
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class NullableValueMap extends HashMap<String, @Nullable String> {
                void test() {
                  // BUG: Diagnostic contains: dereferenced expression 'getOrDefault("key", "fallback")' is @Nullable
                  getOrDefault("key", "fallback").length();
                }
              }
              static class GenericValueMap<V extends @Nullable Object, K> extends HashMap<K, V> {}
              static <T> T identity(T value) { return value; }
              void test(GenericValueMap<@Nullable String, String> map) {
                // BUG: Diagnostic contains: dereferenced expression 'map.getOrDefault("key", "fallback")' is @Nullable
                map.getOrDefault("key", "fallback").length();
                // BUG: Diagnostic contains: dereferenced expression 'identity(map).getOrDefault("key", "fallback")' is @Nullable
                identity(map).getOrDefault("key", "fallback").length();
              }
              void wildcard(Map<String, ? super String> map) {
                // BUG: Diagnostic contains: dereferenced expression 'map.getOrDefault("key", "fallback")' is @Nullable
                map.getOrDefault("key", "fallback").hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void containsKeyWithNullableValues() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.HashMap;
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void nonNullValues(Map<String, String> m, String k) {
                if (m.containsKey(k)) {
                  m.get(k).hashCode();
                }
              }
              void nullableValues(Map<String, @Nullable String> m, String k) {
                if (m.containsKey(k)) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(k)' is @Nullable
                  m.get(k).hashCode();
                }
              }
              void nullableValuesSubtype(HashMap<String, @Nullable String> m, String k) {
                if (m.containsKey(k)) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(k)' is @Nullable
                  m.get(k).hashCode();
                }
              }
              void nullableWildcardValues(Map<String, ? extends @Nullable Object> m, String k) {
                if (m.containsKey(k)) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(k)' is @Nullable
                  m.get(k).hashCode();
                }
              }
              void nullableValuesWithCheck(Map<String, @Nullable String> m, String k) {
                if (m.containsKey(k) && m.get(k) != null) {
                  m.get(k).hashCode();
                }
              }
              void rawValues(Map m, Object k) {
                if (m.containsKey(k)) {
                  m.get(k).hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void containsKeyWithNullableValuesInSubclass() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.HashMap;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class NullableValueMap extends HashMap<String, @Nullable String> {
                void test(String k) {
                  if (containsKey(k)) {
                    // BUG: Diagnostic contains: dereferenced expression 'get(k)' is @Nullable
                    get(k).hashCode();
                  }
                }
              }
              static class GenericValueMap<T extends @Nullable Object>
                  extends HashMap<String, T> {}
              void test(NullableValueMap m, String k) {
                if (m.containsKey(k)) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(k)' is @Nullable
                  m.get(k).hashCode();
                }
              }
              void testGeneric(GenericValueMap<@Nullable String> m, String k) {
                if (m.containsKey(k)) {
                  // BUG: Diagnostic contains: dereferenced expression 'm.get(k)' is @Nullable
                  m.get(k).hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void containsKeyNotJSpecifyMode() {
    // Outside JSpecify mode, preserve the legacy containsKey() refinement: get() is treated as
    // non-null even if the map value type has a JSpecify @Nullable annotation.
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Map;
            import org.jspecify.annotations.Nullable;
            class Test {
              void test(Map<String, @Nullable String> m, String k) {
                if (m.containsKey(k)) {
                  m.get(k).hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
