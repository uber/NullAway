package com.uber.nullaway;

import static com.google.common.truth.Truth.assertThat;
import static com.uber.nullaway.Nullness.BOTTOM;
import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NO_CHANGE;
import static com.uber.nullaway.Nullness.NULL;
import static com.uber.nullaway.Nullness.NULLABLE;
import static com.uber.nullaway.Nullness.NULL_NO_CHANGE;

import com.google.common.truth.Expect;
import org.junit.Rule;
import org.junit.Test;

/**
 * {@link Nullness} values form a lattice: {@link Nullness#NO_CHANGE} lies between {@link
 * Nullness#NONNULL} and {@link Nullness#NULLABLE}, and {@link Nullness#NULL_NO_CHANGE} is the meet
 * of {@code NO_CHANGE} and {@link Nullness#NULL}.
 */
public class NullnessTest {

  @Rule public final Expect expect = Expect.create();

  /** Each row is two values, then their least upper bound, then their greatest lower bound. */
  private static final Nullness[][] JOIN_AND_MEET = {
    {NULLABLE, NULL, NULLABLE, NULL},
    {NULLABLE, NO_CHANGE, NULLABLE, NO_CHANGE},
    {NULLABLE, NULL_NO_CHANGE, NULLABLE, NULL_NO_CHANGE},
    {NULLABLE, NONNULL, NULLABLE, NONNULL},
    {NULLABLE, BOTTOM, NULLABLE, BOTTOM},
    {NULL, NO_CHANGE, NULLABLE, NULL_NO_CHANGE},
    {NULL, NULL_NO_CHANGE, NULL, NULL_NO_CHANGE},
    {NULL, NONNULL, NULLABLE, BOTTOM},
    {NULL, BOTTOM, NULL, BOTTOM},
    {NO_CHANGE, NULL_NO_CHANGE, NO_CHANGE, NULL_NO_CHANGE},
    {NO_CHANGE, NONNULL, NO_CHANGE, NONNULL},
    {NO_CHANGE, BOTTOM, NO_CHANGE, BOTTOM},
    {NULL_NO_CHANGE, NONNULL, NO_CHANGE, BOTTOM},
    {NULL_NO_CHANGE, BOTTOM, NULL_NO_CHANGE, BOTTOM},
    {NONNULL, BOTTOM, NONNULL, BOTTOM},
  };

  @Test
  public void joinAndMeetOfEveryPairFollowTheLattice() {
    for (Nullness[] row : JOIN_AND_MEET) {
      Nullness a = row[0];
      Nullness b = row[1];
      expect
          .withMessage("%s.leastUpperBound(%s)", a, b)
          .that(a.leastUpperBound(b))
          .isEqualTo(row[2]);
      expect
          .withMessage("%s.leastUpperBound(%s)", b, a)
          .that(b.leastUpperBound(a))
          .isEqualTo(row[2]);
      expect
          .withMessage("%s.greatestLowerBound(%s)", a, b)
          .that(a.greatestLowerBound(b))
          .isEqualTo(row[3]);
      expect
          .withMessage("%s.greatestLowerBound(%s)", b, a)
          .that(b.greatestLowerBound(a))
          .isEqualTo(row[3]);
    }
  }

  @Test
  public void joinAndMeetAreAssociative() {
    for (Nullness a : Nullness.values()) {
      for (Nullness b : Nullness.values()) {
        for (Nullness c : Nullness.values()) {
          expect
              .withMessage("lub of %s, %s, %s grouped both ways", a, b, c)
              .that(a.leastUpperBound(b).leastUpperBound(c))
              .isEqualTo(a.leastUpperBound(b.leastUpperBound(c)));
          expect
              .withMessage("glb of %s, %s, %s grouped both ways", a, b, c)
              .that(a.greatestLowerBound(b).greatestLowerBound(c))
              .isEqualTo(a.greatestLowerBound(b.greatestLowerBound(c)));
        }
      }
    }
  }

  @Test
  public void inequalityWithAValueKnownNullDeducesNonNull() {
    assertThat(NULL_NO_CHANGE.deducedValueWhenNotEqual()).isEqualTo(NONNULL);
  }

  @Test
  public void inequalityWithNoChangeDeducesNothing() {
    assertThat(NO_CHANGE.deducedValueWhenNotEqual()).isEqualTo(NULLABLE);
  }

  @Test
  public void onlyNullAndNullNoChangeAreKnownNull() {
    expect.that(NULL.isNull()).isTrue();
    expect.that(NULL_NO_CHANGE.isNull()).isTrue();
    expect.that(NO_CHANGE.isNull()).isFalse();
    expect.that(NULLABLE.isNull()).isFalse();
  }

  @Test
  public void onlyNoChangeAndNullNoChangeAreAcceptedByTheirTypeVariable() {
    expect.that(NO_CHANGE.isNoChange()).isTrue();
    expect.that(NULL_NO_CHANGE.isNoChange()).isTrue();
    expect.that(NULL.isNoChange()).isFalse();
    expect.that(NONNULL.isNoChange()).isFalse();
  }

  @Test
  public void aHolderThatRejectsNoChangeKeepsWhetherTheValueIsKnownNull() {
    expect.that(NO_CHANGE.withoutNoChange()).isEqualTo(NULLABLE);
    expect.that(NULL_NO_CHANGE.withoutNoChange()).isEqualTo(NULL);
    expect.that(NONNULL.withoutNoChange()).isEqualTo(NONNULL);
  }
}
