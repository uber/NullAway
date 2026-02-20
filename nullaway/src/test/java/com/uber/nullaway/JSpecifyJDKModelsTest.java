package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JSpecifyJDKModelsTest extends NullAwayTestsBase {

  @Test
  public void modelsEnabledLoadsAstubxModel() {
    CompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(
                List.of(
                    "-XepOpt:NullAway:AnnotatedPackages=foo",
                    "-XepOpt:NullAway:JSpecifyJDKModels=true"))
            .addSourceLines(
                "Test.java",
                """
                package foo;
                import javax.naming.directory.Attributes;
                import org.jspecify.annotations.NullMarked;
                @NullMarked
                class Test {
                  void use(Attributes attrs) {
                    // BUG: Diagnostic contains: @Nullable
                    attrs.get("key").toString();
                  }
                }
                """);
    compilationTestHelper.doTest();
  }

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
                    attrs.get("key").toString();
                  }
                }
                """);
    compilationTestHelper.doTest();
  }
}
