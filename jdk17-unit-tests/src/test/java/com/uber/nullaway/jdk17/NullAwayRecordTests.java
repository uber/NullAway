package com.uber.nullaway.jdk17;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayRecordTests {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  @Before
  public void setup() {
    defaultCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber"));
  }

  @Test
  public void testRecordConstructorCalls() {
    defaultCompilationHelper
        .addSourceLines(
            "Records.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Records {",
            "  record Rec(Object first, @Nullable Object second) { }",
            "  public void testRecordConstructors() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Rec rec1 = new Rec(null, null);",
            "    Rec rec2 = new Rec(new Object(), null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testRecordInstanceMethodCalls() {
    defaultCompilationHelper
        .addSourceLines(
            "Records.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Records {",
            "  record Rec(Object first, @Nullable Object second) { }",
            "  public void testRecordConstructors() {",
            "    Rec rec = new Rec(new Object(), null);",
            "    rec.first().toString();",
            "    // BUG: Diagnostic contains: dereferenced expression rec.second()",
            "    rec.second().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testRecordInstanceMethods() {
    defaultCompilationHelper
        .addSourceLines(
            "Records.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Records {",
            "  record Rec(Object first, @Nullable Object second) {",
            "    Object m1() {",
            "      // BUG: Diagnostic contains: returning @Nullable expression from method",
            "      return second();",
            "    }",
            "    Object m2() {",
            "      return first();",
            "    }",
            "    void derefs() {",
            "      first().toString();",
            "      // BUG: Diagnostic contains: dereferenced expression second()",
            "      second().toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testRecordFields() {
    defaultCompilationHelper
        .addSourceLines(
            "Records.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Records {",
            "  record Rec(Object first, @Nullable Object second) {",
            "    Object m1() {",
            "      // BUG: Diagnostic contains: returning @Nullable expression from method",
            "      return second;",
            "    }",
            "    Object m2() {",
            "      return first;",
            "    }",
            "    void derefs() {",
            "      first.toString();",
            "      // BUG: Diagnostic contains: dereferenced expression second",
            "      second.toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testRecordConstructor() {
    defaultCompilationHelper
        .addSourceLines(
            "Records.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Records {",
            "  record Rec(Object first, @Nullable Object second) {",
            "    Rec {",
            "      first.toString();",
            "      // BUG: Diagnostic contains: dereferenced expression second",
            "      second.toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testRecordImplementsInterface() {
    defaultCompilationHelper
        .addSourceLines(
            "Records.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Records {",
            "  interface I1 { Object m1(); }",
            "  interface I2 { void m2(@Nullable Object x); }",
            "  record Rec1(Object first, @Nullable Object second) implements I1 {",
            "    // BUG: Diagnostic contains: method returns @Nullable, but superclass method",
            "    @Nullable public Object m1() { return second(); }",
            "  }",
            "  record Rec2(Object first, @Nullable Object second) implements I1 {",
            "    public Object m1() { return first(); }",
            "  }",
            "  record Rec3(Object first, @Nullable Object second) implements I2 {",
            "    // BUG: Diagnostic contains: parameter x is @NonNull, but parameter in superclass",
            "    public void m2(Object x) { x.toString(); }",
            "  }",
            "  record Rec4(Object first, @Nullable Object second) implements I2 {",
            "    public void m2(@Nullable Object x) { if (x != null) { x.toString(); } }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testLocalRecord() {
    defaultCompilationHelper
        .addSourceLines(
            "Records.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Records {",
            "  public void testLocalRecord() {",
            "    record Rec(Object first, @Nullable Object second) { }",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Rec rec1 = new Rec(null, null);",
            "    Rec rec2 = new Rec(new Object(), null);",
            "  }",
            "}")
        .doTest();
  }
}
