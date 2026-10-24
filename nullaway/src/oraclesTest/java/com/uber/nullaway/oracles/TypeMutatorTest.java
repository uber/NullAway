package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;

/**
 * Each candidate mutation, applied alone, changes the shape of one type or of the construct one
 * value passes through.
 */
public class TypeMutatorTest {

  /** Returns the text of the single-unit seed {@code text} after each candidate mutation alone. */
  private static ImmutableList<String> singleMutations(String text) {
    Seed seed = new Seed("seed", ImmutableList.of(new Source("A.java", text)));
    return TypeMutator.candidates(seed).stream()
        .map(m -> TextEdit.apply(text, List.of(m.edit())))
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void aTypeArgumentBecomesAnExtendsAndASuperWildcard() {
    assertThat(singleMutations("class A { java.util.List<String> f; }"))
        .containsAtLeast(
            "class A { java.util.List<? extends String> f; }",
            "class A { java.util.List<? super String> f; }");
  }

  @Test
  public void aBoundedWildcardBecomesItsBoundOrUnbounded() {
    assertThat(singleMutations("class A { java.util.List<? extends String> f; }"))
        .containsAtLeast(
            "class A { java.util.List<String> f; }", "class A { java.util.List<?> f; }");
  }

  @Test
  public void aMethodBecomesGenericInTheTypeItReturnsAndTakes() {
    assertThat(singleMutations("class A { String m(String s, int i) { return s; } }"))
        .contains("class A { <T__ extends String> T__ m(T__ s, int i) { return s; } }");
  }

  @Test
  public void aGenericMethodGainsTheTypeParameterAtTheEndOfItsList() {
    assertThat(singleMutations("class A { <U> String m(String s, U u) { return s; } }"))
        .contains("class A { <U, T__ extends String> T__ m(T__ s, U u) { return s; } }");
  }

  @Test
  public void explicitLambdaParametersLoseTheirTypes() {
    assertThat(
            singleMutations(
                "class A { java.util.function.BiFunction<String, String, String> f = (String a, String b) -> a; }"))
        .contains(
            "class A { java.util.function.BiFunction<String, String, String> f = (a, b) -> a; }");
  }

  @Test
  public void anImplicitLambdaParameterIsDeclaredWithVarInParentheses() {
    assertThat(
            singleMutations("class A { java.util.function.Function<String, String> f = a -> a; }"))
        .contains("class A { java.util.function.Function<String, String> f = (var a) -> a; }");
  }

  @Test
  public void aReturnedExpressionMovesIntoALambdaAnAnonymousClassOrALocalClass() {
    assertThat(singleMutations("class A { String m(String s) { return s; } }"))
        .containsAtLeast(
            "class A { String m(String s) { return ((java.util.function.Supplier<String>) () -> s).get(); } }",
            "class A { String m(String s) { return new Object() { String get() { return s; } }.get(); } }",
            "class A { String m(String s) { class L__ { String get() { return s; } } return new L__().get(); } }");
  }

  @Test
  public void aPrimitiveResultIsNeverWrappedInASupplier() {
    ImmutableList<String> mutations = singleMutations("class A { int m(int i) { return i; } }");
    assertThat(mutations)
        .contains(
            "class A { int m(int i) { return new Object() { int get() { return i; } }.get(); } }");
    assertThat(mutations)
        .doesNotContain(
            "class A { int m(int i) { return ((java.util.function.Supplier<int>) () -> i).get(); } }");
  }

  @Test
  public void anArgumentIsWrappedInAGenericCall() {
    assertThat(singleMutations("class A { void m(String s) { System.out.println(s); } }"))
        .containsAtLeast(
            "class A { void m(String s) { System.out.println(java.util.Objects.requireNonNull(s)); } }",
            "class A { void m(String s) { System.out.println(java.util.Optional.ofNullable(s).orElseThrow()); } }",
            "class A { void m(String s) { System.out.println(java.util.Collections.singletonList(s).get(0)); } }");
  }

  @Test
  public void anUnreadLastParameterBecomesVarargs() {
    assertThat(singleMutations("class A { void m(String s, Object o) { s.length(); } }"))
        .contains("class A { void m(String s, Object... o) { s.length(); } }");
  }

  @Test
  public void aLastParameterTheBodyReadsStaysAsItIs() {
    ImmutableList<String> mutations =
        singleMutations("class A { Object m(Object o) { return o; } }");
    assertThat(mutations).contains("class A { <T__ extends Object> T__ m(T__ o) { return o; } }");
    assertThat(mutations).doesNotContain("class A { Object m(Object... o) { return o; } }");
  }
}
