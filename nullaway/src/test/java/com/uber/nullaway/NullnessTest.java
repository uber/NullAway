package com.uber.nullaway;

import static com.google.common.truth.Truth.assertThat;
import static com.uber.nullaway.Nullness.BOTTOM;
import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NO_CHANGE;
import static com.uber.nullaway.Nullness.NULL;
import static com.uber.nullaway.Nullness.NULLABLE;
import static com.uber.nullaway.Nullness.NULL_NO_CHANGE;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Expect;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
  public void joinAndMeetTableListsEveryPairOfDistinctValuesOnce() {
    List<Set<Nullness>> listed = new ArrayList<>();
    for (Nullness[] row : JOIN_AND_MEET) {
      listed.add(EnumSet.of(row[0], row[1]));
    }
    List<Set<Nullness>> distinctPairs = new ArrayList<>();
    Nullness[] values = Nullness.values();
    for (int i = 0; i < values.length; i++) {
      for (int j = i + 1; j < values.length; j++) {
        distinctPairs.add(EnumSet.of(values[i], values[j]));
      }
    }
    assertThat(listed).containsExactlyElementsIn(distinctPairs);
  }

  @Test
  public void joinAndMeetOfAValueWithItselfIsThatValue() {
    for (Nullness a : Nullness.values()) {
      expect.withMessage("%s.leastUpperBound(%s)", a, a).that(a.leastUpperBound(a)).isEqualTo(a);
      expect
          .withMessage("%s.greatestLowerBound(%s)", a, a)
          .that(a.greatestLowerBound(a))
          .isEqualTo(a);
    }
  }

  @Test
  public void joinAndMeetAbsorbEachOther() {
    for (Nullness a : Nullness.values()) {
      for (Nullness b : Nullness.values()) {
        expect
            .withMessage("%s.leastUpperBound(%s.greatestLowerBound(%s))", a, a, b)
            .that(a.leastUpperBound(a.greatestLowerBound(b)))
            .isEqualTo(a);
        expect
            .withMessage("%s.greatestLowerBound(%s.leastUpperBound(%s))", a, a, b)
            .that(a.greatestLowerBound(a.leastUpperBound(b)))
            .isEqualTo(a);
      }
    }
  }

  @Test
  public void onlyNullAndNullNoChangeAreKnownNull() {
    Set<Nullness> knownNull = EnumSet.of(NULL, NULL_NO_CHANGE);
    for (Nullness value : Nullness.values()) {
      expect
          .withMessage("%s.isNull()", value)
          .that(value.isNull())
          .isEqualTo(knownNull.contains(value));
    }
  }

  @Test
  public void onlyNoChangeAndNullNoChangeAreAcceptedByTheirTypeVariable() {
    Set<Nullness> noChange = EnumSet.of(NO_CHANGE, NULL_NO_CHANGE);
    for (Nullness value : Nullness.values()) {
      expect
          .withMessage("%s.isNoChange()", value)
          .that(value.isNoChange())
          .isEqualTo(noChange.contains(value));
    }
  }

  @Test
  public void aHolderThatRejectsNoChangeKeepsWhetherTheValueIsKnownNull() {
    ImmutableMap<Nullness, Nullness> changed =
        ImmutableMap.of(NO_CHANGE, NULLABLE, NULL_NO_CHANGE, NULL);
    for (Nullness value : Nullness.values()) {
      expect
          .withMessage("%s.withoutNoChange()", value)
          .that(value.withoutNoChange())
          .isEqualTo(changed.getOrDefault(value, value));
    }
  }
}
