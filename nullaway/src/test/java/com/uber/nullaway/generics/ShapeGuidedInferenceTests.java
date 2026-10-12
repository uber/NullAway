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
import com.uber.nullaway.generics.ConstraintSolver.SourceSupport;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
  public void sourceSupportNormalizesAlternativesAndConjunctivePremises() {
    Tree site = mock(Tree.class);
    Type type = mock(Type.class);
    SourceOrigin unavailable = new SourceOrigin(mock(Tree.class), site, type);
    SourceOrigin edge = new SourceOrigin(mock(Tree.class), site, type);
    SourceOrigin independent = new SourceOrigin(mock(Tree.class), site, type);
    List<SourceOrigin> mutable = new ArrayList<>(List.of(unavailable, edge, unavailable));
    BoundEvidence conjunction = new BoundEvidence(type, mutable);
    mutable.clear();
    assertThat(conjunction.availableSupport(origin -> true).orElseThrow())
        .containsExactly(unavailable, edge)
        .inOrder();
    assertThat(conjunction.availableSupport(origin -> !origin.equals(unavailable))).isEmpty();
    assertThat(conjunction.availableSupport(origin -> !origin.equals(edge))).isEmpty();
    SourceSupport alternatives =
        conjunction.support().or(new SourceSupport(List.of(List.of(independent))));
    BoundEvidence evidence = new BoundEvidence(type, List.of(unavailable, edge), alternatives);
    assertThat(evidence.availableSupport(origin -> !origin.equals(unavailable)).orElseThrow())
        .containsExactly(independent);
    assertThat(evidence.origins()).containsExactly(unavailable, edge, independent).inOrder();
    assertThat(
            alternatives
                .and(new SourceSupport(List.of(List.of(edge))))
                .availableSupport(origin -> !origin.equals(edge)))
        .isEmpty();
    SourceSupport minimal =
        new SourceSupport(
            List.of(List.of(unavailable, edge), List.of(edge, unavailable), List.of(unavailable)));
    assertThat(minimal).isEqualTo(new SourceSupport(List.of(List.of(unavailable))));
    assertThat(minimal.availableSupport(origin -> true).orElseThrow()).containsExactly(unavailable);
    BoundEvidence sourceLess = new BoundEvidence(type, List.of());
    assertThat(sourceLess.availableSupport(origin -> false).orElseThrow()).isEmpty();
    assertThat(
            alternatives.or(sourceLess.support()).availableSupport(origin -> false).orElseThrow())
        .isEmpty();
    assertThat(new SourceSupport(List.of()).availableSupport(origin -> true)).isEmpty();
    assertThrows(UnsupportedOperationException.class, () -> alternatives.origins().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> conjunction.availableSupport(origin -> true).orElseThrow().clear());
  }

  @Test
  public void sourceProofEqualityIsSemanticAcrossOrdersAndFactories() {
    Tree site = mock(Tree.class);
    Type type = mock(Type.class);
    SourceOrigin a = new SourceOrigin(mock(Tree.class), site, type);
    SourceOrigin b = new SourceOrigin(mock(Tree.class), site, type);
    SourceOrigin c = new SourceOrigin(mock(Tree.class), site, type);
    SourceSupport.Factory factory = new SourceSupport.Factory();
    SourceSupport first = factory.conjunction(List.of(a));
    SourceSupport second = factory.conjunction(List.of(b));
    SourceSupport third = factory.conjunction(List.of(c));
    SourceSupport factored = first.and(second.or(third));
    SourceSupport distributed = first.and(second).or(third.and(first));
    SourceSupport imported = new SourceSupport(List.of(List.of(c, a), List.of(b, a)));
    assertThat(factored).isEqualTo(distributed);
    assertThat(factored).isEqualTo(imported);
    assertThat(imported).isEqualTo(factored);
    assertThat(imported.hashCode()).isEqualTo(factored.hashCode());
    assertThat(first.or(first.and(second))).isEqualTo(first);
    assertThat(first.and(first.or(second))).isEqualTo(first);
    assertThat(first.or(second)).isEqualTo(second.or(first));
    assertThat(first.and(second)).isEqualTo(second.and(first));
    assertThat(imported.availableSupport(origin -> true).orElseThrow())
        .containsExactly(c, a)
        .inOrder();
    for (int mask = 0; mask < 8; mask++) {
      Set<SourceOrigin> available = new LinkedHashSet<>();
      if ((mask & 1) != 0) {
        available.add(a);
      }
      if ((mask & 2) != 0) {
        available.add(b);
      }
      if ((mask & 4) != 0) {
        available.add(c);
      }
      boolean expected = (mask & 1) != 0 && (mask & 6) != 0;
      assertThat(factored.availableSupport(available::contains).isPresent()).isEqualTo(expected);
      assertThat(imported.availableSupport(available::contains).isPresent()).isEqualTo(expected);
      factored
          .availableSupport(available::contains)
          .ifPresent(
              witness -> {
                assertThat(available).containsAtLeastElementsIn(witness);
                assertThat(witness).contains(a);
                assertThat(witness.contains(b) || witness.contains(c)).isTrue();
              });
    }
  }

  @Test(timeout = 60000)
  public void twentyIndependentAlternativesStayFactoredAndSnapshotStable() {
    Tree site = mock(Tree.class);
    Type type = mock(Type.class);
    SourceSupport.Factory factory = new SourceSupport.Factory();
    SourceSupport proof = factory.conjunction(List.of());
    List<SourceOrigin> first = new ArrayList<>();
    List<SourceOrigin> second = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      SourceOrigin a = new SourceOrigin(mock(Tree.class), site, type);
      SourceOrigin b = new SourceOrigin(mock(Tree.class), site, type);
      first.add(a);
      second.add(b);
      proof = proof.and(factory.conjunction(List.of(a)).or(factory.conjunction(List.of(b))));
    }
    assertThat(proof.nodeCount()).isEqualTo(40);
    assertThat(factory.allocatedNodeCount()).isLessThan(1000);
    assertThat(proof.availableSupport(first::contains).orElseThrow())
        .containsExactlyElementsIn(first)
        .inOrder();
    assertThat(proof.availableSupport(second::contains).orElseThrow())
        .containsExactlyElementsIn(second)
        .inOrder();
    Map<SourceOrigin, Integer> checks = new LinkedHashMap<>();
    assertThat(
            proof
                .availableSupport(
                    origin -> {
                      checks.merge(origin, 1, Integer::sum);
                      return true;
                    })
                .orElseThrow())
        .containsExactlyElementsIn(first)
        .inOrder();
    assertThat(checks.values()).containsExactlyElementsIn(java.util.Collections.nCopies(40, 1));
    for (int i = 0; i < 20; i++) {
      SourceOrigin a = first.get(i);
      SourceOrigin b = second.get(i);
      assertThat(proof.availableSupport(origin -> !origin.equals(a) && !origin.equals(b)))
          .isEmpty();
    }
    SourceSupport snapshot = proof;
    List<SourceOrigin> oldWitness = proof.availableSupport(first::contains).orElseThrow();
    SourceOrigin late = new SourceOrigin(mock(Tree.class), site, type);
    SourceSupport extended = proof.or(factory.conjunction(List.of(late)));
    assertThat(extended.availableSupport(late::equals).orElseThrow()).containsExactly(late);
    assertThat(snapshot.availableSupport(late::equals)).isEmpty();
    assertThat(oldWitness).containsExactlyElementsIn(first).inOrder();
    assertThat(snapshot.origins()).doesNotContain(late);
    assertThat(snapshot.and(extended)).isEqualTo(snapshot);
  }

  @Test(timeout = 120000)
  public void singleStructuredLowerDoesNotBuildReferencedValueProofs() {
    runProof(factoredProofDeclaration(false));
  }

  @Test(timeout = 120000)
  public void concreteConflictRetainsTwentyIndependentAlternativePremises() {
    runProof(factoredProofDeclaration(true));
  }

  /** Generates a compiler-owned twenty-slot shape with an independently conflicting final slot. */
  private static String factoredProofDeclaration(boolean conflict) {
    List<String> declarations = new ArrayList<>();
    List<String> variables = new ArrayList<>();
    List<String> concrete = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      variables.add("E" + i);
      declarations.add("E" + i + " extends @Nullable Object");
      concrete.add("String");
    }
    String methodVariables = String.join(", ", declarations);
    declarations.add("F extends @Nullable Object");
    variables.add("@Nullable String");
    concrete.add("String");
    String shape = "Tuple<" + String.join(", ", concrete) + ">";
    return "static class Tuple<"
        + String.join(", ", declarations)
        + "> {}\n<R extends @Nullable Object, "
        + methodVariables
        + "> void inference(String value, "
        + shape
        + " shape, Tuple<"
        + String.join(", ", variables)
        + "> symbolic, "
        + shape
        + " concrete, Object "
        + (conflict ? "conflictProof" : "singleProof")
        + ") {}";
  }

  @Test
  public void deduplicatedOuterSuppliersRemainAvailableAcrossFailedInnerPathsAndCycles() {
    runProof(
        """
        <R, U, D> void inference(
            Box<@Nullable String> first, Box<String> second, Box<String> shape,
            String independentShape, Box<R> dependentTemplate, Box<Box<String>> dependentShape,
            Box raw, String supportProof) { pick(id(first), second); id(second); id(first); }
        """);
  }

  @Test
  public void symbolicConflictSourcesRequireTheirNestedValueSupport() {
    runProof(
        """
        <R extends @Nullable Object, E extends @Nullable Object> void inference(
            Box<@Nullable String> first, Box<String> second, Pair<Box<String>, String> shape,
            Box<String> slotShape, Pair<E, @Nullable String> symbolic, String flatShape,
            Pair<E, String> concrete) {}
        """);
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
                static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
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
        if (method.getTypeParameters().size() == 21) {
          checkFactoredSupportProof(
              method,
              Objects.requireNonNull(declaration),
              Objects.requireNonNull(siteA),
              Objects.requireNonNull(siteB),
              state,
              config,
              reverse);
        } else if (method.getTypeParameters().size() == 3) {
          checkConflictProof(
              method,
              Objects.requireNonNull(declaration),
              Objects.requireNonNull(siteB),
              state,
              config,
              reverse);
        } else if (method.getParameters().size() == 7) {
          checkNestedSupportProof(
              method, Objects.requireNonNull(siteA), Objects.requireNonNull(siteB), state, config);
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
     * Exercises the solver path, not just the proof algebra: one structured lower requires no value
     * proof, while a concrete last-slot conflict needs all twenty independently supported values.
     */
    @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // select the original retained bound
    private static void checkFactoredSupportProof(
        Symbol.MethodSymbol method,
        MethodTree declaration,
        Tree site,
        Tree slotSite,
        VisitorState state,
        Config config,
        boolean reverse) {
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(new Handler() {});
      ConstraintSolverImpl solver = new ConstraintSolverImpl(config, state, analysis);
      Type value = method.getParameters().get(0).type;
      Type shape = method.getParameters().get(1).type;
      Type template = method.getParameters().get(2).type;
      Type concrete = method.getParameters().get(3).type;
      Element r = method.getTypeParameters().get(0);
      List<Symbol.TypeVariableSymbol> slots =
          new ArrayList<>(method.getTypeParameters().subList(1, 21));
      Map<Element, Type> shapes = new LinkedHashMap<>();
      slots.forEach(slot -> shapes.put(slot, value));
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(slotSite, slots, Map.of(), Set.copyOf(slots), shapes);
      List<SourceOrigin> first = new ArrayList<>();
      List<SourceOrigin> second = new ArrayList<>();
      for (Element slot : slots) {
        // Declaration trees give distinct compiler-owned origins even though every bound is String.
        SourceOrigin a =
            new SourceOrigin(
                declaration.getTypeParameters().get(first.size() + 1), slotSite, value);
        SourceOrigin b =
            new SourceOrigin(declaration.getTypeParameters().get(second.size() + 1), site, value);
        first.add(a);
        second.add(b);
        solver.addSubtypeConstraint(
            value, Objects.requireNonNull(fresh.get(slot)), false, reverse ? b : a);
        solver.addSubtypeConstraint(
            value, Objects.requireNonNull(fresh.get(slot)), false, reverse ? a : b);
      }
      Type root =
          Objects.requireNonNull(
              solver
                  .registerInferenceVariables(
                      site, List.of(r), Map.of(), Set.of(r), Map.of(r, shape))
                  .get(r));
      Type symbolic =
          TypeSubstitutionUtils.substituteTypeVariables(template, fresh, state.getTypes(), config);
      SourceOrigin symbolicOrigin =
          new SourceOrigin(declaration.getParameters().get(2), site, template);
      SourceOrigin concreteOrigin =
          new SourceOrigin(declaration.getParameters().get(3), site, concrete);
      solver.addSubtypeConstraint(symbolic, root, false, symbolicOrigin);
      boolean conflict =
          method.getParameters().get(4).getSimpleName().contentEquals("conflictProof");
      if (conflict) {
        solver.addSubtypeConstraint(concrete, root, false, concreteOrigin);
      }
      int beforeSolve = solver.sourceSupportNodeCount();
      Solution solution = solver.solve();
      if (!conflict) {
        assertThat(solution.isComplete()).isTrue();
        assertThat(solution.lowerConflicts()).isEmpty();
        assertThat(solver.sourceSupportNodeCount()).isEqualTo(beforeSolve);
        assertThat(solver.solve().lowerConflicts()).isEmpty();
        assertThat(solver.sourceSupportNodeCount()).isEqualTo(beforeSolve);
      } else {
        LowerBoundConflict fact =
            solution.lowerConflicts().stream()
                .filter(candidate -> candidate.variable().equals(new InferenceVariable(r, site)))
                .findFirst()
                .orElseThrow();
        BoundEvidence evidence = fact.first().type() == symbolic ? fact.first() : fact.second();
        assertThat(evidence.support().nodeCount()).isLessThan(200);
        assertThat(solver.sourceSupportNodeCount()).isLessThan(5000);
        List<SourceOrigin> firstWitness = new ArrayList<>(first);
        firstWitness.add(symbolicOrigin);
        List<SourceOrigin> secondWitness = new ArrayList<>(second);
        secondWitness.add(symbolicOrigin);
        assertThat(evidence.availableSupport(firstWitness::contains).orElseThrow())
            .containsExactlyElementsIn(firstWitness);
        assertThat(evidence.availableSupport(secondWitness::contains).orElseThrow())
            .containsExactlyElementsIn(secondWitness);
        for (int i = 0; i < 20; i++) {
          SourceOrigin a = first.get(i);
          SourceOrigin b = second.get(i);
          assertThat(
                  evidence.availableSupport(
                      origin ->
                          !origin.equals(a) && !origin.equals(b) && !origin.equals(concreteOrigin)))
              .isEmpty();
        }
        SourceOrigin late = new SourceOrigin(declaration.getParameters().get(4), slotSite, value);
        solver.addSubtypeConstraint(
            value, Objects.requireNonNull(fresh.get(slots.get(0))), false, late);
        List<SourceOrigin> lateWitness = new ArrayList<>(firstWitness);
        lateWitness.remove(first.get(0));
        lateWitness.add(late);
        assertThat(evidence.availableSupport(lateWitness::contains)).isEmpty();
        Solution updated = solver.solve();
        LowerBoundConflict updatedFact =
            updated.lowerConflicts().stream()
                .filter(candidate -> candidate.variable().equals(new InferenceVariable(r, site)))
                .findFirst()
                .orElseThrow();
        BoundEvidence updatedEvidence =
            updatedFact.first().type() == symbolic ? updatedFact.first() : updatedFact.second();
        assertThat(updatedEvidence.availableSupport(lateWitness::contains).orElseThrow())
            .containsExactlyElementsIn(lateWitness);
        assertThat(solver.solve().lowerConflicts()).isEqualTo(updated.lowerConflicts());
      }
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
        assertThat(
                firstEvidence
                    .availableSupport(origin -> !origin.equals(repeatedOrigin))
                    .orElseThrow())
            .containsExactly(firstOrigin, resultOrigin);
        assertThat(firstEvidence.availableSupport(origin -> !origin.equals(resultOrigin)))
            .isEmpty();
        assertThat(
                firstEvidence.availableSupport(origin -> !origin.equals(firstOrigin)).orElseThrow())
            .containsExactly(repeatedOrigin, resultOrigin);
        assertThat(secondEvidence.availableSupport(origin -> true).orElseThrow())
            .containsExactly(secondOrigin);
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
      assertThat(legacyConflict.first().availableSupport(origin -> false).orElseThrow()).isEmpty();
      if (method.getParameters().size() > 7) {
        SourceOrigin alternativeEdge =
            new SourceOrigin(declaration.getParameters().get(4), owner, nestedResultType);
        solver.addSubtypeConstraint(nested, root, false, alternativeEdge);
        LowerBoundConflict edgeConflict =
            solver.solve().lowerConflicts().stream()
                .filter(fact -> fact.variable().equals(rootKey))
                .findFirst()
                .orElseThrow();
        BoundEvidence edgeFirst =
            edgeConflict.first().type() == first ? edgeConflict.first() : edgeConflict.second();
        assertThat(edgeFirst.availableSupport(origin -> !origin.equals(resultOrigin)).orElseThrow())
            .containsExactly(firstOrigin, alternativeEdge);
        SourceOrigin outerFirst =
            new SourceOrigin(declaration.getParameters().get(0), owner, first);
        SourceOrigin innerSecond =
            new SourceOrigin(declaration.getParameters().get(1), nestedCall, second);
        solver.addSubtypeConstraint(first, root, false, outerFirst);
        solver.addSubtypeConstraint(second, nested, false, innerSecond);
        SourceOrigin cycle =
            new SourceOrigin(declaration.getParameters().get(2), nestedCall, shape);
        solver.addSubtypeConstraint(root, nested, false, cycle);
        Solution supported = solver.solve();
        LowerBoundConflict outerConflict =
            supported.lowerConflicts().stream()
                .filter(fact -> fact.variable().equals(rootKey))
                .findFirst()
                .orElseThrow();
        BoundEvidence outerNullable =
            outerConflict.first().type() == first ? outerConflict.first() : outerConflict.second();
        BoundEvidence outerClean =
            outerConflict.first().type() == second ? outerConflict.first() : outerConflict.second();
        assertThat(outerNullable.origins()).containsAtLeast(resultOrigin, outerFirst, cycle);
        assertThat(
                outerNullable
                    .availableSupport(
                        origin -> !origin.equals(resultOrigin) && !origin.equals(alternativeEdge))
                    .orElseThrow())
            .containsExactly(outerFirst);
        assertThat(
                outerClean
                    .availableSupport(
                        origin -> !origin.equals(resultOrigin) && !origin.equals(alternativeEdge))
                    .orElseThrow())
            .containsExactly(secondOrigin);
        assertThat(
                outerNullable.availableSupport(
                    origin ->
                        !origin.equals(resultOrigin)
                            && !origin.equals(alternativeEdge)
                            && !origin.equals(outerFirst)))
            .isEmpty();
        assertThat(
                Objects.requireNonNull(previous).lowerConflicts().stream()
                    .flatMap(fact -> java.util.stream.Stream.of(fact.first(), fact.second()))
                    .flatMap(bound -> bound.support().origins().stream())
                    .toList())
            .doesNotContain(outerFirst);
        assertThat(solver.solve().lowerConflicts()).isEqualTo(supported.lowerConflicts());
      }
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
      assertThat(derived.availableSupport(origin -> !origin.equals(firstOrigin))).isEmpty();
      assertThat(derived.availableSupport(origin -> !origin.equals(nestedOrigin))).isEmpty();
      assertThat(derived.availableSupport(origin -> !origin.equals(initialOrigin)).orElseThrow())
          .containsExactly(firstOrigin, nestedOrigin);
      assertThat(firstOrigin.originalType()).isSameInstanceAs(first);
      assertThat(nestedOrigin.originalType()).isSameInstanceAs(template);
      assertThat(
              solution.lowerConflicts().stream()
                  .flatMap(fact -> java.util.stream.Stream.of(fact.first(), fact.second()))
                  .flatMap(bound -> bound.origins().stream())
                  .toList())
          .contains(initialOrigin);
      ConstraintSolver upperSolver = new ConstraintSolverImpl(config, state, analysis);
      Type upperRoot =
          upperSolver
              .registerInferenceVariables(
                  rootCall, List.of(r), Map.of(), Set.of(r), Map.of(r, shape))
              .get(r);
      Type upperSlot =
          upperSolver
              .registerInferenceVariables(
                  slotCall, List.of(e), Map.of(), Set.of(e), Map.of(e, slotShape))
              .get(e);
      Type upperTemplate =
          TypeSubstitutionUtils.substituteTypeVariables(
              template, Map.of(e, upperSlot), state.getTypes(), config);
      upperSolver.addSubtypeConstraint(initial, upperSlot, false, initialOrigin);
      upperSolver.addSubtypeConstraint(first, upperRoot, false, firstOrigin);
      upperSolver.addSubtypeConstraint(upperRoot, upperTemplate, false, nestedOrigin);
      LowerBoundConflict upperConflict =
          upperSolver.solve().lowerConflicts().stream()
              .filter(fact -> fact.variable().equals(slotKey))
              .findFirst()
              .orElseThrow();
      BoundEvidence upperDerived =
          upperConflict.first().origins().contains(firstOrigin)
              ? upperConflict.first()
              : upperConflict.second();
      assertThat(upperDerived.availableSupport(origin -> !origin.equals(firstOrigin))).isEmpty();
      assertThat(upperDerived.availableSupport(origin -> !origin.equals(nestedOrigin))).isEmpty();
      assertThat(
              upperDerived.availableSupport(origin -> !origin.equals(initialOrigin)).orElseThrow())
          .containsExactly(firstOrigin, nestedOrigin);
    }

    /** Checks that symbolic conflict values need their own nested premises, not just context. */
    @SuppressWarnings({
      "ReferenceEquality",
      "TypeEquals"
    }) // select the retained original bound object
    private static void checkNestedSupportProof(
        Symbol.MethodSymbol method, Tree site, Tree slotSite, VisitorState state, Config config) {
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(new Handler() {});
      ConstraintSolver solver = new ConstraintSolverImpl(config, state, analysis);
      Element r = method.getTypeParameters().get(0);
      Element e = method.getTypeParameters().get(1);
      Type nestedValue = method.getParameters().get(1).type;
      Type shape = method.getParameters().get(2).type;
      Type slotShape = method.getParameters().get(3).type;
      Type template = method.getParameters().get(4).type;
      Type concreteTemplate = method.getParameters().get(6).type;
      Type root =
          solver
              .registerInferenceVariables(site, List.of(r), Map.of(), Set.of(r), Map.of(r, shape))
              .get(r);
      Type slot =
          solver
              .registerInferenceVariables(
                  slotSite, List.of(e), Map.of(), Set.of(e), Map.of(e, slotShape))
              .get(e);
      Type symbolic =
          TypeSubstitutionUtils.substituteTypeVariables(
              template, Map.of(e, slot), state.getTypes(), config);
      Type concrete =
          TypeSubstitutionUtils.substituteTypeVariables(
              concreteTemplate, Map.of(e, slot), state.getTypes(), config);
      SourceOrigin nestedOrigin = new SourceOrigin(slotSite, slotSite, nestedValue);
      SourceOrigin symbolicOrigin = new SourceOrigin(slotSite, site, template);
      SourceOrigin concreteOrigin = new SourceOrigin(site, site, concrete);
      solver.addSubtypeConstraint(nestedValue, slot, false, nestedOrigin);
      solver.addSubtypeConstraint(symbolic, root, false, symbolicOrigin);
      solver.addSubtypeConstraint(concrete, root, false, concreteOrigin);
      Solution before = solver.solve();
      LowerBoundConflict conflict =
          before.lowerConflicts().stream()
              .filter(fact -> fact.variable().equals(new InferenceVariable(r, site)))
              .findFirst()
              .orElseThrow();
      BoundEvidence symbolicEvidence =
          conflict.first().type() == symbolic ? conflict.first() : conflict.second();
      assertThat(symbolicEvidence.availableSupport(origin -> !origin.equals(nestedOrigin)))
          .isEmpty();
      assertThat(symbolicEvidence.availableSupport(origin -> !origin.equals(symbolicOrigin)))
          .isEmpty();
      assertThat(symbolicEvidence.availableSupport(origin -> true).orElseThrow())
          .containsExactly(symbolicOrigin, nestedOrigin);
      SourceOrigin alternative = new SourceOrigin(site, slotSite, nestedValue);
      solver.addSubtypeConstraint(nestedValue, slot, false, alternative);
      Solution after = solver.solve();
      LowerBoundConflict updated =
          after.lowerConflicts().stream()
              .filter(fact -> fact.variable().equals(new InferenceVariable(r, site)))
              .findFirst()
              .orElseThrow();
      BoundEvidence updatedSymbolic =
          updated.first().type() == symbolic ? updated.first() : updated.second();
      assertThat(
              updatedSymbolic
                  .availableSupport(origin -> !origin.equals(nestedOrigin))
                  .orElseThrow())
          .containsExactly(symbolicOrigin, alternative);
      assertThat(symbolicEvidence.availableSupport(origin -> !origin.equals(nestedOrigin)))
          .isEmpty();
      assertThat(solver.solve().lowerConflicts()).isEqualTo(after.lowerConflicts());
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
