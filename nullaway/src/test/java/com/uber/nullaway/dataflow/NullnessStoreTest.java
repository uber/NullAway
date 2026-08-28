package com.uber.nullaway.dataflow;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class NullnessStoreTest {

  @Test
  public void unreachableStoreLeastUpperBound() {
    NullnessStore empty = NullnessStore.empty();
    NullnessStore unreachable = NullnessStore.unreachable();

    assertThat(unreachable.leastUpperBound(unreachable)).isSameInstanceAs(unreachable);
    assertThat(unreachable.leastUpperBound(empty)).isSameInstanceAs(empty);
    assertThat(empty.leastUpperBound(unreachable)).isSameInstanceAs(empty);
  }

  @Test
  public void unreachableStoreIsDistinctFromEmptyStore() {
    NullnessStore empty = NullnessStore.empty();
    NullnessStore unreachable = NullnessStore.unreachable();

    assertThat(unreachable.isUnreachable()).isTrue();
    assertThat(empty.isUnreachable()).isFalse();
    assertThat(unreachable).isNotEqualTo(empty);
  }
}
