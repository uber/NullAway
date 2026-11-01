package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Nominal projections must be certified from actual bounds, not javac's annotations. */
public class PolyNullProjectionTests {
  @Test
  public void actualEvidenceControlsProjectionsAndCertification() {
    CompilationTestHelper.newInstance(ProjectionChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Test {
              static Object carrier(Object value) { return value; }
              static class Outer<A> { class Inner {} }
              static <T extends @Nullable Object> void nullableSubtype(
                  List<Object> shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void nullableSupertype(
                  List<Object> shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void nonNullSubtype(
                  List<@Nullable Object> shape, List<Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void nonNullSupertype(
                  List<@Nullable Object> shape, List<Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void lateNullableEvidence(
                  List<Object> shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void nullableEdgeSubtype(
                  List<Object> shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void nullableEdgeSupertype(
                  List<Object> shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void nonNullConflictSubtype(
                  List<@Nullable Object> shape, List<Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void nonNullConflictSupertype(
                  List<@Nullable Object> shape, List<Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void noShape(
                  List<Object> shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void rawShape(
                  List shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void arrayNullable(
                  Object[] shape, @Nullable Object[] evidence) { carrier(null); }
              static <T extends @Nullable Object> void wildcardNullable(
                  List<? extends Object> shape, List<? extends @Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void explicitNonNullConflict(
                  List<Object> shape, List<@Nullable Object> evidence) { carrier(null); }
              static <T extends @Nullable Object, F extends List<@Nullable Object>> void foreignInput(
                  List<Object> shape, F evidence) { carrier(null); }
              static <T extends @Nullable Object, U extends @Nullable Object> void sharedNominal(
                  List<String> shape, List<@Nullable String> evidence,
                  List<Integer> otherShape, List<@Nullable Integer> otherEvidence) { carrier(null); }
              static <T extends @Nullable Object> void unsupportedArray(
                  String[] shape, String[] evidence) { carrier(null); }
              static <T extends @Nullable Object> void unsupportedWildcard(
                  List<? extends String> shape, List<? extends String> evidence) { carrier(null); }
              static <T extends @Nullable Object> void unsupportedArity(
                  List<Object> shape, List<Object> evidence) { carrier(null); }
              static <T extends @Nullable Object> void unsupportedArgument(
                  List<String> shape, List<String> evidence) { carrier(null); }
              static <T extends @Nullable Object> void unsupportedEnclosing(
                  Outer<String>.Inner shape, Outer<String>.Inner evidence) { carrier(null); }
              static <T extends @Nullable Object, U extends @Nullable Object> void symbolicShape(
                  List<@Nullable U> shape, List<@Nullable U> evidence) { carrier(null); }
              static <T extends @Nullable Object, U extends @Nullable Object> void symbolicEvidence(
                  List<@Nullable U> shape, List<@Nullable U> evidence) { carrier(null); }
              static <T extends @Nullable Object, U extends @Nullable Object> void symbolicShapeThenEvidence(
                  List<@Nullable U> shape, List<@Nullable U> evidence) { carrier(null); }
            }
            """)
        .doTest();
  }

  /** Checks both directions, late bounds, missing structure, and genuine non-null conflicts. */
  @BugPattern(summary = "Checks actual PolyNull projection evidence", severity = SUGGESTION)
  public static final class ProjectionChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {
    /** Runs one scenario using compiler-attributed types and a fresh owned PolyNull context. */
    @SuppressWarnings("MemoizeConstantVisitorStateLookups") // Per-compilation test fixture.
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (method == null || method.getTypeParameters().isEmpty()) {
        return NO_MATCH;
      }
      String scenario = tree.getName().toString();
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
      ConstraintSolver solver =
          new PolyNullInference.Solver(
              new ConstraintSolverImpl(analysis.getConfig(), state, analysis),
              state,
              analysis.getConfig(),
              analysis.getHandler());
      MethodInvocationTree invocation =
          (MethodInvocationTree)
              ((ExpressionStatementTree) tree.getBody().getStatements().get(0)).getExpression();
      Symbol.MethodSymbol carrier = ASTHelpers.getSymbol(invocation);
      var context =
          PolyNullInference.createContext(
              carrier,
              invocation,
              (Type.MethodType) carrier.type,
              ImmutableSet.of(
                  new PolyNullLocation(new PolyNullLocation.Return(), ImmutableList.of())),
              GenericsChecks.getSyntheticNullableAnnotType(state),
              state);
      Element t = method.getTypeParameters().head;
      Type.TypeVar poly = context.inferenceVariable();
      Element p = poly.asElement();
      Type shape = method.getParameters().get(0).type;
      Type evidence = method.getParameters().get(1).type;
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              invocation,
              java.util.List.of(t, p),
              Map.of(t, ((Type.TypeVar) t.asType()).getUpperBound(), p, poly.getUpperBound()),
              Set.of(t, p),
              scenario.equals("noShape") ? Map.of() : Map.of(t, shape));
      Type.TypeVar alpha = fresh.get(t);
      Type.TypeVar qualifier = fresh.get(p);
      Type listOfPoly =
          new Type.ClassType(
              Type.noType,
              com.sun.tools.javac.util.List.of(qualifier),
              state.getTypeFromString("java.util.List").tsym);
      Type modeled;
      if (shape instanceof Type.ArrayType array) {
        modeled =
            new Type.ArrayType(
                scenario.equals("unsupportedArray") ? listOfPoly : qualifier, array.tsym);
      } else {
        Type argument = qualifier;
        if (scenario.equals("unsupportedArgument")) {
          argument = listOfPoly;
        }
        if (scenario.equals("wildcardNullable") || scenario.equals("unsupportedWildcard")) {
          Type.WildcardType original = (Type.WildcardType) shape.getTypeArguments().head;
          Type.WildcardType wildcard =
              new Type.WildcardType(
                  scenario.equals("unsupportedWildcard") ? listOfPoly : qualifier,
                  BoundKind.EXTENDS,
                  original.tsym);
          wildcard.bound = original.bound;
          argument = wildcard;
        }
        modeled =
            new Type.ClassType(
                shape.getEnclosingType(),
                scenario.equals("unsupportedArity")
                    ? com.sun.tools.javac.util.List.of(argument, argument)
                    : com.sun.tools.javac.util.List.of(argument),
                shape.tsym);
        if (scenario.equals("unsupportedEnclosing")) {
          Type enclosing = shape.getEnclosingType();
          Type modeledEnclosing =
              new Type.ClassType(
                  enclosing.getEnclosingType(),
                  com.sun.tools.javac.util.List.of(listOfPoly),
                  enclosing.tsym);
          modeled =
              new Type.ClassType(modeledEnclosing, com.sun.tools.javac.util.List.nil(), shape.tsym);
        }
      }
      Type nullableObject =
          TypeSubstitutionUtils.typeWithAnnot(
              state.getSymtab().objectType, GenericsChecks.getSyntheticNullableAnnotType(state));
      if (scenario.equals("foreignInput")) {
        solver.registerExternalInferenceSymbols(Set.of(method.getTypeParameters().get(1)));
      }
      if (!scenario.equals("lateNullableEvidence") && !scenario.startsWith("symbolicShape")) {
        solver.addSubtypeConstraint(evidence, alpha, false);
      }
      if (scenario.equals("explicitNonNullConflict")) {
        solver.addSubtypeConstraint(qualifier, state.getSymtab().objectType, false);
      } else if (scenario.endsWith("Supertype")) {
        solver.addSubtypeConstraint(modeled, alpha, false);
      } else {
        solver.addSubtypeConstraint(alpha, modeled, false);
      }
      if (scenario.equals("lateNullableEvidence")) {
        solver.addSubtypeConstraint(evidence, alpha, false);
      }
      if (scenario.equals("sharedNominal")) {
        Element u = method.getTypeParameters().get(1);
        Type other =
            solver
                .registerInferenceVariables(
                    tree,
                    java.util.List.of(u),
                    Map.of(u, ((Type.TypeVar) u.asType()).getUpperBound()),
                    Set.of(u),
                    Map.of(u, method.getParameters().get(2).type))
                .get(u);
        solver.addSubtypeConstraint(method.getParameters().get(3).type, other, false);
        solver.addSubtypeConstraint(other, modeled, false);
      }
      if (scenario.startsWith("nonNullConflict") || scenario.equals("explicitNonNullConflict")) {
        ConstraintSolver.UnsatisfiableConstraintsException failure =
            assertThrows(
                ConstraintSolver.UnsatisfiableConstraintsException.class,
                () -> {
                  solver.addSubtypeConstraint(nullableObject, qualifier, false);
                  solver.solve();
                });
        assertThat(failure.getTypeVariable()).isSameInstanceAs(p);
        assertThat(failure.getInferenceSite()).isSameInstanceAs(invocation);
        assertThat(failure.isCausedByNonNullUpperBound()).isFalse();
        return NO_MATCH;
      }
      if (scenario.startsWith("nullableEdge")) {
        solver.addSubtypeConstraint(nullableObject, qualifier, false);
      }
      if (scenario.equals("symbolicShapeThenEvidence")) {
        assertThat(solver.solve().isComplete()).isFalse();
        solver.addSubtypeConstraint(evidence, alpha, false);
      }
      ConstraintSolver.Solution solution = solver.solve();
      var key = new ConstraintSolver.InferenceVariable(p, invocation);
      for (ConstraintSolver.InferenceVariable candidate : solution.inferredTypes().keySet()) {
        if (!candidate.typeVariable().equals(p)
            && !method.getTypeParameters().contains(candidate.typeVariable())) {
          assertThat(candidate.site().getKind()).isEqualTo(Tree.Kind.OTHER);
          assertThat(
                  candidate
                      .site()
                      .accept(
                          new SimpleTreeVisitor<Tree, String>() {
                            @Override
                            protected Tree defaultAction(Tree node, String unused) {
                              return node;
                            }
                          },
                          "projection"))
              .isSameInstanceAs(candidate.site());
        }
      }
      if (scenario.equals("nullableSubtype")) {
        assertThat(solver.knownNullness(new ConstraintSolver.InferenceVariable(t, invocation)))
            .isEqualTo(com.uber.nullaway.Nullness.NONNULL);
      }
      if (scenario.equals("foreignInput")) {
        assertThat(solution.inputDependencies().variables()).contains(key);
        assertThat(solution.inputDependencies().isIndependentForSite(invocation)).isFalse();
        assertThat(solver.hasExternalInputDependency(qualifier)).isTrue();
        return NO_MATCH;
      }
      if (scenario.equals("noShape")
          || scenario.equals("rawShape")
          || scenario.equals("symbolicShape")
          || scenario.startsWith("unsupported")) {
        assertThat(solution.isComplete()).isFalse();
        assertThat(solution.incompleteVariables()).contains(key);
        if (scenario.equals("symbolicShape")) {
          assertThat(solution.incompleteVariables())
              .contains(new ConstraintSolver.InferenceVariable(t, invocation));
        }
      } else {
        assertThat(solution.isComplete()).isTrue();
        assertThat(solution.inconsistentVariables()).isEmpty();
        assertThat(
                GenericsUtils.isAnnotatedAsNullable(
                    solution.inferredTypes().get(key), analysis.getConfig()))
            .isEqualTo(!scenario.startsWith("nonNull"));
        assertThat(solution.inputDependencies().variables()).isEmpty();
      }
      return NO_MATCH;
    }
  }

  @Test
  public void nominalPassRetainsIndividuallyPassedVarargsArray() {
    CompilationTestHelper.newInstance(VarargsChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test {
              static Object varargs(Object... values) { return values; }
              void test(Object[] values) {
                varargs(new Object(), new Object());
                varargs(values);
              }
            }
            """)
        .doTest();
  }

  /** Checks the synthetic varargs array, which is not a modeled qualifier input. */
  @BugPattern(summary = "Checks nominal PolyNull varargs signatures", severity = SUGGESTION)
  public static final class VarargsChecker extends BugChecker
      implements BugChecker.MethodInvocationTreeMatcher {
    /** Compares array-passing and element-passing views without changing compiler types. */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (!method.getSimpleName().contentEquals("varargs")) {
        return NO_MATCH;
      }
      Type.MethodType original = method.type.asMethodType();
      var context =
          PolyNullInference.createContext(
              method,
              tree,
              original,
              ImmutableSet.of(
                  new PolyNullLocation(new PolyNullLocation.Parameter(0), ImmutableList.of()),
                  new PolyNullLocation(new PolyNullLocation.Return(), ImmutableList.of())),
              GenericsChecks.getSyntheticNullableAnnotType(state),
              state);
      Type.MethodType nominal =
          PolyNullInference.argumentMethodType(
              original,
              context,
              Map.of(context.inferenceVariable().asElement(), context.inferenceVariable()));
      if (NullabilityUtil.isVarArgsCall(tree)) {
        assertThat(nominal.argtypes.head).isSameInstanceAs(original.argtypes.head);
      } else {
        assertThat(nominal.argtypes.head).isSameInstanceAs(context.inferenceVariable());
      }
      assertThat(nominal.restype).isSameInstanceAs(original.restype);
      return NO_MATCH;
    }
  }
}
