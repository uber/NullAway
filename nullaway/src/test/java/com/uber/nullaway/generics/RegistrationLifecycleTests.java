package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
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
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
import java.util.Map;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Repeated registration preserves graph identity, bounds, shapes, and external-input ownership. */
public class RegistrationLifecycleTests {
  @Test
  public void registrationAndDependentRootsRetainTheirContracts() {
    CompilationTestHelper.newInstance(LifecycleChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object, U extends T>
                  void dependentRoot(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable T>
                  void nullableRootOverride(List<@Nullable String> evidence) {}
              static <U extends @Nullable T, T extends @Nullable Object>
                  void reverseNullableRootOverride(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void repeatedConstraints(List<@Nullable String> evidence) {}
              static <T extends @Nullable Object, U extends @Nullable Object>
                  void repeatedOwnership(List<@Nullable String> evidence) {}
            }
            """)
        .doTest();
  }

  /** Exercises the real solver with detached, call-specific variables from an attributed method. */
  @BugPattern(
      summary = "Checks registration identity and dependent roots",
      severity = BugPattern.SeverityLevel.SUGGESTION)
  public static final class LifecycleChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {
    /** Checks declaration-root and repeated-registration contracts on compiler-attributed types. */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (method == null || method.getTypeParameters().isEmpty()) {
        return Description.NO_MATCH;
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
      ConstraintSolver solver = new ConstraintSolverImpl(analysis.getConfig(), state, analysis);
      Element t =
          method.getTypeParameters().stream()
              .filter(v -> v.getSimpleName().contentEquals("T"))
              .findFirst()
              .orElseThrow();
      Element u =
          method.getTypeParameters().stream()
              .filter(v -> v.getSimpleName().contentEquals("U"))
              .findFirst()
              .orElseThrow();
      Type evidence = method.getParameters().head.type;
      String name = tree.getName().toString();
      Map<Element, Type.TypeVar> fresh;
      if (name.startsWith("repeated")) {
        ConstraintSolver outer = new ConstraintSolverImpl(analysis.getConfig(), state, analysis);
        Map<Element, Type.TypeVar> foreign =
            outer.registerInferenceVariables(tree, method.getTypeParameters());
        if (name.equals("repeatedOwnership")) {
          solver.registerExternalInferenceSymbols(outer.inferenceVariableSymbols());
        }
        fresh =
            solver.registerInferenceVariables(
                tree,
                method.getTypeParameters(),
                name.equals("repeatedOwnership") ? Map.of(t, foreign.get(t)) : Map.of());
        solver.addSubtypeConstraint(evidence, fresh.get(t), false);
        Map<Element, Type.TypeVar> again =
            solver.registerInferenceVariables(
                tree,
                method.getTypeParameters(),
                Map.of(t, evidence, u, state.getSymtab().objectType));
        assertThat(again).isSameInstanceAs(fresh);
        assertThat(again.get(t)).isSameInstanceAs(fresh.get(t));
        solver.registerInferenceVariables(tree, method.getTypeParameters(), Map.of());
        Solution result = solver.solve();
        Type inferred = result.inferredTypes().get(new InferenceVariable(t, tree));
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    inferred.getTypeArguments().head, analysis.getConfig()))
            .isTrue();
        assertThat(result.incompleteVariables()).isEmpty();
        assertThat(result.inconsistentVariables()).isEmpty();
        assertThat(result.inputDependencies().variables().contains(new InferenceVariable(t, tree)))
            .isEqualTo(name.equals("repeatedOwnership"));
      } else {
        fresh =
            solver.registerInferenceVariables(
                tree,
                method.getTypeParameters(),
                Map.of(t, state.getSymtab().stringType, u, state.getSymtab().stringType));
        solver.addSubtypeConstraint(fresh.get(t), state.getSymtab().stringType, false);
        solver.addSubtypeConstraint(state.getSymtab().botType, fresh.get(u), false);
        if (name.equals("dependentRoot")) {
          assertThrows(UnsatisfiableConstraintsException.class, solver::solve);
        } else {
          Solution result = solver.solve();
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      result.inferredTypes().get(new InferenceVariable(t, tree)),
                      analysis.getConfig()))
              .isFalse();
          assertThat(
                  GenericsUtils.isAnnotatedAsNullable(
                      result.inferredTypes().get(new InferenceVariable(u, tree)),
                      analysis.getConfig()))
              .isTrue();
          assertThat(result.isComplete()).isTrue();
        }
      }
      return Description.NO_MATCH;
    }
  }
}
