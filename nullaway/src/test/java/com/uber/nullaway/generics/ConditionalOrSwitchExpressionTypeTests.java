package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import org.junit.Test;

public class ConditionalOrSwitchExpressionTypeTests {
  @Test
  public void contextualTypesAreCachedOnlyOutsideDataflow() {
    CompilationTestHelper.newInstance(CacheCheckingNullAway.class, getClass())
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
            @NullMarked class Test {
              static List<@Nullable String> nullableValues() { return List.of(); }
              void conditional(boolean flag) {
                Supplier<List<@Nullable String>> queried =
                    flag ? Test::nullableValues : Test::nullableValues;
              }
              void switchExpression(int selector) {
                Supplier<List<@Nullable String>> queried =
                    switch (selector) { default -> Test::nullableValues; };
              }
            }
            """)
        .doTest();
  }

  /** Checks type queries before the variable visitor caches its initializer's target. */
  @BugPattern(summary = "Checks contextual type cache phases", severity = WARNING)
  public static final class CacheCheckingNullAway extends NullAway {
    @Inject
    public CacheCheckingNullAway(ErrorProneFlags flags) {
      super(flags);
    }

    /**
     * Requires dataflow queries to recover the target again, and later visitor queries to reuse it.
     */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      if (tree.getName().contentEquals("queried")) {
        ExpressionTree expression = tree.getInitializer();
        AtomicInteger parentReads = new AtomicInteger();
        TreePath path =
            new TreePath(state.getPath(), expression) {
              @Override
              public TreePath getParentPath() {
                parentReads.incrementAndGet();
                return super.getParentPath();
              }
            };
        VisitorState expressionState = state.withPath(path);
        checkNullableElements(getGenericsChecks().getTreeType(expression, expressionState, true));
        int afterFirstDataflowQuery = parentReads.get();
        checkNullableElements(getGenericsChecks().getTreeType(expression, expressionState, true));
        assertWithMessage("parent-context reads for repeated dataflow query of %s", expression)
            .that(parentReads.get())
            .isGreaterThan(afterFirstDataflowQuery);
        checkNullableElements(getGenericsChecks().getTreeType(expression, expressionState));
        int afterVisitorQuery = parentReads.get();
        checkNullableElements(getGenericsChecks().getTreeType(expression, expressionState));
        assertWithMessage("parent-context reads for repeated visitor query of %s", expression)
            .that(parentReads.get())
            .isEqualTo(afterVisitorQuery);
      }
      return super.matchVariable(tree, state);
    }

    /** Requires the contextual Supplier<List<@Nullable String>> type on every query. */
    private void checkNullableElements(Type type) {
      assertThat(type).isNotNull();
      Type elementType = type.getTypeArguments().get(0).getTypeArguments().get(0);
      assertWithMessage("nullable list element in %s", type)
          .that(getGenericsChecks().isNullableAnnotated(elementType))
          .isTrue();
    }
  }
}
