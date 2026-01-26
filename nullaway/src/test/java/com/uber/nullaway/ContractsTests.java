package com.uber.nullaway;

import static com.uber.nullaway.generics.JSpecifyJavacConfig.withJSpecifyModeArgs;

import java.util.Arrays;
import org.junit.Test;

public class ContractsTests extends NullAwayTestsBase {

  @Test
  public void checkContractPositiveCases() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceLines(
            "CheckContractPositiveCases.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class CheckContractPositiveCases {
              @Contract("_, !null -> !null")
              @Nullable
              Object foo(Object a, @Nullable Object b) {
                if (a.hashCode() % 2 == 0) {
                  // BUG: Diagnostic contains: Method foo has @Contract
                  return null;
                }
                return new Object();
              }
              @Contract("_, !null -> !null")
              @Nullable
              Object fooTwo(Object a, @Nullable Object b) {
                if (b != null) {
                  // BUG: Diagnostic contains: Method fooTwo has @Contract(_, !null -> !null), but this appears
                  // to be violated, as a @Nullable value may be returned when parameter b is non-null.
                  return null;
                }
                return new Object();
              }
              @Contract("_, !null, _ -> !null")
              @Nullable
              Object fooThree(Object a, @Nullable Object b, Object c) {
                if (b != null) {
                  // BUG: Diagnostic contains:  Method fooThree has @Contract(_, !null, _ -> !null), but this
                  // appears to be violated, as a @Nullable value may be returned when parameter b is non-null.
                  return null;
                }
                return new Object();
              }
              @Contract("_, !null, !null, _ -> !null")
              @Nullable
              Object fooFour(Object a, @Nullable Object b, Object c, Object d) {
                if (b != null) {
                  // BUG: Diagnostic contains: Method fooFour has @Contract(_, !null, !null, _ -> !null), but
                  // this appears to be violated, as a @Nullable value may be returned when the contract
                  // preconditions are true.
                  return null;
                }
                return new Object();
              }
              @Nullable Object value = null;
              @Contract("_ -> !null")
              public @Nullable Object orElse(@Nullable Object other) {
                // Both contract and method signature assume 'other' is NULLABLE
                // BUG: Diagnostic contains: Method orElse has @Contract(_ -> !null), but this appears to be
                // violated, as a @Nullable value may be returned when the contract preconditions are true.
                return value != null ? value : other;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noContractCheckErrorsWithoutFlag() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            class Test {
              @Contract("_, !null -> !null")
              @Nullable
              Object foo(Object a, @Nullable Object b) {
                if (a.hashCode() % 2 == 0) {
                  // no error since CheckContracts is not set
                  return null;
                }
                return new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void checkContractNegativeCases() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceLines(
            "CheckContractNegativeCases.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class CheckContractNegativeCases {
              @Contract("_, !null -> !null")
              @Nullable
              Object foo(Object a, @Nullable Object b) {
                if (a.hashCode() % 2 == 0) {
                  return b;
                }
                return new Object();
              }
              @Contract("_, !null -> !null")
              @Nullable
              Object fooTwo(Object a, @Nullable Object b) {
                if (b != null) {
                  return b;
                }
                return new Object();
              }
              @Nullable Object value = null;
              @Contract("_ -> !null")
              public @Nullable Object orElse(Object other) {
                // 'other' is assumed to NONNULL using the information from method signature
                return value != null ? value : other;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void basicContractAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class NullnessChecker {
              @Contract("_, null -> true")
              static boolean isNull(boolean flag, @Nullable Object o) { return o == null; }
              @Contract("null -> false")
              static boolean isNonNull(@Nullable Object o) { return o != null; }
              @Contract("null -> fail")
              static void assertNonNull(@Nullable Object o) { if (o != null) throw new Error(); }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String test1(@Nullable Object o) {
                return NullnessChecker.isNonNull(o) ? o.toString() : "null";
              }
              String test2(@Nullable Object o) {
                return NullnessChecker.isNull(false, o) ? "null" : o.toString();
              }
              String test3(@Nullable Object o) {
                NullnessChecker.assertNonNull(o);
                return o.toString();
              }
              String test4(java.util.Map<String,Object> m) {
                NullnessChecker.assertNonNull(m.get("foo"));
                return m.get("foo").toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonJetbrainsAnnotationNamedContract() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            public class NullnessChecker {
              @Target(ElementType.METHOD)
              public @interface Contract {
                  String value();
              }
              @Target(ElementType.METHOD)
              public @interface NotContract {
                  String value();
              }
              @Contract("_, null -> true")
              static boolean isNull(boolean flag, @Nullable Object o) { return o == null; }
              @Contract("null -> false")
              static boolean isNonNull(@Nullable Object o) { return o != null; }
              @Contract("null -> fail")
              static void assertNonNull(@Nullable Object o) { if (o != null) throw new Error(); }
              @NotContract("null -> fail")
              static void assertNonNullNotContract(@Nullable Object o) { if (o != null) throw new Error(); }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String test1(@Nullable Object o) {
                return NullnessChecker.isNonNull(o) ? o.toString() : "null";
              }
              String test2(@Nullable Object o) {
                return NullnessChecker.isNull(false, o) ? "null" : o.toString();
              }
              String test3(@Nullable Object o) {
                NullnessChecker.assertNonNull(o);
                return o.toString();
              }
              String test4(@Nullable Object o) {
                NullnessChecker.assertNonNullNotContract(o);
                // BUG: Diagnostic contains: dereferenced expression
                return o.toString();
              }
              String test4(java.util.Map<String,Object> m) {
                NullnessChecker.assertNonNull(m.get("foo"));
                return m.get("foo").toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void impliesNonNullContractAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class NullnessChecker {
              @Contract("!null -> !null")
              static @Nullable Object noOp(@Nullable Object o) { return o; }
              @Contract("null -> null")
              static @Nullable Object uselessContract(@Nullable Object o) { return o; }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String test1(@Nullable Object o1) {
                // BUG: Diagnostic contains: dereferenced expression
                return NullnessChecker.noOp(o1).toString();
              }
              String test2(Object o2) {
                return NullnessChecker.noOp(o2).toString();
              }
              Object test3(@Nullable Object o1) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return NullnessChecker.noOp(o1);
              }
              Object test4(Object o4) {
                // still get a report here since the @Contract annotation doesn't help
                // BUG: Diagnostic contains: returning @Nullable expression
                return NullnessChecker.uselessContract(o4);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void malformedContractAnnotations() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            class Test {
              @Contract("!null -> -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object foo(@Nullable Object o) { return o; }
              @Contract("!null -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object bar(@Nullable Object o, String s) { return o; }
              @Contract("jabberwocky -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object baz(@Nullable Object o) { return o; }
              @Contract("!null -> -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object dontcare(@Nullable Object o) { return o; }
              // don't report errors about invalid contract annotations at calls to these methods
              static Object test1() {
                // BUG: Diagnostic contains: returning @Nullable expression
                return foo(null);
              }
              static Object test2() {
                // BUG: Diagnostic contains: returning @Nullable expression
                return bar(null, "");
              }
              static Object test3() {
                // BUG: Diagnostic contains: returning @Nullable expression
                return baz(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void malformedNonJetbrainsContracts() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CustomContractAnnotations=com.example.library.CustomContract"))
        .addSourceLines(
            "CustomContract.java",
            """
            package com.example.library;
            import static java.lang.annotation.RetentionPolicy.CLASS;
            import java.lang.annotation.Retention;
            @Retention(CLASS)
            public @interface CustomContract {
              String value();
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import com.example.library.CustomContract;
            class Test {
              @Target(ElementType.METHOD)
              public @interface Contract {
                  String value();
              }
              @Contract("!null -> -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object foo(@Nullable Object o) { return o; }
              @Contract("!null -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object bar(@Nullable Object o, String s) { return o; }
              @Contract("jabberwocky -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object baz(@Nullable Object o) { return o; }
              @Contract("!null -> -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object dontcare(@Nullable Object o) { return o; }
              @CustomContract("!null -> -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object foo2(@Nullable Object o) { return o; }
              @CustomContract("!null -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object bar2(@Nullable Object o, String s) { return o; }
              @CustomContract("jabberwocky -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object baz2(@Nullable Object o) { return o; }
              @CustomContract("!null -> -> !null")
              // BUG: Diagnostic contains: Invalid @Contract annotation
              static @Nullable Object dontcare2(@Nullable Object o) { return o; }
            }
            """)
        .doTest();
  }

  @Test
  public void contractNonVarArg() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class NullnessChecker {
              @Contract("null -> fail")
              static void assertNonNull(@Nullable Object o) { if (o != null) throw new Error(); }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              void test(java.util.function.Function<Object, Object> fun) {
                NullnessChecker.assertNonNull(fun.apply(new Object()));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void contractPureOnlyIgnored() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "PureLibrary.java",
            """
            package com.example.library;
            import org.jetbrains.annotations.Contract;
            public class PureLibrary {
              @Contract(
                pure = true
              )
              public static String pi() {
                // Meh, close enough...
                return Double.toString(3.14);
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.example.library.PureLibrary;
            import javax.annotation.Nullable;
            class Test {
              String piValue() {
                String pi = PureLibrary.pi();
                // Note: we must trigger dataflow to ensure that
                // ContractHandler.onDataflowVisitMethodInvocation is called
                if (pi != null) {
                   return pi;
                }
                return Integer.toString(3);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void customContractAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CustomContractAnnotations=com.example.library.CustomContract",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceLines(
            "CustomContract.java",
            """
            package com.example.library;
            import static java.lang.annotation.RetentionPolicy.CLASS;
            import java.lang.annotation.Retention;
            @Retention(CLASS)
            public @interface CustomContract {
              String value();
            }
            """)
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import com.example.library.CustomContract;
            public class NullnessChecker {
              @CustomContract("_, !null -> !null")
              @Nullable
              static Object bad(Object a, @Nullable Object b) {
                if (a.hashCode() % 2 == 0) {
                  // BUG: Diagnostic contains: Method bad has @Contract
                  return null;
                }
                return new Object();
              }

              @CustomContract("_, !null -> !null")
              @Nullable
              static Object good(Object a, @Nullable Object b) {
                if (a.hashCode() % 2 == 0) {
                  return b;
                }
                return new Object();
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String test1() {
                return NullnessChecker.good("bar", "foo").toString();
              }
              String test2() {
                // BUG: Diagnostic contains: dereferenced expression
                return NullnessChecker.good("bar", null).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void contractDeclaringBothNotNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class NullnessChecker {
              @Contract("null, _ -> false; _, null -> false")
              static boolean bothNotNull(@Nullable Object o1, @Nullable Object o2) {
                // null, _ -> false
                if (o1 == null) {
                  return false;
                }
                // _, null -> false
                if (o2 == null) {
                  return false;
                }
                // Function cannot declare a contract for true
                return System.currentTimeMillis() % 100 == 0;
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String test1(@Nullable Object o) {
                return NullnessChecker.bothNotNull("", o)
                  ? o.toString()
                  // BUG: Diagnostic contains: dereferenced expression
                  : o.toString();
              }
              String test2(@Nullable Object o) {
                return NullnessChecker.bothNotNull(o, "")
                  ? o.toString()
                  // BUG: Diagnostic contains: dereferenced expression
                  : o.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void contractDeclaringEitherNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class NullnessChecker {
              @Contract("null, _ -> true; _, null -> true")
              static boolean eitherIsNullOrRandom(@Nullable Object o1, @Nullable Object o2) {
                // null, _ -> true
                if (o1 == null) {
                  return true;
                }
                // _, null -> true
                if (o2 == null) {
                  return true;
                }
                // Function cannot declare a contract for false
                return System.currentTimeMillis() % 100 == 0;
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String test1(@Nullable Object o) {
                return NullnessChecker.eitherIsNullOrRandom("", o)
                  // BUG: Diagnostic contains: dereferenced expression
                  ? o.toString()
                  : o.toString();
              }
              String test2(@Nullable Object o) {
                return NullnessChecker.eitherIsNullOrRandom(o, "")
                  // BUG: Diagnostic contains: dereferenced expression
                  ? o.toString()
                  : o.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void contractDeclaringNullOrRandomFalse() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class NullnessChecker {
              @Contract("null -> false")
              static boolean isHashCodeZero(@Nullable Object o) {
                // null -> false
                if (o == null) {
                  return false;
                }
                // Function cannot declare a contract for true
                return o.hashCode() == 0;
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              String test1(@Nullable Object o) {
                return NullnessChecker.isHashCodeZero(o)
                  ? o.toString()
                  // BUG: Diagnostic contains: dereferenced expression
                  : o.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void contractUnreachablePath() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class NullnessChecker {
              @Contract("!null -> false")
              static boolean isNull(@Nullable Object o) {
                // !null -> false
                if (o != null) {
                  return false;
                }
                return true;
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            class Test {
              String test(Object required) {
                return NullnessChecker.isNull(required)
                  ? required.toString()
                  : required.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void booleanToNotNullContract() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            @NullMarked
            class Test {
              @Contract("false -> !null")
              @Nullable String nonNullWhenPassedFalse(boolean returnNull) {
                  if (returnNull) {
                      return null;
                  }
                  return "foo";
              }
              @Contract("true -> !null")
              @Nullable String nonNullWhenPassedTrue(boolean dontReturnNull) {
                  if (dontReturnNull) {
                      return "foo";
                  }
                  return null;
              }
              void testNegative() {
                  nonNullWhenPassedFalse(false).hashCode();
                  nonNullWhenPassedTrue(true).hashCode();
              }
              void testPositive(boolean b) {
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenPassedFalse(true).hashCode();
                  // false positive expected here since we do not do boolean reasoning
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenPassedFalse(b && !b).hashCode();
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenPassedTrue(false).hashCode();
                  // false positive expected here since we do not do boolean reasoning
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenPassedFalse(b || !b).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void multiArgBooleanToNotNullContract() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            @NullMarked
            class Test {
              @Contract("false, _ -> !null")
              @Nullable String nonNullWhenFirstFalse(boolean returnNull, @Nullable String value) {
                  if (returnNull) {
                      return null;
                  }
                  return "foo";
              }
              @Contract("_, true -> !null")
              @Nullable String nonNullWhenSecondTrue(@Nullable String value, boolean dontReturnNull) {
                  if (dontReturnNull) {
                      return "bar";
                  }
                  return null;
              }
              void testNegative() {
                  nonNullWhenFirstFalse(false, null).hashCode();
                  nonNullWhenSecondTrue(null, true).hashCode();
              }
              void testPositive(boolean b) {
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenFirstFalse(true, null).hashCode();
                  // false positive expected here since we do not do boolean reasoning
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenFirstFalse(b && !b, null).hashCode();
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenSecondTrue(null, false).hashCode();
                  // false positive expected here since we do not do boolean reasoning
                  // BUG: Diagnostic contains: dereferenced expression
                  nonNullWhenSecondTrue(null, b || !b).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void checkNotNullToNotNullContract() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            @NullMarked
            class Test {
                @Contract("!null -> !null")
                public static @Nullable Integer testNegative1(@Nullable String text) {
                    if (text != null) {
                        return Integer.parseInt(text);
                    } else {
                        return null;
                    }
                }
                @Contract("!null -> !null")
                public static @Nullable Integer testPositive1(@Nullable String text) {
                    if (text == null) {
                        return Integer.valueOf(0);
                    } else {
                        // BUG: Diagnostic contains: Method testPositive1 has @Contract(!null -> !null), but this appears
                        return null;
                    }
                }
                @Contract("!null -> !null")
                public static @Nullable String testNegative2(@Nullable String value) {
                    if (value == null) return null;
                    return value;
                }
                @Contract("!null -> !null")
                public static @Nullable String testPositive2(@Nullable String value) {
                    if (value != null) {
                        // BUG: Diagnostic contains: Method testPositive2 has @Contract(!null -> !null), but this appears
                        return null;
                    }
                    return value;
                }
            }
            """)
        .doTest();
  }

  @Test
  public void checkNotNullToNotNullContractMultiLevel() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            @NullMarked
            class Test {
                @Contract("!null -> !null")
                public static @Nullable String id(@Nullable String s) { return s; }
                @Contract("!null -> !null")
                public static @Nullable String wrapper(@Nullable String s) {
                    return id(s);
                }
                @Contract("!null -> !null")
                public static @Nullable String wrapper2(@Nullable String s) {
                    String result = id(s);
                    if (result != null) {
                        return result + "wrapped";
                    }
                    return null;
                }
            }
            """)
        .doTest();
  }

  /** unrealistic cases where we should still be able to verify a contract */
  @Test
  public void checkNotNullToNotNullContractSilly() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            @NullMarked
            class Test {
                @Contract("!null -> !null")
                public static @Nullable String silly1(@Nullable String s) {
                    if (s != null) {
                        if (s == null) {
                            return null;
                        }
                        return s;
                    } else {
                        return null;
                    }
                }
                @Contract("!null -> !null")
                public static @Nullable String silly2(@Nullable String s) {
                    // we can verify this method since we check for BOTTOM anywhere in the store,
                    // including for x
                    Object x = new Object();
                    if (x == null) return null;
                    if (s != null) {
                        return s;
                    } else {
                        return null;
                    }
                }
            }
            """)
        .doTest();
  }

  @Test
  public void checkDontCrashOnVoidReturn() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            @NullMarked
            class Test {
                @Contract("!null -> !null")
                public static void foo(@Nullable String s) {
                    return;
                }
            }
            """)
        .doTest();
  }

  @Test
  public void checkReturnTernary() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            @NullMarked
            class Test {
                @Contract("!null -> !null")
                public static @Nullable String ternary(@Nullable String s) {
                    return s == null ? null : s;
                }
                @Contract("!null -> !null")
                public static @Nullable String ternaryWithParens(@Nullable String s) {
                    return (s == null ? null : ((s + "")));
                }
                @Contract("!null, _ -> !null")
                public static @Nullable String nestedTernary(@Nullable String s, boolean b) {
                    return (s != null ? (b ? s : "hi") : null);
                }
                @Contract("!null, _ -> !null")
                public static @Nullable String nestedTernaryPositive(@Nullable String s, boolean b) {
                    // BUG: Diagnostic contains: Method nestedTernaryPositive has @Contract(!null, _ -> !null), but
                    return (s != null ? (b ? s : null) : null);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void checkReturnNestedLambdaOrAnonymousClass() {
    makeTestHelperWithArgs(
            withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jetbrains.annotations.Contract;
            import java.util.function.Function;
            @NullMarked
            class Test {
                @Contract("!null -> !null")
                public static @Nullable Function<Integer,Integer> lambda(@Nullable String s) {
                    return s == null ? null : (i -> { return i + 1; });
                }
                @Contract("!null -> !null")
                public static @Nullable Function<Integer,Integer> anonymousClass(@Nullable String s) {
                    return s == null ? null : new Function<Integer,Integer>() {
                        @Override
                        public Integer apply(Integer i) {
                            return i + 1;
                        }
                    };
                }
            }
            """)
        .doTest();
  }
}
