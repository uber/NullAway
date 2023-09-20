package com.uber.nullaway;

import java.util.Arrays;
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
  public void defaultLibraryModelsClassIsInstance() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  int classIsInstance(@Nullable String s) {",
            "    if (CharSequence.class.isInstance(s)) {",
            "      return s.hashCode();",
            "    } else {",
            "      // BUG: Diagnostic contains: dereferenced",
            "      return s.hashCode();",
            "    }",
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
  public void springTestAutowiredFieldTest() {
    defaultCompilationHelper
        .addSourceFile("springboot-annotations/MockBean.java")
        .addSourceFile("springboot-annotations/SpyBean.java")
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
            "TestCase.java",
            "package com.uber;",
            "import org.junit.jupiter.api.Test;",
            "import org.springframework.boot.test.mock.mockito.SpyBean;",
            "import org.springframework.boot.test.mock.mockito.MockBean;",
            "public class TestCase {",
            "  @SpyBean",
            "  private Foo spy;", // Initialized by spring test (via Mockito).
            "  @MockBean",
            "  private Foo mock;", // Initialized by spring test (via Mockito).
            "  @Test",
            "  void springTest() {",
            "    spy.setBar(\"hello\");",
            "    mock.setBar(\"hello\");",
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

  @Test
  public void testLombokBuilderWithGeneratedAsUnannotated() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lombok.LombokDTO;",
            "class Test {",
            "  void testSetters(LombokDTO ldto) {",
            "     ldto.setNullableField(null);",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "     ldto.setField(null);",
            "  }",
            "  String testGetterSafe(LombokDTO ldto) {",
            "     return ldto.getField();",
            "  }",
            "  String testGetterNullable(LombokDTO ldto) {",
            "     // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "     return ldto.getNullableField();",
            "  }",
            "  LombokDTO testBuilderSafe(@Nullable String s1, String s2) {",
            "     // Safe, because s2 is non-null and nullableField can take @Nullable",
            "     return LombokDTO.builder().nullableField(s1).field(s2).build();",
            "  }",
            "  LombokDTO testBuilderUnsafe(@Nullable String s1, @Nullable String s2) {",
            "     // No error, because the code of LombokDTO.Builder is @Generated and we are",
            "     // building with TreatGeneratedAsUnannotated=true",
            "     return LombokDTO.builder().nullableField(s1).field(s2).build();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testLombokBuilderWithoutGeneratedAsUnannotated() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lombok.LombokDTO;",
            "class Test {",
            "  void testSetters(LombokDTO ldto) {",
            "     ldto.setNullableField(null);",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "     ldto.setField(null);",
            "  }",
            "  String testGetterSafe(LombokDTO ldto) {",
            "     return ldto.getField();",
            "  }",
            "  String testGetterNullable(LombokDTO ldto) {",
            "     // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "     return ldto.getNullableField();",
            "  }",
            "  LombokDTO testBuilderSafe(@Nullable String s1, String s2) {",
            "     // Safe, because s2 is non-null and nullableField can take @Nullable",
            "     return LombokDTO.builder().nullableField(s1).field(s2).build();",
            "  }",
            "  LombokDTO testBuilderUnsafe(@Nullable String s1, @Nullable String s2) {",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 's2' where @NonNull is required",
            "     return LombokDTO.builder().nullableField(s1).field(s2).build();",
            "  }",
            "}")
        .doTest();
  }

  /**
   * This test is solely to check if we can run through some of the {@link
   * com.uber.nullaway.handlers.LombokHandler} logic without crashing. It does not check that the
   * logic is correct.
   */
  @Test
  public void lombokHandlerRunsWithoutCrashing() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable Object test;",
            "  @lombok.Generated",
            "  Object $default$test() {",
            "    return new Object();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void systemConsoleNullable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  void foo() {",
            "     // BUG: Diagnostic contains: dereferenced expression System.console()",
            "    System.console().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void mapGetOrDefault() {
    String[] sourceLines =
        new String[] {
          "package com.uber;",
          "import java.util.Map;",
          "import com.google.common.collect.ImmutableMap;",
          "import org.jspecify.annotations.Nullable;",
          "class Test {",
          "  void testGetOrDefaultMap(Map<String, String> m, String nonNullString, @Nullable String nullableString) {",
          "    m.getOrDefault(\"key\", \"value\").toString();",
          "    m.getOrDefault(\"key\", nonNullString).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    m.getOrDefault(\"key\", null).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    m.getOrDefault(\"key\", nullableString).toString();",
          "  }",
          "  void testGetOrDefaultImmutableMap(ImmutableMap<String, String> im, String nonNullString, @Nullable String nullableString) {",
          "    im.getOrDefault(\"key\", \"value\").toString();",
          "    im.getOrDefault(\"key\", nonNullString).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    im.getOrDefault(\"key\", null).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    im.getOrDefault(\"key\", nullableString).toString();",
          "  }",
          "}"
        };
    // test *without* restrictive annotations enabled
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines("Test.java", sourceLines)
        .doTest();
    // test *with* restrictive annotations enabled
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines("Test.java", sourceLines)
        .doTest();
  }

  @Test
  public void defaultLibraryModelsClassCast() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  void castNullable(@Nullable String s) {",
            "    // BUG: Diagnostic contains: dereferenced",
            "    CharSequence.class.cast(s).hashCode();",
            "  }",
            "  void castNonnull(String s1, @Nullable String s2) {",
            "    CharSequence.class.cast(s1).hashCode();",
            "    if (s2 instanceof CharSequence) {",
            "      CharSequence.class.cast(s2).hashCode();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateNotNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.notNull(s);",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateNotNullWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.notNull(s, \"Message\");",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateArrayNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String[] s) {",
            "    Validate.notEmpty(s, \"Message\");",
            "    int l = s.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateArrayNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String[] s) {",
            "    Validate.notEmpty(s);",
            "    int l = s.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateListNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.List;",
            "public class Foo {",
            "  public void bar(@Nullable List<String> s) {",
            "    Validate.notEmpty(s, \"Message\");",
            "    int l = s.size();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateListNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.List;",
            "public class Foo {",
            "  public void bar(@Nullable List<String> s) {",
            "    Validate.notEmpty(s);",
            "    int l = s.size();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateMapNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.Map;",
            "public class Foo {",
            "  public void bar(@Nullable Map<String, String> s) {",
            "    Validate.notEmpty(s, \"Message\");",
            "    int l = s.size();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateMapNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.Map;",
            "public class Foo {",
            "  public void bar(@Nullable Map<String, String> s) {",
            "    Validate.notEmpty(s);",
            "    int l = s.size();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateStringNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.notEmpty(s, \"Message\");",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateStringNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.notEmpty(s);",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateStringNotBlankWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.notBlank(s, \"Message\");",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateStringNotBlank() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.notBlank(s);",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateArrayNoNullElementsWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String[] s) {",
            "    Validate.noNullElements(s, \"Message\");",
            "    int l = s.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateArrayNoNullElements() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String[] s) {",
            "    Validate.noNullElements(s);",
            "    int l = s.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateIterableNoNullElementsWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.Iterator;",
            "public class Foo {",
            "  public void bar(@Nullable Iterable<String> s) {",
            "    Validate.noNullElements(s, \"Message\");",
            "    Iterator<String> l = s.iterator();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateIterableNoNullElements() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.Iterator;",
            "public class Foo {",
            "  public void bar(@Nullable Iterable<String> s) {",
            "    Validate.noNullElements(s);",
            "    Iterator<String> l = s.iterator();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateArrayValidIndexWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String[] s) {",
            "    Validate.validIndex(s, 0, \"Message\");",
            "    int l = s.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateArrayValidIndex() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String[] s) {",
            "    Validate.validIndex(s, 0);",
            "    int l = s.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateCollectionValidIndexWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.List;",
            "public class Foo {",
            "  public void bar(@Nullable List<String> s) {",
            "    Validate.validIndex(s, 0, \"Message\");",
            "    int l = s.size();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateCollectionValidIndex() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.List;",
            "public class Foo {",
            "  public void bar(@Nullable List<String> s) {",
            "    Validate.validIndex(s, 0);",
            "    int l = s.size();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateStringValidIndexWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.validIndex(s, 0, \"Message\");",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void apacheValidateStringValidIndex() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import org.apache.commons.lang3.Validate;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Foo {",
            "  public void bar(@Nullable String s) {",
            "    Validate.validIndex(s, 0);",
            "    int l = s.length();",
            "  }",
            "}")
        .doTest();
  }
}
