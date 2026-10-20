package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.oracles.AnnotationMutator.Variant;
import org.junit.Test;

/**
 * The mutants of a seed depend on the seed's name, the configuration, and the run's random seed
 * alone, so a test added before a seed leaves that seed's mutants as they were.
 */
public class OracleRunnerTest {

  /** Returns a seed with a dozen candidate mutations, so that two random draws rarely agree. */
  private static Seed seed(String name) {
    return new Seed(
        name,
        ImmutableList.of(
            new Source(
                "com/uber/A.java",
                """
                package com.uber;
                class A {
                  java.util.List<String> f;
                  Object g(String a, Object[] b, java.util.Map<String, Object> c) { return a; }
                }
                """)));
  }

  private static ImmutableList<Variant> mutants(Seed seed, long randomSeed) {
    return OracleRunner.mutants(seed, AnnotationMutator.candidates(seed), "legacy", randomSeed, 3);
  }

  @Test
  public void aSeedGetsTheSameMutantsWhetherOrNotAnotherSeedRanBeforeIt() {
    ImmutableList<Variant> alone = mutants(seed("B.java#b"), 1);
    ImmutableList<Variant> unused = mutants(seed("A.java#a"), 1);
    assertThat(mutants(seed("B.java#b"), 1)).isEqualTo(alone);
  }

  @Test
  public void anotherRandomSeedGivesTheSeedOtherMutants() {
    assertThat(mutants(seed("B.java#b"), 2)).isNotEqualTo(mutants(seed("B.java#b"), 1));
  }
}
