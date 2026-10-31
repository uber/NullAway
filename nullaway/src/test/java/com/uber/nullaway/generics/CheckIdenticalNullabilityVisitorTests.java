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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Deterministic containment-work and transaction-safety tests using attributed javac symbols.
 *
 * <p>The fixtures copy class and intersection wrappers into distinct LHS and RHS graphs, preserving
 * sharing within each graph. Implicit wildcards become explicit extends wildcards holding detached
 * formal bounds: capture conversion must not canonicalize both sides back to a compiler-owned bound
 * and accidentally bypass the containment traversal. Sharing concrete Object leaves is intentional.
 * The cyclic fixture links only newly allocated wrappers; no compiler-owned types are rewritten.
 * Recheck these representations if javac's wildcard or intersection internals change.
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
          && !name.equals("contextual")) {
        return NO_MATCH;
      }
      String message;
      try {
        Type original = ASTHelpers.getType(tree);
        message =
            switch (name) {
              case "cycleFailure" -> checkCycle(original, state);
              case "contextual" -> checkContextualBounds(original, state);
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
     * String only on the RHS. Box and B share the exact wildcard arguments, so B's provisional
     * success depends on Box's active containment comparison. A then fails at its second argument.
     * A one-argument probe retries A's first containment question in isolation, so a stale cached
     * success cannot be hidden by rechecking A's second argument.
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

      Type.ClassType lhsProbe = shell(originalB);
      Type.ClassType rhsProbe = shell(originalB);
      lhsProbe.typarams_field = List.of(lhsA.getTypeArguments().head);
      rhsProbe.typarams_field = List.of(rhsA.getTypeArguments().head);
      CountingVisitor visitor = new CountingVisitor(state, nullAway, 64);
      assertThat(lhsBox.accept(visitor, rhsBox)).isFalse();
      visitor.reset(64);
      assertThat(lhsProbe.accept(visitor, rhsProbe)).isFalse();
      visitor.reset(64);
      assertThat(lhsProbe.accept(visitor, rhsProbe)).isFalse();
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

    /** Allocates a detached placeholder so the cyclic fixture can link its own type arguments. */
    private Type.ClassType shell(Type.ClassType original) {
      return new Type.ClassType(original.getEnclosingType(), List.nil(), original.tsym);
    }

    /** Gives the detached graph a direct upper-bound edge without using a declaration formal. */
    private Type.WildcardType extendsBound(Type bound, VisitorState state) {
      return new Type.WildcardType(bound, BoundKind.EXTENDS, state.getSymtab().boundClass);
    }
  }

  /**
   * Counts all class visits, including intersections, and aborts before unbounded recursive work.
   */
  private static final class CountingVisitor extends CheckIdenticalNullabilityVisitor {
    private int comparisons;
    private int budget;

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
