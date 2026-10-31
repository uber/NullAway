package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;

/**
 * Each candidate mutation, applied alone, puts a nullness annotation at one type-use position or
 * removes one, in a form javac accepts.
 */
public class AnnotationMutatorTest {

  private static final String N = "@org.jspecify.annotations.Nullable";

  /** Returns the text of the single-unit seed {@code text} after each candidate mutation alone. */
  private static ImmutableList<String> singleMutations(String text) {
    Seed seed = new Seed("seed", ImmutableList.of(new Source("A.java", text)));
    return AnnotationMutator.candidates(seed).stream()
        .map(m -> TextEdit.apply(text, List.of(m.edit())))
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void aQualifiedTypeIsAnnotatedBeforeItsLastName() {
    assertThat(singleMutations("class A { java.util.List<String> f; }"))
        .contains("class A { java.util." + N + " List<String> f; }");
  }

  @Test
  public void aTypeArgumentIsAnnotated() {
    assertThat(singleMutations("class A { java.util.List<String> f; }"))
        .contains("class A { java.util.List<" + N + " String> f; }");
  }

  @Test
  public void anArrayIsAnnotatedAtTheElementAndAtTheArrayLevel() {
    assertThat(singleMutations("class A { void m(Object[] a) {} }"))
        .containsAtLeast(
            "class A { void m(" + N + " Object[] a) {} }",
            "class A { void m(Object " + N + "[] a) {} }");
  }

  @Test
  public void anExistingNullableIsRemoved() {
    assertThat(singleMutations("class A { @Nullable Object f; }"))
        .contains("class A {  Object f; }");
  }

  @Test
  public void aPrimitiveIsNeverAnnotated() {
    assertThat(singleMutations("class A { int f; }"))
        .containsExactly("@org.jspecify.annotations.NullMarked class A { int f; }");
  }

  @Test
  public void aLocalVariableIsNeverAnnotated() {
    assertThat(singleMutations("class A { void m() { String s = null; } }"))
        .containsExactly(
            "@org.jspecify.annotations.NullMarked class A { void m() { String s = null; } }",
            "class A { @org.jspecify.annotations.NullUnmarked void m() { String s = null; } }");
  }

  @Test
  public void aNullMarkedClassIsNotMarkedAgain() {
    assertThat(singleMutations("@NullMarked class A { int f; }"))
        .containsExactly(" class A { int f; }");
  }

  @Test
  public void anAnonymousClassIsNotMarked() {
    assertThat(singleMutations("@NullMarked class A { int h = new Object() {}.hashCode(); }"))
        .containsExactly(" class A { int h = new Object() {}.hashCode(); }");
  }
}
