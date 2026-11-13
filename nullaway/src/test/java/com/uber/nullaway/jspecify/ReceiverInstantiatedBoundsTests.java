package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Receiver-instantiated bounds remain coherent across annotated method-variable occurrences. */
public class ReceiverInstantiatedBoundsTests extends NullAwayTestsBase {

  private static final String HEADER =
      """
      import java.io.Serializable;
      import java.util.ArrayList;
      import java.util.List;
      import org.jspecify.annotations.NullMarked;
      import org.jspecify.annotations.Nullable;
      @NullMarked
      class Test {
        static class Receiver<E extends @Nullable Object> {
          <U extends E> U identity(U value) { return value; }
          <U extends @Nullable T, T extends List<E>> U forward(U value) { return value; }
          <T extends List<E>, U extends @Nullable T> U reverse(U value) { return value; }
          <U extends @Nullable V, V extends @Nullable T, T extends List<E>> U forwardChain(
              U value) { return value; }
          <T extends List<E>, V extends @Nullable T, U extends @Nullable V> U reverseChain(
              U value) { return value; }
          <U extends List<E> & Serializable> U intersection(U value) { return value; }
          <U extends List<@Nullable T> & Serializable, T extends List<E>> U nestedForward(
              U value, T witness) { return value; }
          <T extends List<E>, U extends List<@Nullable T> & Serializable> U nestedReverse(
              U value, T witness) { return value; }
        }
      """;

  @Test
  public void annotatedForwardPeersUseTheCompletedBound() {
    check(
        """
          void test(Receiver<@Nullable String> receiver, List<@Nullable String> values) {
            List<@Nullable String> result = receiver.forward(values);
            // BUG: Diagnostic contains: incompatible types
            List<String> invalid = receiver.forward(values);
          }
        """);
  }

  @Test
  public void annotatedReversePeersUseTheCompletedBound() {
    check(
        """
          void test(Receiver<@Nullable String> receiver, List<@Nullable String> values) {
            List<@Nullable String> result = receiver.reverse(values);
            // BUG: Diagnostic contains: incompatible types
            List<String> invalid = receiver.reverse(values);
          }
        """);
  }

  @Test
  public void annotatedPeerChainsWorkInBothDeclarationOrders() {
    check(
        """
          void test(Receiver<@Nullable String> nullableReceiver, Receiver<String> nonNullReceiver,
              List<@Nullable String> nullableValues, List<String> nonNullValues) {
            List<@Nullable String> forward = nullableReceiver.forwardChain(nullableValues);
            List<@Nullable String> reverse = nullableReceiver.reverseChain(nullableValues);
            List<String> nonNullForward = nonNullReceiver.forwardChain(nonNullValues);
            List<String> nonNullReverse = nonNullReceiver.reverseChain(nonNullValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.forwardChain(nullableValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.reverseChain(nullableValues);
          }
        """);
  }

  @Test
  public void alternatingReceiversDoNotMutateSharedDeclarationBounds() {
    check(
        """
          void nullableFirst(Receiver<@Nullable String> nullableReceiver,
              Receiver<String> nonNullReceiver, List<@Nullable String> nullableValues,
              List<String> nonNullValues) {
            nullableReceiver.forward(nullableValues);
            nonNullReceiver.forward(nonNullValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.forward(nullableValues);
            nullableReceiver.forward(nullableValues);
            nullableReceiver.forwardChain(nullableValues);
            nonNullReceiver.forwardChain(nonNullValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.forwardChain(nullableValues);
            nullableReceiver.forwardChain(nullableValues);
          }
          void nonNullFirst(Receiver<@Nullable String> nullableReceiver,
              Receiver<String> nonNullReceiver, List<@Nullable String> nullableValues,
              List<String> nonNullValues) {
            nonNullReceiver.reverse(nonNullValues);
            nullableReceiver.reverse(nullableValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.reverse(nullableValues);
            nonNullReceiver.reverse(nonNullValues);
            nonNullReceiver.reverseChain(nonNullValues);
            nullableReceiver.reverseChain(nullableValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.reverseChain(nullableValues);
            nonNullReceiver.reverseChain(nonNullValues);
          }
        """);
  }

  @Test
  public void ordinaryReceiversKeepTheirScalarBoundNullability() {
    check(
        """
          void test(Receiver<@Nullable String> nullableReceiver, Receiver<String> nonNullReceiver,
              @Nullable String nullableValue) {
            nullableReceiver.identity(nullableValue);
            nullableReceiver.identity(null);
            String result = nonNullReceiver.identity("value");
            // BUG: Diagnostic contains: inference failure
            nonNullReceiver.identity(null);
            // BUG: Diagnostic contains: inference failure
            nonNullReceiver.identity(nullableValue);
            nullableReceiver.identity(null);
            String unchanged = nonNullReceiver.identity("value");
          }
        """);
  }

  @Test
  public void wildcardReceiversKeepTheirEffectiveBoundNullability() {
    check(
        """
          void test(Receiver<?> unbounded, Receiver<? extends @Nullable String> nullableUpper,
              Receiver<? extends String> nonNullUpper, Receiver<? super String> lowerBounded) {
            unbounded.identity(null);
            nullableUpper.identity(null);
            // BUG: Diagnostic contains: inference failure
            nonNullUpper.identity(null);
            lowerBounded.identity(null);
            unbounded.identity(null);
            nullableUpper.identity(null);
            // BUG: Diagnostic contains: inference failure
            nonNullUpper.identity(null);
          }
        """);
  }

  @Test
  public void intersectionComponentsPreserveReceiverNullability() {
    check(
        """
          void test(Receiver<@Nullable String> nullableReceiver, Receiver<String> nonNullReceiver,
              ArrayList<@Nullable String> nullableValues, ArrayList<String> nonNullValues) {
            ArrayList<@Nullable String> nullableResult = nullableReceiver.intersection(nullableValues);
            ArrayList<String> nonNullResult = nonNullReceiver.intersection(nonNullValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.intersection(nullableValues);
          }
        """);
  }

  @Test
  public void annotatedPeersInIntersectionComponentsUseCompletedBounds() {
    check(
        """
          void test(Receiver<@Nullable String> nullableReceiver, Receiver<String> nonNullReceiver,
              ArrayList<@Nullable List<@Nullable String>> nullableValues,
              ArrayList<@Nullable List<String>> nonNullValues,
              List<@Nullable String> nullableWitness, List<String> nonNullWitness) {
            nullableReceiver.nestedForward(nullableValues, nullableWitness);
            nullableReceiver.nestedReverse(nullableValues, nullableWitness);
            nonNullReceiver.nestedForward(nonNullValues, nonNullWitness);
            nonNullReceiver.nestedReverse(nonNullValues, nonNullWitness);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.nestedForward(nullableValues, nonNullWitness);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.nestedReverse(nullableValues, nonNullWitness);
          }
        """);
  }

  @Test
  public void alternatingIntersectionReceiversDoNotMutateSharedBounds() {
    check(
        """
          void test(Receiver<@Nullable String> nullableReceiver, Receiver<String> nonNullReceiver,
              ArrayList<@Nullable String> nullableValues, ArrayList<String> nonNullValues,
              ArrayList<@Nullable List<@Nullable String>> nullableNested,
              ArrayList<@Nullable List<String>> nonNullNested,
              List<@Nullable String> nullableWitness, List<String> nonNullWitness) {
            nullableReceiver.intersection(nullableValues);
            nonNullReceiver.intersection(nonNullValues);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.intersection(nullableValues);
            nullableReceiver.intersection(nullableValues);
            nullableReceiver.nestedForward(nullableNested, nullableWitness);
            nonNullReceiver.nestedForward(nonNullNested, nonNullWitness);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.nestedForward(nullableNested, nonNullWitness);
            nullableReceiver.nestedForward(nullableNested, nullableWitness);
            nonNullReceiver.nestedReverse(nonNullNested, nonNullWitness);
            nullableReceiver.nestedReverse(nullableNested, nullableWitness);
            // BUG: Diagnostic contains: incompatible types
            nonNullReceiver.nestedReverse(nullableNested, nonNullWitness);
            nonNullReceiver.nestedReverse(nonNullNested, nonNullWitness);
          }
        """);
  }

  /** Checks a source body against the common receiver declarations in a nullness-marked context. */
  private void check(String body) {
    makeHelper().addSourceLines("Test.java", HEADER + body + "\n}\n").doTest();
  }

  /** Creates a JSpecify helper with inference diagnostics enabled by the standard javac config. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
