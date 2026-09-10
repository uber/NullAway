package com.uber.nullaway.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.List;
import org.junit.Test;

public class SourceOrderTest {

  @Test
  public void equalDiagnosticsPass() {
    SourceOrder.compare(
        List.of("Test.java:3: a", "Other.java:1: b"), List.of("Test.java:3: a", "Other.java:1: b"));
  }

  @Test
  public void aDiagnosticThatMovedToAnotherFileFails() {
    List<String> asGiven = List.of("Test.java:3: a");
    List<String> reversed = List.of("Other.java:3: a");
    AssertionError error =
        assertThrows(AssertionError.class, () -> SourceOrder.compare(asGiven, reversed));
    assertThat(error)
        .hasMessageThat()
        .contains("Only in the order the test gave:\n  Test.java:3: a");
    assertThat(error).hasMessageThat().contains("Only in the reversed order:\n  Other.java:3: a");
  }

  @Test
  public void aDiagnosticThatAppearedOnlyInOneOrderFails() {
    List<String> asGiven = List.of();
    List<String> reversed = List.of("Test.java:3: a");
    AssertionError error =
        assertThrows(AssertionError.class, () -> SourceOrder.compare(asGiven, reversed));
    assertThat(error).hasMessageThat().contains("Reversing the order of the compilation units");
  }
}
