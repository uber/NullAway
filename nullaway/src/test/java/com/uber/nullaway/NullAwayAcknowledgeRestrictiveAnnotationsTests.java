package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayAcknowledgeRestrictiveAnnotationsTests extends NullAwayTestsBase {

  @Test
  public void generatedAsUnannotatedPlusRestrictive() {
    String generatedAnnot =
        (Double.parseDouble(System.getProperty("java.specification.version")) >= 11)
            ? "@javax.annotation.processing.Generated"
            : "@javax.annotation.Generated";
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            generatedAnnot + "(\"foo\")",
            "public class Generated {",
            "  @javax.annotation.Nullable",
            "  public Object retNull() {",
            "    return null;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  void foo() { (new Generated()).retNull().toString(); }",
            "}")
        .doTest();
  }

  @Test
  public void defaultPermissiveOnUnannotated() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    // Assume methods take @Nullable, even if annotated otherwise",
            "    RestrictivelyAnnotatedClass.consumesObjectUnannotated(null);",
            "    RestrictivelyAnnotatedClass.consumesObjectNonNull(null);",
            "    // Ignore explict @Nullable return",
            "    return RestrictivelyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void acknowledgeRestrictiveAnnotationsWhenFlagSet() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    RestrictivelyAnnotatedClass.consumesObjectUnannotated(null);",
            "    // BUG: Diagnostic contains: @NonNull is required",
            "    RestrictivelyAnnotatedClass.consumesObjectNonNull(null);",
            "    // BUG: Diagnostic contains: @NonNull is required",
            "    RestrictivelyAnnotatedClass.consumesObjectNotNull(null);",
            "    // BUG: Diagnostic contains: returning @Nullable",
            "    return RestrictivelyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void defaultPermissiveOnRecently() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                // should be permissive even when AcknowledgeRestrictiveAnnotations is set
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.AndroidRecentlyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    // Assume methods take @Nullable, even if annotated otherwise",
            "    AndroidRecentlyAnnotatedClass.consumesObjectUnannotated(null);",
            "    AndroidRecentlyAnnotatedClass.consumesObjectNonNull(null);",
            "    // Ignore explict @Nullable return",
            "    return AndroidRecentlyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void acknowledgeRecentlyAnnotationsWhenFlagSet() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true",
                "-XepOpt:NullAway:AcknowledgeAndroidRecent=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.AndroidRecentlyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    AndroidRecentlyAnnotatedClass.consumesObjectUnannotated(null);",
            "    // BUG: Diagnostic contains: @NonNull is required",
            "    AndroidRecentlyAnnotatedClass.consumesObjectNonNull(null);",
            "    // BUG: Diagnostic contains: returning @Nullable",
            "    return AndroidRecentlyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void restrictivelyAnnotatedMethodsWorkWithNullnessFromDataflow() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "class Test {",
            "  Object test1(RestrictivelyAnnotatedClass instance) {",
            "    if (instance.getField() != null) {",
            "      return instance.getField();",
            "    }",
            "    throw new Error();",
            "  }",
            "  Object test2(RestrictivelyAnnotatedClass instance) {",
            "    if (instance.field != null) {",
            "      return instance.field;",
            "    }",
            "    throw new Error();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void restrictivelyAnnotatedMethodsWorkWithNullnessFromDataflow2() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedGenericContainer;",
            "class Test {",
            "  String test(RestrictivelyAnnotatedGenericContainer<Integer> instance) {",
            "    if (instance.getField() == null) {",
            "      return \"\";",
            "    }",
            "    return instance.getField().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overridingRestrictivelyAnnotatedMethod() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "TestNegativeCases.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "import org.checkerframework.checker.nullness.qual.NonNull;",
            "import javax.annotation.Nullable;",
            "public class TestNegativeCases extends RestrictivelyAnnotatedClass {",
            "   TestNegativeCases(){ super(new Object()); }",
            "   @Override public void acceptsNonNull(@Nullable Object o) { }",
            "   @Override public void acceptsNonNull2(Object o) { }",
            "   @Override public void acceptsNullable2(@Nullable Object o) { }",
            "   @Override public Object returnsNonNull() { return new Object(); }",
            "   @Override public Object returnsNullable() { return new Object(); }",
            "   @Override public @Nullable Object returnsNullable2() { return new Object();}",
            "}")
        .addSourceLines(
            "TestPositiveCases.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "import org.checkerframework.checker.nullness.qual.NonNull;",
            "import javax.annotation.Nullable;",
            "public class TestPositiveCases extends RestrictivelyAnnotatedClass {",
            "   TestPositiveCases(){ super(new Object()); }",
            "   // BUG: Diagnostic contains: parameter o is @NonNull",
            "   public void acceptsNullable(Object o) { }",
            "   // BUG: Diagnostic contains: method returns @Nullable",
            "   public @Nullable Object returnsNonNull2() { return new Object(); }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaPlusRestrictivePositive() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFI;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void foo() {",
            "    RestrictivelyAnnotatedFI func = (x) -> {",
            "      // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "      x.toString();",
            "      return new Object();",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaPlusRestrictiveNegative() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFI;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void foo() {",
            "    RestrictivelyAnnotatedFI func = (x) -> {",
            "      // no error since AcknowledgeRestrictiveAnnotations disabled",
            "      x.toString();",
            "      return new Object();",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void annotatedVsUnannotatedMethodRefOverrideChecks() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                // Note: this is the OFF case.
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=false"))
        .addSourceLines(
            "AnnotatedStringIDFunctions.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class AnnotatedStringIDFunctions {",
            "    public static String idRetNonNull(String s) {",
            "        return s;",
            "    }",
            "    @Nullable",
            "    public static String idRetNullable(String s) {",
            "        return s;",
            "    }",
            "}")
        .addSourceLines(
            "UnannotatedStringIDFunctions.java",
            "package com.uber.nullaway.lib.unannotated;",
            "import javax.annotation.Nullable;",
            "public class UnannotatedStringIDFunctions {",
            "    public static String idRetNonNull(String s) {",
            "        return s;",
            "    }",
            "    @Nullable",
            "    public static String idRetNullable(String s) {",
            "        return s;",
            "    }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.base.Function;", // is Function<String, String!> from model
            "import com.google.common.collect.Maps;",
            "import com.uber.nullaway.lib.unannotated.UnannotatedStringIDFunctions;",
            "import java.util.List;",
            "import java.util.Map;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "    public static Map<String, String> testFunctionOverrideMethodRef1(List<String> keys) {",
            "        return Maps.toMap(keys,",
            "                /* is Function<String, String!> */ AnnotatedStringIDFunctions::idRetNonNull);",
            "    }",
            "    public static Map<String, String> testFunctionOverrideMethodRef2(List<String> keys) {",
            "        return Maps.toMap(keys,",
            "                // BUG: Diagnostic contains: method returns @Nullable, but functional interface",
            "                /* is Function<String, String?> */ AnnotatedStringIDFunctions::idRetNullable);",
            "    }",
            "    public static Map<String, String> testFunctionOverrideMethodRef3(List<String> keys) {",
            "        return Maps.toMap(keys,",
            "                /* is Function<String, String!> */ UnannotatedStringIDFunctions::idRetNonNull);",
            "    }",
            "    public static Map<String, String> testFunctionOverrideMethodRef4(List<String> keys) {",
            "        // No report, since idRetNullable() is unannotated and restrictive annotations are off",
            "        return Maps.toMap(keys,",
            "                /* is Function<String, String?> */ UnannotatedStringIDFunctions::idRetNullable);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void annotatedVsUnannotatedMethodRefOverrideWithRestrictiveAnnotations() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "AnnotatedStringIDFunctions.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class AnnotatedStringIDFunctions {",
            "    public static String idRetNonNull(String s) {",
            "        return s;",
            "    }",
            "    @Nullable",
            "    public static String idRetNullable(String s) {",
            "        return s;",
            "    }",
            "}")
        .addSourceLines(
            "UnannotatedStringIDFunctions.java",
            "package com.uber.nullaway.lib.unannotated;",
            "import javax.annotation.Nullable;",
            "public class UnannotatedStringIDFunctions {",
            "    public static String idRetNonNull(String s) {",
            "        return s;",
            "    }",
            "    @Nullable",
            "    public static String idRetNullable(String s) {",
            "        return s;",
            "    }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.base.Function;", // is Function<String, String!> from model
            "import com.google.common.collect.Maps;",
            "import com.uber.nullaway.lib.unannotated.UnannotatedStringIDFunctions;",
            "import java.util.List;",
            "import java.util.Map;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "    public static Map<String, String> testFunctionOverrideMethodRef1(List<String> keys) {",
            "        return Maps.toMap(keys,",
            "                /* is Function<String, String!> */ AnnotatedStringIDFunctions::idRetNonNull);",
            "    }",
            "    public static Map<String, String> testFunctionOverrideMethodRef2(List<String> keys) {",
            "        return Maps.toMap(keys,",
            "                // BUG: Diagnostic contains: method returns @Nullable, but functional interface",
            "                /* is Function<String, String?> */ AnnotatedStringIDFunctions::idRetNullable);",
            "    }",
            "    public static Map<String, String> testFunctionOverrideMethodRef3(List<String> keys) {",
            "        return Maps.toMap(keys,",
            "                /* is Function<String, String!> */ UnannotatedStringIDFunctions::idRetNonNull);",
            "    }",
            "    public static Map<String, String> testFunctionOverrideMethodRef4(List<String> keys) {",
            "        // Note: doesn't matter that the method ref is unannotated, since restrictive annotations",
            "        // are on.",
            "        return Maps.toMap(keys,",
            "                // BUG: Diagnostic contains: method returns @Nullable, but functional interface",
            "                /* is Function<String, String?> */ UnannotatedStringIDFunctions::idRetNullable);",
            "    }",
            "}")
        .doTest();
  }
}
