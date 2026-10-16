package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.handlers.Handler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Compiler-backed regressions for call-specific inference variables. */
public class CallSpecificInferenceTests {

  private static final ThreadLocal<Boolean> CHECKED = ThreadLocal.withInitial(() -> false);

  @Test
  public void modeledNullableIntersectionBoundPreservesCompilerStructure() {
    CHECKED.set(false);
    try {
      CompilationTestHelper.newInstance(IntersectionBoundChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import org.jspecify.annotations.NullMarked;
              @NullMarked
              class Test {
                interface A {}
                interface B {}
                static <T extends A & B> void fixture(T value) {}
              }
              """)
          .doTest();
      assertThat(CHECKED.get()).isTrue();
    } finally {
      CHECKED.remove();
    }
  }

  @Test
  public void recursiveReferenceFreshensOnlyTheReferencedMethodSide() {
    CHECKED.set(false);
    try {
      CompilationTestHelper.newInstance(RecursiveReferenceChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import java.util.function.Function;
              import org.jspecify.annotations.NullMarked;
              import org.jspecify.annotations.Nullable;
              @NullMarked
              class Test {
                static <T extends @Nullable Object> T recursive(T value) {
                  class Local {
                    <U extends @Nullable Object> void accept(Function<T, T> function, U ignored) {}
                  }
                  new Local().accept(Test::recursive, value);
                  return value;
                }
              }
              """)
          .doTest();
      assertThat(CHECKED.get()).isTrue();
    } finally {
      CHECKED.remove();
    }
  }

  /**
   * Exercises registration on real javac types while a declaration-only model forces the repair.
   */
  @BugPattern(summary = "Checks preservation of modeled intersection bounds", severity = SUGGESTION)
  public static final class IntersectionBoundChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /**
     * Checks the model mismatch and intersection structure without annotating its synthetic root.
     */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      if (!tree.getName().contentEquals("fixture")) {
        return NO_MATCH;
      }
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      Symbol.TypeVariableSymbol declared = method.getTypeParameters().head;
      Type.IntersectionClassType original =
          (Type.IntersectionClassType) ((Type.TypeVar) declared.type).getUpperBound();
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      Handler handler =
          new Handler() {
            @Override
            public boolean onOverrideMethodTypeVariableUpperBound(
                Symbol.MethodSymbol symbol, int index, VisitorState unused) {
              return symbol.equals(method) && index == 0;
            }
          };
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(handler);
      ConstraintSolver solver = new ConstraintSolverImpl(config, state, analysis);

      assertThat(GenericsUtils.upperBoundIsNullable(declared, config, handler, state)).isTrue();
      Type.TypeVar unmodeled =
          new Type.TypeVar(
              declared.name, declared.owner, ((Type.TypeVar) declared.type).getLowerBound());
      unmodeled.setUpperBound(original);
      assertThat(GenericsUtils.upperBoundIsNullable(unmodeled.tsym, config, handler, state))
          .isFalse();
      Type.TypeVar fresh = solver.registerInferenceVariables(tree, List.of(declared)).get(declared);
      assertThat(fresh).isNotNull();
      assertThat(fresh.tsym).isNotSameInstanceAs(declared);
      assertThat(method.getTypeParameters()).doesNotContain(fresh.tsym);

      assertThat(fresh.getUpperBound()).isInstanceOf(Type.IntersectionClassType.class);
      Type.IntersectionClassType preserved = (Type.IntersectionClassType) fresh.getUpperBound();
      assertThat(preserved.tsym).isSameInstanceAs(original.tsym);
      assertThat(preserved.allInterfaces).isTrue();
      assertThat(preserved.getExplicitComponents().stream().map(type -> type.tsym).toList())
          .containsExactlyElementsIn(
              original.getExplicitComponents().stream().map(type -> type.tsym).toList())
          .inOrder();
      assertThat(preserved.getAnnotationMirrors()).isEmpty();
      assertThat(((Type.TypeVar) declared.type).getUpperBound()).isSameInstanceAs(original);
      assertThat(original.getExplicitComponents()).hasSize(2);
      assertThat(original.getAnnotationMirrors()).isEmpty();
      CHECKED.set(true);
      return NO_MATCH;
    }
  }

  /** Inspects both subtype relations using the same declaration symbol in caller and reference. */
  @BugPattern(
      summary = "Checks recursive method-reference variable ownership",
      severity = SUGGESTION)
  public static final class RecursiveReferenceChecker extends BugChecker
      implements BugChecker.MemberReferenceTreeMatcher {

    /** Checks the return and parameter directions rather than relying on fallback diagnostics. */
    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
      Symbol.MethodSymbol referenced = ASTHelpers.getSymbol(tree);
      MethodTree enclosing = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
      assertThat(ASTHelpers.getSymbol(enclosing)).isSameInstanceAs(referenced);
      Symbol.TypeVariableSymbol declared = referenced.getTypeParameters().head;
      Type target = ASTHelpers.getType(tree);
      assertThat(target.getTypeArguments()).hasSize(2);
      assertThat(target.getTypeArguments().get(0).tsym).isSameInstanceAs(declared);
      assertThat(target.getTypeArguments().get(1).tsym).isSameInstanceAs(declared);

      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      Handler handler = new Handler() {};
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(handler);
      ConstraintSolver solver = spy(new ConstraintSolverImpl(config, state, analysis));
      GenericsChecks checks = new GenericsChecks(analysis, config, handler);
      generateReferenceConstraints(checks, state, solver, target, tree);

      ArgumentCaptor<Type> subtypes = ArgumentCaptor.forClass(Type.class);
      ArgumentCaptor<Type> supertypes = ArgumentCaptor.forClass(Type.class);
      verify(solver, times(2))
          .addSubtypeConstraint(subtypes.capture(), supertypes.capture(), eq(false));
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(tree, referenced.getTypeParameters());
      Type.TypeVar referenceVariable = fresh.get(declared);
      assertThat(referenceVariable).isNotNull();
      assertThat(referenceVariable.tsym).isNotSameInstanceAs(declared);
      // Return: referenced T <: caller T. Parameter: caller T <: referenced T.
      assertThat(subtypes.getAllValues().get(0).tsym).isSameInstanceAs(referenceVariable.tsym);
      assertThat(supertypes.getAllValues().get(0).tsym).isSameInstanceAs(declared);
      assertThat(subtypes.getAllValues().get(1).tsym).isSameInstanceAs(declared);
      assertThat(supertypes.getAllValues().get(1).tsym).isSameInstanceAs(referenceVariable.tsym);
      assertThat(target.getTypeArguments().get(0).tsym).isSameInstanceAs(declared);
      assertThat(target.getTypeArguments().get(1).tsym).isSameInstanceAs(declared);
      CHECKED.set(true);
      return NO_MATCH;
    }

    /**
     * Exercises the production constraint-generation path while retaining access to its relations.
     */
    private static void generateReferenceConstraints(
        GenericsChecks checks,
        VisitorState state,
        ConstraintSolver solver,
        Type target,
        MemberReferenceTree tree) {
      try {
        Method method =
            GenericsChecks.class.getDeclaredMethod(
                "handleMethodRefInGenericMethodInference",
                VisitorState.class,
                ConstraintSolver.class,
                Type.class,
                MemberReferenceTree.class);
        method.setAccessible(true);
        method.invoke(checks, state, solver, target, tree);
      } catch (InvocationTargetException exception) {
        throw new AssertionError(
            "Method-reference constraint generation failed", exception.getCause());
      } catch (ReflectiveOperationException exception) {
        throw new LinkageError(exception.getMessage(), exception);
      }
    }
  }
}
