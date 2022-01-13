package com.uber.nullaway;

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
}
