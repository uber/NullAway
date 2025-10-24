package com.uber.nullaway.javacplugin;

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.reflect.TypeToken;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.gson.GsonBuilder;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.ClassInfo;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.MethodInfo;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.TypeParamInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
                                List.of()))))));
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
                                List.of()))))));
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
            "  private class PrivateInner<String extends @Nullable Object> { }",
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
                                List.of(new TypeParamInfo("U", List.of("@Nullable Object")))))))));
  }

  @Test
  public void skipNonAnnotatedClasses() {
    compilationTestHelper
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Foo {",
            "  class NoAnnotation {}",
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
                                List.of()))),
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
                                List.of(new TypeParamInfo("T", List.of("@Nullable Object")))),
                            new MethodInfo(
                                "java.lang.@org.jspecify.annotations.Nullable String",
                                "annotatedReturnType()",
                                false,
                                false,
                                List.of()),
                            new MethodInfo(
                                "void",
                                "annotatedParameter(java.lang.@org.jspecify.annotations.Nullable String)",
                                false,
                                false,
                                List.of()))))));
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
            "  public void wildCard(List<? super @Nullable Integer> list) {}",
            "  public <U extends @Nullable Object> void typeVariable(U u) {}",
            "  public <T extends @Nullable String & Bar> void intersection(T t) {}",
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
                                List.of()),
                            new MethodInfo(
                                "void",
                                "wildCard(java.util.List<? super java.lang.@org.jspecify.annotations.Nullable Integer>)",
                                false,
                                false,
                                List.of()),
                            new MethodInfo(
                                "void",
                                "<U>typeVariable(U)",
                                false,
                                false,
                                List.of(new TypeParamInfo("U", List.of("@Nullable Object")))),
                            new MethodInfo(
                                "void",
                                "<T>intersection(T)",
                                false,
                                false,
                                List.of(
                                    new TypeParamInfo(
                                        "T", List.of("@Nullable String", "Bar")))))))));
  }

  private Map<String, List<ClassInfo>> getParsedJSON() {
    String tempPath = temporaryFolder.getRoot().getAbsolutePath();
    // list all json files in the  tempPath
    try (Stream<Path> stream = Files.list(Paths.get(tempPath))) {
      return stream
          .filter(path -> path.toString().endsWith(".json"))
          .findFirst()
          .<Map<String, List<ClassInfo>>>map(
              path -> {
                try {
                  return new GsonBuilder()
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
