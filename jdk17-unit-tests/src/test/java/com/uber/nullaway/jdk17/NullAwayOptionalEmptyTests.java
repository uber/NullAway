package com.uber.nullaway.jdk17;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
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
            "      // no error since a.isPresent() is called",
            "      if(!a.isEmpty()){",
            "         a.get().toString();",
            "       }",
            "    }",
            "  void foo2() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since a.isPresent() is called",
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
}
