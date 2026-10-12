package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.InputDependencies;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import com.uber.nullaway.generics.ConstraintSolver.SourceOrigin;
import com.uber.nullaway.handlers.Handler;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Compiler-backed proofs of external-input dependence, independent of inference certification. */
public class SolverInputDependenciesTests extends NullAwayTestsBase {
  private static final ThreadLocal<Integer> PROOF_COUNT = ThreadLocal.withInitial(() -> 0);

  @Test
  public void ownedForeignAndStableCallerSymbolsAreDistinct() {
    runProof("symbolsProof");
  }

  @Test
  public void scalarSameValueAndDeduplicatedBoundsRetainDependencies() {
    runProof("scalarProof");
  }

  @Test
  public void nestedDerivedDependenciesPreserveIndependentSibling() {
    runProof("nestedProof");
  }

  @Test
  public void independentSlotsInTheSameRelationDoNotShareForeignInputs() {
    runProof("sameRelationProof");
  }

  @Test
  public void fixedForeignSlotDoesNotTaintOwnedSiblingSlots() {
    runProof("fixedSlotProof");
  }

  @Test
  public void wholeStructuredBoundsRetainForeignInputsWithoutOrigins() {
    runProof("wholeBoundProof");
  }

  @Test
  public void foreignFixedBoundExpansionRetainsReducedSlotProvenance() {
    runProof("fixedBoundProof");
  }

  @Test
  public void registrationInputsAndRepeatedSolutionsAreSnapshots() {
    runProof("registrationProof");
  }

  @Test
  public void initialBoundsAndNestedShapesSeedOnlyTheirSpecificVariables() {
    runProof("initialRegistrationProof");
  }

  @Test
  public void failureRetainsDependenciesSeededBeforeTheException() {
    runProof("failureProof");
  }

  @Test
  public void rawInputsAndFixedRelationsMarkOnlyTheirOwnerSite() {
    runProof("siteProof");
  }

  @Test
  public void dependencyRecordsAndLegacySolutionsAreImmutable() {
    Tree site = mock(Tree.class);
    InferenceVariable variable = new InferenceVariable(mock(Element.class), site);
    Set<InferenceVariable> variables = new LinkedHashSet<>(Set.of(variable));
    Set<Tree> sites = new LinkedHashSet<>(Set.of(site));
    InputDependencies dependencies = new InputDependencies(variables, sites);
    variables.clear();
    sites.clear();
    assertThat(dependencies.variables()).containsExactly(variable);
    assertThat(dependencies.sites()).containsExactly(site);
    assertThat(dependencies.isIndependentForSite(site)).isFalse();
    assertThrows(UnsupportedOperationException.class, () -> dependencies.variables().clear());
    assertThrows(UnsupportedOperationException.class, () -> dependencies.sites().clear());
    assertThat(new Solution(Map.of(), Set.of(), Set.of()).inputDependencies().variables())
        .isEmpty();
    assertThat(new Solution(Map.of(), Set.of(), Set.of(), List.of()).inputDependencies().sites())
        .isEmpty();
  }

  /** Runs a proof once on attributed types, without exercising frontend inference policy. */
  private void runProof(String proof) {
    PROOF_COUNT.set(0);
    try {
      CompilationTestHelper.newInstance(InputProofChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import org.jspecify.annotations.Nullable;
              class Test<C extends @Nullable Object> {
                static class Box<E extends @Nullable Object> {}
                static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
                <T extends @Nullable Object, U extends @Nullable Object,
                    R extends Box<U>, S extends @Nullable Object> void inference(
                    String nonnull, @Nullable String nullable, Box<String> box,
                    C caller, @Nullable C nullableCaller, Pair<String, String> pair) {}
                Object siteA;
                Object siteB;
              """
                  + "Object "
                  + proof
                  + ";\n}\n")
          .doTest();
      assertThat(PROOF_COUNT.get()).isEqualTo(1);
    } finally {
      PROOF_COUNT.remove();
    }
  }

  /** Builds solver-only proofs from compiler-owned symbols and types. */
  @BugPattern(summary = "Checks solver input dependencies", severity = SUGGESTION)
  public static final class InputProofChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      if (!tree.getName().toString().endsWith("Proof")) {
        return NO_MATCH;
      }
      TreePath path = state.getPath();
      while (!(path.getLeaf() instanceof ClassTree)) {
        path = Objects.requireNonNull(path.getParentPath());
      }
      ClassTree owner = (ClassTree) path.getLeaf();
      MethodTree declaration = null;
      Tree siteA = null;
      Tree siteB = null;
      for (Tree member : owner.getMembers()) {
        if (member instanceof MethodTree method && method.getName().contentEquals("inference")) {
          declaration = method;
        } else if (member instanceof VariableTree field) {
          if (field.getName().contentEquals("siteA")) {
            siteA = field;
          } else if (field.getName().contentEquals("siteB")) {
            siteB = field;
          }
        }
      }
      checkProof(
          tree.getName().toString(),
          ASTHelpers.getSymbol(Objects.requireNonNull(declaration)),
          Objects.requireNonNull(siteA),
          Objects.requireNonNull(siteB),
          state);
      PROOF_COUNT.set(PROOF_COUNT.get() + 1);
      return NO_MATCH;
    }

    /**
     * Exercises relation seeding, existing dependency closure, registration, and failure snapshots.
     */
    private static void checkProof(
        String proof, Symbol.MethodSymbol method, Tree site, Tree otherSite, VisitorState state) {
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(new Handler() {});
      ConstraintSolver solver = new ConstraintSolverImpl(config, state, analysis);
      ConstraintSolver enclosing = new ConstraintSolverImpl(config, state, analysis);
      Element t = method.getTypeParameters().get(0);
      Element u = method.getTypeParameters().get(1);
      Element r = method.getTypeParameters().get(2);
      Element s = method.getTypeParameters().get(3);
      Type nonnull = method.getParameters().get(0).type;
      Type pair = method.getParameters().get(5).type;
      Type box = method.getParameters().get(2).type;
      Type caller = method.getParameters().get(3).type;
      Type nullableCaller = method.getParameters().get(4).type;
      Type.TypeVar foreign =
          enclosing
              .registerInferenceVariables(
                  otherSite, List.of(t), Map.of(), Set.of(t), Map.of(t, nonnull))
              .get(t);
      Objects.requireNonNull(foreign);
      solver.registerExternalInferenceSymbols(enclosing.inferenceVariableSymbols());
      Type foreignBox =
          new Type.ClassType(
              box.getEnclosingType(), com.sun.tools.javac.util.List.of(foreign), box.tsym);
      boolean initialRegistration = proof.equals("initialRegistrationProof");
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              site,
              List.of(t, u, r, s),
              initialRegistration ? Map.of(u, foreign) : Map.of(),
              Set.of(t, u, r, s),
              Map.of(
                  t, initialRegistration ? foreignBox : nonnull, u, nonnull, r, box, s, nonnull));
      Type.TypeVar local = Objects.requireNonNull(fresh.get(t));
      InferenceVariable tKey = new InferenceVariable(t, site);
      InferenceVariable uKey = new InferenceVariable(u, site);
      InferenceVariable rKey = new InferenceVariable(r, site);
      InferenceVariable sKey = new InferenceVariable(s, site);
      switch (proof) {
        case "symbolsProof" -> {
          Set<Element> owned = solver.inferenceVariableSymbols();
          solver.registerExternalInferenceSymbols(owned);
          assertThat(solver.hasExternalInputDependency(local)).isFalse();
          assertThat(solver.hasExternalInputDependency(foreign)).isTrue();
          assertThat(solver.hasExternalInputDependency(caller)).isFalse();
          solver.recordInferenceSiteInput(site, local);
          assertThat(solver.inputDependencies().sites()).isEmpty();
          solver.addSubtypeConstraint(caller, local, false);
          solver.addSubtypeConstraint(local, fresh.get(s), false);
          assertThat(solver.inputDependencies().variables()).isEmpty();
          solver.registerInferenceVariables(otherSite, List.of(t), Map.of(), Set.of(t));
          assertThat(owned).hasSize(4);
          assertThat(solver.inferenceVariableSymbols()).hasSize(5);
          assertThrows(UnsupportedOperationException.class, owned::clear);
          solver.addSubtypeConstraint(
              local,
              TypeSubstitutionUtils.typeWithAnnot(
                  foreign, GenericsChecks.getSyntheticNonNullAnnotType(state)),
              false);
          assertThat(solver.inputDependencies().variables()).containsExactly(tKey, sKey);
          assertThat(solver.inputDependencies().isIndependentForSite(otherSite)).isTrue();
        }
        case "scalarProof" -> {
          Type annotatedForeign =
              TypeSubstitutionUtils.typeWithAnnot(
                  foreign, GenericsChecks.getSyntheticNonNullAnnotType(state));
          solver.addSubtypeConstraint(local, nonnull, false);
          Solution before = solver.solve();
          solver.addSubtypeConstraint(local, annotatedForeign, false);
          solver.addSubtypeConstraint(local, annotatedForeign, false);
          assertThat(solver.inputDependencies().variables()).containsExactly(tKey);
          Solution after = solver.solve();
          assertThat(after.inputDependencies().variables()).containsExactly(tKey);
          assertThat(after.incompleteVariables()).doesNotContain(sKey);
          assertThat(after.inconsistentVariables()).doesNotContain(sKey);
          assertThat(before.inputDependencies().variables()).isEmpty();
          assertThat(solver.solve().inputDependencies()).isEqualTo(after.inputDependencies());
          // Diagnostic metadata alone must not seed a sibling value.
          solver.addSubtypeConstraint(
              nonnull, fresh.get(s), false, new SourceOrigin(otherSite, site, foreign));
          assertThat(solver.inputDependencies().variables()).doesNotContain(sKey);
        }
        case "nestedProof" -> {
          solver.addSubtypeConstraint(
              local,
              TypeSubstitutionUtils.typeWithAnnot(
                  foreign, GenericsChecks.getSyntheticNonNullAnnotType(state)),
              false);
          // Construct the same javac shape with a fresh slot.
          Type symbolicBox =
              new Type.ClassType(
                  box.getEnclosingType(), com.sun.tools.javac.util.List.of(local), box.tsym);
          solver.addSubtypeConstraint(symbolicBox, fresh.get(r), false);
          assertThat(solver.inputDependencies().variables()).containsExactly(tKey, uKey, rKey);
          Solution solution = solver.solve();
          assertThat(solution.inputDependencies().variables()).containsExactly(tKey, uKey, rKey);
          assertThat(solution.incompleteVariables()).doesNotContain(sKey);
          assertThat(solution.inconsistentVariables()).doesNotContain(sKey);
          assertThat(solution.inputDependencies().variables()).doesNotContain(sKey);
          assertThat(solution.inputDependencies().sites()).isEmpty();
        }
        case "sameRelationProof", "fixedSlotProof" -> {
          Type source =
              new Type.ClassType(
                  pair.getEnclosingType(),
                  com.sun.tools.javac.util.List.of(foreign, fresh.get(u)),
                  pair.tsym);
          Type target =
              new Type.ClassType(
                  pair.getEnclosingType(),
                  com.sun.tools.javac.util.List.of(
                      proof.equals("sameRelationProof") ? local : nonnull, fresh.get(s)),
                  pair.tsym);
          solver.addSubtypeConstraint(source, target, false);
          InputDependencies before = solver.inputDependencies();
          if (proof.equals("sameRelationProof")) {
            assertThat(before.variables()).containsExactly(tKey);
          } else {
            assertThat(before.variables()).isEmpty();
          }
          assertThat(before.variables()).containsNoneOf(uKey, rKey, sKey);
          assertThat(before.sites()).isEmpty();
          solver.addSubtypeConstraint(
              source, target, false, new SourceOrigin(otherSite, site, source));
          for (int solve = 0; solve < 2; solve++) {
            Solution solution = solver.solve();
            assertThat(solution.inputDependencies().variables()).isEqualTo(before.variables());
            assertThat(solution.incompleteVariables()).containsNoneOf(uKey, rKey, sKey);
            assertThat(solution.inconsistentVariables()).containsNoneOf(uKey, rKey, sKey);
            // Fixed first-slot inputs and preparation's fixed derived pairs retain owner-site
            // provenance without making the independent second-slot values dependent.
            assertThat(solution.inputDependencies().sites()).containsExactly(site);
            if (proof.equals("fixedSlotProof")) {
              assertThat(solution.isComplete()).isTrue();
            }
          }
          assertThat(before.sites()).isEmpty();
        }
        case "wholeBoundProof" -> {
          Type source =
              new Type.ClassType(
                  pair.getEnclosingType(),
                  com.sun.tools.javac.util.List.of(foreign, fresh.get(s)),
                  pair.tsym);
          solver.registerInferenceVariables(site, List.of(t), Map.of(), Set.of(t), Map.of(t, pair));
          solver.addSubtypeConstraint(source, local, false);
          solver.addSubtypeConstraint(source, local, false);
          assertThat(solver.inputDependencies().variables()).containsExactly(tKey, sKey);
          assertThat(solver.inputDependencies().sites()).isEmpty();
          Solution solution = solver.solve();
          assertThat(solution.inputDependencies().variables()).containsExactly(tKey, sKey);
          assertThat(solution.incompleteVariables()).containsNoneOf(uKey, rKey);
          assertThat(solution.inconsistentVariables()).containsNoneOf(uKey, rKey);
        }
        case "fixedBoundProof" -> {
          ConstraintSolver boundOwner = new ConstraintSolverImpl(config, state, analysis);
          Type fixed =
              boundOwner
                  .registerInferenceVariables(
                      otherSite, List.of(t), Map.of(t, box), Set.of(t), Map.of(t, box))
                  .get(t);
          Objects.requireNonNull(fixed);
          solver.registerExternalInferenceSymbols(boundOwner.inferenceVariableSymbols());
          Type target =
              new Type.ClassType(
                  box.getEnclosingType(), com.sun.tools.javac.util.List.of(fresh.get(u)), box.tsym);
          solver.addSubtypeConstraint(fixed, target, false);
          assertThat(solver.inputDependencies().variables()).containsExactly(uKey, rKey);
          Solution solution = solver.solve();
          assertThat(solution.inputDependencies().variables()).containsExactly(uKey, rKey);
          assertThat(solution.inputDependencies().sites()).isEmpty();
          assertThat(solution.isComplete()).isTrue();
        }
        case "initialRegistrationProof" -> {
          assertThat(solver.hasExternalInputDependency(foreignBox)).isTrue();
          InputDependencies before = solver.inputDependencies();
          assertThat(before.variables()).containsExactly(tKey, uKey, rKey);
          assertThat(before.sites()).isEmpty();
          assertThat(solver.solve().inputDependencies()).isEqualTo(before);
          assertThat(solver.solve().inputDependencies()).isEqualTo(before);
          assertThat(before.variables()).doesNotContain(sKey);
        }
        case "registrationProof" -> {
          Solution before = solver.solve();
          solver.registerInferenceVariables(
              site,
              List.of(t, u, r, s),
              Map.of(u, foreign),
              Set.of(t, u, r, s),
              Map.of(t, foreign));
          InputDependencies registered = solver.inputDependencies();
          assertThat(registered.variables()).containsExactly(tKey, uKey, rKey);
          assertThat(registered.variables()).doesNotContain(sKey);
          Solution after = solver.solve();
          assertThat(after.inputDependencies()).isEqualTo(registered);
          assertThat(before.inputDependencies().variables()).isEmpty();
          solver.registerInferenceVariables(
              site, List.of(t, u, r, s), Map.of(), Set.of(t, u, r, s), Map.of(t, nonnull));
          assertThat(solver.solve().inputDependencies()).isEqualTo(registered);
          solver.recordInferenceSiteInput(otherSite, foreign);
          assertThat(after.inputDependencies().sites()).isEmpty();
          assertThat(solver.inputDependencies().sites()).containsExactly(otherSite);
        }
        case "failureProof" -> {
          solver.addSubtypeConstraint(local, nonnull, false);
          InputDependencies before = solver.inputDependencies();
          Type annotatedForeign =
              TypeSubstitutionUtils.typeWithAnnot(
                  foreign, GenericsChecks.getSyntheticNullableAnnotType(state));
          assertThrows(
              ConstraintSolver.UnsatisfiableConstraintsException.class,
              () -> solver.addSubtypeConstraint(annotatedForeign, local, false));
          assertThat(solver.inputDependencies().variables()).containsExactly(tKey);
          assertThat(before.variables()).isEmpty();
          assertThat(solver.inputDependencies().variables()).doesNotContain(sKey);
        }
        case "siteProof" -> {
          solver.recordInferenceSiteInput(site, nullableCaller);
          assertThat(solver.inputDependencies().isIndependentForSite(site)).isTrue();
          solver.recordInferenceSiteInput(site, foreign);
          assertThat(solver.inputDependencies().variables()).isEmpty();
          assertThat(solver.inputDependencies().isIndependentForSite(site)).isFalse();
          assertThat(solver.inputDependencies().isIndependentForSite(otherSite)).isTrue();
          solver.addSubtypeConstraint(
              foreign, nonnull, false, new SourceOrigin(site, otherSite, foreign));
          Solution solution = solver.solve();
          assertThat(solution.inputDependencies().sites()).containsExactly(site, otherSite);
          assertThat(solution.inputDependencies().variables()).isEmpty();
          assertThat(solution.isComplete()).isTrue();
        }
        default -> throw new AssertionError(proof);
      }
    }
  }
}
