package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/**
 * End-to-end regressions for balanced generic inference problems.
 *
 * <p>Routine tests use 31/63-call expressions to avoid spending most of the regression time in
 * javac's own poly-expression inference. Larger workloads also run through NullAway rather than
 * directly inspecting solver state or javac types. To profile the original 255/511-call sources,
 * run {@code ./gradlew :nullaway:test --tests '*BalancedInferenceCallsTests'
 * -PbalancedInferenceStress=true}.
 */
public class BalancedInferenceCallsTests extends NullAwayTestsBase {

  @Test
  public void nullableListLeaves() {
    checkNullableListLeaves(Boolean.getBoolean("nullaway.balancedInferenceStress") ? 255 : 31);
  }

  @Test
  public void largerNullableListLeaves() {
    checkNullableListLeaves(Boolean.getBoolean("nullaway.balancedInferenceStress") ? 511 : 63);
  }

  @Test
  public void contextualTargetAndIndependentNestedCalls31Calls() {
    checkContextualTargetAndIndependentNestedCalls(31);
  }

  @Test
  public void contextualTargetAndIndependentNestedCalls63Calls() {
    checkContextualTargetAndIndependentNestedCalls(63);
  }

  @Test
  public void dataflowRefinementDoesNotRetainAnEarlierNullableResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            source(
                """
                void test(@Nullable String value) {
                  // BUG: Diagnostic contains: dereferenced expression
                  %s.length();
                  if (value == null) { return; }
                  %s.length();
                  value = null;
                  // BUG: Diagnostic contains: dereferenced expression
                  %s.length();
                }
                """
                    .formatted(
                        balancedPick(31, "value", "value"),
                        balancedPick(63, "value", "value"),
                        balancedPick(31, "value", "value"))))
        .doTest();
  }

  @Test
  public void dataflowReuseTracksAssignmentsWithinAnInferenceRoot() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            source(
                """
                void test(@Nullable String value) {
                  if (value == null) { return; }
                  var nonNull = %s;
                  nonNull.length();
                  var nullable = pick(%s, pick(value = null, value));
                  // BUG: Diagnostic contains: dereferenced expression
                  nullable.length();
                }
                """
                    .formatted(
                        balancedPick(31, "value", "value"), balancedPick(31, "value", "value"))))
        .doTest();
  }

  @Test
  public void dataflowReuseTracksLoopStoreChangesAtTheSameCallSite() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            source(
                """
                void test(@Nullable String value, boolean repeat) {
                  if (value == null) { return; }
                  while (repeat) {
                    // BUG: Diagnostic contains: dereferenced expression
                    %s.length();
                    value = null;
                  }
                }
                """
                    .formatted(balancedPick(31, "value", "value"))))
        .doTest();
  }

  @Test
  public void dataflowReuseTracksReceiverInputsAtTheSameCallSite() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            receiverSource(
                """
                void test(@Nullable String value, boolean repeat) {
                  if (value == null) { return; }
                  while (repeat) {
                    // BUG: Diagnostic contains: dereferenced expression
                    box(value).list(0).get(0).length();
                    value = null;
                  }
                }
                """))
        .doTest();
  }

  @Test
  public void dataflowReuseTracksReceiverInputsForInferredLocals() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            receiverSource(
                """
                void loop(@Nullable String value, boolean repeat) {
                  if (value == null) { return; }
                  while (repeat) {
                    var values = box(value).list(0);
                    // BUG: Diagnostic contains: dereferenced expression
                    values.get(0).length();
                    value = null;
                  }
                }
                void assignment(@Nullable String value) {
                  if (value == null) { return; }
                  var nonNull = box(value).list(0);
                  nonNull.get(0).length();
                  value = null;
                  var nullable = box(value).list(0);
                  // BUG: Diagnostic contains: dereferenced expression
                  nullable.get(0).length();
                }
                """))
        .doTest();
  }

  @Test
  public void provisionalLambdaParametersPreserveEachFinalNestedType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            source(
                """
                interface Mapper<A extends @Nullable Object, R extends @Nullable Object> {
                  R apply(A value);
                }
                static <A extends @Nullable Object, R extends @Nullable Object> R map(
                    A value, Mapper<A, R> mapper) {
                  return mapper.apply(value);
                }
                void test(List<@Nullable String> nullable, List<String> nonNull) {
                  List<@Nullable String> nullableResult = map(nullable, value -> %s);
                  // BUG: Diagnostic contains: dereferenced expression
                  nullableResult.get(0).length();
                  List<String> nonNullResult = map(nonNull, value -> %s);
                  nonNullResult.get(0).length();
                }
                """
                    .formatted(
                        balancedPick(31, "value", "value"), balancedPick(31, "value", "value"))))
        .doTest();
  }

  /**
   * Checks a successful nullable-element target and independently verifies that an inferred local
   * retains nullable elements. Separate JUnit methods expose each workload's duration without a
   * machine-dependent timing assertion.
   */
  private void checkNullableListLeaves(int callCount) {
    makeHelper()
        .addSourceLines(
            "Test.java",
            source(
                """
                List<@Nullable String> target(List<@Nullable String> a, List<@Nullable String> b) {
                  return %s;
                }
                void inferred(List<@Nullable String> a, List<@Nullable String> b) {
                  var result = %s;
                  acceptsNullable(result);
                  // BUG: Diagnostic contains: dereferenced expression
                  result.get(0).length();
                }
                """
                    .formatted(
                        balancedPick(callCount, "a", "b"), balancedPick(callCount, "a", "b"))))
        .doTest();
  }

  /**
   * Checks both a contradictory target and a failed enclosing problem with independently valid
   * nested calls. Subsequent standalone checks must still distinguish non-null and nullable leaves.
   */
  private void checkContextualTargetAndIndependentNestedCalls(int callCount) {
    int nestedCallCount = callCount / 2;
    makeHelper()
        .addSourceLines(
            "Test.java",
            source(
                """
                List<String> wrongTarget(List<@Nullable String> a, List<@Nullable String> b) {
                  // BUG: Diagnostic contains: incompatible types
                  return %s;
                }
                void nested(List<String> nonNull, List<@Nullable String> nullable) {
                  // BUG: Diagnostic contains: incompatible types
                  acceptsNonNull(pick(%s, %s));
                  acceptsNonNull(%s);
                  acceptsNullable(%s);
                  %s.get(0).length();
                  // BUG: Diagnostic contains: dereferenced expression
                  %s.get(0).length();
                }
                """
                    .formatted(
                        balancedPick(callCount, "a", "b"),
                        balancedPick(nestedCallCount, "nonNull", "nonNull"),
                        balancedPick(nestedCallCount, "nullable", "nullable"),
                        balancedPick(nestedCallCount, "nonNull", "nonNull"),
                        balancedPick(nestedCallCount, "nullable", "nullable"),
                        balancedPick(nestedCallCount, "nonNull", "nonNull"),
                        balancedPick(nestedCallCount, "nullable", "nullable"))))
        .doTest();
  }

  /**
   * Generates a perfect binary expression tree with exactly {@code callCount} invocations of {@code
   * pick}. The two supplied leaf expressions swap roles in each right subtree; valid counts are
   * zero or one less than a power of two.
   */
  private static String balancedPick(int callCount, String firstLeaf, String secondLeaf) {
    if (callCount < 0 || (callCount & (callCount + 1)) != 0) {
      throw new IllegalArgumentException("Expected a perfect binary tree call count: " + callCount);
    }
    if (callCount == 0) {
      return firstLeaf;
    }
    int childCallCount = callCount / 2;
    return "pick("
        + balancedPick(childCallCount, firstLeaf, secondLeaf)
        + ", "
        + balancedPick(childCallCount, secondLeaf, firstLeaf)
        + ")";
  }

  /** Adds a generic receiver whose fixed element type depends on its inferred factory input. */
  private static String receiverSource(String members) {
    return source(
        """
        static class Box<T extends @Nullable Object> {
          final T value;
          Box(T value) { this.value = value; }
          <U> List<T> list(U ignored) {
            return java.util.Collections.singletonList(value);
          }
        }
        static <T extends @Nullable Object> Box<T> box(T value) {
          return new Box<>(value);
        }
        """
            + members);
  }

  /** Wraps generated test members in a self-contained nullness-marked class. */
  private static String source(String members) {
    return """
        import java.util.List;
        import org.jspecify.annotations.NullMarked;
        import org.jspecify.annotations.Nullable;
        @NullMarked
        class Test {
          static <T extends @Nullable Object> T pick(T a, T b) { return a; }
          static void acceptsNullable(List<@Nullable String> values) {}
          static void acceptsNonNull(List<String> values) {}
        """
        + members
        + "}\n";
  }

  /** Creates a helper without depending on package-based nullness configuration. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
