package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/**
 * Foundation behavior retained through the typed solver: root fallback, graph updates, cycles,
 * immutable snapshots, and proper certified substitutions. The removed FullTypeBounds helper's
 * successful-bounds getter budget is private-algorithm coverage, not a constraint on this solver.
 */
public class FoundationSolveTests extends NullAwayTestsBase {
  @Test
  public void rootFactsExcludeCandidateDefaultsAndNonQualifierStates() {
    CompilationTestHelper.newInstance(SolveChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Test {
              <T extends @Nullable Object> void rootFacts(List<?> value) {}
            }
            """)
        .doTest();
  }

  @Test
  public void cyclesAndLaterEvidenceDoNotPoisonOtherResolutions() {
    CompilationTestHelper.newInstance(SolveChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked class Test {
              static class Box<X extends @Nullable Object> {}
              <T extends @Nullable Object, U extends @Nullable Object, V extends @Nullable Object>
                  void cycleForward(Box<String> box, String word) {}
              <T extends @Nullable Object, U extends @Nullable Object, V extends @Nullable Object>
                  void cycleReverse(Box<String> box, String word) {}
              <T extends @Nullable Object, U extends @Nullable Object, V extends @Nullable Object>
                  void properLowerBeforeCycle(Box<String> box, String word) {}
              <T extends Comparable<T>, U extends @Nullable Object, V extends @Nullable Object>
                  void fBoundWithProperLower(Box<String> box, String word) {}
              <T extends @Nullable Object, U extends @Nullable Object, V extends @Nullable Object>
                  void graphUpdate(Box<String> box, String word) {}
            }
            """)
        .doTest();
  }

  @Test
  public void rootOnlyFallbackRetainsNullableWildcardResult() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked class Test {
              static <T extends @Nullable Object> T identity(T value) {
                return value;
              }
              static void nullableWildcard(@Nullable List<?> input) {
                // BUG: Diagnostic contains: dereferenced expression
                identity(input).size();
              }
              static void nonNullWildcard(List<?> input) {
                identity(input).size();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void compilerWrapChainPreservesNestedNullness() {
    int depth = 18;
    String nullableType = "@Nullable String";
    String nonNullType = "String";
    String expression = "value";
    for (int index = 0; index < depth; index++) {
      nullableType = "Box<" + nullableType + ">";
      nonNullType = "Box<" + nonNullType + ">";
      expression = "wrap(" + expression + ")";
    }
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<T> wrap(T value) {
                throw new UnsupportedOperationException();
              }
              static void acceptsNullable(%s value) {}
              static void acceptsNonNull(%s value) {}
              static void nullableLeaf(@Nullable String value) {
                var result = %s;
                acceptsNullable(result);
                // BUG: Diagnostic contains: incompatible types
                acceptsNonNull(result);
              }
              static void nonNullLeaf(String value) {
                var result = %s;
                acceptsNonNull(result);
                // BUG: Diagnostic contains: incompatible types
                acceptsNullable(result);
              }
            }
            """
                .formatted(nullableType, nonNullType, expression, expression))
        .doTest();
  }

  /** Uses real compiler symbols and checks certification separately from diagnostic candidates. */
  @BugPattern(summary = "Checks foundation solve contracts", severity = SUGGESTION)
  public static final class SolveChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {
    /**
     * Builds the named graph without assuming the foundation helper's narrower supported domain.
     */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (method == null || method.getTypeParameters().isEmpty()) {
        return NO_MATCH;
      }
      NullAway analysis =
          new NullAway(
              ErrorProneFlags.fromMap(
                  Map.of(
                      "NullAway:OnlyNullMarked",
                      "true",
                      "NullAway:JSpecifyMode",
                      "true",
                      "NullAway:HandleWildcardGenerics",
                      "true")));
      ConstraintSolver solver = new ConstraintSolverImpl(analysis.getConfig(), state, analysis);
      if (tree.getName().contentEquals("rootFacts")) {
        checkRootFacts(tree, method, solver, state, analysis);
      } else {
        checkCycleAndUpdate(tree, method, solver, state, analysis);
      }
      return NO_MATCH;
    }

    /**
     * Literal null establishes nullable, never NULL/BOTTOM, while default candidates are not facts.
     */
    private static void checkRootFacts(
        MethodTree tree,
        Symbol.MethodSymbol method,
        ConstraintSolver solver,
        VisitorState state,
        NullAway analysis) {
      Element t = method.getTypeParameters().head;
      Type shape = method.getParameters().head.type;
      var fresh =
          solver.registerInferenceVariables(
              tree,
              method.getTypeParameters(),
              Map.of(),
              Set.copyOf(method.getTypeParameters()),
              Map.of(t, shape));
      InferenceVariable variable = new InferenceVariable(t, tree);
      Solution before = solver.solve();
      assertThat(solver.knownNullness(variable)).isNull();
      solver.addSubtypeConstraint(state.getSymtab().botType, fresh.get(t), false);
      Solution after = solver.solve();
      assertThat(solver.knownNullness(variable)).isEqualTo(Nullness.NULLABLE);
      assertThat(
              GenericsUtils.isAnnotatedAsNullable(
                  after.inferredTypes().get(variable), analysis.getConfig()))
          .isTrue();
      assertThat(
              GenericsUtils.isAnnotatedAsNullable(
                  before.inferredTypes().get(variable), analysis.getConfig()))
          .isFalse();
      assertThrows(UnsupportedOperationException.class, after.inferredTypes()::clear);
      ConstraintSolver nonNull = new ConstraintSolverImpl(analysis.getConfig(), state, analysis);
      var other =
          nonNull.registerInferenceVariables(
              tree,
              method.getTypeParameters(),
              Map.of(),
              Set.copyOf(method.getTypeParameters()),
              Map.of(t, shape));
      nonNull.addSubtypeConstraint(other.get(t), state.getSymtab().objectType, false);
      nonNull.solve();
      assertThat(nonNull.knownNullness(variable)).isEqualTo(Nullness.NONNULL);
    }

    /**
     * Preserves independent values, immutable results, and later graph/root changes across solves.
     */
    private static void checkCycleAndUpdate(
        MethodTree tree,
        Symbol.MethodSymbol method,
        ConstraintSolver solver,
        VisitorState state,
        NullAway analysis) {
      String name = tree.getName().toString();
      var declarations = method.getTypeParameters();
      Element t = declarations.get(0);
      Element u = declarations.get(1);
      Element v = declarations.get(2);
      Type box = method.getParameters().get(0).type;
      Type word = method.getParameters().get(1).type;
      var order = name.equals("cycleReverse") ? List.of(u, t, v) : List.of(t, u, v);
      var shapes =
          name.equals("graphUpdate") || name.equals("fBoundWithProperLower")
              ? Map.of(t, word, u, box, v, word)
              : Map.of(t, box, u, box, v, word);
      var fresh =
          solver.registerInferenceVariables(tree, order, Map.of(), Set.copyOf(order), shapes);
      solver.addSubtypeConstraint(word, fresh.get(v), false);
      if (name.equals("graphUpdate")) {
        solver.addSubtypeConstraint(withArgument(box, fresh.get(t)), fresh.get(u), false);
        solver.addSubtypeConstraint(fresh.get(u), state.getSymtab().objectType, false);
        Solution before = solver.solve();
        assertThat(solver.knownNullness(new InferenceVariable(t, tree))).isNull();
        assertThat(solver.knownNullness(new InferenceVariable(u, tree)))
            .isEqualTo(Nullness.NONNULL);
        solver.addSubtypeConstraint(word, fresh.get(t), false);
        Solution nonNull = solver.solve();
        assertThat(nonNull.inferredTypes()).hasSize(3);
        assertCertifiedProper(nonNull);
        Type nullable =
            TypeSubstitutionUtils.typeWithAnnot(
                word, GenericsChecks.getSyntheticNullableAnnotType(state));
        solver.addSubtypeConstraint(nullable, fresh.get(t), false);
        Solution after = solver.solve();
        assertThat(after.inferredTypes()).hasSize(3);
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    after.inferredTypes().get(new InferenceVariable(t, tree)),
                    analysis.getConfig()))
            .isTrue();
        Type nested =
            after.inferredTypes().get(new InferenceVariable(u, tree)).getTypeArguments().head;
        assertThat(GenericsUtils.isAnnotatedAsNullable(nested, analysis.getConfig())).isTrue();
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    nonNull.inferredTypes().get(new InferenceVariable(t, tree)),
                    analysis.getConfig()))
            .isFalse();
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    before.inferredTypes().get(new InferenceVariable(t, tree)),
                    analysis.getConfig()))
            .isFalse();
        assertThrows(UnsupportedOperationException.class, after.inferredTypes()::clear);
        assertThrows(UnsupportedOperationException.class, after.incompleteVariables()::clear);
        assertCertifiedProper(after);
        return;
      }
      if (name.equals("fBoundWithProperLower")) {
        solver.addSubtypeConstraint(word, fresh.get(t), false);
        Type upper = ((Type.TypeVar) ((Symbol) t).type).getUpperBound();
        upper =
            TypeSubstitutionUtils.substituteTypeVariables(
                upper, Map.of(t, word), state.getTypes(), analysis.getConfig());
        assertThat(state.getTypes().isSubtype(word, upper)).isTrue();
      } else {
        if (name.equals("properLowerBeforeCycle")) {
          solver.addSubtypeConstraint(box, fresh.get(t), false);
        }
        solver.addSubtypeConstraint(withArgument(box, fresh.get(u)), fresh.get(t), false);
        solver.addSubtypeConstraint(withArgument(box, fresh.get(t)), fresh.get(u), false);
      }
      Solution result = solver.solve();
      if (!name.equals("fBoundWithProperLower")) {
        assertThat(
                result.incompleteVariables().contains(new InferenceVariable(t, tree))
                    || result.inconsistentVariables().contains(new InferenceVariable(t, tree)))
            .isTrue();
        assertThat(
                result.incompleteVariables().contains(new InferenceVariable(u, tree))
                    || result.inconsistentVariables().contains(new InferenceVariable(u, tree)))
            .isTrue();
      } else {
        assertThat(result.inconsistentVariables()).doesNotContain(new InferenceVariable(t, tree));
      }
      assertThat(result.incompleteVariables()).doesNotContain(new InferenceVariable(v, tree));
      assertThat(result.inconsistentVariables()).doesNotContain(new InferenceVariable(v, tree));
      assertThat(
              state
                  .getTypes()
                  .isSameType(result.inferredTypes().get(new InferenceVariable(v, tree)), word))
          .isTrue();
      assertCertifiedProper(result);
      assertThrows(UnsupportedOperationException.class, result.inferredTypes()::clear);
    }

    /** No declared or fresh placeholder may escape as a certified proper substitution here. */
    private static void assertCertifiedProper(Solution result) {
      result
          .inferredTypes()
          .forEach(
              (variable, type) -> {
                if (!result.incompleteVariables().contains(variable)
                    && !result.inconsistentVariables().contains(variable)) {
                  assertProper(type);
                }
              });
    }

    /** Recursively checks the ordinary proper types in the retained foundation graph fixtures. */
    private static void assertProper(Type type) {
      assertThat(type).isNotInstanceOf(Type.TypeVar.class);
      assertThat(type).isNotInstanceOf(Type.WildcardType.class);
      if (type instanceof Type.ClassType clazz) {
        assertProper(clazz.getEnclosingType());
        for (Type argument : clazz.getTypeArguments()) {
          assertProper(argument);
        }
      } else if (type instanceof Type.ArrayType array) {
        assertProper(array.elemtype);
      } else {
        assertThat(type.isPrimitive() || type.hasTag(TypeTag.NONE)).isTrue();
      }
    }

    /** Builds a structural lower bound without using the retired helper's counting subclass. */
    private static Type withArgument(Type template, Type argument) {
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          template, template.getEnclosingType(), com.sun.tools.javac.util.List.of(argument));
    }
  }
}
