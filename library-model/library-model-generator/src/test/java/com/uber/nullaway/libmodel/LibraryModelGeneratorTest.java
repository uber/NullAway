package com.uber.nullaway.libmodel;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibraryModelGeneratorTest {

  /** For input source code files */
  @Rule public TemporaryFolder inputSourcesFolder = new TemporaryFolder();

  /** For output astubx files */
  @Rule public TemporaryFolder outputFolder = new TemporaryFolder();

  @Test
  public void firstTest() throws IOException {
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
    // write it to a source file in inputSourcesFolder with the right file name
    Files.write(
        inputSourcesFolder.newFile("AnnotationExample.java").toPath(),
        String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    // run the generator
    String astubxOutputPath =
        Paths.get(outputFolder.getRoot().getAbsolutePath(), "output.astubx").toString();
    LibraryModelGenerator.ModelData modelData =
        LibraryModelGenerator.generateAstubxForLibraryModels(
            inputSourcesFolder.getRoot().getAbsolutePath(), astubxOutputPath);
    // check that the output file was created
    Assert.assertTrue("astubx file was not created", Files.exists(Paths.get(astubxOutputPath)));
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "AnnotationExample:String makeUpperCase(String)",
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    assertThat(modelData.methodRecords, equalTo(expectedMethodRecords));
  }
}
