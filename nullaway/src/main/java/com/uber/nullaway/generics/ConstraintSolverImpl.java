package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Types;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.type.TypeVariable;

/** JSpecify-style nullability constraint solver for NullAway. */
@SuppressWarnings("UnusedVariable")
public final class ConstraintSolverImpl implements ConstraintSolver {
  public ConstraintSolverImpl(Types types) {}

  /* ───────────────────── internal enums & data ───────────────────── */

  private enum Nullness {
    UNKNOWN,
    NONNULL,
    NULLABLE
  }

  /** Per-variable state (nullability, sub-/supertype edges, base type). */
  private static final class VarState {
    final boolean nullableAllowed; // upper-bound annotated @Nullable ?
    Nullness nullness = Nullness.UNKNOWN;
    final Set<TypeVariable> supertypes = new HashSet<>();
    final Set<TypeVariable> subtypes = new HashSet<>();
    Type baseType; // structural type to return

    VarState(boolean nullableAllowed, Type baseType) {
      this.nullableAllowed = nullableAllowed;
      this.baseType = baseType;
    }
  }

  /* All variables seen so far. */
  private final Map<TypeVariable, VarState> vars = new HashMap<>();

  /* ───────────────────── public API ───────────────────── */

  @Override
  public void addSubtypeConstraint(Type subtype, Type supertype) {
    addSubtypeInternal(subtype, supertype, new HashSet<>()); // avoid cycles
  }

  @Override
  public Map<TypeVariable, Type> solve() {
    /* ---------- work-list propagation of nullability ---------- */
    Deque<TypeVariable> work = new ArrayDeque<>();
    vars.forEach(
        (tv, st) -> {
          if (st.nullness != Nullness.UNKNOWN) {
            work.add(tv);
          }
        });

    while (!work.isEmpty()) {
      TypeVariable tv = work.removeFirst();
      VarState st = vars.get(tv);

      switch (st.nullness) {
        case NONNULL:
          /* S <: tv  &  tv NONNULL  ⇒  S NONNULL */
          for (TypeVariable sub : st.subtypes) {
            if (updateNullness(sub, Nullness.NONNULL)) {
              work.add(sub);
            }
          }
          break;

        case NULLABLE:
          /* tv <: T  &  tv NULLABLE  ⇒  T NULLABLE */
          for (TypeVariable sup : st.supertypes) {
            if (updateNullness(sup, Nullness.NULLABLE)) {
              work.add(sup);
            }
          }
          break;

        default: // UNKNOWN
      }
    }

    /* ---------- build final solution map ---------- */
    Map<TypeVariable, Type> result = new HashMap<>();
    vars.forEach(
        (tv, st) -> {
          Nullness n = (st.nullness == Nullness.UNKNOWN) ? Nullness.NONNULL : st.nullness;
          Type base = (st.baseType != null) ? st.baseType : defaultUpperBound(tv);
          result.put(tv, n == Nullness.NULLABLE ? makeNullable(base) : base);
        });
    return result;
  }

  /* ───────────────────── core recursive routine ───────────────────── */

  private void addSubtypeInternal(Type s, Type t, Set<Pair<Type, Type>> seen) {
    Pair<Type, Type> p = Pair.of(s, t);
    if (!seen.add(p)) {
      return; // already processed
    }

    /* 1️⃣  variable-to-variable edge */
    if (isTypeVariable(s) && isTypeVariable(t)) {
      TypeVariable sv = (TypeVariable) s;
      TypeVariable tv = (TypeVariable) t;
      getState(sv).supertypes.add(tv);
      getState(tv).subtypes.add(sv);
    }

    /* 2️⃣  top-level nullability rules */
    if (isKnownNonNull(t)) {
      requireNonNull(s);
    }
    if (isKnownNullable(s)) {
      requireNullable(t);
    }

    /* 3️⃣  remember base-type when variable meets concrete type */
    if (isTypeVariable(s) && !isTypeVariable(t)) {
      rememberBaseType((TypeVariable) s, t);
    } else if (!isTypeVariable(s) && isTypeVariable(t)) {
      rememberBaseType((TypeVariable) t, s);
    }

    /* 4️⃣  invariance of type arguments – recurse both directions */
    if (sameErasure(s, t)) {
      List<Type> sArgs = s.getTypeArguments();
      List<Type> tArgs = t.getTypeArguments();
      for (int i = 0; i < sArgs.size(); i++) {
        addSubtypeInternal(sArgs.get(i), tArgs.get(i), seen);
        addSubtypeInternal(tArgs.get(i), sArgs.get(i), seen);
      }
    }
  }

  /* ───────────────────── nullability bookkeeping ───────────────────── */

  /** Force {@code tv} to {@code n}. Returns true if state changed. */
  private boolean updateNullness(TypeVariable tv, Nullness n) {
    VarState st = getState(tv);

    if (st.nullness == n) {
      return false;
    }
    if (st.nullness != Nullness.UNKNOWN) {
      throw new IllegalStateException(
          "Contradictory nullability for " + tv + ": " + st.nullness + " vs. " + n);
    }
    if (n == Nullness.NULLABLE && !st.nullableAllowed) {
      throw new IllegalStateException(tv + " cannot be @Nullable (upper bound is @NonNull)");
    }
    st.nullness = n;
    return true;
  }

  private void requireNullable(Type t) {
    if (isTypeVariable(t)) {
      updateNullness((TypeVariable) t, Nullness.NULLABLE);
    } else if (isKnownNonNull(t)) {
      throw new IllegalStateException("Cannot treat @NonNull type as @Nullable: " + t);
    }
  }

  private void requireNonNull(Type t) {
    if (isTypeVariable(t)) {
      updateNullness((TypeVariable) t, Nullness.NONNULL);
    } else if (isKnownNullable(t)) {
      throw new IllegalStateException("Cannot treat @Nullable type as @NonNull: " + t);
    }
  }

  /* ───────────────────── helpers & stubs ───────────────────── */

  private VarState getState(TypeVariable tv) {
    return vars.computeIfAbsent(
        tv, v -> new VarState(upperBoundIsNullable(v), /* baseType= */ null));
  }

  private void rememberBaseType(TypeVariable tv, Type candidate) {
    VarState st = getState(tv);
    if (st.baseType == null) {
      st.baseType = stripNullability(candidate);
    }
  }

  private static boolean isTypeVariable(Type t) {
    return t instanceof TypeVar;
  }

  /** Replace with NullAway logic to detect a direct @Nullable annotation. */
  private static boolean isKnownNullable(Type t) {
    // TODO implement
    return false;
  }

  /** Everything non-nullable *and* non-variable counts as @NonNull. */
  private static boolean isKnownNonNull(Type t) {
    return !isKnownNullable(t) && !isTypeVariable(t);
  }

  /** Replace with NullAway logic to check if the type variable’s upper bound is @Nullable. */
  private static boolean upperBoundIsNullable(TypeVariable tv) {
    // TODO implement
    return false;
  }

  /** True if both declared types erase to the same class/interface symbol. */
  private static boolean sameErasure(Type a, Type b) {
    return a instanceof ClassType
        && b instanceof ClassType
        && ((ClassType) a).tsym.equals(((ClassType) b).tsym);
  }

  /** Remove any top-level nullability annotation so we can re-apply later. */
  private static Type stripNullability(Type t) {
    // TODO implement
    return t;
  }

  /** Fallback base type = upper bound of the variable. */
  private static Type defaultUpperBound(TypeVariable tv) {
    return ((TypeVar) tv).getUpperBound();
  }

  /** Produce a copy of {@code t} with a @Nullable annotation. */
  private static Type makeNullable(Type t) {
    // TODO implement
    throw new UnsupportedOperationException("makeNullable stub");
  }

  /* ───────────────────── tiny immutable pair helper ───────────────────── */

  private static final class Pair<A, B> {
    final A first;
    final B second;

    private Pair(A first, B second) {
      this.first = first;
      this.second = second;
    }

    static <A, B> Pair<A, B> of(A a, B b) {
      return new Pair<>(a, b);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Pair)) {
        return false;
      }
      Pair<?, ?> p = (Pair<?, ?>) o;
      return Objects.equals(first, p.first) && Objects.equals(second, p.second);
    }

    @Override
    public int hashCode() {
      return Objects.hash(first, second);
    }
  }
}
