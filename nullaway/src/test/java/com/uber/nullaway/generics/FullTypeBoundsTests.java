package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.VerifyException;
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
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Checks full substitutions and top-level-only results using javac-attributed types. */
public class FullTypeBoundsTests {

  @Test
  public void supportedSubstitutionsAndTopLevelOnlyFallback() {
    CompilationTestHelper.newInstance(BoundsChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.ArrayList;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Test {
              @Target(ElementType.TYPE_USE) @interface Marker {}
              static class Outer<V extends @Nullable Object> { class Inner {} }
              static class Strings extends ArrayList<@Nullable String> {}
              static class StringArrays extends ArrayList<String[]> {}
              static class WildcardLists extends ArrayList<List<?>> {}
              // Parameter roles select instantiation, lower, and upper types independently.
              // The capture and erroneous roles transform only the parameter carrying that role.
              // Unsupported inference with no established top-level qualifier has No result.
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void annotatedLower(List<@Nullable String> instantiation_lower) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperOnly(List<@Nullable String> instantiation_upper) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void variableEdges(List<@Nullable String> instantiation_lower_edges) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void missingInstantiation(List<@Nullable String> lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void noBounds(List<@Nullable String> instantiation) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardInstantiation(List<?> instantiation_lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void capturedInstantiation(List<?> capture_instantiation_lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void conflictingInvariantBounds(List<@Nullable String> instantiation_lower, List<String> lower) {}
              // List<@Nullable String> <: alpha <: List<beta> must not solve beta by incorporation.
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void noIncorporation(List<@Nullable String> instantiation_lower, List<U> dependency_upper) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void nestedDependency(List<@Nullable String> nested_instantiation_dependency_lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void structuredCycle(List<String> instantiation_cycle_lower) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void conflictingUpper(List<@Nullable String> instantiation_lower, List<String> upper) {}
              // BUG: Diagnostic contains: No result
              static <T extends List<String>, U extends @Nullable Object>
                  void declarationBoundMismatch(List<@Nullable String> instantiation_lower) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void incompatibleArrayUpper(@Nullable String[] instantiation_lower, String[] upper) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void compatibleArrayUpper(String[] instantiation_lower, @Nullable String[] upper) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperArrayMeet(@Nullable String[] instantiation_upper, String[] upper) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invariantArrays(List<String[]> instantiation_lower, List<@Nullable String[]> lower) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void supportedArrayArgument(List<String[]> instantiation_lower_nonNullArgument) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void subclassArrayArgumentProjection(StringArrays lower_nonNullArgument, List<String[]> instantiation) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void enclosingMismatch(Outer<@Nullable String>.Inner instantiation_lower, Outer<String>.Inner upper) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void unresolvedArray(String[] instantiation_unresolvedArray_lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void unresolvedEnclosing(Outer<String>.Inner instantiation_unresolvedEnclosing_lower) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperArrayMeetReversed(String[] instantiation_upper, @Nullable String[] upper) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void upperArrayBothNullable(@Nullable String[] instantiation_upper_nullableElement, @Nullable String[] upper) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void annotatedMetadata(@NonNull @Marker List<@Nullable String> instantiation_lower_marker) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void subclassProjection(Strings lower, List<@Nullable String> instantiation_upper) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void primitiveArray(int[] instantiation_lower) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void primitiveMatrix(int[][] instantiation_lower) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invariantClassMismatch(List<ArrayList<String>> instantiation_lower, List<List<String>> upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invalidArrayClassUpper(String[] instantiation_lower, String upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invalidClassArrayUpper(String instantiation_lower, String[] upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void invalidPrimitiveArrayUpper(long[] instantiation_lower, int[] upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArrayComponent(String[] lower, Integer[] instantiation_upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArrayComponentReversed(Integer[] lower, String[] instantiation_upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArgument(List<String> lower, List<Integer> instantiation_upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionArgumentReversed(List<Integer> lower, List<String> instantiation_upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionEnclosing(Outer<String>.Inner lower, Outer<Integer>.Inner instantiation_upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void projectionEnclosingReversed(Outer<Integer>.Inner lower, Outer<String>.Inner instantiation_upper) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void enclosingLowerConflict(Outer<@Nullable String>.Inner instantiation_lower, Outer<String>.Inner lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void enclosingLowerConflictReversed(Outer<String>.Inner instantiation_lower, Outer<@Nullable String>.Inner lower) {}
              // BUG: Diagnostic contains: Substitution
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void provisionalAnnotations(List<String> lower_publish, List<@Nullable String> instantiation) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void erroneousInstantiation(List<@Nullable String> erroneous_instantiation_lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardArrayInstantiation(List<?>[] instantiation_lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardEnclosingInstantiation(Outer<?>.Inner instantiation_lower) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void knownNullableWildcardTopLevel(
                      @Nullable List<? extends String> lower_topLevelFallback,
                      @Nullable List<? extends @Nullable String> instantiation_upper) {}

              // Unsupported javac instantiations, each with an independently resolvable lower bound.
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardInstantiationWithProperBound(List<?> instantiation, List<String> lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void capturedInstantiationWithProperBound(List<?> capture_instantiation, List<String> lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void rawInstantiationWithProperBound(List instantiation, List<String> lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void erroneousInstantiationWithProperBound(List<String> erroneous_instantiation, List<String> lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardArrayInstantiationWithProperBound(List<?>[] instantiation, List<String>[] lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void wildcardEnclosingInstantiationWithProperBound(Outer<?>.Inner instantiation, Outer<String>.Inner lower) {}

              // Supported javac instantiations, each with an independently unsupported bound.
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithWildcardLower(List<String> instantiation, List<?> lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithCapturedLower(List<String> instantiation, List<?> capture_lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithRawLower(List<String> instantiation, List lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithErroneousLower(List<String> instantiation, List<String> erroneous_lower) {}
              // The subclass itself is proper; only its projection to List exposes the wildcard.
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithProjectedWildcardLower(List<List<String>> instantiation, WildcardLists lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithWildcardArrayLower(List<String>[] instantiation, List<?>[] lower) {}
              // BUG: Diagnostic contains: No result
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithWildcardEnclosingLower(Outer<String>.Inner instantiation, Outer<?>.Inner lower) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithWildcardUpper(List<String> instantiation_lower, List<?> upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithErroneousUpper(List<String> instantiation_lower, List<String> erroneous_upper) {}
              // BUG: Diagnostic contains: TopLevelOnly
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void properInstantiationWithWildcardEnclosingUpper(Outer<String>.Inner instantiation_lower, Outer<?>.Inner upper) {}
            }
            """)
        .doTest();
  }

  @Test
  public void dependentDeclarationBoundsUseTopLevelOnlyFallback() {
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
                  void repeatedKeepsInstantiation(List<@Nullable String> first) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void repeatedReceivesInstantiation(List<@Nullable String> first) {}
            }
            """)
        .doTest();
  }

  /** Checks inferred annotations and cases where no full substitution is available. */
  @BugPattern(
      summary = "Checks full-type bounds and top-level-only fallback",
      severity = SUGGESTION)
  public static final class BoundsChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /**
     * Runs each parameter-role scenario against fresh variables without changing compiler types.
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
      String name = tree.getName().toString();
      if (name.startsWith("dependent")) {
        checkDependentDeclaration(tree, method, solver, analysis);
        return NO_MATCH;
      }
      if (name.startsWith("repeated")) {
        checkRepeatedRegistration(tree, method, solver, state, analysis);
        return NO_MATCH;
      }
      Type firstType = method.getParameters().head.type;
      Element t = method.getTypeParameters().get(0);
      Element u = method.getTypeParameters().get(1);
      Type instantiation = firstType;
      boolean hasInstantiation = false;
      for (Symbol.VarSymbol parameter : method.getParameters()) {
        if (hasRole(parameter, "instantiation")) {
          instantiation = parameterType(parameter, state);
          hasInstantiation = true;
        }
      }
      Symbol.VarSymbol firstParameter = method.getParameters().head;
      if (hasRole(firstParameter, "nested")) {
        instantiation = parameterizedLike(firstType, firstType);
      }
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              tree,
              method.getTypeParameters(),
              hasInstantiation ? Map.of(t, instantiation, u, firstType) : Map.of());
      Type.TypeVar alpha = fresh.get(t);
      Type.TypeVar beta = fresh.get(u);
      Type lower = firstType;
      if (hasRole(firstParameter, "dependency")) {
        lower = parameterizedLike(firstType, beta);
        solver.addSubtypeConstraint(firstType, beta, false);
      } else if (hasRole(firstParameter, "cycle")) {
        lower = parameterizedLike(firstType, alpha);
      } else if (hasRole(firstParameter, "unresolvedArray")) {
        lower =
            TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
                (Type.ArrayType) firstType, beta);
      } else if (hasRole(firstParameter, "unresolvedEnclosing")) {
        Type enclosing = parameterizedLike(firstType.getEnclosingType(), beta);
        lower =
            TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
                firstType, enclosing, firstType.getTypeArguments());
      }
      for (Symbol.VarSymbol parameter : method.getParameters()) {
        if (hasRole(parameter, "lower")) {
          // Identity selects the original parameter and detects whether its lower type was rebuilt.
          @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
          Type bound =
              parameter == firstParameter && lower != firstType
                  ? lower
                  : parameterType(parameter, state);
          solver.addSubtypeConstraint(bound, alpha, false);
        }
        if (hasRole(parameter, "upper")) {
          Type upper =
              hasRole(parameter, "dependency")
                  ? parameterizedLike(parameter.type, beta)
                  : parameterType(parameter, state);
          solver.addSubtypeConstraint(alpha, upper, false);
        }
      }
      if (hasRole(firstParameter, "edges")) {
        solver.addSubtypeConstraint(alpha, beta, false);
        solver.addSubtypeConstraint(beta, alpha, false);
      }

      Map<InferenceVariable, ConstraintSolver.InferredType> solution = solver.solve();
      Map<InferenceVariable, Type> fullTypes = substitutions(solution);
      ConstraintSolver.InferredType result = solution.get(new InferenceVariable(t, tree));
      if (result instanceof ConstraintSolver.Substitution) {
        Type inferred = fullTypes.get(new InferenceVariable(t, tree));
        assertThat(inferred).isNotNull();
        assertThat(state.getTypes().isSameType(inferred, instantiation)).isTrue();
        assertThat(inferred.getAnnotationMirrors())
            .hasSize(hasRole(firstParameter, "marker") ? 2 : 1);
        if (hasRole(firstParameter, "marker")) {
          assertThat(
                  inferred.getAnnotationMirrors().stream()
                      .map(annotation -> annotation.type.toString()))
              .contains("Test.Marker");
          assertThat(firstType.getAnnotationMirrors()).hasSize(2);
        }
        if (inferred instanceof Type.ArrayType array) {
          assertThat(GenericsUtils.isAnnotatedAsNullable(array.elemtype, analysis.getConfig()))
              .isEqualTo(hasRole(firstParameter, "nullableElement"));
          assertThat(array.elemtype.getAnnotationMirrors())
              .hasSize(array.elemtype.isPrimitive() ? 0 : 1);
        } else {
          Type argument = inferred.getTypeArguments().head;
          if (hasRole(firstParameter, "nested")) {
            argument = argument.getTypeArguments().head;
            assertThat(fullTypes).containsKey(new InferenceVariable(u, tree));
          }
          assertThat(GenericsUtils.isAnnotatedAsNullable(argument, analysis.getConfig()))
              .isEqualTo(
                  !hasRole(firstParameter, "publish")
                      && !hasRole(firstParameter, "nonNullArgument"));
          assertThat(argument.getAnnotationMirrors()).hasSize(1);
          if (hasRole(firstParameter, "nonNullArgument")) {
            assertThat(argument).isInstanceOf(Type.ArrayType.class);
            Type element = ((Type.ArrayType) argument).elemtype;
            assertThat(GenericsUtils.isAnnotatedAsNullable(element, analysis.getConfig()))
                .isFalse();
            assertThat(element.getAnnotationMirrors()).hasSize(1);
          }
        }
        if (hasRole(firstParameter, "edges")) {
          assertThat(fullTypes).containsKey(new InferenceVariable(u, tree));
        }
        if (hasRole(firstParameter, "publish")) {
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      instantiation.getTypeArguments().head, analysis.getConfig()))
              .isTrue();
          Type published =
              TypeSubstitutionUtils.updateTypeWithInferredTypes(
                  instantiation,
                  ((Symbol) t).type,
                  Map.of(t, new ConstraintSolver.Substitution(inferred)),
                  state,
                  analysis.getConfig());
          assertThat(state.getTypes().isSameType(published, instantiation)).isTrue();
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      published.getTypeArguments().head, analysis.getConfig()))
              .isFalse();
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      instantiation.getTypeArguments().head, analysis.getConfig()))
              .isTrue();
        }
      } else {
        assertThat(fullTypes).isEmpty();
      }
      if (hasRole(firstParameter, "topLevelFallback")) {
        ConstraintSolver.InferredType inferred = solution.get(new InferenceVariable(t, tree));
        assertThat(inferred).isEqualTo(new ConstraintSolver.TopLevelOnly(Nullness.NULLABLE));
        Type target =
            TypeSubstitutionUtils.removeNullableAnnotation(firstType, analysis.getConfig());
        Type.WildcardType nested = (Type.WildcardType) target.getTypeArguments().head;
        assertThat(GenericsUtils.isAnnotatedAsNullable(nested.type, analysis.getConfig()))
            .isFalse();
        Type updated =
            TypeSubstitutionUtils.updateTypeWithInferredTypes(
                target, ((Symbol) t).type, Map.of(t, inferred), state, analysis.getConfig());
        assertThat(GenericsUtils.isAnnotatedAsNullable(updated, analysis.getConfig())).isTrue();
        assertThat(updated.getTypeArguments().head).isSameInstanceAs(nested);

        Type omitted =
            TypeSubstitutionUtils.updateTypeWithInferredTypes(
                target, ((Symbol) t).type, Map.of(), state, analysis.getConfig());
        assertThat(GenericsUtils.isAnnotatedAsNullable(omitted, analysis.getConfig())).isFalse();

        assertThat(state.getTypes().isSameType(instantiation, target)).isTrue();
        Type unchecked =
            TypeSubstitutionUtils.updateTypeWithInferredTypes(
                target,
                ((Symbol) t).type,
                Map.of(t, new ConstraintSolver.Substitution(instantiation)),
                state,
                analysis.getConfig());
        Type.WildcardType uncheckedNested = (Type.WildcardType) unchecked.getTypeArguments().head;
        assertThat(GenericsUtils.isAnnotatedAsNullable(uncheckedNested.type, analysis.getConfig()))
            .isTrue();
        assertThat(GenericsUtils.isAnnotatedAsNullable(nested.type, analysis.getConfig()))
            .isFalse();
      }
      // Report on T so expectations sit immediately before each generic declaration.
      return buildDescription(tree.getTypeParameters().get(0))
          .setMessage(
              result == null
                  ? "No result"
                  : result instanceof ConstraintSolver.Substitution
                      ? "Substitution"
                      : "TopLevelOnly")
          .build();
    }

    /** Checks a fixture parameter's underscore-separated solver-input roles. */
    private static boolean hasRole(Symbol.VarSymbol parameter, String role) {
      return ("_" + parameter.getSimpleName() + "_").contains("_" + role + "_");
    }

    /**
     * Materializes a fixture input, transforming captures and errors independently of its roles.
     */
    private static Type parameterType(Symbol.VarSymbol parameter, VisitorState state) {
      if (hasRole(parameter, "erroneous")) {
        return state.getSymtab().errType;
      }
      return hasRole(parameter, "capture")
          ? state.getTypes().capture(parameter.type)
          : parameter.type;
    }

    /**
     * Declines dependent declaration roots without inferring new edges or reporting a
     * contradiction, including when another variable reaches such a declaration through an existing
     * edge.
     */
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
              tree, declarations, Map.of(t, nonNull, u, nonNull, v, nonNull));
      solver.addSubtypeConstraint(nonNull, fresh.get(t), false);
      solver.addSubtypeConstraint(fresh.get(t), nonNull, false);
      boolean throughEdge = tree.getName().contentEquals("dependentThroughEdge");
      solver.addSubtypeConstraint(other, fresh.get(throughEdge ? v : u), false);
      if (throughEdge) {
        solver.addSubtypeConstraint(fresh.get(v), fresh.get(u), false);
      } else if (tree.getName().contentEquals("dependentNonNull")) {
        solver.addSubtypeConstraint(fresh.get(u), nonNull, false);
      }
      var solution = solver.solve();
      assertThat(
              solution.get(new InferenceVariable(t, tree)).topLevelNullness(analysis.getConfig()))
          .isEqualTo(Nullness.NONNULL);
      ConstraintSolver.InferredType dependentResult = solution.get(new InferenceVariable(u, tree));
      Nullness expectedTopLevel =
          tree.getName().contentEquals("dependentNonNull") ? Nullness.NONNULL : Nullness.NULLABLE;
      assertThat(dependentResult).isEqualTo(new ConstraintSolver.TopLevelOnly(expectedTopLevel));
      assertThat(dependentResult.topLevelNullness(analysis.getConfig()))
          .isEqualTo(expectedTopLevel);
      Map<InferenceVariable, Type> full = substitutions(solution);
      assertThat(full).containsKey(new InferenceVariable(t, tree));
      assertThat(full).doesNotContainKey(new InferenceVariable(u, tree));
      if (throughEdge) {
        assertThat(full).doesNotContainKey(new InferenceVariable(v, tree));
      }
    }

    /**
     * Retains existing bounds, instantiations, and symbol identities while accepting instantiations
     * supplied only by a later registration.
     */
    private static void checkRepeatedRegistration(
        MethodTree tree,
        Symbol.MethodSymbol method,
        ConstraintSolver solver,
        VisitorState state,
        NullAway analysis) {
      Element t = method.getTypeParameters().get(0);
      Element u = method.getTypeParameters().get(1);
      Type first = method.getParameters().get(0).type;
      Map<Element, Type> instantiations = Map.of(t, first, u, first);
      boolean receivesInstantiation = tree.getName().contentEquals("repeatedReceivesInstantiation");
      Map<Element, Type.TypeVar> initial =
          solver.registerInferenceVariables(
              tree, method.getTypeParameters(), receivesInstantiation ? Map.of() : instantiations);
      if (tree.getName().contentEquals("repeatedKeepsUpper")) {
        solver.addSubtypeConstraint(initial.get(t), first, false);
      } else {
        solver.addSubtypeConstraint(first, initial.get(t), false);
      }
      // A later visit must accept newly supplied instantiations without losing earlier bounds.
      // An empty map must instead retain any previously supplied instantiations.
      Map<Element, Type.TypeVar> repeated =
          solver.registerInferenceVariables(
              tree, method.getTypeParameters(), receivesInstantiation ? instantiations : Map.of());
      assertThat(repeated.get(t)).isSameInstanceAs(initial.get(t));
      assertThat(repeated.get(u)).isSameInstanceAs(initial.get(u));
      assertThat(solver.registerInferenceVariables(tree, List.of(u, t), Map.of()))
          .isSameInstanceAs(initial);
      assertThrows(
          VerifyException.class,
          () -> solver.registerInferenceVariables(tree, List.of(t), Map.of()));
      assertThrows(
          VerifyException.class,
          () -> solver.registerInferenceVariables(tree, List.of(t, method), Map.of()));

      assertThat(
              solver
                  .registerInferenceVariables(tree, method.getTypeParameters(), instantiations)
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
      var solution = solver.solve();
      Map<InferenceVariable, Type> full = substitutions(solution);
      if (tree.getName().contentEquals("repeatedKeepsInstantiation") || receivesInstantiation) {
        assertThat(solution.get(new InferenceVariable(t, tree)))
            .isInstanceOf(ConstraintSolver.Substitution.class);
        Type inferred = full.get(new InferenceVariable(t, tree));
        assertThat(inferred).isNotNull();
        assertThat(state.getTypes().isSameType(inferred, first)).isTrue();
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    inferred.getTypeArguments().head, analysis.getConfig()))
            .isTrue();
      } else {
        assertThat(full).isEmpty();
      }
    }

    /** Extracts full substitutions, excluding top-level-only results. */
    private static Map<InferenceVariable, Type> substitutions(
        Map<InferenceVariable, ConstraintSolver.InferredType> solution) {
      Map<InferenceVariable, Type> result = new LinkedHashMap<>();
      solution.forEach(
          (variable, inferred) -> {
            if (inferred instanceof ConstraintSolver.Substitution substitution) {
              result.put(variable, substitution.type());
            }
          });
      return result;
    }

    /** Copies a class type with one replacement type argument. */
    private static Type parameterizedLike(Type template, Type argument) {
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          template, template.getEnclosingType(), com.sun.tools.javac.util.List.of(argument));
    }
  }
}
