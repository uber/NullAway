package com.uber.nullaway;

import org.junit.Test;

public class EnsuresNonNullTests extends NullAwayTestsBase {

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
            "  public static void run() {",
            "    nullableItem.call();",
            "     ",
            "  }",
            "  @EnsuresNonNull(\"nullableItem\")",
            "  public static void test2() {",
            "    nullableItem = new Item();",
            "  }",
            "  @RequiresNonNull(\"this.nullableItem\")",
            "  // BUG: Diagnostic contains: Cannot refer to static field nullableItem using this.",
            "  public static void test() {",
            "  // BUG: Diagnostic contains:  dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "  }",
            "  @EnsuresNonNull(\"this.nullableItem\")",
            "  // BUG: Diagnostic contains: Cannot refer to static field nullableItem using this.",
            "  public static void test3() {",
            "    nullableItem = new Item();",
            "  }",
            "  @RequiresNonNull(\"nullableItem2\")",
            "  // BUG: Diagnostic contains: For @RequiresNonNull annotation, cannot find instance field nullableItem2 in class Foo",
            "  public static void test4() {",
            "  // BUG: Diagnostic contains:  dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "  }",
            "  @EnsuresNonNull(\"nullableItem2\")",
            "  // BUG: Diagnostic contains: For @EnsuresNonNull annotation, cannot find instance field nullableItem2 in class Foo",
            "  public static void test5() {",
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
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNull but fails to ensure the following fields are non-null at exit: [nullItem]",
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
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNull but fails to ensure the following fields are non-null at exit: [nullItem]",
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

  @Test
  public void ensuresNonNullWithStaticField() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Foo {",
            "  @Nullable static Bar staticNullableItem;",
            "  @EnsuresNonNull(\"staticNullableItem\")",
            "  public void initializeStaticField() {",
            "    staticNullableItem = new Bar();",
            "  }",
            "  public void useStaticField() {",
            "    initializeStaticField();",
            "    staticNullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Bar.java", "package com.uber;", "class Bar {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void requiresNonNullCallSiteErrorStaticField() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class Foo {",
            "  static @Nullable Item staticNullableItem;",
            "  ",
            "  @RequiresNonNull({\"staticNullableItem\"})",
            "  public void run() {",
            "    staticNullableItem.call();",
            "  }",
            "  ",
            "  public static void main(String[] args) {",
            "    Foo foo = new Foo();",
            "    // BUG: Diagnostic contains: Expected static field staticNullableItem to be non-null",
            "    foo.run();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void requiresNonNullStaticFieldInterpretation() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class Foo {",
            "  @Nullable static Item staticNullableItem;",
            "  ",
            "  @RequiresNonNull(\"staticNullableItem\")",
            "  public void run() {",
            "    staticNullableItem.call();",
            "    staticNullableItem = null;",
            "    // BUG: Diagnostic contains: dereferenced expression staticNullableItem is @Nullable",
            "    staticNullableItem.call();",
            "  }",
            "  ",
            "  @RequiresNonNull(\"staticNullableItem\")",
            "  public void test() {",
            "    staticNullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void requiresAndEnsuresNonNullOnSameMethod() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Foo {",
            "  @Nullable static Item field1;",
            "  @Nullable static Item field2;",
            "",
            "  @RequiresNonNull(\"field1\")",
            "  @EnsuresNonNull(\"field2\")",
            "  public void positiveEnsureNonnull() {",
            "    field1.call(); ",
            "    field2 = new Item(); ",
            "  }",
            "  @RequiresNonNull(\"field1\")",
            "  @EnsuresNonNull(\"field2\")",
            "    // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNull but fails to ensure the following fields are non-null at exit",
            "  public void negativeEnsureNonnull() {",
            "    field1.call(); ",
            "  }",
            "  @RequiresNonNull(\"field1\")",
            "  @EnsuresNonNull(\"field1\")",
            "  public void combinedPositive() {",
            "    field1.call(); ",
            "  }",
            "  @RequiresNonNull(\"field1\")",
            "  @EnsuresNonNull(\"field1\")",
            "    // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNull but fails to ensure the following fields are non-null at exit",
            "  public void combinedNegative() {",
            "    field1= null; ",
            "  }",
            "",
            "  public void positiveRequiresNonnull() {",
            "    field1 = new Item(); ",
            "    positiveEnsureNonnull(); ",
            "    combinedPositive(); ",
            "    field2.call(); ",
            "  }",
            "",
            "  public void negativeRequiresNonnull() {",
            "    // BUG: Diagnostic contains: Expected static field field1 to be non-null at call site",
            "    negativeEnsureNonnull();",
            "    // BUG: Diagnostic contains: Expected static field field1 to be non-null at call site",
            "    combinedNegative();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }
}
