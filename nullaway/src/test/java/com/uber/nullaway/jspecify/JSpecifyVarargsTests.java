package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.VarargsTests;
import java.util.Arrays;
import org.junit.Test;

/**
 * Tests for handling of varargs annotations in JSpecify mode. Based on {@link VarargsTests}, with
 * tests and assertions modified appropriately.
 */
public class JSpecifyVarargsTests extends NullAwayTestsBase {

  @Test
  public void testNonNullVarargs() {
    makeHelper()
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
            "    Object[] x = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'x'",
            "    Utilities.takesNonNullVarargs(o1, x);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testNullableVarargs() {
    makeHelper()
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Utilities {",
            " public static String takesNullableVarargs(Object o, @Nullable Object... others) {",
            "  String s = o.toString() + \" \" + others.toString();",
            "  for (Object other : others) {",
            "    // BUG: Diagnostic contains: dereferenced expression other is @Nullable",
            "    s += other.toString();",
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
            "    Utilities.takesNullableVarargs(o1, o2, o3, o4);",
            "    Utilities.takesNullableVarargs(o1);", // Empty var args passed
            "    Utilities.takesNullableVarargs(o1, o4);",
            "    Utilities.takesNullableVarargs(o1, (java.lang.Object) null);",
            "    Object[] x = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'x'",
            "    Utilities.takesNullableVarargs(o1, x);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableTypeUseVarargs() {
    makeHelper()
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "public class Utilities {",
            " public static String takesNullableVarargs(Object o, @Nullable Object... others) {",
            "  String s = o.toString() + \" \" + others.toString();",
            "  for (Object other : others) {",
            "    // BUG: Diagnostic contains: dereferenced expression other is @Nullable",
            "    s += other.toString();",
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
            "    Utilities.takesNullableVarargs(o1, o2, o3, o4);",
            "    Utilities.takesNullableVarargs(o1);", // Empty var args passed
            "    Utilities.takesNullableVarargs(o1, o4);",
            "    Utilities.takesNullableVarargs(o1, (java.lang.Object) null);",
            "    Object[] x = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'x'",
            "    Utilities.takesNullableVarargs(o1, x);",
            "  }",
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

  // This is required for compatibility with kotlinc generated jars
  // See https://github.com/uber/NullAway/issues/720
  @Test
  public void testSkipJetbrainsNotNullOnVarArgsFromThirdPartyJars() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "ThirdParty.java",
            "package com.uber.nullaway.lib.unannotated;",
            "import org.jetbrains.annotations.NotNull;",
            "public class ThirdParty {",
            " public static String takesNullableVarargs(@NotNull Object o, @NotNull Object... others) {",
            "  String s = o.toString() + \" \";",
            "  for (Object other : others) {",
            "    s += (other == null) ? \"(null) \" : other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "FirstParty.java",
            "package com.uber;",
            "import org.jetbrains.annotations.NotNull;",
            "public class FirstParty {",
            " public static String takesNonNullVarargs(@NotNull Object o, @NotNull Object... others) {",
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
            "import com.uber.nullaway.lib.unannotated.ThirdParty;",
            "public class Test {",
            "  public void testNullableVarargs(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    ThirdParty.takesNullableVarargs(o1, o2, o3, o4);",
            "    ThirdParty.takesNullableVarargs(o1);", // Empty var args passed
            "    ThirdParty.takesNullableVarargs(o1, o4);",
            "    ThirdParty.takesNullableVarargs(o1, (Object) null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter '(Object[]) null' where @NonNull",
            "    ThirdParty.takesNullableVarargs(o1, (Object[]) null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    ThirdParty.takesNullableVarargs(o4);", // First arg is not varargs.
            "  }",
            "  public void testNonNullVarargs(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    FirstParty.takesNonNullVarargs(o1, o2, o3);",
            "    FirstParty.takesNonNullVarargs(o1);", // Empty var args passed
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    FirstParty.takesNonNullVarargs(o1, o4);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseNullableVarargsArray() {
    makeHelper()
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "public class Utilities {",
            " public static String takesNullableVarargsArray(Object o, Object @Nullable... others) {",
            "  String s = o.toString() + \" \";",
            "  // BUG: Diagnostic contains: enhanced-for expression others is @Nullable",
            "  for (Object other : others) {",
            "    s += (other == null) ? \"(null) \" : other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "public class Test {",
            "  public void testNullableVarargsArray(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    Utilities.takesNullableVarargsArray(o1, o2, o3, o4);",
            "    Utilities.takesNullableVarargsArray(o1);", // Empty var args passed
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object) null);",
            "    // this is fine!",
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object[]) null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseNullableVarargsArrayAndElements() {
    makeHelper()
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "public class Utilities {",
            " public static String takesNullableVarargsArray(Object o, @Nullable Object @Nullable... others) {",
            "  String s = o.toString() + \" \";",
            "  // BUG: Diagnostic contains: enhanced-for expression others is @Nullable",
            "  for (Object other : others) {",
            "    // BUG: Diagnostic contains: dereferenced expression other is @Nullable",
            "    s += other.toString();",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "public class Test {",
            "  public void testNullableVarargsArray(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    Utilities.takesNullableVarargsArray(o1, o2, o3, o4);",
            "    Utilities.takesNullableVarargsArray(o1);", // Empty var args passed
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object) null);",
            "    // this is fine!",
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object[]) null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseAndDeclarationBeforeDots() {
    makeHelper()
        .addSourceLines(
            "Nullable.java",
            "package com.uber;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})",
            "public @interface Nullable {}")
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "public class Utilities {",
            " public static String takesNullableVarargsArray(Object o, Object @Nullable... others) {",
            "  String s = o.toString() + \" \";",
            "  // BUG: Diagnostic contains: enhanced-for expression others is @Nullable",
            "  for (Object other : others) {",
            "    s += (other == null) ? \"(null) \" : other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "public class Test {",
            "  public void testNullableVarargsArray(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    Utilities.takesNullableVarargsArray(o1, o2, o3, o4);",
            "    Utilities.takesNullableVarargsArray(o1);", // Empty var args passed
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object) null);",
            "    // this is fine!",
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object[]) null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseAndDeclarationOnElements() {
    makeHelper()
        .addSourceLines(
            "Nullable.java",
            "package com.uber;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})",
            "public @interface Nullable {}")
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "public class Utilities {",
            " public static String takesNullableVarargsArray(Object o, @Nullable Object... others) {",
            "  String s = o.toString() + \" \";",
            "  for (Object other : others) {",
            "    // BUG: Diagnostic contains: dereferenced expression other is @Nullable",
            "    s += other.toString();",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "public class Test {",
            "  public void testNullableVarargsArray(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    Utilities.takesNullableVarargsArray(o1, o2, o3, o4);",
            "    Utilities.takesNullableVarargsArray(o1);", // Empty var args passed
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object) null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter '(java.lang.Object[]) null' where @NonNull",
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object[]) null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseAndDeclarationOnBoth() {
    makeHelper()
        .addSourceLines(
            "Nullable.java",
            "package com.uber;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})",
            "public @interface Nullable {}")
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "public class Utilities {",
            " public static String takesNullableVarargsArray(Object o, @Nullable Object @Nullable... others) {",
            "  String s = o.toString() + \" \";",
            "  // BUG: Diagnostic contains: enhanced-for expression others is @Nullable",
            "  for (Object other : others) {",
            "    // BUG: Diagnostic contains: dereferenced expression other is @Nullable",
            "    s += other.toString();",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "public class Test {",
            "  public void testNullableVarargsArray(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    Utilities.takesNullableVarargsArray(o1, o2, o3, o4);",
            "    Utilities.takesNullableVarargsArray(o1);", // Empty var args passed
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object) null);",
            "    Utilities.takesNullableVarargsArray(o1, (java.lang.Object[]) null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void varargsPassArrayAndOtherArgs() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "public class Test {",
            "  static void takesVarargs(Object... args) {}",
            "  static void test(Object o) {",
            "    Object[] x = new Object[10];",
            "    takesVarargs(x, o);",
            "  }",
            "  static void takesNullableVarargsArray(Object @Nullable... args) {}",
            "  static void test2(Object o) {",
            "    Object[] x = null;",
            "    // can pass x as the varargs array itself, but not along with other args",
            "    takesNullableVarargsArray(x);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'x'",
            "    takesNullableVarargsArray(x, o);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testVarargsNullArrayUnannotated() {
    defaultCompilationHelper
        .addSourceLines(
            "Unannotated.java",
            "package foo.unannotated;",
            "public class Unannotated {",
            "  public static void takesVarargs(Object... args) {}",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import foo.unannotated.Unannotated;",
            "public class Test {",
            "  public void test() {",
            "    Object[] x = null;",
            "    Unannotated.takesVarargs(x);",
            "  }",
            "}")
        .doTest();
  }

  /**
   * This test is a WIP for restrictive annotations on varargs. More assertions still need to be
   * written, and more support needs to be implemented.
   */
  @Test
  public void testVarargsRestrictive() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JSpecifyMode=true",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "NonNull.java",
            "package com.uber.both;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})",
            "public @interface NonNull {}")
        .addSourceLines(
            "Unannotated.java",
            "package foo.unannotated;",
            "public class Unannotated {",
            "  public static void takesVarargsDeclaration(@javax.annotation.Nonnull Object... args) {}",
            "  public static void takesVarargsTypeUseOnArray(Object @org.jspecify.annotations.NonNull... args) {}",
            "  public static void takesVarargsTypeUseOnElements(@org.jspecify.annotations.NonNull Object... args) {}",
            "  public static void takesVarargsTypeUseOnBoth(@org.jspecify.annotations.NonNull Object @org.jspecify.annotations.NonNull... args) {}",
            "  public static void takesVarargsBothOnArray(Object @com.uber.both.NonNull... args) {}",
            "  public static void takesVarargsBothOnElements(@com.uber.both.NonNull Object... args) {}",
            "  public static void takesVarargsBothOnBoth(@com.uber.both.NonNull Object @com.uber.both.NonNull... args) {}",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import foo.unannotated.Unannotated;",
            "public class Test {",
            "  public void testDeclaration() {",
            "    Object x = null;",
            "    Object[] y = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'x'",
            "    Unannotated.takesVarargsDeclaration(x);",
            "    Unannotated.takesVarargsDeclaration(y);",
            "  }",
            "  public void testTypeUseOnArray() {",
            "    Object x = null;",
            "    Object[] y = null;",
            "    Unannotated.takesVarargsTypeUseOnArray(x);",
            // TODO report an error here; will require some refactoring of restrictive annotation
            //  handling
            "    Unannotated.takesVarargsTypeUseOnArray(y);",
            "  }",
            "  public void testTypeUseOnElements() {",
            "    Object x = null;",
            "    Object[] y = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'x'",
            "    Unannotated.takesVarargsTypeUseOnElements(x);",
            "    Unannotated.takesVarargsTypeUseOnElements(y);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void varargsOverride() {
    makeHelper()
        .addSourceLines(
            "VarargsOverride.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "public class VarargsOverride {",
            "    interface NullableVarargsContents {",
            "        void varargs(@Nullable Object... params);",
            "    }",
            "    static class NullableVarargsContentsImpl1 implements NullableVarargsContents {",
            "        @Override",
            "        // legal override",
            "        public void varargs(@Nullable Object... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsContentsImpl2 implements NullableVarargsContents {",
            "        @Override",
            "        // BUG: Diagnostic contains: Parameter has type Object[], but overridden method",
            "        public void varargs(Object... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsContentsImpl3 implements NullableVarargsContents {",
            "        @Override",
            // TODO open an issue to improve the error message in a follow up
            "        // BUG: Diagnostic contains: Parameter has type Object[]",
            "        public void varargs(Object @Nullable... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsContentsImpl4 implements NullableVarargsContents {",
            "        @Override",
            "        // legal override",
            "        public void varargs(@Nullable Object @Nullable... params) {",
            "        }",
            "    }",
            "    interface NullableVarargsArray {",
            "        void varargs(Object @Nullable... params);",
            "    }",
            "    static class NullableVarargsArrayImpl1 implements NullableVarargsArray {",
            "        @Override",
            "        // BUG: Diagnostic contains: parameter params is @NonNull",
            "        public void varargs(@Nullable Object... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsArrayImpl2 implements NullableVarargsArray {",
            "        @Override",
            "        // BUG: Diagnostic contains: parameter params is @NonNull",
            "        public void varargs(Object... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsArrayImpl3 implements NullableVarargsArray {",
            "        @Override",
            "        // legal override",
            "        public void varargs(Object @Nullable... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsArrayImpl4 implements NullableVarargsArray {",
            "        @Override",
            "        // ok: contravariance",
            "        public void varargs(@Nullable Object @Nullable... params) {",
            "        }",
            "    }",
            "    interface NullableVarargsBoth {",
            "        void varargs(@Nullable Object @Nullable... params);",
            "    }",
            "    static class NullableVarargsBothImpl1 implements NullableVarargsBoth {",
            "        @Override",
            "        // BUG: Diagnostic contains: parameter params is @NonNull",
            "        public void varargs(@Nullable Object... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsBothImpl2 implements NullableVarargsBoth {",
            "        @Override",
            "        // BUG: Diagnostic contains: parameter params is @NonNull",
            "        public void varargs(Object... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsBothImpl3 implements NullableVarargsBoth {",
            "        @Override",
            "        // BUG: Diagnostic contains: Parameter has type Object[]",
            "        public void varargs(Object @Nullable... params) {",
            "        }",
            "    }",
            "    static class NullableVarargsBothImpl4 implements NullableVarargsBoth {",
            "        @Override",
            "        // legal override",
            "        public void varargs(@Nullable Object @Nullable... params) {",
            "        }",
            "    }",
            "    interface NonNullVarargs {",
            "        void varargs(Object... params);",
            "    }",
            "    static class NonNullVarargsImpl1 implements NonNullVarargs {",
            "        @Override",
            "        // ok: contravariance",
            "        public void varargs(@Nullable Object... params) {",
            "        }",
            "    }",
            "    static class NonNullVarargsImpl2 implements NonNullVarargs {",
            "        @Override",
            "        // legal override",
            "        public void varargs(Object... params) {",
            "        }",
            "    }",
            "    static class NonNullVarargsImpl3 implements NonNullVarargs {",
            "        @Override",
            "        // legal override",
            "        public void varargs(Object @Nullable... params) {",
            "        }",
            "    }",
            "    static class NonNullVarargsImpl4 implements NonNullVarargs {",
            "        @Override",
            "        // ok: contravariance",
            "        public void varargs(@Nullable Object @Nullable... params) {",
            "        }",
            "    }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
