package com.uber.nullaway;

import org.junit.Ignore;
import org.junit.Test;

public class EnsuresNonNullIfTests extends NullAwayTestsBase {

  @Test
  public void ensuresNonNullMethodCalled() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItem()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void ensuresNonNullMethodWithMoreDataComplexFlow() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {",
            "    if(nullableItem != null) {",
            "      return true;",
            "    } else {",
            "      return false;",
            "    }",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItem()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  @Ignore // fails as both stores in the Return data flow mark the field as nullable
  public void ensuresNonNullMethodWithMoreDataComplexFlow_2() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {",
            "    var x = (nullableItem != null);",
            "    return x;",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItem()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void understandsBasicReturnFlowsInEnsuresNonNullMethods() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem]",
            "  public boolean hasNullableItem() {",
            "    if(nullableItem != null) {",
            "      return false;",
            "    } else {",
            "      return true;",
            "    }",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItem()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void multipleEnsuresNonNullIfMethods() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  @EnsuresNonNullIf(\"nullableItem2\")",
            "  public boolean hasNullableItem2() {",
            "    return nullableItem2 != null;",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItem() || !hasNullableItem2()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    nullableItem2.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void missingSomeChecks() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  @EnsuresNonNullIf(\"nullableItem2\")",
            "  public boolean hasNullableItem2() {",
            "    return nullableItem2 != null;",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItem()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem2 is @Nullable",
            "    nullableItem2.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void ensuresNonNullMethodCalledUsingThis() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {",
            "    return this.nullableItem != null;",
            "  }",
            "  public void runOk() {",
            "    if(!hasNullableItem()) {",
            "      return;" + "    }",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void ensuresNonNullMethodResultStoredInVariable() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  public void runOk() {",
            "    boolean check = hasNullableItem();",
            "    if(!check) {",
            "      return;",
            "    }",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void ensuresNonNullMethodResultStoredInVariableInverse() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {"
                + "    return nullableItem != null;"
                + "  }"
                + "  public void runOk() {",
            "    boolean check = !hasNullableItem();",
            "    if(check) {" + "      return;" + "    }",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void ensuresNonNullMethodNotCalled() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  public boolean hasNullableItem() {"
                + "    return nullableItem != null;"
                + "  }"
                + "  public void runNOk() {",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void warnsIfEnsuresNonNullIfIsWrong() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem]",
            "  public boolean hasNullableItem() {",
            "    return true;",
            "  }",
            "  public void runOk() {",
            "    if(!hasNullableItem()) {",
            "      return;" + "    }",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void warnsIfEnsuresNonNullDoesntReturnBoolean() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(\"nullableItem\")",
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not return boolean",
            "  public void hasNullableItem() {",
            "    var x = nullableItem != null;",
            "  }",
            "  public void runOk() {",
            "    hasNullableItem();",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }
}
