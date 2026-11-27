package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** Tests bounded inference work when dataflow queries each call in a nested expression. */
public class GenericInferencePerformanceTests {

  @Test
  public void nestedDataflowQueriesAvoidRepeatedEnclosingInference() {
    String expression = "\"leaf\"";
    for (int level = 0; level < 6; level++) {
      expression = "join(" + expression + ", " + expression + ")";
    }
    checkNestedCallInferenceWork(expression);
  }

  @Test
  public void nestedDataflowQueriesAfterContradictionsAvoidRepeatedConstraintGeneration() {
    checkNestedCallInferenceWork("join(null, ".repeat(63) + "\"leaf\"" + ")".repeat(63));
  }

  /** Checks the inference work for an expression containing 63 calls to {@code join}. */
  private void checkNestedCallInferenceWork(String expression) {
    CompilationTestHelper.newInstance(InferenceWorkChecker.class, getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static <T> T join(T first, T second) { return first; }
              String nestedCalls() {
                return %s;
              }
            }
            """
                .formatted(expression))
        .doTest();
  }

  /** Counts executable-type modeling while exercising the return-nullness API used by dataflow. */
  @BugPattern(summary = "Checks bounded generic inference work", severity = SUGGESTION)
  public static final class InferenceWorkChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /** Queries calls in evaluation order without caching provisional dataflow inference results. */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      if (!tree.getName().contentEquals("nestedCalls")) {
        return NO_MATCH;
      }
      NullAway analysis = new NullAway(state.errorProneOptions().getFlags());
      AtomicInteger executableTypes = new AtomicInteger();
      Handler handler =
          new Handler() {
            @Override
            public Type.MethodType onOverrideMethodType(
                Symbol.MethodSymbol method,
                Type.MethodType methodType,
                VisitorState state,
                @Nullable MethodInvocationTree invocation) {
              if (method.getSimpleName().contentEquals("join")
                  && methodType.getReturnType() instanceof Type.TypeVar) {
                executableTypes.incrementAndGet();
              }
              return methodType;
            }
          };
      GenericsChecks checks = new GenericsChecks(analysis, analysis.getConfig(), handler);
      DataflowQueryScanner scanner = new DataflowQueryScanner(checks, state);
      scanner.scan(state.getPath(), null);
      assertThat(scanner.calls).isEqualTo(63);
      // Allow joint inference of each call's own subtree in the balanced expression. In the
      // failing chain, each contradiction leaves the remaining calls to their own inference.
      assertThat(executableTypes.get()).isAtMost(24 * scanner.calls);
      assertThat(executableTypes.get()).isAtLeast(scanner.calls);
      return NO_MATCH;
    }
  }

  /** Queries a call after its arguments, as dataflow does. */
  private static final class DataflowQueryScanner
      extends TreePathScanner<@Nullable Void, @Nullable Void> {
    private final GenericsChecks checks;
    private final VisitorState state;
    private int calls;

    DataflowQueryScanner(GenericsChecks checks, VisitorState state) {
      this.checks = checks;
      this.state = state;
    }

    /** Checks the inferred nullness of each call in evaluation order. */
    @Override
    public @Nullable Void visitMethodInvocation(MethodInvocationTree call, @Nullable Void unused) {
      super.visitMethodInvocation(call, null);
      calls++;
      assertThat(
              checks.getGenericReturnNullnessAtInvocation(
                  ASTHelpers.getSymbol(call),
                  call,
                  getCurrentPath(),
                  state.withPath(getCurrentPath()),
                  true))
          .isEqualTo(Nullness.NONNULL);
      return null;
    }
  }
}
