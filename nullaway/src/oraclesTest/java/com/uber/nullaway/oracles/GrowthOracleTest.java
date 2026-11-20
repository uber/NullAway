package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.truth.Expect;
import com.uber.nullaway.oracles.BoundGraphGenerator.Family;
import com.uber.nullaway.oracles.BoundGraphGenerator.Nullness;
import com.uber.nullaway.oracles.BoundGraphGenerator.Shape;
import com.uber.nullaway.oracles.BoundGraphGenerator.Use;
import com.uber.nullaway.oracles.GrowthOracle.Measurement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;

/**
 * A run is slow at two seconds, or at four times the run at the depth before it once it takes half
 * a second. The check of a family stops at the first slow depth, repeats a slow run under four
 * seconds once and keeps the faster time, and stops without a finding at a run that ends without
 * warnings.
 */
public class GrowthOracleTest {

  @Rule public final Expect expect = Expect.create();

  private static final Family FAMILY = new Family(Shape.BACK_EDGE, Nullness.NONE, Use.ASSIGNMENT);

  /**
   * Stands in for NullAway: each run at a depth returns the next time scripted for that depth, in
   * milliseconds, where -1 is a run that ends without warnings; a depth whose script is used up
   * runs in 100 ms. Records the depth of every run.
   */
  private static final class ScriptedRunner implements GrowthOracle.Runner {
    private final Map<Integer, Deque<Long>> script = new HashMap<>();
    final List<Integer> runs = new ArrayList<>();

    ScriptedRunner at(int depth, long... millis) {
      Deque<Long> queue = new ArrayDeque<>();
      for (long m : millis) {
        queue.add(m);
      }
      script.put(depth, queue);
      return this;
    }

    @Override
    public @Nullable Duration run(int depth, Source source) {
      runs.add(depth);
      Deque<Long> queue = script.get(depth);
      long millis = queue == null || queue.isEmpty() ? 100 : queue.poll();
      return millis < 0 ? null : Duration.ofMillis(millis);
    }
  }

  private static Duration ms(long millis) {
    return Duration.ofMillis(millis);
  }

  @Test
  public void aRunIsSlowFromTwoSecondsWhateverTheDepthBeforeIt() {
    expect.withMessage("isSlow(null, 2000 ms)").that(GrowthOracle.isSlow(null, ms(2000))).isTrue();
    expect.withMessage("isSlow(null, 1999 ms)").that(GrowthOracle.isSlow(null, ms(1999))).isFalse();
    expect
        .withMessage("isSlow(1900 ms, 2000 ms)")
        .that(GrowthOracle.isSlow(ms(1900), ms(2000)))
        .isTrue();
  }

  @Test
  public void aRunIsSlowAtFourTimesTheDepthBeforeItOnceItTakesHalfASecond() {
    expect
        .withMessage("isSlow(200 ms, 800 ms)")
        .that(GrowthOracle.isSlow(ms(200), ms(800)))
        .isTrue();
    expect
        .withMessage("isSlow(200 ms, 799 ms)")
        .that(GrowthOracle.isSlow(ms(200), ms(799)))
        .isFalse();
    expect
        .withMessage("isSlow(100 ms, 500 ms)")
        .that(GrowthOracle.isSlow(ms(100), ms(500)))
        .isTrue();
    expect
        .withMessage("isSlow(100 ms, 499 ms)")
        .that(GrowthOracle.isSlow(ms(100), ms(499)))
        .isFalse();
  }

  @Test
  public void aFamilyThatStaysFastRunsAtEveryDepthWithoutAFinding() {
    ScriptedRunner runner = new ScriptedRunner();
    assertThat(GrowthOracle.check(FAMILY, "c", runner)).isNull();
    assertThat(runner.runs).containsExactlyElementsIn(GrowthOracle.DEPTHS).inOrder();
  }

  @Test
  public void theCheckStopsAtTheFirstSlowDepthWithEveryDepthBeforeIt() {
    ScriptedRunner runner = new ScriptedRunner().at(3, 110).at(4, 3000, 3100);
    GrowthOracle.Finding finding = GrowthOracle.check(FAMILY, "c", runner);
    assertThat(finding).isNotNull();
    assertThat(finding.measurements())
        .containsExactly(
            new Measurement(2, ms(100)), new Measurement(3, ms(110)), new Measurement(4, ms(3000)))
        .inOrder();
    assertThat(finding.source()).isEqualTo(FAMILY.source(4));
    assertThat(runner.runs).containsExactly(2, 3, 4, 4).inOrder();
  }

  @Test
  public void aRunIsComparedWithTheDepthJustBeforeIt() {
    ScriptedRunner runner = new ScriptedRunner().at(3, 300).at(4, 900);
    assertThat(GrowthOracle.check(FAMILY, "c", runner)).isNull();
  }

  @Test
  public void aSlowRunThatIsFastWhenRepeatedDoesNotStopTheCheck() {
    ScriptedRunner runner = new ScriptedRunner().at(3, 2500, 120);
    assertThat(GrowthOracle.check(FAMILY, "c", runner)).isNull();
    assertThat(runner.runs).containsExactly(2, 3, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16).inOrder();
  }

  @Test
  public void aRunOfFourSecondsIsAFindingWithoutARepeat() {
    ScriptedRunner runner = new ScriptedRunner().at(2, 4000, 100);
    GrowthOracle.Finding finding = GrowthOracle.check(FAMILY, "c", runner);
    assertThat(finding).isNotNull();
    assertThat(finding.slow()).isEqualTo(new Measurement(2, ms(4000)));
    assertThat(runner.runs).containsExactly(2);
  }

  @Test
  public void aRepeatThatEndsWithoutWarningsStopsTheCheckWithoutAFinding() {
    ScriptedRunner runner = new ScriptedRunner().at(3, 3000, -1);
    assertThat(GrowthOracle.check(FAMILY, "c", runner)).isNull();
    assertThat(runner.runs).containsExactly(2, 3, 3).inOrder();
  }

  @Test
  public void aRunThatEndsWithoutWarningsStopsTheCheckWithoutAFinding() {
    ScriptedRunner runner = new ScriptedRunner().at(3, -1);
    assertThat(GrowthOracle.check(FAMILY, "c", runner)).isNull();
    assertThat(runner.runs).containsExactly(2, 3).inOrder();
  }
}
