package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeVariable;

/** JSpecify-style nullability constraint solver for NullAway. */
public final class ConstraintSolverImpl implements ConstraintSolver {
  private final Config config; // for nullability annotations
  private final CodeAnnotationInfo codeAnnotationInfo;
  private final Handler handler;

  public ConstraintSolverImpl(Config config, VisitorState state, NullAway analysis) {
    this.config = config;
    this.codeAnnotationInfo = CodeAnnotationInfo.instance(state.context);
    this.handler = analysis.getHandler();
  }

  /* ───────────────────── internal enums & data ───────────────────── */

  private enum NullnessState {
    UNKNOWN,
    NONNULL,
    NULLABLE
  }

  /** Per-variable state (nullability, sub-/supertype edges). */
  private static final class VarState {
    final boolean nullableAllowed; // upper-bound annotated @Nullable ?
    NullnessState nullness = NullnessState.UNKNOWN;
    final Set<TypeVariable> supertypes = new HashSet<>();
    final Set<TypeVariable> subtypes = new HashSet<>();

    VarState(boolean nullableAllowed) {
      this.nullableAllowed = nullableAllowed;
    }
  }

  /* All variables seen so far. */
  private final Map<TypeVariable, VarState> vars = new HashMap<>();

  /* ───────────────────── public API ───────────────────── */

  @Override
  public void addSubtypeConstraint(Type subtype, Type supertype) throws UnsatConstraintsException {
    addSubtypeInternal(subtype, supertype, new HashSet<>()); // avoid cycles
  }

  @Override
  public Map<TypeVariable, Boolean> solve() throws UnsatConstraintsException {
    /* ---------- work-list propagation of nullability ---------- */
    Deque<TypeVariable> work = new ArrayDeque<>();
    vars.forEach(
        (tv, st) -> {
          if (st.nullness != NullnessState.UNKNOWN) {
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
            if (updateNullness(sub, NullnessState.NONNULL)) {
              work.add(sub);
            }
          }
          break;

        case NULLABLE:
          /* tv <: T  &  tv NULLABLE  ⇒  T NULLABLE */
          for (TypeVariable sup : st.supertypes) {
            if (updateNullness(sup, NullnessState.NULLABLE)) {
              work.add(sup);
            }
          }
          break;

        default: // UNKNOWN
      }
    }

    /* ---------- build final solution map ---------- */
    Map<TypeVariable, Boolean> result = new HashMap<>();
    vars.forEach(
        (tv, st) -> {
          NullnessState n =
              (st.nullness == NullnessState.UNKNOWN) ? NullnessState.NONNULL : st.nullness;
          result.put(tv, n == NullnessState.NULLABLE);
        });
    return result;
  }

  /* ───────────────────── core recursive routine ───────────────────── */

  private void addSubtypeInternal(Type s, Type t, Set<Pair<Type, Type>> seen)
      throws UnsatConstraintsException {
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

    /* 3️⃣  invariance of type arguments – recurse both directions */
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
  private boolean updateNullness(TypeVariable tv, NullnessState n)
      throws UnsatConstraintsException {
    VarState st = getState(tv);

    if (st.nullness == n) {
      return false;
    }
    if (st.nullness != NullnessState.UNKNOWN) {
      throw new UnsatConstraintsException(
          "Contradictory nullability for " + tv + ": " + st.nullness + " vs. " + n);
    }
    if (n == NullnessState.NULLABLE && !st.nullableAllowed) {
      throw new UnsatConstraintsException(tv + " cannot be @Nullable (upper bound is @NonNull)");
    }
    st.nullness = n;
    return true;
  }

  private void requireNullable(Type t) throws UnsatConstraintsException {
    if (isTypeVariable(t)) {
      updateNullness((TypeVariable) t, NullnessState.NULLABLE);
    } else if (isKnownNonNull(t)) {
      throw new UnsatConstraintsException("Cannot treat @NonNull type as @Nullable: " + t);
    }
  }

  private void requireNonNull(Type t) throws UnsatConstraintsException {
    if (isTypeVariable(t)) {
      updateNullness((TypeVariable) t, NullnessState.NONNULL);
    } else if (isKnownNullable(t)) {
      throw new UnsatConstraintsException("Cannot treat @Nullable type as @NonNull: " + t);
    }
  }

  /* ───────────────────── helpers & stubs ───────────────────── */

  private VarState getState(TypeVariable tv) {
    return vars.computeIfAbsent(tv, v -> new VarState(upperBoundIsNullable(v)));
  }

  private static boolean isTypeVariable(Type t) {
    return t instanceof TypeVar;
  }

  /** Replace with NullAway logic to detect a direct @Nullable annotation. */
  private boolean isKnownNullable(Type t) {
    if (t instanceof NullType) {
      return true;
    }
    return Nullness.hasNullableAnnotation(t.getAnnotationMirrors().stream(), config);
  }

  /** Everything non-nullable *and* non-variable counts as @NonNull. */
  private boolean isKnownNonNull(Type t) {
    return !isKnownNullable(t) && !isTypeVariable(t);
  }

  /** Replace with NullAway logic to check if the type variable’s upper bound is @Nullable. */
  private boolean upperBoundIsNullable(TypeVariable tv) {

    if (fromUnannotatedMethod(tv)) {
      return true;
    }
    Type upperBound = (Type) tv.getUpperBound();
    com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
        upperBound.getAnnotationMirrors();
    return com.uber.nullaway.Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
  }

  private boolean fromUnannotatedMethod(TypeVariable tv) {
    Symbol enclosingElement = (Symbol) tv.asElement().getEnclosingElement();
    return codeAnnotationInfo.isSymbolUnannotated(enclosingElement, config, handler);
  }

  /** True if both declared types erase to the same class/interface symbol. */
  private static boolean sameErasure(Type a, Type b) {
    return a instanceof ClassType
        && b instanceof ClassType
        && ((ClassType) a).tsym.equals(((ClassType) b).tsym);
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
