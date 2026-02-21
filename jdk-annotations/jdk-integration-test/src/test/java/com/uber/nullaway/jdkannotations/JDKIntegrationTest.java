package com.uber.nullaway.jdkannotations;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JDKIntegrationTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @Before
  public void setup() {
    compilationHelper = CompilationTestHelper.newInstance(NullAway.class, getClass());
  }

  @Test
  public void libraryModelNullableReturnsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  static ReturnAnnotation returnAnnotation = new ReturnAnnotation();",
            "  static void test(String value){",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'returnAnnotation.makeUpperCase(\"nullaway\")'",
            "    test(returnAnnotation.makeUpperCase(\"nullaway\"));",
            "  }",
            "  static void testNegative() {",
            "    // no error since nullReturn is annotated with javax.annotation.Nullable,",
            "    // which is not considered when generating stubx files",
            "    test(returnAnnotation.nullReturn());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelNullableReturnsArrayTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  static ReturnAnnotation returnAnnotation = new ReturnAnnotation();",
            "  static void test(Integer[] value){",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'returnAnnotation.generateIntArray(7)'",
            "    test(returnAnnotation.generateIntArray(7));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelWithoutJarInferEnabledTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  static ReturnAnnotation returnAnnotation = new ReturnAnnotation();",
            "  static void test(String value){",
            "  }",
            "  static void testNegative() {",
            "    // Since the JarInferEnabled flag is not set, we don't get an error here",
            "    test(returnAnnotation.makeUpperCase(\"nullaway\"));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelInnerClassNullableReturnsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  static ReturnAnnotation.InnerExample innerExample = new ReturnAnnotation.InnerExample();",
            "  static void test(String value){}",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'innerExample.returnNull()'",
            "    test(innerExample.returnNull());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelInnerClassNullableUpperBoundsTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  static ReturnAnnotation.UpperBoundExample<@Nullable Object> upperBoundExample = new ReturnAnnotation.UpperBoundExample<@Nullable Object>();",
            "  static void test(Object value){",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'upperBoundExample.getNullable()'",
            "    test(upperBoundExample.getNullable());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelNullableUpperBoundsWithoutJarInferTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  // BUG: Diagnostic contains: Generic type parameter cannot be @Nullable",
            "  static ReturnAnnotation.UpperBoundExample<@Nullable Object> upperBoundExample = new ReturnAnnotation.UpperBoundExample<@Nullable Object>();",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelDefaultParameterNullabilityTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "class Test {",
            "  static ParameterAnnotation parameterAnnotation = new ParameterAnnotation();",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    parameterAnnotation.add(5,null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelParameterNullabilityTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "class Test {",
            "  static ParameterAnnotation parameterAnnotation = new ParameterAnnotation();",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    parameterAnnotation.printObjectString(null);",
            "  }",
            "  static void testNegative() {",
            "    parameterAnnotation.getNewObjectIfNull(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableArrayTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "class Test {",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    ParameterAnnotation.takesNonNullArray(null);",
            "  }",
            "  static void testNegative() {",
            "    ParameterAnnotation.takesNullArray(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableGenericArrayTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    ParameterAnnotation.takesNonNullGenericArray(null);",
            "    // BUG: Diagnostic contains: dereferenced expression ReturnAnnotation.returnNullableGenericArray()",
            "    ReturnAnnotation.returnNullableGenericArray().hashCode();",
            "  }",
            "  static void testNegative() {",
            "    ParameterAnnotation.takesNullGenericArray(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableGenericReturnTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "class Test {",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: dereferenced expression ReturnAnnotation.returnNullableGenericContainingNullable()",
            "    ReturnAnnotation.returnNullableGenericContainingNullable().hashCode();",
            "  }",
            "  static void testNegative() {",
            "    ReturnAnnotation.returnNonNullGenericContainingNullable().hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericParameterTest() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "class Test {",
            "  static ParameterAnnotation.Generic<String> ex = new ParameterAnnotation.Generic<>();",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    ex.printObjectString(null);",
            "  }",
            "  static void testNegative() {",
            "    ex.getString(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void varargs() {
    compilationHelper
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:JarInferEnabled=true")))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "class Test {",
            "  static void testNegative() {",
            "    ParameterAnnotation.varargs(null, null);",
            "    Object[] args = null;",
            "    ParameterAnnotation.varargs(args);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryLoadMethodParserReturn() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "import java.util.List;",
            "class Test<T> {",
            "  void testCall() {",
            "    // BUG: Diagnostic contains: dereferenced expression ReturnAnnotation.getList(6, null) is @Nullable",
            "    ReturnAnnotation.getList(6, null).isEmpty();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void loadLibraryModuleMethodTypeParam() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JSpecifyMode=true",
                "-XDaddTypeAnnotationsToSymbol=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "import java.util.List;",
            "class Test {",
            "  void testCall() {",
            "    // BUG: Diagnostic contains: dereferenced expression ParameterAnnotation.nullableTypeParam(1, null) is @Nullable",
            "    ParameterAnnotation.nullableTypeParam(1, null).toString();",
            "    ParameterAnnotation.nullableTypeParam(1, \"string\").toString();",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    ParameterAnnotation.nullableTypeParam(null, \"string\");",
            "    ParameterAnnotation.nonNullTypeParam(1);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    ParameterAnnotation.nonNullTypeParam(null);",
            "    Object x = ParameterAnnotation.twoNullableTypeParam(null, \"string\");",
            "    // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "    x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void loadLibraryModuleMethodReturnTypeNestedAnnotation() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JSpecifyMode=true",
                "-XDaddTypeAnnotationsToSymbol=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jdkannotations.ReturnAnnotation;",
            "import java.util.*;",
            "class Test {",
            "  void testCall() {",
            "    // -- Type argument nullability",
            "    // BUG: Diagnostic contains: incompatible types",
            "    List<String> typeArg1 = ReturnAnnotation.nestedAnnotTypeArg();",
            "    List<@Nullable String> typeArg2 = ReturnAnnotation.nestedAnnotTypeArg();",
            "    // -- Array Element nullability",
            "    // BUG: Diagnostic contains: incompatible types",
            "    String[] arrayElement1 = ReturnAnnotation.nestedAnnotArrayElement();",
            "    // BUG: Diagnostic contains: incompatible types",
            "    String @Nullable [] arrayElement2 = ReturnAnnotation.nestedAnnotArrayElement();",
            "    @Nullable String [] arrayElement3 = ReturnAnnotation.nestedAnnotArrayElement();",
            "    // -- mixed type nullability",
            "    // BUG: Diagnostic contains: incompatible types",
            "    List<Integer>[] mixed1 = ReturnAnnotation.nestedAnnotMixed();",
            "    // BUG: Diagnostic contains: incompatible types",
            "    @Nullable List<Integer>[] mixed2 = ReturnAnnotation.nestedAnnotMixed();",
            "    // BUG: Diagnostic contains: incompatible types",
            "    List<@Nullable Integer>[] mixed3 = ReturnAnnotation.nestedAnnotMixed();",
            "    @Nullable List<@Nullable Integer>[] mixed4 = ReturnAnnotation.nestedAnnotMixed();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void loadLibraryModuleMethodParameterTypeNestedAnnotation() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JSpecifyMode=true",
                "-XDaddTypeAnnotationsToSymbol=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jdkannotations.ParameterAnnotation;",
            "import java.util.*;",
            "class Test {",
            "  void testCall() {",
            "    // -- Type argument",
            "    List<@Nullable String> nullableTypeArg = new ArrayList<>();",
            "    nullableTypeArg.add(\"string\");",
            "    nullableTypeArg.add(null);",
            "    List<String> nonNullTypeArg = new ArrayList<>(2);",
            "    // -- Array type",
            "    @Nullable String[] nullableArray = new String[] {\"populated\", \"value\", null};",
            "    String[] nonNullArray = new String[] {\"populated\", \"value\"};",
            "    // -- Multiple nested annotations",
            "    List<@Nullable Integer> innerList = new ArrayList<>();",
            "    innerList.add(null);",
            "    innerList.add(4);",
            "    @Nullable List<@Nullable Integer>[] nullableMixed = (@Nullable List<@Nullable Integer>[]) new List<?>[3];",
            "    nullableMixed[0] = innerList;",
            "    nullableMixed[0] = null;",
            "    List<@Nullable Integer>[] nonNullArrayMixed = (List<@Nullable Integer>[]) new List<?>[3];",
            "    @Nullable List<Integer>[] nonNullTypeArgMixed = (@Nullable List<Integer>[]) new List<?>[3];",
            "    // === test calls",
            "    ParameterAnnotation.nestedAnnotations(nullableTypeArg, nullableArray, nullableMixed);",
            "    // BUG: Diagnostic contains: incompatible types",
            "    ParameterAnnotation.nestedAnnotations(nonNullTypeArg, nullableArray, nullableMixed);",
            "    ParameterAnnotation.nestedAnnotations(nullableTypeArg, nonNullArray, nullableMixed);",
            "    ParameterAnnotation.nestedAnnotations(nullableTypeArg, nullableArray, nonNullArrayMixed);",
            "    // BUG: Diagnostic contains: incompatible types",
            "    ParameterAnnotation.nestedAnnotations(nullableTypeArg, nullableArray, nonNullTypeArgMixed);",
            "  }",
            "}")
        .doTest();
  }
}
