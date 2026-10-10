package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import com.uber.nullaway.generics.ConstraintSolver.NonNullWildcardBoundViolationException;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Direct solver assertions on attributed javac types, executed during an active compilation. */
@RunWith(JUnit4.class)
public class ConstraintSolverImplTests {

  private static final ThreadLocal<List<String>> EXECUTED_MARKERS =
      ThreadLocal.withInitial(ArrayList::new);

  @Test
  public void callsToSameDeclarationHaveSeparateInferenceVariables() {
    runFixture("callSeparation", "<T extends @Nullable Object> void inference(String shape) {}");
  }

  @Test
  public void completeSupplierSubstitutionRetainsNullableFixedOuterSymbol() {
    runFixture(
        "nestedSupplier",
        """
        <R extends Supplier<?>> void inference(
            Supplier<@Nullable OuterT> lower, Supplier<OuterT> shape) {}
        """);
  }

  @Test
  public void enclosingTypeArgumentsConstrainInnerClassSubtyping() {
    runFixture(
        "enclosingType",
        """
        static class Outer<E extends @Nullable Object> {
          class Inner {}
        }
        <T extends @Nullable Object> void inference(
            Outer<@Nullable String>.Inner lower, Outer<T>.Inner template, String shape) {}
        """);
  }

  @Test
  public void covariantArrayMergeIsIndependentOfLowerBoundOrder() {
    runFixture(
        "arrayMerge",
        """
        <R extends @Nullable Object> void inference(
            String[] nonNullElements, @Nullable String[] nullableElements) {}
        """);
  }

  @Test
  public void contradictoryInvariantBoundsAreTaggedInconsistent() {
    runFixture(
        "invariantContradiction",
        """
        <R extends @Nullable Object, U extends @Nullable Object> void inference(
            Box<String> nonNullArgument, Box<@Nullable String> nullableArgument,
            String unrelatedShape) {}
        """);
  }

  @Test
  public void unusedRegisteredVariablesHaveConcreteShapesAndNonNullRootDefaults() {
    runFixture(
        "unusedVariables",
        "<T, U extends @Nullable Object> void inference(String tShape, Object uShape) {}");
  }

  @Test
  public void registrationAndSolvingDoNotMutateSourceOrDeclaredTypes() {
    runFixture(
        "sourceIsolation",
        """
        <T extends @Nullable Object, R extends Supplier<T>> void inference(
            Supplier<T> template, @Nullable String nullableT, Supplier<String> rShape,
            String tShape, Test<?> wildcard, String[] array,
            @Nullable String[] nullableElements) {}
        """);
  }

  @Test
  public void dependentVariablesReceiveLateNullableEvidence() {
    runFixture(
        "lateEvidence",
        """
        <T extends @Nullable Object, R extends Supplier<T>> void inference(
            Supplier<T> template, @Nullable String nullableT, Supplier<String> rShape,
            String tShape) {}
        """);
  }

  @Test
  public void nullableHandlerModelOverridesNonNullReceiverInstantiatedBound() {
    runFixture(
        "modeledBound", "<T extends OuterT> void inference(String receiverBound, String shape) {}");
  }

  @Test
  public void fourArgumentRegistrationWithoutJavaShapesIsIncomplete() {
    runFixture("missingShapes", "<T extends @Nullable Object> void inference(String evidence) {}");
  }

  @Test
  public void nonNullWildcardObligationIsIndependentOfEvidenceOrderAndRepeatedSolves() {
    runFixture(
        "wildcardEvidenceOrder",
        """
        <E extends @Nullable Object, R extends @Nullable Object, U> void inference(
            Box<E> inner, Box<R> outer, Box<? extends U> required,
            OuterT fixed, Object shape) {}
        """);
  }

  @Test
  public void annotatedOccurrencesBlockNonNullWildcardRootEvidence() {
    runFixture(
        "wildcardProjectionBarriers",
        """
        <E, R extends @Nullable Object, U> void inference(
            Box<E> inner, Box<R> outer, Box<? extends U> required,
            OuterT fixed, @Nullable E nullableDestination, @NonNull OuterT nonNullSource,
            Object shape) {}
        """);
  }

  @Test
  public void modeledUnregisteredFixedSourceProvesNonNullWildcardViolation() {
    runFixture(
        "wildcardModeledFixedSource",
        """
        static <S, E extends @Nullable Object, R extends @Nullable Object, U> void inference(
            Box<E> inner, Box<R> outer, Box<? extends U> required,
            S fixed, @NonNull S nonNullSource, Object shape) {}
        """);
  }

  /** Compiles one marker-field scenario and verifies its assertions ran exactly once. */
  private void runFixture(String marker, String members) {
    EXECUTED_MARKERS.get().clear();
    try {
      CompilationTestHelper.newInstance(SolverAssertionsChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import java.util.function.Supplier;
              import org.jspecify.annotations.NonNull;
              import org.jspecify.annotations.Nullable;
              class Test<OuterT extends @Nullable Object> {
                static class Box<E extends @Nullable Object> {}
                static <V extends @Nullable Object> V id(V value) { return value; }
                Object siteA = id(null);
                Object siteB = id(null);
              """
                  + members
                  + "\nObject "
                  + marker
                  + ";\n}\n")
          .doTest();
      assertThat(EXECUTED_MARKERS.get()).containsExactly(marker);
    } finally {
      EXECUTED_MARKERS.remove();
    }
  }

  /** Runs direct solver assertions at a marker field while compiler types are available. */
  @BugPattern(summary = "Checks compiler-backed constraint solver results", severity = SUGGESTION)
  public static final class SolverAssertionsChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {

    private static final Set<String> MARKERS =
        Set.of(
            "callSeparation",
            "nestedSupplier",
            "enclosingType",
            "arrayMerge",
            "invariantContradiction",
            "unusedVariables",
            "sourceIsolation",
            "lateEvidence",
            "modeledBound",
            "missingShapes",
            "wildcardEvidenceOrder",
            "wildcardProjectionBarriers",
            "wildcardModeledFixedSource");

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      String marker = tree.getName().toString();
      if (!MARKERS.contains(marker)) {
        return NO_MATCH;
      }
      TestContext context = createContext(state);
      switch (marker) {
        case "callSeparation" -> checkCallSeparation(context);
        case "nestedSupplier" -> checkNestedSupplier(context);
        case "enclosingType" -> checkEnclosingType(context);
        case "arrayMerge" -> checkArrayMerge(context);
        case "invariantContradiction" -> checkInvariantContradiction(context);
        case "unusedVariables" -> checkUnusedVariables(context);
        case "sourceIsolation" -> checkSourceIsolation(context);
        case "lateEvidence" -> checkLateEvidence(context);
        case "modeledBound" -> checkModeledBound(context);
        case "missingShapes" -> checkMissingShapes(context);
        case "wildcardEvidenceOrder" -> checkWildcardEvidenceOrder(context);
        case "wildcardProjectionBarriers" -> checkWildcardProjectionBarriers(context);
        case "wildcardModeledFixedSource" -> checkWildcardModeledFixedSource(context);
        default -> throw new AssertionError("Unhandled marker: " + marker);
      }
      EXECUTED_MARKERS.get().add(marker);
      return NO_MATCH;
    }

    /** Finds the fixture declarations and invocation sites without fabricating compiler symbols. */
    private static TestContext createContext(VisitorState state) {
      TreePath path = state.getPath();
      while (!(path.getLeaf() instanceof ClassTree)) {
        path = java.util.Objects.requireNonNull(path.getParentPath());
      }
      ClassTree classTree = (ClassTree) path.getLeaf();
      MethodTree declaration = null;
      MethodInvocationTree siteA = null;
      MethodInvocationTree siteB = null;
      for (Tree member : classTree.getMembers()) {
        if (member instanceof MethodTree method && method.getName().contentEquals("inference")) {
          declaration = method;
        } else if (member instanceof VariableTree field) {
          if (field.getName().contentEquals("siteA")) {
            siteA = (MethodInvocationTree) field.getInitializer();
          } else if (field.getName().contentEquals("siteB")) {
            siteB = (MethodInvocationTree) field.getInitializer();
          }
        }
      }
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      return new TestContext(
          state,
          config,
          ASTHelpers.getSymbol(classTree),
          ASTHelpers.getSymbol(java.util.Objects.requireNonNull(declaration)),
          java.util.Objects.requireNonNull(siteA),
          java.util.Objects.requireNonNull(siteB));
    }

    /** Supplies only the handler dependency required by the solver's NullAway constructor input. */
    private static ConstraintSolver newSolver(TestContext context, Handler handler) {
      NullAway analysis = mock(NullAway.class);
      when(analysis.getHandler()).thenReturn(handler);
      return new ConstraintSolverImpl(context.config(), context.state(), analysis);
    }

    /** Creates an isolated solver with the interface's real no-op default handler methods. */
    private static ConstraintSolver newSolver(TestContext context) {
      return newSolver(context, new Handler() {});
    }

    /** Registers all method variables with marked declaration bounds and attributed Java shapes. */
    private static Map<Element, Type.TypeVar> register(
        TestContext context, ConstraintSolver solver, Tree site, Map<Element, Type> shapes) {
      return solver.registerInferenceVariables(
          site,
          context.declaration().getTypeParameters(),
          Map.of(),
          Set.copyOf(context.declaration().getTypeParameters()),
          shapes);
    }

    /** Checks freshness, idempotent registration, and independent nullness at two call sites. */
    private static void checkCallSeparation(TestContext context) {
      ConstraintSolver solver = newSolver(context);
      Element declared = context.variable(0);
      Type shape = context.parameter(0);
      Map<Element, Type> shapes = Map.of(declared, shape);
      Type.TypeVar first = register(context, solver, context.siteA(), shapes).get(declared);
      Type.TypeVar second = register(context, solver, context.siteB(), shapes).get(declared);
      assertThat(first.tsym).isNotSameInstanceAs(second.tsym);
      assertThat(first.tsym).isNotSameInstanceAs(declared);
      assertThat(register(context, solver, context.siteA(), shapes).get(declared))
          .isSameInstanceAs(first);
      solver.addSubtypeConstraint(context.state().getSymtab().botType, first, false);
      solver.addSubtypeConstraint(second, shape, false);
      Solution solution = solver.solve();
      InferenceVariable firstKey = new InferenceVariable(declared, context.siteA());
      InferenceVariable secondKey = new InferenceVariable(declared, context.siteB());
      assertComplete(solution, firstKey, secondKey);
      assertNullness(solution.inferredTypes().get(firstKey), true, context);
      assertNullness(solution.inferredTypes().get(secondKey), false, context);
      assertThat(solution.inferredTypes().get(firstKey).tsym).isSameInstanceAs(shape.tsym);
      assertThat(solution.inferredTypes().get(secondKey).tsym).isSameInstanceAs(shape.tsym);
    }

    /** Rejects wildcard upper-bound fallback and preserves the fixed outer variable's symbol. */
    private static void checkNestedSupplier(TestContext context) {
      ConstraintSolver solver = newSolver(context);
      Element declared = context.variable(0);
      Type lower = context.parameter(0);
      Type shape = context.parameter(1);
      Type.TypeVar fresh =
          register(context, solver, context.siteA(), Map.of(declared, shape)).get(declared);
      solver.addSubtypeConstraint(lower, fresh, false);
      Solution solution = solver.solve();
      InferenceVariable key = new InferenceVariable(declared, context.siteA());
      assertComplete(solution, key);
      Type result = solution.inferredTypes().get(key);
      assertThat(result).isInstanceOf(Type.ClassType.class);
      assertThat(result.tsym).isSameInstanceAs(shape.tsym);
      assertNullness(result, false, context);
      assertThat(result.getTypeArguments()).hasSize(1);
      Type argument = result.getTypeArguments().head;
      assertThat(argument).isInstanceOf(Type.TypeVar.class);
      assertThat(argument).isNotInstanceOf(Type.CapturedType.class);
      assertThat(argument.tsym).isSameInstanceAs(context.owner().getTypeParameters().head);
      assertNullness(argument, true, context);
      assertThat(((Type.TypeVar) argument).getUpperBound().tsym)
          .isSameInstanceAs(
              ((Type.TypeVar) context.owner().getTypeParameters().head.type).getUpperBound().tsym);
      assertNoFreshVariables(result, List.of(fresh));
    }

    /** Infers nullness from enclosing arguments without mutating the original inner type graphs. */
    private static void checkEnclosingType(TestContext context) {
      ConstraintSolver solver = newSolver(context);
      Element declared = context.variable(0);
      Type lower = context.parameter(0);
      Type template = context.parameter(1);
      Type shape = context.parameter(2);
      SourceSnapshot snapshot = new SourceSnapshot();
      snapshot.capture(lower);
      snapshot.capture(template);
      snapshot.capture((Type) declared.asType());
      Type.TypeVar fresh =
          register(context, solver, context.siteA(), Map.of(declared, shape)).get(declared);
      Type supertype =
          TypeSubstitutionUtils.substituteTypeVariables(
              template, Map.of(declared, fresh), context.state().getTypes(), context.config());
      assertThat(lower.getTypeArguments()).isEmpty();
      assertThat(supertype.getTypeArguments()).isEmpty();
      assertThat(supertype.getEnclosingType().getTypeArguments().head.tsym)
          .isSameInstanceAs(fresh.tsym);
      solver.addSubtypeConstraint(lower, supertype, false);
      Solution solution = solver.solve();
      InferenceVariable key = new InferenceVariable(declared, context.siteA());
      assertComplete(solution, key);
      Type result = solution.inferredTypes().get(key);
      assertThat(result.tsym).isSameInstanceAs(shape.tsym);
      assertNullness(result, true, context);
      assertNoFreshVariables(result, List.of(fresh));
      snapshot.assertUnchanged();
      assertThat(template.getEnclosingType().getTypeArguments().head.tsym)
          .isSameInstanceAs(declared);
      assertNullness(lower.getEnclosingType().getTypeArguments().head, true, context);
    }

    /** Requires nullable array components, but non-null array roots, in both insertion orders. */
    private static void checkArrayMerge(TestContext context) {
      for (boolean nullableFirst : List.of(false, true)) {
        ConstraintSolver solver = newSolver(context);
        Element declared = context.variable(0);
        Type nonNullElements = context.parameter(0);
        Type nullableElements = context.parameter(1);
        assertNullness(((Type.ArrayType) nonNullElements).elemtype, false, context, false);
        assertNullness(((Type.ArrayType) nullableElements).elemtype, true, context);
        Type.TypeVar fresh =
            register(context, solver, context.siteA(), Map.of(declared, nonNullElements))
                .get(declared);
        solver.addSubtypeConstraint(
            nullableFirst ? nullableElements : nonNullElements, fresh, false);
        solver.addSubtypeConstraint(
            nullableFirst ? nonNullElements : nullableElements, fresh, false);
        Solution solution = solver.solve();
        InferenceVariable key = new InferenceVariable(declared, context.siteA());
        assertComplete(solution, key);
        Type result = solution.inferredTypes().get(key);
        assertThat(result).isInstanceOf(Type.ArrayType.class);
        assertNullness(result, false, context);
        Type component = ((Type.ArrayType) result).elemtype;
        assertThat(component.tsym)
            .isSameInstanceAs(((Type.ArrayType) nonNullElements).elemtype.tsym);
        assertNullness(component, true, context);
        assertNoFreshVariables(result, List.of(fresh));
      }
    }

    /** Requires contradictory invariant evidence to taint only the affected inference variable. */
    private static void checkInvariantContradiction(TestContext context) {
      for (boolean nullableFirst : List.of(false, true)) {
        ConstraintSolver solver = newSolver(context);
        Element declared = context.variable(0);
        Element unrelated = context.variable(1);
        Map<Element, Type.TypeVar> fresh =
            register(
                context,
                solver,
                context.siteA(),
                Map.of(declared, context.parameter(0), unrelated, context.parameter(2)));
        solver.addSubtypeConstraint(
            context.parameter(nullableFirst ? 1 : 0), fresh.get(declared), false);
        solver.addSubtypeConstraint(
            context.parameter(nullableFirst ? 0 : 1), fresh.get(declared), false);
        Solution solution = solver.solve();
        InferenceVariable key = new InferenceVariable(declared, context.siteA());
        InferenceVariable unrelatedKey = new InferenceVariable(unrelated, context.siteA());
        assertThat(solution.inferredTypes().keySet()).containsExactly(key, unrelatedKey);
        assertThat(solution.inconsistentVariables()).containsExactly(key);
        assertThat(solution.incompleteVariables()).doesNotContain(unrelatedKey);
        assertThat(solution.isComplete()).isFalse();
        assertThat(solution.isCompleteForSite(context.siteA())).isFalse();
        assertNullness(solution.inferredTypes().get(unrelatedKey), false, context);
      }
    }

    /**
     * Checks that registration alone creates complete concrete results with default root nullness.
     */
    private static void checkUnusedVariables(TestContext context) {
      ConstraintSolver solver = newSolver(context);
      Element first = context.variable(0);
      Element second = context.variable(1);
      register(
          context,
          solver,
          context.siteA(),
          Map.of(first, context.parameter(0), second, context.parameter(1)));
      Solution solution = solver.solve();
      InferenceVariable firstKey = new InferenceVariable(first, context.siteA());
      InferenceVariable secondKey = new InferenceVariable(second, context.siteA());
      assertComplete(solution, firstKey, secondKey);
      for (int index = 0; index < 2; index++) {
        Type result =
            solution
                .inferredTypes()
                .get(new InferenceVariable(context.variable(index), context.siteA()));
        assertThat(result).isInstanceOf(Type.ClassType.class);
        assertThat(result.tsym).isSameInstanceAs(context.parameter(index).tsym);
        assertNullness(result, false, context);
      }
    }

    /**
     * Snapshots recursive source graphs before registration, substitution, merging, and solving.
     */
    private static void checkSourceIsolation(TestContext context) {
      SourceSnapshot snapshot = new SourceSnapshot();
      snapshot.capture(context.owner().type);
      for (Symbol.TypeVariableSymbol variable : context.declaration().getTypeParameters()) {
        snapshot.capture(variable.type);
      }
      for (Symbol.VarSymbol parameter : context.declaration().getParameters()) {
        snapshot.capture(parameter.type);
      }
      solveDependent(context, false);
      snapshot.assertUnchanged();

      ConstraintSolver arrays = newSolver(context);
      Element variable = context.variable(0);
      Type.TypeVar fresh =
          arrays
              .registerInferenceVariables(
                  context.siteB(),
                  List.of(variable),
                  Map.of(),
                  Set.of(variable),
                  Map.of(variable, context.parameter(5)))
              .get(variable);
      arrays.addSubtypeConstraint(context.parameter(5), fresh, false);
      arrays.addSubtypeConstraint(context.parameter(6), fresh, false);
      Solution solution = arrays.solve();
      InferenceVariable key = new InferenceVariable(variable, context.siteB());
      assertComplete(solution, key);
      assertNullness(((Type.ArrayType) solution.inferredTypes().get(key)).elemtype, true, context);
      snapshot.assertUnchanged();
    }

    /** Requires dependent substitution to see nullable evidence regardless of constraint order. */
    private static void checkLateEvidence(TestContext context) {
      solveDependent(context, false);
      solveDependent(context, true);
    }

    /** Solves Supplier<T> evidence and verifies the final substitution contains nullable String. */
    private static void solveDependent(TestContext context, boolean nullableFirst) {
      ConstraintSolver solver = newSolver(context);
      Element t = context.variable(0);
      Element r = context.variable(1);
      Map<Element, Type.TypeVar> fresh =
          register(
              context,
              solver,
              context.siteA(),
              Map.of(t, context.parameter(3), r, context.parameter(2)));
      Type template =
          TypeSubstitutionUtils.substituteTypeVariables(
              context.parameter(0), fresh, context.state().getTypes(), context.config());
      if (nullableFirst) {
        solver.addSubtypeConstraint(context.parameter(1), fresh.get(t), false);
      }
      solver.addSubtypeConstraint(template, fresh.get(r), false);
      if (!nullableFirst) {
        solver.addSubtypeConstraint(context.parameter(1), fresh.get(t), false);
      }
      Solution solution = solver.solve();
      InferenceVariable tKey = new InferenceVariable(t, context.siteA());
      InferenceVariable rKey = new InferenceVariable(r, context.siteA());
      assertComplete(solution, tKey, rKey);
      Type tResult = solution.inferredTypes().get(tKey);
      assertThat(tResult.tsym).isSameInstanceAs(context.parameter(3).tsym);
      assertNullness(tResult, true, context);
      Type rResult = solution.inferredTypes().get(rKey);
      assertThat(rResult).isInstanceOf(Type.ClassType.class);
      assertThat(rResult.tsym).isSameInstanceAs(context.parameter(2).tsym);
      assertNullness(rResult, false, context);
      assertThat(rResult.getTypeArguments()).hasSize(1);
      Type argument = rResult.getTypeArguments().head;
      assertThat(argument).isInstanceOf(Type.ClassType.class);
      assertThat(argument.tsym).isSameInstanceAs(context.parameter(3).tsym);
      assertNullness(argument, true, context);
      assertNoFreshVariables(rResult, List.copyOf(fresh.values()));
    }

    /**
     * Uses a real method owner/index to test model precedence over a substituted receiver bound.
     */
    private static void checkModeledBound(TestContext context) {
      Element declared = context.variable(0);
      Type.TypeVar source = (Type.TypeVar) declared.asType();
      Type originalUpperBound = source.getUpperBound();
      assertThat(originalUpperBound.tsym)
          .isSameInstanceAs(context.owner().getTypeParameters().head);
      Type receiverBound = context.parameter(0);
      Type shape = context.parameter(1);
      Handler modeledHandler =
          new Handler() {
            @Override
            public boolean onOverrideMethodTypeVariableUpperBound(
                Symbol.MethodSymbol methodSymbol, int index, VisitorState state) {
              return methodSymbol.equals(context.declaration()) && index == 0;
            }
          };
      ConstraintSolver modeled = newSolver(context, modeledHandler);
      Type.TypeVar fresh =
          modeled
              .registerInferenceVariables(
                  context.siteA(),
                  List.of(declared),
                  Map.of(declared, receiverBound),
                  Set.of(declared),
                  Map.of(declared, shape))
              .get(declared);
      assertNullness(fresh.getUpperBound(), true, context);
      modeled.addSubtypeConstraint(context.state().getSymtab().botType, fresh, false);
      Solution solution = modeled.solve();
      InferenceVariable key = new InferenceVariable(declared, context.siteA());
      assertComplete(solution, key);
      assertThat(solution.inferredTypes().get(key).tsym).isSameInstanceAs(shape.tsym);
      assertNullness(solution.inferredTypes().get(key), true, context);
      assertThat(source.getUpperBound()).isSameInstanceAs(originalUpperBound);
      assertThat(receiverBound.getAnnotationMirrors()).isEmpty();

      ConstraintSolver control = newSolver(context);
      Type.TypeVar controlFresh =
          control
              .registerInferenceVariables(
                  context.siteB(),
                  List.of(declared),
                  Map.of(declared, receiverBound),
                  Set.of(declared),
                  Map.of(declared, shape))
              .get(declared);
      UnsatisfiableConstraintsException exception =
          assertThrows(
              UnsatisfiableConstraintsException.class,
              () ->
                  control.addSubtypeConstraint(
                      context.state().getSymtab().botType, controlFresh, false));
      assertThat(exception.getTypeVariable()).isSameInstanceAs(declared);
      assertThat(exception.getInferenceSite()).isSameInstanceAs(context.siteB());
      assertThat(exception.isCausedByNonNullUpperBound()).isTrue();
      assertThat(source.getUpperBound()).isSameInstanceAs(originalUpperBound);
    }

    /**
     * Verifies the compatibility overload cannot certify a variable without an attributed shape.
     */
    private static void checkMissingShapes(TestContext context) {
      ConstraintSolver solver = newSolver(context);
      Element declared = context.variable(0);
      Map<Element, Type.TypeVar> fresh =
          solver.registerInferenceVariables(
              context.siteA(), List.of(declared), Map.of(), Set.of(declared));
      solver.addSubtypeConstraint(context.parameter(0), fresh.get(declared), false);
      Solution solution = solver.solve();
      InferenceVariable key = new InferenceVariable(declared, context.siteA());
      assertThat(solution.inferredTypes().keySet()).containsExactly(key);
      assertThat(solution.incompleteVariables()).containsExactly(key);
      assertThat(solution.inconsistentVariables()).isEmpty();
      assertThat(solution.isComplete()).isFalse();
      assertThat(solution.isCompleteForSite(context.siteA())).isFalse();
    }

    /** Registers two inner variables at site A and the non-null wildcard variable at site B. */
    private static Map<Element, Type.TypeVar> registerWildcardChain(
        TestContext context, ConstraintSolver solver, int firstIndex, Type shape) {
      Element inner = context.variable(firstIndex);
      Element outer = context.variable(firstIndex + 1);
      Element required = context.variable(firstIndex + 2);
      Map<Element, Type.TypeVar> innerVariables =
          solver.registerInferenceVariables(
              context.siteA(),
              List.of(inner, outer),
              Map.of(),
              Set.of(inner, outer),
              Map.of(inner, shape, outer, shape));
      Type.TypeVar requiredVariable =
          solver
              .registerInferenceVariables(
                  context.siteB(),
                  List.of(required),
                  Map.of(),
                  Set.of(required),
                  Map.of(required, shape))
              .get(required);
      return Map.of(
          inner, innerVariables.get(inner),
          outer, innerVariables.get(outer),
          required, requiredVariable);
    }

    /** Substitutes registered occurrences into attributed fixture types, retaining projections. */
    private static Type wildcardFixtureType(
        TestContext context, int parameter, Map<Element, ? extends Type> replacements) {
      return TypeSubstitutionUtils.substituteTypeVariables(
          context.parameter(parameter), replacements, context.state().getTypes(), context.config());
    }

    /** Requires the deferred exception, not a contextual scalar contradiction, on every solve. */
    private static void assertWildcardFailure(
        TestContext context, ConstraintSolver solver, Element required) {
      for (int attempt = 0; attempt < 2; attempt++) {
        NonNullWildcardBoundViolationException exception =
            assertThrows(NonNullWildcardBoundViolationException.class, solver::solve);
        assertThat(exception.getClass()).isEqualTo(NonNullWildcardBoundViolationException.class);
        assertThat(exception.getTypeVariable()).isSameInstanceAs(required);
        assertThat(exception.getInferenceSite()).isSameInstanceAs(context.siteB());
        assertThat(exception.isCausedByNonNullUpperBound()).isTrue();
      }
    }

    /** Tests both obligation orders, both lower/edge orders, and evidence added after a solve. */
    private static void checkWildcardEvidenceOrder(TestContext context) {
      Element inner = context.variable(0);
      Element outer = context.variable(1);
      Element required = context.variable(2);
      for (boolean obligationFirst : List.of(false, true)) {
        for (boolean lowerFirst : List.of(false, true)) {
          ConstraintSolver solver = newSolver(context);
          Map<Element, Type.TypeVar> fresh =
              registerWildcardChain(context, solver, 0, context.parameter(4));
          Type actual = wildcardFixtureType(context, 1, fresh);
          Type formal = wildcardFixtureType(context, 2, fresh);
          if (obligationFirst) {
            solver.addSubtypeConstraint(actual, formal, false);
          }
          if (lowerFirst) {
            solver.addSubtypeConstraint(context.parameter(3), fresh.get(inner), false);
          }
          solver.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
          if (!lowerFirst) {
            solver.addSubtypeConstraint(context.parameter(3), fresh.get(inner), false);
          }
          if (!obligationFirst) {
            solver.addSubtypeConstraint(actual, formal, false);
          }
          assertWildcardFailure(context, solver, required);
        }
      }
      ConstraintSolver late = newSolver(context);
      Map<Element, Type.TypeVar> fresh =
          registerWildcardChain(context, late, 0, context.parameter(4));
      late.addSubtypeConstraint(
          wildcardFixtureType(context, 1, fresh), wildcardFixtureType(context, 2, fresh), false);
      late.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
      for (int attempt = 0; attempt < 2; attempt++) {
        assertComplete(
            late.solve(),
            new InferenceVariable(inner, context.siteA()),
            new InferenceVariable(outer, context.siteA()),
            new InferenceVariable(required, context.siteB()));
      }
      late.addSubtypeConstraint(context.parameter(3), fresh.get(inner), false);
      assertWildcardFailure(context, late, required);
    }

    /** Keeps structural evidence while blocking scalar provenance at annotated occurrences. */
    private static void checkWildcardProjectionBarriers(TestContext context) {
      Element inner = context.variable(0);
      Element outer = context.variable(1);
      Element required = context.variable(2);
      List<InferenceVariable> keys =
          List.of(
              new InferenceVariable(inner, context.siteA()),
              new InferenceVariable(outer, context.siteA()),
              new InferenceVariable(required, context.siteB()));
      for (boolean nullableDestination : List.of(true, false)) {
        ConstraintSolver solver = newSolver(context);
        Map<Element, Type.TypeVar> fresh =
            registerWildcardChain(context, solver, 0, context.parameter(6));
        solver.addSubtypeConstraint(
            wildcardFixtureType(context, 1, fresh), wildcardFixtureType(context, 2, fresh), false);
        solver.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
        if (nullableDestination) {
          Type destination = wildcardFixtureType(context, 4, fresh);
          assertNullness(destination, true, context);
          solver.addSubtypeConstraint(context.parameter(3), destination, false);
        } else {
          assertNullness(context.parameter(5), false, context);
          solver.addSubtypeConstraint(context.parameter(5), fresh.get(inner), false);
        }
        Solution solution = solver.solve();
        assertThat(solution.inferredTypes().keySet()).containsExactlyElementsIn(keys);
        // Structural validation excludes root occurrence checks. Both explicit projections allow
        // the non-null Java shape, without leaking the fixed variable's nullable bound into E.
        assertComplete(solution, keys.toArray(new InferenceVariable[0]));
        for (InferenceVariable key : keys) {
          Type result = solution.inferredTypes().get(key);
          assertThat(result.tsym).isSameInstanceAs(context.parameter(6).tsym);
          assertNullness(result, false, context);
          assertNoFreshVariables(result, List.copyOf(fresh.values()));
        }
      }
    }

    /** Checks a real unregistered method variable with model, no-model, and source projections. */
    private static void checkWildcardModeledFixedSource(TestContext context) {
      Element fixed = context.variable(0);
      Element inner = context.variable(1);
      Element outer = context.variable(2);
      Element required = context.variable(3);
      Handler modeledHandler =
          new Handler() {
            @Override
            public boolean onOverrideMethodTypeVariableUpperBound(
                Symbol.MethodSymbol methodSymbol, int index, VisitorState state) {
              return methodSymbol.equals(context.declaration()) && index == 0;
            }
          };
      assertThat(context.parameter(3).tsym).isSameInstanceAs(fixed);
      assertThat(context.parameter(3).getAnnotationMirrors()).isEmpty();
      for (boolean transitive : List.of(false, true)) {
        for (int control = 0; control < 3; control++) {
          ConstraintSolver solver =
              control == 1 ? newSolver(context) : newSolver(context, modeledHandler);
          Map<Element, Type.TypeVar> fresh =
              registerWildcardChain(context, solver, 1, context.parameter(5));
          assertThat(fresh).doesNotContainKey(fixed);
          Type source = context.parameter(control == 2 ? 4 : 3);
          Type formal = wildcardFixtureType(context, 2, fresh);
          if (transitive) {
            solver.addSubtypeConstraint(wildcardFixtureType(context, 1, fresh), formal, false);
            solver.addSubtypeConstraint(fresh.get(inner), fresh.get(outer), false);
            solver.addSubtypeConstraint(source, fresh.get(inner), false);
          } else {
            Type actual = wildcardFixtureType(context, 0, Map.of(inner, source));
            solver.addSubtypeConstraint(actual, formal, false);
          }
          if (control == 0) {
            assertWildcardFailure(context, solver, required);
          } else {
            Solution solution = solver.solve();
            assertComplete(
                solution,
                new InferenceVariable(inner, context.siteA()),
                new InferenceVariable(outer, context.siteA()),
                new InferenceVariable(required, context.siteB()));
            for (Type result : solution.inferredTypes().values()) {
              assertThat(result.tsym).isSameInstanceAs(context.parameter(5).tsym);
              assertNullness(result, false, context);
              assertNoFreshVariables(result, List.copyOf(fresh.values()));
            }
          }
        }
      }
    }

    /** Checks exact result coverage and both independent certification status sets. */
    private static void assertComplete(Solution solution, InferenceVariable... variables) {
      assertThat(solution.inferredTypes().keySet()).containsExactlyElementsIn(List.of(variables));
      assertThat(solution.incompleteVariables()).isEmpty();
      assertThat(solution.inconsistentVariables()).isEmpty();
      assertThat(solution.isComplete()).isTrue();
      for (InferenceVariable variable : variables) {
        assertThat(solution.isCompleteForSite(variable.site())).isTrue();
      }
    }

    /**
     * Requires explicit solved nullness, including synthetic non-null metadata on concrete roots.
     */
    private static void assertNullness(Type type, boolean nullable, TestContext context) {
      assertNullness(type, nullable, context, true);
    }

    /**
     * Distinguishes source default non-nullness from the explicit annotations on solver results.
     */
    private static void assertNullness(
        Type type, boolean nullable, TestContext context, boolean requireExplicitNonNull) {
      assertThat(
              Nullness.hasNullableAnnotation(
                  type.getAnnotationMirrors().stream(), context.config()))
          .isEqualTo(nullable);
      if (nullable || requireExplicitNonNull) {
        assertThat(
                Nullness.hasNonNullAnnotation(
                    type.getAnnotationMirrors().stream(), context.config()))
            .isEqualTo(!nullable);
      }
    }

    /** Rejects unexpanded inference symbols at every position of an inferred annotation source. */
    private static void assertNoFreshVariables(Type result, List<Type.TypeVar> freshVariables) {
      for (Type.TypeVar fresh : freshVariables) {
        TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(fresh.tsym);
        result.accept(collector, null);
        assertThat(collector.getMatches()).isEmpty();
      }
    }
  }

  /** Holds compiler-owned declarations and types for exactly one marker callback. */
  private record TestContext(
      VisitorState state,
      Config config,
      Symbol.ClassSymbol owner,
      Symbol.MethodSymbol declaration,
      MethodInvocationTree siteA,
      MethodInvocationTree siteB) {

    /** Returns a genuine declaration variable, never a synthesized stand-in symbol. */
    Element variable(int index) {
      return declaration.getTypeParameters().get(index);
    }

    /** Returns an attributed parameter type, preserving its source type-use annotations. */
    Type parameter(int index) {
      return declaration.getParameters().get(index).type;
    }
  }

  /** Captures identities and annotations throughout recursive source type graphs. */
  private static final class SourceSnapshot {
    private final IdentityHashMap<Type, Boolean> seen = new IdentityHashMap<>();
    private final List<Runnable> checks = new ArrayList<>();

    /** Saves source metadata and mutable child links, guarding recursive declaration bounds. */
    void capture(Type source) {
      if (source == null || seen.put(source, true) != null) {
        return;
      }
      Symbol symbol = source.tsym;
      var annotations = List.copyOf(source.getAnnotationMirrors());
      checks.add(() -> assertThat(source.tsym).isSameInstanceAs(symbol));
      checks.add(
          () ->
              assertThat(source.getAnnotationMirrors())
                  .containsExactlyElementsIn(annotations)
                  .inOrder());
      List<Type> arguments = List.copyOf(source.getTypeArguments());
      checks.add(() -> assertThat(source.getTypeArguments()).hasSize(arguments.size()));
      for (int index = 0; index < arguments.size(); index++) {
        int argumentIndex = index;
        Type argument = arguments.get(index);
        checks.add(
            () ->
                assertThat(source.getTypeArguments().get(argumentIndex))
                    .isSameInstanceAs(argument));
        capture(argument);
      }
      if (source instanceof Type.TypeVar variable) {
        Type upper = variable.getUpperBound();
        Type lower = variable.getLowerBound();
        checks.add(() -> assertThat(variable.getUpperBound()).isSameInstanceAs(upper));
        checks.add(() -> assertThat(variable.getLowerBound()).isSameInstanceAs(lower));
        capture(upper);
        capture(lower);
      } else if (source instanceof Type.ArrayType array) {
        Type component = array.elemtype;
        checks.add(() -> assertThat(array.elemtype).isSameInstanceAs(component));
        capture(component);
      } else if (source instanceof Type.WildcardType wildcard) {
        Type boundType = wildcard.type;
        Type.TypeVar formalBound = wildcard.bound;
        checks.add(() -> assertThat(wildcard.type).isSameInstanceAs(boundType));
        checks.add(() -> assertThat(wildcard.bound).isSameInstanceAs(formalBound));
        capture(boundType);
        capture(formalBound);
      } else if (source instanceof Type.ClassType classType) {
        Type enclosing = classType.getEnclosingType();
        checks.add(() -> assertThat(classType.getEnclosingType()).isSameInstanceAs(enclosing));
        capture(enclosing);
      }
    }

    /** Rechecks all saved links and metadata after the solver has consumed these types. */
    void assertUnchanged() {
      checks.forEach(Runnable::run);
    }
  }
}
