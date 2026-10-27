package com.uber.nullaway.generics;

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
import com.uber.nullaway.handlers.Handler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Public-helper contract tests with compiler-attributed types, not end-to-end reproducers.
 *
 * <p>WARNING: These tests depend on javac internal APIs and its representation of intersection
 * types, wildcard captures, and type-use annotations. If a javac implementation change breaks a
 * test, recheck how the fixture types are obtained before changing the expected nullability
 * behavior. These tests deliberately avoid checks of object identity or component order.
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
          // BUG: Diagnostic contains: reordered: result=unannotated, A.argument=@Nullable, original.A.argument=@NonNull, B=unannotated, C=@Nullable, C.argument=@NonNull, original.C=@Nullable, source.A.argument=@Nullable; inputs: source=unannotated->unannotated, target=unannotated->unannotated
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
          // BUG: Diagnostic contains: ordinary: result=unannotated, A.argument=@Nullable, original.A.argument=@NonNull, B=unannotated, source.A.argument=@Nullable; inputs: source=unannotated->unannotated, target=unannotated->unannotated
          T ordinary;
        }
        """);
  }

  @Test
  public void mixedIntersectionDoesNotLeakComponentTopLevelNullabilityOntoOrdinaryTarget() {
    compile(
        """
        class Test<S extends @Nullable A<@Nullable String> & B> {
          S source;
          // BUG: Diagnostic contains: mixedTopLevel: result=unannotated, result.argument=@Nullable, original.argument=@NonNull, source.A=@Nullable, source.A.argument=@Nullable; inputs: source=unannotated->unannotated, target=unannotated->unannotated
          A<@NonNull String> mixedTopLevel;
          // BUG: Diagnostic contains: unmatched: result=@NonNull, result.argument=@NonNull, source.A.argument=@Nullable; inputs: source=unannotated->unannotated, target=@NonNull->@NonNull
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
          // BUG: Diagnostic contains: nullableCapture: result=unannotated, wildcard.argument=@Nullable, original.upperBound=unannotated, original.wildcard=unannotated, original.wildcard.argument=unannotated, upperBound=@Nullable, source.A.argument=@Nullable; inputs: source=@Nullable->@Nullable, target=unannotated->unannotated
          Box<? extends A<String>> nullableCapture;
          // BUG: Diagnostic contains: nonNullCapture: result=unannotated, wildcard.argument=@Nullable, original.upperBound=unannotated, original.wildcard=unannotated, original.wildcard.argument=unannotated, upperBound=@NonNull, source.A.argument=@Nullable; inputs: source=@NonNull->@NonNull, target=unannotated->unannotated
          Box<? extends A<String>> nonNullCapture;
        }
        """);
  }

  @Test
  public void mixedCaptureSkipsNullableSyntheticComponentAndDistinguishesExplicitAnnotations() {
    compile(
        """
        class Test<S extends @Nullable A<@Nullable String> & @NonNull B> {
          S source;
          // BUG: Diagnostic contains: mixedSynthetic: result=unannotated, wildcard.argument=@Nullable, original.upperBound=unannotated, original.wildcard=unannotated, original.wildcard.argument=unannotated, upperBound=@NonNull, source.A.argument=@Nullable; inputs: source=@NonNull->@NonNull, target=unannotated->unannotated
          Box<? extends A<String>> mixedSynthetic;
          // BUG: Diagnostic contains: explicitCapture: result=@NonNull, upperBound=unannotated, wildcard.argument=@Nullable, source.B=@NonNull, original.capture=unannotated, original.upperBound=unannotated, source.A.argument=@Nullable; inputs: source=@NonNull->@NonNull, target=unannotated->unannotated
          Box<? extends A<String>> explicitCapture;
        }
        """);
  }

  @Test
  public void explicitDeclarationBoundNarrowsNullableComputedIntersection() {
    compile(
        """
        @org.jspecify.annotations.NullMarked
        class Holder<T extends @NonNull A<@Nullable String> & B> {}
        class Test<S extends @NonNull A<@Nullable String> & B> {
          S source;
          // BUG: Diagnostic contains: narrowedCapture: result=@NonNull, computed=@Nullable, original.upperBound=@NonNull, source.A.argument=@Nullable; inputs: source=@NonNull->@NonNull, target=@Nullable->@Nullable
          Holder<?> narrowedCapture;
        }
        """);
  }

  /** Compiles one rule with expected diagnostics beside every checked fixture field. */
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

  /** Reports observable nullability from public restoration helpers, never identity or layout. */
  @BugPattern(summary = "Checks public intersection restoration contracts", severity = SUGGESTION)
  public static final class IntersectionRestorationChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {
    private static final Set<String> CASES =
        Set.of(
            "reordered",
            "ordinary",
            "mixedTopLevel",
            "unmatched",
            "nullableCapture",
            "nonNullCapture",
            "mixedSynthetic",
            "explicitCapture",
            "narrowedCapture");
    private final Config config =
        new NullAway(
                ErrorProneFlags.fromMap(
                    Map.of(
                        "NullAway:OnlyNullMarked", "true",
                        "NullAway:JSpecifyMode", "true",
                        "NullAway:HandleWildcardGenerics", "true")))
            .getConfig();

    /** Reports a diagnostic for each checked field so an unvisited fixture cannot silently pass. */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      Symbol.VarSymbol symbol = ASTHelpers.getSymbol(tree);
      String name = tree.getName().toString();
      if (symbol == null || symbol.getKind() != ElementKind.FIELD || !CASES.contains(name)) {
        return NO_MATCH;
      }
      Type source = sourceField(symbol);
      Type target = symbol.type;
      Map<String, String> observations = new LinkedHashMap<>();
      StringBuilder inputs = new StringBuilder();
      switch (name) {
        case "reordered", "ordinary" -> {
          target = ((Type.TypeVar) target).getUpperBound();
          Type result = restore(source, target, inputs);
          observe(observations, "result", result);
          observe(observations, "A.argument", argument(component(result, "A")));
          observe(observations, "original.A.argument", argument(component(target, "A")));
          observe(observations, "B", component(result, "B"));
          if (name.equals("reordered")) {
            observe(observations, "C", component(result, "C"));
            observe(observations, "C.argument", argument(component(result, "C")));
            observe(observations, "original.C", component(target, "C"));
          }
        }
        case "mixedTopLevel" -> {
          Type result = restore(source, target, inputs);
          observe(observations, "result", result);
          observe(observations, "result.argument", argument(result));
          observe(observations, "original.argument", argument(target));
          observe(observations, "source.A", component(source, "A"));
        }
        case "unmatched" -> {
          Type result = restore(source, target, inputs);
          observe(observations, "result", result);
          observe(observations, "result.argument", argument(result));
        }
        case "nullableCapture", "nonNullCapture" -> {
          Type annotation =
              name.equals("nullableCapture")
                  ? GenericsChecks.getSyntheticNullableAnnotType(state)
                  : GenericsChecks.getSyntheticNonNullAnnotType(state);
          Type annotatedSource = TypeSubstitutionUtils.typeWithAnnot(source, annotation);
          Type.CapturedType result =
              restoreCapture(annotatedSource, capture(target, state), observations, inputs);
          observe(observations, "upperBound", result.getUpperBound());
        }
        case "mixedSynthetic" -> {
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
          Type.CapturedType result =
              restoreCapture(mixed, capture(target, state), observations, inputs);
          observe(observations, "upperBound", result.getUpperBound());
        }
        case "narrowedCapture" -> {
          Type.CapturedType original = capture(target, state);
          // Deliberately construct a conflicting computed bound: ordinary javac capture conversion
          // generally retains the declaration annotations, so source alone does not isolate this
          // guard.
          Type computed =
              TypeSubstitutionUtils.typeWithAnnot(
                  original.getUpperBound(), GenericsChecks.getSyntheticNullableAnnotType(state));
          Type.CapturedType nullableCapture =
              TypeMetadataBuilder.TYPE_METADATA_BUILDER.createDetachedCapturedType(
                  original, original.wildcard, computed);
          String sourceBefore = nullness(source);
          String computedBefore = nullness(computed);
          Type result =
              GenericsUtils.effectiveWildcardUpperBound(
                  nullableCapture, state, config, new Handler() {});
          observe(observations, "result", result);
          observe(observations, "computed", computed);
          observe(observations, "original.upperBound", original.getUpperBound());
          inputs.append("source=").append(sourceBefore).append("->").append(nullness(source));
          inputs.append(", target=").append(computedBefore).append("->").append(nullness(computed));
        }
        case "explicitCapture" -> {
          Type.CapturedType captured = capture(target, state);
          Type.CapturedType result = (Type.CapturedType) restore(source, captured, inputs);
          observe(observations, "result", result);
          observe(observations, "upperBound", result.getUpperBound());
          observe(observations, "wildcard.argument", argument(result.wildcard.type));
          observe(observations, "source.B", component(source, "B"));
          observe(observations, "original.capture", captured);
          observe(observations, "original.upperBound", captured.getUpperBound());
        }
        default -> throw new AssertionError("Unrecognized case: " + name);
      }
      observe(observations, "source.A.argument", argument(component(source, "A")));
      String values =
          observations.entrySet().stream()
              .map(entry -> entry.getKey() + "=" + entry.getValue())
              .collect(Collectors.joining(", "));
      return buildDescription(tree)
          .setMessage(name + ": " + values + "; inputs: " + inputs)
          .build();
    }

    /** Restores annotations and records input top-level nullability before and after the call. */
    private Type restore(Type source, Type target, StringBuilder inputs) {
      String sourceBefore = nullness(source);
      String targetBefore = nullness(target);
      Type result =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(source, target, config);
      inputs.append("source=").append(sourceBefore).append("->").append(nullness(source));
      inputs.append(", target=").append(targetBefore).append("->").append(nullness(target));
      return result;
    }

    /** Records capture nullability and the original bound annotations alongside the result. */
    private Type.CapturedType restoreCapture(
        Type source,
        Type.CapturedType target,
        Map<String, String> observations,
        StringBuilder inputs) {
      Type.CapturedType result = (Type.CapturedType) restore(source, target, inputs);
      observe(observations, "result", result);
      observe(observations, "wildcard.argument", argument(result.wildcard.type));
      observe(observations, "original.upperBound", target.getUpperBound());
      observe(observations, "original.wildcard", target.wildcard.type);
      observe(observations, "original.wildcard.argument", argument(target.wildcard.type));
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

    /** Finds a semantic component by name, independent of ordering or an implicit Object. */
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

    /** Records a checked type's top-level nullness under its semantic role in the fixture. */
    private void observe(Map<String, String> observations, String label, Type type) {
      observations.put(label, nullness(type));
    }

    /** Describes top-level nullness, including annotations on intersection components. */
    private String nullness(Type type) {
      if (GenericsUtils.isAnnotatedAsNullable(type, config)) {
        return "@Nullable";
      }
      return GenericsUtils.isAnnotatedAsNonNull(type, config) ? "@NonNull" : "unannotated";
    }
  }
}
