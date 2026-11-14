package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Tests the nullable Void return exemption with and without experimental inference diagnostics. */
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
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T nid(T value) { return value; }
              static Void requiresNonNull(String value) { return null; }

              Void direct(@Nullable Void value) { return value; }
              @Nullable Void explicitlyNullable(@Nullable Void value) { return nid(value); }
              Void inferred(@Nullable Void value) { return nid(value); }
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
