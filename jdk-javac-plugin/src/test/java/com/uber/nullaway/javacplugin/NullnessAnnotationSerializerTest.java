package com.uber.nullaway.javacplugin;

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.ClassInfo;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.MethodInfo;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.TypeParamInfo;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo.Annotation;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo.TypePathEntry;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullnessAnnotationSerializerTest {

  private CompilationTestHelper compilationTestHelper;

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * A dummy checker to allow us to use {@link CompilationTestHelper} to compile Java code for
   * testing, as it requires a {@link BugChecker} to run.
   */
  @BugPattern(summary = "Dummy checker to use CompilationTestHelper", severity = WARNING)
  public static class DummyChecker extends BugChecker {
    public DummyChecker() {}
  }

  @Before
  public void setup() {
    String tempPath = temporaryFolder.getRoot().getAbsolutePath();
    compilationTestHelper =
        CompilationTestHelper.newInstance(DummyChecker.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    tempPath,
                    "--module-path",
                    System.getProperty("test.module.path"),
                    "-Xplugin:NullnessAnnotationSerializer " + tempPath));
  }

  @Test
  public void nullMarkedClassNoModule() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "class Foo {}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(new ClassInfo("Foo", "Foo", true, false, List.of(), List.of()))));
  }

  @Test
  public void nullMarkedClassWithModule() {
    compilationTestHelper
        .addSourceLines(
            "module-info.java",
            "module com.example {",
            "    requires java.base;",
            "    requires org.jspecify;",
            "}")
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            "  public @Nullable String NullableReturn(@Nullable Integer i) { return null;}",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "com.example",
                List.of(
                    new ClassInfo(
                        "Foo",
                        "Foo",
                        true,
                        false,
                        List.of(),
                        List.of(
                            new MethodInfo(
                                "java.lang.@org.jspecify.annotations.Nullable String",
                                "NullableReturn(java.lang.@org.jspecify.annotations.Nullable Integer)",
                                false,
                                false,
                                List.of(),
                                new HashMap<>()))))));
  }

  @Test
  public void privateMethodsExcluded() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            "  void publicMethod(@Nullable String s) {}",
            "  private void privateMethod(@Nullable String s) {}",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(
                    new ClassInfo(
                        "Foo",
                        "Foo",
                        true,
                        false,
                        List.of(),
                        List.of(
                            new MethodInfo(
                                "void",
                                "publicMethod(java.lang.@org.jspecify.annotations.Nullable String)",
                                false,
                                false,
                                List.of(),
                                new HashMap<>()))))));
  }

  @Test
  public void innerAndAnonymousClasses() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Foo {",
            "  @NullUnmarked",
            "  class Inner { }",
            "  private class PrivateInner<T extends @Nullable Object> { }",
            "  void method() {",
            "    Runnable r = new Runnable() { public void run() {} };",
            "  }",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(
                    new ClassInfo("Inner", "Foo.Inner", false, true, List.of(), List.of()),
                    new ClassInfo("Foo", "Foo", true, false, List.of(), List.of()))));
  }

  @Test
  public void typeParameters() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "public class Foo<T extends @Nullable Object> {",
            "  public <U extends @Nullable Object> U make(U u) { throw new RuntimeException(); }",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(
                    new ClassInfo(
                        "Foo",
                        "Foo<T>",
                        true,
                        false,
                        List.of(new TypeParamInfo("T", List.of("@Nullable Object"))),
                        List.of(
                            new MethodInfo(
                                "U",
                                "<U>make(U)",
                                false,
                                false,
                                List.of(new TypeParamInfo("U", List.of("@Nullable Object"))),
                                new HashMap<>()))))));
  }

  @Test
  public void skipNonAnnotatedClasses() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Foo {",
            "  class NoAnnotation { void noAnnotMethod() {} }",
            "  class Inner {",
            "    void annotatedMethod(@Nullable String s) {}",
            "  }",
            "  class AnnotatedGeneric<T extends @Nullable Object> {}",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(
                    new ClassInfo(
                        "Inner",
                        "Foo.Inner",
                        false,
                        false,
                        List.of(),
                        List.of(
                            new MethodInfo(
                                "void",
                                "annotatedMethod(java.lang.@org.jspecify.annotations.Nullable String)",
                                false,
                                false,
                                List.of(),
                                new HashMap<>()))),
                    new ClassInfo(
                        "AnnotatedGeneric",
                        "Foo.AnnotatedGeneric<T>",
                        false,
                        false,
                        List.of(new TypeParamInfo("T", List.of("@Nullable Object"))),
                        List.of()),
                    new ClassInfo("Foo", "Foo", true, false, List.of(), List.of()))));
  }

  @Test
  public void skipNonAnnotatedMethods() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Foo {",
            "  void noAnnotation() {}",
            "  <T extends @Nullable Object> void annotatedTypeArgument() {}",
            "  @Nullable String annotatedReturnType() { throw new RuntimeException(); }",
            "  void annotatedParameter(@Nullable String s) {}",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(
                    new ClassInfo(
                        "Foo",
                        "Foo",
                        true,
                        false,
                        List.of(),
                        List.of(
                            new MethodInfo(
                                "void",
                                "<T>annotatedTypeArgument()",
                                false,
                                false,
                                List.of(new TypeParamInfo("T", List.of("@Nullable Object"))),
                                new HashMap<>()),
                            new MethodInfo(
                                "java.lang.@org.jspecify.annotations.Nullable String",
                                "annotatedReturnType()",
                                false,
                                false,
                                List.of(),
                                new HashMap<>()),
                            new MethodInfo(
                                "void",
                                "annotatedParameter(java.lang.@org.jspecify.annotations.Nullable String)",
                                false,
                                false,
                                List.of(),
                                new HashMap<>()))))));
  }

  @Test
  public void annotationDeepScan() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.*;",
            "import java.util.List;",
            "@NullMarked",
            "public class Foo {",
            "  interface Bar {}",
            "  public void arrayType(@Nullable String[] arr) {}",
            "  public void wildcard(List<? super @Nullable Integer> list) {}",
            "  public <U extends @Nullable Object> void typeVariable(U u) {}",
            "  public <T extends @Nullable String & Bar> void intersection(T t) {}",
            "  public <T> void nullableOnTypeVar(List<@Nullable T> list) {}",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(
                    new ClassInfo(
                        "Foo",
                        "Foo",
                        true,
                        false,
                        List.of(),
                        List.of(
                            new MethodInfo(
                                "void",
                                "arrayType(java.lang.@org.jspecify.annotations.Nullable String[])",
                                false,
                                false,
                                List.of(),
                                new HashMap<>()),
                            new MethodInfo(
                                "void",
                                "wildcard(java.util.List<? super java.lang.@org.jspecify.annotations.Nullable Integer>)",
                                false,
                                false,
                                List.of(),
                                new HashMap<>(
                                    Map.of(
                                        0,
                                        new HashSet<>(
                                            Set.of(
                                                new NestedAnnotationInfo(
                                                    Annotation.NULLABLE,
                                                    ImmutableList.of(
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.TYPE_ARGUMENT, 0),
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.WILDCARD_BOUND,
                                                            1)))))))),
                            new MethodInfo(
                                "void",
                                "<U>typeVariable(U)",
                                false,
                                false,
                                List.of(new TypeParamInfo("U", List.of("@Nullable Object"))),
                                new HashMap<>()),
                            new MethodInfo(
                                "void",
                                "<T>intersection(T)",
                                false,
                                false,
                                List.of(new TypeParamInfo("T", List.of("@Nullable String", "Bar"))),
                                new HashMap<>()),
                            new MethodInfo(
                                "void",
                                "<T>nullableOnTypeVar(java.util.List<@org.jspecify.annotations.Nullable T>)",
                                false,
                                false,
                                List.of(new TypeParamInfo("T", List.of())),
                                new HashMap<>(
                                    Map.of(
                                        0,
                                        new HashSet<>(
                                            Set.of(
                                                new NestedAnnotationInfo(
                                                    Annotation.NULLABLE,
                                                    ImmutableList.of(
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.TYPE_ARGUMENT,
                                                            0)))))))))))));
  }

  @Test
  public void nestedAnnotationsForMethods() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.*;",
            "import java.util.*;",
            "@NullMarked",
            "public abstract class Foo {",
            "  public abstract <E extends @Nullable Object> Collection<E> checkedCollection(Collection<E> c, Class<@NonNull E> type);",
            "  public abstract List<@Nullable String> nestedReturnType();",
            "  public abstract void nestedParameterType(String @Nullable [] arr, List<@NonNull String>[] s);",
            "  public abstract List<? extends @Nullable String> wildcardUpperBound();",
            "}")
        .doTest();
    Map<String, List<ClassInfo>> moduleClasses = getParsedJSON();
    assertThat(moduleClasses)
        .containsExactlyEntriesOf(
            Map.of(
                "unnamed",
                List.of(
                    new ClassInfo(
                        "Foo",
                        "Foo",
                        true,
                        false,
                        List.of(),
                        List.of(
                            new MethodInfo(
                                "java.util.Collection<E>",
                                "<E>checkedCollection(java.util.Collection<E>,java.lang.Class<@org.jspecify.annotations.NonNull E>)",
                                false,
                                false,
                                List.of(new TypeParamInfo("E", List.of("@Nullable Object"))),
                                new HashMap<>(
                                    Map.of(
                                        1,
                                        new HashSet<>(
                                            Set.of(
                                                new NestedAnnotationInfo(
                                                    Annotation.NONNULL,
                                                    ImmutableList.of(
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.TYPE_ARGUMENT,
                                                            0)))))))),
                            new MethodInfo(
                                "java.util.List<java.lang.@org.jspecify.annotations.Nullable String>",
                                "nestedReturnType()",
                                false,
                                false,
                                List.of(),
                                new HashMap<>(
                                    Map.of(
                                        -1,
                                        new HashSet<>(
                                            Set.of(
                                                new NestedAnnotationInfo(
                                                    Annotation.NULLABLE,
                                                    ImmutableList.of(
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.TYPE_ARGUMENT,
                                                            0)))))))),
                            new MethodInfo(
                                "void",
                                "nestedParameterType(java.lang.String @org.jspecify.annotations.Nullable [],java.util.List<java.lang.@org.jspecify.annotations.NonNull String>[])",
                                false,
                                false,
                                List.of(),
                                new HashMap<>(
                                    Map.of(
                                        0,
                                        new HashSet<>(
                                            Set.of(
                                                new NestedAnnotationInfo(
                                                    Annotation.NULLABLE,
                                                    ImmutableList.of(
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.ARRAY_ELEMENT,
                                                            -1))))),
                                        1,
                                        new HashSet<>(
                                            Set.of(
                                                new NestedAnnotationInfo(
                                                    Annotation.NONNULL,
                                                    ImmutableList.of(
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.ARRAY_ELEMENT, -1),
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.TYPE_ARGUMENT,
                                                            0)))))))),
                            new MethodInfo(
                                "java.util.List<? extends java.lang.@org.jspecify.annotations.Nullable String>",
                                "wildcardUpperBound()",
                                false,
                                false,
                                List.of(),
                                new HashMap<>(
                                    Map.of(
                                        -1,
                                        new HashSet<>(
                                            Set.of(
                                                new NestedAnnotationInfo(
                                                    Annotation.NULLABLE,
                                                    ImmutableList.of(
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.TYPE_ARGUMENT, 0),
                                                        new TypePathEntry(
                                                            TypePathEntry.Kind.WILDCARD_BOUND,
                                                            0)))))))))))));
  }

  private Map<String, List<ClassInfo>> getParsedJSON() {
    String tempPath = temporaryFolder.getRoot().getAbsolutePath();

    // 1. Define the Adapter for ImmutableList
    // This works for ImmutableList<String>, ImmutableList<Integer>, etc.
    JsonDeserializer<ImmutableList<?>> immutableListAdapter =
        (json, typeOfT, context) -> {
          ImmutableList.Builder<Object> builder = ImmutableList.builder();

          // Ensure the JSON is actually an array
          if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();

            // Critical Step: Find out what type is inside the list!
            // e.g., if typeOfT is ImmutableList<String>, contentClass will be String.class
            Type contentClass = ((ParameterizedType) typeOfT).getActualTypeArguments()[0];

            for (JsonElement element : array) {
              // Deserialize the inner element using Gson's context
              Object value = context.deserialize(element, contentClass);
              builder.add(value);
            }
          }
          return builder.build();
        };

    // list all json files in the  tempPath
    try (Stream<Path> stream = Files.list(Paths.get(tempPath))) {
      return stream
          .filter(path -> path.toString().endsWith(".json"))
          .findFirst()
          .<Map<String, List<ClassInfo>>>map(
              path -> {
                try {
                  return new GsonBuilder()
                      .registerTypeAdapter(ImmutableList.class, immutableListAdapter)
                      .create()
                      .fromJson(
                          new String(Files.readAllBytes(path), StandardCharsets.UTF_8),
                          new TypeToken<Map<String, List<ClassInfo>>>() {}.getType());
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              })
          .orElseThrow();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
