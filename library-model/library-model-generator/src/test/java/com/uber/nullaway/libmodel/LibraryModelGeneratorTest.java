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
      ImmutableMap<String, Set<Integer>> expectedNullableUpperBounds,
      ImmutableSet<String> expectedNullMarkedClasses)
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
    assertThat(modelData.methodRecords, equalTo(expectedMethodRecords));
    assertThat(modelData.nullableUpperBounds, equalTo(expectedNullableUpperBounds));
    assertThat(modelData.nullMarkedClasses, equalTo(expectedNullMarkedClasses));
    Assert.assertTrue(
        "astubx file was not created",
        Files.exists(Paths.get(astubxOutputPath))
            || (expectedMethodRecords.isEmpty()
                && expectedNullableUpperBounds.isEmpty()
                && expectedNullMarkedClasses.isEmpty()));
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
    runTest(
        "AnnotationExample.java",
        lines,
        expectedMethodRecords,
        ImmutableMap.of(),
        ImmutableSet.of("AnnotationExample"));
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
    runTest(
        "NullableUpperBound.java",
        lines,
        ImmutableMap.of(),
        expectedNullableUpperBounds,
        ImmutableSet.of("NullableUpperBound"));
  }

  @Test
  public void nullMarkedClasses() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "@NullMarked",
          "public class NullMarked {",
          "  public static class Nested {}",
          "}",
        };
    ImmutableSet<String> expectedNullMarkedClasses =
        ImmutableSet.of("NullMarked", "NullMarked.Nested");
    runTest(
        "NullMarked.java", lines, ImmutableMap.of(), ImmutableMap.of(), expectedNullMarkedClasses);
  }

  @Test
  public void noNullMarkedClasses() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "public class NotNullMarked {",
          "  public static class Nested {}",
          "}",
        };
    runTest("NotNullMarked.java", lines, ImmutableMap.of(), ImmutableMap.of(), ImmutableSet.of());
  }

  @Test
  public void nullableParameters() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class NullableParameters{",
          " public static Object getNewObjectIfNull(@Nullable Object object) {",
          "   if (object == null) {",
          "     return new Object();",
          "   } else {",
          "     return object;",
          "   }",
          " }",
          "}"
        };
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "NullableParameters:java.lang.Object getNewObjectIfNull(java.lang.Object)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable"))));
    runTest(
        "NullableParameters.java",
        lines,
        expectedMethodRecords,
        ImmutableMap.of(),
        ImmutableSet.of("NullableParameters"));
  }

  @Test
  public void nullableParametersInNullUnmarkedClass() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "public class NullUnmarked{",
          " public static Object getNewObjectIfNull(@Nullable Object object) {",
          "   if (object == null) {",
          "     return new Object();",
          "   } else {",
          "     return object;",
          "   }",
          " }",
          "}"
        };
    runTest("NullUnmarked.java", lines, ImmutableMap.of(), ImmutableMap.of(), ImmutableSet.of());
  }

  @Test
  public void nullableArrayTypeParameter() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class NullableParameters{",
          " public static Object[] getNewObjectArrayIfNull(Object @Nullable [] objectArray) {",
          "   if (Arrays.stream(objectArray).allMatch(e -> e == null)) {",
          "     return new Object[]{new Object(),new Object()};",
          "   } else {",
          "     return objectArray;",
          "   }",
          " }",
          "}"
        };
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "NullableParameters:java.lang.Object[] getNewObjectArrayIfNull(java.lang.Object[])",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable"))));
    runTest(
        "NullableParameters.java",
        lines,
        expectedMethodRecords,
        ImmutableMap.of(),
        ImmutableSet.of("NullableParameters"));
  }

  @Test
  public void genericParameter() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class Generic<T> {",
          " public String getString(@Nullable T t) {",
          "   return t.toString();",
          " }",
          "}"
        };
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "Generic:java.lang.String getString(T)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable"))));
    runTest(
        "Generic.java",
        lines,
        expectedMethodRecords,
        ImmutableMap.of(),
        ImmutableSet.of("Generic"));
  }

  @Test
  public void primitiveTypeReturn() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class PrimitiveType {",
          "    public int multiply(@Nullable Integer num1, @Nullable Integer num2) {",
          "       if(num1!=null && num2!=null){",
          "           return num1*num2;",
          "       }",
          "    }",
          "}"
        };
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "PrimitiveType:int multiply(java.lang.Integer, java.lang.Integer)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(),
                ImmutableMap.of(0, ImmutableSet.of("Nullable"), 1, ImmutableSet.of("Nullable"))));
    runTest(
        "PrimitiveType.java",
        lines,
        expectedMethodRecords,
        ImmutableMap.of(),
        ImmutableSet.of("PrimitiveType"));
  }

  @Test
  public void voidReturn() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class VoidReturn {",
          "    public void printMultiply(@Nullable Integer num1, @Nullable Integer num2) {",
          "       if(num1!=null && num2!=null){",
          "           System.out.println(num1*num2);",
          "       }",
          "    }",
          "}"
        };
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "VoidReturn:void printMultiply(java.lang.Integer, java.lang.Integer)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(),
                ImmutableMap.of(0, ImmutableSet.of("Nullable"), 1, ImmutableSet.of("Nullable"))));
    runTest(
        "VoidReturn.java",
        lines,
        expectedMethodRecords,
        ImmutableMap.of(),
        ImmutableSet.of("VoidReturn"));
  }
}
