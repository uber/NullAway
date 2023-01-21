package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayOptionalEmptinessTests extends NullAwayTestsBase {

  @Test
  public void optionalEmptinessHandlerTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since a.isPresent() is called",
            "      if(a.isPresent()){",
            "         a.get().toString();",
            "       }",
            "    }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "  @SuppressWarnings(\"NullAway.Optional\")",
            "  void SupWarn() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since suppressed",
            "      a.get().toString();",
            "    }",
            "  void SupWarn2() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since suppressed",
            "     @SuppressWarnings(\"NullAway.Optional\") String b = a.get().toString();",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "   // This tests if the suppression is not suppressing unrelated errors ",
            "  @SuppressWarnings(\"NullAway.Optional\")",
            "  void SupWarn() {",
            "    Object a = null;",
            "      // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "      a.toString();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandlerWithSingleCustomPathTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:CheckOptionalEmptinessCustomClasses=com.google.common.base.Optional"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  class ABC { Optional<Object> ob = Optional.absent();} ",
            "  void foo() {",
            "      ABC abc = new ABC();",
            "      // no error since a.isPresent() is called",
            "      if(abc.ob.isPresent()){",
            "         abc.ob.get().toString();",
            "       }",
            "    }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  class ABC { Optional<Object> ob = Optional.empty();} ",
            "  void foo() {",
            "    ABC abc = new ABC();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional abc.ob",
            "    abc.ob.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive2.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive2 {",
            "  void foo() {",
            "    Optional<Object> a = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandlerWithTwoCustomPathsTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:CheckOptionalEmptinessCustomClasses=does.not.matter.Optional,com.google.common.base.Optional"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.absent();",
            "      // no error since a.isPresent() is called",
            "      if(a.isPresent()){",
            "         a.get().toString();",
            "       }",
            "    }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive2.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive2 {",
            "  void foo() {",
            "    Optional<Object> a = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessUncheckedTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // no error since the handler is not attached",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "      if(b.isPresent()){",
            "    // no error since the handler is not attached",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessRxPositiveTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber,io.reactivex",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import io.reactivex.Observable;",
            "public class TestPositive {",
            "  private static boolean perhaps() { return Math.random() > 0.5; }",
            "  void foo(Observable<Optional<String>> observable) {",
            "     observable",
            "           .filter(optional -> optional.isPresent() || perhaps())",
            "           // BUG: Diagnostic contains: Invoking get() on possibly empty Optional optional",
            "           .map(optional -> optional.get().toString());",
            "     observable",
            "           .filter(optional -> optional.isPresent() || perhaps())",
            "           // BUG: Diagnostic contains: Invoking get() on possibly empty Optional optional",
            "           .map(optional -> optional.get())",
            "           .map(irr -> irr.toString());",
            "     }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessRxNegativeTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import io.reactivex.Observable;",
            "public class TestNegative {",
            "  private static boolean perhaps() { return Math.random() > 0.5; }",
            "  void foo(Observable<Optional<String>> observable) {",
            "     observable",
            "           .filter(optional -> optional.isPresent())",
            "           .map(optional -> optional.get().toString());",
            "     observable",
            "           .filter(optional -> optional.isPresent() && perhaps())",
            "           .map(optional -> optional.get().toString());",
            "     observable",
            "           .filter(optional -> optional.isPresent() && perhaps())",
            "           .map(optional -> optional.get());",
            "     }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandleAssertionLibraryTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber,io.reactivex",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "import static com.google.common.truth.Truth.assertThat;",
            "public class Test {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    assertThat(a.isPresent()).isTrue(); ",
            "    a.get().toString();",
            "  }",
            "  public void lambdaConsumer(Function a){",
            "    return;",
            "  }",
            "  void bar() {",
            "    Optional<Object> b = Optional.empty();",
            "    assertThat(b.isPresent()).isTrue(); ",
            "    lambdaConsumer(v -> b.get().toString());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandleAssertionLibraryTruthAssertThat() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import com.google.common.truth.Truth;",
            "",
            "public class Test {",
            "  void truthAssertThatIsPresentIsTrue() {",
            "    Optional<Object> a = Optional.empty();",
            "    Truth.assertThat(a.isPresent()).isFalse();  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "    Truth.assertThat(a.isPresent()).isTrue();",
            "    a.get().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandleAssertionLibraryAssertJAssertThat() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.HashMap;",
            "import java.util.Map;",
            "import java.util.Optional;",
            "import org.assertj.core.api.Assertions;",
            "",
            "public class Test {",
            "  void assertJAssertThatIsPresentIsTrue() {",
            "    Optional<Object> a = Optional.empty();",
            "    Assertions.assertThat(a.isPresent()).isFalse();  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "    Assertions.assertThat(a.isPresent()).isTrue();",
            "    a.get().toString();",
            "  }",
            "",
            "  void assertJAssertThatOptionalIsPresent() {",
            "    Optional<Object> c = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional c",
            "    c.get().toString();",
            "    Assertions.assertThat(c).isPresent();",
            "    c.get().toString();",
            "  }",
            "",
            "  void assertJAssertThatOptionalIsNotEmpty() {",
            "    Optional<Object> d = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional d",
            "    d.get().toString();",
            "    Assertions.assertThat(d).isNotEmpty();",
            "    d.get().toString();",
            "  }",
            "",
            "  static Optional<String> aStaticField = Optional.empty();",
            "  Optional<String> aField = Optional.empty();",
            "",
            "  void assertJAssertThatOptionalFields() {",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional aField",
            "    aField.get().toString();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional aStaticField",
            "    aStaticField.get().toString();",
            "    Assertions.assertThat(aStaticField).isPresent();",
            "    aStaticField.get().toString();",
            "    Assertions.assertThat(aField).isNotEmpty();",
            "    aField.get().toString();",
            "  }",
            "",
            "  Optional<Long> getOptional() {",
            "    return Optional.of(2L);",
            "  }",
            "",
            "  void assertJAssertThatOptionalGetter() {",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional getOptional()",
            "    long x = 2L + getOptional().get();",
            "    Assertions.assertThat(getOptional()).isPresent();",
            "    long y = 2L + getOptional().get();",
            "  }",
            "",
            "  Map<String, Optional<Integer>> getMapWithOptional() {",
            "    return new HashMap<>();",
            "  }",
            "",
            "  void assertJAssertThatMapWithOptional() {",
            "    Assertions.assertThat(getMapWithOptional().get(\"thekey\")).isNotNull();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional getMapWithOptional().get(\"thekey\")",
            "    int x = 1 + getMapWithOptional().get(\"thekey\").get();",
            "    Assertions.assertThat(getMapWithOptional().get(\"thekey\")).isPresent();",
            "    int y = 1 + getMapWithOptional().get(\"thekey\").get();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandleAssertionLibraryJUnitAssertions() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import org.junit.Assert;",
            "import org.junit.jupiter.api.Assertions;",
            "",
            "public class Test {",
            "  void junit4AssertTrueIsPresent() {",
            "    Optional<Object> a = Optional.empty();",
            "    Assert.assertFalse(a.isPresent());  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "    Assert.assertTrue(\"not present\", a.isPresent());",
            "    a.get().toString();",
            "  }",
            "",
            "  void junit5AssertTrueIsPresent() {",
            "    Optional<Object> c = Optional.empty();",
            "    Assert.assertFalse(c.isPresent());  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional c",
            "    c.get().toString();",
            "    Assertions.assertTrue(c.isPresent(), \"not present\");",
            "    c.get().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessAssignmentCheckNegativeTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    Object x = a.isPresent() ? a.get() : \"something\";",
            "    x.toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessAssignmentCheckPositiveTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    Object x = a.get();",
            "    x.toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "     lambdaConsumer(v -> {Object x = b.get();  return \"irrelevant\";});",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessContextualSuppressionTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestClassSuppression.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "@SuppressWarnings(\"NullAway.Optional\")",
            "public class TestClassSuppression {",
            "  // no error since suppressed",
            "  Function<Optional, String> lambdaField = opt -> opt.get().toString();",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // no error since suppressed",
            "    a.get().toString();",
            "  }",
            "  public void lambdaConsumer(Function a){",
            "    return;",
            "  }",
            "  void bar() {",
            "    Optional<Object> b = Optional.empty();",
            "    // no error since suppressed",
            "    lambdaConsumer(v -> b.get().toString());",
            "  }",
            "  void baz(@Nullable Object o) {",
            "    // unrelated errors not suppressed",
            "    // BUG: Diagnostic contains: dereferenced expression o is @Nullable",
            "    o.toString();",
            "  }",
            "  public static class Inner {",
            "    void foo() {",
            "      Optional<Object> a = Optional.empty();",
            "      // no error since suppressed in outer class",
            "      a.get().toString();",
            "    }",
            "  }",
            "}")
        .addSourceLines(
            "TestLambdaFieldNoSuppression.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import com.google.common.base.Function;",
            "public class TestLambdaFieldNoSuppression {",
            "  // BUG: Diagnostic contains: Invoking get() on possibly empty Optional opt",
            "  Function<Optional, String> lambdaField = opt -> opt.get().toString();",
            "}")
        .addSourceLines(
            "TestLambdaFieldWithSuppression.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import com.google.common.base.Function;",
            "public class TestLambdaFieldWithSuppression {",
            "  // no error since suppressed",
            "  @SuppressWarnings(\"NullAway.Optional\")",
            "  Function<Optional, String> lambdaField = opt -> opt.get().toString();",
            "}")
        .doTest();
  }

  @Test
  public void optionalOfMapResultTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import java.util.Optional;",
            "class Test {",
            "  private Optional<String> f(Map<String, String> map1, Map<String, String> map2) {",
            "    Optional<String> opt = Optional.ofNullable(map1.get(\"key\"));",
            "    if (!opt.isPresent()) {",
            "      return Optional.empty();",
            "    }",
            "    return map2.entrySet().stream()",
            "      .filter(entry -> entry.getValue().equals(opt.get()))",
            "      .map(Map.Entry::getKey)",
            "      .findFirst();",
            "  }",
            "}")
        .doTest();
  }
}
