package com.uber.nullaway;

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCSwitchExpression;
import com.sun.tools.javac.util.List;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    java.util.List<String> args = java.util.List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber");
    CompilationTestHelper.newInstance(CountingNullAway.class, getClass())
        .setArgs(jspecifyMode ? JSpecifyJavacConfig.withJSpecifyModeArgs(args) : args)
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

  @Test
  public void arrowSwitchCheckingCollectsResultPathsOnce() {
    checkSwitchWork(
        "return switch (selector) { %s default -> \"value\"; };", "case %d -> \"value\";");
  }

  @Test
  public void yieldSwitchCheckingCollectsResultPathsOnce() {
    checkSwitchWork(
        "return switch (selector) { %s default -> { yield \"value\"; } };",
        "case %d -> { yield \"value\"; }");
  }

  @Test
  public void colonSwitchCheckingCollectsResultPathsOnce() {
    checkSwitchWork(
        "return switch (selector) { %s default: yield \"value\"; };", "case %d: yield \"value\";");
  }

  @Test
  public void arrowSwitchInferenceCollectsResultPathsOnce() {
    checkSwitchWork(
        "inferSwitch(switch (selector) { %s default -> \"value\"; }); return \"\";",
        "case %d -> \"value\";");
  }

  @Test
  public void yieldSwitchInferenceCollectsResultPathsOnce() {
    checkSwitchWork(
        "inferSwitch(switch (selector) { %s default -> { yield \"value\"; } }); return \"\";",
        "case %d -> { yield \"value\"; }");
  }

  @Test
  public void colonSwitchInferenceCollectsResultPathsOnce() {
    checkSwitchWork(
        "inferSwitch(switch (selector) { %s default: yield \"value\"; }); return \"\";",
        "case %d: yield \"value\";");
  }

  @Test
  public void nestedArrowContextRecoveryHasLinearWork() {
    checkNestedContextWork("switch (selector) { default -> %s; }");
  }

  @Test
  public void nestedYieldContextRecoveryHasLinearWork() {
    checkNestedContextWork("switch (selector) { default -> { yield %s; } }");
  }

  @Test
  public void nestedConditionalContextRecoveryHasLinearWork() {
    checkNestedContextWork("(selector == 0 ? %s : \"value\")");
  }

  @Test
  public void sharedYieldAncestorRecoveryHasLinearWork() {
    java.util.List<String> source = new java.util.ArrayList<>();
    source.add("package com.uber; import java.util.function.Supplier; class Test {");
    source.add("static String value() { return \"value\"; }");
    for (int depth : new int[] {1, 16, 32, 64, 128}) {
      String statements = "yield Test::value;";
      for (int level = 0; level < depth; level++) {
        statements =
            "if (selector == " + level + ") { yield Test::value; } else { " + statements + " }";
      }
      source.add(
          "Supplier<String> depth"
              + depth
              + "(int selector) { return switch (selector) { default -> { "
              + statements
              + " } }; }");
    }
    source.add("}");
    java.util.List<String> args = java.util.List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber");
    CompilationTestHelper.newInstance(ContextCountingNullAway.class, getClass())
        .setArgs(jspecifyMode ? JSpecifyJavacConfig.withJSpecifyModeArgs(args) : args)
        .addSourceLines("Test.java", source.toArray(String[]::new))
        .doTest();
  }

  /**
   * Compiles result chains of increasing depth and bounds the matchers' total parent-path reads.
   */
  private void checkNestedContextWork(String expressionTemplate) {
    java.util.List<String> source = new java.util.ArrayList<>();
    source.add("package com.uber; class Test {");
    for (int depth : new int[] {16, 32, 64, 128}) {
      String expression = "\"value\"";
      for (int level = 0; level < depth; level++) {
        expression = String.format(expressionTemplate, expression);
      }
      source.add("String depth" + depth + "(int selector) { return " + expression + "; }");
    }
    source.add("}");
    java.util.List<String> args = java.util.List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber");
    CompilationTestHelper.newInstance(ContextCountingNullAway.class, getClass())
        .setArgs(jspecifyMode ? JSpecifyJavacConfig.withJSpecifyModeArgs(args) : args)
        .addSourceLines("Test.java", source.toArray(String[]::new))
        .doTest();
  }

  @Test
  public void switchResultPathsRetainAncestors() {
    CompilationTestHelper.newInstance(PathCheckingNullAway.class, getClass())
        .setArgs(java.util.List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.function.IntSupplier;
            class Test {
              int arrow(int selector) {
                return switch (selector) { case 0 -> 1; default -> 2; };
              }
              int yield(int selector) {
                return switch (selector) {
                  case 0 -> { if (selector == 0) { yield 1; } else { yield 2; } }
                  default -> throw new IllegalArgumentException();
                };
              }
              int colon(int selector) {
                return switch (selector) { case 0: yield 1; default: yield 2; };
              }
              IntSupplier nested(int outer, int inner) {
                return () -> switch (outer) {
                  case 0 -> { yield switch (inner) { case 0 -> 1; default -> 2; }; }
                  default -> 3;
                };
              }
            }
            """)
        .doTest();
  }

  /** Compiles a generated switch through checking or inference and bounds case-list reads. */
  private void checkSwitchWork(String statement, String caseTemplate) {
    StringBuilder cases = new StringBuilder();
    for (int label = 0; label < 63; label++) {
      cases.append(String.format(caseTemplate, label));
    }
    java.util.List<String> args = java.util.List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber");
    CompilationTestHelper.newInstance(CountingNullAway.class, getClass())
        .setArgs(jspecifyMode ? JSpecifyJavacConfig.withJSpecifyModeArgs(args) : args)
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.*;",
            "@NullMarked class Test {",
            "  static <T> T inferSwitch(T value) { return value; }",
            "  String result(int selector) {",
            String.format(statement, cases),
            "  }",
            "}")
        .doTest();
  }

  /** Counts case-list reads during switch checking and generic-call inference. */
  @BugPattern(summary = "Counts switch case traversals", severity = WARNING)
  public static final class CountingNullAway extends NullAway {
    @Inject
    public CountingNullAway(ErrorProneFlags flags) {
      super(flags);
    }

    /** Runs the real matcher with the counted tree in the path and bounds its case-list reads. */
    @Override
    public Description matchSwitchExpression(SwitchExpressionTree tree, VisitorState state) {
      CountingSwitch counted = new CountingSwitch((JCSwitchExpression) tree);
      TreePath countedPath = new TreePath(state.getPath().getParentPath(), counted);
      Description result = super.matchSwitchExpression(counted, state.withPath(countedPath));
      // Null labels need one traversal. Collecting results adds one only when a check needs them.
      int caseReadBudget = getConfig().isJSpecifyMode() || counted.type.isPrimitive() ? 2 : 1;
      assertWithMessage("case-list reads for %s", state.getSourceForNode(tree))
          .that(counted.caseReads)
          .isAtMost(caseReadBudget);
      return result;
    }

    /** Bounds switch traversal during the generic call's real inference run. */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      if (!ASTHelpers.getSymbol(tree).getSimpleName().contentEquals("inferSwitch")) {
        return super.matchMethodInvocation(tree, state);
      }
      JCMethodInvocation invocation = (JCMethodInvocation) tree;
      com.sun.tools.javac.util.List<com.sun.tools.javac.tree.JCTree.JCExpression> originalArgs =
          invocation.args;
      CountingSwitch counted = new CountingSwitch((JCSwitchExpression) originalArgs.head);
      invocation.args = com.sun.tools.javac.util.List.of(counted);
      try {
        Description result = super.matchMethodInvocation(tree, state);
        assertWithMessage("case-list reads during inference of %s", tree)
            .that(counted.caseReads)
            .isAtMost(2);
        return result;
      } finally {
        invocation.args = originalArgs;
      }
    }
  }

  /** Checks that collected results reuse the scanner's original enclosing paths. */
  @BugPattern(summary = "Checks switch result paths", severity = WARNING)
  public static final class PathCheckingNullAway extends NullAway {
    @Inject
    public PathCheckingNullAway(ErrorProneFlags flags) {
      super(flags);
    }

    /** Checks result selection and ancestor identity before normal switch checking. */
    @Override
    @SuppressWarnings("ReferenceEquality") // Tree identity locates the enclosing switch.
    public Description matchSwitchExpression(SwitchExpressionTree tree, VisitorState state) {
      java.util.List<TreePath> results =
          NullabilityUtil.getSwitchExpressionResultPaths(tree, state.getPath());
      assertWithMessage("result paths for %s", tree).that(results).hasSize(2);
      for (TreePath result : results) {
        assertWithMessage("result expression in %s", tree)
            .that(result.getLeaf())
            .isInstanceOf(ExpressionTree.class);
        TreePath ancestor = result.getParentPath();
        while (ancestor != null && ancestor.getLeaf() != tree) {
          ancestor = ancestor.getParentPath();
        }
        assertWithMessage("enclosing path for result %s", result.getLeaf())
            .that(ancestor)
            .isSameInstanceAs(state.getPath());
      }
      return super.matchSwitchExpression(tree, state);
    }
  }

  /** Counts parent-path reads through the real result-expression matchers. */
  @BugPattern(summary = "Counts enclosing result context walks", severity = WARNING)
  public static final class ContextCountingNullAway extends NullAway {
    private final IdentityHashMap<TreePath, TreePath> countedPaths = new IdentityHashMap<>();
    private final AtomicInteger parentReads = new AtomicInteger();
    private String currentMethod = "";

    @Inject
    public ContextCountingNullAway(ErrorProneFlags flags) {
      super(flags);
    }

    /** Preserves ancestor identity while counting parent reads by switch checking. */
    @Override
    public Description matchSwitchExpression(SwitchExpressionTree tree, VisitorState state) {
      prepareMethod(state.getPath());
      Description result =
          super.matchSwitchExpression(tree, state.withPath(countedPath(state.getPath())));
      checkWork();
      return result;
    }

    /** Preserves ancestor identity while counting parent reads by conditional checking. */
    @Override
    public Description matchConditionalExpression(
        com.sun.source.tree.ConditionalExpressionTree tree, VisitorState state) {
      prepareMethod(state.getPath());
      Description result =
          super.matchConditionalExpression(tree, state.withPath(countedPath(state.getPath())));
      checkWork();
      return result;
    }

    /** Preserves ancestor identity while counting parent reads by method-reference checking. */
    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
      prepareMethod(state.getPath());
      Description result =
          super.matchMemberReference(tree, state.withPath(countedPath(state.getPath())));
      checkWork();
      return result;
    }

    /** Starts a separate count for each generated method. */
    private void prepareMethod(TreePath path) {
      while (!(path.getLeaf() instanceof MethodTree)) {
        path = path.getParentPath();
      }
      String name = ((MethodTree) path.getLeaf()).getName().toString();
      if (!name.equals(currentMethod)) {
        currentMethod = name;
        countedPaths.clear();
        parentReads.set(0);
      }
    }

    /** Bounds all work observed so far in the generated method by its nesting depth. */
    private void checkWork() {
      int depth = Integer.parseInt(currentMethod.substring("depth".length()));
      assertWithMessage(
              "parent-path reads in %s, JSpecifyMode=%s",
              currentMethod, getConfig().isJSpecifyMode())
          .that(parentReads.get())
          .isAtMost(32 * depth);
    }

    /** Returns one counted wrapper per original path, retaining shared scanner ancestors. */
    private TreePath countedPath(TreePath path) {
      TreePath cached = countedPaths.get(path);
      if (cached != null) {
        return cached;
      }
      TreePath parent = path.getParentPath();
      if (parent == null) {
        return path;
      }
      TreePath counted =
          new TreePath(countedPath(parent), path.getLeaf()) {
            @Override
            public TreePath getParentPath() {
              parentReads.incrementAndGet();
              return super.getParentPath();
            }
          };
      countedPaths.put(path, counted);
      return counted;
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
