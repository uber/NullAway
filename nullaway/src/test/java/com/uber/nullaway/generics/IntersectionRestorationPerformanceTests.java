package com.uber.nullaway.generics;

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
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Bounded-work tests of the public restoration helper, not wall-clock performance assertions.
 *
 * <p>WARNING: These tests depend on javac internals. Only detached type variables, wildcards, and
 * intersection components are rewritten; compiler-owned bounds and symbols are never mutated. Wide
 * components share detached tracking symbols with identity equality and distinct stable hashes.
 * Recheck the fixture representation if javac changes its implicit-wildcard-bound representation or
 * symbol equality.
 */
public class IntersectionRestorationPerformanceTests {
  private static final int LEVELS = 20;
  private static final int WIDTH = 64;

  @Test
  public void sharedImplicitBoundsAreVisitedWithBoundedWork() {
    StringBuilder source = new StringBuilder("interface A0<T> {} interface B0<T> {}\n");
    for (int i = 1; i <= LEVELS; i++) {
      for (String name : new String[] {"A", "B"}) {
        source
            .append("interface ")
            .append(name)
            .append(i)
            .append("<T extends A")
            .append(i - 1)
            .append("<?> & B")
            .append(i - 1)
            .append("<?>> {}\n");
      }
    }
    source.append(
        """
        class Test {
          void unchangedCase() {
            // BUG: Diagnostic contains: unchanged: bounded=true, leafNullable=false, inputsPreserved=true
            A20<?> unchanged;
          }
          void restoredCase() {
            // BUG: Diagnostic contains: restored: bounded=true, leafNullable=true, inputsPreserved=true
            A20<?> restored;
          }
        }
        """);
    compile(source.toString());
  }

  @Test
  public void smallSharedBoundsWithCyclicLeafReuseCompletedWork() {
    compile(
        """
        interface A0<T extends A0<?>> {}
        interface B0<T> {}
        interface A1<T extends A0<?> & B0<?>> {}
        interface B1<T extends A0<?> & B0<?>> {}
        interface A2<T extends A1<?> & B1<?>> {}
        class Test {
          // BUG: Diagnostic contains: cyclicShared: visitedOnce=true, leafNullable=true, inputsPreserved=true
          A2<?> cyclicShared;
        }
        """);
  }

  @Test
  public void mutualCycleRestorationMatchesIsolatedEntryPoints() {
    compile(
        """
        interface A<T extends B<?>> {}
        interface B<T extends A<?>> {}
        interface Root<T extends A<?> & B<?>> {}
        class Test {
          // BUG: Diagnostic contains: cycleContexts: matchesIsolated=true, restored=true, inputsPreserved=true
          Root<?> cycleContexts;
        }
        """);
  }

  @Test
  public void wideReorderedIntersectionsMatchComponentsBySymbol() {
    StringBuilder source = new StringBuilder("import org.jspecify.annotations.*;\n");
    for (int i = 0; i < WIDTH; i++) {
      source.append("interface C").append(i).append("<T> {}\n");
    }
    source.append("class Test<S extends ");
    for (int i = 0; i < WIDTH; i++) {
      if (i != 0) {
        source.append(" & ");
      }
      source.append("C").append(i).append(i % 2 == 0 ? "<@Nullable String>" : "<@NonNull String>");
    }
    source.append(", T extends ");
    for (int i = WIDTH - 1; i >= 0; i--) {
      if (i != WIDTH - 1) {
        source.append(" & ");
      }
      source.append("C").append(i).append(i % 2 == 0 ? "<@NonNull String>" : "<@Nullable String>");
    }
    source.append(
        """
        > {
          S source;
          // BUG: Diagnostic contains: wide: bounded=true, matched=64, inputsPreserved=true
          T wide;
        }
        """);
    compile(source.toString());
  }

  /** Keeps every observation next to an expected fixture diagnostic. */
  private void compile(String source) {
    CompilationTestHelper.newInstance(RestorationWorkChecker.class, getClass())
        .addSourceLines("Test.java", source)
        .doTest();
  }

  /** Exercises real restoration with attributed symbols and a deterministic bound-read budget. */
  @BugPattern(summary = "Checks bounded intersection restoration work", severity = SUGGESTION)
  public static final class RestorationWorkChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {
    private final Config config =
        new NullAway(
                ErrorProneFlags.fromMap(
                    Map.of(
                        "NullAway:OnlyNullMarked",
                        "true",
                        "NullAway:JSpecifyMode",
                        "true",
                        "NullAway:HandleWildcardGenerics",
                        "true")))
            .getConfig();
    private int remaining;
    private int remainingSymbolComparisons;
    private final IdentityHashMap<Type, Integer> visits = new IdentityHashMap<>();

    /** Reports bounded work and restoration observations at each explicitly marked fixture. */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      String name = tree.getName().toString();
      if (!name.equals("unchanged")
          && !name.equals("restored")
          && !name.equals("wide")
          && !name.equals("cyclicShared")
          && !name.equals("cycleContexts")) {
        return NO_MATCH;
      }
      String message;
      try {
        // There are 2 * LEVELS + 1 reachable formal bounds, not 2^LEVELS independent paths.
        remaining = 32 * (2 * LEVELS + 1);
        visits.clear();
        if (name.equals("wide")) {
          message = checkWide(tree);
        } else if (name.equals("cycleContexts")) {
          message = checkCycleContexts(tree, state);
        } else {
          Type original = ASTHelpers.getType(tree);
          boolean annotate = name.equals("restored") || name.equals("cyclicShared");
          Type source = detach(original, new IdentityHashMap<>(), annotate, state);
          Type target = detach(original, new IdentityHashMap<>(), false, state);
          Type result =
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(source, target, config);
          boolean leafNullable = GenericsUtils.isAnnotatedAsNullable(leaf(result), config);
          boolean preserved =
              !GenericsUtils.isAnnotatedAsNullable(leaf(target), config)
                  && GenericsUtils.isAnnotatedAsNullable(leaf(source), config) == annotate
                  && !GenericsUtils.isAnnotatedAsNullable(leaf(original), config);
          if (name.equals("cyclicShared")) {
            boolean visitedOnce =
                !visits.isEmpty() && visits.values().stream().allMatch(count -> count == 1);
            message =
                name
                    + ": visitedOnce="
                    + visitedOnce
                    + ", leafNullable="
                    + leafNullable
                    + ", inputsPreserved="
                    + preserved;
          } else {
            message =
                name
                    + ": bounded=true, leafNullable="
                    + leafNullable
                    + ", inputsPreserved="
                    + preserved;
          }
        }
      } catch (WorkBudgetExceeded exhausted) {
        message =
            name
                + (name.equals("wide")
                    ? ": symbol-comparison budget exceeded"
                    : ": bound-read budget exceeded");
      }
      return buildDescription(tree).setMessage(message).build();
    }

    /**
     * Copies the fixture DAG, preserving shared bounds without delegating mutable state to javac.
     */
    private Type detach(
        Type type, IdentityHashMap<Type, Type> copies, boolean annotate, VisitorState state) {
      Type existing = copies.get(type);
      if (existing != null) {
        return existing;
      }
      if (type instanceof Type.TypeVar variable) {
        Type.TypeVar copy =
            new Type.TypeVar(variable.tsym, variable.getUpperBound(), variable.lower) {
              // Stop exponential re-entry before it can hang the test.
              @Override
              public Type getUpperBound() {
                if (--remaining < 0) {
                  throw new WorkBudgetExceeded();
                }
                return super.getUpperBound();
              }
            };
        copies.put(type, copy);
        Type upper = detach(variable.getUpperBound(), copies, annotate, state);
        if (annotate && !(upper instanceof Type.IntersectionClassType)) {
          upper =
              TypeSubstitutionUtils.typeWithAnnot(
                  upper, GenericsChecks.getSyntheticNullableAnnotType(state));
        }
        copy.setUpperBound(upper);
        return copy;
      }
      Type copy;
      if (type instanceof Type.IntersectionClassType intersection) {
        ListBuffer<Type> components = new ListBuffer<>();
        for (Type component : intersection.getComponents()) {
          components.append(detach(component, copies, annotate, state));
        }
        copy =
            new Type.IntersectionClassType(
                components.toList(), (Symbol.ClassSymbol) type.tsym, intersection.allInterfaces) {
              @Override
              public <R, S> R accept(Type.Visitor<R, S> visitor, S parameter) {
                visits.merge(this, 1, Integer::sum);
                return super.accept(visitor, parameter);
              }
            };
      } else if (type instanceof Type.ClassType clazz && !clazz.getTypeArguments().isEmpty()) {
        ListBuffer<Type> arguments = new ListBuffer<>();
        var formals = clazz.tsym.type.getTypeArguments();
        for (Type argument : clazz.getTypeArguments()) {
          Type.WildcardType wildcard = (Type.WildcardType) argument;
          Type.TypeVar formal =
              wildcard.bound == null ? (Type.TypeVar) formals.head : wildcard.bound;
          arguments.append(
              new Type.WildcardType(
                  wildcard.type,
                  wildcard.kind,
                  wildcard.tsym,
                  (Type.TypeVar) detach(formal, copies, annotate, state)));
          formals = formals.tail;
        }
        copy =
            new Type.ClassType(clazz.getEnclosingType(), arguments.toList(), clazz.tsym) {
              @Override
              public <R, S> R accept(Type.Visitor<R, S> visitor, S parameter) {
                visits.merge(this, 1, Integer::sum);
                return super.accept(visitor, parameter);
              }
            };
      } else {
        return type;
      }
      copies.put(type, copy);
      return copy;
    }

    /**
     * Checks that shared traversal of a two-node cycle agrees with entering each node in isolation.
     * Reusing a truncated result under a different active cycle entry changes the third bound.
     */
    private String checkCycleContexts(VariableTree tree, VisitorState state) {
      Type original = ASTHelpers.getType(tree);
      Type source = detach(original, new IdentityHashMap<>(), true, state);
      Type target = detach(original, new IdentityHashMap<>(), false, state);
      Type sourceBound = implicitBound(source);
      Type targetBound = implicitBound(target);
      Type sourceA = intersectionComponent(sourceBound, "A");
      Type sourceB = intersectionComponent(sourceBound, "B");
      Type targetA = intersectionComponent(targetBound, "A");
      Type targetB = intersectionComponent(targetBound, "B");
      String sourceBefore = boundNullness(sourceA) + boundNullness(sourceB);
      String targetBefore = boundNullness(targetA) + boundNullness(targetB);
      Type shared =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(source, target, config);
      Type sharedBound = implicitBound(shared);
      String sharedA = boundNullness(intersectionComponent(sharedBound, "A"));
      String sharedB = boundNullness(intersectionComponent(sharedBound, "B"));
      Type isolatedA =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(sourceA, targetA, config);
      Type isolatedB =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(sourceB, targetB, config);
      return "cycleContexts: matchesIsolated="
          + (sharedA.equals(boundNullness(isolatedA)) && sharedB.equals(boundNullness(isolatedB)))
          + ", restored="
          + (sharedA.startsWith("11") && sharedB.startsWith("11"))
          + ", inputsPreserved="
          + (sourceBefore.equals(boundNullness(sourceA) + boundNullness(sourceB))
              && targetBefore.equals(boundNullness(targetA) + boundNullness(targetB)));
    }

    /** Records only three top-level nullness observations, never recursively expands a cycle. */
    private String boundNullness(Type type) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < 3; i++) {
        type = implicitBound(type);
        result.append(GenericsUtils.isAnnotatedAsNullable(type, config) ? '1' : '0');
      }
      return result.toString();
    }

    /** Reads the implicit bound of the fixture's only wildcard argument. */
    private static Type implicitBound(Type type) {
      Type.WildcardType wildcard = (Type.WildcardType) type.getTypeArguments().head;
      Type.TypeVar formal =
          wildcard.bound == null
              ? (Type.TypeVar) type.tsym.type.getTypeArguments().head
              : wildcard.bound;
      return formal.getUpperBound();
    }

    /** Selects a named component without depending on javac's intersection ordering. */
    private static Type intersectionComponent(Type type, String name) {
      for (Type component : ((Type.IntersectionClassType) type).getExplicitComponents()) {
        if (component.tsym.getSimpleName().contentEquals(name)) {
          return component;
        }
      }
      throw new AssertionError("Missing component: " + name);
    }

    /** Follows one shared branch to a non-intersection bound, without expanding the DAG. */
    private static Type leaf(Type type) {
      Type.WildcardType wildcard = (Type.WildcardType) type.getTypeArguments().head;
      Type.TypeVar formal =
          wildcard.bound == null
              ? (Type.TypeVar) type.tsym.type.getTypeArguments().head
              : wildcard.bound;
      Type upper = formal.getUpperBound();
      return upper instanceof Type.IntersectionClassType intersection
          ? leaf(intersection.getExplicitComponents().head)
          : upper;
    }

    /**
     * Checks all reordered argument annotations and verifies both detached inputs remain intact.
     */
    private String checkWide(VariableTree tree) {
      Type.TypeVar target = (Type.TypeVar) ASTHelpers.getType(tree);
      Type.TypeVar source = (Type.TypeVar) target.tsym.owner.type.getTypeArguments().head;
      Type.IntersectionClassType sourceBound = (Type.IntersectionClassType) source.getUpperBound();
      Type.IntersectionClassType targetBound = (Type.IntersectionClassType) target.getUpperBound();
      // A repeated linear search needs at least WIDTH * (WIDTH + 1) / 2 comparisons (2080).
      remainingSymbolComparisons = 8 * WIDTH;
      IdentityHashMap<Symbol.TypeSymbol, Symbol.ClassSymbol> symbols = new IdentityHashMap<>();
      Type sourceCopy = detachWide(sourceBound, symbols);
      Type targetCopy = detachWide(targetBound, symbols);
      String sourceBefore = sourceCopy.toString();
      String targetBefore = targetCopy.toString();
      Type.IntersectionClassType result =
          (Type.IntersectionClassType)
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                  sourceCopy, targetCopy, config);
      int matched = 0;
      for (Type component : result.getExplicitComponents()) {
        int index = Integer.parseInt(component.tsym.getSimpleName().toString().substring(1));
        Type argument = component.getTypeArguments().head;
        if (index % 2 == 0
            ? GenericsUtils.isAnnotatedAsNullable(argument, config)
            : GenericsUtils.isAnnotatedAsNonNull(argument, config)) {
          matched++;
        }
      }
      return "wide: bounded=true, matched="
          + matched
          + ", inputsPreserved="
          + (sourceBefore.equals(sourceCopy.toString())
              && targetBefore.equals(targetCopy.toString()));
    }

    /**
     * Copies wide components with shared identity-equal tracking symbols and attributed arguments.
     * The symbols retain the original declared type for javac's raw-type and metadata operations.
     */
    private Type detachWide(
        Type.IntersectionClassType bound,
        IdentityHashMap<Symbol.TypeSymbol, Symbol.ClassSymbol> symbols) {
      ListBuffer<Type> components = new ListBuffer<>();
      for (Type component : bound.getComponents()) {
        int hash = symbols.size();
        Symbol.ClassSymbol tracking =
            symbols.computeIfAbsent(
                component.tsym,
                original ->
                    new Symbol.ClassSymbol(
                        original.flags_field, original.name, original.type, original.owner) {
                      @Override
                      @SuppressWarnings("ReferenceEquality") // Preserve Symbol's identity equality.
                      public boolean equals(Object other) {
                        if (--remainingSymbolComparisons < 0) {
                          throw new WorkBudgetExceeded();
                        }
                        return this == other;
                      }

                      @Override
                      public int hashCode() {
                        return hash;
                      }
                    });
        components.append(
            new Type.ClassType(
                component.getEnclosingType(), component.getTypeArguments(), tracking));
      }
      return new Type.IntersectionClassType(
          components.toList(), (Symbol.ClassSymbol) bound.tsym, bound.allInterfaces);
    }
  }

  /** Stops excessive restoration work promptly, converting it into a normal diagnostic mismatch. */
  private static final class WorkBudgetExceeded extends RuntimeException {}
}
