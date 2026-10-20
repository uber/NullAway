package com.uber.nullaway.generics;

import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** Tests the exclusions promised by the method-reference return-nullness hook. */
public class MethodReferenceResolutionTests {

  @Test
  public void skipsUnsupportedHints() {
    checkUnsupportedHints(List.of("-XepOpt:NullAway:OnlyNullMarked=true"));
  }

  @Test
  public void skipsUnsupportedHintsInJSpecifyMode() {
    checkUnsupportedHints(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }

  /** Verifies that each unsupported adaptation is resolved without consulting the hint hook. */
  private void checkUnsupportedHints(List<String> args) {
    CompilationTestHelper.newInstance(HintExclusionChecker.class, getClass())
        .setArgs(args)
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import java.util.function.Supplier;

            class Test {
              interface VarargsFunction { Object apply(Object... values); }

              static Object fixed(Object value) { return value; }
              static Object varargs(Object... values) { return values; }

              Supplier<Test> constructor = Test::new;
              Function raw = Test::fixed;
              Function<Object, Object> varargsMethod = Test::varargs;
              VarargsFunction varargsTarget = Test::fixed;
            }
            """)
        .doTest();
  }

  /** Exercises resolution with a handler that fails if an excluded reference reaches its hook. */
  @BugPattern(summary = "Checks method-reference hint exclusions", severity = SUGGESTION)
  public static final class HintExclusionChecker extends BugChecker
      implements BugChecker.MemberReferenceTreeMatcher {

    /** Resolves the reference and fails if the resolver calls the excluded hint hook. */
    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
      NullAway analysis = new NullAway(state.errorProneOptions().getFlags());
      Handler handler =
          new Handler() {
            @Override
            public NullnessHint onMethodReferenceReturnNullnessHint(
                Symbol.MethodSymbol referencedMethod,
                @Nullable Type receiverType,
                Symbol.MethodSymbol functionalInterfaceMethod,
                Type.MethodType functionalInterfaceMethodType,
                boolean isUnbound,
                VisitorState state) {
              throw new AssertionError(
                  "Unexpected return-nullness hook for " + state.getSourceForNode(tree));
            }
          };
      GenericsChecks checks = new GenericsChecks(analysis, analysis.getConfig(), handler);
      checks.resolveMemberReference(
          tree, ASTHelpers.getSymbol(tree), ASTHelpers.getType(tree), state);
      return NO_MATCH;
    }
  }
}
