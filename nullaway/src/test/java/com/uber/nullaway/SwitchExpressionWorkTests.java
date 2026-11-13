package com.uber.nullaway;

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCSwitchExpression;
import com.sun.tools.javac.util.List;
import javax.inject.Inject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SwitchExpressionWorkTests {
  private final boolean jspecifyMode;

  public SwitchExpressionWorkTests(boolean jspecifyMode) {
    this.jspecifyMode = jspecifyMode;
  }

  @Parameters(name = "JSpecifyMode={0}")
  public static Object[][] modes() {
    return new Object[][] {{true}, {false}};
  }

  @Test
  public void primitiveSwitchResultsAreCollectedOnce() {
    CompilationTestHelper.newInstance(CountingNullAway.class, getClass())
        .setArgs(
            "-XepOpt:NullAway:JSpecifyMode=" + jspecifyMode,
            "-XepOpt:NullAway:AnnotatedPackages=com.uber")
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.*;
            @NullMarked class Test {
              int nullableArrow(int selector, @Nullable Integer value) {
                return switch (selector) {
                  // BUG: Diagnostic contains: unboxing of a @Nullable expression
                  case 0 -> value;
                  default -> 1;
                };
              }
              int nullableYield(int selector, @Nullable Integer value) {
                return switch (selector) {
                  case 0 -> {
                    // BUG: Diagnostic contains: unboxing of a @Nullable expression
                    yield value;
                  }
                  default -> 1;
                };
              }
              int nonNullArrow(int selector, Integer value) {
                return switch (selector) { case 0 -> value; default -> 1; };
              }
              @Nullable Integer boxedResult(int selector, @Nullable Integer value) {
                return switch (selector) { case 0 -> value; default -> 1; };
              }
            }
            """)
        .doTest();
  }

  /**
   * Counts case-list reads during the real switch matcher, before the scanner visits its children.
   */
  @BugPattern(summary = "Counts switch case traversals", severity = WARNING)
  public static final class CountingNullAway extends NullAway {
    @Inject
    public CountingNullAway(ErrorProneFlags flags) {
      super(flags);
    }

    /** Runs the real matcher on a detached tree and bounds its case-list reads. */
    @Override
    public Description matchSwitchExpression(SwitchExpressionTree tree, VisitorState state) {
      CountingSwitch counted = new CountingSwitch((JCSwitchExpression) tree);
      Description result = super.matchSwitchExpression(counted, state);
      // Null labels need one traversal. Collecting results adds one only when a check needs them.
      int caseReadBudget = getConfig().isJSpecifyMode() || counted.type.isPrimitive() ? 2 : 1;
      assertWithMessage("case-list reads for %s", state.getSourceForNode(tree))
          .that(counted.caseReads)
          .isAtMost(caseReadBudget);
      return result;
    }
  }

  /**
   * Keeps the attributed selector and cases, counting reads without modifying the compiler's tree.
   */
  private static final class CountingSwitch extends JCSwitchExpression {
    private int caseReads;

    private CountingSwitch(JCSwitchExpression original) {
      super(original.selector, original.cases);
      type = original.type;
      pos = original.pos;
      polyKind = original.polyKind;
    }

    @Override
    public List<JCCase> getCases() {
      caseReads++;
      return super.getCases();
    }
  }
}
