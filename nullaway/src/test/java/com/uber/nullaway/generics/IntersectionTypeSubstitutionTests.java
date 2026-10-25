package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.List;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Public-helper contract tests with compiler-attributed types, not end-to-end reproducers.
 *
 * <p>WARNING: These tests depend on javac internal APIs and its representation of intersection
 * types, wildcard captures, and type-use annotations. If a javac implementation change breaks a
 * test, recheck how the fixture types are obtained before changing the asserted nullability
 * behavior. These tests deliberately avoid assertions about object identity or component order.
 */
@RunWith(JUnit4.class)
public class IntersectionTypeSubstitutionTests {

  @Test
  public void reorderedIntersectionsRestoreArgumentsAndPreserveUnmatchedComponents() {
    compile(
        """
        class Test<S extends B & A<@Nullable String>,
                   T extends @Nullable C<@NonNull String> & A<@NonNull String> & B> {
          S source;
          T reordered;
        }
        """);
  }

  @Test
  public void ordinarySourceRestoresItsMatchingIntersectionComponent() {
    compile(
        """
        class Test<T extends B & A<@NonNull String>> {
          A<@Nullable String> source;
          T ordinary;
        }
        """);
  }

  @Test
  public void mixedIntersectionDoesNotLeakComponentRootNullabilityOntoOrdinaryTarget() {
    compile(
        """
        class Test<S extends @Nullable A<@Nullable String> & B> {
          S source;
          A<@NonNull String> mixedRoot;
          @NonNull C<@NonNull String> unmatched;
        }
        """);
  }

  @Test
  public void syntheticIntersectionNullabilityConstrainsCapturedUpperBounds() {
    compile(
        """
        class Test<S extends A<@Nullable String> & B> {
          S source;
          Box<? extends A<String>> synthetic;
        }
        """);
  }

  @Test
  public void mixedCaptureSkipsNullableSyntheticComponentAndDistinguishesExplicitAnnotations() {
    compile(
        """
        class Test<S extends @Nullable A<@Nullable String> & @NonNull B> {
          S source;
          Box<? extends A<String>> mixedSynthetic;
        }
        """);
  }

  /**
   * Compiles one focused case; the checker asserts the contract while javac types are available.
   */
  private void compile(String source) {
    CompilationTestHelper.newInstance(IntersectionRestorationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            interface A<T> {}
            interface B {}
            interface C<T> {}
            class Box<T> {}
            """
                + source)
        .doTest();
  }

  /**
   * Calls only public restoration helpers and checks annotation semantics, never identity/layout.
   */
  @BugPattern(summary = "Checks public intersection restoration contracts", severity = SUGGESTION)
  public static final class IntersectionRestorationChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {
    private static final Set<String> CASES =
        Set.of("reordered", "ordinary", "mixedRoot", "unmatched", "synthetic", "mixedSynthetic");
    private final Config config =
        new NullAway(
                ErrorProneFlags.fromMap(
                    Map.of(
                        "NullAway:OnlyNullMarked", "true",
                        "NullAway:JSpecifyMode", "true",
                        "NullAway:HandleWildcardGenerics", "true")))
            .getConfig();

    /** Filters fixture fields and local variables before extracting any test-specific types. */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      Symbol.VarSymbol symbol = ASTHelpers.getSymbol(tree);
      if (symbol == null
          || symbol.getKind() != ElementKind.FIELD
          || !CASES.contains(tree.getName().toString())) {
        return NO_MATCH;
      }
      Type source = sourceField(symbol);
      Type target = symbol.type;
      switch (tree.getName().toString()) {
        case "reordered", "ordinary" -> {
          target = ((Type.TypeVar) target).getUpperBound();
          Type result = restore(source, target);
          assertThat(nullable(result)).isFalse();
          assertThat(nullable(argument(component(result, "A")))).isTrue();
          assertThat(nonNull(argument(component(target, "A")))).isTrue();
          assertThat(nullable(component(result, "B"))).isFalse();
          if (tree.getName().contentEquals("reordered")) {
            assertThat(nullable(component(result, "C"))).isTrue();
            assertThat(nonNull(argument(component(result, "C")))).isTrue();
            assertThat(nullable(component(target, "C"))).isTrue();
          }
        }
        case "mixedRoot" -> {
          Type result = restore(source, target);
          assertThat(nullable(result)).isFalse();
          assertThat(nonNull(result)).isFalse();
          assertThat(nullable(argument(result))).isTrue();
          assertThat(nonNull(argument(target))).isTrue();
          assertThat(nullable(component(source, "A"))).isTrue();
        }
        case "unmatched" -> {
          Type result = restore(source, target);
          assertThat(nullable(result)).isFalse();
          assertThat(nonNull(result)).isTrue();
          assertThat(nonNull(argument(result))).isTrue();
        }
        case "synthetic" -> {
          Type.CapturedType captured = capture(target, state);
          Type nullableSource =
              TypeSubstitutionUtils.typeWithAnnot(
                  source, GenericsChecks.getSyntheticNullableAnnotType(state));
          Type.CapturedType nullableResult = restoreCapture(nullableSource, captured);
          assertThat(nullable(nullableResult.getUpperBound())).isTrue();
          Type nonNullSource =
              TypeSubstitutionUtils.typeWithAnnot(
                  source, GenericsChecks.getSyntheticNonNullAnnotType(state));
          Type.CapturedType nonNullResult = restoreCapture(nonNullSource, captured);
          assertThat(nullable(nonNullResult.getUpperBound())).isFalse();
          assertThat(nonNull(nonNullResult.getUpperBound())).isTrue();
          assertThat(nullable(source)).isFalse();
        }
        case "mixedSynthetic" -> {
          Type.CapturedType captured = capture(target, state);
          // Synthetic annotations have no source spelling; reuse attributed, named components.
          Type mixed =
              state
                  .getTypes()
                  .makeIntersectionType(
                      List.of(
                          TypeSubstitutionUtils.typeWithAnnot(
                              component(source, "A"),
                              GenericsChecks.getSyntheticNullableAnnotType(state)),
                          TypeSubstitutionUtils.typeWithAnnot(
                              component(source, "B"),
                              GenericsChecks.getSyntheticNonNullAnnotType(state))));
          Type.CapturedType inferred = restoreCapture(mixed, captured);
          assertThat(nullable(inferred.getUpperBound())).isFalse();
          assertThat(nonNull(inferred.getUpperBound())).isTrue();
          // Explicit annotations project onto the capture, not its structural upper bound.
          Type.CapturedType explicit = (Type.CapturedType) restore(source, captured);
          assertThat(nonNull(explicit)).isTrue();
          assertThat(nonNull(explicit.getUpperBound())).isFalse();
          assertThat(nullable(explicit.getUpperBound())).isFalse();
          assertThat(nullable(argument(explicit.wildcard.type))).isTrue();
          assertThat(nonNull(component(source, "B"))).isTrue();
          assertThat(nonNull(captured)).isFalse();
          assertThat(nonNull(captured.getUpperBound())).isFalse();
        }
        default -> throw new AssertionError("Unrecognized case");
      }
      assertThat(nullable(argument(component(source, "A")))).isTrue();
      return NO_MATCH;
    }

    /**
     * Restores annotations and verifies that neither input's observable root nullability changes.
     */
    private Type restore(Type source, Type target) {
      boolean sourceNullable = nullable(source);
      boolean targetNullable = nullable(target);
      boolean sourceNonNull = nonNull(source);
      boolean targetNonNull = nonNull(target);
      Type result =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(source, target, config);
      assertThat(nullable(source)).isEqualTo(sourceNullable);
      assertThat(nullable(target)).isEqualTo(targetNullable);
      assertThat(nonNull(source)).isEqualTo(sourceNonNull);
      assertThat(nonNull(target)).isEqualTo(targetNonNull);
      return result;
    }

    /** Checks synthetic capture semantics and preservation of the original bound annotations. */
    private Type.CapturedType restoreCapture(Type source, Type.CapturedType target) {
      Type.CapturedType result = (Type.CapturedType) restore(source, target);
      assertThat(nullable(result)).isFalse();
      assertThat(nonNull(result)).isFalse();
      assertThat(nullable(argument(result.wildcard.type))).isTrue();
      assertThat(nullable(target.getUpperBound())).isFalse();
      assertThat(nonNull(target.getUpperBound())).isFalse();
      assertThat(nullable(target.wildcard.type)).isFalse();
      assertThat(nonNull(target.wildcard.type)).isFalse();
      assertThat(nullable(argument(target.wildcard.type))).isFalse();
      return result;
    }

    /** Uses javac's capture conversion on the actual wildcard field type. */
    private static Type.CapturedType capture(Type field, VisitorState state) {
      return (Type.CapturedType) state.getTypes().capture(field).getTypeArguments().head;
    }

    /** Reads the source by symbol, independent of field traversal order, unwrapping its bound. */
    private static Type sourceField(Symbol.VarSymbol target) {
      Symbol.ClassSymbol owner = (Symbol.ClassSymbol) target.owner;
      for (Symbol member : owner.members().getSymbols()) {
        if (member.getKind() == ElementKind.FIELD
            && member.getSimpleName().contentEquals("source")) {
          return member.type instanceof Type.TypeVar variable
              ? variable.getUpperBound()
              : member.type;
        }
      }
      throw new AssertionError("Missing source field");
    }

    /** Finds a semantic component by name, without assertions about ordering or implicit Object. */
    private static Type component(Type type, String name) {
      List<Type> components =
          type instanceof Type.IntersectionClassType intersection
              ? intersection.getExplicitComponents()
              : List.of(type);
      for (Type component : components) {
        if (component.tsym.getSimpleName().contentEquals(name)) {
          return component;
        }
      }
      throw new AssertionError("Missing component: " + name);
    }

    /** Reads the sole argument of the fixture's A or C type. */
    private static Type argument(Type type) {
      return type.getTypeArguments().head;
    }

    /** Queries root nullability, including intersections' component-based semantics. */
    private boolean nullable(Type type) {
      return GenericsUtils.isAnnotatedAsNullable(type, config);
    }

    /** Queries explicit non-null annotations on an ordinary type or nested argument. */
    private boolean nonNull(Type type) {
      return Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config);
    }
  }
}
