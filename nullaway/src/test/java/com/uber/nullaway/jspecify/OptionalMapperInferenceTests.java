package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Frontend regressions for preserving nullable mapper targets through completed inference. */
@RunWith(Parameterized.class)
public class OptionalMapperInferenceTests extends NullAwayTestsBase {

  /** Exercises completed targets both with and without wildcard-aware containment checks. */
  @Parameterized.Parameters(name = "wildcardGenerics={0}")
  public static Object[] configurations() {
    return new Object[] {false, true};
  }

  @Parameterized.Parameter public boolean wildcardGenerics;

  @Test
  public void optionalMapAllowsNullableMethodReferenceAndLambdaReturns() {
    check(
        """
          static class Node {
            @Nullable String getCassandraVersion() { return null; }
          }
          void test(Optional<Node> nodeUp) {
            nodeUp.map(Node::getCassandraVersion).ifPresent(version -> version.length());
            nodeUp.map(node -> node.getCassandraVersion()).ifPresent(version -> version.length());
            nodeUp.map(node -> { return node.getCassandraVersion(); })
                .ifPresent(version -> version.length());
            Optional<String> reference = nodeUp.map(Node::getCassandraVersion);
            Optional<String> lambda = nodeUp.map(node -> node.getCassandraVersion());
          }
        """);
  }

  @Test
  public void optionalMapPreservesNullableGenericReturnOccurrences() {
    check(
        """
          static class Node<T extends @Nullable Object> {
            @Nullable T value() { return null; }
          }
          static <T extends @Nullable Object> @Nullable T value(Node<T> node) {
            return node.value();
          }
          void test(Optional<Node<String>> nodes) {
            nodes.map(Node::value).ifPresent(value -> value.length());
            nodes.map(Test::value).ifPresent(value -> value.length());
            nodes.map(node -> value(node)).ifPresent(value -> value.length());
            Optional<String> reference = nodes.map(Test::value);
            Optional<String> lambda = nodes.map(node -> value(node));
          }
          <T extends @Nullable Object> void nullableBound(Optional<Node<T>> nodes) {
            nodes.map(Node::value).ifPresent(value -> {});
            nodes.map(Test::value).ifPresent(value -> {});
            nodes.map(node -> value(node)).ifPresent(value -> {});
          }
        """);
  }

  @Test
  public void optionalMapAllowsNullableReturnsFromUnmarkedLibraryNodes() {
    check(
        """
          void test(Optional<LibraryNode<String>> nodes) {
            nodes.map(LibraryNode::value).ifPresent(value -> value.length());
            nodes.map(node -> node.value()).ifPresent(value -> value.length());
            nodes.map(LibraryNode::boundedValue).ifPresent(value -> value.length());
          }
          <T extends @Nullable Object> void nullableBound(Optional<LibraryNode<T>> nodes) {
            nodes.map(LibraryNode::value).ifPresent(value -> {});
            nodes.map(node -> node.value()).ifPresent(value -> {});
            nodes.map(LibraryNode::boundedValue).ifPresent(value -> {});
            nodes.map(node -> node.boundedValue()).ifPresent(value -> {});
          }
        """);
  }

  @Test
  public void declaredNullableWildcardTargetsUseTheSameContract() {
    check(
        """
          static class Node {
            @Nullable String value() { return null; }
          }
          static <U> void map(Function<? super Node, ? extends @Nullable U> mapper) {}
          void test() {
            map(Node::value);
            map(node -> node.value());
            map(node -> { return node.value(); });
          }
        """);
  }

  @Test
  public void directNonNullFunctionTargetsStillRejectNullableReturns() {
    check(
        """
          static class Node<T extends @Nullable Object> {
            @Nullable T value() { return null; }
          }
          static <T extends @Nullable Object> @Nullable T value(Node<T> node) {
            return node.value();
          }
          void test() {
            // BUG: Diagnostic contains: referenced method returns @Nullable
            Function<Node<String>, String> reference = Node::value;
            // BUG: Diagnostic contains: referenced method returns @Nullable
            Function<Node<String>, String> genericReference = Test::value;
            // BUG: Diagnostic contains: returning @Nullable
            Function<Node<String>, String> lambda = node -> node.value();
            // BUG: Diagnostic contains: returning @Nullable
            Function<Node<String>, String> genericLambda = node -> value(node);
            Function<Node<String>, @Nullable String> nullableReference = Node::value;
            Function<Node<String>, @Nullable String> nullableLambda = node -> value(node);
          }
          <T extends @Nullable Object> void nullableBound() {
            // BUG: Diagnostic contains: referenced method returns @Nullable
            Function<Node<T>, T> reference = Node::value;
            // BUG: Diagnostic contains: referenced method returns @Nullable
            Function<Node<T>, T> genericReference = Test::value;
            // BUG: Diagnostic contains: returning @Nullable
            Function<Node<T>, T> lambda = node -> node.value();
            // BUG: Diagnostic contains: returning @Nullable
            Function<Node<T>, T> genericLambda = node -> value(node);
          }
        """);
  }

  /** Checks mapper contracts with JSpecify full-type inference and the standard JDK models. */
  private void check(String members) {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:HandleWildcardGenerics=" + wildcardGenerics)))
        .addSourceLines(
            "LibraryNode.java",
            """
            import org.jspecify.annotations.Nullable;
            class LibraryNode<T extends @Nullable Object> {
              @Nullable T value() { return null; }
              T boundedValue() { return null; }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
            """
                + members
                + "}\n")
        .doTest();
  }
}
