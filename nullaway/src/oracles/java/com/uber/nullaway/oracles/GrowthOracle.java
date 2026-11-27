package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.oracles.BoundGraphGenerator.Family;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Checks that the time of one NullAway run grows slowly with the depth of a {@link
 * BoundGraphGenerator} program: a family of programs is compiled at each of {@link #DEPTHS} in
 * turn, and its check stops at the first depth whose run is slow.
 *
 * <p>A run is slow when it takes at least {@link #SLOW_RUN}, or at least {@link #JUMP_FLOOR} and
 * {@link #JUMP_RATIO} times the run at the depth before it. A run that is slow but under twice
 * {@link #SLOW_RUN} is repeated once and judged by the faster of the two, since a pause of the JVM
 * can slow one run of a fast program. In the report of issue #2093, a graph of five levels took
 * 19.4 s where four levels took 1.9 s.
 *
 * <p>The check stops at the first slow depth, so a family whose time grows exponentially costs one
 * slow run and not the run at the deepest depth.
 */
final class GrowthOracle {

  private GrowthOracle() {}

  /**
   * The depths a family is compiled at, in order. The step grows only past the depth at which an
   * exponential family has already shown itself, so that the first slow run stays short.
   */
  static final ImmutableList<Integer> DEPTHS =
      ImmutableList.of(2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16);

  /** The time at which a run is slow whatever the depth before it took. */
  static final Duration SLOW_RUN = Duration.ofSeconds(2);

  /** The least time at which a run is slow because of its ratio to the depth before it. */
  static final Duration JUMP_FLOOR = Duration.ofMillis(500);

  /** The ratio to the depth before it at which a run of at least {@link #JUMP_FLOOR} is slow. */
  static final int JUMP_RATIO = 4;

  /**
   * Compiles the program of one depth with NullAway.
   *
   * <p>{@link #run} returns the time of a run that ended with NullAway's warnings, or null for a
   * run that ended otherwise, which the caller has already recorded as a crash or an invalid
   * source.
   */
  @FunctionalInterface
  interface Runner {
    @Nullable Duration run(int depth, Source source);
  }

  /**
   * The time of the run at one depth.
   *
   * @param depth the depth of the program
   * @param elapsed the wall-clock time of the run, the faster of two where the run was repeated
   */
  record Measurement(int depth, Duration elapsed) {}

  /**
   * A family whose time grew too fast.
   *
   * @param family the family
   * @param config the configuration its programs ran under, a key of {@link OracleRunner#CONFIGS}
   * @param measurements every depth that ran, the slow one last
   * @param source the program at the slow depth
   */
  record Finding(
      Family family, String config, ImmutableList<Measurement> measurements, Source source) {

    /** Returns the slow measurement. */
    Measurement slow() {
      return measurements.get(measurements.size() - 1);
    }
  }

  /**
   * Compiles {@code family} at each of {@link #DEPTHS} until one run is slow or does not end with
   * warnings, and returns a finding for the slow run, or null if every depth ran fast.
   */
  static @Nullable Finding check(Family family, String config, Runner runner) {
    List<Measurement> measurements = new ArrayList<>();
    for (int depth : DEPTHS) {
      Source source = family.source(depth);
      Duration elapsed = runner.run(depth, source);
      if (elapsed == null) {
        return null;
      }
      Duration previous =
          measurements.isEmpty() ? null : measurements.get(measurements.size() - 1).elapsed();
      if (isSlow(previous, elapsed) && elapsed.compareTo(SLOW_RUN.multipliedBy(2)) < 0) {
        Duration again = runner.run(depth, source);
        if (again == null) {
          return null;
        }
        if (again.compareTo(elapsed) < 0) {
          elapsed = again;
        }
      }
      measurements.add(new Measurement(depth, elapsed));
      if (isSlow(previous, elapsed)) {
        return new Finding(family, config, ImmutableList.copyOf(measurements), source);
      }
    }
    return null;
  }

  /**
   * Returns whether a run that took {@code elapsed} is slow.
   *
   * @param previous the time of the run at the depth before it, or null at the first depth
   */
  static boolean isSlow(@Nullable Duration previous, Duration elapsed) {
    if (elapsed.compareTo(SLOW_RUN) >= 0) {
      return true;
    }
    return previous != null
        && elapsed.compareTo(JUMP_FLOOR) >= 0
        && elapsed.compareTo(previous.multipliedBy(JUMP_RATIO)) >= 0;
  }
}
