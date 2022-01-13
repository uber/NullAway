package com.uber.nullaway;

import org.junit.Test;

public class NullAwayFrameworkTests extends NullAwayTestsBase {
  @Test
  public void lombokSupportTesting() {
    defaultCompilationHelper.addSourceFile("lombok/LombokBuilderInit.java").doTest();
  }

  @Test
  public void coreNullabilityNativeModels() {
    defaultCompilationHelper
        .addSourceFile("NullAwayNativeModels.java")
        .addSourceFile("androidstubs/WebView.java")
        .addSourceFile("androidstubs/TextUtils.java")
        .doTest();
  }

  @Test
  public void rxSupportPositiveCases() {
    defaultCompilationHelper.addSourceFile("NullAwayRxSupportPositiveCases.java").doTest();
  }

  @Test
  public void rxSupportNegativeCases() {
    defaultCompilationHelper.addSourceFile("NullAwayRxSupportNegativeCases.java").doTest();
  }

  @Test
  public void streamSupportNegativeCases() {
    defaultCompilationHelper.addSourceFile("NullAwayStreamSupportNegativeCases.java").doTest();
  }

  @Test
  public void streamSupportPositiveCases() {
    defaultCompilationHelper.addSourceFile("NullAwayStreamSupportPositiveCases.java").doTest();
  }

  @Test
  public void supportObjectsIsNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  private void foo(@Nullable String s) {",
            "    if (!Objects.isNull(s)) {",
            "      s.toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testJDKPathGetParentModel() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import java.nio.file.Files;",
            "import java.nio.file.Path;",
            "public class Test {",
            " Optional<Path> findConfig(Path searchDir) {",
            "    Path configFile = searchDir.resolve(\"foo.yml\");",
            "    if (Files.exists(configFile)) {",
            "      return Optional.of(configFile);",
            "    }",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'searchDir.getParent()' where @NonNull",
            "    return this.findConfig(searchDir.getParent());",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void defaultLibraryModelsObjectNonNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  String foo(@Nullable Object o) {",
            "    if (Objects.nonNull(o)) {",
            "     return o.toString();",
            "    };",
            "    return \"\";",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void checkForNullSupport() {
    defaultCompilationHelper
        // This is just to check the behavior is the same between @Nullable and @CheckForNull
        .addSourceLines(
            "TestNullable.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class TestNullable {",
            "  @Nullable",
            "  Object nullable = new Object();",
            "  public void setNullable(@Nullable Object nullable) {this.nullable = nullable;}",
            "  // BUG: Diagnostic contains: dereferenced expression nullable is @Nullable",
            "  public void run() {System.out.println(nullable.toString());}",
            "}")
        .addSourceLines(
            "TestCheckForNull.java",
            "package com.uber;",
            "import javax.annotation.CheckForNull;",
            "class TestCheckForNull {",
            "  @CheckForNull",
            "  Object checkForNull = new Object();",
            "  public void setCheckForNull(@CheckForNull Object checkForNull) {this.checkForNull = checkForNull;}",
            "  // BUG: Diagnostic contains: dereferenced expression checkForNull is @Nullable",
            "  public void run() {System.out.println(checkForNull.toString());}",
            "}")
        .doTest();
  }

  @Test
  public void orElseLibraryModelSupport() {
    // Checks both Optional.orElse(...) support itself and the general nullImpliesNullParameters
    // Library Models mechanism for encoding @Contract(!null -> !null) as a library model.
    defaultCompilationHelper
        .addSourceLines(
            "TestOptionalOrElseNegative.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.util.Optional;",
            "class TestOptionalOrElseNegative {",
            "  public Object foo(Optional<Object> o) {",
            "    return o.orElse(\"Something\");",
            "  }",
            "  public @Nullable Object bar(Optional<Object> o) {",
            "    return o.orElse(null);",
            "  }",
            "}")
        .addSourceLines(
            "TestOptionalOrElsePositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "class TestOptionalOrElsePositive {",
            "  public Object foo(Optional<Object> o) {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return o.orElse(null);",
            "  }",
            "  public void bar(Optional<Object> o) {",
            "    // BUG: Diagnostic contains: dereferenced expression o.orElse(null) is @Nullable",
            "    System.out.println(o.orElse(null).toString());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overridingNativeModelsInAnnotatedCodeDoesNotPropagateTheModel() {
    // See https://github.com/uber/NullAway/issues/445
    defaultCompilationHelper
        .addSourceLines(
            "NonNullGetMessage.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "class NonNullGetMessage extends RuntimeException {",
            "  NonNullGetMessage(final String message) {",
            "     super(message);",
            "  }",
            "  @Override",
            "  public String getMessage() {",
            "    return Objects.requireNonNull(super.getMessage());",
            "  }",
            "  public static void foo(NonNullGetMessage e) {",
            "    expectsNonNull(e.getMessage());",
            "  }",
            "  public static void expectsNonNull(String str) {",
            "    System.out.println(str);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overridingNativeModelsInAnnotatedCodeDoesNotGenerateSafetyHoles() {
    // See https://github.com/uber/NullAway/issues/445
    defaultCompilationHelper
        .addSourceLines(
            "NonNullGetMessage.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "class NonNullGetMessage extends RuntimeException {",
            "  NonNullGetMessage(@Nullable String message) {",
            "     super(message);",
            "  }",
            "  @Override",
            "  public String getMessage() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return super.getMessage();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void springAutowiredFieldTest() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.springframework.stereotype.Component;",
            "@Component",
            "public class Foo {",
            "  @Nullable String bar;",
            "  public void setBar(String s) {",
            "    bar = s;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.springframework.beans.factory.annotation.Autowired;",
            "import org.springframework.stereotype.Service;",
            "@Service",
            "public class Test {",
            "  @Autowired",
            "  Foo f;", // Initialized by spring.
            "  public void Fun() {",
            "    f.setBar(\"hello\");",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void springAutowiredConstructorTest() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.springframework.stereotype.Component;",
            "@Component",
            "public class Foo {",
            "  @Nullable String bar;",
            "  public void setBar(String s) {",
            "    bar = s;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.springframework.beans.factory.annotation.Autowired;",
            "import org.springframework.stereotype.Service;",
            "@Service",
            "public class Test {",
            "  Foo f;", // Initialized by spring.
            "  @Autowired",
            "  public void init() {",
            "     f = new Foo();",
            "  }",
            "  public void Fun() {",
            "    f.setBar(\"hello\");",
            "  }",
            "}")
        .doTest();
  }
}
