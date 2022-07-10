package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayThriftTests extends NullAwayTestsBase {
  @Test
  public void testThriftIsSet() {
    defaultCompilationHelper
        .addSourceLines("TBase.java", "package org.apache.thrift;", "public interface TBase {}")
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Generated implements org.apache.thrift.TBase {",
            "  public @Nullable Object id;",
            "  public boolean isFixed;",
            "  @Nullable public Object getId() { return this.id; }",
            "  // this is to ensure we don't crash on unions",
            "  public boolean isSet() { return false; }",
            "  public boolean isSetId() { return this.id != null; }",
            "  public boolean isFixed() { return this.isFixed; }",
            "  public boolean isSetIsFixed() { return false; }",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(Generated g) {",
            "    if (g.isSetId()) {",
            "      g.getId().toString();",
            "      g.id.hashCode();",
            "    }",
            "    if (g.isSetIsFixed()) {",
            "      g.isFixed();",
            "    }",
            "    if (g.isSet()) {}",
            "  }",
            "  public void testPos(Generated g) {",
            "    if (!g.isSetId()) {",
            "      // BUG: Diagnostic contains: dereferenced expression g.getId() is @Nullable",
            "      g.getId().hashCode();",
            "    } else {",
            "      g.id.toString();",
            "    }",
            "    java.util.List<Generated> l = new java.util.ArrayList<>();",
            "    if (l.get(0).isSetId()) {",
            "      l.get(0).getId().hashCode();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testThriftIsSetWithGenerics() {
    defaultCompilationHelper
        .addSourceLines(
            "TBase.java", "package org.apache.thrift;", "public interface TBase<T, F> {}")
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Generated implements org.apache.thrift.TBase<String, Integer> {",
            "  public @Nullable Object id;",
            "  @Nullable public Object getId() { return this.id; }",
            "  public boolean isSetId() { return this.id != null; }",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(Generated g) {",
            "    if (!g.isSetId()) {",
            "      return;",
            "    }",
            "    Object x = g.getId();",
            "    if (x.toString() == null) return;",
            "    g.getId().toString();",
            "    g.id.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testThriftIsSetWithArg() {
    defaultCompilationHelper
        .addSourceLines(
            "TBase.java",
            "package org.apache.thrift;",
            "public interface TBase {",
            "  boolean isSet(String fieldName);",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(org.apache.thrift.TBase tBase) {",
            "    if (tBase.isSet(\"Hello\")) {",
            "      System.out.println(\"set\");",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  /** we do not have proper support for Thrift unions yet; just checks that we don't crash */
  @Test
  public void testThriftUnion() {
    defaultCompilationHelper
        .addSourceLines(
            "TBase.java", "package org.apache.thrift;", "public interface TBase<T, F> {}")
        .addSourceLines(
            "TUnion.java",
            "package org.apache.thrift;",
            "public abstract class TUnion<T, F> implements TBase<T, F> {",
            "  protected Object value_;",
            "  public Object getFieldValue() {",
            "    return this.value_;",
            "  }",
            "}")
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Generated extends org.apache.thrift.TUnion<String, Integer> {",
            "  public Object getId() { return getFieldValue(); }",
            "  public boolean isSetId() { return true; }",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(Generated g) {",
            "    if (!g.isSetId()) {",
            "      return;",
            "    }",
            "    Object x = g.getId();",
            "    if (x.toString() == null) return;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testThriftAndCastToNonNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                // We give the following in Regexp format to test that support
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.nullaway.testdata.TestAnnot",
                "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceFile("Util.java")
        .addSourceLines("TBase.java", "package org.apache.thrift;", "public interface TBase {}")
        .addSourceLines(
            "GeneratedClass.java",
            "package com.uber.lib.unannotated;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Generated;",
            "@Generated(\"test\")",
            "public class GeneratedClass implements org.apache.thrift.TBase {",
            "  public @Nullable Object id;",
            "  @Nullable public Object getId() { return this.id; }",
            "  // this is to ensure we don't crash on unions",
            "  public boolean isSet() { return false; }",
            "  public boolean isSetId() { return this.id != null; }",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "import com.uber.lib.unannotated.GeneratedClass;",
            "public class Client {",
            "  public void testPos(GeneratedClass g) {",
            "    // g.getId() is @NonNull because it's treated as unannotated code and RestrictiveAnnotationHandler exempts it",
            "    // BUG: Diagnostic contains: passing known @NonNull parameter 'g.getId()' to CastToNonNullMethod",
            "    Object o = castToNonNull(g.getId());",
            "    o.toString();",
            "  }",
            "  public void testNeg(GeneratedClass g) {",
            "    Object o = g.getId();",
            "    o.toString();",
            "  }",
            "}")
        .doTest();
  }
}
