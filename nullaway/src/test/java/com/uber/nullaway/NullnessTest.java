package com.uber.nullaway;

import static com.google.common.truth.Truth.assertThat;
import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NO_CHANGE;
import static com.uber.nullaway.Nullness.NULL;
import static com.uber.nullaway.Nullness.NULLABLE;

import org.junit.Test;

/**
 * {@link Nullness#NO_CHANGE} lies between {@link Nullness#NONNULL} and {@link Nullness#NULLABLE},
 * and meets {@link Nullness#NULL} at {@code NO_CHANGE}: a no-change value equal to null is null
 * only where its type variable admits null.
 */
public class NullnessTest {

  @Test
  public void joiningNoChangeWithNonNullKeepsNoChange() {
    assertThat(NO_CHANGE.leastUpperBound(NONNULL)).isEqualTo(NO_CHANGE);
    assertThat(NONNULL.leastUpperBound(NO_CHANGE)).isEqualTo(NO_CHANGE);
  }

  @Test
  public void joiningNoChangeWithNullIsNullable() {
    assertThat(NO_CHANGE.leastUpperBound(NULL)).isEqualTo(NULLABLE);
    assertThat(NULL.leastUpperBound(NO_CHANGE)).isEqualTo(NULLABLE);
  }

  @Test
  public void meetingNoChangeWithNonNullIsNonNull() {
    assertThat(NO_CHANGE.greatestLowerBound(NONNULL)).isEqualTo(NONNULL);
    assertThat(NONNULL.greatestLowerBound(NO_CHANGE)).isEqualTo(NONNULL);
  }

  @Test
  public void meetingNoChangeWithNullKeepsNoChange() {
    assertThat(NO_CHANGE.greatestLowerBound(NULL)).isEqualTo(NO_CHANGE);
    assertThat(NULL.greatestLowerBound(NO_CHANGE)).isEqualTo(NO_CHANGE);
  }

  @Test
  public void inequalityWithNoChangeDeducesNothing() {
    assertThat(NO_CHANGE.deducedValueWhenNotEqual()).isEqualTo(NULLABLE);
  }
}
