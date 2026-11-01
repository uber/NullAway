package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.errorprone.CompilationTestHelper;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.ConstraintSolver.BoundEvidence;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.LowerBoundConflict;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import com.uber.nullaway.generics.ConstraintSolver.SourceOrigin;
import com.uber.nullaway.generics.ConstraintSolver.SourceSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Source-support unit tests and checker-level regressions for shared inference shapes. */
public class ShapeGuidedInferenceTests extends NullAwayTestsBase {

  @Test
  public void nestedUpperBoundViolationPreservesInferenceSiteAndCause() {
    Element variable = mock(Element.class);
    Tree site = mock(Tree.class);
    Type lower = mock(Type.class);
    Type upper = mock(Type.class);
    ConstraintSolver.NestedUpperBoundViolationException failure =
        new ConstraintSolver.NestedUpperBoundViolationException(variable, site, lower, upper);
    assertThat(failure.getTypeVariable()).isSameInstanceAs(variable);
    assertThat(failure.getInferenceSite()).isSameInstanceAs(site);
    assertThat(failure.getSite()).isSameInstanceAs(site);
    assertThat(failure.getLowerBound()).isSameInstanceAs(lower);
    assertThat(failure.getUpperBound()).isSameInstanceAs(upper);
    assertThat(failure.isCausedByNonNullUpperBound()).isFalse();
  }

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

  @Test
  public void sourceProofEqualityDistinguishesDifferentRequirements() {
    Tree site = mock(Tree.class);
    Type type = mock(Type.class);
    SourceOrigin a = new SourceOrigin(mock(Tree.class), site, type);
    SourceOrigin b = new SourceOrigin(mock(Tree.class), site, type);
    SourceSupport.Factory factory = new SourceSupport.Factory();
    SourceSupport first = factory.conjunction(List.of(a));
    SourceSupport second = factory.conjunction(List.of(b));
    assertThat(first).isNotEqualTo(second);
    assertThat(first.and(second)).isNotEqualTo(first.or(second));
    assertThat(first).isNotEqualTo(factory.conjunction(List.of()));
    assertThat(factory.conjunction(List.of())).isNotEqualTo(new SourceSupport(List.of()));
    assertThat(first).isNotEqualTo(new Object());
  }

  @Test
  public void witnessPreferenceUsesOriginOrderRatherThanFactoryAllocationOrder() {
    Tree site = mock(Tree.class);
    Type type = mock(Type.class);
    SourceOrigin a = new SourceOrigin(mock(Tree.class), site, type);
    SourceOrigin b = new SourceOrigin(mock(Tree.class), site, type);
    SourceSupport.Factory factory = new SourceSupport.Factory();
    SourceSupport first = factory.conjunction(List.of(a));
    SourceSupport second = factory.conjunction(List.of(b));
    assertThat(first.or(second)).isEqualTo(second.or(first));
    assertThat(first.or(second).availableSupport(origin -> true).orElseThrow()).containsExactly(a);
    assertThat(second.or(first).availableSupport(origin -> true).orElseThrow()).containsExactly(b);
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
    assertThat(factory.allocatedNodeCount()).isAtLeast(proof.nodeCount());
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

  /** Creates the ordinary full-inference helper with wildcard containment enabled. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
