package com.uber.nullaway.jdkannotations;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.uber.nullaway.libmodel.MethodAnnotationsRecord;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AstubxTest {
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
    File sourceFile = inputSourcesFolder.newFile(sourceFileName);
    Files.write(sourceFile.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

    String outputJsonPath = "build/generated/json-output";
    String outputAstubxPath = "build/generated/astubx-output";
    new File(outputJsonPath).mkdirs();
    new File(outputAstubxPath).mkdirs();

    File jsonOutputDir = outputFolder.newFolder("build/generated/json-output");
    File classOutputDir = outputFolder.newFolder("build/generated/class-output");

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new AssertionError("Compiler not found. Please run with a JDK.");
    }

    // Find necessary JARs from the test's own runtime classpath
    String runtimeClasspath = System.getProperty("java.class.path");
    String jspecifyJarPath =
        Arrays.stream(runtimeClasspath.split(File.pathSeparator))
            .filter(path -> path.contains("jspecify"))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("Could not find jspecify JAR on the runtime classpath."));

    String pluginJarPath =
        Paths.get(
                System.getProperty("user.dir"),
                "..",
                "..",
                "jdk-javac-plugin",
                "build",
                "libs",
                "jdk-javac-plugin-all.jar")
            .toString();
    if (!Files.exists(Paths.get(pluginJarPath))) {
      throw new AssertionError(
          "Plugin JAR not found at: " + pluginJarPath + ". Please build it first.");
    }

    // Build the classpath needed to COMPILE the source code
    String compilationClasspath = pluginJarPath + File.pathSeparator + jspecifyJarPath;

    // Set compiler options, replicating the '-Xplugin' argument
    List<String> options = new ArrayList<>();
    options.addAll(Arrays.asList("-processorpath", pluginJarPath));
    options.addAll(Arrays.asList("-classpath", compilationClasspath));
    options.addAll(Arrays.asList("-d", classOutputDir.getAbsolutePath()));
    options.add("-Xplugin:NullnessAnnotationSerializer " + jsonOutputDir.getAbsolutePath());

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> compilationUnits =
        fileManager.getJavaFileObjects(sourceFile);

    boolean success =
        compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits).call();
    fileManager.close();

    boolean hasErrors = false;
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
      // Only print errors and warnings, as notes can be verbose
      if (diagnostic.getKind() == Diagnostic.Kind.ERROR
          || diagnostic.getKind() == Diagnostic.Kind.WARNING) {
        hasErrors = true;
        System.err.format(
            "Compiler Diagnostic: %s on line %d in %s%n",
            diagnostic.getMessage(null),
            diagnostic.getLineNumber(),
            diagnostic.getSource() != null ? diagnostic.getSource().getName() : "Unknown file");
      }
    }

    // Fail the test if the plugin reported errors, even if 'success' is true
    if (hasErrors) {
      Assert.fail("Plugin reported errors. See diagnostics above.");
    }

    if (!success) {
      Assert.fail("Java compilation with plugin failed.");
    }

    // run the generator
    String astubxOutputDirPath = Paths.get(outputFolder.getRoot().getAbsolutePath()).toString();
    AstubxGeneratorCLI.LibraryModelData modelData =
        AstubxGeneratorCLI.generateAstubx(jsonOutputDir.getAbsolutePath(), astubxOutputDirPath);
    System.err.println("modelData: " + modelData.toString());

    assertThat(modelData.methodRecords, equalTo(expectedMethodRecords));
    assertThat(modelData.nullableUpperBounds, equalTo(expectedNullableUpperBounds));
    assertThat(modelData.nullMarkedClasses, equalTo(expectedNullMarkedClasses));
    Assert.assertTrue(
        "astubx file was not created",
        Files.exists(Paths.get(Paths.get(astubxOutputDirPath, "output.astubx").toString()))
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
  public void nestedNullableUpperBound() throws IOException {
    String[] lines =
        new String[] {
          //          "package com.uber.nullaway.jdkannotations;",
          "import org.jspecify.annotations.NullMarked;",
          "import org.jspecify.annotations.Nullable;",
          "@NullMarked",
          "public class ReturnAnnotation {",
          "  public static class UpperBoundExample<T extends @Nullable Object> {",
          "    T nullableObject;",
          "    public T getNullable() {",
          "      return nullableObject;",
          "    }",
          "  }",
          "}"
        };
    ImmutableMap<String, MethodAnnotationsRecord> expectedMethodRecords =
        ImmutableMap.of(
            "ReturnAnnotation.UpperBoundExample:T getNullable()",
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    ImmutableMap<String, Set<Integer>> expectedNullableUpperBounds =
        ImmutableMap.of("ReturnAnnotation.UpperBoundExample", ImmutableSet.of(0));
    runTest(
        "ReturnAnnotation.java",
        lines,
        expectedMethodRecords,
        expectedNullableUpperBounds,
        ImmutableSet.of("ReturnAnnotation"));
  }

  @Test
  public void nullMarkedClasses() throws IOException {
    String[] lines =
        new String[] {
          "import org.jspecify.annotations.NullMarked;",
          "@NullMarked",
          "public class NullMarkedClass {",
          "  public static class Nested {}",
          "}",
        };
    ImmutableSet<String> expectedNullMarkedClasses = ImmutableSet.of("NullMarkedClass");
    runTest(
        "NullMarkedClass.java",
        lines,
        ImmutableMap.of(),
        ImmutableMap.of(),
        expectedNullMarkedClasses);
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
    runTest(
        "NullUnmarked.java",
        lines,
        ImmutableMap.of(
            "NullUnmarked:java.lang.Object getNewObjectIfNull(java.lang.Object)",
            MethodAnnotationsRecord.create(
                ImmutableSet.of(), ImmutableMap.of(0, ImmutableSet.of("Nullable")))),
        ImmutableMap.of(),
        ImmutableSet.of());
  }

  @Test
  public void nullableArrayTypeParameter() throws IOException {
    String[] lines =
        new String[] {
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
          "       return -1;",
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
