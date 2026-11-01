package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Checks typed candidates and independent certification using compiler-attributed types. */
public class FullTypeBoundsTests {

  @Test
  public void typedSubstitutionsAndCertification() {
    CompilationTestHelper.newInstance(BoundsChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.ArrayList;
            import java.io.Serializable;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Test {
              @Target(ElementType.TYPE_USE) @interface Marker {}
              static class Outer<V extends @Nullable Object> { class Inner {} }
              static class Strings extends ArrayList<@Nullable String> {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void annotatedLower(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperOnly(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void variableEdges(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void missingShape(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void noEvidence(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardShape(List<?> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void capturedShape(List<?> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void conflictingInvariantBounds(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void incorporatesNestedVariable(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void nestedDependency(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void structuredCycle(List<String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void conflictingUpper(List<@Nullable String> evidence, List<String> upper) {}
              static <T extends List<String>, U extends @Nullable Object>
                  void declarationBoundMismatch(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void incompatibleArrayUpper(@Nullable String[] evidence, String[] upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void compatibleArrayUpper(String[] evidence, @Nullable String[] upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperArrayMeet(@Nullable String[] evidence, String[] upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invariantArrays(List<String[]> evidence, List<@Nullable String[]> other) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void enclosingMismatch(Outer<@Nullable String>.Inner evidence, Outer<String>.Inner upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void unresolvedArray(String[] evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void unresolvedEnclosing(Outer<String>.Inner evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperArrayMeetReversed(String[] evidence, @Nullable String[] upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperArrayBothNullable(@Nullable String[] evidence, @Nullable String[] upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void annotatedMetadata(@NonNull @Marker List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void nominalProjection(Strings evidence, List<@Nullable String> upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void conflictingNominalBounds(Strings evidence, List<String> upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void structuredCycleWithoutShape(List<String> evidence) {}
              static <T extends @Nullable Object,
                  U extends @Marker List<@Nullable String> & @Marker Serializable>
                  void intersectionMetadata(U evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void primitiveArray(int[] evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void primitiveMatrix(int[][] evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invariantNominal(List<ArrayList<String>> evidence, List<List<String>> upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invalidArrayClassUpper(String[] evidence, String upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invalidClassArrayUpper(String evidence, String[] upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invalidPrimitiveArrayUpper(long[] evidence, int[] upper) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArrayComponent(String[] evidence, Integer[] shape) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArrayComponentReversed(Integer[] evidence, String[] shape) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArgument(List<String> evidence, List<Integer> shape) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArgumentReversed(List<Integer> evidence, List<String> shape) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionEnclosing(Outer<String>.Inner evidence, Outer<Integer>.Inner shape) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionEnclosingReversed(Outer<Integer>.Inner evidence, Outer<String>.Inner shape) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void enclosingLowerConflict(Outer<@Nullable String>.Inner evidence, Outer<String>.Inner other) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void enclosingLowerConflictReversed(Outer<String>.Inner evidence, Outer<@Nullable String>.Inner other) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void provisionalAnnotations(List<String> evidence, List<@Nullable String> shape) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void erroneousShape(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardArrayShape(List<?>[] evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardEnclosingShape(Outer<?>.Inner evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void knownNullableWildcardRoot(
                      @Nullable List<? extends String> evidence,
                      @Nullable List<? extends @Nullable String> provisional) {}
            }
            """)
        .doTest();
  }

  @Test
  public void dependentDeclarationBoundsParticipateInTypedInference() {
    CompilationTestHelper.newInstance(BoundsChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object, U extends T, V extends @Nullable Object>
                  void dependentNullable(String nonNull, @Nullable String nullable) {}
              static <T extends @Nullable Object, U extends T, V extends @Nullable Object>
                  void dependentNonNull(String nonNull, String other) {}
              static <T extends @Nullable Object, U extends T, V extends @Nullable Object>
                  void dependentThroughEdge(String nonNull, @Nullable String nullable) {}
            }
            """)
        .doTest();
  }

  @Test
  public void repeatedRegistrationRetainsFreshVariablesAndConstraints() {
    CompilationTestHelper.newInstance(BoundsChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void repeatedKeepsLower(List<@Nullable String> first, List<String> later) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void repeatedKeepsUpper(List<String> first, List<@Nullable String> later) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void repeatedAddsLower(List<@Nullable String> first, List<String> later) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void repeatedKeepsEvidence(List<@Nullable String> first) {}
            }
            """)
        .doTest();
  }

  /** Checks candidate annotations and the status preventing unsafe full-type publication. */
  @BugPattern(summary = "Checks full-type bounds and scalar fallback", severity = SUGGESTION)
  public static final class BoundsChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /**
     * Runs the named scenario against fresh inference variables without changing compiler types.
     */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (method == null || method.getTypeParameters().isEmpty()) {
        return NO_MATCH;
      }
      NullAway analysis =
          new NullAway(
              ErrorProneFlags.fromMap(
                  Map.of(
                      "NullAway:OnlyNullMarked", "true",
                      "NullAway:JSpecifyMode", "true",
                      "NullAway:HandleWildcardGenerics", "true")));
      ConstraintSolver solver = new ConstraintSolverImpl(analysis.getConfig(), state, analysis);
      String rule = tree.getName().toString();
      if (rule.startsWith("dependent")) {
        checkDependentDeclaration(tree, method, solver, analysis);
        return NO_MATCH;
      }
      if (rule.startsWith("repeated")) {
        checkRepeatedRegistration(tree, method, solver, state, analysis);
        return NO_MATCH;
      }
      Type evidence = method.getParameters().head.type;
      Element t = method.getTypeParameters().get(0);
      Element u = method.getTypeParameters().get(1);
      String name = tree.getName().toString();
      if (name.equals("intersectionMetadata")) {
        evidence = ((Type.TypeVar) evidence).getUpperBound();
      }
      Type shape = name.equals("capturedShape") ? state.getTypes().capture(evidence) : evidence;
      if (name.equals("nestedDependency")) {
        shape = parameterizedLike(evidence, evidence);
      } else if (name.equals("nominalProjection")
          || name.equals("conflictingNominalBounds")
          || name.startsWith("projection")
          || name.equals("provisionalAnnotations")
          || name.equals("knownNullableWildcardRoot")) {
        shape = method.getParameters().get(1).type;
      } else if (name.equals("erroneousShape")) {
        shape = state.getSymtab().errType;
      }
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              tree,
              method.getTypeParameters(),
              Map.of(),
              Set.copyOf(method.getTypeParameters()),
              name.equals("missingShape")
                  ? Map.of()
                  : name.equals("structuredCycleWithoutShape")
                      ? Map.of(u, evidence)
                      : name.startsWith("unresolved")
                          ? Map.of(t, shape)
                          : Map.of(
                              t,
                              shape,
                              u,
                              name.equals("incorporatesNestedVariable")
                                  ? evidence.getTypeArguments().head
                                  : evidence));
      Type.TypeVar alpha = fresh.get(t);
      Type.TypeVar beta = fresh.get(u);
      Type lower = evidence;
      if (name.equals("nestedDependency")) {
        lower = parameterizedLike(evidence, beta);
        solver.addSubtypeConstraint(evidence, beta, false);
      } else if (name.startsWith("structuredCycle")) {
        lower = parameterizedLike(evidence, alpha);
      } else if (name.equals("unresolvedArray")) {
        lower =
            TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
                (Type.ArrayType) evidence, beta);
      } else if (name.equals("unresolvedEnclosing")) {
        Type enclosing = parameterizedLike(evidence.getEnclosingType(), beta);
        lower =
            TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
                evidence, enclosing, evidence.getTypeArguments());
      }
      if (name.equals("upperOnly") || name.startsWith("upperArray")) {
        solver.addSubtypeConstraint(alpha, evidence, false);
      } else if (!name.equals("noEvidence")) {
        solver.addSubtypeConstraint(lower, alpha, false);
      }
      if (method.getParameters().size() == 2
          && !name.equals("provisionalAnnotations")
          && !name.equals("knownNullableWildcardRoot")) {
        Type other = method.getParameters().get(1).type;
        if (name.equals("invariantArrays")
            || name.equals("conflictingNominalBounds")
            || name.startsWith("enclosingLowerConflict")) {
          solver.addSubtypeConstraint(other, alpha, false);
        } else {
          solver.addSubtypeConstraint(alpha, other, false);
        }
      }
      if (name.equals("variableEdges")) {
        solver.addSubtypeConstraint(alpha, beta, false);
        solver.addSubtypeConstraint(beta, alpha, false);
      } else if (name.equals("conflictingInvariantBounds")) {
        Type nonNullEvidence =
            TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
                evidence,
                evidence.getEnclosingType(),
                com.sun.tools.javac.util.List.of(state.getSymtab().stringType));
        solver.addSubtypeConstraint(nonNullEvidence, alpha, false);
      } else if (name.equals("incorporatesNestedVariable")) {
        // Incorporation derives @Nullable String <: beta from both structured bounds.
        Type upper =
            TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
                evidence, evidence.getEnclosingType(), com.sun.tools.javac.util.List.of(beta));
        solver.addSubtypeConstraint(alpha, upper, false);
      }
      ConstraintSolver.Solution solution = solver.solve();
      Map<InferenceVariable, Type> fullTypes = solution.inferredTypes();
      InferenceVariable target = new InferenceVariable(t, tree);
      assertThat(fullTypes).containsKey(target);
      assertThat(fullTypes).containsKey(new InferenceVariable(u, tree));
      if (name.equals("knownNullableWildcardRoot")) {
        Type candidate = fullTypes.get(target);
        assertThat(solver.knownNullness(target)).isEqualTo(com.uber.nullaway.Nullness.NULLABLE);
        assertThat(GenericsUtils.isAnnotatedAsNullable(candidate, analysis.getConfig())).isTrue();
        Type targetType =
            TypeSubstitutionUtils.removeNullableAnnotation(evidence, analysis.getConfig());
        Type.WildcardType nested = (Type.WildcardType) targetType.getTypeArguments().head;
        Type rootOnly =
            TypeSubstitutionUtils.typeWithAnnot(
                ((Symbol) t).type, GenericsChecks.getSyntheticNullableAnnotType(state));
        Type updated =
            TypeSubstitutionUtils.updateTypeWithInferredNullability(
                targetType, ((Symbol) t).type, Map.of(t, rootOnly), state, analysis.getConfig());
        assertThat(GenericsUtils.isAnnotatedAsNullable(updated, analysis.getConfig())).isTrue();
        assertThat(updated.getTypeArguments().head).isSameInstanceAs(nested);
        Type omitted =
            TypeSubstitutionUtils.updateTypeWithInferredNullability(
                targetType, ((Symbol) t).type, Map.of(), state, analysis.getConfig());
        assertThat(GenericsUtils.isAnnotatedAsNullable(omitted, analysis.getConfig())).isFalse();
        if (!solution.incompleteVariables().contains(target)
            && !solution.inconsistentVariables().contains(target)) {
          Type.WildcardType checked = (Type.WildcardType) candidate.getTypeArguments().head;
          assertThat(GenericsUtils.isAnnotatedAsNullable(checked.type, analysis.getConfig()))
              .isFalse();
        }
        Type unchecked =
            TypeSubstitutionUtils.updateTypeWithInferredNullability(
                targetType, ((Symbol) t).type, Map.of(t, shape), state, analysis.getConfig());
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    ((Type.WildcardType) unchecked.getTypeArguments().head).type,
                    analysis.getConfig()))
            .isTrue();
        assertThat(GenericsUtils.isAnnotatedAsNullable(nested.type, analysis.getConfig()))
            .isFalse();
        return NO_MATCH;
      }
      if (name.equals("intersectionMetadata")) {
        Type inferred = fullTypes.get(target);
        assertThat(inferred).isInstanceOf(Type.IntersectionClassType.class);
        assertThat(solution.isComplete()).isTrue();
        for (Type component : ((Type.IntersectionClassType) inferred).getExplicitComponents()) {
          assertThat(
                  component.getAnnotationMirrors().stream()
                      .map(annotation -> annotation.type.toString()))
              .contains("Test.Marker");
          assertThat(GenericsUtils.isAnnotatedAsNonNull(component, analysis.getConfig())).isTrue();
          if (!component.getTypeArguments().isEmpty()) {
            assertThat(
                    GenericsUtils.isAnnotatedAsNullable(
                        component.getTypeArguments().head, analysis.getConfig()))
                .isTrue();
          }
        }
      } else if (name.equals("annotatedLower")
          || name.equals("upperOnly")
          || name.equals("variableEdges")
          || name.equals("nestedDependency")
          || name.equals("compatibleArrayUpper")
          || name.startsWith("upperArray")
          || name.equals("annotatedMetadata")
          || name.equals("nominalProjection")
          || name.equals("incorporatesNestedVariable")
          || name.equals("primitiveArray")
          || name.equals("primitiveMatrix")
          || name.equals("provisionalAnnotations")) {
        Type inferred = fullTypes.get(new InferenceVariable(t, tree));
        assertThat(inferred).isNotNull();
        assertThat(state.getTypes().isSameType(inferred, shape)).isTrue();
        assertThat(solution.incompleteVariables()).doesNotContain(target);
        assertThat(solution.inconsistentVariables()).doesNotContain(target);
        assertThat(inferred.getAnnotationMirrors())
            .hasSize(name.equals("annotatedMetadata") ? 2 : 1);
        if (name.equals("annotatedMetadata")) {
          assertThat(
                  inferred.getAnnotationMirrors().stream()
                      .map(annotation -> annotation.type.toString()))
              .contains("Test.Marker");
          assertThat(evidence.getAnnotationMirrors()).hasSize(2);
        }
        if (inferred instanceof Type.ArrayType array) {
          assertThat(GenericsUtils.isAnnotatedAsNullable(array.elemtype, analysis.getConfig()))
              .isEqualTo(name.equals("upperArrayBothNullable"));
          assertThat(array.elemtype.getAnnotationMirrors())
              .hasSize(array.elemtype.isPrimitive() ? 0 : 1);
        } else {
          Type argument = inferred.getTypeArguments().head;
          if (name.equals("nestedDependency")) {
            argument = argument.getTypeArguments().head;
            assertThat(fullTypes).containsKey(new InferenceVariable(u, tree));
          }
          assertThat(GenericsUtils.isAnnotatedAsNullable(argument, analysis.getConfig()))
              .isEqualTo(!name.equals("provisionalAnnotations"));
          assertThat(argument.getAnnotationMirrors()).hasSize(1);
        }
        if (name.equals("variableEdges")) {
          assertThat(fullTypes).containsKey(new InferenceVariable(u, tree));
        }
        if (name.equals("provisionalAnnotations")) {
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      shape.getTypeArguments().head, analysis.getConfig()))
              .isTrue();
          Type published =
              TypeSubstitutionUtils.updateTypeWithInferredNullability(
                  shape, ((Symbol) t).type, Map.of(t, inferred), state, analysis.getConfig());
          assertThat(state.getTypes().isSameType(published, shape)).isTrue();
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      published.getTypeArguments().head, analysis.getConfig()))
              .isFalse();
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      shape.getTypeArguments().head, analysis.getConfig()))
              .isTrue();
        }
      } else if (name.equals("missingShape")
          || name.equals("capturedShape")
          || name.equals("erroneousShape")
          || name.equals("structuredCycleWithoutShape")
          || name.startsWith("unresolved")) {
        assertThat(solution.incompleteVariables()).contains(target);
      } else if (name.equals("noEvidence")
          || name.equals("wildcardShape")
          || name.equals("wildcardArrayShape")
          || name.equals("wildcardEnclosingShape")) {
        assertThat(solution.inconsistentVariables()).doesNotContain(target);
        assertThat(state.getTypes().isSameType(fullTypes.get(target), shape)).isTrue();
      } else if (name.startsWith("projection")
          || name.startsWith("invalid")
          || name.equals("invariantNominal")) {
        assertThat(
                solution.incompleteVariables().contains(target)
                    || solution.inconsistentVariables().contains(target))
            .isTrue();
      } else {
        assertThat(solution.inconsistentVariables()).contains(target);
      }
      return NO_MATCH;
    }

    /** Checks declaration-root edges rather than publishing A's unchecked scalar fallback. */
    private static void checkDependentDeclaration(
        MethodTree tree, Symbol.MethodSymbol method, ConstraintSolver solver, NullAway analysis) {
      var declarations = method.getTypeParameters();
      Element t = declarations.get(0);
      Element u = declarations.get(1);
      Element v = declarations.get(2);
      Type nonNull = method.getParameters().get(0).type;
      Type other = method.getParameters().get(1).type;
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              tree,
              declarations,
              Map.of(),
              Set.copyOf(declarations),
              Map.of(t, nonNull, u, nonNull, v, nonNull));
      solver.addSubtypeConstraint(nonNull, fresh.get(t), false);
      solver.addSubtypeConstraint(fresh.get(t), nonNull, false);
      boolean throughEdge = tree.getName().contentEquals("dependentThroughEdge");
      solver.addSubtypeConstraint(other, fresh.get(throughEdge ? v : u), false);
      if (throughEdge) {
        solver.addSubtypeConstraint(fresh.get(v), fresh.get(u), false);
      } else if (tree.getName().contentEquals("dependentNonNull")) {
        solver.addSubtypeConstraint(fresh.get(u), nonNull, false);
      }
      if (!tree.getName().contentEquals("dependentNonNull")) {
        assertThrows(ConstraintSolver.UnsatisfiableConstraintsException.class, solver::solve);
      } else {
        ConstraintSolver.Solution result = solver.solve();
        assertThat(result.isComplete()).isTrue();
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    result.inferredTypes().get(new InferenceVariable(t, tree)),
                    analysis.getConfig()))
            .isFalse();
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    result.inferredTypes().get(new InferenceVariable(u, tree)),
                    analysis.getConfig()))
            .isFalse();
      }
    }

    /** Keeps both visits' constraints, stable symbols and the earlier attributed shape. */
    private static void checkRepeatedRegistration(
        MethodTree tree,
        Symbol.MethodSymbol method,
        ConstraintSolver solver,
        VisitorState state,
        NullAway analysis) {
      Element t = method.getTypeParameters().get(0);
      Element u = method.getTypeParameters().get(1);
      Type first = method.getParameters().get(0).type;
      Map<Element, Type> shapes = Map.of(t, first, u, first);
      Map<Element, Type.TypeVar> initial =
          solver.registerInferenceVariables(
              tree,
              method.getTypeParameters(),
              Map.of(),
              Set.copyOf(method.getTypeParameters()),
              shapes);
      if (tree.getName().contentEquals("repeatedKeepsUpper")) {
        solver.addSubtypeConstraint(initial.get(t), first, false);
      } else {
        solver.addSubtypeConstraint(first, initial.get(t), false);
      }
      Map<Element, Type.TypeVar> repeated =
          solver.registerInferenceVariables(
              tree,
              method.getTypeParameters(),
              Map.of(),
              Set.copyOf(method.getTypeParameters()),
              Map.of());
      assertThat(repeated.get(t)).isSameInstanceAs(initial.get(t));
      assertThat(repeated.get(u)).isSameInstanceAs(initial.get(u));
      assertThat(
              solver
                  .registerInferenceVariables(
                      tree,
                      method.getTypeParameters(),
                      Map.of(),
                      Set.copyOf(method.getTypeParameters()),
                      shapes)
                  .get(t))
          .isSameInstanceAs(initial.get(t));
      if (method.getParameters().size() == 2) {
        Type later = method.getParameters().get(1).type;
        if (tree.getName().contentEquals("repeatedKeepsLower")) {
          solver.addSubtypeConstraint(repeated.get(t), later, false);
        } else {
          solver.addSubtypeConstraint(later, repeated.get(t), false);
        }
      }
      ConstraintSolver.Solution result = solver.solve();
      InferenceVariable target = new InferenceVariable(t, tree);
      if (tree.getName().contentEquals("repeatedKeepsEvidence")) {
        Type inferred = result.inferredTypes().get(target);
        assertThat(state.getTypes().isSameType(inferred, first)).isTrue();
        assertThat(result.incompleteVariables()).doesNotContain(target);
        assertThat(result.inconsistentVariables()).doesNotContain(target);
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    inferred.getTypeArguments().head, analysis.getConfig()))
            .isTrue();
      } else {
        assertThat(result.inconsistentVariables()).contains(target);
      }
    }

    /** Reuses a real class type's nominal shape with one substituted type argument. */
    private static Type parameterizedLike(Type template, Type argument) {
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          template, template.getEnclosingType(), com.sun.tools.javac.util.List.of(argument));
    }
  }
}
