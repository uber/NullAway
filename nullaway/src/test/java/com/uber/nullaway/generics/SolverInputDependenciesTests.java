package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.sun.source.tree.Tree;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.InputDependencies;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Unit tests for immutable solver input-dependency records and solution defaults. */
public class SolverInputDependenciesTests {

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
}
