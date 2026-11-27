package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.uber.nullaway.oracles.KnownFindings.Verdict;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * A finding is known when an entry of the known-findings file has its exact key, or, for a
 * divergence, the same key with {@code *} for the seed, or, for a growth finding, the key with
 * {@code *} for any word of the family; every other finding is new.
 */
public class KnownFindingsTest {

  private static final String NPE =
      "java.lang.NullPointerException at com.uber.nullaway.NullAway.f";
  private static final String TEMPLATE = "[NullAway] dereferenced expression '_' is @Nullable";

  private static KnownFindings of(String... lines) {
    return KnownFindings.parse(List.of(lines));
  }

  @Test
  public void aListedCrashSignatureIsKnownWithItsVerdict() {
    KnownFindings known = of("crash\t" + NPE + "\t#1991");
    assertThat(known.crash(NPE)).hasValue(new Verdict("#1991", ""));
  }

  @Test
  public void anUnlistedCrashSignatureIsNew() {
    KnownFindings known = of("crash\t" + NPE + "\t#1991");
    assertThat(known.crash("java.lang.NullPointerException at com.uber.nullaway.NullAway.g"))
        .isEmpty();
  }

  @Test
  public void aDivergenceIsKnownOnlyOnTheSideItsEntryNames() {
    KnownFindings known = of("divergence\tcast\tA.java#f\tright\t" + TEMPLATE + "\t#1993");
    assertThat(known.divergence("cast", "A.java#f", "right", TEMPLATE))
        .hasValue(new Verdict("#1993", ""));
    assertThat(known.divergence("cast", "A.java#f", "left", TEMPLATE)).isEmpty();
  }

  @Test
  public void aWildcardSeedMatchesEverySeed() {
    KnownFindings known =
        of("divergence\tparentheses\t*\tleft\t" + TEMPLATE + "\tnoise\tquotes differ");
    assertThat(known.divergence("parentheses", "B.java#g", "left", TEMPLATE))
        .hasValue(new Verdict("noise", "quotes differ"));
  }

  @Test
  public void anEntryForTheSeedWinsOverAWildcardEntry() {
    KnownFindings known =
        of(
            "divergence\tcast\t*\tright\t" + TEMPLATE + "\tnoise\tquotes differ",
            "divergence\tcast\tA.java#f\tright\t" + TEMPLATE + "\t#1993");
    assertThat(known.divergence("cast", "A.java#f", "right", TEMPLATE))
        .hasValue(new Verdict("#1993", ""));
  }

  @Test
  public void aGrowthEntryMatchesOnlyItsFamily() {
    KnownFindings known = of("growth\tback-edge\tnone\tassignment\t#2093");
    assertThat(known.growth("back-edge none assignment")).hasValue(new Verdict("#2093", ""));
    assertThat(known.growth("back-edge none argument")).isEmpty();
  }

  @Test
  public void aWildcardWordOfAGrowthEntryMatchesEveryValueOfThatWord() {
    KnownFindings known = of("growth\tback-edge\t*\t*\tpending\tone cause");
    assertThat(known.growth("back-edge mixed lambda"))
        .hasValue(new Verdict("pending", "one cause"));
    assertThat(known.growth("subtype mixed lambda")).isEmpty();
  }

  @Test
  public void theFirstGrowthEntryThatMatchesAFamilyWins() {
    KnownFindings known =
        of("growth\tback-edge\tnone\tassignment\t#1", "growth\tback-edge\t*\t*\t#2");
    assertThat(known.growth("back-edge none assignment")).hasValue(new Verdict("#1", ""));
    assertThat(known.growth("back-edge none argument")).hasValue(new Verdict("#2", ""));
  }

  @Test
  public void aGrowthEntryWithoutItsUseIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> of("growth\tback-edge\tnone\t#2093"));
  }

  @Test
  public void commentsAndBlankLinesAreNotEntries() {
    KnownFindings known = of("# crash\t" + NPE + "\t#1991", "", "crash\t" + NPE + "\t#1990");
    assertThat(known.crash(NPE)).hasValue(new Verdict("#1990", ""));
  }

  @Test
  public void entriesNoLookupMatchedAreReportedAsUnmatched() {
    KnownFindings known =
        of("crash\t" + NPE + "\t#1991", "crash\tjava.lang.Error at com.uber.nullaway.X.y\t#1992");
    Optional<Verdict> unused = known.crash(NPE);
    assertThat(known.unmatched())
        .containsExactly(
            new KnownFindings.Entry(
                "crash\tjava.lang.Error at com.uber.nullaway.X.y", new Verdict("#1992", ""), 2));
  }

  @Test
  public void aPendingVerdictWithoutANoteIsRejected() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> of("crash\t" + NPE + "\tpending"));
    assertThat(e).hasMessageThat().contains("line 1");
  }

  @Test
  public void anUnknownVerdictIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> of("crash\t" + NPE + "\tfixed"));
  }

  @Test
  public void anUnknownSideIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> of("divergence\tcast\tA.java#f\tboth\t" + TEMPLATE + "\t#1993"));
  }

  @Test
  public void aDivergenceWithoutItsTemplateIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> of("divergence\tcast\tA.java#f\tright\t#1993"));
  }

  @Test
  public void aRepeatedKeyIsRejected() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> of("crash\t" + NPE + "\t#1991", "crash\t" + NPE + "\t#1990"));
    assertThat(e).hasMessageThat().contains("line 2");
  }

  /** Guards the checked-in file, which a hand edit can break. */
  @Test
  public void theCheckedInFileParses() throws Exception {
    KnownFindings known = KnownFindings.read(Path.of("src/oracles/known-findings.tsv"));
    assertThat(known.unmatched()).isNotEmpty();
  }

  @Test
  public void aNoiseVerdictWithoutANoteIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> of("crash\t" + NPE + "\tnoise"));
  }

  @Test
  public void aLineWithAnExtraFieldIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> of("crash\t" + NPE + "\t#1991\tnote\textra"));
  }

  @Test
  public void anUnknownKindIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> of("hang\t" + NPE + "\t#1991"));
  }
}
