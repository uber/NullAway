package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JSpecifyJDKModelsTest extends NullAwayTestsBase {

  @Test
  public void modelsDisabledDoesNotLoadAstubxModel() {
    CompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(List.of("-XepOpt:NullAway:AnnotatedPackages=foo"))
            .addSourceLines(
                "Test.java",
                """
                package foo;
                import javax.naming.directory.Attributes;
                import org.jspecify.annotations.NullMarked;
                @NullMarked
                class Test {
                  void use(Attributes attrs) {
                    // Attributes.get returns @Nullable in the models, but since we don't load
                    // models here, we get no warning
                    attrs.get("key").toString();
                  }
                }
                """);
    compilationTestHelper.doTest();
  }

  @Test
  public void listContainingNullsWithModel() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:AnnotatedPackages=foo")))
        .addSourceLines(
            "Test.java",
            """
            package foo;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void testNullableContents(List<@Nullable String> list) {
                list.add(null);
                // BUG: Diagnostic contains: dereferenced expression 'list.get(0)' is @Nullable
                list.get(0).toString();
              }
              void testNonNullContents(List<String> list) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                list.add(null);
                list.get(0).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void listContainingNullsWithoutModel() {
    makeTestHelperWithArgs(
            // We specifically exclude the JSpecifyJDKModels flag here (so we can't use
            // `withJSpecifyModeArgs`)
            List.of(
                "-XepOpt:NullAway:AnnotatedPackages=foo",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG,
                JSpecifyJavacConfig.HANDLE_WILDCARD_GENERICS_FLAG))
        .addSourceLines(
            "Test.java",
            """
            package foo;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void use(List<@Nullable String> list) {
                list.add(null);
                // no warning, since List.get() is unmarked without the model
                list.get(0).toString();
              }
              void testNonNullContents(List<String> list) {
                // no warning, since List.add() is unmarked without the model
                list.add(null);
                list.get(0).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void ignoredMethodDoesNotInheritNullMarkedLibraryModel() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.lang.reflect.Field;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              Object getStaticField(Class<?> cls) throws ReflectiveOperationException {
                // BUG: Diagnostic contains: returning @Nullable expression
                return cls.getField("someNonNullStaticField").get(null);
              }
            }
            """)
        .doTest();

    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:IgnoreLibraryModelsFor=java.lang.reflect.Field.get")))
        .addSourceLines(
            "Test.java",
            """
            import java.lang.reflect.Field;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              Object getStaticField(Class<?> cls) throws ReflectiveOperationException {
                return cls.getField("someNonNullStaticField").get(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void defaultLibraryModelsClassIsArray() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:AnnotatedPackages=foo")))
        .addSourceLines(
            "Test.java",
            """
            package foo;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            public class Test {
              int classIsArray(Class<?> clazz) {
                if (clazz.isArray()) {
                  return clazz.getComponentType().hashCode();
                } else {
                  // BUG: Diagnostic contains: dereferenced
                  return clazz.getComponentType().hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void biConsumerNullableUpperBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.*;
            @NullMarked
            class Test {
              // test that we can make both type arguments @Nullable
              @Nullable BiConsumer<@Nullable Object, @Nullable Object> b = null;
            }
            """)
        .doTest();
  }

  @Test
  public void nullableMethodReferenceReturnForVoidFunctionalInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static @Nullable String nullable(String value) {
                return null;
              }

              void test(
                  Map<String, String> source,
                  Map<String, String> destination,
                  Collection<String> collection) {
                source.forEach(destination::put);
                collection.forEach(Test::nullable);
                source.forEach((key, value) -> destination.put(key, value));
              }

              interface NullableConsumer {
                void accept(@Nullable String value);
              }

              static void consumeNonNull(String value) {}

              void testParameterChecking() {
                // BUG: Diagnostic contains: parameter value of referenced method is @NonNull, but parameter in functional interface method
                NullableConsumer consumer = Test::consumeNonNull;
              }
            }
            """)
        .doTest();
  }

  @Ignore(
      "We need to merge https://github.com/uber/NullAway/pull/1689 and re-generate JSpecify JDK models before this will work")
  @Test
  public void nullableArrayContents() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.text.*;
            @NullMarked
            class Test {
              MessageFormat getMsgFormat() { throw new RuntimeException(); }
              void test() {
                @Nullable Format[] formats = getMsgFormat().getFormatsByArgumentIndex();
                System.out.println(formats.length);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void streamMapNullableTest() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.*;
            @NullMarked
            class Test {
                static @Nullable String mapToNull(String s) {
                    return null;
                }
                static void test(List<String> list) {
                    list.stream().map(Test::mapToNull).forEach(s -> {
                        // BUG: Diagnostic contains: dereferenced expression 's' is @Nullable
                        s.hashCode();
                    });
                }
            }""")
        .doTest();
  }

  @Test
  public void streamMapNullableMapGetMethodReferenceTest() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import java.util.List;
            import java.util.Map;
            import java.util.Objects;
            @NullMarked
            class Test {
                static void unsafeGetValues(Map<String, String> values, List<String> keys) {
                    keys.stream().map(values::get).forEach(value -> {
                        // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                        value.hashCode();
                    });
                }
                static List<String> getValues(Map<String, String> values, List<String> keys) {
                    return keys.stream()
                        .map(values::get)
                        .filter(Objects::nonNull)
                        .toList();
                }
            }""")
        .doTest();
  }

  @Test
  public void mapComputeOverrideUsesJSpecifyModel() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            abstract class Test implements Map<String, String> {
              static void callSite(
                  Map<String, String> map,
                  BiFunction<String, @Nullable String, @Nullable String> remappingFunction) {
                map.compute("key", remappingFunction);
              }
              @Override
              public @Nullable String compute(
                  String key,
                  BiFunction<
                          ? super String,
                          ? super @Nullable String,
                          ? extends @Nullable String>
                      remappingFunction) {
                return null;
              }
            }
            @NullMarked
            abstract class InvalidTest implements Map<String, String> {
              @Override
              public @Nullable String compute(
                  String key,
                  BiFunction<? super String, ? super String, ? extends String>
                      // BUG: Diagnostic contains: Parameter has type BiFunction<? super String, ? super String, ? extends String>, but overridden method has parameter type BiFunction<? super String, ? super @Nullable String, ? extends @Nullable String>
                      remappingFunction) {
                return null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledMethodTypeVariableBoundUsedForOverride() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.io.IOException;
            import java.net.ServerSocket;
            import java.net.SocketOption;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class ValidOverride extends ServerSocket {
              ValidOverride() throws IOException {}
              @Override
              public <T extends @Nullable Object> ServerSocket setOption(
                  SocketOption<T> name, T value) throws IOException {
                return this;
              }
            }
            @NullMarked
            class InvalidOverride extends ServerSocket {
              InvalidOverride() throws IOException {}
              @Override
              // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
              public <T> ServerSocket setOption(SocketOption<T> name, T value) throws IOException {
                return this;
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
