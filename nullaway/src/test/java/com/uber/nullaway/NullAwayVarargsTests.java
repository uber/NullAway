package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayVarargsTests extends NullAwayTestsBase {

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
