package com.uber.nullaway.jspecify;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import javax.inject.Inject;
import org.junit.Test;

public class ResultExpressionTargetTests {
  @Test
  public void inferredTargetsAreAvailableBeforeResultExpressionsAreVisited() {
    CompilationTestHelper.newInstance(TargetCheckingNullAway.class, getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Supplier;
            import org.jspecify.annotations.*;
            @NullMarked class Test<T> {
              List<@Nullable T> nullableValues() { throw new UnsupportedOperationException(); }
              static <U extends @Nullable Object> void accept(Supplier<List<U>> fn, List<U> values) {}
              Supplier<List<@Nullable T>> parenthesized = (this::nullableValues);
              void take(Supplier<List<@Nullable T>> fn) {}
              static void substitutedParenthesized(Test<String> target) {
                target.take((target::nullableValues));
              }
              static void arrow(Test<String> target, int selector, List<@Nullable String> values) {
                accept(switch (selector) { default -> target::nullableValues; }, values);
              }
              static void yield(Test<String> target, int selector, List<@Nullable String> values) {
                accept(switch (selector) { default -> { yield target::nullableValues; } }, values);
              }
              static void nested(Test<String> target, int selector, boolean flag,
                  List<@Nullable String> values) {
                accept(flag ? switch (selector) { default -> target::nullableValues; }
                    : target::nullableValues, values);
              }
              static void parenthesized(Test<String> target, boolean flag,
                  List<@Nullable String> values) {
                accept((flag ? target::nullableValues : target::nullableValues), values);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void earlyReferenceTypeQueriesDoNotFreezeUninferredTargets() {
    CompilationTestHelper.newInstance(TargetCheckingNullAway.class, getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Supplier;
            import org.jspecify.annotations.*;
            @NullMarked class Test<T> {
              List<@Nullable T> nullableValues() { throw new UnsupportedOperationException(); }
              static <U extends @Nullable Object> void acceptAfterEarlyQuery(
                  Supplier<List<U>> fn, List<U> values) {}
              static void direct(Test<String> target, List<@Nullable String> values) {
                acceptAfterEarlyQuery(target::nullableValues, values);
              }
              static void conditional(Test<String> target, boolean flag,
                  List<@Nullable String> values) {
                acceptAfterEarlyQuery(flag ? target::nullableValues : target::nullableValues, values);
              }
              static void arrow(Test<String> target, int selector, List<@Nullable String> values) {
                acceptAfterEarlyQuery(switch (selector) { default -> target::nullableValues; }, values);
              }
              static void yield(Test<String> target, int selector, List<@Nullable String> values) {
                acceptAfterEarlyQuery(switch (selector) {
                  default -> { yield target::nullableValues; }
                }, values);
              }
              static void nested(Test<String> target, int selector, boolean flag,
                  List<@Nullable String> values) {
                acceptAfterEarlyQuery(flag ? switch (selector) { default -> target::nullableValues; }
                    : target::nullableValues, values);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void returnedReferenceQueriesReuseTheRecoveredTarget() {
    CompilationTestHelper.newInstance(TargetCheckingNullAway.class, getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Supplier;
            import org.jspecify.annotations.*;
            @NullMarked class Test<T> {
              interface Factory<E> { Supplier<List<@Nullable E>> create(); }
              List<@Nullable T> nullableValues() { throw new UnsupportedOperationException(); }
              static Supplier<List<@Nullable String>> direct(Test<String> target) {
                return target::nullableValues;
              }
              static Supplier<List<@Nullable String>> parenthesized(Test<String> target) {
                return ((target::nullableValues));
              }
              static Factory<String> expressionLambda(Test<String> target) {
                return () -> target::nullableValues;
              }
              static Factory<String> blockLambda(Test<String> target) {
                return () -> { return target::nullableValues; };
              }
            }
            """)
        .doTest();
  }

  /** Queries result types after call inference, before the scanner visits the call's arguments. */
  @BugPattern(summary = "Checks inferred result targets", severity = WARNING)
  public static final class TargetCheckingNullAway extends NullAway {
    @Inject
    public TargetCheckingNullAway(ErrorProneFlags flags) {
      super(flags);
    }

    /** Checks a parenthesized reference before the variable visitor caches its initializer type. */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      if (tree.getName().contentEquals("parenthesized")) {
        ExpressionTree reference = ASTHelpers.stripParentheses(tree.getInitializer());
        TreePath path = TreePath.getPath(state.getPath(), reference);
        checkNullableElementType(getGenericsChecks().getTreeType(reference, state.withPath(path)));
      }
      return super.matchVariable(tree, state);
    }

    /**
     * Checks reference targets before argument visitation and result caches after call inference.
     */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      if (ASTHelpers.getSymbol(tree).getSimpleName().contentEquals("take")) {
        ExpressionTree reference = ASTHelpers.stripParentheses(tree.getArguments().get(0));
        TreePath path = TreePath.getPath(state.getPath(), reference);
        checkNullableElementType(getGenericsChecks().getTreeType(reference, state.withPath(path)));
      }
      boolean queryBeforeInference =
          ASTHelpers.getSymbol(tree).getSimpleName().contentEquals("acceptAfterEarlyQuery");
      if (queryBeforeInference) {
        queryReferenceTypesBeforeInference(tree, state);
      }
      Description result = super.matchMethodInvocation(tree, state);
      if (!ASTHelpers.getSymbol(tree).getSimpleName().contentEquals("accept")) {
        return result;
      }
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree expression, Void unused) {
          checkTargetAndCache(expression, state);
          return super.visitConditionalExpression(expression, null);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree expression, Void unused) {
          checkTargetAndCache(expression, state);
          return super.visitSwitchExpression(expression, null);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
          TreePath path = TreePath.getPath(state.getPath(), reference);
          checkNullableElementType(
              getGenericsChecks().getTreeType(reference, state.withPath(path)));
          return super.visitMemberReference(reference, null);
        }
      }.scan(tree.getArguments().get(0), null);
      return result;
    }

    /** Requires returned references to reuse the target already recovered by the return visitor. */
    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
      TreePath parent = state.getPath().getParentPath();
      while (parent != null && parent.getLeaf() instanceof ParenthesizedTree) {
        parent = parent.getParentPath();
      }
      if (parent != null
          && (parent.getLeaf() instanceof ReturnTree
              || parent.getLeaf() instanceof LambdaExpressionTree)) {
        CountingPath path = new CountingPath(state.getPath());
        checkNullableElementType(getGenericsChecks().getTreeType(tree, state.withPath(path)));
        assertWithMessage("parent-path reads for returned reference %s", tree)
            .that(path.parentReads)
            .isEqualTo(0);
      }
      return super.matchMemberReference(tree, state);
    }

    /** Exercises early type queries before the enclosing call has supplied its inferred target. */
    private void queryReferenceTypesBeforeInference(MethodInvocationTree call, VisitorState state) {
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
          TreePath path = TreePath.getPath(state.getPath(), reference);
          getGenericsChecks().getTreeType(reference, state.withPath(path));
          return super.visitMemberReference(reference, null);
        }
      }.scan(call.getArguments().get(0), null);
    }

    /** Requires a contextual type on the first query and no parent-context walk on the second. */
    private void checkTargetAndCache(ExpressionTree expression, VisitorState state) {
      TreePath original = TreePath.getPath(state.getPath(), expression);
      CountingPath path = new CountingPath(original);
      VisitorState expressionState = state.withPath(path);
      checkNullableElementType(getGenericsChecks().getTreeType(expression, expressionState));
      int readsAfterFirstQuery = path.parentReads;
      checkNullableElementType(getGenericsChecks().getTreeType(expression, expressionState));
      assertWithMessage("parent-path reads after a second query of %s", expression)
          .that(path.parentReads)
          .isEqualTo(readsAfterFirstQuery);
    }

    /** Checks the nullable element annotation in the expected Supplier<List<@Nullable String>>. */
    private void checkNullableElementType(Type type) {
      assertThat(type).isNotNull();
      Type elementType = type.getTypeArguments().get(0).getTypeArguments().get(0);
      assertWithMessage("nullable list element in %s", type)
          .that(getGenericsChecks().isNullableAnnotated(elementType))
          .isTrue();
    }
  }

  /** Counts parent-context access while retaining the scanner's original enclosing paths. */
  private static final class CountingPath extends TreePath {
    private int parentReads;

    private CountingPath(TreePath original) {
      super(original.getParentPath(), original.getLeaf());
    }

    @Override
    public TreePath getParentPath() {
      parentReads++;
      return super.getParentPath();
    }
  }
}
