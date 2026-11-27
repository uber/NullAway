package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A chain of {@code addSourceLines} calls whose arguments are all string literals becomes one seed,
 * and a chain with any other argument becomes none.
 */
public class SeedExtractorTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final String Q = "\"";

  /** Returns the seeds of a test file whose {@code test} method holds {@code statement}. */
  private ImmutableList<Seed> seedsOf(String statement) throws Exception {
    Path root = temporaryFolder.newFolder().toPath();
    Files.writeString(
        root.resolve("SomeTest.java"),
        "class SomeTest {\n  void test() {\n    " + statement + "\n  }\n}\n");
    return SeedExtractor.extract(root);
  }

  @Test
  public void aLiteralChainBecomesOneSeedWithOneSourcePerCall() throws Exception {
    ImmutableList<Seed> seeds =
        seedsOf(
            "helper.addSourceLines("
                + Q
                + "A.java"
                + Q
                + ", "
                + Q
                + "class A {"
                + Q
                + ", "
                + Q
                + "}"
                + Q
                + ").addSourceLines("
                + Q
                + "B.java"
                + Q
                + ", "
                + Q
                + "class B {}"
                + Q
                + ").doTest();");
    assertThat(seeds)
        .containsExactly(
            new Seed(
                "SomeTest.java#test",
                ImmutableList.of(
                    new Source("A.java", "class A {\n}"), new Source("B.java", "class B {}"))));
  }

  @Test
  public void aChainWithANonLiteralArgumentBecomesNoSeed() throws Exception {
    ImmutableList<Seed> seeds =
        seedsOf(
            "helper.addSourceLines("
                + Q
                + "A.java"
                + Q
                + ", "
                + Q
                + "class A {}"
                + Q
                + ").addSourceLines("
                + Q
                + "B.java"
                + Q
                + ", SOURCE).doTest();");
    assertThat(seeds).isEmpty();
  }

  @Test
  public void aSecondChainInOneMethodIsNumberedAndNeitherNameHoldsALine() throws Exception {
    ImmutableList<Seed> seeds =
        seedsOf(
            "helper.addSourceLines("
                + Q
                + "A.java"
                + Q
                + ", "
                + Q
                + "class A {}"
                + Q
                + ").doTest();\n"
                + "    helper.addSourceLines("
                + Q
                + "B.java"
                + Q
                + ", "
                + Q
                + "class B {}"
                + Q
                + ").doTest();");
    assertThat(seeds.stream().map(Seed::name).toList())
        .containsExactly("SomeTest.java#test", "SomeTest.java#test[2]")
        .inOrder();
  }
}
