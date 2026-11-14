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
import com.uber.nullaway.generics.CheckIdenticalNullabilityVisitor.ParameterizationKey;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Checks that comparisons grow linearly with bound depth, a repeated comparison takes one class
 * visit, and successes from failed or interrupted outermost comparisons are not cached. Effective
 * upper bounds are computed once per parameterized type, including across recreated class-type
 * wrappers.
 *
 * <p>The detached fixtures copy class and intersection wrappers into distinct LHS and RHS graphs,
 * preserving sharing within each graph. Implicit wildcards become explicit extends wildcards
 * holding detached formal bounds: capture conversion must not canonicalize both sides to a
 * compiler-owned bound and accidentally bypass the containment traversal. Sharing concrete Object
 * leaves is intentional. The detached cyclic fixture links only newly allocated wrappers; no
 * compiler-owned types are rewritten. The fixture without an effective-upper-bounds cache instead
 * traverses javac's real cyclic bound graph, asserting that computing effective upper bounds
 * produces fresh intersection wrappers. Recheck these representations if javac's wildcard or
 * intersection internals change.
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
  public void effectiveUpperBoundsAreComputedOncePerParameterization() {
    StringBuilder source = new StringBuilder();
    for (int arity : new int[] {4, 16, 64}) {
      source.append("interface Arity").append(arity).append('<');
      for (int index = 0; index < arity; index++) {
        if (index > 0) {
          source.append(", ");
        }
        source.append('T').append(index);
      }
      source.append("> {}\n");
    }
    source.append("class Test {\n");
    for (int arity : new int[] {4, 16, 64}) {
      source
          .append("void arity")
          .append(arity)
          .append("() {\n")
          .append("// BUG: Diagnostic contains: arity")
          .append(arity)
          .append(": computedOnce, repeatsUnchanged, recreatedWrappers, distinctParameterization\n")
          .append("Arity")
          .append(arity)
          .append('<');
      for (int index = 0; index < arity; index++) {
        if (index > 0) {
          source.append(", ");
        }
        source.append('?');
      }
      source.append("> arity").append(arity).append(";\n}\n");
    }
    source.append("}\n");
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
  public void containmentCyclesTerminateWithoutEffectiveUpperBoundsCache() {
    compile(
        """
        interface A0<T extends A2<?> & B2<?>> {}
        interface B0<T extends B2<?>> {}
        interface A1<T extends A0<?> & B0<?>> {}
        interface B1<T extends A0<?> & B0<?>> {}
        interface A2<T extends A1<?> & B1<?>> {}
        interface B2<T extends A1<?> & B1<?>> {}
        class Test {
          void uncachedCycleCase() {
            // BUG: Diagnostic contains: uncachedCycle: terminated, freshBounds, pendingRevisited
            A2<?> uncachedCycle;
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
          && !name.equals("uncachedCycle")
          && !name.equals("argumentPositions")
          && !name.equals("arity4")
          && !name.equals("arity16")
          && !name.equals("arity64")) {
        return NO_MATCH;
      }
      String message;
      try {
        Type original = ASTHelpers.getType(tree);
        message =
            switch (name) {
              case "cycleFailure" -> checkCycle(original, state);
              case "contextual" -> checkContextualBounds(original, state);
              case "uncachedCycle" -> checkUncachedCycle((Type.ClassType) original, state);
              case "argumentPositions" -> checkArgumentPositions(original, state);
              case "arity4", "arity16", "arity64" ->
                  checkEffectiveUpperBounds((Type.ClassType) original, state);
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
     * Counts actual effective-upper-bound computations as arity and repeated comparisons grow.
     * Recreated wrappers keep argument identities, while replacing one wildcard creates exactly one
     * new parameterization and requires exactly one additional computation.
     */
    private String checkEffectiveUpperBounds(Type.ClassType original, VisitorState state) {
      CountingVisitor visitor =
          new CountingVisitor(state, nullAway, 4 * original.getTypeArguments().size() + 4);
      compareRecreatedTypes(original, visitor);
      ParameterizationKey originalKey = new ParameterizationKey(original);
      assertThat(visitor.computedBounds.get(originalKey)).isEqualTo(1);
      Map<ParameterizationKey, Integer> expected = new HashMap<>(visitor.computedBounds);
      for (int repeat = 0; repeat < 32; repeat++) {
        compareRecreatedTypes(original, visitor);
        assertThat(visitor.computedBounds).containsExactlyEntriesIn(expected);
      }

      Type.WildcardType wildcard = (Type.WildcardType) original.getTypeArguments().head;
      Type.WildcardType replacement =
          new Type.WildcardType(wildcard.type, wildcard.kind, wildcard.tsym, wildcard.bound);
      assertThat(replacement).isNotSameInstanceAs(wildcard);
      Type.ClassType different =
          new Type.ClassType(
              original.getEnclosingType(),
              original.getTypeArguments().tail.prepend(replacement),
              original.tsym);
      ParameterizationKey differentKey = new ParameterizationKey(different);
      assertThat(differentKey).isNotEqualTo(originalKey);
      expected.put(differentKey, 1);
      compareRecreatedTypes(different, visitor);
      assertThat(visitor.computedBounds).containsExactlyEntriesIn(expected);
      for (int repeat = 0; repeat < 32; repeat++) {
        compareRecreatedTypes(original, visitor);
        compareRecreatedTypes(different, visitor);
        assertThat(visitor.computedBounds).containsExactlyEntriesIn(expected);
      }
      return "arity"
          + original.getTypeArguments().size()
          + ": computedOnce, repeatsUnchanged, recreatedWrappers, distinctParameterization";
    }

    /**
     * Compares new class-type wrappers sharing the original parameterization's argument objects.
     */
    private void compareRecreatedTypes(Type.ClassType original, CountingVisitor visitor) {
      Type.ClassType lhs =
          new Type.ClassType(
              original.getEnclosingType(), original.getTypeArguments(), original.tsym);
      Type.ClassType rhs =
          new Type.ClassType(
              original.getEnclosingType(), original.getTypeArguments(), original.tsym);
      assertThat(lhs).isNotSameInstanceAs(original);
      assertThat(rhs).isNotSameInstanceAs(original);
      assertThat(lhs).isNotSameInstanceAs(rhs);
      assertThat(lhs.getTypeArguments().head).isSameInstanceAs(rhs.getTypeArguments().head);
      visitor.reset(4 * original.getTypeArguments().size() + 4);
      assertThat(lhs.accept(visitor, rhs)).isTrue();
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
     * Traverses javac's real cyclic bound graph without retaining computed effective upper bounds.
     * Computing bounds allocates fresh intersection wrappers, so bound-identity containment keys
     * would exhaust the class-visit budget. Every parameterized type in this fixture has one
     * wildcard argument. Computing one parameterization more than twice during a single root
     * comparison therefore proves a pending question was revisited: the initial visit computes both
     * sides, and no completed result is published until the outermost comparison returns.
     */
    private String checkUncachedCycle(Type.ClassType original, VisitorState state) {
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
      visitor.cacheEffectiveUpperBounds = false;
      assertThat(original.accept(visitor, original)).isTrue();
      assertThat(visitor.comparisons).isAtMost(96);
      assertThat(
              visitor.computedBounds.entrySet().stream()
                  .anyMatch(
                      entry -> entry.getKey().arguments().size() == 1 && entry.getValue() > 2))
          .isTrue();
      return "uncachedCycle: terminated, freshBounds, pendingRevisited";
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

    /** Allocates a detached placeholder so the cyclic fixture can link its own type arguments. */
    private Type.ClassType shell(Type.ClassType original) {
      return new Type.ClassType(original.getEnclosingType(), List.nil(), original.tsym);
    }

    /** Gives the detached graph a direct upper-bound edge without using a declaration formal. */
    private Type.WildcardType extendsBound(Type bound, VisitorState state) {
      return new Type.WildcardType(bound, BoundKind.EXTENDS, state.getSymtab().boundClass);
    }
  }

  /** Counts class visits and actual effective-upper-bound computations, aborting unbounded work. */
  private static final class CountingVisitor extends CheckIdenticalNullabilityVisitor {
    private int comparisons;
    private int budget;
    private boolean cacheEffectiveUpperBounds = true;
    private final Map<ParameterizationKey, Integer> computedBounds = new HashMap<>();

    CountingVisitor(VisitorState state, NullAway nullAway, int budget) {
      super(state, nullAway.getGenericsChecks(), nullAway.getConfig(), nullAway.getHandler());
      this.budget = budget;
    }

    /**
     * Starts a new observation without clearing the visitor's containment or upper-bound caches.
     */
    void reset(int budget) {
      comparisons = 0;
      this.budget = budget;
    }

    /** Bypasses only the effective-upper-bounds cache for the cyclic-bound regression. */
    @Override
    java.util.List<Type> effectiveUpperBounds(Type.ClassType type, ParameterizationKey key) {
      return cacheEffectiveUpperBounds
          ? super.effectiveUpperBounds(type, key)
          : computeEffectiveUpperBounds(type);
    }

    /** Records actual computations, not cache lookups, by parameterization. */
    @Override
    java.util.List<Type> computeEffectiveUpperBounds(Type.ClassType type) {
      computedBounds.merge(new ParameterizationKey(type), 1, Integer::sum);
      return super.computeEffectiveUpperBounds(type);
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
