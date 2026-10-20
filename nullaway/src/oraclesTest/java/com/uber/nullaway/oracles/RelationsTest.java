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
}
