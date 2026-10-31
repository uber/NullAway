package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.oracles.AnnotationMutator.Variant;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

/**
 * The mutants of a seed depend on the seed's name, the configuration, and the run's random seed
 * alone, so a test added before a seed leaves that seed's mutants as they were. They draw on the
 * candidates of the mutators the run names, and a generated program runs under its own
 * configuration. The families of the growth oracle are not seeds.
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

  /** Returns a runner whose mutants draw on the candidates of {@code mutators}. */
  private static OracleRunner runner(String... mutators) {
    return new OracleRunner(
        KnownFindings.parse(List.of()),
        new Compiler(),
        OracleRunner.RUN_TIMEOUT,
        List.of(mutators));
  }

  @Test
  public void eachMutatorAddsItsCandidates() {
    Seed seed = seed("A.java#a");
    assertThat(runner("annotation").candidates(seed))
        .containsExactlyElementsIn(AnnotationMutator.candidates(seed));
    assertThat(runner("type").candidates(seed))
        .containsExactlyElementsIn(TypeMutator.candidates(seed));
    assertThat(runner("annotation", "type").candidates(seed))
        .hasSize(AnnotationMutator.candidates(seed).size() + TypeMutator.candidates(seed).size());
  }

  @Test
  public void anUnknownMutatorIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> runner("annotation", "syntax"));
  }

  @Test
  public void aGeneratedProgramRunsUnderTheConfigurationOfItsRow() throws IOException {
    CombinationGenerator.Row row = CombinationGenerator.generate(1).get(0);
    OracleRunner.Input input = OracleRunner.inputs(List.of("generated"), List.of("1")).get(0);
    assertThat(input.config()).isEqualTo(row.config());
    assertThat(input.seed().sources()).containsExactly(row.source());
  }

  @Test
  public void theBoundGraphsSourceSelectsTheFamiliesTheFilterMatches() {
    assertThat(OracleRunner.families(List.of("tests", "bound-graphs"), "back-edge none ass"))
        .containsExactly(
            new BoundGraphGenerator.Family(
                BoundGraphGenerator.Shape.BACK_EDGE,
                BoundGraphGenerator.Nullness.NONE,
                BoundGraphGenerator.Use.ASSIGNMENT));
    assertThat(OracleRunner.families(List.of("tests"), "back-edge none ass")).isEmpty();
  }

  @Test
  public void theBoundGraphsSourceAddsNoSeed() throws IOException {
    assertThat(OracleRunner.inputs(List.of("bound-graphs"), List.of("1"))).isEmpty();
  }

  @Test
  public void anUnknownSeedSourceIsRejected() {
    List<String> sources = List.of("generated", "tests-and-more");
    List<String> generatorSeeds = List.of("1");
    assertThrows(
        IllegalArgumentException.class, () -> OracleRunner.inputs(sources, generatorSeeds));
  }
}
