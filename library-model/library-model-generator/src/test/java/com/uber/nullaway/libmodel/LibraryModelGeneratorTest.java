package com.uber.nullaway.libmodel;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibraryModelGeneratorTest {

  /** For input source code files */
  @Rule public TemporaryFolder inputSourcesFolder = new TemporaryFolder();

  /** For output astubx files */
  @Rule public TemporaryFolder outputFolder = new TemporaryFolder();

  private void runTest(
      String sourceFileName,
      String[] lines,
      ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords,
      ImmutableMap<String, Set<Integer>> expectedNullableUpperBounds)
      throws IOException {
    // write it to a source file in inputSourcesFolder with the right file name
    Files.write(
        inputSourcesFolder.newFile(sourceFileName).toPath(),
        String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    // run the generator
    String astubxOutputPath =
        Paths.get(outputFolder.getRoot().getAbsolutePath(), "output.astubx").toString();
    LibraryModelGenerator.LibraryModelData modelData =
        LibraryModelGenerator.generateAstubxForLibraryModels(
            inputSourcesFolder.getRoot().getAbsolutePath(), astubxOutputPath);
    System.err.println("modelData: " + modelData);
    Assert.assertTrue("astubx file was not created", Files.exists(Paths.get(astubxOutputPath)));
    assertThat(modelData.methodRecords, equalTo(expectedMethodRecords));
    assertThat(modelData.nullableUpperBounds, equalTo(expectedNullableUpperBounds));
  }

  @Test
  public void nullableReturn() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class AnnotationExample {",
          "    @Nullable",
          "    public String makeUpperCase(String inputString) {",
          "        if (inputString == null || inputString.isEmpty()) {",
          "            return null;",
          "        } else {",
          "            return inputString.toUpperCase();",
          "        }",
          "    }",
          "}"
        };
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "AnnotationExample:java.lang.String makeUpperCase(java.lang.String)",
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    runTest("AnnotationExample.java", lines, expectedMethodRecords, ImmutableMap.of());
  }

  @Test
  public void nullableUpperBound() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class NullableUpperBound<T extends @Nullable Object> {",
          "        T nullableObject;",
          "        public T getNullable() {",
          "            return nullableObject;",
          "        }",
          "}"
        };
    ImmutableMap<String, Set<Integer>> expectedNullableUpperBounds =
        ImmutableMap.of("NullableUpperBound", ImmutableSet.of(0));
    runTest("NullableUpperBound.java", lines, ImmutableMap.of(), expectedNullableUpperBounds);
  }
}
