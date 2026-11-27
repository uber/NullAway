package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

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
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Checks bounded containment work, cache reuse, and cleanup after failures or interruptions.
 *
 * <p>Detached fixtures give each side separate copies of bound types so NullAway's {@code
 * GenericsChecks.identicalTypeParameterNullability} cannot skip traversal via its {@code lhsType ==
 * rhsType} shortcut. Cyclic fixtures modify only these copies; the fresh-bound fixtures use javac's
 * real captured bounds.
 */
public class CheckIdenticalNullabilityVisitorTests {

  @Test(timeout = 30_000)
  public void sharedBoundsHaveLinearDeterministicComparisonCounts() {
    compile(
        boundDeclarations(12)
            + """
        class Test {
          void depthFour() {
            // BUG: Diagnostic contains: depth4: first=34, repeat=1, fresh=34, afterInterrupt=34, accepted=true/true/true/true, interrupted=true
            A4<?> depth4;
          }
          void depthEight() {
            // BUG: Diagnostic contains: depth8: first=82, repeat=1, fresh=82, afterInterrupt=82, accepted=true/true/true/true, interrupted=true
            A8<?> depth8;
          }
          void depthTwelve() {
            // BUG: Diagnostic contains: depth12: first=130, repeat=1, fresh=130, afterInterrupt=130, accepted=true/true/true/true, interrupted=true
            A12<?> depth12;
          }
        }
        """);
  }

  @Test(timeout = 30_000)
  public void assertionFailuresBecomeDiagnosticsAndDoNotStopLaterFixtures() {
    compile(
        boundDeclarations(4)
            + """
        class Test {
          void assertionFailureCase() {
            // BUG: Diagnostic contains: assertionFailure: expected fixture assertion
            Object assertionFailure;
          }
          void subsequentFixtureCase() {
            // BUG: Diagnostic contains: depth4: first=34, repeat=1, fresh=34, afterInterrupt=34, accepted=true/true/true/true, interrupted=true
            A4<?> depth4;
          }
        }
        """);
  }

  @Test(timeout = 30_000)
  public void siblingWildcardArgumentsReuseSuccessfulComparisonsInOneAccept() {
    compile(
        boundDeclarations(8)
            + """
        interface Pair<X, Y> {}
        class Test {
          void siblingArgumentsCase() {
            // BUG: Diagnostic contains: siblings: comparisons=84, accepted=true
            Pair<A8<?>, A8<?>> siblings;
          }
        }
        """);
  }

  @Test(timeout = 30_000)
  public void cycleSuccessBeforeLateMismatchIsNotCached() {
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
          void cycleWithDifferentRhsArguments() {
            // BUG: Diagnostic contains: distinctRhsCycle: rejectedRoot, rejectedDependent, rejectedAgain
            Box<A<B<Object>, String>> distinctRhsCycle;
          }
          void differentRhsBoundNullness() {
            // BUG: Diagnostic contains: differentRhsBounds: accepted=false, acceptedAgain=false
            Box<Box<Object>> differentRhsBounds;
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
  public void recursiveLowerBoundsTerminateWithFreshIndependentCaptures() {
    compile(
        """
        interface C<X extends C<?, ? super X>, Y> {}
        class Test {
          void recursiveLowerBoundTerminates() {
            // BUG: Diagnostic contains: recursiveLowerBound: terminated, freshIndependentCapture
            C<?, ?> recursiveLowerBound;
          }
          void independentMismatchAfterRecursionRejects() {
            // BUG: Diagnostic contains: recursiveLateMismatch: rejectedAfterRecursion
            C<?, String> recursiveLateMismatch;
          }
        }
        """);
  }

  /** Gives each level two paths to the same lower-level bounds. */
  private String boundDeclarations(int depth) {
    StringBuilder source = new StringBuilder("interface A0<T> {} interface B0<T> {}\n");
    for (int level = 1; level <= depth; level++) {
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
    return source.toString();
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

    /**
     * Reports budget exhaustion and assertion failures as diagnostics without hiding observations
     * from the other fixtures. Assertion diagnostics include the fixture name and failure message.
     */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      String name = tree.getName().toString();
      if (!name.equals("depth4")
          && !name.equals("depth8")
          && !name.equals("depth12")
          && !name.equals("siblings")
          && !name.equals("cycleFailure")
          && !name.equals("distinctRhsCycle")
          && !name.equals("differentRhsBounds")
          && !name.equals("contextual")
          && !name.equals("freshBoundsCycle")
          && !name.equals("argumentPositions")
          && !name.equals("recursiveLowerBound")
          && !name.equals("recursiveLateMismatch")
          && !name.equals("assertionFailure")) {
        return NO_MATCH;
      }
      String message;
      try {
        Type original = ASTHelpers.getType(tree);
        message =
            switch (name) {
              case "assertionFailure" -> throw new AssertionError("expected fixture assertion");
              case "siblings" -> checkSiblings((Type.ClassType) original, state);
              case "cycleFailure", "distinctRhsCycle" -> checkCycle(original, state, name);
              case "differentRhsBounds" ->
                  checkDifferentRhsBounds((Type.ClassType) original, state);
              case "contextual" -> checkContextualBounds(original, state);
              case "freshBoundsCycle" -> checkFreshBoundsCycle((Type.ClassType) original, state);
              case "argumentPositions" -> checkArgumentPositions(original, state);
              case "recursiveLowerBound" ->
                  checkRecursiveLowerBound((Type.ClassType) original, state);
              case "recursiveLateMismatch" ->
                  checkRecursiveLateMismatch((Type.ClassType) original, state);
              default -> checkDepth(original, Integer.parseInt(name.substring(5)), state);
            };
      } catch (WorkBudgetExceeded exhausted) {
        message = name + ": budgetExhausted";
      } catch (AssertionError failed) {
        message = name + ": " + failed.getMessage();
      }
      return buildDescription(tree).setMessage(message).build();
    }

    /** Returns all counts so a regression does not stop checking the remaining depths. */
    @SuppressWarnings("MissingFail") // The diagnostic checks whether the exception was thrown.
    private String checkDepth(Type original, int depth, VisitorState state) {
      Type lhs = detach(original, new IdentityHashMap<>());
      Type rhs = detach(original, new IdentityHashMap<>());
      assertThat(lhs).isNotSameInstanceAs(rhs);
      assertThat(((Type.WildcardType) lhs.getTypeArguments().head).type)
          .isNotSameInstanceAs(((Type.WildcardType) rhs.getTypeArguments().head).type);
      int budget = 100_000;
      CountingVisitor visitor = new CountingVisitor(state, nullAway, budget);
      boolean firstAccepted = lhs.accept(visitor, rhs);
      int first = visitor.comparisons;

      visitor.reset(budget);
      boolean repeatAccepted = lhs.accept(visitor, rhs);
      int repeat = visitor.comparisons;

      CountingVisitor fresh = new CountingVisitor(state, nullAway, budget);
      boolean freshAccepted = lhs.accept(fresh, rhs);

      CountingVisitor interrupted = new CountingVisitor(state, nullAway, 2);
      boolean interruptedAsExpected = false;
      try {
        lhs.accept(interrupted, rhs);
      } catch (WorkBudgetExceeded expected) {
        interruptedAsExpected = true;
      }
      interrupted.reset(budget);
      boolean afterInterruptAccepted = lhs.accept(interrupted, rhs);

      return "depth"
          + depth
          + ": first="
          + first
          + ", repeat="
          + repeat
          + ", fresh="
          + fresh.comparisons
          + ", afterInterrupt="
          + interrupted.comparisons
          + ", accepted="
          + firstAccepted
          + "/"
          + repeatAccepted
          + "/"
          + freshAccepted
          + "/"
          + afterInterruptAccepted
          + ", interrupted="
          + interruptedAsExpected;
    }

    /**
     * Makes the second sibling revisit a successful deep comparison during the same root accept.
     */
    private String checkSiblings(Type.ClassType original, VisitorState state) {
      Type bound = original.getTypeArguments().head;
      Type lhsBound = detach(bound, new IdentityHashMap<>());
      Type rhsBound = detach(bound, new IdentityHashMap<>());
      assertThat(lhsBound).isNotSameInstanceAs(rhsBound);
      Type.ClassType lhs = shell(original);
      Type.ClassType rhs = shell(original);
      lhs.typarams_field = List.of(extendsBound(lhsBound, state), extendsBound(lhsBound, state));
      rhs.typarams_field = List.of(extendsBound(rhsBound, state), extendsBound(rhsBound, state));
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 100_000);
      boolean accepted = lhs.accept(visitor, rhs);
      return "siblings: comparisons=" + visitor.comparisons + ", accepted=" + accepted;
    }

    /**
     * Copies shared bounds without capture conversion, which could canonicalize both sides to the
     * same compiler-owned bound. Concrete leaves can stay shared.
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
     * Lets B succeed through a cycle before A fails at its nullable String argument. Retrying B
     * alone must not reuse that earlier success. A second case checks one LHS wildcard against
     * different RHS arguments. Only detached copies are linked into the cycle.
     */
    private String checkCycle(Type original, VisitorState state, String name) {
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
      rhsB.typarams_field =
          List.of(name.equals("distinctRhsCycle") ? extendsBound(rhsA, state) : rhsRoot);
      Type.ClassType lhsBox = shell(originalBox);
      Type.ClassType rhsBox = shell(originalBox);
      lhsBox.typarams_field = List.of(lhsRoot);
      rhsBox.typarams_field = List.of(rhsRoot);

      CountingVisitor visitor = new CountingVisitor(state, nullAway, 64);
      assertWithMessage("rejectedRoot").that(lhsBox.accept(visitor, rhsBox)).isFalse();
      visitor.reset(64);
      assertWithMessage("rejectedDependent").that(lhsB.accept(visitor, rhsB)).isFalse();
      visitor.reset(64);
      assertWithMessage("rejectedAgain").that(lhsB.accept(visitor, rhsB)).isFalse();
      return name + ": rejectedRoot, rejectedDependent, rejectedAgain";
    }

    /** An active LHS wildcard must still check a different RHS wildcard's nullable bound. */
    private String checkDifferentRhsBounds(Type.ClassType original, VisitorState state) {
      Type.ClassType originalInner = (Type.ClassType) original.getTypeArguments().head;
      Type.ClassType lhsBox = shell(original);
      Type.WildcardType lhsWildcard = extendsBound(lhsBox, state);
      lhsBox.typarams_field = List.of(lhsWildcard);

      Type.ClassType nullableBox = shell(originalInner);
      nullableBox.typarams_field = originalInner.getTypeArguments();
      Type nullableBound =
          TypeSubstitutionUtils.typeWithAnnot(
              nullableBox, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.ClassType rhsInner = shell(originalInner);
      rhsInner.typarams_field = List.of(extendsBound(nullableBound, state));
      Type.ClassType rhsRoot = shell(original);
      rhsRoot.typarams_field = List.of(extendsBound(rhsInner, state));
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(lhsBox)).isFalse();
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(rhsInner)).isFalse();
      assertThat(nullAway.getGenericsChecks().isNullableAnnotated(nullableBound)).isTrue();

      CountingVisitor visitor = new CountingVisitor(state, nullAway, 64);
      boolean accepted = lhsBox.accept(visitor, rhsRoot);
      visitor.reset(64);
      boolean acceptedAgain = lhsBox.accept(visitor, rhsRoot);
      return "differentRhsBounds: accepted=" + accepted + ", acceptedAgain=" + acceptedAgain;
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
      assertWithMessage("acceptedNonNull").that(lhs.accept(visitor, nonNullRhs)).isTrue();
      assertWithMessage("rejectedNullable").that(lhs.accept(visitor, nullableRhs)).isFalse();
      assertWithMessage("acceptedAgain").that(lhs.accept(visitor, nonNullRhs)).isTrue();
      return "contextual: acceptedNonNull, rejectedNullable, acceptedAgain";
    }

    /**
     * Exercises a real cycle whose captured effective upper bounds are fresh intersection types.
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
      assertWithMessage("terminated").that(original.accept(visitor, original)).isTrue();
      assertThat(visitor.comparisons).isAtMost(96);
      return "freshBoundsCycle: terminated, freshBounds";
    }

    /**
     * Uses the same wildcard pair at positions with different bounds. Explicit annotations avoid
     * nullable defaults in this unmarked fixture.
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
      assertWithMessage("acceptedFirst").that(lhs.accept(visitor, firstOnlyRhs)).isTrue();
      assertWithMessage("rejectedSecond").that(lhs.accept(visitor, rhs)).isFalse();
      return "argumentPositions: acceptedFirst, rejectedSecond";
    }

    /** Checks termination when a recursive lower bound keeps creating an unrelated capture. */
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
      assertWithMessage("terminated").that(original.accept(visitor, original)).isTrue();
      assertThat(visitor.comparisons).isGreaterThan(1);
      visitor.reset(96);
      Type.ClassType freshCopy = shell(original);
      freshCopy.typarams_field = original.getTypeArguments();
      assertWithMessage("freshIndependentCapture")
          .that(freshCopy.accept(visitor, original))
          .isTrue();
      return "recursiveLowerBound: terminated, freshIndependentCapture";
    }

    /** Checks that closing a recursive argument does not skip a later nullable mismatch. */
    private String checkRecursiveLateMismatch(Type.ClassType original, VisitorState state) {
      Type string = original.getTypeArguments().tail.head;
      Type nullableString =
          TypeSubstitutionUtils.typeWithAnnot(
              string, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.ClassType rhs = shell(original);
      rhs.typarams_field = List.of(original.getTypeArguments().head, nullableString);
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 96);
      assertWithMessage("rejectedAfterRecursion").that(original.accept(visitor, rhs)).isFalse();
      assertThat(visitor.comparisons).isGreaterThan(1);
      visitor.reset(96);
      assertWithMessage("rejectedAgain").that(original.accept(visitor, rhs)).isFalse();
      return "recursiveLateMismatch: rejectedAfterRecursion";
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

    CountingVisitor(VisitorState state, NullAway nullAway, int budget) {
      super(
          state,
          nullAway.getGenericsChecks(),
          nullAway.getConfig(),
          nullAway.getHandler(),
          ComparisonContext.NONE);
      this.budget = budget;
    }

    /** Starts a new observation without clearing the visitor's containment cache. */
    void reset(int budget) {
      comparisons = 0;
      this.budget = budget;
    }

    /** Fails deterministically even when successful containment work is not cached. */
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
