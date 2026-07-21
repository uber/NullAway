package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
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
}
