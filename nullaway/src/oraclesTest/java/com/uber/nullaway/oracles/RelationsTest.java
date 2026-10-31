package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

/**
 * Each relation rewrites a seed into a program Java gives the same meaning, keeps every line on its
 * line number, and yields no variant where the rewrite would change the meaning.
 */
public class RelationsTest {

  private static ImmutableList<ImmutableList<Source>> variants(
      Relation relation, Source... sources) {
    return relation.variants(new Seed("seed", ImmutableList.copyOf(sources)));
  }

  /**
   * Returns the text of the single unit in the first variant of {@code relation} over {@code text}.
   */
  private static String rewritten(Relation relation, String text) {
    return variants(relation, new Source("A.java", text)).get(0).get(0).text();
  }

  @Test
  public void parenthesesWrapAReturnedExpressionAndACallReceiver() {
    assertThat(
            rewritten(
                SyntacticRelations.PARENTHESES,
                "class A { String f(String s) { return s.trim().trim(); } }"))
        .isEqualTo("class A { String f(String s) { return ((s.trim()).trim()); } }");
  }

  @Test
  public void nullCheckSwapExchangesTheOperandsAroundTheOperator() {
    assertThat(
            rewritten(
                SyntacticRelations.NULL_CHECK_SWAP,
                "class A { boolean f(Object o) { return o != null; } }"))
        .isEqualTo("class A { boolean f(Object o) { return null != o; } }");
  }

  @Test
  public void nullCheckNegationRewritesNotEqualsAsANegatedEquals() {
    assertThat(
            rewritten(
                SyntacticRelations.NULL_CHECK_NEGATION,
                "class A { boolean f(Object o) { return o != null; } }"))
        .isEqualTo("class A { boolean f(Object o) { return !(o == null); } }");
  }

  @Test
  public void splitDeclarationKeepsTheLineBreakAfterTheEqualsSign() {
    assertThat(
            rewritten(
                SyntacticRelations.SPLIT_DECLARATION,
                "class A {\n  void f() {\n    Object o =\n        new Object();\n  }\n}\n"))
        .isEqualTo("class A {\n  void f() {\n    Object o ; o =\n        new Object();\n  }\n}\n");
  }

  @Test
  public void splitDeclarationLeavesAnAnnotatedDeclaration() {
    assertThat(
            variants(
                SyntacticRelations.SPLIT_DECLARATION,
                new Source(
                    "A.java",
                    "class A { void f() { @SuppressWarnings(\"x\") Object o = null; } }")))
        .isEmpty();
  }

  @Test
  public void packageNullMarkedMovesTheAnnotationIntoANewPackageInfo() {
    assertThat(
            variants(
                SyntacticRelations.PACKAGE_NULL_MARKED,
                new Source("p/A.java", "package p;\n@NullMarked class A {}\n"),
                new Source("p/B.java", "package p;\n@NullMarked class B {}\n")))
        .containsExactly(
            ImmutableList.of(
                new Source("p/A.java", "package p;\n class A {}\n"),
                new Source("p/B.java", "package p;\n class B {}\n"),
                new Source(
                    "p/package-info.java", "@org.jspecify.annotations.NullMarked\npackage p;\n")));
  }

  @Test
  public void packageNullMarkedLeavesAPackageWithAnUnmarkedClass() {
    assertThat(
            variants(
                SyntacticRelations.PACKAGE_NULL_MARKED,
                new Source("p/A.java", "package p;\n@NullMarked class A {}\n"),
                new Source("p/B.java", "package p;\nclass B {}\n")))
        .isEmpty();
  }

  @Test
  public void castWrapsAnArgumentInACastToItsOwnType() {
    assertThat(
            rewritten(
                AttributedRelations.CAST,
                "class A { void g(String s) {} void f(String s) { g(s); } }"))
        .isEqualTo(
            "class A { void g(String s) {} void f(String s) { g((java.lang.String) (s)); } }");
  }

  @Test
  public void castLeavesTheResultOfAGenericMethodCall() {
    assertThat(
            rewritten(
                AttributedRelations.CAST,
                "class A { String f(String s) { return java.util.Objects.requireNonNull(s); } }"))
        .isEqualTo(
            "class A { String f(String s) { return java.util.Objects.requireNonNull((java.lang.String) (s)); } }");
  }

  @Test
  public void lambdaBecomesAnAnonymousClassOfAnInterfaceFromTheSeed() {
    assertThat(
            rewritten(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                "class A { interface F { String apply(String s); } F f = s -> s.trim(); }"))
        .isEqualTo(
            "class A { interface F { String apply(String s); } F f = new A.F() { @Override public"
                + " java.lang.String apply(java.lang.String s) { return s.trim(); } }; }");
  }

  @Test
  public void lambdaOfAnInterfaceFromAClassFileStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "class A { java.util.function.Function<String, String> f = s -> s.trim(); }")))
        .isEmpty();
  }

  @Test
  public void lambdaPassedToAGenericMethodStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "class A { interface F { String apply(String s); }"
                        + " static <T> void take(T t, F f) {} void g() { take(\"x\", s -> s); } }")))
        .isEmpty();
  }

  @Test
  public void nullCheckSwapLeavesOperandsOnDifferentLines() {
    assertThat(
            variants(
                SyntacticRelations.NULL_CHECK_SWAP,
                new Source("A.java", "class A { boolean f(Object o) { return o\n == null; } }")))
        .isEmpty();
  }

  @Test
  public void nullCheckNegationRewritesTheOperatorAndNotAnOperatorInAComment() {
    assertThat(
            rewritten(
                SyntacticRelations.NULL_CHECK_NEGATION,
                "class A { boolean f(Object o) { return o /* != */ != null; } }"))
        .isEqualTo("class A { boolean f(Object o) { return !(o /* != */ == null); } }");
  }

  @Test
  public void lambdaCallingADefaultMethodNameOfItsInterfaceStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "class A { interface F { String get(); default String foo() { return \"\"; } }"
                        + " String foo() { return null; } F f = () -> foo(); }")))
        .isEmpty();
  }

  @Test
  public void lambdaWhoseInterfaceHasOtherNullnessDefaultsStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "@org.jspecify.annotations.NullMarked class A {"
                        + " @org.jspecify.annotations.NullUnmarked interface F { Object get(); }"
                        + " F f = () -> null; }")))
        .isEmpty();
  }

  @Test
  public void lambdaWithAnAnnotatedInferredParameterStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "class A { interface F { String apply(String s); }"
                        + " F f = (@org.jspecify.annotations.Nullable var s) -> s; }")))
        .isEmpty();
  }

  @Test
  public void lambdaWithAVarParameterBecomesAnAnonymousClassWithTheDeclaredType() {
    assertThat(
            rewritten(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                "class A { interface F { String apply(String s); } F f = (var s) -> s.trim(); }"))
        .isEqualTo(
            "class A { interface F { String apply(String s); } F f = new A.F() { @Override public"
                + " java.lang.String apply(java.lang.String s) { return s.trim(); } }; }");
  }

  @Test
  public void lambdaWithExplicitParametersAndVoidResultIsRewrittenAcrossNullnessScopes() {
    assertThat(
            rewritten(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                "@org.jspecify.annotations.NullMarked class A { interface F {"
                    + " @org.jspecify.annotations.NullUnmarked void apply(Object o); }"
                    + " F f = (Object o) -> o.toString(); }"))
        .isEqualTo(
            "@org.jspecify.annotations.NullMarked class A { interface F {"
                + " @org.jspecify.annotations.NullUnmarked void apply(Object o); }"
                + " F f = new A.F() { @Override public void apply(Object o) { o.toString(); } }; }");
  }

  @Test
  public void splitDeclarationSplitsTheLastOfSeveralVariables() {
    assertThat(
            rewritten(
                SyntacticRelations.SPLIT_DECLARATION, "class A { void f() { int p = 1, q = 2; } }"))
        .isEqualTo("class A { void f() { int p = 1, q ; q = 2; } }");
  }

  @Test
  public void lambdaPassedToANonGenericMethodBecomesAnAnonymousClass() {
    assertThat(
            rewritten(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                "class A { interface F { String apply(String s); }"
                    + " static void take(F f) {} void g() { take(s -> s); } }"))
        .isEqualTo(
            "class A { interface F { String apply(String s); }"
                + " static void take(F f) {} void g() { take(new A.F() { @Override public"
                + " java.lang.String apply(java.lang.String s) { return s; } }); } }");
  }

  @Test
  public void lambdaCallingAnObjectMethodByItsSimpleNameStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "class A { interface F { String apply(String s); } F f = s -> toString(); }")))
        .isEmpty();
  }

  @Test
  public void castLeavesAParenthesizedGenericMethodCall() {
    assertThat(
            rewritten(
                AttributedRelations.CAST,
                "class A { static <T> T id(T t) { return t; } Object f(Object y) { return (id(y)); } }"))
        .isEqualTo(
            "class A { static <T> T id(T t) { return t; }"
                + " Object f(Object y) { return (id((java.lang.Object) (y))); } }");
  }

  @Test
  public void castLeavesAConditional() {
    assertThat(
            variants(
                AttributedRelations.CAST,
                new Source(
                    "A.java", "class A { String f(boolean b, String s) { return b ? s : s; } }")))
        .isEmpty();
  }

  @Test
  public void lambdaWithAnInferredReferenceParameterAcrossNullnessScopesStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "A.java",
                    "@org.jspecify.annotations.NullMarked class A {"
                        + " @org.jspecify.annotations.NullUnmarked interface F { void apply(Object o); }"
                        + " F f = o -> {}; }")))
        .isEmpty();
  }

  @Test
  public void lambdaWithAnInferredPrimitiveParameterIsRewrittenAcrossNullnessScopes() {
    assertThat(
            rewritten(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                "@org.jspecify.annotations.NullMarked class A {"
                    + " @org.jspecify.annotations.NullUnmarked interface F { void apply(int i); }"
                    + " F f = i -> {}; }"))
        .isEqualTo(
            "@org.jspecify.annotations.NullMarked class A {"
                + " @org.jspecify.annotations.NullUnmarked interface F { void apply(int i); }"
                + " F f = new A.F() { @Override public void apply(int i) {} }; }");
  }

  @Test
  public void lambdaReturningAReferenceFromAnInterfaceInAnotherPackageStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source("p/F.java", "package p; public interface F { Object get(); }"),
                new Source("q/A.java", "package q; class A { p.F f = () -> null; }")))
        .isEmpty();
  }

  @Test
  public void lambdaUnderAMarkedClassOfAMarkedPackageWithAnUnmarkedInterfaceStaysALambda() {
    assertThat(
            variants(
                AttributedRelations.LAMBDA_TO_ANONYMOUS_CLASS,
                new Source(
                    "p/package-info.java", "@org.jspecify.annotations.NullMarked package p;"),
                new Source("p/F.java", "package p; interface F { Object get(); }"),
                new Source(
                    "p/A.java",
                    "package p; @org.jspecify.annotations.NullMarked class A { F f = () -> null; }")))
        .isEmpty();
  }

  @Test
  public void parenthesesLeaveAnExpressionThatSpansLines() {
    assertThat(
            variants(
                SyntacticRelations.PARENTHESES,
                new Source("A.java", "class A { String f(String s) { return s\n.trim(); } }")))
        .isEmpty();
  }

  @Test
  public void castLeavesAnExpressionThatSpansLines() {
    assertThat(
            variants(
                AttributedRelations.CAST,
                new Source("A.java", "class A { String f(String s) { return s\n.trim(); } }")))
        .isEmpty();
  }

  @Test
  public void simpleNamesShortenATypeAndATypeUseAnnotationAndImportBothOnThePackageLine() {
    assertThat(
            rewritten(
                ImportRelations.SIMPLE_NAMES,
                """
                package p;
                class A { java.util.@org.jspecify.annotations.Nullable List<String> f; }
                """))
        .isEqualTo(
            """
            package p; import java.util.List; import org.jspecify.annotations.Nullable;
            class A { @Nullable List<String> f; }
            """);
  }

  @Test
  public void simpleNamesImportAtTheStartOfAUnitWithNoPackageOrImports() {
    assertThat(
            rewritten(
                ImportRelations.SIMPLE_NAMES,
                "class A { @org.jspecify.annotations.Nullable Object f; }"))
        .isEqualTo("import org.jspecify.annotations.Nullable; class A { @Nullable Object f; }");
  }

  @Test
  public void simpleNamesShortenOnlyOneOfTwoClassesThatShareASimpleName() {
    assertThat(
            rewritten(
                ImportRelations.SIMPLE_NAMES,
                "class A { java.util.List<String> f; java.awt.List g; }"))
        .isEqualTo("import java.util.List; class A { List<String> f; java.awt.List g; }");
  }

  @Test
  public void simpleNamesShortenTheSupertypeOfAnAnonymousClass() {
    // The rewrite threw StringIndexOutOfBoundsException on the trees javac synthesizes for it.
    assertThat(
            rewritten(
                ImportRelations.SIMPLE_NAMES,
                "class A { Object o = new java.util.ArrayList<String>() {}; }"))
        .isEqualTo(
            "import java.util.ArrayList; class A { Object o = new ArrayList<String>() {}; }");
  }

  @Test
  public void simpleNamesLeaveANameImportedFromAnotherPackage() {
    assertThat(
            variants(
                ImportRelations.SIMPLE_NAMES,
                new Source(
                    "A.java", "import java.awt.List; class A { java.util.List<String> f; }")))
        .isEmpty();
  }

  @Test
  public void simpleNamesAddNoImportForAClassTheUnitImportsAlready() {
    assertThat(
            rewritten(
                ImportRelations.SIMPLE_NAMES,
                "import java.util.List; class A { java.util.List<String> f; List<String> g; }"))
        .isEqualTo("import java.util.List; class A { List<String> f; List<String> g; }");
  }

  @Test
  public void simpleNamesLeaveANameOfATypeParameter() {
    assertThat(
            variants(
                ImportRelations.SIMPLE_NAMES,
                new Source("A.java", "class A<List> { java.util.List<String> f; }")))
        .isEmpty();
  }

  @Test
  public void simpleNamesImportOnTheFirstImportLineOfAUnitWithNoPackage() {
    assertThat(
            rewritten(
                ImportRelations.SIMPLE_NAMES,
                """
                import java.util.Map;
                class A { java.util.List<String> f; Map<String, String> m; }
                """))
        .isEqualTo(
            """
            import java.util.Map; import java.util.List;
            class A { List<String> f; Map<String, String> m; }
            """);
  }

  @Test
  public void simpleNamesShadowAClassOfTheSamePackageThatTheUnitNeverNames() {
    assertThat(
            variants(
                    ImportRelations.SIMPLE_NAMES,
                    new Source("p/List.java", "package p; class List {}"),
                    new Source("p/A.java", "package p; class A { java.util.List<String> f; }"))
                .get(0)
                .get(1)
                .text())
        .isEqualTo("package p; import java.util.List; class A { List<String> f; }");
  }

  @Test
  public void simpleNamesLeaveANameTheUnitDeclares() {
    assertThat(
            variants(
                ImportRelations.SIMPLE_NAMES,
                new Source("A.java", "class A { class List {} java.util.List<String> f; }")))
        .isEmpty();
  }

  @Test
  public void simpleNamesLeaveANameThatAFieldWouldObscure() {
    // The early checks let java.util.List through; attributing List.of() finds the field instead.
    assertThat(
            variants(
                ImportRelations.SIMPLE_NAMES,
                new Source(
                    "A.java", "class A { Object List = null; Object g = java.util.List.of(); }")))
        .isEmpty();
  }

  @Test
  public void simpleNamesLeaveANameThatAnInheritedMemberClassWouldShadow() {
    // List<String> compiles after the rewrite, but names B.List, so the references differ.
    assertThat(
            variants(
                ImportRelations.SIMPLE_NAMES,
                new Source("B.java", "class B { static class List<T> {} }"),
                new Source("A.java", "class A extends B { java.util.List<String> f; }")))
        .isEmpty();
  }

  @Test
  public void qualifiedNamesQualifyATypeAndItsTypeArgument() {
    assertThat(
            rewritten(
                ImportRelations.QUALIFIED_NAMES,
                """
                import java.util.List;
                class A { List<String> f; }
                """))
        .isEqualTo(
            """
            import java.util.List;
            class A { java.util.List<java.lang.String> f; }
            """);
  }

  @Test
  public void qualifiedNamesPutThePackageBeforeATypeUseAnnotation() {
    assertThat(
            rewritten(
                ImportRelations.QUALIFIED_NAMES,
                """
                import org.jspecify.annotations.Nullable;
                class A { java.util.List<@Nullable Object> f; }
                """))
        .isEqualTo(
            """
            import org.jspecify.annotations.Nullable;
            class A { java.util.List<java.lang.@org.jspecify.annotations.Nullable Object> f; }
            """);
  }

  @Test
  public void qualifiedNamesLeaveATypeAfterAnAnnotationInTheModifiers() {
    // @Nullable is a type-use annotation here, and java.lang.String may not follow it.
    assertThat(
            rewritten(
                ImportRelations.QUALIFIED_NAMES,
                """
                import org.jspecify.annotations.Nullable;
                class A { @Nullable String f; }
                """))
        .isEqualTo(
            """
            import org.jspecify.annotations.Nullable;
            class A { @org.jspecify.annotations.Nullable String f; }
            """);
  }

  @Test
  public void qualifiedNamesLeaveAClassOfTheUnnamedPackage() {
    assertThat(rewritten(ImportRelations.QUALIFIED_NAMES, "class B {} class A { B b; Object o; }"))
        .isEqualTo("class B {} class A { B b; java.lang.Object o; }");
  }

  @Test
  public void qualifiedNamesKeepOnlyVariantsThatAFieldNamedJavaDoesNotObscure() {
    // java.util.List.of() would read the field java; the variant that qualifies Object stays.
    ImmutableList<ImmutableList<Source>> variants =
        variants(
            ImportRelations.QUALIFIED_NAMES,
            new Source(
                "A.java",
                "import java.util.List; class A { Object java = null; Object g = List.of(); }"));
    assertThat(variants.stream().map(v -> v.get(0).text()).toList())
        .containsExactly(
            "import java.util.List; class A { java.lang.Object java = null; java.lang.Object g ="
                + " List.of(); }");
  }
}
