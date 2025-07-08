/*
 * Copyright (c) 2025 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Symbol.TypeVariableSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A two-point-lattice constraint solver for JSpecify nullability.
 *
 * <p>The solver is <strong>nullability-only</strong>: the syntax-level generator feeds it subtype
 * constraints of the form {@code S <: T}. Each constraint may mention type variables of the
 * enclosing generic method; those variables are the <em>unknowns</em> solved for here.
 *
 * <p>Lattice: {@code @NonNull ⊑ @Nullable}. The result is the <em>least-nullable</em> assignment
 * that satisfies all constraints, or an {@link UnsatException} if none exists.
 */
public final class NullabilitySolver {

  /* ------ Enums and exceptions ------ */

  /** The three run-time states of a variable during solving. */
  private enum Nullab {
    NONNULL,
    NULLABLE,
    UNKNOWN
  }

  /** Thrown when the constraints are mutually inconsistent. */
  public static final class UnsatException extends RuntimeException {
    UnsatException(String who) {
      super("Nullability constraints are unsatisfiable for type variable " + who);
    }
  }

  /* ------ Public API ------ */

  /**
   * @param types The shared {@link Types} instance for the javac context that is invoking the
   *     solver. Used only for erasure/equality helpers.
   */
  public NullabilitySolver(Types types) {
    this.types = types;
    // create the two lattice constants
    constNonnull = new VarInfo("<NONNULL-const>", /* mayBeNullable= */ false);
    constNonnull.value = Nullab.NONNULL;
    constNullable = new VarInfo("<NULLABLE-const>", /* mayBeNullable= */ true);
    constNullable.value = Nullab.NULLABLE;
  }

  /**
   * Add one subtyping constraint {@code S <: T}. <em>Generation-time</em> recursion across type
   * structure is handled here; clients just call this once per syntactic constraint.
   */
  public void addSubtypeConstraint(Type S, Type T) {

    // 1. Variable <: Variable  ――― record edge
    if (isTypeVar(S) && isTypeVar(T)) {
      addEdge(info(S), info(T)); // v_S ⩽ v_T
      return;
    }

    // 2. Right-hand side definitely NONNULL ⇒ left must be NONNULL
    if (isDefinitelyNonnull(T)) {
      if (isTypeVar(S)) {
        addEdge(info(S), constNonnull); // v_S ⩽ NONNULL
      }
      // else S has no variables → nothing to track
      return;
    }

    // 3. Left-hand side definitely NULLABLE ⇒ right must be NULLABLE
    if (isDefinitelyNullable(S)) {
      if (isTypeVar(T)) {
        addEdge(constNullable, info(T)); // NULLABLE ⩽ v_T
      }
      return;
    }

    // 4. Same erasure ⇒ recurse over invariant type arguments (equality)
    if (sameErasure(S, T)) {
      // TODO may need to do asSuper here
      List<Type> sArgs = S.getTypeArguments();
      List<Type> tArgs = T.getTypeArguments();
      int n = Math.min(sArgs.size(), tArgs.size());
      for (int i = 0; i < n; i++) {
        addEquality(sArgs.get(i), tArgs.get(i));
      }
      // arrays: invariant element type
    } else if (S.hasTag(TypeTag.ARRAY) && T.hasTag(TypeTag.ARRAY)) {
      addEquality(((ArrayType) S).elemtype, ((ArrayType) T).elemtype);
    }
    // any other structure differences are ignored – they cannot mention the method's vars
  }

  /**
   * Solve all accumulated constraints.
   *
   * @return a map {@code TypeVariableSymbol → Boolean} where {@code true} means the variable is
   *     {@code @Nullable}, {@code false} means {@code @NonNull}.
   * @throws UnsatException if no consistent assignment exists
   */
  public Map<TypeVariableSymbol, Boolean> solve() {
    /* 0. seed work-queue with the lattice constants and upper-bound restrictions */
    Deque<VarInfo> work = new ArrayDeque<>();
    for (VarInfo v : varMap.values()) {
      if (!v.mayBeNullable) {
        enqueueIfStronger(v, Nullab.NONNULL, work);
      }
    }
    work.add(constNonnull);
    work.add(constNullable);

    /* 1. fixed-point propagation */
    while (!work.isEmpty()) {
      VarInfo v = work.removeFirst();
      switch (v.value) {
        case NONNULL:
          for (VarInfo w : v.up) enqueueIfStronger(w, Nullab.NONNULL, work);
          break;
        case NULLABLE:
          for (VarInfo w : v.down) enqueueIfStronger(w, Nullab.NULLABLE, work);
          break;
        default:
          break; // UNKNOWN never sits in the queue
      }
    }

    /* 2. choose ⊥ for remaining unknowns (least-nullable solution) */
    Map<TypeVariableSymbol, Boolean> out = new HashMap<>();
    for (VarInfo v : varMap.values()) {
      if (v.value == Nullab.UNKNOWN) v.value = Nullab.NONNULL;
      out.put(v.sym, v.value == Nullab.NULLABLE);
    }
    return out;
  }

  /* ------ Internal representation ------ */

  private final Types types;

  /** per-variable record */
  private static final class VarInfo {
    final @Nullable TypeVariableSymbol sym; // real symbol or null for the two constants
    final String debugName; // for helpful errors
    final boolean mayBeNullable; // upper-bound permission
    Nullab value = Nullab.UNKNOWN;

    final List<VarInfo> up = new ArrayList<>(); // this ⩽ x
    final List<VarInfo> down = new ArrayList<>(); // x ⩽ this

    VarInfo(TypeVariableSymbol sym, boolean mayBeNullable) {
      this.sym = sym;
      this.debugName = sym.getSimpleName().toString();
      this.mayBeNullable = mayBeNullable;
    }

    // constructor for the lattice constants
    VarInfo(String name, boolean mayBeNullable) {
      this.sym = null;
      this.debugName = name;
      this.mayBeNullable = mayBeNullable;
    }

    @Override
    public String toString() {
      return debugName;
    }
  }

  /* constants */
  private final VarInfo constNonnull;
  private final VarInfo constNullable;

  /* lookup table – identity semantics are vital for javac symbols */
  private final Map<TypeVariableSymbol, VarInfo> varMap = new IdentityHashMap<>();

  /* ------ Graph-building helpers ------ */

  private void addEquality(Type a, Type b) {
    addSubtypeConstraint(a, b);
    addSubtypeConstraint(b, a);
  }

  private void addEdge(VarInfo sub, VarInfo sup) {
    if (sub == sup) return; // avoid trivial self-loops
    sub.up.add(sup);
    sup.down.add(sub);
  }

  private VarInfo info(Type t) {
    TypeVariableSymbol sym = (TypeVariableSymbol) ((TypeVar) t).tsym;
    return varMap.computeIfAbsent(
        sym,
        s -> {
          boolean ubAllowsNullable = upperBoundAllowsNullable(((TypeVar) t).getUpperBound());
          return new VarInfo(s, ubAllowsNullable);
        });
  }

  /* ------  Propagation helpers  ------ */

  private void enqueueIfStronger(VarInfo v, Nullab newVal, Deque<VarInfo> work) {
    switch (v.value) {
      case UNKNOWN:
        v.value = newVal;
        work.add(v);
        return;
      case NONNULL:
        if (newVal == Nullab.NULLABLE) conflict(v);
        return;
      case NULLABLE:
        if (newVal == Nullab.NONNULL) conflict(v);
        return;
    }
  }

  private static void conflict(VarInfo v) {
    throw new UnsatException(v.debugName);
  }

  /* ------  Type-inspection helpers  ------
   *
   * These are <strong>intentionally thin wrappers</strong> so that you can swap them
   * out for whatever NullAway/NullnessUtil offers in your build.
   */

  private boolean isTypeVar(Type t) {
    return t.hasTag(TypeTag.TYPEVAR);
  }

  private boolean isDefinitelyNullable(Type t) {
    // Direct annotation check only – solver runs on already‐annotated types.
    return NullnessAnnotationUtil.hasNullableAnnotation(t);
  }

  private boolean isDefinitelyNonnull(Type t) {
    return NullnessAnnotationUtil.hasNonnullAnnotation(t);
  }

  private boolean upperBoundAllowsNullable(Type ub) {
    // If any upper bound annotation is @Nullable, allow nullable. Otherwise, no.
    return NullnessAnnotationUtil.hasNullableAnnotation(ub);
  }

  private boolean sameErasure(Type s, Type t) {
    return types.isSameType(types.erasure(s), types.erasure(t));
  }

  /* ------  Minimal annotation helper stubs  ------ */

  /**
   * Replace these with calls into NullAway’s NullnessUtil or your own annotation accessor. They
   * treat only direct (top-level) annotations; that is exactly what JSpecify mandates for subtyping
   * in this two-point lattice.
   */
  private static final class NullnessAnnotationUtil {
    private static boolean hasNullableAnnotation(Type t) {
      return hasAnnotation(t, "org.jspecify.annotations.Nullable");
    }

    private static boolean hasNonnullAnnotation(Type t) {
      return !hasNullableAnnotation(t); // JSpecify has exactly one annotation at present
    }

    private static boolean hasAnnotation(Type t, String fqName) {
      return t.getAnnotationMirrors().stream()
          .anyMatch(m -> m.getAnnotationType().toString().equals(fqName));
    }
  }
}
