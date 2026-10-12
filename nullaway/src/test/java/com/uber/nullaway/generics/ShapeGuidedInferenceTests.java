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
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.ConstraintSolver.BoundEvidence;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.LowerBoundConflict;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import com.uber.nullaway.generics.ConstraintSolver.SourceOrigin;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Shared-shape regressions and compiler-backed certification of sibling inference slots. */
public class ShapeGuidedInferenceTests extends NullAwayTestsBase {

  private static final ThreadLocal<Integer> PROOF_COUNT = ThreadLocal.withInitial(() -> 0);

  @Test
  public void evidenceAndConflictResultsAreImmutableSnapshots() {
    Tree owner = mock(Tree.class);
    Type type = mock(Type.class);
    InferenceVariable variable = new InferenceVariable(mock(Element.class), owner);
    SourceOrigin origin = new SourceOrigin(mock(Tree.class), owner, type);
    List<SourceOrigin> origins = new ArrayList<>(List.of(origin, origin));
    BoundEvidence evidence = new BoundEvidence(type, origins);
    origins.clear();
    assertThat(evidence.origins()).containsExactly(origin);
    List<LowerBoundConflict> facts =
        new ArrayList<>(List.of(new LowerBoundConflict(variable, evidence, evidence, type)));
    Solution solution = new Solution(Map.of(variable, type), Set.of(), Set.of(), facts);
    facts.clear();
    assertThat(solution.lowerConflicts()).hasSize(1);
    assertThat(solution.inconsistentVariables()).containsExactly(variable);
    assertThat(solution.isCompleteForSite(owner)).isFalse();
    Solution legacy = new Solution(Map.of(variable, type), Set.of(), Set.of());
    assertThat(legacy.lowerConflicts()).isEmpty();
    assertThat(legacy.isComplete()).isTrue();
  }

  @Test
  public void diamondsBelowExtendsBoundsKeepConcreteSlotsInvariant() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {
                E get() { throw new RuntimeException(); }
              }
              static class Boxes<E extends @Nullable Object> extends ArrayList<Box<E>> {}
              static <T> T pick(T a, T b) { return a; }
              void test(List<? extends Box<@Nullable String>> nullable,
                  List<? extends Box<String>> clean) {
                List<? extends Box<@Nullable String>> forward =
                    pick(nullable, new Boxes<>());
                List<? extends Box<@Nullable String>> reverse =
                    pick(new Boxes<>(), nullable);
                List<? extends Box<String>> cleanForward = pick(clean, new Boxes<>());
                List<? extends Box<String>> cleanReverse = pick(new Boxes<>(), clean);
                cleanForward.get(0).get().length();
                cleanReverse.get(0).get().length();
                // BUG: Diagnostic contains: dereferenced expression
                forward.get(0).get().length();
                // BUG: Diagnostic contains: dereferenced expression
                reverse.get(0).get().length();
                // BUG: Diagnostic contains: incompatible types
                List<? extends Box<String>> invalid = pick(nullable, new Boxes<>());
                // BUG: Diagnostic contains: incompatible types
                List<? extends Box<String>> invalidReverse = pick(new Boxes<>(), nullable);
                // BUG: Diagnostic contains: incompatible types
                List<? extends Box<@Nullable String>> explicit = pick(nullable, new Boxes<String>());
                // BUG: Diagnostic contains: incompatible types
                List<? extends Box<@Nullable String>> explicitReverse = pick(new Boxes<String>(), nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void enclosingGenericDisagreementsCannotHideBehindEmptyInnerArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Outer<E extends @Nullable Object> { class Inner {} }
              static <T> T pick(T a, T b) { return a; }
              void test(Outer<@Nullable String>.Inner nullable,
                  Outer<@Nullable String>.Inner alsoNullable, Outer<String>.Inner clean) {
                Outer<@Nullable String>.Inner forward = pick(nullable, alsoNullable);
                Outer<@Nullable String>.Inner reverse = pick(alsoNullable, nullable);
                Outer<String>.Inner cleanResult = pick(clean, clean);
                // BUG: Diagnostic contains: incompatible types
                Outer<@Nullable String>.Inner invalidForward = pick(nullable, clean);
                // BUG: Diagnostic contains: incompatible types
                Outer<@Nullable String>.Inner invalidReverse = pick(clean, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void freshBoxBelowWildcardBoundIsFullyCertified() {
    runProof(
        """
        <R, E extends @Nullable Object> void inference(
            List<? extends @Nullable Box<@Nullable String>> evidence,
            List<? extends Box<E>> template, List<? extends Box<String>> shape,
            String slotShape) {}
        """);
  }

  @Test
  public void freshBoxBelowSuperBoundIsFullyCertified() {
    runProof(
        """
        <R, E extends @Nullable Object> void inference(
            List<? super @Nullable Box<@Nullable String>> evidence,
            List<? super Box<E>> template, List<? super Box<String>> shape,
            String slotShape) {}
        """);
  }

  @Test
  public void freshSlotOnlyInEnclosingTypeIsFullyCertified() {
    runProof(
        """
        <R, E extends @Nullable Object> void inference(
            Outer<@Nullable String>.Inner evidence,
            Outer<E>.Inner template, Outer<String>.Inner shape, String slotShape) {}
        """);
  }

  @Test
  public void nominallyDifferentEvidenceUsesTheAttributedCommonShape() {
    runProof(
        """
        <R extends Box<View<String>>, E extends @Nullable Object> void inference(
            Box<View<String>> evidence, Box<E> template, Box<View<String>> shape,
            View<String> slotShape, First<String> first, Second<String> second) {}
        """);
  }

  @Test
  public void inheritedEvidencePreparesSlotsBeforeShapedComparison() {
    runProof(
        """
        <R extends Base<E>, E extends @Nullable Object> void inference(
            Nested lower, Nested shape, Box<String> slotShape) {}
        """);
  }

  @Test
  public void broadShapeViolatingEqualityIsNotDeclarationConflictEvidence() {
    runProof(
        """
        <R extends Base<E>, E extends @Nullable Object> void inference(
            Nested lower, Nested shape, Object slotShape) {}
        """);
  }

  @Test
  public void concreteInheritedDeclarationViolationStillThrows() {
    runProof(
        """
        <R extends Base<Box<String>>, E extends @Nullable Object> void inference(
            Nested lower, Nested shape, Box<String> slotShape) {}
        """);
  }

  @Test
  public void derivedSlotsRetainTheirRootSourcesAndNestedCallOrigins() {
    runProof(
        """
        <R, E extends @Nullable Object> void inference(
            List<? extends Box<Box<@Nullable String>>> first,
            List<? extends Box<E>> template, List<? extends Box<Box<String>>> shape,
            Box<String> slotShape, Box<String> initial) { id(first); id(initial); id(template); }
        """);
  }

  @Test
  public void sameNominalConflictsRetainOriginsAndCertifyIndependentSiblings() {
    runProof(
        """
        <R, U, D> void inference(
            Box<@Nullable String> first, Box<String> second, Box<String> shape,
            String independentShape, Box<R> dependentTemplate, Box<Box<String>> dependentShape,
            Box raw) { pick(id(first), second); id(second); id(first); }
        """);
  }

  @Test
  public void declaredDependentBoundsBecomeInconsistentWithTheirOwner() {
    runProof(
        """
        <R, U, D extends R> void inference(
            Box<@Nullable String> first, Box<String> second, Box<String> shape,
            String independentShape, R dependentTemplate, Box<String> dependentShape,
            Box raw) { pick(id(first), second); id(second); id(first); }
        """);
  }

  @Test
  public void alignedConflictsRetainOriginsAndCertifyIndependentSiblings() {
    runProof(
        """
        <R, U, D> void inference(
            First<@Nullable String> first, Second<String> second, View<String> shape,
            String independentShape, Box<R> dependentTemplate, Box<View<String>> dependentShape,
            View raw) { pick(id(first), second); id(second); id(first); }
        """);
  }

  /** Compiles a proof fixture and requires its compiler-backed assertions to execute. */
  private void runProof(String declaration) {
    PROOF_COUNT.set(0);
    try {
      CompilationTestHelper.newInstance(ShapeProofChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import java.util.List;
              import org.jspecify.annotations.Nullable;
              class Test {
                static class Box<E extends @Nullable Object> {}
                static class Base<E extends @Nullable Object> {}
                static class Nested extends Base<Box<@Nullable String>> {}
                interface View<E extends @Nullable Object> {}
                static class First<E extends @Nullable Object> implements View<E> {}
                static class Second<E extends @Nullable Object> implements View<E> {}
                static class Outer<E extends @Nullable Object> { class Inner {} }
                static <T> T id(T value) { return value; }
                static <T> T pick(T first, T second) { return first; }
                Object siteA = id(null);
                Object siteB = id(null);
              """
                  + declaration
                  + "\nObject shapeProof;\n}\n")
          .doTest();
      assertThat(PROOF_COUNT.get()).isEqualTo(1);
    } finally {
      PROOF_COUNT.remove();
    }
  }

  /** Creates the ordinary full-inference helper with wildcard containment enabled. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }

  /** Asserts solver results on compiler-owned types, independently of ordinary call diagnostics. */
  @BugPattern(summary = "Checks shared-shape inference certification", severity = SUGGESTION)
  public static final class ShapeProofChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      if (!tree.getName().contentEquals("shapeProof")) {
        return NO_MATCH;
      }
      TreePath path = state.getPath();
      while (!(path.getLeaf() instanceof ClassTree)) {
        path = Objects.requireNonNull(path.getParentPath());
      }
      ClassTree owner = (ClassTree) path.getLeaf();
      MethodTree declaration = null;
      MethodInvocationTree siteA = null;
      MethodInvocationTree siteB = null;
      for (Tree member : owner.getMembers()) {
        if (member instanceof MethodTree method && method.getName().contentEquals("inference")) {
          declaration = method;
        } else if (member instanceof VariableTree field) {
          if (field.getName().contentEquals("siteA")) {
            siteA = (MethodInvocationTree) field.getInitializer();
          } else if (field.getName().contentEquals("siteB")) {
            siteB = (MethodInvocationTree) field.getInitializer();
          }
        }
      }
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(Objects.requireNonNull(declaration));
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      for (boolean reverse : List.of(false, true)) {
        if (method.getTypeParameters().size() == 3) {
          checkConflictProof(
              method,
              Objects.requireNonNull(declaration),
              Objects.requireNonNull(siteB),
              state,
              config,
              reverse);
        } else if (method.getParameters().size() == 3) {
          checkInheritedEvidenceProof(method, Objects.requireNonNull(siteA), state, config);
        } else if (method.getParameters().size() == 5) {
          checkDerivedOriginsProof(
              method, Objects.requireNonNull(declaration), state, config, reverse);
        } else {
          checkProof(
              method,
              Objects.requireNonNull(siteA),
              Objects.requireNonNull(siteB),
              state,
              config,
              reverse);
        }
      }
      PROOF_COUNT.set(PROOF_COUNT.get() + 1);
      return NO_MATCH;
    }

    /**
     * Checks inherited lower/upper equality with a concrete slot shape and a broad incompatible
     * supplied shape. The latter must remain uncertified, not become a false declaration violation.
     * A concrete declaration contract is still independently enforced before candidate selection.
     */
    private static void checkInheritedEvidenceProof(
        Symbol.MethodSymbol method, Tree site, VisitorState state, Config config) {
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(new Handler() {});
      ConstraintSolver solver = new ConstraintSolverImpl(config, state, analysis);
      Element r = method.getTypeParameters().get(0);
      Element e = method.getTypeParameters().get(1);
      Type lower = method.getParameters().get(0).type;
      Type shape = method.getParameters().get(1).type;
      Type slotShape = method.getParameters().get(2).type;
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              site, List.of(r, e), Map.of(), Set.of(r, e), Map.of(r, shape, e, slotShape));
      solver.addSubtypeConstraint(lower, Objects.requireNonNull(fresh.get(r)), false);
      Type upperArgument = ((Type.TypeVar) r.asType()).getUpperBound().getTypeArguments().head;
      if (!(upperArgument instanceof Type.TypeVar)) {
        ConstraintSolver.NestedUpperBoundViolationException violation =
            assertThrows(ConstraintSolver.NestedUpperBoundViolationException.class, solver::solve);
        assertThat(violation.getTypeVariable()).isSameInstanceAs(r);
        assertThat(violation.getLowerBound()).isSameInstanceAs(lower);
        return;
      }
      InferenceVariable rootKey = new InferenceVariable(r, site);
      InferenceVariable slotKey = new InferenceVariable(e, site);
      Solution previous = null;
      for (int solve = 0; solve < 2; solve++) {
        Solution solution = solver.solve();
        assertThat(solution.inferredTypes().keySet()).containsExactly(rootKey, slotKey);
        assertThat(solution.lowerConflicts()).isEmpty();
        // Even an incompatible supplied slot shape must not replace Nested with Base<E> as a
        // declaration-diagnostic fallback. The result retains attribution's original Java shapes.
        assertThat(solution.inferredTypes().get(rootKey).tsym).isSameInstanceAs(shape.tsym);
        Type inferredSlot = solution.inferredTypes().get(slotKey);
        assertThat(inferredSlot.tsym).isSameInstanceAs(slotShape.tsym);
        if (slotShape.getTypeArguments().isEmpty()) {
          assertThat(solution.isComplete()).isFalse();
          assertThat(solution.inconsistentVariables()).containsAtLeast(rootKey, slotKey);
        } else {
          assertThat(solution.isComplete()).isTrue();
          assertThat(solution.incompleteVariables()).isEmpty();
          assertThat(solution.inconsistentVariables()).isEmpty();
          assertThat(
                  Nullness.hasNullableAnnotation(
                      inferredSlot.getTypeArguments().head.getAnnotationMirrors().stream(), config))
              .isTrue();
        }
        if (previous != null) {
          assertThat(solution.inferredTypes().get(rootKey).toString())
              .isEqualTo(previous.inferredTypes().get(rootKey).toString());
          assertThat(inferredSlot.toString())
              .isEqualTo(previous.inferredTypes().get(slotKey).toString());
          assertThat(solution.inconsistentVariables()).isEqualTo(previous.inconsistentVariables());
        }
        previous = solution;
      }
    }

    /**
     * Checks that raw evidence cannot hide a later conflict, root-edge origins survive collection,
     * and a symbolic nested dependent fails certification without poisoning an independent sibling.
     */
    @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
    private static void checkConflictProof(
        Symbol.MethodSymbol method,
        MethodTree declaration,
        Tree independentSite,
        VisitorState state,
        Config config,
        boolean reverse) {
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(new Handler() {});
      ConstraintSolver solver = new ConstraintSolverImpl(config, state, analysis);
      Element r = method.getTypeParameters().get(0);
      Element u = method.getTypeParameters().get(1);
      Element d = method.getTypeParameters().get(2);
      Type first = method.getParameters().get(0).type;
      Type second = method.getParameters().get(1).type;
      Type shape = method.getParameters().get(2).type;
      Type independentShape = method.getParameters().get(3).type;
      Type dependentTemplate = method.getParameters().get(4).type;
      Type dependentShape = method.getParameters().get(5).type;
      Type raw = method.getParameters().get(6).type;
      MethodInvocationTree owner =
          (MethodInvocationTree)
              ((ExpressionStatementTree) declaration.getBody().getStatements().get(0))
                  .getExpression();
      MethodInvocationTree nestedCall = (MethodInvocationTree) owner.getArguments().get(0);
      MethodInvocationTree repeatedCall =
          (MethodInvocationTree)
              ((ExpressionStatementTree) declaration.getBody().getStatements().get(2))
                  .getExpression();
      Type nestedResultType = ASTHelpers.getType(nestedCall);
      SourceOrigin firstOrigin =
          new SourceOrigin(nestedCall.getArguments().get(0), nestedCall, first);
      SourceOrigin repeatedOrigin =
          new SourceOrigin(repeatedCall.getArguments().get(0), repeatedCall, first);
      SourceOrigin resultOrigin = new SourceOrigin(nestedCall, owner, nestedResultType);
      SourceOrigin secondOrigin = new SourceOrigin(owner.getArguments().get(1), owner, second);
      Type nested =
          solver
              .registerInferenceVariables(
                  nestedCall, List.of(r), Map.of(), Set.of(r), Map.of(r, nestedResultType))
              .get(r);
      // Registration is idempotent per site, not additive. Register all peers together so bounds
      // such as D extends R reference the same site's fresh R, rather than a fixed declaration.
      Map<Element, Type.TypeVar> ownerVariables =
          solver.registerInferenceVariables(
              owner, List.of(r, d), Map.of(), Set.of(r, d), Map.of(r, shape, d, dependentShape));
      assertThat(ownerVariables.keySet()).containsExactly(r, d);
      Type root = Objects.requireNonNull(ownerVariables.get(r));
      Type.TypeVar dependent = Objects.requireNonNull(ownerVariables.get(d));
      solver.registerInferenceVariables(
          independentSite, List.of(u), Map.of(), Set.of(u), Map.of(u, independentShape));
      boolean declaredDependency = ((Type.TypeVar) d.asType()).getUpperBound().tsym.equals(r);
      if (declaredDependency) {
        assertThat(dependent.getUpperBound().tsym).isSameInstanceAs(root.tsym);
      }
      solver.addSubtypeConstraint(first, nested, false, firstOrigin);
      solver.addSubtypeConstraint(first, nested, false, firstOrigin);
      solver.addSubtypeConstraint(first, nested, false, repeatedOrigin);
      // An opaque first lower must not suppress the concrete disagreement between later lowers.
      solver.addSubtypeConstraint(raw, root, false);
      if (reverse) {
        solver.addSubtypeConstraint(second, root, false, secondOrigin);
        solver.addSubtypeConstraint(nested, root, false, resultOrigin);
      } else {
        solver.addSubtypeConstraint(nested, root, false, resultOrigin);
        solver.addSubtypeConstraint(second, root, false, secondOrigin);
      }
      if (!declaredDependency) {
        Type dependentLower =
            TypeSubstitutionUtils.substituteTypeVariables(
                dependentTemplate, Map.of(r, root), state.getTypes(), config);
        solver.addSubtypeConstraint(
            dependentLower,
            dependent,
            false,
            new SourceOrigin(nestedCall, owner, dependentTemplate));
      }
      InferenceVariable rootKey = new InferenceVariable(r, owner);
      InferenceVariable independentKey = new InferenceVariable(u, independentSite);
      InferenceVariable dependentKey = new InferenceVariable(d, owner);
      Solution previous = null;
      for (int solve = 0; solve < 2; solve++) {
        Solution solution = solver.solve();
        assertThat(solution.inferredTypes().keySet())
            .containsExactly(
                new InferenceVariable(r, nestedCall), rootKey, independentKey, dependentKey);
        assertThat(solution.lowerConflicts()).hasSize(1);
        LowerBoundConflict conflict = solution.lowerConflicts().get(0);
        assertThat(conflict.variable()).isEqualTo(rootKey);
        assertThat(conflict.shape()).isSameInstanceAs(shape);
        BoundEvidence firstEvidence =
            conflict.first().type() == first ? conflict.first() : conflict.second();
        BoundEvidence secondEvidence =
            conflict.first().type() == second ? conflict.first() : conflict.second();
        assertThat(firstEvidence.type()).isSameInstanceAs(first);
        assertThat(secondEvidence.type()).isSameInstanceAs(second);
        // Equal nominal shapes with different annotations must remain distinct bounds, not dedup.
        assertThat(
                Nullness.hasNullableAnnotation(
                    firstEvidence.type().getTypeArguments().head.getAnnotationMirrors().stream(),
                    config))
            .isTrue();
        assertThat(
                Nullness.hasNullableAnnotation(
                    secondEvidence.type().getTypeArguments().head.getAnnotationMirrors().stream(),
                    config))
            .isFalse();
        assertThat(firstEvidence.origins())
            .containsExactly(firstOrigin, repeatedOrigin, resultOrigin);
        assertThat(secondEvidence.origins()).containsExactly(secondOrigin);
        assertThat(firstOrigin.originalType()).isSameInstanceAs(first);
        assertThat(resultOrigin.expression()).isSameInstanceAs(nestedCall);
        assertThat(resultOrigin.ownerSite()).isSameInstanceAs(owner);
        List<SourceOrigin> parentArgumentOrigins =
            firstEvidence.origins().stream()
                .filter(origin -> origin.ownerSite() == conflict.variable().site())
                .toList();
        assertThat(parentArgumentOrigins).containsExactly(resultOrigin);
        SourceOrigin preferred = parentArgumentOrigins.get(0);
        assertThat(preferred.expression()).isSameInstanceAs(nestedCall);
        assertThat(preferred.expression()).isNotSameInstanceAs(firstOrigin.expression());
        assertThat(preferred.originalType()).isSameInstanceAs(nestedResultType);
        assertThat(secondOrigin.expression()).isSameInstanceAs(owner.getArguments().get(1));
        assertThat(solution.inconsistentVariables()).containsAtLeast(rootKey, dependentKey);
        assertThat(solution.inconsistentVariables()).doesNotContain(independentKey);
        assertThat(solution.incompleteVariables()).doesNotContain(independentKey);
        assertThat(solution.isCompleteForSite(independentSite)).isTrue();
        assertThat(solution.isCompleteForSite(owner)).isFalse();
        assertThat(solution.inferredTypes().get(independentKey).tsym)
            .isSameInstanceAs(independentShape.tsym);
        assertThrows(UnsupportedOperationException.class, () -> solution.lowerConflicts().clear());
        assertThrows(UnsupportedOperationException.class, () -> firstEvidence.origins().clear());
        if (previous != null) {
          assertThat(solution.lowerConflicts()).isEqualTo(previous.lowerConflicts());
        }
        previous = solution;
      }
      SourceOrigin lateOrigin = new SourceOrigin(repeatedCall, owner, first);
      solver.addSubtypeConstraint(first, nested, false, lateOrigin);
      Solution updated = solver.solve();
      assertThat(updated.lowerConflicts()).hasSize(1);
      assertThat(
              updated.lowerConflicts().stream()
                  .flatMap(fact -> java.util.stream.Stream.of(fact.first(), fact.second()))
                  .flatMap(bound -> bound.origins().stream())
                  .toList())
          .contains(lateOrigin);
      assertThat(
              Objects.requireNonNull(previous).lowerConflicts().stream()
                  .flatMap(fact -> java.util.stream.Stream.of(fact.first(), fact.second()))
                  .flatMap(bound -> bound.origins().stream())
                  .toList())
          .doesNotContain(lateOrigin);
      assertThat(solver.solve().lowerConflicts()).isEqualTo(updated.lowerConflicts());
      assertThat(
              Objects.requireNonNull(previous).lowerConflicts().stream()
                  .flatMap(fact -> java.util.stream.Stream.of(fact.first(), fact.second()))
                  .flatMap(bound -> bound.origins().stream())
                  .toList())
          .doesNotContain(lateOrigin);

      // Legacy constraints must not manufacture origins or inherit another solver's source context.
      ConstraintSolver legacy = new ConstraintSolverImpl(config, state, analysis);
      Type legacyRoot =
          legacy
              .registerInferenceVariables(owner, List.of(r), Map.of(), Set.of(r), Map.of(r, shape))
              .get(r);
      legacy.addSubtypeConstraint(reverse ? second : first, legacyRoot, false);
      legacy.addSubtypeConstraint(reverse ? first : second, legacyRoot, false);
      Solution legacySolution = legacy.solve();
      assertThat(legacySolution.lowerConflicts()).hasSize(1);
      LowerBoundConflict legacyConflict = legacySolution.lowerConflicts().get(0);
      assertThat(legacyConflict.first().origins()).isEmpty();
      assertThat(legacyConflict.second().origins()).isEmpty();
    }

    /** Checks provenance on a lower bound generated beneath a wildcard, not supplied directly. */
    private static void checkDerivedOriginsProof(
        Symbol.MethodSymbol method,
        MethodTree declaration,
        VisitorState state,
        Config config,
        boolean reverse) {
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(new Handler() {});
      ConstraintSolver solver = new ConstraintSolverImpl(config, state, analysis);
      Element r = method.getTypeParameters().get(0);
      Element e = method.getTypeParameters().get(1);
      Type first = method.getParameters().get(0).type;
      Type template = method.getParameters().get(1).type;
      Type shape = method.getParameters().get(2).type;
      Type slotShape = method.getParameters().get(3).type;
      Type initial = method.getParameters().get(4).type;
      MethodInvocationTree rootCall =
          (MethodInvocationTree)
              ((ExpressionStatementTree) declaration.getBody().getStatements().get(0))
                  .getExpression();
      MethodInvocationTree slotCall =
          (MethodInvocationTree)
              ((ExpressionStatementTree) declaration.getBody().getStatements().get(1))
                  .getExpression();
      MethodInvocationTree nestedCall =
          (MethodInvocationTree)
              ((ExpressionStatementTree) declaration.getBody().getStatements().get(2))
                  .getExpression();
      SourceOrigin firstOrigin = new SourceOrigin(rootCall.getArguments().get(0), rootCall, first);
      SourceOrigin initialOrigin =
          new SourceOrigin(slotCall.getArguments().get(0), slotCall, initial);
      SourceOrigin nestedOrigin = new SourceOrigin(nestedCall, rootCall, template);
      Type root =
          solver
              .registerInferenceVariables(
                  rootCall, List.of(r), Map.of(), Set.of(r), Map.of(r, shape))
              .get(r);
      Type slot =
          solver
              .registerInferenceVariables(
                  slotCall, List.of(e), Map.of(), Set.of(e), Map.of(e, slotShape))
              .get(e);
      Type sibling =
          TypeSubstitutionUtils.substituteTypeVariables(
              template, Map.of(e, slot), state.getTypes(), config);
      solver.addSubtypeConstraint(initial, slot, false, initialOrigin);
      if (reverse) {
        solver.addSubtypeConstraint(sibling, root, false, nestedOrigin);
        solver.addSubtypeConstraint(first, root, false, firstOrigin);
      } else {
        solver.addSubtypeConstraint(first, root, false, firstOrigin);
        solver.addSubtypeConstraint(sibling, root, false, nestedOrigin);
      }
      Solution solution = solver.solve();
      InferenceVariable slotKey = new InferenceVariable(e, slotCall);
      assertThat(solution.inconsistentVariables())
          .containsAtLeast(slotKey, new InferenceVariable(r, rootCall));
      LowerBoundConflict conflict =
          solution.lowerConflicts().stream()
              .filter(fact -> fact.variable().equals(slotKey))
              .findFirst()
              .orElseThrow();
      BoundEvidence derived =
          conflict.first().origins().contains(firstOrigin) ? conflict.first() : conflict.second();
      assertThat(derived.type().tsym).isSameInstanceAs(slotShape.tsym);
      assertThat(
              Nullness.hasNullableAnnotation(
                  derived.type().getTypeArguments().head.getAnnotationMirrors().stream(), config))
          .isTrue();
      assertThat(derived.origins()).containsAtLeast(firstOrigin, nestedOrigin);
      assertThat(firstOrigin.originalType()).isSameInstanceAs(first);
      assertThat(nestedOrigin.originalType()).isSameInstanceAs(template);
      assertThat(
              solution.lowerConflicts().stream()
                  .flatMap(fact -> java.util.stream.Stream.of(fact.first(), fact.second()))
                  .flatMap(bound -> bound.origins().stream())
                  .toList())
          .contains(initialOrigin);
    }

    /** Registers sibling owners separately; only shared lower slots can constrain the fresh E. */
    private static void checkProof(
        Symbol.MethodSymbol method,
        Tree siteA,
        Tree siteB,
        VisitorState state,
        Config config,
        boolean reverse) {
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(new Handler() {});
      ConstraintSolver solver = new ConstraintSolverImpl(config, state, analysis);
      Element r = method.getTypeParameters().get(0);
      Element e = method.getTypeParameters().get(1);
      Type evidence = method.getParameters().get(0).type;
      Type template = method.getParameters().get(1).type;
      Type shape = method.getParameters().get(2).type;
      Type slotShape = method.getParameters().get(3).type;
      Type freshR =
          solver
              .registerInferenceVariables(siteA, List.of(r), Map.of(), Set.of(r), Map.of(r, shape))
              .get(r);
      Type freshE =
          solver
              .registerInferenceVariables(
                  siteB, List.of(e), Map.of(), Set.of(e), Map.of(e, slotShape))
              .get(e);
      boolean nominalEvidence = method.getParameters().size() > 4;
      if (nominalEvidence) {
        solver.addSubtypeConstraint(
            method.getParameters().get(reverse ? 5 : 4).type, freshE, false);
        solver.addSubtypeConstraint(
            method.getParameters().get(reverse ? 4 : 5).type, freshE, false);
      }
      Type sibling =
          TypeSubstitutionUtils.substituteTypeVariables(
              template, Map.of(e, freshE), state.getTypes(), config);
      solver.addSubtypeConstraint(reverse ? sibling : evidence, freshR, false);
      solver.addSubtypeConstraint(reverse ? evidence : sibling, freshR, false);
      InferenceVariable rKey = new InferenceVariable(r, siteA);
      InferenceVariable eKey = new InferenceVariable(e, siteB);
      for (int solve = 0; solve < 2; solve++) {
        Solution solution = solver.solve();
        assertThat(solution.inferredTypes().keySet()).containsExactly(rKey, eKey);
        assertThat(solution.lowerConflicts()).isEmpty();
        assertThat(solution.incompleteVariables()).isEmpty();
        assertThat(solution.inconsistentVariables()).isEmpty();
        assertThat(solution.isComplete()).isTrue();
        assertThat(solution.isCompleteForSite(siteA)).isTrue();
        assertThat(solution.isCompleteForSite(siteB)).isTrue();
        Type inferredE = solution.inferredTypes().get(eKey);
        assertThat(inferredE.tsym).isSameInstanceAs(slotShape.tsym);
        assertThat(
                Nullness.hasNullableAnnotation(inferredE.getAnnotationMirrors().stream(), config))
            .isEqualTo(!nominalEvidence);
        Type inferredR = solution.inferredTypes().get(rKey);
        assertThat(inferredR.tsym).isSameInstanceAs(shape.tsym);
        if (nominalEvidence) {
          Type argument = inferredR.getTypeArguments().head;
          assertThat(argument.tsym).isSameInstanceAs(slotShape.tsym);
          assertThat(argument.getTypeArguments().head.tsym)
              .isSameInstanceAs(slotShape.getTypeArguments().head.tsym);
        } else if (shape.getTypeArguments().isEmpty()) {
          assertThat(inferredR.getTypeArguments()).isEmpty();
          Type enclosingSlot = inferredR.getEnclosingType().getTypeArguments().head;
          assertThat(
                  Nullness.hasNullableAnnotation(
                      enclosingSlot.getAnnotationMirrors().stream(), config))
              .isTrue();
        } else {
          Type.WildcardType wildcard = (Type.WildcardType) inferredR.getTypeArguments().head;
          Type.WildcardType shapeWildcard = (Type.WildcardType) shape.getTypeArguments().head;
          assertThat(wildcard.kind).isEqualTo(shapeWildcard.kind);
          assertThat(wildcard.type.tsym).isSameInstanceAs(shapeWildcard.type.tsym);
          assertThat(
                  Nullness.hasNullableAnnotation(
                      wildcard.type.getTypeArguments().head.getAnnotationMirrors().stream(),
                      config))
              .isTrue();
          assertThat(
                  Nullness.hasNullableAnnotation(
                      wildcard.type.getAnnotationMirrors().stream(), config))
              .isEqualTo(wildcard.isExtendsBound());
        }
      }
    }
  }
}
