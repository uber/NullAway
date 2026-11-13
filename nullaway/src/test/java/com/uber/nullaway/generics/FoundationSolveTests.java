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
import com.uber.nullaway.generics.ConstraintSolver.InferredType;
import com.uber.nullaway.generics.ConstraintSolver.RootOnly;
import com.uber.nullaway.generics.ConstraintSolver.Substitution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Contracts for one typed solve, solve-local resolution reuse, and explicit root-only fallback. */
public class FoundationSolveTests extends NullAwayTestsBase {

  @Test
  public void rootOnlyFallbackRejectsNonQualifierStates() {
    assertThat(new RootOnly(Nullness.NONNULL).nullness()).isEqualTo(Nullness.NONNULL);
    assertThat(new RootOnly(Nullness.NULLABLE).nullness()).isEqualTo(Nullness.NULLABLE);
    assertThrows(IllegalArgumentException.class, () -> new RootOnly(Nullness.NULL));
    assertThrows(IllegalArgumentException.class, () -> new RootOnly(Nullness.BOTTOM));
  }

  @Test
  public void nestedLowerChainsHaveBoundedWork() {
    StringBuilder source =
        new StringBuilder(
            "import org.jspecify.annotations.*; @NullMarked class Test { static class Box<T extends @Nullable Object> {} ");
    for (int depth : new int[] {12, 24}) {
      List<String> declarations = new ArrayList<>();
      for (int index = 0; index < depth; index++) {
        declarations.add("T" + index + " extends @Nullable Object");
      }
      source
          .append("<")
          .append(String.join(",", declarations))
          .append("> void chain")
          .append(depth)
          .append("(Box<String> box, String word) {}");
    }
    source.append("}");
    CompilationTestHelper.newInstance(SolveChecker.class, getClass())
        .addSourceLines("Test.java", source.toString())
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

  /** Uses real compiler symbols while observing only reads of the supplied structured bounds. */
  @BugPattern(summary = "Checks foundation solve contracts", severity = SUGGESTION)
  public static final class SolveChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {
    /**
     * Builds the named input graph and checks supported types rather than only final diagnostics.
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
      if (tree.getName().toString().startsWith("chain")) {
        checkChain(tree, method, solver, state);
      } else {
        checkCycleAndUpdate(tree, method, solver, state, analysis);
      }
      return NO_MATCH;
    }

    /** A getter budget fails exponentially repeated work without depending on compiler speed. */
    private static void checkChain(
        MethodTree tree, Symbol.MethodSymbol method, ConstraintSolver solver, VisitorState state) {
      var declarations = method.getTypeParameters();
      Type.ClassType box = (Type.ClassType) method.getParameters().get(0).type;
      Type word = method.getParameters().get(1).type;
      Map<Element, Type> shapes = new LinkedHashMap<>();
      Type shape = word;
      for (int index = declarations.size() - 1; index >= 0; index--) {
        shapes.put(declarations.get(index), shape);
        shape = withArgument(box, shape);
      }
      var fresh = solver.registerInferenceVariables(tree, declarations, shapes);
      AtomicInteger reads = new AtomicInteger();
      int budget = 4 * declarations.size();
      for (int index = 0; index < declarations.size(); index++) {
        Type lower =
            index + 1 == declarations.size()
                ? word
                : new CountingClassType(box, fresh.get(declarations.get(index + 1)), reads, budget);
        solver.addSubtypeConstraint(lower, fresh.get(declarations.get(index)), false);
      }
      reads.set(0);
      var result = solver.solve();
      assertThat(result).hasSize(declarations.size());
      for (Element declaration : declarations) {
        Substitution inferred = (Substitution) result.get(new InferenceVariable(declaration, tree));
        assertThat(state.getTypes().isSameType(inferred.type(), shapes.get(declaration))).isTrue();
        assertProper(inferred.type());
      }
      assertThat(reads.get()).isGreaterThan(0);
      assertThat(reads.get()).isAtMost(budget);
      assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /**
     * Cycles retain fallback, while independent values and later graph/root updates still solve.
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
      var fresh = solver.registerInferenceVariables(tree, order, shapes);
      solver.addSubtypeConstraint(word, fresh.get(v), false);
      if (name.equals("graphUpdate")) {
        solver.addSubtypeConstraint(withArgument(box, fresh.get(t)), fresh.get(u), false);
        solver.addSubtypeConstraint(fresh.get(u), state.getSymtab().objectType, false);
        var before = solver.solve();
        assertThat(before).doesNotContainKey(new InferenceVariable(t, tree));
        assertThat(before.get(new InferenceVariable(u, tree)))
            .isEqualTo(new RootOnly(Nullness.NONNULL));
        solver.addSubtypeConstraint(word, fresh.get(t), false);
        var nonNull = solver.solve();
        assertThat(nonNull).hasSize(3);
        assertThat(nonNull.get(new InferenceVariable(t, tree))).isInstanceOf(Substitution.class);
        assertThat(nonNull.get(new InferenceVariable(t, tree)).rootNullness(analysis.getConfig()))
            .isEqualTo(Nullness.NONNULL);
        Type nullable =
            TypeSubstitutionUtils.typeWithAnnot(
                word, GenericsChecks.getSyntheticNullableAnnotType(state));
        solver.addSubtypeConstraint(nullable, fresh.get(t), false);
        var after = solver.solve();
        assertThat(after).hasSize(3);
        assertThat(after.get(new InferenceVariable(t, tree)).rootNullness(analysis.getConfig()))
            .isEqualTo(Nullness.NULLABLE);
        Type nested =
            ((Substitution) after.get(new InferenceVariable(u, tree)))
                .type()
                .getTypeArguments()
                .head;
        assertThat(GenericsUtils.isAnnotatedAsNullable(nested, analysis.getConfig())).isTrue();
        assertThat(nonNull.get(new InferenceVariable(t, tree)).rootNullness(analysis.getConfig()))
            .isEqualTo(Nullness.NONNULL);
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
      Map<InferenceVariable, InferredType> result = solver.solve();
      assertThat(result.get(new InferenceVariable(t, tree))).isNotInstanceOf(Substitution.class);
      assertThat(result.get(new InferenceVariable(u, tree))).isNotInstanceOf(Substitution.class);
      assertThat(result.get(new InferenceVariable(v, tree))).isInstanceOf(Substitution.class);
    }

    /**
     * Recursively rules out any declared/fresh variable or unsupported shape in published types.
     */
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

    /** Creates a regular nominal shape, not the counting bound used by the work regression. */
    private static Type withArgument(Type template, Type argument) {
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          template, template.getEnclosingType(), com.sun.tools.javac.util.List.of(argument));
    }
  }

  /** Throws during an unbounded traversal, instead of leaving a compiler thread stalled. */
  private static final class CountingClassType extends Type.ClassType {
    private final AtomicInteger reads;
    private final int budget;

    CountingClassType(Type.ClassType template, Type argument, AtomicInteger reads, int budget) {
      super(template.getEnclosingType(), com.sun.tools.javac.util.List.of(argument), template.tsym);
      this.reads = reads;
      this.budget = budget;
    }

    /**
     * Counts reads of original symbolic lower bounds; materialized types are ordinary class types.
     */
    @Override
    public com.sun.tools.javac.util.List<Type> getTypeArguments() {
      if (reads.incrementAndGet() > budget) {
        throw new AssertionError(
            "Repeated nested-bound resolution exceeded " + budget + " argument reads");
      }
      return super.getTypeArguments();
    }
  }
}
