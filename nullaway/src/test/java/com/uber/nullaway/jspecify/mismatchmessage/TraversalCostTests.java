package com.uber.nullaway.jspecify.mismatchmessage;

import org.junit.Test;

/**
 * Building the message costs time linear in the bound graph the check walks, however many paths
 * reach a shared wildcard bound. Each {@code L<i>} bounds its type parameter by a pair of two
 * {@code L<i-1><?>}, so a walk that revisits a bound once per path takes time exponential in the
 * depth, and the timeout of each test is far below what depth 24 takes then.
 */
public class TraversalCostTests extends MismatchMessageTestBase {

  @Test(timeout = 30_000)
  public void aSharedBoundBesideTheMismatchIsWalkedOnce() {
    assertMessage(
        """
        incompatible nullability in P type argument A: found @Nullable String, required String
            found:    P<@Nullable String, L24<?>>
                        ^^^^^^^^^^^^^^^^
            required: P<String, L24<?>>
                        ^^^^^^
            fix: declare x as P<@Nullable String, L24<?>>
        """,
        """
        interface P<A extends @Nullable Object, B> {}
        interface L0<T> {}
        interface L1<T extends P<L0<?>, L0<?>>> {}
        interface L2<T extends P<L1<?>, L1<?>>> {}
        interface L3<T extends P<L2<?>, L2<?>>> {}
        interface L4<T extends P<L3<?>, L3<?>>> {}
        interface L5<T extends P<L4<?>, L4<?>>> {}
        interface L6<T extends P<L5<?>, L5<?>>> {}
        interface L7<T extends P<L6<?>, L6<?>>> {}
        interface L8<T extends P<L7<?>, L7<?>>> {}
        interface L9<T extends P<L8<?>, L8<?>>> {}
        interface L10<T extends P<L9<?>, L9<?>>> {}
        interface L11<T extends P<L10<?>, L10<?>>> {}
        interface L12<T extends P<L11<?>, L11<?>>> {}
        interface L13<T extends P<L12<?>, L12<?>>> {}
        interface L14<T extends P<L13<?>, L13<?>>> {}
        interface L15<T extends P<L14<?>, L14<?>>> {}
        interface L16<T extends P<L15<?>, L15<?>>> {}
        interface L17<T extends P<L16<?>, L16<?>>> {}
        interface L18<T extends P<L17<?>, L17<?>>> {}
        interface L19<T extends P<L18<?>, L18<?>>> {}
        interface L20<T extends P<L19<?>, L19<?>>> {}
        interface L21<T extends P<L20<?>, L20<?>>> {}
        interface L22<T extends P<L21<?>, L21<?>>> {}
        interface L23<T extends P<L22<?>, L22<?>>> {}
        interface L24<T extends P<L23<?>, L23<?>>> {}
        static P<@Nullable String, L24<?>> get() {
          throw new RuntimeException();
        }
        void test() {
          // BUG: Diagnostic matches: MESSAGE
          P<String, L24<?>> x = get();
        }
        """);
  }

  @Test(timeout = 30_000)
  public void aSharedBoundBesideANestedMismatchIsWalkedOnce() {
    assertMessage(
        """
        incompatible nullability in List type argument E -> Box type argument B: found @Nullable String, required String
            found:    List<Box<L24<?>, @Nullable String>>
                                       ^^^^^^^^^^^^^^^^
            required: List<Box<L24<?>, String>>
                                       ^^^^^^
            fix: declare x as List<Box<L24<?>, @Nullable String>>
        """,
        """
        interface P<A extends @Nullable Object, B> {}
        interface L0<T> {}
        interface L1<T extends P<L0<?>, L0<?>>> {}
        interface L2<T extends P<L1<?>, L1<?>>> {}
        interface L3<T extends P<L2<?>, L2<?>>> {}
        interface L4<T extends P<L3<?>, L3<?>>> {}
        interface L5<T extends P<L4<?>, L4<?>>> {}
        interface L6<T extends P<L5<?>, L5<?>>> {}
        interface L7<T extends P<L6<?>, L6<?>>> {}
        interface L8<T extends P<L7<?>, L7<?>>> {}
        interface L9<T extends P<L8<?>, L8<?>>> {}
        interface L10<T extends P<L9<?>, L9<?>>> {}
        interface L11<T extends P<L10<?>, L10<?>>> {}
        interface L12<T extends P<L11<?>, L11<?>>> {}
        interface L13<T extends P<L12<?>, L12<?>>> {}
        interface L14<T extends P<L13<?>, L13<?>>> {}
        interface L15<T extends P<L14<?>, L14<?>>> {}
        interface L16<T extends P<L15<?>, L15<?>>> {}
        interface L17<T extends P<L16<?>, L16<?>>> {}
        interface L18<T extends P<L17<?>, L17<?>>> {}
        interface L19<T extends P<L18<?>, L18<?>>> {}
        interface L20<T extends P<L19<?>, L19<?>>> {}
        interface L21<T extends P<L20<?>, L20<?>>> {}
        interface L22<T extends P<L21<?>, L21<?>>> {}
        interface L23<T extends P<L22<?>, L22<?>>> {}
        interface L24<T extends P<L23<?>, L23<?>>> {}
        static class Box<A extends @Nullable Object, B extends @Nullable Object> {}
        void test(List<Box<L24<?>, @Nullable String>> l) {
          // BUG: Diagnostic matches: MESSAGE
          List<Box<L24<?>, String>> x = l;
        }
        """);
  }
}
