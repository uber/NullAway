package com.uber.nullaway.jdk17;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests for support of the {@code Optional.isEmpty()} API. This API was introduced in JDK 11. */
public class NullAwayOptionalEmptyTests {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationTestHelper;

  @Before
  public void setup() {
    compilationTestHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:CheckOptionalEmptiness=true"));
  }

  @Test
  public void optionalIsEmptyNegative() {
    compilationTestHelper
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since a.isEmpty() is called",
            "      if(!a.isEmpty()){",
            "         a.get().toString();",
            "       }",
            "    }",
            "  void foo2() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since a.isEmpty() is called",
            "      if(a.isEmpty()){",
            "      } else {",
            "         a.get().toString();",
            "       }",
            "    }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "      if(!b.isEmpty()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalIsEmptyPositive() {
    compilationTestHelper
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    if (a.isEmpty()) {",
            "      // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "      a.get().toString();",
            "    }",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "     if (b.isEmpty()) {",
            "       // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "       lambdaConsumer(v -> b.get().toString());",
            "     }",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalIsEmptyHandleAssertionLibraryTruthAssertThat() {
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
            "  void truthAssertThatIsEmptyIsFalse() {",
            "    Optional<Object> b = Optional.empty();",
            "    Truth.assertThat(b.isEmpty()).isTrue();  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "    b.get().toString();",
            "    Truth.assertThat(b.isEmpty()).isFalse();",
            "    b.get().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalIsEmptyHandleAssertionLibraryAssertJAssertThat() {
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
            "import org.assertj.core.api.Assertions;",
            "",
            "public class Test {",
            "  void assertJAssertThatIsEmptyIsFalse() {",
            "    Optional<Object> b = Optional.empty();",
            "    Assertions.assertThat(b.isEmpty()).isTrue();  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "    b.get().toString();",
            "    Assertions.assertThat(b.isEmpty()).isFalse();",
            "    b.get().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalIsEmptyHandleAssertionLibraryJUnitAssertions() {
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
            "  void junit4AssertFalseIsEmpty() {",
            "    Optional<Object> b = Optional.empty();",
            "    Assert.assertTrue(b.isEmpty());  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "    b.get().toString();",
            "    Assert.assertFalse(\"errormsg\", b.isEmpty());",
            "    b.get().toString();",
            "  }",
            "",
            "  void junit5AssertFalseIsEmpty() {",
            "    Optional<Object> d = Optional.empty();",
            "    Assertions.assertTrue(d.isEmpty());  // no impact",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional d",
            "    d.get().toString();",
            "    Assertions.assertFalse(d.isEmpty(), \"errormsg\");",
            "    d.get().toString();",
            "  }",
            "}")
        .doTest();
  }

  protected CompilationTestHelper makeTestHelperWithArgs(List<String> args) {
    return CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
  }
}
