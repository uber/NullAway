package com.uber.nullaway;

import org.junit.Test;

public class NullAwayEnsuresNonNullTests extends NullAwayTestsBase {

  @Test
  public void requiresNonNullInterpretation() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @RequiresNonNull(\"nullableItem\")",
            "  public void run() {",
            "    nullableItem.call();",
            "    nullableItem = null;",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "     ",
            "  }",
            "  @RequiresNonNull(\"this.nullableItem\")",
            "  public void test() {",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  /**
   * Tests that we properly report errors for {@code @RequiresNonNull} or {@code @EnsuresNonNull}
   * annotations mentioning static fields
   */
  @Test
  public void requiresEnsuresNonNullStaticFields() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Foo {",
            "  @Nullable static Item nullableItem;",
            "  @RequiresNonNull(\"nullableItem\")",
            "  // BUG: Diagnostic contains: For @RequiresNonNull annotation, cannot find instance field",
            "  public static void run() {",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "     ",
            "  }",
            "  @RequiresNonNull(\"this.nullableItem\")",
            "  // BUG: Diagnostic contains: For @RequiresNonNull annotation, cannot find instance field",
            "  public static void test() {",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "  }",
            "  @EnsuresNonNull(\"nullableItem\")",
            "  // BUG: Diagnostic contains: For @EnsuresNonNull annotation, cannot find instance field",
            "  public static void test2() {",
            "    nullableItem = new Item();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void supportRequiresNonNullOverridingTest() {
    defaultCompilationHelper
        .addSourceLines(
            "SuperClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class SuperClass {",
            "  @Nullable Item a;",
            "  @RequiresNonNull(\"a\")",
            "  public void test0() {",
            "    a.call();",
            "  }",
            "  public void test1() {",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test2() {",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test3() {",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test4() {",
            "    a.call();",
            "  }",
            "}")
        .addSourceLines(
            "ChildLevelOne.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class ChildLevelOne extends SuperClass {",
            "  @Nullable Item b;",
            "  public void test0() { }",
            "  public void test1() {",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.call();",
            "  }",
            "  public void test2() {",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test3() {",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"b\")",
            "  // BUG: Diagnostic contains: precondition inheritance is violated, method in child class cannot have a stricter precondition than its closest overridden method, adding @requiresNonNull for fields [a] makes this method precondition stricter",
            "  public void test4() {",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.call();",
            "    b.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void ensuresNonNullInterpretation() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Foo {",
            "  @Nullable Item nullItem;",
            "  Foo foo = new Foo();",
            "  @EnsuresNonNull(\"nullItem\")",
            "  public void test1() {",
            "    nullItem = new Item();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  // BUG: Diagnostic contains: test2() is annotated with @EnsuresNonNull annotation, it indicates that all fields in the annotation parameter must be guaranteed to be nonnull at exit point. However, the method's body fails to ensure this for the following fields: [nullItem]",
            "  public void test2() {",
            "  }",
            "  @EnsuresNonNull(\"this.nullItem\")",
            "  public void test3() {",
            "    test1();",
            "  }",
            "  @EnsuresNonNull(\"other.nullItem\")",
            "  // BUG: Diagnostic contains: currently @EnsuresNonNull supports only class fields of the method receiver: other.nullItem is not supported",
            "  public void test4() {",
            "    nullItem = new Item();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  // BUG: Diagnostic contains: method: test5() is annotated with @EnsuresNonNull annotation, it indicates that all fields in the annotation parameter must be guaranteed to be nonnull at exit point. However, the method's body fails to ensure this for the following fields: [nullItem]",
            "  public void test5() {",
            "    this.foo.test1();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  public void test6() {",
            "    this.test1();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void supportEnsuresNonNullOverridingTest() {
    defaultCompilationHelper
        .addSourceLines(
            "SuperClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class SuperClass {",
            "  @Nullable Item a;",
            "  @EnsuresNonNull(\"a\")",
            "  public void test1() {",
            "    a = new Item();",
            "  }",
            "  @EnsuresNonNull(\"a\")",
            "  public void test2() {",
            "    a = new Item();",
            "  }",
            "  @EnsuresNonNull(\"a\")",
            "  public void noAnnotation() {",
            "    a = new Item();",
            "  }",
            "}")
        .addSourceLines(
            "ChildLevelOne.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class ChildLevelOne extends SuperClass {",
            "  @Nullable Item b;",
            "  @EnsuresNonNull(\"b\")",
            "  // BUG: Diagnostic contains: postcondition inheritance is violated, this method must guarantee that all fields written in the @EnsuresNonNull annotation of overridden method SuperClass.test1 are @NonNull at exit point as well. Fields [a] must explicitly appear as parameters at this method @EnsuresNonNull annotation",
            "  public void test1() {",
            "    b = new Item();",
            "  }",
            "  @EnsuresNonNull({\"b\", \"a\"})",
            "  public void test2() {",
            "    super.test2();",
            "    b = new Item();",
            "  }",
            "  // BUG: Diagnostic contains: postcondition inheritance is violated, this method must guarantee that all fields written in the @EnsuresNonNull annotation of overridden method SuperClass.noAnnotation are @NonNull at exit point as well. Fields [a] must explicitly appear as parameters at this method @EnsuresNonNull annotation",
            "  public void noAnnotation() {",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void supportEnsuresAndRequiresNonNullContract() {
    defaultCompilationHelper
        .addSourceLines(
            "Content.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Content {",
            "  @Nullable Item nullItem;",
            "  @RequiresNonNull(\"nullItem\")",
            "  public void run() {",
            "    nullItem.call();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  public void init() {",
            "    nullItem = new Item();",
            "  }",
            "  public void test1() {",
            "    init();",
            "    run();",
            "  }",
            "  public void test2() {",
            "    Content content = new Content();",
            "    content.init();",
            "    content.run();",
            "  }",
            "  public void test3() {",
            "    Content content = new Content();",
            "    init();",
            "    Content other = new Content();",
            "    other.init();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    content.run();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .addSourceLines(
            "Box.java",
            "package com.uber;",
            "class Box {",
            "  Content content = new Content();",
            "}")
        .addSourceLines(
            "Office.java",
            "package com.uber;",
            "class Office {",
            "  Box box = new Box();",
            "  public void test1() {",
            "    Office office1 = new Office();",
            "    Office office2 = new Office();",
            "    office1.box.content.init();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    office2.box.content.run();",
            "  }",
            "  public void test2() {",
            "    Office office1 = new Office();",
            "    office1.box.content.init();",
            "    office1.box.content.run();",
            "  }",
            "  public void test3() {",
            "    Box box = new Box();",
            "    this.box.content.init();",
            "    this.box.content.run();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    box.content.run();",
            "  }",
            "  public void test4(int i) {",
            "    Office office1 = new Office();",
            "    Office office2 = new Office();",
            "    boolean b = i > 10;",
            "    (b ? office1.box.content : office1.box.content).init();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    office1.box.content.run();",
            "  }",
            "}")
        .doTest();
  }
}
