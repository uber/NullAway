package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static org.junit.Assert.assertThrows;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.NullAway;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Checks that comparisons grow linearly with bound depth, a repeated comparison takes one class
 * visit, and successes from failed or interrupted outermost comparisons are not cached.
 *
 * <p>The detached fixtures copy class and intersection wrappers into distinct LHS and RHS graphs,
 * preserving sharing within each graph. Implicit wildcards become explicit extends wildcards
 * holding detached formal bounds: capture conversion must not canonicalize both sides to a
 * compiler-owned bound and accidentally bypass the containment traversal. Sharing concrete Object
 * leaves is intentional. The detached cyclic fixture links only newly allocated wrappers; no
 * compiler-owned types are rewritten. The fresh-bound fixture instead traverses javac's real cyclic
 * bound graph, asserting that computing effective upper bounds produces fresh intersection
 * wrappers. Recheck these representations if javac's wildcard or intersection internals change.
 */
public class CheckIdenticalNullabilityVisitorTests {

  @Test(timeout = 30_000)
  public void sharedBoundsHaveLinearDeterministicComparisonCounts() {
    StringBuilder source = new StringBuilder("interface A0<T> {} interface B0<T> {}\n");
    for (int level = 1; level <= 12; level++) {
      for (String name : new String[] {"A", "B"}) {
        source
            .append("interface ")
            .append(name)
            .append(level)
            .append("<T extends A")
            .append(level - 1)
            .append("<?> & B")
            .append(level - 1)
            .append("<?>> {}\n");
      }
    }
    source.append(
        """
        class Test {
          void depthFour() {
            // BUG: Diagnostic contains: depth4: bounded, repeat=1, deterministic, exceptionRolledBack
            A4<?> depth4;
          }
          void depthEight() {
            // BUG: Diagnostic contains: depth8: bounded, repeat=1, deterministic, exceptionRolledBack
            A8<?> depth8;
          }
          void depthTwelve() {
            // BUG: Diagnostic contains: depth12: bounded, repeat=1, deterministic, exceptionRolledBack, linear
            A12<?> depth12;
          }
        }
        """);
    compile(source.toString());
  }

  @Test(timeout = 30_000)
  public void cycleSuccessBeforeLateMismatchIsNotPublished() {
    compile(
        """
        interface A<X, Y> {}
        interface B<X> {}
        interface Box<X> {}
        class Test {
          void cyclicCase() {
            // BUG: Diagnostic contains: cycleFailure: rejectedRoot, rejectedDependent, rejectedAgain
            Box<A<B<Object>, String>> cycleFailure;
          }
        }
        """);
  }

  @Test
  public void sharedWildcardArgumentsKeepTheirContextualBounds() {
    compile(
        """
        import org.jspecify.annotations.Nullable;
        interface Pair<T extends @Nullable Object, U extends T> {}
        class Test {
          void contextualCase() {
            // BUG: Diagnostic contains: contextual: acceptedNonNull, rejectedNullable, acceptedAgain
            Pair<String, ?> contextual;
          }
        }
        """);
  }

  @Test(timeout = 30_000)
  public void containmentCyclesTerminateWithFreshEffectiveUpperBounds() {
    compile(
        """
        interface A0<T extends A2<?> & B2<?>> {}
        interface B0<T extends B2<?>> {}
        interface A1<T extends A0<?> & B0<?>> {}
        interface B1<T extends A0<?> & B0<?>> {}
        interface A2<T extends A1<?> & B1<?>> {}
        interface B2<T extends A1<?> & B1<?>> {}
        class Test {
          void freshBoundsCycleCase() {
            // BUG: Diagnostic contains: freshBoundsCycle: terminated, freshBounds
            A2<?> freshBoundsCycle;
          }
        }
        """);
  }

  @Test
  public void sharedWildcardArgumentsKeepTheirArgumentPositions() {
    compile(
        """
        import org.jspecify.annotations.NonNull;
        import org.jspecify.annotations.Nullable;
        interface Positions<T extends @NonNull String, U extends @Nullable Object> {}
        class Test {
          void argumentPositionsCase() {
            // BUG: Diagnostic contains: argumentPositions: acceptedFirst, rejectedSecond
            Positions<?, ?> argumentPositions;
          }
        }
        """);
  }

  @Test(timeout = 30_000)
  public void recursiveLowerBoundsIgnoreFreshIndependentCaptures() {
    compile(
        """
        interface C<X extends C<?, ? super X>, Y> {}
        class Test {
          void recursiveLowerBoundTerminates() {
            // BUG: Diagnostic contains: recursiveLowerBound: terminated, freshIndependentCapture, scannedOnce
            C<?, ?> recursiveLowerBound;
          }
          void independentMismatchAfterRecursionRejects() {
            // BUG: Diagnostic contains: recursiveLateMismatch: rejectedAfterRecursion
            C<?, String> recursiveLateMismatch;
          }
        }
        """);
  }

  @Test
  public void sharedWildcardArgumentsKeepTransitiveBoundDependencies() {
    compile(
        """
        import org.jspecify.annotations.Nullable;
        interface Chain<T extends @Nullable Object, U extends T, V extends U> {}
        class Test {
          void transitiveCase() {
            // BUG: Diagnostic contains: transitiveDependency: acceptedNonNull, rejectedNullable, acceptedAgain
            Chain<String, ?, ?> transitiveDependency;
          }
        }
        """);
  }

  @Test
  public void sharedWildcardArgumentsKeepStructurallyNestedBoundDependencies() {
    compile(
        """
        import org.jspecify.annotations.Nullable;
        interface Box<T extends @Nullable Object> {}
        interface Marker {}
        class Outer<T extends @Nullable Object> { class Inner {} }
        interface ClassBound<T extends @Nullable Object, U extends Box<T>> {}
        interface WildcardBound<T extends @Nullable Object, U extends Box<? extends T>> {}
        interface SuperBound<T extends @Nullable Object, U extends Box<? super T>> {}
        interface ArrayBound<T extends @Nullable Object, U extends Box<T[]>> {}
        interface IntersectionBound<T extends @Nullable Object, U extends Marker & Box<T>> {}
        interface EnclosingBound<T extends @Nullable Object, U extends Outer<T>.Inner> {}
        class Test {
          void classArgumentDependency() {
            // BUG: Diagnostic contains: classDependency: acceptedNonNull, rejectedNullable, acceptedAgain
            ClassBound<String, ?> classDependency;
          }
          void extendsWildcardDependency() {
            // BUG: Diagnostic contains: wildcardDependency: acceptedNonNull, rejectedNullable, acceptedAgain
            WildcardBound<String, ?> wildcardDependency;
          }
          void superWildcardDependency() {
            // BUG: Diagnostic contains: superDependency: acceptedNullable, rejectedNonNull, acceptedAgain
            SuperBound<String, ?> superDependency;
          }
          void arrayComponentDependency() {
            // BUG: Diagnostic contains: arrayDependency: acceptedNonNull, rejectedNullable, acceptedAgain
            ArrayBound<String, ?> arrayDependency;
          }
          void laterIntersectionComponentDependency() {
            // BUG: Diagnostic contains: intersectionDependency: acceptedNonNull, rejectedNullable, acceptedAgain
            IntersectionBound<String, ?> intersectionDependency;
          }
          void enclosingClassDependency() {
            // BUG: Diagnostic contains: enclosingDependency: acceptedNonNull, rejectedNullable, acceptedAgain
            EnclosingBound<String, ?> enclosingDependency;
          }
        }
        """);
  }

  @Test
  public void explicitSuperBoundsKeepComparedArgumentIdentities() {
    compile(
        """
        interface Box<T> {}
        class Test {
          void lowerBoundCase() {
            // BUG: Diagnostic contains: explicitSuper: acceptedEqual, rejectedRhsChange, acceptedLhsChange, rejectedAgain
            Box<? super String> explicitSuper;
          }
        }
        """);
  }

  @Test
  public void existingCaptureAliasesKeepTheirFullArgumentContext() {
    compile(
        """
        import org.jspecify.annotations.NonNull;
        import org.jspecify.annotations.Nullable;
        interface Example<Z extends @Nullable Object, X extends Z, Y extends @NonNull Object> {}
        class Test {
          void aliasedCaptureCase() {
            // BUG: Diagnostic contains: captureAlias: acceptedAliased, rejectedDistinct, acceptedAgain
            Example<?, ?, ?> captureAlias;
          }
        }
        """);
  }

  @Test
  public void declarationProjectionHandlesUnreferencedBoundsAndExternalFormals() {
    compile(
        """
        interface Box<T> {}
        interface NonObject<T, U extends Number> {}
        interface PrimitiveArray<T, U extends Box<int[]>> {}
        class Outer<T> { class Inner<U extends T> {} }
        class Test {
          void unreferencedNonObjectBoundIsRetained() {
            // BUG: Diagnostic contains: nonObjectBound: projectionChecked
            NonObject<?, ?> nonObjectBound;
          }
          void primitiveArrayDoesNotRetainUnrelatedFormal() {
            // BUG: Diagnostic contains: primitiveArrayBound: projectionChecked
            PrimitiveArray<?, ?> primitiveArrayBound;
          }
          void enclosingFormalIsNotAnOwnFormal() {
            // BUG: Diagnostic contains: externalFormal: projectionChecked
            Outer<String>.Inner<?> externalFormal;
          }
        }
        """);
  }

  @Test
  public void unsupportedRecoveryBoundsRetainEveryFormal() {
    compile(
        """
        interface Recovery<X, Y> {}
        class Test {
          void unsupportedRecoveryBound() {
            // BUG: Diagnostic contains: recoveryBound: retainedAll
            Recovery<?, ?> recoveryBound;
          }
        }
        """);
  }

  @Test
  public void sharedDeclarationBoundsAreScannedOnce() {
    compile(
        """
        interface Shared<X extends java.util.List<String>, Y extends java.util.List<String>> {}
        class Test {
          void sharedBoundGraphIsVisitedOnce() {
            // BUG: Diagnostic contains: sharedDeclarationBound: scannedOnce
            Shared<?, ?> sharedDeclarationBound;
          }
        }
        """);
  }

  /** Runs only the fixture checker, leaving each observation next to its expected diagnostic. */
  private void compile(String source) {
    CompilationTestHelper.newInstance(ContainmentWorkChecker.class, getClass())
        .addSourceLines("Test.java", source)
        .doTest();
  }

  /** Exercises the real visitor with a comparison budget instead of a wall-clock assertion. */
  @BugPattern(summary = "Checks containment caching and bounded work", severity = SUGGESTION)
  public static final class ContainmentWorkChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {
    private final NullAway nullAway =
        new NullAway(
            ErrorProneFlags.fromMap(
                Map.of(
                    "NullAway:OnlyNullMarked", "true",
                    "NullAway:JSpecifyMode", "true",
                    "NullAway:HandleWildcardGenerics", "true")));
    private final Map<Integer, Integer> comparisonsByDepth = new HashMap<>();

    /**
     * Reports budget exhaustion as a mismatched diagnostic rather than continuing exponential work.
     */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      String name = tree.getName().toString();
      if (!name.equals("depth4")
          && !name.equals("depth8")
          && !name.equals("depth12")
          && !name.equals("cycleFailure")
          && !name.equals("contextual")
          && !name.equals("freshBoundsCycle")
          && !name.equals("argumentPositions")
          && !name.equals("recursiveLowerBound")
          && !name.equals("recursiveLateMismatch")
          && !name.equals("transitiveDependency")
          && !name.equals("classDependency")
          && !name.equals("wildcardDependency")
          && !name.equals("superDependency")
          && !name.equals("arrayDependency")
          && !name.equals("intersectionDependency")
          && !name.equals("enclosingDependency")
          && !name.equals("explicitSuper")
          && !name.equals("captureAlias")
          && !name.equals("nonObjectBound")
          && !name.equals("primitiveArrayBound")
          && !name.equals("externalFormal")
          && !name.equals("recoveryBound")
          && !name.equals("sharedDeclarationBound")) {
        return NO_MATCH;
      }
      String message;
      try {
        Type original = ASTHelpers.getType(tree);
        message =
            switch (name) {
              case "cycleFailure" -> checkCycle(original, state);
              case "contextual" -> checkContextualBounds(original, state);
              case "freshBoundsCycle" -> checkFreshBoundsCycle((Type.ClassType) original, state);
              case "argumentPositions" -> checkArgumentPositions(original, state);
              case "explicitSuper" -> checkExplicitSuper((Type.ClassType) original, state);
              case "captureAlias" -> checkCaptureAlias((Type.ClassType) original, state);
              case "recoveryBound" -> checkRecoveryBound((Type.ClassType) original, state);
              case "sharedDeclarationBound" ->
                  checkSharedDeclarationBound((Type.ClassType) original, state);
              case "nonObjectBound", "primitiveArrayBound" -> {
                assertReferencedTypeParameters((Type.ClassType) original, state, 1);
                yield name + ": projectionChecked";
              }
              case "externalFormal" -> {
                assertReferencedTypeParameters((Type.ClassType) original, state, 0);
                yield name + ": projectionChecked";
              }
              case "recursiveLowerBound" ->
                  checkRecursiveLowerBound((Type.ClassType) original, state);
              case "recursiveLateMismatch" ->
                  checkRecursiveLateMismatch((Type.ClassType) original, state);
              case "transitiveDependency",
                  "classDependency",
                  "wildcardDependency",
                  "superDependency",
                  "arrayDependency",
                  "intersectionDependency",
                  "enclosingDependency" ->
                  checkBoundDependency((Type.ClassType) original, name, state);
              default -> checkDepth(original, Integer.parseInt(name.substring(5)), state);
            };
      } catch (WorkBudgetExceeded exhausted) {
        message = name + ": class-comparison budget exceeded";
      }
      return buildDescription(tree).setMessage(message).build();
    }

    /**
     * Checks linear growth, completed-cache reuse, deterministic counts, and exception rollback.
     */
    private String checkDepth(Type original, int depth, VisitorState state) {
      Type lhs = detach(original, new IdentityHashMap<>());
      Type rhs = detach(original, new IdentityHashMap<>());
      assertThat(lhs).isNotSameInstanceAs(rhs);
      assertThat(((Type.WildcardType) lhs.getTypeArguments().head).type)
          .isNotSameInstanceAs(((Type.WildcardType) rhs.getTypeArguments().head).type);
      int budget = 12 * depth;
      CountingVisitor visitor = new CountingVisitor(state, nullAway, budget);
      assertThat(lhs.accept(visitor, rhs)).isTrue();
      int first = visitor.comparisons;
      assertThat(first).isGreaterThan(depth);
      assertThat(first).isAtMost(budget);

      visitor.reset(budget);
      assertThat(lhs.accept(visitor, rhs)).isTrue();
      assertThat(visitor.comparisons).isEqualTo(1);

      CountingVisitor fresh = new CountingVisitor(state, nullAway, budget);
      assertThat(lhs.accept(fresh, rhs)).isTrue();
      assertThat(fresh.comparisons).isEqualTo(first);

      CountingVisitor interrupted = new CountingVisitor(state, nullAway, 2);
      assertThrows(WorkBudgetExceeded.class, () -> lhs.accept(interrupted, rhs));
      interrupted.reset(budget);
      assertThat(lhs.accept(interrupted, rhs)).isTrue();
      assertThat(interrupted.comparisons).isEqualTo(first);

      comparisonsByDepth.put(depth, first);
      String message = "depth" + depth + ": bounded, repeat=1, deterministic, exceptionRolledBack";
      if (comparisonsByDepth.size() == 3) {
        int four = comparisonsByDepth.get(4);
        int eight = comparisonsByDepth.get(8);
        int twelve = comparisonsByDepth.get(12);
        assertThat(eight).isGreaterThan(four);
        assertThat(twelve - eight).isEqualTo(eight - four);
        message += ", linear";
      }
      return message + ", comparisons=" + first;
    }

    /**
     * Copies the acyclic bound DAG, expanding unbounded wildcards without invoking capture.
     * Distinct source wrappers remain distinct, while repeated references to the same formal bound
     * stay shared.
     */
    private Type detach(Type type, IdentityHashMap<Type, Type> copies) {
      Type existing = copies.get(type);
      if (existing != null) {
        return existing;
      }
      Type copy;
      if (type instanceof Type.IntersectionClassType intersection) {
        ListBuffer<Type> components = new ListBuffer<>();
        for (Type component : intersection.getComponents()) {
          components.append(detach(component, copies));
        }
        copy =
            new Type.IntersectionClassType(
                components.toList(), (Symbol.ClassSymbol) type.tsym, intersection.allInterfaces);
      } else if (type instanceof Type.ClassType clazz && !clazz.getTypeArguments().isEmpty()) {
        ListBuffer<Type> arguments = new ListBuffer<>();
        List<Type> formals = clazz.tsym.type.getTypeArguments();
        for (Type argument : clazz.getTypeArguments()) {
          Type.WildcardType wildcard = (Type.WildcardType) argument;
          assertThat(wildcard.kind).isEqualTo(BoundKind.UNBOUND);
          Type upperBound = ((Type.TypeVar) formals.head).getUpperBound();
          arguments.append(
              new Type.WildcardType(detach(upperBound, copies), BoundKind.EXTENDS, wildcard.tsym));
          formals = formals.tail;
        }
        copy = new Type.ClassType(clazz.getEnclosingType(), arguments.toList(), clazz.tsym);
      } else {
        return type;
      }
      copies.put(type, copy);
      return copy;
    }

    /**
     * Builds Box<? extends A<? extends B<? extends A<...>>, String>> on both sides, with a nullable
     * String only on the RHS. Box and B share the exact wildcard arguments but have distinct
     * containment contexts. Recursing through B revisits A's active first-argument comparison; B's
     * success is therefore provisional. A then fails at its second argument. Retrying B in
     * isolation must reject too, rather than reuse a success from the failed outer traversal.
     */
    private String checkCycle(Type original, VisitorState state) {
      Type.ClassType originalBox = (Type.ClassType) original;
      Type.ClassType originalA = (Type.ClassType) originalBox.getTypeArguments().head;
      Type.ClassType originalB = (Type.ClassType) originalA.getTypeArguments().head;
      Type string = originalA.getTypeArguments().tail.head;
      Type nullableString =
          TypeSubstitutionUtils.typeWithAnnot(
              string, GenericsChecks.getSyntheticNullableAnnotType(state));
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(nullableString)).isTrue();
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(string)).isFalse();

      Type.ClassType lhsA = shell(originalA);
      Type.ClassType rhsA = shell(originalA);
      Type.ClassType lhsB = shell(originalB);
      Type.ClassType rhsB = shell(originalB);
      lhsA.typarams_field = List.of(extendsBound(lhsB, state), string);
      rhsA.typarams_field = List.of(extendsBound(rhsB, state), nullableString);
      Type.WildcardType lhsRoot = extendsBound(lhsA, state);
      Type.WildcardType rhsRoot = extendsBound(rhsA, state);
      lhsB.typarams_field = List.of(lhsRoot);
      rhsB.typarams_field = List.of(rhsRoot);
      Type.ClassType lhsBox = shell(originalBox);
      Type.ClassType rhsBox = shell(originalBox);
      lhsBox.typarams_field = List.of(lhsRoot);
      rhsBox.typarams_field = List.of(rhsRoot);

      CountingVisitor visitor = new CountingVisitor(state, nullAway, 64);
      assertThat(lhsBox.accept(visitor, rhsBox)).isFalse();
      visitor.reset(64);
      assertThat(lhsB.accept(visitor, rhsB)).isFalse();
      visitor.reset(64);
      assertThat(lhsB.accept(visitor, rhsB)).isFalse();
      return "cycleFailure: rejectedRoot, rejectedDependent, rejectedAgain";
    }

    /**
     * Reuses wildcard arguments across contexts whose dependent upper bounds differ in nullness.
     */
    private String checkContextualBounds(Type original, VisitorState state) {
      Type string = original.getTypeArguments().head;
      Type nullableString =
          TypeSubstitutionUtils.typeWithAnnot(
              string, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.WildcardType originalWildcard =
          (Type.WildcardType) original.getTypeArguments().tail.head;
      Type.WildcardType sharedWildcard =
          new Type.WildcardType(
              originalWildcard.type,
              originalWildcard.kind,
              originalWildcard.tsym,
              originalWildcard.bound);
      Type lhs =
          new Type.ClassType(
              original.getEnclosingType(),
              List.of(extendsBound(nullableString, state), extendsBound(string, state)),
              original.tsym);
      Type nonNullRhs =
          new Type.ClassType(
              original.getEnclosingType(), List.of(string, sharedWildcard), original.tsym);
      Type nullableRhs =
          new Type.ClassType(
              original.getEnclosingType(), List.of(nullableString, sharedWildcard), original.tsym);
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 32);
      assertThat(lhs.accept(visitor, nonNullRhs)).isTrue();
      assertThat(lhs.accept(visitor, nullableRhs)).isFalse();
      assertThat(lhs.accept(visitor, nonNullRhs)).isTrue();
      return "contextual: acceptedNonNull, rejectedNullable, acceptedAgain";
    }

    /**
     * Traverses javac's real cyclic bound graph. Computing effective upper bounds allocates fresh
     * intersection wrappers, so bound-identity containment keys would exhaust the class-visit
     * budget.
     */
    private String checkFreshBoundsCycle(Type.ClassType original, VisitorState state) {
      Type firstBound =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
                  original, state, nullAway.getConfig(), nullAway.getHandler())
              .get(0);
      Type secondBound =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
                  original, state, nullAway.getConfig(), nullAway.getHandler())
              .get(0);
      assertThat(firstBound.isIntersection()).isTrue();
      assertThat(secondBound).isNotSameInstanceAs(firstBound);

      CountingVisitor visitor = new CountingVisitor(state, nullAway, 96);
      assertThat(original.accept(visitor, original)).isTrue();
      assertThat(visitor.comparisons).isAtMost(96);
      return "freshBoundsCycle: terminated, freshBounds";
    }

    /**
     * Reuses the same wildcard pair at both argument indices of one parameterization. The first
     * implicit RHS bound is explicitly non-null String and fits the LHS extends String; the second
     * is nullable Object and does not. Explicit bound annotations avoid nullable defaults in this
     * unmarked fixture. Parameterization identities alone cannot distinguish these questions.
     */
    private String checkArgumentPositions(Type original, VisitorState state) {
      Type string = ((Type.TypeVar) original.tsym.type.getTypeArguments().head).getUpperBound();
      Type.WildcardType lhsWildcard = extendsBound(string, state);
      Type.WildcardType rhsWildcard = (Type.WildcardType) original.getTypeArguments().head;
      Type.ClassType lhs =
          new Type.ClassType(
              original.getEnclosingType(), List.of(lhsWildcard, lhsWildcard), original.tsym);
      Type.ClassType rhs =
          new Type.ClassType(
              original.getEnclosingType(), List.of(rhsWildcard, rhsWildcard), original.tsym);
      java.util.List<Type> rhsBounds =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
              rhs, state, nullAway.getConfig(), nullAway.getHandler());
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(rhsBounds.get(0))).isFalse();
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(rhsBounds.get(1))).isTrue();
      Type.ClassType firstOnlyRhs =
          new Type.ClassType(
              original.getEnclosingType(), List.of(rhsWildcard, lhsWildcard), original.tsym);
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 32);
      assertThat(lhs.accept(visitor, firstOnlyRhs)).isTrue();
      assertThat(lhs.accept(visitor, rhs)).isFalse();
      return "argumentPositions: acceptedFirst, rejectedSecond";
    }

    /**
     * Uses javac's real recursive lower bound, whose unrelated second argument is freshly captured
     * on each expansion even though the first wildcard remains identical.
     */
    private String checkRecursiveLowerBound(Type.ClassType original, VisitorState state) {
      Type.ClassType firstBound =
          (Type.ClassType)
              GenericsUtils.effectiveUpperBoundsForTypeArguments(
                      original, state, nullAway.getConfig(), nullAway.getHandler())
                  .get(0);
      Type.ClassType secondBound =
          (Type.ClassType)
              GenericsUtils.effectiveUpperBoundsForTypeArguments(
                      original, state, nullAway.getConfig(), nullAway.getHandler())
                  .get(0);
      assertThat(firstBound.getTypeArguments().head)
          .isSameInstanceAs(secondBound.getTypeArguments().head);
      Type.WildcardType firstSuper = (Type.WildcardType) firstBound.getTypeArguments().tail.head;
      Type.WildcardType secondSuper = (Type.WildcardType) secondBound.getTypeArguments().tail.head;
      assertThat(firstSuper.kind).isEqualTo(BoundKind.SUPER);
      assertThat(firstSuper.type).isInstanceOf(Type.CapturedType.class);
      assertThat(firstSuper.type).isNotSameInstanceAs(secondSuper.type);

      CountingVisitor visitor = new CountingVisitor(state, nullAway, 96);
      assertThat(original.accept(visitor, original)).isTrue();
      assertThat(visitor.comparisons).isGreaterThan(1);
      assertThat(visitor.comparisons).isAtMost(96);
      assertThat(visitor.boundScans).containsKey(original.tsym);
      for (int scans : visitor.boundScans.values()) {
        assertThat(scans).isEqualTo(1);
      }
      visitor.reset(96);
      Type.ClassType freshWrapper = shell(original);
      freshWrapper.typarams_field = original.getTypeArguments();
      assertThat(freshWrapper.accept(visitor, original)).isTrue();
      for (int scans : visitor.boundScans.values()) {
        assertThat(scans).isEqualTo(1);
      }
      return "recursiveLowerBound: terminated, freshIndependentCapture, scannedOnce";
    }

    /**
     * Checks an independent argument after the recursive argument closes through the cycle guard.
     */
    private String checkRecursiveLateMismatch(Type.ClassType original, VisitorState state) {
      Type string = original.getTypeArguments().tail.head;
      Type nullableString =
          TypeSubstitutionUtils.typeWithAnnot(
              string, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.ClassType rhs = shell(original);
      rhs.typarams_field = List.of(original.getTypeArguments().head, nullableString);
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 96);
      assertThat(original.accept(visitor, rhs)).isFalse();
      assertThat(visitor.comparisons).isGreaterThan(1);
      visitor.reset(96);
      assertThat(original.accept(visitor, rhs)).isFalse();
      return "recursiveLateMismatch: rejectedAfterRecursion";
    }

    /**
     * Changes only T on the RHS while sharing every wildcard identity, including the compared last
     * argument. Earlier LHS arguments accept both contexts, so only the last implicit bound can
     * reject. Chain's final bound reaches T through the capture of U; the other fixtures hide T
     * under the named structural bound component. Super wildcards reverse the nullness direction.
     */
    private String checkBoundDependency(Type.ClassType original, String name, VisitorState state) {
      if (name.equals("transitiveDependency")) {
        assertReferencedTypeParameters(original, state, 0, 1, 2);
      } else {
        assertReferencedTypeParameters(original, state, 0, 1);
      }
      Type string = original.getTypeArguments().head;
      Type nullableString =
          TypeSubstitutionUtils.typeWithAnnot(
              string, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.ClassType nullableRhs = shell(original);
      nullableRhs.typarams_field = original.getTypeArguments().tail.prepend(nullableString);
      boolean reverse = name.equals("superDependency");
      Type.ClassType acceptedRhs = reverse ? nullableRhs : original;
      Type.ClassType rejectedRhs = reverse ? original : nullableRhs;
      int last = original.getTypeArguments().size() - 1;
      Type acceptedBound =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
                  acceptedRhs, state, nullAway.getConfig(), nullAway.getHandler())
              .get(last);
      ListBuffer<Type> lhsArguments = new ListBuffer<>();
      Type.WildcardType permissive = extendsBound(nullableString, state);
      for (int i = 0; i < last; i++) {
        lhsArguments.append(permissive);
      }
      lhsArguments.append(extendsBound(acceptedBound, state));
      Type.ClassType lhs = shell(original);
      lhs.typarams_field = lhsArguments.toList();
      assertThat(acceptedRhs.getTypeArguments().get(last))
          .isSameInstanceAs(rejectedRhs.getTypeArguments().get(last));

      CountingVisitor visitor = new CountingVisitor(state, nullAway, 96);
      assertThat(lhs.accept(visitor, acceptedRhs)).isTrue();
      visitor.reset(96);
      assertThat(lhs.accept(visitor, rejectedRhs)).isFalse();
      visitor.reset(96);
      assertThat(lhs.accept(visitor, acceptedRhs)).isTrue();
      return name
          + (reverse
              ? ": acceptedNullable, rejectedNonNull, acceptedAgain"
              : ": acceptedNonNull, rejectedNullable, acceptedAgain");
    }

    /**
     * With no declared bound dependencies, only the compared argument identities distinguish these
     * lower-bound questions. Checks both RHS and LHS changes without altering existing wildcards.
     */
    private String checkExplicitSuper(Type.ClassType original, VisitorState state) {
      Type.WildcardType nonNullSuper = (Type.WildcardType) original.getTypeArguments().head;
      Type nullableString =
          TypeSubstitutionUtils.typeWithAnnot(
              nonNullSuper.type, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.WildcardType nullableSuper =
          new Type.WildcardType(
              nullableString, BoundKind.SUPER, nonNullSuper.tsym, nonNullSuper.bound);
      Type.ClassType nullable = shell(original);
      nullable.typarams_field = List.of(nullableSuper);
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 32);
      assertThat(nullable.accept(visitor, nullable)).isTrue();
      assertThat(nullable.accept(visitor, original)).isFalse();
      assertThat(original.accept(visitor, original)).isTrue();
      assertThat(nullable.accept(visitor, original)).isFalse();
      return "explicitSuper: acceptedEqual, rejectedRhsChange, acceptedLhsChange, rejectedAgain";
    }

    /**
     * Checks declaration projection directly, independently of containment caches and own-argument
     * identity checks. Each call uses a separate visitor so it does not affect scan-count
     * assertions.
     */
    private void assertReferencedTypeParameters(
        Type.ClassType original, VisitorState state, int... indices) {
      BitSet expected = new BitSet();
      for (int index : indices) {
        expected.set(index);
      }
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 96);
      assertThat(visitor.computeReferencedTypeParameters(original)).isEqualTo(expected);
    }

    /**
     * Shares one instrumented upper-bound wrapper between detached formals. Its arguments must be
     * traversed once per declaration scan, not once per incoming edge in the shared bound graph.
     */
    private String checkSharedDeclarationBound(Type.ClassType original, VisitorState state) {
      Symbol.ClassSymbol symbol =
          new Symbol.ClassSymbol(
              original.tsym.flags_field,
              original.tsym.name,
              original.tsym.type,
              original.tsym.owner);
      List<Type> formals = original.tsym.type.getTypeArguments();
      Type.ClassType bound = (Type.ClassType) ((Type.TypeVar) formals.head).getUpperBound();
      int[] argumentReads = {0};
      Type.ClassType sharedBound =
          new Type.ClassType(bound.getEnclosingType(), bound.getTypeArguments(), bound.tsym) {
            // Counts structural traversal without changing the shared wrapper's arguments.
            @Override
            public List<Type> getTypeArguments() {
              argumentReads[0]++;
              return super.getTypeArguments();
            }
          };
      Type.TypeVar first =
          new Type.TypeVar(formals.head.tsym, sharedBound, state.getSymtab().botType);
      Type.TypeVar second =
          new Type.TypeVar(formals.tail.head.tsym, sharedBound, state.getSymtab().botType);
      Type.ClassType detached =
          new Type.ClassType(original.getEnclosingType(), List.of(first, second), symbol);
      symbol.type = detached;
      assertReferencedTypeParameters(detached, state, 0, 1);
      assertThat(argumentReads[0]).isEqualTo(1);
      return "sharedDeclarationBound: scannedOnce";
    }

    /**
     * Gives a detached declaration an unsupported recovery bound. The fallback must retain Y even
     * though only X has a non-Object bound; no compiler-owned symbols or bounds are modified.
     */
    private String checkRecoveryBound(Type.ClassType original, VisitorState state) {
      Symbol.ClassSymbol symbol =
          new Symbol.ClassSymbol(
              original.tsym.flags_field,
              original.tsym.name,
              original.tsym.type,
              original.tsym.owner);
      List<Type> formals = original.tsym.type.getTypeArguments();
      Type.TypeVar first =
          new Type.TypeVar(formals.head.tsym, state.getSymtab().botType, state.getSymtab().botType);
      Type.TypeVar second =
          new Type.TypeVar(
              formals.tail.head.tsym, state.getSymtab().objectType, state.getSymtab().botType);
      Type.ClassType detached =
          new Type.ClassType(original.getEnclosingType(), List.of(first, second), symbol);
      symbol.type = detached;
      assertReferencedTypeParameters(detached, state, 0, 1);
      return "recoveryBound: retainedAll";
    }

    /**
     * Reuses one existing capture at Z and Y. The contextual capture-to-formal map assigns it to Y
     * (the last occurrence), narrowing X's effective bound to non-null Object. Replacing only Y
     * leaves that same capture assigned to nullable Z instead. Neither the compared X arguments nor
     * the declaration's projected arguments change, so direct captures require the full context.
     */
    private String checkCaptureAlias(Type.ClassType original, VisitorState state) {
      assertReferencedTypeParameters(original, state, 0, 1);
      Type object = state.getSymtab().objectType;
      Type nullableObject =
          TypeSubstitutionUtils.typeWithAnnot(
              object, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.WildcardType wildcard = (Type.WildcardType) original.getTypeArguments().head;
      Type.CapturedType capture =
          new Type.CapturedType(
              original.tsym.name,
              original.tsym,
              nullableObject,
              state.getSymtab().botType,
              wildcard);
      Type.CapturedType otherCapture =
          new Type.CapturedType(
              original.tsym.name,
              original.tsym,
              nullableObject,
              state.getSymtab().botType,
              wildcard);
      Type sharedWildcard = original.getTypeArguments().tail.head;
      Type.ClassType aliased = shell(original);
      aliased.typarams_field = List.of(capture, sharedWildcard, capture);
      Type.ClassType distinct = shell(original);
      distinct.typarams_field = List.of(capture, sharedWildcard, otherCapture);
      Type aliasedBound =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
                  aliased, state, nullAway.getConfig(), nullAway.getHandler())
              .get(1);
      Type distinctBound =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
                  distinct, state, nullAway.getConfig(), nullAway.getHandler())
              .get(1);
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(aliasedBound)).isFalse();
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(distinctBound)).isTrue();
      Type.WildcardType permissive = extendsBound(nullableObject, state);
      Type.ClassType lhs = shell(original);
      lhs.typarams_field = List.of(permissive, extendsBound(object, state), permissive);
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 96);
      assertThat(lhs.accept(visitor, aliased)).isTrue();
      visitor.reset(96);
      assertThat(lhs.accept(visitor, distinct)).isFalse();
      visitor.reset(96);
      assertThat(lhs.accept(visitor, aliased)).isTrue();
      return "captureAlias: acceptedAliased, rejectedDistinct, acceptedAgain";
    }

    /** Allocates a detached placeholder so the cyclic fixture can link its own type arguments. */
    private Type.ClassType shell(Type.ClassType original) {
      return new Type.ClassType(original.getEnclosingType(), List.nil(), original.tsym);
    }

    /** Gives the detached graph a direct upper-bound edge without using a declaration formal. */
    private Type.WildcardType extendsBound(Type bound, VisitorState state) {
      return new Type.WildcardType(bound, BoundKind.EXTENDS, state.getSymtab().boundClass);
    }
  }

  /** Counts class visits, aborting unbounded work. */
  private static final class CountingVisitor extends CheckIdenticalNullabilityVisitor {
    private int comparisons;
    private int budget;
    private final IdentityHashMap<Symbol.TypeSymbol, Integer> boundScans = new IdentityHashMap<>();

    CountingVisitor(VisitorState state, NullAway nullAway, int budget) {
      super(state, nullAway.getGenericsChecks(), nullAway.getConfig(), nullAway.getHandler());
      this.budget = budget;
    }

    /** Starts a new observation without clearing the visitor's containment cache. */
    void reset(int budget) {
      comparisons = 0;
      this.budget = budget;
    }

    /** Counts actual declaration-bound scans, not lookups in the visitor's projection cache. */
    @Override
    BitSet computeReferencedTypeParameters(Type.ClassType type) {
      boundScans.merge(type.tsym, 1, Integer::sum);
      return super.computeReferencedTypeParameters(type);
    }

    /** Fails deterministically even when completed containment work is not cached. */
    @Override
    public Boolean visitClassType(Type.ClassType lhs, Type rhs) {
      if (++comparisons > budget) {
        throw new WorkBudgetExceeded();
      }
      return super.visitClassType(lhs, rhs);
    }
  }

  private static final class WorkBudgetExceeded extends RuntimeException {}
}
