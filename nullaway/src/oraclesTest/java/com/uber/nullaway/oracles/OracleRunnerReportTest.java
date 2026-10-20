package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A crash signature the known findings do not list fails the run, appears under the new crashes of
 * the summary, and is written to {@code new-findings.tsv} as a line that parses once its verdict is
 * filled in; a listed signature does none of these.
 */
public class OracleRunnerReportTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * The signature of every crash of {@link CompilerTest.ThrowingChecker}, which is not NullAway.
   */
  private static final String SIGNATURE =
      "java.lang.IllegalStateException at " + CrashSignature.NO_NULLAWAY_FRAME;

  private static final Seed SEED =
      new Seed(
          "A.java#a",
          ImmutableList.of(
              new Source("com/uber/A.java", "package com.uber; class A { void f() {} }")));

  /**
   * Returns a runner that has checked {@link #SEED} with a checker that throws on every method, so
   * that its one finding is a crash.
   */
  private static OracleRunner afterACrash(KnownFindings known) {
    return afterACrash(known, ImmutableList.of());
  }

  private static OracleRunner afterACrash(KnownFindings known, ImmutableList<Relation> relations) {
    OracleRunner runner = new OracleRunner(known, new Compiler(CompilerTest.ThrowingChecker.class));
    runner.check(SEED, List.of("legacy"), relations, false, 1, 0);
    return runner;
  }

  /** Throws from {@link Relation#variants}, standing in for a defect of a relation. */
  private static final Relation THROWING =
      new Relation() {
        @Override
        public String name() {
          return "throwing";
        }

        @Override
        public ImmutableList<ImmutableList<Source>> variants(Seed seed) {
          throw new IllegalStateException("thrown by the relation");
        }
      };

  @Test
  public void anUnlistedCrashSignatureIsNewInTheSummaryAndFailsTheRun() throws Exception {
    OracleRunner runner = afterACrash(KnownFindings.parse(List.of()));
    String summary = runner.writeReport(temporaryFolder.getRoot().toPath(), 1, 1, Duration.ZERO);
    assertThat(runner.foundNewCrash()).isTrue();
    assertThat(summary).contains("### New crashes\n\n- `" + SIGNATURE + "`, 1 inputs");
  }

  @Test
  public void anUnlistedCrashSignatureIsWrittenAsALineForTheKnownFindings() throws Exception {
    Path out = temporaryFolder.getRoot().toPath();
    afterACrash(KnownFindings.parse(List.of())).writeReport(out, 1, 1, Duration.ZERO);
    String line = Files.readString(out.resolve("new-findings.tsv"));
    assertThat(line).isEqualTo("crash\t" + SIGNATURE + "\tTODO\n");
    assertThat(KnownFindings.parse(List.of(line.strip().replace("TODO", "#1"))).crash(SIGNATURE))
        .isPresent();
  }

  @Test
  public void aListedCrashSignatureIsKnownAndDoesNotFailTheRun() throws Exception {
    Path out = temporaryFolder.getRoot().toPath();
    OracleRunner runner = afterACrash(KnownFindings.parse(List.of("crash\t" + SIGNATURE + "\t#1")));
    String summary = runner.writeReport(out, 1, 1, Duration.ZERO);
    assertThat(runner.foundNewCrash()).isFalse();
    assertThat(summary).contains("### New crashes\n\nNone.");
    assertThat(Files.readString(out.resolve("new-findings.tsv"))).isEmpty();
  }

  @Test
  public void aRelationThatThrowsIsCountedAndTheSeedIsStillCompiled() throws Exception {
    OracleRunner runner = afterACrash(KnownFindings.parse(List.of()), ImmutableList.of(THROWING));
    String summary = runner.writeReport(temporaryFolder.getRoot().toPath(), 1, 1, Duration.ZERO);
    assertThat(summary).contains("A relation or the mutator threw 1 times");
    assertThat(runner.foundNewCrash()).isTrue();
  }
}
