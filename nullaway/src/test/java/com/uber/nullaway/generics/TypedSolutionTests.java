package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Typed candidates must not erase incompleteness, inconsistency, or snapshot boundaries. */
public class TypedSolutionTests {
  @Test
  public void candidatesAndCertificationAreImmutableIndependentSnapshots() {
    Tree firstSite = mock(Tree.class);
    Tree secondSite = mock(Tree.class);
    InferenceVariable first = new InferenceVariable(mock(Element.class), firstSite);
    InferenceVariable second = new InferenceVariable(mock(Element.class), secondSite);
    Map<InferenceVariable, Type> candidates = new LinkedHashMap<>();
    candidates.put(first, Type.noType);
    candidates.put(second, Type.noType);
    Set<InferenceVariable> incomplete = new LinkedHashSet<>(Set.of(first));
    Set<InferenceVariable> inconsistent = new LinkedHashSet<>(Set.of(second));
    Solution result = new Solution(candidates, incomplete, inconsistent);
    candidates.clear();
    incomplete.clear();
    inconsistent.clear();
    assertThat(result.inferredTypes()).hasSize(2);
    assertThat(result.incompleteVariables()).containsExactly(first);
    assertThat(result.inconsistentVariables()).containsExactly(second);
    assertThat(result.isComplete()).isFalse();
    assertThat(result.isCompleteForSite(firstSite)).isFalse();
    assertThat(result.isCompleteForSite(secondSite)).isFalse();
    assertThrows(UnsupportedOperationException.class, () -> result.inferredTypes().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.incompleteVariables().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.inconsistentVariables().clear());
    assertThat(new Solution(Map.of(first, Type.noType), Set.of(), Set.of()).isComplete()).isTrue();
  }
}
