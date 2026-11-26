package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Tests the nullable Void return exemption in plain and experimental JSpecify modes. */
@RunWith(Parameterized.class)
public class GenericVoidReturnTests extends NullAwayTestsBase {

  @Parameterized.Parameters(name = "experimental={0}")
  public static Object[] experimentalModes() {
    return new Object[] {false, true};
  }

  private final boolean experimental;

  public GenericVoidReturnTests(boolean experimental) {
    this.experimental = experimental;
  }

  @Test
  public void voidReturnContextsAllowNullableResults() {
    List<String> args =
        new ArrayList<>(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                "-XepOpt:NullAway:CheckContracts=true",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG));
    if (experimental) {
      args.add(JSpecifyJavacConfig.JSPECIFY_EXPERIMENTAL);
    }
    makeTestHelperWithArgs(args)
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jetbrains.annotations.Contract;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T nid(T value) { return value; }
              static Void requiresNonNull(String value) { return null; }
              interface VoidFn { Void run(); }
              interface NullableVoidFn { @Nullable Void run(); }
              interface StringFn { String run(); }
              interface ListFn { List<Void> run(); }
              interface GenericFn<R extends @Nullable Object> { R run(); }
              static <U> void exec(VoidFn function, U value) {}
              static <U> void execNullable(NullableVoidFn function, U value) {}
              static <U> void execString(StringFn function, U value) {}
              static <U> void execList(ListFn function, U value) {}
              static <U> void execGeneric(GenericFn<Void> function, U value) {}
              static void execPlain(VoidFn function) {}

              void assignedLambda(@Nullable Void value) {
                VoidFn function = () -> nid(value);
              }
              void assignedBlockLambda(@Nullable Void value) {
                VoidFn function = () -> { return nid(value); };
              }
              void plainLambdaArgument(@Nullable Void value) {
                execPlain(() -> nid(value));
              }
              void plainBlockLambdaArgument(@Nullable Void value) {
                execPlain(() -> { return nid(value); });
              }
              void genericLambdaArgument(@Nullable Void value) {
                exec(() -> nid(value), "x");
              }
              void genericBlockLambdaArgument(@Nullable Void value) {
                exec(() -> { return nid(value); }, "x");
              }
              void nestedGenericLambdaArgument(@Nullable Void value) {
                exec(() -> nid(nid(value)), "x");
              }
              void explicitlyNullableLambdaArgument(@Nullable Void value) {
                execNullable(() -> nid(value), "x");
              }
              void nonNullLambdaReturn(@Nullable String value) {
                // BUG: Diagnostic contains: inference failure
                execString(() -> nid(value), "x");
              }
              void invalidLambdaArgument(@Nullable String value) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                exec(() -> requiresNonNull(value), "x");
              }
              void incompatibleLambdaTypeArguments(List<@Nullable Void> values) {
                // BUG: Diagnostic contains: incompatible types:
                execList(() -> values, "x");
              }
              void substitutedVoidLambdaReturn(@Nullable Void value) {
                // BUG: Diagnostic contains: inference failure
                execGeneric(() -> nid(value), "x");
              }

              Void direct(@Nullable Void value) { return value; }
              @Nullable Void explicitlyNullable(@Nullable Void value) { return nid(value); }
              Void inferred(@Nullable Void value) { return nid(value); }
              @Contract("-> !null")
              Void violatesNonNullContract() {
                // BUG: Diagnostic contains: Method violatesNonNullContract has @Contract(-> !null), but this appears to be violated
                return nid(null);
              }
              Void nested(@Nullable Void value) { return nid(nid(value)); }
              Void conditional(boolean condition, @Nullable Void value) {
                return condition ? nid(value) : value;
              }
              Void parenthesized(@Nullable Void value) { return (nid(value)); }
              Void invalidArgument(@Nullable String value) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                return requiresNonNull(value);
              }
              List<Void> incompatibleTypeArguments(List<@Nullable Void> values) {
                // BUG: Diagnostic contains: incompatible types:
                return values;
              }

              interface Visitor<R extends @Nullable Object> { R visit(Node node); }
              interface Node { <R extends @Nullable Object> R accept(Visitor<R> visitor); }
              static class NullableVoidVisitor implements Visitor<@Nullable Void> {
                public Void visit(Node node) {
                  // Return inference honors the same nullable Void exemption as direct returns.
                  return node.accept(this);
                }
              }
            }
            """)
        .doTest();
  }
}
