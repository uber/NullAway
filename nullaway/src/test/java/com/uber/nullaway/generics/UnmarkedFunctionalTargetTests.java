package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Checks target publication and its no-functional-arguments fast path after shared inference. */
public class UnmarkedFunctionalTargetTests {

  @Test
  public void nestedMethodReferenceTargetsAndOrdinaryCallFastPath() {
    CompilationTestHelper.newInstance(FunctionalTargetChecker.class, getClass())
        .setArgs(JSpecifyJavacConfig.withJSpecifyModeArgs(List.of()))
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Consumer;
            import org.jspecify.annotations.*;

            @NullMarked class Test {
              static class Box<E extends @Nullable Object> {}
              static <X> X outer(X value) { return value; }
              @NullUnmarked static <T> T unmarked(T value, Consumer<T> consumer) { return value; }
              @NullUnmarked static <T> T unmarked(T value) { return value; }
              static void consume(Object value) {}

              void nullableMethodReference(Box<@Nullable String> value) {
                outer(unmarked(value, (Test::consume)));
              }
              void nonNullMethodReference(Box<String> value) {
                outer(unmarked(value, Test::consume));
              }
              void ordinaryCall(Box<@Nullable String> value) {
                outer(unmarked(value));
              }
            }
            """)
        .doTest();
  }

  /** Observes inferred targets through public APIs without scanning with NullAway itself. */
  @BugPattern(summary = "Checks unmarked functional argument targets", severity = SUGGESTION)
  public static final class FunctionalTargetChecker extends BugChecker
      implements BugChecker.CompilationUnitTreeMatcher, BugChecker.MethodInvocationTreeMatcher {
    private final NullAway analysis =
        new NullAway(
            ErrorProneFlags.fromMap(
                Map.of(
                    "NullAway:OnlyNullMarked", "true",
                    "NullAway:JSpecifyMode", "true",
                    "NullAway:WarnOnGenericInferenceFailure", "true")));

    /** Initializes the analysis and its dataflow before requesting inference directly. */
    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
      analysis.matchCompilationUnit(tree, state);
      return NO_MATCH;
    }

    /** Infers the outer call first, then publishes only the nested unmarked call's targets. */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      if (!ASTHelpers.getSymbol(tree).getSimpleName().contentEquals("outer")) {
        return NO_MATCH;
      }
      MethodInvocationTree inner = (MethodInvocationTree) tree.getArguments().get(0);
      GenericsChecks checks = analysis.getGenericsChecks();
      Type valueType = ASTHelpers.getSymbol(inner.getArguments().get(0)).type;
      boolean nullableElement =
          GenericsUtils.isAnnotatedAsNullable(
              valueType.getTypeArguments().head, analysis.getConfig());
      Type outerType = checks.getTreeType(tree, state);
      assertThat(outerType).isNotNull();
      assertThat(
              GenericsUtils.isAnnotatedAsNullable(
                  outerType.getTypeArguments().head, analysis.getConfig()))
          .isEqualTo(nullableElement);

      Symbol.MethodSymbol method = ASTHelpers.getSymbol(inner);
      AtomicInteger methodTypeResolutions = new AtomicInteger();
      Symbol.MethodSymbol observedMethod = withObservedType(method, methodTypeResolutions);
      VisitorState innerState = state.withPath(new TreePath(state.getPath(), inner));
      if (inner.getArguments().size() == 1) {
        checks.cacheInferredFunctionalArgumentTypesForUnmarkedCall(
            observedMethod, inner, inner.getArguments(), innerState);
        assertThat(methodTypeResolutions.get()).isEqualTo(0);
        return NO_MATCH;
      }

      MemberReferenceTree reference =
          (MemberReferenceTree) ASTHelpers.stripParentheses(inner.getArguments().get(1));
      // Shared inference stores the inner substitutions, but does not publish its functional
      // target.
      assertThat(checks.getPolyExpressionType(reference))
          .isSameInstanceAs(ASTHelpers.getType(reference));
      checks.cacheInferredFunctionalArgumentTypesForUnmarkedCall(
          observedMethod, inner, inner.getArguments(), innerState);
      // This positive control proves that the type observer sees actual method-type resolution.
      assertThat(methodTypeResolutions.get()).isGreaterThan(0);
      Type target = checks.getPolyExpressionType(reference);
      assertThat(target).isNotNull();
      assertThat(
              GenericsUtils.isAnnotatedAsNullable(
                  target.getTypeArguments().head.getTypeArguments().head, analysis.getConfig()))
          .isEqualTo(nullableElement);
      return NO_MATCH;
    }

    /** Copies the executable type to count resolution without mutating compiler-owned objects. */
    private static Symbol.MethodSymbol withObservedType(
        Symbol.MethodSymbol method, AtomicInteger resolutions) {
      Type.ForAll original = (Type.ForAll) method.type;
      Type.ForAll observed =
          new Type.ForAll(original.tvars, original.qtype) {
            @Override
            public Type.MethodType asMethodType() {
              resolutions.incrementAndGet();
              return super.asMethodType();
            }
          };
      return new Symbol.MethodSymbol(method.flags(), method.name, observed, method.owner);
    }
  }
}
