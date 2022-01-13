package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayVarargsTests {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  /**
   * Creates a new {@link CompilationTestHelper} with a list of javac arguments. As of Error Prone
   * 2.5.1, {@link CompilationTestHelper#setArgs(List)} can only be invoked once per object. So,
   * this method must be used to create a test helper when a different set of javac arguments is
   * required than those used for {@link #defaultCompilationHelper}.
   *
   * @param args the javac arguments
   * @return the test helper
   */
  private CompilationTestHelper makeTestHelperWithArgs(List<String> args) {
    return CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
  }

  @Test
  public void testNonNullVarargs() {
    defaultCompilationHelper
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Utilities {",
            " public static String takesNonNullVarargs(Object o, Object... others) {",
            "  String s = o.toString() + \" \";",
            "  for (Object other : others) {",
            "    s += other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testNonNullVarargs(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    Utilities.takesNonNullVarargs(o1, o2, o3);",
            "    Utilities.takesNonNullVarargs(o1);", // Empty var args passed
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    Utilities.takesNonNullVarargs(o1, o4);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testNullableVarargs() {
    defaultCompilationHelper
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Utilities {",
            " public static String takesNullableVarargs(Object o, @Nullable Object... others) {",
            "  String s = o.toString() + \" \";",
            "  // BUG: Diagnostic contains: enhanced-for expression others is @Nullable",
            "  for (Object other : others) {",
            "    s += (other == null) ? \"(null) \" : other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void testNonNullVarargsFromHandler() {
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
            "import javax.annotation.Nonnull;",
            generatedAnnot + "(\"foo\")",
            "public class Generated {",
            " public static String takesNonNullVarargs(@Nonnull Object o, @Nonnull Object... others) {",
            "  String s = o.toString() + \" \";",
            "  for (Object other : others) {",
            "    s += other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testNonNullVarargs(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    Generated.takesNonNullVarargs(o1, o2, o3);",
            "    Generated.takesNonNullVarargs(o1);", // Empty var args passed
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    Generated.takesNonNullVarargs(o1, o4);",
            "  }",
            "}")
        .doTest();
  }
}
