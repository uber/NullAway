package com.uber.nullaway;

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
            "  public boolean hasNullableItem() {"
                + "    return nullableItem != null;"
                + "  }"
                + "  public void runOk() {",
            "    if(!hasNullableItem()) {" + "      return;" + "    }",
            "    nullableItem.call();",
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
            "  public boolean hasNullableItem() {"
                + "    return this.nullableItem != null;"
                + "  }"
                + "  public void runOk() {",
            "    if(!hasNullableItem()) {" + "      return;" + "    }",
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
            "  public boolean hasNullableItem() {"
                + "    return nullableItem != null;"
                + "  }"
                + "  public void runOk() {",
            "    boolean check = hasNullableItem();",
            "    if(!check) {" + "      return;" + "    }",
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
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not implement",
            "  public boolean hasNullableItem() {"
                + "    return true;"
                + "  }"
                + "  public void runOk() {",
            "    if(!hasNullableItem()) {" + "      return;" + "    }",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }
}
