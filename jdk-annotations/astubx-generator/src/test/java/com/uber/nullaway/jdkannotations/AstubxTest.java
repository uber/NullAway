package com.uber.nullaway.jdkannotations;

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.bugpatterns.BugChecker;
import com.uber.nullaway.libmodel.MethodAnnotationsRecord;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AstubxTest {
  /** For input source code files */
  @Rule public TemporaryFolder jsonFolder = new TemporaryFolder();

  /** For output astubx files */
  @Rule public TemporaryFolder astubxFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @BugPattern(summary = "Dummy checker to use CompilationTestHelper", severity = WARNING)
  public static class DummyChecker extends BugChecker {
    public DummyChecker() {}
  }

  @Before
  public void setup() {
    String tempPath = jsonFolder.getRoot().getAbsolutePath();
    compilationHelper =
        CompilationTestHelper.newInstance(DummyChecker.class, getClass())
            .setArgs(
                Arrays.asList("-d", tempPath, "-Xplugin:NullnessAnnotationSerializer " + tempPath));
  }

  @Test
  public void nullableReturn() {
    compilationHelper
        .addSourceLines(
            "AnnotationExample.java",
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
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "AnnotationExample:java.lang.String makeUpperCase(java.lang.String)",
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of("AnnotationExample"));
  }

  @Test
  public void nullableUpperBound() {
    compilationHelper
        .addSourceLines(
            "NullableUpperBound.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class NullableUpperBound<T extends @Nullable Object> {",
            "        T nullableObject;",
            "        public T getNullable() {",
            "            return nullableObject;",
            "        }",
            "        public @Nullable T withAnnotation() {",
            "            return nullableObject;",
            "        }",
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "NullableUpperBound:T withAnnotation()",
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    ImmutableMap<String, Set<Integer>> expectedNullableUpperBounds =
        ImmutableMap.of("NullableUpperBound", ImmutableSet.of(0));
    runTest(
        expectedMethodRecords, expectedNullableUpperBounds, ImmutableSet.of("NullableUpperBound"));
  }

  @Test
  public void nestedNullableUpperBound() {
    compilationHelper
        .addSourceLines(
            "ReturnAnnotation.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class ReturnAnnotation {",
            "  public static class UpperBoundExample<T extends @Nullable Object> {",
            "    T nullableObject;",
            "    public T getNullable() {",
            "      return nullableObject;",
            "    }",
            "    public @Nullable T withAnnotation() {",
            "        return nullableObject;",
            "    }",
            "  }",
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "ReturnAnnotation.UpperBoundExample:T withAnnotation()",
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    ImmutableMap<String, Set<Integer>> expectedNullableUpperBounds =
        ImmutableMap.of("ReturnAnnotation.UpperBoundExample", ImmutableSet.of(0));
    runTest(
        expectedMethodRecords, expectedNullableUpperBounds, ImmutableSet.of("ReturnAnnotation"));
  }

  @Test
  public void nullMarkedClasses() {
    compilationHelper
        .addSourceLines(
            "NullMarkedClass.java",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "public class NullMarkedClass {",
            "  public static class Nested {}",
            "}")
        .doTest();
    runTest(ImmutableMap.of(), ImmutableMap.of(), ImmutableSet.of("NullMarkedClass"));
  }

  @Test
  public void noNullMarkedClasses() {
    compilationHelper
        .addSourceLines(
            "NotNullMarked.java",
            "import org.jspecify.annotations.NullMarked;",
            "public class NotNullMarked {",
            "  public static class Nested {}",
            "}")
        .doTest();
    runTest(ImmutableMap.of(), ImmutableMap.of(), ImmutableSet.of());
  }

  @Test
  public void nullableParameters() {
    compilationHelper
        .addSourceLines(
            "NullableParameters.java",
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
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "NullableParameters:java.lang.Object getNewObjectIfNull(java.lang.Object)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable"))));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of("NullableParameters"));
  }

  @Test
  public void nullableParametersInNullUnmarkedClass() {
    compilationHelper
        .addSourceLines(
            "NullUnmarked.java",
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
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "NullUnmarked:java.lang.Object getNewObjectIfNull(java.lang.Object)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable"))));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of());
  }

  @Test
  public void nullableArrayTypeParameter() {
    compilationHelper
        .addSourceLines(
            "NullableParameters.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.Arrays;",
            "@NullMarked",
            "public class NullableParameters{",
            " public static Object[] getNewObjectArrayIfNull(Object @Nullable [] objectArray) {",
            "   if (Arrays.stream(objectArray).allMatch(e -> e == null)) {",
            "     return new Object[]{new Object(),new Object()};",
            "   } else {",
            "     return objectArray;",
            "   }",
            " }",
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "NullableParameters:java.lang.Object[] getNewObjectArrayIfNull(java.lang.Object[])",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable"))));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of("NullableParameters"));
  }

  @Test
  public void genericParameter() {
    compilationHelper
        .addSourceLines(
            "Generic.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Generic<T> {",
            " public String getString(@Nullable T t) {",
            "   return t.toString();",
            " }",
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "Generic:java.lang.String getString(T)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable"))));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of("Generic"));
  }

  @Test
  public void parameterizedTypeArray() {
    compilationHelper
        .addSourceLines(
            "ParameterizedTypeArray.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "@NullMarked",
            "public class ParameterizedTypeArray {",
            "  public List<String>[] identity(List<String>[] listArray) {",
            "    return listArray;",
            "  }",
            "  public List<@Nullable String>[] nullableIdentity(List<@Nullable String>[] listArray) {",
            "    return listArray;",
            "  }",
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "ParameterizedTypeArray:java.util.List<java.lang.String>[] nullableIdentity(java.util.List<java.lang.String>[])",
            MethodAnnotationsRecord.create(ImmutableSet.of(), ImmutableMap.of()));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of("ParameterizedTypeArray"));
  }

  @Test
  public void primitiveTypeReturn() {
    compilationHelper
        .addSourceLines(
            "PrimitiveType.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class PrimitiveType {",
            "    public int multiply(@Nullable Integer num1, @Nullable Integer num2) {",
            "       if(num1!=null && num2!=null){",
            "           return num1*num2;",
            "       }",
            "       return -1;",
            "    }",
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "PrimitiveType:int multiply(java.lang.Integer, java.lang.Integer)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(),
                ImmutableMap.of(0, ImmutableSet.of("Nullable"), 1, ImmutableSet.of("Nullable"))));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of("PrimitiveType"));
  }

  @Test
  public void voidReturn() {
    compilationHelper
        .addSourceLines(
            "VoidReturn.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class VoidReturn {",
            "    public void printMultiply(@Nullable Integer num1, @Nullable Integer num2) {",
            "       if(num1!=null && num2!=null){",
            "           System.out.println(num1*num2);",
            "       }",
            "    }",
            "}")
        .doTest();
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "VoidReturn:void printMultiply(java.lang.Integer, java.lang.Integer)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(),
                ImmutableMap.of(0, ImmutableSet.of("Nullable"), 1, ImmutableSet.of("Nullable"))));
    runTest(expectedMethodRecords, ImmutableMap.of(), ImmutableSet.of("VoidReturn"));
  }

  private void runTest(
      ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords,
      ImmutableMap<String, Set<Integer>> expectedNullableUpperBounds,
      ImmutableSet<String> expectedNullMarkedClasses) {
    String astubxOutputDirPath = astubxFolder.getRoot().getAbsolutePath();
    // get astubx data
    AstubxGenerator.AstubxData astubxData =
        AstubxGenerator.getAstubxData(jsonFolder.getRoot().getAbsolutePath());

    assertThat(astubxData.methodRecords(), equalTo(expectedMethodRecords));
    assertThat(astubxData.nullableUpperBounds(), equalTo(expectedNullableUpperBounds));
    assertThat(astubxData.nullMarkedClasses(), equalTo(expectedNullMarkedClasses));

    // write astubx file
    AstubxGenerator.writeToAstubxFile(astubxOutputDirPath, astubxData);
    Assert.assertTrue(
        "astubx file was not created",
        Files.exists(Paths.get(astubxOutputDirPath, "output.astubx"))
            || (expectedMethodRecords.isEmpty()
                && expectedNullableUpperBounds.isEmpty()
                && expectedNullMarkedClasses.isEmpty()));
  }
}
