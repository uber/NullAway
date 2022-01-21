package com.uber.nullaway;

import com.uber.nullaway.testlibrarymodels.TestLibraryModels;
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
  public void libraryModelsOverrideRestrictiveAnnotations() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-processorpath",
                TestLibraryModels.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath(),
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void bar(RestrictivelyAnnotatedFIWithModelOverride f) {",
            "     // Param is @NullableDecl in bytecode, overridden by library model",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull",
            "     f.apply(null);",
            "  }",
            "  void foo() {",
            "    RestrictivelyAnnotatedFIWithModelOverride func = (x) -> {",
            "     // Param is @NullableDecl in bytecode, overridden by library model, thus safe",
            "     return x.toString();",
            "    };",
            "  }",
            "  void baz() {",
            "     // Safe to pass, since Function can't have a null instance parameter",
            "     bar(Object::toString);",
            "  }",
            "}")
        .doTest();
  }
}
