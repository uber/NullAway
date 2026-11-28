package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.SourceOrigin;
import com.uber.nullaway.generics.ConstraintSolver.SourceSupport;
import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Actual source proofs survive nominal projections, scalar propagation and owning-call remaps. */
public class PolyNullProjectionProvenanceTests {
  @Test
  public void projectionAndScalarFailuresRetainRequiredPremises() {
    CompilationTestHelper.newInstance(ProofChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Box<A extends @Nullable Object> {}
              static Object carrier(Object value) { return value; }
              static <T extends @Nullable Object> void subtype(
                  List<Object> shape, List<@Nullable Object> actual, @Nullable Object seed) {
                carrier(shape); carrier(actual); carrier(seed);
              }
              static <T extends @Nullable Object> void supertype(
                  List<Object> shape, List<@Nullable Object> actual, @Nullable Object seed) {
                carrier(shape); carrier(actual); carrier(seed);
              }
              static <T extends @Nullable Object> void projectionConflict(
                  List<Object> shape, List<Object> actual, @Nullable Object seed) {
                carrier(shape); carrier(actual); carrier(seed);
              }
              static <T extends @Nullable Object> void scalarConflict(
                  List<Object> shape, List<Object> actual, @Nullable Object seed) {
                carrier(shape); carrier(actual); carrier(seed);
              }
              static <T extends @Nullable Object> void nonNullScalarChain(
                  Object shape, @Nullable Object actual, @Nullable Object seed) {
                carrier(shape); carrier(actual); carrier(seed);
              }
              static <T extends @Nullable Object> void factoredScalarSupport(
                  Object shape, @Nullable Object actual, @Nullable Object seed) {
                carrier(shape); carrier(actual); carrier(seed);
              }
              static <T extends @Nullable Object> void lowerConflict(
                  List<Box<String>> shape, List<Box<String>> actual, List<Box<@Nullable String>> peer) {
                carrier(shape); carrier(actual); carrier(peer);
              }
              static <T extends @Nullable Object, U extends @Nullable Object,
                  V extends @Nullable Object> void alternativeScalarCycle(
                  Object shape, @Nullable Object first, @Nullable Object second) {
                carrier(shape); carrier(first); carrier(second);
              }
            }
            """)
        .doTest();
  }

  /** Uses real solver origins rather than inferring source identity from resulting javac types. */
  @BugPattern(summary = "Checks PolyNull projection source proofs", severity = SUGGESTION)
  public static final class ProofChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {
    /** Checks source availability for direct, projected, conflicting and cyclic scalar facts. */
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
                      "NullAway:OnlyNullMarked",
                      "true",
                      "NullAway:JSpecifyMode",
                      "true",
                      "NullAway:HandleWildcardGenerics",
                      "true")));
      ConstraintSolver solver =
          new PolyNullInference.Solver(
              new ConstraintSolverImpl(analysis.getConfig(), state, analysis),
              state,
              analysis.getConfig(),
              analysis.getHandler());
      MethodInvocationTree site = invocation(tree, 0);
      var context =
          PolyNullInference.createContext(
              ASTHelpers.getSymbol(site),
              site,
              ASTHelpers.getSymbol(site).type.asMethodType(),
              ImmutableSet.of(
                  new PolyNullLocation(new PolyNullLocation.Return(), ImmutableList.of())),
              GenericsChecks.getSyntheticNullableAnnotType(state),
              state);
      Element p = context.inferenceVariable().asElement();
      Element t = method.getTypeParameters().head;
      Type shape = method.getParameters().get(0).type;
      Type actual = method.getParameters().get(1).type;
      Type seed = method.getParameters().get(2).type;
      java.util.List<Element> declarations = new ArrayList<>(method.getTypeParameters());
      declarations.add(p);
      Map<Element, Type> bounds = new LinkedHashMap<>();
      Map<Element, Type> shapes = new LinkedHashMap<>();
      for (Element declaration : declarations) {
        bounds.put(declaration, ((Type.TypeVar) declaration.asType()).getUpperBound());
        shapes.put(declaration, shape);
      }
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              site, declarations, bounds, Set.copyOf(declarations), shapes);
      Type alpha = fresh.get(t);
      Type qualifier = fresh.get(p);
      InferenceVariable poly = new InferenceVariable(p, site);
      SourceOrigin projection = origin(tree, 0, site);
      SourceOrigin input = origin(tree, 1, site);
      SourceOrigin second = origin(tree, 2, site);
      String name = tree.getName().toString();
      if (name.equals("nonNullScalarChain")) {
        solver.addSubtypeConstraint(qualifier, state.getSymtab().objectType, false, input);
        solver.addSubtypeConstraint(alpha, qualifier, false, projection);
        assertThat(solver.solve().isComplete()).isTrue();
        assertRequired(solver.nullnessSupport(new InferenceVariable(t, site)), input, projection);
        return NO_MATCH;
      }
      if (name.equals("factoredScalarSupport")) {
        solver.addSubtypeConstraintWithSupport(
            seed,
            qualifier,
            false,
            new SourceSupport.Factory().conjunction(java.util.List.of(input, projection)));
        assertThat(solver.solve().isComplete()).isTrue();
        assertRequired(solver.nullnessSupport(poly), input, projection);
        return NO_MATCH;
      }
      if (name.equals("alternativeScalarCycle")) {
        Type u = fresh.get(method.getTypeParameters().get(1));
        Type v = fresh.get(method.getTypeParameters().get(2));
        solver.addSubtypeConstraint(actual, alpha, false, input);
        solver.addSubtypeConstraint(seed, alpha, false, second);
        solver.addSubtypeConstraint(alpha, u, false, projection);
        solver.addSubtypeConstraint(u, v, false);
        solver.addSubtypeConstraint(v, u, false);
        solver.addSubtypeConstraint(v, qualifier, false);
        assertThat(solver.solve().isComplete()).isTrue();
        SourceSupport proof = solver.nullnessSupport(poly);
        assertThat(proof.availableSupport(o -> !o.equals(second))).isPresent();
        assertThat(proof.availableSupport(o -> !o.equals(input))).isPresent();
        assertThat(proof.availableSupport(o -> !o.equals(input) && !o.equals(second))).isEmpty();
        assertThat(proof.availableSupport(o -> !o.equals(projection))).isEmpty();
        return NO_MATCH;
      }
      if (name.equals("scalarConflict")) {
        solver.addSubtypeConstraint(seed, qualifier, false, second);
        UnsatisfiableConstraintsException failure =
            assertThrows(
                UnsatisfiableConstraintsException.class,
                () ->
                    solver.addSubtypeConstraint(
                        qualifier, state.getSymtab().objectType, false, projection));
        assertOwner(failure, p, site);
        assertRequired(failure.getSourceSupport(), second, projection);
        return NO_MATCH;
      }
      Type modeled =
          new Type.ClassType(
              shape.getEnclosingType(), com.sun.tools.javac.util.List.of(qualifier), shape.tsym);
      solver.addSubtypeConstraint(actual, alpha, false, input);
      if (name.equals("supertype")) {
        solver.addSubtypeConstraint(modeled, alpha, false, projection);
      } else {
        solver.addSubtypeConstraint(alpha, modeled, false, projection);
      }
      if (name.equals("projectionConflict")) {
        UnsatisfiableConstraintsException failure =
            assertThrows(
                UnsatisfiableConstraintsException.class,
                () -> {
                  solver.addSubtypeConstraint(seed, qualifier, false, second);
                  solver.solve();
                });
        assertOwner(failure, p, site);
        assertRequired(failure.getSourceSupport(), input, projection, second);
        return NO_MATCH;
      }
      if (name.equals("lowerConflict")) {
        solver.addSubtypeConstraint(seed, alpha, false, second);
        var solution = solver.solve();
        var conflict =
            solution.lowerConflicts().stream()
                .filter(c -> c.variable().equals(poly))
                .findFirst()
                .orElseThrow();
        assertRequired(conflict.first().support(), input, projection);
        assertRequired(conflict.second().support(), second, projection);
        assertThat(solution.inconsistentVariables()).contains(poly);
        return NO_MATCH;
      }
      assertThat(solver.solve().isComplete()).isTrue();
      assertRequired(solver.nullnessSupport(poly), input, projection);
      return NO_MATCH;
    }

    /** Gets each explicit source from an actual invocation argument in the compiler fixture. */
    private static MethodInvocationTree invocation(MethodTree method, int index) {
      return (MethodInvocationTree)
          ((ExpressionStatementTree) method.getBody().getStatements().get(index)).getExpression();
    }

    /** Retains original source type and owner even when the fact crosses a synthetic projection. */
    private static SourceOrigin origin(MethodTree method, int index, MethodInvocationTree owner) {
      var expression = invocation(method, index).getArguments().get(0);
      return new SourceOrigin(expression, owner, ASTHelpers.getType(expression));
    }

    /** Checks that unavailable premises cannot be replaced by flattened diagnostic context. */
    private static void assertRequired(SourceSupport proof, SourceOrigin... premises) {
      assertThat(proof).isNotNull();
      assertThat(proof.availableSupport(o -> true).orElseThrow())
          .containsAtLeastElementsIn(java.util.List.of(premises));
      for (SourceOrigin premise : premises) {
        assertThat(proof.availableSupport(o -> !o.equals(premise))).isEmpty();
      }
    }

    /** Checks that synthetic declarations and sites never escape the scalar failure contract. */
    private static void assertOwner(
        UnsatisfiableConstraintsException failure, Element p, MethodInvocationTree site) {
      assertThat(failure.getTypeVariable()).isSameInstanceAs(p);
      assertThat(failure.getInferenceSite()).isSameInstanceAs(site);
      assertThat(failure.isCausedByNonNullUpperBound()).isFalse();
    }
  }
}
