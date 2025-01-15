package com.uber.nullaway;

import org.junit.Ignore;
import org.junit.Test;

public class EnsuresNonNullIfTests extends NullAwayTestsBase {

  @Test
  public void correctUse() {
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
  public void correctUse_declarationReversed() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  public int runOk() {",
            "    if(!hasNullableItem()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void correctAndIncorrectUse() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  public int runOk() {",
            "    if(hasNullableItem()) {",
            "      nullableItem.call();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void correctUse_moreComplexFlow() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
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
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  @EnsuresNonNullIf(value=\"nullableItem2\", result=true)",
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
  public void multipleEnsuresNonNullIfMethods_2() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  @EnsuresNonNullIf(value=\"nullableItem2\", result=true)",
            "  public boolean hasNullableItem2() {",
            "    return nullableItem2 != null;",
            "  }",
            "  public int runOk() {",
            "    if(hasNullableItem() && hasNullableItem2()) {",
            "      nullableItem.call();",
            "      nullableItem2.call();",
            "      return 1;",
            "    }",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void multipleEnsuresNonNullIfMethods_3() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  @EnsuresNonNullIf(value=\"nullableItem2\", result=true)",
            "  public boolean hasNullableItem2() {",
            "    return nullableItem2 != null;",
            "  }",
            "  public int runOk() {",
            "    if(hasNullableItem() || hasNullableItem2()) {",
            "      // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "      nullableItem.call();",
            "      // BUG: Diagnostic contains: dereferenced expression nullableItem2 is @Nullable",
            "      nullableItem2.call();",
            "      return 1;",
            "    }",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void multipleFieldsInSingleAnnotation() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value={\"nullableItem\", \"nullableItem2\"}, result=true)",
            "  public boolean hasNullableItems() {",
            "    return nullableItem != null && nullableItem2 != null;",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItems()) {",
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
  public void multipleFieldsInSingleAnnotation_oneNotValidated() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value={\"nullableItem\", \"nullableItem2\"}, result=true)",
            "  public boolean hasNullableItems() {",
            "    // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem2]",
            "    return nullableItem != null;",
            "  }",
            "  public int runOk() {",
            "    if(!hasNullableItems()) {",
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
  public void possibleDeferencedExpression() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  @EnsuresNonNullIf(value=\"nullableItem2\", result=true)",
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
  public void ensuresNonNullMethodUsingThis() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return this.nullableItem != null;",
            "  }",
            "  public void runOk() {",
            "    if(!hasNullableItem()) {",
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
  public void postConditions_Inheritance() {
    defaultCompilationHelper
        .addSourceLines(
            "SuperClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class SuperClass {",
            "  @Nullable Item a;",
            "  @Nullable Item b;",
            "  @EnsuresNonNullIf(value=\"a\", result=true)",
            "  public boolean hasA() {",
            "    return a != null;",
            "  }",
            "  @EnsuresNonNullIf(value=\"b\", result=true)",
            "  public boolean hasB() {",
            "    return b != null;",
            "  }",
            "  public void doSomething() {",
            "    if(hasA()) {",
            "      a.call();",
            "    }",
            "  }",
            "}")
        .addSourceLines(
            "ChildLevelOne.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class ChildLevelOne extends SuperClass {",
            "  @Nullable Item c;",
            "  @EnsuresNonNullIf(value=\"c\", result=true)",
            "  // BUG: Diagnostic contains: postcondition inheritance is violated, this method must guarantee that all fields written in the @EnsuresNonNullIf annotation of overridden method SuperClass.hasA are @NonNull at exit point as well. Fields [a] must explicitly appear as parameters at this method @EnsuresNonNullIf annotation",
            "  public boolean hasA() {",
            "    return c != null;",
            "  }",
            "  @EnsuresNonNullIf(value={\"b\", \"c\"}, result=true)",
            "  public boolean hasB() {",
            "    return b != null && c != null;",
            "  }",
            "  public void doSomething() {",
            "    if(hasB()) {",
            "      b.call();",
            "    }",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  /** Tests related to setting return=false */
  @Test
  public void setResultToFalse() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=false)",
            "  public boolean doesNotHaveNullableItem() {",
            "    return nullableItem == null;",
            "  }",
            "  public int runOk() {",
            "    if(doesNotHaveNullableItem()) {",
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
  public void setResultToFalse_multipleElements_wrongSemantics_1() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value={\"nullableItem\",\"nullableItem2\"}, result=false)",
            "  public boolean doesNotHaveNullableItem() {",
            "    // If nullableItem != null but nullableItem2 == null, then this function returns false. So returning false does not guarantee that both the fields are non-null.",
            "    // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem, nullableItem2]",
            "    return nullableItem == null && nullableItem2 == null;",
            "  }",
            "  public int runOk() {",
            "    if(doesNotHaveNullableItem()) {",
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
  public void setResultToFalse_multipleElements_correctSemantics() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value={\"nullableItem\",\"nullableItem2\"}, result=false)",
            "  public boolean nullableItemsAreNull() {",
            "    // If the function returns false, we know that neither of the fields can be null, i.e., both are non-null.",
            "    return nullableItem == null || nullableItem2 == null;",
            "  }",
            "  public int runOk() {",
            "    if(nullableItemsAreNull()) {",
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
  public void setResultToFalse_multipleElements_correctSemantics_2() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value={\"nullableItem\",\"nullableItem2\"}, result=false)",
            "  public boolean nullableItemsAreNull() {",
            "    // If the function returns false, we know that neither of the fields can be null, i.e., both are non-null.",
            "    if(nullableItem == null || nullableItem2 == null) {",
            "        return true;",
            "    } else {",
            "        return false;",
            "    }",
            "  }",
            "  public int runOk() {",
            "    if(nullableItemsAreNull()) {",
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
  public void setResultToFalse_multipleElements_correctSemantics_dereferenceFound() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @Nullable Item nullableItem2;",
            "  @EnsuresNonNullIf(value={\"nullableItem\",\"nullableItem2\"}, result=false)",
            "  public boolean nullableItemsAreNull() {",
            "    // If the function returns false, we know that neither of the fields can be null, i.e., both are non-null.",
            "    return nullableItem == null || nullableItem2 == null;",
            "  }",
            "  public int runOk() {",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
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

  /********************************************************
   * Tests related to semantic issues
   ********************************************************
   */
  @Test
  public void semanticIssues_doesntEnsureNonNullabilityOfFields() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    if(nullableItem != null) {",
            "      return false;",
            "    } else {",
            "      // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem]",
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
  public void noSemanticIssues_resultFalse() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=false)",
            "  public boolean doesNotHaveNullableItem() {",
            "    if(nullableItem != null) {",
            "      return false;",
            "    } else {",
            "      return true;",
            "    }",
            "  }",
            "  public int runOk() {",
            "    if(doesNotHaveNullableItem()) {",
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
  public void semanticIssue_combinationOfExpressionAndLiteralBoolean_2() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem]",
            "    return false || nullableItem != null;",
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
  public void semanticIssue_combinationOfExpressionAndLiteralBoolean() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem]",
            "    return true || nullableItem != null;",
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
  public void noSemanticIssue_combinationOfExpressionAndLiteralBoolean() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return true && nullableItem != null;",
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
  public void semanticIssues_methodDeclarationReversed() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  public int runOk() {",
            "    if(!hasNullableItem()) {",
            "      return 1;",
            "    }",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    if(nullableItem != null) {",
            "      return false;",
            "    } else {",
            "      // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem]",
            "      return true;",
            "    }",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void semanticIssues_ignoreLambdaReturns() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.util.function.BooleanSupplier;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    BooleanSupplier test = () -> {",
            "      return nullableItem == null;",
            "    };",
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
  public void semanticIssues_hardCodedReturnTrue() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not ensure fields [nullableItem]",
            "    return true;",
            "  }",
            "  public void runOk() {",
            "    if(!hasNullableItem()) {",
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
  public void semanticIssues_warnsIfEnsuresNonNullDoesntReturnBoolean() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  // BUG: Diagnostic contains: Method is annotated with @EnsuresNonNullIf but does not return boolean",
            "  public void hasNullableItem() {",
            "    var x = nullableItem != null;",
            "  }",
            "  public void runOk() {",
            "    hasNullableItem();",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  // These tests are ignored because currently NullAway doesn't support data-flow of local variables
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
            "  @EnsuresNonNullIf(\"nullableItem\", result=true)",
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
  @Ignore
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
  @Ignore
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
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  public void runOk() {",
            "    boolean check = !hasNullableItem();",
            "    if(check) {",
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
  public void staticFieldCorrectUse() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable static Item staticNullableItem;",
            "  @EnsuresNonNullIf(value=\"staticNullableItem\", result=true)",
            "  public static boolean hasStaticNullableItem() {",
            "    return staticNullableItem != null;",
            "  }",
            "  public static int runOk() {",
            "    if(!hasStaticNullableItem()) {",
            "      return 1;",
            "    }",
            "    staticNullableItem.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void staticFieldIncorrectUse() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNullIf;",
            "class Foo {",
            "  @Nullable static Item nullableItem;",
            "  @EnsuresNonNullIf(value=\"nullableItem\", result=true)",
            "  public boolean hasNullableItem() {",
            "    return nullableItem != null;",
            "  }",
            "  public int runOk() {",
            "    if(hasNullableItem()) {",
            "      nullableItem.call();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "    return 0;",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }
}
