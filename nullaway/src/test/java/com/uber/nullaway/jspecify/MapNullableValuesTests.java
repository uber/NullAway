package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import com.uber.nullaway.tools.DualModeCompilationTestHelper;
import java.util.Arrays;
import org.junit.Test;

/** Tests for reasoning about maps with {@code @Nullable} values in JSpecify mode. */
public class MapNullableValuesTests extends NullAwayTestsBase {

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

  private DualModeCompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
